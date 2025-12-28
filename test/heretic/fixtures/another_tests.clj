(ns heretic.fixtures.another-tests
  "Another sample test namespace for collector tests.

   Used to test multi-namespace discovery.

   Note: These are NOT run by Kaocha - they are test fixtures.")

(defn ^{:test true} test-in-another-ns
  "A test in a different namespace."
  []
  (assert (string? "hello")))

(defn not-a-test
  "A regular function in this namespace."
  [x y]
  (+ x y))
