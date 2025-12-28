(ns heretic.fixtures.another-tests
  "Another sample test namespace for collector tests.

   Used to test multi-namespace discovery."
  (:require [clojure.test :refer [deftest is]]))

(deftest test-in-another-ns
  (is (string? "hello")))

(defn not-a-test
  "A regular function in this namespace."
  [x y]
  (+ x y))
