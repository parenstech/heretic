# Heretic Architecture & Design

**Status:** Draft
**Last Updated:** 2025-12-28

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

For maximum efficiency, use **mutant schemata**: compile all mutations once, select at runtime.

**Key Insight:** Clojure's dynamic vars enable elegant schemata without bytecode manipulation:

```clojure
;; Original function
(defn calculate-total [items]
  (reduce + (map :price items)))

;; Schematized version (all mutations embedded)
(def ^:dynamic *mutation-id* nil)

(defn calculate-total [items]
  (reduce
    (case *mutation-id*
      :m1 -        ; Mutant 1: + → -
      :m2 *        ; Mutant 2: + → *
      +)           ; Original
    (map
      (case *mutation-id*
        :m3 :cost  ; Mutant 3: :price → :cost
        :price)
      items)))

;; Test runner selects mutation
(binding [*mutation-id* :m1]
  (run-tests 'myapp.core-test))
```

**Benefits:**
- Single recompilation covers all mutants
- No file system I/O per mutant
- Fast mutation switching via dynamic binding
- Natural thread isolation

**Implementation Sketch:**

```clojure
(defn schematize-form [zloc operators]
  (let [mutations (find-all-mutations zloc operators)]
    (if (seq mutations)
      (z/replace zloc
        `(case ~'heretic/*mutation-id*
           ~@(mapcat (fn [{:keys [id mutated]}]
                       [id mutated])
                     mutations)
           ~(z/sexpr zloc)))  ; Default: original
      zloc)))

(defn run-with-mutation [mutation-id test-vars]
  (binding [heretic/*mutation-id* mutation-id]
    (run-tests test-vars)))
```

**Trade-offs:**
- Increases code size (all mutations compiled)
- Dynamic var lookup has small overhead
- Not compatible with AOT compilation
- Best for: interactive development, CI pipelines

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

Users can define custom operators:

```clojure
(defrecord MyCustomOperator []
  heretic.operator/Operator
  (id [_] :custom/my-mutation)
  (category [_] :deterministic)
  (find-targets [_ zloc] ...)
  (mutate [_ zloc] ...))

;; Register
(heretic/register-operator (->MyCustomOperator))
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
