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
  (:require [clj-reload.core :as reload]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state (atom {:initialized? false :dirs nil}))

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
          {:keys [initialized?] current-dirs :dirs} @state]
      (when (or (not initialized?)
                (not= dirs current-dirs))
        (reload/init (merge {:dirs dirs} opts))
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
  [& {:keys [only] :as opts}]
  (when-not (:initialized? @state)
    (throw (ex-info "Reloader not initialized. Call init! first."
                    {:type :not-initialized})))
  (try
    (let [result (reload/reload (merge {:throw false} opts))]
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
