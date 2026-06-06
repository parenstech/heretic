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

   ── Single-worker (B3a) vs N-worker pool (B3b) ──────────────────────────────
   `:parallel-workers` (default 1) selects the path:
   - 1  → the original SINGLE-worker path (heretic.process-worker/run-requests),
          unchanged: the child reads the request's embedded :mutation, in the real
          working tree.
   - N>1 → the N-worker pool (heretic.process-pool/run-pool) with PER-WORKER
          FILESYSTEM ISOLATION. The orchestrator makes N copies of the project
          (heretic.sandbox/copy-project!, including the .heretic index so workers
          do NOT re-collect), each worker child runs with cwd = its copy and
          addresses mutants by stable KEY (never by absolute path across copies).
          A mid-apply kill leaves a worker's COPY mutated; the on-respawn hook
          restores that copy's pristine snapshot before its replacement boots. All
          copies are deleted in a finally; the real working tree is never on the
          write path under N>1.

   WHY PER-WORKER COPIES ARE MANDATORY: parallel children share one filesystem.
   Two children mutating/reloading the same tree concurrently cross-contaminate —
   reload-mutated-file! reloads the mutated ns + its DEPENDENTS from disk, so one
   worker's test can read another worker's on-disk mutation → wrong verdict
   (silent, flaky). Each worker MUST run in its own copy. The parallel result set
   then EQUALS the sequential one (the isolation correctness proof).

   Parity: for non-loop mutants the verdicts match heretic.runner/evaluate-mutation
   {:kill-matrix-mode true} run in-process — same :status / :killed-by-all — because
   the child runs that exact call.

   Result shape per mutation (in `mutations` order):
     {:mutation M            ; the original mutation record
      :status   :killed/:survived/:no-coverage/:timeout/:error
      :killed-by sym :killed-by-all #{syms}
      :timed-out #{syms} :eval-ms n}
   A reclaimed loop mutant gets {:status :timeout :timed-out-by :worker-kill}."
  (:require [clojure.java.io :as io]
            [heretic.killmatrix :as km]
            [heretic.process-pool :as pool]
            [heretic.process-worker :as pw]
            [heretic.sandbox :as sandbox]))

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

(defn- evaluate-single-worker
  "B3a path: one forked worker over the REAL working tree. Snapshots the source up
   front, restores it before each respawn AND once more in a finally."
  [config mutations]
  (let [snapshots (snapshot-sources mutations)
        spawn-spec (child-spawn-spec config)
        requests (mapv ->request mutations)
        timeout-ms (:timeout-ms config 30000)
        on-timeout (:on-timeout config :respawn)]
    (try
      (let [results (pw/run-requests
                     spawn-spec requests
                     {:timeout-ms timeout-ms
                      :on-timeout on-timeout
                      ;; The mutation child emits {:tag :ready} after its ~2.8s
                      ;; ClojureStorm boot — wait for it so boot cost doesn't eat
                      ;; the first mutant's deadline.
                      :await-ready? true
                      :boot-timeout-ms (:boot-timeout-ms config 60000)
                      ;; Restore the pristine source a mid-apply kill left mutated,
                      ;; before the fresh child loads the tree.
                      :on-respawn (fn [_killed-request] (restore-sources! snapshots))})]
        (mapv result-for mutations results))
      (finally
        ;; Final pristine restore — no matter what happened above.
        (restore-sources! snapshots)))))

;; ---------------------------------------------------------------------------
;; B3b — N-worker pool with per-worker filesystem isolation
;; ---------------------------------------------------------------------------

(defn worker-copy-dirs
  "The N per-worker copy directories under the sandbox dir, e.g.
   `<sandbox>/w0` .. `<sandbox>/w{N-1}`."
  [config project-root n]
  (let [sandbox (sandbox/resolve-sandbox-dir config project-root)]
    (mapv #(.getPath (io/file sandbox (str "w" %))) (range n))))

(defn- pool-spawn-spec
  "child-spawn-spec for a worker whose cwd is its OWN copy `copy-dir`. The config
   the child reads is the copy's heretic.edn (written by copy-project!). :dir is
   forced to the copy (so :local/root absolutization + the copy's index resolve)."
  [config copy-dir]
  (-> (child-spawn-spec (assoc config
                               :process-worker-dir copy-dir
                               :process-worker-config-path "heretic.edn"))
      (assoc :dir copy-dir)))

(defn- evaluate-pool
  "B3b path: N per-worker copies + key-addressed shared-queue pool. The real
   working tree is never on the write path; each worker mutates only its own copy.
   All copies are deleted in a finally."
  [config mutations n]
  (let [project-root (or (:process-worker-dir config) (System/getProperty "user.dir"))
        copy-dirs (worker-copy-dirs config project-root n)
        timeout-ms (:timeout-ms config 30000)
        on-timeout (:on-timeout config :respawn)
        ;; Build N isolated copies (each carries the .heretic index so the worker
        ;; loads it instead of re-collecting) and snapshot each copy's source so a
        ;; mid-apply kill can be restored per-copy on respawn.
        _ (doseq [d copy-dirs]
            (sandbox/copy-project! config project-root d :include-heretic true))
        ;; Per-copy pristine snapshots: file path -> content, indexed by worker.
        copy-snapshots
        (mapv (fn [d]
                (into {}
                      (for [rel (distinct (map :file mutations))
                            ;; mutation :file paths are relative to a project root;
                            ;; resolve them under the copy dir.
                            :let [f (io/file d rel)]
                            :when (.exists f)]
                        [(.getPath f) (slurp f)])))
              copy-dirs)
        spawn-specs (mapv #(pool-spawn-spec config %) copy-dirs)
        ;; Address mutants by stable KEY only (the child resolves the key to its
        ;; OWN copy's mutation — no absolute path crosses copies).
        requests (mapv (fn [m] {:tag :request :key (km/mutation-key m)}) mutations)]
    (try
      (let [results (pool/run-pool
                     spawn-specs requests
                     {:timeout-ms timeout-ms
                      :on-timeout on-timeout
                      ;; Each worker child emits {:tag :ready} after its ~2.8s
                      ;; ClojureStorm boot — wait for it so boot cost doesn't eat
                      ;; the first mutant's deadline (the cause of false timeouts).
                      :await-ready? true
                      :boot-timeout-ms (:boot-timeout-ms config 60000)
                      ;; Restore THIS worker's copy source (a mid-apply kill left
                      ;; it mutated) before its replacement boots.
                      :on-respawn (fn [worker-idx _killed-request]
                                    (restore-sources! (nth copy-snapshots worker-idx)))})]
        (mapv result-for mutations results))
      (finally
        ;; Delete every copy — the real tree was never written, so nothing else to
        ;; restore. Each copy dir is disposable.
        (doseq [d copy-dirs]
          (sandbox/delete-tree! (io/file d)))))))

(defn evaluate-mutations-process
  "Evaluate `mutations` via forked worker JVM(s), with kill + restore + respawn.
   Returns a vector of per-mutation result maps in `mutations` order.

   config keys (in addition to those `child-spawn-spec` reads):
   - :parallel-workers  N worker JVMs (default 1). 1 = the single-worker B3a path
                        over the real tree (unchanged). N>1 = the B3b pool with
                        per-worker project copies (full filesystem isolation); the
                        parallel result set EQUALS the sequential one.
   - :timeout-ms  per-mutant deadline before the worker is force-killed (default
                  30000). NOTE this is the WHOLE-mutant budget at the parent; the
                  child's own per-test :timeout-ms (from its heretic.edn) should be
                  smaller so normal slow tests don't trip the parent kill.
   - :on-timeout  :respawn (default) | :stop.
   - :sandbox-dir (N>1) where per-worker copies live (default \".heretic-sandbox\",
                  copies at `<sandbox>/w0`..). Cleaned up in a finally.

   SAFETY:
   - N=1: source files are snapshotted up front; restored before each respawn (a
     mid-apply kill leaves the file mutated) AND once more in a finally — working
     tree left pristine.
   - N>1: the real tree is never written. Each worker mutates only its own copy;
     a kill restores THAT copy's snapshot; all copies are deleted in a finally.
   The worker(s) (and every respawn) are destroyed in a finally + tracked by a
   shutdown hook: no orphan JVM."
  [config mutations]
  (let [mutations (vec mutations)
        n (or (:parallel-workers config) 1)]
    (if (and (integer? n) (> n 1))
      (evaluate-pool config mutations n)
      (evaluate-single-worker config mutations))))
