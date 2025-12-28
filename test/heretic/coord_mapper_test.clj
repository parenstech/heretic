(ns heretic.coord-mapper-test
  "Tests for coordinate mapping between ClojureStorm and rewrite-clj.

   These tests are CRITICAL - the coordinate mapper must be validated
   with round-trip tests before Phase 1 is complete.

   Validation requirement:
   (= coord (zloc->coord (coord->zloc zloc coord)))"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.coord-mapper :as mapper]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Coordinate Parsing
;; =============================================================================

(deftest test-parse-coord-integers
  (testing "Parses integer-only coordinates"
    (is (= [3 2 1] (mapper/parse-coord "3,2,1")))
    (is (= [0] (mapper/parse-coord "0")))
    (is (= [10 20] (mapper/parse-coord "10,20")))))

(deftest test-parse-coord-mixed
  (testing "Parses mixed integer/hash coordinates"
    (is (= [3 "K-12345" 1] (mapper/parse-coord "3,K-12345,1")))
    (is (= ["K-999"] (mapper/parse-coord "K-999")))))

(deftest test-parse-coord-already-vector
  (testing "Vectors pass through unchanged"
    (is (= [3 2 1] (mapper/parse-coord [3 2 1])))))

(deftest test-stringify-coord
  (testing "Vectors are stringified"
    (is (= "3,2,1" (mapper/stringify-coord [3 2 1])))
    (is (= "3,K-12345,1" (mapper/stringify-coord [3 "K-12345" 1])))))

;; =============================================================================
;; Sequential Navigation
;; =============================================================================

(deftest test-coord->zloc-simple
  (testing "Navigates simple sequential forms"
    (let [zloc (z/of-string "(defn foo [a b] (+ a b))")]
      ;; (defn foo [a b] (+ a b))
      ;;   0    1   2     3
      (is (= 'defn (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (= '[a b] (z/sexpr (mapper/coord->zloc zloc [2]))))
      (is (= '(+ a b) (z/sexpr (mapper/coord->zloc zloc [3])))))))

(deftest test-coord->zloc-nested
  (testing "Navigates nested forms"
    (let [zloc (z/of-string "(defn foo [a] (+ a 1))")]
      ;; [3] -> (+ a 1)
      ;; [3 0] -> +
      ;; [3 1] -> a
      ;; [3 2] -> 1
      (is (= '+ (z/sexpr (mapper/coord->zloc zloc [3 0]))))
      (is (= 'a (z/sexpr (mapper/coord->zloc zloc [3 1]))))
      (is (= 1 (z/sexpr (mapper/coord->zloc zloc [3 2])))))))

(deftest test-coord->zloc-invalid
  (testing "Returns nil for invalid coordinates"
    (let [zloc (z/of-string "(+ 1 2)")]
      (is (nil? (mapper/coord->zloc zloc [99])))
      (is (nil? (mapper/coord->zloc zloc [0 0 0]))))))

;; =============================================================================
;; Coordinate Extraction
;; =============================================================================

(deftest test-zloc->coord-simple
  (testing "Extracts coordinate for element"
    (let [zloc (z/of-string "(+ 1 2)")
          plus-zloc (z/down zloc)]
      (is (= [0] (mapper/zloc->coord plus-zloc))))))

(deftest test-zloc->coord-root
  (testing "Root element has nil coordinate"
    (let [zloc (z/of-string "(+ 1 2)")]
      (is (nil? (mapper/zloc->coord zloc))))))

;; =============================================================================
;; Round-Trip Validation (CRITICAL)
;; =============================================================================

(deftest test-round-trip-sequential
  (testing "Round-trip for sequential forms"
    (let [zloc (z/of-string "(defn foo [a b] (+ (* a b) (- a b)))")]
      (doseq [coord [[0] [1] [2] [2 0] [2 1]
                     [3] [3 0] [3 1] [3 1 0] [3 1 1] [3 1 2]
                     [3 2] [3 2 0] [3 2 1] [3 2 2]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for " coord ": " result)))))))

;; TODO: Add round-trip tests for maps and sets
;; These require matching ClojureStorm's hash algorithm

;; =============================================================================
;; Map/Set Navigation (TODO)
;; =============================================================================

(deftest test-map-navigation
  (testing "Navigates maps using hash coordinates"
    ;; TODO: Implement and test hash-based navigation
    ;; Requires matching ClojureStorm's exact hashing
    ))

(deftest test-set-navigation
  (testing "Navigates sets using hash coordinates"
    ;; TODO: Implement and test hash-based navigation
    ))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-reader-macros
  (testing "Handles reader macros"
    ;; TODO: Test @, ', #(), etc.
    ;; These expand before instrumentation
    ))

(deftest test-metadata
  (testing "Handles metadata on forms"
    ;; TODO: Verify coordinate navigation with metadata
    ))
