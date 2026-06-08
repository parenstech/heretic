(ns heretic.oracle.sound-test
  "Tests for the sound (FP=0) equivalence detectors. Soundness is the whole point:
   read-identity must fire on a `.cljc` dead-branch mutation and must NOT fire on
   a live mutation."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.oracle.sound :as sound]))

(deftest read-identity-catches-jvm-dead-branches
  (testing "a mutation inside a #?(:cljs …) branch reads identically on the JVM"
    (let [orig "(defn f [x] #?(:clj (+ x 1) :cljs (- x 1)))"
          ;; mutate the :cljs branch (- -> +): invisible to the JVM reader
          mut  "(defn f [x] #?(:clj (+ x 1) :cljs (+ x 1)))"]
      (is (true? (sound/read-identical? orig mut)))))
  (testing "a mutation in the :clj branch is NOT read-identical (live code)"
    (let [orig "(defn f [x] #?(:clj (+ x 1) :cljs (- x 1)))"
          mut  "(defn f [x] #?(:clj (- x 1) :cljs (- x 1)))"]
      (is (false? (sound/read-identical? orig mut)))))
  (testing "a plain-Clojure live mutation is not read-identical"
    (is (false? (sound/read-identical? "(defn f [x] (+ x 1))"
                                       "(defn f [x] (- x 1))")))))

(deftest macroexpand-identity-is-conservative
  (testing "identical forms macroexpand-identical"
    (is (true? (sound/macroexpand-identical?
                'clojure.core "(when true 1)" "(when true 1)"))))
  (testing "a live difference is not macroexpand-identical"
    (is (not (sound/macroexpand-identical?
              'clojure.core "(when true 1)" "(when false 1)"))))
  (testing "unknown ns ⇒ cannot prove ⇒ falsey (never a false positive)"
    (is (not (sound/macroexpand-identical?
              'no.such.ns123 "(+ 1 2)" "(+ 1 2)")))))
