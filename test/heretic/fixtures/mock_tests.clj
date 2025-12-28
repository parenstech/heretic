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

;; Register the :test metadata so these can be run via clojure.test/test-var
(alter-meta! #'passing-test assoc :test passing-test)
(alter-meta! #'failing-test assoc :test failing-test)
(alter-meta! #'erroring-test assoc :test erroring-test)
(alter-meta! #'slow-test assoc :test slow-test)
