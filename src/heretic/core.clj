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
  (:require [heretic.persistence :as persist]
            [heretic.coverage-map :as coverage]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

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

   Returns map with collection statistics."
  [config & {:keys [force namespaces]}]
  ;; TODO: Implement coverage collection
  ;; 1. Initialize ClojureStorm tracer callbacks
  ;; 2. Discover test namespaces (or use provided list)
  ;; 3. Find stale namespaces (or all if :force)
  ;; 4. For each stale namespace:
  ;;    a. Run tests one-by-one with coverage tracking
  ;;    b. Persist coverage to .heretic/coverage/<namespace>.edn
  ;; 5. Rebuild inverse index
  ;; 6. Return statistics
  (throw (ex-info "Coverage collection not yet implemented" {})))

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
  ;; TODO: Implement status checking
  ;; 1. Load existing coverage files
  ;; 2. Check staleness of each test namespace
  ;; 3. Return summary
  (throw (ex-info "Status checking not yet implemented" {})))

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
  (let [heretic-dir (io/file (:heretic-dir config))]
    (when (.exists heretic-dir)
      ;; TODO: Use proper recursive delete
      (run! io/delete-file (reverse (file-seq heretic-dir)))
      {:deleted true :path (.getPath heretic-dir)})))
