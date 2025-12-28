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
            [heretic.mutation-engine :as engine]
            [heretic.persistence :as persist]
            [heretic.reloader :as reloader]
            [heretic.reporter :as reporter]
            [heretic.runner :as runner]))

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
   :mutation-operators [:arithmetic :comparison :boolean :return-values]
   :skip-forms #{'comment}
   :timeout-ms 5000
   :parallel-mutate false
   :report-format :terminal
   :output-path "target/heretic-report"})

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
          mutations (if files
                      (mapcat engine/mutations-for-file files)
                      (engine/generate-mutations source-paths))
          mutations-vec (vec mutations)
          total (count mutations-vec)]

      (println (format "Found %d mutation sites." total))

      (if (zero? total)
        (do
          (println "No mutations to test.")
          {:total 0
           :killed 0
           :survived 0
           :no-coverage 0
           :timeout 0
           :error 0
           :mutation-score 1.0
           :survivors []})

        ;; Step 4: Evaluate each mutation
        (do
          (println)
          (println "Running mutation tests...")
          (let [timeout-ms (or (:timeout-ms config) 5000)
                test-config {:timeout-ms timeout-ms}
                results (atom [])
                start-time (System/currentTimeMillis)]

            (doseq [[idx mutation] (map-indexed vector mutations-vec)]
              (try
                (let [result (evaluate-mutation-with-reload! index mutation test-config)]
                  (swap! results conj result)
                  (print-progress (inc idx) total (:status result)))
                (catch Exception e
                  (swap! results conj {:mutation mutation
                                       :status :error
                                       :tests-run #{}
                                       :duration-ms 0
                                       :error-message (str e)})
                  (print-progress (inc idx) total :error))))

            (println)  ; Newline after progress

            ;; Step 5: Generate summary
            (let [all-results @results
                  summary (runner/summarize-results all-results)
                  survivors (filter #(= :survived (:status %)) all-results)
                  final-result (assoc summary
                                      :survivors (mapv :mutation survivors)
                                      :total-duration-ms (- (System/currentTimeMillis) start-time))]

              ;; Step 6: Print report
              (println)
              (reporter/print-summary all-results)

              (when (seq survivors)
                (println)
                (reporter/print-survivors all-results))

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
