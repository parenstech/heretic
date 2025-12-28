(ns heretic.reporter-test
  "Tests for heretic.reporter calculation functions.

   Tests cover:
   - count-by-status: counting mutations by status category
   - mutation-score: score calculation with various inputs
   - survivors/killed/etc: filtering functions
   - summary-data: data export"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.reporter :as reporter]))

;; =============================================================================
;; Test Data Fixtures
;; =============================================================================

(def sample-mutation
  "A sample mutation for testing."
  {:id #uuid "550e8400-e29b-41d4-a716-446655440000"
   :file "src/my/app.clj"
   :form-id 12345678
   :coord "3,0"
   :operator :swap-plus-minus
   :original "+"
   :replacement "-"
   :line 42
   :column 10})

(defn make-result
  "Create a mutation result with the given status."
  ([status]
   (make-result status sample-mutation))
  ([status mutation]
   {:mutation mutation
    :status status
    :tests-run #{'my.app-test/test-add}
    :duration-ms 150}))

(def empty-results [])

(def all-killed-results
  [(make-result :killed)
   (make-result :killed)
   (make-result :killed)])

(def all-survived-results
  [(make-result :survived)
   (make-result :survived)])

(def mixed-results
  "Mixed results with various statuses."
  [(make-result :killed)
   (make-result :killed)
   (make-result :killed)
   (make-result :survived)
   (make-result :survived)
   (make-result :no-coverage)
   (make-result :timeout)
   (make-result :error)])

;; =============================================================================
;; count-by-status Tests
;; =============================================================================

(deftest count-by-status-empty-test
  (testing "empty results return zero counts"
    (let [counts (reporter/count-by-status empty-results)]
      (is (= 0 (:killed counts)))
      (is (= 0 (:survived counts)))
      (is (= 0 (:no-coverage counts)))
      (is (= 0 (:timeout counts)))
      (is (= 0 (:error counts))))))

(deftest count-by-status-all-killed-test
  (testing "all killed mutations"
    (let [counts (reporter/count-by-status all-killed-results)]
      (is (= 3 (:killed counts)))
      (is (= 0 (:survived counts))))))

(deftest count-by-status-mixed-test
  (testing "mixed status results"
    (let [counts (reporter/count-by-status mixed-results)]
      (is (= 3 (:killed counts)))
      (is (= 2 (:survived counts)))
      (is (= 1 (:no-coverage counts)))
      (is (= 1 (:timeout counts)))
      (is (= 1 (:error counts))))))

;; =============================================================================
;; mutation-score Tests
;; =============================================================================

(deftest mutation-score-empty-test
  (testing "empty results return nil"
    (is (nil? (reporter/mutation-score empty-results)))))

(deftest mutation-score-all-killed-test
  (testing "all killed mutations return 1.0 (100% score)"
    (is (= 1.0 (reporter/mutation-score all-killed-results)))))

(deftest mutation-score-all-survived-test
  (testing "all survived mutations return 0.0 (0% score)"
    (is (= 0.0 (reporter/mutation-score all-survived-results)))))

(deftest mutation-score-mixed-test
  (testing "mixed killed/survived returns correct ratio"
    ;; 3 killed, 2 survived = 3/5 = 0.6
    (let [score (reporter/mutation-score mixed-results)]
      (is (= 0.6 score)))))

(deftest mutation-score-excludes-non-killable-test
  (testing "no-coverage, timeout, and error don't affect score"
    ;; Results with only no-coverage/timeout/error should return nil
    (let [non-killable [(make-result :no-coverage)
                        (make-result :timeout)
                        (make-result :error)]]
      (is (nil? (reporter/mutation-score non-killable))
          "Score should be nil when no killable mutations exist"))))

(deftest mutation-score-precise-calculation-test
  (testing "score calculation is precise"
    (let [results (concat (repeat 7 (make-result :killed))
                          (repeat 3 (make-result :survived)))]
      (is (= 0.7 (reporter/mutation-score results))))))

;; =============================================================================
;; Filtering Function Tests
;; =============================================================================

(deftest survivors-filter-test
  (testing "survivors returns only survived mutations"
    (let [result (reporter/survivors mixed-results)]
      (is (= 2 (count result)))
      (is (every? #(= :survived (:status %)) result)))))

(deftest killed-filter-test
  (testing "killed returns only killed mutations"
    (let [result (reporter/killed mixed-results)]
      (is (= 3 (count result)))
      (is (every? #(= :killed (:status %)) result)))))

(deftest no-coverage-filter-test
  (testing "no-coverage returns only no-coverage mutations"
    (let [result (reporter/no-coverage mixed-results)]
      (is (= 1 (count result)))
      (is (every? #(= :no-coverage (:status %)) result)))))

(deftest timeouts-filter-test
  (testing "timeouts returns only timeout mutations"
    (let [result (reporter/timeouts mixed-results)]
      (is (= 1 (count result)))
      (is (every? #(= :timeout (:status %)) result)))))

(deftest errors-filter-test
  (testing "errors returns only error mutations"
    (let [result (reporter/errors mixed-results)]
      (is (= 1 (count result)))
      (is (every? #(= :error (:status %)) result)))))

(deftest filters-empty-results-test
  (testing "all filters return empty seq for empty results"
    (is (empty? (reporter/survivors empty-results)))
    (is (empty? (reporter/killed empty-results)))
    (is (empty? (reporter/no-coverage empty-results)))
    (is (empty? (reporter/timeouts empty-results)))
    (is (empty? (reporter/errors empty-results)))))

;; =============================================================================
;; summary-data Tests
;; =============================================================================

(deftest summary-data-empty-test
  (testing "summary-data with empty results"
    (let [data (reporter/summary-data empty-results)]
      (is (= 0 (:total data)))
      (is (= {:killed 0 :survived 0 :no-coverage 0 :timeout 0 :error 0}
             (:counts data)))
      (is (nil? (:score data)))
      (is (nil? (:score-percentage data))))))

(deftest summary-data-mixed-test
  (testing "summary-data with mixed results"
    (let [data (reporter/summary-data mixed-results)]
      (is (= 8 (:total data)))
      (is (= 3 (get-in data [:counts :killed])))
      (is (= 2 (get-in data [:counts :survived])))
      (is (= 0.6 (:score data)))
      (is (= 60.0 (:score-percentage data))))))

(deftest summary-data-all-killed-test
  (testing "summary-data with perfect score"
    (let [data (reporter/summary-data all-killed-results)]
      (is (= 3 (:total data)))
      (is (= 1.0 (:score data)))
      (is (= 100.0 (:score-percentage data))))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest single-killed-mutation-test
  (testing "single killed mutation has 100% score"
    (let [results [(make-result :killed)]]
      (is (= 1.0 (reporter/mutation-score results))))))

(deftest single-survived-mutation-test
  (testing "single survived mutation has 0% score"
    (let [results [(make-result :survived)]]
      (is (= 0.0 (reporter/mutation-score results))))))

(deftest many-mutations-test
  (testing "score calculation with many mutations"
    (let [results (concat (repeat 850 (make-result :killed))
                          (repeat 150 (make-result :survived)))]
      (is (= 0.85 (reporter/mutation-score results))))))

(deftest mixed-with-only-non-killable-test
  (testing "only non-killable statuses return nil score"
    (let [results [(make-result :no-coverage)
                   (make-result :no-coverage)
                   (make-result :timeout)]]
      (is (nil? (reporter/mutation-score results))))))

(deftest results-with-different-mutations-test
  (testing "results with different mutation data"
    (let [mutation1 (assoc sample-mutation :line 10 :operator :swap-plus-minus)
          mutation2 (assoc sample-mutation :line 20 :operator :negate-conditional)
          mutation3 (assoc sample-mutation :line 30 :operator :return-null)
          results [(make-result :killed mutation1)
                   (make-result :survived mutation2)
                   (make-result :killed mutation3)]
          survivors (reporter/survivors results)]
      (is (= 1 (count survivors)))
      (is (= 20 (get-in (first survivors) [:mutation :line]))))))
