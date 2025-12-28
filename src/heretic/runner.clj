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
        (catch AssertionError _e
          ;; clojure.test uses AssertionError for failures
          (swap! results update :fail inc))
        (catch Exception _e
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
  "Execute a set of tests with independent timeout handling.

   Per-test timeout: Each test has its own timeout. A timeout in one test
   does NOT stop execution of remaining tests.

   Budget timeout: Optional total time budget. If exceeded, remaining tests
   are skipped but results from completed tests are returned.

   Arguments:
   - test-syms: Collection of test symbols to run
   - opts: Either a number (timeout-ms for backwards compat) or options map:
     - :timeout-ms - Per-test timeout in milliseconds (default 5000)
     - :budget-ms - Total time budget for all tests (optional, nil = unlimited)

   Returns:
   {:status :completed/:partial/:budget-exhausted/:no-tests
    :results {:pass n :fail n :error n}  ; aggregate counts
    :tests-run #{test-syms...}
    :timed-out #{test-syms...}  ; tests that timed out individually
    :any-failed boolean  ; true if any test failed or errored
    :any-timeout boolean  ; true if any test timed out
    :duration-ms n}"
  ([test-syms timeout-ms]
   (if (map? timeout-ms)
     (run-tests test-syms timeout-ms :internal)
     (run-tests test-syms {:timeout-ms timeout-ms} :internal)))
  ([test-syms opts _]
   (let [{:keys [timeout-ms budget-ms] :or {timeout-ms 5000}} opts
         start-time (System/currentTimeMillis)
         test-syms-set (set test-syms)]
     (if (empty? test-syms-set)
       {:status :no-tests
        :results {:pass 0 :fail 0 :error 0}
        :tests-run #{}
        :timed-out #{}
        :any-failed false
        :any-timeout false
        :duration-ms 0}
       (loop [remaining (seq test-syms-set)
              aggregate {:pass 0 :fail 0 :error 0}
              ran #{}
              timed-out #{}]
         (let [elapsed (- (System/currentTimeMillis) start-time)]
           (cond
             ;; Budget exhausted - stop running more tests
             (and budget-ms (>= elapsed budget-ms))
             {:status :budget-exhausted
              :results aggregate
              :tests-run ran
              :timed-out timed-out
              :skipped (set remaining)
              :any-failed (or (pos? (:fail aggregate 0)) (pos? (:error aggregate 0)))
              :any-timeout (boolean (seq timed-out))
              :duration-ms elapsed}

             ;; No more tests to run
             (not remaining)
             {:status (if (seq timed-out) :partial :completed)
              :results aggregate
              :tests-run ran
              :timed-out timed-out
              :any-failed (or (pos? (:fail aggregate 0)) (pos? (:error aggregate 0)))
              :any-timeout (boolean (seq timed-out))
              :duration-ms elapsed}

             :else
             ;; Run next test
             (let [test-sym (first remaining)
                   test-var (resolve-test-var test-sym)]
               (if-not test-var
                 ;; Var not found, skip and continue
                 (recur (next remaining) aggregate ran timed-out)
                 ;; Run the test
                 (let [result (run-test-with-timeout test-var timeout-ms)]
                   (case (:status result)
                     ;; Timeout: record it but continue with other tests
                     :timeout
                     (recur (next remaining)
                            aggregate
                            (conj ran test-sym)
                            (conj timed-out test-sym))

                     ;; Completed: aggregate results and continue
                     :completed
                     (recur (next remaining)
                            (merge-with + aggregate (:results result))
                            (conj ran test-sym)
                            timed-out)

                     ;; Error: record error and continue with other tests
                     (recur (next remaining)
                            (update aggregate :error inc)
                            (conj ran test-sym)
                            timed-out))))))))))))

;; =============================================================================
;; Mutation Evaluation
;; =============================================================================

(defn- determine-mutation-status
  "Determine mutation status from test run results.

   - :killed if any test failed or errored (mutation was detected)
   - :survived if all tests passed (mutation escaped detection)

   Uses the :any-failed field from run-tests for efficiency."
  [test-result]
  (if (:any-failed test-result)
    :killed
    :survived))

(defn evaluate-mutation
  "Full mutation evaluation lifecycle.

   1. Look up tests that cover this mutation
   2. Execute those tests with timeout
   3. Determine if mutation was killed or survived

   Arguments:
   - index: Coverage index
   - mutation: Mutation record with :form-id, :coord, :operator, etc.
   - config: Configuration map with:
     - :timeout-ms - Per-test timeout (default 5000)
     - :budget-ms - Total time budget per mutation (optional)

   Returns MutationResult:
   {:mutation <mutation-record>
    :status :killed/:survived/:no-coverage/:timeout/:error
    :tests-run #{test-sym1 test-sym2}
    :timed-out #{test-sym...}  ; tests that individually timed out
    :duration-ms 150}"
  [index mutation config]
  (let [timeout-ms (or (:timeout-ms config) 5000)
        budget-ms (:budget-ms config)
        tests (tests-for-mutation index mutation)]
    (if (empty? tests)
      ;; No tests cover this mutation
      {:mutation mutation
       :status :no-coverage
       :tests-run #{}
       :timed-out #{}
       :duration-ms 0}
      ;; Run the tests with timeout options
      (let [test-result (run-tests tests {:timeout-ms timeout-ms
                                          :budget-ms budget-ms})]
        {:mutation mutation
         :status (case (:status test-result)
                   ;; All tests completed - check if mutation was killed
                   :completed (determine-mutation-status test-result)
                   ;; Some tests timed out but we got results - check kills
                   :partial (if (:any-failed test-result)
                              :killed
                              :timeout)
                   ;; Budget exhausted - check if we have enough data
                   :budget-exhausted (if (:any-failed test-result)
                                       :killed
                                       :timeout)
                   ;; No tests to run
                   :no-tests :no-coverage)
         :tests-run (:tests-run test-result)
         :timed-out (:timed-out test-result #{})
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
    :tests-timed-out n  ; total individual tests that timed out
    :mutation-score 0.75  ; killed / (killed + survived)
    :total-duration-ms n}"
  [results]
  (let [by-status (group-by :status results)
        killed (count (get by-status :killed []))
        survived (count (get by-status :survived []))
        testable (+ killed survived)
        tests-timed-out (reduce + 0 (map #(count (:timed-out % #{})) results))]
    {:total (count results)
     :killed killed
     :survived survived
     :no-coverage (count (get by-status :no-coverage []))
     :timeout (count (get by-status :timeout []))
     :error (count (get by-status :error []))
     :tests-timed-out tests-timed-out
     :mutation-score (if (pos? testable)
                       (double (/ killed testable))
                       1.0)  ; No testable mutations = perfect score
     :total-duration-ms (reduce + 0 (map :duration-ms results))}))
