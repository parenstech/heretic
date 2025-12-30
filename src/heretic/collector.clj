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
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [heretic.tracer :as tracer]))

;; =============================================================================
;; Test Discovery
;; =============================================================================

(defn- file->ns-symbol
  "Extract namespace symbol from a Clojure file.
   Returns nil if namespace cannot be determined."
  [file]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader file))]
      (let [form (read rdr)]
        (when (and (seq? form) (= 'ns (first form)))
          (second form))))
    (catch Exception _
      nil)))

(defn- clojure-file?
  "Returns true if file is a Clojure source file (.clj or .cljc)."
  [file]
  (let [name (.getName file)]
    (or (.endsWith name ".clj")
        (.endsWith name ".cljc"))))

(defn- find-clj-files
  "Recursively find all Clojure files (.clj and .cljc) in a directory."
  [dir]
  (let [d (io/file dir)]
    (when (.exists d)
      (->> (file-seq d)
           (filter #(.isFile %))
           (filter clojure-file?)))))

(defn discover-test-namespaces
  "Find all test namespaces in the given test paths.

   Scans directories for Clojure files (.clj and .cljc) and extracts ns declarations.
   Returns sequence of namespace symbols."
  [test-paths]
  (->> test-paths
       (mapcat find-clj-files)
       (keep file->ns-symbol)
       (distinct)))

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

(defn- apply-each-fixtures
  "Apply :each fixtures for a namespace around a function.
   clojure.test/test-var doesn't apply fixtures - we need to do it manually."
  [ns-obj f]
  (let [each-fixtures (::t/each-fixtures (meta ns-obj))
        fixture-fn (t/join-fixtures (or each-fixtures []))]
    (fixture-fn f)))

(defn run-test-with-coverage
  "Run a single test and capture its coverage.

   Manually applies :each fixtures since clojure.test/test-var doesn't.
   We call the var directly rather than using t/test-var to avoid
   generating test reports (we only care about coverage, not results).
   Exceptions are caught inside the fixture wrapper to continue collection.

   Returns map of {test-symbol -> {form-id -> #{coords}}}"
  [test-var]
  (tracer/reset-current-coverage!)
  (let [ns-obj (:ns (meta test-var))]
    ;; Apply :each fixtures manually, with exception handling inside
    (apply-each-fixtures ns-obj
                         (fn []
                           (try
                             ;; Call the :test function directly, not the var wrapper
                             ;; (deftest creates a var that internally calls t/test-var)
                             ((:test (meta test-var)))
                             (catch Throwable _e nil)))))
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
  (when-not (tracer/initialized?)
    (tracer/init!))
  (into {}
        (for [ns-sym stale-ns]
          [ns-sym (collect-coverage-for-ns ns-sym)])))
