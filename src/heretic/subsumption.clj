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

   Main API:
   - `analyze-kill-patterns` - Group mutants by which test killed them
   - `find-subsumed` - Identify mutants that could potentially be skipped
   - `subsumption-stats` - Report on potential savings from subsumption
   - `operator-subsumption` - Get statically-known operator relationships
   - `find-dominator-mutants` - Find mutants that are not subsumed by others
   - `minimal-mutation-set` - Select representative mutants per location"
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
           (let [local-test-idx (zipmap tests (range))]
             (reduce-kv
              (fn [m local-m-idx local-kills]
                (let [global-m-idx (get mutant-idx-map (nth mutants local-m-idx))
                      global-kills (set (map #(get test-idx-map (nth tests %)) local-kills))]
                  (update m global-m-idx (fnil into #{}) global-kills)))
              acc
              matrix)))
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
