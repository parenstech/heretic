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

   This module provides static pattern detection to identify likely equivalent
   mutants before running tests."
  (:require [rewrite-clj.zip :as z]))

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
    :reason "Comparing identical values"}])

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
