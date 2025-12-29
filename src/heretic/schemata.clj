(ns heretic.schemata
  "Mutant schemata optimization for Heretic.

   Mutant schemata is a compile-once, run-many optimization technique that
   embeds multiple mutations into a single compilation unit using runtime
   selection via dynamic binding.

   Instead of:
   1. For each mutation:
      a. Modify source file
      b. Reload namespace (compile)
      c. Run tests
      d. Revert source file

   Schemata does:
   1. Generate combined source with all mutations as conditionals
   2. Reload namespace once
   3. For each mutation:
      a. Bind *active-mutant* to mutation id
      b. Run tests
   4. Revert source file

   Benefits:
   - Single compilation for N mutations (compile-once)
   - No file I/O between mutations
   - Fast mutation switching via dynamic binding
   - Natural thread isolation (each thread can bind independently)

   Example transformation:
   ```clojure
   ;; Original
   (defn calculate [x] (+ x 1))

   ;; Schematized (with 2 mutations)
   (defn calculate [x]
     (case heretic.schemata/*active-mutant*
       :m1 (- x 1)    ; swap-plus-minus
       :m2 (+ x 2)    ; replace-1-to-2
       (+ x 1)))      ; original (default)
   ```

   Main API:
   - `*active-mutant*`    - Dynamic var controlling which mutation is active
   - `build-schemata`     - Generate schematized source from mutations
   - `with-mutant`        - Execute code with a specific mutant active
   - `schematize-file!`   - Apply schemata to a file, returning revert info
   - `run-mutation-batch` - Run tests for multiple mutations efficiently"
  (:require [clojure.java.io :as io]
            [heretic.coord-mapper :as coord]
            [heretic.operators :as ops]
            [heretic.parser :as parser]
            [rewrite-clj.node :as n]
            [rewrite-clj.node.protocols]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Dynamic Mutation Selection
;; =============================================================================

(def ^:dynamic *active-mutant*
  "Dynamic var controlling which mutation is active.

   When nil (default), the original code path is executed.
   When set to a mutation id (keyword), that mutation is active.

   Usage:
   (binding [*active-mutant* :m1]
     (my-function ...))  ; Executes with mutation :m1 active"
  nil)

;; =============================================================================
;; Schemata Node Building
;; =============================================================================

(defn- mutation-id
  "Generate a unique, readable mutation id for use in schemata.

   Format: :mut-<line>-<col>-<operator-suffix>
   Example: :mut-42-5-plus-minus"
  [{:keys [line column operator]}]
  (let [op-suffix (-> (name operator)
                      (clojure.string/replace #"^swap-" "")
                      (clojure.string/replace #"^replace-" ""))]
    (keyword (format "mut-%d-%d-%s" (or line 0) (or column 0) op-suffix))))

(defn- node?
  "Check if x is a rewrite-clj node."
  [x]
  (and (some? x)
       (satisfies? rewrite-clj.node.protocols/Node x)))

(defn- build-case-node
  "Build a case form node for selecting between mutations.

   Returns a rewrite-clj node representing:
   (case heretic.schemata/*active-mutant*
     :m1 replacement-1
     :m2 replacement-2
     original)

   Arguments:
   - original-node: The original rewrite-clj node
   - mutations: Sequence of {:id :keyword, :replacement node/string}"
  [original-node mutations]
  (let [case-sym (symbol "case")
        active-var (symbol "heretic.schemata/*active-mutant*")
        ;; Build case clauses: id1 replacement1 id2 replacement2 ...
        clauses (mapcat (fn [{:keys [id replacement]}]
                          [(n/keyword-node id)
                           (if (node? replacement)
                             replacement
                             (n/token-node replacement))])
                        mutations)
        ;; Default case is the original
        default original-node
        ;; Build complete case form
        case-children (concat [(n/token-node case-sym)
                               (n/spaces 1)
                               (n/token-node active-var)
                               (n/newlines 1)]
                              (interpose (n/spaces 1) clauses)
                              [(n/newlines 1)
                               default])]
    (n/list-node case-children)))

;; =============================================================================
;; Mutation Grouping and Schemata Building
;; =============================================================================

(defn group-mutations-by-location
  "Group mutations that target the same source location.

   Two mutations target the same location if they have the same
   :file, :form-id, and :coord.

   Returns a map of [file form-id coord] -> [mutation1 mutation2 ...]"
  [mutations]
  (group-by (juxt :file :form-id :coord) mutations))

(defn build-schemata-for-location
  "Build a schematized node for a single location with multiple mutations.

   Arguments:
   - zloc: Zipper positioned at the mutation site
   - mutations: Sequence of mutations all targeting this location

   Returns: {:original-node, :schemata-node, :mutation-ids}"
  [zloc mutations]
  (let [original-node (z/node zloc)
        mutations-with-ids (for [m mutations
                                 :let [op (get ops/operators-by-id (:operator m))]
                                 :when op]
                             (let [replacement-str (ops/apply-operator op zloc)
                                   id (mutation-id m)]
                               {:id id
                                :mutation m
                                :replacement (read-string replacement-str)}))
        schemata-node (build-case-node original-node mutations-with-ids)]
    {:original-node original-node
     :schemata-node schemata-node
     :mutation-ids (mapv :id mutations-with-ids)
     :mutations (mapv :mutation mutations-with-ids)}))

(defn- build-location-index
  "Build an index of mutations by their line/column position.

   Returns a map of [line column] -> mutations"
  [mutations]
  (reduce (fn [idx m]
            (let [k [(:line m) (:column m)]]
              (update idx k (fnil conj []) m)))
          {}
          mutations))

(defn- sort-mutations-reverse
  "Sort mutations in reverse order by position (end of file first).

   This allows processing mutations from end to beginning, so that
   replacements don't affect the positions of earlier mutations."
  [mutations]
  (sort-by (fn [m] [(- (:line m 0)) (- (:column m 0))])
           mutations))

(defn- find-zloc-at-position
  "Find the zloc at a specific line/column position.

   Walks the tree depth-first until finding a node at the target position."
  [zloc target-line target-col]
  (loop [z zloc]
    (when-not (z/end? z)
      (let [[line col] (z/position z)]
        (if (and (= line target-line) (= col target-col))
          z
          (recur (z/next z)))))))

(defn- apply-schemata-at-location
  "Apply schemata transformation for mutations at a specific location.

   Returns {:source modified-source :mutation-info {:ids [...] :mutations [...]}}"
  [source mutations-at-location]
  (let [zloc (z/of-string source {:track-position? true})
        {:keys [line column]} (first mutations-at-location)
        target-zloc (find-zloc-at-position zloc line column)]
    (when target-zloc
      (let [{:keys [schemata-node mutation-ids mutations]}
            (build-schemata-for-location target-zloc mutations-at-location)
            modified-source (-> target-zloc
                                (z/replace schemata-node)
                                z/root-string)]
        {:source modified-source
         :mutation-info {:ids mutation-ids :mutations mutations}}))))

(defn build-schemata
  "Build schematized source from original source and mutations.

   Takes the original source and a collection of mutations for that file,
   and produces schematized source code where each mutation point is
   wrapped in a case form controlled by *active-mutant*.

   Mutations are processed from end of file to beginning to avoid
   position shifts affecting subsequent replacements.

   Arguments:
   - source: Original source code string
   - mutations: Sequence of mutation records for this file

   Returns:
   {:schemata-source \"...\"       ; The schematized source code
    :mutation-map {id mutation}    ; Map from mutation id to mutation record
    :location-count n}             ; Number of locations schematized"
  [source mutations]
  (when (and source (seq mutations))
    (let [;; Group by location and sort reverse (end of file first)
          by-location (group-mutations-by-location mutations)
          sorted-locations (sort-by (fn [[[_file _form-id _coord] ms]]
                                      (let [m (first ms)]
                                        [(- (:line m 0)) (- (:column m 0))]))
                                    by-location)]
      ;; Process each location in reverse order
      (loop [current-source source
             remaining sorted-locations
             mutation-map {}
             location-count 0]
        (if (empty? remaining)
          {:schemata-source current-source
           :mutation-map mutation-map
           :location-count location-count}
          (let [[_loc-key loc-mutations] (first remaining)
                result (apply-schemata-at-location current-source loc-mutations)]
            (if result
              (let [{:keys [source mutation-info]} result
                    {:keys [ids mutations]} mutation-info]
                (recur source
                       (rest remaining)
                       (merge mutation-map (zipmap ids mutations))
                       (inc location-count)))
              ;; Failed to apply at this location, skip it
              (recur current-source
                     (rest remaining)
                     mutation-map
                     location-count))))))))

;; =============================================================================
;; File Operations
;; =============================================================================

(defn schematize-file!
  "Apply schemata transformation to a file.

   Reads the file, applies schemata for all provided mutations,
   and writes the schematized source back.

   Arguments:
   - file-path: Path to the source file
   - mutations: Sequence of mutations for this file

   Returns:
   {:backup \"original source\"
    :mutation-map {id mutation}
    :location-count n}

   The backup can be used to restore the original file."
  [file-path mutations]
  (let [original (slurp file-path)
        result (build-schemata original mutations)]
    (when result
      (spit file-path (:schemata-source result))
      {:backup original
       :file file-path
       :mutation-map (:mutation-map result)
       :location-count (:location-count result)})))

(defn restore-file!
  "Restore a file from its backup after schematization.

   Arguments:
   - schemata-info: Map returned by schematize-file! containing :file and :backup"
  [{:keys [file backup]}]
  (when (and file backup)
    (spit file backup)
    true))

;; =============================================================================
;; Execution Helpers
;; =============================================================================

(defmacro with-mutant
  "Execute body with a specific mutation active.

   Example:
   (with-mutant :mut-42-5-plus-minus
     (run-test 'my-test))"
  [mutation-id & body]
  `(binding [*active-mutant* ~mutation-id]
     ~@body))

(defmacro with-schemata
  "Execute body with schematized file, automatically restoring on completion.

   Arguments:
   - binding: [var schemata-info] where schemata-info is from schematize-file!
   - body: Code to execute

   The file will be restored even if body throws."
  [[binding schemata-info] & body]
  `(let [~binding ~schemata-info]
     (try
       ~@body
       (finally
         (restore-file! ~binding)))))

;; =============================================================================
;; Batch Mutation Testing
;; =============================================================================

(defn run-mutation-batch
  "Run tests for a batch of mutations using schemata optimization.

   This is the main entry point for schemata-based mutation testing.

   Arguments:
   - file-path: Path to the source file
   - mutations: Sequence of mutations for this file
   - test-fn: Function to call for each mutation (fn [mutation-id mutation] -> result)
   - opts: Optional configuration:
     - :on-progress - Callback (fn [completed total result])
     - :reload-fn - Function to reload namespaces after schematization

   Returns:
   {:results [{:mutation-id :m1, :mutation {...}, :result ...} ...]
    :location-count n
    :compile-count 1}"
  [file-path mutations test-fn & {:keys [on-progress reload-fn]}]
  (let [schemata-info (schematize-file! file-path mutations)
        on-progress (or on-progress (fn [_ _ _] nil))
        total (count (:mutation-map schemata-info))]
    (try
      ;; Reload once after schematization
      (when reload-fn (reload-fn))

      ;; Run tests for each mutation
      (let [results
            (reduce
             (fn [acc [idx [mutation-id mutation]]]
               (let [result (with-mutant mutation-id
                              (test-fn mutation-id mutation))]
                 (on-progress (inc idx) total result)
                 (conj acc {:mutation-id mutation-id
                            :mutation mutation
                            :result result})))
             []
             (map-indexed vector (:mutation-map schemata-info)))]

        {:results results
         :location-count (:location-count schemata-info)
         :compile-count 1})

      (finally
        (restore-file! schemata-info)
        ;; Reload again to restore original code
        (when reload-fn (reload-fn))))))

;; =============================================================================
;; Integration with Worker System
;; =============================================================================

(defn make-schemata-config
  "Create configuration for schemata-based mutation testing.

   Returns a config map suitable for use with the worker system."
  [base-config]
  (assoc base-config
         :schemata-enabled true
         :batch-by-file true))

(defn should-use-schemata?
  "Determine if schemata optimization should be used.

   Heuristic: Use schemata when there are multiple mutations per file.
   The overhead of case dispatch is offset by avoiding recompilation."
  [mutations]
  (let [by-file (group-by :file mutations)]
    (boolean
     (some (fn [[_file file-mutations]]
             (> (count file-mutations) 2))
           by-file))))
