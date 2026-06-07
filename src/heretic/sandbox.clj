(ns heretic.sandbox
  "Run mutation testing against an isolated sandbox copy of the project.

   Heretic's `mutate` applies mutations by writing them into source files on
   disk (spit the mutation, reload, run the covering tests, spit the original
   back). That dirties the working tree for the whole run and can strand a
   mutation if the process is killed mid-flight (see docs/sandboxed-mutation.md
   and issue #2).

   This namespace resolves that WITHOUT touching the mutation engine: it copies
   the project into a throwaway sandbox and runs the *normal* `collect` +
   `mutate` pipeline there, in a child JVM whose working directory is the
   sandbox. The user's checkout is never on the write path, so:

   - the working tree stays clean during and after a run, and
   - a kill strands a mutation only in the disposable sandbox.

   Because the whole pipeline runs with one working directory (the sandbox),
   the coverage index's absolute canonical paths stay self-consistent with the
   mutation sites' paths — no remapping, no `:no-coverage` epidemic.

   The child is launched exactly like `bb.edn`'s `run-with-clojurestorm`
   (ClojureStorm JVM opts derived from the project's instrument prefixes), only
   with `:dir` set to the sandbox.

   Public entry point: `mutate-in-sandbox!`. By default the sandbox is
   persistent and reused incrementally between runs (only the namespaces whose
   source changed re-collect); `:fresh-sandbox` forces a clean rebuild this run
   and `:keep-sandbox false` makes each run a hermetic full copy. See
   docs/sandboxed-mutation.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; =============================================================================
;; Pure helpers (unit-tested directly)
;; =============================================================================

(defn storm-jvm-opts
  "Build the ClojureStorm JVM options for the child collect/mutate run.

   The single source of the ClojureStorm flags — bb.edn's tasks call this via
   `sandbox/storm-jvm-opts`. Always enables instrumentation, and adds the
   only/skip prefix filters when configured. Returns a vector of `-D...` strings
   (no `-J` prefix — see `child-command`)."
  [config]
  (let [prefixes (:instrument-prefixes config)
        skip (:instrument-skip-prefixes config)]
    (cond-> ["-Dclojure.storm.instrumentEnable=true"]
      (seq prefixes)
      (conj (str "-Dclojure.storm.instrumentOnlyPrefixes=" (str/join "," prefixes)))
      (seq skip)
      (conj (str "-Dclojure.storm.instrumentSkipPrefixes=" (str/join "," skip))))))

(defn child-code
  "Build the `-e` form the child JVM runs: load Heretic, run `mutate!` (which
   collects first via ensure-coverage! if the sandbox has no index), exit.

   `files` (optional) restricts mutation to specific source files."
  [files]
  (str "(require '[heretic.core :as heretic])"
       "(heretic/mutate! (heretic/load-config)"
       (when (seq files)
         (str " :files [" (str/join " " (map pr-str files)) "]"))
       ")"
       "(System/exit 0)"))

(defn child-command
  "Build the child process command vector:
   `clojure -J<storm opts> [-J<extra>] [-Sdeps <edn>] -M:<aliases> -e <code>`.

   Config keys, for projects whose Heretic invocation needs more than a plain
   `-M:collect`:
   - :sandbox-aliases  (default [\"collect\"]) - clj aliases the child runs under
                       (the alias putting ClojureStorm + test paths on classpath).
   - :sandbox-jvm-opts - extra JVM opts, each gets a `-J` prefix, e.g.
                       [\"-Xmx4g\" \"-Dclojure.storm.instrumentAutoPrefixes=false\"].
   - :sandbox-deps     - an `-Sdeps` EDN map merged into the child's deps, e.g. to
                       inject an alias putting Heretic's own source on the child
                       classpath: {:aliases {:heretic-src {:extra-paths [\"/abs/heretic/src\"]}}}.

   The orchestrator JVM itself needs none of this."
  [config code]
  (let [aliases (:sandbox-aliases config ["collect"])
        alias-arg (str "-M:" (str/join ":" aliases))
        j-flags (mapv #(str "-J" %)
                      (concat (storm-jvm-opts config) (:sandbox-jvm-opts config)))
        sdeps (when-let [d (:sandbox-deps config)] ["-Sdeps" (pr-str d)])]
    (-> (into ["clojure"] j-flags)
        (into (or sdeps []))
        (conj alias-arg "-e" code))))

(defn resolve-sandbox-dir
  "Absolute path of the sandbox directory. `:sandbox-dir` (default
   \".heretic-sandbox\") is resolved against `project-root` when relative."
  [config project-root]
  (let [d (:sandbox-dir config ".heretic-sandbox")
        f (io/file d)]
    (.getPath (if (.isAbsolute f) f (io/file project-root d)))))

(defn sync-entries
  "Top-level project entries to copy into the sandbox: the union of deps.edn
   `:paths`, the config's `:source-paths` and `:test-paths`, and the project
   files the child needs (deps.edn, heretic.edn, tests.edn, bb.edn). Filtered
   to those that actually exist under `project-root` (passed as a value, not
   read from the ambient working directory, so the helper is testable against a
   fixture root).

   `:sandbox-extra-paths` adds top-level entries the test classpath needs that
   aren't under deps `:paths`/source/test roots — e.g. a `cassettes/` dir of VCR
   recordings, or an alias's `:extra-paths`."
  [config project-root]
  (let [deps-paths (try (:paths (edn/read-string (slurp (io/file project-root "deps.edn"))))
                        (catch Exception _ nil))
        dirs (concat deps-paths (:source-paths config) (:test-paths config)
                     (:sandbox-extra-paths config))
        files ["deps.edn" "heretic.edn" "tests.edn" "bb.edn"]]
    (->> (concat dirs files)
         (distinct)
         (filter #(.exists (io/file project-root %)))
         (vec))))

(defn read-summary
  "Read the result map `mutate!` persisted to `<sandbox>/<heretic-dir>/
   mutation-results.edn` (the `:summary` value — same shape `mutate!` returns).
   Returns nil if the file is absent."
  [sandbox config]
  (let [f (io/file sandbox (:heretic-dir config ".heretic") "mutation-results.edn")]
    (when (.exists f)
      (:summary (edn/read-string (slurp f))))))

(defn absolutize-local-roots
  "Rewrite every relative `:local/root` path in a deps.edn map to an absolute
   path resolved against `project-root`, so local dependencies still resolve when
   the deps.edn is run from a different working directory (the sandbox). Without
   this, a project that depends on Heretic via e.g. `{:local/root \"../heretic\"}`
   would resolve that path against the sandbox dir and break.

   Absolute roots and non-local coordinates are left unchanged. Covers top-level
   `:deps` and each alias's `:extra-deps` / `:replace-deps` / `:override-deps`.
   Returns the deps map unchanged (`=`) when there is nothing to rewrite."
  [deps project-root]
  (let [abs-root (fn [root]
                   (let [f (io/file root)]
                     (if (.isAbsolute f)
                       root
                       (.getCanonicalPath (io/file project-root root)))))
        fix-coord (fn [coord]
                    (if (and (map? coord) (:local/root coord))
                      (update coord :local/root abs-root)
                      coord))
        fix-deps (fn [m]
                   (if (map? m)
                     (reduce-kv (fn [acc k v] (assoc acc k (fix-coord v))) {} m)
                     m))
        fix-alias (fn [a]
                    (cond-> a
                      (:extra-deps a)    (update :extra-deps fix-deps)
                      (:replace-deps a)  (update :replace-deps fix-deps)
                      (:override-deps a) (update :override-deps fix-deps)))]
    (cond-> deps
      (:deps deps)
      (update :deps fix-deps)
      (:aliases deps)
      (update :aliases
              (fn [as] (reduce-kv (fn [acc k v]
                                    (assoc acc k (if (map? v) (fix-alias v) v)))
                                  {} as))))))

;; =============================================================================
;; Filesystem (side effects)
;; =============================================================================

;; Dependency-free recursive copy/delete: the orchestrator JVM is plain Clojure
;; (no babashka.fs on its classpath), and the sandbox is a disposable copy, so a
;; small hand-rolled walk is preferred over pulling in a tree-walk dependency.

(defn delete-tree!
  "Recursively delete a file/directory. Idempotent — a no-op on an absent path."
  [^File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn copy-tree!
  "Recursively copy `src` to `dst`, skipping any directory/file whose name
   satisfies `exclude?`. Directory structure (and the entry's relative path) is
   preserved."
  [^File src ^File dst exclude?]
  (if (.isDirectory src)
    (do
      (.mkdirs dst)
      (doseq [c (.listFiles src)
              :when (not (exclude? (.getName c)))]
        (copy-tree! c (io/file dst (.getName c)) exclude?)))
    (do
      (io/make-parents dst)
      (io/copy src dst))))

(def ^:private base-excludes
  #{".heretic" "target" ".cpcache" ".git" "node_modules" ".clj-kondo" ".lsp"})

(defn- sync-tree!
  "Copy the project's classpath roots + config files (resolved under
   `project-root`) into a fresh sandbox, skipping VCS/build/coverage dirs and
   the sandbox itself. The entry's relative path is preserved (e.g. `src/main`
   lands at `sandbox/src/main`, not `sandbox/main`)."
  [config project-root sandbox]
  (let [excludes (conj base-excludes (.getName (io/file sandbox)))
        exclude? #(contains? excludes %)]
    (doseq [entry (sync-entries config project-root)]
      (copy-tree! (io/file project-root entry) (io/file sandbox entry) exclude?))))

(defn write-effective-config!
  "Write the orchestrator's resolved config as the sandbox's heretic.edn so the
   child JVM's (load-config) agrees with the orchestrator (test namespaces,
   operators, etc.) — single source of config truth."
  [config sandbox]
  (spit (io/file sandbox "heretic.edn") (pr-str config)))

(defn derive-process-child-config
  "Ergonomic wiring for `:executor :process` under the sandbox flow.

   The `:process` executor forks worker JVM(s) that must load instrumented code,
   so they need the SAME ClojureStorm classpath the sandbox child runs under
   (`child-command`). Rather than make a user re-specify it, default the worker
   spawn keys (`:child-aliases` / `:child-jvm-opts` / `:child-deps`) from the
   sandbox's own config — so `bb mutate` with just `:executor :process` works.
   Explicit `:child-*` keys are respected (only absent ones are filled).

   No-op unless `:executor` is `:process`."
  [config]
  (if (= :process (:executor config))
    (-> config
        (update :child-aliases  #(or % (:sandbox-aliases config ["collect"])))
        (update :child-jvm-opts #(or % (into (vec (storm-jvm-opts config))
                                             (:sandbox-jvm-opts config))))
        (update :child-deps     #(or % (:sandbox-deps config))))
    config))

(defn copy-project!
  "Public, self-contained project copier for per-worker filesystem isolation (B3b).

   Copies the project's classpath roots + config files (the `sync-entries` set)
   from `project-root` into a FRESH `target-dir`, then:
   - with `{:include-heretic true}` (default false) ALSO copies the existing
     `.heretic` coverage index so the worker child does NOT re-collect — it loads
     the index its copy carries;
   - absolutizes every relative `:local/root` in the copy's deps.edn against the
     ORIGINAL `project-root` (so e.g. `heretic/heretic {:local/root \"..\"}`
     resolves to the shared real Heretic source — only the TARGET source is copied
     + mutated per worker; Heretic itself is never copied);
   - writes the effective `config` as the copy's heretic.edn.

   Each copy is thus self-consistent: its index, its source, and the apply-target
   all live in ONE working dir — the single-sandbox path-consistency property the
   key-addressed pool relies on. `target-dir` is created fresh (any prior contents
   removed). Returns the absolute path of `target-dir`."
  [config project-root target-dir & {:keys [include-heretic] :or {include-heretic false}}]
  (let [target (io/file target-dir)
        ;; Exclude the target itself (in case it sits under project-root) and, by
        ;; default, .heretic. When :include-heretic, drop .heretic from the
        ;; exclude set so the index dir is copied with the source.
        base (if include-heretic (disj base-excludes ".heretic") base-excludes)
        excludes (conj base (.getName target))
        exclude? #(contains? excludes %)
        heretic-dir (:heretic-dir config ".heretic")]
    (delete-tree! target)
    (.mkdirs target)
    ;; Classpath roots + config files (relative paths preserved).
    (doseq [entry (sync-entries config project-root)]
      (copy-tree! (io/file project-root entry) (io/file target entry) exclude?))
    ;; The coverage index lives OUTSIDE the sync-entries set, so copy it
    ;; explicitly when requested (so the worker loads it instead of re-collecting).
    (when include-heretic
      (let [hd (io/file project-root heretic-dir)]
        (when (.isDirectory hd)
          (copy-tree! hd (io/file target heretic-dir) (constantly false)))))
    ;; Absolutize :local/root against the ORIGINAL project-root so shared local
    ;; deps (Heretic itself) still resolve from the copy's working dir.
    (let [src-deps (io/file project-root "deps.edn")]
      (when (.exists src-deps)
        (let [deps (edn/read-string (slurp src-deps))
              fixed (absolutize-local-roots deps project-root)]
          (spit (io/file target "deps.edn") (pr-str fixed)))))
    (write-effective-config! config target)
    (.getPath target)))

(defn- rewrite-deps-in-sandbox!
  "Absolutize relative :local/root paths in the sandbox's deps.edn so local deps
   (e.g. a project depending on Heretic via :local/root) still resolve when the
   child runs with the sandbox as its working directory. No-op when the project
   has no deps.edn or no relative local roots (the verbatim copy is kept)."
  [project-root sandbox]
  (let [src (io/file project-root "deps.edn")]
    (when (.exists src)
      (let [deps (edn/read-string (slurp src))
            fixed (absolutize-local-roots deps project-root)]
        (when (not= deps fixed)
          (spit (io/file sandbox "deps.edn") (pr-str fixed)))))))

(defn- copy-back!
  "Copy the run's durable artifacts out of the sandbox into the real project so
   `survivors`/reports work from the user's checkout: mutation-results.edn and
   the report output dir."
  [sandbox project-root config]
  (let [hd (:heretic-dir config ".heretic")
        results (io/file sandbox hd "mutation-results.edn")]
    (when (.exists results)
      (let [dst (io/file project-root hd "mutation-results.edn")]
        (io/make-parents dst)
        (io/copy results dst))))
  (let [out (:output-path config "target/heretic-report")
        out-src (io/file sandbox out)]
    (when (.exists out-src)
      (copy-tree! out-src (io/file project-root out) (constantly false)))))

(defn- run-process!
  "Run a command vector with stdout/stderr inherited (streams live), in `dir`.
   Returns the exit code."
  [cmd dir]
  (let [pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.directory (io/file dir))
             (.inheritIO))]
    (.waitFor (.start pb))))

(defn- rsync-available?
  "True when an `rsync` binary is on PATH (used for incremental sandbox reuse)."
  []
  (try
    (-> (doto (ProcessBuilder. ["rsync" "--version"])
          (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
          (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        (.start) (.waitFor) (zero?))
    (catch Exception _ false)))

(defn- rsync-into!
  "Incrementally mirror the project's classpath roots + config files into an
   EXISTING sandbox via `rsync -a --delete`, so only changed files are copied
   and deleted-from-source files are removed. The sandbox's own `.heretic`
   (coverage + timing data) is never under a synced root, so it survives — that
   is what lets the child's staleness machinery re-collect only what changed."
  [config project-root sandbox]
  (let [excludes (mapcat (fn [e] ["--exclude" e])
                         (conj base-excludes (.getName (io/file sandbox))))
        entries (sync-entries config project-root)
        {dirs true files false} (group-by #(.isDirectory (io/file project-root %)) entries)]
    ;; Directories: `rsync -aR --delete` (run from project-root with the relative
    ;; entry) preserves the entry's path (src/main -> sandbox/src/main) so nested
    ;; classpath roots survive, and --delete propagates within-dir removals.
    (doseq [d dirs]
      (run-process! (concat ["rsync" "-aR" "--delete"] excludes
                            [d (str sandbox "/")])
                    project-root))
    ;; Config files: overwrite-copy (rewrite-deps + write-config run after this).
    (doseq [f files]
      (io/make-parents (io/file sandbox f))
      (io/copy (io/file project-root f) (io/file sandbox f)))))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn mutate-in-sandbox!
  "Run mutation testing against an isolated copy of the project.

   Mirrors the project's classpath roots + config into a sandbox
   (`:sandbox-dir`, default \".heretic-sandbox\"), then runs the normal
   collect+mutate pipeline in a child JVM whose working directory is the
   sandbox. The working tree is never written to.

   Sandbox lifecycle (config keys):
   - :keep-sandbox  (default true)  - persist the sandbox between runs. The next
                    run rsyncs the working tree in incrementally and KEEPS the
                    sandbox's `.heretic`, so the child re-collects only the
                    namespaces whose source changed. `false` = one-shot: wipe
                    after the run, full copy each time (CI/hermetic).
   - :fresh-sandbox (default false) - force a clean rebuild + full re-collect
                    this run (escape hatch when you suspect sandbox drift).

   A fresh full copy is also used whenever the sandbox is absent or `rsync` is
   unavailable (the dep-free copy is correct but not incremental).

   Options:
   - :files        - restrict mutation to specific source files (passed to mutate!).
   - :project-root - project to copy (default: the JVM working directory). Passed
                     as a value so the orchestrator is testable against a fixture.

   Returns the same result-map shape `mutate!` returns (`:total`, `:killed`,
   `:survived`, `:no-coverage`, `:mutation-score`, `:survivors`, ...), with an
   added `:sandbox` metadata map (`:dir`, `:exit`, `:kept?`, `:reused?`). Returns
   `{:sandbox ... :error ...}` when the child failed or wrote no results."
  [config & {:keys [files project-root]}]
  (let [config (derive-process-child-config config)
        project-root (or project-root (System/getProperty "user.dir"))
        sandbox (resolve-sandbox-dir config project-root)
        sandbox-f (io/file sandbox)
        keep? (:keep-sandbox config true)
        ;; Reuse the persisted sandbox only in keep mode. With :keep-sandbox
        ;; false (hermetic/CI) every run is a full copy + full re-collect, as
        ;; documented — so a leftover sandbox from a prior keep-mode run can't
        ;; feed stale coverage into a run that promised to be fresh.
        reuse? (and keep?
                    (.exists sandbox-f)
                    (not (:fresh-sandbox config false))
                    (rsync-available?))]
    (println)
    (println "Sandboxed mutation run — working tree will not be modified.")
    (println "  Sandbox:" sandbox (if reuse? "(reusing — incremental sync)" "(fresh)"))
    (if reuse?
      (rsync-into! config project-root sandbox)
      (do (delete-tree! sandbox-f)
          (.mkdirs sandbox-f)
          (sync-tree! config project-root sandbox)))
    (rewrite-deps-in-sandbox! project-root sandbox)
    (write-effective-config! config sandbox)
    (try
      (let [cmd (child-command config (child-code files))
            _ (println "  Launching pipeline in sandbox ...")
            _ (println)
            exit (run-process! cmd sandbox)
            summary (read-summary sandbox config)
            sandbox-meta {:dir sandbox :exit exit :kept? (boolean keep?) :reused? reuse?}]
        (copy-back! sandbox project-root config)
        (if summary
          (assoc summary :sandbox sandbox-meta)
          {:sandbox sandbox-meta
           :error (str "Sandbox run produced no results (child exit " exit ").")}))
      (finally
        ;; Cleanup runs even if the child launch throws, so a wipe-mode run never
        ;; leaks a sandbox on a failed spawn.
        (when-not keep?
          (delete-tree! sandbox-f))))))

(defn clean-sandbox!
  "Delete the sandbox directory. Returns the path."
  [config]
  (let [sandbox (resolve-sandbox-dir config (System/getProperty "user.dir"))]
    (delete-tree! (io/file sandbox))
    sandbox))
