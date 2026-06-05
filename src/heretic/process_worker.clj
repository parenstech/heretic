(ns heretic.process-worker
  "Generic forked worker-JVM harness (B3 — the only reliable fix for an
   uninterruptible infinite-loop mutant).

   The in-process runner's timeout is `future`/`Thread.interrupt`, which CANNOT
   stop a tight CPU loop that never hits an interruptible point (see
   docs/runner-isolation-spikes.md §B). The ONLY platform-correct fix on the JVM
   is to kill the OS process. This namespace spawns ONE child `clojure` JVM that
   does expensive load-once work, then serves a queue of requests over a line
   protocol; the parent kills + respawns the child on a per-request timeout and
   continues the queue.

   This layer is DELIBERATELY generic — it knows nothing about Heretic,
   ClojureStorm, mutations, or coverage. That keeps the risky systems part
   (spawn / framing / timeout / kill / respawn / no-orphan) deterministically
   testable under plain `clj` with a trivial echo/spin child (see
   test/heretic/process_worker_test.clj). Layer 2 (heretic.process-worker-child
   + heretic.runner-process) supplies the mutation child main and a public API on
   top of this harness.

   ── Protocol (newline-delimited EDN, STDOUT is the verdict channel) ─────────
   - Parent writes ONE EDN request map per line to the child's stdin, flushed.
   - Child writes EXACTLY ONE verdict line per request to STDOUT — a map tagged
     `{:tag :verdict ...}`.
   - ALL other child output (ClojureStorm banners, reload chatter, prns, stack
     traces) MUST go to STDERR, never STDOUT. To survive a stray stdout line
     anyway, the parent's reader IGNORES any stdout line that does not parse to a
     map with `:tag :verdict` — a junk line can't desync the stream.

   ── Timeout / kill / respawn ───────────────────────────────────────────────
   Each verdict is read with a deadline. On timeout the parent `.destroyForcibly()`s
   the child, records a `{:tag :timeout ...}` result for that request, and (when
   `:on-timeout :respawn`, the default) spawns a FRESH child to finish the queue.
   A drained/crashed child between requests is also respawned.

   ── No orphans ─────────────────────────────────────────────────────────────
   The child is destroyed in a `finally`, and a JVM shutdown hook destroys any
   live child so an abrupt parent exit leaves no runaway worker JVM."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader Writer]
           [java.util.concurrent ArrayBlockingQueue TimeUnit]))

;; =============================================================================
;; Spawn-spec -> child command
;; =============================================================================

(defn build-command
  "Build the child process command vector from a spawn-spec.

   spawn-spec keys (all optional unless noted):
   - :main       (REQUIRED) a fully-qualified namespace SYMBOL whose `-main` the
                 child runs (launched via `clojure ... -M -m <main>`), or
   - :code       an `-e` form string to eval instead of :main (one of :main/:code
                 is required).
   - :aliases    vector of clj alias keywords/strings put on the child (`-M:a:b`).
                 When absent the child runs a plain `-M` (with :main) / `-M -e`.
   - :deps       an `-Sdeps` EDN map merged into the child's deps (pr-str'd).
   - :jvm-opts   extra JVM opts, each gets a `-J` prefix (e.g. ClojureStorm -D flags).
   - :args       extra string args appended after the main (consumed by the child's
                 -main as command-line args).

   Returns a command vector suitable for ProcessBuilder."
  [{:keys [main code aliases deps jvm-opts args]}]
  (when (and (nil? main) (nil? code))
    (throw (ex-info "spawn-spec needs :main or :code" {})))
  (let [j-flags (mapv #(str "-J" %) jvm-opts)
        sdeps (when deps ["-Sdeps" (pr-str deps)])
        ;; `clojure -M` runs the main; aliases fold into the same flag (`-M:a:b`).
        exec-flag (if (seq aliases)
                    (str "-M:" (str/join ":" (map name aliases)))
                    "-M")
        invoke (if code ["-e" code] ["-m" (name main)])]
    (-> (into ["clojure"] j-flags)
        (into (or sdeps []))
        (conj exec-flag)
        (into invoke)
        (into (mapv str args)))))

;; =============================================================================
;; Child process record
;; =============================================================================

;; A spawned child: the Process plus its stdin writer and a background reader
;; thread that drains stdout into a bounded queue of parsed verdict maps. Reading
;; on a separate thread is what lets the parent enforce a per-request DEADLINE
;; (queue.poll with timeout) and `destroyForcibly` a child that never replies.

(defn- start-reader-thread
  "Drain the child's stdout line-by-line; push every line that parses to a map
   with `:tag :verdict` onto `q`. Non-verdict / unparseable lines are dropped
   (framing robustness — a stray stdout line can't desync the protocol). Exits
   when stdout closes (child dead)."
  [^BufferedReader rdr ^ArrayBlockingQueue q]
  (doto (Thread.
         ^Runnable
         (fn []
           (try
             (loop []
               (when-let [line (.readLine rdr)]
                 (let [v (try (edn/read-string line) (catch Exception _ ::junk))]
                   (when (and (map? v) (= :verdict (:tag v)))
                     ;; Bounded queue; block briefly if the parent is slow, but
                     ;; never wedge forever (the parent always drains in order).
                     (.offer q v 60 TimeUnit/SECONDS)))
                 (recur)))
             (catch Exception _ nil))))
    (.setDaemon true)
    (.setName "heretic-pw-reader")
    (.start)))

(defn spawn!
  "Spawn ONE child from a spawn-spec. Returns a worker map:
   {:process Process :in Writer(stdin) :verdicts ArrayBlockingQueue :reader Thread}.

   STDERR is inherited by default (child diagnostics stream live to the parent's
   stderr); pass `:log-file path` in the spawn-spec to redirect child STDERR to a
   file instead. STDOUT is the protocol channel and is always piped."
  [{:keys [dir log-file] :as spawn-spec}]
  (let [cmd (build-command spawn-spec)
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectError (if log-file
                               (java.lang.ProcessBuilder$Redirect/to (io/file log-file))
                               java.lang.ProcessBuilder$Redirect/INHERIT)))
        _ (when dir (.directory pb (io/file dir)))
        proc (.start pb)
        in (io/writer (.getOutputStream proc))
        rdr (io/reader (.getInputStream proc))
        q (ArrayBlockingQueue. 256)
        reader (start-reader-thread rdr q)]
    {:process proc :in in :verdicts q :reader reader :cmd cmd}))

(defn alive?
  "True when the worker's child process is still running."
  [worker]
  (some-> ^Process (:process worker) (.isAlive)))

(defn destroy!
  "Force-kill the worker's child and close its stdin. Idempotent; safe on a dead
   child. Returns nil."
  [worker]
  (when-let [^Process p (:process worker)]
    (try (.destroyForcibly p) (catch Exception _ nil))
    (try (.waitFor p 2 TimeUnit/SECONDS) (catch Exception _ nil)))
  (when-let [^Writer w (:in worker)]
    (try (.close w) (catch Exception _ nil)))
  nil)

(defn- send-line!
  "Write one EDN request as a single flushed line to the child's stdin."
  [^Writer in request]
  (.write in (pr-str request))
  (.write in "\n")
  (.flush in))

(defn- read-verdict
  "Poll the worker's verdict queue up to `timeout-ms`. Returns the parsed verdict
   map, or ::timeout if none arrived in time, or ::dead if the child died with the
   queue empty (drained / crashed)."
  [worker timeout-ms]
  (let [^ArrayBlockingQueue q (:verdicts worker)
        v (.poll q timeout-ms TimeUnit/MILLISECONDS)]
    (cond
      v v
      (alive? worker) ::timeout
      ;; Child gone, queue empty: a brief BLOCKING grace drain (the reader thread
      ;; may have the child's final verdict in-flight — a non-blocking poll here
      ;; would race the reader's .offer and spuriously declare ::dead) then give up.
      :else (or (.poll q 100 TimeUnit/MILLISECONDS) ::dead))))

;; =============================================================================
;; Shutdown-hook orphan guard
;; =============================================================================

(def ^:private live-children
  "Set (atom) of every spawned Process still believed alive — destroyed by the
   shutdown hook so an abrupt parent exit leaves no orphan worker JVM."
  (atom #{}))

(defonce ^:private shutdown-hook-installed
  (delay
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                       (fn []
                         (doseq [^Process p @live-children]
                           (try (.destroyForcibly p) (catch Exception _ nil))))))
    true))

(defn- track! [worker]
  @shutdown-hook-installed
  (swap! live-children conj (:process worker))
  worker)

(defn- untrack! [worker]
  (swap! live-children disj (:process worker))
  worker)

;; =============================================================================
;; Pool-of-one lifecycle
;; =============================================================================

(defn with-worker
  "Spawn one child from `spawn-spec`, call `(f send!)`, and GUARANTEE the child is
   destroyed afterwards (finally + shutdown-hook tracking).

   `send!` is `(fn [request timeout-ms] -> verdict | ::timeout | ::dead)`: it
   writes one request line and reads one verdict with a deadline. It does NOT
   respawn — that policy lives in `run-requests`. Use `with-worker` directly when
   you want manual control of a warm child.

   Returns whatever `f` returns."
  [spawn-spec f]
  (let [worker (track! (spawn! spawn-spec))]
    (try
      (f (fn send! [request timeout-ms]
           (send-line! (:in worker) request)
           (read-verdict worker timeout-ms)))
      (finally
        (destroy! worker)
        (untrack! worker)))))

(defn run-requests
  "Feed `requests` to a warm child IN ORDER, returning a vector of results in
   request order — the pool-of-one work-queue API.

   For each request: send it, read the verdict with a `:timeout-ms` deadline.
   - On a verdict, that map is the result.
   - On ::timeout, `.destroyForcibly()` the child, record
     `{:tag :timeout :request req}`, and (when `:on-timeout :respawn`, default)
     spawn a FRESH child to serve the remaining requests. With `:on-timeout :stop`
     the remaining requests are recorded as `{:tag :skipped}`.
   - On ::dead (child drained/crashed without a verdict), record
     `{:tag :dead :request req}` and respawn (under :respawn) to continue.

   opts:
   - :timeout-ms  per-request deadline (default 30000).
   - :on-timeout  :respawn (default) | :stop.
   - :on-respawn  optional `(fn [killed-request])` invoked AFTER the hung child is
                  destroyed and BEFORE the fresh child is spawned. This is the hook
                  the mutation layer uses to RESTORE the pristine source snapshot a
                  mid-apply kill left mutated on disk, so the respawned child loads
                  a clean tree. Runs on both ::timeout and ::dead.

   GUARANTEE: the child (original and every respawn) is destroyed in a finally and
   tracked by the shutdown hook, so no orphan worker JVM survives."
  [spawn-spec requests {:keys [timeout-ms on-timeout on-respawn]
                        :or {timeout-ms 30000 on-timeout :respawn}}]
  (let [reqs (vec requests)
        respawn? (= on-timeout :respawn)
        worker-atom (atom (track! (spawn! spawn-spec)))]
    (try
      (loop [i 0
             acc (transient [])]
        (if (>= i (count reqs))
          (persistent! acc)
          (let [req (nth reqs i)
                w @worker-atom]
            (send-line! (:in w) req)
            (let [v (read-verdict w timeout-ms)]
              (cond
                (map? v)
                (recur (inc i) (conj! acc v))

                ;; Timed out or died without replying: kill, record, respawn-or-stop.
                :else
                (let [tag (if (= v ::dead) :dead :timeout)]
                  (destroy! w)
                  (untrack! w)
                  ;; File-restore hook: a mid-apply kill left the source mutated;
                  ;; restore the pristine snapshot before the fresh child loads it.
                  (when on-respawn (on-respawn req))
                  (if respawn?
                    (do (reset! worker-atom (track! (spawn! spawn-spec)))
                        (recur (inc i) (conj! acc {:tag tag :request req})))
                    ;; :stop — record this one and skip the rest.
                    (persistent!
                     (reduce (fn [a j]
                               (conj! a (if (= j i)
                                          {:tag tag :request req}
                                          {:tag :skipped :request (nth reqs j)})))
                             acc
                             (range i (count reqs)))))))))))
      (finally
        (let [w @worker-atom]
          (destroy! w)
          (untrack! w))))))
