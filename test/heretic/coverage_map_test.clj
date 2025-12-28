(ns heretic.coverage-map-test
  "Tests for coverage index building and queries.

   These tests verify:
   - Inverse index construction
   - Query functions (tests-for-location)
   - Source dependency extraction"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.coverage-map :as coverage]))

;; =============================================================================
;; Inverse Index Building
;; =============================================================================

(deftest test-build-inverse-index-empty
  (testing "Empty input produces empty index"
    (is (= {} (coverage/build-inverse-index [])))))

(deftest test-build-inverse-index-single
  (testing "Single test coverage produces correct index"
    (let [coverage-data [{:coverage {'my.test/test-foo
                                     {12345 #{"3" "3,1"}}}}]
          result (coverage/build-inverse-index coverage-data)]
      (is (= #{'my.test/test-foo}
             (get result [12345 "3"])))
      (is (= #{'my.test/test-foo}
             (get result [12345 "3,1"]))))))

(deftest test-build-inverse-index-multiple-tests
  (testing "Multiple tests hitting same coord accumulate"
    (let [coverage-data [{:coverage {'my.test/test-foo
                                     {12345 #{"3"}}}}
                         {:coverage {'my.test/test-bar
                                     {12345 #{"3"}}}}]
          result (coverage/build-inverse-index coverage-data)]
      (is (= #{'my.test/test-foo 'my.test/test-bar}
             (get result [12345 "3"]))))))

;; =============================================================================
;; Query Functions
;; =============================================================================

(deftest test-tests-for-location-with-coord
  (testing "Returns tests for specific form+coord"
    (let [index {:coord-to-tests {[12345 "3"] #{'test-a 'test-b}
                                  [12345 "4"] #{'test-c}}}]
      (is (= #{'test-a 'test-b}
             (coverage/tests-for-location index 12345 "3")))
      (is (= #{'test-c}
             (coverage/tests-for-location index 12345 "4"))))))

(deftest test-tests-for-location-form-level
  (testing "Returns all tests for form when no coord given"
    (let [index {:coord-to-tests {[12345 "3"] #{'test-a}
                                  [12345 "4"] #{'test-b}
                                  [67890 "1"] #{'test-c}}}]
      (is (= #{'test-a 'test-b}
             (coverage/tests-for-location index 12345))))))

(deftest test-tests-for-location-missing
  (testing "Returns empty set for unknown location"
    (let [index {:coord-to-tests {}}]
      (is (= #{} (coverage/tests-for-location index 99999 "1")))
      (is (= #{} (coverage/tests-for-location index 99999))))))

;; =============================================================================
;; Source Dependency Tracking
;; =============================================================================

(deftest test-extract-source-deps
  (testing "Extracts touched source files from coverage"
    ;; TODO: Test with mock form registry
    ;; Verify correct file paths are returned
    ))

;; =============================================================================
;; Integration Tests
;; =============================================================================

;; TODO: Add integration tests that verify:
;; - rebuild-index! persists correctly
;; - Index survives round-trip through persistence
