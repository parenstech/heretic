(ns heretic.coverage-map.index
  "Coverage index building and querying. Pure functions.

   The index maps code locations (form-id + coord) to the tests that exercise them.
   This enables targeted test execution during mutation testing.

   Index structure:
   {:coord-to-tests {[form-id coord] -> #{test-symbols}}
    :form-to-tests {form-id -> #{test-symbols}}  ; Aggregated for O(1) form lookup
    :form-location-index {[file line] -> form-id}
    :included-test-ns #{test-namespace-symbols}
    :rebuilt-at timestamp}

   All functions in this module are pure - they take data and return data.")

;; =============================================================================
;; Index Building
;; =============================================================================

(defn build-inverse-index
  "Build form+coord -> tests index from coverage data.

   Takes sequence of coverage data maps (from per-namespace files).
   Returns {[form-id coord] -> #{test-symbols}}

   Pure function."
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

(defn build-form-to-tests
  "Build form-id -> tests index from coord-to-tests.

   Aggregates all tests that hit any coordinate in each form for O(1) form-level lookup.

   Pure function."
  [coord-to-tests]
  (reduce-kv
   (fn [acc [form-id _coord] tests]
     (update acc form-id (fnil into #{}) tests))
   {}
   coord-to-tests))

(defn build-index
  "Build a complete coverage index from coverage files and metadata.

   Arguments:
   - coverage-files: Sequence of coverage data maps
   - form-location-index: Map of {[file line] -> form-id} for mutation bridging

   Returns complete index structure.

   Pure function."
  [coverage-files form-location-index]
  (let [coord-to-tests (build-inverse-index coverage-files)
        form-to-tests (build-form-to-tests coord-to-tests)
        included-ns (into #{} (map :test-ns coverage-files))]
    {:coord-to-tests coord-to-tests
     :form-to-tests form-to-tests
     :form-location-index (or form-location-index {})
     :included-test-ns included-ns
     :rebuilt-at (System/currentTimeMillis)}))

;; =============================================================================
;; Index Queries
;; =============================================================================

(defn tests-for-location
  "Given a form-id and optional coord, return tests that hit it.

   With coord: Returns tests that hit that specific coordinate.
   Without coord: Returns all tests that hit any coordinate in the form (O(1) lookup).

   Pure function."
  ([index form-id]
   ;; Use form-to-tests index for O(1) form-level lookup
   (get-in index [:form-to-tests form-id] #{}))

  ([index form-id coord]
   (get-in index [:coord-to-tests [form-id coord]] #{})))

(defn uncovered-coords
  "Find coordinates that have no test coverage.

   Returns sequence of [form-id coord] pairs.

   Pure function."
  [index forms]
  (for [[form-id {:keys [form/emitted-coords]}] forms
        coord emitted-coords
        :when (empty? (tests-for-location index form-id coord))]
    [form-id coord]))

(defn coverage-stats
  "Calculate coverage statistics from index and forms.

   Returns {:total-coords N :covered-coords N :coverage-pct N}

   Pure function."
  [index forms]
  (let [all-coords (for [[form-id {:keys [form/emitted-coords]}] forms
                         coord emitted-coords]
                     [form-id coord])
        total (count all-coords)
        covered (count (filter (fn [[form-id coord]]
                                 (seq (tests-for-location index form-id coord)))
                               all-coords))]
    {:total-coords total
     :covered-coords covered
     :coverage-pct (if (pos? total)
                     (double (/ covered total))
                     1.0)}))
