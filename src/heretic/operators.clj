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

;; =============================================================================
;; Operator Registry
;; =============================================================================

(def all-operators
  "All available mutation operators."
  [swap-plus-minus
   swap-minus-plus
   swap-mult-div
   swap-div-mult
   swap-and-or
   swap-or-and
   swap-true-false
   swap-false-true])

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
