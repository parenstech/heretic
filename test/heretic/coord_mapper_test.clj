(ns heretic.coord-mapper-test
  "Tests for coordinate mapping between ClojureStorm and rewrite-clj.

   These tests are CRITICAL - the coordinate mapper must be validated
   with round-trip tests before Phase 1 is complete.

   Validation requirement:
   (= coord (zloc->coord (coord->zloc zloc coord)))"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

;; =============================================================================
;; Map/Set Navigation
;; =============================================================================

(deftest test-map-navigation
  (testing "Navigates maps using hash coordinates"
    (let [zloc (z/of-string "{:a 1 :b 2}")
          ;; Navigate to each element and get its coordinate
          key-a (z/down zloc)
          val-1 (z/right key-a)
          key-b (z/right val-1)
          val-2 (z/right key-b)
          ;; Get the hash-based coordinates
          coord-key-a (mapper/zloc->coord key-a)
          coord-val-1 (mapper/zloc->coord val-1)
          coord-key-b (mapper/zloc->coord key-b)
          coord-val-2 (mapper/zloc->coord val-2)]

      (testing "zloc->coord returns K-prefixed coords for keys"
        (is (= 1 (count coord-key-a)))
        (is (string? (first coord-key-a)))
        (is (str/starts-with? (first coord-key-a) "K")))

      (testing "zloc->coord returns V-prefixed coords for values"
        (is (= 1 (count coord-val-1)))
        (is (string? (first coord-val-1)))
        (is (str/starts-with? (first coord-val-1) "V")))

      (testing "coord->zloc navigates to correct key"
        (is (= :a (z/sexpr (mapper/coord->zloc zloc coord-key-a))))
        (is (= :b (z/sexpr (mapper/coord->zloc zloc coord-key-b)))))

      (testing "coord->zloc navigates to correct value"
        (is (= 1 (z/sexpr (mapper/coord->zloc zloc coord-val-1))))
        (is (= 2 (z/sexpr (mapper/coord->zloc zloc coord-val-2)))))

      (testing "round-trip for map keys"
        (is (:valid (mapper/validate-round-trip zloc coord-key-a)))
        (is (:valid (mapper/validate-round-trip zloc coord-key-b))))

      (testing "round-trip for map values"
        (is (:valid (mapper/validate-round-trip zloc coord-val-1)))
        (is (:valid (mapper/validate-round-trip zloc coord-val-2))))))

  (testing "Navigates nested maps"
    (let [zloc (z/of-string "{:outer {:inner 42}}")
          outer-key (z/down zloc)
          outer-val (z/right outer-key)
          coord-outer-val (mapper/zloc->coord outer-val)]

      (testing "outer value is the inner map"
        (is (= {:inner 42} (z/sexpr (mapper/coord->zloc zloc coord-outer-val)))))

      ;; Navigate into the inner map
      (let [inner-zloc (mapper/coord->zloc zloc coord-outer-val)
            inner-key (z/down inner-zloc)
            inner-val (z/right inner-key)
            coord-inner-key (mapper/zloc->coord inner-key)
            coord-inner-val (mapper/zloc->coord inner-val)]

        (testing "inner map key navigation"
          (is (= :inner (z/sexpr (mapper/coord->zloc zloc coord-inner-key)))))

        (testing "inner map value navigation"
          (is (= 42 (z/sexpr (mapper/coord->zloc zloc coord-inner-val)))))

        (testing "round-trip for nested elements"
          (is (:valid (mapper/validate-round-trip zloc coord-inner-key)))
          (is (:valid (mapper/validate-round-trip zloc coord-inner-val))))))))

(deftest test-set-navigation
  (testing "Navigates sets using hash coordinates"
    (let [zloc (z/of-string "#{:a :b :c}")
          ;; Navigate to each element and get its coordinate
          el-a (z/down zloc)
          el-b (z/right el-a)
          el-c (z/right el-b)
          ;; Get the hash-based coordinates
          coord-a (mapper/zloc->coord el-a)
          coord-b (mapper/zloc->coord el-b)
          coord-c (mapper/zloc->coord el-c)]

      (testing "zloc->coord returns K-prefixed coords for set elements"
        (is (= 1 (count coord-a)))
        (is (string? (first coord-a)))
        (is (str/starts-with? (first coord-a) "K"))
        (is (str/starts-with? (first coord-b) "K"))
        (is (str/starts-with? (first coord-c) "K")))

      (testing "coord->zloc navigates to correct elements"
        (is (= :a (z/sexpr (mapper/coord->zloc zloc coord-a))))
        (is (= :b (z/sexpr (mapper/coord->zloc zloc coord-b))))
        (is (= :c (z/sexpr (mapper/coord->zloc zloc coord-c)))))

      (testing "round-trip for set elements"
        (is (:valid (mapper/validate-round-trip zloc coord-a)))
        (is (:valid (mapper/validate-round-trip zloc coord-b)))
        (is (:valid (mapper/validate-round-trip zloc coord-c))))))

  (testing "Navigates sets with complex elements"
    (let [zloc (z/of-string "#{[1 2] {:a 1} (+ 1 2)}")
          el-1 (z/down zloc)
          el-2 (z/right el-1)
          el-3 (z/right el-2)
          coord-1 (mapper/zloc->coord el-1)
          coord-2 (mapper/zloc->coord el-2)
          coord-3 (mapper/zloc->coord el-3)]

      (testing "coord->zloc works with complex elements"
        (is (= [1 2] (z/sexpr (mapper/coord->zloc zloc coord-1))))
        (is (= {:a 1} (z/sexpr (mapper/coord->zloc zloc coord-2))))
        (is (= '(+ 1 2) (z/sexpr (mapper/coord->zloc zloc coord-3)))))

      (testing "round-trip for complex set elements"
        (is (:valid (mapper/validate-round-trip zloc coord-1)))
        (is (:valid (mapper/validate-round-trip zloc coord-2)))
        (is (:valid (mapper/validate-round-trip zloc coord-3)))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest ^:pending test-reader-macros
  (testing "Handles reader macros"
    ;; TODO: Test @, ', #(), etc.
    ;; These expand before instrumentation
    (is (= :pending :pending) "Pending: Phase 2 feature")))

(deftest ^:pending test-metadata
  (testing "Handles metadata on forms"
    ;; TODO: Verify coordinate navigation with metadata
    (is (= :pending :pending) "Pending: Phase 2 feature")))
