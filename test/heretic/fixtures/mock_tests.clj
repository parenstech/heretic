(ns heretic.fixtures.mock-tests
  "Mock test functions for heretic.runner-test.

   These are functions with :test metadata that can be used to test
   the runner's ability to execute tests and capture results.

   They are in a separate namespace to avoid being picked up by Kaocha
   as actual tests to run."
  (:require [clojure.test :refer [is]]))

;; =============================================================================
;; Mock Test Functions
;; =============================================================================

(defn passing-test
  "A test that always passes."
  []
  (is (= 1 1)))

(defn failing-test
  "A test that always fails."
  []
  (is (= 1 2)))

(defn erroring-test
  "A test that throws an exception."
  []
  (throw (ex-info "Test error" {})))

(defn slow-test
  "A test that takes 5 seconds to complete."
  []
  (Thread/sleep 5000)
  (is (= 1 1)))

;; =============================================================================
;; Configurable Slow Tests (for timeout edge cases)
;; =============================================================================

(defn fast-test
  "A test that completes in 10ms."
  []
  (Thread/sleep 10)
  (is (= 1 1)))

(defn medium-slow-test
  "A test that takes 200ms to complete."
  []
  (Thread/sleep 200)
  (is (= 1 1)))

(defn slow-failing-test
  "A test that takes 500ms and then fails."
  []
  (Thread/sleep 500)
  (is (= 1 2)))

(defn slow-erroring-test
  "A test that takes 500ms and then throws."
  []
  (Thread/sleep 500)
  (throw (ex-info "Slow test error" {})))

(defn boundary-test-150ms
  "A test that takes exactly 150ms to complete."
  []
  (Thread/sleep 150)
  (is (= 1 1)))

(defn cancellation-tracking-test
  "A test that records when it was cancelled/interrupted.
   Uses a promise that can be checked after the test."
  []
  (try
    (Thread/sleep 5000)
    (is (= 1 1))
    (catch InterruptedException _e
      ;; Test was interrupted - this is expected during cancellation
      nil)))

;; Register the :test metadata so these can be run via clojure.test/test-var
(alter-meta! #'passing-test assoc :test passing-test)
(alter-meta! #'failing-test assoc :test failing-test)
(alter-meta! #'erroring-test assoc :test erroring-test)
(alter-meta! #'slow-test assoc :test slow-test)
(alter-meta! #'fast-test assoc :test fast-test)
(alter-meta! #'medium-slow-test assoc :test medium-slow-test)
(alter-meta! #'slow-failing-test assoc :test slow-failing-test)
(alter-meta! #'slow-erroring-test assoc :test slow-erroring-test)
(alter-meta! #'boundary-test-150ms assoc :test boundary-test-150ms)
(alter-meta! #'cancellation-tracking-test assoc :test cancellation-tracking-test)
