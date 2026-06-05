(ns heretic.reloader
  "Thin wrapper around clj-reload for namespace reloading.

   Provides controlled namespace reloading for mutation testing:
   - Initialize once with source paths
   - Reload changed namespaces after mutations
   - Handle errors gracefully with structured results

   Usage:
     (init! [\"src\" \"test\"])
     (reload!)  ;=> {:success true :reloaded [ns1 ns2] :unloaded [ns1 ns2]}

   clj-reload handles:
   - Dependency-aware reload ordering
   - Protocol/multimethod preservation
   - Lifecycle hooks (before-ns-unload/after-ns-reload)"
  (:require [clj-reload.core :as reload]
            [clj-reload.parse :as parse]
            [clojure.java.io :as io])
  (:import [java.io File StringWriter PushbackReader]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state (atom {:initialized? false :dirs nil}))

;; =============================================================================
;; Helpers
;; =============================================================================

(defmacro ^:private with-quiet-stdout
  "Execute body with stdout suppressed. Returns the result of body."
  [& body]
  `(let [sw# (StringWriter.)]
     (binding [*out* sw#]
       ~@body)))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init!
  "Initialize clj-reload with the given source directories.

   Options (passed to clj-reload.core/init):
   - :no-reload - namespaces to never reload (e.g., #{my.app.server})
   - :no-unload - namespaces to reload but never unload
   - :output - logging verbosity (:verbose, :quieter, :quiet)

   Returns {:success true} on success, or {:success false :error <ex>} on failure.

   Subsequent calls with same dirs are no-ops. Call with different dirs to reinitialize."
  [source-paths & {:as opts}]
  (try
    (let [dirs (vec source-paths)
          {:keys [initialized?] current-dirs :dirs} @state
          init-opts (merge {:dirs dirs :output :quiet} opts)]
      (when (or (not initialized?)
                (not= dirs current-dirs))
        (reload/init init-opts)
        (reset! state {:initialized? true :dirs dirs})))
    {:success true}
    (catch Exception e
      {:success false :error e})))

(defn initialized?
  "Returns true if clj-reload has been initialized."
  []
  (:initialized? @state))

(defn reset-state!
  "Reset initialization state. Primarily for testing."
  []
  (reset! state {:initialized? false :dirs nil})
  nil)

;; =============================================================================
;; Reload Operations
;; =============================================================================

(defn reload!
  "Reload changed namespaces.

   Options:
   - :only - Scope for reload: :loaded (default), :all, or regex for ns matching

   Returns:
   {:success true :reloaded [ns1 ns2] :unloaded [ns1 ns2]}
   or
   {:success false :error <ex> :failed <ns> :reloaded [...] :unloaded [...]}

   Throws if not initialized."
  [& {:as opts}]
  (when-not (:initialized? @state)
    (throw (ex-info "Reloader not initialized. Call init! first."
                    {:type :not-initialized})))
  (try
    (let [result (with-quiet-stdout (reload/reload (merge {:throw false} opts)))]
      (if (:exception result)
        {:success false
         :error (:exception result)
         :failed (:failed result)
         :reloaded (vec (:loaded result))
         :unloaded (vec (:unloaded result))}
        {:success true
         :reloaded (vec (:loaded result))
         :unloaded (vec (:unloaded result))}))
    (catch Exception e
      {:success false :error e :reloaded [] :unloaded []})))

(defn reload-after-mutation!
  "Reload namespaces after applying a code mutation.

   This is the primary reload entry point for mutation testing.
   It reloads all changed namespaces to pick up the mutated code.

   Returns same structure as reload!"
  [& opts]
  (apply reload! opts))

(defn reload-after-revert!
  "Reload namespaces after reverting a mutation.

   Semantically identical to reload-after-mutation! but named
   for clarity in mutation testing workflows.

   Returns same structure as reload!"
  [& opts]
  (apply reload! opts))

(defn ns-for-file
  "Read the namespace symbol declared in `file` — the first top-level `(ns ...)`
   form — or nil if none is found. Used to force-reload a mutated file's namespace
   without depending on clj-reload's change detection."
  [file]
  (with-open [r (PushbackReader. (io/reader (io/file file)))]
    (loop []
      (let [form (try (read {:eof ::eof :read-cond :allow} r)
                      (catch Exception _ ::eof))]
        (cond
          (= form ::eof) nil
          (and (seq? form) (= 'ns (first form)) (symbol? (second form))) (second form)
          :else (recur))))))

;; =============================================================================
;; Dependent discovery (for refreshing value-captured re-exports)
;; =============================================================================
;;
;; A bare `(require ns :reload)` reloads ONLY `ns`. That is not enough when
;; another namespace re-exports one of `ns`'s vars BY VALUE — e.g.
;;
;;     (ns app.facade (:require [app.core :as core]))
;;     (def build-request-body core/build-request-body)   ; captured at def-time
;;
;; `app.facade/build-request-body` holds the *function value* bound when
;; `app.facade` was loaded; reloading only `app.core` leaves it pointing at the
;; pre-mutation function. A covering test that calls the var through the facade
;; then runs UN-mutated code, passes, and the mutant is falsely scored
;; `survived`. To fix it we must also reload the transitive dependents of the
;; mutated namespace so their re-export `def`s re-evaluate.
;;
;; The require graph is static across a mutation run, so we parse it once (via
;; clj-reload's public parser) and memoize on the source dirs.

(defonce ^:private dep-graph-cache (atom nil))

(defn- source-files
  "All .clj/.cljc files under `dirs`."
  [dirs]
  (->> dirs
       (mapcat #(file-seq (io/file %)))
       (filter (fn [^File f] (and (.isFile f)
                                  (re-find #"\.cljc?$" (.getName f)))))))

(defn- dependees-graph
  "clj-reload's reverse-dependency map {ns -> #{dependent-ns ...}} for the
   namespaces declared under `dirs`, memoized on `dirs`. Returns {} if `dirs`
   is empty/nil or parsing yields nothing."
  [dirs]
  (when (seq dirs)
    (let [cached @dep-graph-cache]
      (if (= (:dirs cached) dirs)
        (:graph cached)
        (let [namespaces (reduce (fn [acc ^File f]
                                   (let [res (parse/read-file f)]
                                     (if (instance? Throwable res)
                                       acc
                                       (merge-with merge acc res))))
                                 {}
                                 (source-files dirs))
              graph (parse/dependees namespaces)]
          (reset! dep-graph-cache {:dirs dirs :graph graph})
          graph)))))

(defn- reload-order
  "`ns-sym` followed by its transitive dependents (the namespaces that must be
   reloaded so value-captured re-exports refresh), in dependency-first
   topological order, restricted to namespaces that are currently loaded.

   When the dependency graph is unavailable (reloader not initialized, no source
   dirs, or a parse failure) this degrades to just `[ns-sym]` — the prior
   single-namespace behavior."
  [ns-sym]
  (let [graph (try (dependees-graph (:dirs @state))
                   (catch Throwable _ nil))]
    (if-not (seq graph)
      [ns-sym]
      (let [closure (parse/transitive-closure graph #{ns-sym})
            ;; topo-sort the full reverse-dep graph (roots = upstream
            ;; dependencies first); don't throw on cycles, just append the
            ;; remaining nodes in arbitrary order.
            ordered (parse/topo-sort graph (fn [deps _] (keys deps)))
            ordered (filter closure ordered)
            ;; guarantee ns-sym is present even if it has no graph edges
            ordered (if (some #{ns-sym} ordered) ordered (cons ns-sym ordered))]
        (filterv find-ns ordered)))))

(defn reload-mutated-file!
  "Force-reload the namespace defined in `file` — and its transitive dependents —
   via `(require ns :reload)`, BYPASSING clj-reload's mtime-based change detection.

   Why the bypass exists: the mutate loop spits a mutated source file then reloads
   before running the covering tests. clj-reload detects changes by file mtime at
   millisecond granularity; on coarse-mtime filesystems (e.g. ZFS) consecutive
   sub-millisecond spits collide on the same mtime, so clj-reload decides the file
   is unchanged and SILENTLY SKIPS the reload — the mutated bytecode never enters
   the JVM, the tests run against the original code, and the mutant is falsely
   scored `survived` (a non-deterministic, load-dependent false-negative). A direct
   `(require ns :reload)` re-reads the file from the classpath unconditionally, so
   the mutated (or, on revert, the restored) code always loads.

   Why dependents too: reloading ONLY the mutated namespace is NOT sufficient when
   another namespace re-exports one of its vars by value — `(def f other/f)`. That
   `def` captured the function at load-time, so it still points at the pre-mutation
   function until the re-exporting namespace is itself reloaded. A covering test
   that reaches the mutated code THROUGH such a re-export would otherwise run
   un-mutated code, pass, and falsely score the mutant `survived` (a deterministic
   false-negative for any code exercised via a re-export). `reload-order` returns
   the mutated namespace plus its transitive dependents in dependency-first order,
   restricted to currently-loaded namespaces; we reload them in that order so the
   re-export `def`s re-evaluate against the freshly-reloaded vars. (When the
   dependency graph is unavailable — reloader not initialized — this degrades to
   reloading just the mutated namespace, the prior behavior.)

   Falls back to clj-reload's `reload!` when `file` declares no namespace.
   Returns the same {:success bool ...} shape as `reload!`."
  [file]
  (try
    (if-let [ns-sym (ns-for-file file)]
      (let [nses (reload-order ns-sym)]
        (with-quiet-stdout
          (doseq [n nses] (require n :reload)))
        {:success true :reloaded (vec nses) :unloaded (vec nses)})
      (reload!))
    (catch Throwable e
      {:success false :error e :reloaded [] :unloaded []})))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn find-test-namespaces
  "Find all namespaces matching the test namespace pattern.

   Useful for discovering test namespaces to run after mutation.

   Returns set of namespace symbols, or {:error <ex>} on failure."
  ([]
   (find-test-namespaces #".*-test$"))
  ([pattern]
   (when-not (:initialized? @state)
     (throw (ex-info "Reloader not initialized. Call init! first."
                     {:type :not-initialized})))
   (try
     (reload/find-namespaces pattern)
     (catch Exception e
       {:error e}))))
