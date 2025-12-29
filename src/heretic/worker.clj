(ns heretic.worker
  "Missionary-based worker supervision for mutation testing.

   Provides reliable timeout, configurable parallelism, and failure supervision
   for mutation testing workloads.

   Key features:
   - Reliable timeout via Missionary task cancellation (not just future abandonment)
   - Configurable parallelism with file-level coordination
   - Progress reporting via callbacks
   - Failure supervision policies: :skip, :retry, :abort

   Naming conventions (Missionary standard):
   - `?` prefix = Task (single async result)
   - `>` prefix = Discrete flow (events)
   - `<` prefix = Continuous flow (state)

   Main entry point:
   - `?run-mutation-testing` - Run full mutation testing with supervision

   Example:
   ```clojure
   (m/? (?run-mutation-testing
          {:index coverage-index
           :mutations all-mutations
           :parallelism 4
           :timeout-ms 30000
           :on-progress (fn [progress result] (println progress))}))
   ```"
  (:require [heretic.mutation-engine :as engine]
            [heretic.reloader :as reloader]
            [heretic.runner :as runner]
            [missionary.core :as m]))

;; =============================================================================
;; File Locking for Mutation Safety
;; =============================================================================

;; Map of file paths to lock objects for coordination
(defonce ^:private !file-locks (atom {}))

(defn- get-file-lock
  "Get or create a lock object for a file path."
  [file-path]
  (or (get @!file-locks file-path)
      (let [lock (Object.)]
        (swap! !file-locks
               (fn [locks]
                 (if (contains? locks file-path)
                   locks
                   (assoc locks file-path lock))))
        (get @!file-locks file-path))))

;; =============================================================================
;; Single Mutation Execution
;; =============================================================================

(defn- evaluate-mutation-impl
  "Implementation of mutation evaluation.
   Applies mutation, reloads namespace, runs tests, reverts mutation.

   This is the core work unit - runs synchronously within a worker."
  [index mutation config]
  (engine/with-mutation [applied mutation]
    (let [reload-result (reloader/reload!)]
      (if (:success reload-result)
        (runner/evaluate-mutation index applied config)
        {:mutation applied
         :status :error
         :tests-run #{}
         :timed-out #{}
         :killed-by nil
         :test-durations {}
         :duration-ms 0
         :error-message (str "Reload failed: " (:error reload-result))}))))

(defn ?execute-mutation
  "Execute a single mutation as a cancellable task with timeout.

   The mutation is executed on the blocking thread pool (m/blk) since it
   involves file I/O and test execution. The entire operation is wrapped
   in a timeout - if exceeded, the task is cancelled (not just abandoned).

   Arguments:
   - index: Coverage index for test lookup
   - mutation: Mutation record with :file, :form-id, :coord, :operator
   - config: Configuration map with :timeout-ms, :timing-data

   Returns Task that yields MutationResult:
   {:mutation <mutation>
    :status :killed/:survived/:no-coverage/:timeout/:error
    :killed-by <test-sym> or nil
    :duration-ms <ms>}"
  [index mutation config]
  (let [timeout-ms (or (:mutation-timeout-ms config)
                       (:timeout-ms config)
                       30000)
        file-path (:file mutation)
        file-lock (get-file-lock file-path)]
    (m/sp
     (m/?
      (m/timeout
       (m/via m/blk
              ;; Synchronize access to the same file
              (locking file-lock
                (try
                  (evaluate-mutation-impl index mutation config)
                  (catch Exception e
                    {:mutation mutation
                     :status :error
                     :tests-run #{}
                     :timed-out #{}
                     :killed-by nil
                     :test-durations {}
                     :duration-ms 0
                     :error-message (ex-message e)}))))
       timeout-ms
       {:mutation mutation
        :status :timeout
        :tests-run #{}
        :timed-out #{}
        :killed-by nil
        :test-durations {}
        :duration-ms timeout-ms})))))

;; =============================================================================
;; Supervision Policies
;; =============================================================================

(defn ?with-supervision
  "Wrap a task with supervision policy.

   Policies:
   - :skip - On error, return error result and continue (default)
   - :retry - Retry up to :max-retries times before skipping
   - :abort - Let exception propagate, stopping the run

   Arguments:
   - ?task: The task to supervise
   - policy: Keyword (:skip, :retry, :abort) or map with :policy and options

   Returns supervised task."
  [?task policy]
  (let [{:keys [policy max-retries]
         :or {policy (if (keyword? policy) policy :skip)
              max-retries 3}}
        (if (map? policy) policy {:policy policy})]
    (case policy
      :skip
      (m/sp
       (try
         (m/? ?task)
         (catch Exception e
           {:status :error
            :error-message (ex-message e)})))

      :retry
      (m/sp
       (loop [attempts 0]
         (let [result (try
                        {:ok (m/? ?task)}
                        (catch Exception e
                          (if (< attempts max-retries)
                            {:retry true :error e}
                            {:error e})))]
           (cond
             (:ok result) (:ok result)
             (:retry result) (recur (inc attempts))
             :else {:status :error
                    :error-message (ex-message (:error result))}))))

      :abort
      ?task)))

;; =============================================================================
;; Parallel Worker Pool
;; =============================================================================

(defn ?run-workers-sequential
  "Run mutations sequentially (no parallelism).
   Useful for debugging or when file conflicts are a concern.

   Arguments:
   - mutations: Sequence of mutations to process
   - index: Coverage index
   - config: Configuration including :on-progress callback

   Returns Task yielding vector of results."
  [mutations index config]
  (let [on-progress (:on-progress config identity)
        total (count mutations)
        supervision-policy (or (:supervision config) :skip)]
    (m/sp
     (loop [remaining mutations
            completed 0
            results []]
       (if (empty? remaining)
         results
         (let [mutation (first remaining)
               ?task (?execute-mutation index mutation config)
               ?supervised (?with-supervision ?task supervision-policy)
               result (m/? ?supervised)
               new-completed (inc completed)]
           (on-progress {:completed new-completed
                         :total total
                         :percent (int (* 100.0 (/ new-completed total)))}
                        result)
           (recur (rest remaining)
                  new-completed
                  (conj results result))))))))

(defn ?process-file-mutations
  "Process all mutations for a single file sequentially.

   This is the core work unit for file-level parallelism. All mutations
   for one file are processed in sequence to avoid file modification conflicts.

   Arguments:
   - file-mutations: Vector of mutations for a single file
   - index: Coverage index
   - config: Configuration including :on-progress, :supervision
   - !completed: Atom for tracking global progress
   - total: Total mutation count across all files

   Returns Task yielding vector of results for this file."
  [file-mutations index config !completed total]
  (let [on-progress (:on-progress config (fn [_ _] nil))
        supervision-policy (or (:supervision config) :skip)]
    (m/sp
     (loop [remaining file-mutations
            results []]
       (if (empty? remaining)
         results
         (let [mutation (first remaining)
               ?task (?execute-mutation index mutation config)
               ?supervised (?with-supervision ?task supervision-policy)
               result (m/? ?supervised)
               completed (swap! !completed inc)]
           (on-progress {:completed completed
                         :total total
                         :percent (int (* 100.0 (/ completed total)))}
                        result)
           (recur (rest remaining)
                  (conj results result))))))))

(defn ?run-workers-parallel
  "Run mutations with true file-level parallelism using Missionary flows.

   Mutations are grouped by file. Files are processed concurrently up to
   the parallelism limit, but mutations within the same file are processed
   sequentially. This prevents file conflicts while maximizing throughput.

   Uses m/ap for true concurrent ambiguous process execution rather than
   batch-based processing. This means workers continuously pull new file
   groups as they complete, rather than waiting for batch boundaries.

   Arguments:
   - mutations: Sequence of mutations to process
   - index: Coverage index
   - config: Configuration including:
     - :parallelism - Number of concurrent file processors (default: CPU count)
     - :on-progress - Progress callback (fn [progress-map result])
     - :supervision - :skip, :retry, or :abort (default: :skip)

   Returns Task yielding vector of results."
  [mutations index config]
  (let [n-workers (or (:parallelism config)
                      (.availableProcessors (Runtime/getRuntime)))
        ;; Group mutations by file and sort by mutation count (largest first)
        ;; for better work distribution
        by-file (group-by :file mutations)
        file-groups (->> by-file
                         vals
                         (sort-by #(- (count %)))
                         vec)
        total (count mutations)
        !completed (atom 0)]
    (if (empty? file-groups)
      (m/sp [])
      (m/sp
       ;; Use m/ap with m/amb= for true parallel file processing
       ;; Each "lane" processes file groups concurrently.
       ;; m/amb= forks into n-workers parallel branches,
       ;; each branch then forks for each file group from the flow.
       (let [;; Create a flow that emits file-mutation groups
             >file-groups (m/seed file-groups)
             ;; Process all file groups with bounded parallelism
             ;; m/ap creates an ambiguous process that forks for each value
             ;; m/amb= (non-backpressured fork) creates parallel "lanes"
             <results
             (m/ap
              ;; Fork into n-workers parallel lanes
              (m/amb= (range n-workers))
              ;; Each lane pulls file groups from the shared flow
              ;; m/?> consumes from the discrete flow
              (let [file-mutations (m/?> >file-groups)]
                (m/? (?process-file-mutations
                      file-mutations index config !completed total))))]
         ;; Collect all file results into a single vector
         (m/? (m/reduce into [] <results)))))))

;; =============================================================================
;; Result Aggregation
;; =============================================================================

(defn summarize-results
  "Summarize mutation testing results.

   Arguments:
   - results: Sequence of MutationResult maps

   Returns summary map with counts and mutation score."
  [results]
  (let [by-status (group-by :status results)
        killed (count (get by-status :killed []))
        survived (count (get by-status :survived []))
        testable (+ killed survived)
        tests-timed-out (reduce + 0 (map #(count (:timed-out % #{})) results))]
    {:total (count results)
     :killed killed
     :survived survived
     :no-coverage (count (get by-status :no-coverage []))
     :timeout (count (get by-status :timeout []))
     :error (count (get by-status :error []))
     :tests-timed-out tests-timed-out
     :mutation-score (if (pos? testable)
                       (double (/ killed testable))
                       1.0)
     :total-duration-ms (reduce + 0 (map #(:duration-ms % 0) results))
     :survivors (filterv #(= :survived (:status %)) results)}))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn ?run-mutation-testing
  "Main entry point for Missionary-based mutation testing.

   Runs mutation testing with configurable parallelism, timeout, and supervision.

   Arguments:
   - config: Configuration map with:
     - :index - Coverage index (required)
     - :mutations - Sequence of mutations to test (required)
     - :parallelism - Number of concurrent workers (default: CPU count)
     - :parallel? - Enable parallel execution (default: true)
     - :mutation-timeout-ms - Per-mutation timeout (default: 30000)
     - :timeout-ms - Per-test timeout, passed to runner (default: 5000)
     - :supervision - :skip, :retry, or :abort (default: :skip)
     - :on-progress - Callback (fn [progress-map result]) for progress

   Returns Task yielding summary map:
   {:total n
    :killed n
    :survived n
    :no-coverage n
    :timeout n
    :error n
    :mutation-score 0.75
    :survivors [...]}"
  [{:keys [index mutations parallel?]
    :or {parallel? true}
    :as config}]
  (when-not index
    (throw (ex-info "Missing :index in config" {:config (dissoc config :mutations)})))
  (when-not mutations
    (throw (ex-info "Missing :mutations in config" {:config (dissoc config :index)})))

  (m/sp
   (let [results (if parallel?
                   (m/? (?run-workers-parallel mutations index config))
                   (m/? (?run-workers-sequential mutations index config)))]
     (summarize-results results))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn run-mutation-testing!
  "Synchronous wrapper around ?run-mutation-testing.

   Blocks until all mutations are tested. Useful for REPL and scripts.

   Arguments: same as ?run-mutation-testing

   Returns summary map."
  [config]
  (m/? (?run-mutation-testing config)))

(defn run-with-timeout!
  "Run mutation testing with an overall timeout.

   Arguments:
   - config: Same as ?run-mutation-testing
   - overall-timeout-ms: Maximum time for entire run

   Returns summary map, or {:status :overall-timeout} if exceeded."
  [config overall-timeout-ms]
  (m/?
   (m/timeout
    (?run-mutation-testing config)
    overall-timeout-ms
    {:status :overall-timeout
     :timeout-ms overall-timeout-ms})))

;; =============================================================================
;; Progress Helpers
;; =============================================================================

(defn print-progress
  "Default progress callback that prints to stdout."
  [{:keys [completed total percent]} result]
  (let [status-char (case (:status result)
                      :killed "."
                      :survived "S"
                      :no-coverage "N"
                      :timeout "T"
                      :error "E"
                      "?")]
    (print status-char)
    (when (zero? (mod completed 50))
      (println (format " [%3d%%]" percent)))
    (flush)))

(defn make-progress-callback
  "Create a progress callback that updates an atom.

   Returns [!progress-atom callback-fn]"
  []
  (let [!progress (atom {:completed 0 :total 0 :results []})]
    [!progress
     (fn [progress result]
       (swap! !progress
              (fn [p]
                (-> p
                    (assoc :completed (:completed progress))
                    (assoc :total (:total progress))
                    (update :results conj result)))))]))
