(ns heretic.equivalent-test
  "Tests for heretic.equivalent mutant detection.

   Tests cover:
   - Pattern detection for equivalent mutations
   - Filter function behavior
   - Statistics calculation"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.equivalent :as equiv]
            [heretic.parser :as parser]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-mutation
  "Create a test mutation with given operator and optional overrides."
  [operator & {:as overrides}]
  (merge {:operator operator
          :file "test.clj"
          :line 1
          :column 1
          :coord "0"
          :original "+"
          :replacement "-"}
         overrides))

;; =============================================================================
;; Pattern Detection Tests
;; =============================================================================

(deftest likely-equivalent-add-zero-test
  (testing "Adding zero is detected as equivalent"
    (let [zloc (-> (parser/parse-string "(+ x 0)")
                   z/down)  ; Position at +
          mutation (make-mutation :swap-plus-minus)]
      (is (some? (equiv/likely-equivalent? mutation zloc))
          "Adding zero should be detected as equivalent"))))

(deftest likely-equivalent-subtract-zero-test
  (testing "Subtracting zero is detected as equivalent"
    (let [zloc (-> (parser/parse-string "(- x 0)")
                   z/down)
          mutation (make-mutation :swap-minus-plus)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest likely-equivalent-multiply-one-test
  (testing "Multiplying by one is detected as equivalent"
    (let [zloc (-> (parser/parse-string "(* x 1)")
                   z/down)
          mutation (make-mutation :swap-mult-div)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest likely-equivalent-divide-one-test
  (testing "Dividing by one is detected as equivalent"
    (let [zloc (-> (parser/parse-string "(/ x 1)")
                   z/down)
          mutation (make-mutation :swap-div-mult)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest not-equivalent-regular-add-test
  (testing "Regular addition is not equivalent"
    (let [zloc (-> (parser/parse-string "(+ x y)")
                   z/down)
          mutation (make-mutation :swap-plus-minus)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "Regular addition should not be equivalent"))))

(deftest not-equivalent-add-non-zero-test
  (testing "Adding non-zero is not equivalent"
    (let [zloc (-> (parser/parse-string "(+ x 5)")
                   z/down)
          mutation (make-mutation :swap-plus-minus)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest not-equivalent-multiply-non-one-test
  (testing "Multiplying by non-one is not equivalent"
    (let [zloc (-> (parser/parse-string "(* x 2)")
                   z/down)
          mutation (make-mutation :swap-mult-div)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest not-equivalent-nil-parent-test
  (testing "Pattern check handles nil parent gracefully"
    ;; When zloc is at root level (no parent), should return nil not crash
    ;; This guards against mutations like (and parent ...) -> (or parent ...)
    (let [zloc (parser/parse-string "+")  ; Just a symbol, no parent
          mutation (make-mutation :swap-plus-minus)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "Should return nil when parent is nil, not crash"))))

(deftest not-equivalent-non-list-parent-test
  (testing "Pattern check handles non-list parent gracefully"
    ;; When parent exists but is not a list (e.g., vector), should return nil
    (let [zloc (-> (parser/parse-string "[+ x 0]")
                   z/down)  ; Position at +
          mutation (make-mutation :swap-plus-minus)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "Should return nil when parent is not a list"))))

;; =============================================================================
;; Boolean Pattern Tests
;; =============================================================================

(deftest likely-equivalent-and-true-test
  (testing "and with true is detected as potentially equivalent"
    (let [zloc (-> (parser/parse-string "(and true x)")
                   z/down)
          mutation (make-mutation :swap-and-or)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest likely-equivalent-or-false-test
  (testing "or with false is detected as potentially equivalent"
    (let [zloc (-> (parser/parse-string "(or false x)")
                   z/down)
          mutation (make-mutation :swap-or-and)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest not-equivalent-and-without-true-test
  (testing "and without true is not equivalent"
    (let [zloc (-> (parser/parse-string "(and x y)")
                   z/down)
          mutation (make-mutation :swap-and-or)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Filter Function Tests
;; =============================================================================

(deftest filter-equivalent-mutations-test
  (testing "filter-equivalent-mutations separates equivalent from testable"
    (let [mutations [(make-mutation :swap-plus-minus :id 1)
                     (make-mutation :swap-mult-div :id 2)
                     (make-mutation :swap-and-or :id 3)]
          ;; Mock zloc-fn that returns appropriate zippers
          zloc-fn (fn [m]
                    (case (:id m)
                      1 (-> (parser/parse-string "(+ x 0)") z/down)  ; equivalent
                      2 (-> (parser/parse-string "(* x 2)") z/down)  ; not equivalent
                      3 (-> (parser/parse-string "(and x y)") z/down)))  ; not equivalent
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      (is (= 1 (:filtered-count result))
          "Should filter one equivalent mutation")
      (is (= 2 (count (:mutations result)))
          "Should have two testable mutations"))))

(deftest filter-equivalent-mutations-empty-test
  (testing "filter-equivalent-mutations handles empty input"
    (let [result (equiv/filter-equivalent-mutations [] identity)]
      (is (= 0 (:filtered-count result)))
      (is (empty? (:mutations result))))))

(deftest filter-equivalent-mutations-all-testable-test
  (testing "filter-equivalent-mutations with no equivalents"
    (let [mutations [(make-mutation :swap-plus-minus)
                     (make-mutation :swap-minus-plus)]
          zloc-fn (fn [_] (-> (parser/parse-string "(+ x y)") z/down))
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      (is (= 0 (:filtered-count result)))
      (is (= 2 (count (:mutations result)))))))

;; =============================================================================
;; Statistics Tests
;; =============================================================================

(deftest equivalent-stats-test
  (testing "equivalent-stats calculates correctly"
    (let [stats (equiv/equivalent-stats 100 25)]
      (is (= 100 (:original-count stats)))
      (is (= 25 (:filtered-count stats)))
      (is (= 75 (:remaining-count stats)))
      (is (= 25.0 (:filtered-percentage stats))))))

(deftest equivalent-stats-zero-test
  (testing "equivalent-stats handles zero original"
    (let [stats (equiv/equivalent-stats 0 0)]
      (is (= 0 (:original-count stats)))
      (is (= 0.0 (:filtered-percentage stats))))))

(deftest equivalent-stats-all-filtered-test
  (testing "equivalent-stats handles all filtered"
    (let [stats (equiv/equivalent-stats 50 50)]
      (is (= 50 (:filtered-count stats)))
      (is (= 0 (:remaining-count stats)))
      (is (= 100.0 (:filtered-percentage stats))))))

;; =============================================================================
;; Quick Check Tests
;; =============================================================================

(deftest quick-equivalent-check-test
  (testing "quick-equivalent-check detects patterns from mutation data"
    ;; This is a heuristic check, so it may not always match
    (let [mutation (make-mutation :replace-0-to-1 :file "src/default_config.clj")]
      ;; The quick check looks for "default" in filename
      (is (some? (equiv/quick-equivalent-check mutation))))))

(deftest quick-equivalent-check-no-match-test
  (testing "quick-equivalent-check returns nil when no pattern matches"
    (let [mutation (make-mutation :swap-plus-minus :file "src/math.clj")]
      (is (nil? (equiv/quick-equivalent-check mutation))))))
