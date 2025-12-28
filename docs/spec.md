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
 {test-id-1 {form-id-1 #{"3" "3,2" "3,2,1"}
             form-id-2 #{"1" "2,1"}}
  test-id-2 {form-id-1 #{"3" "4"}
             form-id-3 #{"1"}}}}

;; Inverse index (derived): form+coord → tests
{:coord-to-tests
 {[form-id-1 "3"]     #{test-id-1 test-id-2}
  [form-id-1 "3,2"]   #{test-id-1}
  [form-id-1 "3,2,1"] #{test-id-1}
  [form-id-1 "4"]     #{test-id-2}
  ...}}

;; Form registry (from ClojureStorm): form-id → metadata
{:forms
 {form-id-1 {:form/ns "my.app.core"
             :form/form (defn foo [x] (+ x 1))
             :form/emitted-coords #{"3" "3,1" "3,2"}}
  ...}}
```

### Components

#### 1. Test Context Tracker

Tracks which test is currently executing.

```clojure
(ns heretic.context)

(def ^:dynamic *current-test*
  "The currently executing test identifier (symbol or keyword)"
  nil)

(defmacro with-test [test-id & body]
  `(binding [*current-test* ~test-id]
     ~@body))
```

#### 2. Coverage Tracer

Receives callbacks from ClojureStorm and records coverage per-test.

```clojure
(ns heretic.tracer
  (:require [heretic.context :refer [*current-test*]])
  (:import [clojure.storm Emitter Tracer FormRegistry]))

;; Mutable for performance during collection
(def ^:private coverage-data
  "Atom of {test-id {form-id #{coords}}}"
  (atom {}))

(defn- stringify-coord [coord]
  (if (string? coord)
    coord
    (clojure.string/join "," coord)))

(defn- record-hit! [form-id coord]
  (when-let [test-id *current-test*]
    (swap! coverage-data
           update-in [test-id form-id]
           (fnil conj #{})
           (stringify-coord coord))))

(defn init! []
  "Initialize ClojureStorm instrumentation with Heretic's callbacks"
  (Emitter/setInstrumentationEnable true)
  (Emitter/setFnCallInstrumentationEnable false)  ; optional
  (Emitter/setFnReturnInstrumentationEnable true)
  (Emitter/setExprInstrumentationEnable true)
  (Emitter/setBindInstrumentationEnable false)

  (Tracer/setTraceFnsCallbacks
   {:trace-expr-fn      (fn [_ _ coord form-id] (record-hit! form-id coord))
    :trace-fn-return-fn (fn [_ _ coord form-id] (record-hit! form-id coord))
    :trace-fn-unwind-fn (fn [_ _ coord form-id] (record-hit! form-id coord))}))

(defn get-coverage []
  "Return immutable snapshot of coverage data"
  @coverage-data)

(defn reset-coverage! []
  "Clear coverage data for fresh collection"
  (reset! coverage-data {}))
```

#### 3. Test Runner Integration

Hook into clojure.test to bind test context.

```clojure
(ns heretic.test-runner
  (:require [clojure.test :as t]
            [heretic.context :refer [*current-test*]]))

(defn wrap-test-var
  "Wrap clojure.test/test-var to track current test"
  [original-test-var]
  (fn [v]
    (binding [*current-test* (symbol v)]
      (original-test-var v))))

(defn install-test-wrapper! []
  "Patch clojure.test to track test context"
  (alter-var-root #'t/test-var wrap-test-var))
```

#### 4. Coverage Map Builder

Process raw coverage into queryable indexes.

```clojure
(ns heretic.coverage-map
  (:require [heretic.tracer :as tracer])
  (:import [clojure.storm FormRegistry]))

(defn build-coverage-map []
  "Build complete coverage map with all indexes"
  (let [test-coverage (tracer/get-coverage)
        forms (into {} (FormRegistry/getAllForms))

        ;; Build inverse index: [form-id coord] → #{test-ids}
        coord-to-tests
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
         test-coverage)]

    {:test-coverage test-coverage
     :coord-to-tests coord-to-tests
     :forms forms}))

(defn tests-for-location
  "Given a form-id and optional coord, return tests that hit it"
  ([coverage-map form-id]
   ;; All tests that hit any coord in this form
   (into #{}
         (for [[test-id forms] (:test-coverage coverage-map)
               :when (contains? forms form-id)]
           test-id)))

  ([coverage-map form-id coord]
   ;; Tests that hit this specific coord
   (get-in coverage-map [:coord-to-tests [form-id coord]] #{})))
```

#### 5. Persistence

Save/load coverage maps to avoid re-collection.

```clojure
(ns heretic.persistence
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-path ".heretic/coverage-map.edn")

(defn save-coverage-map! [coverage-map path]
  (io/make-parents path)
  (spit path (pr-str coverage-map)))

(defn load-coverage-map [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn coverage-map-stale?
  "Check if coverage map needs regeneration based on test file timestamps"
  [coverage-map-path test-paths]
  (let [map-time (.lastModified (io/file coverage-map-path))]
    (some #(> (.lastModified (io/file %)) map-time)
          test-paths)))
```

### Collection Flow

```
1. User runs: bb heretic:collect (or automatic on first mutation run)

2. Heretic:
   a. Calls heretic.tracer/init! to set up ClojureStorm callbacks
   b. Calls heretic.test-runner/install-test-wrapper! to patch clojure.test
   c. Runs the test suite (all tests)
   d. Each test execution:
      - Binds *current-test* to test identifier
      - ClojureStorm calls our callbacks for each expression
      - We record (test-id, form-id, coord) tuples
   e. After all tests: heretic.coverage-map/build-coverage-map
   f. Persist to .heretic/coverage-map.edn

3. Output: coverage map ready for mutation testing
```

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
| Original | Mutation |
|----------|----------|
| `first` | `last`, `rest` |
| `last` | `first`, `butlast` |
| `conj` | `disj` (for sets) |
| `inc` | `dec` |
| `dec` | `inc` |

#### Control Flow
| Original | Mutation |
|----------|----------|
| `if` condition | negate condition |
| `when` condition | negate condition |
| `cond` branch | remove branch |

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
  )

(defn apply-mutation [zloc mutation]
  "Apply a mutation at a location, return modified source"
  )
```

#### 2. Form-to-Source Mapper

Map ClojureStorm's form-ids back to source locations.

```clojure
(ns heretic.source-mapper
  (:import [clojure.storm FormRegistry]))

(defn build-source-index [source-paths]
  "Build index from source locations to form-ids.
   Returns {[file line col] -> form-id}"
  ;; Parse each file
  ;; Match forms to FormRegistry entries by namespace + form structure
  )

(defn form-id-for-location [source-index file line col]
  "Given a source location, find the form-id"
  )

(defn coord-for-subexpr [form-id subexpr-location]
  "Given a form-id and location within that form, find the coord string"
  ;; This requires understanding ClojureStorm's coordinate scheme
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
                :output :quiet}))  ;; Silent for mutation testing

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
   per namespace

4. **Zero dependencies**: Small, focused library with no runtime dependencies

5. **Battle-tested**: Used by Tonsky in production projects

#### 4. Mutation Engine

Apply mutations and track results.

```clojure
(ns heretic.mutation-engine
  (:require [heretic.parser :as parser]
            [heretic.source-mapper :as mapper]
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
  ;; 3. Navigate to mutation location
  ;; 4. Replace node
  ;; 5. Write file back
  )

(defn revert-mutation! [mutation]
  "Revert mutation, restore original source"
  ;; 1. Write original content back to file
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

(defn run-tests [test-ids {:keys [timeout]}]
  "Run specific tests, return results"
  ;; Filter test vars to only those in test-ids
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
 :coverage-map-path ".heretic/coverage-map.edn"

 ;; Namespace filtering (passed to ClojureStorm)
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev"]

 ;; Mutation settings
 :mutation-operators [:arithmetic :comparison :boolean :return-values]
 :skip-forms #{comment}
 :timeout-ms 5000
 :parallel true
 :max-workers 4

 ;; Reporting
 :report-format :html
 :output-path "target/heretic-report"}
```

---

## CLI Interface

```bash
# Collect coverage map (explicit)
bb heretic:collect

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

ClojureStorm uses positional paths into the AST, stored as vectors and
stringified with commas:

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

For maps and sets (unordered), coordinates use hash-based identifiers:
- Map keys: `"K-<hash>"`
- Map values: `"V-<hash>"`
- Set elements: `"K-<hash>"`

The coordinate scheme is implemented in `hansel.utils/walk-code-form`.

### Mapping rewrite-clj to ClojureStorm Coordinates

rewrite-clj uses zipper navigation. To convert:

```clojure
(defn coord->zloc [zloc coord]
  "Navigate a zipper using ClojureStorm coordinates"
  (reduce
    (fn [z idx]
      (if (string? idx)
        ;; Map/set element - search by hash
        (find-by-hash z idx)
        ;; Sequential - navigate by position
        (-> z z/down (nth-right idx))))
    zloc
    coord))
```

### clj-reload Integration

clj-reload provides the reloading infrastructure:

1. **Initialization**: `(reload/init {:dirs source-paths})`

2. **After mutation**: `(reload/reload)` detects file changes and reloads
   affected namespaces in correct order

3. **Unload cycle**: For each affected namespace:
   - Calls `before-ns-unload` hook if defined
   - Calls `remove-ns` to fully remove the namespace
   - Removes from `*loaded-libs*`

4. **Load cycle**: For each namespace to reload:
   - Loads the file
   - Calls `after-ns-reload` hook if defined

5. **Dependency handling**: Automatically computes transitive closure of
   dependent namespaces and processes them in topological order

---

## Open Questions

### 1. Coordinate Mapping (Medium Risk)

The core coordinate scheme (integer indices for sequential forms) maps cleanly
between ClojureStorm and rewrite-clj. Map/set coordinates using hashes need
special handling but are resolvable.

**Action**: Build and test the `coord->zloc` translation layer early.

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
- [ ] Set up ClojureStorm integration
- [ ] Implement test context tracking
- [ ] Build coverage map data structure
- [ ] Persistence (save/load)
- [ ] Basic CLI: `heretic:collect`

### Phase 2: Basic Mutation Testing
- [ ] Source parsing with rewrite-clj
- [ ] Implement 2-3 mutation operators (arithmetic, boolean)
- [ ] Form-id to source mapping
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
