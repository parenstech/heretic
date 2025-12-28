(ns heretic.parser-test
  "Tests for source file parsing and mutation site detection.

   Tests verify:
   - File and string parsing into zippers
   - Top-level form navigation
   - Quoted form detection (skip mutations in quoted contexts)
   - Mutation site discovery with correct coordinates"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.coord-mapper :as coord-mapper]
            [heretic.operators :as ops]
            [heretic.parser :as parser]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Parse String Tests
;; =============================================================================

(deftest test-parse-string-simple
  (testing "Parses simple expression"
    (let [zloc (parser/parse-string "(+ 1 2)")]
      (is (some? zloc))
      (is (= '(+ 1 2) (z/sexpr zloc)))))

  (testing "Parses function definition"
    (let [zloc (parser/parse-string "(defn add [a b] (+ a b))")]
      (is (some? zloc))
      (is (= 'defn (z/sexpr (z/down zloc))))))

  (testing "Parses multiple forms"
    (let [zloc (parser/parse-string "(def x 1)\n(def y 2)")]
      (is (some? zloc))
      (is (= 'def (z/sexpr (z/down zloc))))))

  (testing "Returns nil for invalid syntax"
    (is (nil? (parser/parse-string "(+ 1 2"))))

  (testing "Returns empty forms container for empty string"
    (let [zloc (parser/parse-string "")]
      ;; Empty string parses but has no top-level forms
      (is (some? zloc))
      (is (empty? (parser/top-level-forms zloc))))))

;; =============================================================================
;; Top-Level Form Tests
;; =============================================================================

(deftest test-top-level-form-navigation
  (testing "Returns itself when already at top-level"
    (let [zloc (parser/parse-string "(+ 1 2)")
          top (parser/top-level-form zloc)]
      (is (= '(+ 1 2) (z/sexpr top)))))

  (testing "Navigates from nested position to top-level"
    (let [zloc (parser/parse-string "(defn add [a b] (+ a b))")
          ;; Navigate to the + symbol
          plus-zloc (-> zloc z/down z/right z/right z/right z/down)
          top (parser/top-level-form plus-zloc)]
      (is (= '(defn add [a b] (+ a b)) (z/sexpr top)))))

  (testing "Returns nil for :forms container"
    (let [zloc (parser/parse-string "(+ 1 2)")
          forms-node (z/up zloc)]
      (is (= :forms (z/tag forms-node)))
      (is (nil? (parser/top-level-form forms-node))))))

(deftest test-top-level-forms-sequence
  (testing "Returns all top-level forms"
    (let [zloc (parser/parse-string "(def x 1)\n(def y 2)\n(def z 3)")
          forms (parser/top-level-forms zloc)]
      (is (= 3 (count forms)))
      (is (= '(def x 1) (z/sexpr (first forms))))
      (is (= '(def y 2) (z/sexpr (second forms))))
      (is (= '(def z 3) (z/sexpr (nth forms 2))))))

  (testing "Returns empty for nil zipper"
    (is (nil? (parser/top-level-forms nil)))))

;; =============================================================================
;; Quoted Form Detection Tests
;; =============================================================================

(deftest test-in-quoted-form-quote
  (testing "Detects quote reader macro"
    (let [zloc (parser/parse-string "'(+ 1 2)")
          ;; Navigate into the quoted list
          inner (-> zloc z/down)]
      (is (parser/in-quoted-form? inner))))

  (testing "Detects deeply nested quote"
    (let [zloc (parser/parse-string "'((+ 1 2))")
          ;; Navigate to + inside '((+ 1 2))
          plus-zloc (-> zloc z/down z/down z/down)]
      (is (parser/in-quoted-form? plus-zloc)))))

(deftest test-in-quoted-form-syntax-quote
  (testing "Detects syntax quote"
    (let [zloc (parser/parse-string "`(+ 1 2)")
          ;; Navigate into the syntax-quoted list
          inner (-> zloc z/down)]
      (is (parser/in-quoted-form? inner)))))

(deftest test-in-quoted-form-not-quoted
  (testing "Returns false for unquoted forms"
    (let [zloc (parser/parse-string "(+ 1 2)")
          plus-zloc (z/down zloc)]
      (is (not (parser/in-quoted-form? plus-zloc)))))

  (testing "Returns false for top-level form"
    (let [zloc (parser/parse-string "(+ 1 2)")]
      (is (not (parser/in-quoted-form? zloc))))))

(deftest test-in-quoted-form-unquote
  (testing "Unquote inside syntax-quote is still in quoted context"
    (let [zloc (parser/parse-string "`(+ ~x 2)")
          ;; Navigate: syntax-quote -> list -> + -> ~x
          ;; z/down from syntax-quote goes to the list
          ;; z/down from list goes to +
          ;; z/right from + goes to ~x (unquote)
          unquote-zloc (-> zloc z/down z/down z/right)]
      (is (some? unquote-zloc))
      (is (= :unquote (z/tag unquote-zloc)))
      ;; The unquote node itself is inside the syntax-quote
      (is (parser/in-quoted-form? unquote-zloc)))))

;; =============================================================================
;; Find Mutation Sites Tests
;; =============================================================================

(deftest test-find-mutation-sites-simple
  (testing "Finds + operator mutation site"
    (let [zloc (parser/parse-string "(+ 1 2)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 1 (count sites)))
      (is (= :swap-plus-minus (:operator (first sites))))
      (is (= "+" (:original (first sites))))
      (is (= "-" (:replacement (first sites))))
      (is (= "0" (:coord (first sites))))))

  (testing "Finds multiple operators in one form"
    (let [zloc (parser/parse-string "(+ 1 (- 2 3))")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (count sites)))
      (is (= #{:swap-plus-minus :swap-minus-plus}
             (set (map :operator sites)))))))

(deftest test-find-mutation-sites-nested
  (testing "Finds operators in nested forms"
    (let [zloc (parser/parse-string "(defn add [a b] (+ a b))")
          sites (parser/find-mutation-sites zloc)]
      ;; Should find + at [3 0]
      (is (= 1 (count sites)))
      (is (= :swap-plus-minus (:operator (first sites))))
      (is (= "3,0" (:coord (first sites)))))))

(deftest test-find-mutation-sites-boolean
  (testing "Finds boolean mutation sites"
    (let [zloc (parser/parse-string "(if true 1 2)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 1 (count sites)))
      (is (= :swap-true-false (:operator (first sites))))
      (is (= "true" (:original (first sites))))
      (is (= "false" (:replacement (first sites)))))))

(deftest test-find-mutation-sites-logical
  (testing "Finds logical operator mutation sites"
    (let [zloc (parser/parse-string "(and x (or y z))")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (count sites)))
      (is (= #{:swap-and-or :swap-or-and}
             (set (map :operator sites)))))))

(deftest test-find-mutation-sites-multiple-forms
  (testing "Finds sites across multiple top-level forms"
    (let [zloc (parser/parse-string "(+ 1 2)\n(- 3 4)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (count sites)))
      (is (= #{:swap-plus-minus :swap-minus-plus}
             (set (map :operator sites)))))))

(deftest test-find-mutation-sites-skips-quoted
  (testing "Skips mutation sites in quoted forms"
    (let [zloc (parser/parse-string "'(+ 1 2)")
          sites (parser/find-mutation-sites zloc)]
      (is (empty? sites))))

  (testing "Skips mutation sites in syntax-quoted forms"
    (let [zloc (parser/parse-string "`(+ 1 2)")
          sites (parser/find-mutation-sites zloc)]
      (is (empty? sites))))

  (testing "Finds non-quoted sites while skipping quoted"
    (let [zloc (parser/parse-string "(do (+ 1 2) '(- 3 4))")
          sites (parser/find-mutation-sites zloc)]
      ;; Should find + but not -
      (is (= 1 (count sites)))
      (is (= :swap-plus-minus (:operator (first sites)))))))

(deftest test-find-mutation-sites-with-file
  (testing "Includes file path when provided"
    (let [zloc (parser/parse-string "(+ 1 2)")
          sites (parser/find-mutation-sites zloc {:file "test.clj"})]
      (is (= "test.clj" (:file (first sites)))))))

(deftest test-find-mutation-sites-form-id
  (testing "Includes form-id for each site"
    (let [zloc (parser/parse-string "(+ 1 2)")
          sites (parser/find-mutation-sites zloc)]
      (is (integer? (:form-id (first sites))))))

  (testing "Same form has same form-id"
    (let [zloc (parser/parse-string "(+ (- 1 2) 3)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (count sites)))
      (is (= (:form-id (first sites)) (:form-id (second sites))))))

  (testing "Different forms have different form-ids"
    (let [zloc (parser/parse-string "(+ 1 2)\n(- 3 4)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (count sites)))
      (is (not= (:form-id (first sites)) (:form-id (second sites)))))))

(deftest test-find-mutation-sites-line-column
  (testing "Includes line and column when position tracking enabled"
    (let [zloc (parser/parse-string "(+ 1 2)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 1 (:line (first sites))))
      (is (= 2 (:column (first sites))))))

  (testing "Reports correct position for nested forms"
    (let [zloc (parser/parse-string "(defn f []\n  (+ 1 2))")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (:line (first sites))))
      (is (= 4 (:column (first sites)))))))

(deftest test-find-mutation-sites-uuid
  (testing "Each site has a unique UUID"
    (let [zloc (parser/parse-string "(+ (- 1 2) 3)")
          sites (parser/find-mutation-sites zloc)]
      (is (= 2 (count sites)))
      (is (uuid? (:id (first sites))))
      (is (uuid? (:id (second sites))))
      (is (not= (:id (first sites)) (:id (second sites)))))))

(deftest test-find-mutation-sites-custom-operators
  (testing "Can use custom operator subset"
    (let [zloc (parser/parse-string "(+ 1 (- 2 3))")
          sites (parser/find-mutation-sites zloc {:operators [ops/swap-plus-minus]})]
      ;; Should only find +, not -
      (is (= 1 (count sites)))
      (is (= :swap-plus-minus (:operator (first sites)))))))

;; =============================================================================
;; Round-Trip Validation Tests
;; =============================================================================

(deftest test-mutation-site-coord-round-trip
  (testing "Can navigate back to mutation site using coord"
    (let [source "(defn add [a b] (+ a b))"
          zloc (parser/parse-string source)
          sites (parser/find-mutation-sites zloc)
          site (first sites)]
      ;; Navigate using the coord
      (let [target (coord-mapper/coord->zloc zloc (:coord site))]
        (is (some? target))
        (is (= '+ (z/sexpr target)))))))

(deftest test-mutation-site->zloc
  (testing "mutation-site->zloc navigates to correct position"
    (let [source "(+ 1 (- 2 3))"
          zloc (parser/parse-string source)
          sites (parser/find-mutation-sites zloc)]
      (doseq [site sites]
        (let [target (parser/mutation-site->zloc site zloc)]
          (is (some? target))
          (is (= (:original site) (str (z/sexpr target)))))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-empty-form
  (testing "Handles empty list"
    (let [zloc (parser/parse-string "()")]
      (is (some? zloc))
      (is (empty? (parser/find-mutation-sites zloc))))))

(deftest test-no-mutable-forms
  (testing "Returns empty for forms with no mutation sites"
    (let [zloc (parser/parse-string "(foo bar baz)")]
      (is (empty? (parser/find-mutation-sites zloc))))))

(deftest test-deeply-nested
  (testing "Finds sites in deeply nested forms"
    (let [zloc (parser/parse-string "(((((+ 1 2)))))")]
      (let [sites (parser/find-mutation-sites zloc)]
        (is (= 1 (count sites)))
        (is (= :swap-plus-minus (:operator (first sites))))))))

(deftest test-arithmetic-expression
  (testing "Finds all arithmetic operators"
    (let [zloc (parser/parse-string "(+ (* a b) (/ c (- d e)))")
          sites (parser/find-mutation-sites zloc)]
      (is (= 4 (count sites)))
      (is (= #{:swap-plus-minus :swap-minus-plus :swap-mult-div :swap-div-mult}
             (set (map :operator sites)))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-realistic-function
  (testing "Finds mutation sites in realistic function"
    (let [source "(defn calculate-total [items tax-rate]
                    (let [subtotal (reduce + 0 (map :price items))
                          tax (* subtotal tax-rate)]
                      (+ subtotal tax)))"
          zloc (parser/parse-string source)
          sites (parser/find-mutation-sites zloc)]
      ;; Should find: + (reduce), * (tax), + (total)
      (is (= 3 (count sites)))
      (is (= #{:swap-plus-minus :swap-mult-div}
             (set (map :operator sites)))))))

(deftest test-conditional-with-boolean
  (testing "Finds operators and booleans in conditional"
    (let [source "(defn check [x y]
                    (if (and (> x 0) (< y 10))
                      true
                      false))"
          zloc (parser/parse-string source)
          sites (parser/find-mutation-sites zloc)]
      ;; Should find: and, true, false
      (is (= 3 (count sites)))
      (is (= #{:swap-and-or :swap-true-false :swap-false-true}
             (set (map :operator sites)))))))
