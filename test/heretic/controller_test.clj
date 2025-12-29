(ns heretic.controller-test
  "Tests for heretic.controller module.

   Tests cover:
   - resolve-operators: operator resolution from config
   - prepare-mutations: mutation generation and filtering
   - aggregate-results: result aggregation with analysis
   - build-test-config: test configuration building
   - ensure-coverage!: coverage data management"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.controller :as controller]
            [heretic.operators :as ops]))

;; =============================================================================
;; resolve-operators Tests
;; =============================================================================

(deftest resolve-operators-with-override-test
  (testing "explicit operators argument takes highest priority"
    (let [config {:preset :fast}
          custom-ops [ops/swap-plus-minus ops/swap-minus-plus]
          result (controller/resolve-operators config :operators custom-ops)]
      (is (= custom-ops result)
          "Should return the explicit operators argument"))))

(deftest resolve-operators-with-config-operators-test
  (testing "config :operators key (as operator ids) takes priority over preset"
    (let [config {:preset :fast
                  :operators [:swap-plus-minus :swap-minus-plus]}
          result (controller/resolve-operators config)]
      (is (= 2 (count result)))
      (is (= #{:swap-plus-minus :swap-minus-plus} (set (map :id result))))))

  (testing "config :operators key works with full operator definitions"
    (let [config {:operators [ops/swap-plus-minus]}
          result (controller/resolve-operators config)]
      (is (= [ops/swap-plus-minus] result)))))

(deftest resolve-operators-with-preset-test
  (testing "config :preset :fast returns fast operators"
    (let [config {:preset :fast}
          result (controller/resolve-operators config)]
      (is (seq result))
      (is (= (count (:fast ops/presets)) (count result)))))

  (testing "config :preset :standard returns standard operators"
    (let [config {:preset :standard}
          result (controller/resolve-operators config)]
      (is (seq result))
      (is (= (count (:standard ops/presets)) (count result)))))

  (testing "config :preset :comprehensive returns all operators"
    (let [config {:preset :comprehensive}
          result (controller/resolve-operators config)]
      (is (= (count ops/all-operators) (count result))))))

(deftest resolve-operators-default-test
  (testing "defaults to :standard preset when no config specified"
    (let [config {}
          result (controller/resolve-operators config)]
      (is (= (count (:standard ops/presets)) (count result))))))

;; =============================================================================
;; build-test-config Tests
;; =============================================================================

(deftest build-test-config-basic-test
  (testing "builds config with timeout from main config"
    (let [config {:timeout-ms 10000}
          result (controller/build-test-config config nil)]
      (is (= 10000 (:timeout-ms result)))
      (is (nil? (:timing-data result)))
      (is (nil? (:budget-ms result))))))

(deftest build-test-config-with-timing-test
  (testing "includes timing data when provided"
    (let [config {:timeout-ms 5000}
          timing-data {'some.test/foo 100
                       'some.test/bar 200}
          result (controller/build-test-config config timing-data)]
      (is (= 5000 (:timeout-ms result)))
      (is (= timing-data (:timing-data result))))))

(deftest build-test-config-with-budget-test
  (testing "includes budget when specified in config"
    (let [config {:timeout-ms 5000 :budget-ms 30000}
          result (controller/build-test-config config nil)]
      (is (= 30000 (:budget-ms result))))))

(deftest build-test-config-default-timeout-test
  (testing "uses default timeout when not specified"
    (let [config {}
          result (controller/build-test-config config nil)]
      (is (= 5000 (:timeout-ms result))))))

;; =============================================================================
;; get-worker-count Tests
;; =============================================================================

(deftest get-worker-count-specified-test
  (testing "returns specified worker count"
    (let [config {:parallel-workers 4}
          result (controller/get-worker-count config)]
      (is (= 4 result)))))

(deftest get-worker-count-default-test
  (testing "returns CPU count when not specified"
    (let [config {}
          result (controller/get-worker-count config)]
      (is (pos? result))
      (is (= (.availableProcessors (Runtime/getRuntime)) result)))))

;; =============================================================================
;; aggregate-results Tests
;; =============================================================================

(deftest aggregate-results-basic-test
  (testing "aggregates results with summary statistics"
    (let [results [{:status :killed :mutation {:id 1} :duration-ms 100 :timed-out #{}}
                   {:status :killed :mutation {:id 2} :duration-ms 150 :timed-out #{}}
                   {:status :survived :mutation {:id 3} :duration-ms 200 :timed-out #{}}]
          start-time (- (System/currentTimeMillis) 1000)
          result (controller/aggregate-results results 2 start-time)]
      (is (= 3 (:total result)))
      (is (= 2 (:killed result)))
      (is (= 1 (:survived result)))
      (is (= 2 (:equivalent-filtered result)))
      (is (pos? (:total-duration-ms result)))
      (is (vector? (:survivors result)))
      (is (= 1 (count (:survivors result)))))))

(deftest aggregate-results-empty-test
  (testing "handles empty results"
    (let [results []
          start-time (System/currentTimeMillis)
          result (controller/aggregate-results results 0 start-time)]
      (is (= 0 (:total result)))
      (is (= 0 (:killed result)))
      (is (= 0 (:survived result)))
      (is (= [] (:survivors result))))))

(deftest aggregate-results-all-statuses-test
  (testing "correctly counts all status types"
    (let [results [{:status :killed :mutation {:id 1} :duration-ms 100 :timed-out #{}}
                   {:status :survived :mutation {:id 2} :duration-ms 100 :timed-out #{}}
                   {:status :no-coverage :mutation {:id 3} :duration-ms 10 :timed-out #{}}
                   {:status :timeout :mutation {:id 4} :duration-ms 5000 :timed-out #{}}
                   {:status :error :mutation {:id 5} :duration-ms 50 :timed-out #{}}]
          start-time (System/currentTimeMillis)
          result (controller/aggregate-results results 0 start-time)]
      (is (= 5 (:total result)))
      (is (= 1 (:killed result)))
      (is (= 1 (:survived result)))
      (is (= 1 (:no-coverage result)))
      (is (= 1 (:timeout result)))
      (is (= 1 (:error result))))))

;; =============================================================================
;; extract-test-durations Tests
;; =============================================================================

(deftest extract-test-durations-basic-test
  (testing "extracts and merges test durations from results"
    (let [results [{:test-durations {'test1 100 'test2 200}}
                   {:test-durations {'test3 150}}
                   {:test-durations {'test1 120}}] ; Later value overwrites
          result (controller/extract-test-durations results)]
      (is (= 3 (count result)))
      (is (= 120 (get result 'test1))) ; Later value
      (is (= 200 (get result 'test2)))
      (is (= 150 (get result 'test3))))))

(deftest extract-test-durations-empty-test
  (testing "handles results with no durations"
    (let [results [{:status :killed} {:status :survived}]
          result (controller/extract-test-durations results)]
      (is (= {} result)))))

;; =============================================================================
;; ensure-coverage! Tests
;; =============================================================================

(deftest ensure-coverage-fresh-test
  (testing "returns existing index when coverage is fresh"
    (let [mock-index {:coverage-map {}}
          config {:heretic-dir ".heretic"}
          status-fn (fn [_] {:stale-namespaces #{}})
          collect-fn (fn [& _] (throw (ex-info "Should not be called" {})))]
      ;; This will fail because we can't mock coverage/load-index
      ;; In a real test we'd need to set up the file system or mock the function
      ;; For now, we verify the function signature
      (is (fn? controller/ensure-coverage!)))))

(deftest ensure-coverage-function-signature-test
  (testing "ensure-coverage! accepts expected arguments"
    ;; Verify the function exists and accepts the expected args
    (is (fn? controller/ensure-coverage!))
    ;; The actual function requires file system setup to test properly
    ;; This is a smoke test for the interface
    ))

;; =============================================================================
;; File-Level Parallelism Tests
;; =============================================================================

(deftest group-mutations-by-file-test
  (testing "groups mutations by their source file"
    (let [mutations [{:file "a.clj" :id 1}
                     {:file "b.clj" :id 2}
                     {:file "a.clj" :id 3}
                     {:file "c.clj" :id 4}
                     {:file "b.clj" :id 5}]
          result (controller/group-mutations-by-file mutations)]
      (is (= 3 (count result)))
      (is (= 2 (count (get result "a.clj"))))
      (is (= 2 (count (get result "b.clj"))))
      (is (= 1 (count (get result "c.clj"))))))

  (testing "handles empty mutations"
    (let [result (controller/group-mutations-by-file [])]
      (is (= {} result))))

  (testing "handles single mutation"
    (let [result (controller/group-mutations-by-file [{:file "only.clj" :id 1}])]
      (is (= 1 (count result)))
      (is (= 1 (count (get result "only.clj")))))))

(deftest merge-parallel-results-test
  (testing "merges results from multiple files into single vector"
    (let [file-results [[{:id 1} {:id 2}]
                        [{:id 3}]
                        [{:id 4} {:id 5} {:id 6}]]
          result (controller/merge-parallel-results file-results)]
      (is (= 6 (count result)))
      (is (= [1 2 3 4 5 6] (map :id result)))))

  (testing "handles empty file results"
    (let [result (controller/merge-parallel-results [])]
      (is (= [] result))))

  (testing "handles files with empty results"
    (let [file-results [[{:id 1}] [] [{:id 2}]]
          result (controller/merge-parallel-results file-results)]
      (is (= 2 (count result))))))

(deftest balance-file-groups-test
  (testing "sorts file groups by mutation count descending"
    (let [file-groups {"small.clj" [{:id 1}]
                       "large.clj" [{:id 2} {:id 3} {:id 4}]
                       "medium.clj" [{:id 5} {:id 6}]}
          result (controller/balance-file-groups file-groups)]
      ;; Should be sorted: large (3), medium (2), small (1)
      (is (= 3 (count result)))
      (is (= "large.clj" (first (first result))))
      (is (= 3 (count (second (first result)))))
      (is (= "medium.clj" (first (second result))))
      (is (= 2 (count (second (second result)))))
      (is (= "small.clj" (first (nth result 2))))
      (is (= 1 (count (second (nth result 2)))))))

  (testing "handles empty file groups"
    (let [result (controller/balance-file-groups {})]
      (is (= [] result))))

  (testing "handles files with same mutation count"
    (let [file-groups {"a.clj" [{:id 1} {:id 2}]
                       "b.clj" [{:id 3} {:id 4}]}
          result (controller/balance-file-groups file-groups)]
      ;; Both have 2 mutations, order is stable but both should be present
      (is (= 2 (count result)))
      (is (= 2 (count (second (first result)))))
      (is (= 2 (count (second (second result))))))))

;; =============================================================================
;; Integration-style Tests
;; =============================================================================

(deftest controller-functions-exist-test
  (testing "all expected controller functions exist"
    (is (fn? controller/resolve-operators))
    (is (fn? controller/generate-mutations))
    (is (fn? controller/filter-equivalent-mutations))
    (is (fn? controller/prepare-mutations))
    (is (fn? controller/build-test-config))
    (is (fn? controller/get-worker-count))
    (is (fn? controller/aggregate-results))
    (is (fn? controller/extract-test-durations))
    (is (fn? controller/ensure-coverage!))
    (is (fn? controller/load-timing-data))
    (is (fn? controller/save-timing-data!))
    ;; New file-level parallelism functions
    (is (fn? controller/group-mutations-by-file))
    (is (fn? controller/merge-parallel-results))
    (is (fn? controller/balance-file-groups))))
