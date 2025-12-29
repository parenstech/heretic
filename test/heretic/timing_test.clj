(ns heretic.timing-test
  "Tests for heretic.timing test execution timing tracking.

   Tests cover:
   - load-timing/save-timing!: Persistence of timing data
   - record-timing: Updating timing with new measurements
   - order-tests-by-speed: Ordering tests by historical execution time"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.timing :as timing]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn create-temp-dir-fixture [f]
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "heretic-timing-test-" (System/currentTimeMillis)))]
    (.mkdirs temp-dir)
    (binding [*test-dir* (.getPath temp-dir)]
      (try
        (f)
        (finally
          ;; Cleanup: delete temp dir and contents
          (doseq [file (reverse (file-seq temp-dir))]
            (.delete file)))))))

(use-fixtures :each create-temp-dir-fixture)

;; =============================================================================
;; Persistence Tests
;; =============================================================================

(deftest load-timing-empty-test
  (testing "Loading timing from non-existent file returns nil"
    (is (nil? (timing/load-timing *test-dir*)))))

(deftest save-and-load-timing-test
  (testing "Timing data can be saved and loaded"
    (let [timing-data {'test.ns/fast-test {:duration-ms 10 :runs 1}
                       'test.ns/slow-test {:duration-ms 1000 :runs 1}}]
      (timing/save-timing! *test-dir* timing-data)
      (is (= timing-data (timing/load-timing *test-dir*))))))

(deftest timing-file-path-test
  (testing "Timing file is stored in heretic-dir/timing.edn"
    (let [expected (io/file *test-dir* "timing.edn")]
      (is (= expected (timing/timing-file-path *test-dir*))))))

;; =============================================================================
;; record-timing Tests
;; =============================================================================

(deftest record-timing-first-run-test
  (testing "First measurement creates new entry"
    (let [result (timing/record-timing nil {'test/foo 100})]
      (is (= 1 (get-in result ['test/foo :runs])))
      (is (= 100 (get-in result ['test/foo :duration-ms]))))))

(deftest record-timing-multiple-tests-test
  (testing "Multiple tests recorded in one call"
    (let [result (timing/record-timing nil {'test/foo 100
                                            'test/bar 200})]
      (is (= 100 (get-in result ['test/foo :duration-ms])))
      (is (= 200 (get-in result ['test/bar :duration-ms])))
      (is (= 1 (get-in result ['test/foo :runs])))
      (is (= 1 (get-in result ['test/bar :runs]))))))

(deftest record-timing-update-existing-test
  (testing "Subsequent measurements update with exponential moving average"
    (let [existing {'test/foo {:duration-ms 100 :runs 1}}
          ;; With alpha=0.3: new_avg = 0.3 * 200 + 0.7 * 100 = 130
          result (timing/record-timing existing {'test/foo 200})]
      (is (= 2 (get-in result ['test/foo :runs])))
      (is (= 130 (get-in result ['test/foo :duration-ms]))))))

(deftest record-timing-preserves-other-tests-test
  (testing "Updating one test preserves data for other tests"
    (let [existing {'test/foo {:duration-ms 100 :runs 1}
                    'test/bar {:duration-ms 200 :runs 5}}
          result (timing/record-timing existing {'test/foo 150})]
      ;; test/bar should be unchanged
      (is (= 200 (get-in result ['test/bar :duration-ms])))
      (is (= 5 (get-in result ['test/bar :runs]))))))

(deftest record-timing-empty-durations-test
  (testing "Empty durations map returns existing data unchanged"
    (let [existing {'test/foo {:duration-ms 100 :runs 1}}
          result (timing/record-timing existing {})]
      (is (= existing result)))))

;; =============================================================================
;; order-tests-by-speed Tests
;; =============================================================================

(deftest order-tests-no-timing-data-test
  (testing "Without timing data, tests are returned as vector"
    (let [tests #{'test/a 'test/b 'test/c}
          result (timing/order-tests-by-speed tests nil)]
      (is (vector? result))
      (is (= (set tests) (set result))))))

(deftest order-tests-empty-timing-data-test
  (testing "Empty timing data returns tests as vector"
    (let [tests #{'test/a 'test/b 'test/c}
          result (timing/order-tests-by-speed tests {})]
      (is (vector? result))
      (is (= (set tests) (set result))))))

(deftest order-tests-by-speed-test
  (testing "Tests are ordered by duration (fastest first)"
    (let [tests #{'test/slow 'test/fast 'test/medium}
          timing-data {'test/slow {:duration-ms 1000 :runs 1}
                       'test/fast {:duration-ms 10 :runs 1}
                       'test/medium {:duration-ms 100 :runs 1}}
          result (timing/order-tests-by-speed tests timing-data)]
      (is (vector? result))
      (is (= ['test/fast 'test/medium 'test/slow] result)))))

(deftest order-tests-partial-timing-data-test
  (testing "Tests without timing data are placed at the end"
    (let [tests #{'test/known-fast 'test/unknown 'test/known-slow}
          timing-data {'test/known-fast {:duration-ms 10 :runs 1}
                       'test/known-slow {:duration-ms 1000 :runs 1}}
          result (timing/order-tests-by-speed tests timing-data)]
      (is (vector? result))
      ;; known-fast should be first, known-slow should be second
      (is (= 'test/known-fast (first result)))
      (is (= 'test/known-slow (second result)))
      ;; unknown should be last
      (is (= 'test/unknown (last result))))))

(deftest order-tests-empty-test-set-test
  (testing "Empty test set returns empty vector"
    (let [result (timing/order-tests-by-speed #{} {'test/foo {:duration-ms 100 :runs 1}})]
      (is (vector? result))
      (is (empty? result)))))

(deftest order-tests-timing-data-for-unknown-tests-test
  (testing "Timing data for tests not in the set is ignored"
    (let [tests #{'test/a 'test/b}
          timing-data {'test/a {:duration-ms 100 :runs 1}
                       'test/c {:duration-ms 10 :runs 1}}  ; test/c not in tests
          result (timing/order-tests-by-speed tests timing-data)]
      (is (= 2 (count result)))
      (is (= (set ['test/a 'test/b]) (set result))))))

;; =============================================================================
;; get-estimated-duration Tests
;; =============================================================================

(deftest get-estimated-duration-exists-test
  (testing "Returns duration when test exists in timing data"
    (let [timing-data {'test/foo {:duration-ms 100 :runs 1}}]
      (is (= 100 (timing/get-estimated-duration timing-data 'test/foo))))))

(deftest get-estimated-duration-not-exists-test
  (testing "Returns nil when test not in timing data"
    (let [timing-data {'test/foo {:duration-ms 100 :runs 1}}]
      (is (nil? (timing/get-estimated-duration timing-data 'test/bar))))))

(deftest get-estimated-duration-nil-timing-data-test
  (testing "Returns nil when timing data is nil"
    (is (nil? (timing/get-estimated-duration nil 'test/foo)))))

;; =============================================================================
;; record-timing! Integration Tests
;; =============================================================================

(deftest record-timing-integration-test
  (testing "record-timing! saves and accumulates data"
    ;; First run
    (timing/record-timing! *test-dir* {'test/foo 100})
    (let [first-result (timing/load-timing *test-dir*)]
      (is (= 1 (get-in first-result ['test/foo :runs])))
      (is (= 100 (get-in first-result ['test/foo :duration-ms]))))

    ;; Second run with updated timing
    (timing/record-timing! *test-dir* {'test/foo 200 'test/bar 50})
    (let [second-result (timing/load-timing *test-dir*)]
      (is (= 2 (get-in second-result ['test/foo :runs])))
      ;; EMA: 0.3 * 200 + 0.7 * 100 = 130
      (is (= 130 (get-in second-result ['test/foo :duration-ms])))
      (is (= 1 (get-in second-result ['test/bar :runs])))
      (is (= 50 (get-in second-result ['test/bar :duration-ms]))))))

(deftest record-timing-empty-input-test
  (testing "record-timing! with empty input returns nil and doesn't write file"
    (let [result (timing/record-timing! *test-dir* {})]
      (is (nil? result))
      (is (nil? (timing/load-timing *test-dir*))))))

;; =============================================================================
;; order-tests-by-speed Additional Tests (kill survivors)
;; =============================================================================

(deftest order-tests-nil-timing-preserves-input-order-test
  (testing "With nil timing data, input order is preserved (tests line 114 or->and)"
    ;; Using a vector input to have deterministic order
    (let [tests ['test/first 'test/second 'test/third]
          result (timing/order-tests-by-speed tests nil)]
      ;; The key assertion: order must match input order exactly
      (is (= ['test/first 'test/second 'test/third] result)))))

(deftest order-tests-empty-timing-preserves-input-order-test
  (testing "With empty timing data, input order is preserved (tests line 114 or->and)"
    (let [tests ['test/first 'test/second 'test/third]
          result (timing/order-tests-by-speed tests {})]
      (is (= ['test/first 'test/second 'test/third] result)))))

;; =============================================================================
;; calculate-dynamic-timeout Tests (kill survivors)
;; =============================================================================

(deftest calculate-dynamic-timeout-multiplier-test
  (testing "Timeout is calculated as duration * multiplier (tests line 164 *->/)"
    (let [timing-data {'test/foo {:duration-ms 1000 :runs 1}}
          ;; With multiplier 3.0, expected = 1000 * 3.0 = 3000
          ;; If mutation changes * to /, expected would be 1000 / 3.0 = 333
          ;; base-timeout defaults to 1000, so result must exceed that
          result (timing/calculate-dynamic-timeout timing-data 'test/foo {:multiplier 3.0})]
      (is (= 3000 result)))))

(deftest calculate-dynamic-timeout-respects-base-test
  (testing "Timeout is at least base-timeout-ms"
    (let [timing-data {'test/foo {:duration-ms 10 :runs 1}}
          ;; 10 * 3.0 = 30, but base is 1000
          result (timing/calculate-dynamic-timeout timing-data 'test/foo
                                                   {:base-timeout-ms 1000 :multiplier 3.0})]
      (is (= 1000 result)))))

(deftest calculate-dynamic-timeout-respects-max-test
  (testing "Timeout is capped at max-timeout-ms"
    (let [timing-data {'test/foo {:duration-ms 100000 :runs 1}}
          ;; 100000 * 3.0 = 300000, but max is 30000
          result (timing/calculate-dynamic-timeout timing-data 'test/foo
                                                   {:max-timeout-ms 30000 :multiplier 3.0})]
      (is (= 30000 result)))))

(deftest calculate-dynamic-timeout-unknown-test-test
  (testing "Unknown test gets max timeout"
    (let [timing-data {'test/other {:duration-ms 100 :runs 1}}
          result (timing/calculate-dynamic-timeout timing-data 'test/unknown
                                                   {:max-timeout-ms 5000})]
      (is (= 5000 result)))))

;; =============================================================================
;; estimate-total-duration Tests (kill survivors)
;; =============================================================================

(deftest estimate-total-duration-uses-custom-default-test
  (testing "Custom default-per-test is used for unknown tests (tests line 181 or->and/true)"
    (let [timing-data {}
          tests ['test/unknown]
          ;; With custom default 500, should return 500
          ;; If or->and mutation: (and 500 1000) = 1000 (wrong)
          ;; If or->true mutation: would use `true` as duration (wrong)
          result (timing/estimate-total-duration timing-data tests 500)]
      (is (= 500 result)))))

(deftest estimate-total-duration-nil-default-uses-1000-test
  (testing "nil default-per-test falls back to 1000"
    (let [timing-data {}
          tests ['test/unknown]
          result (timing/estimate-total-duration timing-data tests nil)]
      (is (= 1000 result)))))

(deftest estimate-total-duration-adds-durations-test
  (testing "Durations are accumulated by addition (tests line 184 +->-)"
    (let [timing-data {'test/a {:duration-ms 100 :runs 1}
                       'test/b {:duration-ms 200 :runs 1}}
          tests ['test/a 'test/b]
          ;; Should be 100 + 200 = 300
          ;; If + becomes -, would be 100 - 200 = -100 or 0 - 100 - 200 = -300
          result (timing/estimate-total-duration timing-data tests 1000)]
      (is (= 300 result)))))

(deftest estimate-total-duration-uses-timing-data-test
  (testing "Known test durations are used, not default (tests line 184 or->and/true)"
    (let [timing-data {'test/fast {:duration-ms 50 :runs 1}}
          tests ['test/fast]
          ;; Should use 50 from timing data, not default 1000
          ;; If or->and: (and 50 1000) = 1000 (wrong)
          ;; If or->true: would use `true` (wrong)
          result (timing/estimate-total-duration timing-data tests 1000)]
      (is (= 50 result)))))

(deftest estimate-total-duration-mixed-test
  (testing "Mix of known and unknown test durations"
    (let [timing-data {'test/known {:duration-ms 100 :runs 1}}
          tests ['test/known 'test/unknown]
          ;; known=100, unknown=500 (default), total=600
          result (timing/estimate-total-duration timing-data tests 500)]
      (is (= 600 result)))))
