---
status: stable
contributions:
  - "Could use: more operator examples for edge cases"
  - "Could use: benchmarks comparing schemata vs traditional approach"
---

# Heretic Architecture & Design

**Last Updated:** 2025-12-29

This document describes the architectural design of Heretic, informed by the research in `research.md`.

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [Core Abstractions](#2-core-abstractions)
3. [Pipeline Architecture](#3-pipeline-architecture)
4. [Test Selection Strategy](#4-test-selection-strategy)
5. [Mutator System](#5-mutator-system)
6. [AI Integration](#6-ai-integration)
7. [Performance Model](#7-performance-model)
8. [Extensibility](#8-extensibility)
9. [Data Model](#9-data-model)
10. [Open Questions](#10-open-questions)

---

## 1. Design Principles

### 1.1 Hybrid-First

Combine fast deterministic mutations with intelligent AI mutations. Neither alone is sufficient:
- Deterministic: Fast, predictable, cheap - but limited to syntactic patterns
- AI: Semantic understanding, realistic bugs - but slow and expensive

The architecture must treat both as first-class citizens with a unified interface.

### 1.2 Test Selection is Non-Negotiable

Running the full test suite for every mutant is O(mutants × tests). With hundreds of mutants and a multi-second test suite, this is unusable.

Test-to-code mapping enables O(mutants × relevant_tests), which can be orders of magnitude faster.

**Implication:** Test selection is a core architectural component, not an optimization.

### 1.3 Source-Level, Not Bytecode

Unlike PIT (bytecode), Heretic operates on Clojure source:
- Better error messages (line numbers, readable diffs)
- Clojure-specific mutations (keywords, destructuring, threading)
- Macro-aware (can mutate pre-expansion forms)
- No "junk mutations" from bytecode mismatch

**Trade-off:** Slower than bytecode mutation, but more meaningful results.

### 1.4 Incremental by Default

Mutation testing is expensive. The architecture should support:
- Caching mutation results across runs
- Only re-testing when source or tests change
- Resumable runs (interrupt and continue)

### 1.5 Schema-Aware (Optional)

When Malli/Spec schemas are available, use them to:
- Generate type-aware mutations
- Skip obviously invalid mutations
- Inform AI about expected data shapes

This is an enhancement, not a requirement.

### 1.6 Fail Fast

Stop testing a mutant as soon as any test fails. The goal is killed/survived, not "how many tests fail."

---

## 2. Core Abstractions

### 2.1 Mutant

A mutant represents a single change to the source code.

```clojure
{:id          "abc123"                    ; Unique identifier
 :file        "src/myapp/core.clj"        ; Source file
 :location    {:line 42 :column 5}        ; Where in the file
 :operator    :boolean/and-or             ; Which operator created this
 :original    "(and a b)"                 ; Original code (string)
 :mutated     "(or a b)"                  ; Mutated code (string)
 :description "Swap and → or"             ; Human-readable
 :category    :deterministic}             ; :deterministic or :ai
```

### 2.1.1 Mutation Identifier (Lightweight Reference)

For efficient controller↔worker communication, use lightweight identifiers:

```clojure
;; Full mutant lives in controller's registry
;; Workers receive only the identifier + minimal context

{:mutation-id "file:line:col:op:hash"     ; Unique, reproducible ID
 :file        "src/myapp/core.clj"        ; For worker file access
 :coordinates "3,2,1"                      ; zipper path into form
 :operator    :boolean/and-or}             ; How to apply

;; ID composition (reproducible across runs)
(defn mutation-id [file line col operator original-hash]
  (str file ":" line ":" col ":" (name operator) ":"
       (subs original-hash 0 8)))

;; Example: "src/myapp/core.clj:42:5:boolean/and-or:a1b2c3d4"
```

**Benefits:**
- Minimal data over the wire to workers
- Reproducible: same code + operator = same ID
- Traceable: ID encodes exact location
- Cache key: ID can index stored results

### 2.2 Operator

An operator knows how to find mutation points and generate mutants.

```clojure
(defprotocol Operator
  (id [this]
    "Keyword identifying this operator, e.g., :boolean/and-or")

  (category [this]
    "Either :deterministic or :ai")

  (find-targets [this zloc]
    "Given a zipper location, return truthy if this location can be mutated")

  (mutate [this zloc]
    "Given a target zipper location, return seq of mutated zloc variants"))
```

### 2.3 MutantResult

The outcome of testing a mutant.

```clojure
{:mutant-id   "abc123"
 :status      :killed            ; :killed, :survived, :equivalent, :error
 :killed-by   'myapp.core-test/test-foo  ; Which test killed it (if killed)
 :duration-ms 45                 ; How long testing took
 :error       nil}               ; Exception if :error status
```

### 2.4 TestIndex

Maps code locations to relevant tests (see Section 4).

```clojure
{:myapp.core/some-fn #{myapp.core-test/test-some-fn
                       myapp.core-test/test-integration}
 :myapp.core/other-fn #{myapp.core-test/test-other-fn}}
```

### 2.5 MutationRun

A complete mutation testing session.

```clojure
{:id           "run-20251228-143052"
 :started-at   #inst "2025-12-28T14:30:52"
 :finished-at  #inst "2025-12-28T14:35:12"
 :namespaces   [myapp.core myapp.util]
 :operators    [:boolean/and-or :comparison/boundary ...]
 :mutants      [...]             ; All generated mutants
 :results      [...]             ; All results
 :summary      {:total 127
                :killed 98
                :survived 24
                :equivalent 5
                :error 0
                :mutation-score 0.803}}
```

---

## 3. Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              HERETIC PIPELINE                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌────────┐│
│  │  Parse   │──▶│  Analyze │──▶│ Generate │──▶│  Filter  │──▶│  Test  ││
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘   └────────┘│
│       │              │              │              │              │     │
│       ▼              ▼              ▼              ▼              ▼     │
│   rewrite-clj    Test Index     Mutants      Valid Mutants    Results  │
│   zippers        extraction     (raw)        (filtered)                 │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                           Report                                  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Parse Phase

**Input:** Source file paths or namespace symbols
**Output:** rewrite-clj zipper for each file

```clojure
(defn parse-namespace [ns-sym]
  (let [file (ns->file ns-sym)
        source (slurp file)]
    {:namespace ns-sym
     :file file
     :zloc (z/of-string source)}))
```

**Decisions:**
- Parse all target files upfront
- Preserve original source for diff generation
- Track file metadata (path, checksum for caching)

### 3.2 Analyze Phase

**Input:** Parsed source, test namespaces
**Output:** Test index, schema information

This phase builds the test-to-code mapping (see Section 4) and optionally extracts Malli/Spec schemas for schema-aware mutation.

### 3.3 Generate Phase

**Input:** Parsed source, enabled operators
**Output:** Raw mutant candidates

```clojure
(defn generate-mutants [zloc operators]
  (loop [loc zloc
         mutants []]
    (if (z/end? loc)
      mutants
      (let [new-mutants (for [op operators
                              :when (find-targets op loc)
                              mutated (mutate op loc)]
                          (->Mutant op loc mutated))]
        (recur (z/next loc)
               (into mutants new-mutants))))))
```

**Decisions:**
- Depth-first traversal of AST
- Each operator gets a chance at each node
- Operators can generate multiple mutants per location

### 3.4 Filter Phase

**Input:** Raw mutants
**Output:** Valid mutants worth testing

Based on Meta's research: **25% of LLM-generated mutants are trivially syntactically equivalent**, and **61% differ only in comments**. Simple preprocessing is crucial.

#### Filter Pipeline

```clojure
(defn filter-mutants [mutants]
  (->> mutants
       ;; Stage 1: Syntactic (fast, cheap)
       (filter valid-syntax?)              ; clj-kondo check
       (remove trivially-equivalent?)      ; AST identity
       (remove comment-only-diff?)         ; Strip comments, compare
       (dedupe-by normalized-source)       ; Normalize whitespace

       ;; Stage 2: Semantic (slower)
       (remove known-equivalent-pattern?)  ; Heuristic patterns
       (remove arid-node?)                 ; No test coverage

       ;; Stage 3: Subsumption (optional)
       (apply-subsumption)))               ; Skip dominated mutants
```

#### Trivially Equivalent Detection

```clojure
(defn trivially-equivalent? [mutant]
  (let [orig-ast (parse (:original mutant))
        mut-ast (parse (:mutated mutant))]
    (= orig-ast mut-ast)))

(defn comment-only-diff? [mutant]
  (let [strip-comments #(remove-all-comments (parse %))
        orig (strip-comments (:original mutant))
        mut (strip-comments (:mutated mutant))]
    (= orig mut)))

(defn normalized-source [mutant]
  (-> (:mutated mutant)
      z/of-string
      z/root-string))  ; Normalizes whitespace
```

#### Known Equivalent Patterns (Heuristics)

```clojure
(def equivalent-patterns
  [;; (not (not x)) → x
   {:pattern '(not (not ?x)) :equivalent-to '?x}

   ;; (if true a b) → a
   {:pattern '(if true ?a ?b) :equivalent-to '?a}

   ;; (or x false) → x
   {:pattern '(or ?x false) :equivalent-to '?x}

   ;; (and x true) → x
   {:pattern '(and ?x true) :equivalent-to '?x}])
```

#### Suppression Rules (Google-Style)

Skip unproductive mutations that rarely reveal real bugs:

```clojure
(def suppression-rules
  {:skip-forms
   '#{log/debug log/info log/warn log/error  ; Logging
      println prn pr                          ; Debug output
      comment                                 ; Comment blocks
      assert}                                 ; Assertions

   :skip-patterns
   [#"^tap>"                                  ; tap> debugging
    #"^js/console"]                           ; JS console

   :skip-metadata-only true                   ; ^:private, ^:deprecated, etc.

   :skip-docstrings true})                    ; Docstring changes

(defn suppressed? [mutant rules]
  (or (contains? (:skip-forms rules) (first-symbol mutant))
      (some #(re-matches % (str (:original mutant))) (:skip-patterns rules))
      (and (:skip-metadata-only rules) (metadata-only-change? mutant))))
```

### 3.5 Test Phase

**Input:** Valid mutants, test index
**Output:** MutantResults

For each mutant:
1. Look up relevant tests from test index
2. Apply mutation to source (in-memory or temp file)
3. Load mutated namespace
4. Run relevant tests
5. Record result (killed/survived)
6. Restore original

```clojure
(defn test-mutant [mutant test-index]
  (let [relevant-tests (get-relevant-tests test-index mutant)
        mutated-source (apply-mutation mutant)]
    (with-mutated-ns mutated-source
      (run-tests-until-failure relevant-tests))))
```

**Parallelization:** Multiple mutants can be tested in parallel if they don't affect the same namespaces.

### 3.6 Report Phase

**Input:** All results
**Output:** Human-readable report, machine-readable data

Formats:
- CLI summary (default)
- Detailed survived mutants list
- HTML report (optional)
- EDN/JSON for CI integration

---

## 4. Test Selection Strategy

Test selection maps code locations to the tests that exercise them. Without this, every mutant requires running the full test suite.

### 4.1 Coverage-Based Selection (Primary Approach)

Based on PITest's proven architecture and Google's industrial deployment:

```
Phase 1: Coverage Collection
┌─────────────────────────────────────────────┐
│  Run all tests with coverage instrumentation │
│  (ClojureStorm or custom probes)            │
└─────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────┐
│  Build Coverage Map:                         │
│  form-id → #{test-var-1 test-var-2 ...}     │
└─────────────────────────────────────────────┘

Phase 2: Mutation Testing
┌─────────────────────────────────────────────┐
│  For each mutant:                            │
│  1. Look up form-id in coverage map         │
│  2. Run only covering tests                  │
│  3. Stop on first failure (early exit)      │
└─────────────────────────────────────────────┘
```

### 4.2 Coverage Map Schema

```clojure
{:coverage-map
 {form-id                              ; Unique form identifier
  {:tests #{test-var-1 test-var-2}     ; Tests that execute this form
   :coordinates #{"3" "3,2" "3,2,1"}   ; Sub-form coordinates covered
   :execution-count 5}}                 ; Times executed across all tests

 :test-metadata
 {test-var
  {:namespace 'myapp.core-test
   :execution-time-ms 45               ; For test ordering
   :forms-covered #{form-id-1 ...}     ; Inverse mapping
   :priority :unit}}                   ; :unit, :integration, :e2e

 :file-checksums
 {"src/myapp/core.clj" "abc123"        ; For cache invalidation
  "test/myapp/core_test.clj" "def456"}}
```

### 4.3 Test Ordering Within Coverage Set

Order tests for maximum early-exit benefit:

1. **Fastest first** - Tests with lowest `execution-time-ms`
2. **Most specific first** - Tests covering fewer forms (more targeted)
3. **Historical killers first** - Tests that killed similar mutants before

```clojure
(defn order-tests [tests mutant history]
  (->> tests
       (sort-by (juxt
                  :execution-time-ms           ; Fast first
                  #(count (:forms-covered %))  ; Specific first
                  #(- (kill-count history % mutant)))) ; Killers first
       (map :test-var)))
```

### 4.4 Commit-Aware Mode

Research shows **93% mutant reduction** and **30% higher fault-revelation** by focusing on changed code.

```clojure
(defn commit-relevant-mutants [diff all-mutants coverage-map]
  (let [changed-forms (extract-changed-forms diff)
        interacting-forms (find-interacting-forms changed-forms coverage-map)]
    ;; Mutate both changed code AND code that interacts with changes
    (filter #(or (changed-forms (:form-id %))
                 (interacting-forms (:form-id %)))
            all-mutants)))
```

**Key insight from research:** 81% of commit-relevant mutants are **outside** the changed code, in code that interacts with the changes.

### 4.5 Arid Node Filtering (Google Approach)

Skip forms with zero test coverage ("arid nodes"):

```clojure
(defn productive-mutants [mutants coverage-map]
  (filter #(seq (get-in coverage-map [:coverage-map (:form-id %) :tests]))
          mutants))
```

This prevents generating mutants that can never be killed.

### 4.6 Integration Points

```clojure
(defprotocol CoverageIndex
  (build-index [this test-namespaces]
    "Run tests with coverage, build the coverage map")

  (relevant-tests [this form-id]
    "Return ordered seq of test vars covering this form")

  (invalidate [this changed-files]
    "Invalidate cache entries for changed files")

  (is-arid? [this form-id]
    "Return true if no tests cover this form"))
```

### 4.7 Fallback Behavior

When coverage data is unavailable:
1. **Namespace convention**: `myapp.core` → `myapp.core-test`
2. **All tests in test-ns**: Run all tests in corresponding test namespace
3. **Warn user**: Suggest building coverage index for better performance

---

## 5. Mutator System

### 5.1 Operator Categories

```
┌─────────────────────────────────────────────┐
│               Operator Registry              │
├─────────────────────────────────────────────┤
│                                              │
│  ┌─────────────────┐  ┌─────────────────┐  │
│  │  Deterministic  │  │       AI        │  │
│  │    Operators    │  │   Operators     │  │
│  ├─────────────────┤  ├─────────────────┤  │
│  │ • boolean/and-or│  │ • ai/semantic   │  │
│  │ • cmp/boundary  │  │ • ai/logic-inv  │  │
│  │ • coll/first-last│ │ • ai/edge-case  │  │
│  │ • nil/seq-empty │  │                 │  │
│  │ • thread/dir    │  │                 │  │
│  │ • kw/namespace  │  │                 │  │
│  │ • hof/filter-rem│  │                 │  │
│  │ • destruct/keys │  │                 │  │
│  └─────────────────┘  └─────────────────┘  │
│                                              │
└─────────────────────────────────────────────┘
```

### 5.2 Deterministic Operator Implementation

```clojure
(defrecord AndOrOperator []
  Operator
  (id [_] :boolean/and-or)
  (category [_] :deterministic)

  (find-targets [_ zloc]
    (and (z/list? zloc)
         (let [first-child (-> zloc z/down z/sexpr)]
           (contains? #{'and 'or} first-child))))

  (mutate [_ zloc]
    (let [op (-> zloc z/down z/sexpr)
          new-op (if (= op 'and) 'or 'and)]
      [(-> zloc z/down (z/replace new-op) z/up)])))
```

### 5.3 Functional Programming Operators (MuCheck-Inspired)

Based on MuCheck (Haskell mutation tester), add operators targeting FP idioms:

| Operator | Original | Mutated | Targets |
|----------|----------|---------|---------|
| `:fp/fold-direction` | `reduce` | `reduce-right` (via `rseq`) | Fold order sensitivity |
| `:fp/partial-apply` | `(partial f a)` | `(partial f a b)` | Currying errors |
| `:fp/compose-order` | `(comp f g)` | `(comp g f)` | Composition bugs |
| `:fp/eta-reduce` | `#(f %)` | `f` | Unnecessary wrapping |
| `:fp/applicative-swap` | `(map f xs)` | `(mapv f xs)` | Lazy/eager mismatch |
| `:fp/monadic-bind` | `(mapcat f xs)` | `(map f xs)` | Flattening omission |
| `:fp/guard-remove` | `(when pred body)` | `body` | Guard elision |
| `:fp/recursion-base` | `(if (empty? xs) base ...)` | `(if (empty? xs) nil ...)` | Base case |

```clojure
(defrecord ComposeOrderOperator []
  Operator
  (id [_] :fp/compose-order)
  (category [_] :deterministic)

  (find-targets [_ zloc]
    (and (z/list? zloc)
         (= 'comp (-> zloc z/down z/sexpr))))

  (mutate [_ zloc]
    ;; (comp f g h) → (comp h g f)
    (let [args (-> zloc z/down z/rights)]
      [(-> zloc z/down
           (z/replace-children (cons 'comp (reverse args))))])))
```

### 5.4 Operator Composition

Operators can be:
- **Enabled/disabled** per run
- **Prioritized** (run high-value operators first)
- **Grouped** into profiles (e.g., `:quick`, `:thorough`, `:ai-enhanced`)

```clojure
(def profiles
  {:quick      [:boolean/and-or :cmp/boundary :nil/seq-empty]
   :standard   [:quick :coll/first-last :hof/filter-remove :thread/direction]
   :thorough   [:standard :destruct/keys :kw/namespace :lazy/eager :fp/compose-order]
   :fp         [:thorough :fp/fold-direction :fp/partial-apply :fp/monadic-bind]
   :ai         [:fp :ai/semantic :ai/logic-invert]})
```

### 5.5 Mutant Schemata (Compile-Once Optimization)

For maximum efficiency, use **mutant schemata**: compile all mutations once, select at runtime. This technique is implemented in `heretic.schemata`.

**Key Insight:** Clojure's dynamic vars enable elegant schemata without bytecode manipulation.

#### 5.5.1 The `*active-mutant*` Dynamic Var

The `heretic.schemata/*active-mutant*` dynamic var controls which mutation is active:

```clojure
(def ^:dynamic *active-mutant*
  "Dynamic var controlling which mutation is active.
   When nil (default), the original code path is executed.
   When set to a mutation id (keyword), that mutation is active."
  nil)
```

When `*active-mutant*` is `nil` (the default), all schematized code executes the original behavior. When bound to a mutation id (e.g., `:mut-42-5-plus-minus`), the corresponding mutant code path is executed.

**Thread Safety:** Dynamic vars in Clojure are thread-local, so different threads can test different mutations simultaneously without interference.

#### 5.5.2 How `build-schemata` Transforms Source Code

The `build-schemata` function transforms original source into schematized source:

```clojure
;; Original source
(defn calculate [x] (+ x 1))

;; After build-schemata with 2 mutations at the + location
(defn calculate [x]
  (case heretic.schemata/*active-mutant*
    :mut-1-5-plus-minus (- x 1)    ; swap-plus-minus operator
    :mut-1-5-mult-div   (* x 1)    ; swap-mult-div operator
    (+ x 1)))                      ; original (default case)
```

**Transformation Process:**

1. **Group mutations by location** - Multiple operators may target the same source location
2. **Sort in reverse order** - Process from end of file to beginning so replacements don't shift positions
3. **Build case nodes** - For each location, generate a `case` form with mutation id → replacement mappings
4. **Preserve original as default** - The original code is always the default case

```clojure
(defn build-schemata
  "Build schematized source from original source and mutations.

   Returns:
   {:schemata-source \"...\"       ; The schematized source code
    :mutation-map {id mutation}    ; Map from mutation id to mutation record
    :location-count n}             ; Number of locations schematized"
  [source mutations]
  ;; Implementation groups by location, processes in reverse order,
  ;; and builds case forms for each mutation point
  ...)
```

#### 5.5.3 The `case` Form Generation

When multiple mutations target the same location, they're combined into a single `case`:

```clojure
;; Original: (if (< x 10) a b)
;; With 3 mutations at the < location:

(if (case heretic.schemata/*active-mutant*
      :mut-1-5-lt-gt     (> x 10)   ; swap-lt-gt
      :mut-1-5-lt-lte    (<= x 10)  ; swap-lt-lte
      :mut-1-5-lt-neq    (not= x 10) ; swap-lt-neq
      (< x 10))                     ; original
    a b)
```

**Mutation ID Format:** `:mut-<line>-<col>-<operator-suffix>`

Example: `:mut-42-5-plus-minus` means line 42, column 5, swap-plus-minus operator.

#### 5.5.4 When to Use Schemata vs Traditional File Modification

| Approach | Best For | Trade-offs |
|----------|----------|------------|
| **Schemata** | Multiple mutations per file (>2), CI pipelines, parallel testing | Larger compiled code, dynamic var overhead |
| **Traditional** | Single mutation, debugging specific mutants, AOT compilation | File I/O per mutant, recompilation overhead |

The `should-use-schemata?` heuristic checks if any file has more than 2 mutations:

```clojure
(defn should-use-schemata?
  "Heuristic: Use schemata when there are multiple mutations per file."
  [mutations]
  (let [by-file (group-by :file mutations)]
    (boolean
     (some (fn [[_file file-mutations]]
             (> (count file-mutations) 2))
           by-file))))
```

#### 5.5.5 Performance Characteristics

**Compile-Once Benefit:**

```
Traditional approach (N mutations in 1 file):
  N × (modify file + reload namespace + run tests + revert file)

Schemata approach (N mutations in 1 file):
  1 × (build schemata + write file + reload namespace)
  + N × (bind *active-mutant* + run tests)
```

For a file with 50 mutations where namespace reload takes 200ms:
- Traditional: 50 × 200ms = 10 seconds of compilation
- Schemata: 1 × 200ms = 200ms of compilation

**Runtime Overhead:**

The `case` dispatch adds nanosecond-level overhead per mutation point. This is negligible compared to test execution time.

#### 5.5.6 Example Walkthrough

Given this source file:

```clojure
;; src/myapp/math.clj
(ns myapp.math)

(defn add-positive [a b]
  (if (and (> a 0) (> b 0))
    (+ a b)
    0))
```

With these mutations identified:
1. Line 4, col 7: `and` → `or` (swap-and-or)
2. Line 4, col 12: `>` → `<` (swap-gt-lt)
3. Line 4, col 20: `>` → `<` (swap-gt-lt)
4. Line 5, col 5: `+` → `-` (swap-plus-minus)
5. Line 6, col 5: `0` → `1` (replace-0-to-1)

**After schematization:**

```clojure
(ns myapp.math)

(defn add-positive [a b]
  (if (case heretic.schemata/*active-mutant*
        :mut-4-7-and-or or
        and)
      (case heretic.schemata/*active-mutant*
        :mut-4-12-gt-lt <
        >)
      a 0)
      (case heretic.schemata/*active-mutant*
        :mut-4-20-gt-lt <
        >)
      b 0))
    (case heretic.schemata/*active-mutant*
      :mut-5-5-plus-minus -
      +)
    a b)
    (case heretic.schemata/*active-mutant*
      :mut-6-5-0-to-1 1
      0)))
```

**Testing with schemata:**

```clojure
(require '[heretic.schemata :as schemata])

;; Test mutation 1 (and → or)
(schemata/with-mutant :mut-4-7-and-or
  (run-tests 'myapp.math-test))

;; Or use run-mutation-batch for all mutations
(schemata/run-mutation-batch
  "src/myapp/math.clj"
  mutations
  (fn [mutation-id mutation]
    (run-tests 'myapp.math-test))
  :reload-fn #(require 'myapp.math :reload))
```

#### 5.5.7 Integration with Worker System

The worker system (`heretic.worker`) uses schemata for efficient batch testing:

```clojure
;; In heretic.worker, the flow is:
;; 1. Controller groups mutations by file
;; 2. For each file with multiple mutations:
;;    a. schematize-file! transforms the source
;;    b. reload-fn reloads the namespace once
;;    c. For each mutation-id in the file:
;;       - bind *active-mutant*
;;       - run relevant tests
;;       - report result
;;    d. restore-file! reverts to original
;;    e. reload-fn restores original code

;; The run-mutation-batch function handles this:
(schemata/run-mutation-batch
  file-path
  mutations
  (fn [mutation-id mutation]
    ;; This runs with *active-mutant* bound
    (runner/evaluate-mutation index mutation config))
  :reload-fn #(reloader/reload!)
  :on-progress progress-callback)
```

**Key Integration Points:**

- `schematize-file!` - Writes schematized source, returns backup for restoration
- `restore-file!` - Restores original source from backup
- `with-mutant` - Macro for binding `*active-mutant*` around test execution
- `with-schemata` - Macro for safe schematize/restore lifecycle

**Benefits:**
- Single recompilation covers all mutants in a file
- No file system I/O between mutations
- Fast mutation switching via dynamic binding
- Natural thread isolation (each worker can test different mutations)

**Trade-offs:**
- Increases code size (all mutations compiled into case forms)
- Dynamic var lookup has small overhead (~nanoseconds)
- Not compatible with AOT compilation
- Best for: interactive development, CI pipelines with many mutations per file

### 5.6 Higher-Order Mutations

Design consideration: Should Heretic support combining multiple first-order mutations?

**Arguments for:**
- Research shows HOMs find subtle bugs
- Reduce equivalent mutants
- More realistic fault simulation

**Arguments against:**
- Combinatorial explosion
- Harder to interpret survivors
- Added complexity

**Decision:** Design the Mutant abstraction to support HOMs, but defer implementation.

```clojure
;; HOM support in data model
{:id "hom-123"
 :components [{:operator :boolean/and-or :location {...}}
              {:operator :cmp/boundary :location {...}}]
 :category :higher-order}
```

---

## 6. AI Integration

### 6.0 Provider Abstraction (Dependency Injection)

Heretic has **zero LLM dependencies**. Users inject their own provider implementation.

**Minimal Protocol:**
```clojure
(defprotocol LLMProvider
  (completion [this messages opts]
    "Execute a completion request.
     Returns: {:content string :usage {:prompt-tokens n :completion-tokens n}}"))
```

**Why minimal?** Following Clojure protocol best practices:
- Protocols define primitives, not domain operations
- Domain logic (mutation generation, equivalent detection) lives in regular functions
- Heretic owns all prompts and response parsing
- Any LLM library (clj-http, openai-clojure, Bosquet, etc.) can implement the protocol

See `docs/spec.md` section 4.0 for full design and example adapters.

### 6.1 Three-Agent Workflow (Meta ACH Pattern)

Based on Meta's research, use a **three-agent pipeline** for AI mutations:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        AI Mutation Pipeline                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐            │
│  │   GENERATOR  │────▶│   ANALYZER   │────▶│    ORACLE    │            │
│  │    Agent     │     │    Agent     │     │    Agent     │            │
│  └──────────────┘     └──────────────┘     └──────────────┘            │
│         │                    │                    │                     │
│         ▼                    ▼                    ▼                     │
│   Generate N           Filter out          Predict killed/            │
│   mutants from         obviously           survived to                │
│   function             equivalent          prioritize                  │
│                        mutants                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Agent Roles:**

1. **Generator Agent** - Produces mutant candidates with semantic understanding
2. **Analyzer Agent** - Filters equivalent mutants before expensive testing
3. **Oracle Agent** - Predicts likelihood of detection for prioritization

### 6.2 When to Use AI

AI mutations are expensive (latency + cost). Use them:
- After deterministic mutations (fill gaps)
- For functions with high complexity
- When deterministic survival rate is high (tests may be weak)
- On user request

### 6.3 Context Gathering

AI needs context to generate meaningful mutations:

```clojure
{:function-name   "apply-discount"
 :docstring       "Apply discount to price for premium users"
 :source          "(defn apply-discount [price user] ...)"
 :schemas         {:price :int, :user [:map [:premium :boolean]]}
 :test-names      ["test-apply-discount" "test-premium-pricing"]
 :dependencies    [calculate-base-price lookup-discount-rate]
 :related-fns     [apply-surcharge remove-discount]}
```

### 6.4 Prompt Structure

```
You are an expert at finding subtle bugs in Clojure code.

Given this function:
- Name: {function-name}
- Purpose: {docstring}
- Source:
```clojure
{source}
```

Generate 3-5 mutated versions that:
1. Compile and run without errors
2. Represent realistic developer mistakes
3. Change behavior in ways tests might miss
4. Are subtle (not obviously wrong)

For each mutation, provide:
- The mutated code
- A brief explanation of what's wrong
- Why tests might miss it

Format as EDN:
[{:mutated "..." :explanation "..." :why-subtle "..."}]
```

### 6.5 Response Parsing

```clojure
(defn parse-ai-response [response]
  (->> response
       (parse-edn)
       (map (fn [{:keys [mutated explanation]}]
              {:source mutated
               :description explanation
               :category :ai}))
       (filter valid-clojure?)))
```

### 6.6 Cost Control

- **Budget:** Max tokens/cost per run
- **Batching:** Send multiple functions in one request
- **Caching:** Cache AI responses for unchanged functions
- **Fallback:** If AI fails, continue with deterministic only

---

## 7. Performance Model

### 7.1 Cost Equation

```
Total Time = Parse + Analyze + Generate + Filter + Test
           ≈ O(source_size) + O(tests) + O(mutants) + O(mutants) + O(mutants × tests_per_mutant)
```

The dominant factor is **testing**. Everything else is negligible.

### 7.2 Test Phase Optimization

| Optimization | Impact | Complexity |
|--------------|--------|------------|
| **Test selection** | 10-100x | Medium |
| **Early termination** | 2-5x | Low |
| **Parallel execution** | Nx (cores) | Medium |
| **Incremental runs** | 5-20x | Medium |
| **In-memory mutation** | 1.5-2x | Low |

### 7.3 Parallelization Strategy

```
┌─────────────────────────────────────────┐
│            Mutant Queue                  │
├─────────────────────────────────────────┤
│  M1  │  M2  │  M3  │  M4  │  M5  │ ... │
└──┬───┴──┬───┴──┬───┴──┬───┴──┬───┴─────┘
   │      │      │      │      │
   ▼      ▼      ▼      ▼      ▼
┌─────┐┌─────┐┌─────┐┌─────┐┌─────┐
│ W1  ││ W2  ││ W3  ││ W4  ││ W5  │  Workers
└─────┘└─────┘└─────┘└─────┘└─────┘
```

**Constraints:**
- Mutants affecting the same namespace may conflict
- Each worker needs isolated classloader/namespace state
- Coordinate via work-stealing queue

### 7.4 Caching Strategy

```clojure
;; Cache key: hash of (source-file + test-files + operator-config)
;; Cache value: mutation results

(defn cache-key [file operators]
  (hash [(slurp file)
         (map slurp (test-files-for file))
         operators]))
```

Cache invalidation:
- Source file changes → invalidate mutants for that file
- Test file changes → invalidate results for covered source
- Operator config changes → invalidate all

---

## 8. Extensibility

### 8.1 Custom Operators

Heretic's operator system is data-driven, making it easy to define custom operators. Each operator is a map with specific keys that define its behavior.

#### 8.1.1 Operator Structure

An operator is a map with these keys:

```clojure
{:id          :keyword           ; Unique identifier for this operator
 :original    value-or-:dynamic  ; What this operator matches (or :dynamic for computed)
 :replacement value-or-fn        ; What to replace with (or fn for computed)
 :description "string"           ; Human-readable description
 :matcher     (fn [zloc] bool)}  ; Predicate that identifies targets
```

#### 8.1.2 Complete Example: `swap-assoc-dissoc`

This example implements an operator that swaps `assoc` with `dissoc` to catch map manipulation bugs.

**Step 1: Define the Matcher**

The matcher is a predicate that returns true when the zipper is positioned at a valid mutation target:

```clojure
(ns myapp.custom-operators
  (:require [heretic.operators :as ops]
            [rewrite-clj.zip :as z]))

(defn- symbol-matcher
  "Create a matcher that checks if zloc is a specific symbol."
  [sym]
  (fn [zloc]
    (and (= :token (z/tag zloc))
         (symbol? (z/sexpr zloc))
         (= sym (z/sexpr zloc)))))
```

**Step 2: Define the Operator**

```clojure
(def swap-assoc-dissoc
  "Replace assoc with dissoc.
   Catches bugs where map entries are added when they should be removed,
   or where dissoc behavior differs from assoc (e.g., missing key handling)."
  {:id :swap-assoc-dissoc
   :original 'assoc
   :replacement 'dissoc
   :description "Replace assoc with dissoc"
   :matcher (symbol-matcher 'assoc)})

(def swap-dissoc-assoc
  "Replace dissoc with assoc.
   Catches bugs where map entries are removed when they should be added."
  {:id :swap-dissoc-assoc
   :original 'dissoc
   :replacement 'assoc
   :description "Replace dissoc with assoc"
   :matcher (symbol-matcher 'dissoc)})
```

**Step 3: Register the Operators**

Add your operators to the registry so they're included in mutation generation:

```clojure
;; Option 1: Extend all-operators (at load time)
(def my-operators [swap-assoc-dissoc swap-dissoc-assoc])

;; Add to your project's operator config
(def extended-operators
  (concat ops/all-operators my-operators))

;; Option 2: Create a custom preset
(def my-preset
  (into (:standard ops/presets)
        #{:swap-assoc-dissoc :swap-dissoc-assoc}))
```

**Step 4: Create a Custom Preset**

For reuse across projects, define a named preset:

```clojure
(def custom-presets
  {:map-ops
   #{:swap-assoc-dissoc
     :swap-dissoc-assoc}

   :my-standard
   (into (:standard ops/presets)
         #{:swap-assoc-dissoc :swap-dissoc-assoc})})

;; Usage in config
{:preset :my-standard
 :operators custom-presets}
```

**Step 5: Write Tests for the Operator**

```clojure
(ns myapp.custom-operators-test
  (:require [clojure.test :refer [deftest is testing]]
            [myapp.custom-operators :as custom]
            [heretic.operators :as ops]
            [rewrite-clj.zip :as z]))

(defn- zloc-at
  "Parse source and navigate to the form at the given coord."
  [source coord]
  (let [zloc (z/of-string source)]
    (reduce (fn [z idx]
              (let [child (z/down z)]
                (loop [c child i 0]
                  (cond
                    (= i idx) c
                    (nil? c) nil
                    :else (recur (z/right c) (inc i))))))
            zloc
            coord)))

(deftest test-swap-assoc-dissoc-matcher
  (testing "Matches assoc symbol"
    (let [zloc (zloc-at "(assoc m :key val)" [0])]
      (is ((:matcher custom/swap-assoc-dissoc) zloc))))

  (testing "Does not match dissoc symbol"
    (let [zloc (zloc-at "(dissoc m :key)" [0])]
      (is (not ((:matcher custom/swap-assoc-dissoc) zloc)))))

  (testing "Does not match assoc-in"
    (let [zloc (zloc-at "(assoc-in m [:a :b] val)" [0])]
      (is (not ((:matcher custom/swap-assoc-dissoc) zloc))))))

(deftest test-swap-dissoc-assoc-matcher
  (testing "Matches dissoc symbol"
    (let [zloc (zloc-at "(dissoc m :key)" [0])]
      (is ((:matcher custom/swap-dissoc-assoc) zloc))))

  (testing "Does not match assoc symbol"
    (let [zloc (zloc-at "(assoc m :key val)" [0])]
      (is (not ((:matcher custom/swap-dissoc-assoc) zloc))))))

(deftest test-apply-operator-assoc-dissoc
  (testing "Returns correct replacement"
    (let [zloc (zloc-at "(assoc m :k v)" [0])]
      (is (= "dissoc" (ops/apply-operator custom/swap-assoc-dissoc zloc))))
    (let [zloc (zloc-at "(dissoc m :k)" [0])]
      (is (= "assoc" (ops/apply-operator custom/swap-dissoc-assoc zloc))))))

(deftest test-integration-with-applicable-operators
  (testing "Custom operators are found by applicable-operators"
    ;; Temporarily extend operators for this test
    (let [extended (concat ops/all-operators
                           [custom/swap-assoc-dissoc
                            custom/swap-dissoc-assoc])]
      (with-redefs [ops/all-operators extended]
        (let [zloc (zloc-at "(assoc m :key val)" [0])
              applicable (ops/applicable-operators zloc)
              ids (set (map :id applicable))]
          (is (contains? ids :swap-assoc-dissoc)))))))
```

#### 8.1.3 Dynamic Operators (Computed Replacements)

For operators where the replacement depends on the matched value, use a function:

```clojure
(def mutate-keyword-typo
  "Add common typo to keyword names (double letter).
   Example: :name -> :namee"
  {:id :mutate-keyword-typo
   :original :dynamic
   :replacement (fn [zloc]
                  (let [kw (z/sexpr zloc)
                        name-str (name kw)
                        ;; Double the last letter
                        new-name (str name-str (last name-str))]
                    (if-let [ns (namespace kw)]
                      (keyword ns new-name)
                      (keyword new-name))))
   :description "Add typo to keyword (double last letter)"
   :matcher (fn [zloc]
              (and (= :token (z/tag zloc))
                   (keyword? (z/sexpr zloc))
                   (> (count (name (z/sexpr zloc))) 2)))})
```

**Usage:**
```clojure
;; Original
(get user :email)

;; Mutated
(get user :emaill)  ; Tests should catch if :email is expected
```

#### 8.1.4 Context-Aware Matchers

Some operators need to examine the surrounding context:

```clojure
(defn- in-threading-context?
  "Check if zloc is inside a threading macro."
  [zloc]
  (loop [z (z/up zloc)]
    (when z
      (if (and (z/list? z)
               (contains? #{'-> '->> 'some-> 'some->>}
                          (some-> z z/down z/sexpr)))
        true
        (recur (z/up z))))))

(def swap-get-in-get
  "Replace get-in with get (drops nested access).
   Only in non-threading context where arity matters."
  {:id :swap-get-in-get
   :original 'get-in
   :replacement 'get
   :description "Replace get-in with get"
   :matcher (fn [zloc]
              (and (= :token (z/tag zloc))
                   (= 'get-in (z/sexpr zloc))
                   (not (in-threading-context? zloc))))})
```

#### 8.1.5 Best Practices for Custom Operators

1. **Make matchers precise** - Avoid false positives that waste testing time
2. **Test edge cases** - Ensure matchers handle strings, comments, and unusual syntax
3. **Document the mutation's purpose** - Explain what bug pattern this catches
4. **Consider arity** - Some mutations only make sense for certain arities
5. **Use existing helpers** - `symbol-matcher` and `value-matcher` cover common cases
6. **Group related operators** - Create presets for domain-specific mutation sets

```clojure
;; Example: Domain-specific preset for web applications
(def web-preset
  #{:swap-assoc-dissoc      ; Map manipulation
    :swap-dissoc-assoc
    :mutate-kebab-to-camel  ; JS interop
    :mutate-camel-to-kebab
    :swap-nil-some          ; Null safety
    :swap-some-nil
    :swap-get-get-in        ; Nested access
    :swap-get-in-get})
```

### 8.2 Test Runner Adapters

Support different test frameworks:

```clojure
(defprotocol TestRunner
  (discover-tests [this namespaces]
    "Find all tests in the given namespaces")

  (run-tests [this test-vars]
    "Run the specified tests, return pass/fail"))

;; Built-in adapters
(defrecord ClojureTestRunner [] ...)
(defrecord KaochaRunner [] ...)
```

### 8.3 Reporter Plugins

Custom output formats:

```clojure
(defprotocol Reporter
  (on-mutant-tested [this mutant result])
  (on-run-complete [this run-summary]))

;; Built-in reporters
(defrecord CLIReporter [] ...)
(defrecord HTMLReporter [] ...)
(defrecord EDNReporter [] ...)
```

### 8.4 Hooks

Extension points for custom logic:

```clojure
{:before-parse    (fn [files] ...)
 :after-generate  (fn [mutants] ...)
 :before-test     (fn [mutant] ...)
 :after-test      (fn [mutant result] ...)
 :on-complete     (fn [run] ...)}
```

---

## 9. Data Model

### 9.1 Configuration

```clojure
{:namespaces     [myapp.core myapp.util]    ; Namespaces to mutate
 :test-nss       [myapp.core-test]          ; Test namespaces
 :operators      [:boolean/and-or ...]      ; Or profile keyword
 :profile        :standard                  ; Operator profile
 :parallelism    4                          ; Worker count
 :timeout-ms     5000                       ; Per-mutant timeout
 :fail-fast      false                      ; Stop on first survivor?
 :ai             {:enabled false            ; AI mutations
                  :model "claude-sonnet"
                  :max-cost 1.00}
 :cache          {:enabled true
                  :dir ".heretic-cache"}
 :report         {:format :cli              ; :cli, :html, :edn
                  :output "mutation-report"}}
```

### 9.2 Persistent State

```
.heretic-cache/
├── index.edn           # File checksums, last run info
├── test-index.edn      # Test-to-code mapping
├── mutants/            # Cached mutant generation
│   ├── abc123.edn
│   └── def456.edn
└── results/            # Cached test results
    ├── run-001.edn
    └── run-002.edn
```

### 9.3 Report Schema

```clojure
{:summary
 {:total 127
  :killed 98
  :survived 24
  :equivalent 5
  :error 0
  :mutation-score 0.803
  :duration-ms 45230}

 :by-operator
 {:boolean/and-or {:total 12 :killed 10 :survived 2}
  :cmp/boundary   {:total 23 :killed 20 :survived 3}
  ...}

 :by-file
 {"src/myapp/core.clj" {:total 45 :killed 38 :survived 7}
  ...}

 :survived
 [{:mutant {...}
   :location {:file "..." :line 42}
   :original "(and a b)"
   :mutated "(or a b)"
   :tested-with [test-foo test-bar]}
  ...]}
```

---

## 10. Open Questions

### 10.1 Test Execution Isolation

**Decision:** Adopt PITest's proven architecture - **Controller + Worker processes**.

**Implementation:** The codebase is organized in three main modules:

- **`heretic.core`** - Entry point and CLI interface. Handles configuration loading,
  coverage collection, and mutation testing orchestration. Uses ExecutorService for
  file-level parallelism.

- **`heretic.controller`** - Pure orchestration functions following functional core /
  imperative shell pattern. Provides:
  - `resolve-operators` - Operator resolution from config
  - `prepare-mutations` - Mutation generation and filtering
  - `aggregate-results` - Result aggregation with subsumption analysis
  - `build-test-config` - Test configuration building
  - Coverage and timing data management

- **`heretic.worker`** - Missionary-based execution with proper timeout and supervision.
  See `docs/worker-supervision-design.md` for the full design. Key features:
  - Reliable timeout via Missionary task cancellation (not just future abandonment)
  - File-level parallelism with proper coordination
  - Supervision policies: `:skip`, `:retry`, `:abort`
  - Progress callbacks for real-time reporting

This separation ensures:
- Pure functions are easily testable and composable
- Side effects are isolated to core.clj entry points
- Worker module provides alternative executor with better supervision

```
┌─────────────────────────────────────────────────────────────┐
│                    Heretic Controller                        │
│  (Main process - never loads code under test)               │
├─────────────────────────────────────────────────────────────┤
│  • Mutation identifier registry                              │
│  • Coverage map store                                        │
│  • Work queue management                                     │
│  • Result aggregation                                        │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Worker 1   │      │  Worker 2   │      │  Worker N   │
│  (nREPL)    │      │  (nREPL)    │      │  (nREPL)    │
├─────────────┤      ├─────────────┤      ├─────────────┤
│ • Receive   │      │ • Receive   │      │ • Receive   │
│   mutation  │      │   mutation  │      │   mutation  │
│   spec      │      │   spec      │      │   spec      │
│ • Apply via │      │ • Apply via │      │ • Apply via │
│   rewrite-  │      │   rewrite-  │      │   rewrite-  │
│   clj       │      │   clj       │      │   clj       │
│ • Reload ns │      │ • Reload ns │      │ • Reload ns │
│ • Run tests │      │ • Run tests │      │ • Run tests │
│ • Report    │      │ • Report    │      │ • Report    │
└─────────────┘      └─────────────┘      └─────────────┘
```

**Benefits:**
- Controller remains clean (no state pollution)
- Can kill worker on infinite loop (timeout)
- Natural parallelism
- Worker can be reused across mutants (amortize startup)

**Worker Lifecycle:**
1. Start worker REPL with test dependencies
2. Receive mutation spec from controller
3. Apply mutation, reload namespace
4. Run targeted tests with timeout
5. Report result, reset state OR restart worker
6. Repeat until queue empty

### 10.2 Macro Handling

**Question:** Should we mutate macro forms or their expansions?

Options:
1. **Pre-expansion** - Mutate what developers write
2. **Post-expansion** - Mutate what actually runs
3. **Both** - Different operator categories

### 10.3 ClojureScript Support

**Question:** Should Heretic support ClojureScript?

Considerations:
- Different runtime (Node, browser)
- Different test frameworks (cljs.test)
- rewrite-clj works for both
- Could be a V2 feature

### 10.4 AI Provider Abstraction

**Question:** Should Heretic support multiple LLM providers?

Options:
1. **Claude-only** - Simpler, consistent results
2. **Provider abstraction** - More flexible, more complexity
3. **Start with one, abstract later** - Pragmatic

### 10.5 Equivalent Mutant Strategy

**Question:** How aggressively should we detect equivalents?

Options:
1. **Conservative** - Only obvious patterns (risk: false survivors)
2. **Aggressive** - ML-based detection (risk: false equivalents)
3. **User-assisted** - Flag suspects, let user decide

---

## Appendix: Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2025-12-28 | Source-level not bytecode | Better Clojure integration, readable diffs |
| 2025-12-28 | Test selection is core | Performance is unusable without it |
| 2025-12-28 | Unified operator protocol | AI and deterministic should compose |
| 2025-12-28 | Controller + Worker process isolation | PITest-proven architecture, timeout safety, parallel |
| 2025-12-28 | Three-agent AI workflow | Meta ACH research shows Generator→Analyzer→Oracle effective |
| 2025-12-28 | Coverage-based test selection | PITest/Google proven, commit-aware gives 93% reduction |
| 2025-12-28 | Equivalent mutant preprocessing | 25% trivially equivalent, 61% comment-only (Meta research) |
| 2025-12-28 | Mutant schemata via dynamic vars | Compile-once optimization, natural for Clojure |
| 2025-12-28 | FP-specific operators | MuCheck-inspired, targets Clojure idioms |
| | | |

---

## Next Steps

1. ~~Finalize test selection strategy~~ ✓ Coverage-based with commit-aware mode
2. ~~Decide on test isolation approach~~ ✓ Controller + Worker architecture
3. **Prototype coverage instrumentation** (ClojureStorm or custom probes)
4. **Implement core data types** (Mutant, Operator protocol, MutantResult)
5. **Build minimal parse → mutate → test pipeline** (single operator, no parallelism)
6. **Add operators incrementally** (start with boolean, comparison, collection)
7. **Implement mutant schemata** (dynamic var binding approach)
8. **Integrate AI pipeline** (three-agent workflow)
