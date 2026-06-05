(ns heretic.runner-process
  "Public API for forked-worker mutation evaluation (B3) — the process-isolated
   analog of heretic.runner/evaluate-mutations.

   `evaluate-mutations-process` spawns ONE child JVM (heretic.process-worker-child)
   that loads the coverage index + test namespaces ONCE, then serves mutant
   requests over heretic.process-worker's line protocol. A mutant that turns
   bounded code into an uninterruptible CPU loop — which the in-process runner's
   Thread.interrupt CANNOT stop — is reclaimed by `.destroyForcibly()`-ing the
   child; the parent then RESTORES the pristine source (a mid-apply kill leaves it
   mutated on disk) and RESPAWNS a fresh child to finish the queue. The runaway is
   recorded as `{:status :timeout}` while the rest of the run completes and the
   parent stays healthy.

   This is the SINGLE-worker executor (the N-worker pool is B3b, out of scope).

   Parity: for non-loop mutants the verdicts match heretic.runner/evaluate-mutation
   {:kill-matrix-mode true} run in-process — same :status / :killed-by-all — because
   the child runs that exact call.

   Result shape per mutation (in request order):
     {:mutation M            ; the original mutation record
      :status   :killed/:survived/:no-coverage/:timeout/:error
      :killed-by sym :killed-by-all #{syms}
      :timed-out #{syms} :eval-ms n}
   A reclaimed loop mutant gets {:status :timeout :timed-out-by :worker-kill}."
  (:require [heretic.killmatrix :as km]
            [heretic.process-worker :as pw]))

;; ---------------------------------------------------------------------------
;; Child spawn-spec from a heretic config
;; ---------------------------------------------------------------------------

(defn child-spawn-spec
  "Build the heretic.process-worker spawn-spec for the mutation child.

   The child needs the flow-storm (ClojureStorm) classpath. Two ways to supply it:
   - :child-aliases  clj alias keywords that already put ClojureStorm + Heretic +
                     test paths on the classpath (e.g. [:heretic] in
                     validation/sample's deps.edn). Preferred — run with :dir set
                     to that project so the alias resolves.
   - :child-deps     an explicit -Sdeps map (when no suitable alias exists).

   config keys consumed:
   - :process-worker-dir   working dir for the child (project root; default the
                           JVM's user.dir).
   - :child-aliases        (default nil)
   - :child-deps           (default nil)
   - :child-jvm-opts       extra -J opts (ClojureStorm -D flags are usually carried
                           by the alias; add here if running via :child-deps).
   - :process-worker-config-path  path to the heretic.edn the child reads (default
                           \"heretic.edn\" resolved against the child dir).
   - :process-worker-log   child STDERR log file (default nil = inherit)."
  [config]
  (let [dir (:process-worker-dir config)
        cfg-path (:process-worker-config-path config "heretic.edn")]
    (cond-> {:main 'heretic.process-worker-child
             :args [cfg-path]
             :dir dir
             :log-file (:process-worker-log config)}
      (:child-aliases config) (assoc :aliases (:child-aliases config))
      (:child-deps config) (assoc :deps (:child-deps config))
      (:child-jvm-opts config) (assoc :jvm-opts (:child-jvm-opts config)))))

;; ---------------------------------------------------------------------------
;; Snapshot + restore (the crash-safety net for a mid-apply kill)
;; ---------------------------------------------------------------------------

(defn snapshot-sources
  "Slurp every distinct source file touched by `mutations` into a {file content}
   map — the pristine baseline the parent restores after a worker kill (and once
   more at the very end)."
  [mutations]
  (into {}
        (for [file (distinct (map :file mutations))]
          [file (slurp file)])))

(defn restore-sources!
  "Write every snapshot back to disk (pristine restore)."
  [snapshots]
  (doseq [[file content] snapshots]
    (spit file content)))

;; ---------------------------------------------------------------------------
;; Verdict <-> mutation mapping by stable key
;; ---------------------------------------------------------------------------

(defn- ->request [m]
  ;; Drop the volatile UUID :id (default EDN can't read #uuid) — the child works
  ;; from :file/:form-id/:coord/:operator and we key by the stable mutation-key.
  {:tag :request
   :key (km/mutation-key m)
   :mutation (dissoc m :id)})

(defn- result-for
  "Turn one harness result (a child verdict, or a harness :timeout/:dead/:skipped
   marker) into the public per-mutation result map."
  [m harness-result]
  (case (:tag harness-result)
    :verdict
    (do
      ;; Pairing is positional; the protocol also carries the stable :key on both
      ;; the request and the verdict, so assert they agree — a mismatch means the
      ;; stream desynced (one mutant emitted 0 or 2 verdict lines) and must fail
      ;; loudly rather than silently mis-attribute a verdict to the wrong mutant.
      (when-let [vk (:key harness-result)]
        (when (not= vk (km/mutation-key m))
          (throw (ex-info "process-worker verdict/mutation desync"
                          {:verdict-key vk :mutation-key (km/mutation-key m) :mutation m}))))
      {:mutation m
       :status (:status harness-result)
       :killed-by (:killed-by harness-result)
       :killed-by-all (:killed-by-all harness-result)
       :timed-out (:timed-out harness-result)
       :eval-ms (:eval-ms harness-result)})

    ;; The worker was force-killed (uninterruptible loop) or died without a
    ;; verdict — the runaway mutant is recorded as a timeout.
    (:timeout :dead)
    {:mutation m
     :status :timeout
     :killed-by nil
     :killed-by-all nil
     :timed-out-by :worker-kill}

    ;; :on-timeout :stop tail
    :skipped
    {:mutation m :status :skipped}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn evaluate-mutations-process
  "Evaluate `mutations` via a SINGLE forked worker JVM, with kill + restore +
   respawn. Returns a vector of per-mutation result maps in `mutations` order.

   config keys (in addition to those `child-spawn-spec` reads):
   - :timeout-ms  per-mutant deadline before the worker is force-killed (default
                  30000). NOTE this is the WHOLE-mutant budget at the parent; the
                  child's own per-test :timeout-ms (from its heretic.edn) should be
                  smaller so normal slow tests don't trip the parent kill.
   - :on-timeout  :respawn (default) | :stop.

   SAFETY: source files are snapshotted up front; the snapshot is restored before
   each respawn (a mid-apply kill leaves the file mutated) AND once more in a
   finally at the end — so the working tree is left pristine. The worker (and every
   respawn) is destroyed in a finally + tracked by a shutdown hook: no orphan JVM."
  [config mutations]
  (let [mutations (vec mutations)
        snapshots (snapshot-sources mutations)
        spawn-spec (child-spawn-spec config)
        requests (mapv ->request mutations)
        timeout-ms (:timeout-ms config 30000)
        on-timeout (:on-timeout config :respawn)]
    (try
      (let [results (pw/run-requests
                     spawn-spec requests
                     {:timeout-ms timeout-ms
                      :on-timeout on-timeout
                      ;; Restore the pristine source a mid-apply kill left mutated,
                      ;; before the fresh child loads the tree.
                      :on-respawn (fn [_killed-request] (restore-sources! snapshots))})]
        (mapv result-for mutations results))
      (finally
        ;; Final pristine restore — no matter what happened above.
        (restore-sources! snapshots)))))
