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

### Data Structures

```clojure
;; Primary output: test → form → coords
{:test-coverage
 {my.app-test/test-addition {12345 #{"3" "3,1" "3,2"}
                              12346 #{"1" "2,1"}}
  my.app-test/test-subtraction {12345 #{"3" "4"}
                                 12347 #{"1"}}}}

;; Inverse index (derived): form+coord → tests
{:coord-to-tests
 {[12345 "3"]     #{my.app-test/test-addition my.app-test/test-subtraction}
  [12345 "3,2"]   #{my.app-test/test-addition}
  [12345 "3,2,1"] #{my.app-test/test-addition}
  [12345 "4"]     #{my.app-test/test-subtraction}
  ...}}

;; Form registry (from ClojureStorm FormRegistry/getAllForms)
;; Returns: {form-id -> form-metadata}
{:forms
 {12345 {:form/ns "my.app.core"
         :form/form (defn foo [x] (+ x 1))
         :form/emitted-coords #{"3" "3,1" "3,2"}}
  12346 {:form/ns "my.app.core"
         :form/form (defn bar [a b] (* a b))
         :form/emitted-coords #{"3" "3,1" "3,2"}}
  ...}}

;; Metadata for staleness checking
{:metadata
 {:collected-at 1703789012345
  :source-hash "abc123..."      ;; Hash of all source files
  :test-hash "def456..."        ;; Hash of all test files
  :config-hash "ghi789..."}}    ;; Hash of heretic config
```

### Components

#### 1. Coverage Tracer

Receives callbacks from ClojureStorm and records coverage. ClojureStorm callbacks
receive coordinates as **vectors** (e.g., `[3 2 1]`), which are then stringified
for storage (e.g., `"3,2,1"`).

```clojure
(ns heretic.tracer
  (:require [clojure.string :as str])
  (:import [clojure.storm Emitter Tracer FormRegistry]))

;; Current coverage accumulator (reset between tests)
(def ^:private current-coverage
  "Atom of {form-id #{coords}} for the currently running test"
  (atom {}))

(defn- stringify-coord
  "Convert coordinate vector to string.
   ClojureStorm passes coords as vectors: [3 2 1] -> \"3,2,1\"
   Hash-based coords for maps/sets arrive as strings: \"K-12345\""
  [coord]
  (if (string? coord)
    coord
    (str/join "," coord)))

(defn- record-hit!
  "Record a coverage hit for the current test.
   Called by ClojureStorm for each expression evaluation."
  [form-id coord]
  (swap! current-coverage
         update form-id
         (fnil conj #{})
         (stringify-coord coord)))

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

Process raw coverage into queryable indexes.

```clojure
(ns heretic.coverage-map
  (:require [heretic.collector :as collector])
  (:import [clojure.storm FormRegistry]))

(defn get-form-registry
  "Get all forms from ClojureStorm's FormRegistry.
   Returns {form-id -> {:form/ns, :form/form, :form/emitted-coords}}"
  []
  (into {} (FormRegistry/getAllForms)))

(defn build-inverse-index
  "Build form+coord -> tests index from test-coverage"
  [test-coverage]
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
    {}
    test-coverage))

(defn build-coverage-map
  "Build complete coverage map with all indexes"
  [test-namespaces source-paths test-paths config]
  (let [{:keys [test-coverage]} (collector/collect-all-coverage test-namespaces)
        forms (get-form-registry)
        coord-to-tests (build-inverse-index test-coverage)]
    {:test-coverage test-coverage
     :coord-to-tests coord-to-tests
     :forms forms
     :metadata {:collected-at (System/currentTimeMillis)
                :source-hash (hash-files source-paths)
                :test-hash (hash-files test-paths)
                :config-hash (hash config)}}))

(defn tests-for-location
  "Given a form-id and optional coord, return tests that hit it"
  ([coverage-map form-id]
   (into #{}
         (for [[test-id forms] (:test-coverage coverage-map)
               :when (contains? forms form-id)]
           test-id)))

  ([coverage-map form-id coord]
   (get-in coverage-map [:coord-to-tests [form-id coord]] #{})))
```

#### 4. Persistence

Save/load coverage maps with atomic writes to prevent corruption.

```clojure
(ns heretic.persistence
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]))

(def default-path ".heretic/coverage-map.edn")

(defn- atomic-spit!
  "Write content atomically using temp file + rename.
   Prevents corruption if process crashes mid-write."
  [path content]
  (let [file (io/file path)
        parent (.getParentFile file)
        temp-file (File/createTempFile "heretic" ".tmp" parent)]
    (try
      (spit temp-file content)
      (Files/move (.toPath temp-file)
                  (.toPath file)
                  (into-array [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE]))
      (catch Exception e
        (.delete temp-file)
        (throw e)))))

(defn save-coverage-map!
  "Save coverage map atomically"
  [coverage-map path]
  (io/make-parents path)
  (atomic-spit! path (pr-str coverage-map)))

(defn load-coverage-map
  "Load coverage map from disk"
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn- hash-files
  "Compute hash of file contents for staleness detection"
  [paths]
  (let [contents (mapcat #(file-seq (io/file %)) paths)
        file-hashes (for [f contents
                          :when (.isFile f)]
                      (hash (slurp f)))]
    (hash (vec file-hashes))))

(defn coverage-map-stale?
  "Check if coverage map needs regeneration.
   Stale if source files, test files, OR config changed."
  [coverage-map source-paths test-paths config]
  (let [{:keys [source-hash test-hash config-hash]} (:metadata coverage-map)]
    (or (not= source-hash (hash-files source-paths))
        (not= test-hash (hash-files test-paths))
        (not= config-hash (hash config)))))
```

### Collection Flow

```
1. User runs: bb heretic:collect (or automatic on first mutation run)

2. Heretic:
   a. Calls heretic.tracer/init! to set up ClojureStorm callbacks
   b. Discovers all test vars in test namespaces
   c. For each test var:
      i.   Reset current coverage accumulator
      ii.  Run the single test
      iii. Capture coverage: {form-id -> #{coords}}
      iv.  Store under test identifier
   d. Build inverse index (coord -> tests)
   e. Persist atomically to .heretic/coverage-map.edn

3. Output: coverage map ready for mutation testing
```

**Key design decision**: Running tests one-by-one is slower than running all tests
at once, but it:
- Works with ALL test runners (not just clojure.test)
- Avoids thread-local binding issues with async tests
- Produces accurate per-test coverage
- Is only done once per test suite change

---

## Phase 2: Mutation Testing

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
   Hash format: K-<hash> for keys/set-elements, V-<hash> for values"
  [zloc hash-str]
  (let [[prefix hash-val] (str/split hash-str #"-" 2)
        target-hash (parse-long hash-val)]
    ;; Walk children, compute hash of each, find match
    ;; For maps: K- matches keys, V- matches values
    ;; For sets: K- matches elements
    ))

(defn coord->zloc
  "Navigate a zipper using ClojureStorm coordinates.
   coord can be a string \"3,2,1\" or vector [3 2 1]"
  [zloc coord]
  (let [parts (if (string? coord)
                (map #(if (re-matches #"\d+" %)
                        (parse-long %)
                        %)
                     (str/split coord #","))
                coord)]
    (reduce
      (fn [z part]
        (if (string? part)
          (find-by-hash z part)
          (nth-child z part)))
      zloc
      parts)))

(defn zloc->coord
  "Get ClojureStorm coordinate for a zipper position.
   Returns vector like [3 2 1]"
  [zloc]
  ;; Walk up to root, collecting indices/hashes
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

---

## Configuration

```clojure
;; heretic.edn or in deps.edn under :heretic alias
{:source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces [my.app.core-test my.app.util-test]  ;; or :all
 :coverage-map-path ".heretic/coverage-map.edn"

 ;; Namespace filtering (passed to ClojureStorm)
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev"]

 ;; Mutation settings
 :mutation-operators [:arithmetic :comparison :boolean :return-values]
 :skip-forms #{comment}
 :timeout-ms 5000
 :parallel false  ;; Phase 3 feature

 ;; Reporting
 :report-format :html
 :output-path "target/heretic-report"}
```

---

## CLI Interface

```bash
# Collect coverage map (explicit)
bb heretic:collect

# Force recollection even if not stale
bb heretic:collect --force

# Run mutation testing
bb heretic:mutate

# Run mutation testing on specific files
bb heretic:mutate --files src/my_app/core.clj

# Run mutation testing with specific operators only
bb heretic:mutate --operators arithmetic,comparison

# Show surviving mutations from last run
bb heretic:survivors

# Check if coverage map is stale
bb heretic:status
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

ClojureStorm uses positional paths into the AST. Coordinates arrive at tracer
callbacks as **vectors** (e.g., `[3 2 1]`) and are stringified for storage
(e.g., `"3,2,1"`).

```clojure
;; For the form:
(defn foo [a b] (+ a b))
;;  0    1   2     3

;; Coordinate examples:
;; [3]     → (+ a b)     → stringified as "3"
;; [3 0]   → +           → stringified as "3,0"
;; [3 1]   → a           → stringified as "3,1"
;; [3 2]   → b           → stringified as "3,2"
```

For **maps and sets** (unordered), coordinates use hash-based identifiers:
- Map keys: `"K<hash>"` where hash is computed from `pr-str` of the key
- Map values: `"V<hash>"`
- Set elements: `"K<hash>"`

```clojure
;; For the form:
{:a 1 :b 2}

;; Coordinates might be:
;; ["K12345"]  → :a (key)
;; ["V12345"]  → 1 (value for :a)
;; ["K67890"]  → :b (key)
;; ["V67890"]  → 2 (value for :b)
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
(coord->zloc root-zloc "3,2,1")  ;; Navigate to [3 2 1]

;; rewrite-clj zipper position → ClojureStorm coord
(zloc->coord some-zloc)  ;; Returns [3 2 1]
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
- [ ] Build coverage map with inverse index
- [ ] Atomic file persistence with staleness detection
- [ ] Basic CLI: `heretic:collect`

**Acceptance criteria**:
- Running `heretic:collect` on a sample project produces a coverage map
- Coverage map correctly identifies which tests hit which code
- Round-trip: `coord->zloc` and `zloc->coord` are inverses

### Phase 2: Basic Mutation Testing
- [ ] Source parsing with rewrite-clj
- [ ] Implement 2-3 mutation operators (arithmetic, boolean)
- [ ] Form-id to source mapping via coordinate mapper
- [ ] clj-reload integration for namespace reloading
- [ ] Targeted test execution
- [ ] Basic terminal report
- [ ] CLI: `heretic:mutate`

### Phase 3: Full Mutation Suite
- [ ] All mutation operators
- [ ] HTML reports
- [ ] File-level parallel execution
- [ ] Timeout handling
- [ ] Incremental coverage updates

### Phase 4: Polish
- [ ] ClojureScript support (Node.js first)
- [ ] IDE integration
- [ ] CI integration
- [ ] Performance optimization
- [ ] Worker pool for full parallelism
