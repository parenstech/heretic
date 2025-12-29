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

   Main API:
   - `analyze-kill-patterns` - Group mutants by which test killed them
   - `find-subsumed` - Identify mutants that could potentially be skipped
   - `subsumption-stats` - Report on potential savings from subsumption

   Note: This is Phase 3.3 groundwork. Actual skipping of subsumed mutants
   requires careful consideration of mutation ordering and early termination."
  (:require [clojure.set :as set]))

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
