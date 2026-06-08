(ns heretic.oracle.harvest-test
  "Tests for the pure suite-seeded-fuzzing helpers (perturbation + input assembly).
   harvest-args itself is exercised end-to-end by the harness on real targets."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.oracle.harvest :as harvest])
  (:import [java.util Random]))

(deftest perturb-value-is-type-aware
  (let [r (Random. 1)]
    (is (= false (harvest/perturb-value r true)) "boolean flips")
    (is (number? (harvest/perturb-value r 5)) "number stays a number")
    (is (string? (harvest/perturb-value r "abc")) "string stays a string")
    (is (some? (harvest/perturb-value r nil)) "nil becomes a concrete value")
    (is (vector? (harvest/perturb-value r [1 2 3])) "vector stays a vector")))

(deftest perturbations-skip-empty-and-vary-one-position
  (let [r (Random. 2)
        out (harvest/perturbations r [[1 2] []] 3)]
    (testing "empty tuple is skipped; non-empty yields k variants"
      (is (= 3 (count out)))
      (is (every? #(= 2 (count %)) out)))
    (testing "each variant perturbs at most one position (a tweak may coincide)"
      (doseq [v out]
        (is (<= (count (remove true? (map = [1 2] v))) 1))))))

(deftest inputs-for-combines-base-and-perturbations
  (let [r (Random. 3)
        harvested {'f [[1] [2] [3]]}
        in (harvest/inputs-for r harvested 'f {:perturb-k 2})]
    (testing "base tuples are included, plus k perturbations each"
      (is (= (+ 3 (* 3 2)) (count in)))      ; 3 base + 3*2 perturbations
      (is (every? #(= 1 (count %)) in)))
    (testing "a fn the suite never called ⇒ nil (oracle falls back to random)"
      (is (nil? (harvest/inputs-for r harvested 'never-called {}))))))
