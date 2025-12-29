(ns heretic.equivalent
  "Equivalent mutant detection.

   Some mutations produce code that is semantically equivalent to the original,
   meaning they can never be detected by tests. Detecting and filtering these
   upfront saves time during mutation testing.

   Common equivalent mutation patterns:
   - Adding zero: (+ x 0) -> (- x 0) is equivalent
   - Multiplying by one: (* x 1) -> (/ x 1) is equivalent
   - Boolean identity: (and true x) -> (or true x) changes behavior but
     (and x true) at the end may not if x is already boolean
   - Boundary comparisons: (>= (count x) 0) is always true
   - Function contracts: (nil? (str x)) is always false

   This module provides static pattern detection to identify likely equivalent
   mutants before running tests."
  (:require [rewrite-clj.zip :as z]))

;; =============================================================================
;; Helper Functions for Pattern Detection
;; =============================================================================

(defn- non-negative-fn?
  "Check if a symbol represents a function that always returns non-negative values."
  [sym]
  (contains? #{'count '.length '.size 'Math/abs} sym))

(defn- never-nil-fn?
  "Check if a symbol represents a function that never returns nil."
  [sym]
  (contains? #{'str 'vec 'vector 'set 'hash-set 'list 'count 'inc 'dec
               'name 'keyword 'symbol 'namespace} sym))

(defn- realizing-fn?
  "Check if a symbol represents a function that realizes lazy sequences."
  [sym]
  (contains? #{'vec 'into 'count 'doall 'dorun 'str 'apply 'reduce
               'frequencies 'group-by 'sort 'sort-by} sym))

(defn- single-arity-fn?
  "Check if a symbol represents a common single-arity function."
  [sym]
  (contains? #{'inc 'dec 'str 'name 'keyword 'symbol 'count 'first 'last
               'rest 'next 'seq 'vec 'set 'keys 'vals 'not 'identity
               'clojure.core/inc 'clojure.core/dec} sym))

(defn- in-realizing-context?
  "Check if the current location is inside a realizing function call."
  [zloc]
  (when-let [gp (some-> zloc z/up z/up)]
    (when (= :list (z/tag gp))
      (realizing-fn? (first (z/child-sexprs gp))))))

(defn- literal-non-nil?
  "Check if a value is a non-nil literal."
  [v]
  (or (number? v) (string? v) (keyword? v) (boolean? v)
      (and (vector? v) true)
      (and (map? v) true)
      (and (set? v) true)))

;; =============================================================================
;; Equivalent Mutation Patterns
;; =============================================================================

(def equivalent-patterns
  "Patterns that produce semantically equivalent code when mutated.

   Each pattern specifies:
   - :operator - The mutation operator that may create equivalence
   - :context - A function (zloc) -> bool that checks if mutation is in an equivalent context
   - :reason - Human-readable explanation"
  [;; Adding/subtracting zero
   {:operator :swap-plus-minus
    :context (fn [zloc]
               ;; Check if any sibling is 0
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   ;; child-sexprs returns s-expressions directly, not zippers
                   (some #(= 0 %)
                         (rest (z/child-sexprs parent))))))
    :reason "Adding or subtracting zero has no effect"}

   {:operator :swap-minus-plus
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (some #(= 0 %)
                         (rest (z/child-sexprs parent))))))
    :reason "Adding or subtracting zero has no effect"}

   ;; Multiplying/dividing by one
   {:operator :swap-mult-div
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (some #(= 1 %)
                         (rest (z/child-sexprs parent))))))
    :reason "Multiplying or dividing by one has no effect"}

   {:operator :swap-div-mult
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (some #(= 1 %)
                         (rest (z/child-sexprs parent))))))
    :reason "Multiplying or dividing by one has no effect"}

   ;; Boolean with true/false in and/or
   {:operator :swap-and-or
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   ;; (and true x) or (and x true) where result is already constrained
                   (let [children (rest (z/child-sexprs parent))]
                     (and (= 2 (count children))
                          (some true? children))))))
    :reason "Boolean operation with literal true may be equivalent"}

   {:operator :swap-or-and
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [children (rest (z/child-sexprs parent))]
                     (and (= 2 (count children))
                          (some false? children))))))
    :reason "Boolean operation with literal false may be equivalent"}

   ;; Comparison with self (rare but possible)
   {:operator :swap-eq-neq
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [children (rest (z/child-sexprs parent))]
                     (and (= 2 (count children))
                          (apply = children))))))
    :reason "Comparing identical values"}

   ;; rest->next when result is passed to `some`
   ;; (some pred (rest coll)) ≡ (some pred (next coll))
   ;; because some returns nil for both () and nil
   {:operator :swap-rest-next
    :context (fn [zloc]
               ;; Check if this rest call is a direct argument to `some`
               ;; (some pred (rest ...)) - rest is at position 2 in some's args
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [grandparent (z/up parent)]
                     (when (and grandparent (= :list (z/tag grandparent)))
                       (let [gp-children (z/child-sexprs grandparent)]
                         (= 'some (first gp-children))))))))
    :reason "rest/next equivalent when passed to some (both return nil for empty)"}

   {:operator :swap-next-rest
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [grandparent (z/up parent)]
                     (when (and grandparent (= :list (z/tag grandparent)))
                       (let [gp-children (z/child-sexprs grandparent)]
                         (= 'some (first gp-children))))))))
    :reason "rest/next equivalent when passed to some (both return nil for empty)"}

   ;; (not (nil? x)) ≡ (some? x) when one is swapped for the other
   ;; These patterns detect when nil?/some? swaps happen in a negation context
   {:operator :swap-nil-some
    :context (fn [zloc]
               ;; Check if we're inside (not ...)
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [grandparent (z/up parent)]
                     (when (and grandparent (= :list (z/tag grandparent)))
                       (let [gp-children (z/child-sexprs grandparent)]
                         (= 'not (first gp-children))))))))
    :reason "(not (nil? x)) is equivalent to (some? x)"}

   {:operator :swap-some-nil
    :context (fn [zloc]
               ;; Check if we're inside (not ...)
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [grandparent (z/up parent)]
                     (when (and grandparent (= :list (z/tag grandparent)))
                       (let [gp-children (z/child-sexprs grandparent)]
                         (= 'not (first gp-children))))))))
    :reason "(not (some? x)) is not equivalent to (nil? x), but the swap pattern is suspicious"}

   ;; =========================================================================
   ;; Boundary Comparison Patterns (count/length are always non-negative)
   ;; =========================================================================

   ;; (>= (count x) 0) is always true - swapping >= to > changes behavior
   ;; but (< (count x) 0) is always false - any mutation here is equivalent
   {:operator :swap-lt-lte
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [[op left right] (z/child-sexprs parent)]
                     (and (= '< op)
                          (= 0 right)
                          (seq? left)
                          (non-negative-fn? (first left)))))))
    :reason "(< (count x) 0) is always false"}

   {:operator :swap-lte-lt
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [[op left right] (z/child-sexprs parent)]
                     (and (= '<= op)
                          (= 0 right)
                          (seq? left)
                          (non-negative-fn? (first left)))))))
    :reason "(<= (count x) 0) is only true when count=0, but (<= x 0) for non-negative is equivalent to (= x 0)"}

   ;; =========================================================================
   ;; Multiply by Zero Pattern (result is always 0)
   ;; =========================================================================

   {:operator :swap-mult-div
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (some #(= 0 %)
                         (rest (z/child-sexprs parent))))))
    :reason "Multiplying by zero always returns zero"}

   {:operator :swap-div-mult
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   ;; (/ 0 x) is always 0 (unless x is 0, but that's an error anyway)
                   (= 0 (second (z/child-sexprs parent))))))
    :reason "Dividing zero by anything is zero"}

   ;; =========================================================================
   ;; Function Contract Patterns
   ;; =========================================================================

   ;; (nil? (str x)) is always false - str never returns nil
   {:operator :swap-nil-some
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (and (seq? arg)
                          (never-nil-fn? (first arg)))))))
    :reason "Function never returns nil, so nil? is always false"}

   ;; (some? (str x)) is always true - str never returns nil
   {:operator :swap-some-nil
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (and (seq? arg)
                          (never-nil-fn? (first arg)))))))
    :reason "Function never returns nil, so some? is always true"}

   ;; =========================================================================
   ;; Lazy/Eager Equivalences
   ;; =========================================================================

   ;; (map f coll) vs (mapv f coll) when wrapped in vec/into/count
   {:operator :swap-map-mapv
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "map/mapv equivalent when immediately realized"}

   {:operator :swap-mapv-map
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "mapv/map equivalent when immediately realized"}

   {:operator :swap-filter-filterv
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "filter/filterv equivalent when immediately realized"}

   {:operator :swap-filterv-filter
    :context (fn [zloc] (in-realizing-context? zloc))
    :reason "filterv/filter equivalent when immediately realized"}

   ;; =========================================================================
   ;; Collection Literal Patterns
   ;; =========================================================================

   ;; (empty? []) is always true
   ;; Note: [] and '() are equal in Clojure, so only include [] to avoid duplicate key error
   {:operator :swap-seq-empty
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (contains? #{[] {} #{}} arg)))))
    :reason "empty? on literal empty collection is always true"}

   ;; (seq []) is always nil
   {:operator :swap-empty-seq
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (contains? #{[] {} #{}} arg)))))
    :reason "seq on literal empty collection is always nil"}

   ;; (first [x]) == (last [x]) for single element
   {:operator :swap-first-last
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (and (vector? arg) (= 1 (count arg)))))))
    :reason "first and last are equivalent on single-element collection"}

   {:operator :swap-last-first
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [arg (second (z/child-sexprs parent))]
                     (and (vector? arg) (= 1 (count arg)))))))
    :reason "last and first are equivalent on single-element collection"}

   ;; =========================================================================
   ;; Threading Macro Equivalences
   ;; =========================================================================

   ;; (-> x) == x (threading with single value is identity)
   {:operator :swap-thread-first-last
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [children (rest (z/child-sexprs parent))]
                     ;; Only the initial value, no transformations
                     (= 1 (count children))))))
    :reason "Threading with single value is identity"}

   ;; some-> vs -> when initial is non-nil literal
   {:operator :swap-some-thread-first
    :context (fn [zloc]
               (let [parent (z/up zloc)]
                 (when (and parent (= :list (z/tag parent)))
                   (let [initial (second (z/child-sexprs parent))]
                     (literal-non-nil? initial)))))
    :reason "some-> is equivalent to -> when initial value is non-nil literal"}])

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
  "Check if a mutation is likely to produce equivalent code.

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
  "Filter out likely equivalent mutations from a collection.

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
;; Simple Heuristic Detection (No Zipper Required)
;; =============================================================================

(def simple-equivalent-patterns
  "Simple patterns that can be detected from mutation data alone.
   These don't require parsing the source file."
  [;; Constant mutations that are often equivalent
   {:check (fn [m]
             (and (= :replace-0-to-1 (:operator m))
                  ;; 0 in certain contexts is often a sentinel/default
                  (re-find #"default|init|start|begin" (str (:file m)))))
    :reason "Zero as default/initial value may be equivalent"}])

(defn quick-equivalent-check
  "Quick heuristic check for equivalent mutations without parsing.

   Less accurate than full detection but very fast.
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
