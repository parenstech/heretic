(ns heretic.parser
  "Parse source files and find mutation sites.

   This namespace provides tools to:
   - Parse Clojure source files into rewrite-clj zippers
   - Navigate and find all top-level forms
   - Identify mutation sites within forms
   - Skip quoted forms (inside `'()` or `` `() ``)

   Main API:
   - `parse-file`          - Parse file into zipper
   - `parse-string`        - Parse string into zipper
   - `top-level-form`      - Navigate to root form from any position
   - `in-quoted-form?`     - Check if zloc is inside quoted context
   - `find-mutation-sites` - Return mutation site records for a zipper"
  (:require [clojure.java.io :as io]
            [heretic.coord-mapper :as coord-mapper]
            [heretic.operators :as ops]
            [rewrite-clj.zip :as z])
  (:import [java.util UUID]))

;; =============================================================================
;; File Parsing
;; =============================================================================

(defn parse-string
  "Parse a Clojure source string into a rewrite-clj zipper.

   Returns the zipper positioned at the first form, or nil if parsing fails."
  [source]
  (try
    (z/of-string source {:track-position? true})
    (catch Exception _e
      nil)))

(defn parse-file
  "Parse a Clojure source file into a rewrite-clj zipper.

   path can be a string, File, or anything coercible by io/reader.
   Returns the zipper positioned at the first form, or nil if parsing fails."
  [path]
  (try
    (let [content (slurp (io/file path))]
      (parse-string content))
    (catch Exception _e
      nil)))

;; =============================================================================
;; Form Navigation
;; =============================================================================

(defn- root-form?
  "Check if this zloc is the root form (direct child of :forms node).
   The :forms node is the implicit container created by z/of-string."
  [zloc]
  (when-let [parent (z/up zloc)]
    (= :forms (z/tag parent))))

(defn top-level-form
  "Navigate to the root (top-level) form from any position.

   Returns the zipper positioned at the top-level form containing zloc,
   or zloc itself if it's already a top-level form.
   Returns nil if zloc is the :forms container itself."
  [zloc]
  (cond
    ;; Already at root form level
    (root-form? zloc) zloc

    ;; At the :forms container - no single top-level form
    (= :forms (z/tag zloc)) nil

    ;; Navigate up until we reach root form
    :else
    (loop [z zloc]
      (cond
        (nil? z) nil
        (root-form? z) z
        :else (recur (z/up z))))))

(defn top-level-forms
  "Return a lazy sequence of all top-level forms in a zipper.

   zloc should be the result of parse-file or parse-string."
  [zloc]
  (when zloc
    (let [;; Navigate to the first top-level form
          first-form (if (= :forms (z/tag zloc))
                       (z/down zloc)
                       (top-level-form zloc))]
      (when first-form
        (->> first-form
             (iterate z/right)
             (take-while some?))))))

;; =============================================================================
;; Quoted Form Detection
;; =============================================================================

(def ^:private quote-tags
  "Node tags that indicate quoted context where mutations should be skipped."
  #{:quote :syntax-quote})

(defn in-quoted-form?
  "Check if zloc is inside a quoted context.

   Quoted contexts include:
   - Quote reader macro: '(...)
   - Syntax quote: `(...)

   Returns true if any ancestor is a quote or syntax-quote node."
  [zloc]
  (loop [z (z/up zloc)]
    (cond
      (nil? z) false
      (quote-tags (z/tag z)) true
      :else (recur (z/up z)))))

;; =============================================================================
;; Zipper Tree Walking
;; =============================================================================

(defn- walk-form
  "Walk a single form depth-first, returning a lazy sequence of all zloc positions.

   Walks only within the given form (does not traverse siblings at the top level)."
  [zloc]
  (when zloc
    (lazy-seq
     (cons zloc
           (when-let [child (z/down zloc)]
             (->> child
                  (iterate z/right)
                  (take-while some?)
                  (mapcat walk-form)))))))

;; =============================================================================
;; Mutation Site Detection
;; =============================================================================

(defn- make-mutation-site
  "Create a mutation site record for a zloc and operator."
  [zloc op file form-id]
  (let [pos (z/position zloc)
        coord (coord-mapper/zloc->coord zloc)]
    {:id (UUID/randomUUID)
     :file file
     :form-id form-id
     :coord (if coord
              (coord-mapper/stringify-coord coord)
              "")
     :operator (:id op)
     :original (str (:original op))
     :replacement (str (:replacement op))
     :line (when pos (first pos))
     :column (when pos (second pos))}))

(defn find-mutation-sites
  "Find all mutation sites in a zipper.

   Arguments:
   - zloc: A rewrite-clj zipper (from parse-file or parse-string)
   - opts: Optional map with:
     - :file - File path for the :file field in mutation sites
     - :operators - Operators to use (defaults to ops/all-operators)

   Returns a sequence of mutation site records, each containing:
   - :id - Unique UUID
   - :file - Source file path
   - :form-id - Hash of the top-level form
   - :coord - ClojureStorm coordinate string
   - :operator - Operator keyword (e.g., :swap-plus-minus)
   - :original - Original value as string
   - :replacement - Replacement value as string
   - :line - Line number (1-indexed)
   - :column - Column number (1-indexed)"
  ([zloc]
   (find-mutation-sites zloc {}))
  ([zloc {:keys [file operators] :or {operators ops/all-operators}}]
   (when zloc
     (let [;; Get all top-level forms
           forms (top-level-forms zloc)]
       (->> forms
            ;; Walk each form
            (mapcat (fn [form]
                      (let [form-id (hash (z/string form))]
                        (->> (walk-form form)
                             ;; Skip quoted forms
                             (remove in-quoted-form?)
                             ;; Find applicable operators for each position
                             (mapcat (fn [z]
                                       (let [applicable (filter #(try
                                                                   ((:matcher %) z)
                                                                   (catch Exception _e false))
                                                                operators)]
                                         (map #(make-mutation-site z % file form-id)
                                              applicable))))))))
            ;; Return as a vector for consistent ordering
            vec)))))

(defn find-mutation-sites-in-file
  "Find all mutation sites in a source file.

   Convenience function that parses the file and finds mutation sites.
   Returns nil if the file cannot be parsed."
  ([path]
   (find-mutation-sites-in-file path {}))
  ([path opts]
   (when-let [zloc (parse-file path)]
     (find-mutation-sites zloc (assoc opts :file (str path))))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn find-form-by-id
  "Navigate to the top-level form whose hash matches form-id.

   form-id is `(hash (z/string form))` of a top-level form, as stamped by
   `find-mutation-sites` and `heretic.mutation-engine/apply-mutation!`.
   Returns the zloc at the matching form, or nil if none matches.

   This is the navigation half that `apply-mutation!` performs before applying
   a form-relative coord; `mutation-site->zloc` reuses it so the read-back path
   anchors identically to the apply path."
  [zloc form-id]
  (->> (top-level-forms zloc)
       (filter #(= form-id (hash (z/string %))))
       first))

(defn mutation-site->zloc
  "Navigate to the mutation site location in a zipper.

   A mutation site's :coord is RELATIVE to its top-level form (identified by
   :form-id) — `make-mutation-site` and `apply-mutation!` both compute and
   consume coords this way. So when the site carries a :form-id we must first
   anchor to that form, then apply the form-relative coord; navigating the
   coord straight from the file root resolves only for single-form sources and
   returns nil for any mutation outside the first top-level form.

   Falls back to navigating from the given zloc directly when the site has no
   :form-id (e.g. a hand-built single-form site)."
  [site zloc]
  (let [form-zloc (if-let [fid (:form-id site)]
                    (find-form-by-id zloc fid)
                    zloc)]
    (when form-zloc
      (coord-mapper/coord->zloc form-zloc (:coord site)))))
