---
status: phase-3-nearly-complete
contributions:
  - "Needs: Phase 3 file-level parallelism (mutate files concurrently)"
  - "Needs: Phase 3 mutant clustering (group similar, test representative)"
  - "Needs: Phase 4 process-level worker pool with pre-forked JVMs"
  - "Needs: Phase 4 ClojureScript/shadow-cljs integration"
  - "Needs: Phase 4 AI-powered semantic mutation generation"
  - "Needs: Phase 4 LLM test generation for survivors (from Meta ACH)"
  - "Needs: Phase 4 Hybrid equivalent detection with LLM fallback"
---

# Heretic: Mutation Testing for Clojure

## Overview

Heretic is a mutation testing tool for Clojure that leverages ClojureStorm's instrumentation to efficiently map tests to code. This enables targeted mutation testing - only running relevant tests for each mutation rather than the entire suite.

## Goals

1. **Test-to-code mapping**: Know which tests exercise which code
2. **Targeted mutation testing**: Run only relevant tests per mutation
3. **Mutation operators**: Apply semantic mutations to Clojure code
4. **Reporting**: Show which mutations survived (indicating weak tests)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Heretic                                  │
├──────────────────┬──────────────────┬───────────────────────────┤
│  Coverage Map    │  Mutation Engine │  Test Runner              │
│  (Phase 1)       │  (Phase 2)       │  (Phase 2)                │
├──────────────────┴──────────────────┴───────────────────────────┤
│                    clj-reload (namespace reloading)              │
├─────────────────────────────────────────────────────────────────┤
│                    ClojureStorm (instrumentation)                │
├─────────────────────────────────────────────────────────────────┤
│                    rewrite-clj (source manipulation)             │
└─────────────────────────────────────────────────────────────────┘
```

## Phase 1: Coverage Map Collection

### Purpose

Build a mapping of which tests exercise which code locations. This is a one-time cost (per test suite change) that enables efficient Phase 2.

### Storage Layout

Coverage data is split across multiple files for incremental updates:

```
.heretic/
├── meta.edn                         # Global metadata + form registry
├── coverage/
│   ├── my.app.core-test.edn         # Coverage for this test namespace
│   ├── my.app.util-test.edn         # Coverage for this test namespace
│   └── ...
└── index.edn                        # Derived inverse index (rebuilt from parts)
```

### Data Structures

**Per-test-namespace file** (`.heretic/coverage/my.app.core-test.edn`):

```clojure
{:test-ns my.app.core-test

 ;; Coverage: test-var → form-id → coords
 :coverage
 {my.app.core-test/test-addition {12345 #{"3" "3,1" "3,2"}
                                   12346 #{"1" "2,1"}}
  my.app.core-test/test-subtraction {12345 #{"3" "4"}
                                      12347 #{"1"}}}

 ;; Dependencies: source files this test namespace touched
 :source-deps #{"src/my/app/core.clj" "src/my/app/util.clj"}

 ;; Staleness: hashes for this test namespace only
 :hashes {:test-file "abc123..."       ;; Hash of test file
          :source-files "def456..."    ;; Hash of touched source files
          :config "ghi789..."}}        ;; Hash of heretic config
```

**Global metadata** (`.heretic/meta.edn`):

```clojure
{;; Form registry (from ClojureStorm FormRegistry/getAllForms)
 ;; Note: emitted-coords are extracted from form metadata (:clojure.storm/emitted-coords)
 ;; and converted from java.util.HashSet to Clojure set
 :forms
 {12345 {:form/ns "my.app.core"
         :form/form (defn foo [x] (+ x 1))
         :form/emitted-coords #{"" "3" "3,1" "3,2"}}  ;; "" is root/fn-return coord
  12346 {:form/ns "my.app.core"
         :form/form (defn bar [a b] (* a b))
         :form/emitted-coords #{"" "3" "3,1" "3,2"}}
  ...}

 ;; Global metadata
 :collected-at 1703789012345
 :heretic-version "0.1.0"}
```

**Derived inverse index** (`.heretic/index.edn`):

```clojure
;; Rebuilt from all coverage files - not authoritative
{:coord-to-tests
 {[12345 "3"]     #{my.app.core-test/test-addition my.app.core-test/test-subtraction}
  [12345 "3,2"]   #{my.app.core-test/test-addition}
  [12345 "3,2,1"] #{my.app.core-test/test-addition}
  [12345 "4"]     #{my.app.core-test/test-subtraction}
  ...}

 ;; Which test namespaces are included
 :included-test-ns #{my.app.core-test my.app.util-test}

 ;; When index was rebuilt
 :rebuilt-at 1703789012400}
```

### Components

#### 1. Coverage Tracer

Receives callbacks from ClojureStorm and records coverage. ClojureStorm callbacks
receive coordinates as **strings** (e.g., `"3,2,1"`), so no conversion is needed.

```clojure
(ns heretic.tracer
  (:import [clojure.storm Emitter Tracer FormRegistry]))

;; Current coverage accumulator (reset between tests)
(def ^:private current-coverage
  "Atom of {form-id #{coords}} for the currently running test"
  (atom {}))

(defn- record-hit!
  "Record a coverage hit for the current test.
   Called by ClojureStorm for each expression evaluation.
   Coordinates are already strings (e.g., \"3,2,1\" or \"\" for root)."
  [form-id coord]
  (swap! current-coverage
         update form-id
         (fnil conj #{})
         coord))

(defn init!
  "Initialize ClojureStorm instrumentation with Heretic's callbacks"
  []
  (Emitter/setInstrumentationEnable true)
  (Emitter/setFnCallInstrumentationEnable false)
  (Emitter/setFnReturnInstrumentationEnable true)
  (Emitter/setExprInstrumentationEnable true)
  (Emitter/setBindInstrumentationEnable false)

  (Tracer/setTraceFnsCallbacks
   {:trace-expr-fn      (fn [_ _ coord form-id] (record-hit! form-id coord))
    :trace-fn-return-fn (fn [_ _ coord form-id] (record-hit! form-id coord))
    :trace-fn-unwind-fn (fn [_ _ coord form-id] (record-hit! form-id coord))}))

(defn get-current-coverage
  "Return coverage accumulated for the current test"
  []
  @current-coverage)

(defn reset-current-coverage!
  "Clear coverage for the next test"
  []
  (reset! current-coverage {}))
```

#### 2. Per-Test Coverage Collector

Runs each test individually and collects its coverage. This approach:
- Works with **all test runners** (Kaocha, Cognitect, clojure.test)
- Avoids threading issues with `binding`
- Doesn't require `alter-var-root` hacks

```clojure
(ns heretic.collector
  (:require [heretic.tracer :as tracer]
            [clojure.test :as t]))

(defn discover-test-vars
  "Find all test vars in the given namespaces"
  [test-namespaces]
  (for [ns-sym test-namespaces
        :let [ns-obj (the-ns ns-sym)]
        [_ v] (ns-publics ns-obj)
        :when (:test (meta v))]
    v))

(defn run-test-with-coverage
  "Run a single test and capture its coverage.
   Returns {test-symbol -> {form-id -> #{coords}}}"
  [test-var]
  (tracer/reset-current-coverage!)
  (try
    ;; Run the test (works regardless of test runner)
    (test-var)
    (catch Throwable t
      ;; Log but continue - we still want the coverage
      (println "Test threw exception:" (.getMessage t))))
  ;; Return coverage for this test
  {(symbol test-var) (tracer/get-current-coverage)})

(defn collect-all-coverage
  "Run all tests one by one and collect per-test coverage.
   Returns {:test-coverage {test-sym {form-id #{coords}}}}"
  [test-namespaces]
  (tracer/init!)
  (let [test-vars (discover-test-vars test-namespaces)
        coverage (reduce
                   (fn [acc test-var]
                     (merge acc (run-test-with-coverage test-var)))
                   {}
                   test-vars)]
    {:test-coverage coverage}))
```

#### 3. Coverage Map Builder

Process raw coverage into per-namespace files and rebuild indexes.

```clojure
(ns heretic.coverage-map
  (:require [heretic.collector :as collector]
            [heretic.persistence :as persist]
            [clojure.java.io :as io])
  (:import [clojure.storm FormRegistry]))

(defn get-form-registry
  "Get all forms from ClojureStorm's FormRegistry.
   Returns {form-id -> {:form/ns, :form/form, :form/emitted-coords}}

   Note: FormRegistry/getAllForms returns a vector of maps.
   emitted-coords must be extracted from form metadata and converted
   from java.util.HashSet to Clojure set."
  []
  (into {}
        (for [entry (FormRegistry/getAllForms)]
          [(:form/id entry)
           (assoc entry
                  :form/emitted-coords
                  (-> entry :form/form meta :clojure.storm/emitted-coords set))])))

(defn- extract-source-deps
  "Given coverage data, determine which source files were touched.
   Uses FormRegistry to map form-ids to namespaces, then to file paths."
  [coverage forms source-paths]
  (let [touched-ns (into #{}
                         (for [[_ form-coords] coverage
                               form-id (keys form-coords)
                               :let [ns-str (get-in forms [form-id :form/ns])]
                               :when ns-str]
                           ns-str))]
    ;; Map namespaces to actual file paths
    (into #{}
          (for [ns-str touched-ns
                :let [path (-> ns-str
                               (clojure.string/replace "." "/")
                               (clojure.string/replace "-" "_")
                               (str ".clj"))
                      full-path (some #(let [f (io/file % path)]
                                         (when (.exists f) (.getPath f)))
                                      source-paths)]
                :when full-path]
            full-path))))

(defn collect-test-namespace!
  "Collect coverage for a single test namespace.
   Returns per-namespace coverage data structure."
  [test-ns forms source-paths config]
  (let [{:keys [test-coverage]} (collector/collect-coverage-for-ns test-ns)
        source-deps (extract-source-deps test-coverage forms source-paths)
        test-file (-> (str test-ns)
                      (clojure.string/replace "." "/")
                      (clojure.string/replace "-" "_")
                      (str ".clj"))]
    {:test-ns test-ns
     :coverage test-coverage
     :source-deps source-deps
     :hashes {:test-file (persist/hash-file test-file)
              :source-files (persist/hash-files source-deps)
              :config (hash config)}}))

(defn build-inverse-index
  "Build form+coord -> tests index from all coverage files"
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

(defn rebuild-index!
  "Rebuild inverse index from all coverage files.
   Called after incremental updates."
  [heretic-dir]
  (let [coverage-dir (io/file heretic-dir "coverage")
        coverage-files (for [f (.listFiles coverage-dir)
                             :when (.endsWith (.getName f) ".edn")]
                         (persist/load-edn f))
        coord-to-tests (build-inverse-index coverage-files)
        included-ns (into #{} (map :test-ns coverage-files))]
    (persist/save-edn! (io/file heretic-dir "index.edn")
                       {:coord-to-tests coord-to-tests
                        :included-test-ns included-ns
                        :rebuilt-at (System/currentTimeMillis)})))

(defn tests-for-location
  "Given a form-id and optional coord, return tests that hit it"
  ([index form-id]
   ;; For form-level lookup, scan all coords for this form-id
   (into #{}
         (for [[[fid _] tests] (:coord-to-tests index)
               :when (= fid form-id)
               test tests]
           test)))

  ([index form-id coord]
   (get-in index [:coord-to-tests [form-id coord]] #{})))
```

#### 4. Persistence

Save/load coverage with atomic writes and per-namespace staleness checking.

```clojure
(ns heretic.persistence
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]))

(def default-dir ".heretic")

;; ============================================================
;; Atomic File Operations
;; ============================================================

(defn- atomic-spit!
  "Write content atomically using temp file + rename.
   Prevents corruption if process crashes mid-write."
  [path content]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (io/make-parents path)
    (let [temp-file (File/createTempFile "heretic" ".tmp" parent)]
      (try
        (spit temp-file content)
        (Files/move (.toPath temp-file)
                    (.toPath file)
                    (into-array [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (catch Exception e
          (.delete temp-file)
          (throw e))))))

(defn save-edn!
  "Save EDN data atomically"
  [path data]
  (atomic-spit! path (pr-str data)))

(defn load-edn
  "Load EDN data from disk"
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

;; ============================================================
;; Hashing for Staleness Detection
;; ============================================================

(defn hash-file
  "Compute hash of a single file's contents"
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (hash (slurp f)))))

(defn hash-files
  "Compute combined hash of multiple files"
  [paths]
  (let [file-hashes (for [path paths
                          :let [h (hash-file path)]
                          :when h]
                      h)]
    (hash (vec (sort file-hashes)))))

;; ============================================================
;; Per-Namespace Coverage Files
;; ============================================================

(defn coverage-file-path
  "Get path for a test namespace's coverage file"
  [heretic-dir test-ns]
  (io/file heretic-dir "coverage"
           (str (str/replace (str test-ns) "." "-") ".edn")))

(defn save-test-ns-coverage!
  "Save coverage data for a single test namespace"
  [heretic-dir coverage-data]
  (let [path (coverage-file-path heretic-dir (:test-ns coverage-data))]
    (save-edn! path coverage-data)))

(defn load-test-ns-coverage
  "Load coverage data for a single test namespace"
  [heretic-dir test-ns]
  (load-edn (coverage-file-path heretic-dir test-ns)))

(defn delete-test-ns-coverage!
  "Delete coverage file for a test namespace (e.g., if namespace deleted)"
  [heretic-dir test-ns]
  (let [f (coverage-file-path heretic-dir test-ns)]
    (when (.exists f)
      (.delete f))))

(defn list-coverage-files
  "List all coverage files in the heretic directory"
  [heretic-dir]
  (let [coverage-dir (io/file heretic-dir "coverage")]
    (when (.exists coverage-dir)
      (for [f (.listFiles coverage-dir)
            :when (.endsWith (.getName f) ".edn")]
        f))))

;; ============================================================
;; Staleness Detection (Per-Namespace)
;; ============================================================

(defn test-ns-stale?
  "Check if a single test namespace's coverage needs regeneration.
   Stale if:
   - Coverage file doesn't exist
   - Test file changed
   - Any source file it depends on changed
   - Config changed"
  [heretic-dir test-ns test-paths source-paths config]
  (let [coverage-data (load-test-ns-coverage heretic-dir test-ns)]
    (if (nil? coverage-data)
      true  ;; No coverage file → stale
      (let [{:keys [test-file source-files config]} (:hashes coverage-data)
            {:keys [source-deps]} coverage-data
            ;; Find test file path
            test-file-path (some #(let [f (io/file % (-> (str test-ns)
                                                          (str/replace "." "/")
                                                          (str/replace "-" "_")
                                                          (str ".clj")))]
                                    (when (.exists f) (.getPath f)))
                                 test-paths)]
        (or (not= test-file (hash-file test-file-path))
            (not= source-files (hash-files source-deps))
            (not= (:config (:hashes coverage-data)) (hash config)))))))

(defn find-stale-test-namespaces
  "Find all test namespaces that need recollection.
   Returns set of namespace symbols."
  [heretic-dir test-namespaces test-paths source-paths config]
  (into #{}
        (filter #(test-ns-stale? heretic-dir % test-paths source-paths config))
        test-namespaces))

;; ============================================================
;; Global Metadata
;; ============================================================

(defn save-meta!
  "Save global metadata (form registry, etc.)"
  [heretic-dir meta-data]
  (save-edn! (io/file heretic-dir "meta.edn") meta-data))

(defn load-meta
  "Load global metadata"
  [heretic-dir]
  (load-edn (io/file heretic-dir "meta.edn")))

;; ============================================================
;; Index (Derived, Rebuilt)
;; ============================================================

(defn save-index!
  "Save derived inverse index"
  [heretic-dir index-data]
  (save-edn! (io/file heretic-dir "index.edn") index-data))

(defn load-index
  "Load derived inverse index"
  [heretic-dir]
  (load-edn (io/file heretic-dir "index.edn")))
```

### Collection Flow

```
1. User runs: bb heretic:collect (or automatic on first mutation run)

2. Heretic checks for incremental update:
   a. Find stale test namespaces (test file changed, source deps changed, or missing)
   b. If --force flag, treat all as stale
   c. Report: "3 of 15 test namespaces need recollection"

3. For each stale test namespace:
   a. Initialize ClojureStorm callbacks if not already done
   b. Discover test vars in this namespace
   c. For each test var:
      i.   Reset current coverage accumulator
      ii.  Run the single test
      iii. Capture coverage: {form-id -> #{coords}}
      iv.  Store under test identifier
   d. Extract source dependencies (which source files were touched)
   e. Compute hashes for staleness tracking
   f. Persist atomically to .heretic/coverage/<namespace>.edn

4. After all stale namespaces collected:
   a. Update global metadata (form registry snapshot)
   b. Rebuild inverse index from all coverage files
   c. Save index.edn

5. Output: coverage ready for mutation testing
```

**Benefits of split storage**:
- **Incremental updates**: Only recollect test namespaces that changed
- **Targeted staleness**: Track source deps per test namespace, not globally
- **Parallelism-ready**: Different test namespaces can be collected in parallel
- **Granular invalidation**: Changing `core.clj` only invalidates tests that touch it

**Trade-off**: Index must be rebuilt after any coverage file changes. This is fast
(just reading and merging EDN files) compared to re-running tests.

**Key design decision**: Running tests one-by-one is slower than running all tests
at once, but it:
- Works with ALL test runners (not just clojure.test)
- Avoids thread-local binding issues with async tests
- Produces accurate per-test coverage
- Enables per-namespace staleness tracking

---

## Phase 2: Mutation Testing

### CRITICAL: Self-Instrumentation Prevention

**Heretic code must NOT be instrumented during mutation testing.** If ClojureStorm
instruments heretic itself, infinite recursion occurs when tracer callbacks invoke
instrumented heretic functions, causing hangs.

**Required JVM opts:**
```
-Dclojure.storm.instrumentAutoPrefixes=false
-Dclojure.storm.instrumentOnlyPrefixes=<your-app-prefix>
```

The `instrumentAutoPrefixes=false` prevents ClojureStorm from automatically
instrumenting all loaded code. Combined with `instrumentOnlyPrefixes`, this ensures
only target application code is instrumented, excluding heretic.

Alternatively, explicitly skip heretic:
```
-Dclojure.storm.instrumentSkipPrefixes=heretic
```

### Purpose

Apply mutations to source code and run targeted tests to see if mutations are caught.

### Mutation Operators

Clojure-specific mutations to apply:

#### Arithmetic Operators
| Original | Mutation |
|----------|----------|
| `+` | `-`, `*`, `/` |
| `-` | `+`, `*`, `/` |
| `*` | `+`, `-`, `/` |
| `/` | `+`, `-`, `*` |

#### Comparison Operators
| Original | Mutation |
|----------|----------|
| `<` | `<=`, `>`, `>=`, `=` |
| `<=` | `<`, `>`, `>=`, `=` |
| `>` | `<`, `<=`, `>=`, `=` |
| `>=` | `<`, `<=`, `>`, `=` |
| `=` | `not=` |
| `not=` | `=` |

#### Boolean Operators
| Original | Mutation |
|----------|----------|
| `and` | `or` |
| `or` | `and` |
| `not` | (remove) |
| `true` | `false` |
| `false` | `true` |

#### Collection Operators
| Original | Mutation | Context |
|----------|----------|---------|
| `first` | `last`, `rest` | sequences only |
| `last` | `first`, `butlast` | sequences only |
| `conj` | `disj` | sets only |
| `inc` | `dec` | |
| `dec` | `inc` | |

#### Control Flow
| Original | Mutation |
|----------|----------|
| `if` condition | negate condition |
| `when` condition | negate condition |
| `cond` branch | remove branch |

**Note**: Don't mutate inside quoted forms (`'(...)` or `(quote ...)`).

#### Return Values
| Original | Mutation |
|----------|----------|
| Return value | `nil` |
| Non-empty collection | `[]` or `{}` or `#{}` |
| Non-zero number | `0` |
| Non-empty string | `""` |

### Form-ID Computation

Form-IDs are computed by `hansel.utils/clojure-form-source-hash`. This is the same
hash used by ClojureStorm to identify forms in the FormRegistry. Heretic must use
the same algorithm to look up coverage data for mutation sites.

**Algorithm:**

```clojure
(defn clojure-form-source-hash
  "Hash a clojure form string into a 32 bit num.
  Meant to be called with printed representations of a form,
  or a form source read from a file."
  [s]
  (let [M 4294967291
        clean-s (-> s
                    (str/replace #"#[/.a-zA-Z0-9_-]+" "") ;; remove tags
                    (str/replace #"\^:[a-zA-Z0-9_-]+" "") ;; remove meta keys
                    (str/replace #"\^\{.+?\}" "")         ;; remove meta maps
                    (str/replace #";.+\n" "")             ;; remove comments
                    (str/replace #"[ \t\n]+" ""))]        ;; remove non visible
    (loop [sum 0
           mul 1
           i 0
           [c & srest] clean-s]
      (if (nil? c)
        (mod sum M)
        (let [mul' (if (= 0 (mod i 4)) 1 (* mul 256))
              sum' (+ sum (* (int c) mul'))]
          (recur sum' mul' (inc i) srest))))))
```

**Key characteristics:**
- Returns a Long (not int)
- Normalizes whitespace, removes metadata, comments, and tags before hashing
- Uses a custom rolling hash with M=4294967291
- Deterministic: same form source always produces same hash

### Mutation Site to Coverage Lookup Bridge

To find which tests cover a mutation site, heretic must bridge from rewrite-clj
locations to ClojureStorm's coverage index:

```
Mutation Discovery → Coverage Lookup Flow:

1. Parse source file with rewrite-clj
2. Find mutation sites (zloc positions for +, -, and, etc.)
3. For each mutation site:
   a. Navigate up to find the top-level form
   b. Get the source string of that form (z/string)
   c. Hash it using clojure-form-source-hash → this is the form-id
   d. Compute the coordinate within the form using zloc->coord
   e. Look up [form-id coord] in coverage index → get relevant tests
```

**Example:**
```clojure
;; Source file contains:
(defn add [a b] (+ a b))

;; Mutation site: the + symbol at position [3 0]
;; 1. Get top-level form source: "(defn add [a b] (+ a b))"
;; 2. Hash: (clojure-form-source-hash "(defn add [a b] (+ a b))") → 12345678
;; 3. Coord: "3,0" (from zloc->coord)
;; 4. Lookup: (get-in index [:coord-to-tests [12345678 "3,0"]]) → #{my.app-test/test-add}
```

### Components

#### 1. Source Parser

Parse Clojure source to identify mutation sites.

```clojure
(ns heretic.parser
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn parse-file [path]
  "Parse a Clojure file into a zipper for traversal"
  (z/of-file path))

(defn find-mutation-sites [zloc]
  "Find all locations in the AST where mutations can be applied.
   Returns seq of {:zloc, :form-id, :coord, :operator, :mutations}"
  ;; Walk the tree, identify operators that can be mutated
  ;; Skip quoted forms
  )

(defn apply-mutation [zloc mutation]
  "Apply a mutation at a location, return modified source"
  )
```

#### 2. Coordinate Mapper

Bidirectional mapping between ClojureStorm coordinates and rewrite-clj zippers.

```clojure
(ns heretic.coord-mapper
  (:require [rewrite-clj.zip :as z]
            [clojure.string :as str]))

(defn- nth-child
  "Navigate to the nth child of a zipper location"
  [zloc n]
  (nth (iterate z/right (z/down zloc)) n))

(defn- find-by-hash
  "Find element in unordered collection by its hash.
   Hash format: K<hash> for keys/set-elements, V<hash> for values (no dash)"
  [zloc hash-str]
  (let [[_ prefix hash-val] (re-matches #"([KV])(\d+)" hash-str)
        target-hash (parse-long hash-val)]
    ;; Walk children, compute hash of each using clojure-form-source-hash
    ;; For maps: K matches keys, V matches values
    ;; For sets: K matches elements (always K prefix)
    ;;
    ;; Hash computation: use hansel.utils/obj-coord algorithm:
    ;;   (str kind (clojure-form-source-hash (pr-str obj)))
    ;; where kind is "K" for keys/elements, "V" for values
    ))

(defn coord->zloc
  "Navigate a zipper using ClojureStorm coordinates.
   Coordinates are strings: \"3,2,1\" or \"\" for root.
   Hash-based parts use format K<hash> or V<hash> (no dash)."
  [zloc coord]
  (if (= "" coord)
    zloc  ;; Empty string = root/fn-return coordinate
    (let [parts (map #(if (re-matches #"\d+" %)
                        (parse-long %)
                        %)
                     (str/split coord #","))]
      (reduce
        (fn [z part]
          (if (string? part)
            (find-by-hash z part)
            (nth-child z part)))
        zloc
        parts))))

(defn zloc->coord
  "Get ClojureStorm coordinate for a zipper position.
   Returns string like \"3,2,1\" or \"\" for root.

   IMPORTANT: rewrite-clj creates an implicit :forms container node when parsing
   with z/of-string or z/of-file. This container is NOT part of the ClojureStorm
   coordinate system. zloc->coord must stop at the root form level (the direct
   child of the :forms container), not include the container index.

   Example:
   (z/of-string \"(+ 1 2)\")  ; Creates :forms -> :list
   The :list is the root form. Coords start from its children:
   - [0] -> +
   - [1] -> 1
   - [2] -> 2

   The root form itself returns nil or empty coord (\"\" for ClojureStorm)."
  [zloc]
  ;; Walk up to root, collecting indices/hashes
  ;; STOP when parent is :forms (the implicit container)
  ;; Join with comma, or return empty string for root form
  )
```

#### 3. Namespace Reloader (clj-reload)

Reload namespaces after mutations using clj-reload for correct handling of
protocols, multimethods, and dependency ordering.

```clojure
(ns heretic.reloader
  (:require [clj-reload.core :as reload]))

(defn init! [source-paths]
  "Initialize clj-reload with source directories"
  (reload/init {:dirs source-paths
                :output :quiet}))

(defn reload-after-mutation!
  "Reload changed namespaces after a mutation is applied.
   clj-reload automatically:
   - Detects which files changed
   - Computes transitive dependents
   - Unloads in reverse dependency order
   - Reloads in correct dependency order
   - Handles protocols/multimethods correctly"
  []
  (reload/reload {:throw false}))

(defn unload-for-revert!
  "After reverting a mutation, reload to restore original code"
  []
  (reload/reload {:throw false}))
```

**Why clj-reload?**

clj-reload solves critical namespace reloading problems:

1. **Proper unloading**: Calls `remove-ns` before reloading, preventing
   protocol/multimethod accumulation

2. **Dependency ordering**: Topologically sorts namespaces, unloading dependents
   first and reloading dependencies first

3. **Lifecycle hooks**: Supports `before-ns-unload` and `after-ns-reload` hooks
   (configured globally via `:unload-hook` and `:reload-hook` in init)

4. **Zero dependencies**: Small, focused library with no runtime dependencies

5. **Battle-tested**: Used by Tonsky in production projects

#### 4. Mutation Engine

Apply mutations and track results.

```clojure
(ns heretic.mutation-engine
  (:require [heretic.parser :as parser]
            [heretic.coord-mapper :as mapper]
            [heretic.coverage-map :as coverage]
            [heretic.reloader :as reloader]))

(defrecord Mutation [id file form-id coord operator original replacement])

(defrecord MutationResult [mutation status tests-run duration])
;; status: :killed, :survived, :no-coverage, :timeout, :error

(defn generate-mutations [source-paths]
  "Generate all possible mutations for the given source files"
  (for [path source-paths
        site (parser/find-mutation-sites (parser/parse-file path))
        mutation (:mutations site)]
    (map->Mutation {...})))

(defn apply-mutation! [mutation]
  "Apply mutation to source file"
  ;; 1. Read file
  ;; 2. Parse with rewrite-clj
  ;; 3. Navigate to mutation location using coord-mapper
  ;; 4. Replace node
  ;; 5. Write file back (atomic)
  )

(defn revert-mutation! [mutation]
  "Revert mutation, restore original source"
  ;; 1. Write original content back to file (atomic)
  )
```

#### 5. Targeted Test Runner

Run only tests relevant to a mutation.

```clojure
(ns heretic.targeted-runner
  (:require [heretic.coverage-map :as coverage]
            [heretic.reloader :as reloader]
            [clojure.test :as t]))

(defn tests-for-mutation [coverage-map mutation]
  "Get tests that need to run for this mutation"
  (coverage/tests-for-location
   coverage-map
   (:form-id mutation)
   (:coord mutation)))

(defn run-tests [test-syms {:keys [timeout]}]
  "Run specific tests by symbol, return results"
  ;; Resolve test vars from symbols
  ;; Run with timeout
  ;; Return {:passed, :failed, :errors, :duration}
  )

(defn evaluate-mutation [coverage-map mutation]
  "Apply mutation, reload, run relevant tests, determine if killed"
  (let [tests (tests-for-mutation coverage-map mutation)]
    (if (empty? tests)
      (->MutationResult mutation :no-coverage [] 0)
      (try
        ;; Apply mutation to source file
        (apply-mutation! mutation)

        ;; Reload affected namespaces (clj-reload handles dependencies)
        (reloader/reload-after-mutation!)

        ;; Run targeted tests
        (let [result (run-tests tests {:timeout 5000})]
          (if (or (pos? (:failed result))
                  (pos? (:errors result)))
            (->MutationResult mutation :killed tests (:duration result))
            (->MutationResult mutation :survived tests (:duration result))))

        (finally
          ;; Revert mutation and reload original code
          (revert-mutation! mutation)
          (reloader/unload-for-revert!))))))
```

#### 6. Reporter

Generate mutation testing reports.

```clojure
(ns heretic.reporter)

(defn mutation-score [results]
  "Calculate mutation score: killed / (killed + survived)"
  (let [killed (count (filter #(= :killed (:status %)) results))
        survived (count (filter #(= :survived (:status %)) results))
        total (+ killed survived)]
    (if (zero? total)
      1.0
      (/ killed total))))

(defn generate-report [results {:keys [format output-path]}]
  "Generate mutation testing report"
  ;; Formats: :terminal, :html, :json, :edn
  )

(defn print-summary [results]
  (let [by-status (group-by :status results)]
    (println "Mutation Testing Results")
    (println "========================")
    (println (format "Killed:      %d" (count (:killed by-status))))
    (println (format "Survived:    %d" (count (:survived by-status))))
    (println (format "No coverage: %d" (count (:no-coverage by-status))))
    (println (format "Errors:      %d" (count (:error by-status))))
    (println (format "Score:       %.1f%%" (* 100 (mutation-score results))))))
```

### Mutation Flow

```
1. User runs: bb heretic:mutate

2. Heretic:
   a. Initialize clj-reload with source paths
   b. Load coverage map (or collect if stale/missing)
   c. Generate all mutations for source files
   d. For each mutation:
      i.   Look up tests via coverage map
      ii.  If no tests → mark :no-coverage, skip
      iii. Apply mutation to source file
      iv.  Call clj-reload/reload to reload affected namespaces
      v.   Run targeted tests with timeout
      vi.  If any test fails → :killed
      vii. If all tests pass → :survived (weak tests!)
      viii. Revert mutation file
      ix.  Call clj-reload/reload to restore original code
   e. Generate report

3. Output: mutation score + list of surviving mutations
```

### Testing Requirements

**All heretic code requires ClojureStorm on the classpath** - it is a core dependency,
not optional. However, instrumentation should be disabled for unit tests and enabled
only for integration tests.

**Unit tests** (testing heretic internals):
```
-Dclojure.storm.instrumentEnable=false
```

This ensures ClojureStorm classes are available but no instrumentation occurs.
Unit tests for coord-mapper, persistence, etc. run normally.

**Integration tests** (testing coverage collection):
```
-Dclojure.storm.instrumentEnable=true
-Dclojure.storm.instrumentAutoPrefixes=false
-Dclojure.storm.instrumentOnlyPrefixes=<target-prefix>
```

Integration tests that actually collect coverage need instrumentation enabled,
but MUST exclude heretic itself (see Self-Instrumentation Prevention above).

**Example deps.edn aliases:**
```clojure
:test {:jvm-opts ["-Dclojure.storm.instrumentEnable=false"]}

:test-integration
{:jvm-opts ["-Dclojure.storm.instrumentEnable=true"
            "-Dclojure.storm.instrumentAutoPrefixes=false"
            "-Dclojure.storm.instrumentOnlyPrefixes=sample"]}
```

---

## Configuration

```clojure
;; heretic.edn or in deps.edn under :heretic alias
{:source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces [my.app.core-test my.app.util-test]  ;; or :all
 :heretic-dir ".heretic"  ;; Directory for coverage data

 ;; Namespace filtering (passed to ClojureStorm)
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev"]

 ;; Collection settings
 :parallel-collect false  ;; Phase 3: collect test namespaces in parallel

 ;; Mutation settings
 :mutation-operators [:arithmetic :comparison :boolean :return-values]
 :skip-forms #{comment}
 :timeout-ms 5000
 :parallel-mutate false  ;; Phase 3: run mutations in parallel

 ;; Reporting
 :report-format :html
 :output-path "target/heretic-report"}
```

---

## CLI Interface

```bash
# Collect coverage (incremental - only stale test namespaces)
bb heretic:collect

# Force full recollection
bb heretic:collect --force

# Collect specific test namespaces only
bb heretic:collect --namespaces my.app.core-test,my.app.util-test

# Show collection status (which namespaces are stale)
bb heretic:status

# Run mutation testing
bb heretic:mutate

# Run mutation testing on specific files
bb heretic:mutate --files src/my_app/core.clj

# Run mutation testing with specific operators only
bb heretic:mutate --operators arithmetic,comparison

# Show surviving mutations from last run
bb heretic:survivors

# Clean heretic data (remove .heretic directory)
bb heretic:clean
```

---

## Dependencies

```clojure
{:deps
 {;; ClojureStorm - patched Clojure compiler for instrumentation
  com.github.flow-storm/clojure {:mvn/version "1.12.0-1"}

  ;; Flow Storm debugger - provides FormRegistry API
  com.github.flow-storm/flow-storm-dbg {:mvn/version "X.Y.Z"}

  ;; Source code manipulation
  rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}

  ;; Namespace reloading with proper protocol/multimethod handling
  io.github.tonsky/clj-reload {:mvn/version "0.7.1"}}}
```

JVM args for ClojureStorm:
```
-Dclojure.storm.instrumentEnable=true
-Dclojure.storm.instrumentOnlyPrefixes=my-app
-Dclojure.storm.instrumentSkipPrefixes=my-app.test
```

---

## Technical Details

### ClojureStorm Coordinate System

ClojureStorm uses positional paths into the AST. Coordinates are passed to tracer
callbacks as **strings** (e.g., `"3,2,1"`), not vectors. The root/fn-return
coordinate is an empty string `""`.

```clojure
;; For the form:
(defn foo [a b] (+ a b))
;;  0    1   2     3

;; Coordinate examples:
;; ""      → function return (root)
;; "3"     → (+ a b)
;; "3,0"   → +
;; "3,1"   → a
;; "3,2"   → b
```

For **maps and sets** (unordered), coordinates use hash-based identifiers with
format `K<hash>` or `V<hash>` (no dash between letter and hash):
- Map keys: `"K<hash>"` where hash is computed from the form
- Map values: `"V<hash>"`
- Set elements: `"K<hash>"`

```clojure
;; For the form:
{:a 1 :b 2}

;; Coordinates might be:
;; "K12345"  → :a (key)
;; "V12345"  → 1 (value for :a)
;; "K67890"  → :b (key)
;; "V67890"  → 2 (value for :b)

;; Nested example:
;; "4,1,1,V3919306159"      → navigate to index 4, 1, 1, then value with hash
;; "4,1,1,V1836413754,2"    → same pattern, then continue to child index 2
```

The hash is computed by `hansel.utils/clojure-form-source-hash` which normalizes
whitespace and removes comments before hashing.

**Important limitations:**
- **Records are NOT recursed** - they're treated as leaf forms
- **Metadata cannot always be attached** - some objects (primitives, Java objects)
  silently skip coordinate tagging
- **Lazy sequences are forced** - coordinates are assigned eagerly via `doall`

The coordinate scheme is implemented in `hansel.utils/walk-code-form`.

### Mapping rewrite-clj to ClojureStorm Coordinates

rewrite-clj uses zipper navigation. The `heretic.coord-mapper` namespace provides
bidirectional conversion:

```clojure
;; ClojureStorm coord → rewrite-clj zipper position
(coord->zloc root-zloc "3,2,1")  ;; Navigate to position
(coord->zloc root-zloc "")       ;; Empty string = root

;; rewrite-clj zipper position → ClojureStorm coord
(zloc->coord some-zloc)  ;; Returns "3,2,1" or "" for root
```

**Validation requirement**: The coordinate mapper MUST be tested with round-trip
validation before Phase 1 is complete:
```clojure
(= (zloc->coord (coord->zloc zloc coord)) coord)  ;; Must be true
```

### clj-reload Integration

clj-reload provides the reloading infrastructure:

1. **Initialization**: `(reload/init {:dirs source-paths :output :quiet})`

2. **After mutation**: `(reload/reload)` detects file changes and reloads
   affected namespaces in correct order

3. **Unload cycle**: For each affected namespace:
   - Calls `before-ns-unload` hook if defined (global config, not per-namespace)
   - Calls `remove-ns` to fully remove the namespace
   - Removes from `*loaded-libs*`

4. **Load cycle**: For each namespace to reload:
   - Loads the file
   - Calls `after-ns-reload` hook if defined

5. **Dependency handling**: Automatically computes transitive closure of
   dependent namespaces and processes them in topological order

---

## Open Questions

### 1. Coordinate Mapping Edge Cases (Medium Risk)

The core coordinate scheme (integer indices for sequential forms) maps cleanly
between ClojureStorm and rewrite-clj. Known edge cases:

- **Hash collisions**: Unlikely but possible for map/set coordinates
- **Records**: Not recursed into - mutations inside records won't have deep coords
- **Reader macros**: `#(...)`, `@`, `'` expand before instrumentation

**Action**: Build and validate the coordinate mapper with extensive test cases
including maps, sets, and nested structures.

### 2. Parallel Execution

Options for parallel mutation testing:

- **File-level locking**: Parallel across files, sequential within files
- **Copy-on-write source trees**: Full parallelism with temp directories
- **Worker pool**: Pre-fork JVMs that receive mutations over sockets

**Recommendation**: Start sequential, add file-level parallelism in Phase 3.

### 3. ClojureScript Support

ClojureScript requires:
- ClojureScriptStorm (shadow-cljs integration)
- Form registry stored client-side
- Coverage data sent via HTTP POST
- shadow-cljs rebuild after mutations

**Recommendation**: CLJ-only for MVP. Add Node.js CLJS support in Phase 4.

---

## Implementation Phases

### Phase 1: Coverage Collection (MVP)

**Priority 1: Validation (do first)**
- [ ] Verify FormRegistry output format matches spec assumptions
- [ ] Build and test coordinate mapper with round-trip validation
- [ ] Integration test with real clojure.test suite

**Priority 2: Core Implementation**
- [ ] Set up ClojureStorm integration (tracer callbacks)
- [ ] Implement per-test coverage collection
- [ ] Split storage model:
  - [ ] Per-test-namespace coverage files
  - [ ] Source dependency tracking per namespace
  - [ ] Per-namespace staleness detection
  - [ ] Index rebuild from coverage files
- [ ] Atomic file persistence
- [ ] Basic CLI: `heretic:collect`, `heretic:status`, `heretic:clean`

**Acceptance criteria**:
- Running `heretic:collect` on a sample project produces coverage in `.heretic/`
- Coverage correctly identifies which tests hit which code
- Running `heretic:collect` again only recollects changed test namespaces
- Round-trip: `coord->zloc` and `zloc->coord` are inverses

### Phase 2: Basic Mutation Testing
- [ ] Source parsing with rewrite-clj
- [ ] Implement 2-3 mutation operators (arithmetic, boolean)
- [ ] Form-id to source mapping via coordinate mapper
- [ ] clj-reload integration for namespace reloading
- [ ] Targeted test execution
- [ ] Basic terminal report
- [ ] CLI: `heretic:mutate`

### Phase 3: Full Mutation Suite + Performance (~88% Complete)

**Implementation Insights from Phase 2:**

The form-location-index pattern proved critical: ClojureStorm form-ids (compiler-assigned)
don't match rewrite-clj form hashes. We bridge this gap by building an index of
`{[file, line] -> form-id}` during coverage collection, allowing mutation sites
(discovered via rewrite-clj) to look up their corresponding ClojureStorm form-ids
for test targeting.

#### 3.1 Complete Clojure-Specific Operators ✅ COMPLETE

65+ operators implemented in `operators.clj`:

**Collection Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `first` | `last`, `rest` | Sequence endpoints |
| `last` | `first`, `butlast` | Sequence endpoints |
| `rest` | `next` | Nil vs empty-seq semantics |
| `next` | `rest` | Empty-seq vs nil semantics |
| `take` | `drop` | Collection slicing |
| `drop` | `take` | Collection slicing |
| `conj` | `disj` | Set operations only |
| `inc` | `dec` | Numeric increment |
| `dec` | `inc` | Numeric decrement |

**Nil-Handling Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `nil?` | `some?` | Nil check inversion |
| `some?` | `nil?` | Existence check inversion |
| `seq` | `empty?` | Truthy collection check |
| `empty?` | `seq` | Empty check inversion |

**Threading Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `->` | `->>` | Thread-first ↔ thread-last |
| `->>` | `->` | Thread-last ↔ thread-first |
| `->` | `some->` | Add nil short-circuiting |
| `->>` | `some->>` | Add nil short-circuiting |
| `some->` | `->` | Remove nil safety |
| `some->>` | `->>` | Remove nil safety |

**Lazy/Eager Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `map` | `mapv` | Lazy → eager |
| `mapv` | `map` | Eager → lazy |
| `filter` | `filterv` | Lazy → eager |
| `for` | `doseq` | Lazy seq → side-effect iteration |

**Higher-Order Function Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `filter` | `remove` | Predicate inversion |
| `remove` | `filter` | Predicate inversion |
| `keep` | `filter` | Nil-filtering semantics |

**Destructuring Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `{:keys [user-id]}` | `{:keys [userId]}` | kebab ↔ camel |
| `:user/id` | `:users/id` | Namespace typo |
| `:user/id` | `:user-id` | Qualified → unqualified |

**Return Value Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| Return value | `nil` | Nil return |
| Non-empty coll | `[]`, `{}`, `#{}` | Empty collection |
| Non-zero number | `0` | Zero return |
| Non-empty string | `""` | Empty string |

**Constant Replacement Operators:**
| Original | Mutation | Notes |
|----------|----------|-------|
| `c` (integer) | `0` | Zero replacement |
| `c` (integer) | `1` | One replacement |
| `c` (integer) | `-1` | Negative one |
| `c` (integer) | `c + 1` | Off-by-one (up) |
| `c` (integer) | `c - 1` | Off-by-one (down) |

#### 3.2 Equivalent Mutant Detection ✅ COMPLETE

Implemented in `equivalent.clj` with 7 pattern categories:

- [x] **Boundary comparisons**: `(>= (count x) 0)` always true, `(neg? (count x))` always false
- [x] **Multiply-by-zero**: `(* x 0)` → `0` is equivalent
- [x] **Function contracts**: `(nil? (str x))` always false (str never returns nil)
- [x] **Lazy/eager equivalences**: `(vec (map f xs))` ≡ `(vec (mapv f xs))` in realizing context
- [x] **Collection literals**: `(empty? [])` always true, `(first [x])` → `x`
- [x] **Threading macros**: `(-> x f)` ≡ `(f x)` for single-arity functions
- [x] **Nil/some swap**: `(not (nil? x))` ≡ `(not (not (some? x)))` detection
- [ ] **ML-based detection** (Phase 4): Train classifier on labeled mutants

#### 3.3 Subsumption Analysis ✅ COMPLETE

Implemented in `subsumption.clj` with state-of-the-art techniques:

- [x] **RORG operator-level subsumption tables**: Minimal operator sets for relational/arithmetic/boolean
- [x] **Kill matrix tracking**: Build complete {mutant → {killing-tests}} matrix
- [x] **Dominator mutant selection**: Find mutants with minimal kill sets (hardest to kill)
- [x] **Enhanced incremental analysis**: `can-skip-mutation?` infers results from previous runs
- [x] **Reduction statistics**: Report dominated/dominator counts and savings

Research shows subsumption can reduce mutation testing time by 30-50%.

#### 3.4 Parallel Execution Architecture ⚠️ PARTIAL

**Current Implementation:**
- [x] Uses `future` for test timeout handling in `runner.clj`
- [ ] File-level parallelism (mutate files concurrently) - NOT YET
- [ ] Process-level worker pool (Phase 4)

**Planned Controller + Worker Model:**

```
┌─────────────────────────────────────────────────┐
│                  Controller                      │
│  - Generates mutant queue                       │
│  - Dispatches to workers                        │
│  - Collects results                             │
│  - Handles timeouts                             │
└──────────────────┬──────────────────────────────┘
                   │
       ┌───────────┼───────────┐
       ▼           ▼           ▼
  ┌─────────┐ ┌─────────┐ ┌─────────┐
  │ Worker1 │ │ Worker2 │ │ Worker3 │
  │ (JVM)   │ │ (JVM)   │ │ (JVM)   │
  └─────────┘ └─────────┘ └─────────┘
```

**TODO - File-Level Parallelism:**
- [ ] Parallel across source files (mutex within file)
- [ ] Each file mutation is sequential
- [ ] Simple locking, no JVM coordination needed

**Process-Level Parallelism (Phase 4):**
- [ ] Pre-fork worker JVMs
- [ ] Socket-based task distribution
- [ ] Mutant schemata via dynamic vars (no file modification)

#### 3.5 Performance Optimizations ✅ MOSTLY COMPLETE

- [x] **Early termination**: Stop testing mutant on first failure (in `runner.clj`)
- [x] **Selective mutation**: Three presets in `operators.clj`:
  - `:fast` - 15 high-impact operators (~99% bug detection)
  - `:standard` - Balanced set with collection/nil/threading
  - `:comprehensive` - All 65+ operators
- [x] **Incremental mutation**: Form-level hashing in `incremental.clj`
- [x] **Test ordering**: Fastest-first ordering in `timing.clj`, proven-killers prioritized
- [ ] **Clustering**: Group similar mutants, test one representative - NOT YET

#### 3.6 HTML Reports ✅ COMPLETE

Rich visual reports implemented in `reporter.clj`:

- [x] Mutation score dashboard with summary stats
- [x] Source file heatmap (visual bar showing killed/survived ratio)
- [x] Survivor details with code snippets and operator info
- [x] Test effectiveness ranking (which tests kill most mutants)
- [x] Trend charts (historical score visualization)
- [x] Export to JSON/EDN for external tools

#### 3.7 Watch Mode ✅ COMPLETE

Continuous mutation testing implemented in `watch.clj` using hawk:

- [x] Watch source and test files for changes
- [x] Incremental re-collect coverage on test changes
- [x] Re-mutate only affected functions on source changes
- [x] Debounced event handling to avoid duplicate runs
- [ ] Live terminal dashboard with running results (basic output exists)
- [ ] Integration with editor notifications (future enhancement)

#### 3.8 Timeout Handling ✅ COMPLETE

Robust timeout handling in `runner.clj` and `timing.clj`:

- [x] Per-mutant timeout with futures (configurable, default 5s)
- [x] Timeout status tracking (`:timeout` in results)
- [x] Dynamic timeout multiplier based on historical test timing
- [x] Future cancellation for runaway tests
- [x] Graceful cleanup on timeout (revert mutation, restore state)

**Note**: The split storage model (Phase 1) already supports incremental updates.
Phase 3 adds parallelism and watch mode to leverage this.

**Acceptance Criteria:**
- [x] All Clojure-specific operators implemented and tested (65+ operators)
- [x] Equivalent mutant detection reduces false survivors (7 pattern categories)
- [ ] File-level parallelism achieves linear speedup (NOT YET IMPLEMENTED)
- [x] HTML report provides actionable insights (heatmaps, trends, survivors)
- [x] Watch mode enables feedback on incremental changes

**Remaining for 100% Phase 3:**
1. File-level parallelism (mutate files concurrently)
2. Mutant clustering (group similar, test representative)

### Phase 4: AI Integration + Polish

#### 4.1 AI-Powered Mutation Generation

Based on research showing traditional operators miss ~49% of real-world bugs,
add LLM-powered semantic mutations:

**AI Mutator Protocol:**
```clojure
(defprotocol AIMutator
  (generate-mutations [this context]
    "Generate semantic mutations using AI.
     context: {:source-code, :function-name, :docstring, :schemas}"))
```

**AI Operators:**
| ID | Name | Description |
|----|------|-------------|
| `ai-logic-invert` | Business logic inversion | Flip conditional meaning |
| `ai-edge-case` | Edge case removal | Remove boundary handling |
| `ai-semantic` | Semantic mutation | Wrong-but-plausible code |
| `ai-nilsafe` | Nil safety removal | Remove nil guards |

**Integration with Malli/Spec:**
- Extract schemas for function inputs/outputs
- Guide mutations to produce type-valid alternatives
- Avoid obviously invalid mutations (wrong arity, type mismatches)

**Cost/Quality Tradeoffs:**
- [ ] Tiered approach: deterministic first, AI for survivors
- [ ] Caching: reuse AI mutations for similar code patterns

#### 4.7 LLM-based Test Generation (from Meta ACH)

Based on Meta's ACH paper (2025) showing 73% acceptance rate for LLM-generated tests:

- [ ] For each surviving mutant, generate a test that would kill it
- [ ] Use function context (docstring, schemas, examples) as prompt
- [ ] Present generated tests for human review before commit
- [ ] Track acceptance rate for feedback loop
- [ ] Integration with PR workflow (suggest tests in comments)

**Prompt Strategy:**
```
Given this Clojure function and a mutation that survived testing:
- Function: {source-code}
- Docstring: {docstring}
- Schema: {malli-schema}
- Mutation: Changed {original} to {mutated} at line {line}

Generate a test that would detect this mutation.
```

#### 4.8 Hybrid Equivalent Detection (from Meta ACH)

Combine static patterns with LLM for high-precision equivalent detection:

- [ ] Use static patterns first (cheap, fast) - already in `equivalent.clj`
- [ ] Fall back to LLM for uncertain/complex cases
- [ ] Preprocessing: normalize code before LLM analysis
- [ ] Target: 0.95+ precision to avoid false positives (ACH achieved this)
- [ ] Cache LLM decisions for similar patterns

**Two-Stage Pipeline:**
```
Mutation → Static Pattern Check → [equivalent? skip]
                ↓ (uncertain)
           LLM Analysis → [equivalent? skip]
                ↓ (not equivalent)
           Run Tests
```
- [ ] Batch prompting: group multiple mutation sites per API call
- [ ] Local models: support Ollama for cost-sensitive environments

#### 4.2 ClojureScript Support

**Node.js First Approach:**
- ClojureScriptStorm integration (shadow-cljs)
- Coverage via Node.js instrumentation
- Form registry stored server-side
- shadow-cljs rebuild after mutations

**Browser Support (later):**
- Coverage sent via HTTP POST from browser
- Headless Chrome for test execution
- Hot reload integration

#### 4.3 IDE Integration

- [ ] LSP server for inline mutation indicators
- [ ] VS Code extension with survivor highlighting
- [ ] Emacs/CIDER integration via nREPL
- [ ] IntelliJ/Cursive plugin

#### 4.4 CI/CD Integration

- [ ] GitHub Actions workflow templates
- [ ] GitLab CI configuration
- [ ] Jenkins plugin
- [ ] PR comment with mutation score delta
- [ ] Quality gate: fail if score drops below threshold

#### 4.5 Advanced Parallelism

**Process Pool Architecture:**
- [ ] Pre-fork worker JVMs at startup
- [ ] Socket-based mutant distribution
- [ ] Mutant schemata via dynamic vars:
  ```clojure
  (defonce ^:dynamic *mutant-id* nil)

  (defn original-fn [x]
    (case *mutant-id*
      :m1 (- x 1)  ; mutation 1
      :m2 (* x 2)  ; mutation 2
      (+ x 1)))    ; original
  ```
- [ ] Zero-copy mutation (no file modification)
- [ ] Worker health monitoring and restart

**Performance Targets:**
- 10x speedup over sequential on 8-core machine
- Sub-minute mutation testing for typical projects (< 1000 LOC)
- Linear scaling with additional cores

#### 4.6 ML-Based Equivalent Detection

Train classifier to identify equivalent mutants:
- [ ] Labeled dataset from heretic runs
- [ ] Features: AST diff, operator type, context
- [ ] Model: lightweight decision tree or small NN
- [ ] Integration: filter before testing phase

**Acceptance Criteria:**
- AI mutations catch bugs missed by deterministic operators
- ClojureScript support works with shadow-cljs projects
- IDE integration provides inline feedback
- CI integration enables mutation testing in PRs
- Worker pool achieves 10x speedup on multi-core
