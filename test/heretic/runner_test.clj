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

;; =============================================================================
;; Timeout Edge Cases Tests
;; =============================================================================

;; Additional mock test symbols
(def fast-test-sym 'heretic.fixtures.mock-tests/fast-test)
(def medium-slow-test-sym 'heretic.fixtures.mock-tests/medium-slow-test)
(def slow-failing-test-sym 'heretic.fixtures.mock-tests/slow-failing-test)
(def slow-erroring-test-sym 'heretic.fixtures.mock-tests/slow-erroring-test)
(def boundary-test-150ms-sym 'heretic.fixtures.mock-tests/boundary-test-150ms)
(def cancellation-tracking-test-sym 'heretic.fixtures.mock-tests/cancellation-tracking-test)

(deftest run-tests-timeout-boundary-test
  (testing "Test that takes exactly timeout duration"
    (testing "completes when timeout is slightly longer than test duration"
      ;; boundary-test-150ms takes 150ms, give it 250ms timeout
      (let [result (runner/run-tests [boundary-test-150ms-sym] 250)]
        (is (= :completed (:status result)))
        (is (= #{boundary-test-150ms-sym} (:tests-run result)))
        (is (pos? (get-in result [:results :pass])))))

    (testing "times out when timeout is shorter than test duration"
      ;; boundary-test-150ms takes 150ms, give it 50ms timeout
      (let [result (runner/run-tests [boundary-test-150ms-sym] 50)]
        (is (= :timeout (:status result)))
        (is (= boundary-test-150ms-sym (:failed-test result)))))))

(deftest run-tests-mixed-timeout-test
  (testing "Multiple tests where some complete and some timeout"
    (testing "fast tests complete before slow test times out"
      ;; Run fast tests first, then a slow one that will timeout
      ;; Order may vary since tests are in a set, but the slow one should timeout
      (let [result (runner/run-tests [fast-test-sym slow-test-sym] 100)]
        ;; One of the tests should timeout
        (is (= :timeout (:status result)))
        ;; The slow test should be the one that failed
        (is (= slow-test-sym (:failed-test result)))))

    (testing "medium slow tests complete, very slow test times out"
      ;; medium-slow-test takes 200ms, slow-test takes 5000ms
      ;; With 300ms timeout, medium-slow should complete, slow should timeout
      (let [result (runner/run-tests [medium-slow-test-sym slow-test-sym] 300)]
        (is (= :timeout (:status result)))
        (is (= slow-test-sym (:failed-test result)))
        ;; medium-slow-test should have completed
        (is (contains? (:tests-run result) medium-slow-test-sym))))))

(deftest run-tests-timeout-with-passing-tests-test
  (testing "Partial results accumulated before timeout"
    ;; Run multiple passing tests followed by a slow one
    (let [result (runner/run-tests [passing-test-sym fast-test-sym slow-test-sym] 100)]
      (is (= :timeout (:status result)))
      ;; Should have accumulated some passing results before timeout
      ;; The exact count depends on execution order, but results should exist
      (is (map? (:results result)))
      (is (contains? (:results result) :pass)))))

(deftest run-tests-timeout-with-failing-tests-test
  (testing "Timeout with mix of passing and failing tests before slow test"
    ;; If a failing test runs before the slow test, results should contain failures
    (let [result (runner/run-tests [failing-test-sym slow-test-sym] 100)]
      (is (= :timeout (:status result)))
      ;; Results should have been accumulated
      (is (map? (:results result))))))

(deftest run-tests-timeout-with-erroring-tests-test
  (testing "Timeout with erroring tests in the batch"
    (let [result (runner/run-tests [erroring-test-sym slow-test-sym] 100)]
      (is (= :timeout (:status result)))
      (is (map? (:results result))))))

(deftest run-tests-future-cancellation-test
  (testing "Timed out futures are properly cancelled"
    ;; Run a slow test with short timeout
    (let [start-time (System/currentTimeMillis)
          result (runner/run-tests [slow-test-sym] 100)
          elapsed (- (System/currentTimeMillis) start-time)]
      (is (= :timeout (:status result)))
      ;; The test should return quickly (not wait 5 seconds for the slow test)
      ;; Allow some buffer for test overhead
      (is (< elapsed 1000) "Future should be cancelled promptly after timeout"))))

(deftest run-tests-slow-failing-test-timeout-test
  (testing "Slow failing test that times out before failure"
    ;; slow-failing-test takes 500ms then fails
    ;; With 100ms timeout, it should timeout before the failure
    (let [result (runner/run-tests [slow-failing-test-sym] 100)]
      (is (= :timeout (:status result)))
      (is (= slow-failing-test-sym (:failed-test result))))))

(deftest run-tests-slow-erroring-test-timeout-test
  (testing "Slow erroring test that times out before error"
    ;; slow-erroring-test takes 500ms then throws
    ;; With 100ms timeout, it should timeout before the error
    (let [result (runner/run-tests [slow-erroring-test-sym] 100)]
      (is (= :timeout (:status result)))
      (is (= slow-erroring-test-sym (:failed-test result))))))

(deftest run-tests-all-tests-complete-within-timeout-test
  (testing "All tests complete when timeout is sufficient"
    (let [result (runner/run-tests [fast-test-sym medium-slow-test-sym passing-test-sym] 500)]
      (is (= :completed (:status result)))
      (is (= 3 (count (:tests-run result))))
      ;; All should pass
      (is (= 3 (get-in result [:results :pass]))))))

(deftest run-tests-timeout-duration-tracking-test
  (testing "Duration is tracked correctly on timeout"
    (let [result (runner/run-tests [slow-test-sym] 100)]
      (is (= :timeout (:status result)))
      (is (number? (:duration-ms result)))
      ;; Duration should be approximately the timeout value (with some overhead)
      (is (>= (:duration-ms result) 100))
      (is (< (:duration-ms result) 500)))))

(deftest evaluate-mutations-partial-timeout-test
  (testing "Batch evaluation with some mutations timing out"
    (let [index {:coord-to-tests {[100 [0]] #{passing-test-sym}
                                   [101 [0]] #{slow-test-sym}
                                   [102 [0]] #{failing-test-sym}}
                 :form-to-tests {100 #{passing-test-sym}
                                 101 #{slow-test-sym}
                                 102 #{failing-test-sym}}}
          mutations [{:form-id 100 :coord [0]}   ; will pass (survived)
                     {:form-id 101 :coord [0]}   ; will timeout
                     {:form-id 102 :coord [0]}]  ; will fail (killed)
          results (runner/evaluate-mutations index mutations {:timeout-ms 100})]
      (is (= 3 (count results)))
      (is (= :survived (:status (nth results 0))))
      (is (= :timeout (:status (nth results 1))))
      (is (= :killed (:status (nth results 2)))))))

(deftest evaluate-mutations-all-timeout-test
  (testing "Batch evaluation where all mutations timeout"
    (let [index {:coord-to-tests {[100 [0]] #{slow-test-sym}
                                   [101 [0]] #{slow-test-sym}}
                 :form-to-tests {100 #{slow-test-sym}
                                 101 #{slow-test-sym}}}
          mutations [{:form-id 100 :coord [0]}
                     {:form-id 101 :coord [0]}]
          results (runner/evaluate-mutations index mutations {:timeout-ms 50})]
      (is (= 2 (count results)))
      (is (every? #(= :timeout (:status %)) results)))))

(deftest summarize-results-many-timeouts-test
  (testing "Summary correctly counts multiple timeouts"
    (let [results [{:status :killed :duration-ms 50}
                   {:status :timeout :duration-ms 100}
                   {:status :timeout :duration-ms 100}
                   {:status :timeout :duration-ms 100}
                   {:status :survived :duration-ms 50}]
          summary (runner/summarize-results results)]
      (is (= 5 (:total summary)))
      (is (= 1 (:killed summary)))
      (is (= 1 (:survived summary)))
      (is (= 3 (:timeout summary)))
      ;; Mutation score only considers killed/survived
      ;; 1 killed / (1 killed + 1 survived) = 0.5
      (is (= 0.5 (:mutation-score summary)))
      (is (= 400 (:total-duration-ms summary))))))
