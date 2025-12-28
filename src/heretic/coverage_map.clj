(ns heretic.coverage-map
  "Coverage index building and queries.

   Manages the inverse index that maps code locations to tests:
   - Build index from per-namespace coverage files
   - Query tests for a given form-id and coordinate
   - Track source dependencies per test namespace

   Storage layout:
   .heretic/
   ├── meta.edn           # Global metadata + form registry
   ├── coverage/
   │   ├── ns1-test.edn   # Per-namespace coverage
   │   └── ns2-test.edn
   └── index.edn          # Derived inverse index"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heretic.collector :as collector]
            [heretic.persistence :as persist]
            [heretic.tracer :as tracer])
  (:import [clojure.storm FormRegistry]))

;; =============================================================================
;; Form Registry (ClojureStorm)
;; =============================================================================

(defn get-form-registry
  "Get all forms from ClojureStorm's FormRegistry.

   Returns {form-id -> {:form/ns, :form/form, :form/emitted-coords, ...}}

   ClojureStorm stores emitted-coords in the metadata of :form/form
   under :clojure.storm/emitted-coords as a java.util.HashSet.
   This function extracts and converts it to a Clojure set for each form.

   Form entry structure from FormRegistry:
   {:form/id       Long (hash)
    :form/ns       String
    :form/form     <the form data>
    :form/def-kind Keyword (:defn, :def, etc.)
    :form/file     String (relative path)
    :form/line     Integer}"
  []
  (into {}
        (for [form (FormRegistry/getAllForms)]
          [(:form/id form)
           (assoc form
                  :form/emitted-coords
                  (-> form :form/form meta :clojure.storm/emitted-coords set))])))

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
;; Inverse Index Building
;; =============================================================================

(defn build-inverse-index
  "Build form+coord -> tests index from coverage data.

   Takes sequence of coverage data maps (from per-namespace files).
   Returns {[form-id coord] -> #{test-symbols}}"
  [coverage-files]
  (reduce
   (fn [idx {:keys [coverage]}]
     (reduce-kv
      (fn [idx test-id form-coords]
        (reduce-kv
         (fn [idx form-id coords]
           (reduce
            (fn [idx coord]
              (update idx [form-id coord] (fnil conj #{}) test-id))
            idx
            coords))
         idx
         form-coords))
      idx
      coverage))
   {}
   coverage-files))

(defn- build-form-to-tests
  "Build form-id -> tests index from coord-to-tests.

   Aggregates all tests that hit any coordinate in each form for O(1) form-level lookup."
  [coord-to-tests]
  (reduce-kv
   (fn [acc [form-id _coord] tests]
     (update acc form-id (fnil into #{}) tests))
   {}
   coord-to-tests))

(defn rebuild-index!
  "Rebuild inverse index from all coverage files.

   Called after incremental updates to ensure index is consistent."
  [heretic-dir]
  (let [coverage-files (for [f (persist/list-coverage-files heretic-dir)]
                         (persist/load-edn f))
        coord-to-tests (build-inverse-index coverage-files)
        form-to-tests (build-form-to-tests coord-to-tests)
        included-ns (into #{} (map :test-ns coverage-files))]
    (persist/save-index! heretic-dir
                         {:coord-to-tests coord-to-tests
                          :form-to-tests form-to-tests
                          :included-test-ns included-ns
                          :rebuilt-at (System/currentTimeMillis)})))

;; =============================================================================
;; Queries
;; =============================================================================

(defn load-index
  "Load the inverse index from disk."
  [heretic-dir]
  (persist/load-index heretic-dir))

(defn tests-for-location
  "Given a form-id and optional coord, return tests that hit it.

   With coord: Returns tests that hit that specific coordinate.
   Without coord: Returns all tests that hit any coordinate in the form (O(1) lookup)."
  ([index form-id]
   ;; Use form-to-tests index for O(1) form-level lookup
   (get-in index [:form-to-tests form-id] #{}))

  ([index form-id coord]
   (get-in index [:coord-to-tests [form-id coord]] #{})))

(defn uncovered-coords
  "Find coordinates that have no test coverage.

   Returns sequence of [form-id coord] pairs."
  [index forms]
  (for [[form-id {:keys [form/emitted-coords]}] forms
        coord emitted-coords
        :when (empty? (tests-for-location index form-id coord))]
    [form-id coord]))

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
        _ (println "  Tracer initialized")

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
        forms (get-form-registry)
        _ (println "  Found" (count forms) "forms")

        ;; Collect each stale namespace (using into [] for eager evaluation)
        ;; Side effects happen in the body, so we use reduce rather than lazy for
        collected (reduce
                   (fn [acc ns-sym]
                     (println "  Collecting" ns-sym "...")
                     (let [coverage-data (collect-test-namespace! ns-sym forms source-paths config)]
                       (persist/save-test-ns-coverage! heretic-dir coverage-data)
                       (conj acc coverage-data)))
                   []
                   stale-ns)

        collected-count (count collected)

        ;; Save global metadata
        _ (persist/save-meta! heretic-dir
                              {:forms forms
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
