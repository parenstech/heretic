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
  (:require [heretic.collector :as collector]
            [heretic.persistence :as persist]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Form Registry (ClojureStorm)
;; =============================================================================

(defn get-form-registry
  "Get all forms from ClojureStorm's FormRegistry.

   Returns {form-id -> {:form/ns, :form/form, :form/emitted-coords}}"
  []
  ;; TODO: Implement FormRegistry access
  ;; Requires ClojureStorm on classpath:
  ;; (into {} (FormRegistry/getAllForms))
  (throw (ex-info "FormRegistry access not yet implemented"
                  {:hint "Requires ClojureStorm on classpath"})))

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

(defn rebuild-index!
  "Rebuild inverse index from all coverage files.

   Called after incremental updates to ensure index is consistent."
  [heretic-dir]
  (let [coverage-files (for [f (persist/list-coverage-files heretic-dir)]
                         (persist/load-edn f))
        coord-to-tests (build-inverse-index coverage-files)
        included-ns (into #{} (map :test-ns coverage-files))]
    (persist/save-index! heretic-dir
                         {:coord-to-tests coord-to-tests
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
   Without coord: Returns all tests that hit any coordinate in the form."
  ([index form-id]
   ;; For form-level lookup, scan all coords for this form-id
   (into #{}
         (for [[[fid _] tests] (:coord-to-tests index)
               :when (= fid form-id)
               test tests]
           test)))

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
   - :force - Recollect all namespaces
   - :namespaces - Specific namespaces to collect

   Returns collection statistics."
  [config & {:keys [force namespaces]}]
  ;; TODO: Implement full workflow
  ;; 1. Initialize tracer
  ;; 2. Get form registry
  ;; 3. Find stale namespaces (or all if force)
  ;; 4. Collect each stale namespace
  ;; 5. Persist coverage files
  ;; 6. Save form registry to meta.edn
  ;; 7. Rebuild index
  ;; 8. Return statistics
  (throw (ex-info "Full collection workflow not yet implemented" {})))
