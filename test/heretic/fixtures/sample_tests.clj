(ns heretic.fixtures.sample-tests
  "Sample test namespace for collector tests.

   This namespace contains both test and non-test vars
   to verify discovery logic.

   Note: These use deftest properly so clojure.test/test-var can call them."
  (:require [clojure.test :refer [deftest is]]))

;; =============================================================================
;; Test vars (have :test metadata)
;; =============================================================================

(deftest sample-passing-test
  (is (= 1 1)))

(deftest sample-another-test
  (is (= 2 2)))

(deftest sample-failing-test
  ;; Use assert instead of is to avoid Kaocha reporting during coverage collection
  (assert (= 1 2) "Expected failure"))

(deftest sample-throwing-test
  (throw (ex-info "Intentional exception" {:reason :testing})))

;; =============================================================================
;; Non-test vars (no :test metadata)
;; =============================================================================

(defn helper-fn
  "A regular function, not a test."
  [x]
  (inc x))

(def sample-data
  "A data var, not a test."
  {:foo :bar})

(defn another-helper
  "Another non-test function."
  []
  (+ 1 2 3))
