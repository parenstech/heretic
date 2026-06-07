(ns heretic.killmatrix
  "Resumable, chunked kill-matrix runs.

   The no-early-exit kill-matrix analysis (`runner/evaluate-mutations-full` +
   `subsumption/kill-matrix-analysis`) runs EVERY covering test for EVERY mutant,
   so it is slow and, on large targets, may not finish in a single process — and
   an infinite-loop mutant can hang JVM exit (see `runner/run-test-with-timeout`,
   now on a daemon thread). This namespace makes that work DURABLE and SPLITTABLE:

   - each mutant's result is APPENDED to an on-disk log (NDJSON of EDN) as soon as
     it completes, so a killed/crashed/hung run loses no completed work;
   - a re-run LOADS the log, SKIPS already-recorded mutants, and finishes only the
     remainder;
   - `:chunk [i n]` slices a *deterministically ordered* mutation list into
     disjoint contiguous blocks that can run in separate processes/machines and
     reassemble losslessly into the identical kill matrix.

   The persistence + resume + analysis layer here is pure (the only effect is the
   append-only log write); the per-mutant evaluation is INJECTED as `:evaluate-one`,
   so a caller wires the real apply -> reload -> evaluate-mutation -> restore loop
   and tests inject a stub. This keeps the whole layer loadable + testable without
   ClojureStorm.

   Spike A1 (docs/validation-results.md): proven on validation/sample — an
   uninterrupted run, a kill@K-then-resume, and a 3-way chunk split all produced
   the IDENTICAL kill-matrix fingerprint at <1% logging overhead (~0.25 ms/mutant)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [heretic.subsumption :as sub]))

;; =============================================================================
;; Stable, restart-independent identity (UUID-independent)
;; =============================================================================
;; `mutations-for-file` attaches a random UUID :id, but mutation ORDER is
;; deterministic (driven by source position). To make chunking + resume robust
;; across process restarts we key on a stable identity that does NOT depend on the
;; UUID — the same fields `subsumption/mutation-identity` selects — so the log key
;; and the analysis key agree.

;; A mutation's identity is its source LOCATION plus what it BECOMES. :coord can
;; repeat for sibling literals in one form (e.g. three `0`s under one "…,V48"
;; path), so :column is required to tell them apart; :replacement guards the case
;; of one operator emitting several replacements at a single column. Omitting
;; either lets assert-unique-keys! reject a valid run (distinct sites → one key).
(def ^:private identity-keys [:file :form-id :coord :operator :line :column :replacement])

(defn mutation-key
  "Stable, restart-independent string key for a mutation record (no UUID)."
  [m]
  (pr-str (select-keys m identity-keys)))

(defn assert-unique-keys!
  "Throw if two distinct mutations collapse to the same stable key — which would
   make the resume log silently dedupe distinct mutation sites. Returns the
   mutations unchanged when all keys are unique."
  [mutations]
  (let [dups (->> (map mutation-key mutations)
                  frequencies
                  (keep (fn [[k c]] (when (> c 1) k)))
                  vec)]
    (when (seq dups)
      (throw (ex-info "Duplicate mutation keys — resume log would dedupe distinct sites"
                      {:duplicate-key-count (count dups)
                       :sample-duplicate (first dups)})))
    mutations))

;; =============================================================================
;; Deterministic ordering + chunking
;; =============================================================================

(defn order-mutations
  "Deterministic, restart-independent ordering (sort by the stable key)."
  [mutations]
  (vec (sort-by mutation-key mutations)))

(defn select-chunk
  "Contiguous, disjoint i-of-n slice (0-indexed `i`) of an ordered coll. The n
   chunks partition the coll exactly — the first `(mod total n)` chunks get one
   extra element — so the union of all n chunks is the whole coll with no overlap."
  [coll i n]
  (let [v (vec coll)
        total (count v)
        base (quot total n)
        r (mod total n)
        size-of (fn [k] (+ base (if (< k r) 1 0)))
        start (reduce + 0 (map size-of (range i)))]
    (subvec v start (+ start (size-of i)))))

;; =============================================================================
;; Append-only log (NDJSON of EDN: one pr-str map per line)
;; =============================================================================

(defn result->entry
  "Minimal persisted shape — exactly what `kill-matrix-analysis` needs to rebuild
   the matrix from the log alone (identity + status + killer sets)."
  [m result]
  {:mutation (select-keys m identity-keys)
   :key (mutation-key m)
   :status (:status result)
   :killed-by (:killed-by result)
   :killed-by-all (:killed-by-all result)
   :eval-ms (:eval-ms result)})

(defn append-result!
  "Append one result as a single flushed EDN line. Returns the persisted entry."
  [log-path m result]
  (let [entry (result->entry m result)]
    (with-open [w (io/writer log-path :append true)]
      (.write w (pr-str entry))
      (.write w "\n")
      (.flush w))
    entry))

(defn load-log
  "Read the log into a vector of entries, tolerating a truncated/garbage trailing
   line (so a crash mid-append at worst re-runs one mutant on resume)."
  [log-path]
  (let [f (io/file log-path)]
    (if-not (.exists f)
      []
      (->> (str/split-lines (slurp f))
           (remove str/blank?)
           (keep #(try (edn/read-string %) (catch Exception _ nil)))
           vec))))

(defn done-keys
  "Set of stable keys already recorded in the log entries."
  [entries]
  (set (map :key entries)))

(defn pending
  "Mutations from `ordered` not yet present in the log entries."
  [ordered entries]
  (let [done (done-keys entries)]
    (remove #(contains? done (mutation-key %)) ordered)))

;; =============================================================================
;; Analysis from the log alone
;; =============================================================================

(defn- entry->result
  "Reconstruct the minimal MutationResult shape `subsumption` needs from a log
   entry."
  [entry]
  {:mutation (:mutation entry)
   :status (:status entry)
   :killed-by (:killed-by entry)
   :killed-by-all (:killed-by-all entry)})

(defn log->results
  "Turn log entries into the result seq consumed by
   `subsumption/build-full-kill-matrix` / `kill-matrix-analysis`."
  [entries]
  (mapv entry->result entries))

(defn analyze-log
  "Load a (possibly multi-chunk-merged) log file and run the exact
   dominator/minimal-set analysis over it."
  [log-path]
  (-> (load-log log-path) log->results sub/kill-matrix-analysis))

;; =============================================================================
;; Resumable / chunked driver (per-mutant evaluation injected)
;; =============================================================================

(defn run-resumable!
  "Drive a resumable/chunked kill-matrix run, appending each result to the log.

   opts:
   - :mutations    - the full mutation set (will be ordered, then optionally chunked)
   - :log-path     - NDJSON log file (created if absent, appended otherwise)
   - :evaluate-one - (fn [mutation] -> result) producing at least :status,
                     :killed-by and :killed-by-all. The caller wires the real
                     apply -> reload -> evaluate-mutation {:kill-matrix-mode true}
                     -> restore loop; tests inject a stub.
   - :chunk        - [i n] to run only the i-of-n slice (optional)
   - :fresh?       - delete the log before starting (optional)
   - :limit        - cap how many mutants to run THIS invocation (optional; lets a
                     long run be split across processes/turns)
   - :on-result    - (fn [entry]) progress callback (optional)

   Returns {:total n :ran n :skipped n :log-path s}. Re-invoking finishes the
   remainder; invoking on a complete log runs nothing (idempotent)."
  [{:keys [mutations log-path evaluate-one chunk fresh? limit on-result]}]
  (assert-unique-keys! mutations)
  (when fresh? (io/delete-file log-path true))
  (let [ordered (order-mutations mutations)
        scoped (if chunk (select-chunk ordered (first chunk) (second chunk)) ordered)
        all-todo (pending scoped (load-log log-path))
        todo (if limit (take limit all-todo) all-todo)]
    (doseq [m todo]
      (let [entry (append-result! log-path m (evaluate-one m))]
        (when on-result (on-result entry))))
    {:total (count scoped)
     :ran (count todo)
     :skipped (- (count scoped) (count todo))
     :log-path log-path}))
