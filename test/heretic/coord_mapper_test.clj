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
;; Edge Cases: Reader Macros
;; =============================================================================

(deftest test-deref-reader-macro
  (testing "Navigates deref @ reader macro"
    (let [zloc (z/of-string "(do @foo)")]
      ;; (do @foo)
      ;;  0   1
      (is (= 'do (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= '(deref foo) (z/sexpr (mapper/coord->zloc zloc [1]))))

      (testing "round-trip for deref"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1]))))))

  (testing "Navigates into deref expression"
    (let [zloc (z/of-string "(do @(atom nil))")]
      ;; @(atom nil) -> [1]
      ;; (atom nil) -> [1 0]
      ;; atom -> [1 0 0]
      ;; nil -> [1 0 1]
      (is (= 'atom (z/sexpr (mapper/coord->zloc zloc [1 0 0]))))
      (is (= nil (z/sexpr (mapper/coord->zloc zloc [1 0 1]))))

      (testing "round-trip into deref"
        (is (:valid (mapper/validate-round-trip zloc [1])))
        (is (:valid (mapper/validate-round-trip zloc [1 0])))
        (is (:valid (mapper/validate-round-trip zloc [1 0 0])))
        (is (:valid (mapper/validate-round-trip zloc [1 0 1])))))))

(deftest test-quote-reader-macro
  (testing "Navigates quote ' reader macro"
    (let [zloc (z/of-string "'(a b c)")]
      ;; Root is :quote, child is the list
      (is (= :quote (z/tag zloc)))
      (is (= '(a b c) (z/sexpr (mapper/coord->zloc zloc [0]))))

      (testing "navigate into quoted list"
        (is (= 'a (z/sexpr (mapper/coord->zloc zloc [0 0]))))
        (is (= 'b (z/sexpr (mapper/coord->zloc zloc [0 1]))))
        (is (= 'c (z/sexpr (mapper/coord->zloc zloc [0 2])))))

      (testing "round-trip for quoted forms"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [0 0])))
        (is (:valid (mapper/validate-round-trip zloc [0 1])))
        (is (:valid (mapper/validate-round-trip zloc [0 2])))))))

(deftest test-anonymous-fn-reader-macro
  (testing "Navigates anonymous fn #() reader macro"
    (let [zloc (z/of-string "(map #(+ % 1) xs)")]
      ;; (map #(+ % 1) xs)
      ;;   0      1     2
      (is (= 'map (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= 'xs (z/sexpr (mapper/coord->zloc zloc [2]))))

      (testing "navigate into anonymous fn body"
        ;; #(+ % 1) -> [1]
        ;; + -> [1 0]
        ;; % -> [1 1]
        ;; 1 -> [1 2]
        (is (= "#(+ % 1)" (z/string (mapper/coord->zloc zloc [1]))))
        (is (= '+ (z/sexpr (mapper/coord->zloc zloc [1 0]))))
        (is (= 1 (z/sexpr (mapper/coord->zloc zloc [1 2])))))

      (testing "round-trip for anonymous fn"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1])))
        (is (:valid (mapper/validate-round-trip zloc [1 0])))
        (is (:valid (mapper/validate-round-trip zloc [1 1])))
        (is (:valid (mapper/validate-round-trip zloc [1 2])))
        (is (:valid (mapper/validate-round-trip zloc [2])))))))

(deftest test-syntax-quote-reader-macro
  (testing "Navigates syntax quote ` reader macro"
    (let [zloc (z/of-string "`(foo ~bar)")]
      (is (= :syntax-quote (z/tag zloc)))

      (testing "navigate into syntax-quoted list"
        ;; The inner list is at [0]
        (is (= "(foo ~bar)" (z/string (mapper/coord->zloc zloc [0]))))
        ;; foo is at [0 0]
        (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [0 0]))))
        ;; ~bar is at [0 1] (unquote node)
        (is (= :unquote (z/tag (mapper/coord->zloc zloc [0 1])))))

      (testing "round-trip for syntax-quoted forms"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [0 0])))
        (is (:valid (mapper/validate-round-trip zloc [0 1])))))))

(deftest test-unquote-reader-macro
  (testing "Navigates unquote ~ reader macro"
    (let [zloc (z/of-string "~foo")]
      (is (= :unquote (z/tag zloc)))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [0]))))

      (testing "round-trip for unquote"
        (is (:valid (mapper/validate-round-trip zloc [0]))))))

  (testing "Navigates unquote-splicing ~@ reader macro"
    (let [zloc (z/of-string "~@foo")]
      (is (= :unquote-splicing (z/tag zloc)))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [0]))))

      (testing "round-trip for unquote-splicing"
        (is (:valid (mapper/validate-round-trip zloc [0])))))))

(deftest test-var-quote-reader-macro
  (testing "Navigates var quote #' reader macro"
    (let [zloc (z/of-string "(var foo)")]
      ;; (var foo) is the expanded form
      (is (= 'var (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [1]))))

      (testing "round-trip for var quote"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1])))))))

(deftest test-regex-reader-macro
  (testing "Navigates regex #\"\" reader macro"
    (let [zloc (z/of-string "(re-find #\"foo\" s)")]
      ;; (re-find #"foo" s)
      ;;    0       1    2
      (is (= 're-find (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= :regex (z/tag (mapper/coord->zloc zloc [1]))))
      (is (= 's (z/sexpr (mapper/coord->zloc zloc [2]))))

      (testing "round-trip for regex"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1])))
        (is (:valid (mapper/validate-round-trip zloc [2])))))))

;; =============================================================================
;; Edge Cases: Metadata
;; =============================================================================

(deftest test-keyword-metadata
  (testing "Navigates forms with keyword metadata"
    (let [zloc (z/of-string "(defn ^:private foo [] nil)")]
      ;; (defn ^:private foo [] nil)
      ;;   0       1        2   3
      ;; Note: ^:private foo is a single :meta node at position 1
      (is (= 'defn (z/sexpr (mapper/coord->zloc zloc [0]))))
      ;; The meta node contains the symbol foo
      (is (= :meta (z/tag (mapper/coord->zloc zloc [1]))))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (= [] (z/sexpr (mapper/coord->zloc zloc [2]))))
      (is (= nil (z/sexpr (mapper/coord->zloc zloc [3]))))

      (testing "round-trip for metadata forms"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1])))
        (is (:valid (mapper/validate-round-trip zloc [2])))
        (is (:valid (mapper/validate-round-trip zloc [3])))))))

(deftest test-map-metadata
  (testing "Navigates forms with map metadata"
    (let [zloc (z/of-string "(defn ^{:doc \"test\"} foo [] nil)")]
      ;; (defn ^{:doc "test"} foo [] nil)
      ;;   0          1         2   3
      (is (= 'defn (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= :meta (z/tag (mapper/coord->zloc zloc [1]))))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (= [] (z/sexpr (mapper/coord->zloc zloc [2]))))

      (testing "round-trip for map metadata"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1])))
        (is (:valid (mapper/validate-round-trip zloc [2])))))))

(deftest test-type-hint-metadata
  (testing "Navigates forms with type hint metadata"
    (let [zloc (z/of-string "(defn foo [^String x] (.length x))")]
      ;; (defn foo [^String x] (.length x))
      ;;   0    1       2           3
      (is (= 'defn (z/sexpr (mapper/coord->zloc zloc [0]))))
      (is (= 'foo (z/sexpr (mapper/coord->zloc zloc [1]))))
      ;; The vector is at [2]
      (is (= :vector (z/tag (mapper/coord->zloc zloc [2]))))
      ;; Inside the vector, ^String x is a meta node at [2 0]
      (is (= :meta (z/tag (mapper/coord->zloc zloc [2 0]))))
      (is (= 'x (z/sexpr (mapper/coord->zloc zloc [2 0]))))

      (testing "round-trip for type hints"
        (is (:valid (mapper/validate-round-trip zloc [0])))
        (is (:valid (mapper/validate-round-trip zloc [1])))
        (is (:valid (mapper/validate-round-trip zloc [2])))
        (is (:valid (mapper/validate-round-trip zloc [2 0])))
        (is (:valid (mapper/validate-round-trip zloc [3])))))))

(deftest test-multiple-metadata
  (testing "Navigates forms with multiple metadata"
    (let [zloc (z/of-string "[^:foo ^:bar x]")]
      ;; [^:foo ^:bar x]
      ;;       0
      ;; The whole ^:foo ^:bar x is a nested meta node
      (is (= :meta (z/tag (mapper/coord->zloc zloc [0]))))
      (is (= 'x (z/sexpr (mapper/coord->zloc zloc [0]))))

      (testing "round-trip for multiple metadata"
        (is (:valid (mapper/validate-round-trip zloc [0])))))))

(deftest test-metadata-on-collections
  (testing "Navigates metadata on collections"
    (let [zloc (z/of-string "^:const [1 2 3]")]
      (is (= :meta (z/tag zloc)))
      ;; The value [1 2 3] is accessible via sexpr
      (is (= [1 2 3] (z/sexpr zloc)))

      ;; Navigate into the meta node's children
      ;; [0] is the metadata keyword :const
      ;; [1] is the vector [1 2 3]
      (let [meta-down (z/down zloc)]
        (is (= :token (z/tag meta-down)))  ; :const
        (let [vec-node (z/right meta-down)]
          (is (= :vector (z/tag vec-node)))
          (is (= [1 2 3] (z/sexpr vec-node))))))))
