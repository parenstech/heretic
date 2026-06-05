(ns heretic.process-pool
  "Generic N-worker process pool over a shared work queue (B3b).

   This is the multi-worker generalization of heretic.process-worker's pool-of-one
   (`run-requests`). It spawns N child JVMs from per-worker spawn-specs, then feeds
   them a SHARED queue of requests: each idle worker pulls the next request, serves
   it, and pulls again, until the queue is empty. A per-request timeout
   `.destroyForcibly()`-s the hung worker and (under `:on-timeout :respawn`)
   respawns it to keep consuming; the killed request is recorded as a timeout.

   Like heretic.process-worker, this layer is DELIBERATELY generic — it knows
   nothing about Heretic, ClojureStorm, mutations, coverage, or per-worker
   filesystem copies. That keeps the risky systems part (N spawns / shared queue /
   per-worker timeout+kill+respawn / no-orphan / exactly-once dispatch) testable
   under plain `clj` with the trivial echo/spin child (see
   test/heretic/process_pool_test.clj). Layer 2 (heretic.runner-process) supplies
   the per-worker sandbox copies + the key->mutation child + the on-respawn
   source-restore hook on top of this harness.

   ── Protocol ────────────────────────────────────────────────────────────────
   Same newline-delimited EDN protocol as heretic.process-worker: the parent
   writes one request map per line to a worker's stdin; the child writes EXACTLY
   one `{:tag :verdict ...}` line per request to STDOUT (all other output on
   STDERR). Each request is expected to carry a stable `:key`; the verdict echoes
   it. Results are returned KEYED by request key (order-independent), so the
   orchestrator reconciles by key rather than by position — essential because a
   shared queue dispatches work in nondeterministic completion order.

   ── Exactly-once dispatch ───────────────────────────────────────────────────
   The shared queue is drained by a single coordinator lock: each request is
   handed to exactly one worker, and a respawned worker resumes pulling from the
   SAME queue. Every request produces exactly one result (a verdict, or a
   timeout/dead marker), so `run-pool` returns one result per input request — no
   duplicates, no drops.

   ── No orphans ──────────────────────────────────────────────────────────────
   Every spawned child (original + respawns) is tracked in heretic.process-worker's
   shutdown-hook set and destroyed in a `finally`, so an abrupt parent exit leaves
   no runaway worker JVM."
  (:require [heretic.process-worker :as pw])
  (:import [java.util.concurrent ConcurrentLinkedQueue]))

;; =============================================================================
;; Shared-queue pool
;; =============================================================================

(defn- serve-one!
  "Send `request` to worker `w` and read one verdict with a deadline. Returns the
   verdict map, or ::timeout / ::dead (delegates to the same read path the
   pool-of-one uses, via process-worker's private send/read)."
  [w request timeout-ms]
  ((:send! w) request timeout-ms))

(defn run-pool
  "Run `requests` across N workers pulling from ONE shared queue. Returns a vector
   of results in request order (re-sorted from completion order at the end).

   Each result is the child's verdict map for that request, or a marker map:
   - `{:tag :timeout :request req}` — the worker hung on this request and was
     force-killed (recorded under :respawn AND continues; under :stop the rest are
     left for other workers / drained).
   - `{:tag :dead :request req}`    — the worker died without replying.

   spawn-specs: a vector of N spawn-specs (one per worker). Workers are launched
   from these in order; the on-respawn hook receives the worker INDEX so the
   caller can restore that worker's own copy. Use `(repeat n spec)` for N
   identical workers, or distinct specs (different :dir per worker) for per-worker
   filesystem isolation.

   opts:
   - :timeout-ms  per-request deadline (default 30000).
   - :on-timeout  :respawn (default) | :stop. With :stop a hung worker is killed
                  and NOT respawned; remaining queued requests are still served by
                  the OTHER live workers (only the actually-killed request is
                  marked :timeout). When the queue empties with workers down, any
                  request that was never served is recorded :skipped.
   - :on-respawn  optional `(fn [worker-index killed-request])` invoked AFTER the
                  hung worker is destroyed and BEFORE its replacement is spawned —
                  the hook the mutation layer uses to RESTORE that worker's copy
                  source (a mid-apply kill left it mutated) before the fresh child
                  boots.
   - :await-ready?  when true, consume a `{:tag :ready}` handshake after each spawn
                  (and respawn) BEFORE the worker pulls from the queue, so the
                  child's one-time boot (e.g. ClojureStorm ~2.8s) does not count
                  against the first request's deadline.
   - :boot-timeout-ms  deadline for the :ready handshake (default 60000).

   GUARANTEE: every request yields exactly one result; every child (original +
   respawn) is destroyed in a finally + tracked by the shutdown hook."
  [spawn-specs requests {:keys [timeout-ms on-timeout on-respawn await-ready? boot-timeout-ms]
                         :or {timeout-ms 30000 on-timeout :respawn boot-timeout-ms 60000}}]
  (let [reqs (vec requests)
        n (count spawn-specs)
        respawn? (= on-timeout :respawn)
        ;; Shared queue: items are [index request]; results collected by index.
        queue (ConcurrentLinkedQueue. ^java.util.Collection (map-indexed vector reqs))
        results (atom {})                       ; idx -> result map
        ;; One worker slot per spawn-spec; a slot's spawn-spec stays fixed so a
        ;; respawn reuses the same (per-worker) spec.
        spawn (fn [spec]
                (let [worker (#'pw/track! (pw/spawn! spec))
                      w (assoc worker :send! (fn [request t]
                                               (#'pw/send-line! (:in worker) request)
                                               (#'pw/read-verdict worker t)))]
                  ;; Consume the boot handshake so the per-request deadline excludes
                  ;; the child's one-time setup (only when the child emits :ready).
                  (when await-ready? (pw/wait-ready! w boot-timeout-ms))
                  w))
        worker-thread
        (fn [worker-idx spec]
          (fn []
            (loop [w (spawn spec)]
              (if-let [item (.poll queue)]
                (let [[idx request] item
                      v (try (serve-one! w request timeout-ms)
                             (catch Throwable _ ::dead))]
                  (cond
                    (map? v)
                    (do (swap! results assoc idx v)
                        (recur w))

                    :else
                    (let [tag (if (= v ::dead) :dead :timeout)]
                      (pw/destroy! w)
                      (#'pw/untrack! w)
                      (swap! results assoc idx {:tag tag :request request})
                      (when on-respawn (on-respawn worker-idx request))
                      (if respawn?
                        (recur (spawn spec))
                        ;; :stop — this worker is done; the queue's remaining items
                        ;; are served by the OTHER live workers. Leave the loop.
                        nil))))
                ;; Queue drained — this worker is done.
                (do (pw/destroy! w)
                    (#'pw/untrack! w)
                    nil)))))]
    (try
      (let [threads (mapv (fn [idx spec]
                            (doto (Thread. ^Runnable (worker-thread idx spec))
                              (.setName (str "heretic-pool-worker-" idx))
                              (.start)))
                          (range n)
                          spawn-specs)]
        (doseq [^Thread t threads] (.join t)))
      ;; Any request never served (possible only under :on-timeout :stop, if all
      ;; workers stopped while items remained) is recorded :skipped.
      (loop []
        (when-let [[idx request] (.poll queue)]
          (swap! results assoc idx {:tag :skipped :request request})
          (recur)))
      (mapv #(get @results %) (range (count reqs)))
      (finally
        ;; Belt-and-suspenders: nothing should be alive here (each thread destroys
        ;; its worker), but a thrown coordinator leaves no orphan either.
        nil))))
