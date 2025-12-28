(ns heretic.collector-test
  "Tests for per-test coverage collection.

   These tests verify:
   - Test discovery in namespaces
   - Per-test coverage isolation
   - Handling of test exceptions"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.collector :as collector]))

;; =============================================================================
;; Test Discovery
;; =============================================================================

(deftest test-discover-test-vars
  (testing "Discovers vars with :test metadata"
    ;; TODO: Create test namespace with known test vars
    ;; Verify discover-test-vars finds them
    ))

(deftest test-discover-ignores-non-tests
  (testing "Ignores vars without :test metadata"
    ;; TODO: Verify regular functions are not discovered
    ))

;; =============================================================================
;; Per-Test Coverage
;; =============================================================================

(deftest test-run-test-with-coverage
  (testing "Returns coverage keyed by test symbol"
    ;; TODO: Run a test and verify coverage structure
    ;; {test-symbol -> {form-id -> #{coords}}}
    ))

(deftest test-coverage-isolation
  (testing "Coverage is isolated between tests"
    ;; TODO: Run two tests, verify each has its own coverage
    ;; Coverage from test A should not appear in test B's results
    ))

;; =============================================================================
;; Exception Handling
;; =============================================================================

(deftest test-exception-handling
  (testing "Coverage is collected even when test throws"
    ;; TODO: Run a failing test
    ;; Verify coverage up to the exception point is captured
    ))

;; =============================================================================
;; Namespace-Level Collection
;; =============================================================================

(deftest test-collect-coverage-for-ns
  (testing "Collects coverage for all tests in namespace"
    ;; TODO: Create test namespace with multiple tests
    ;; Verify all tests' coverage is collected
    ))

;; =============================================================================
;; Integration Tests
;; =============================================================================

;; TODO: Add integration tests with actual test namespaces
;; These should verify end-to-end coverage collection
