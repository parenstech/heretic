(ns heretic.core
  "Entry point for Heretic mutation testing tool.

   Heretic provides:
   1. Test-to-code coverage mapping via ClojureStorm instrumentation
   2. Targeted mutation testing - only run relevant tests per mutation
   3. Mutation score reporting

   Main entry points:
   - `collect!` - Build coverage map from test suite
   - `mutate!` - Run mutation testing
   - `status` - Check staleness of coverage data
   - `clean!` - Remove coverage data

   Configuration is loaded from heretic.edn in the project root.

   Architecture:
   - core.clj: Entry point, CLI interface, side-effectful execution
   - controller.clj: Pure orchestration functions (mutation prep, result aggregation)
   - worker.clj: Missionary-based execution with supervision (alternative executor)

   Executor selection:
   - :executor :legacy - Uses Java ExecutorService (default, backwards compatible)
   - :executor :missionary - Uses Missionary-based worker supervision with reliable timeout
   - :executor :process - Forked worker JVM(s) (B3): the ONLY platform-correct fix
     for an uninterruptible infinite-loop mutant (kill the OS process). Honors
     :parallel-workers = N (N>1 = the per-worker-copy pool, full filesystem
     isolation). :executor :process IS its own isolation (per-worker copies) and
     must NOT be nested inside heretic.sandbox/mutate-in-sandbox! — it is a direct
     core path (see run-mutations-process)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [heretic.collector :as collector]
            [heretic.controller :as controller]
            [heretic.coverage-map :as coverage]
            [heretic.mutation-engine :as engine]
            [heretic.persistence :as persist]
            [heretic.reloader :as reloader]
            [heretic.reporter :as reporter]
            [heretic.runner :as runner]
            [heretic.worker :as worker]
            [missionary.core :as m])
  (:import [java.util.concurrent Executors ExecutorService]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-config
  "Default configuration values"
  {:source-paths ["src"]
   :test-paths ["test"]
   :test-namespaces :all
   :exclude-test-namespaces #{} ; namespaces to skip from collection/running
   :heretic-dir ".heretic"
   :instrument-prefixes []
   :instrument-skip-prefixes []
   :exclude-files []
   :parallel-collect false
   ;; Operator selection - use :preset OR :operators (operators takes precedence)
   ;; :preset can be :fast, :standard, or :comprehensive
   ;; :operators is a sequence of operator definitions or ids
   :preset :standard
   :skip-forms #{'comment}
   ;; Timeout configuration
   :timeout-ms 5000           ; Per-test timeout in milliseconds
   :mutation-timeout-ms 30000 ; Per-mutation timeout (Missionary executor)
   :budget-ms nil             ; Optional total time budget per mutation (nil = unlimited)
   ;; Parallel mutation testing
   :parallel-mutate false     ; Enable file-level parallelism
   :parallel-workers nil      ; Number of worker threads (nil = CPU count)
   ;; Equivalent mutant detection
   :filter-equivalent true    ; Filter likely equivalent mutants
   ;; Report output
   :report-format :terminal
   :output-path "target/heretic-report"
   ;; Executor selection
   :executor :legacy          ; :legacy (ExecutorService) or :missionary (Missionary workers)
   :supervision-policy :skip  ; :skip, :retry, or :abort (Missionary executor only)
   })

(defn resolve-operators
  "Resolve operators from config.

   Priority:
   1. If :operators is specified in config, use those
   2. If :preset is specified, use operators for that preset
   3. Default to :standard preset

   Arguments:
   - config: Configuration map
   - override-operators: Optional operators to use (takes highest priority)

   Returns sequence of operator definitions.

   Delegates to controller/resolve-operators."
  [config & {:keys [operators]}]
  (controller/resolve-operators config :operators operators))

(defn load-config
  "Load configuration from heretic.edn, merged with defaults.
   Throws if config file is missing."
  ([]
   (load-config "heretic.edn"))
  ([path]
   (let [f (io/file path)]
     (if (.exists f)
       (merge default-config (edn/read-string (slurp f)))
       (throw (ex-info "Missing heretic.edn config file"
                       {:path path}))))))

;; =============================================================================
;; Coverage Collection
;; =============================================================================

(defn collect!
  "Collect test-to-code coverage mapping.

   Options:
   - :force - Recollect all test namespaces, ignoring staleness
   - :namespaces - Specific test namespaces to collect (default: all)

   Returns map with collection statistics:
   {:total-ns, :stale-ns, :collected-ns, :forms, :duration-ms}"
  [config & {:keys [force namespaces]}]
  (reporter/print-phase "Collecting coverage...")
  (let [result (coverage/collect-and-persist! config :force force :namespaces namespaces)]
    (reporter/print-collection-result result)
    result))

;; =============================================================================
;; Status Checking
;; =============================================================================

(defn status
  "Check staleness of coverage data.

   Returns map with:
   - :stale-namespaces - Set of test namespaces needing recollection
   - :fresh-namespaces - Set of test namespaces with valid coverage
   - :total-coverage-files - Count of existing coverage files
   - :index-exists? - Whether inverse index exists"
  [config]
  (let [heretic-dir (:heretic-dir config)
        source-paths (:source-paths config)
        test-paths (:test-paths config)

        ;; Discover all test namespaces (minus :exclude-test-namespaces)
        all-test-ns (collector/resolve-test-namespaces config)

        ;; Check existing coverage files
        coverage-files (persist/list-coverage-files heretic-dir)
        coverage-count (count (or coverage-files []))

        ;; Find stale namespaces
        stale (persist/find-stale-test-namespaces
               heretic-dir all-test-ns test-paths source-paths config)
        fresh (set/difference (set all-test-ns) stale)

        ;; Check index
        index-exists? (boolean (persist/load-index heretic-dir))]

    {:stale-namespaces stale
     :fresh-namespaces fresh
     :total-coverage-files coverage-count
     :index-exists? index-exists?}))

;; =============================================================================
;; Mutation Testing - Coverage Handling
;; =============================================================================

(defn- ensure-coverage!
  "Ensure coverage data exists and is fresh. Collects if needed.

   Uses controller/ensure-coverage! with status and collect! injected."
  [config]
  (controller/ensure-coverage! config
                               :status-fn status
                               :collect-fn collect!))

;; =============================================================================
;; Mutation Testing - Execution
;; =============================================================================

(defn- evaluate-mutation-with-reload!
  "Evaluate a single mutation with file modification and namespace reloading."
  [index mutation config]
  (engine/with-mutation [applied mutation]
    ;; Force-reload the mutated namespace, bypassing clj-reload's mtime gate.
    ;; Consecutive sub-millisecond spits (apply → revert) collide on the same
    ;; mtime, so plain reload! silently skips the reload and the mutant is falsely
    ;; scored `survived` (0% on fast/small projects). Mirrors heretic.worker's
    ;; parallel path.
    (let [reload-result (reloader/reload-mutated-file! (:file applied))]
      (if (:success reload-result)
        ;; Run tests for this mutation
        (runner/evaluate-mutation index applied config)
        ;; Reload failed - treat as error
        {:mutation applied
         :status :error
         :tests-run #{}
         :duration-ms 0
         :error-message (str "Reload failed: " (:error reload-result))}))))

(defn- run-mutations-sequential
  "Run mutations sequentially. Standard mode for single-threaded execution."
  [index mutations config progress-atom total]
  (let [results (atom [])]
    (doseq [mutation mutations]
      (try
        (let [result (evaluate-mutation-with-reload! index mutation config)]
          (swap! results conj result)
          (let [current (swap! progress-atom inc)]
            (reporter/print-mutation-progress current total (:status result))))
        (catch Exception e
          (swap! results conj {:mutation mutation
                               :status :error
                               :tests-run #{}
                               :timed-out #{}
                               :duration-ms 0
                               :error-message (str e)})
          (let [current (swap! progress-atom inc)]
            (reporter/print-mutation-progress current total :error)))))
    @results))

(defn- run-file-mutations!
  "Run all mutations for a single file sequentially.
   Returns vector of results."
  [index mutations config progress-atom total]
  (let [results (atom [])]
    (doseq [mutation mutations]
      (try
        (let [result (evaluate-mutation-with-reload! index mutation config)]
          (swap! results conj result)
          (let [current (swap! progress-atom inc)]
            (locking *out*
              (reporter/print-mutation-progress current total (:status result)))))
        (catch Exception e
          (swap! results conj {:mutation mutation
                               :status :error
                               :tests-run #{}
                               :timed-out #{}
                               :duration-ms 0
                               :error-message (str e)})
          (let [current (swap! progress-atom inc)]
            (locking *out*
              (reporter/print-mutation-progress current total :error))))))
    @results))

(defn- run-mutations-parallel
  "Run mutations with file-level parallelism.

   Mutations are grouped by file, and each file's mutations are processed
   sequentially on a dedicated thread. Different files are processed in parallel.

   This ensures:
   - No concurrent file modifications (mutations within same file are sequential)
   - Maximum parallelism across files
   - Safe namespace reloading (each file completes before next starts)"
  [index mutations config worker-count]
  (let [total (count mutations)
        progress-atom (atom 0)
        results-atom (atom [])
        by-file (group-by :file mutations)
        ^ExecutorService executor (Executors/newFixedThreadPool worker-count)]
    (try
      (let [futures (doall
                     (for [[_file file-mutations] by-file]
                       (.submit executor
                                ^Callable
                                (fn []
                                  (let [file-results (run-file-mutations!
                                                      index file-mutations config
                                                      progress-atom total)]
                                    (swap! results-atom into file-results)
                                    nil)))))]
        ;; Wait for all tasks to complete
        (doseq [f futures]
          (.get f)))
      @results-atom
      (finally
        (.shutdown executor)))))

;; =============================================================================
;; Missionary Executor
;; =============================================================================

(defn- run-mutations-missionary
  "Run mutations using Missionary-based worker supervision.

   Uses worker/?run-mutation-testing with:
   - Reliable timeout via Missionary task cancellation
   - Configurable supervision policy (:skip, :retry, :abort)
   - Progress reporting via callbacks

   Arguments:
   - index: Coverage index
   - mutations: Sequence of mutations to test
   - config: Configuration map with :parallel?, :parallelism, :supervision-policy, etc.

   Returns vector of mutation results."
  [index mutations config]
  (let [total (count mutations)
        results-atom (atom [])
        on-progress (fn [progress result]
                      (swap! results-atom conj result)
                      (let [current (:completed progress)]
                        (locking *out*
                          (reporter/print-mutation-progress current total (:status result)))))
        worker-config {:index index
                       :mutations mutations
                       :parallel? (:parallel-mutate config true)
                       :parallelism (controller/get-worker-count config)
                       :mutation-timeout-ms (or (:mutation-timeout-ms config) 30000)
                       :timeout-ms (or (:timeout-ms config) 5000)
                       :supervision (or (:supervision-policy config) :skip)
                       :timing-data (:timing-data config)
                       :on-progress on-progress}]
    ;; Run the Missionary-based mutation testing
    ;; The worker returns a summary, but we collect results via progress callback
    ;; to get the raw results needed for timing data extraction
    (m/? (worker/?run-mutation-testing worker-config))
    @results-atom))

;; =============================================================================
;; Process Executor (B3 — forked worker JVM(s))
;; =============================================================================

(defn run-mutations-process
  "Run `mutations` via heretic.runner-process — forked worker JVM(s) that survive
   an uninterruptible infinite-loop mutant (kill the OS process), with
   per-worker filesystem isolation when :parallel-workers > 1.

   This is a DIRECT core path: it is its own isolation (per-worker copies), so it
   is invoked WITHOUT the heretic.sandbox/mutate-in-sandbox! wrapper. It returns
   the same per-mutation result-map vector the in-process executors produce,
   normalized to the MutationResult shape (so aggregate-results / reporting work
   unchanged). A reclaimed loop mutant is :timeout.

   The runner-process child needs the ClojureStorm classpath; supply :child-aliases
   (e.g. [:heretic]) + :process-worker-dir, or :child-deps — see
   heretic.runner-process/child-spawn-spec. `index` is accepted for signature
   parity with the other executors but is unused (each child loads its own index)."
  [_index mutations config]
  (let [eval-process (requiring-resolve 'heretic.runner-process/evaluate-mutations-process)
        ;; runner-process returns {:mutation :status :killed-by :killed-by-all
        ;; :timed-out :eval-ms}; normalize to the MutationResult shape the rest of
        ;; core/controller expects (add :tests-run / :duration-ms defaults).
        results (eval-process config mutations)
        total (count mutations)
        progress (atom 0)]
    (mapv (fn [r]
            (let [current (swap! progress inc)]
              (locking *out*
                (reporter/print-mutation-progress current total (:status r))))
            {:mutation (:mutation r)
             :status (:status r)
             :tests-run (or (:tests-run r) #{})
             :timed-out (or (:timed-out r) #{})
             :killed-by (:killed-by r)
             :killed-by-all (:killed-by-all r)
             :duration-ms (or (:eval-ms r) 0)})
          results)))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn mutate!
  "Run mutation testing on the codebase.

   Options:
   - :files - Specific source files to mutate (default: all in source-paths)
   - :operators - Mutation operators to use (default: from config)
   - :verbose - Print detailed progress

   Configuration:
   - :executor - :legacy (ExecutorService) or :missionary (Missionary workers)
   - :supervision-policy - :skip, :retry, or :abort (Missionary executor only)
   - :mutation-timeout-ms - Per-mutation timeout in ms (Missionary executor, default: 30000)

   Returns mutation testing results including:
   {:total, :killed, :survived, :no-coverage, :mutation-score, :survivors}

   The workflow:
   1. Ensure coverage data is available (collect if needed)
   2. Initialize namespace reloader
   3. Generate and filter mutations (via controller)
   4. Execute mutations (via selected executor)
   5. Aggregate results with analysis (via controller)
   6. Print reports and return results"
  [config & {:keys [files operators verbose]}]
  (reporter/print-header)

  ;; Step 1: Ensure coverage exists
  (let [index (ensure-coverage! config)]
    (when-not index
      (throw (ex-info "Failed to load coverage index" {})))

    ;; Step 2: Initialize reloader
    (println)
    (reporter/print-phase "Initializing namespace reloader...")
    (let [source-paths (:source-paths config)]
      (reloader/init! source-paths :output :quiet))
    (reporter/print-phase "Reloader ready.")

    ;; Step 3: Generate and filter mutations (via controller)
    (println)
    (reporter/print-phase "Scanning for mutation sites...")
    (let [resolved-ops (resolve-operators config :operators operators)
          _ (when verbose
              (reporter/print-phase (format "Using %d operators" (count resolved-ops))))

          ;; Use controller for pure mutation preparation
          {:keys [mutations total-found filtered-count]}
          (controller/prepare-mutations config resolved-ops :files files)

          total (count mutations)]

      (reporter/print-mutation-scan-result total-found filtered-count (:filter-equivalent config true))

      (if (zero? total)
        (do
          (reporter/print-phase "No mutations to test.")
          {:total 0
           :killed 0
           :survived 0
           :no-coverage 0
           :timeout 0
           :error 0
           :equivalent-filtered filtered-count
           :mutation-score 1.0
           :survivors []})

        ;; Step 4: Execute mutations
        (do
          (println)
          (let [parallel? (:parallel-mutate config)
                worker-count (controller/get-worker-count config)
                executor (or (:executor config) :legacy)]
            (reporter/print-parallel-mode parallel? worker-count)
            (when (= executor :missionary)
              (reporter/print-phase (format "Using Missionary executor (supervision: %s)"
                                            (name (or (:supervision-policy config) :skip))))))

          (let [heretic-dir (:heretic-dir config)
                ;; Load timing data via controller
                timing-data (controller/load-timing-data heretic-dir)
                _ (when timing-data
                    (reporter/print-timing-loaded (count timing-data)))
                ;; Build test config via controller
                test-config (controller/build-test-config config timing-data)
                parallel? (:parallel-mutate config)
                worker-count (controller/get-worker-count config)
                executor (or (:executor config) :legacy)
                start-time (System/currentTimeMillis)
                progress-atom (atom 0)

                ;; Run mutations using selected executor
                all-results (case executor
                              :missionary
                              (run-mutations-missionary index mutations
                                                        (assoc test-config
                                                               :parallel-mutate parallel?
                                                               :parallel-workers worker-count
                                                               :mutation-timeout-ms (:mutation-timeout-ms config 30000)
                                                               :supervision-policy (:supervision-policy config :skip)))

                              :process
                              ;; Forked worker JVM(s) — its OWN isolation (per-worker
                              ;; copies when :parallel-workers > 1); a direct core
                              ;; path, NOT wrapped in mutate-in-sandbox!. Pass the
                              ;; raw config (child-aliases / parallel-workers /
                              ;; timeout-ms / sandbox-dir) through.
                              ;; default-config has :parallel-workers nil (key present),
                              ;; so coerce explicitly to 1 here rather than relying on the
                              ;; 3-arg default (which never fires) + downstream nil-coercion.
                              (run-mutations-process index mutations
                                                     (merge config test-config
                                                            {:parallel-workers (or (:parallel-workers config) 1)}))

                              ;; :legacy (default) - use ExecutorService
                              (if parallel?
                                (run-mutations-parallel index mutations test-config worker-count)
                                (run-mutations-sequential index mutations test-config progress-atom total)))]

            (println)  ; Newline after progress

            ;; Step 5: Save timing data via controller
            (controller/save-timing-data! heretic-dir all-results)

            ;; Step 6: Aggregate results via controller
            (let [final-result (controller/aggregate-results all-results filtered-count start-time)
                  survivor-list (filter #(= :survived (:status %)) all-results)
                  report-format (:report-format config)
                  output-path (:output-path config "target/heretic-report")]

              ;; Step 6.5: Save mutation results for survivors command
              (let [results-file (io/file heretic-dir "mutation-results.edn")
                    survivors-data (mapv (fn [{:keys [mutation]}]
                                           (select-keys mutation [:file :line :column :operator :original :replacement]))
                                         survivor-list)]
                (spit results-file (pr-str {:survivors survivors-data
                                            :summary final-result
                                            :timestamp (System/currentTimeMillis)})))

              ;; Step 7: Print survivor hotspots (files with most survivors)
              (when (seq survivor-list)
                (reporter/print-survivor-hotspots all-results))

              ;; Step 8: Generate file report based on config
              (case report-format
                :html (let [html-path (reporter/generate-html-report
                                       all-results
                                       (str output-path "/index.html"))]
                        (reporter/print-html-report-written html-path))
                :json (let [json-path (reporter/generate-json-report
                                       all-results
                                       (str output-path "/report.json"))]
                        (reporter/print-json-report-written json-path))
                :edn (let [edn-path (reporter/generate-edn-report
                                     all-results
                                     (str output-path "/report.edn"))]
                       (reporter/print-edn-report-written edn-path))
                ;; :terminal or default - no file output needed
                nil)

              ;; Step 9: Print summary at the end
              (println)
              (reporter/print-summary all-results)

              ;; Return results
              final-result)))))))

;; Sandboxed mutation (issue #2) lives in heretic.sandbox/mutate-in-sandbox!,
;; deliberately NOT exposed through heretic.core — see the heretic.sandbox ns
;; docstring for why (it keeps the orchestrator off the ClojureStorm classpath).

(defn survivors
  "Get surviving mutations from last run.

   Loads results from .heretic/mutation-results.edn if present.
   Returns sequence of mutations that were not killed by tests."
  [config]
  (let [heretic-dir (:heretic-dir config)
        results-file (io/file heretic-dir "mutation-results.edn")]
    (if (.exists results-file)
      (let [data (edn/read-string (slurp results-file))]
        (:survivors data))
      (throw (ex-info "No mutation results found. Run `mutate!` first."
                      {:path (.getPath results-file)})))))

;; =============================================================================
;; CLI Helpers
;; =============================================================================

(defn clean!
  "Remove .heretic directory and all coverage data."
  [config]
  (let [heretic-dir (:heretic-dir config)]
    (if (persist/clean-heretic-dir! heretic-dir)
      (do
        (reporter/print-phase (str "Removed " heretic-dir))
        {:deleted true :path heretic-dir})
      (do
        (reporter/print-phase (str "Nothing to clean - " heretic-dir " does not exist"))
        {:deleted false :path heretic-dir}))))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(defn- print-survivors [config]
  (let [survs (survivors config)]
    (if (seq survs)
      (doseq [s survs]
        (println (format "  %s:%s  %s -> %s"
                         (:file s) (:line s) (:original s) (:replacement s))))
      (println "No surviving mutations."))))

(defn- print-status [config]
  (let [{:keys [stale-namespaces fresh-namespaces index-exists?]} (status config)]
    (println (format "Coverage index: %s" (if index-exists? "present" "missing")))
    (println (format "Fresh namespaces: %d" (count fresh-namespaces)))
    (println (format "Stale namespaces: %d%s" (count stale-namespaces)
                     (if (seq stale-namespaces)
                       (str " — " (str/join ", " stale-namespaces))
                       "")))))

(defn -main
  "CLI entry point.

   Usage: clojure -M:heretic -m heretic.core <command> [args]

   Commands:
     collect [--force]        Build / refresh the test-to-code coverage map
     mutate  [--files a,b]    Run mutation testing (sandboxed — working tree untouched)
     watch                    Continuous sandboxed mutation testing on file changes
     status                   Show which test namespaces need recollection
     survivors                Show surviving mutations from the last run
     clean                    Remove cached coverage data

   `mutate` and `watch` run against an isolated sandbox copy via heretic.sandbox
   (see docs/sandboxed-mutation.md); the others operate in-process."
  [& args]
  (let [[cmd & opts] args
        config (load-config)
        files (when-let [v (->> opts (drop-while #(not= "--files" %)) second)]
                (str/split v #","))]
    (case cmd
      "collect"   (collect! config :force (boolean (some #{"--force"} opts)))
      "mutate"    (let [mutate-in-sandbox! (requiring-resolve 'heretic.sandbox/mutate-in-sandbox!)]
                    (if files
                      (mutate-in-sandbox! config :files files)
                      (mutate-in-sandbox! config)))
      "watch"     ((requiring-resolve 'heretic.watch/watch!) config)  ; blocks until interrupted
      "status"    (print-status config)
      "survivors" (print-survivors config)
      "clean"     (clean! config)
      (do
        (println "Unknown command:" (pr-str cmd))
        (println "Commands: collect | mutate | watch | status | survivors | clean")
        (System/exit 1)))
    ;; watch blocks forever; every other command is one-shot.
    (when-not (= cmd "watch")
      (shutdown-agents)
      (System/exit 0))))
