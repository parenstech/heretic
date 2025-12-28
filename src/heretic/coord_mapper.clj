(ns heretic.coord-mapper
  "Bidirectional mapping between ClojureStorm coordinates and rewrite-clj zippers.

   ClojureStorm uses positional paths into the AST:
   - Sequential forms: comma-separated indices like \"3,2,1\"
   - Unordered forms (maps/sets): hash-based strings like \"K12345\" for keys, \"V12345\" for values

   rewrite-clj uses zippers for navigation. This namespace bridges the two:
   - coord->zloc: Navigate a zipper using ClojureStorm coordinates
   - zloc->coord: Get ClojureStorm coordinate for a zipper position

   Validation requirement: Round-trip must be identity
   (= coord (zloc->coord (coord->zloc zloc coord)))

   Example coordinates:
   (defn foo [a b] (+ a b))
   ;;  0    1   2     3
   ;; \"3\"     -> (+ a b)
   ;; \"3,0\"   -> +
   ;; \"3,1\"   -> a
   ;; \"3,2\"   -> b"
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Coordinate Parsing
;; =============================================================================

(defn parse-coord
  "Parse a stringified coordinate into components.

   \"3,2,1\" -> [3 2 1]
   \"3,K12345,1\" -> [3 \"K12345\" 1]
   \"\" -> []

   Components are either integers (for sequential access) or strings
   (for hash-based access to map/set elements: K<hash> for keys, V<hash> for values)."
  [coord-str]
  (cond
    (vector? coord-str) coord-str  ;; Already parsed
    (= "" coord-str) []            ;; Empty coord (function return)
    :else (mapv (fn [part]
                  (if (re-matches #"\d+" part)
                    (parse-long part)
                    part))
                (str/split coord-str #","))))

(defn stringify-coord
  "Convert coordinate vector to string.

   [3 2 1] -> \"3,2,1\"
   [3 \"K12345\" 1] -> \"3,K12345,1\"
   [] -> \"\""
  [coord]
  (cond
    (string? coord) coord
    (empty? coord) ""
    :else (str/join "," coord)))

;; =============================================================================
;; Hash-based Navigation (Maps/Sets)
;; =============================================================================

(defn- compute-form-hash
  "Compute hash for a rewrite-clj node, matching ClojureStorm's hansel.utils/clojure-form-source-hash.

   The algorithm:
   1. Converts form to string via z/string
   2. Removes reader tags (#tag)
   3. Removes metadata keywords (^:key)
   4. Removes metadata maps (^{...})
   5. Removes comments (; ...)
   6. Removes all whitespace
   7. Computes a 32-bit hash using a position-weighted sum

   Returns the hash as a Long."
  [zloc]
  (let [M 4294967291
        s (z/string zloc)
        clean-s (-> s
                    (str/replace #"#[/.a-zA-Z0-9_-]+" "")  ;; remove tags
                    (str/replace #"\^:[a-zA-Z0-9_-]+" "")  ;; remove meta keys
                    (str/replace #"\^\{.+?\}" "")          ;; remove meta maps
                    (str/replace #";.+\n" "")              ;; remove comments
                    (str/replace #"[ \t\n]+" ""))]         ;; remove non visible
    (loop [sum 0
           mul 1
           i 0
           [c & srest] clean-s]
      (if (nil? c)
        (mod sum M)
        (let [mul' (if (zero? (mod i 4)) 1 (* mul 256))
              sum' (+ sum (* (long c) mul'))]
          (recur sum' mul' (inc i) srest))))))

(defn- find-by-hash
  "Find element in unordered collection by its hash.

   Hash format (no dash between letter and hash):
   - K<hash> for map keys or set elements
   - V<hash> for map values

   Returns zipper at the matching element, or nil if not found."
  [zloc hash-str]
  (let [prefix (subs hash-str 0 1)                    ;; "K" or "V"
        target-hash (parse-long (subs hash-str 1))    ;; hash after first letter
        is-map? (= :map (z/tag zloc))
        is-set? (= :set (z/tag zloc))]
    (cond
      ;; For sets, search all elements
      is-set?
      (loop [child (z/down zloc)]
        (when child
          (if (= target-hash (compute-form-hash child))
            child
            (recur (z/right child)))))

      ;; For maps, search keys or values based on prefix
      is-map?
      (loop [child (z/down zloc)
             is-key? true]
        (when child
          (let [matches? (and (= target-hash (compute-form-hash child))
                              (case prefix
                                "K" is-key?
                                "V" (not is-key?)
                                false))]
            (if matches?
              child
              (recur (z/right child) (not is-key?))))))

      :else nil)))

;; =============================================================================
;; Sequential Navigation
;; =============================================================================

(defn- nth-child
  "Navigate to the nth child of a zipper location.
   Returns nil if the child doesn't exist."
  [zloc n]
  (let [first-child (z/down zloc)]
    (when first-child
      (loop [current first-child
             i 0]
        (cond
          (= i n) current
          (nil? current) nil
          :else (recur (z/right current) (inc i)))))))

;; =============================================================================
;; Coordinate Navigation
;; =============================================================================

(defn coord->zloc
  "Navigate a zipper using ClojureStorm coordinates.

   coord can be:
   - A string like \"3,2,1\"
   - A vector like [3 2 1]
   - Mixed with hash refs: [3 \"K12345\" 1] or \"3,V12345,1\"

   Returns the zipper at the target location, or nil if navigation fails."
  [zloc coord]
  (let [parts (parse-coord coord)]
    (reduce
     (fn [z part]
       (when z
         (if (string? part)
           (find-by-hash z part)
           (nth-child z part))))
     zloc
     parts)))

;; =============================================================================
;; Coordinate Extraction
;; =============================================================================

(defn- child-index
  "Get the index of a zipper among its siblings.
   Returns the 0-based index."
  [zloc]
  (loop [z (z/left zloc)
         idx 0]
    (if z
      (recur (z/left z) (inc idx))
      idx)))

(defn- is-unordered-collection?
  "Check if the parent is an unordered collection (map or set)."
  [zloc]
  (when-let [parent (z/up zloc)]
    (#{:map :set} (z/tag parent))))

(defn- compute-hash-coord
  "Compute hash-based coordinate for element in unordered collection.

   For sets: Returns \"K<hash>\"
   For maps: Returns \"K<hash>\" for keys, \"V<hash>\" for values"
  [zloc]
  (let [parent (z/up zloc)
        h (compute-form-hash zloc)]
    (if (= :set (z/tag parent))
      (str "K" h)
      ;; For maps, determine if this is a key or value
      (let [idx (child-index zloc)]
        (if (even? idx)
          (str "K" h)
          (str "V" h))))))

(defn- root-form?
  "Check if this zloc is the root form (direct child of :forms node).
   The :forms node is the implicit container created by z/of-string."
  [zloc]
  (when-let [parent (z/up zloc)]
    (= :forms (z/tag parent))))

(defn zloc->coord
  "Get ClojureStorm coordinate for a zipper position.

   Returns a vector like [3 2 1] or [3 \"K-12345\" 1].
   Returns nil if at the root form (the top-level form from z/of-string)."
  [zloc]
  (loop [z zloc
         coord []]
    (cond
      ;; At root form (direct child of :forms) - stop here
      (root-form? z)
      (when (seq coord)
        (vec coord))

      ;; Has parent - add index and continue up
      (z/up z)
      (let [part (if (is-unordered-collection? z)
                   (compute-hash-coord z)
                   (child-index z))]
        (recur (z/up z) (cons part coord)))

      ;; No parent at all (shouldn't happen with z/of-string)
      :else
      (when (seq coord)
        (vec coord)))))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-round-trip
  "Validate that coord->zloc and zloc->coord are inverses.

   Returns {:valid true} or {:valid false :error ...}"
  [zloc coord]
  (try
    (let [target (coord->zloc zloc coord)]
      (if (nil? target)
        {:valid false
         :error :navigation-failed
         :coord coord}
        (let [recovered (zloc->coord target)]
          (if (= (parse-coord coord) recovered)
            {:valid true}
            {:valid false
             :error :round-trip-mismatch
             :original coord
             :recovered recovered}))))
    (catch Exception e
      {:valid false
       :error :exception
       :message (.getMessage e)})))

(defn validate-all-coords
  "Validate round-trip for all coordinates in a form.

   Returns sequence of validation results for failed coordinates."
  [zloc coords]
  (for [coord coords
        :let [result (validate-round-trip zloc coord)]
        :when (not (:valid result))]
    result))
