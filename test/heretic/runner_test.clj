(ns heretic.runner-test
  "Tests for heretic.runner mutation test execution.

   Tests cover:
   - tests-for-mutation: Test lookup via coverage index
   - run-tests: Test execution with timeout handling
   - evaluate-mutation: Full mutation evaluation lifecycle
   - summarize-results: Result aggregation"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.fixtures.mock-tests]  ; Load mock tests
            [heretic.runner :as runner]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

;; Mock coverage index for testing
(def mock-index
  {:coord-to-tests {[123 [0]] #{'test.ns/test-add 'test.ns/test-math}
                    [123 [1]] #{'test.ns/test-add}
                    [456 [0]] #{'test.ns/test-other}}
   :form-to-tests {123 #{'test.ns/test-add 'test.ns/test-math}
                   456 #{'test.ns/test-other}}})

;; Symbols for our mock tests (in separate namespace to avoid Kaocha pickup)
(def passing-test-sym 'heretic.fixtures.mock-tests/passing-test)
(def failing-test-sym 'heretic.fixtures.mock-tests/failing-test)
(def erroring-test-sym 'heretic.fixtures.mock-tests/erroring-test)
(def slow-test-sym 'heretic.fixtures.mock-tests/slow-test)

;; =============================================================================
;; tests-for-mutation Tests
;; =============================================================================

(deftest tests-for-mutation-with-coord-test
  (testing "Returns tests for specific coordinate"
    (let [mutation {:form-id 123 :coord [0]}
          result (runner/tests-for-mutation mock-index mutation)]
      (is (= #{'test.ns/test-add 'test.ns/test-math} result)))))

(deftest tests-for-mutation-with-different-coord-test
  (testing "Different coords return different tests"
    (let [mutation {:form-id 123 :coord [1]}
          result (runner/tests-for-mutation mock-index mutation)]
      (is (= #{'test.ns/test-add} result)))))

(deftest tests-for-mutation-without-coord-test
  (testing "Without coord, returns all tests for form"
    (let [mutation {:form-id 123}
          result (runner/tests-for-mutation mock-index mutation)]
      (is (= #{'test.ns/test-add 'test.ns/test-math} result)))))

(deftest tests-for-mutation-no-coverage-test
  (testing "Returns empty set for uncovered mutations"
    (let [mutation {:form-id 999 :coord [0]}
          result (runner/tests-for-mutation mock-index mutation)]
      (is (= #{} result)))))

;; =============================================================================
;; run-tests Tests
;; =============================================================================

(deftest run-tests-empty-test
  (testing "Empty test set returns no-tests status"
    (let [result (runner/run-tests [] 5000)]
      (is (= :no-tests (:status result)))
      (is (= #{} (:tests-run result)))
      (is (= {:pass 0 :fail 0 :error 0} (:results result))))))

(deftest run-tests-passing-test
  (testing "Passing tests return completed status with pass count"
    (let [result (runner/run-tests [passing-test-sym] 5000)]
      (is (= :completed (:status result)))
      (is (= #{passing-test-sym} (:tests-run result)))
      (is (pos? (get-in result [:results :pass]))))))

(deftest run-tests-failing-test
  (testing "Failing tests return completed status with fail count"
    (let [result (runner/run-tests [failing-test-sym] 5000)]
      (is (= :completed (:status result)))
      (is (= #{failing-test-sym} (:tests-run result)))
      (is (pos? (get-in result [:results :fail]))))))

(deftest run-tests-erroring-test
  (testing "Erroring tests return completed status with error count"
    (let [result (runner/run-tests [erroring-test-sym] 5000)]
      (is (= :completed (:status result)))
      (is (= #{erroring-test-sym} (:tests-run result)))
      (is (pos? (get-in result [:results :error]))))))

(deftest run-tests-multiple-test
  (testing "Multiple tests aggregate results"
    (let [result (runner/run-tests [passing-test-sym failing-test-sym] 5000)]
      (is (= :completed (:status result)))
      (is (= 2 (count (:tests-run result))))
      (is (pos? (get-in result [:results :pass])))
      (is (pos? (get-in result [:results :fail]))))))

(deftest run-tests-timeout-test
  (testing "Slow tests timeout correctly"
    (let [result (runner/run-tests [slow-test-sym] 100)]
      (is (= :timeout (:status result)))
      (is (= slow-test-sym (:failed-test result))))))

(deftest run-tests-nonexistent-var-test
  (testing "Nonexistent vars are skipped"
    (let [result (runner/run-tests ['nonexistent.ns/fake-test passing-test-sym] 5000)]
      (is (= :completed (:status result)))
      ;; Only the passing test was run
      (is (= #{passing-test-sym} (:tests-run result))))))

(deftest run-tests-duration-tracking-test
  (testing "Duration is tracked"
    (let [result (runner/run-tests [passing-test-sym] 5000)]
      (is (number? (:duration-ms result)))
      (is (>= (:duration-ms result) 0)))))

;; =============================================================================
;; evaluate-mutation Tests
;; =============================================================================

(deftest evaluate-mutation-no-coverage-test
  (testing "Mutation with no coverage returns :no-coverage"
    (let [mutation {:form-id 999 :coord [0] :operator :swap-plus-minus}
          result (runner/evaluate-mutation mock-index mutation {:timeout-ms 5000})]
      (is (= :no-coverage (:status result)))
      (is (= mutation (:mutation result)))
      (is (= #{} (:tests-run result))))))

(deftest evaluate-mutation-killed-test
  (testing "Mutation is killed when tests fail"
    ;; Create a mock index that maps to our failing test
    (let [index {:coord-to-tests {[100 [0]] #{failing-test-sym}}
                 :form-to-tests {100 #{failing-test-sym}}}
          mutation {:form-id 100 :coord [0] :operator :swap-plus-minus}
          result (runner/evaluate-mutation index mutation {:timeout-ms 5000})]
      (is (= :killed (:status result)))
      (is (= #{failing-test-sym} (:tests-run result))))))

(deftest evaluate-mutation-survived-test
  (testing "Mutation survives when all tests pass"
    ;; Create a mock index that maps to our passing test
    (let [index {:coord-to-tests {[100 [0]] #{passing-test-sym}}
                 :form-to-tests {100 #{passing-test-sym}}}
          mutation {:form-id 100 :coord [0] :operator :swap-plus-minus}
          result (runner/evaluate-mutation index mutation {:timeout-ms 5000})]
      (is (= :survived (:status result)))
      (is (= #{passing-test-sym} (:tests-run result))))))

(deftest evaluate-mutation-timeout-test
  (testing "Mutation returns :timeout when test times out"
    (let [index {:coord-to-tests {[100 [0]] #{slow-test-sym}}
                 :form-to-tests {100 #{slow-test-sym}}}
          mutation {:form-id 100 :coord [0] :operator :swap-plus-minus}
          result (runner/evaluate-mutation index mutation {:timeout-ms 100})]
      (is (= :timeout (:status result))))))

(deftest evaluate-mutation-default-timeout-test
  (testing "Default timeout is 5000ms when not specified"
    (let [index {:coord-to-tests {[100 [0]] #{passing-test-sym}}
                 :form-to-tests {100 #{passing-test-sym}}}
          mutation {:form-id 100 :coord [0]}
          result (runner/evaluate-mutation index mutation {})]
      ;; Should complete without timeout
      (is (= :survived (:status result))))))

;; =============================================================================
;; evaluate-mutations (batch) Tests
;; =============================================================================

(deftest evaluate-mutations-batch-test
  (testing "Batch evaluation returns results for each mutation"
    (let [index {:coord-to-tests {[100 [0]] #{passing-test-sym}
                                  [101 [0]] #{failing-test-sym}}
                 :form-to-tests {100 #{passing-test-sym}
                                 101 #{failing-test-sym}}}
          mutations [{:form-id 100 :coord [0]}
                     {:form-id 101 :coord [0]}
                     {:form-id 999 :coord [0]}]  ; no coverage
          results (runner/evaluate-mutations index mutations {:timeout-ms 5000})]
      (is (= 3 (count results)))
      (is (= :survived (:status (nth results 0))))
      (is (= :killed (:status (nth results 1))))
      (is (= :no-coverage (:status (nth results 2)))))))

;; =============================================================================
;; summarize-results Tests
;; =============================================================================

(deftest summarize-results-empty-test
  (testing "Empty results give perfect score"
    (let [summary (runner/summarize-results [])]
      (is (= 0 (:total summary)))
      (is (= 1.0 (:mutation-score summary))))))

(deftest summarize-results-all-killed-test
  (testing "All killed gives perfect score"
    (let [results [{:status :killed :duration-ms 100}
                   {:status :killed :duration-ms 100}]
          summary (runner/summarize-results results)]
      (is (= 2 (:total summary)))
      (is (= 2 (:killed summary)))
      (is (= 0 (:survived summary)))
      (is (= 1.0 (:mutation-score summary)))
      (is (= 200 (:total-duration-ms summary))))))

(deftest summarize-results-all-survived-test
  (testing "All survived gives zero score"
    (let [results [{:status :survived :duration-ms 50}
                   {:status :survived :duration-ms 50}]
          summary (runner/summarize-results results)]
      (is (= 2 (:total summary)))
      (is (= 0 (:killed summary)))
      (is (= 2 (:survived summary)))
      (is (= 0.0 (:mutation-score summary))))))

(deftest summarize-results-mixed-test
  (testing "Mixed results give correct score"
    (let [results [{:status :killed :duration-ms 100}
                   {:status :survived :duration-ms 100}
                   {:status :killed :duration-ms 100}
                   {:status :survived :duration-ms 100}]
          summary (runner/summarize-results results)]
      (is (= 4 (:total summary)))
      (is (= 2 (:killed summary)))
      (is (= 2 (:survived summary)))
      (is (= 0.5 (:mutation-score summary))))))

(deftest summarize-results-with-uncovered-test
  (testing "No-coverage mutations don't affect mutation score"
    (let [results [{:status :killed :duration-ms 100}
                   {:status :no-coverage :duration-ms 0}
                   {:status :no-coverage :duration-ms 0}]
          summary (runner/summarize-results results)]
      (is (= 3 (:total summary)))
      (is (= 1 (:killed summary)))
      (is (= 2 (:no-coverage summary)))
      ;; Only 1 testable mutation, which was killed
      (is (= 1.0 (:mutation-score summary))))))

(deftest summarize-results-with-timeouts-test
  (testing "Timeouts are counted separately"
    (let [results [{:status :killed :duration-ms 100}
                   {:status :timeout :duration-ms 5000}
                   {:status :error :duration-ms 50}]
          summary (runner/summarize-results results)]
      (is (= 3 (:total summary)))
      (is (= 1 (:killed summary)))
      (is (= 1 (:timeout summary)))
      (is (= 1 (:error summary)))
      (is (= 5150 (:total-duration-ms summary))))))

(deftest summarize-results-score-calculation-test
  (testing "Mutation score = killed / (killed + survived)"
    (let [results [{:status :killed :duration-ms 0}
                   {:status :killed :duration-ms 0}
                   {:status :killed :duration-ms 0}
                   {:status :survived :duration-ms 0}]
          summary (runner/summarize-results results)]
      ;; 3 killed / (3 killed + 1 survived) = 0.75
      (is (= 0.75 (:mutation-score summary))))))
