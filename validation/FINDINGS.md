# ClojureStorm API Validation Findings

This document records the actual behavior of ClojureStorm APIs vs. the assumptions in the Heretic spec.

## Summary

| Aspect | Spec Assumption | Actual Behavior | Impact |
|--------|-----------------|-----------------|--------|
| Coordinate format | Vectors `[3 2 1]` | **Strings** `"3,2,1"` | Minor - no conversion needed |
| Map/set coords | `K-<hash>` / `V-<hash>` | `K<hash>` / `V<hash>` (no dash) | Minor - adjust regex |
| emitted-coords location | `:form/emitted-coords` key | Form **metadata** under `:clojure.storm/emitted-coords` | Adjust access pattern |
| emitted-coords type | Set of strings | `java.util.HashSet` | Convert to Clojure set |
| FormRegistry return | Unclear | Vector of maps | Clear structure |
| Form ID type | Integer (assumed) | **Long** (hash of form) | Confirmed correct |

## Detailed Findings

### 1. FormRegistry Output Format

**`FormRegistry/getAllForms`** returns:
- Type: `clojure.lang.PersistentVector`
- Each element is a `clojure.lang.PersistentArrayMap`

**Form entry structure:**
```clojure
{:form/id       -1004798181        ; Long (hash)
 :form/ns       "sample.core"      ; String
 :form/form     (defn add [a b] (+ a b))  ; The original form (as data)
 :form/def-kind :defn              ; Keyword: :defn, :def, etc.
 :form/file     "sample/core.clj"  ; String - relative path
 :form/line     7}                 ; Integer - line number
```

**Key insight**: The form entry does NOT have a `:form/emitted-coords` key at the top level.
Instead, `emitted-coords` is in the **metadata** of `:form/form`:

```clojure
(-> form-entry :form/form meta :clojure.storm/emitted-coords)
;; => #object[java.util.HashSet ... [, 3, 3,1, 3,2]]
```

**`FormRegistry/getForm`** takes a form-id (Long) and returns the same map structure.

### 2. Coordinate Format

**CRITICAL DIFFERENCE**: Coordinates are passed to callbacks as **strings**, not vectors.

| Spec Assumed | Actual |
|--------------|--------|
| `[3]` | `"3"` |
| `[3 2]` | `"3,2"` |
| `[3 2 1]` | `"3,2,1"` |

**Function return coords**: Empty string `""` (not empty vector `[]`)

**Implication**: The `stringify-coord` function in the spec is **unnecessary** - coords are already strings.

### 3. Hash-Based Coordinates (Maps/Sets)

For map values and set elements, coordinates include hash identifiers.

**Spec assumed format**: `K-<hash>` / `V-<hash>` (with dash)
**Actual format**: `K<hash>` / `V<hash>` (no dash)

Example observed coordinates:
```
"4,1,1,V3919306159"       ; Navigate to index 4, 1, 1, then value with hash 3919306159
"4,1,1,V1836413754,2"     ; Same pattern, then continue to child index 2
```

**Coordinate parts breakdown:**
- Numeric parts: positional indices into sequential forms
- `K<hash>`: Map key or set element identified by hash
- `V<hash>`: Map value for a key identified by hash

### 4. Emitted Coords Storage

The `emitted-coords` (the set of all instrumentable positions in a form) is stored as:
- Location: Metadata of `:form/form` under key `:clojure.storm/emitted-coords`
- Type: `java.util.HashSet` (NOT Clojure set)
- Contents: Strings in comma-separated format

**Must convert to Clojure set for use:**
```clojure
(set (-> form :form/form meta :clojure.storm/emitted-coords))
```

### 5. Coordinate Depth Analysis

From our test run:
| Depth | Count | Examples |
|-------|-------|----------|
| 0 (fn return) | 1 | `""` |
| 1 | 4 | `"3"`, `"4"`, `"1"` |
| 2 | 6 | `"3,2"`, `"4,3"`, `"3,1"` |
| 3 | 7 | `"3,1,1"`, `"4,3,1"`, `"3,3,1"` |
| 4 | 20 | `"2,4,1,2"`, `"4,1,1,V3919306159"` |
| 5 | 11 | `"2,3,1,1,1"`, `"4,1,1,V1836413754,2"` |

### 6. Tracer Callback Signature

```clojure
(Tracer/setTraceFnsCallbacks
 {:trace-expr-fn      (fn [result throwable coord form-id] ...)
  :trace-fn-return-fn (fn [result throwable coord form-id] ...)
  :trace-fn-unwind-fn (fn [result throwable coord form-id] ...)})
```

**Parameters:**
- `result`: The value of the expression (nil for trace-fn-unwind)
- `throwable`: Exception if unwinding, nil otherwise
- `coord`: String coordinate (e.g., `"3,2,1"`)
- `form-id`: Long form ID (matches `:form/id` in FormRegistry)

## Spec Corrections Required

### 1. Remove stringify-coord function

The spec's `stringify-coord` function is not needed since coords arrive as strings:

```clojure
;; SPEC (incorrect assumption):
(defn- stringify-coord [coord]
  (if (string? coord)
    coord
    (str/join "," coord)))

;; ACTUAL: Just use coord directly, it's already a string
```

### 2. Fix emitted-coords access

```clojure
;; SPEC (incorrect):
(get-in forms [form-id :form/emitted-coords])

;; ACTUAL:
(-> (FormRegistry/getForm form-id) :form/form meta :clojure.storm/emitted-coords set)
```

### 3. Hash coordinate regex adjustment

```clojure
;; SPEC (incorrect):
(re-find #"K-\d+" coord)  ; K-12345 format

;; ACTUAL:
(re-find #"K\d+" coord)   ; K12345 format (no dash)
```

## Test Coverage Statistics

From validation run:
- Forms registered: 19 (for sample.core namespace)
- Tests run: 14
- Coordinate hits captured: 299 total, 49 unique
- Hash-based coordinates: 8 (from map value accesses)

## Recommendations

1. **Simplify coverage storage**: Since coords are already strings, store them directly without conversion

2. **Handle HashSet conversion**: When reading emitted-coords from form metadata, convert Java HashSet to Clojure set

3. **Adjust coordinate mapper**: The `coord->zloc` function needs to parse strings, not vectors:
   ```clojure
   (defn parse-coord [coord-str]
     (if (= "" coord-str)
       []
       (mapv #(if (re-matches #"\d+" %) (parse-long %) %)
             (str/split coord-str #","))))
   ```

4. **Document form-id stability**: Form IDs are hashes of the form source, so they change when the form changes. This is correct for cache invalidation.
