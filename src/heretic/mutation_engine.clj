(ns heretic.mutation-engine
  "Apply and revert mutations to source files.

   The mutation engine is responsible for:
   1. Generating all possible mutations for given source paths
   2. Applying a mutation to a source file (with backup)
   3. Reverting a mutation (restoring original content)

   Mutation record structure:
   {:id          <uuid>
    :file        \"src/my/app.clj\"
    :form-id     12345678
    :coord       \"3,0\"
    :operator    :swap-plus-minus
    :original    \"+\"
    :replacement \"-\"
    :line        42
    :column      10
    :backup      <original-content>}  ; Added by apply-mutation!

   Safety: mutations should always be reverted, even on error."
  (:require [clojure.java.io :as io]
            [heretic.coord-mapper :as coord]
            [heretic.operators :as ops]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z])
  (:import [java.util UUID]))

;; =============================================================================
;; Zipper Traversal
;; =============================================================================

(defn- zloc-seq
  "Return a lazy sequence of all zlocs in depth-first order.
   Traverses the entire tree structure, stopping at the end marker."
  [zloc]
  (take-while (complement z/end?) (iterate z/next zloc)))

;; =============================================================================
;; Mutation Site Discovery
;; =============================================================================

(defn- zloc->position
  "Extract line and column from a zipper location.
   Returns {:line N :column N} or nil if position unavailable."
  [zloc]
  (let [pos (z/position zloc)]
    (when pos
      {:line (first pos)
       :column (second pos)})))

(defn- find-form-bounds
  "Find the start and end positions of top-level forms in a file.
   Returns a sorted map of {start-line -> {:form-zloc, :form-id, :end-line}}.

   form-id is computed from the node's string representation hash,
   similar to ClojureStorm's FormRegistry."
  [zloc]
  (loop [z zloc
         forms (sorted-map)]
    (if (z/end? z)
      forms
      (let [pos (zloc->position z)]
        (if (and pos (z/up z) (= :forms (z/tag (z/up z))))
          ;; This is a top-level form
          (let [start-line (:line pos)
                ;; Compute a form-id by hashing the form string
                form-id (hash (z/string z))
                end-line (+ start-line (count (filter #(= % \newline) (z/string z))))]
            (recur (z/next z)
                   (assoc forms start-line {:form-zloc z
                                            :form-id form-id
                                            :end-line end-line})))
          (recur (z/next z) forms))))))

(defn- form-id-for-zloc
  "Compute a form-id for a zloc based on its top-level form.
   Navigates up to find the root form and computes its hash."
  [zloc]
  (loop [z zloc]
    (let [parent (z/up z)]
      (cond
        ;; At root form (parent is :forms container)
        (and parent (= :forms (z/tag parent)))
        (hash (z/string z))

        ;; Has parent, keep going up
        parent
        (recur parent)

        ;; No parent, use current
        :else
        (hash (z/string z))))))

(defn find-mutation-sites
  "Find all mutation sites in a source file.

   Returns sequence of maps:
   {:file     \"path/to/file.clj\"
    :form-id  12345678
    :coord    \"3,0\"
    :operator {:id :swap-plus-minus ...}
    :original \"+\"
    :line     42
    :column   10}"
  [file-path]
  (let [content (slurp file-path)
        zloc (z/of-string content {:track-position? true})]
    (for [z (zloc-seq zloc)
          :when (not (z/end? z))
          op (ops/applicable-operators z)
          :let [pos (zloc->position z)
                c (coord/zloc->coord z)]
          :when (and pos c)]
      {:file file-path
       :form-id (form-id-for-zloc z)
       :coord (coord/stringify-coord c)
       :operator op
       :original (z/string z)
       :line (:line pos)
       :column (:column pos)})))

;; =============================================================================
;; Mutation Generation
;; =============================================================================

(defn generate-mutations
  "Generate all mutation records for the given source paths.

   Arguments:
   - source-paths: Sequence of source directories to scan
   - operators: (optional) Sequence of operators to use (defaults to all)

   Returns sequence of mutation records (without :backup, :id)."
  ([source-paths]
   (generate-mutations source-paths ops/all-operators))
  ([source-paths operators]
   (let [operator-ids (set (map :id operators))]
     (for [source-path source-paths
           :let [dir (io/file source-path)]
           :when (.exists dir)
           file (file-seq dir)
           :when (and (.isFile file)
                      (.endsWith (.getName file) ".clj"))
           :let [file-path (.getPath file)]
           site (find-mutation-sites file-path)
           :when (contains? operator-ids (get-in site [:operator :id]))]
       (-> site
           (assoc :id (UUID/randomUUID))
           (update :operator :id))))))

;; =============================================================================
;; Mutation Application
;; =============================================================================

(defn- find-form-by-id
  "Find the top-level form with the given form-id (hash).
   Returns the zloc at the matching form, or nil if not found."
  [zloc form-id]
  (let [forms-container (z/up zloc)]
    (if (= :forms (z/tag forms-container))
      ;; Navigate through siblings to find matching form
      (loop [z zloc]
        (when z
          (if (= form-id (hash (z/string z)))
            z
            (recur (z/right z)))))
      ;; Single form (zloc is not under :forms container)
      (when (= form-id (hash (z/string zloc)))
        zloc))))

(defn apply-mutation!
  "Apply a mutation to a source file.

   Reads the file, navigates to the mutation location using form-id and coord,
   replaces the node with the operator's replacement, and writes back.

   The original file content is stored in :backup for later reverting.

   Arguments:
   - mutation: A mutation record with :file, :form-id, :coord, :operator

   Returns the mutation record with :backup added.

   Throws if:
   - File doesn't exist
   - Navigation to coord fails
   - Operator lookup fails"
  [mutation]
  (let [{:keys [file form-id coord operator]} mutation
        operator-def (get ops/operators-by-id operator)
        _ (when-not operator-def
            (throw (ex-info "Unknown operator"
                            {:operator operator
                             :available (keys ops/operators-by-id)})))
        original-content (slurp file)
        zloc (z/of-string original-content {:track-position? true})

        ;; First find the top-level form with matching form-id
        form-zloc (if form-id
                    (find-form-by-id zloc form-id)
                    zloc)
        _ (when-not form-zloc
            (throw (ex-info "Failed to find form with matching form-id"
                            {:file file
                             :form-id form-id})))

        ;; Navigate within the form to the mutation location
        target-zloc (coord/coord->zloc form-zloc coord)
        _ (when-not target-zloc
            (throw (ex-info "Failed to navigate to mutation location"
                            {:file file
                             :form-id form-id
                             :coord coord})))

        ;; Get the replacement string from the operator
        replacement-str (ops/apply-operator operator-def target-zloc)

        ;; Replace the node with the replacement
        modified-zloc (z/replace target-zloc (n/token-node (symbol replacement-str)))
        modified-content (z/root-string modified-zloc)]

    ;; Write modified content back to file
    (spit file modified-content)

    ;; Return mutation with backup
    (assoc mutation :backup original-content)))

;; =============================================================================
;; Mutation Reversion
;; =============================================================================

(defn revert-mutation!
  "Revert a mutation by restoring the original file content from backup.

   Arguments:
   - mutation: A mutation record with :file and :backup

   Returns the mutation record without :backup.

   Throws if :backup is missing."
  [mutation]
  (let [{:keys [file backup]} mutation]
    (when-not backup
      (throw (ex-info "Cannot revert mutation without backup"
                      {:mutation (dissoc mutation :backup)})))
    (spit file backup)
    (dissoc mutation :backup)))

;; =============================================================================
;; Safe Mutation Execution
;; =============================================================================

(defmacro with-mutation
  "Execute body with mutation applied, automatically reverting on completion or error.

   Usage:
   (with-mutation [m mutation]
     ;; m is the mutation with :backup
     (run-tests!)
     :killed)

   Always reverts the mutation, even if body throws."
  [[binding mutation] & body]
  `(let [~binding (apply-mutation! ~mutation)]
     (try
       ~@body
       (finally
         (revert-mutation! ~binding)))))

;; =============================================================================
;; Batch Operations
;; =============================================================================

(defn mutations-for-file
  "Generate all mutations for a single file.

   Arguments:
   - file-path: Path to the source file
   - operators: (optional) Operators to use

   Returns sequence of mutation records."
  ([file-path]
   (mutations-for-file file-path ops/all-operators))
  ([file-path operators]
   (let [operator-ids (set (map :id operators))]
     (for [site (find-mutation-sites file-path)
           :when (contains? operator-ids (get-in site [:operator :id]))]
       (-> site
           (assoc :id (UUID/randomUUID))
           (update :operator :id))))))

(defn count-mutations
  "Count total mutations that would be generated for source paths.

   Useful for progress reporting without generating all mutations upfront."
  ([source-paths]
   (count-mutations source-paths ops/all-operators))
  ([source-paths operators]
   (count (generate-mutations source-paths operators))))
