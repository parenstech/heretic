(ns heretic.reporter-test
  "Tests for heretic.reporter calculation functions.

   Tests cover:
   - count-by-status: counting mutations by status category
   - mutation-score: score calculation with various inputs
   - survivors/killed/etc: filtering functions
   - summary-data: data export
   - JSON report generation
   - EDN report generation"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heretic.reporter :as reporter]))

;; =============================================================================
;; Test Data Fixtures
;; =============================================================================

(def sample-mutation
  "A sample mutation for testing."
  {:id #uuid "550e8400-e29b-41d4-a716-446655440000"
   :file "src/my/app.clj"
   :form-id 12345678
   :coord "3,0"
   :operator :swap-plus-minus
   :original "+"
   :replacement "-"
   :line 42
   :column 10})

(defn make-result
  "Create a mutation result with the given status."
  ([status]
   (make-result status sample-mutation))
  ([status mutation]
   {:mutation mutation
    :status status
    :tests-run #{'my.app-test/test-add}
    :duration-ms 150}))

(def empty-results [])

(def all-killed-results
  [(make-result :killed)
   (make-result :killed)
   (make-result :killed)])

(def all-survived-results
  [(make-result :survived)
   (make-result :survived)])

(def mixed-results
  "Mixed results with various statuses."
  [(make-result :killed)
   (make-result :killed)
   (make-result :killed)
   (make-result :survived)
   (make-result :survived)
   (make-result :no-coverage)
   (make-result :timeout)
   (make-result :error)])

;; =============================================================================
;; count-by-status Tests
;; =============================================================================

(deftest count-by-status-empty-test
  (testing "empty results return zero counts"
    (let [counts (reporter/count-by-status empty-results)]
      (is (= 0 (:killed counts)))
      (is (= 0 (:survived counts)))
      (is (= 0 (:no-coverage counts)))
      (is (= 0 (:timeout counts)))
      (is (= 0 (:error counts))))))

(deftest count-by-status-all-killed-test
  (testing "all killed mutations"
    (let [counts (reporter/count-by-status all-killed-results)]
      (is (= 3 (:killed counts)))
      (is (= 0 (:survived counts))))))

(deftest count-by-status-mixed-test
  (testing "mixed status results"
    (let [counts (reporter/count-by-status mixed-results)]
      (is (= 3 (:killed counts)))
      (is (= 2 (:survived counts)))
      (is (= 1 (:no-coverage counts)))
      (is (= 1 (:timeout counts)))
      (is (= 1 (:error counts))))))

;; =============================================================================
;; mutation-score Tests
;; =============================================================================

(deftest mutation-score-empty-test
  (testing "empty results return nil"
    (is (nil? (reporter/mutation-score empty-results)))))

(deftest mutation-score-all-killed-test
  (testing "all killed mutations return 1.0 (100% score)"
    (is (= 1.0 (reporter/mutation-score all-killed-results)))))

(deftest mutation-score-all-survived-test
  (testing "all survived mutations return 0.0 (0% score)"
    (is (= 0.0 (reporter/mutation-score all-survived-results)))))

(deftest mutation-score-mixed-test
  (testing "mixed killed/survived returns correct ratio"
    ;; 3 killed, 2 survived = 3/5 = 0.6
    (let [score (reporter/mutation-score mixed-results)]
      (is (= 0.6 score)))))

(deftest mutation-score-excludes-non-killable-test
  (testing "no-coverage, timeout, and error don't affect score"
    ;; Results with only no-coverage/timeout/error should return nil
    (let [non-killable [(make-result :no-coverage)
                        (make-result :timeout)
                        (make-result :error)]]
      (is (nil? (reporter/mutation-score non-killable))
          "Score should be nil when no killable mutations exist"))))

(deftest mutation-score-precise-calculation-test
  (testing "score calculation is precise"
    (let [results (concat (repeat 7 (make-result :killed))
                          (repeat 3 (make-result :survived)))]
      (is (= 0.7 (reporter/mutation-score results))))))

;; =============================================================================
;; Filtering Function Tests
;; =============================================================================

(deftest survivors-filter-test
  (testing "survivors returns only survived mutations"
    (let [result (reporter/survivors mixed-results)]
      (is (= 2 (count result)))
      (is (every? #(= :survived (:status %)) result)))))

(deftest killed-filter-test
  (testing "killed returns only killed mutations"
    (let [result (reporter/killed mixed-results)]
      (is (= 3 (count result)))
      (is (every? #(= :killed (:status %)) result)))))

(deftest no-coverage-filter-test
  (testing "no-coverage returns only no-coverage mutations"
    (let [result (reporter/no-coverage mixed-results)]
      (is (= 1 (count result)))
      (is (every? #(= :no-coverage (:status %)) result)))))

(deftest timeouts-filter-test
  (testing "timeouts returns only timeout mutations"
    (let [result (reporter/timeouts mixed-results)]
      (is (= 1 (count result)))
      (is (every? #(= :timeout (:status %)) result)))))

(deftest errors-filter-test
  (testing "errors returns only error mutations"
    (let [result (reporter/errors mixed-results)]
      (is (= 1 (count result)))
      (is (every? #(= :error (:status %)) result)))))

(deftest filters-empty-results-test
  (testing "all filters return empty seq for empty results"
    (is (empty? (reporter/survivors empty-results)))
    (is (empty? (reporter/killed empty-results)))
    (is (empty? (reporter/no-coverage empty-results)))
    (is (empty? (reporter/timeouts empty-results)))
    (is (empty? (reporter/errors empty-results)))))

;; =============================================================================
;; summary-data Tests
;; =============================================================================

(deftest summary-data-empty-test
  (testing "summary-data with empty results"
    (let [data (reporter/summary-data empty-results)]
      (is (= 0 (:total data)))
      (is (= {:killed 0 :survived 0 :no-coverage 0 :timeout 0 :error 0}
             (:counts data)))
      (is (nil? (:score data)))
      (is (nil? (:score-percentage data))))))

(deftest summary-data-mixed-test
  (testing "summary-data with mixed results"
    (let [data (reporter/summary-data mixed-results)]
      (is (= 8 (:total data)))
      (is (= 3 (get-in data [:counts :killed])))
      (is (= 2 (get-in data [:counts :survived])))
      (is (= 0.6 (:score data)))
      (is (= 60.0 (:score-percentage data))))))

(deftest summary-data-all-killed-test
  (testing "summary-data with perfect score"
    (let [data (reporter/summary-data all-killed-results)]
      (is (= 3 (:total data)))
      (is (= 1.0 (:score data)))
      (is (= 100.0 (:score-percentage data))))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest single-killed-mutation-test
  (testing "single killed mutation has 100% score"
    (let [results [(make-result :killed)]]
      (is (= 1.0 (reporter/mutation-score results))))))

(deftest single-survived-mutation-test
  (testing "single survived mutation has 0% score"
    (let [results [(make-result :survived)]]
      (is (= 0.0 (reporter/mutation-score results))))))

(deftest many-mutations-test
  (testing "score calculation with many mutations"
    (let [results (concat (repeat 850 (make-result :killed))
                          (repeat 150 (make-result :survived)))]
      (is (= 0.85 (reporter/mutation-score results))))))

(deftest mixed-with-only-non-killable-test
  (testing "only non-killable statuses return nil score"
    (let [results [(make-result :no-coverage)
                   (make-result :no-coverage)
                   (make-result :timeout)]]
      (is (nil? (reporter/mutation-score results))))))

(deftest results-with-different-mutations-test
  (testing "results with different mutation data"
    (let [mutation1 (assoc sample-mutation :line 10 :operator :swap-plus-minus)
          mutation2 (assoc sample-mutation :line 20 :operator :negate-conditional)
          mutation3 (assoc sample-mutation :line 30 :operator :return-null)
          results [(make-result :killed mutation1)
                   (make-result :survived mutation2)
                   (make-result :killed mutation3)]
          survivors (reporter/survivors results)]
      (is (= 1 (count survivors)))
      (is (= 20 (get-in (first survivors) [:mutation :line]))))))

;; =============================================================================
;; HTML Report Generation Tests
;; =============================================================================

(deftest generate-html-report-creates-file-test
  (testing "generate-html-report creates an HTML file"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report mixed-results path)
        (is (.exists tmp-file)
            "HTML file should be created")
        (is (pos? (.length tmp-file))
            "HTML file should not be empty")
        (finally
          (.delete tmp-file))))))

(deftest generate-html-report-contains-doctype-test
  (testing "generated HTML contains proper DOCTYPE"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report mixed-results path)
        (let [content (slurp path)]
          (is (.startsWith content "<!DOCTYPE html>")
              "HTML should start with DOCTYPE"))
        (finally
          (.delete tmp-file))))))

(deftest generate-html-report-contains-score-test
  (testing "generated HTML contains mutation score"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report mixed-results path)
        (let [content (slurp path)]
          (is (.contains content "60.0%")
              "HTML should contain the mutation score (60.0%)"))
        (finally
          (.delete tmp-file))))))

(deftest generate-html-report-contains-survivors-test
  (testing "generated HTML contains surviving mutations section"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report mixed-results path)
        (let [content (slurp path)]
          (is (.contains content "Surviving Mutations")
              "HTML should contain survivors section"))
        (finally
          (.delete tmp-file))))))

(deftest write-report-terminal-test
  (testing "write-report! with :terminal format prints to stdout"
    (let [output (with-out-str (reporter/write-report! mixed-results :terminal nil))]
      (is (.contains output "Mutation Testing Results")
          "Terminal output should contain results header"))))

(deftest write-report-html-test
  (testing "write-report! with :html format creates file"
    (let [tmp-dir (java.io.File/createTempFile "heretic-report" "")
          dir-path (.getPath tmp-dir)]
      (.delete tmp-dir)  ; Remove file so we can create dir
      (try
        (let [result (reporter/write-report! mixed-results :html dir-path)]
          (is (.endsWith result "index.html")
              "Result should be path to index.html")
          (is (.exists (java.io.File. result))
              "HTML file should exist"))
        (finally
          ;; Clean up
          (doseq [f (reverse (file-seq (java.io.File. dir-path)))]
            (.delete f)))))))

(deftest write-report-unknown-format-test
  (testing "write-report! with unknown format throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown report format"
                          (reporter/write-report! mixed-results :unknown nil)))))

;; =============================================================================
;; Test Effectiveness Reporting Tests
;; =============================================================================

(defn make-killed-result
  "Create a killed mutation result with the specified killer test."
  [killer-test tests-run mutation]
  {:mutation mutation
   :status :killed
   :killed-by killer-test
   :tests-run tests-run
   :duration-ms 100})

(def effectiveness-test-results
  "Results for testing test effectiveness features.
   - test-a kills 3 mutations
   - test-b kills 2 mutations
   - test-c kills 1 mutation
   - test-d runs but never kills anything (ineffective)"
  (let [test-a 'my.app-test/test-a
        test-b 'my.app-test/test-b
        test-c 'my.app-test/test-c
        test-d 'my.app-test/test-d
        all-tests #{test-a test-b test-c test-d}
        m1 (assoc sample-mutation :line 1)
        m2 (assoc sample-mutation :line 2)
        m3 (assoc sample-mutation :line 3)
        m4 (assoc sample-mutation :line 4)
        m5 (assoc sample-mutation :line 5)
        m6 (assoc sample-mutation :line 6)
        m7 (assoc sample-mutation :line 7)]
    [(make-killed-result test-a all-tests m1)   ; test-a kill 1
     (make-killed-result test-a all-tests m2)   ; test-a kill 2
     (make-killed-result test-a all-tests m3)   ; test-a kill 3
     (make-killed-result test-b all-tests m4)   ; test-b kill 1
     (make-killed-result test-b all-tests m5)   ; test-b kill 2
     (make-killed-result test-c all-tests m6)   ; test-c kill 1
     {:mutation m7                               ; survived - all tests ran but none killed
      :status :survived
      :tests-run all-tests
      :duration-ms 100}]))

(deftest generate-html-report-contains-test-effectiveness-test
  (testing "generated HTML contains test effectiveness section"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report effectiveness-test-results path)
        (let [content (slurp path)]
          (is (.contains content "Test Effectiveness")
              "HTML should contain Test Effectiveness section")
          (is (.contains content "Top Tests by Kill Count")
              "HTML should contain top tests subsection")
          (is (.contains content "Ineffective Tests")
              "HTML should contain ineffective tests subsection"))
        (finally
          (.delete tmp-file))))))

(deftest generate-html-report-shows-kill-counts-test
  (testing "generated HTML shows correct kill counts for tests"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report effectiveness-test-results path)
        (let [content (slurp path)]
          (is (.contains content "3 kills")
              "HTML should show test-a with 3 kills")
          (is (.contains content "2 kills")
              "HTML should show test-b with 2 kills")
          (is (.contains content "1 kills")
              "HTML should show test-c with 1 kill"))
        (finally
          (.delete tmp-file))))))

(deftest generate-html-report-shows-ineffective-tests-test
  (testing "generated HTML shows tests that ran but never killed"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".html")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-html-report effectiveness-test-results path)
        (let [content (slurp path)]
          (is (.contains content "test-d")
              "HTML should show test-d as ineffective")
          (is (.contains content "0 kills")
              "HTML should show 0 kills for ineffective test"))
        (finally
          (.delete tmp-file))))))

(deftest print-test-effectiveness-outputs-top-tests-test
  (testing "print-test-effectiveness outputs top tests by kill count"
    (let [output (with-out-str (reporter/print-test-effectiveness effectiveness-test-results))]
      (is (.contains output "Test Effectiveness")
          "Output should contain Test Effectiveness header")
      (is (.contains output "Top Tests by Kill Count")
          "Output should contain top tests section")
      (is (.contains output "test-a")
          "Output should contain test-a (top killer)")
      (is (.contains output "3 kills")
          "Output should show test-a with 3 kills"))))

(deftest print-test-effectiveness-outputs-ineffective-tests-test
  (testing "print-test-effectiveness outputs ineffective tests"
    (let [output (with-out-str (reporter/print-test-effectiveness effectiveness-test-results))]
      (is (.contains output "never killed")
          "Output should mention tests that never killed")
      (is (.contains output "test-d")
          "Output should show test-d as ineffective"))))

(deftest print-test-effectiveness-empty-results-test
  (testing "print-test-effectiveness with no killed mutations produces no output"
    (let [output (with-out-str (reporter/print-test-effectiveness all-survived-results))]
      (is (= "" output)
          "No output when there are no killed mutations"))))

(deftest print-report-includes-test-effectiveness-test
  (testing "print-report includes test effectiveness section"
    (let [output (with-out-str (reporter/print-report effectiveness-test-results))]
      (is (.contains output "Test Effectiveness")
          "Full report should include test effectiveness section"))))

;; =============================================================================
;; JSON Report Generation Tests
;; =============================================================================

(deftest json-report-data-structure-test
  (testing "json-report-data returns correct structure"
    (let [data (reporter/json-report-data mixed-results)]
      (is (map? data) "Should return a map")
      (is (contains? data :summary) "Should contain :summary")
      (is (contains? data :survivors) "Should contain :survivors")
      (is (contains? data :byFile) "Should contain :byFile"))))

(deftest json-report-data-summary-test
  (testing "json-report-data summary contains correct values"
    (let [data (reporter/json-report-data mixed-results)
          summary (:summary data)]
      (is (= 8 (:total summary)) "Total should be 8")
      (is (= 3 (:killed summary)) "Killed should be 3")
      (is (= 2 (:survived summary)) "Survived should be 2")
      (is (= 1 (:noCoverage summary)) "noCoverage should be 1")
      (is (= 1 (:timeout summary)) "timeout should be 1")
      (is (= 1 (:error summary)) "error should be 1")
      (is (= 0.6 (:score summary)) "Score should be 0.6"))))

(deftest json-report-data-survivors-test
  (testing "json-report-data survivors list is correct"
    (let [data (reporter/json-report-data mixed-results)
          survivors (:survivors data)]
      (is (= 2 (count survivors)) "Should have 2 survivors")
      (let [survivor (first survivors)]
        (is (string? (:file survivor)) "Survivor file should be a string")
        (is (number? (:line survivor)) "Survivor line should be a number")
        (is (string? (:operator survivor)) "Survivor operator should be a string")
        (is (string? (:original survivor)) "Survivor original should be a string")
        (is (string? (:replacement survivor)) "Survivor replacement should be a string")
        (is (vector? (:testsRun survivor)) "Survivor testsRun should be a vector")))))

(deftest json-report-data-by-file-test
  (testing "json-report-data byFile contains correct stats"
    (let [data (reporter/json-report-data mixed-results)
          by-file (:byFile data)]
      (is (map? by-file) "byFile should be a map")
      (is (contains? by-file "src/my/app.clj") "Should contain the file path"))))

(deftest generate-json-report-creates-file-test
  (testing "generate-json-report creates a JSON file"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".json")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-json-report mixed-results path)
        (is (.exists tmp-file)
            "JSON file should be created")
        (is (pos? (.length tmp-file))
            "JSON file should not be empty")
        (finally
          (.delete tmp-file))))))

(deftest generate-json-report-valid-json-test
  (testing "generated JSON is valid and parseable"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".json")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-json-report mixed-results path)
        (let [content (slurp path)]
          ;; Check it starts with { and ends with }
          (is (.startsWith (.trim content) "{")
              "JSON should start with {")
          (is (.endsWith (.trim content) "}")
              "JSON should end with }")
          ;; Check it contains expected keys
          (is (.contains content "\"summary\"")
              "JSON should contain summary key")
          (is (.contains content "\"survivors\"")
              "JSON should contain survivors key")
          (is (.contains content "\"byFile\"")
              "JSON should contain byFile key"))
        (finally
          (.delete tmp-file))))))

(deftest generate-json-report-contains-summary-values-test
  (testing "generated JSON contains correct summary values"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".json")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-json-report mixed-results path)
        (let [content (slurp path)]
          (is (.contains content "\"total\": 8")
              "JSON should contain total: 8")
          (is (.contains content "\"killed\": 3")
              "JSON should contain killed: 3")
          (is (.contains content "\"survived\": 2")
              "JSON should contain survived: 2")
          (is (.contains content "\"score\": 0.6")
              "JSON should contain score: 0.6"))
        (finally
          (.delete tmp-file))))))

(deftest generate-json-report-contains-survivor-details-test
  (testing "generated JSON contains survivor mutation details"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".json")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-json-report mixed-results path)
        (let [content (slurp path)]
          (is (.contains content "src/my/app.clj")
              "JSON should contain file path")
          (is (.contains content "\"line\": 42")
              "JSON should contain line number")
          (is (.contains content "\"operator\": \"swap-plus-minus\"")
              "JSON should contain operator name"))
        (finally
          (.delete tmp-file))))))

(deftest write-report-json-test
  (testing "write-report! with :json format creates file"
    (let [tmp-dir (java.io.File/createTempFile "heretic-report" "")
          dir-path (.getPath tmp-dir)]
      (.delete tmp-dir)  ; Remove file so we can create dir
      (try
        (let [result (reporter/write-report! mixed-results :json dir-path)]
          (is (.endsWith result "report.json")
              "Result should be path to report.json")
          (is (.exists (java.io.File. result))
              "JSON file should exist"))
        (finally
          ;; Clean up
          (doseq [f (reverse (file-seq (java.io.File. dir-path)))]
            (.delete f)))))))

(deftest json-report-empty-results-test
  (testing "json-report-data handles empty results"
    (let [data (reporter/json-report-data empty-results)
          summary (:summary data)]
      (is (= 0 (:total summary)) "Total should be 0")
      (is (= 0 (:killed summary)) "Killed should be 0")
      (is (nil? (:score summary)) "Score should be nil")
      (is (empty? (:survivors data)) "Survivors should be empty"))))

(deftest json-report-all-killed-test
  (testing "json-report-data with all killed mutations"
    (let [data (reporter/json-report-data all-killed-results)
          summary (:summary data)]
      (is (= 3 (:total summary)) "Total should be 3")
      (is (= 3 (:killed summary)) "Killed should be 3")
      (is (= 1.0 (:score summary)) "Score should be 1.0")
      (is (empty? (:survivors data)) "Survivors should be empty"))))

;; =============================================================================
;; EDN Report Generation Tests
;; =============================================================================

(deftest edn-report-data-structure-test
  (testing "edn-report-data returns correct structure"
    (let [data (reporter/edn-report-data mixed-results)]
      (is (map? data) "Should return a map")
      (is (contains? data :summary) "Should contain :summary")
      (is (contains? data :survivors) "Should contain :survivors")
      (is (contains? data :by-file) "Should contain :by-file"))))

(deftest edn-report-data-summary-test
  (testing "edn-report-data summary contains correct values"
    (let [data (reporter/edn-report-data mixed-results)
          summary (:summary data)]
      (is (= 8 (:total summary)) "Total should be 8")
      (is (= 3 (:killed summary)) "Killed should be 3")
      (is (= 2 (:survived summary)) "Survived should be 2")
      (is (= 1 (:no-coverage summary)) "no-coverage should be 1")
      (is (= 1 (:timeout summary)) "timeout should be 1")
      (is (= 1 (:error summary)) "error should be 1")
      (is (= 0.6 (:score summary)) "Score should be 0.6"))))

(deftest edn-report-data-survivors-test
  (testing "edn-report-data survivors list is correct"
    (let [data (reporter/edn-report-data mixed-results)
          survivors (:survivors data)]
      (is (= 2 (count survivors)) "Should have 2 survivors")
      (let [survivor (first survivors)]
        (is (string? (:file survivor)) "Survivor file should be a string")
        (is (number? (:line survivor)) "Survivor line should be a number")
        (is (keyword? (:operator survivor)) "Survivor operator should be a keyword")
        (is (string? (:original survivor)) "Survivor original should be a string")
        (is (string? (:replacement survivor)) "Survivor replacement should be a string")
        (is (vector? (:tests-run survivor)) "Survivor tests-run should be a vector")))))

(deftest edn-report-data-by-file-test
  (testing "edn-report-data by-file contains correct stats"
    (let [data (reporter/edn-report-data mixed-results)
          by-file (:by-file data)]
      (is (map? by-file) "by-file should be a map")
      (is (contains? by-file "src/my/app.clj") "Should contain the file path"))))

(deftest generate-edn-report-creates-file-test
  (testing "generate-edn-report creates an EDN file"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".edn")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-edn-report mixed-results path)
        (is (.exists tmp-file)
            "EDN file should be created")
        (is (pos? (.length tmp-file))
            "EDN file should not be empty")
        (finally
          (.delete tmp-file))))))

(deftest generate-edn-report-valid-edn-test
  (testing "generated EDN is valid and readable"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".edn")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-edn-report mixed-results path)
        (let [content (slurp path)
              parsed (edn/read-string content)]
          ;; Check structure
          (is (map? parsed) "Parsed EDN should be a map")
          (is (contains? parsed :summary) "EDN should contain :summary")
          (is (contains? parsed :survivors) "EDN should contain :survivors")
          (is (contains? parsed :by-file) "EDN should contain :by-file")
          ;; Check summary values
          (is (= 8 (get-in parsed [:summary :total])) "Total should be 8")
          (is (= 3 (get-in parsed [:summary :killed])) "Killed should be 3")
          (is (= 0.6 (get-in parsed [:summary :score])) "Score should be 0.6"))
        (finally
          (.delete tmp-file))))))

(deftest generate-edn-report-uses-kebab-case-test
  (testing "generated EDN uses kebab-case keys (idiomatic Clojure)"
    (let [tmp-file (java.io.File/createTempFile "heretic-test" ".edn")
          path (.getPath tmp-file)]
      (try
        (reporter/generate-edn-report mixed-results path)
        (let [content (slurp path)
              parsed (edn/read-string content)]
          ;; Check for kebab-case keys
          (is (contains? (:summary parsed) :no-coverage)
              "EDN should use :no-coverage (kebab-case)")
          (is (contains? parsed :by-file)
              "EDN should use :by-file (kebab-case)")
          ;; Check survivor uses tests-run not testsRun
          (when (seq (:survivors parsed))
            (is (contains? (first (:survivors parsed)) :tests-run)
                "Survivor should use :tests-run (kebab-case)")))
        (finally
          (.delete tmp-file))))))

(deftest write-report-edn-test
  (testing "write-report! with :edn format creates file"
    (let [tmp-dir (java.io.File/createTempFile "heretic-report" "")
          dir-path (.getPath tmp-dir)]
      (.delete tmp-dir)  ; Remove file so we can create dir
      (try
        (let [result (reporter/write-report! mixed-results :edn dir-path)]
          (is (.endsWith result "report.edn")
              "Result should be path to report.edn")
          (is (.exists (java.io.File. result))
              "EDN file should exist"))
        (finally
          ;; Clean up
          (doseq [f (reverse (file-seq (java.io.File. dir-path)))]
            (.delete f)))))))

(deftest edn-report-empty-results-test
  (testing "edn-report-data handles empty results"
    (let [data (reporter/edn-report-data empty-results)
          summary (:summary data)]
      (is (= 0 (:total summary)) "Total should be 0")
      (is (= 0 (:killed summary)) "Killed should be 0")
      (is (nil? (:score summary)) "Score should be nil")
      (is (empty? (:survivors data)) "Survivors should be empty"))))

(deftest edn-report-all-killed-test
  (testing "edn-report-data with all killed mutations"
    (let [data (reporter/edn-report-data all-killed-results)
          summary (:summary data)]
      (is (= 3 (:total summary)) "Total should be 3")
      (is (= 3 (:killed summary)) "Killed should be 3")
      (is (= 1.0 (:score summary)) "Score should be 1.0")
      (is (empty? (:survivors data)) "Survivors should be empty"))))

(deftest edn-report-preserves-operator-keyword-test
  (testing "EDN report preserves operator as keyword (unlike JSON which uses string)"
    (let [data (reporter/edn-report-data mixed-results)]
      (when (seq (:survivors data))
        (let [survivor-operator (:operator (first (:survivors data)))]
          (is (keyword? survivor-operator)
              "Operator should be a keyword in EDN format"))))))

;; =============================================================================
;; Survivor-triage verdict in reports (golden-shape) — coverage-gap-triage Phase 2
;; =============================================================================

(def triaged-survivor-results
  "One survived result per triage arm (all five), with the verdict merged on (the
   shape core/triage-survivors! produces)."
  [(merge (make-result :survived) {:triage :coverage-gap         :witness [1 2]})
   (merge (make-result :survived) {:triage :proven-equivalent    :proof   :read-identity})
   (merge (make-result :survived) {:triage :candidate-equivalent :trials  200})
   (merge (make-result :survived) {:triage :not-applicable       :reason  :impure})
   (merge (make-result :survived) {:triage :undetermined         :reason  :ns-not-loaded})])

(def ^:private base-json-entry
  {:file "src/my/app.clj" :line 42 :column 10 :operator "swap-plus-minus"
   :original "+" :replacement "-" :testsRun ["my.app-test/test-add"]})

(deftest json-report-survivors-carry-triage-verdict
  (testing "each JSON survivor entry carries its serialized triage verdict (pinned shape)"
    (is (= [(assoc base-json-entry :triage "coverage-gap"         :witness "[1 2]")
            (assoc base-json-entry :triage "proven-equivalent"    :proof   "read-identity")
            (assoc base-json-entry :triage "candidate-equivalent" :trials  200)
            (assoc base-json-entry :triage "not-applicable"       :reason  "impure")
            (assoc base-json-entry :triage "undetermined"         :reason  "ns-not-loaded")]
           (:survivors (reporter/json-report-data triaged-survivor-results))))))

(deftest edn-report-survivors-carry-triage-verdict
  (testing "EDN keeps the verdict labels as keywords (like :operator); witness is pr-str'd"
    (let [survs (:survivors (reporter/edn-report-data triaged-survivor-results))]
      (is (= [{:triage :coverage-gap         :witness "[1 2]"}
              {:triage :proven-equivalent    :proof   :read-identity}
              {:triage :candidate-equivalent :trials  200}
              {:triage :not-applicable       :reason  :impure}
              {:triage :undetermined         :reason  :ns-not-loaded}]
             (mapv #(select-keys % [:triage :witness :proof :reason :trials]) survs))))))

(deftest edn-report-round-trips-triage
  (testing "generate-edn-report writes a file whose survivors carry the triage verdict"
    (let [tmp (java.io.File/createTempFile "heretic-report" ".edn")]
      (try
        (reporter/generate-edn-report triaged-survivor-results (.getPath tmp))
        (let [survs (:survivors (edn/read-string (slurp tmp)))]
          (is (= :coverage-gap (:triage (first survs))))
          (is (= "[1 2]" (:witness (first survs))))
          (is (= :read-identity (:proof (second survs)))))
        (finally (.delete tmp))))))

(deftest reports-omit-triage-when-absent
  (testing "a survivor without a triage verdict (triage disabled) gains no triage keys"
    (let [j (first (:survivors (reporter/json-report-data [(make-result :survived)])))
          e (first (:survivors (reporter/edn-report-data [(make-result :survived)])))]
      (doseq [m [j e] k [:triage :witness :proof :reason :trials]]
        (is (not (contains? m k)) (str "no " k " when triage didn't run"))))))

(deftest html-report-renders-triage-badges-and-lines
  (testing "HTML carries a badge class + human label per arm, and each arm's own detail line"
    (let [tmp  (java.io.File/createTempFile "heretic-report" ".html")]
      (try
        (reporter/generate-html-report triaged-survivor-results (.getPath tmp))
        (let [html (slurp tmp)
              has? (fn [s] (str/includes? html s))
              n    (fn [s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) html)))]
          (testing "rendered badge class (not the CSS rule) + human label for every arm"
            (doseq [[cls label] [["triage-coverage-gap" "COVERAGE GAP"]
                                 ["triage-proven-equivalent" "EQUIVALENT"]
                                 ["triage-candidate-equivalent" "LIKELY EQUIV"]
                                 ["triage-not-applicable" "N/A"]
                                 ["triage-undetermined" "UNDETERMINED"]]]
              ;; "triage-badge <cls>" is the rendered <span> attribute — discriminating
              ;; from the bare ".cls {" CSS rule, which is present even with no survivors.
              (is (has? (str "triage-badge " cls)) (str "rendered badge class " cls))
              (is (has? label) (str "badge label " label))))
          (testing "each arm renders exactly its own detail line, no spurious extras"
            ;; The tagged-verdict shape gives one arm field per survivor; pin that the
            ;; counts match the fixture (1 witness, 1 proof, 2 reason-only arms) rather
            ;; than claiming a suppression-precedence the valid shape never triggers.
            (is (= 1 (n "Witness: ")) "only the coverage-gap arm")
            (is (= 1 (n "Proof: ")) "only the proven-equivalent arm")
            (is (= 2 (n "Reason: ")) "the not-applicable + undetermined arms")))
        (finally (.delete tmp))))))
