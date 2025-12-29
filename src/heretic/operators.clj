(ns heretic.operators
  "Mutation operator definitions for Heretic.

   Mutation operators define transformations to apply to source code.
   Each operator is a data map with:
   - :id          - Keyword identifier like :swap-plus-minus
   - :original    - The symbol/value being replaced (or :dynamic for computed replacements)
   - :replacement - What it becomes (or a function for computed replacements)
   - :description - Human readable description
   - :matcher     - Predicate that checks if a zloc matches

   Main API:
   - `applicable-operators` - Return operators that match a zipper location
   - `apply-operator`       - Return the replacement string for an operator"
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))

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
;; Keyword Transformation Helpers
;; =============================================================================

(defn- kebab->camel
  "Convert kebab-case to camelCase.
   user-id -> userId
   first-name -> firstName"
  [s]
  (let [parts (str/split s #"-")]
    (if (> (count parts) 1)
      (str (first parts)
           (apply str (map str/capitalize (rest parts))))
      s)))

(defn- camel->kebab
  "Convert camelCase to kebab-case.
   userId -> user-id
   firstName -> first-name"
  [s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case))

(defn- has-kebab-case?
  "Check if string contains kebab-case (has hyphen between word chars)."
  [s]
  (boolean (re-find #"\w-\w" s)))

(defn- has-camel-case?
  "Check if string contains camelCase (lowercase followed by uppercase)."
  [s]
  (boolean (re-find #"[a-z][A-Z]" s)))

(defn- add-s-to-namespace
  "Add 's' suffix to namespace part of a qualified keyword.
   :user/id -> :users/id"
  [kw]
  (when-let [ns (namespace kw)]
    (keyword (str ns "s") (name kw))))

(defn- in-destructuring-context?
  "Check if zloc is in a destructuring context.
   Returns true if the keyword is inside:
   - {:keys [...]} vector
   - map literal used in destructuring (let, fn, defn bindings)"
  [zloc]
  (when-let [parent (z/up zloc)]
    (or
     ;; Inside a :keys vector
     (and (= :vector (z/tag parent))
          (when-let [grandparent (z/up parent)]
            (and (= :map (z/tag grandparent))
                 ;; Check if this vector follows a :keys, :strs, or :syms key
                 (when-let [prev (z/left (z/down grandparent))]
                   (let [prev-sexpr (try (z/sexpr prev) (catch Exception _ nil))]
                     (contains? #{:keys :strs :syms} prev-sexpr))))))
     ;; In a map literal (could be map destructuring)
     (= :map (z/tag parent)))))

(defn- keyword-matcher
  "Create a matcher for keywords with a predicate on the keyword name."
  [pred]
  (fn [zloc]
    (and (= :token (z/tag zloc))
         (keyword? (z/sexpr zloc))
         (pred (z/sexpr zloc)))))

(defn- destructuring-keyword-matcher
  "Create a matcher for keywords in destructuring contexts with a predicate."
  [pred]
  (fn [zloc]
    (and (= :token (z/tag zloc))
         (keyword? (z/sexpr zloc))
         (pred (z/sexpr zloc))
         (in-destructuring-context? zloc))))

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
;; Phase 3.1: Destructuring Operators
;; =============================================================================

(def mutate-kebab-to-camel
  "Replace kebab-case keyword with camelCase in destructuring.
   Catches bugs where JS interop expects camelCase but Clojure uses kebab."
  {:id :mutate-kebab-to-camel
   :original :dynamic
   :replacement (fn [zloc]
                  (let [kw (z/sexpr zloc)
                        ns (namespace kw)
                        new-name (kebab->camel (name kw))]
                    (if ns
                      (keyword ns new-name)
                      (keyword new-name))))
   :description "Replace kebab-case keyword with camelCase"
   :matcher (destructuring-keyword-matcher
             (fn [kw] (has-kebab-case? (name kw))))})

(def mutate-camel-to-kebab
  "Replace camelCase keyword with kebab-case in destructuring.
   Catches bugs where Clojure code expects kebab-case but data uses camelCase."
  {:id :mutate-camel-to-kebab
   :original :dynamic
   :replacement (fn [zloc]
                  (let [kw (z/sexpr zloc)
                        ns (namespace kw)
                        new-name (camel->kebab (name kw))]
                    (if ns
                      (keyword ns new-name)
                      (keyword new-name))))
   :description "Replace camelCase keyword with kebab-case"
   :matcher (destructuring-keyword-matcher
             (fn [kw] (has-camel-case? (name kw))))})

(def mutate-ns-typo
  "Add 's' suffix to namespace (common typo: :user/id -> :users/id).
   Catches bugs where namespace singular/plural is incorrect."
  {:id :mutate-ns-typo
   :original :dynamic
   :replacement (fn [zloc]
                  (let [kw (z/sexpr zloc)]
                    (add-s-to-namespace kw)))
   :description "Add 's' suffix to keyword namespace"
   :matcher (keyword-matcher
             (fn [kw]
               ;; Only match qualified keywords that don't already end in 's'
               (and (namespace kw)
                    (not (str/ends-with? (namespace kw) "s")))))})

(def mutate-qualified-to-unqualified
  "Remove namespace from qualified keyword.
   Catches bugs where unqualified key is used but data has qualified keys."
  {:id :mutate-qualified-to-unqualified
   :original :dynamic
   :replacement (fn [zloc]
                  (let [kw (z/sexpr zloc)]
                    (keyword (name kw))))
   :description "Remove namespace from qualified keyword"
   :matcher (keyword-matcher namespace)})

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
   swap-empty-seq
   ;; Destructuring
   mutate-kebab-to-camel
   mutate-camel-to-kebab
   mutate-ns-typo
   mutate-qualified-to-unqualified])

(def operators-by-id
  "Map from operator id to operator definition."
  (into {} (map (juxt :id identity) all-operators)))

;; =============================================================================
;; Operator Presets
;; =============================================================================

(def presets
  "Predefined operator sets for different mutation testing strategies.

   :fast - ~15 high-impact operators that research shows catch ~99% of bugs.
           Includes core arithmetic, comparison, and boolean operators.
           Best for quick feedback during development.

   :standard - A balanced set of operators for regular mutation testing.
               Includes all :fast operators plus collection, nil-handling,
               and threading operators. Good default for CI.

   :comprehensive - All available operators for thorough mutation testing.
                    Use when you want maximum coverage and have time budget."
  {:fast
   ;; Core arithmetic (catches off-by-one, sign errors)
   #{:swap-plus-minus
     :swap-minus-plus
     :swap-mult-div
     :swap-div-mult
     :swap-inc-dec
     :swap-dec-inc
     ;; Comparison operators (catches boundary errors)
     :swap-lt-gt
     :swap-gt-lt
     :swap-eq-neq
     :swap-neq-eq
     ;; Boolean operators (catches logic errors)
     :swap-and-or
     :swap-or-and
     :swap-true-false
     :swap-false-true
     ;; Nil handling (catches null safety issues)
     :swap-nil-some
     :swap-some-nil}

   :standard
   ;; All :fast operators plus...
   #{;; Fast core operators
     :swap-plus-minus
     :swap-minus-plus
     :swap-mult-div
     :swap-div-mult
     :swap-inc-dec
     :swap-dec-inc
     :swap-lt-gt
     :swap-gt-lt
     :swap-eq-neq
     :swap-neq-eq
     :swap-and-or
     :swap-or-and
     :swap-true-false
     :swap-false-true
     :swap-nil-some
     :swap-some-nil
     ;; Boundary mutations
     :swap-lt-lte
     :swap-gt-gte
     :swap-lte-lt
     :swap-gte-gt
     :swap-lte-gte
     :swap-gte-lte
     ;; Collection operators
     :swap-first-last
     :swap-last-first
     :swap-rest-next
     :swap-next-rest
     :swap-take-drop
     :swap-drop-take
     :swap-conj-disj
     :swap-disj-conj
     ;; Nil handling extended
     :swap-seq-empty
     :swap-empty-seq
     ;; HOF operators
     :swap-filter-remove
     :swap-remove-filter
     ;; Threading operators
     :swap-thread-first-last
     :swap-thread-last-first}

   :comprehensive
   ;; All operators
   (set (map :id all-operators))})

(defn operators-for-preset
  "Return the operator definitions for a given preset name.

   Arguments:
   - preset: Keyword preset name (:fast, :standard, :comprehensive)

   Returns sequence of operator maps.

   Throws if preset is unknown."
  [preset]
  (if-let [op-ids (get presets preset)]
    (keep operators-by-id op-ids)
    (throw (ex-info "Unknown operator preset"
                    {:preset preset
                     :available (keys presets)}))))

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

   For dynamic operators (where :replacement is a function), the function
   is called with the zloc and the result is converted to a string.

   Returns the replacement as a string."
  [op zloc]
  (let [replacement (:replacement op)
        ;; For dynamic operators, call the replacement function
        resolved (if (fn? replacement)
                   (replacement zloc)
                   replacement)]
    (cond
      (keyword? resolved) (str resolved)
      (symbol? resolved) (name resolved)
      (boolean? resolved) (str resolved)
      :else (str resolved))))
