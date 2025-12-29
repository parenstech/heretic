(ns heretic.trends-test
  "Tests for trend data storage and analysis."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.trends :as trends]))

(deftest trend-data-empty-test
  (testing "Empty history returns no-data trend"
    ;; Use a non-existent directory
    (let [td (trends/trend-data "/tmp/nonexistent-heretic-dir-12345")]
      (is (= :no-data (:score-trend td)))
      (is (empty? (:runs td)))
      (is (nil? (:avg-score td))))))

(deftest score-sparkline-empty-test
  (testing "Empty history returns empty sparkline"
    (let [sparkline (trends/score-sparkline "/tmp/nonexistent-heretic-dir-12345")]
      (is (= "" sparkline)))))

(deftest format-trend-summary-test
  (testing "Format no-data summary"
    (let [td {:score-trend :no-data :runs []}
          summary (trends/format-trend-summary td)]
      (is (string? summary))
      (is (.contains summary "No historical data"))))

  (testing "Format improving trend summary"
    (let [td {:score-trend :improving
              :avg-score 0.75
              :best-score 0.90
              :worst-score 0.60
              :runs (repeat 5 {:score 0.8})}
          summary (trends/format-trend-summary td)]
      (is (.contains summary "Improving"))
      (is (.contains summary "75.0%"))
      (is (.contains summary "90.0%")))))
