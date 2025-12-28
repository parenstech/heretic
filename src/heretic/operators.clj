(ns heretic.operators
  "Mutation operator definitions for Heretic.

   Mutation operators define transformations to apply to source code.
   Each operator is a data map with:
   - :id          - Keyword identifier like :swap-plus-minus
   - :original    - The symbol/value being replaced
   - :replacement - What it becomes
   - :description - Human readable description
   - :matcher     - Predicate that checks if a zloc matches

   Main API:
   - `applicable-operators` - Return operators that match a zipper location
   - `apply-operator`       - Return the replacement string for an operator"
  (:require [rewrite-clj.zip :as z]))

;; =============================================================================
;; Matcher Predicates
;; =============================================================================

(defn- symbol-matcher
  "Create a matcher that checks if zloc is a specific symbol."
  [sym]
  (fn [zloc]
    (and (= :token (z/tag zloc))
         (symbol? (z/sexpr zloc))
         (= sym (z/sexpr zloc)))))

(defn- value-matcher
  "Create a matcher that checks if zloc is a specific value (boolean, etc)."
  [v]
  (fn [zloc]
    (and (= :token (z/tag zloc))
         (= v (z/sexpr zloc)))))

;; =============================================================================
;; Operator Definitions
;; =============================================================================

(def swap-plus-minus
  "Replace + with -"
  {:id :swap-plus-minus
   :original '+
   :replacement '-
   :description "Replace + with -"
   :matcher (symbol-matcher '+)})

(def swap-minus-plus
  "Replace - with +"
  {:id :swap-minus-plus
   :original '-
   :replacement '+
   :description "Replace - with +"
   :matcher (symbol-matcher '-)})

(def swap-mult-div
  "Replace * with /"
  {:id :swap-mult-div
   :original '*
   :replacement '/
   :description "Replace * with /"
   :matcher (symbol-matcher '*)})

(def swap-div-mult
  "Replace / with *"
  {:id :swap-div-mult
   :original '/
   :replacement '*
   :description "Replace / with *"
   :matcher (symbol-matcher '/)})

(def swap-and-or
  "Replace and with or"
  {:id :swap-and-or
   :original 'and
   :replacement 'or
   :description "Replace and with or"
   :matcher (symbol-matcher 'and)})

(def swap-or-and
  "Replace or with and"
  {:id :swap-or-and
   :original 'or
   :replacement 'and
   :description "Replace or with and"
   :matcher (symbol-matcher 'or)})

(def swap-true-false
  "Replace true with false"
  {:id :swap-true-false
   :original true
   :replacement false
   :description "Replace true with false"
   :matcher (value-matcher true)})

(def swap-false-true
  "Replace false with true"
  {:id :swap-false-true
   :original false
   :replacement true
   :description "Replace false with true"
   :matcher (value-matcher false)})

;; Comparison operators
(def swap-lt-gt
  "Replace < with >"
  {:id :swap-lt-gt
   :original '<
   :replacement '>
   :description "Replace < with >"
   :matcher (symbol-matcher '<)})

(def swap-gt-lt
  "Replace > with <"
  {:id :swap-gt-lt
   :original '>
   :replacement '<
   :description "Replace > with <"
   :matcher (symbol-matcher '>)})

(def swap-lte-gte
  "Replace <= with >="
  {:id :swap-lte-gte
   :original '<=
   :replacement '>=
   :description "Replace <= with >="
   :matcher (symbol-matcher '<=)})

(def swap-gte-lte
  "Replace >= with <="
  {:id :swap-gte-lte
   :original '>=
   :replacement '<=
   :description "Replace >= with <="
   :matcher (symbol-matcher '>=)})

(def swap-eq-neq
  "Replace = with not="
  {:id :swap-eq-neq
   :original '=
   :replacement 'not=
   :description "Replace = with not="
   :matcher (symbol-matcher '=)})

(def swap-neq-eq
  "Replace not= with ="
  {:id :swap-neq-eq
   :original 'not=
   :replacement '=
   :description "Replace not= with ="
   :matcher (symbol-matcher 'not=)})

;; Boundary mutations (off-by-one errors)
(def swap-lt-lte
  "Replace < with <= (boundary mutation)"
  {:id :swap-lt-lte
   :original '<
   :replacement '<=
   :description "Replace < with <="
   :matcher (symbol-matcher '<)})

(def swap-gt-gte
  "Replace > with >= (boundary mutation)"
  {:id :swap-gt-gte
   :original '>
   :replacement '>=
   :description "Replace > with >="
   :matcher (symbol-matcher '>)})

(def swap-lte-lt
  "Replace <= with < (boundary mutation)"
  {:id :swap-lte-lt
   :original '<=
   :replacement '<
   :description "Replace <= with <"
   :matcher (symbol-matcher '<=)})

(def swap-gte-gt
  "Replace >= with > (boundary mutation)"
  {:id :swap-gte-gt
   :original '>=
   :replacement '>
   :description "Replace >= with >"
   :matcher (symbol-matcher '>=)})

;; =============================================================================
;; Phase 3.3: Threading Operators
;; =============================================================================

(def swap-thread-first-last
  "Replace -> with ->>"
  {:id :swap-thread-first-last
   :original '->
   :replacement '->>
   :description "Replace -> with ->>"
   :matcher (symbol-matcher '->)})

(def swap-thread-last-first
  "Replace ->> with ->"
  {:id :swap-thread-last-first
   :original '->>
   :replacement '->
   :description "Replace ->> with ->"
   :matcher (symbol-matcher '->>)})

(def swap-thread-some-first
  "Replace -> with some->"
  {:id :swap-thread-some-first
   :original '->
   :replacement 'some->
   :description "Replace -> with some->"
   :matcher (symbol-matcher '->)})

(def swap-some-first-thread
  "Replace some-> with ->"
  {:id :swap-some-first-thread
   :original 'some->
   :replacement '->
   :description "Replace some-> with ->"
   :matcher (symbol-matcher 'some->)})

(def swap-thread-some-last
  "Replace ->> with some->>"
  {:id :swap-thread-some-last
   :original '->>
   :replacement 'some->>
   :description "Replace ->> with some->>"
   :matcher (symbol-matcher '->>)})

(def swap-some-last-thread
  "Replace some->> with ->>"
  {:id :swap-some-last-thread
   :original 'some->>
   :replacement '->>
   :description "Replace some->> with ->>"
   :matcher (symbol-matcher 'some->>)})

;; =============================================================================
;; Phase 3.4: Lazy/Eager Operators
;; =============================================================================

(def swap-map-mapv
  "Replace map with mapv"
  {:id :swap-map-mapv
   :original 'map
   :replacement 'mapv
   :description "Replace map with mapv"
   :matcher (symbol-matcher 'map)})

(def swap-mapv-map
  "Replace mapv with map"
  {:id :swap-mapv-map
   :original 'mapv
   :replacement 'map
   :description "Replace mapv with map"
   :matcher (symbol-matcher 'mapv)})

(def swap-filter-filterv
  "Replace filter with filterv"
  {:id :swap-filter-filterv
   :original 'filter
   :replacement 'filterv
   :description "Replace filter with filterv"
   :matcher (symbol-matcher 'filter)})

(def swap-filterv-filter
  "Replace filterv with filter"
  {:id :swap-filterv-filter
   :original 'filterv
   :replacement 'filter
   :description "Replace filterv with filter"
   :matcher (symbol-matcher 'filterv)})

(def swap-for-doseq
  "Replace for with doseq"
  {:id :swap-for-doseq
   :original 'for
   :replacement 'doseq
   :description "Replace for with doseq"
   :matcher (symbol-matcher 'for)})

(def swap-doseq-for
  "Replace doseq with for"
  {:id :swap-doseq-for
   :original 'doseq
   :replacement 'for
   :description "Replace doseq with for"
   :matcher (symbol-matcher 'doseq)})

;; =============================================================================
;; Phase 3.5: HOF Operators
;; =============================================================================

(def swap-filter-remove
  "Replace filter with remove"
  {:id :swap-filter-remove
   :original 'filter
   :replacement 'remove
   :description "Replace filter with remove"
   :matcher (symbol-matcher 'filter)})

(def swap-remove-filter
  "Replace remove with filter"
  {:id :swap-remove-filter
   :original 'remove
   :replacement 'filter
   :description "Replace remove with filter"
   :matcher (symbol-matcher 'remove)})

(def swap-keep-filter
  "Replace keep with filter"
  {:id :swap-keep-filter
   :original 'keep
   :replacement 'filter
   :description "Replace keep with filter"
   :matcher (symbol-matcher 'keep)})

(def swap-filter-keep
  "Replace filter with keep"
  {:id :swap-filter-keep
   :original 'filter
   :replacement 'keep
   :description "Replace filter with keep"
   :matcher (symbol-matcher 'filter)})

;; =============================================================================
;; Phase 3.6: Return Value Operators
;; =============================================================================

(def replace-nil-false
  "Replace nil with false"
  {:id :replace-nil-false
   :original nil
   :replacement false
   :description "Replace nil with false"
   :matcher (value-matcher nil)})

(def replace-nil-zero
  "Replace nil with 0"
  {:id :replace-nil-zero
   :original nil
   :replacement 0
   :description "Replace nil with 0"
   :matcher (value-matcher nil)})

(def replace-nil-empty-vec
  "Replace nil with []"
  {:id :replace-nil-empty-vec
   :original nil
   :replacement []
   :description "Replace nil with []"
   :matcher (value-matcher nil)})

(def replace-nil-empty-map
  "Replace nil with {}"
  {:id :replace-nil-empty-map
   :original nil
   :replacement {}
   :description "Replace nil with {}"
   :matcher (value-matcher nil)})

(def replace-nil-empty-str
  "Replace nil with \"\""
  {:id :replace-nil-empty-str
   :original nil
   :replacement ""
   :description "Replace nil with \"\""
   :matcher (value-matcher nil)})

;; =============================================================================
;; Phase 3.7: Constant Replacement Operators
;; =============================================================================

(def replace-0-to-1
  "Replace 0 with 1"
  {:id :replace-0-to-1
   :original 0
   :replacement 1
   :description "Replace 0 with 1"
   :matcher (value-matcher 0)})

(def replace-1-to-0
  "Replace 1 with 0"
  {:id :replace-1-to-0
   :original 1
   :replacement 0
   :description "Replace 1 with 0"
   :matcher (value-matcher 1)})

(def replace-0-to-neg1
  "Replace 0 with -1"
  {:id :replace-0-to-neg1
   :original 0
   :replacement -1
   :description "Replace 0 with -1"
   :matcher (value-matcher 0)})

(def replace-1-to-neg1
  "Replace 1 with -1"
  {:id :replace-1-to-neg1
   :original 1
   :replacement -1
   :description "Replace 1 with -1"
   :matcher (value-matcher 1)})

(def replace-neg1-to-0
  "Replace -1 with 0"
  {:id :replace-neg1-to-0
   :original -1
   :replacement 0
   :description "Replace -1 with 0"
   :matcher (value-matcher -1)})

(def replace-neg1-to-1
  "Replace -1 with 1"
  {:id :replace-neg1-to-1
   :original -1
   :replacement 1
   :description "Replace -1 with 1"
   :matcher (value-matcher -1)})

(def replace-2-to-1
  "Replace 2 with 1"
  {:id :replace-2-to-1
   :original 2
   :replacement 1
   :description "Replace 2 with 1"
   :matcher (value-matcher 2)})

(def replace-2-to-0
  "Replace 2 with 0"
  {:id :replace-2-to-0
   :original 2
   :replacement 0
   :description "Replace 2 with 0"
   :matcher (value-matcher 2)})

(def replace-10-to-0
  "Replace 10 with 0"
  {:id :replace-10-to-0
   :original 10
   :replacement 0
   :description "Replace 10 with 0"
   :matcher (value-matcher 10)})

(def replace-100-to-0
  "Replace 100 with 0"
  {:id :replace-100-to-0
   :original 100
   :replacement 0
   :description "Replace 100 with 0"
   :matcher (value-matcher 100)})

;; =============================================================================
;; Phase 3.1: Collection Operators
;; =============================================================================

(def swap-first-last
  "Replace first with last"
  {:id :swap-first-last
   :original 'first
   :replacement 'last
   :description "Replace first with last"
   :matcher (symbol-matcher 'first)})

(def swap-last-first
  "Replace last with first"
  {:id :swap-last-first
   :original 'last
   :replacement 'first
   :description "Replace last with first"
   :matcher (symbol-matcher 'last)})

(def swap-first-rest
  "Replace first with rest"
  {:id :swap-first-rest
   :original 'first
   :replacement 'rest
   :description "Replace first with rest"
   :matcher (symbol-matcher 'first)})

(def swap-rest-next
  "Replace rest with next"
  {:id :swap-rest-next
   :original 'rest
   :replacement 'next
   :description "Replace rest with next"
   :matcher (symbol-matcher 'rest)})

(def swap-next-rest
  "Replace next with rest"
  {:id :swap-next-rest
   :original 'next
   :replacement 'rest
   :description "Replace next with rest"
   :matcher (symbol-matcher 'next)})

(def swap-take-drop
  "Replace take with drop"
  {:id :swap-take-drop
   :original 'take
   :replacement 'drop
   :description "Replace take with drop"
   :matcher (symbol-matcher 'take)})

(def swap-drop-take
  "Replace drop with take"
  {:id :swap-drop-take
   :original 'drop
   :replacement 'take
   :description "Replace drop with take"
   :matcher (symbol-matcher 'drop)})

(def swap-conj-disj
  "Replace conj with disj"
  {:id :swap-conj-disj
   :original 'conj
   :replacement 'disj
   :description "Replace conj with disj"
   :matcher (symbol-matcher 'conj)})

(def swap-disj-conj
  "Replace disj with conj"
  {:id :swap-disj-conj
   :original 'disj
   :replacement 'conj
   :description "Replace disj with conj"
   :matcher (symbol-matcher 'disj)})

(def swap-inc-dec
  "Replace inc with dec"
  {:id :swap-inc-dec
   :original 'inc
   :replacement 'dec
   :description "Replace inc with dec"
   :matcher (symbol-matcher 'inc)})

(def swap-dec-inc
  "Replace dec with inc"
  {:id :swap-dec-inc
   :original 'dec
   :replacement 'inc
   :description "Replace dec with inc"
   :matcher (symbol-matcher 'dec)})

;; =============================================================================
;; Phase 3.2: Nil-Handling Operators
;; =============================================================================

(def swap-nil-some
  "Replace nil? with some?"
  {:id :swap-nil-some
   :original 'nil?
   :replacement 'some?
   :description "Replace nil? with some?"
   :matcher (symbol-matcher 'nil?)})

(def swap-some-nil
  "Replace some? with nil?"
  {:id :swap-some-nil
   :original 'some?
   :replacement 'nil?
   :description "Replace some? with nil?"
   :matcher (symbol-matcher 'some?)})

(def swap-seq-empty
  "Replace seq with empty?"
  {:id :swap-seq-empty
   :original 'seq
   :replacement 'empty?
   :description "Replace seq with empty?"
   :matcher (symbol-matcher 'seq)})

(def swap-empty-seq
  "Replace empty? with seq"
  {:id :swap-empty-seq
   :original 'empty?
   :replacement 'seq
   :description "Replace empty? with seq"
   :matcher (symbol-matcher 'empty?)})

;; =============================================================================
;; Operator Registry
;; =============================================================================

(def all-operators
  "All available mutation operators."
  [;; Arithmetic
   swap-plus-minus
   swap-minus-plus
   swap-mult-div
   swap-div-mult
   ;; Boolean
   swap-and-or
   swap-or-and
   swap-true-false
   swap-false-true
   ;; Comparison
   swap-lt-gt
   swap-gt-lt
   swap-lte-gte
   swap-gte-lte
   swap-eq-neq
   swap-neq-eq
   ;; Boundary
   swap-lt-lte
   swap-gt-gte
   swap-lte-lt
   swap-gte-gt
   ;; Threading
   swap-thread-first-last
   swap-thread-last-first
   swap-thread-some-first
   swap-some-first-thread
   swap-thread-some-last
   swap-some-last-thread
   ;; Lazy/Eager
   swap-map-mapv
   swap-mapv-map
   swap-filter-filterv
   swap-filterv-filter
   swap-for-doseq
   swap-doseq-for
   ;; HOF
   swap-filter-remove
   swap-remove-filter
   swap-keep-filter
   swap-filter-keep
   ;; Return Value
   replace-nil-false
   replace-nil-zero
   replace-nil-empty-vec
   replace-nil-empty-map
   replace-nil-empty-str
   ;; Constant Replacement
   replace-0-to-1
   replace-1-to-0
   replace-0-to-neg1
   replace-1-to-neg1
   replace-neg1-to-0
   replace-neg1-to-1
   replace-2-to-1
   replace-2-to-0
   replace-10-to-0
   replace-100-to-0
   ;; Collection
   swap-first-last
   swap-last-first
   swap-first-rest
   swap-rest-next
   swap-next-rest
   swap-take-drop
   swap-drop-take
   swap-conj-disj
   swap-disj-conj
   swap-inc-dec
   swap-dec-inc
   ;; Nil-Handling
   swap-nil-some
   swap-some-nil
   swap-seq-empty
   swap-empty-seq])

(def operators-by-id
  "Map from operator id to operator definition."
  (into {} (map (juxt :id identity) all-operators)))

;; =============================================================================
;; Main API
;; =============================================================================

(defn applicable-operators
  "Return all operators that match the given zipper location.

   Returns a sequence of operator maps whose :matcher predicates
   return true for the given zloc."
  [zloc]
  (filter (fn [op]
            (try
              ((:matcher op) zloc)
              (catch Exception _e
                false)))
          all-operators))

(defn apply-operator
  "Apply an operator to a zipper location, returning the replacement string.

   The operator's :replacement value is converted to a string suitable
   for use with rewrite-clj's z/replace or z/edit functions.

   Returns the replacement as a string."
  [op _zloc]
  (let [replacement (:replacement op)]
    (cond
      (symbol? replacement) (name replacement)
      (boolean? replacement) (str replacement)
      :else (str replacement))))
