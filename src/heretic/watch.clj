(ns heretic.watch
  "File watching for continuous mutation testing.

   Watch mode monitors source and test files for changes:
   - Source file changes: Re-mutate affected file
   - Test file changes: Re-collect coverage, then re-mutate affected sources

   Usage:
   (def watcher (start-watch! config))
   ;; ... work on code ...
   (stop! watcher)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hawk.core :as hawk]
            [heretic.coverage-map :as coverage]
            [heretic.mutation-engine :as engine]
            [heretic.runner :as runner]
            [heretic.reporter :as reporter]
            [heretic.reloader :as reloader]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private watcher-state (atom nil))

(defn- now-ms []
  (System/currentTimeMillis))

;; =============================================================================
;; File Classification
;; =============================================================================

(defn- clj-file? [path]
  (and (string? path)
       (or (str/ends-with? path ".clj")
           (str/ends-with? path ".cljc"))))

(defn- in-path? [paths file]
  (let [file-path (.getCanonicalPath (io/file file))]
    (some (fn [path]
            (str/starts-with? file-path
                              (.getCanonicalPath (io/file path))))
          paths)))

(defn- source-file? [config file]
  (in-path? (:source-paths config) file))

(defn- test-file? [config file]
  (in-path? (:test-paths config) file))

;; =============================================================================
;; Change Handlers
;; =============================================================================

(defn- file-path [event]
  (when-let [f (:file event)]
    (.getPath f)))

(defn- handle-source-change
  "Handle source file change - run mutations for the affected file."
  [config index file verbose?]
  (when verbose?
    (println (format "\n[watch] Source changed: %s" file)))

  ;; Generate mutations for this file only
  (let [mutations (vec (engine/mutations-for-file file))
        total (count mutations)]

    (if (zero? total)
      (when verbose?
        (println "[watch] No mutations found in file"))

      (do
        (when verbose?
          (println (format "[watch] Found %d mutations, testing..." total)))

        ;; Reload changed namespaces
        (reloader/reload!)

        ;; Run each mutation
        (let [timeout-ms (or (:timeout-ms config) 5000)
              results (atom [])]
          (doseq [mutation mutations]
            (try
              (let [result (engine/with-mutation [applied mutation]
                             (reloader/reload!)
                             (runner/evaluate-mutation index applied {:timeout-ms timeout-ms}))]
                (swap! results conj result))
              (catch Exception e
                (swap! results conj {:mutation mutation
                                     :status :error
                                     :error-message (str e)}))))

          ;; Print summary
          (let [summary (runner/summarize-results @results)
                survivors (filter #(= :survived (:status %)) @results)]
            (println)
            (println (format "[watch] Results: %d killed, %d survived, %d no-coverage"
                             (:killed summary) (:survived summary) (:no-coverage summary)))
            (when (seq survivors)
              (println "[watch] Surviving mutations:")
              (doseq [{:keys [mutation]} survivors]
                (println (format "  - Line %d: %s -> %s"
                                 (:line mutation)
                                 (:original mutation)
                                 (:replacement mutation)))))))))))

(defn- handle-test-change
  "Handle test file change - re-collect coverage for the test namespace."
  [config file verbose?]
  (when verbose?
    (println (format "\n[watch] Test changed: %s" file)))

  ;; Convert file path to namespace
  (let [test-ns (try
                  (let [content (slurp file)
                        ns-form (read-string content)]
                    (when (and (seq? ns-form) (= 'ns (first ns-form)))
                      (second ns-form)))
                  (catch Exception _
                    nil))]

    (if test-ns
      (do
        (when verbose?
          (println (format "[watch] Re-collecting coverage for %s..." test-ns)))

        ;; Re-collect coverage for this namespace
        (try
          (coverage/collect-and-persist! config :namespaces [test-ns])
          (when verbose?
            (println "[watch] Coverage updated"))
          :collected
          (catch Exception e
            (when verbose?
              (println (format "[watch] Failed to collect: %s" (.getMessage e))))
            :error)))
      (when verbose?
        (println "[watch] Could not determine namespace from file")))))

;; =============================================================================
;; Debouncing
;; =============================================================================

(defn- debounce
  "Create a debounced function that only executes after delay-ms of quiet."
  [f delay-ms]
  (let [pending (atom nil)]
    (fn [& args]
      ;; Cancel any pending execution
      (when-let [^java.util.concurrent.Future fut @pending]
        (.cancel fut false))
      ;; Schedule new execution
      (reset! pending
              (future
                (Thread/sleep delay-ms)
                (apply f args))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start-watch!
  "Start watching source and test files for changes.

   Options:
   - :verbose - Print detailed progress (default: true)
   - :debounce-ms - Delay before reacting to changes (default: 300)

   Returns watcher handle that can be passed to stop!"
  [config & {:keys [verbose debounce-ms]
             :or {verbose true
                  debounce-ms 300}}]
  (println "Starting Heretic watch mode...")
  (println (format "  Watching source paths: %s" (str/join ", " (:source-paths config))))
  (println (format "  Watching test paths: %s" (str/join ", " (:test-paths config))))

  ;; Load coverage index
  (let [heretic-dir (:heretic-dir config)
        index (coverage/load-index heretic-dir)]

    (when-not index
      (println "Warning: No coverage data found. Run `collect!` first for targeted testing."))

    ;; Initialize reloader
    (reloader/init! (:source-paths config))

    ;; Create debounced handlers
    (let [source-handler (debounce
                          (fn [file]
                            (handle-source-change config index file verbose))
                          debounce-ms)
          test-handler (debounce
                        (fn [file]
                          (let [result (handle-test-change config file verbose)]
                            ;; After test coverage updates, we could re-run mutations
                            ;; but that might be noisy. Just update coverage for now.
                            result))
                        debounce-ms)]

      ;; Start file watcher
      (let [all-paths (concat (:source-paths config) (:test-paths config))
            watcher (hawk/watch!
                     [{:paths all-paths
                       :filter (fn [_ {:keys [file]}]
                                 (and file (clj-file? (.getPath file))))
                       :handler (fn [_ {:keys [kind file] :as event}]
                                  (when (and (#{:modify :create} kind) file)
                                    (let [path (.getPath file)]
                                      (cond
                                        (source-file? config file)
                                        (source-handler path)

                                        (test-file? config file)
                                        (test-handler path)))))}])]

        (reset! watcher-state {:watcher watcher
                               :config config
                               :index index
                               :started-at (now-ms)})

        (println)
        (println "Watch mode started. Press Ctrl+C to stop.")
        (println "Edit source files to trigger mutation testing.")
        (println)

        watcher))))

(defn stop!
  "Stop the file watcher."
  ([]
   (when-let [state @watcher-state]
     (stop! (:watcher state))))
  ([watcher]
   (when watcher
     (hawk/stop! watcher)
     (reset! watcher-state nil)
     (println "\nWatch mode stopped."))))

(defn status
  "Get current watch mode status."
  []
  (if-let [state @watcher-state]
    {:running? true
     :started-at (:started-at state)
     :uptime-ms (- (now-ms) (:started-at state))
     :config (:config state)}
    {:running? false}))

;; =============================================================================
;; Blocking Watch (for CLI)
;; =============================================================================

(defn watch!
  "Start watch mode and block until interrupted.

   This is the main entry point for CLI usage."
  [config & opts]
  (let [watcher (apply start-watch! config opts)]
    (try
      ;; Block forever (until interrupted)
      @(promise)
      (finally
        (stop! watcher)))))
