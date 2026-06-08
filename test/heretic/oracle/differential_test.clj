(ns heretic.oracle.differential-test
  "Tests for the differential-oracle building blocks (pure parts) plus one
   end-to-end classify on a temp fixture namespace — proving the in-memory
   apply/observe/restore path labels a known-killable mutant :killable and a
   known-equivalent mutant :candidate-equivalent."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.oracle.differential :as diff]
            [heretic.oracle.gen :as gen])
  (:import [java.io File]))

(deftest gen-is-seeded-and-deterministic
  (testing "same seed ⇒ identical inputs; different seed ⇒ different"
    (is (= (gen/inputs 2 50 7) (gen/inputs 2 50 7)))
    (is (not= (gen/inputs 2 50 7) (gen/inputs 2 50 8))))
  (testing "shape: n arg-vectors each of the requested arity"
    (let [in (gen/inputs 3 10 1)]
      (is (= 10 (count in)))
      (is (every? #(= 3 (count %)) in)))))

(deftest pure-defn-detects-impurity
  (is (true?  (diff/pure-defn? '(defn add [a b] (+ a b)))))
  (is (true?  (diff/pure-defn? '(defn f [m] (assoc m :k (inc (:k m)))))))
  (is (false? (diff/pure-defn? '(defn f [a] (swap! a inc))))      "atom mutation")
  (is (false? (diff/pure-defn? '(defn f [w] (.write w 1))))       "interop method")
  (is (false? (diff/pure-defn? '(defn f [] (rand-int 10))))       "nondeterminism")
  (is (false? (diff/pure-defn? '(defn f [] (System/currentTimeMillis)))) "class static"))

(deftest form-def-info-classifies
  (is (= {:kind :defn :name 'add :pure? true} (diff/form-def-info "(defn add [a b] (+ a b))")))
  (is (= :def   (:kind (diff/form-def-info "(def x 1)"))))
  (is (= :other (:kind (diff/form-def-info "(defmethod foo :a [_] 1)")))))

(deftest arities-from-arglists
  (is (= [1]    (diff/arities '([x]))))
  (is (= [1 2]  (diff/arities '([x] [x y]))))
  (testing "variadic contributes required+2"
    (is (= [3]  (diff/arities '([x & xs]))))))

(deftest find-witness-soundness
  (testing "distinct fns ⇒ a witness; identical fns ⇒ none"
    (let [inputs (gen/inputs 2 100 3)]
      (is (some? (:witness (diff/find-witness + - inputs 2 300))))
      (is (nil?  (:witness (diff/find-witness + + inputs 2 300))))
      (is (true? (:applicable (diff/find-witness + + inputs 2 300))))))
  (testing "type difference is distinguishing (pr-str fidelity): map vs mapv"
    (let [inputs (gen/inputs 1 60 4)
          mp  (fn [c] (when (sequential? c) (map inc c)))
          mpv (fn [c] (when (sequential? c) (mapv inc c)))]
      (is (some? (:witness (diff/find-witness mp mpv inputs 1 300)))))))

(deftest form-string-extraction
  (let [f (File/createTempFile "ora" ".clj")]
    (try
      (spit f "(ns ofx)\n(defn add [a b] (+ a b))\n")
      (let [muts (engine/mutations-for-file (.getPath f) ops/all-operators)
            plus (first (filter #(= :swap-plus-minus (:operator %)) muts))
            orig (diff/original-form-string plus)]
        (is (some? plus) "engine generates a +→- mutation")
        (is (= "(defn add [a b] (+ a b))" orig))
        (is (= "(defn add [a b] (- a b))" (diff/mutated-form-string plus orig))))
      (finally (.delete f)))))

(deftest classify-end-to-end
  (let [nm  (str "heretic.oracle.fixtgt" (System/nanoTime))
        f   (File/createTempFile "ora" ".clj")]
    (try
      ;; const ignores the literal entirely → swap-true-false on the literal is equivalent;
      ;; add is killable by +→-.
      (spit f (str "(ns " nm ")\n"
                   "(defn add [a b] (+ a b))\n"
                   "(defn const [a] (if true a a))\n"))
      (load-file (.getPath f))
      (let [muts (engine/mutations-for-file (.getPath f) ops/all-operators)
            by   (fn [op] (first (filter #(= op (:operator %)) muts)))
            opts {:ns-sym (symbol nm) :n-trials 100 :seed 1}]
        (testing "a +→- mutation on a numeric fn is provably killable"
          (is (= :killable (:label (diff/classify-mutant (by :swap-plus-minus) opts)))))
        (testing "true→false where both if-branches are identical is candidate-equivalent"
          (let [r (diff/classify-mutant (by :swap-true-false) opts)]
            (is (= :candidate-equivalent (:label r))))))
      (finally
        (remove-ns (symbol nm))
        (.delete f)))))
