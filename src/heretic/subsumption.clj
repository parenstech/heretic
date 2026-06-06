(ns heretic.subsumption
  "Subsumption analysis for mutation testing optimization.

   Subsumption analysis identifies redundant testing by analyzing which tests
   kill which mutants. If mutant A is always killed by the same tests that
   kill mutant B, then A is 'subsumed' by B - we can potentially skip testing
   A once B is confirmed killed.

   Key concepts:
   - Kill pattern: The set of tests that kill a given mutant
   - Subsumption: Mutant A subsumes B if every test that kills B also kills A
   - Minimal mutant set: Mutants with unique kill patterns (non-subsumed)
   - Operator subsumption: Pre-computed relationships between mutation operators
   - Dominator mutants: Mutants at the top of the subsumption hierarchy
   - Subsumption graph: DAG of dominance relationships between operators

   Main API:
   - `analyze-kill-patterns` - Group mutants by which test killed them
   - `find-subsumed` - Identify mutants that could potentially be skipped
   - `subsumption-stats` - Report on potential savings from subsumption
   - `operator-subsumption` - Get statically-known operator relationships
   - `find-dominator-mutants` - Find mutants that are not subsumed by others
   - `minimal-mutation-set` - Select representative mutants per location

   Operator Selection API (new):
   - `subsumption-graph` - Complete graph of operator dominance relationships
   - `minimal-operator-set` - Compute minimal operator set covering all subsumption chains
   - `dominated-operators` - Get operators dominated by a given operator
   - `dominating-operators` - Get operators that dominate a given operator"
  (:require [clojure.set :as set]))

;; =============================================================================
;; Operator-Level Subsumption (RORG Schema)
;; =============================================================================
;; Based on research: if one mutation is killed, related mutations are likely killed too.
;; This allows us to skip redundant mutations at the operator level.

(def relational-operator-subsumption
  "Subsumption relationships for relational operator replacement (ROR).

   Based on the RORG (Relational Operator Replacement with Guard) schema.
   For each original operator, lists the minimal set of replacement operators
   that subsume all others. If any of these mutants survive, test quality is
   at that level; if killed, all subsumed variants would also be killed.

   Format: {original-op -> [minimal-replacement-ops]}

   Example: For `<`, only need to test `<=`, `!=`, and `false`.
   If `<` -> `<=` is killed, then `<` -> `true` would also be killed."
  {'<  [:swap-lt-lte :swap-lt-neq :replace-comparison-false]
   '>  [:swap-gt-gte :swap-gt-neq :replace-comparison-false]
   '<= [:swap-lte-lt :swap-lte-eq :replace-comparison-true]
   '>= [:swap-gte-gt :swap-gte-eq :replace-comparison-true]
   '=  [:swap-eq-lte :swap-eq-gte :replace-comparison-false]
   'not= [:swap-neq-lt :swap-neq-gt :replace-comparison-true]})

(def arithmetic-operator-subsumption
  "Subsumption for arithmetic operators (AOR).

   For arithmetic, swapping to the inverse operator typically subsumes
   other mutations. If `+` -> `-` is killed, then `+` -> `*` would likely be too.

   Simplified AORs uses only inverse operators."
  {'+  [:swap-plus-minus]              ; If + -> - killed, others subsumed
   '-  [:swap-minus-plus]
   '*  [:swap-mult-div]
   '/  [:swap-div-mult]})

(def boolean-operator-subsumption
  "Subsumption for boolean operators."
  {'and [:swap-and-or :replace-and-false]
   'or  [:swap-or-and :replace-or-true]
   'not [:remove-not]})

(def all-operator-subsumption
  "Combined operator subsumption tables."
  (merge relational-operator-subsumption
         arithmetic-operator-subsumption
         boolean-operator-subsumption))

;; =============================================================================
;; Formal Subsumption Graph
;; =============================================================================
;; The subsumption graph represents dominance relationships between operators.
;; If A dominates B, killing A implies killing B - so we only need to test A.
;;
;; Graph structure: {dominating-operator -> #{dominated-operators}}
;; Reading: "A subsumes B" means A is the dominator, B is dominated
;;
;; Based on RORG (Relational Operator Replacement with Guard) research:
;; - Boundary mutations (< -> <=) are stronger than simple swaps
;; - Replacing with false/true covers extreme cases
;; - For arithmetic, inverse operations catch most faults

(def ^:private relational-subsumption-edges
  "Subsumption edges for relational operators.

   Key insight from RORG: For `<`, the mutations `<=`, `!=`, and `false`
   together form a minimal set. If all three are killed, we have confidence
   the test suite is thorough for that comparison.

   The graph is organized by original operator:
   - :swap-lt-lte dominates :swap-lt-gt (boundary change catches simple swap)
   - :replace-comparison-false dominates most other mutations (extreme case)

   Note: replace-comparison-false/true are shared across all comparison operators,
   so they dominate ALL simple swaps for their respective classes."
  {;; < mutations - swap-lt-lte is the boundary mutation (stronger)
   :swap-lt-lte #{:swap-lt-gt}        ; < -> <= catches more than < -> >
   :swap-lt-neq #{:swap-lt-gt}        ; < -> != catches more than < -> >

   ;; > mutations - analogous to <
   :swap-gt-gte #{:swap-gt-lt}
   :swap-gt-neq #{:swap-gt-lt}

   ;; <= mutations
   :swap-lte-lt #{:swap-lte-gte}
   :swap-lte-eq #{:swap-lte-gte}

   ;; >= mutations
   :swap-gte-gt #{:swap-gte-lte}
   :swap-gte-eq #{:swap-gte-lte}

   ;; = mutations
   :swap-eq-lte #{:swap-eq-neq}
   :swap-eq-gte #{:swap-eq-neq}

   ;; not= mutations
   :swap-neq-lt #{:swap-neq-eq}
   :swap-neq-gt #{:swap-neq-eq}

   ;; Extreme replacements dominate all simple swaps for their class
   ;; false dominates <, >, = mutations (strictness mutations)
   :replace-comparison-false #{:swap-lt-gt :swap-lt-lte :swap-lt-neq
                               :swap-gt-lt :swap-gt-gte :swap-gt-neq
                               :swap-eq-neq :swap-eq-lte :swap-eq-gte}
   ;; true dominates <=, >=, not= mutations (inclusiveness mutations)
   :replace-comparison-true #{:swap-lte-gte :swap-lte-lt :swap-lte-eq
                              :swap-gte-lte :swap-gte-gt :swap-gte-eq
                              :swap-neq-eq :swap-neq-lt :swap-neq-gt}})

(def ^:private arithmetic-subsumption-edges
  "Subsumption edges for arithmetic operators.

   For arithmetic, the inverse operation is typically sufficient.
   + -> - catches sign errors, which subsume other arithmetic mutations."
  {:swap-plus-minus #{}   ; Minimal - no dominated operators
   :swap-minus-plus #{}
   :swap-mult-div #{}
   :swap-div-mult #{}})

(def ^:private boolean-subsumption-edges
  "Subsumption edges for boolean operators.

   For boolean operators:
   - Replacing with false/true tests the extreme case
   - and -> or tests logic inversion
   - remove-not tests negation handling"
  {;; and mutations
   :replace-and-false #{:swap-and-or}  ; false is stronger than or
   :swap-and-or #{}                     ; Minimal for and logic

   ;; or mutations
   :replace-or-true #{:swap-or-and}    ; true is stronger than and
   :swap-or-and #{}                     ; Minimal for or logic

   ;; not mutations
   :remove-not #{}})                    ; Minimal - removes negation

(def ^:private collection-subsumption-edges
  "Subsumption edges for collection operators.

   Collection operators have their own subsumption relationships:
   - first/last are incomparable (different positions)
   - rest/next have subtle differences (empty vs nil)
   - take/drop are inverse operations"
  {:swap-first-last #{}                ; Minimal - different semantics
   :swap-last-first #{}
   :swap-first-rest #{}                ; first -> rest is different type
   :swap-rest-next #{}                 ; rest/next differ on empty
   :swap-next-rest #{}
   :swap-take-drop #{}                 ; Inverse operations - both minimal
   :swap-drop-take #{}
   :swap-conj-disj #{}
   :swap-disj-conj #{}
   :swap-inc-dec #{}
   :swap-dec-inc #{}})

(def ^:private nil-handling-subsumption-edges
  "Subsumption edges for nil-handling operators.

   nil?/some? are direct inverses, both minimal.
   seq/empty? have different semantics (truthy vs boolean)."
  {:swap-nil-some #{}
   :swap-some-nil #{}
   :swap-seq-empty #{}
   :swap-empty-seq #{}})

(def ^:private threading-subsumption-edges
  "Subsumption edges for threading operators.

   -> / ->> swap tests argument position.
   some-> removes nil safety - potentially stronger mutation."
  {:swap-thread-first-last #{}
   :swap-thread-last-first #{}
   :swap-some-first-thread #{:swap-thread-first-last}  ; Removing nil safety is stronger
   :swap-thread-some-first #{}                          ; Adding nil safety is different
   :swap-some-last-thread #{:swap-thread-last-first}
   :swap-thread-some-last #{}})

(def ^:private lazy-eager-subsumption-edges
  "Subsumption edges for lazy/eager operators.

   Lazy vs eager typically caught by same tests."
  {:swap-map-mapv #{}
   :swap-mapv-map #{}
   :swap-filter-filterv #{}
   :swap-filterv-filter #{}
   :swap-for-doseq #{}
   :swap-doseq-for #{}})

(def ^:private hof-subsumption-edges
  "Subsumption edges for higher-order function operators.

   filter/remove are inverses - both minimal."
  {:swap-filter-remove #{}
   :swap-remove-filter #{}
   :swap-keep-filter #{}
   :swap-filter-keep #{}})

(def ^:private constant-subsumption-edges
  "Subsumption edges for constant replacement operators.

   For constants, different replacements test different boundary conditions:
   - 0 -> 1 tests off-by-one
   - 0 -> -1 tests sign handling"
  {:replace-0-to-1 #{}
   :replace-1-to-0 #{}
   :replace-0-to-neg1 #{}
   :replace-1-to-neg1 #{}
   :replace-neg1-to-0 #{}
   :replace-neg1-to-1 #{}
   :replace-2-to-1 #{}
   :replace-2-to-0 #{}
   :replace-10-to-0 #{}
   :replace-100-to-0 #{}
   :replace-nil-false #{}
   :replace-nil-zero #{}
   :replace-nil-empty-vec #{}
   :replace-nil-empty-map #{}
   :replace-nil-empty-str #{}})

(def ^:private destructuring-subsumption-edges
  "Subsumption edges for destructuring operators."
  {:mutate-kebab-to-camel #{}
   :mutate-camel-to-kebab #{}
   :mutate-ns-typo #{}
   :mutate-qualified-to-unqualified #{}})

(def subsumption-graph
  "Complete subsumption graph for all operators.

   Structure: {dominating-operator -> #{dominated-operators}}

   An operator A dominates B if killing A implies B would also be killed.
   In the graph, edges point from dominator to dominated.

   This graph is used to:
   1. Compute minimal operator sets
   2. Skip redundant mutations
   3. Prioritize stronger mutations"
  (merge-with into
              relational-subsumption-edges
              arithmetic-subsumption-edges
              boolean-subsumption-edges
              collection-subsumption-edges
              nil-handling-subsumption-edges
              threading-subsumption-edges
              lazy-eager-subsumption-edges
              hof-subsumption-edges
              constant-subsumption-edges
              destructuring-subsumption-edges))

(def ^:private reverse-subsumption-graph
  "Reverse subsumption graph: {dominated-operator -> #{dominating-operators}}

   Used to find what operators dominate a given operator."
  (reduce-kv
   (fn [acc dominator dominated-set]
     (reduce (fn [m dominated]
               (update m dominated (fnil conj #{}) dominator))
             acc
             dominated-set))
   {}
   subsumption-graph))

;; =============================================================================
;; Operator Selection Functions
;; =============================================================================

(defn dominated-operators
  "Return the set of operators dominated by the given operator.

   An operator A dominates B if killing A implies B would also be killed.
   Returns all operators that would be 'covered' by testing the given operator.

   Arguments:
   - op-id: Keyword identifier of the operator (e.g., :swap-lt-lte)

   Returns set of dominated operator ids, or empty set if none."
  [op-id]
  (get subsumption-graph op-id #{}))

(defn dominating-operators
  "Return the set of operators that dominate the given operator.

   If A dominates B, then B is subsumed by A - killing A would kill B too.
   Returns all operators that are 'stronger' than the given operator.

   Arguments:
   - op-id: Keyword identifier of the operator (e.g., :swap-lt-gt)

   Returns set of dominating operator ids, or empty set if none."
  [op-id]
  (get reverse-subsumption-graph op-id #{}))

(defn- transitive-closure
  "Compute transitive closure of dominated operators.

   Given an operator, returns all operators it dominates directly or indirectly."
  [op-id]
  (loop [visited #{}
         frontier #{op-id}]
    (if (empty? frontier)
      (disj visited op-id)  ; Remove the starting operator itself
      (let [current (first frontier)
            directly-dominated (get subsumption-graph current #{})
            new-ops (set/difference directly-dominated visited)]
        (recur (conj visited current)
               (into (disj frontier current) new-ops))))))

(defn all-dominated-operators
  "Return all operators transitively dominated by the given operator.

   Unlike `dominated-operators` which returns direct dominance,
   this returns the transitive closure - all operators that would
   be covered by testing the given operator.

   Arguments:
   - op-id: Keyword identifier of the operator

   Returns set of all transitively dominated operator ids."
  [op-id]
  (transitive-closure op-id))

(defn- find-graph-roots
  "Find root operators in the subsumption graph.

   Roots are operators that are not dominated by any other operator.
   These are the 'strongest' mutations in each subsumption chain."
  [graph]
  (let [all-ops (set (keys graph))
        dominated (reduce into #{} (vals graph))]
    (set/difference all-ops dominated)))

(defn minimal-operator-set
  "Compute the minimal set of operators that covers all subsumption chains.

   The minimal set contains only dominating operators - those that are not
   subsumed by any other operator. Testing these operators is sufficient
   because killing any of them implies killing all operators they dominate.

   Arguments:
   - operators: Set of operator ids to consider (default: all operators in graph)

   Returns set of operator ids representing the minimal covering set.

   Example:
   (minimal-operator-set #{:swap-lt-lte :swap-lt-gt :replace-comparison-false})
   ;; => #{:replace-comparison-false}
   ;; Because replace-comparison-false dominates both swap-lt-lte and swap-lt-gt"
  ([]
   (minimal-operator-set (set (keys subsumption-graph))))
  ([operators]
   (let [op-set (set operators)]
     ;; Filter to only operators in the input set
     (set/difference
      op-set
      ;; Remove any operator that is dominated by another operator in the set
      (reduce
       (fn [dominated op]
         (let [ops-dominated-by-op (get subsumption-graph op #{})]
           ;; Only add dominated operators that are also in our input set
           (into dominated (set/intersection ops-dominated-by-op op-set))))
       #{}
       op-set)))))

(defn operator-subsumption-chains
  "Get all subsumption chains starting from root operators.

   Returns a map of {root-operator -> [chain of dominated operators]}
   where chains are ordered from strongest to weakest.

   Useful for understanding the subsumption hierarchy."
  []
  (let [roots (find-graph-roots subsumption-graph)]
    (reduce
     (fn [acc root]
       (assoc acc root
              (loop [chain [root]
                     visited #{root}
                     frontier (vec (dominated-operators root))]
                (if (empty? frontier)
                  chain
                  (let [current (first frontier)
                        next-dominated (set/difference
                                        (dominated-operators current)
                                        visited)]
                    (recur (conj chain current)
                           (conj visited current)
                           (into (vec (rest frontier)) next-dominated)))))))
     {}
     roots)))

;; =============================================================================
;; Operator Categories for Presets
;; =============================================================================

(def operator-categories
  "Categorization of operators by mutation type.

   Used to build presets that select specific categories."
  {:relational #{:swap-lt-gt :swap-gt-lt :swap-lte-gte :swap-gte-lte
                 :swap-eq-neq :swap-neq-eq
                 :swap-lt-lte :swap-gt-gte :swap-lte-lt :swap-gte-gt
                 :swap-lt-neq :swap-gt-neq :swap-lte-eq :swap-gte-eq
                 :swap-eq-lte :swap-eq-gte :swap-neq-lt :swap-neq-gt
                 :replace-comparison-false :replace-comparison-true}

   :arithmetic #{:swap-plus-minus :swap-minus-plus
                 :swap-mult-div :swap-div-mult
                 :swap-inc-dec :swap-dec-inc}

   :boolean #{:swap-and-or :swap-or-and
              :swap-true-false :swap-false-true
              :replace-and-false :replace-or-true
              :remove-not}

   :collection #{:swap-first-last :swap-last-first
                 :swap-first-rest :swap-rest-next :swap-next-rest
                 :swap-take-drop :swap-drop-take
                 :swap-conj-disj :swap-disj-conj}

   :nil-handling #{:swap-nil-some :swap-some-nil
                   :swap-seq-empty :swap-empty-seq}

   :threading #{:swap-thread-first-last :swap-thread-last-first
                :swap-thread-some-first :swap-some-first-thread
                :swap-thread-some-last :swap-some-last-thread}

   :lazy-eager #{:swap-map-mapv :swap-mapv-map
                 :swap-filter-filterv :swap-filterv-filter
                 :swap-for-doseq :swap-doseq-for}

   :hof #{:swap-filter-remove :swap-remove-filter
          :swap-keep-filter :swap-filter-keep}

   :constant #{:replace-0-to-1 :replace-1-to-0
               :replace-0-to-neg1 :replace-1-to-neg1
               :replace-neg1-to-0 :replace-neg1-to-1
               :replace-2-to-1 :replace-2-to-0
               :replace-10-to-0 :replace-100-to-0
               :replace-nil-false :replace-nil-zero
               :replace-nil-empty-vec :replace-nil-empty-map
               :replace-nil-empty-str}

   :destructuring #{:mutate-kebab-to-camel :mutate-camel-to-kebab
                    :mutate-ns-typo :mutate-qualified-to-unqualified}})

(def minimal-preset-operators
  "Operators for the :minimal preset.

   This preset uses only dominating operators - the minimal set that
   achieves coverage of all subsumption chains. Based on RORG research,
   this achieves nearly the same fault detection with ~40% fewer mutations.

   Categories included:
   - Arithmetic: Just inverse operations (+ <-> -, * <-> /)
   - Relational: Boundary + extreme (<=, !=, false/true)
   - Boolean: Logic swap + extreme (and<->or, false/true)
   - Collection: Key operations (first/last, rest/next)
   - Nil-handling: nil?/some?, seq/empty?"
  #{;; Arithmetic - inverse operations only
    :swap-plus-minus
    :swap-minus-plus
    :swap-mult-div
    :swap-div-mult

    ;; Relational - RORG minimal set
    ;; For <: swap-lt-lte, swap-lt-neq, replace-comparison-false
    :swap-lt-lte
    :swap-lt-neq
    :swap-gt-gte
    :swap-gt-neq
    :swap-lte-lt
    :swap-lte-eq
    :swap-gte-gt
    :swap-gte-eq
    :swap-eq-lte
    :swap-eq-gte
    :swap-neq-lt
    :swap-neq-gt
    :replace-comparison-false
    :replace-comparison-true

    ;; Boolean - logic + extreme
    :swap-and-or
    :swap-or-and
    :replace-and-false
    :replace-or-true
    :remove-not

    ;; Collection - essential operations
    :swap-first-last
    :swap-last-first
    :swap-rest-next
    :swap-next-rest

    ;; Nil-handling
    :swap-nil-some
    :swap-some-nil
    :swap-seq-empty
    :swap-empty-seq})

(defn minimal-operators-for
  "Get the minimal set of operators to use for a given original operator.

   Arguments:
   - original-sym: The original operator symbol (e.g., '<, '+, 'and)

   Returns vector of operator keywords representing minimal mutant set,
   or nil if no subsumption information available."
  [original-sym]
  (get all-operator-subsumption original-sym))

(defn subsumed-by?
  "Check if operator A is subsumed by operator B.

   Arguments:
   - op-a: Operator keyword (e.g., :swap-lt-gt)
   - op-b: Operator keyword (e.g., :swap-lt-lte)

   Returns true if B subsumes A (killing B implies killing A)."
  [op-a op-b original-sym]
  (when-let [minimal-ops (minimal-operators-for original-sym)]
    (and (contains? (set minimal-ops) op-b)
         (not (contains? (set minimal-ops) op-a)))))

(defn filter-by-operator-subsumption
  "Filter mutations to only include non-subsumed operators for each location.

   Arguments:
   - mutations: Sequence of mutation records with :operator and :original

   Returns:
   {:mutations [...] - Non-subsumed mutations
    :subsumed [...] - Subsumed mutations (skipped)
    :subsumed-count n}"
  [mutations]
  (let [;; Group mutations by location (file + line + coord)
        by-location (group-by #(select-keys % [:file :line :coord]) mutations)
        ;; For each location, keep only minimal operators
        filtered-and-subsumed
        (reduce-kv
         (fn [acc _loc loc-mutations]
           (let [;; Group by original symbol
                 by-original (group-by :original loc-mutations)
                 ;; For each original, filter to minimal set
                 processed
                 (mapcat (fn [[orig muts]]
                           (if-let [minimal-ops (minimal-operators-for orig)]
                             (let [minimal-set (set minimal-ops)
                                   {keep true skip false}
                                   (group-by #(contains? minimal-set (:operator %)) muts)]
                               [{:keep (or keep []) :skip (or skip [])}])
                             [{:keep muts :skip []}]))
                         by-original)]
             {:keep (into (:keep acc) (mapcat :keep processed))
              :skip (into (:skip acc) (mapcat :skip processed))}))
         {:keep [] :skip []}
         by-location)]
    {:mutations (:keep filtered-and-subsumed)
     :subsumed (:skip filtered-and-subsumed)
     :subsumed-count (count (:skip filtered-and-subsumed))}))

;; =============================================================================
;; Dominator Mutant Selection
;; =============================================================================

(defn find-dominator-mutants
  "Find mutants that dominate others in the subsumption hierarchy.

   A dominator mutant has a minimal kill set - no other mutant has a strict
   subset of tests that kill it. These are the 'hardest' mutants to kill.

   In subsumption terms: if A's kills ⊆ B's kills, then A dominates B
   (A is harder to kill, and any test that kills A also kills B).

   For mutation reduction: testing only dominators is sufficient because
   killing a dominator implies killing all mutants it dominates.

   Arguments:
   - kill-matrix: Result from build-kill-matrix with :matrix as {idx -> #{test-indices}}

   Returns set of mutant indices that are dominators (minimal kill sets)."
  [{:keys [matrix]}]
  (let [mutant-indices (keys matrix)]
    (set
     (filter
      (fn [m-idx]
        (let [m-kills (get matrix m-idx #{})]
          ;; m is dominator if no other mutant has a strict subset of m's kills
          ;; (meaning no one is "harder" to kill than m)
          (not-any?
           (fn [[other-idx other-kills]]
             (and (not= m-idx other-idx)
                  ;; other dominates m if other's kills are a proper subset
                  (set/subset? other-kills m-kills)
                  (not= other-kills m-kills)))
           matrix)))
      mutant-indices))))

(defn dominator-reduction-stats
  "Calculate statistics about dominator-based mutant reduction.

   Arguments:
   - kill-matrix: Result from build-kill-matrix

   Returns:
   {:total-mutants n
    :dominator-count n
    :reduction-percentage pct}"
  [kill-matrix]
  (let [total (count (:mutants kill-matrix))
        dominators (find-dominator-mutants kill-matrix)
        dom-count (count dominators)]
    {:total-mutants total
     :dominator-count dom-count
     :dominated-count (- total dom-count)
     :reduction-percentage (if (pos? total)
                             (* 100.0 (/ (- total dom-count) total))
                             0.0)}))

;; =============================================================================
;; Full Kill Matrix Mode (Calibration)
;; =============================================================================

(defn merge-kill-matrices
  "Merge multiple kill matrices from different runs.

   Used for calibration mode where we run all tests (not early exit)
   to build complete subsumption information.

   Arguments:
   - matrices: Sequence of kill matrix maps

   Returns combined kill matrix."
  [matrices]
  (let [all-mutants (into [] (distinct (mapcat :mutants matrices)))
        all-tests (into [] (distinct (mapcat :tests matrices)))
        test-idx-map (zipmap all-tests (range))
        mutant-idx-map (zipmap all-mutants (range))
        ;; Build combined matrix
        combined-matrix
        (reduce
         (fn [acc {:keys [mutants tests matrix]}]
           (reduce-kv
            (fn [m local-m-idx local-kills]
              (let [global-m-idx (get mutant-idx-map (nth mutants local-m-idx))
                    global-kills (set (map #(get test-idx-map (nth tests %)) local-kills))]
                (update m global-m-idx (fnil into #{}) global-kills)))
            acc
            matrix))
         {}
         matrices)]
    {:mutants all-mutants
     :tests all-tests
     :matrix combined-matrix}))

(defn complete-subsumption-analysis
  "Perform complete subsumption analysis on a full kill matrix.

   Unlike early-exit mode, this requires running all tests for each mutant
   to build complete kill sets.

   Arguments:
   - kill-matrix: Full kill matrix with complete test coverage per mutant

   Returns:
   {:dominators #{mutant-indices}
    :subsumption-graph {m-idx -> #{dominated-by-m-indices}}
    :stats {...}}"
  [kill-matrix]
  (let [{:keys [matrix]} kill-matrix
        mutant-indices (set (keys matrix))
        ;; Build subsumption graph: m1 -> #{m2, m3} means m1 subsumes m2, m3
        subsumption-graph
        (reduce
         (fn [graph m-idx]
           (let [m-kills (get matrix m-idx #{})]
             (assoc graph m-idx
                    (set (filter
                          (fn [other-idx]
                            (and (not= m-idx other-idx)
                                 (let [other-kills (get matrix other-idx #{})]
                                   ;; m subsumes other if other's kills ⊂ m's kills
                                   (and (set/subset? other-kills m-kills)
                                        (not= other-kills m-kills)))))
                          mutant-indices)))))
         {}
         mutant-indices)
        dominators (find-dominator-mutants kill-matrix)]
    {:dominators dominators
     :subsumption-graph subsumption-graph
     :stats (dominator-reduction-stats kill-matrix)}))

(defn select-minimal-mutants
  "Select a minimal set of mutants that covers all tests.

   Uses a greedy set-cover algorithm: repeatedly select the mutant
   whose kills cover the most uncovered tests.

   Arguments:
   - kill-matrix: Full kill matrix

   Returns vector of selected mutant indices."
  [{:keys [matrix tests]}]
  (let [all-test-indices (set (range (count tests)))]
    (loop [uncovered all-test-indices
           selected []]
      (if (empty? uncovered)
        selected
        ;; Find mutant that covers most uncovered tests
        (let [best-mutant
              (apply max-key
                     (fn [m-idx]
                       (count (set/intersection (get matrix m-idx #{}) uncovered)))
                     (keys matrix))
              new-covered (get matrix best-mutant #{})]
          (if (empty? (set/intersection new-covered uncovered))
            selected  ; No progress, done
            (recur (set/difference uncovered new-covered)
                   (conj selected best-mutant))))))))

;; =============================================================================
;; Enhanced Incremental Analysis
;; =============================================================================

(defn can-skip-mutation?
  "Determine if a mutation result can be inferred from previous run history.

   Arguments:
   - mutation: Mutation record
   - history: Map of {mutation-identity -> previous-result}
   - changed-tests: Set of test symbols that have changed since last run
   - changed-files: Set of source files that have changed

   Returns {:skip true :inferred-status ...} or {:skip false :reason ...}"
  [mutation history changed-tests changed-files]
  (let [m-id (select-keys mutation [:file :line :coord :operator])
        prev-result (get history m-id)]
    (cond
      ;; No previous result
      (nil? prev-result)
      {:skip false :reason :no-history}

      ;; Source file changed - must re-test
      (contains? changed-files (:file mutation))
      {:skip false :reason :source-changed}

      ;; Previously killed, killer test unchanged
      (and (= :killed (:status prev-result))
           (not (contains? changed-tests (:killed-by prev-result))))
      {:skip true :inferred-status :killed :reason :unchanged-killer}

      ;; Previously survived, no covering tests changed
      (and (= :survived (:status prev-result))
           (empty? (set/intersection changed-tests (:tests-run prev-result))))
      {:skip true :inferred-status :survived :reason :unchanged-covering-tests}

      ;; Previously timed out or errored, source unchanged
      (and (contains? #{:timeout :error} (:status prev-result))
           (not (contains? changed-files (:file mutation))))
      {:skip true :inferred-status (:status prev-result) :reason :unchanged-problematic}

      :else
      {:skip false :reason :requires-retest})))

;; =============================================================================
;; Kill Pattern Analysis
;; =============================================================================

(defn analyze-kill-patterns
  "Group mutants by the test that killed them.

   Takes a sequence of mutation results (from runner/evaluate-mutations)
   and groups killed mutants by the test symbol that killed them.

   Arguments:
   - results: Sequence of mutation result maps with :status and :killed-by

   Returns map of {test-sym -> [mutant1 mutant2 ...]}
   where each mutant is the full mutation result map.
   Only includes killed mutants (ignores survived, no-coverage, timeout)."
  [results]
  (let [killed-results (filter #(= :killed (:status %)) results)]
    (group-by :killed-by killed-results)))

(defn mutation-identity
  "Create a unique identity for a mutation based on its location and operation.

   This is used to compare mutations across different runs.

   Returns a map with the identifying fields:
   {:form-id n :coord [...] :operator kw :file str :line n}"
  [result]
  (select-keys (:mutation result) [:form-id :coord :operator :file :line]))

;; =============================================================================
;; Subsumption Detection
;; =============================================================================

(defn find-subsumed
  "Identify mutants that are always killed by the same test.

   A mutant is 'subsumed' if every time it's killed, it's killed by the same
   test that kills other mutants. These are candidates for skipping in future
   runs since killing the 'dominating' mutant implies killing the subsumed ones.

   Arguments:
   - results: Sequence of mutation result maps

   Returns map of {test-sym -> {:count n :mutants [...]}}
   where :count is the number of mutants killed by that test
   and :mutants are the mutation result maps.

   Tests that kill many mutants represent high-value tests.
   Mutants killed by the same test are subsumption candidates."
  [results]
  (let [kill-patterns (analyze-kill-patterns results)]
    (reduce-kv
     (fn [acc test-sym mutants]
       (if (nil? test-sym)
         acc  ;; Skip results without killed-by info
         (assoc acc test-sym
                {:count (count mutants)
                 :mutants mutants})))
     {}
     kill-patterns)))

(defn dominant-tests
  "Find tests that kill the most mutants (high-value tests).

   These tests are particularly valuable for mutation testing optimization
   because they have high 'killing power'.

   Arguments:
   - results: Sequence of mutation result maps
   - n: Number of top tests to return (default 10)

   Returns vector of [test-sym kill-count] pairs, sorted by kill count descending."
  ([results] (dominant-tests results 10))
  ([results n]
   (let [subsumed (find-subsumed results)]
     (->> subsumed
          (map (fn [[test-sym data]] [test-sym (:count data)]))
          (sort-by second >)
          (take n)
          vec))))

(defn mutants-killed-by-test
  "Get all mutants killed by a specific test.

   Arguments:
   - results: Sequence of mutation result maps
   - test-sym: The test symbol to look up

   Returns sequence of mutation result maps killed by this test."
  [results test-sym]
  (filter #(and (= :killed (:status %))
                (= test-sym (:killed-by %)))
          results))

;; =============================================================================
;; Subsumption Statistics
;; =============================================================================

(defn subsumption-stats
  "Generate statistics about kill patterns and potential optimization savings.

   Arguments:
   - results: Sequence of mutation result maps

   Returns:
   {:total-killed n  ; Total mutations killed
    :unique-killers n  ; Number of unique tests that killed mutations
    :single-test-kills n  ; Mutants killed by only one test (subsumption candidates)
    :dominant-tests [...]  ; Top tests by kill count
    :potential-savings pct  ; Estimated % of tests that could be skipped
    :kill-distribution {...}}  ; Distribution of kill counts per test"
  [results]
  (let [subsumed (find-subsumed results)
        total-killed (reduce + 0 (map :count (vals subsumed)))
        unique-killers (count (remove nil? (keys subsumed)))
        kill-counts (map :count (vals subsumed))
        dominant (dominant-tests results 5)]
    {:total-killed total-killed
     :unique-killers unique-killers
     :single-test-kills (count (filter #(= 1 %) kill-counts))
     :dominant-tests dominant
     ;; Potential savings: if we run dominant tests first and they kill their
     ;; share of mutants, we could skip those mutants for other tests
     :potential-savings (if (pos? total-killed)
                          (let [top-kills (reduce + 0 (map second dominant))]
                            (double (/ top-kills total-killed)))
                          0.0)
     :kill-distribution (frequencies kill-counts)}))

(defn test-effectiveness-report
  "Generate a report on test effectiveness for mutation testing.

   Shows which tests are most effective at killing mutants, useful for:
   - Prioritizing test ordering (run effective tests first)
   - Identifying redundant tests (tests that never kill anything unique)
   - Understanding test suite coverage characteristics

   Arguments:
   - results: Sequence of mutation result maps

   Returns:
   {:effective-tests [...]  ; Tests ranked by kill count
    :ineffective-tests [...] ; Tests that never killed any mutants (from tests-run)
    :coverage-gaps [...]}  ; No-coverage mutants (not killed by any test)"
  [results]
  (let [;; All tests that were run across all mutations
        all-tests-run (reduce into #{} (map :tests-run results))
        ;; Tests that actually killed something
        killer-tests (set (remove nil? (map :killed-by results)))
        ;; Tests that ran but never killed anything
        ineffective (set/difference all-tests-run killer-tests)
        ;; Get kill counts per test
        subsumed (find-subsumed results)
        effective (sort-by (comp - :count second)
                           (map (fn [[k v]] [k v]) subsumed))
        ;; Mutations with no coverage
        no-coverage (filter #(= :no-coverage (:status %)) results)]
    {:effective-tests (mapv (fn [[test-sym data]]
                              {:test test-sym
                               :kills (:count data)})
                            effective)
     :ineffective-tests (vec ineffective)
     :coverage-gaps (mapv mutation-identity no-coverage)}))

;; =============================================================================
;; Kill Matrix (Advanced Analysis)
;; =============================================================================

(defn build-kill-matrix
  "Build a matrix of which tests kill which mutants.

   This is the foundation for advanced subsumption analysis and
   test suite minimization algorithms.

   Arguments:
   - results: Sequence of mutation result maps

   Returns:
   {:mutants [m1 m2 ...]  ; Vector of mutant identities
    :tests [t1 t2 ...]  ; Vector of test symbols
    :matrix {mutant-idx #{test-indices...}}}  ; Which tests kill each mutant

   Note: Due to early exit, each mutant will have at most one killer.
   The matrix structure supports future work where we might run all tests
   for more complete subsumption analysis."
  [results]
  (let [killed (filter #(= :killed (:status %)) results)
        mutants (mapv mutation-identity killed)
        tests (vec (distinct (remove nil? (map :killed-by killed))))
        test-idx (zipmap tests (range))
        matrix (reduce
                (fn [acc [idx result]]
                  (if-let [killer (:killed-by result)]
                    (assoc acc idx #{(test-idx killer)})
                    acc))
                {}
                (map-indexed vector killed))]
    {:mutants mutants
     :tests tests
     :matrix matrix}))

(defn build-full-kill-matrix
  "Build a COMPLETE kill matrix from no-early-exit results.

   Unlike `build-kill-matrix` (which reads the single `:killed-by` produced by an
   early-exit run and so yields an at-most-one-killer-per-mutant matrix), this reads
   `:killed-by-all` — the FULL set of tests that kill each mutant — so the resulting
   matrix supports EXACT dominator/subsumption analysis.

   Requires results produced by `runner/evaluate-mutations-full` (or
   `runner/evaluate-mutation` with `:kill-matrix-mode true`), which populate
   `:killed-by-all`. Falls back to the single `:killed-by` when `:killed-by-all` is
   absent, so it degrades gracefully on early-exit results.

   Returns {:mutants [...] :tests [...] :matrix {mutant-idx #{test-indices}}}
   — the shape consumed by `complete-subsumption-analysis`, `find-dominator-mutants`,
   and `select-minimal-mutants`."
  [results]
  (let [killed (filter #(= :killed (:status %)) results)
        killers-of (fn [r] (or (not-empty (:killed-by-all r))
                               (some-> (:killed-by r) hash-set)
                               #{}))
        mutants (mapv mutation-identity killed)
        all-tests (vec (distinct (mapcat killers-of killed)))
        test-idx (zipmap all-tests (range))
        matrix (into {}
                     (map-indexed
                      (fn [idx r]
                        [idx (set (map test-idx (killers-of r)))])
                      killed))]
    {:mutants mutants
     :tests all-tests
     :matrix matrix}))

(defn kill-matrix-analysis
  "End-to-end exact subsumption analysis from kill-matrix-mode results.

   Takes the results of `runner/evaluate-mutations-full` (each carrying
   `:killed-by-all`) and returns the dominator/minimal-set analysis the G2/G3/G5
   calibration experiments need:

   {:kill-matrix {...}                  ; the full {mutant -> #{killing tests}} matrix
    :dominators #{mutant-indices}       ; dominator mutants (minimal kill sets)
    :minimal-mutants [mutant-indices]   ; greedy test-cover selection
    :subsumption-graph {m-idx -> #{dominated}}
    :stats {:total-mutants n :dominator-count n :reduction-percentage pct}}"
  [results]
  (let [km (build-full-kill-matrix results)
        analysis (complete-subsumption-analysis km)]
    {:kill-matrix km
     :dominators (:dominators analysis)
     :minimal-mutants (select-minimal-mutants km)
     :subsumption-graph (:subsumption-graph analysis)
     :stats (:stats analysis)}))
