(ns heretic.subsumption-test
  "Tests for heretic.subsumption kill pattern analysis.

   Tests cover:
   - analyze-kill-patterns: Grouping mutants by killer test
   - find-subsumed: Identifying subsumption candidates
   - dominant-tests: Finding high-value tests
   - subsumption-stats: Statistical analysis
   - test-effectiveness-report: Test effectiveness analysis
   - build-kill-matrix: Kill matrix construction"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.subsumption :as sub]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

;; Mock mutation results representing different kill patterns
(def mock-results
  [;; Mutant 1: killed by test-a
   {:mutation {:form-id 100 :coord [0] :operator :swap-plus-minus :file "a.clj" :line 10}
    :status :killed
    :killed-by 'test.ns/test-a
    :tests-run #{'test.ns/test-a 'test.ns/test-b}}

   ;; Mutant 2: killed by test-a (same killer as mutant 1)
   {:mutation {:form-id 101 :coord [1] :operator :swap-plus-minus :file "a.clj" :line 15}
    :status :killed
    :killed-by 'test.ns/test-a
    :tests-run #{'test.ns/test-a}}

   ;; Mutant 3: killed by test-b (different killer)
   {:mutation {:form-id 102 :coord [0] :operator :negate-conditional :file "b.clj" :line 20}
    :status :killed
    :killed-by 'test.ns/test-b
    :tests-run #{'test.ns/test-a 'test.ns/test-b}}

   ;; Mutant 4: survived (no killer)
   {:mutation {:form-id 103 :coord [2] :operator :swap-plus-minus :file "b.clj" :line 25}
    :status :survived
    :killed-by nil
    :tests-run #{'test.ns/test-a 'test.ns/test-b}}

   ;; Mutant 5: no coverage
   {:mutation {:form-id 104 :coord [0] :operator :delete-call :file "c.clj" :line 30}
    :status :no-coverage
    :killed-by nil
    :tests-run #{}}

   ;; Mutant 6: killed by test-c
   {:mutation {:form-id 105 :coord [0] :operator :swap-plus-minus :file "a.clj" :line 40}
    :status :killed
    :killed-by 'test.ns/test-c
    :tests-run #{'test.ns/test-c}}])

;; =============================================================================
;; analyze-kill-patterns Tests
;; =============================================================================

(deftest analyze-kill-patterns-groups-by-killer-test
  (testing "Groups killed mutants by the test that killed them"
    (let [patterns (sub/analyze-kill-patterns mock-results)]
      (is (= 3 (count (keys patterns)))
          "Should have 3 unique killers: test-a, test-b, test-c")
      (is (contains? patterns 'test.ns/test-a))
      (is (contains? patterns 'test.ns/test-b))
      (is (contains? patterns 'test.ns/test-c)))))

(deftest analyze-kill-patterns-counts-correctly-test
  (testing "Counts kills per test correctly"
    (let [patterns (sub/analyze-kill-patterns mock-results)]
      (is (= 2 (count (get patterns 'test.ns/test-a)))
          "test-a killed 2 mutants")
      (is (= 1 (count (get patterns 'test.ns/test-b)))
          "test-b killed 1 mutant")
      (is (= 1 (count (get patterns 'test.ns/test-c)))
          "test-c killed 1 mutant"))))

(deftest analyze-kill-patterns-excludes-non-killed-test
  (testing "Excludes survived and no-coverage mutants"
    (let [patterns (sub/analyze-kill-patterns mock-results)
          all-mutants (apply concat (vals patterns))]
      (is (every? #(= :killed (:status %)) all-mutants)
          "All grouped mutants should have :killed status"))))

(deftest analyze-kill-patterns-empty-results-test
  (testing "Returns empty map for empty results"
    (let [patterns (sub/analyze-kill-patterns [])]
      (is (= {} patterns)))))

(deftest analyze-kill-patterns-no-kills-test
  (testing "Returns empty map when no mutants were killed"
    (let [survived-only [{:status :survived :killed-by nil}
                         {:status :no-coverage :killed-by nil}]
          patterns (sub/analyze-kill-patterns survived-only)]
      (is (= {} patterns)))))

;; =============================================================================
;; mutation-identity Tests
;; =============================================================================

(deftest mutation-identity-extracts-key-fields-test
  (testing "Extracts identifying fields from mutation"
    (let [result (first mock-results)
          identity (sub/mutation-identity result)]
      (is (= 100 (:form-id identity)))
      (is (= [0] (:coord identity)))
      (is (= :swap-plus-minus (:operator identity)))
      (is (= "a.clj" (:file identity)))
      (is (= 10 (:line identity))))))

;; =============================================================================
;; find-subsumed Tests
;; =============================================================================

(deftest find-subsumed-returns-test-kill-data-test
  (testing "Returns data about what each test killed"
    (let [subsumed (sub/find-subsumed mock-results)]
      (is (= 2 (:count (get subsumed 'test.ns/test-a))))
      (is (= 1 (:count (get subsumed 'test.ns/test-b))))
      (is (= 1 (:count (get subsumed 'test.ns/test-c)))))))

(deftest find-subsumed-excludes-nil-killer-test
  (testing "Excludes entries with nil killed-by"
    (let [subsumed (sub/find-subsumed mock-results)]
      (is (not (contains? subsumed nil))))))

(deftest find-subsumed-includes-mutant-details-test
  (testing "Includes full mutant details in results"
    (let [subsumed (sub/find-subsumed mock-results)
          test-a-mutants (:mutants (get subsumed 'test.ns/test-a))]
      (is (= 2 (count test-a-mutants)))
      (is (every? :mutation test-a-mutants)
          "Each mutant should have :mutation field"))))

;; =============================================================================
;; dominant-tests Tests
;; =============================================================================

(deftest dominant-tests-returns-top-killers-test
  (testing "Returns tests sorted by kill count"
    (let [dominant (sub/dominant-tests mock-results)]
      (is (vector? dominant))
      ;; test-a killed 2, should be first
      (is (= 'test.ns/test-a (first (first dominant))))
      (is (= 2 (second (first dominant)))))))

(deftest dominant-tests-respects-limit-test
  (testing "Respects the n parameter"
    (let [dominant (sub/dominant-tests mock-results 2)]
      (is (= 2 (count dominant))))))

(deftest dominant-tests-handles-empty-results-test
  (testing "Returns empty vector for empty results"
    (let [dominant (sub/dominant-tests [])]
      (is (= [] dominant)))))

;; =============================================================================
;; mutants-killed-by-test Tests
;; =============================================================================

(deftest mutants-killed-by-test-returns-correct-mutants-test
  (testing "Returns mutants killed by specific test"
    (let [killed (sub/mutants-killed-by-test mock-results 'test.ns/test-a)]
      (is (= 2 (count killed)))
      (is (every? #(= 'test.ns/test-a (:killed-by %)) killed)))))

(deftest mutants-killed-by-test-returns-empty-for-unknown-test
  (testing "Returns empty for unknown test"
    (let [killed (sub/mutants-killed-by-test mock-results 'test.ns/unknown)]
      (is (empty? killed)))))

;; =============================================================================
;; subsumption-stats Tests
;; =============================================================================

(deftest subsumption-stats-total-killed-test
  (testing "Counts total killed correctly"
    (let [stats (sub/subsumption-stats mock-results)]
      (is (= 4 (:total-killed stats))
          "4 mutants were killed in mock-results"))))

(deftest subsumption-stats-unique-killers-test
  (testing "Counts unique killer tests"
    (let [stats (sub/subsumption-stats mock-results)]
      (is (= 3 (:unique-killers stats))
          "3 unique tests killed mutants"))))

(deftest subsumption-stats-dominant-tests-test
  (testing "Includes dominant tests"
    (let [stats (sub/subsumption-stats mock-results)]
      (is (vector? (:dominant-tests stats)))
      (is (pos? (count (:dominant-tests stats)))))))

(deftest subsumption-stats-potential-savings-test
  (testing "Calculates potential savings"
    (let [stats (sub/subsumption-stats mock-results)]
      (is (number? (:potential-savings stats)))
      (is (<= 0.0 (:potential-savings stats) 1.0)))))

(deftest subsumption-stats-kill-distribution-test
  (testing "Includes kill distribution"
    (let [stats (sub/subsumption-stats mock-results)]
      (is (map? (:kill-distribution stats))))))

(deftest subsumption-stats-empty-results-test
  (testing "Handles empty results gracefully"
    (let [stats (sub/subsumption-stats [])]
      (is (= 0 (:total-killed stats)))
      (is (= 0 (:unique-killers stats)))
      (is (= 0.0 (:potential-savings stats))))))

;; =============================================================================
;; test-effectiveness-report Tests
;; =============================================================================

(deftest test-effectiveness-report-effective-tests-test
  (testing "Lists effective tests with kill counts"
    (let [report (sub/test-effectiveness-report mock-results)]
      (is (vector? (:effective-tests report)))
      (is (every? :test (:effective-tests report)))
      (is (every? :kills (:effective-tests report))))))

(deftest test-effectiveness-report-ineffective-tests-test
  (testing "Lists tests that ran but never killed"
    (let [;; Add a test that ran but never killed
          results-with-ineffective
          (conj mock-results
                {:mutation {:form-id 200 :coord [0] :operator :x :file "x.clj" :line 1}
                 :status :survived
                 :killed-by nil
                 :tests-run #{'test.ns/test-d}})  ; test-d never kills anything
          report (sub/test-effectiveness-report results-with-ineffective)]
      (is (contains? (set (:ineffective-tests report)) 'test.ns/test-d)))))

(deftest test-effectiveness-report-coverage-gaps-test
  (testing "Lists mutations with no coverage"
    (let [report (sub/test-effectiveness-report mock-results)]
      (is (vector? (:coverage-gaps report)))
      ;; mock-results has 1 no-coverage mutant
      (is (= 1 (count (:coverage-gaps report)))))))

;; =============================================================================
;; build-kill-matrix Tests
;; =============================================================================

(deftest build-kill-matrix-structure-test
  (testing "Returns correct structure"
    (let [matrix (sub/build-kill-matrix mock-results)]
      (is (vector? (:mutants matrix)))
      (is (vector? (:tests matrix)))
      (is (map? (:matrix matrix))))))

(deftest build-kill-matrix-counts-test
  (testing "Contains correct number of elements"
    (let [matrix (sub/build-kill-matrix mock-results)]
      ;; 4 killed mutants
      (is (= 4 (count (:mutants matrix))))
      ;; 3 unique killers
      (is (= 3 (count (:tests matrix)))))))

(deftest build-kill-matrix-mappings-test
  (testing "Matrix entries contain test indices"
    (let [matrix (sub/build-kill-matrix mock-results)]
      ;; Each matrix entry should be a set of indices
      (is (every? set? (vals (:matrix matrix))))
      ;; Due to early exit, each mutant has exactly one killer
      (is (every? #(= 1 (count %)) (vals (:matrix matrix)))))))

(deftest build-kill-matrix-empty-results-test
  (testing "Handles empty results"
    (let [matrix (sub/build-kill-matrix [])]
      (is (= [] (:mutants matrix)))
      (is (= [] (:tests matrix)))
      (is (= {} (:matrix matrix))))))
