(ns heretic.oracle.gen
  "Seeded, dependency-free structural value generators for the differential
   oracle (docs/g1-recall-measurement-plan.md §4.3).

   The seed is threaded as a VALUE (a java.util.Random built from a long) so a
   `:candidate-equivalent` verdict ('no witness in N trials') is reproducible —
   there is NO ambient RNG (the planreview seed-as-value guardrail). Sampling
   only, no shrinking: the oracle needs *a* witness, not a minimal one.

   The distribution deliberately favours SMALL numeric ranges (so equality /
   boundary cases for `<`/`<=`, `=`/`not=`, arithmetic-identity mutants are
   probable) and SHORT collections (0-3 elements, so `first`/`last`,
   `next`/`rest`, `take`/`drop` mutants are distinguishable)."
  (:import [java.util Random]))

(def ^:private alpha "abcdexyz_-0 ")
(def ^:private kws [:a :b :c :k :v :x :id :name])

(defn- gen-string [^Random r]
  (let [n (.nextInt r 6)]
    (apply str (mapv (fn [_] (nth alpha (.nextInt r (count alpha)))) (range n)))))

(defn gen-value
  "Generate one random value from a small, type-diverse distribution. `depth`
   bounds nesting of collections; leaves are scalars."
  [^Random r depth]
  (let [leaf? (or (<= depth 0) (< (.nextDouble r) 0.55))
        pick  (.nextInt r (if leaf? 9 13))]
    (case pick
      0 (- (.nextInt r 17) 8)                       ; int -8..8 (non-zero, boundaries)
      1 (long (- (.nextInt r 5) 2))                 ; small long
      2 (double (- (.nextInt r 17) 8))              ; double
      3 (gen-string r)                              ; short string incl. ""
      4 (nth kws (.nextInt r (count kws)))          ; keyword
      5 (.nextBoolean r)                            ; boolean
      6 nil
      7 (char (+ 97 (.nextInt r 6)))                ; \a..\f
      8 (- (.nextInt r 5) 2)                        ; another small int (weight ints)
      9  (mapv (fn [_] (gen-value r (dec depth))) (range (.nextInt r 4)))   ; vector 0-3
      10 (apply list (mapv (fn [_] (gen-value r (dec depth))) (range (.nextInt r 4)))) ; list 0-3
      11 (into {} (mapv (fn [_] [(gen-value r 0) (gen-value r (dec depth))])
                        (range (.nextInt r 3))))    ; map 0-2
      12 (into #{} (mapv (fn [_] (gen-value r 0)) (range (.nextInt r 3)))))))  ; set 0-2

(defn inputs
  "Return n argument-vectors of the given `arity`, deterministically from `seed`.
   Each element is an independent gen-value draw (collection depth 3). Eager and
   left-to-right so the shared mutable Random yields the same sequence every run."
  [arity n seed]
  (let [r (Random. (long seed))]
    (mapv (fn [_] (mapv (fn [_] (gen-value r 3)) (range arity)))
          (range n))))
