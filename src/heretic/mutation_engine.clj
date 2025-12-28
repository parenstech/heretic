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
            [heretic.parser :as parser]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z])
  (:import [java.util UUID]))

;; =============================================================================
;; Mutation Site Discovery
;; =============================================================================

;; Delegates to heretic.parser for the core site-finding logic.
;; This ensures quoted forms are properly skipped and we have one source of truth.

(defn find-sites-in-source
  "Find all mutation sites in a source string. Pure function.

   Delegates to heretic.parser/find-mutation-sites which properly:
   - Handles quoted forms (skips '(...) and `(...))
   - Computes form-id from top-level forms
   - Generates coordinates for navigation

   Arguments:
   - source: Clojure source code as a string
   - file-path: Optional file path for the :file field in results

   Returns sequence of mutation site maps, or nil if source cannot be parsed."
  ([source]
   (find-sites-in-source source nil))
  ([source file-path]
   (when-let [zloc (parser/parse-string source)]
     (parser/find-mutation-sites zloc {:file file-path}))))

(defn find-mutation-sites
  "Find all mutation sites in a source file.

   Thin I/O wrapper around find-sites-in-source.
   Returns empty sequence if file cannot be parsed."
  [file-path]
  (try
    (let [content (slurp file-path)]
      (or (find-sites-in-source content file-path) []))
    (catch Exception e
      (println "Warning: Could not parse" file-path "-" (.getMessage e))
      [])))

;; =============================================================================
;; Mutation Generation
;; =============================================================================

(defn generate-mutations
  "Generate all mutation records for the given source paths.

   Arguments:
   - source-paths: Sequence of source directories to scan
   - operators: (optional) Sequence of operators to use (defaults to all)

   Returns sequence of mutation records with :id added."
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
           ;; parser already returns :operator as keyword
           :when (contains? operator-ids (:operator site))]
       (assoc site :id (UUID/randomUUID))))))

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
           ;; parser already returns :operator as keyword
           :when (contains? operator-ids (:operator site))]
       (assoc site :id (UUID/randomUUID))))))

(defn count-mutations
  "Count total mutations that would be generated for source paths.

   Useful for progress reporting without generating all mutations upfront."
  ([source-paths]
   (count-mutations source-paths ops/all-operators))
  ([source-paths operators]
   (count (generate-mutations source-paths operators))))
