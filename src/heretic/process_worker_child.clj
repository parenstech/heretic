(ns heretic.process-worker-child
  "LAYER-2 mutation child: the forked worker JVM that heretic.runner-process
   drives over heretic.process-worker's line protocol.

   It does the expensive setup ONCE — load the full in-memory coverage index,
   init the reloader, require the target test namespaces — then serves a queue of
   mutant requests, one verdict per request:

     read {:tag :request :key K :mutation M}
       -> apply-mutation!  (writes the mutant into the source file on disk)
       -> reload-mutated-file!
       -> runner/evaluate-mutation {:kill-matrix-mode true :timeout-ms n}
       -> revert (restore the pristine source from the in-process snapshot, reload)
       -> emit {:tag :verdict :key K :status S :killed-by ... :killed-by-all ...}

   This child runs with the flow-storm classpath (ClojureStorm) — it NEEDS it to
   run, but the harness in heretic.process-worker needs none of it. The index is
   rebuilt the way validation/experiments/g2g3_killmatrix does: from the on-disk
   coverage files + meta.edn :forms, via form-bridge/build-form-location-index.

   ── STDOUT discipline ──────────────────────────────────────────────────────
   STDOUT carries ONLY verdict lines. ClojureStorm's banner, clj-reload chatter,
   and every diagnostic go to STDERR. We bind *out* to *err* around all setup and
   evaluation, and write verdicts straight to the raw `System/out`.

   ── File safety on a mid-apply kill ────────────────────────────────────────
   If the parent destroyForcibly's this child between apply-mutation! and revert,
   the source file is left MUTATED on disk. The child cannot clean that up (it is
   dead). The PARENT (heretic.runner-process) holds the pristine snapshot and
   restores it before respawning — so the in-process `finally` revert here is the
   fast path, and the parent's snapshot-restore is the crash-safety net.

   ── Key-addressed serving (B3b — per-worker copies) ─────────────────────────
   The N-worker pool gives each worker its OWN copy of the project, so an absolute
   mutation path from one copy is meaningless in another. To stay copy-consistent
   the child, on boot, regenerates the mutations over ITS OWN copy's source and
   builds a {mutation-key -> mutation} map (heretic.killmatrix/mutation-key). A
   request then carries only a stable `:key`; the child looks up its-copy mutation
   and evaluates it in its-copy source. A request WITHOUT a :key (or with a :key
   the child can't find) falls back to the request's embedded :mutation — so the
   single-worker B3a path (a literal :mutation, no copies) is unchanged.

   Args (positional, from -main):
   - heretic.edn path (the project config: source/test paths, instrument prefixes,
     heretic-dir, test-namespaces, mutation operators)."
  (:require [clojure.edn :as edn]
            [heretic.coverage-map.index :as cindex]
            [heretic.form-bridge :as bridge]
            [heretic.killmatrix :as km]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.persistence :as persist]
            [heretic.reloader :as reloader]
            [heretic.runner :as runner]))

;; ---------------------------------------------------------------------------
;; Index loading — rebuild the COMPLETE in-memory index from on-disk artifacts
;; (mirrors validation/experiments/g2g3_killmatrix/load-full-index).
;; ---------------------------------------------------------------------------

(defn load-full-index
  [{:keys [heretic-dir source-paths test-paths]}]
  (let [coverage-files (for [f (persist/list-coverage-files heretic-dir)]
                         (persist/load-edn f))
        meta-data (persist/load-meta heretic-dir)
        forms (:forms meta-data)
        fli (bridge/build-form-location-index forms (concat source-paths test-paths))]
    (cindex/build-index coverage-files fli)))

;; ---------------------------------------------------------------------------
;; Boot-time key -> mutation map (B3b) — generate mutations over THIS COPY's
;; source so a request's stable :key resolves to a this-copy mutation record.
;; ---------------------------------------------------------------------------

(defn build-key->mutation
  "Generate every mutation over the config's source paths (this copy's source)
   with `ops/all-operators` and index them by stable km/mutation-key, so a pooled
   request carrying only a :key resolves to a copy-local mutation. Throws if two
   distinct sites collapse to one key (would mis-serve a request)."
  [{:keys [source-paths exclude-files]}]
  (let [muts (vec (engine/generate-mutations (or source-paths ["src"])
                                             ops/all-operators
                                             exclude-files))]
    (km/assert-unique-keys! muts)
    (into {} (map (fn [m] [(km/mutation-key m) m])) muts)))

;; ---------------------------------------------------------------------------
;; Per-mutant evaluation — apply -> reload -> evaluate -> revert, snapshot
;; restored in a finally (the in-process fast path; parent is the crash net).
;; ---------------------------------------------------------------------------

(defn evaluate-one
  "Evaluate a single mutation in kill-matrix mode. `snapshots` maps a source file
   path to its pristine content (so revert restores even if :backup is absent).
   Returns a verdict map (no :tag) carrying :status / :killed-by / :killed-by-all."
  [index mutation timeout-ms snapshots]
  (let [file (:file mutation)
        snapshot (get snapshots file)
        start (System/currentTimeMillis)]
    (try
      (let [applied (engine/apply-mutation! mutation)
            reload-result (reloader/reload-mutated-file! (:file applied))]
        (if (:success reload-result)
          (let [r (runner/evaluate-mutation index applied
                                            {:kill-matrix-mode true
                                             :timeout-ms timeout-ms})]
            {:status (:status r)
             :killed-by (:killed-by r)
             :killed-by-all (:killed-by-all r)
             :tests-run (:tests-run r)
             :timed-out (:timed-out r)
             :eval-ms (- (System/currentTimeMillis) start)})
          {:status :error
           :killed-by nil :killed-by-all nil
           :error-message (str "reload failed: " (:error reload-result))
           :eval-ms (- (System/currentTimeMillis) start)}))
      (finally
        ;; Restore the pristine source from snapshot (preferred) or :backup, then
        ;; reload so the JVM is clean for the next mutant.
        (when snapshot (spit file snapshot))
        (reloader/reload-mutated-file! file)))))

;; ---------------------------------------------------------------------------
;; Serve loop
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [config-path (first args)
        config (edn/read-string (slurp config-path))
        out System/out
        emit! (fn [m] (.println out (pr-str m)) (.flush out))]
    (binding [*out* *err*]
      ;; All setup chatter -> stderr.
      (println "[pw-child] loading index from" (:heretic-dir config))
      (let [index (load-full-index config)
            source-paths (:source-paths config ["src"])
            ;; B3b: build the copy-local {key -> mutation} map ONCE so pooled
            ;; requests can address mutants by stable key (never by absolute path
            ;; from another copy). Cheap (parsing the source); done once per boot.
            key->mut (build-key->mutation config)]
        (reloader/init! source-paths :output :quiet)
        ;; Snapshot every source file ONCE so revert is robust even if a
        ;; mutation arrives without an applicable :backup.
        (println "[pw-child] index loaded; reloader initialized?"
                 (reloader/initialized?))
        (println "[pw-child] key->mutation entries:" (count key->mut))
        (println "[pw-child] ready, serving requests")
        ;; READINESS HANDSHAKE: signal on the verdict channel that all expensive
        ;; one-time setup (index load + reloader init + key map) is DONE, so the
        ;; parent can start the per-request deadline only now — the child's ~2.8s
        ;; ClojureStorm boot must NOT eat into the first mutant's timeout budget.
        (emit! {:tag :ready})
        (let [snapshots (atom {})
              snapshot-file! (fn [file]
                               (or (get @snapshots file)
                                   (let [s (slurp file)]
                                     (swap! snapshots assoc file s)
                                     s)))
              ;; Resolve a request to a copy-local mutation: prefer the stable
              ;; :key lookup (B3b pool — copy-consistent); fall back to the
              ;; request's embedded :mutation (B3a single-worker, no copies).
              resolve-mutation (fn [req]
                                 (or (get key->mut (:key req))
                                     (:mutation req)))
              rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
              timeout-ms (:timeout-ms config 5000)]
          (loop []
            (when-let [line (.readLine rdr)]
              (let [req (try (edn/read-string line) (catch Exception _ nil))]
                (when (= :request (:tag req))
                  (let [m (resolve-mutation req)
                        verdict (if (nil? m)
                                  {:status :error
                                   :killed-by nil :killed-by-all nil
                                   :error-message (str "no mutation for key " (:key req))}
                                  (do
                                    (snapshot-file! (:file m))
                                    (try
                                      (evaluate-one index m timeout-ms @snapshots)
                                      (catch Throwable e
                                        {:status :error
                                         :killed-by nil :killed-by-all nil
                                         :error-message (str (class e) ": "
                                                             (.getMessage e))}))))]
                    (emit! (assoc verdict :tag :verdict :key (:key req))))))
              (recur))))))))
