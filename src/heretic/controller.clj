(ns heretic.controller
  "Workflow orchestration for Heretic mutation testing.

   This module provides pure orchestration functions used by core.clj:
   - Operator resolution
   - Mutation preparation (generation + filtering)
   - Mutation clustering for optimization
   - Result aggregation with analysis

   The controller separates pure logic from side effects, following
   the functional core / imperative shell pattern.

   Architecture:
   - controller.clj: Pure orchestration functions
   - core.clj: Entry point with side effects (IO + execution: the :legacy
     in-process ExecutorService file-level parallelism, run-mutations-parallel)
   - runner-process.clj / process-pool.clj: the :process executor (forked worker
     JVMs; the only platform-correct infinite-loop reclaim)

   Key functions:
   - `resolve-operators` - Resolve operators from config
   - `prepare-mutations` - Generate and filter mutations
   - `prepare-clustered-mutations` - Prepare mutations with clustering
   - `aggregate-results` - Combine results with analysis
   - `build-test-config` - Build configuration for test runner"
  (:require [heretic.clustering :as clustering]
            [heretic.coverage-map :as coverage]
            [heretic.equivalent :as equiv]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.parser :as parser]
            [heretic.reporter :as reporter]
            [heretic.runner :as runner]
            [heretic.subsumption :as subsumption]
            [heretic.timing :as timing]))

;; =============================================================================
;; Operator Resolution (Pure)
;; =============================================================================

(defn resolve-operators
  "Resolve operators from config.

   Priority:
   1. If :operators is specified in config, use those
   2. If :preset is specified, use operators for that preset
   3. Default to :standard preset

   When :use-subsumption is true, applies subsumption analysis to reduce
   the operator set to only dominating operators.

   Arguments:
   - config: Configuration map
   - override-operators: Optional operators to use (takes highest priority)

   Returns sequence of operator definitions."
  [config & {:keys [operators]}]
  (let [base-operators
        (cond
          ;; Explicit operators passed as argument take highest priority
          operators
          operators

          ;; Config :operators key (sequence of operator defs or ids)
          (:operators config)
          (let [ops-cfg (:operators config)]
            (if (every? keyword? ops-cfg)
              ;; Sequence of operator ids
              (keep ops/operators-by-id ops-cfg)
              ;; Already operator definitions
              ops-cfg))

          ;; Config :preset key
          (:preset config)
          (ops/operators-for-preset (:preset config))

          ;; Default to :standard preset
          :else
          (ops/operators-for-preset :standard))]
    ;; Apply subsumption filtering if requested
    (if (:use-subsumption config)
      (let [op-ids (set (map :id base-operators))
            minimal-ids (subsumption/minimal-operator-set op-ids)]
        (filter #(contains? minimal-ids (:id %)) base-operators))
      base-operators)))

(defn apply-subsumption-filter
  "Apply subsumption-based filtering to mutations.

   Uses the subsumption graph to filter out mutations whose operators
   are dominated by other operators present in the mutation set.

   Arguments:
   - mutations: Sequence of mutation records
   - config: Configuration with :use-subsumption flag

   Returns map with:
   - :mutations - Filtered mutations
   - :subsumed-count - Count of mutations filtered by subsumption"
  [mutations config]
  (if (:use-subsumption config)
    (subsumption/filter-by-operator-subsumption mutations)
    {:mutations mutations
     :subsumed []
     :subsumed-count 0}))

;; =============================================================================
;; Mutation Generation (Pure)
;; =============================================================================

(defn generate-mutations
  "Generate all mutations for source files.

   Arguments:
   - source-paths: Sequence of source directories
   - operators: Sequence of operator definitions
   - files: Optional specific files to mutate (nil = all)
   - exclude-files: Optional files to exclude from mutation

   Returns lazy sequence of mutations."
  [source-paths operators & {:keys [files exclude-files]}]
  (if files
    ;; When specific files provided, filter out excluded ones
    (let [files-to-mutate (if (seq exclude-files)
                            (remove #(some (fn [excl] (.endsWith % excl)) exclude-files) files)
                            files)]
      (mapcat #(engine/mutations-for-file % operators) files-to-mutate))
    (engine/generate-mutations source-paths operators exclude-files)))

(defn- read-identity-equivalent?
  "SOUND dead-branch check for one mutation: does the mutated top-level form READ
   identically to the original under the JVM reader? (i.e. the mutation is in
   JVM-dead `.cljc` code). Extracts the forms in memory via the mutation engine."
  [mutation]
  (try
    (let [orig (engine/original-form-string mutation)
          mut  (when orig (engine/mutated-form-string mutation orig))]
      (boolean (and orig mut (equiv/read-identical? orig mut))))
    (catch Exception _ false)))

(defn filter-equivalent-mutations
  "Filter out provably/likely equivalent mutations (sound — only drops mutants no
   test could ever kill).

   Two sound passes: (1) the static pattern set (`equivalent.clj`, rarely fires on
   idiomatic code), then (2) read-identity dead-branch detection — the equivalent
   class that actually occurs in Clojure (`#?(:cljs …)` etc.), which gives the
   filter its real recall (docs/validation-results.md §2.2).

   Arguments:
   - mutations: Sequence of mutations to filter
   - filter-enabled?: Whether filtering is enabled

   Returns map with:
   - :mutations - Filtered mutations vector
   - :filtered-count - Number filtered out"
  [mutations filter-enabled?]
  (if filter-enabled?
    (let [zloc-fn (fn [m]
                    (try
                      (when-let [zloc (parser/parse-file (:file m))]
                        (parser/mutation-site->zloc m zloc))
                      (catch Exception _ nil)))
          pat (equiv/filter-equivalent-mutations (vec mutations) zloc-fn)
          db  (group-by read-identity-equivalent? (:mutations pat))]
      {:mutations (vec (get db false []))
       :filtered-count (+ (:filtered-count pat) (count (get db true [])))})
    {:mutations (vec mutations)
     :filtered-count 0}))

(defn prepare-mutations
  "Prepare mutations for testing: generate, filter, and return with metadata.

   This is a pure function that transforms configuration into testable mutations.

   Filtering pipeline:
   1. Generate all mutations from source (excluding :exclude-files)
   2. Filter equivalent mutations (if :filter-equivalent is true)
   3. Filter by operator subsumption (if :use-subsumption is true)

   Arguments:
   - config: Configuration map with :source-paths, :filter-equivalent, :use-subsumption, :exclude-files
   - operators: Resolved operators
   - files: Optional specific files (nil = all in source-paths)

   Returns map with:
   - :mutations - Vector of mutations ready for testing
   - :total-found - Count before filtering
   - :filtered-count - Count removed by equivalent filter
   - :subsumed-count - Count removed by subsumption filter"
  [config operators & {:keys [files]}]
  (let [source-paths (:source-paths config)
        exclude-files (:exclude-files config)
        all-mutations (generate-mutations source-paths operators
                                          :files files
                                          :exclude-files exclude-files)
        all-mutations-vec (vec all-mutations)
        total-found (count all-mutations-vec)
        ;; Step 1: Filter equivalent mutations
        filter-equiv? (:filter-equivalent config true)
        {:keys [mutations filtered-count]}
        (filter-equivalent-mutations all-mutations-vec filter-equiv?)
        ;; Step 2: Apply subsumption filtering
        {:keys [mutations subsumed-count]}
        (apply-subsumption-filter mutations config)]
    {:mutations (vec mutations)
     :total-found total-found
     :filtered-count filtered-count
     :subsumed-count (or subsumed-count 0)}))

;; =============================================================================
;; Test Configuration (Pure)
;; =============================================================================

(defn build-test-config
  "Build test configuration from main config and timing data.

   Arguments:
   - config: Main configuration map
   - timing-data: Historical timing data (or nil)

   Returns map suitable for runner/evaluate-mutation."
  [config timing-data]
  (let [timeout-ms (or (:timeout-ms config) 5000)
        budget-ms (:budget-ms config)]
    (cond-> {:timeout-ms timeout-ms
             :timing-data timing-data}
      budget-ms (assoc :budget-ms budget-ms))))

(defn get-worker-count
  "Get the number of parallel workers to use.

   Arguments:
   - config: Configuration map with :parallel-workers

   Returns worker count (CPU count if not specified)."
  [config]
  (or (:parallel-workers config)
      (.availableProcessors (Runtime/getRuntime))))

;; =============================================================================
;; File-Level Parallelism (Pure)
;; =============================================================================

(defn group-mutations-by-file
  "Group mutations by their source file for parallel processing.

   This enables file-level parallelism: files can be processed concurrently,
   while mutations within each file are processed sequentially to avoid
   file modification conflicts.

   Arguments:
   - mutations: Sequence of mutation records with :file key

   Returns map of file-path -> vector of mutations."
  [mutations]
  (reduce
   (fn [acc mutation]
     (update acc (:file mutation) (fnil conj []) mutation))
   {}
   mutations))

(defn merge-parallel-results
  "Merge results from parallel file workers into a single vector.

   Arguments:
   - file-results: Sequence of vectors, each containing results for one file

   Returns flat vector of all results preserving order within each file."
  [file-results]
  (into [] cat file-results))

(defn balance-file-groups
  "Balance file groups for optimal work distribution across workers.

   Sorts file groups by mutation count (descending) so that larger files
   are processed first. This helps avoid the situation where one worker
   gets stuck with a large file at the end while others are idle.

   Arguments:
   - file-groups: Map of file-path -> mutations vector

   Returns sequence of [file-path mutations] pairs, sorted by mutation count descending."
  [file-groups]
  (->> file-groups
       (sort-by (fn [[_file mutations]] (- (count mutations))))
       vec))

;; =============================================================================
;; Result Aggregation (Pure)
;; =============================================================================

(defn aggregate-results
  "Aggregate mutation testing results with analysis.

   Combines raw results with:
   - Summary statistics
   - Subsumption analysis
   - Survivor details
   - Duration information

   Arguments:
   - results: Sequence of mutation result maps
   - equivalent-filtered: Count of mutations filtered as equivalent
   - start-time-ms: When testing started (for duration calc)
   - subsumed-filtered: Count of mutations filtered by subsumption (optional)

   Returns comprehensive result map."
  [results equivalent-filtered start-time-ms & {:keys [subsumed-filtered]}]
  (let [summary (runner/summarize-results results)
        survivor-list (filter #(= :survived (:status %)) results)
        sub-stats (subsumption/subsumption-stats results)]
    (cond-> (assoc summary
                   :survivors (mapv :mutation survivor-list)
                   :equivalent-filtered equivalent-filtered
                   :subsumption-stats sub-stats
                   :total-duration-ms (- (System/currentTimeMillis) start-time-ms))
      subsumed-filtered (assoc :subsumed-filtered subsumed-filtered))))

(defn extract-test-durations
  "Extract test durations from results for timing data.

   Arguments:
   - results: Sequence of mutation result maps with :test-durations

   Returns merged map of test -> duration."
  [results]
  (reduce (fn [acc result]
            (merge acc (:test-durations result {})))
          {}
          results))

;; =============================================================================
;; Coverage Management (Side Effects)
;; =============================================================================

(defn ensure-coverage!
  "Ensure coverage data exists and is fresh. Collects if needed.

   This is a side-effectful function that may run test collection.

   Arguments:
   - config: Configuration map with :heretic-dir
   - status-fn: Function to get status (fn [config] -> {:stale-namespaces #{}})
   - collect-fn: Function to collect coverage (fn [config & opts] -> result)

   Returns the coverage index, or nil if collection failed."
  [config & {:keys [status-fn collect-fn]
             :or {status-fn (constantly {:stale-namespaces #{}})
                  collect-fn (constantly nil)}}]
  (let [heretic-dir (:heretic-dir config)
        index (coverage/load-index heretic-dir)
        {:keys [stale-namespaces]} (when index (status-fn config))]
    (cond
      ;; No index exists - collect everything
      (nil? index)
      (do
        (reporter/print-phase "No coverage data found, collecting...")
        (collect-fn config)
        (coverage/load-index heretic-dir))

      ;; Index exists but some namespaces are stale
      (seq stale-namespaces)
      (do
        (reporter/print-phase "Coverage data is stale, recollecting...")
        (collect-fn config :namespaces stale-namespaces)
        (coverage/load-index heretic-dir))

      ;; Index exists and everything is fresh
      :else
      (do
        (reporter/print-phase "Coverage data is up to date.")
        index))))

;; =============================================================================
;; Timing Management (Side Effects)
;; =============================================================================

(defn load-timing-data
  "Load historical timing data for test ordering.

   Arguments:
   - heretic-dir: Path to .heretic directory

   Returns timing data map or nil."
  [heretic-dir]
  (timing/load-timing heretic-dir))

(defn save-timing-data!
  "Save timing data from mutation testing run.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - results: Sequence of mutation results with :test-durations"
  [heretic-dir results]
  (let [all-test-durations (extract-test-durations results)]
    (when (seq all-test-durations)
      (timing/record-timing! heretic-dir all-test-durations))))

;; =============================================================================
;; Mutation Clustering (Pure)
;; =============================================================================

(defn resolve-clustering-strategy
  "Resolve clustering strategy from config.

   Arguments:
   - config: Configuration map with :clustering-strategy

   Returns clustering strategy keyword (:none, :operator, :location, :similarity)"
  [config]
  (let [strategy (or (:clustering-strategy config) :none)]
    (clustering/validate-strategy strategy)))

(defn prepare-clustered-mutations
  "Prepare mutations with clustering optimization.

   This function combines mutation preparation with clustering:
   1. Generate and filter mutations (via prepare-mutations)
   2. Apply clustering strategy
   3. Select representatives for testing

   Arguments:
   - config: Configuration map with:
     - :source-paths - Source directories
     - :filter-equivalent - Filter equivalent mutations (default true)
     - :use-subsumption - Use subsumption filtering (default false)
     - :clustering-strategy - Clustering strategy (:none, :operator, :location, :similarity)
   - operators: Resolved operators
   - files: Optional specific files (nil = all)

   Returns map with:
   - :mutations - Vector of all mutations (for reporting)
   - :to-test - Vector of representative mutations to actually test
   - :clusters - Map of cluster-id -> [mutations]
   - :representatives - Map of cluster-id -> {:representative ... :cluster ...}
   - :clustering-stats - Statistics about clustering
   - :total-found - Count before filtering
   - :filtered-count - Count removed by equivalent filter
   - :subsumed-count - Count removed by subsumption filter"
  [config operators & {:keys [files]}]
  (let [;; Step 1: Prepare mutations with existing filtering
        {:keys [mutations total-found filtered-count subsumed-count]}
        (prepare-mutations config operators :files files)

        ;; Step 2: Apply clustering
        strategy (resolve-clustering-strategy config)
        {:keys [clusters representatives to-test stats]}
        (clustering/prepare-clustered-mutations mutations strategy)]

    {:mutations mutations
     :to-test to-test
     :clusters clusters
     :representatives representatives
     :clustering-stats stats
     :clustering-strategy strategy
     :total-found total-found
     :filtered-count filtered-count
     :subsumed-count subsumed-count}))

(defn expand-clustered-results
  "Expand results from testing representatives to all mutations.

   Arguments:
   - representatives: Map from prepare-clustered-mutations
   - results: Sequence of results for representative mutations

   Returns sequence of results for all mutations (including inferred)."
  [representatives results]
  (clustering/apply-results-to-clusters representatives results))

(defn aggregate-clustered-results
  "Aggregate results from clustered mutation testing.

   Similar to aggregate-results but includes clustering statistics.

   Arguments:
   - results: Sequence of mutation result maps (including inferred)
   - equivalent-filtered: Count of mutations filtered as equivalent
   - start-time-ms: When testing started
   - clustering-stats: Statistics from prepare-clustered-mutations
   - subsumed-filtered: Count removed by subsumption (optional)

   Returns comprehensive result map including clustering info."
  [results equivalent-filtered start-time-ms clustering-stats
   & {:keys [subsumed-filtered]}]
  (let [base-results (aggregate-results results equivalent-filtered start-time-ms
                                        :subsumed-filtered subsumed-filtered)
        ;; Separate actual vs inferred results for stats
        inferred-count (count (filter :inferred? results))
        tested-count (- (count results) inferred-count)]
    (assoc base-results
           :clustering-stats clustering-stats
           :tested-count tested-count
           :inferred-count inferred-count)))
