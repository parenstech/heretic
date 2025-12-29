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

;; =============================================================================
;; Comprehensive Tests to Kill Surviving Mutations
;; =============================================================================

;; Kill: rest->next mutations (lines 37, 45, 54, 62)
;; rest returns () for single element, next returns nil
(deftest rest-vs-next-single-element-test
  (testing "Pattern check with single-element list (just function, no args)"
    ;; (+ ) has only the function symbol, rest gives (), next gives nil
    ;; some on () returns nil, some on nil returns nil - but behavior differs
    (let [zloc (-> (parser/parse-string "(+)")
                   z/down)
          mutation (make-mutation :swap-plus-minus)]
      ;; Should not detect as equivalent since there's no 0 argument
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "Single element list should not match zero pattern"))))

;; Kill: and->or mutations for each operator (lines 43, 52, 60)
(deftest minus-plus-non-list-parent-test
  (testing "swap-minus-plus with non-list parent"
    (let [zloc (-> (parser/parse-string "[- x 0]")
                   z/down)
          mutation (make-mutation :swap-minus-plus)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest mult-div-non-list-parent-test
  (testing "swap-mult-div with non-list parent"
    (let [zloc (-> (parser/parse-string "[* x 1]")
                   z/down)
          mutation (make-mutation :swap-mult-div)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest div-mult-non-list-parent-test
  (testing "swap-div-mult with non-list parent"
    (let [zloc (-> (parser/parse-string "[/ x 1]")
                   z/down)
          mutation (make-mutation :swap-div-mult)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; Kill: and->or mutations (lines 69, 79, 81, 89, 91)
(deftest and-or-non-list-parent-test
  (testing "swap-and-or with non-list parent"
    (let [zloc (-> (parser/parse-string "[and true x]")
                   z/down)
          mutation (make-mutation :swap-and-or)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest or-and-non-list-parent-test
  (testing "swap-or-and with non-list parent"
    (let [zloc (-> (parser/parse-string "[or false x]")
                   z/down)
          mutation (make-mutation :swap-or-and)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest eq-neq-non-list-parent-test
  (testing "swap-eq-neq with non-list parent"
    (let [zloc (-> (parser/parse-string "[= x x]")
                   z/down)
          mutation (make-mutation :swap-eq-neq)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; Kill: =->not= mutations (lines 61, 89, 92)
(deftest divide-by-two-not-equivalent-test
  (testing "Dividing by 2 (not 1) is not equivalent"
    (let [zloc (-> (parser/parse-string "(/ x 2)")
                   z/down)
          mutation (make-mutation :swap-div-mult)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest eq-neq-different-values-test
  (testing "Comparing different values is not equivalent"
    (let [zloc (-> (parser/parse-string "(= x y)")
                   z/down)
          mutation (make-mutation :swap-eq-neq)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest eq-neq-same-values-is-equivalent-test
  (testing "Comparing same values IS equivalent"
    (let [zloc (-> (parser/parse-string "(= x x)")
                   z/down)
          mutation (make-mutation :swap-eq-neq)]
      (is (some? (equiv/likely-equivalent? mutation zloc))
          "Comparing identical values should be detected as equivalent"))))

;; Kill: 2->1, 2->0 mutations (line 91) - count check
(deftest and-three-args-not-equivalent-test
  (testing "and with 3 args (not 2) is not detected as equivalent"
    (let [zloc (-> (parser/parse-string "(and true x y)")
                   z/down)
          mutation (make-mutation :swap-and-or)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "3-arg and should not match 2-arg pattern"))))

(deftest and-one-arg-not-equivalent-test
  (testing "and with 1 arg is not detected as equivalent"
    (let [zloc (-> (parser/parse-string "(and true)")
                   z/down)
          mutation (make-mutation :swap-and-or)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "1-arg and should not match 2-arg pattern"))))

(deftest or-three-args-not-equivalent-test
  (testing "or with 3 args is not detected as equivalent"
    (let [zloc (-> (parser/parse-string "(or false x y)")
                   z/down)
          mutation (make-mutation :swap-or-and)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest eq-three-args-not-equivalent-test
  (testing "= with 3 args is not detected as equivalent even if all same"
    (let [zloc (-> (parser/parse-string "(= x x x)")
                   z/down)
          mutation (make-mutation :swap-eq-neq)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "3-arg = should not match 2-arg pattern"))))

;; Kill: nil->X mutations (line 109) - check-pattern exception handling
(deftest check-pattern-exception-returns-nil-test
  (testing "check-pattern returns nil on exception, not false or other value"
    ;; Create a situation where the context function would throw
    ;; by using a mutation that doesn't match any pattern
    (let [mutation (make-mutation :unknown-operator)
          zloc (-> (parser/parse-string "(foo)") z/down)
          result (equiv/likely-equivalent? mutation zloc)]
      (is (nil? result)
          "Unknown operator should return nil"))))

(deftest filter-handles-zloc-fn-exception-test
  (testing "filter-equivalent-mutations handles zloc-fn throwing exception"
    (let [mutations [(make-mutation :swap-plus-minus :id 1)]
          zloc-fn (fn [_] (throw (Exception. "zloc error")))
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      ;; Should treat as testable (not crash)
      (is (= 1 (count (:mutations result))))
      (is (= 0 (:filtered-count result))))))

(deftest filter-handles-nil-zloc-test
  (testing "filter-equivalent-mutations handles nil zloc"
    (let [mutations [(make-mutation :swap-plus-minus)]
          zloc-fn (fn [_] nil)
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      ;; Should treat as testable
      (is (= 1 (count (:mutations result))))
      (is (= 0 (:filtered-count result))))))

;; Kill: true->false mutations (lines 121, 174)
(deftest likely-equivalent-returns-true-in-map-test
  (testing "likely-equivalent? returns map with :equivalent? true"
    (let [zloc (-> (parser/parse-string "(+ x 0)")
                   z/down)
          mutation (make-mutation :swap-plus-minus)
          result (equiv/likely-equivalent? mutation zloc)]
      (is (map? result))
      (is (true? (:equivalent? result))
          ":equivalent? must be true, not just truthy"))))

(deftest quick-check-returns-true-in-map-test
  (testing "quick-equivalent-check returns map with :equivalent? true"
    (let [mutation (make-mutation :replace-0-to-1 :file "src/default_config.clj")
          result (equiv/quick-equivalent-check mutation)]
      (is (map? result))
      (is (true? (:equivalent? result))
          ":equivalent? must be true, not just truthy"))))

;; Additional edge cases for rest->next (lines 71, 80, 90)
(deftest and-or-rest-vs-next-test
  (testing "and/or pattern with single boolean arg"
    ;; (and true) - rest gives (true), checking (some true? (true)) works
    ;; but next would give nil on single element after rest
    (let [zloc (-> (parser/parse-string "(and true)")
                   z/down)
          mutation (make-mutation :swap-and-or)]
      ;; Should NOT match because count is 1, not 2
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest eq-neq-rest-vs-next-test
  (testing "= pattern uses rest correctly"
    ;; (= x) - single arg, rest gives (x), count is 1, not 2
    (let [zloc (-> (parser/parse-string "(= x)")
                   z/down)
          mutation (make-mutation :swap-eq-neq)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "Single arg = should not match 2-arg pattern"))))

;; Kill: and->or at line 161 (in simple-equivalent-patterns check)
(deftest simple-pattern-and-check-test
  (testing "simple pattern check uses and correctly"
    ;; The simple pattern checks (and (= :replace-0-to-1 op) (re-find ...))
    ;; If mutated to or, wrong operator would match
    (let [wrong-op (make-mutation :swap-plus-minus :file "src/default_config.clj")
          result (equiv/quick-equivalent-check wrong-op)]
      (is (nil? result)
          "Wrong operator should not match even with matching filename"))))

(deftest simple-pattern-both-conditions-required-test
  (testing "simple pattern requires both operator AND filename match"
    ;; Right operator, wrong filename
    (let [wrong-file (make-mutation :replace-0-to-1 :file "src/math.clj")
          result (equiv/quick-equivalent-check wrong-file)]
      (is (nil? result)
          "Right operator with wrong filename should not match"))))

;; =============================================================================
;; Exception Path Tests (kills nil->X mutations at line 109)
;; =============================================================================

(deftest exception-in-context-returns-nil-test
  (testing "Exception in context function returns nil, not truthy value"
    ;; Pass invalid zloc (a number) which causes z/up to throw
    ;; This exercises the catch block at line 109
    ;; If nil is mutated to 0/[]/{}/"", the return would be {:equivalent? true ...}
    (let [mutation (make-mutation :swap-plus-minus)
          invalid-zloc 42  ; z/up throws UnsupportedOperationException on this
          result (equiv/likely-equivalent? mutation invalid-zloc)]
      (is (nil? result)
          "Exception should return nil, not a truthy value")
      (is (not (map? result))
          "Exception should not return a map"))))

(deftest exception-returns-nil-not-false-test
  (testing "Exception returns nil specifically, not false"
    (let [mutation (make-mutation :swap-minus-plus)
          result (equiv/likely-equivalent? mutation 123)]
      (is (nil? result))
      (is (not (false? result))
          "Should return nil, not false"))))

(deftest exception-returns-nil-not-empty-collection-test
  (testing "Exception returns nil, not empty collection"
    (let [mutation (make-mutation :swap-mult-div)
          result (equiv/likely-equivalent? mutation :keyword-not-zloc)]
      (is (nil? result))
      (is (not (= [] result)))
      (is (not (= {} result)))
      (is (not (= "" result))))))

;; =============================================================================
;; rest->next Equivalent Pattern Tests
;; =============================================================================

(deftest rest-next-equivalent-in-some-context-test
  (testing "rest->next is equivalent when passed to some"
    ;; (some pred (rest coll)) ≡ (some pred (next coll))
    (let [zloc (-> (parser/parse-string "(some #(= 0 %) (rest coll))")
                   z/down    ; at 'some
                   z/right   ; at predicate
                   z/right   ; at (rest coll)
                   z/down)   ; at 'rest
          mutation (make-mutation :swap-rest-next)]
      (is (some? (equiv/likely-equivalent? mutation zloc))
          "rest->next in some context should be detected as equivalent"))))

(deftest rest-next-not-equivalent-outside-some-test
  (testing "rest->next is NOT equivalent outside some context"
    ;; (first (rest coll)) is NOT equivalent to (first (next coll))
    ;; when coll has one element: (first (rest '(a))) = nil, (first (next '(a))) = nil
    ;; Actually that's the same... let me use a different example
    ;; (count (rest coll)) vs (count (next coll)) when coll is empty:
    ;; (count (rest '())) = 0, (count (next '())) throws NPE on count of nil...
    ;; Actually no, (count nil) = 0 in Clojure
    ;; The difference is (rest '()) = (), (next '()) = nil
    ;; For most sequence operations they behave the same
    ;; But the pattern only marks it equivalent when in `some` context
    (let [zloc (-> (parser/parse-string "(first (rest coll))")
                   z/down    ; at 'first
                   z/right   ; at (rest coll)
                   z/down)   ; at 'rest
          mutation (make-mutation :swap-rest-next)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))
          "rest->next outside some context should NOT be marked equivalent"))))

(deftest next-rest-equivalent-in-some-context-test
  (testing "next->rest is also equivalent when passed to some"
    (let [zloc (-> (parser/parse-string "(some pred (next items))")
                   z/down z/right z/right z/down)
          mutation (make-mutation :swap-next-rest)]
      (is (some? (equiv/likely-equivalent? mutation zloc))
          "next->rest in some context should be detected as equivalent"))))

;; =============================================================================
;; Boundary Comparison Pattern Tests
;; =============================================================================

(deftest lt-count-zero-always-false-test
  (testing "(< (count x) 0) is always false - detected as equivalent"
    (let [zloc (-> (parser/parse-string "(< (count coll) 0)")
                   z/down)  ; at '<
          mutation (make-mutation :swap-lt-lte)]
      (is (some? (equiv/likely-equivalent? mutation zloc))
          "< with count and 0 should be equivalent"))))

(deftest lte-count-zero-test
  (testing "(<= (count x) 0) is equivalent to (= (count x) 0)"
    (let [zloc (-> (parser/parse-string "(<= (count coll) 0)")
                   z/down)
          mutation (make-mutation :swap-lte-lt)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest lt-non-count-not-equivalent-test
  (testing "(< x 0) without count is not equivalent"
    (let [zloc (-> (parser/parse-string "(< x 0)")
                   z/down)
          mutation (make-mutation :swap-lt-lte)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest lt-count-non-zero-not-equivalent-test
  (testing "(< (count x) 5) is not equivalent"
    (let [zloc (-> (parser/parse-string "(< (count coll) 5)")
                   z/down)
          mutation (make-mutation :swap-lt-lte)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Multiply by Zero Pattern Tests
;; =============================================================================

(deftest mult-by-zero-equivalent-test
  (testing "(* x 0) is always 0 - mutation equivalent"
    (let [zloc (-> (parser/parse-string "(* x 0)")
                   z/down)
          mutation (make-mutation :swap-mult-div)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest div-zero-numerator-equivalent-test
  (testing "(/ 0 x) is always 0"
    (let [zloc (-> (parser/parse-string "(/ 0 x)")
                   z/down)
          mutation (make-mutation :swap-div-mult)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest div-non-zero-numerator-not-equivalent-test
  (testing "(/ 5 x) is not equivalent to (* 5 x)"
    (let [zloc (-> (parser/parse-string "(/ 5 x)")
                   z/down)
          mutation (make-mutation :swap-div-mult)]
      ;; May or may not match depending on pattern - check for divide by 1
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Function Contract Pattern Tests
;; =============================================================================

(deftest nil-check-on-str-always-false-test
  (testing "(nil? (str x)) is always false - str never returns nil"
    (let [zloc (-> (parser/parse-string "(nil? (str x))")
                   z/down)  ; at 'nil?
          mutation (make-mutation :swap-nil-some)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest some-check-on-str-always-true-test
  (testing "(some? (str x)) is always true"
    (let [zloc (-> (parser/parse-string "(some? (str x))")
                   z/down)
          mutation (make-mutation :swap-some-nil)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest nil-check-on-vec-always-false-test
  (testing "(nil? (vec x)) is always false"
    (let [zloc (-> (parser/parse-string "(nil? (vec x))")
                   z/down)
          mutation (make-mutation :swap-nil-some)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest nil-check-on-count-always-false-test
  (testing "(nil? (count x)) is always false"
    (let [zloc (-> (parser/parse-string "(nil? (count x))")
                   z/down)
          mutation (make-mutation :swap-nil-some)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest nil-check-on-regular-fn-not-equivalent-test
  (testing "(nil? (foo x)) is not equivalent - foo might return nil"
    (let [zloc (-> (parser/parse-string "(nil? (foo x))")
                   z/down)
          mutation (make-mutation :swap-nil-some)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Lazy/Eager Equivalence Pattern Tests
;; =============================================================================

(deftest map-mapv-in-vec-context-test
  (testing "(vec (map f coll)) ≡ (vec (mapv f coll))"
    ;; map inside vec - swapping to mapv is equivalent
    (let [zloc (-> (parser/parse-string "(vec (map inc coll))")
                   z/down    ; at 'vec
                   z/right   ; at (map inc coll)
                   z/down)   ; at 'map
          mutation (make-mutation :swap-map-mapv)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest mapv-map-in-count-context-test
  (testing "(count (mapv f coll)) ≡ (count (map f coll))"
    (let [zloc (-> (parser/parse-string "(count (mapv inc coll))")
                   z/down z/right z/down)
          mutation (make-mutation :swap-mapv-map)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest filter-filterv-in-into-context-test
  (testing "(into [] (filter pred coll)) ≡ with filterv"
    (let [zloc (-> (parser/parse-string "(into [] (filter even? coll))")
                   z/down z/right z/right z/down)
          mutation (make-mutation :swap-filter-filterv)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest map-outside-realizing-context-not-equivalent-test
  (testing "(map f coll) alone is not equivalent to mapv"
    (let [zloc (-> (parser/parse-string "(map inc coll)")
                   z/down)
          mutation (make-mutation :swap-map-mapv)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Collection Literal Pattern Tests
;; =============================================================================

(deftest empty-check-on-empty-vec-test
  (testing "(empty? []) is always true"
    (let [zloc (-> (parser/parse-string "(empty? [])")
                   z/down)
          mutation (make-mutation :swap-seq-empty)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest empty-check-on-empty-map-test
  (testing "(empty? {}) is always true"
    (let [zloc (-> (parser/parse-string "(empty? {})")
                   z/down)
          mutation (make-mutation :swap-seq-empty)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest seq-on-empty-vec-test
  (testing "(seq []) is always nil"
    (let [zloc (-> (parser/parse-string "(seq [])")
                   z/down)
          mutation (make-mutation :swap-empty-seq)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest empty-check-on-non-empty-vec-not-equivalent-test
  (testing "(empty? [1 2]) is not equivalent"
    (let [zloc (-> (parser/parse-string "(empty? [1 2])")
                   z/down)
          mutation (make-mutation :swap-seq-empty)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest first-last-single-element-test
  (testing "(first [x]) ≡ (last [x]) for single element"
    (let [zloc (-> (parser/parse-string "(first [42])")
                   z/down)
          mutation (make-mutation :swap-first-last)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest last-first-single-element-test
  (testing "(last [x]) ≡ (first [x]) for single element"
    (let [zloc (-> (parser/parse-string "(last [42])")
                   z/down)
          mutation (make-mutation :swap-last-first)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest first-last-multiple-elements-not-equivalent-test
  (testing "(first [1 2]) is NOT equivalent to (last [1 2])"
    (let [zloc (-> (parser/parse-string "(first [1 2])")
                   z/down)
          mutation (make-mutation :swap-first-last)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Threading Macro Pattern Tests
;; =============================================================================

(deftest thread-first-single-value-test
  (testing "(-> x) is identity - swapping to ->> is equivalent"
    (let [zloc (-> (parser/parse-string "(-> x)")
                   z/down)
          mutation (make-mutation :swap-thread-first-last)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest thread-first-with-forms-not-equivalent-test
  (testing "(-> x f g) is not equivalent to (->> x f g)"
    (let [zloc (-> (parser/parse-string "(-> x f g)")
                   z/down)
          mutation (make-mutation :swap-thread-first-last)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

(deftest some-thread-with-literal-test
  (testing "(some-> 42 f) ≡ (-> 42 f) when initial is non-nil"
    (let [zloc (-> (parser/parse-string "(some-> 42 inc)")
                   z/down)
          mutation (make-mutation :swap-some-thread-first)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest some-thread-with-string-literal-test
  (testing "(some-> \"hi\" f) ≡ (-> \"hi\" f)"
    (let [zloc (-> (parser/parse-string "(some-> \"hi\" str/upper-case)")
                   z/down)
          mutation (make-mutation :swap-some-thread-first)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest some-thread-with-symbol-not-equivalent-test
  (testing "(some-> x f) is NOT equivalent to (-> x f) - x might be nil"
    (let [zloc (-> (parser/parse-string "(some-> x inc)")
                   z/down)
          mutation (make-mutation :swap-some-thread-first)]
      (is (nil? (equiv/likely-equivalent? mutation zloc))))))

;; =============================================================================
;; Helper Function Coverage Tests
;; =============================================================================

(deftest non-negative-fn-count-test
  (testing "non-negative-fn? recognizes count"
    ;; Indirectly tested via boundary comparison patterns
    (let [zloc (-> (parser/parse-string "(< (count x) 0)")
                   z/down)
          mutation (make-mutation :swap-lt-lte)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest non-negative-fn-length-test
  (testing "non-negative-fn? recognizes .length"
    (let [zloc (-> (parser/parse-string "(< (.length s) 0)")
                   z/down)
          mutation (make-mutation :swap-lt-lte)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest non-negative-fn-math-abs-test
  (testing "non-negative-fn? recognizes Math/abs"
    (let [zloc (-> (parser/parse-string "(< (Math/abs n) 0)")
                   z/down)
          mutation (make-mutation :swap-lt-lte)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest never-nil-fn-keyword-test
  (testing "never-nil-fn? recognizes keyword"
    (let [zloc (-> (parser/parse-string "(nil? (keyword s))")
                   z/down)
          mutation (make-mutation :swap-nil-some)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest never-nil-fn-name-test
  (testing "never-nil-fn? recognizes name"
    (let [zloc (-> (parser/parse-string "(nil? (name k))")
                   z/down)
          mutation (make-mutation :swap-nil-some)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest realizing-fn-doall-test
  (testing "realizing-fn? recognizes doall"
    (let [zloc (-> (parser/parse-string "(doall (map inc coll))")
                   z/down z/right z/down)
          mutation (make-mutation :swap-map-mapv)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))

(deftest realizing-fn-frequencies-test
  (testing "realizing-fn? recognizes frequencies"
    (let [zloc (-> (parser/parse-string "(frequencies (map inc coll))")
                   z/down z/right z/down)
          mutation (make-mutation :swap-map-mapv)]
      (is (some? (equiv/likely-equivalent? mutation zloc))))))
