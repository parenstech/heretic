(ns heretic.oracle.sound-test
  "Tests for the sound (FP=0) equivalence detectors. read-identity now delegates to
   heretic.equivalent/read-identical? (covered by heretic.equivalent-test); this ns
   covers the oracle-local macroexpand-identity detector."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.oracle.sound :as sound]))

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

(deftest macroexpand-identity-adds-power-over-read-identity
  (testing "forms that READ differently but macroexpand-all identically are caught
            — the case read-identity cannot see, i.e. why this detector exists"
    ;; (-> 1 inc) and (inc 1) are different data when read, but both expand to (inc 1).
    (is (not= (read-string "(-> 1 inc)") (read-string "(inc 1)"))
        "sanity: the two forms read to different data")
    (is (true? (sound/macroexpand-identical? 'clojure.core "(-> 1 inc)" "(inc 1)"))
        "macroexpansion proves them equivalent where a plain read-= would miss it")))
