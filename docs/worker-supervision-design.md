# Worker Supervision Architecture for Heretic

**Status:** Implemented — see `src/heretic/worker.clj` (Missionary file-level pool) and, for process isolation, `src/heretic/process_worker.clj` / `process_pool.clj` / `runner_process.clj` (shipped in #9). This document is retained as the design rationale.
**Date:** 2025-12-29

## Problem Statement

The current Heretic mutation testing implementation has several limitations:

1. **No timeout protection for infinite loops**: Mutated code can hang forever
2. **No true parallelism**: Current `parallel-mutate` uses Java ExecutorService but mutations still share JVM state
3. **Single bad mutation can hang the entire run**: No isolation between mutations
4. **Future-based cancellation is unreliable**: `future-cancel` doesn't reliably stop blocking operations

## Goals

1. Workers that can be truly cancelled on timeout
2. Clean Missionary-based reactive architecture
3. Configurable parallelism with proper work distribution
4. Graceful handling of worker failures (restart/skip policies)
5. Clear separation between Controller (orchestration) and Workers (execution)

## Missionary Architecture Overview

```
                    Controller (Main Process)
                    ========================
                           |
              +------------+------------+
              |            |            |
        [Worker 1]    [Worker 2]   [Worker N]
        (Isolated)    (Isolated)   (Isolated)
```

### Core Abstractions

| Abstraction | Missionary Type | Purpose |
|------------|-----------------|---------|
| Mutation Queue | Discrete Flow (`>`) | Stream of mutations to process |
| Worker | Task (`?`) | Execute single mutation with timeout |
| Result Aggregator | Continuous Flow (`<`) | Accumulated results |
| Supervisor | Task (`?`) | Manage worker lifecycle |

## Design

### 1. Mutation Queue (`>mutations`)

A discrete flow that emits mutations to be processed:

```clojure
(defn >mutations
  "Create a discrete flow of mutations from a collection.
   Supports backpressure - workers pull when ready."
  [mutations]
  (m/seed mutations))
```

### 2. Worker Task (`?execute-mutation`)

Each mutation is executed as a cancellable task with timeout:

```clojure
(defn ?execute-mutation
  "Execute a single mutation as a cancellable task.

   Returns MutationResult:
   {:mutation <mutation>
    :status :killed/:survived/:timeout/:error
    :killed-by <test-sym>
    :duration-ms <ms>}"
  [index mutation config]
  (m/sp
    (m/?
      (m/timeout
        (m/via m/blk
          ;; This runs on a blocking thread pool
          (evaluate-mutation-impl index mutation config))
        (:timeout-ms config 30000)
        {:mutation mutation
         :status :timeout
         :duration-ms (:timeout-ms config 30000)}))))
```

Key design decisions:
- `m/via m/blk` executes on blocking thread pool (IO-bound work)
- `m/timeout` wraps the entire operation for guaranteed termination
- Task can be cancelled mid-execution via Missionary's cancellation

### 3. Worker Pool (`?run-workers`)

Parallel execution with configurable concurrency:

```clojure
(defn ?run-workers
  "Run N workers in parallel consuming from mutation flow.

   Uses m/ap with m/amb= for parallelism - each branch runs
   independently and results are merged."
  [>mutations n-workers config on-result]
  (m/sp
    (m/?
      (m/reduce
        (fn [acc result]
          (on-result result)  ; Callback for progress
          (conj acc result))
        []
        (m/ap
          (let [;; Fork N parallel branches
                _ (m/amb= (range n-workers))
                ;; Each branch pulls from the same mutation flow
                mutation (m/?> >mutations)]
            (m/? (?execute-mutation (:index config) mutation config))))))))
```

Alternative design using `m/join` for bounded parallelism:

```clojure
(defn ?run-workers-batched
  "Process mutations in batches of N.

   More predictable memory usage than unbounded parallelism."
  [mutations n-workers config]
  (m/sp
    (loop [remaining mutations
           results []]
      (if (empty? remaining)
        results
        (let [batch (take n-workers remaining)
              batch-results (m/?
                              (apply m/join vector
                                (map #(?execute-mutation (:index config) % config) batch)))]
          (recur (drop n-workers remaining)
                 (into results batch-results)))))))
```

### 4. Supervision Strategy

Handle failures at the worker level:

```clojure
(defn ?supervised-worker
  "Wrap worker execution with supervision policy.

   Policies:
   - :skip - Return error result, continue with next mutation
   - :retry - Retry N times before skipping
   - :abort - Stop entire run on first error"
  [?task policy]
  (case policy
    :skip
    (m/sp
      (try
        (m/? ?task)
        (catch Exception e
          {:status :error :error (ex-message e)})))

    :retry
    (m/sp
      (loop [attempts 0]
        (let [result (try
                       (m/? ?task)
                       (catch Exception e
                         (when (< attempts (:max-retries policy 3))
                           ::retry)))]
          (if (= result ::retry)
            (recur (inc attempts))
            result))))

    :abort
    ?task))  ; Let exception propagate
```

### 5. Progress Reporting (`<progress`)

Continuous flow of aggregated progress:

```clojure
(defn <progress
  "Continuous flow of mutation testing progress.

   Updates whenever a new result arrives."
  [>results]
  (->> >results
       (m/reductions
         (fn [state result]
           (-> state
               (update :completed inc)
               (update (name (:status result)) (fnil inc 0))
               (update :results conj result)))
         {:completed 0
          :killed 0
          :survived 0
          :timeout 0
          :error 0
          :results []})
       (m/relieve {})))  ; Required for backpressure
```

### 6. Main Entry Point (`?run-mutation-testing`)

```clojure
(defn ?run-mutation-testing
  "Main entry point for Missionary-based mutation testing.

   Config:
   - :parallelism - Number of concurrent workers (default: CPU count)
   - :timeout-ms - Per-mutation timeout (default: 30000)
   - :supervision - :skip, :retry, or :abort (default: :skip)
   - :on-progress - Callback for progress updates"
  [index mutations config]
  (m/sp
    (let [n-workers (or (:parallelism config)
                        (.availableProcessors (Runtime/getRuntime)))
          timeout-ms (or (:timeout-ms config) 30000)

          ;; Create mutation flow
          >muts (>mutations mutations)

          ;; Track progress
          !progress (atom {:completed 0 :total (count mutations)})
          on-result (fn [r]
                      (swap! !progress update :completed inc)
                      (when-let [cb (:on-progress config)]
                        (cb @!progress r)))

          ;; Run workers with supervision
          results (m/? (?run-workers >muts n-workers
                         (assoc config :timeout-ms timeout-ms)
                         on-result))]

      ;; Return summary
      (summarize-results results))))
```

## Timeout Mechanism Deep Dive

The key innovation is using Missionary's cancellation for reliable timeout:

```clojure
(m/timeout ?task duration-ms fallback-value)
```

This works because:
1. Missionary tasks are **interruptible by design**
2. When timeout fires, the task is **cancelled**, not just abandoned
3. Resources are cleaned up via Missionary's structured concurrency

Compare with current `future-deref`:
```clojure
;; CURRENT (unreliable)
(let [f (future (do-mutation))]
  (deref f timeout-ms :timeout))
;; Problem: future continues running even after timeout!

;; MISSIONARY (reliable)
(m/? (m/timeout (m/via m/blk (do-mutation)) timeout-ms :timeout))
;; Task is actually cancelled when timeout fires
```

## File Modification Safety

Mutations modify source files. With parallelism, we need coordination:

### Option A: File-Level Locking (Current Approach)

```clojure
(defn ?execute-mutation-with-lock
  "Execute mutation with file-level coordination.
   Only one mutation per file can be active at a time."
  [index mutation config file-locks]
  (let [file (:file mutation)
        lock (get @file-locks file (Object.))]
    (swap! file-locks assoc file lock)
    (m/via m/blk
      (locking lock
        (evaluate-mutation-impl index mutation config)))))
```

### Option B: Single-Writer Queue Per File

```clojure
(defn group-by-file
  "Partition mutations by file for sequential per-file processing."
  [mutations]
  (vals (group-by :file mutations)))

(defn ?run-file-mutations
  "Process all mutations for a single file sequentially."
  [file-mutations index config]
  (m/sp
    (loop [remaining file-mutations
           results []]
      (if (empty? remaining)
        results
        (let [result (m/? (?execute-mutation index (first remaining) config))]
          (recur (rest remaining)
                 (conj results result)))))))

(defn ?run-parallel-files
  "Process files in parallel, mutations within file sequentially."
  [mutations index config n-workers]
  (let [by-file (group-by-file mutations)]
    (m/sp
      (->> by-file
           (map #(?run-file-mutations % index config))
           (partition-all n-workers)
           (mapcat (fn [batch]
                     (m/? (apply m/join vector batch))))
           vec))))
```

**Recommendation:** Option B is cleaner and matches the current `run-mutations-parallel` approach.

## Integration with Existing Code

### Changes to `heretic.core`

```clojure
;; Add to deps
[missionary.core :as m]
[heretic.worker :as worker]

;; Replace run-mutations-parallel with:
(defn run-mutations-missionary
  "Run mutations using Missionary worker supervision."
  [index mutations config]
  (let [on-progress (fn [progress result]
                      (print-progress (:completed progress)
                                      (:total progress)
                                      (:status result)))]
    (m/?
      (worker/?run-mutation-testing
        index
        mutations
        (assoc config :on-progress on-progress)))))
```

### New Namespace: `heretic.worker`

```clojure
(ns heretic.worker
  "Missionary-based worker supervision for mutation testing.

   Provides:
   - Reliable timeout via task cancellation
   - Configurable parallelism
   - Progress reporting
   - Failure supervision (skip/retry/abort)"
  (:require [missionary.core :as m]
            [heretic.mutation-engine :as engine]
            [heretic.reloader :as reloader]
            [heretic.runner :as runner]))

;; Implementation as designed above
```

## Error Handling

### Mutation Application Errors

```clojure
(defn ?safe-apply-mutation
  "Apply mutation with error handling.
   Returns {:ok mutation-with-backup} or {:error message}."
  [mutation]
  (m/via m/blk
    (try
      {:ok (engine/apply-mutation! mutation)}
      (catch Exception e
        {:error (ex-message e) :mutation mutation}))))
```

### Reload Errors

```clojure
(defn ?safe-reload
  "Reload namespace with error handling."
  []
  (m/via m/blk
    (let [result (reloader/reload!)]
      (if (:success result)
        {:ok result}
        {:error (:error result)}))))
```

### Full Pipeline with Error Handling

```clojure
(defn ?evaluate-mutation-safe
  "Full mutation evaluation with comprehensive error handling."
  [index mutation config]
  (m/sp
    (let [;; Apply mutation
          apply-result (m/? (?safe-apply-mutation mutation))]
      (if (:error apply-result)
        {:mutation mutation
         :status :error
         :error-message (:error apply-result)}

        (try
          ;; Reload and test
          (let [reload-result (m/? (?safe-reload))]
            (if (:error reload-result)
              {:mutation mutation
               :status :error
               :error-message (str "Reload failed: " (:error reload-result))}

              ;; Run tests
              (m/? (?run-tests index (:ok apply-result) config))))

          (finally
            ;; Always revert
            (engine/revert-mutation! (:ok apply-result))))))))
```

## Performance Considerations

### Memory

- Mutations are processed as a flow, not loaded all at once
- Results are aggregated incrementally
- Progress state is minimal (counts, not full history)

### Thread Pools

```clojure
;; Missionary's default executors
m/blk  ; Fixed thread pool for blocking IO
m/cpu  ; Work-stealing pool for CPU-bound work

;; Mutation testing is IO-bound (file read/write, test execution)
;; Use m/blk for most operations
```

### Backpressure

```clojure
;; Workers naturally apply backpressure
;; - They only pull next mutation when ready
;; - No unbounded queuing
;; - Memory stays bounded
```

## Testing Strategy

### Unit Tests

```clojure
(deftest test-worker-timeout
  (let [slow-mutation {:id "slow" :file "test.clj" ...}
        result (m/? (m/timeout
                      (?execute-mutation nil slow-mutation {:timeout-ms 100})
                      200
                      :outer-timeout))]
    (is (= :timeout (:status result)))))

(deftest test-worker-cancellation
  (let [started (atom false)
        completed (atom false)
        task (m/sp
               (reset! started true)
               (m/? (m/sleep 10000))
               (reset! completed true))
        cancel (task #(reset! completed :cancelled) #())]
    (Thread/sleep 100)
    (cancel)
    (is @started)
    (is (not @completed))))
```

### Integration Tests

```clojure
(deftest test-parallel-file-safety
  ;; Verify that mutations to same file don't conflict
  (let [mutations (generate-mutations-for-file "test/fixtures/sample.clj")
        results (m/? (?run-parallel-files mutations index config 4))]
    (is (every? #(not= :error (:status %)) results))))
```

## Migration Path

1. **Phase 1**: Add `heretic.worker` namespace with Missionary implementation
2. **Phase 2**: Add `:use-missionary` config flag to switch between implementations
3. **Phase 3**: Make Missionary the default, deprecate ExecutorService implementation
4. **Phase 4**: Remove old implementation

## Dependencies

Add to `deps.edn`:

```clojure
missionary/missionary {:mvn/version "b.40"}
```

## Open Questions

1. **Process isolation**: Should workers run in separate JVM processes for complete isolation?
   - Pro: True isolation, can kill hung processes
   - Con: More complex, slower startup, harder debugging
   - **Decision**: Started thread-based (Missionary file-level pool, `worker.clj`). ~~add process isolation later if needed~~ — **RESOLVED, shipped in #9**: process isolation is implemented as `:executor :process` (`heretic.process-worker` / `process-pool` / `runner-process`), which forks worker JVMs and `destroyForcibly()`s them on timeout — the only reliable way to reclaim an uninterruptible infinite-loop mutant on the JVM. See this document's header.

2. **State pollution**: Mutated code might pollute global state
   - Current approach: Reload namespace after each mutation
   - Enhanced approach: Reset specific atoms/refs after test
   - **Decision**: Current approach is sufficient for MVP

3. **Test framework integration**: How to handle test framework state?
   - Some test frameworks have global state
   - **Decision**: Document known issues, provide hooks for cleanup

## Summary

This design provides:
- Reliable timeout via Missionary's cancellation semantics
- Clean parallel execution with `m/ap` and `m/amb=`
- Progress reporting via continuous flows
- Failure handling via supervision policies
- Integration with existing Heretic infrastructure

The key insight is using Missionary's **structured concurrency** to ensure:
- Tasks are truly cancellable (not just abandoned)
- Resources are properly cleaned up
- Parallelism is bounded and predictable
