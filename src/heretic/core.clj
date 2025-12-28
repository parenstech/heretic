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
            [heretic.persistence :as persist]))

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

(defn mutate!
  "Run mutation testing on the codebase.

   Options:
   - :files - Specific source files to mutate (default: all in source-paths)
   - :operators - Mutation operators to use (default: from config)

   Returns mutation testing results."
  [config & {:keys [files operators]}]
  ;; TODO: Implement mutation testing (Phase 2)
  ;; 1. Load coverage map (or collect if stale/missing)
  ;; 2. Generate mutations for source files
  ;; 3. For each mutation:
  ;;    a. Look up relevant tests
  ;;    b. Apply mutation
  ;;    c. Reload namespaces
  ;;    d. Run tests
  ;;    e. Record result
  ;;    f. Revert mutation
  ;; 4. Generate report
  (throw (ex-info "Mutation testing not yet implemented (Phase 2)" {})))

(defn survivors
  "Get surviving mutations from last run.

   Returns sequence of mutations that were not killed by tests."
  [config]
  ;; TODO: Implement survivor retrieval (Phase 2)
  (throw (ex-info "Survivor retrieval not yet implemented (Phase 2)" {})))

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
