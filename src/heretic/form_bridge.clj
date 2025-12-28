(ns heretic.form-bridge
  "Bridge mutation sites to ClojureStorm form-ids.

   The mutation engine computes form-id from source (hash of source string
   via rewrite-clj), but coverage uses ClojureStorm's form-id (computed during
   compilation). This namespace bridges the gap.

   Key insight: ClojureStorm stores [file, line] for each form in its registry.
   We can map mutation sites to coverage by:
   1. Building a form-location-index: {[file, line] -> form-id}
   2. Finding the containing form for a mutation by looking up file + line

   Since mutation sites can be anywhere within a form, we find the form whose
   start line is the largest value <= mutation line (the containing form).

   This module is pure - it takes data and returns data, no I/O."
  (:require [clojure.java.io :as io]))

;; =============================================================================
;; Form Location Index Building
;; =============================================================================

(defn build-form-location-index
  "Build a lookup from [file line] -> form-id for bridging mutation sites to coverage.

   Arguments:
   - forms: Map of {form-id -> {:form/file, :form/line, ...}} from ClojureStorm
   - source-paths: Sequence of source directories to resolve relative paths

   Returns {[absolute-file-path line] -> form-id}

   Pure function."
  [forms source-paths]
  (into {}
        (for [[form-id {:keys [form/file form/line]}] forms
              :when (and file line)
              :let [abs-path (some (fn [src-path]
                                     (let [f (io/file src-path file)]
                                       (when (.exists f)
                                         (.getCanonicalPath f))))
                                   source-paths)]
              :when abs-path]
          [[abs-path line] form-id])))

;; =============================================================================
;; Form-ID Resolution
;; =============================================================================

(defn- canonical-path
  "Get canonical path for a file, or return the original path if it fails."
  [file]
  (try
    (.getCanonicalPath (io/file file))
    (catch Exception _ file)))

(defn find-containing-form
  "Find the form that contains the given file and line.

   Since mutation sites can be anywhere within a form, we find the form whose
   start line is the largest value <= the given line (the containing form).

   Arguments:
   - form-location-index: Map of {[file, line] -> form-id}
   - file: Absolute file path
   - line: Line number of the mutation site

   Returns the form-id of the containing form, or nil if not found.

   Pure function."
  [form-location-index file line]
  (when (and form-location-index file line)
    (let [canonical-file (canonical-path file)]
      (->> form-location-index
           ;; Filter to forms in this file that start at or before our line
           (filter (fn [[[f l] _form-id]]
                     (and (= f canonical-file)
                          (<= l line))))
           ;; Sort by line descending to get the closest containing form first
           (sort-by (fn [[[_ l] _]] l) >)
           ;; Take the first match (form with largest start-line <= mutation line)
           first
           ;; Extract the form-id
           second))))

(defn resolve-form-id
  "Resolve a mutation's form-id to ClojureStorm's form-id.

   The mutation engine computes form-id from source (hash of source string),
   but coverage uses ClojureStorm's form-id (computed during compilation).

   This function bridges the gap by looking up via file path and line number.

   Arguments:
   - form-location-index: Map of {[file, line] -> form-id} from coverage index
   - mutation: Mutation record with :file, :line, and optionally :form-id

   Returns:
   - The ClojureStorm form-id if found via file+line lookup
   - Falls back to mutation's :form-id for backwards compatibility

   Pure function."
  [form-location-index mutation]
  (let [{:keys [file line form-id]} mutation]
    (or (find-containing-form form-location-index file line)
        ;; Fall back to mutation's form-id (for tests with mock indexes)
        form-id)))

;; =============================================================================
;; Coord Matching Strategy
;; =============================================================================

(defn use-form-level-matching?
  "Determine if we should use form-level matching instead of coord-level.

   When using real ClojureStorm coverage (with form-location-index), we need
   form-level matching because coord formats differ between:
   - rewrite-clj (used for mutations): uses child indices
   - ClojureStorm (used for coverage): uses its own coord format

   For mock indexes in tests, coord matching works because both use the same format.

   Arguments:
   - index: Coverage index (may contain :form-location-index)

   Returns true if form-level matching should be used (i.e., form-location-index
   is present and non-empty).

   Pure function."
  [index]
  (seq (:form-location-index index)))
