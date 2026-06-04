(ns heretic.incremental
  "Incremental mutation testing support.

   Tracks which source forms have been mutated and their content hashes.
   On subsequent runs, only forms that have changed need re-mutation.

   Storage layout:
   .heretic/
   ├── form-hashes.edn  # {file -> {form-id -> hash}}

   Main API:
   - `load-form-hashes` - Load previous form hash data
   - `save-form-hashes!` - Persist form hash data
   - `compute-form-hash` - Hash a source form
   - `changed-forms` - Find forms that have changed since last run"
  (:require [clojure.java.io :as io]
            [clojure.set]
            [heretic.persistence :as persist]
            [heretic.parser :as parser]
            [rewrite-clj.zip :as z])
  (:import [java.security MessageDigest]
           [java.util Base64]))

;; =============================================================================
;; Hashing
;; =============================================================================

(defn compute-hash
  "Compute SHA-256 hash of a string, returning base64-encoded result."
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes s "UTF-8"))]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn compute-form-hash
  "Compute hash of a source form's content.

   Arguments:
   - zloc: Zipper positioned at the form

   Returns base64-encoded SHA-256 hash of the form's string representation."
  [zloc]
  (when zloc
    (compute-hash (z/string zloc))))

;; =============================================================================
;; Storage
;; =============================================================================

(defn- hash-file-path
  "Get path for form hash data file."
  [heretic-dir]
  (io/file heretic-dir "form-hashes.edn"))

(defn load-form-hashes
  "Load form hash data from disk.

   Returns map of {file-path -> {form-id -> hash-string}}
   or nil if no data exists."
  [heretic-dir]
  (persist/load-edn (hash-file-path heretic-dir)))

(defn save-form-hashes!
  "Save form hash data to disk atomically."
  [heretic-dir hash-data]
  (persist/save-edn! (hash-file-path heretic-dir) hash-data))

;; =============================================================================
;; Change Detection
;; =============================================================================

(defn- clojure-file?
  "Returns true if file is a Clojure source file (.clj or .cljc)."
  [f]
  (let [name (.getName f)]
    (or (.endsWith name ".clj")
        (.endsWith name ".cljc"))))

(defn- find-clj-files
  "Recursively find all Clojure files (.clj and .cljc) in directories."
  [source-paths]
  (for [dir source-paths
        :let [d (io/file dir)]
        :when (.exists d)
        f (file-seq d)
        :when (and (.isFile f) (clojure-file? f))]
    (.getPath f)))

(defn hash-file-forms
  "Compute hashes for all top-level forms in a file.

   Arguments:
   - file-path: Path to the source file

   Returns map of {form-id -> hash-string} for each top-level form.
   Form-id is the index of the form (0, 1, 2, ...)."
  [file-path]
  (try
    (when-let [zloc (parser/parse-file file-path)]
      (loop [current (z/down zloc)
             form-id 0
             result {}]
        (if (nil? current)
          result
          (let [hash (compute-form-hash current)]
            (recur (z/right current)
                   (inc form-id)
                   (assoc result form-id hash))))))
    (catch Exception _
      nil)))

(defn- compute-current-hashes
  "Compute hashes for all source files.

   Arguments:
   - source-paths: Sequence of source directory paths

   Returns map of {file-path -> {form-id -> hash-string}}."
  [source-paths]
  (let [clj-files (find-clj-files source-paths)]
    (reduce (fn [acc file-path]
              (if-let [hashes (hash-file-forms file-path)]
                (assoc acc file-path hashes)
                acc))
            {}
            clj-files)))

(defn changed-forms
  "Find forms that have changed since the last run.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - source-paths: Sequence of source directory paths

   Returns:
   {:changed-files #{file-paths...}  ; Files with any changed forms
    :changed-forms {file -> #{form-ids...}}  ; Specific forms that changed
    :new-files #{file-paths...}  ; Files not in previous data
    :deleted-files #{file-paths...}  ; Files in previous data but gone
    :current-hashes {file -> {form-id -> hash}}  ; All current hashes}"
  [heretic-dir source-paths]
  (let [previous (or (load-form-hashes heretic-dir) {})
        current (compute-current-hashes source-paths)
        prev-files (set (keys previous))
        curr-files (set (keys current))
        new-files (clojure.set/difference curr-files prev-files)
        deleted-files (clojure.set/difference prev-files curr-files)
        common-files (clojure.set/intersection prev-files curr-files)]
    (loop [files (seq common-files)
           changed-files #{}
           changed-forms {}]
      (if-not files
        {:changed-files (into changed-files new-files)
         :changed-forms (reduce (fn [acc file]
                                  ;; All forms in new files are "changed"
                                  (assoc acc file (set (keys (get current file)))))
                                changed-forms
                                new-files)
         :new-files new-files
         :deleted-files deleted-files
         :current-hashes current}
        (let [file (first files)
              prev-forms (get previous file {})
              curr-forms (get current file {})
              curr-ids (set (keys curr-forms))
              ;; Find forms where hash differs or form is new
              changed-in-file (filter (fn [form-id]
                                        (let [prev-hash (get prev-forms form-id)
                                              curr-hash (get curr-forms form-id)]
                                          (or (nil? prev-hash)  ; New form
                                              (not= prev-hash curr-hash))))  ; Changed
                                      curr-ids)
              changed-set (set changed-in-file)]
          (if (seq changed-set)
            (recur (next files)
                   (conj changed-files file)
                   (assoc changed-forms file changed-set))
            (recur (next files)
                   changed-files
                   changed-forms)))))))

(defn filter-mutations-by-changes
  "Filter mutations to only those in changed forms.

   Arguments:
   - mutations: Sequence of mutation records with :file and :form-id
   - change-data: Result from `changed-forms`

   Returns:
   {:mutations [...filtered mutations...]
    :skipped-count n
    :reason :incremental}"
  [mutations change-data]
  (let [changed-forms-map (:changed-forms change-data)
        changed-files (:changed-files change-data)]
    (if (empty? changed-files)
      {:mutations []
       :skipped-count (count mutations)
       :reason :no-changes}
      (let [filtered (filter (fn [m]
                               (let [file (:file m)
                                     form-id (:form-id m)]
                                 (and (contains? changed-files file)
                                      (or (nil? form-id)  ; Can't determine form-id, include
                                          (contains? (get changed-forms-map file #{})
                                                     form-id)))))
                             mutations)]
        {:mutations (vec filtered)
         :skipped-count (- (count mutations) (count filtered))
         :reason :incremental}))))

(defn update-form-hashes!
  "Update stored form hashes after a mutation run.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - current-hashes: Map from `changed-forms` :current-hashes"
  [heretic-dir current-hashes]
  (save-form-hashes! heretic-dir current-hashes))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn incremental-stats
  "Generate statistics about incremental mutation filtering.

   Arguments:
   - change-data: Result from `changed-forms`
   - original-count: Number of mutations before filtering
   - filtered-count: Number after filtering

   Returns map with change counts and savings."
  [change-data original-count filtered-count]
  (let [skipped (- original-count filtered-count)
        pct-skipped (if (pos? original-count)
                      (* 100.0 (/ skipped original-count))
                      0.0)]
    {:changed-file-count (count (:changed-files change-data))
     :new-file-count (count (:new-files change-data))
     :deleted-file-count (count (:deleted-files change-data))
     :original-mutations original-count
     :filtered-mutations filtered-count
     :skipped-mutations skipped
     :savings-percentage pct-skipped}))
