(ns heretic.tracer-test
  "Tests for ClojureStorm tracer integration.

   These tests verify:
   - Coverage recording callbacks
   - Coverage accumulator state management
   - Initialization state transitions

   Note: Full integration tests require ClojureStorm on classpath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.tracer :as tracer]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-coverage-fixture
  "Reset coverage state before each test."
  [f]
  (tracer/reset-current-coverage!)
  (f))

(use-fixtures :each reset-coverage-fixture)

;; =============================================================================
;; Coverage Accumulator
;; =============================================================================

(deftest test-coverage-reset
  (testing "Coverage is empty after reset"
    (tracer/reset-current-coverage!)
    (is (= {} (tracer/get-current-coverage)))))

(deftest test-coverage-accumulation
  (testing "Single hit is recorded"
    (tracer/record-hit! 12345 "3")
    (is (= {12345 #{"3"}} (tracer/get-current-coverage))))

  (testing "Multiple hits to same form accumulate coords"
    (tracer/reset-current-coverage!)
    (tracer/record-hit! 12345 "3")
    (tracer/record-hit! 12345 "3,1")
    (tracer/record-hit! 12345 "3,2")
    (is (= {12345 #{"3" "3,1" "3,2"}} (tracer/get-current-coverage))))

  (testing "Duplicate coords are deduplicated"
    (tracer/reset-current-coverage!)
    (tracer/record-hit! 12345 "3")
    (tracer/record-hit! 12345 "3")
    (tracer/record-hit! 12345 "3")
    (is (= {12345 #{"3"}} (tracer/get-current-coverage))))

  (testing "Hits to different forms are tracked separately"
    (tracer/reset-current-coverage!)
    (tracer/record-hit! 12345 "1")
    (tracer/record-hit! 12346 "2")
    (tracer/record-hit! 12345 "3")
    (is (= {12345 #{"1" "3"}
            12346 #{"2"}}
           (tracer/get-current-coverage))))

  (testing "Empty string coords (function return) are valid"
    (tracer/reset-current-coverage!)
    (tracer/record-hit! 12345 "")
    (is (= {12345 #{""}} (tracer/get-current-coverage))))

  (testing "Hash-based coords for unordered collections are valid"
    (tracer/reset-current-coverage!)
    (tracer/record-hit! 12345 "K12345")
    (tracer/record-hit! 12345 "V67890")
    (is (= {12345 #{"K12345" "V67890"}} (tracer/get-current-coverage)))))

;; =============================================================================
;; Initialization State
;; =============================================================================

(deftest test-initialization-state
  ;; Note: These tests manipulate global state. We ensure proper cleanup
  ;; to avoid affecting other tests.

  (testing "shutdown! resets initialized state"
    ;; Start from a known state by shutting down first
    (tracer/shutdown!)
    (is (false? (tracer/initialized?*))
        "After shutdown!, initialized?* should return false"))

  (testing "shutdown! is idempotent"
    (tracer/shutdown!)
    (tracer/shutdown!)
    (is (false? (tracer/initialized?*))
        "Multiple shutdown! calls should be safe"))

  ;; Note: We cannot test init! without ClojureStorm on the classpath
  ;; as it calls Emitter/setInstrumentationEnable which requires the library.
  ;; Integration tests with :clojurestorm alias would cover init! behavior.
  )

;; =============================================================================
;; Integration Tests (require ClojureStorm)
;; =============================================================================

;; TODO: Add integration tests that run with :clojurestorm alias
;; These should verify:
;; - init! returns true on first call, false on subsequent calls
;; - Emitter callbacks are received
;; - Form IDs match FormRegistry
;; - Coordinates match expected format
