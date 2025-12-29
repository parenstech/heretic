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

   Configuration is loaded from heretic.edn in the project root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [heretic.collector :as collector]
            [heretic.coverage-map :as coverage]
            [heretic.equivalent :as equiv]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.parser :as parser]
            [heretic.persistence :as persist]
            [heretic.reloader :as reloader]
            [heretic.reporter :as reporter]
            [heretic.runner :as runner]
            [heretic.subsumption :as subsumption]
            [heretic.timing :as timing])
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
   :parallel-collect false
   ;; Operator selection - use :preset OR :operators (operators takes precedence)
   ;; :preset can be :fast, :standard, or :comprehensive
   ;; :operators is a sequence of operator definitions or ids
   :preset :standard
   :skip-forms #{'comment}
   ;; Timeout configuration
   :timeout-ms 5000           ; Per-test timeout in milliseconds
   :budget-ms nil             ; Optional total time budget per mutation (nil = unlimited)
   ;; Parallel mutation testing
   :parallel-mutate false     ; Enable file-level parallelism
   :parallel-workers nil      ; Number of worker threads (nil = CPU count)
   ;; Equivalent mutant detection
   :filter-equivalent true    ; Filter likely equivalent mutants
   ;; Report output
   :report-format :terminal
   :output-path "target/heretic-report"})

(defn resolve-operators
  "Resolve operators from config.

   Priority:
   1. If :operators is specified in config, use those
   2. If :preset is specified, use operators for that preset
   3. Default to :standard preset

   Arguments:
   - config: Configuration map
   - override-operators: Optional operators to use (takes highest priority)

   Returns sequence of operator definitions."
  [config & {:keys [operators]}]
  (cond
    ;; Explicit operators passed as argument take highest priority
    operators
    operators

    ;; Config :operators key (sequence of operator defs or ids)
    (:operators config)
    (let [ops-cfg (:operators config)]
      (if (every? keyword? ops-cfg)
        ;; Sequence of operator ids
        (keep ops/operators-by-id ops-cfg)
        ;; Already operator definitions
        ops-cfg))

    ;; Config :preset key
    (:preset config)
    (ops/operators-for-preset (:preset config))

    ;; Default to :standard preset
    :else
    (ops/operators-for-preset :standard)))

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
  (println "Collecting coverage...")
  (let [result (coverage/collect-and-persist! config :force force :namespaces namespaces)]
    (println)
    (println "Collection complete:")
    (println (format "  Test namespaces: %d total, %d stale, %d collected"
                     (:total-ns result) (:stale-ns result) (:collected-ns result)))
    (println (format "  Forms registered: %d" (:forms result)))
    (println (format "  Duration: %dms" (:duration-ms result)))
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
;; Mutation Testing (Phase 2)
;; =============================================================================

(defn- ensure-coverage!
  "Ensure coverage data exists and is fresh. Collects if needed."
  [config]
  (let [heretic-dir (:heretic-dir config)
        index (coverage/load-index heretic-dir)
        {:keys [stale-namespaces]} (when index (status config))]
    (cond
      ;; No index exists - collect everything
      (nil? index)
      (do
        (println "No coverage data found, collecting...")
        (collect! config)
        (coverage/load-index heretic-dir))

      ;; Index exists but some namespaces are stale
      (seq stale-namespaces)
      (do
        (println "Coverage data is stale, recollecting...")
        (collect! config :namespaces stale-namespaces)
        (coverage/load-index heretic-dir))

      ;; Index exists and everything is fresh
      :else
      (do
        (println "Coverage data is up to date.")
        index))))

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

(defn- print-progress
  "Print mutation testing progress."
  [current total status]
  (let [pct (int (* 100.0 (/ current total)))
        status-indicator (case status
                           :killed "✓"
                           :survived "✗"
                           :no-coverage "○"
                           :timeout "⏱"
                           :error "!"
                           "?")]
    (print (format "\r[%3d%%] %d/%d mutations tested %s" pct current total status-indicator))
    (flush)))

;; =============================================================================
;; Parallel Mutation Testing
;; =============================================================================

(defn- run-mutations-sequential
  "Run mutations sequentially. Standard mode for single-threaded execution."
  [index mutations config progress-atom total]
  (let [results (atom [])]
    (doseq [mutation mutations]
      (try
        (let [result (evaluate-mutation-with-reload! index mutation config)]
          (swap! results conj result)
          (let [current (swap! progress-atom inc)]
            (print-progress current total (:status result))))
        (catch Exception e
          (swap! results conj {:mutation mutation
                               :status :error
                               :tests-run #{}
                               :timed-out #{}
                               :duration-ms 0
                               :error-message (str e)})
          (let [current (swap! progress-atom inc)]
            (print-progress current total :error)))))
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
              (print-progress current total (:status result)))))
        (catch Exception e
          (swap! results conj {:mutation mutation
                               :status :error
                               :tests-run #{}
                               :timed-out #{}
                               :duration-ms 0
                               :error-message (str e)})
          (let [current (swap! progress-atom inc)]
            (locking *out*
              (print-progress current total :error))))))
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

(defn mutate!
  "Run mutation testing on the codebase.

   Options:
   - :files - Specific source files to mutate (default: all in source-paths)
   - :operators - Mutation operators to use (default: from config)
   - :verbose - Print detailed progress

   Returns mutation testing results including:
   {:total, :killed, :survived, :no-coverage, :mutation-score, :survivors}"
  [config & {:keys [files operators verbose]}]
  (println "═══════════════════════════════════════════════════════════════")
  (println "                    Heretic Mutation Testing")
  (println "═══════════════════════════════════════════════════════════════")
  (println)

  ;; Step 1: Ensure coverage exists
  (let [index (ensure-coverage! config)]
    (when-not index
      (throw (ex-info "Failed to load coverage index" {})))

    ;; Step 2: Initialize reloader
    (println)
    (println "Initializing namespace reloader...")
    (let [source-paths (:source-paths config)]
      (reloader/init! source-paths))
    (println "Reloader ready.")

    ;; Step 3: Generate mutations
    (println)
    (println "Scanning for mutation sites...")
    (let [source-paths (:source-paths config)
          ;; Resolve operators from config or override
          resolved-ops (resolve-operators config :operators operators)
          _ (when verbose
              (println (format "Using %d operators" (count resolved-ops))))
          all-mutations (if files
                          (mapcat #(engine/mutations-for-file % resolved-ops) files)
                          (engine/generate-mutations source-paths resolved-ops))
          all-mutations-vec (vec all-mutations)
          total-found (count all-mutations-vec)

          ;; Step 3b: Filter equivalent mutations if enabled
          filter-equiv? (:filter-equivalent config true)
          {:keys [mutations-vec filtered-count]}
          (if filter-equiv?
            (let [zloc-fn (fn [m]
                            (try
                              (when-let [zloc (parser/parse-file (:file m))]
                                (parser/mutation-site->zloc m zloc))
                              (catch Exception _ nil)))
                  result (equiv/filter-equivalent-mutations all-mutations-vec zloc-fn)]
              {:mutations-vec (vec (:mutations result))
               :filtered-count (:filtered-count result)})
            {:mutations-vec all-mutations-vec
             :filtered-count 0})

          total (count mutations-vec)]

      (println (format "Found %d mutation sites." total-found))
      (when (and filter-equiv? (pos? filtered-count))
        (println (format "Filtered %d likely equivalent mutations." filtered-count)))

      (if (zero? total)
        (do
          (println "No mutations to test.")
          {:total 0
           :killed 0
           :survived 0
           :no-coverage 0
           :timeout 0
           :error 0
           :equivalent-filtered filtered-count
           :mutation-score 1.0
           :survivors []})

        ;; Step 4: Evaluate each mutation
        (do
          (println)
          (let [parallel? (:parallel-mutate config)
                worker-count (or (:parallel-workers config)
                                 (.availableProcessors (Runtime/getRuntime)))]
            (if parallel?
              (println (format "Running mutation tests in parallel (%d workers)..." worker-count))
              (println "Running mutation tests...")))

          (let [heretic-dir (:heretic-dir config)
                timeout-ms (or (:timeout-ms config) 5000)
                budget-ms (:budget-ms config)
                ;; Load historical timing data for test ordering (fastest first)
                timing-data (timing/load-timing heretic-dir)
                _ (when timing-data
                    (println (format "Loaded timing data for %d tests (ordering fastest first)."
                                     (count timing-data))))
                test-config (cond-> {:timeout-ms timeout-ms
                                     :timing-data timing-data}
                              budget-ms (assoc :budget-ms budget-ms))
                parallel? (:parallel-mutate config)
                worker-count (or (:parallel-workers config)
                                 (.availableProcessors (Runtime/getRuntime)))
                start-time (System/currentTimeMillis)
                progress-atom (atom 0)

                ;; Run mutations either in parallel or sequentially
                all-results (if parallel?
                              (run-mutations-parallel index mutations-vec test-config worker-count)
                              (run-mutations-sequential index mutations-vec test-config progress-atom total))]

            (println)  ; Newline after progress

            ;; Step 5: Collect and save timing data from this run
            (let [all-test-durations (reduce (fn [acc result]
                                               (merge acc (:test-durations result {})))
                                             {}
                                             all-results)]
              (when (seq all-test-durations)
                (timing/record-timing! heretic-dir all-test-durations)))

            ;; Step 6: Generate summary with subsumption analysis
            (let [summary (runner/summarize-results all-results)
                  survivor-list (filter #(= :survived (:status %)) all-results)
                  ;; Compute subsumption statistics for potential optimization insights
                  sub-stats (subsumption/subsumption-stats all-results)
                  final-result (assoc summary
                                      :survivors (mapv :mutation survivor-list)
                                      :equivalent-filtered filtered-count
                                      :subsumption-stats sub-stats
                                      :total-duration-ms (- (System/currentTimeMillis) start-time))]

              ;; Step 7: Print terminal report
              (println)
              (reporter/print-summary all-results)

              (when (seq survivor-list)
                (println)
                (reporter/print-survivors all-results))

              ;; Step 7b: Print test effectiveness report
              (reporter/print-test-effectiveness all-results)

              ;; Step 8: Generate HTML report if configured
              (when (= :html (:report-format config))
                (let [output-path (:output-path config "target/heretic-report")
                      html-path (reporter/generate-html-report
                                 all-results
                                 (str output-path "/index.html"))]
                  (println)
                  (println (format "HTML report written to: %s" html-path))))

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
        (println "Removed" heretic-dir)
        {:deleted true :path heretic-dir})
      (do
        (println "Nothing to clean -" heretic-dir "does not exist")
        {:deleted false :path heretic-dir}))))
