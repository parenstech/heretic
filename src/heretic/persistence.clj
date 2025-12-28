(ns heretic.persistence
  "Split storage and atomic file operations.

   Manages the .heretic directory structure:
   - Per-namespace coverage files for incremental updates
   - Global metadata (form registry)
   - Derived inverse index

   All writes are atomic (temp file + rename) to prevent corruption
   if the process crashes mid-write.

   Staleness detection checks file hashes to determine which test
   namespaces need recollection."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

;; =============================================================================
;; Malli Schemas
;; =============================================================================

(def Coord
  "A ClojureStorm coordinate string (e.g., \"3,2,1\" or \"\" for root)."
  :string)

(def FormId
  "A form identifier from ClojureStorm's FormRegistry (computed hash as long)."
  :int)

(def FormCoverage
  "Coverage data for a single form: set of covered coordinate strings."
  [:set Coord])

(def TestVarCoverage
  "Coverage data for a single test var: form-id -> set of coords."
  [:map-of FormId FormCoverage])

(def Hashes
  "Staleness detection hashes.
   Note: :test-file and :source-files are SHA-256 hex strings from hash-file/hash-files,
   while :config is a Clojure hash integer from (hash config)."
  [:map
   [:test-file [:maybe :string]]
   [:source-files [:maybe :string]]
   [:config [:maybe :int]]])

(def TestNsCoverage
  "Per-test-namespace coverage data stored in .heretic/coverage/<ns>.edn.

   Structure:
   - :test-ns - the test namespace symbol
   - :coverage - map from test var symbol to form coverage
   - :source-deps - set of source file paths this test namespace touched
   - :hashes - staleness detection hashes"
  [:map
   {:closed true}
   [:test-ns :symbol]
   [:coverage [:map-of :symbol TestVarCoverage]]
   [:source-deps [:set :string]]
   [:hashes Hashes]])

;; =============================================================================
;; Schema Validation
;; =============================================================================

(defn validate-test-ns-coverage
  "Validate coverage data against TestNsCoverage schema.
   Returns {:valid true} on success, or {:valid false :errors [...]} on failure."
  [data]
  (if (m/validate TestNsCoverage data)
    {:valid true}
    {:valid false
     :errors (me/humanize (m/explain TestNsCoverage data))}))

(def default-dir ".heretic")

;; =============================================================================
;; Atomic File Operations
;; =============================================================================

(defn- atomic-spit!
  "Write content atomically using temp file + rename.
   Prevents corruption if process crashes mid-write."
  [path content]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (io/make-parents path)
    (let [temp-file (File/createTempFile "heretic" ".tmp" parent)]
      (try
        (spit temp-file content)
        (Files/move (.toPath temp-file)
                    (.toPath file)
                    (into-array [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (catch Exception e
          (.delete temp-file)
          (throw e))))))

(defn save-edn!
  "Save EDN data atomically to the given path."
  [path data]
  (atomic-spit! path (pr-str data)))

(defn load-edn
  "Load EDN data from disk. Returns nil if file doesn't exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

;; =============================================================================
;; Hashing for Staleness Detection
;; =============================================================================

(defn- sha256
  "Compute SHA-256 hash of a string, return hex string."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn hash-file
  "Compute hash of a single file's contents.
   Returns nil if file doesn't exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (sha256 (slurp f)))))

(defn hash-files
  "Compute combined hash of multiple files.
   Hashes are sorted to ensure deterministic result regardless of order."
  [paths]
  (let [file-hashes (for [path paths
                          :let [h (hash-file path)]
                          :when h]
                      h)]
    (when (seq file-hashes)
      (sha256 (str/join "" (sort file-hashes))))))

;; =============================================================================
;; Per-Namespace Coverage Files
;; =============================================================================

(defn coverage-file-path
  "Get path for a test namespace's coverage file."
  [heretic-dir test-ns]
  (io/file heretic-dir "coverage"
           (str (str/replace (str test-ns) "." "-") ".edn")))

(defn save-test-ns-coverage!
  "Save coverage data for a single test namespace."
  [heretic-dir coverage-data]
  (let [path (coverage-file-path heretic-dir (:test-ns coverage-data))]
    (save-edn! path coverage-data)))

(defn load-test-ns-coverage
  "Load coverage data for a single test namespace.
   Returns nil if no coverage file exists.
   Throws ex-info with :type :corrupted-coverage-file if validation fails."
  [heretic-dir test-ns]
  (when-let [data (load-edn (coverage-file-path heretic-dir test-ns))]
    (let [{:keys [valid errors]} (validate-test-ns-coverage data)]
      (if valid
        data
        (throw (ex-info "Corrupted coverage file detected"
                        {:type :corrupted-coverage-file
                         :test-ns test-ns
                         :file (str (coverage-file-path heretic-dir test-ns))
                         :errors errors}))))))

(defn delete-test-ns-coverage!
  "Delete coverage file for a test namespace.
   Used when a test namespace is deleted from the project."
  [heretic-dir test-ns]
  (let [f (coverage-file-path heretic-dir test-ns)]
    (when (.exists f)
      (.delete f))))

(defn list-coverage-files
  "List all coverage files in the heretic directory.
   Returns sequence of File objects."
  [heretic-dir]
  (let [coverage-dir (io/file heretic-dir "coverage")]
    (when (.exists coverage-dir)
      (for [f (.listFiles coverage-dir)
            :when (.endsWith (.getName f) ".edn")]
        f))))

;; =============================================================================
;; Staleness Detection (Per-Namespace)
;; =============================================================================

(defn test-ns-stale?
  "Check if a single test namespace's coverage needs regeneration.

   Stale if:
   - Coverage file doesn't exist
   - Test file changed (hash mismatch)
   - Any source file it depends on changed
   - Config changed"
  [heretic-dir test-ns test-paths source-paths config]
  (let [coverage-data (load-test-ns-coverage heretic-dir test-ns)]
    (if (nil? coverage-data)
      true  ;; No coverage file -> stale
      (let [{stored-hashes :hashes
             source-deps :source-deps} coverage-data
            ;; Find test file path
            test-file-path (some (fn [test-path]
                                   (let [rel (-> (str test-ns)
                                                 (str/replace "." "/")
                                                 (str/replace "-" "_")
                                                 (str ".clj"))
                                         f (io/file test-path rel)]
                                     (when (.exists f)
                                       (.getPath f))))
                                 test-paths)
            current-test-hash (hash-file test-file-path)
            current-source-hash (hash-files source-deps)
            current-config-hash (hash config)]
        (or (not= (:test-file stored-hashes) current-test-hash)
            (not= (:source-files stored-hashes) current-source-hash)
            (not= (:config stored-hashes) current-config-hash))))))

(defn find-stale-test-namespaces
  "Find all test namespaces that need recollection.
   Returns set of namespace symbols."
  [heretic-dir test-namespaces test-paths source-paths config]
  (into #{}
        (filter #(test-ns-stale? heretic-dir % test-paths source-paths config))
        test-namespaces))

;; =============================================================================
;; Global Metadata
;; =============================================================================

(defn save-meta!
  "Save global metadata (form registry, version, timestamp)."
  [heretic-dir meta-data]
  (save-edn! (io/file heretic-dir "meta.edn") meta-data))

(defn load-meta
  "Load global metadata."
  [heretic-dir]
  (load-edn (io/file heretic-dir "meta.edn")))

;; =============================================================================
;; Index (Derived, Rebuilt)
;; =============================================================================

(defn save-index!
  "Save derived inverse index."
  [heretic-dir index-data]
  (save-edn! (io/file heretic-dir "index.edn") index-data))

(defn load-index
  "Load derived inverse index."
  [heretic-dir]
  (load-edn (io/file heretic-dir "index.edn")))

;; =============================================================================
;; Directory Management
;; =============================================================================

(defn ensure-heretic-dir!
  "Ensure the .heretic directory and subdirectories exist."
  [heretic-dir]
  (let [coverage-dir (io/file heretic-dir "coverage")]
    (when-not (.exists coverage-dir)
      (.mkdirs coverage-dir))))

(defn clean-heretic-dir!
  "Remove the .heretic directory entirely."
  [heretic-dir]
  (let [dir (io/file heretic-dir)]
    (when (.exists dir)
      ;; Delete recursively (files first, then directories)
      (doseq [f (reverse (file-seq dir))]
        (.delete f))
      true)))
