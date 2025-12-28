(ns sample.core-test
  "Tests for sample.core - used to validate ClojureStorm coverage collection"
  (:require [clojure.test :refer [deftest testing is]]
            [sample.core :as core]))

(deftest test-add
  (testing "Basic addition"
    (is (= 5 (core/add 2 3)))
    (is (= 0 (core/add -1 1)))))

(deftest test-multiply
  (testing "Basic multiplication"
    (is (= 6 (core/multiply 2 3)))
    (is (= 0 (core/multiply 0 100)))))

(deftest test-compute-with-nested
  (testing "Nested arithmetic"
    (is (= 12.0 (core/compute-with-nested 2 5 4)))))

(deftest test-classify-number
  (testing "Number classification with cond"
    (is (= :negative (core/classify-number -5)))
    (is (= :zero (core/classify-number 0)))
    (is (= :positive (core/classify-number 10)))))

(deftest test-safe-divide
  (testing "Division with zero handling"
    (is (= 2 (core/safe-divide 10 5)))
    (is (nil? (core/safe-divide 10 0)))))

(deftest test-check-range
  (testing "Range checking"
    (is (= :in-range (core/check-range 5 0 10)))
    (is (nil? (core/check-range 15 0 10)))))

(deftest test-lookup-config
  (testing "Map lookup"
    (is (= "heretic" (core/lookup-config core/config-map :name)))
    (is (= {:level 1 :deep {:value 42}}
           (core/lookup-config core/config-map :nested)))))

(deftest test-has-tag
  (testing "Set membership"
    (is (true? (core/has-tag? core/tag-set :alpha)))
    (is (false? (core/has-tag? core/tag-set :delta)))))

(deftest test-process-with-map
  (testing "Inline map creation and access"
    (is (= 7 (core/process-with-map 3 4)))))

(deftest test-filter-with-set
  (testing "Filtering with set"
    (is (= [:a :c] (vec (core/filter-with-set [:a :b :c :d] #{:a :c :e}))))))

(deftest test-factorial
  (testing "Recursive factorial"
    (is (= 1 (core/factorial 0)))
    (is (= 1 (core/factorial 1)))
    (is (= 120 (core/factorial 5)))))

(deftest test-reduce-example
  (testing "Reduce with anonymous function"
    (is (= 14 (core/reduce-example [1 2 3])))))

(deftest test-literal-map
  (testing "Literal map in function body"
    (is (= 1 (core/use-literal-map :key-a)))
    (is (= 2 (core/use-literal-map :key-b)))
    (is (nil? (core/use-literal-map :key-d)))))

(deftest test-literal-set
  (testing "Literal set in function body"
    (is (true? (core/use-literal-set :val-x)))
    (is (false? (core/use-literal-set :val-w)))))
