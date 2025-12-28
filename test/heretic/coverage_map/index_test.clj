(ns heretic.coverage-map.index-test
  "Tests for pure coverage index functions.

   Tests verify:
   - Building inverse index from coverage data
   - Form-to-tests aggregation
   - Query functions for test lookup
   - Coverage statistics calculation"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.coverage-map.index :as index]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-coverage-files
  "Sample coverage files mimicking persisted coverage data."
  [{:test-ns 'my.app-test
    :coverage {'my.app-test/test-add
               {12345 #{"0" "0,0" "0,1"}}
               'my.app-test/test-sub
               {12345 #{"0" "0,2"}
                12346 #{"0"}}}}
   {:test-ns 'my.other-test
    :coverage {'my.other-test/test-mult
               {12346 #{"0" "0,0" "0,1"}}}}])

(def sample-forms
  "Sample forms data for coverage stats."
  {12345 {:form/emitted-coords #{"0" "0,0" "0,1" "0,2" "0,3"}}
   12346 {:form/emitted-coords #{"0" "0,0" "0,1"}}})

;; =============================================================================
;; build-inverse-index Tests
;; =============================================================================

(deftest test-build-inverse-index-basic
  (testing "Builds index from coverage files"
    (let [idx (index/build-inverse-index sample-coverage-files)]
      ;; Form 12345 coords should have tests
      (is (= #{'my.app-test/test-add 'my.app-test/test-sub}
             (get idx [12345 "0"])))
      (is (= #{'my.app-test/test-add}
             (get idx [12345 "0,0"])))
      (is (= #{'my.app-test/test-add}
             (get idx [12345 "0,1"])))
      (is (= #{'my.app-test/test-sub}
             (get idx [12345 "0,2"])))
      ;; Form 12346 coords
      (is (= #{'my.app-test/test-sub 'my.other-test/test-mult}
             (get idx [12346 "0"]))))))

(deftest test-build-inverse-index-empty
  (testing "Returns empty map for empty coverage"
    (is (= {} (index/build-inverse-index [])))))

(deftest test-build-inverse-index-no-coverage
  (testing "Returns empty map for files with no coverage"
    (is (= {} (index/build-inverse-index [{:test-ns 'empty-test :coverage {}}])))))

;; =============================================================================
;; build-form-to-tests Tests
;; =============================================================================

(deftest test-build-form-to-tests-basic
  (testing "Aggregates tests by form-id"
    (let [coord-to-tests {[12345 "0"] #{'test-a 'test-b}
                          [12345 "0,0"] #{'test-a}
                          [12346 "0"] #{'test-c}}
          form-to-tests (index/build-form-to-tests coord-to-tests)]
      (is (= #{'test-a 'test-b} (get form-to-tests 12345)))
      (is (= #{'test-c} (get form-to-tests 12346))))))

(deftest test-build-form-to-tests-empty
  (testing "Returns empty map for empty input"
    (is (= {} (index/build-form-to-tests {})))))

;; =============================================================================
;; build-index Tests
;; =============================================================================

(deftest test-build-index-complete
  (testing "Builds complete index structure"
    (let [idx (index/build-index sample-coverage-files {})]
      (is (contains? idx :coord-to-tests))
      (is (contains? idx :form-to-tests))
      (is (contains? idx :form-location-index))
      (is (contains? idx :included-test-ns))
      (is (contains? idx :rebuilt-at))
      ;; Empty map {} when no form-location-index provided
      (is (= {} (:form-location-index idx)))
      (is (= #{'my.app-test 'my.other-test} (:included-test-ns idx))))))

(deftest test-build-index-with-form-location
  (testing "Includes form-location-index when provided"
    (let [form-loc-idx {["/path/file.clj" 10] 12345}
          idx (index/build-index sample-coverage-files form-loc-idx)]
      (is (= form-loc-idx (:form-location-index idx))))))

;; =============================================================================
;; tests-for-location Tests
;; =============================================================================

(deftest test-tests-for-location-with-coord
  (testing "Returns tests for specific coordinate"
    (let [idx (index/build-index sample-coverage-files {})]
      (is (= #{'my.app-test/test-add}
             (index/tests-for-location idx 12345 "0,0")))
      (is (= #{'my.app-test/test-sub}
             (index/tests-for-location idx 12345 "0,2"))))))

(deftest test-tests-for-location-form-level
  (testing "Returns all tests for form when no coord specified"
    (let [idx (index/build-index sample-coverage-files {})]
      (is (= #{'my.app-test/test-add 'my.app-test/test-sub}
             (index/tests-for-location idx 12345))))))

(deftest test-tests-for-location-not-found
  (testing "Returns empty set for uncovered locations"
    (let [idx (index/build-index sample-coverage-files {})]
      (is (= #{} (index/tests-for-location idx 99999)))
      (is (= #{} (index/tests-for-location idx 12345 "uncovered"))))))

;; =============================================================================
;; uncovered-coords Tests
;; =============================================================================

(deftest test-uncovered-coords-finds-gaps
  (testing "Finds coordinates with no test coverage"
    (let [idx (index/build-index sample-coverage-files {})
          uncovered (set (index/uncovered-coords idx sample-forms))]
      ;; Form 12345 has coords 0, 0,0, 0,1, 0,2, 0,3
      ;; Tests cover: 0, 0,0, 0,1, 0,2 (from sample-coverage-files)
      ;; Uncovered: 0,3
      (is (contains? uncovered [12345 "0,3"])))))

(deftest test-uncovered-coords-empty-when-all-covered
  (testing "Returns empty when all coords covered"
    (let [fully-covered-forms {12345 {:form/emitted-coords #{"0" "0,0"}}}
          idx (index/build-index sample-coverage-files {})
          uncovered (index/uncovered-coords idx fully-covered-forms)]
      (is (empty? uncovered)))))

;; =============================================================================
;; coverage-stats Tests
;; =============================================================================

(deftest test-coverage-stats-basic
  (testing "Calculates coverage statistics"
    (let [idx (index/build-index sample-coverage-files {})
          stats (index/coverage-stats idx sample-forms)]
      (is (pos? (:total-coords stats)))
      (is (pos? (:covered-coords stats)))
      (is (<= 0 (:coverage-pct stats) 1.0)))))

(deftest test-coverage-stats-empty-forms
  (testing "Returns 100% for empty forms"
    (let [stats (index/coverage-stats {} {})]
      (is (= 1.0 (:coverage-pct stats))))))
