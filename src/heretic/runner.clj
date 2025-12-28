(ns heretic.runner
  "Execute targeted tests for mutations.

   The runner is responsible for:
   - Looking up which tests cover a mutation site (via coverage index)
   - Executing those specific tests with timeout handling
   - Determining if the mutation was killed or survived
   - Handling errors gracefully

   Main API:
   - `tests-for-mutation` - Look up tests via coverage map
   - `run-tests` - Execute tests with timeout, return results
   - `evaluate-mutation` - Full mutation evaluation lifecycle

   Mutation result statuses:
   - :killed - At least one test failed/errored (mutation detected)
   - :survived - All tests passed (mutation NOT detected)
   - :no-coverage - No tests cover this mutation site
   - :timeout - Test execution timed out
   - :error - Exception during test execution"
  (:require [clojure.test :as t]
            [heretic.coverage-map :as coverage]
            [heretic.form-bridge :as bridge]))

;; =============================================================================
;; Test Lookup
;; =============================================================================

;; Form-ID resolution is delegated to heretic.form-bridge

(defn tests-for-mutation
  "Look up which tests cover the given mutation.

   Uses the coverage index to find tests that exercise the mutation site.
   First tries to resolve file+line to ClojureStorm's form-id via form-location-index,
   then falls back to the mutation's form-id for backwards compatibility.

   Note: When using form-location-index (ClojureStorm coverage), we use form-level
   matching only because coord formats differ between rewrite-clj (used for mutations)
   and ClojureStorm (used for coverage). For mock indexes in tests, coord matching works.

   Arguments:
   - index: Coverage index from heretic.coverage-map/load-index
   - mutation: Mutation record with :form-id and optionally :file, :line, :coord

   Returns set of test symbols that cover this mutation."
  [index mutation]
  (let [{:keys [coord]} mutation
        form-location-index (:form-location-index index)
        form-id (bridge/resolve-form-id form-location-index mutation)]
    (if-not form-id
      ;; No form-id found, return empty set (no coverage)
      #{}
      ;; If using form-location-index (real ClojureStorm coverage), use form-level lookup
      ;; because coord formats differ. For mock indexes (tests), use coord matching.
      (if (bridge/use-form-level-matching? index)
        ;; Form-level lookup - get all tests that hit any coord in the form
        (coverage/tests-for-location index form-id)
        ;; Coord-specific lookup for backwards compatibility with mock indexes
        (if coord
          (coverage/tests-for-location index form-id coord)
          (coverage/tests-for-location index form-id))))))

;; =============================================================================
;; Test Execution
;; =============================================================================

(defn- resolve-test-var
  "Resolve a test symbol to its var, loading namespace if needed.

   Returns the var or nil if resolution fails."
  [test-sym]
  (try
    (let [ns-sym (symbol (namespace test-sym))]
      (require ns-sym)
      (find-var test-sym))
    (catch Exception _
      nil)))

(defn- run-single-test
  "Run a single test var and capture results.

   Returns {:pass n :fail n :error n} counts."
  [test-var]
  (let [results (atom {:pass 0 :fail 0 :error 0})]
    (binding [t/*test-out* (java.io.StringWriter.)
              t/report (fn [m]
                         (case (:type m)
                           :pass (swap! results update :pass inc)
                           :fail (swap! results update :fail inc)
                           :error (swap! results update :error inc)
                           ;; Ignore other report types (:begin-test-var, :end-test-var, etc.)
                           nil))]
      (try
        (t/test-var test-var)
        (catch AssertionError e
          ;; clojure.test uses AssertionError for failures
          (swap! results update :fail inc))
        (catch Exception e
          (swap! results update :error inc))))
    @results))

(defn- run-test-with-timeout
  "Run a single test with timeout.

   Returns:
   - {:status :completed :results {...}} on successful completion
   - {:status :timeout} if test exceeds timeout
   - {:status :error :exception e} on unexpected error"
  [test-var timeout-ms]
  (let [f (future (run-single-test test-var))
        result (deref f timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do
        (future-cancel f)
        {:status :timeout})
      {:status :completed :results result})))

(defn run-tests
  "Execute a set of tests with timeout handling.

   Arguments:
   - test-syms: Collection of test symbols to run
   - timeout-ms: Per-test timeout in milliseconds

   Returns:
   {:status :completed/:timeout/:error
    :results {:pass n :fail n :error n}  ; aggregate when :completed
    :tests-run #{test-syms...}
    :duration-ms n}"
  [test-syms timeout-ms]
  (let [start-time (System/currentTimeMillis)
        test-syms-set (set test-syms)]
    (if (empty? test-syms-set)
      {:status :no-tests
       :results {:pass 0 :fail 0 :error 0}
       :tests-run #{}
       :duration-ms 0}
      (loop [remaining (seq test-syms-set)
             aggregate {:pass 0 :fail 0 :error 0}
             ran #{}]
        (if-not remaining
          ;; All tests completed
          {:status :completed
           :results aggregate
           :tests-run ran
           :duration-ms (- (System/currentTimeMillis) start-time)}
          ;; Run next test
          (let [test-sym (first remaining)
                test-var (resolve-test-var test-sym)]
            (if-not test-var
              ;; Var not found, skip and continue
              (recur (next remaining)
                     aggregate
                     ran)
              ;; Run the test
              (let [result (run-test-with-timeout test-var timeout-ms)]
                (case (:status result)
                  :timeout
                  {:status :timeout
                   :results aggregate
                   :tests-run (conj ran test-sym)
                   :failed-test test-sym
                   :duration-ms (- (System/currentTimeMillis) start-time)}

                  :completed
                  (recur (next remaining)
                         (merge-with + aggregate (:results result))
                         (conj ran test-sym))

                  ;; Default: error
                  {:status :error
                   :results aggregate
                   :tests-run (conj ran test-sym)
                   :exception (:exception result)
                   :duration-ms (- (System/currentTimeMillis) start-time)})))))))))

;; =============================================================================
;; Mutation Evaluation
;; =============================================================================

(defn- determine-mutation-status
  "Determine mutation status from test run results.

   - :killed if any test failed or errored (mutation was detected)
   - :survived if all tests passed (mutation escaped detection)"
  [test-result]
  (let [{:keys [fail error]} (:results test-result)]
    (if (or (pos? (or fail 0))
            (pos? (or error 0)))
      :killed
      :survived)))

(defn evaluate-mutation
  "Full mutation evaluation lifecycle.

   1. Look up tests that cover this mutation
   2. Execute those tests with timeout
   3. Determine if mutation was killed or survived

   Arguments:
   - index: Coverage index
   - mutation: Mutation record with :form-id, :coord, :operator, etc.
   - config: Configuration map with :timeout-ms

   Returns MutationResult:
   {:mutation <mutation-record>
    :status :killed/:survived/:no-coverage/:timeout/:error
    :tests-run #{test-sym1 test-sym2}
    :duration-ms 150}"
  [index mutation config]
  (let [timeout-ms (or (:timeout-ms config) 5000)
        tests (tests-for-mutation index mutation)]
    (if (empty? tests)
      ;; No tests cover this mutation
      {:mutation mutation
       :status :no-coverage
       :tests-run #{}
       :duration-ms 0}
      ;; Run the tests
      (let [test-result (run-tests tests timeout-ms)]
        {:mutation mutation
         :status (case (:status test-result)
                   :timeout :timeout
                   :error :error
                   :no-tests :no-coverage
                   :completed (determine-mutation-status test-result))
         :tests-run (:tests-run test-result)
         :duration-ms (:duration-ms test-result)}))))

;; =============================================================================
;; Batch Evaluation
;; =============================================================================

(defn evaluate-mutations
  "Evaluate multiple mutations, returning results for each.

   Arguments:
   - index: Coverage index
   - mutations: Sequence of mutation records
   - config: Configuration map

   Returns sequence of MutationResult maps."
  [index mutations config]
  (mapv #(evaluate-mutation index % config) mutations))

(defn summarize-results
  "Summarize mutation evaluation results.

   Returns:
   {:total n
    :killed n
    :survived n
    :no-coverage n
    :timeout n
    :error n
    :mutation-score 0.75  ; killed / (killed + survived)
    :total-duration-ms n}"
  [results]
  (let [by-status (group-by :status results)
        killed (count (get by-status :killed []))
        survived (count (get by-status :survived []))
        testable (+ killed survived)]
    {:total (count results)
     :killed killed
     :survived survived
     :no-coverage (count (get by-status :no-coverage []))
     :timeout (count (get by-status :timeout []))
     :error (count (get by-status :error []))
     :mutation-score (if (pos? testable)
                       (double (/ killed testable))
                       1.0)  ; No testable mutations = perfect score
     :total-duration-ms (reduce + 0 (map :duration-ms results))}))
