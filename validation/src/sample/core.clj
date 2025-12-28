(ns sample.core
  "Sample namespace with various forms for ClojureStorm validation.
   Tests arithmetic, conditionals, and unordered collections (maps/sets).")

;;; Arithmetic functions

(defn add [a b]
  (+ a b))

(defn multiply [a b]
  (* a b))

(defn compute-with-nested
  "Function with nested arithmetic expressions"
  [x y z]
  (+ (* x y)
     (/ z 2)))

;;; Conditional functions

(defn classify-number [n]
  (cond
    (neg? n) :negative
    (zero? n) :zero
    :else :positive))

(defn safe-divide
  "Division with nil safety"
  [a b]
  (if (zero? b)
    nil
    (/ a b)))

(defn check-range
  "Check if value is in range, with early returns"
  [x min max]
  (when (and (>= x min)
             (<= x max))
    :in-range))

;;; Map and set operations (testing hash-based coordinates)

(def config-map
  "A literal map to test map coordinates"
  {:name "heretic"
   :version "0.1.0"
   :enabled true
   :nested {:level 1
            :deep {:value 42}}})

(def tag-set
  "A literal set to test set coordinates"
  #{:alpha :beta :gamma})

(defn lookup-config
  "Function that uses a map"
  [config key]
  (get config key))

(defn has-tag?
  "Function that uses a set"
  [tags tag]
  (contains? tags tag))

(defn process-with-map
  "Function that builds and uses a map inline"
  [x y]
  (let [result {:input-x x
                :input-y y
                :sum (+ x y)
                :product (* x y)}]
    (:sum result)))

(defn filter-with-set
  "Filter values using set membership"
  [values allowed-set]
  (filter allowed-set values))

(defn use-literal-map
  "Function that accesses a literal map at call site"
  [x]
  (get {:key-a 1
        :key-b 2
        :key-c 3}
       x))

(defn use-literal-set
  "Function that checks literal set membership"
  [x]
  (contains? #{:val-x :val-y :val-z} x))

;;; More complex forms

(defn factorial
  "Recursive factorial for testing recursion coordinates"
  [n]
  (if (<= n 1)
    1
    (* n (factorial (dec n)))))

(defn reduce-example
  "Testing reduce with anonymous function"
  [nums]
  (reduce (fn [acc x] (+ acc (* x x)))
          0
          nums))
