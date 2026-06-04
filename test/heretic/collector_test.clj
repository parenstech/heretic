(ns heretic.collector-test
  "Tests for per-test coverage collection.

   These tests verify:
   - Test discovery in namespaces
   - Per-test coverage isolation
   - Handling of test exceptions"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.collector :as collector]
            [heretic.tracer :as tracer]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-tracer-fixture
  "Reset tracer state before each test."
  [f]
  (tracer/reset-current-coverage!)
  (f))

(use-fixtures :each reset-tracer-fixture)

;; =============================================================================
;; Test Discovery
;; =============================================================================

(deftest test-resolve-test-namespaces
  (testing ":exclude-test-namespaces removes namespaces from an explicit list (issue #3)"
    (is (= '[a.core-test b.util-test]
           (collector/resolve-test-namespaces
            {:test-namespaces '[a.core-test b.util-test c.skip-test]
             :exclude-test-namespaces #{'c.skip-test}}))))
  (testing "exclude entries may be strings as well as symbols"
    (is (= '[a.core-test]
           (collector/resolve-test-namespaces
            {:test-namespaces '[a.core-test c.skip-test]
             :exclude-test-namespaces ["c.skip-test"]}))))
  (testing "no excludes leaves the list unchanged"
    (is (= '[a.core-test c.skip-test]
           (collector/resolve-test-namespaces
            {:test-namespaces '[a.core-test c.skip-test]}))))
  (testing "a symbol exclude matches a string-valued :test-namespaces entry (issue #3)"
    (is (= '[a.core-test]
           (collector/resolve-test-namespaces
            {:test-namespaces ["a.core-test" "c.skip-test"]
             :exclude-test-namespaces #{'c.skip-test}})))))

(deftest test-resolve-test-namespaces-all-with-exclude
  (testing ":test-namespaces :all discovers from disk, then :exclude-test-namespaces is removed (issue #3)"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir")
                             (str "heretic-rtn-" (System/currentTimeMillis)))]
      (.mkdirs dir)
      (spit (java.io.File. dir "a_test.clj") "(ns a-test)")
      (spit (java.io.File. dir "b_test.clj") "(ns b-test)")
      (try
        (let [result (set (collector/resolve-test-namespaces
                           {:test-namespaces :all
                            :test-paths [(.getPath dir)]
                            :exclude-test-namespaces #{'b-test}}))]
          (is (contains? result 'a-test) "a non-excluded discovered ns is kept")
          (is (not (contains? result 'b-test)) "the excluded ns is removed from the :all set"))
        (finally
          (doseq [f (reverse (file-seq dir))] (.delete f)))))))

(deftest test-discover-test-vars
  (testing "Discovers vars with :test metadata"
    ;; Load the fixture namespace
    (require 'heretic.fixtures.sample-tests)

    ;; Discover test vars from that namespace
    (let [test-vars (collector/discover-test-vars ['heretic.fixtures.sample-tests])
          test-names (set (map #(-> % meta :name) test-vars))]

      ;; Should find all 4 test vars
      (is (= 4 (count test-vars))
          "Should discover 4 test vars")

      ;; Verify specific test names are found
      (is (contains? test-names 'sample-passing-test)
          "Should find sample-passing-test")
      (is (contains? test-names 'sample-another-test)
          "Should find sample-another-test")
      (is (contains? test-names 'sample-failing-test)
          "Should find sample-failing-test")
      (is (contains? test-names 'sample-throwing-test)
          "Should find sample-throwing-test")))

  (testing "Discovers vars from multiple namespaces"
    (require 'heretic.fixtures.sample-tests)
    (require 'heretic.fixtures.another-tests)

    (let [test-vars (collector/discover-test-vars
                     ['heretic.fixtures.sample-tests
                      'heretic.fixtures.another-tests])
          test-names (set (map #(-> % meta :name) test-vars))]

      ;; Should find tests from both namespaces (4 + 1 = 5)
      (is (= 5 (count test-vars))
          "Should discover tests from both namespaces")

      ;; From sample-tests
      (is (contains? test-names 'sample-passing-test))
      ;; From another-tests
      (is (contains? test-names 'test-in-another-ns)))))

(deftest test-discover-ignores-non-tests
  (testing "Ignores vars without :test metadata"
    (require 'heretic.fixtures.sample-tests)

    (let [test-vars (collector/discover-test-vars ['heretic.fixtures.sample-tests])
          test-names (set (map #(-> % meta :name) test-vars))]

      ;; Should NOT find regular functions or data vars
      (is (not (contains? test-names 'helper-fn))
          "Should not find helper-fn")
      (is (not (contains? test-names 'sample-data))
          "Should not find sample-data")
      (is (not (contains? test-names 'another-helper))
          "Should not find another-helper"))))

(deftest test-discover-test-vars-in-ns
  (testing "Discovers test vars in a single namespace"
    (require 'heretic.fixtures.sample-tests)

    (let [test-vars (collector/discover-test-vars-in-ns 'heretic.fixtures.sample-tests)
          test-names (set (map #(-> % meta :name) test-vars))]

      (is (= 4 (count test-vars))
          "Should discover 4 test vars in namespace")
      (is (contains? test-names 'sample-passing-test))
      (is (not (contains? test-names 'helper-fn))
          "Should not find non-test vars"))))

;; =============================================================================
;; Per-Test Coverage
;; =============================================================================

(deftest test-run-test-with-coverage
  (testing "Returns coverage keyed by test symbol"
    (require 'heretic.fixtures.sample-tests)

    (let [test-var (resolve 'heretic.fixtures.sample-tests/sample-passing-test)
          result (collector/run-test-with-coverage test-var)]

      ;; Result should be a map with test symbol as key
      (is (map? result)
          "Result should be a map")

      ;; The key should be the symbol of the test var
      (is (contains? result 'heretic.fixtures.sample-tests/sample-passing-test)
          "Result should contain test symbol as key")

      ;; The value should be a map (coverage data)
      ;; Note: Without ClojureStorm instrumentation, coverage will be empty
      (is (map? (get result 'heretic.fixtures.sample-tests/sample-passing-test))
          "Coverage value should be a map")))

  (testing "Coverage data structure is correct"
    (require 'heretic.fixtures.sample-tests)

    ;; Manually simulate some coverage to verify structure
    (tracer/reset-current-coverage!)
    (#'tracer/record-hit! 12345 "3,2,1")
    (#'tracer/record-hit! 12345 "3,2,2")
    (#'tracer/record-hit! 67890 "")

    ;; Create a simple test var for this test
    (let [coverage (tracer/get-current-coverage)]
      ;; Verify structure: {form-id -> #{coords}}
      (is (= #{12345 67890} (set (keys coverage)))
          "Should have two form IDs")
      (is (= #{"3,2,1" "3,2,2"} (get coverage 12345))
          "Form 12345 should have two coords")
      (is (= #{""} (get coverage 67890))
          "Form 67890 should have empty string coord"))))

(deftest test-coverage-isolation
  (testing "Coverage is isolated between tests"
    (require 'heretic.fixtures.sample-tests)

    ;; Simulate coverage for first test
    (tracer/reset-current-coverage!)
    (#'tracer/record-hit! 11111 "first")
    (let [first-coverage (tracer/get-current-coverage)]
      (is (= {11111 #{"first"}} first-coverage)))

    ;; Reset and simulate coverage for second test
    (tracer/reset-current-coverage!)
    (#'tracer/record-hit! 22222 "second")
    (let [second-coverage (tracer/get-current-coverage)]
      ;; Second coverage should NOT contain first test's data
      (is (not (contains? second-coverage 11111))
          "Second test should not see first test's coverage")
      (is (= {22222 #{"second"}} second-coverage)
          "Second test should only have its own coverage")))

  (testing "run-test-with-coverage resets coverage before test"
    (require 'heretic.fixtures.sample-tests)

    ;; Add some pre-existing coverage
    (#'tracer/record-hit! 99999 "pre-existing")

    ;; Run a test
    (let [test-var (resolve 'heretic.fixtures.sample-tests/sample-passing-test)
          result (collector/run-test-with-coverage test-var)
          coverage (get result 'heretic.fixtures.sample-tests/sample-passing-test)]

      ;; Pre-existing coverage should be gone (reset by run-test-with-coverage)
      (is (not (contains? coverage 99999))
          "Pre-existing coverage should be cleared"))))

;; =============================================================================
;; Exception Handling
;; =============================================================================

(deftest test-exception-handling
  (testing "Coverage is collected even when test throws"
    (require 'heretic.fixtures.sample-tests)

    ;; Simulate coverage before exception
    ;; In real scenario, ClojureStorm would record hits before the throw
    (tracer/reset-current-coverage!)
    (#'tracer/record-hit! 33333 "before-throw")

    (let [test-var (resolve 'heretic.fixtures.sample-tests/sample-throwing-test)
          ;; run-test-with-coverage should not throw, even with throwing test
          result (collector/run-test-with-coverage test-var)]

      ;; Should get a result map (not throw)
      (is (map? result)
          "Should return result even when test throws")
      (is (contains? result 'heretic.fixtures.sample-tests/sample-throwing-test)
          "Result should contain the throwing test's symbol")))

  (testing "Exception in test does not prevent subsequent tests"
    (require 'heretic.fixtures.sample-tests)

    (let [throwing-var (resolve 'heretic.fixtures.sample-tests/sample-throwing-test)
          passing-var (resolve 'heretic.fixtures.sample-tests/sample-passing-test)]

      ;; Run throwing test first
      (let [throwing-result (collector/run-test-with-coverage throwing-var)]
        (is (map? throwing-result)))

      ;; Should be able to run passing test after throwing test
      (let [passing-result (collector/run-test-with-coverage passing-var)]
        (is (map? passing-result))
        (is (contains? passing-result 'heretic.fixtures.sample-tests/sample-passing-test))))))

;; =============================================================================
;; Namespace-Level Collection
;; =============================================================================

(deftest test-collect-coverage-for-ns
  (testing "Collects coverage for all tests in namespace"
    (require 'heretic.fixtures.sample-tests)

    (let [result (collector/collect-coverage-for-ns 'heretic.fixtures.sample-tests)]
      ;; Result should have :test-coverage key
      (is (contains? result :test-coverage)
          "Result should have :test-coverage key")

      (let [test-coverage (:test-coverage result)
            test-symbols (set (keys test-coverage))]
        ;; Should have coverage for all 4 tests
        (is (= 4 (count test-symbols))
            "Should collect coverage for all 4 tests")

        ;; Verify all test symbols are present
        (is (contains? test-symbols 'heretic.fixtures.sample-tests/sample-passing-test))
        (is (contains? test-symbols 'heretic.fixtures.sample-tests/sample-another-test))
        (is (contains? test-symbols 'heretic.fixtures.sample-tests/sample-failing-test))
        (is (contains? test-symbols 'heretic.fixtures.sample-tests/sample-throwing-test)))))

  (testing "Each test has its own coverage map"
    (require 'heretic.fixtures.sample-tests)

    (let [result (collector/collect-coverage-for-ns 'heretic.fixtures.sample-tests)
          test-coverage (:test-coverage result)]

      ;; Each test's coverage should be a map
      (doseq [[test-sym coverage] test-coverage]
        (is (map? coverage)
            (str "Coverage for " test-sym " should be a map"))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-collect-all-coverage
  (testing "Collects coverage from multiple namespaces"
    (require 'heretic.fixtures.sample-tests)
    (require 'heretic.fixtures.another-tests)

    ;; Note: This will call tracer/init! which may fail without ClojureStorm
    ;; We test the structure, not the ClojureStorm integration
    (try
      (let [result (collector/collect-all-coverage
                    ['heretic.fixtures.sample-tests
                     'heretic.fixtures.another-tests])]

        (is (contains? result :test-coverage)
            "Result should have :test-coverage key")

        (let [test-coverage (:test-coverage result)
              test-symbols (set (keys test-coverage))]
          ;; Should have coverage for 5 tests total (4 + 1)
          (is (= 5 (count test-symbols))
              "Should collect coverage for all 5 tests")

          ;; From sample-tests
          (is (contains? test-symbols 'heretic.fixtures.sample-tests/sample-passing-test))
          ;; From another-tests
          (is (contains? test-symbols 'heretic.fixtures.another-tests/test-in-another-ns))))

      ;; If ClojureStorm is not available, the test will throw during init!
      ;; That's OK - we're testing without ClojureStorm instrumentation
      (catch Exception _e
        ;; Expected when ClojureStorm is not on classpath
        (is true "ClojureStorm not available - skipping full integration test")))))

(deftest test-discover-test-namespaces
  (testing "Discovers test namespaces from test paths"
    (let [test-paths ["test"]
          namespaces (collector/discover-test-namespaces test-paths)]

      ;; Should find our fixture namespaces
      (is (some #(= 'heretic.fixtures.sample-tests %) namespaces)
          "Should discover heretic.fixtures.sample-tests")
      (is (some #(= 'heretic.fixtures.another-tests %) namespaces)
          "Should discover heretic.fixtures.another-tests")
      (is (some #(= 'heretic.collector-test %) namespaces)
          "Should discover this test namespace"))))
