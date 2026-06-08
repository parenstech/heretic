(ns heretic.equivalent
  "Equivalent mutant detection.

   Some mutations produce code that is semantically equivalent to the original,
   meaning they can never be detected by tests. Detecting and filtering these
   upfront saves time during mutation testing.

   SOUNDNESS IS THE PRIME DIRECTIVE. A false positive here is worse than a missed
   optimization: the filter runs BEFORE testing and DROPS every mutation it flags
   (heretic.controller/prepare-mutations keeps only the `:testable` bucket), so a
   mutation wrongly flagged equivalent is never compiled, never run, and silently
   removed from BOTH the numerator and denominator of the mutation score
   (runner/summarize-results). Flagging a *killable* mutation therefore inflates
   the score and hides a real test-coverage gap. Every pattern below must be one
   where NO test could ever distinguish the mutant from the original.

   The literature for static equivalent detection (TCE, EMS) reports low recall —
   a few percent to ~30%, concentrated in a handful of operators — at zero false
   positives. We deliberately keep only provably-sound patterns and accept low
   recall rather than risk score inflation. See docs/validation-plan.md §2 for the
   per-pattern soundness audit that produced this set, and docs/mutation-testing-survey.md
   §3-G1 for the SOTA context.

   This module provides static pattern detection to identify provably-equivalent
   mutants before running tests."
  (:require [rewrite-clj.zip :as z]))

;; =============================================================================
;; Helper Functions for Pattern Detection
;; =============================================================================

(defn- realizing-fn?
  "Check if a symbol represents a function that fully realizes a lazy sequence
   AND normalizes its result type, so that an inner `map`/`mapv` (or
   `filter`/`filterv`) swap cannot be observed by the caller.

   ONLY functions whose output type is independent of whether the input was lazy
   or eager qualify. Notably EXCLUDED (each is a real false-positive source):
   - `str`    : stringifies a LazySeq as \"clojure.lang.LazySeq@..\" vs \"[..]\".
   - `reduce` : can short-circuit via `reduced`, and diverges on infinite lazy seqs.
   - `apply`  : forwards the (possibly type-sensitive) collection to `f`.
   - `doall`  : returns the realized input PRESERVING its type, so `(doall (map ..))`
                is a seq while `(doall (mapv ..))` is a vector — observable via
                `seq?`/`vector?`. (`dorun` is safe: it returns nil.)"
  [sym]
  (contains? #{'vec 'into 'count 'dorun
               'frequencies 'group-by 'sort 'sort-by}
             sym))

(defn- in-realizing-context?
  "Check if the current location is inside a realizing function call."
  [zloc]
  (when-let [gp (some-> zloc z/up z/up)]
    (when (= :list (z/tag gp))
      (realizing-fn? (first (z/child-sexprs gp))))))

(defn- binary-identity?
  "True when the parent form is a binary call `(op a b)` whose last operand is the
   literal `identity-val`. Used for arithmetic-identity equivalences where the
   element must be in the SECOND position and the call must be binary, because the
   operators are non-commutative under the swap:

     (+ x 0) -> (- x 0)  ; x+0 = x-0 = x        EQUIVALENT
     (+ 0 x) -> (- 0 x)  ; 0+x = x, 0-x = -x    NOT equivalent
     (* x 1) -> (/ x 1)  ; x*1 = x/1 = x        EQUIVALENT
     (* 1 x) -> (/ 1 x)  ; 1*x = x, 1/x         NOT equivalent

   zloc is positioned at the operator; parent is the call form."
  [zloc identity-val]
  (let [parent (z/up zloc)]
    (when (and parent (= :list (z/tag parent)))
      (let [args (rest (z/child-sexprs parent))]
        (and (= 2 (count args))
             (= identity-val (last args)))))))

(defn- some-collection-arg?
  "True when zloc (an operator inside `(op coll)`) sits in the COLLECTION argument
   of a `some` call: `(some pred (op coll))`. Used for rest<->next, which are
   indistinguishable as the collection argument of `some` (both () and nil make
   `some` return nil). Requires the grandparent to be a `some`/`clojure.core/some`
   call and the `(op coll)` form to be its last (collection) argument.

   Residual assumption (cannot be resolved statically): `some` is core's `some`,
   not a locally shadowed binding."
  [zloc]
  (let [parent (z/up zloc)]                ; (op coll)
    (when (and parent (= :list (z/tag parent)))
      (let [gp (z/up parent)]              ; (some pred (op coll))
        (when (and gp (= :list (z/tag gp)))
          (let [gp-children (vec (z/child-sexprs gp))]
            (and (contains? #{'some 'clojure.core/some} (first gp-children))
                 ;; the (op coll) form must be the collection arg (last child of
                 ;; the 3-element `(some pred coll)`), not the predicate.
                 (= 3 (count gp-children))
                 (= (z/sexpr parent) (last gp-children)))))))))

;; =============================================================================
;; Equivalent Mutation Patterns (provably sound only)
;; =============================================================================

(def equivalent-patterns
  "Patterns that produce semantically equivalent code when mutated.

   Each pattern specifies:
   - :operator - The mutation operator that may create equivalence
   - :context - A function (zloc) -> bool that checks if mutation is in an
                equivalent context. MUST be sound: it may only return truthy when
                NO test could distinguish the mutant from the original.
   - :reason - Human-readable explanation

   This set is intentionally small and conservative. Patterns removed in the
   soundness pass (docs/validation-plan.md §2) include the boolean and/or swaps,
   the =/not= swap, the nil?/some? swaps, the count-boundary swaps, the
   seq/empty? swaps, and the multiply/divide-by-zero swaps — all of which could
   flag KILLABLE mutants."
  [;; ---------------------------------------------------------------------------
   ;; Arithmetic identity: binary call with the identity element LAST.
   ;; ---------------------------------------------------------------------------
   {:operator :swap-plus-minus
    :context (fn [zloc] (binary-identity? zloc 0))
    :reason "(+ x 0) -> (- x 0): adding/subtracting zero in tail position is identity"}

   {:operator :swap-minus-plus
    :context (fn [zloc] (binary-identity? zloc 0))
    :reason "(- x 0) -> (+ x 0): adding/subtracting zero in tail position is identity"}

   {:operator :swap-mult-div
    :context (fn [zloc] (binary-identity? zloc 1))
    :reason "(* x 1) -> (/ x 1): multiplying/dividing by one in tail position is identity"}

   {:operator :swap-div-mult
    :context (fn [zloc] (binary-identity? zloc 1))
    :reason "(/ x 1) -> (* x 1): multiplying/dividing by one in tail position is identity"}

   ;; ---------------------------------------------------------------------------
   ;; rest <-> next as the collection argument of `some`.
   ;; (some pred (rest coll)) == (some pred (next coll)) because `some` returns nil
   ;; for both () and nil, and rest/next agree on every non-empty input.
   ;; ---------------------------------------------------------------------------
   {:operator :swap-rest-next
    :context some-collection-arg?
    :reason "rest/next are indistinguishable as the collection arg of some"}

   {:operator :swap-next-rest
    :context some-collection-arg?
    :reason "rest/next are indistinguishable as the collection arg of some"}

   ;; ---------------------------------------------------------------------------
   ;; first <-> last on a single-element vector literal.
   ;; (first [x]) == (last [x]) == x.
   ;; ---------------------------------------------------------------------------
   {:operator :swap-first-last
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (and (vector? arg) (= 1 (count arg)))))))
    :reason "first and last are equivalent on a single-element collection"}

   {:operator :swap-last-first
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (and (vector? arg) (= 1 (count arg)))))))
    :reason "last and first are equivalent on a single-element collection"}

   ;; ---------------------------------------------------------------------------
   ;; Lazy/eager swap inside a type-normalizing realizing call.
   ;; (vec (map f xs)) == (vec (mapv f xs)), etc. See realizing-fn? for the
   ;; restricted set of contexts where the swap is unobservable.
   ;; ---------------------------------------------------------------------------
   {:operator :swap-map-mapv
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "map/mapv equivalent when immediately realized by a type-normalizing fn"}

   {:operator :swap-mapv-map
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "mapv/map equivalent when immediately realized by a type-normalizing fn"}

   {:operator :swap-filter-filterv
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "filter/filterv equivalent when immediately realized by a type-normalizing fn"}

   {:operator :swap-filterv-filter
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "filterv/filter equivalent when immediately realized by a type-normalizing fn"}

   ;; ---------------------------------------------------------------------------
   ;; Threading a single value: (-> x) and (->> x) both macroexpand to x.
   ;; ---------------------------------------------------------------------------
   {:operator :swap-thread-first-last
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [children (rest (z/child-sexprs parent))]
                     ;; Only the initial value, no transformation steps.
                     (= 1 (count children))))))
    :reason "Threading a single value with no steps is identity (-> x == ->> x)"}])

;; =============================================================================
;; Detection Functions
;; =============================================================================

(defn- check-pattern
  "Check if a mutation matches an equivalent pattern.
   Returns the pattern if matched, nil otherwise."
  [mutation zloc pattern]
  (when (= (:operator mutation) (:operator pattern))
    (try
      (when ((:context pattern) zloc)
        pattern)
      (catch Exception _
        ;; Context check failed, not equivalent
        nil))))

(defn likely-equivalent?
  "Check if a mutation is provably equivalent (will produce equivalent code).

   Arguments:
   - mutation: Mutation record with :operator, :file, :coord
   - zloc: Zipper positioned at the mutation site

   Returns map with :equivalent? boolean and :reason string, or nil if not equivalent."
  [mutation zloc]
  (when-let [pattern (some #(check-pattern mutation zloc %) equivalent-patterns)]
    {:equivalent? true
     :reason (:reason pattern)
     :operator (:operator mutation)}))

(defn filter-equivalent-mutations
  "Filter out provably equivalent mutations from a collection.

   Arguments:
   - mutations: Sequence of mutation records
   - zloc-fn: Function (mutation) -> zloc that returns zipper at mutation site

   Returns map with:
   - :mutations - Filtered mutations (non-equivalent)
   - :filtered - Mutations that were filtered out
   - :filtered-count - Count of filtered mutations"
  [mutations zloc-fn]
  (let [grouped (group-by
                 (fn [m]
                   (try
                     (if-let [zloc (zloc-fn m)]
                       (if (likely-equivalent? m zloc)
                         :equivalent
                         :testable)
                       :testable)  ; Can't get zloc, assume testable
                     (catch Exception _
                       :testable)))  ; Error, assume testable
                 mutations)]
    {:mutations (get grouped :testable [])
     :filtered (get grouped :equivalent [])
     :filtered-count (count (get grouped :equivalent []))}))

;; =============================================================================
;; Sound dead-branch detection (read-identity)
;; =============================================================================

(defn read-identical?
  "SOUND (FP=0) equivalence: true when the original and mutated top-level forms
   READ to = data under the JVM reader (`:read-cond :allow`). The compiler loads a
   `.cljc` file with exactly this feature set (#{:clj}), so identical reads mean
   identical compiler input — i.e. the mutation lives in JVM-dead code (a
   `#?(:cljs …)` / non-:clj reader-conditional branch) and cannot change a single
   compiled byte. This is the one equivalent class that actually occurs in real
   Clojure (docs/validation-results.md §2.2); the static patterns above match
   contrived shapes that effectively never appear.

   Conservative: any read error ⇒ false (can't prove ⇒ don't claim equivalent)."
  [orig-str mut-str]
  (try
    (= (read-string {:read-cond :allow} orig-str)
       (read-string {:read-cond :allow} mut-str))
    (catch Exception _ false)))

;; =============================================================================
;; Simple Heuristic Detection (No Zipper Required)
;; =============================================================================

(def simple-equivalent-patterns
  "Simple patterns that can be detected from mutation data alone.

   INTENTIONALLY EMPTY. The previous file-name heuristic (flag 0->1 when the path
   matched default|init|start|begin) was unsound — a path substring says nothing
   about whether a literal `0` is semantically a no-op, and it fired on directory
   names too (docs/validation-plan.md §2). No sound mutation-data-only pattern is
   currently known, so this stays empty and `quick-equivalent-check` always
   returns nil. `quick-equivalent-check` is NOT wired into the run path; it is kept
   for API stability only."
  [])

(defn quick-equivalent-check
  "Quick heuristic check for equivalent mutations without parsing.

   Currently always returns nil (no sound mutation-data-only pattern is known).
   Returns {:equivalent? bool :reason str} or nil."
  [mutation]
  (some (fn [pattern]
          (when ((:check pattern) mutation)
            {:equivalent? true
             :reason (:reason pattern)}))
        simple-equivalent-patterns))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn equivalent-stats
  "Calculate statistics about equivalent mutations.

   Arguments:
   - original-count: Number of mutations before filtering
   - filtered-count: Number of mutations filtered as equivalent

   Returns map with counts and percentages."
  [original-count filtered-count]
  (let [remaining (- original-count filtered-count)
        pct (if (pos? original-count)
              (* 100.0 (/ filtered-count original-count))
              0.0)]
    {:original-count original-count
     :filtered-count filtered-count
     :remaining-count remaining
     :filtered-percentage pct}))
