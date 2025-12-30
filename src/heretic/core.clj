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
   - :executor :missionary - Uses Missionary-based worker supervision with reliable timeout"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
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

        ;; Discover all test namespaces
        all-test-ns (if (= :all (:test-namespaces config))
                      (collector/discover-test-namespaces test-paths)
                      (:test-namespaces config))

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
    ;; Reload changed namespaces
    (let [reload-result (reloader/reload!)]
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
      (reloader/init! source-paths))
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

              ;; Step 7: Print terminal report (always)
              (println)
              (reporter/print-summary all-results)

              (when (seq survivor-list)
                (println)
                (reporter/print-survivors all-results)
                ;; Print diagnosis of survivor patterns
                (reporter/print-diagnosis all-results))

              ;; Print test effectiveness report
              (reporter/print-test-effectiveness all-results)

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

              ;; Return results
              final-result)))))))

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
