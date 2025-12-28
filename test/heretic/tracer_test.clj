(ns heretic.tracer-test
  "Tests for ClojureStorm tracer integration.

   These tests verify:
   - Coverage recording callbacks
   - Coordinate stringification
   - Coverage accumulator state management

   Note: Full integration tests require ClojureStorm on classpath."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.tracer :as tracer]))

;; =============================================================================
;; Coordinate Stringification
;; =============================================================================

(deftest test-stringify-coord
  (testing "Vector coordinates are stringified correctly"
    ;; TODO: Test internal stringify-coord function
    ;; [3 2 1] -> "3,2,1"
    ;; [0] -> "0"
    )

  (testing "String coordinates pass through unchanged"
    ;; "K-12345" -> "K-12345"
    ))

;; =============================================================================
;; Coverage Accumulator
;; =============================================================================

(deftest test-coverage-reset
  (testing "Coverage is empty after reset"
    (tracer/reset-current-coverage!)
    (is (= {} (tracer/get-current-coverage)))))

(deftest test-coverage-accumulation
  (testing "Multiple hits to same form accumulate coords"
    ;; TODO: Test record-hit! accumulation
    ;; This requires either exposing record-hit! or mocking tracer callbacks
    ))

;; =============================================================================
;; Initialization
;; =============================================================================

(deftest test-initialization-state
  (testing "Tracer reports uninitialized before init"
    ;; Note: This may fail if another test called init!
    ;; TODO: Add proper test isolation
    ))

;; =============================================================================
;; Integration Tests (require ClojureStorm)
;; =============================================================================

;; TODO: Add integration tests that run with :clojurestorm alias
;; These should verify:
;; - Emitter callbacks are received
;; - Form IDs match FormRegistry
;; - Coordinates match expected format
