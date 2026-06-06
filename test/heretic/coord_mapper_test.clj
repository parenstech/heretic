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
      ;; rewrite-clj 1.2.x renders @foo as Clojure's reader does: (clojure.core/deref foo)
      (is (= '(clojure.core/deref foo) (z/sexpr (mapper/coord->zloc zloc [1]))))

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

;; =============================================================================
;; Comprehensive Round-Trip Tests
;; =============================================================================

(deftest test-round-trip-all-coordinate-types
  (testing "Round-trip validation for integer indices"
    (let [zloc (z/of-string "(+ 1 2 3 4 5)")]
      (doseq [i (range 6)]
        (let [coord [i]
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for integer index " i ": " result))))))

  (testing "Round-trip validation for K-hash (map keys)"
    (let [zloc (z/of-string "{:a 1 :b 2 :c 3}")
          key-a (z/down zloc)
          key-b (-> zloc z/down z/right z/right)
          key-c (-> zloc z/down z/right z/right z/right z/right)
          coord-a (mapper/zloc->coord key-a)
          coord-b (mapper/zloc->coord key-b)
          coord-c (mapper/zloc->coord key-c)]
      (is (:valid (mapper/validate-round-trip zloc coord-a))
          "Round-trip failed for K-hash key :a")
      (is (:valid (mapper/validate-round-trip zloc coord-b))
          "Round-trip failed for K-hash key :b")
      (is (:valid (mapper/validate-round-trip zloc coord-c))
          "Round-trip failed for K-hash key :c")))

  (testing "Round-trip validation for V-hash (map values)"
    (let [zloc (z/of-string "{:a 100 :b 200 :c 300}")
          val-a (-> zloc z/down z/right)
          val-b (-> zloc z/down z/right z/right z/right)
          val-c (-> zloc z/down z/right z/right z/right z/right z/right)
          coord-a (mapper/zloc->coord val-a)
          coord-b (mapper/zloc->coord val-b)
          coord-c (mapper/zloc->coord val-c)]
      (is (:valid (mapper/validate-round-trip zloc coord-a))
          "Round-trip failed for V-hash value 100")
      (is (:valid (mapper/validate-round-trip zloc coord-b))
          "Round-trip failed for V-hash value 200")
      (is (:valid (mapper/validate-round-trip zloc coord-c))
          "Round-trip failed for V-hash value 300"))))

(deftest test-round-trip-edge-cases
  (testing "Empty list round-trip"
    (let [zloc (z/of-string "(do ())")]
      ;; (do ()) - [1] is the empty list
      (is (= () (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (:valid (mapper/validate-round-trip zloc [0]))
          "Round-trip failed for 'do'")
      (is (:valid (mapper/validate-round-trip zloc [1]))
          "Round-trip failed for empty list")))

  (testing "Single-element list round-trip"
    (let [zloc (z/of-string "(x)")]
      (is (:valid (mapper/validate-round-trip zloc [0]))
          "Round-trip failed for single element")))

  (testing "Empty vector round-trip"
    (let [zloc (z/of-string "(fn [] nil)")]
      ;; [] is at [1]
      (is (= [] (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (:valid (mapper/validate-round-trip zloc [1]))
          "Round-trip failed for empty vector")))

  (testing "Empty map round-trip"
    (let [zloc (z/of-string "(do {})")]
      (is (= {} (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (:valid (mapper/validate-round-trip zloc [1]))
          "Round-trip failed for empty map")))

  (testing "Empty set round-trip"
    (let [zloc (z/of-string "(do #{})")]
      (is (= #{} (z/sexpr (mapper/coord->zloc zloc [1]))))
      (is (:valid (mapper/validate-round-trip zloc [1]))
          "Round-trip failed for empty set")))

  (testing "Deeply nested coordinates"
    (let [zloc (z/of-string "(a (b (c (d (e (f (g x)))))))")]
      ;; Navigate to x: [1 1 1 1 1 1 1]
      (is (= 'x (z/sexpr (mapper/coord->zloc zloc [1 1 1 1 1 1 1]))))
      (doseq [depth (range 1 8)]
        (let [coord (vec (repeat depth 1))
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed at depth " depth ": " result)))))))

(deftest test-round-trip-unordered-collections
  (testing "Map with multiple keys - all elements"
    (let [zloc (z/of-string "{:alpha 1 :beta 2 :gamma 3 :delta 4}")
          all-elements (loop [el (z/down zloc)
                              acc []]
                         (if el
                           (recur (z/right el) (conj acc el))
                           acc))]
      (doseq [el all-elements]
        (let [coord (mapper/zloc->coord el)
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for map element: " (z/sexpr el) " coord: " coord))))))

  (testing "Set with multiple elements"
    (let [zloc (z/of-string "#{:one :two :three :four :five}")
          all-elements (loop [el (z/down zloc)
                              acc []]
                         (if el
                           (recur (z/right el) (conj acc el))
                           acc))]
      (doseq [el all-elements]
        (let [coord (mapper/zloc->coord el)
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for set element: " (z/sexpr el)))))))

  (testing "Nested maps - all levels"
    (let [zloc (z/of-string "{:outer {:middle {:inner 42}}}")
          ;; Navigate to each level
          outer-key (z/down zloc)
          outer-val (z/right outer-key)
          middle-key (z/down outer-val)
          middle-val (z/right middle-key)
          inner-key (z/down middle-val)
          inner-val (z/right inner-key)]
      (doseq [[name el] [["outer-key" outer-key]
                         ["outer-val" outer-val]
                         ["middle-key" middle-key]
                         ["middle-val" middle-val]
                         ["inner-key" inner-key]
                         ["inner-val" inner-val]]]
        (let [coord (mapper/zloc->coord el)
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for " name))))))

  (testing "Map with complex keys and values"
    (let [zloc (z/of-string "{[1 2] {:a 1} {:x 1} [3 4]}")
          key-1 (z/down zloc)           ; [1 2]
          val-1 (z/right key-1)         ; {:a 1}
          key-2 (z/right val-1)         ; {:x 1}
          val-2 (z/right key-2)]        ; [3 4]
      (doseq [[name el] [["vector key" key-1]
                         ["map value" val-1]
                         ["map key" key-2]
                         ["vector value" val-2]]]
        (let [coord (mapper/zloc->coord el)
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for " name)))))))

(deftest test-malformed-coordinate-handling
  (testing "Empty coordinate string navigates to root"
    (let [zloc (z/of-string "(+ 1 2)")]
      ;; Empty coord should return the root zloc itself
      (is (= '(+ 1 2) (z/sexpr (mapper/coord->zloc zloc ""))))))

  (testing "Coordinate with invalid hash format returns nil"
    (let [zloc (z/of-string "{:a 1}")]
      ;; Invalid hash that doesn't exist in the map
      (is (nil? (mapper/coord->zloc zloc ["K99999999"])))))

  (testing "Coordinate with wrong hash type returns nil"
    (let [zloc (z/of-string "{:a 1}")
          key-zloc (z/down zloc)
          key-hash (mapper/zloc->coord key-zloc)
          ;; Try to find the key hash as a value hash
          wrong-hash (str/replace (first key-hash) #"^K" "V")]
      ;; The value hash for the key won't match
      (is (nil? (mapper/coord->zloc zloc [wrong-hash])))))

  (testing "Parse coord handles edge cases"
    (is (= [] (mapper/parse-coord "")))
    (is (= [0] (mapper/parse-coord "0")))
    (is (= [0 0 0] (mapper/parse-coord "0,0,0")))))

(deftest test-coordinate-out-of-bounds
  (testing "Index beyond list length returns nil"
    (let [zloc (z/of-string "(a b c)")]
      (is (nil? (mapper/coord->zloc zloc [3])))
      (is (nil? (mapper/coord->zloc zloc [10])))
      (is (nil? (mapper/coord->zloc zloc [100])))))

  (testing "Negative index behavior"
    (let [zloc (z/of-string "(a b c)")]
      ;; Negative indices should fail gracefully
      (is (nil? (mapper/coord->zloc zloc [-1])))))

  (testing "Nested out-of-bounds"
    (let [zloc (z/of-string "(a (b c))")]
      ;; [1] exists, [1 0] exists, [1 2] does not
      (is (= 'b (z/sexpr (mapper/coord->zloc zloc [1 0]))))
      (is (= 'c (z/sexpr (mapper/coord->zloc zloc [1 1]))))
      (is (nil? (mapper/coord->zloc zloc [1 2])))
      (is (nil? (mapper/coord->zloc zloc [1 0 0])))))

  (testing "Navigation into non-collection returns nil"
    (let [zloc (z/of-string "(+ 1 2)")]
      ;; 1 is an atom, can't navigate into it
      (is (nil? (mapper/coord->zloc zloc [1 0])))))

  (testing "Hash coordinate on sequential collection returns nil"
    (let [zloc (z/of-string "[1 2 3]")]
      ;; K-hash on vector should fail
      (is (nil? (mapper/coord->zloc zloc ["K12345"]))))))

(deftest test-round-trip-complex-expressions
  (testing "let binding with destructuring"
    (let [zloc (z/of-string "(let [{:keys [a b]} m] (+ a b))")]
      ;; (let [{:keys [a b]} m] (+ a b))
      ;;   0         1              2
      ;; bindings vector at [1]
      ;; destructuring map at [1 0]
      ;; m at [1 1]
      ;; body at [2]
      (doseq [coord [[0] [1] [1 0] [1 1] [2] [2 0] [2 1] [2 2]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for let binding coord " coord ": " result))))))

  (testing "defn with docstring and multiple arities"
    (let [zloc (z/of-string "(defn foo \"doc\" ([x] x) ([x y] (+ x y)))")]
      ;; (defn foo "doc" ([x] x) ([x y] (+ x y)))
      ;;   0    1    2      3          4
      (doseq [coord [[0] [1] [2] [3] [3 0] [3 0 0] [3 1] [4] [4 0] [4 1]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for defn coord " coord ": " result))))))

  (testing "Threading macro with mixed collections"
    (let [zloc (z/of-string "(-> {:a 1} (assoc :b 2) (update :a inc))")]
      ;; (-> {:a 1} (assoc :b 2) (update :a inc))
      ;;  0    1         2             3
      (is (:valid (mapper/validate-round-trip zloc [0])))
      (is (:valid (mapper/validate-round-trip zloc [1])))
      (is (:valid (mapper/validate-round-trip zloc [2])))
      (is (:valid (mapper/validate-round-trip zloc [3])))
      ;; Navigate into the map {:a 1}
      (let [map-zloc (mapper/coord->zloc zloc [1])
            key-a (z/down map-zloc)
            val-1 (z/right key-a)
            coord-key (mapper/zloc->coord key-a)
            coord-val (mapper/zloc->coord val-1)]
        (is (:valid (mapper/validate-round-trip zloc coord-key))
            "Round-trip failed for map key in threading")
        (is (:valid (mapper/validate-round-trip zloc coord-val))
            "Round-trip failed for map value in threading"))))

  (testing "cond with map predicates"
    (let [zloc (z/of-string "(cond (contains? m :a) (:a m) :else nil)")]
      ;; (cond (contains? m :a) (:a m) :else nil)
      ;;   0         1            2      3    4
      (doseq [coord [[0] [1] [1 0] [1 1] [1 2] [2] [2 0] [2 1] [3] [4]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for cond coord " coord ": " result))))))

  (testing "for comprehension with :let and :when"
    (let [zloc (z/of-string "(for [x xs :let [y (inc x)] :when (pos? y)] y)")]
      ;; Complex binding form
      (doseq [coord [[0] [1] [2]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for 'for' coord " coord ": " result))))))

  (testing "Protocol implementation with methods"
    (let [zloc (z/of-string "(reify IFoo (foo [this x] (+ x 1)) (bar [this] nil))")]
      (doseq [coord [[0] [1] [2] [2 0] [2 1] [2 2] [3] [3 0] [3 1] [3 2]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for reify coord " coord ": " result))))))

  (testing "Namespace-qualified keywords in maps"
    (let [zloc (z/of-string "{::a 1 :foo/bar 2 :baz/qux 3}")
          all-elements (loop [el (z/down zloc)
                              acc []]
                         (if el
                           (recur (z/right el) (conj acc el))
                           acc))]
      (doseq [el all-elements]
        (let [coord (mapper/zloc->coord el)
              result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for ns-qualified element: " (z/string el)))))))

  (testing "Mixed ordered and unordered at multiple levels"
    (let [zloc (z/of-string "(defn process [{:keys [id name]} opts]
                               (when-let [{:keys [validate?]} opts]
                                 {:id id :name name :valid validate?}))")]
      ;; Test key coordinates
      (doseq [coord [[0] [1] [2] [3]]]
        (let [result (mapper/validate-round-trip zloc coord)]
          (is (:valid result)
              (str "Round-trip failed for mixed coord " coord ": " result)))))))

(deftest test-round-trip-stringify-parse-symmetry
  (testing "stringify and parse are inverses for integer coords"
    (let [coords [[0] [1 2 3] [0 0 0] [10 20 30]]]
      (doseq [coord coords]
        (is (= coord (mapper/parse-coord (mapper/stringify-coord coord)))
            (str "stringify/parse symmetry failed for " coord)))))

  (testing "stringify and parse are inverses for hash coords"
    (let [coords [["K12345"] ["V67890"] [0 "K111" 2] ["K1" "V2" "K3"]]]
      (doseq [coord coords]
        (is (= coord (mapper/parse-coord (mapper/stringify-coord coord)))
            (str "stringify/parse symmetry failed for " coord)))))

  (testing "Actual hash coordinates round-trip through stringify/parse"
    (let [zloc (z/of-string "{:a {:b {:c 1}}}")
          ;; Collect actual coordinates from the structure
          key-a (z/down zloc)
          val-a (z/right key-a)
          key-b (z/down val-a)
          val-b (z/right key-b)
          key-c (z/down val-b)
          val-c (z/right key-c)]
      (doseq [[name el] [["key-a" key-a]
                         ["val-a" val-a]
                         ["key-b" key-b]
                         ["val-b" val-b]
                         ["key-c" key-c]
                         ["val-c" val-c]]]
        (let [coord (mapper/zloc->coord el)
              stringified (mapper/stringify-coord coord)
              parsed (mapper/parse-coord stringified)]
          (is (= coord parsed)
              (str "stringify/parse failed for " name ": " coord " -> " stringified " -> " parsed))
          ;; Also verify full round-trip works with stringified coord
          (is (= (z/sexpr el) (z/sexpr (mapper/coord->zloc zloc stringified)))
              (str "Navigation with stringified coord failed for " name)))))))
