(ns heretic.oracle.harvest
  "Suite-seeded input harvesting + perturbation (docs/validation-results.md §2.2
   strengthening): the generator weakness is that random top-level inputs rarely
   REACH a deep mutation. The covering tests already pass arguments that reach it
   (that is what 'covering' means) — they just don't infect/propagate. So we spy
   the target fns during ONE suite run to capture the real argument tuples, then
   feed those + small structural PERTURBATIONS to the differential oracle: reach
   is solved by construction, and the search only has to find infection nearby.

   Seeded throughout (java.util.Random as a value) so a run is reproducible."
  (:require [clojure.test :as test]
            [heretic.oracle.gen :as gen])
  (:import [java.io StringWriter]
           [java.util Random]))

(defn harvest-args
  "Run `test-ns` with every interned fn of `ns-sym` wrapped to record the argument
   tuples it is called with (deduped, capped at `cap` per fn), then restore.
   Returns {fn-sym -> [arg-tuple …]}. No ClojureStorm; pure var-indirection spying."
  [ns-sym test-ns {:keys [cap suite-timeout-ms] :or {cap 60 suite-timeout-ms 30000}}]
  (let [the-ns    (find-ns ns-sym)
        fn-vars   (filter (fn [[_ v]] (and (var? v) (fn? (deref v)))) (ns-interns the-ns))
        recorded  (atom {})
        originals (atom {})]
    (doseq [[sym v] fn-vars]
      (let [orig (deref v)]
        (swap! originals assoc sym orig)
        (alter-var-root v (constantly
                           (fn [& args]
                             (let [seen (get @recorded sym [])]
                               (when (< (count seen) cap)
                                 (swap! recorded update sym (fnil conj #{}) (vec args))))
                             (apply orig args))))))
    (try
      (let [fut (future (binding [test/*test-out* (StringWriter.)] (test/run-tests test-ns)))]
        (deref fut suite-timeout-ms ::timeout))
      (finally
        (doseq [[sym _] fn-vars]
          (alter-var-root (ns-resolve the-ns sym) (constantly (get @originals sym))))))
    (into {} (map (fn [[k s]] [k (vec s)]) @recorded))))

(defn- perturb-coll [^Random r v add-fn]
  (let [v (vec v)]
    (add-fn
     (cond
       (empty? v)              [(gen/gen-value r 1)]
       (< (.nextDouble r) 0.4) (conj v (gen/gen-value r 1))                 ; grow
       (< (.nextDouble r) 0.6) (subvec v 0 (dec (count v)))                 ; shrink
       :else (assoc v (.nextInt r (count v)) (gen/gen-value r 1))))))       ; tweak one

(defn perturb-value
  "A value near `v`: numbers nudged, booleans flipped, collections grown/shrunk/
   tweaked by one element. Drives infection of `<`/`<=`, `first`/`rest`,
   `take`/`drop`, arithmetic, etc. once a reaching tuple is in hand."
  [^Random r v]
  (cond
    (boolean? v)    (not v)
    (integer? v)    (+ v (- (.nextInt r 5) 2))
    (number? v)     (+ v (- (.nextDouble r) 0.5))
    (nil? v)        (nth [0 false "" [] :x] (.nextInt r 5))
    (string? v)     (if (pos? (count v)) (subs v 0 (.nextInt r (count v))) "x")
    (keyword? v)    (keyword (str (name v) (.nextInt r 9)))
    (map? v)        (if (empty? v) {:a 1} (dissoc v (nth (vec (keys v)) (.nextInt r (count v)))))
    (set? v)        (perturb-coll r v set)
    (vector? v)     (perturb-coll r v vec)
    (seq? v)        (seq (perturb-coll r (vec v) vec))
    :else           v))

(defn perturbations
  "For each non-empty base tuple, `k` variants each perturbing ONE argument
   position. (A 0-arg call has nothing to perturb; its base tuple is still tested.)"
  [^Random r tuples k]
  (vec (for [t tuples
             :when (seq t)
             _ (range k)
             :let [t (vec t), i (.nextInt r (count t))]]
         (assoc t i (perturb-value r (nth t i))))))

(defn inputs-for
  "Real harvested tuples for `fn-sym` (capped at `max-base`) ++ `k` perturbations
   each. nil when the fn was never called by the suite (⇒ oracle falls back to
   random generation)."
  [^Random r harvested fn-sym {:keys [max-base perturb-k] :or {max-base 40 perturb-k 8}}]
  (let [base (vec (take max-base (get harvested fn-sym)))]
    (when (seq base)
      (into base (perturbations r base perturb-k)))))
