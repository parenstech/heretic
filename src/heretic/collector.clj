(ns heretic.collector
  "Per-test coverage collection.

   Runs each test individually and captures its coverage via the tracer.
   This approach:
   - Works with ALL test runners (Kaocha, Cognitect, clojure.test)
   - Avoids threading issues with binding
   - Doesn't require alter-var-root hacks
   - Produces accurate per-test coverage

   Trade-off: Running tests one-by-one is slower than running all tests
   at once, but enables accurate per-test coverage mapping."
  (:require [heretic.tracer :as tracer]
            [clojure.test :as t]))

;; =============================================================================
;; Test Discovery
;; =============================================================================

(defn discover-test-namespaces
  "Find all test namespaces matching the given paths or patterns.

   Returns sequence of namespace symbols."
  [test-paths]
  ;; TODO: Implement namespace discovery
  ;; 1. Scan test-paths directories
  ;; 2. Find .clj files
  ;; 3. Extract namespace from ns form
  ;; 4. Return sequence of symbols
  (throw (ex-info "Test namespace discovery not yet implemented" {})))

(defn discover-test-vars
  "Find all test vars in the given namespaces.

   A test var is one with :test metadata.
   Returns sequence of test var objects."
  [test-namespaces]
  (for [ns-sym test-namespaces
        :let [_ (require ns-sym)  ;; Ensure namespace is loaded
              ns-obj (the-ns ns-sym)]
        [_ v] (ns-publics ns-obj)
        :when (:test (meta v))]
    v))

(defn discover-test-vars-in-ns
  "Find all test vars in a single namespace.

   Returns sequence of test var objects."
  [test-ns]
  (require test-ns)
  (let [ns-obj (the-ns test-ns)]
    (for [[_ v] (ns-publics ns-obj)
          :when (:test (meta v))]
      v)))

;; =============================================================================
;; Per-Test Execution
;; =============================================================================

(defn run-test-with-coverage
  "Run a single test and capture its coverage.

   Returns map of {test-symbol -> {form-id -> #{coords}}}"
  [test-var]
  (tracer/reset-current-coverage!)
  (try
    ;; Run the test function directly (works regardless of test runner)
    (test-var)
    (catch Throwable t
      ;; Log but continue - we still want the coverage data
      ;; Test failures are expected during mutation testing
      (println "Test threw exception:" (.getMessage t))))
  ;; Return coverage for this test
  {(symbol test-var) (tracer/get-current-coverage)})

;; =============================================================================
;; Namespace-Level Collection
;; =============================================================================

(defn collect-coverage-for-ns
  "Collect coverage for all tests in a single namespace.

   Returns {:test-coverage {test-sym {form-id #{coords}}}}"
  [test-ns]
  (let [test-vars (discover-test-vars-in-ns test-ns)
        coverage (reduce
                   (fn [acc test-var]
                     (merge acc (run-test-with-coverage test-var)))
                   {}
                   test-vars)]
    {:test-coverage coverage}))

;; =============================================================================
;; Full Collection
;; =============================================================================

(defn collect-all-coverage
  "Run all tests one by one and collect per-test coverage.

   Returns {:test-coverage {test-sym {form-id #{coords}}}}"
  [test-namespaces]
  (tracer/init!)
  (let [test-vars (discover-test-vars test-namespaces)
        coverage (reduce
                   (fn [acc test-var]
                     (merge acc (run-test-with-coverage test-var)))
                   {}
                   test-vars)]
    {:test-coverage coverage}))

;; =============================================================================
;; Incremental Collection
;; =============================================================================

(defn collect-stale-namespaces
  "Collect coverage only for stale test namespaces.

   stale-ns is a set of namespace symbols that need recollection.
   Returns map of {ns-sym -> coverage-data} for collected namespaces."
  [stale-ns]
  (when-not (tracer/initialized?*)
    (tracer/init!))
  (into {}
        (for [ns-sym stale-ns]
          [ns-sym (collect-coverage-for-ns ns-sym)])))
