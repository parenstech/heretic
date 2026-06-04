(ns heretic.watch
  "File watching for continuous, sandboxed mutation testing.

   On each source-file change, Heretic runs the mutation pipeline against an
   isolated sandbox copy (heretic.sandbox) scoped to that file — so the file you
   are editing is never modified in place. The sandbox is persistent and synced
   incrementally between changes, so only the namespaces whose source actually
   changed are re-collected.

   Each change spawns a child JVM (the sandboxed run), so feedback is on the
   order of seconds, not instant — this is mutation testing, not unit-test watch.

   Usage:
   (def watcher (start-watch! config))
   ;; ... edit code ...
   (stop! watcher)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nextjournal.beholder :as beholder]
            [heretic.sandbox :as sandbox]))

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

(defn- canon [p]
  (.getCanonicalPath (io/file p)))

(defn- in-path? [paths path-str]
  (let [fp (canon path-str)]
    (some (fn [dir] (str/starts-with? fp (canon dir))) paths)))

(defn- source-file? [config path]
  (in-path? (:source-paths config) path))

(defn- test-file? [config path]
  (in-path? (:test-paths config) path))

(defn- relativize
  "Path relative to project-root (what mutate-in-sandbox! :files expects — the
   child resolves :files against its cwd = the sandbox)."
  [project-root path]
  (str (.relativize (.toPath (io/file (canon project-root)))
                    (.toPath (io/file (canon path))))))

;; =============================================================================
;; Change Handlers
;; =============================================================================

(defn- handle-source-change
  "Run a sandboxed mutation scoped to the changed source file."
  [config project-root path verbose?]
  (let [rel (relativize project-root path)]
    (when verbose?
      (println (format "\n[watch] Source changed: %s — running sandboxed mutation..." rel)))
    (let [r (sandbox/mutate-in-sandbox! config :files [rel])]
      (if (:error r)
        (println (format "[watch] %s" (:error r)))
        (println (format "[watch] %d killed / %d survived / %d no-coverage  (score %.1f%%)"
                         (:killed r 0) (:survived r 0) (:no-coverage r 0)
                         (* 100.0 (double (or (:mutation-score r) 0)))))))))

(defn- handle-test-change
  "A test-only change refreshes that namespace's coverage on the next sandboxed
   run (the sandbox re-collects stale namespaces automatically), so there is
   nothing to mutate here — just note it."
  [project-root path verbose?]
  (when verbose?
    (println (format "\n[watch] Test changed: %s — its coverage refreshes on the next source-change run."
                     (relativize project-root path)))))

;; =============================================================================
;; Debouncing
;; =============================================================================

(defn- debounce
  "Create a debounced function that only executes after delay-ms of quiet."
  [f delay-ms]
  (let [pending (atom nil)]
    (fn [& args]
      (when-let [^java.util.concurrent.Future fut @pending]
        (.cancel fut false))
      (reset! pending
              (future
                (Thread/sleep delay-ms)
                (apply f args))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start-watch!
  "Start watching source and test files for changes, running a sandboxed
   mutation on each source change (the working tree is never modified).

   Options:
   - :verbose - Print detailed progress (default: true)
   - :debounce-ms - Delay before reacting to changes (default: 2000). A change
     starts a sandboxed run (a child JVM), so a longer window coalesces rapid
     save bursts into a single run.

   Returns a watcher handle that can be passed to stop!."
  [config & {:keys [verbose debounce-ms]
             :or {verbose true
                  debounce-ms 2000}}]
  (let [project-root (System/getProperty "user.dir")]
    (println "Starting Heretic watch mode (sandboxed — your working tree is never modified)...")
    (println (format "  Watching: %s"
                     (str/join ", " (concat (:source-paths config) (:test-paths config)))))

    (let [source-handler (debounce
                          (fn [path] (handle-source-change config project-root path verbose))
                          debounce-ms)
          test-handler (debounce
                        (fn [path] (handle-test-change project-root path verbose))
                        debounce-ms)
          all-paths (vec (concat (:source-paths config) (:test-paths config)))
          handler (fn [{:keys [type path]}]
                    ;; beholder delivers :path as a java.nio.file.Path — coerce to a string.
                    (let [path (str path)]
                      (when (and (#{:modify :create} type)
                                 (clj-file? path))
                        (cond
                          (source-file? config path) (source-handler path)
                          (test-file? config path) (test-handler path)))))
          watcher (apply beholder/watch handler all-paths)]

      (reset! watcher-state {:watcher watcher
                             :config config
                             :started-at (now-ms)})

      (println)
      (println "Watch mode started. Press Ctrl+C to stop.")
      (println "Edit source files to trigger sandboxed mutation testing.")
      (println)

      watcher)))

(defn stop!
  "Stop the file watcher."
  ([]
   (when-let [state @watcher-state]
     (stop! (:watcher state))))
  ([watcher]
   (when watcher
     (beholder/stop watcher)
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
  "Start watch mode and block until interrupted. Main entry point for CLI usage."
  [config & opts]
  (let [watcher (apply start-watch! config opts)]
    (try
      @(promise)
      (finally
        (stop! watcher)))))
