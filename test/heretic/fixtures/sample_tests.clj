(ns heretic.fixtures.sample-tests
  "Sample test namespace for collector tests.

   This namespace contains both test and non-test vars
   to verify discovery logic.

   Note: These are NOT run by Kaocha - they are test fixtures
   for the collector tests. The tests are defined with ^:test
   metadata but without using deftest to avoid Kaocha picking them up.")

;; =============================================================================
;; Test vars (have :test metadata) - NOT discovered by Kaocha
;; These simulate what deftest creates, but without Kaocha integration
;; =============================================================================

(defn ^{:test true} sample-passing-test
  "A simple passing test."
  []
  (assert (= 1 1)))

(defn ^{:test true} sample-another-test
  "Another passing test."
  []
  (assert (= 2 2)))

(defn ^{:test true} sample-failing-test
  "A test that always fails."
  []
  (assert (= 1 2) "Expected failure"))

(defn ^{:test true} sample-throwing-test
  "A test that throws an exception."
  []
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
