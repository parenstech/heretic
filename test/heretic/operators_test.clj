(ns heretic.operators-test
  "Tests for mutation operator definitions.

   Tests verify:
   - Operator matchers correctly identify target zlocs
   - apply-operator returns correct replacement strings
   - applicable-operators returns matching operators for zlocs"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.operators :as ops]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- zloc-at
  "Parse source and navigate to the form at the given coord.
   Coord is a sequence of child indices, e.g., [0] for first child."
  [source coord]
  (let [zloc (z/of-string source)]
    (reduce (fn [z idx]
              (let [child (z/down z)]
                (loop [c child
                       i 0]
                  (cond
                    (= i idx) c
                    (nil? c) nil
                    :else (recur (z/right c) (inc i))))))
            zloc
            coord)))

;; =============================================================================
;; Arithmetic Operator Tests
;; =============================================================================

(deftest test-swap-plus-minus-matcher
  (testing "Matches + symbol"
    (let [zloc (zloc-at "(+ 1 2)" [0])]
      (is ((:matcher ops/swap-plus-minus) zloc))))

  (testing "Does not match other symbols"
    (let [zloc (zloc-at "(- 1 2)" [0])]
      (is (not ((:matcher ops/swap-plus-minus) zloc)))))

  (testing "Does not match + in string"
    (let [zloc (zloc-at "\"+ plus\"" [])]
      (is (not ((:matcher ops/swap-plus-minus) zloc))))))

(deftest test-swap-minus-plus-matcher
  (testing "Matches - symbol"
    (let [zloc (zloc-at "(- 1 2)" [0])]
      (is ((:matcher ops/swap-minus-plus) zloc))))

  (testing "Does not match other symbols"
    (let [zloc (zloc-at "(+ 1 2)" [0])]
      (is (not ((:matcher ops/swap-minus-plus) zloc))))))

(deftest test-swap-mult-div-matcher
  (testing "Matches * symbol"
    (let [zloc (zloc-at "(* 2 3)" [0])]
      (is ((:matcher ops/swap-mult-div) zloc))))

  (testing "Does not match other symbols"
    (let [zloc (zloc-at "(/ 6 2)" [0])]
      (is (not ((:matcher ops/swap-mult-div) zloc))))))

(deftest test-swap-div-mult-matcher
  (testing "Matches / symbol"
    (let [zloc (zloc-at "(/ 6 2)" [0])]
      (is ((:matcher ops/swap-div-mult) zloc))))

  (testing "Does not match other symbols"
    (let [zloc (zloc-at "(* 2 3)" [0])]
      (is (not ((:matcher ops/swap-div-mult) zloc))))))

;; =============================================================================
;; Boolean Operator Tests
;; =============================================================================

(deftest test-swap-and-or-matcher
  (testing "Matches and symbol"
    (let [zloc (zloc-at "(and true false)" [0])]
      (is ((:matcher ops/swap-and-or) zloc))))

  (testing "Does not match or symbol"
    (let [zloc (zloc-at "(or true false)" [0])]
      (is (not ((:matcher ops/swap-and-or) zloc))))))

(deftest test-swap-or-and-matcher
  (testing "Matches or symbol"
    (let [zloc (zloc-at "(or true false)" [0])]
      (is ((:matcher ops/swap-or-and) zloc))))

  (testing "Does not match and symbol"
    (let [zloc (zloc-at "(and true false)" [0])]
      (is (not ((:matcher ops/swap-or-and) zloc))))))

(deftest test-swap-true-false-matcher
  (testing "Matches true literal"
    (let [zloc (zloc-at "(if true 1 2)" [1])]
      (is ((:matcher ops/swap-true-false) zloc))))

  (testing "Does not match false literal"
    (let [zloc (zloc-at "(if false 1 2)" [1])]
      (is (not ((:matcher ops/swap-true-false) zloc)))))

  (testing "Does not match symbols named true"
    ;; In real code, 'true would be a symbol, but the literal true is a boolean
    (let [zloc (zloc-at "(foo true-flag)" [1])]
      (is (not ((:matcher ops/swap-true-false) zloc))))))

(deftest test-swap-false-true-matcher
  (testing "Matches false literal"
    (let [zloc (zloc-at "(if false 1 2)" [1])]
      (is ((:matcher ops/swap-false-true) zloc))))

  (testing "Does not match true literal"
    (let [zloc (zloc-at "(if true 1 2)" [1])]
      (is (not ((:matcher ops/swap-false-true) zloc))))))

;; =============================================================================
;; apply-operator Tests
;; =============================================================================

(deftest test-apply-operator-arithmetic
  (testing "Returns correct replacement for arithmetic operators"
    (let [zloc (zloc-at "(+ 1 2)" [0])]
      (is (= "-" (ops/apply-operator ops/swap-plus-minus zloc)))
      (is (= "+" (ops/apply-operator ops/swap-minus-plus zloc)))
      (is (= "/" (ops/apply-operator ops/swap-mult-div zloc)))
      (is (= "*" (ops/apply-operator ops/swap-div-mult zloc))))))

(deftest test-apply-operator-logical
  (testing "Returns correct replacement for logical operators"
    (let [zloc (zloc-at "(and true false)" [0])]
      (is (= "or" (ops/apply-operator ops/swap-and-or zloc)))
      (is (= "and" (ops/apply-operator ops/swap-or-and zloc))))))

(deftest test-apply-operator-boolean
  (testing "Returns correct replacement for boolean literals"
    (let [zloc (zloc-at "true" [])]
      (is (= "false" (ops/apply-operator ops/swap-true-false zloc)))
      (is (= "true" (ops/apply-operator ops/swap-false-true zloc))))))

;; =============================================================================
;; applicable-operators Tests
;; =============================================================================

(deftest test-applicable-operators-plus
  (testing "Returns swap-plus-minus for + symbol"
    (let [zloc (zloc-at "(+ 1 2)" [0])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-plus-minus (:id (first ops)))))))

(deftest test-applicable-operators-minus
  (testing "Returns swap-minus-plus for - symbol"
    (let [zloc (zloc-at "(- 1 2)" [0])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-minus-plus (:id (first ops)))))))

(deftest test-applicable-operators-mult
  (testing "Returns swap-mult-div for * symbol"
    (let [zloc (zloc-at "(* 2 3)" [0])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-mult-div (:id (first ops)))))))

(deftest test-applicable-operators-div
  (testing "Returns swap-div-mult for / symbol"
    (let [zloc (zloc-at "(/ 6 2)" [0])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-div-mult (:id (first ops)))))))

(deftest test-applicable-operators-and
  (testing "Returns swap-and-or for and symbol"
    (let [zloc (zloc-at "(and a b)" [0])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-and-or (:id (first ops)))))))

(deftest test-applicable-operators-or
  (testing "Returns swap-or-and for or symbol"
    (let [zloc (zloc-at "(or a b)" [0])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-or-and (:id (first ops)))))))

(deftest test-applicable-operators-true
  (testing "Returns swap-true-false for true literal"
    (let [zloc (zloc-at "true" [])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-true-false (:id (first ops)))))))

(deftest test-applicable-operators-false
  (testing "Returns swap-false-true for false literal"
    (let [zloc (zloc-at "false" [])
          ops (ops/applicable-operators zloc)]
      (is (= 1 (count ops)))
      (is (= :swap-false-true (:id (first ops)))))))

(deftest test-applicable-operators-no-match
  (testing "Returns empty for non-matching symbols"
    (let [zloc (zloc-at "(foo 1 2)" [0])
          ops (ops/applicable-operators zloc)]
      (is (empty? ops))))

  (testing "Returns empty for numbers"
    (let [zloc (zloc-at "(+ 1 2)" [1])
          ops (ops/applicable-operators zloc)]
      (is (empty? ops))))

  (testing "Returns empty for strings"
    (let [zloc (zloc-at "\"hello\"" [])
          ops (ops/applicable-operators zloc)]
      (is (empty? ops)))))

;; =============================================================================
;; Operator Registry Tests
;; =============================================================================

(deftest test-all-operators-count
  (testing "all-operators contains expected count"
    (is (= 18 (count ops/all-operators)))))

(deftest test-operators-by-id
  (testing "operators-by-id contains all operators"
    (is (= 18 (count ops/operators-by-id))))

  (testing "Can look up operators by id"
    (is (= ops/swap-plus-minus (get ops/operators-by-id :swap-plus-minus)))
    (is (= ops/swap-minus-plus (get ops/operators-by-id :swap-minus-plus)))
    (is (= ops/swap-true-false (get ops/operators-by-id :swap-true-false)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-mutation-workflow
  (testing "Complete workflow: find applicable ops and apply"
    (let [source "(defn add [a b] (+ a b))"
          ;; Navigate to the + symbol at position [3 0]
          zloc (zloc-at source [3 0])
          applicable (ops/applicable-operators zloc)]
      ;; Should find swap-plus-minus
      (is (= 1 (count applicable)))
      (is (= :swap-plus-minus (:id (first applicable))))
      ;; Apply should give us the replacement
      (is (= "-" (ops/apply-operator (first applicable) zloc))))))

(deftest test-nested-expression-operators
  (testing "Find operators in nested expressions"
    (let [source "(if (and (> x 0) (< y 10)) (+ x y) (* x y))"
          ;; Navigate to 'and' at [1 0]
          and-zloc (zloc-at source [1 0])
          ;; Navigate to + at [2 0]
          plus-zloc (zloc-at source [2 0])
          ;; Navigate to * at [3 0]
          mult-zloc (zloc-at source [3 0])]
      (is (= :swap-and-or (:id (first (ops/applicable-operators and-zloc)))))
      (is (= :swap-plus-minus (:id (first (ops/applicable-operators plus-zloc)))))
      (is (= :swap-mult-div (:id (first (ops/applicable-operators mult-zloc))))))))
