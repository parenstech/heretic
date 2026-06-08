(ns heretic.oracle.harvest-test
  "Tests for the pure suite-seeded-fuzzing helpers (perturbation + input assembly)
   plus the var-spying harvest itself: one suite run records the real call tuples
   across many target namespaces (harvest-args-across) and the single-target
   wrapper (harvest-args, incl. the single-symbol / collection test-ns forms)."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.oracle.harvest :as harvest])
  (:import [java.util Random]))

(defn- eval-ns
  "Eval a source string that defines a namespace, restoring *ns* afterwards (a
   bare (ns …) form would otherwise leave the test runner in the new ns)."
  [s]
  (binding [*ns* *ns*] (eval (read-string s))))

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

(deftest harvest-records-real-call-tuples-in-one-suite-run
  (let [tgt (str "heretic.htgt" (System/nanoTime))
        ta  (str "heretic.hta" (System/nanoTime))
        tb  (str "heretic.htb" (System/nanoTime))]
    (try
      (eval-ns (str "(do (ns " tgt ") (defn twice [x] (* 2 x)))"))
      (eval-ns (str "(do (ns " ta " (:require [clojure.test :refer [deftest is]] [" tgt " :as t]))"
                    " (deftest a (is (= 4 (t/twice 2)))))"))
      (eval-ns (str "(do (ns " tb " (:require [clojure.test :refer [deftest is]] [" tgt " :as t]))"
                    " (deftest b (is (= 6 (t/twice 3)))))"))
      (testing "harvest-args-across: ONE run over a vector of test nses records tuples from BOTH"
        (let [h (harvest/harvest-args-across [(symbol tgt)] [(symbol ta) (symbol tb)] {})]
          (is (= #{[2] [3]} (set (get-in h [(symbol tgt) 'twice])))
              "captured args from both suites in a single run")))
      (testing "harvest-args wrapper returns the flat {fn-sym tuples} map (collection test-ns)"
        (let [h (harvest/harvest-args (symbol tgt) [(symbol ta) (symbol tb)] {})]
          (is (= #{[2] [3]} (set (get h 'twice))))))
      (testing "harvest-args back-compat: a single test-ns symbol is coerced"
        (let [h (harvest/harvest-args (symbol tgt) (symbol ta) {})]
          (is (= #{[2]} (set (get h 'twice))))))
      (testing "the target var is RESTORED after harvesting (no leaked spy wrapper)"
        (is (= 10 ((deref (ns-resolve (symbol tgt) 'twice)) 5))))
      (finally
        (remove-ns (symbol tgt)) (remove-ns (symbol ta)) (remove-ns (symbol tb))))))
