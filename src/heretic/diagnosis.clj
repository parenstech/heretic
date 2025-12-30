(ns heretic.diagnosis
  "Automated diagnosis of surviving mutations.

   Analyzes patterns in surviving mutations to identify common test gaps
   and provide actionable suggestions for improving test coverage.

   Key functions:
   - `diagnose-survivors` - Analyze survivors and return diagnosis
   - `format-diagnosis` - Format diagnosis for display"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Pattern Definitions
;; =============================================================================

(def patterns
  "Known survivor patterns mapped to their diagnosis and fix.
   Each pattern has:
   - :operators - Set of operator IDs that indicate this pattern
   - :threshold - Minimum count to trigger this pattern (default 1)
   - :diagnosis - What the pattern indicates about test gaps
   - :fix - Actionable suggestion to fix the gap
   - :example - Optional code example"

  {:collection-endpoints
   {:operators #{:swap-first-last :swap-last-first :swap-first-rest}
    :threshold 1
    :diagnosis "Tests likely use single-element collections where first/last return the same value"
    :fix "Use 2+ element collections where element order matters"
    :example "(is (= 1 (get-first [1 2 3])))  ; not [42]"}

   :comparison-boundary
   {:operators #{:swap-lt-lte :swap-lte-lt :swap-gt-gte :swap-gte-gt
                 :swap-lt-gt :swap-gt-lt :swap-lte-gte :swap-gte-lte}
    :threshold 1
    :diagnosis "Tests don't hit boundary values where compared values are equal"
    :fix "Add tests where compared values are exactly equal"
    :example "(is (false? (above-threshold? 5 5)))  ; test the boundary"}

   :boolean-logic
   {:operators #{:swap-and-or :swap-or-and}
    :threshold 1
    :diagnosis "Tests only exercise one logical branch (both conditions always true or both false)"
    :fix "Test all truth table combinations: TT, TF, FT, FF"
    :example "(is (false? (valid? {:a true :b false})))  ; test mixed cases"}

   :boolean-constants
   {:operators #{:swap-true-false :swap-false-true}
    :threshold 1
    :diagnosis "Tests don't distinguish between true and false return values"
    :fix "Assert on exact boolean values, not just truthiness"
    :example "(is (true? (enabled?)))  ; not just (is (enabled?))"}

   :arithmetic-symmetry
   {:operators #{:swap-plus-minus :swap-minus-plus :swap-mult-div :swap-div-mult}
    :threshold 2
    :diagnosis "Test values are symmetric or use identity values (0, 1) where operations produce same result"
    :fix "Use asymmetric, non-identity values that distinguish operations"
    :example "(is (= 15 (calc 5 3)))  ; 5*3=15, but 5/3\u22601"}

   :increment-decrement
   {:operators #{:swap-inc-dec :swap-dec-inc}
    :threshold 1
    :diagnosis "Tests don't verify exact numeric results"
    :fix "Assert on exact values, especially at boundaries"
    :example "(is (= 6 (next-index 5)))  ; not just (pos? ...)"}

   :nil-return
   {:operators #{:replace-nil-false :replace-nil-zero :replace-nil-empty-vec
                 :replace-nil-empty-map :replace-nil-empty-str}
    :threshold 1
    :diagnosis "Tests don't verify return values, or nil handling isn't tested"
    :fix "Assert on actual return values, not just side effects"
    :example "(is (= expected-result (process x)))  ; verify return"}

   :sequence-operations
   {:operators #{:swap-take-drop :swap-drop-take :swap-rest-next :swap-next-rest}
    :threshold 1
    :diagnosis "Collection sizes or nil handling masks operation differences"
    :fix "Use varied collection sizes; test empty collection behavior"
    :example "(is (= [1 2] (take-n 2 [1 2 3 4 5])))  ; clear difference"}

   :equality-swap
   {:operators #{:swap-eq-neq :swap-neq-eq}
    :threshold 1
    :diagnosis "Tests don't distinguish between equality and inequality checks"
    :fix "Test both matching and non-matching cases"
    :example "(is (found? {:id 1} {:id 1})) (is (not (found? {:id 1} {:id 2})))"}

   :threading-direction
   {:operators #{:swap-thread-first-last :swap-thread-last-first}
    :threshold 1
    :diagnosis "Threading direction doesn't matter for the functions used (single-arity or symmetric)"
    :fix "Ensure threaded functions are position-sensitive"
    :example "(-> m (assoc :a 1) (get :a))  ; position matters"}

   :filter-semantics
   {:operators #{:swap-filter-remove :swap-remove-filter}
    :threshold 1
    :diagnosis "Tests don't verify which elements are kept vs removed"
    :fix "Assert on exact filtered results, not just count or presence"
    :example "(is (= [2 4] (get-evens [1 2 3 4])))  ; exact result"}

   :lazy-eager
   {:operators #{:swap-map-mapv :swap-mapv-map :swap-filter-filterv :swap-filterv-filter}
    :threshold 2
    :diagnosis "Lazy vs eager semantics don't affect test outcomes"
    :fix "If laziness matters, test for it explicitly; otherwise may be equivalent"
    :example "Consider if lazy evaluation is actually important here"}

   :nil-some-swap
   {:operators #{:swap-nil-some :swap-some-nil :swap-seq-empty :swap-empty-seq}
    :threshold 1
    :diagnosis "Tests don't distinguish nil checks from existence checks"
    :fix "Test with nil, empty collections, and populated collections separately"
    :example "(is (nil? (get-optional nil))) (is (some? (get-optional {})))"}})

;; =============================================================================
;; Diagnosis Logic
;; =============================================================================

(defn- count-by-operator
  "Count survivors by operator ID."
  [survivors]
  (frequencies (map #(get-in % [:mutation :operator]) survivors)))

(defn- detect-pattern
  "Check if a pattern is triggered by the survivor counts.
   Returns the pattern with :count if triggered, nil otherwise."
  [operator-counts [pattern-id pattern-def]]
  (let [{:keys [operators threshold]} pattern-def
        threshold (or threshold 1)
        matching-count (reduce + 0 (keep #(get operator-counts %) operators))]
    (when (>= matching-count threshold)
      (assoc pattern-def
             :pattern-id pattern-id
             :count matching-count
             :matching-operators (filterv #(contains? operator-counts %) operators)))))

(defn diagnose-survivors
  "Analyze surviving mutations and identify patterns.

   Arguments:
   - survivors: Sequence of survivor results (with :mutation containing :operator)

   Returns map with:
   - :total-survivors - Count of survivors analyzed
   - :patterns - Vector of detected patterns with diagnosis
   - :undiagnosed-count - Survivors not matching any pattern
   - :operator-counts - Raw operator frequency map"
  [survivors]
  (if (empty? survivors)
    {:total-survivors 0
     :patterns []
     :undiagnosed-count 0
     :operator-counts {}}
    (let [operator-counts (count-by-operator survivors)
          detected (keep #(detect-pattern operator-counts %) patterns)
          ;; Sort by count descending
          sorted-patterns (vec (sort-by #(- (:count %)) detected))
          ;; Count operators covered by patterns
          diagnosed-operators (into #{} (mapcat :operators (vals patterns)))
          diagnosed-count (reduce + 0 (keep #(get operator-counts %)
                                            (into #{} (mapcat :matching-operators sorted-patterns))))
          total-survivors (count survivors)]
      {:total-survivors total-survivors
       :patterns sorted-patterns
       :undiagnosed-count (- total-survivors diagnosed-count)
       :operator-counts operator-counts})))

;; =============================================================================
;; Formatting
;; =============================================================================

(defn format-diagnosis-terminal
  "Format diagnosis for terminal output.
   Returns a string ready for printing."
  [{:keys [total-survivors patterns undiagnosed-count]}]
  (if (empty? patterns)
    nil
    (let [lines (atom [])]
      (swap! lines conj "")
      (swap! lines conj "Diagnosis")
      (swap! lines conj (apply str (repeat 60 "-")))
      (swap! lines conj "")
      (doseq [{:keys [pattern-id count diagnosis fix example]} patterns]
        (swap! lines conj (format "  [%d survivors] %s" count (name pattern-id)))
        (swap! lines conj (format "    Problem:  %s" diagnosis))
        (swap! lines conj (format "    Fix:      %s" fix))
        (when example
          (swap! lines conj (format "    Example:  %s" example)))
        (swap! lines conj ""))
      (when (pos? undiagnosed-count)
        (swap! lines conj (format "  [%d survivors] Other patterns (see docs/interpreting-survivors.md)"
                                  undiagnosed-count)))
      (str/join "\n" @lines))))

(defn format-diagnosis-html
  "Format diagnosis as HTML hiccup structure."
  [{:keys [patterns undiagnosed-count]}]
  (when (seq patterns)
    [:section.diagnosis
     [:h2 "Diagnosis"]
     [:p "Common patterns detected in surviving mutations:"]
     [:div.diagnosis-list
      (for [{:keys [pattern-id count diagnosis fix example]} patterns]
        [:div.diagnosis-item
         [:h3 (str (name pattern-id) " (" count " survivors)")]
         [:p [:strong "Problem: "] diagnosis]
         [:p [:strong "Fix: "] fix]
         (when example
           [:pre.example example])])]
     (when (pos? undiagnosed-count)
       [:p.undiagnosed
        (str undiagnosed-count " survivors don't match known patterns. "
             "See docs/interpreting-survivors.md for manual analysis.")])]))

(defn format-diagnosis-data
  "Format diagnosis as data (for JSON/EDN).
   Returns map suitable for serialization."
  [{:keys [total-survivors patterns undiagnosed-count operator-counts]}]
  {:total-survivors total-survivors
   :diagnosed-patterns
   (mapv (fn [{:keys [pattern-id count diagnosis fix matching-operators]}]
           {:pattern (name pattern-id)
            :count count
            :diagnosis diagnosis
            :fix fix
            :operators (mapv name matching-operators)})
         patterns)
   :undiagnosed-count undiagnosed-count
   :operator-breakdown
   (into {} (map (fn [[k v]] [(name k) v]) operator-counts))})
