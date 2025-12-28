(ns heretic.coverage-map.workflow
  "Coverage collection workflow orchestration.

   This module contains the I/O-heavy workflow code for:
   - Discovering and loading test namespaces
   - Running tests and collecting coverage
   - Persisting coverage data to disk
   - Rebuilding the inverse index

   Most of the actual work delegates to pure functions in other modules."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heretic.collector :as collector]
            [heretic.coverage-map.index :as index]
            [heretic.coverage-map.registry :as registry]
            [heretic.form-bridge :as bridge]
            [heretic.persistence :as persist]
            [heretic.tracer :as tracer]))

;; =============================================================================
;; Source Dependency Tracking
;; =============================================================================

(defn- ns->file-path
  "Convert namespace symbol to file path (without source root)."
  [ns-sym]
  (-> (str ns-sym)
      (str/replace "." "/")
      (str/replace "-" "_")
      (str ".clj")))

(defn- find-source-file
  "Find the actual source file for a namespace in the given source paths."
  [ns-sym source-paths]
  (let [rel-path (ns->file-path ns-sym)]
    (some (fn [source-path]
            (let [f (io/file source-path rel-path)]
              (when (.exists f)
                (.getPath f))))
          source-paths)))

(defn extract-source-deps
  "Given coverage data, determine which source files were touched.

   Uses FormRegistry to map form-ids to namespaces, then to file paths.
   Returns set of source file paths."
  [coverage forms source-paths]
  (let [touched-ns (into #{}
                         (for [[_ form-coords] coverage
                               form-id (keys form-coords)
                               :let [ns-str (get-in forms [form-id :form/ns])]
                               :when ns-str]
                           (symbol ns-str)))]
    ;; Map namespaces to actual file paths
    (into #{}
          (for [ns-sym touched-ns
                :let [path (find-source-file ns-sym source-paths)]
                :when path]
            path))))

;; =============================================================================
;; Per-Namespace Coverage Collection
;; =============================================================================

(defn collect-test-namespace!
  "Collect coverage for a single test namespace.

   Returns per-namespace coverage data structure ready for persistence."
  [test-ns forms source-paths config]
  (let [{:keys [test-coverage]} (collector/collect-coverage-for-ns test-ns)
        source-deps (extract-source-deps test-coverage forms source-paths)
        test-file (find-source-file test-ns (:test-paths config))]
    {:test-ns test-ns
     :coverage test-coverage
     :source-deps source-deps
     :hashes {:test-file (persist/hash-file test-file)
              :source-files (persist/hash-files source-deps)
              :config (hash config)}}))

;; =============================================================================
;; Index Rebuilding
;; =============================================================================

(defn rebuild-index!
  "Rebuild inverse index from all coverage files.

   Called after incremental updates to ensure index is consistent.
   Also includes the form-location-index from metadata for mutation site bridging."
  [heretic-dir]
  (let [coverage-files (for [f (persist/list-coverage-files heretic-dir)]
                         (persist/load-edn f))
        meta-data (persist/load-meta heretic-dir)
        form-location-index (or (:form-location-index meta-data) {})
        new-index (index/build-index coverage-files form-location-index)]
    (persist/save-index! heretic-dir new-index)))

;; =============================================================================
;; Full Collection Workflow
;; =============================================================================

(defn collect-and-persist!
  "Full collection workflow: collect coverage and persist to disk.

   Options:
   - :force - Recollect all namespaces (ignore staleness)
   - :namespaces - Specific namespaces to collect (symbols or strings)

   Returns collection statistics map:
   {:total-ns      N
    :stale-ns      N
    :collected-ns  N
    :forms         N
    :duration-ms   N}"
  [config & {:keys [force namespaces]}]
  (let [start-time (System/currentTimeMillis)
        heretic-dir (or (:heretic-dir config) persist/default-dir)
        source-paths (:source-paths config)
        test-paths (:test-paths config)

        ;; Ensure directories exist
        _ (persist/ensure-heretic-dir! heretic-dir)

        ;; Initialize tracer
        _ (tracer/init!)

        ;; Discover test namespaces
        _ (println "  Discovering test namespaces in:" test-paths)
        all-test-ns (if (= :all (:test-namespaces config))
                      (collector/discover-test-namespaces test-paths)
                      (:test-namespaces config))
        _ (println "  Found namespaces:" (vec all-test-ns))

        ;; Filter to requested namespaces if specified
        target-ns (if namespaces
                    (let [ns-set (set (map symbol namespaces))]
                      (filter ns-set all-test-ns))
                    all-test-ns)

        ;; Find stale namespaces
        stale-ns (if force
                   (set target-ns)
                   (persist/find-stale-test-namespaces
                    heretic-dir target-ns test-paths source-paths config))

        ;; Get form registry (after loading test namespaces)
        _ (println "  Loading" (count stale-ns) "stale namespaces...")
        _ (doseq [ns-sym stale-ns]
            (println "    Loading" ns-sym)
            (require ns-sym))
        _ (println "  Getting form registry...")
        forms (registry/get-form-registry)
        _ (println "  Found" (count forms) "forms")

        ;; Collect each stale namespace
        collected (reduce
                   (fn [acc ns-sym]
                     (println "  Collecting" ns-sym "...")
                     (let [coverage-data (collect-test-namespace! ns-sym forms source-paths config)]
                       (persist/save-test-ns-coverage! heretic-dir coverage-data)
                       (conj acc coverage-data)))
                   []
                   stale-ns)

        collected-count (count collected)

        ;; Build form location index for mutation site -> coverage bridging
        form-location-index (bridge/build-form-location-index forms (concat source-paths test-paths))

        ;; Save global metadata
        _ (persist/save-meta! heretic-dir
                              {:forms forms
                               :form-location-index form-location-index
                               :collected-at (System/currentTimeMillis)
                               :heretic-version "0.1.0"})

        ;; Rebuild inverse index
        _ (rebuild-index! heretic-dir)

        ;; Shutdown tracer
        _ (tracer/shutdown!)

        end-time (System/currentTimeMillis)]

    {:total-ns (count target-ns)
     :stale-ns (count stale-ns)
     :collected-ns collected-count
     :forms (count forms)
     :duration-ms (- end-time start-time)}))
