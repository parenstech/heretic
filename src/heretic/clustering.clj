(ns heretic.clustering
  "Mutant clustering for mutation testing optimization.

   Mutant clustering groups similar mutations together and tests only a
   representative from each cluster. If the representative is killed, all
   clustered mutants are assumed killed.

   Clustering Strategies:
   - :operator   - Group mutations by operator type
   - :location   - Group mutations at same code location
   - :similarity - Group mutations with similar code patterns

   Key Concepts:
   - Cluster: A group of related mutations with one representative
   - Representative: The mutation selected to represent the cluster (hardest to kill)
   - Inferred Results: Results for non-representative mutations derived from representative

   Main API:
   - `cluster-mutations` - Group mutations by strategy
   - `select-representative` - Pick the hardest-to-kill mutation from cluster
   - `infer-cluster-results` - Derive results for cluster from representative
   - `expand-cluster-results` - Expand clustered results to individual mutations

   Architecture:
   - Pure functions for clustering algorithm (functional core)
   - Pure functions for selecting representatives
   - Integration with controller.clj for execution layer"
  (:require [heretic.subsumption :as subsumption]))

;; =============================================================================
;; Cluster Identity Functions
;; =============================================================================

(defn- mutation-location
  "Extract location identity for a mutation.
   Location is defined by file, line, and form-id."
  [mutation]
  (select-keys mutation [:file :line :form-id]))

(defn- mutation-context
  "Extract context identity for a mutation.
   Context includes the parent form type and position."
  [mutation]
  (let [{:keys [file form-id coord]} mutation
        ;; Use first coord element to identify parent context
        parent-coord (when (and (vector? coord) (pos? (count coord)))
                       (subvec coord 0 (max 1 (dec (count coord)))))]
    {:file file
     :form-id form-id
     :parent-coord parent-coord}))

;; =============================================================================
;; Clustering Strategies (Pure Functions)
;; =============================================================================

(defmulti cluster-mutations
  "Group mutations according to the specified strategy.

   Arguments:
   - mutations: Sequence of mutation records
   - strategy: Clustering strategy keyword

   Returns map of {cluster-id -> [mutations]}

   Strategies:
   - :none      - No clustering, each mutation is its own cluster
   - :operator  - Group by operator type (e.g., all :swap-plus-minus together)
   - :location  - Group by code location (file + line + form-id)
   - :similarity - Group by similar code patterns (operator + location context)"
  (fn [_mutations strategy] strategy))

(defmethod cluster-mutations :none
  [mutations _strategy]
  ;; Each mutation is its own cluster
  (into {}
        (map-indexed (fn [idx m]
                       [(str "single-" idx) [m]])
                     mutations)))

(defmethod cluster-mutations :operator
  [mutations _strategy]
  ;; Group mutations by operator type
  (let [grouped (group-by :operator mutations)]
    (reduce-kv
     (fn [acc op-id muts]
       (assoc acc (str "operator-" (name op-id)) muts))
     {}
     grouped)))

(defmethod cluster-mutations :location
  [mutations _strategy]
  ;; Group mutations by location (file + line + form-id)
  (let [grouped (group-by mutation-location mutations)]
    (reduce-kv
     (fn [acc loc muts]
       (let [cluster-id (str "loc-" (:file loc) "-" (:line loc) "-" (:form-id loc))]
         (assoc acc cluster-id muts)))
     {}
     grouped)))

(defmethod cluster-mutations :similarity
  [mutations _strategy]
  ;; Group mutations by similarity:
  ;; - Same operator on same parent form type
  ;; - Consider subsumption relationships
  (let [;; First group by operator category + location context
        grouped (group-by
                 (fn [m]
                   (let [ctx (mutation-context m)
                         ;; Get operator category from subsumption data
                         op-cat (some (fn [[cat ops]]
                                        (when (contains? ops (:operator m))
                                          cat))
                                      subsumption/operator-categories)]
                     {:category (or op-cat :other)
                      :context ctx}))
                 mutations)]
    (reduce-kv
     (fn [acc {:keys [category context]} muts]
       (let [cluster-id (str "sim-" (name category) "-"
                             (:file context) "-"
                             (:form-id context) "-"
                             (hash (:parent-coord context)))]
         (assoc acc cluster-id muts)))
     {}
     grouped)))

(defmethod cluster-mutations :default
  [_mutations strategy]
  (throw (ex-info "Unknown clustering strategy"
                  {:strategy strategy
                   :available [:none :operator :location :similarity]})))

;; =============================================================================
;; Representative Selection (Pure Functions)
;; =============================================================================

(def ^:private operator-hardness
  "Ranking of operators by difficulty to kill (higher = harder).

   Based on mutation testing research:
   - Boundary mutations are harder to kill than simple swaps
   - Extreme replacements (true/false) are moderately difficult
   - Simple operator swaps are easier to detect"
  {;; Boundary mutations (hard)
   :swap-lt-lte 90
   :swap-gt-gte 90
   :swap-lte-lt 90
   :swap-gte-gt 90
   ;; Nil-handling (hard - subtle bugs)
   :swap-rest-next 85
   :swap-next-rest 85
   :swap-nil-some 85
   :swap-some-nil 85
   ;; RORG mutations (medium-hard)
   :swap-lt-neq 80
   :swap-gt-neq 80
   :swap-lte-eq 80
   :swap-gte-eq 80
   :swap-eq-lte 80
   :swap-eq-gte 80
   :swap-neq-lt 80
   :swap-neq-gt 80
   ;; Extreme replacements (medium)
   :replace-comparison-false 75
   :replace-comparison-true 75
   :replace-and-false 75
   :replace-or-true 75
   ;; Boolean swaps (medium)
   :swap-and-or 70
   :swap-or-and 70
   :remove-not 70
   ;; Collection operations (medium)
   :swap-first-last 65
   :swap-last-first 65
   :swap-take-drop 65
   :swap-drop-take 65
   ;; Simple swaps (easier)
   :swap-lt-gt 50
   :swap-gt-lt 50
   :swap-eq-neq 50
   :swap-neq-eq 50
   :swap-plus-minus 50
   :swap-minus-plus 50
   :swap-mult-div 50
   :swap-div-mult 50
   ;; Lazy/eager (easy to detect with side effects)
   :swap-map-mapv 40
   :swap-mapv-map 40
   :swap-filter-filterv 40
   :swap-filterv-filter 40
   ;; Default for unknown operators
   :default 50})

(defn- operator-hardness-score
  "Get the hardness score for an operator.
   Higher scores indicate mutations that are harder to kill."
  [operator]
  (get operator-hardness operator (get operator-hardness :default)))

(defn- dominator-score
  "Calculate a score based on subsumption dominance.
   Dominating operators should be preferred as representatives."
  [operator]
  (let [dominated-count (count (subsumption/dominated-operators operator))
        dominating-count (count (subsumption/dominating-operators operator))]
    ;; Prefer operators that dominate others and are not dominated
    (+ (* 10 dominated-count)
       (- (* 5 dominating-count)))))

(defn mutation-difficulty-score
  "Calculate overall difficulty score for a mutation.
   Higher scores indicate mutations that are harder to kill.

   Factors:
   - Operator hardness (based on research)
   - Subsumption dominance (dominators are harder to kill)

   Arguments:
   - mutation: Mutation record with :operator

   Returns numeric score."
  [mutation]
  (let [op (:operator mutation)]
    (+ (operator-hardness-score op)
       (dominator-score op))))

(defn select-representative
  "Select the 'hardest to kill' mutation from a cluster.

   The representative is chosen based on:
   1. Operator hardness (boundary mutations are harder)
   2. Subsumption relationships (dominators are preferred)

   If the representative is killed, all clustered mutants are assumed killed.
   If the representative survives, the cluster survives.

   Arguments:
   - cluster: Sequence of mutations in the cluster

   Returns the selected representative mutation."
  [cluster]
  (when (seq cluster)
    (->> cluster
         (sort-by mutation-difficulty-score >)
         first)))

;; =============================================================================
;; Result Inference (Pure Functions)
;; =============================================================================

(defn infer-cluster-results
  "Infer results for all mutations in a cluster based on representative result.

   If the representative was killed, all mutations in the cluster are considered killed.
   If the representative survived, all mutations in the cluster are considered survived.

   Arguments:
   - representative-result: Result map for the representative mutation
   - cluster: Sequence of all mutations in the cluster (including representative)

   Returns sequence of result maps for all mutations in the cluster."
  [representative-result cluster]
  (let [{:keys [status killed-by test-durations tests-run]} representative-result
        rep-mutation (:mutation representative-result)]
    (for [mutation cluster]
      (if (= mutation rep-mutation)
        ;; Representative keeps its actual result
        representative-result
        ;; Non-representatives get inferred results
        {:mutation mutation
         :status status
         :killed-by (when (= :killed status) killed-by)
         :inferred? true
         :inferred-from (:id rep-mutation)
         :test-durations test-durations
         :tests-run tests-run}))))

(defn expand-cluster-results
  "Expand cluster results to individual mutation results.

   Takes a map of cluster results (keyed by cluster-id) and expands them
   to a flat sequence of individual mutation results.

   Arguments:
   - cluster-results: Map of {cluster-id -> {:representative-result ... :cluster [...]}}

   Returns sequence of mutation result maps."
  [cluster-results]
  (mapcat
   (fn [[_cluster-id {:keys [representative-result cluster]}]]
     (infer-cluster-results representative-result cluster))
   cluster-results))

;; =============================================================================
;; Cluster Analysis (Pure Functions)
;; =============================================================================

(defn cluster-stats
  "Calculate statistics about a clustering.

   Arguments:
   - clusters: Map of {cluster-id -> [mutations]}

   Returns map with:
   - :total-mutations - Total number of mutations
   - :cluster-count - Number of clusters
   - :representatives-count - Number of representatives to test
   - :reduction-percentage - Percentage reduction in tests needed
   - :avg-cluster-size - Average mutations per cluster
   - :max-cluster-size - Largest cluster size
   - :single-mutation-clusters - Number of clusters with only one mutation"
  [clusters]
  (let [sizes (map count (vals clusters))
        total (reduce + sizes)
        cluster-count (count clusters)
        single-clusters (count (filter #(= 1 %) sizes))]
    {:total-mutations total
     :cluster-count cluster-count
     :representatives-count cluster-count
     :reduction-percentage (if (pos? total)
                             (* 100.0 (/ (- total cluster-count) total))
                             0.0)
     :avg-cluster-size (if (pos? cluster-count)
                         (double (/ total cluster-count))
                         0.0)
     :max-cluster-size (if (seq sizes) (apply max sizes) 0)
     :single-mutation-clusters single-clusters}))

(defn extract-representatives
  "Extract representative mutations from all clusters.

   Arguments:
   - clusters: Map of {cluster-id -> [mutations]}

   Returns map of {cluster-id -> {:representative mutation :cluster [mutations]}}"
  [clusters]
  (reduce-kv
   (fn [acc cluster-id mutations]
     (assoc acc cluster-id
            {:representative (select-representative mutations)
             :cluster mutations}))
   {}
   clusters))

;; =============================================================================
;; Clustering Workflow (Pure Functions)
;; =============================================================================

(defn prepare-clustered-mutations
  "Prepare mutations for clustered testing.

   This is the main entry point for clustering workflow.

   Arguments:
   - mutations: Sequence of mutation records
   - strategy: Clustering strategy (:none, :operator, :location, :similarity)

   Returns map with:
   - :clusters - Map of {cluster-id -> [mutations]}
   - :representatives - Map of {cluster-id -> {:representative ... :cluster ...}}
   - :to-test - Sequence of representative mutations to test
   - :stats - Clustering statistics"
  [mutations strategy]
  (let [clusters (cluster-mutations mutations strategy)
        representatives (extract-representatives clusters)
        to-test (mapv :representative (vals representatives))]
    {:clusters clusters
     :representatives representatives
     :to-test to-test
     :stats (cluster-stats clusters)}))

(defn apply-results-to-clusters
  "Apply test results to clusters and expand to all mutations.

   Arguments:
   - representatives: Map from prepare-clustered-mutations
   - results: Sequence of result maps for representative mutations

   Returns sequence of result maps for all mutations (including inferred)."
  [representatives results]
  (let [;; Index results by mutation id
        results-by-id (into {} (map (juxt #(get-in % [:mutation :id]) identity) results))
        ;; Map results back to clusters
        cluster-results
        (reduce-kv
         (fn [acc cluster-id {:keys [representative cluster]}]
           (let [rep-result (get results-by-id (:id representative))]
             (if rep-result
               (assoc acc cluster-id {:representative-result rep-result
                                      :cluster cluster})
               ;; No result for representative - shouldn't happen
               acc)))
         {}
         representatives)]
    (expand-cluster-results cluster-results)))

;; =============================================================================
;; Strategy Selection Helpers
;; =============================================================================

(def strategy-descriptions
  "Descriptions of available clustering strategies."
  {:none "No clustering - test every mutation individually"
   :operator "Group by operator type - fast, moderate reduction"
   :location "Group by code location - moderate reduction"
   :similarity "Group by similar patterns - best reduction, may miss edge cases"})

(defn recommended-strategy
  "Recommend a clustering strategy based on mutation count.

   Arguments:
   - mutation-count: Number of mutations to test

   Returns recommended strategy keyword."
  [mutation-count]
  (cond
    (< mutation-count 50) :none
    (< mutation-count 200) :location
    (< mutation-count 500) :operator
    :else :similarity))

(defn validate-strategy
  "Validate that a strategy is supported.

   Arguments:
   - strategy: Strategy keyword to validate

   Returns strategy if valid, throws if invalid."
  [strategy]
  (if (contains? #{:none :operator :location :similarity} strategy)
    strategy
    (throw (ex-info "Invalid clustering strategy"
                    {:strategy strategy
                     :valid #{:none :operator :location :similarity}}))))
