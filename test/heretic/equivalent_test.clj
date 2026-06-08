(ns heretic.equivalent-test
  "Tests for heretic.equivalent mutant detection.

   The detector must be SOUND: it may only flag a mutation equivalent when no test
   could distinguish the mutant from the original. A false positive drops a
   killable mutant from the run and inflates the mutation score
   (see heretic.equivalent ns docstring and docs/validation-plan.md §2).

   Tests cover:
   - True positives: provably-equivalent mutations ARE flagged
   - Soundness regression: killable mutations are NEVER flagged (the §2 audit
     counterexamples for the patterns that were tightened or removed)
   - Filter function behavior and statistics"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.equivalent :as equiv]
            [heretic.parser :as parser]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-mutation
  "Create a test mutation with given operator and optional overrides."
  [operator & {:as overrides}]
  (merge {:operator operator
          :file "test.clj"
          :line 1
          :column 1
          :coord "0"
          :original "+"
          :replacement "-"}
         overrides))

(defn- at-op
  "Parse `src` and position the zipper at the first child (the operator)."
  [src]
  (-> (parser/parse-string src) z/down))

;; =============================================================================
;; Arithmetic identity — TRUE POSITIVES (binary, identity element LAST)
;; =============================================================================

(deftest add-zero-tail-is-equivalent-test
  (testing "(+ x 0) -> (- x 0) is equivalent"
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                         (at-op "(+ x 0)"))))))

(deftest subtract-zero-tail-is-equivalent-test
  (testing "(- x 0) -> (+ x 0) is equivalent"
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-minus-plus)
                                         (at-op "(- x 0)"))))))

(deftest multiply-one-tail-is-equivalent-test
  (testing "(* x 1) -> (/ x 1) is equivalent"
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-mult-div)
                                         (at-op "(* x 1)"))))))

(deftest divide-one-tail-is-equivalent-test
  (testing "(/ x 1) -> (* x 1) is equivalent"
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-div-mult)
                                         (at-op "(/ x 1)"))))))

(deftest likely-equivalent-returns-true-in-map-test
  (testing "likely-equivalent? returns a map with :equivalent? literally true"
    (let [result (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                           (at-op "(+ x 0)"))]
      (is (map? result))
      (is (true? (:equivalent? result))))))

;; =============================================================================
;; Arithmetic identity — SOUNDNESS REGRESSION (killable; must NOT be flagged)
;; =============================================================================
;; These are the §2 counterexamples: the old loose guard ("some operand = 0/1")
;; flagged them, but the swap is observable because +/- and */÷ are non-commutative.

(deftest add-zero-leading-not-equivalent-test
  (testing "(+ 0 x) -> (- 0 x) is NOT equivalent: 0+x=x but 0-x=-x"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (at-op "(+ 0 x)"))))))

(deftest subtract-zero-leading-not-equivalent-test
  (testing "(- 0 x) -> (+ 0 x) is NOT equivalent: 0-x=-x but 0+x=x"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-minus-plus)
                                        (at-op "(- 0 x)"))))))

(deftest multiply-one-leading-not-equivalent-test
  (testing "(* 1 x) -> (/ 1 x) is NOT equivalent: 1*x=x but 1/x is the reciprocal"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-mult-div)
                                        (at-op "(* 1 x)"))))))

(deftest divide-one-leading-not-equivalent-test
  (testing "(/ 1 x) -> (* 1 x) is NOT equivalent"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-div-mult)
                                        (at-op "(/ 1 x)"))))))

(deftest add-non-zero-not-equivalent-test
  (testing "(+ x 5) is not equivalent"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (at-op "(+ x 5)"))))))

(deftest multiply-non-one-not-equivalent-test
  (testing "(* x 2) is not equivalent"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-mult-div)
                                        (at-op "(* x 2)"))))))

(deftest divide-non-one-not-equivalent-test
  (testing "(/ x 2) is not equivalent"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-div-mult)
                                        (at-op "(/ x 2)"))))))

(deftest add-regular-not-equivalent-test
  (testing "(+ x y) is not equivalent"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (at-op "(+ x y)"))))))

(deftest add-ternary-with-zero-not-equivalent-test
  (testing "(+ x 0 y) is not flagged: only binary forms are proven equivalent"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (at-op "(+ x 0 y)"))))))

(deftest multiply-by-zero-not-equivalent-test
  (testing "(* x 0) -> (/ x 0) is NOT equivalent: the mutant THROWS divide-by-zero"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-mult-div)
                                        (at-op "(* x 0)"))))))

(deftest divide-zero-numerator-not-equivalent-test
  (testing "(/ 0 x) -> (* 0 x) is NOT equivalent: (/ 0 0) throws while (* 0 0)=0"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-div-mult)
                                        (at-op "(/ 0 x)"))))))

;; =============================================================================
;; Structural guards (nil / non-list parent, arity) — must not crash, must be nil
;; =============================================================================

(deftest nil-parent-returns-nil-test
  (testing "operator with no parent returns nil, does not crash"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (parser/parse-string "+"))))))

(deftest non-list-parent-returns-nil-test
  (testing "operator whose parent is a vector returns nil"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (at-op "[+ x 0]"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-minus-plus)
                                        (at-op "[- x 0]"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-mult-div)
                                        (at-op "[* x 1]"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-div-mult)
                                        (at-op "[/ x 1]"))))))

(deftest nullary-call-returns-nil-test
  (testing "(+) with no operands does not match the binary identity pattern"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-plus-minus)
                                        (at-op "(+)"))))))

;; =============================================================================
;; REMOVED PATTERNS — soundness regression (must always be nil now)
;; =============================================================================
;; The following operators had unsound contexts that flagged killable mutants.
;; They were removed in the §2 soundness pass; the detector must never flag them.

(deftest boolean-and-or-never-equivalent-test
  (testing "and/or swaps are never flagged (literal true/false makes them diverge)"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-and-or)
                                        (at-op "(and true x)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-and-or)
                                        (at-op "(and x true)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-or-and)
                                        (at-op "(or false x)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-or-and)
                                        (at-op "(or x false)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-and-or)
                                        (at-op "(and x y)"))))))

(deftest eq-neq-never-equivalent-test
  (testing "= -> not= is never flagged: (= x x) is true but (not= x x) is false"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-eq-neq)
                                        (at-op "(= x x)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-eq-neq)
                                        (at-op "(= x y)"))))))

(deftest nil-some-swaps-never-equivalent-test
  (testing "nil?/some? swaps are never flagged (exact negations / different constants)"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-nil-some)
                                        (at-op "(nil? (str x))"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-some-nil)
                                        (at-op "(some? (str x))"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-nil-some)
                                        (at-op "(nil? (count x))"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-nil-some)
                                        (at-op "(nil? (keyword s))"))))))

(deftest count-boundary-swaps-never-equivalent-test
  (testing "count-boundary comparison swaps are never flagged (flip verdict at count=0)"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-lt-lte)
                                        (at-op "(< (count coll) 0)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-lte-lt)
                                        (at-op "(<= (count coll) 0)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-lt-lte)
                                        (at-op "(< (.length s) 0)"))))))

(deftest seq-empty-swaps-never-equivalent-test
  (testing "seq/empty? swaps are never flagged (opposite truthiness, never value-equal)"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-seq-empty)
                                        (at-op "(empty? [])"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-empty-seq)
                                        (at-op "(seq [])"))))))

(deftest some-thread-first-never-equivalent-test
  (testing "some-> <-> -> swaps are never flagged (the operator id is dead/unsound)"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-some-thread-first)
                                        (at-op "(some-> 42 inc)"))))
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-some-thread-first)
                                        (at-op "(some-> x inc)"))))))

;; =============================================================================
;; rest <-> next as the collection argument of `some` — sound
;; =============================================================================

(deftest rest-next-equivalent-in-some-context-test
  (testing "(some pred (rest coll)) == (some pred (next coll))"
    (let [zloc (-> (parser/parse-string "(some #(= 0 %) (rest coll))")
                   z/down z/right z/right z/down)] ; at 'rest
      (is (some? (equiv/likely-equivalent? (make-mutation :swap-rest-next) zloc))))))

(deftest next-rest-equivalent-in-some-context-test
  (testing "(some pred (next items)) == (some pred (rest items))"
    (let [zloc (-> (parser/parse-string "(some pred (next items))")
                   z/down z/right z/right z/down)]
      (is (some? (equiv/likely-equivalent? (make-mutation :swap-next-rest) zloc))))))

(deftest rest-next-not-equivalent-outside-some-test
  (testing "(first (rest coll)) -> (first (next coll)) is NOT flagged"
    (let [zloc (-> (parser/parse-string "(first (rest coll))")
                   z/down z/right z/down)] ; at 'rest
      (is (nil? (equiv/likely-equivalent? (make-mutation :swap-rest-next) zloc))))))

(deftest rest-next-not-equivalent-as-some-predicate-test
  (testing "rest in the PREDICATE position of some is not flagged"
    ;; (some (rest preds) coll) — rest is arg 1 (predicate), not the collection
    (let [zloc (-> (parser/parse-string "(some (rest preds) coll)")
                   z/down z/right z/down)] ; at 'rest inside (rest preds)
      (is (nil? (equiv/likely-equivalent? (make-mutation :swap-rest-next) zloc))))))

;; =============================================================================
;; first <-> last on a single-element vector literal — sound
;; =============================================================================

(deftest first-last-single-element-test
  (testing "(first [42]) == (last [42])"
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-first-last)
                                         (at-op "(first [42])"))))
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-last-first)
                                         (at-op "(last [42])"))))))

(deftest first-last-multiple-elements-not-equivalent-test
  (testing "(first [1 2]) is NOT equivalent to (last [1 2])"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-first-last)
                                        (at-op "(first [1 2])"))))))

;; =============================================================================
;; Lazy/eager swap inside a type-normalizing realizing call — sound
;; =============================================================================

(deftest map-mapv-in-vec-context-test
  (testing "(vec (map f coll)) == (vec (mapv f coll))"
    (let [zloc (-> (parser/parse-string "(vec (map inc coll))")
                   z/down z/right z/down)]
      (is (some? (equiv/likely-equivalent? (make-mutation :swap-map-mapv) zloc))))))

(deftest mapv-map-in-count-context-test
  (testing "(count (mapv f coll)) == (count (map f coll))"
    (let [zloc (-> (parser/parse-string "(count (mapv inc coll))")
                   z/down z/right z/down)]
      (is (some? (equiv/likely-equivalent? (make-mutation :swap-mapv-map) zloc))))))

(deftest filter-filterv-in-into-context-test
  (testing "(into [] (filter pred coll)) == (into [] (filterv pred coll))"
    (let [zloc (-> (parser/parse-string "(into [] (filter even? coll))")
                   z/down z/right z/right z/down)]
      (is (some? (equiv/likely-equivalent? (make-mutation :swap-filter-filterv) zloc))))))

(deftest realizing-fn-frequencies-test
  (testing "frequencies is a type-normalizing realizing context"
    (let [zloc (-> (parser/parse-string "(frequencies (map inc coll))")
                   z/down z/right z/down)]
      (is (some? (equiv/likely-equivalent? (make-mutation :swap-map-mapv) zloc))))))

(deftest map-outside-realizing-context-not-equivalent-test
  (testing "(map f coll) alone is not equivalent to mapv"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-map-mapv)
                                        (at-op "(map inc coll)"))))))

(deftest map-mapv-in-doall-not-equivalent-test
  (testing "doall is NOT type-normalizing: (doall (map ..)) is a seq, (doall (mapv ..)) a vector"
    (let [zloc (-> (parser/parse-string "(doall (map inc coll))")
                   z/down z/right z/down)]
      (is (nil? (equiv/likely-equivalent? (make-mutation :swap-map-mapv) zloc))))))

(deftest map-mapv-in-str-not-equivalent-test
  (testing "str is NOT type-normalizing: it stringifies a LazySeq differently from a vector"
    (let [zloc (-> (parser/parse-string "(str (map inc coll))")
                   z/down z/right z/down)]
      (is (nil? (equiv/likely-equivalent? (make-mutation :swap-map-mapv) zloc))))))

(deftest map-mapv-in-reduce-not-equivalent-test
  (testing "reduce is NOT a safe realizing context (short-circuit / infinite lazy)"
    (let [zloc (-> (parser/parse-string "(reduce + 0 (map inc coll))")
                   z/down z/right z/right z/right z/down)]
      (is (nil? (equiv/likely-equivalent? (make-mutation :swap-map-mapv) zloc))))))

;; =============================================================================
;; Threading a single value — sound
;; =============================================================================

(deftest thread-first-single-value-test
  (testing "(-> x) == (->> x): both are identity"
    (is (some? (equiv/likely-equivalent? (make-mutation :swap-thread-first-last)
                                         (at-op "(-> x)"))))))

(deftest thread-first-with-steps-not-equivalent-test
  (testing "(-> x f g) is not equivalent to (->> x f g)"
    (is (nil? (equiv/likely-equivalent? (make-mutation :swap-thread-first-last)
                                        (at-op "(-> x f g)"))))))

;; =============================================================================
;; Filter Function Tests
;; =============================================================================

(deftest filter-equivalent-mutations-test
  (testing "filter-equivalent-mutations separates equivalent from testable"
    (let [mutations [(make-mutation :swap-plus-minus :id 1)
                     (make-mutation :swap-mult-div :id 2)
                     (make-mutation :swap-and-or :id 3)]
          zloc-fn (fn [m]
                    (case (:id m)
                      1 (at-op "(+ x 0)")   ; equivalent
                      2 (at-op "(* x 2)")   ; not equivalent (×2)
                      3 (at-op "(and x y)")))  ; not equivalent (and/or removed)
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      (is (= 1 (:filtered-count result)))
      (is (= 2 (count (:mutations result)))))))

(deftest filter-equivalent-mutations-empty-test
  (testing "filter-equivalent-mutations handles empty input"
    (let [result (equiv/filter-equivalent-mutations [] identity)]
      (is (= 0 (:filtered-count result)))
      (is (empty? (:mutations result))))))

(deftest filter-equivalent-mutations-all-testable-test
  (testing "filter-equivalent-mutations with no equivalents"
    (let [mutations [(make-mutation :swap-plus-minus)
                     (make-mutation :swap-minus-plus)]
          zloc-fn (fn [_] (at-op "(+ x y)"))
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      (is (= 0 (:filtered-count result)))
      (is (= 2 (count (:mutations result)))))))

(deftest filter-handles-zloc-fn-exception-test
  (testing "filter-equivalent-mutations treats a throwing zloc-fn as testable"
    (let [mutations [(make-mutation :swap-plus-minus :id 1)]
          zloc-fn (fn [_] (throw (Exception. "zloc error")))
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      (is (= 1 (count (:mutations result))))
      (is (= 0 (:filtered-count result))))))

(deftest filter-handles-nil-zloc-test
  (testing "filter-equivalent-mutations treats a nil zloc as testable"
    (let [mutations [(make-mutation :swap-plus-minus)]
          zloc-fn (fn [_] nil)
          result (equiv/filter-equivalent-mutations mutations zloc-fn)]
      (is (= 1 (count (:mutations result))))
      (is (= 0 (:filtered-count result))))))

;; =============================================================================
;; Detection robustness
;; =============================================================================

(deftest unknown-operator-returns-nil-test
  (testing "an operator with no pattern returns nil"
    (is (nil? (equiv/likely-equivalent? (make-mutation :unknown-operator)
                                        (at-op "(foo)"))))))

(deftest context-exception-returns-nil-test
  (testing "an invalid zloc (context throws) returns nil, not a truthy/map value"
    (let [result (equiv/likely-equivalent? (make-mutation :swap-plus-minus) 42)]
      (is (nil? result))
      (is (not (map? result)))
      (is (not (false? result))))))

;; =============================================================================
;; Simple (no-zipper) heuristic — intentionally disabled, always nil
;; =============================================================================

(deftest quick-equivalent-check-always-nil-test
  (testing "quick-equivalent-check returns nil (the unsound file-name heuristic was removed)"
    (is (nil? (equiv/quick-equivalent-check
               (make-mutation :replace-0-to-1 :file "src/default_config.clj"))))
    (is (nil? (equiv/quick-equivalent-check
               (make-mutation :replace-0-to-1 :file "src/initialize.clj"))))
    (is (nil? (equiv/quick-equivalent-check
               (make-mutation :swap-plus-minus :file "src/math.clj"))))))

;; =============================================================================
;; Statistics Tests
;; =============================================================================

(deftest equivalent-stats-test
  (testing "equivalent-stats calculates correctly"
    (let [stats (equiv/equivalent-stats 100 25)]
      (is (= 100 (:original-count stats)))
      (is (= 25 (:filtered-count stats)))
      (is (= 75 (:remaining-count stats)))
      (is (= 25.0 (:filtered-percentage stats))))))

(deftest equivalent-stats-zero-test
  (testing "equivalent-stats handles zero original"
    (let [stats (equiv/equivalent-stats 0 0)]
      (is (= 0 (:original-count stats)))
      (is (= 0.0 (:filtered-percentage stats))))))

(deftest equivalent-stats-all-filtered-test
  (testing "equivalent-stats handles all filtered"
    (let [stats (equiv/equivalent-stats 50 50)]
      (is (= 50 (:filtered-count stats)))
      (is (= 0 (:remaining-count stats)))
      (is (= 100.0 (:filtered-percentage stats))))))

;; =============================================================================
;; read-identity — sound dead-branch detection
;; =============================================================================

(deftest read-identical-sound-dead-branch-test
  (testing "a #?(:cljs …)-branch mutation reads identically under the JVM reader ⇒ equivalent"
    (is (true? (equiv/read-identical?
                "(defn f [x] #?(:clj (+ x 1) :cljs (- x 1)))"
                "(defn f [x] #?(:clj (+ x 1) :cljs (+ x 1)))"))))
  (testing "a :clj-branch mutation is live ⇒ NOT read-identical"
    (is (false? (equiv/read-identical?
                 "(defn f [x] #?(:clj (+ x 1) :cljs (- x 1)))"
                 "(defn f [x] #?(:clj (- x 1) :cljs (- x 1)))"))))
  (testing "a plain-Clojure live mutation is NOT read-identical"
    (is (false? (equiv/read-identical? "(defn f [x] (+ x 1))" "(defn f [x] (- x 1))"))))
  (testing "a read error ⇒ false (never a false positive)"
    (is (false? (equiv/read-identical? "(defn f [" "(defn g [")))))
