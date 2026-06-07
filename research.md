---
status: stable
contributions:
  - "Done (#9, G1): equivalent filter audited — ~20/29 patterns were unsound; rewritten to 13 sound patterns. See docs/validation-results.md §1-2"
  - "Done (#9, G2): real-world subsumption reduction measured across 5 targets — target-dependent (−11pp…+29pp), inconclusive. See docs/validation-results.md §5"
  - "Done (#9, G3): clustering validated — static hardness ranking is no better than random (5/10), retired in heretic.clustering. See docs/validation-results.md §5.3"
  - "Needs: LLM mutation generation quality/cost benchmarks (blocked on Phase 4 AI, unbuilt)"
---

# Heretic: AI-Powered Mutation Testing for Clojure

## Research Document

**Project:** Heretic - Mutation testing that uses AI to break code in subtle, semantically meaningful ways

**Last Updated:** 2025-12-29

---

## Table of Contents

1. [Mutation Testing Landscape](#1-mutation-testing-landscape)
2. [AI-Powered Mutation Testing](#2-ai-powered-mutation-testing)
   - 2.8 [Clojure Equivalent Mutant Patterns (Implemented)](#28-clojure-equivalent-mutant-patterns-implemented)
3. [Clojure-Specific Patterns](#3-clojure-specific-patterns)
4. [Technical Foundation](#4-technical-foundation)
5. [Performance Strategies](#5-performance-strategies)
   - 5.6 [Subsumption Analysis (Implemented)](#56-subsumption-analysis-implemented)
6. [Heretic Architecture](#6-heretic-architecture)
7. [Appendices A-C](#appendix-a-mutation-operator-quick-reference)
8. [Phase 3 Implementation Learnings](#8-phase-3-implementation-learnings)
9. [References](#9-references)

---

## 1. Mutation Testing Landscape

### 1.1 What is Mutation Testing?

Mutation testing evaluates test suite quality by:
1. Introducing small bugs (mutations) into source code
2. Running the test suite against each mutant
3. Checking if tests fail (mutant "killed") or pass (mutant "survived")
4. Surviving mutants indicate weak test coverage

**Mutation Score** = Killed Mutants / Total Mutants

### 1.2 Traditional Tools by Language

#### PIT (Pitest) - Java/JVM
- **Repository:** https://github.com/hcoles/pitest
- **Website:** https://pitest.org/
- **Approach:** Bytecode-level mutation (faster than source transformation)

**Mutation Operators (grouped):**

| Group | Operators |
|-------|-----------|
| **DEFAULTS** | Conditionals Boundary, Negate Conditionals, Math, Increments, Invert Negatives, Return Values |
| **STRONGER** | Defaults + Remove Conditionals |
| **ALL** | Stronger + Experimental operators |

**Experimental Operators:**
- Argument Propagation
- Big Integer mutations
- Member Variable mutations
- Switch statement mutations
- AOR (Arithmetic Operator Replacement)
- AOD (Arithmetic Operator Deletion)
- CRCR (Constant Replacement)
- OBBN (Bitwise Operator)
- ROR (Relational Operator Replacement)
- UOI (Unary Operator Insertion)

**Performance Features:**
- Incremental analysis (only re-test changed code)
- Parallel test execution
- HTML dashboards and XML output for CI
- Maven/Gradle integration

#### Stryker Mutator - JavaScript/TypeScript/C#/Scala
- **Website:** https://stryker-mutator.io/
- **Approach:** AST transformation

**Supported Operators by Language:**

| Operator | StrykerJS | Stryker.NET | Stryker4s |
|----------|-----------|-------------|-----------|
| Arithmetic | Yes | Yes | No |
| Array Declaration | Yes | Yes | No |
| Block Statement | Yes | Yes | No |
| Boolean Literal | Yes | Yes | Yes |
| Conditional Expression | Yes | Yes | Yes |
| Equality Operator | Yes | Yes | Yes |
| Logical Operator | Yes | Yes | Yes |
| Method Expression | Yes | Yes | Yes |
| Regex | Yes | Yes | Yes |
| String Literal | Yes | Yes | Yes |
| Unary Operator | Yes | Yes | No |

**Features:**
- 30+ supported mutations with code analysis
- Parallel test runner processes
- Real-time dashboards
- Visual Studio Code integration

#### Arcmutate - PIT Extensions (Commercial)
- **Documentation:** https://docs.arcmutate.com/docs/extended-operators.html
- **Approach:** Commercial extension to PIT

**Extended Operators:**
- `CHAINED_CALLS` - Builder pattern method removal
- `VARARGS` - Remove last varargs argument
- `ONE_LESS_PARAM` - Swap for overloads with fewer parameters
- Stream: `REMOVE_FILTER`, `REMOVE_DISTINCT`, `REMOVE_LIMIT`, `REMOVE_SKIP`, `REMOVE_SORTED`
- Predicate: `REMOVE_PREDICATE_NEGATION`, `REMOVE_PREDICATE_AND/OR`
- Reactive: Swap between `concatMap`/`flatMap`/`switchMap` variants
- `EXTREME` - Remove entire method bodies with hardcoded returns

**Key Innovation:** Subsumption analysis to remove mutants always killed by the same tests.

#### cargo-mutants - Rust
- **Repository:** https://github.com/sourcefrog/cargo-mutants
- **Documentation:** https://mutants.rs/
- **Status:** Actively maintained (Rustconf 2024 talk)

**Mutation Operators:**
- Binary: `==`/`!=`, `&&`/`||`
- Comparison: `<` to `==`/`>`, `>` to `==`/`<`, `<=` to `>`, `>=` to `<`
- Arithmetic: `+` to `-`/`*`
- Function return value replacement

**Features:**
- Works with unmodified Rust trees
- Incremental builds per mutation
- Supports cargo test and cargo nextest
- CI integration with incremental PR testing

#### mutmut - Python
- **Repository:** https://github.com/boxed/mutmut
- **Documentation:** https://mutmut.readthedocs.io/
- **Status:** Recommended Python tool (MutPy/mutatest abandoned)

**Mutation Operators:**
- Mathematical: `+` to `-`, `>` to `>=`
- Logical: `and` to `or`, vice versa
- Statement deletion: `x += 1` to `None`
- Constant replacement: integers incremented by 1
- Unary insertion: `x` to `-x`

**Features:**
- Remembers work done - incremental runs
- coverage.py integration for line-level mutation targeting
- Configurable test stack depth limits
- Pattern matching for selective mutation

**Note:** Requires fork() support (WSL on Windows).

#### Infection - PHP
- **Repository:** https://github.com/infection/infection
- **Documentation:** https://infection.github.io/guide/mutators.html

**Mutation Categories:**
- Function Signature: `PublicVisibility`, `ProtectedVisibility` changes
- Unwrap Functions: Remove array/string function calls
- Binary Arithmetic: All operators and assignment variants
- Boolean Logic: `&&`/`||`, true/false swaps
- Conditional Boundaries: Comparison operator mutations
- Return Values: Negation, object removal, function call removal
- Removal: Array items, function calls, catch blocks
- Loop Control: break/continue/foreach/while mutations
- Type Casting: Cast removal
- Extensions: BCMath and MBString specializations

### 1.3 Clojure-Specific Tools

#### mutant (jstepien/mutant)
- **Repository:** https://github.com/jstepien/mutant
- **Status:** ARCHIVED (June 5, 2025) - "wildly experimental"

**How it works:**
- Traverses namespaces introducing changes to sources
- Runs test suite after each mutation
- Reports surviving mutations

**Implemented Operators:**

| Operator | Transformation |
|----------|----------------|
| `and-or` | `(and a b)` ↔ `(or a b)` |
| `gt-gte` | `<=` ↔ `<` |
| `lt-lte` | `>=` ↔ `>` |
| `eq-noteq` | `=` ↔ `not=` |
| `empty?-seq` | `empty?` ↔ `seq` |
| `not-boolean` | `boolean` ↔ `not` |
| `for-doseq` | `for` → `doseq` (lazy vs side-effect) |
| `random-keyword` | `:foo` ↔ `:bar` |
| `rm-args` | Remove function arguments |
| `rm-fn-body` | Remove function body expressions |

**Usage:**
```clojure
;; Add to project.clj
:plugins [[lein-mutate "VERSION"]]

;; Run
$ lein trampoline mutate
```

#### mutcl
- **Repository:** https://github.com/ds2643/mutcl
- **Status:** Abandoned
- **Approach:** Exploits homoiconicity to traverse code as nested lists

### 1.4 Why Clojure Mutation Testing is Challenging

1. **Homoiconicity Complexity**
   - Code is data (lists, symbols, vectors)
   - Makes identifying "semantic mutation" vs "syntactic noise" harder
   - What constitutes a meaningful change?

2. **Macro Expansion Issues**
   - Macros expand at compile time
   - Mutations to macro inputs may not produce meaningful code changes
   - `do` form wrapping complicates incremental analysis
   - Macro hygiene (auto-gensym with `#`) adds complexity

3. **Dynamic Code Reloading**
   - REPL-driven development
   - Namespace reloading interactions
   - Hot code swapping affects mutation isolation

4. **Bytecode vs Source Mismatch**
   - PIT can mutate Clojure bytecode
   - Produces "junk mutations" that don't map to source
   - PIT explicitly notes non-useful mutants for unsupported languages

5. **Immutability**
   - Many traditional operators (modify mutable state, remove side effects) don't apply
   - Need functional-programming-aware operators

### 1.5 The Clojure Gap

**Current state: No actively maintained Clojure mutation testing tool exists.**

| Tool | Status | Last Activity |
|------|--------|---------------|
| mutant | Archived | 2025 (marked experimental) |
| mutcl | Abandoned | Years ago |
| PIT on Clojure | Works but poor | Junk mutations |

This represents a significant opportunity for Heretic.

---

## 2. AI-Powered Mutation Testing

### 2.1 The Limitation of Traditional Approaches

Research on mutation operator effectiveness reveals a critical insight:

**"How Closely are Common Mutation Operators Coupled to Real Faults?"** (Chalmers, 2023)

Study of 32,002 mutants vs 144 real faults:
- **9.92%** of mutants are strongly coupled to real faults
- **51.03%** of faults have at least one strongly coupled mutant
- **~49% of real faults have NO strongly coupled mutant**

Traditional mutation operators miss almost half of real-world bugs.

### 2.2 Most/Least Effective Traditional Operators

**Most Effective (highest coupling to real faults):**
- EMM (Expression Mutation)
- ASRS (Assignment Short-cut Replacement)
- ISD (Instance/Static Deletion)
- COI (Conditional Operator Insertion)
- PRVO (Parameter Variable Replacement)

**Least Effective:**
- ISI, JTI, AMC, OAN, LVR
- Mostly produce compilation errors or uncoupled mutants

### 2.3 AI/LLM Mutation Testing Research

#### muBERT / mBERTa (2022)
- **Paper:** https://arxiv.org/abs/2203.03289
- **Repository:** https://github.com/rdegiovanni/mbert

**Approach:**
- Uses CodeBERT to generate mutants via masked language modeling
- Masks tokens and uses CodeBERT to predict replacements

**Results on Defects4J:**
- Detected 27/40 faults vs PiTest's 26
- **2x more cost-effective** than PiTest when analyzing same number of mutants

#### MuTAP (2024)
- **Paper:** https://www.sciencedirect.com/science/article/abs/pii/S0950584924000739
- **Repository:** https://github.com/ExpertiseModel/MuTAP

**Approach:**
- Mutation Test case generation using Augmented Prompts
- Augments prompts with surviving mutants to generate more effective test cases

**Results:**
- **93.57% average mutation score**
- Outperforms Pynguin and conventional zero-shot/few-shot approaches

#### LLMorpheus (2024)
- **Paper:** https://www.franktip.org/pubs/llmorpheus2024.pdf
- **Authors:** Frank Tip, Jonathan Bell, Max Schäfer

**Approach:**
- Uses LLMs to generate mutants
- Leverages temperature parameter for creativity
- Explores different prompt templates

#### LLMut (2024)
- **Paper:** https://arxiv.org/html/2406.09843v4

**Approach:**
- Structured prompt engineering: Instruction, Context, Input Data, Output Indicator
- Compares LLM-generated mutations with existing approaches for behavior similarity with real bugs

### 2.4 Industrial AI Mutation Testing

#### Meta ACH (Automated Compliance Hardening) - 2025
- **Paper:** https://arxiv.org/abs/2501.12862
- **Blog:** https://engineering.fb.com/2025/02/05/security/revolutionizing-software-testing-llm-powered-bug-catchers-meta-ach/

**Key Innovation:**
- **First large-scale industrial deployment** of LLM mutation + LLM test generation
- Combines LLM-based mutant generation with LLM-based test generation
- Generates mutants representing realistic concerns (vs rule-based approaches)
- Automatically generates tests to catch the injected faults

**Scale & Results (from arxiv paper):**
- Applied to 10,795 Android Kotlin classes across 7 Meta platforms
- Generated 9,095 mutants and 571 privacy-focused test cases
- **73% acceptance rate** for generated tests at Messenger and WhatsApp
- 36% of accepted tests judged as directly privacy-relevant

**LLM Equivalent Mutant Detection:**
- Achieved 0.79 precision and 0.47 recall baseline
- With preprocessing: **0.95 precision and 0.96 recall**
- Preprocessing normalizes code before LLM analysis

**Key Techniques for Heretic (Phase 4):**
1. **Hybrid equivalent detection**: Static patterns (cheap) + LLM fallback (accurate)
2. **LLM test generation**: Generate tests to kill survivors (close the loop)
3. **Domain-specific mutations**: Target specific concerns (privacy, security, nil-safety)
4. **Human-in-the-loop**: Present generated tests for review before commit

#### DeepMind CodeMender (2025)
- **Blog:** https://deepmind.google/blog/introducing-codemender-an-ai-agent-for-code-security/

**Approach:**
- AI agent for automated security vulnerability detection and repair
- Uses differential testing, fuzzing, and LLM-based critique for validation
- All patches reviewed by humans before upstream submission

### 2.5 Self-Supervised Bug Generation

#### DeepMutants (2021)
- **Paper:** https://arxiv.org/abs/2107.06657

**Approach:**
- Uses masked language model to produce context-dependent token replacements
- Addresses the lack of realistic defective training examples
- Sampling from language models produces mutants that more accurately represent real bugs

#### BugLab (Microsoft Research)
- **Blog:** https://www.microsoft.com/en-us/research/blog/finding-and-fixing-bugs-with-deep-learning/

**Approach:**
- Self-supervised "hide and seek" game (inspired by GANs)
- Bug selector model decides whether/where/how to introduce a bug
- Bug detector model tries to find and fix the bugs
- Both models jointly trained without labeled data

### 2.6 Higher-Order Mutation Testing

**Reference:** "Higher Order Mutation Testing: A Systematic Literature Review" (2016)
- https://www.sciencedirect.com/science/article/abs/pii/S1574013716301095

Higher-order mutation applies multiple first-order mutations to create complex mutants.

**Benefits:**
- Reduces equivalent mutants
- Simulates more realistic (complex) faults
- Reduces total mutant count while maintaining quality
- Captures partial fault masking

**Key Concept - Subsuming HOMs:**
Higher-order mutants harder to kill than their component first-order mutants. These represent subtle fault combinations.

**Effectiveness:**
- SHOM achieves 8-38% higher strong mutation adequacy than first-order approaches
- SSHOTs (Strongly Subsuming Higher Order Tuples) optimize DL mutation testing

**Challenge:** The space of possible higher-order mutants grows combinatorially. Search-based techniques find "interesting" mutants.

### 2.7 Equivalent Mutant Detection

**The Problem:** Equivalent mutants are semantically identical to the original program and can never be killed. Detecting them is undecidable in general.

**Paper:** "Large Language Models for Equivalent Mutant Detection" (ISSTA 2024)
- https://arxiv.org/html/2408.01760v1

**Techniques:**

| Technique | Description | Effectiveness |
|-----------|-------------|---------------|
| Trivial Compiler Equivalence (TCE) | Compare compiled bytecode | 30% C, 54% Java |
| Machine Learning | Bi-GRU, LSTM, RNN classifiers | >90% accuracy |
| Fine-tuned Code Embeddings | UniXCoder | 1.16-78.85% F1 improvement |
| Static Analysis | Program equivalence heuristics | Limited scope |
| Genetic Algorithms | Co-evolve test cases and mutants | Experimental |

### 2.8 Clojure Equivalent Mutant Patterns (Implemented)

Heretic implements static pattern detection for Clojure-specific equivalent mutants. These patterns are detected at AST level before any test execution, saving significant time.

#### 2.8.1 Boundary Comparison Patterns

Mutations that produce tautologies or contradictions based on function return type guarantees:

| Pattern | Why Equivalent | Example |
|---------|----------------|---------|
| `(>= (count x) 0)` | `count` always returns non-negative | Always true |
| `(< (count x) 0)` | `count` always returns non-negative | Always false |
| `(neg? (count x))` | `count` always returns non-negative | Always false |
| `(>= (.length s) 0)` | `.length` always returns non-negative | Always true |
| `(>= (Math/abs x) 0)` | `Math/abs` always returns non-negative | Always true |

#### 2.8.2 Multiply-by-Zero Pattern

| Pattern | Why Equivalent |
|---------|----------------|
| `(* x 0)` → `0` | Multiplication by zero always equals zero |
| `(* 0 x)` → `0` | Commutative property |

#### 2.8.3 Function Contract Patterns

Based on Clojure function return type guarantees:

| Pattern | Contract | Why Equivalent |
|---------|----------|----------------|
| `(nil? (str x))` | `str` never returns nil | Always false |
| `(nil? (vec x))` | `vec` never returns nil | Always false |
| `(nil? (name k))` | `name` never returns nil | Always false |
| `(nil? (count x))` | `count` never returns nil | Always false |
| `(neg? (count x))` | `count` always ≥ 0 | Always false |

Functions that never return nil: `str`, `vec`, `vector`, `set`, `hash-set`, `list`, `count`, `inc`, `dec`, `name`, `keyword`, `symbol`, `namespace`

#### 2.8.4 Lazy/Eager Equivalences

In certain contexts, lazy and eager variants produce identical results:

| Context | Equivalent Pairs | Why |
|---------|-----------------|-----|
| Inside `vec`/`into` | `(vec (map f xs))` ≡ `(vec (mapv f xs))` | Realization forced |
| Inside `doall`/`dorun` | `(doall (map f xs))` ≡ `(doall (mapv f xs))` | Realization forced |
| Inside `count` | `(count (map f xs))` ≡ `(count (mapv f xs))` | Realization forced |
| Inside `str` | `(str (map f xs))` ≡ `(str (mapv f xs))` | Realization forced |
| Inside `reduce` | `(reduce f init (map g xs))` ≡ `(reduce f init (mapv g xs))` | Realization forced |

Realizing functions: `vec`, `into`, `count`, `doall`, `dorun`, `str`, `apply`, `reduce`, `frequencies`, `group-by`, `sort`, `sort-by`

#### 2.8.5 Collection Literal Patterns

| Pattern | Why Equivalent |
|---------|----------------|
| `(empty? [])` | Empty vector literal is always empty |
| `(seq [])` | Empty vector literal always returns nil from seq |
| `(first [x])` → `x` | Single-element vector, first always returns that element |
| `(last [x])` → `x` | Single-element vector, last always returns that element |
| `(count [])` → `0` | Empty literal always has count 0 |

#### 2.8.6 Threading Macro Equivalences

| Pattern | Why Equivalent |
|---------|----------------|
| `(-> x f)` ≡ `(f x)` | Single-arity function, threading is identity transform |
| `(-> x (f))` ≡ `(f x)` | Parenthesized single function call |
| `(->> x f)` ≡ `(f x)` | Thread-last with single-arity is same as thread-first |

Only applies when `f` is a known single-arity function: `inc`, `dec`, `str`, `keyword`, `symbol`, `name`, `not`, `nil?`, `some?`, `first`, `rest`, `next`, `last`, `count`, `vec`, `set`, `seq`, `vals`, `keys`

#### 2.8.7 Nil/Some Swap in Negation Context

| Pattern | Why Equivalent |
|---------|----------------|
| `(not (nil? x))` ≡ `(not (not (some? x)))` | Double negation with opposite predicates |
| `(not (some? x))` ≡ `(not (not (nil? x)))` | Double negation with opposite predicates |

### 2.9 Key Insight: AI's Advantage

Traditional mutation: Syntactic changes (swap `+` for `-`)

AI mutation: **Semantic changes that understand intent**

```clojure
;; Original
(defn apply-discount [price user]
  (if (:premium user)
    (* price 0.8)
    price))

;; Traditional mutation: * -> /
(defn apply-discount [price user]
  (if (:premium user)
    (/ price 0.8)  ; Obviously wrong
    price))

;; AI mutation: Inverts business logic
(defn apply-discount [price user]
  (if (:premium user)
    (* price 1.2)  ; Premium users pay MORE - subtle, compiles, wrong
    price))
```

AI can:
- Read function names and docstrings
- Understand domain intent
- Generate plausible-but-wrong implementations
- Create bugs developers actually make

---

## 3. Clojure-Specific Patterns

### 3.1 Deterministic Mutation Operators

These can be implemented without AI:

#### Boolean/Logic (High Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(and a b)` | `(or a b)` | Logic inversion |
| `(or a b)` | `(and a b)` | Logic inversion |
| `(= a b)` | `(not= a b)` | Equality flip |
| `(not= a b)` | `(= a b)` | Equality flip |
| `true` | `false` | Boolean literal |
| `false` | `true` | Boolean literal |
| `(not x)` | `x` | Remove negation |
| `x` | `(not x)` | Add negation |

#### Comparison (High Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(< a b)` | `(<= a b)` | Boundary |
| `(<= a b)` | `(< a b)` | Boundary |
| `(> a b)` | `(>= a b)` | Boundary |
| `(>= a b)` | `(> a b)` | Boundary |
| `(pos? x)` | `(neg? x)` | Sign check |
| `(neg? x)` | `(pos? x)` | Sign check |
| `(zero? x)` | `(pos? x)` | Zero check |

#### Collection (High Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(first coll)` | `(last coll)` | Endpoints |
| `(last coll)` | `(first coll)` | Endpoints |
| `(rest coll)` | `(next coll)` | Empty vs nil return |
| `(next coll)` | `(rest coll)` | Empty vs nil return |
| `(take n coll)` | `(drop n coll)` | Keep vs discard |
| `(drop n coll)` | `(take n coll)` | Keep vs discard |
| `(take-while p coll)` | `(drop-while p coll)` | Predicate boundary |
| `(conj coll x)` | `coll` | Remove addition |
| `(cons x coll)` | `coll` | Remove prepend |

#### Nil/Empty (High Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(seq coll)` | `(empty? coll)` | Truthiness flip |
| `(empty? coll)` | `(seq coll)` | Truthiness flip |
| `(nil? x)` | `(some? x)` | Nil check flip |
| `(some? x)` | `(nil? x)` | Nil check flip |
| `(when (seq coll) ...)` | `(when coll ...)` | Nil punning |
| `(or x default)` | `x` | Remove default |

#### Threading (Medium Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(-> x f g)` | `(->> x f g)` | Thread position |
| `(->> x f g)` | `(-> x f g)` | Thread position |
| `(some-> x f)` | `(-> x f)` | Remove nil safety |
| `(some->> x f)` | `(->> x f)` | Remove nil safety |
| `(cond-> x test f)` | `(-> x f)` | Remove conditional |

#### Keywords (Medium Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `:user/id` | `:users/id` | Namespace plural |
| `:user/id` | `:user-id` | Remove namespace |
| `::id` | `:id` | Auto-qualified to plain |
| `:user-id` | `:userId` | Naming convention |
| `:user-id` | `:user_id` | Naming convention |

#### Lazy/Eager (Medium Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(map f coll)` | `(mapv f coll)` | Lazy to eager |
| `(mapv f coll)` | `(map f coll)` | Eager to lazy |
| `(filter p coll)` | `(filterv p coll)` | Lazy to eager |
| `(doall (map ...))` | `(map ...)` | Remove realization |
| `(into [] (map f) coll)` | `(map f coll)` | Transducer to lazy |

#### Higher-Order Functions (Medium Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(map f coll)` | `(filter f coll)` | When f returns truthy |
| `(filter p coll)` | `(remove p coll)` | Predicate inversion |
| `(remove p coll)` | `(filter p coll)` | Predicate inversion |
| `(reduce f init coll)` | `(reduce f coll)` | Remove initial value |
| `(map f coll)` | `(mapcat f coll)` | Flatten results |

#### Arity (Low Priority)

| Original | Mutation | Notes |
|----------|----------|-------|
| `(f a b c)` | `(f a c b)` | Swap arguments |
| `(f a b c)` | `(f a b)` | Remove argument |
| `[a b & more]` | `[a b]` | Remove variadic |

### 3.2 Destructuring Mutations

Destructuring is a rich source of subtle bugs.

#### Wrong Keys

```clojure
;; Original
{:keys [user-id name]}

;; Mutations
{:keys [userId name]}           ; camelCase
{:keys [user_id name]}          ; snake_case
{:keys [user-id]}               ; Missing key
{:keys [user-id name email]}    ; Extra key (silent nil)
```

#### Wrong Namespace

```clojure
;; Original
{:user/keys [id name]}

;; Mutations
{:users/keys [id name]}         ; Plural namespace
{:keys [id name]}               ; Missing namespace
{::keys [id name]}              ; Auto-qualified
```

#### Wrong Nesting

```clojure
;; Data: {:data {:user {:id 1}}}

;; Original
{{:keys [user]} :data}

;; Mutations
{:keys [user]}                  ; Wrong level
{{:keys [id]} :data}            ; Skip intermediate
{:keys [data]}                  ; Don't destructure nested
```

#### :or Default Issues

```clojure
;; Original
{:keys [port] :or {port 8080}}

;; Mutations
{:keys [port] :or [port 8080]}  ; Vector instead of map
{:keys [port]}                  ; Missing default
{:keys [port] :or {ports 8080}} ; Wrong key in :or
```

### 3.3 Nil Punning Issues

From Eric Normand's analysis of nil punning:

| Operation | On nil | On empty | Mutation Risk |
|-----------|--------|----------|---------------|
| `(conj nil x)` | Creates list `(x)` | Depends on type | Type confusion |
| `(first nil)` | Returns nil | Returns nil | Safe |
| `(rest nil)` | Returns `()` | Returns `()` | Safe |
| `(str nil)` | Returns "" | N/A | Silent coercion |
| `(count nil)` | Returns 0 | Returns 0 | Conflation |

**Dangerous nil-punning mutations:**

```clojure
;; Original (safe)
(when (seq users)
  (map :name users))

;; Mutation (unsafe - NPE on some operations)
(when users
  (map :name users))

;; Original (handles nil)
(or (:value m) default)

;; Mutation (false/nil confusion)
(if (:value m) (:value m) default)
```

### 3.4 Threading Macro Confusion

| Pattern | Issue | Mutation |
|---------|-------|----------|
| `(-> coll (map f))` | Wrong position | Should be `->>` |
| `(->> m (get :key))` | Wrong position | Should be `->` |
| `(-> x #(str %))` | Missing parens | Need `((fn [x] ...))` |
| `(-> x f g)` | No nil handling | Change to `some->` |

### 3.5 Common Production Bugs

From clj-kondo and Eastwood linter patterns:

| Category | Bug Type | Example |
|----------|----------|---------|
| **Arity** | Wrong arg count | `(max)` - needs at least one |
| **Logic** | Constant test | `(if true ...)` |
| **Data** | Duplicate map keys | `{:a 1 :a 2}` |
| **Scope** | Unused bindings | `(let [x 1] 2)` |
| **Scope** | Shadowed vars | Local shadows global |
| **Structure** | Missing test assertions | Test with no `is` |
| **Exception** | Swallowed exception | `(catch Exception e nil)` |

### 3.6 Anti-Patterns to Mutate Into

| Anti-Pattern | Description | How to Mutate |
|--------------|-------------|---------------|
| Swallowing exceptions | `(catch Exception e nil)` | Remove error handling |
| Overcatching | Catch `Exception` vs specific | Widen catch scope |
| Global atom state | Singleton atoms | Add shared state |
| Side effects in lazy | Non-deterministic | Add side effects in map |
| Misordered pipeline | Filter after map | Swap operation order |

---

## 4. Technical Foundation

### 4.1 Code Transformation with rewrite-clj

**Repository:** https://github.com/clj-commons/rewrite-clj
**Documentation:** https://cljdoc.org/d/rewrite-clj/rewrite-clj/

rewrite-clj is the standard library for Clojure source code transformation.

#### Core Concepts

- **Nodes:** Represent code elements (symbols, lists, vectors, whitespace)
- **Zippers:** Navigate and edit the code tree
- **Preserves formatting:** Whitespace and comments maintained

#### Basic Operations

```clojure
(require '[rewrite-clj.zip :as z])

;; Parse source code
(def zloc (z/of-string "(defn f [x] (+ x 1))"))

;; Navigate
(z/down zloc)      ; Move into list
(z/right zloc)     ; Move to next sibling
(z/up zloc)        ; Move to parent
(z/next zloc)      ; Depth-first traversal

;; Find
(z/find-value zloc z/next 'defn)  ; Find symbol
(z/find-tag zloc z/next :vector)  ; Find by node type

;; Edit
(z/replace zloc 'new-symbol)      ; Replace current node
(z/edit zloc inc)                 ; Apply function
(z/remove zloc)                   ; Remove current node

;; Output
(z/root-string zloc)              ; Get modified source
```

#### Mutation Example

```clojure
(require '[rewrite-clj.zip :as z])

(defn mutate-plus-to-minus [source]
  (-> (z/of-string source)
      (z/find-value z/next '+)
      (z/replace '-)
      z/root-string))

(mutate-plus-to-minus "(defn f [x] (+ x 1))")
;; => "(defn f [x] (- x 1))"
```

#### Walking for Systematic Mutation

```clojure
(defn replace-all-plus [source]
  (-> (z/of-string source)
      (z/postwalk
        (fn select [zloc] (= '+ (z/sexpr zloc)))
        (fn visit [zloc] (z/replace zloc '-)))
      z/root-string))
```

#### Finding Mutation Points

```clojure
(defn find-mutation-points [source operator]
  (loop [zloc (z/of-string source)
         points []]
    (if (z/end? zloc)
      points
      (recur (z/next zloc)
             (if (= operator (z/sexpr zloc))
               (conj points {:position (z/position zloc)
                            :form (z/string zloc)})
               points)))))
```

### 4.2 Static Analysis Integration

#### clj-kondo

Fast static analyzer that can validate mutants compile:

```clojure
;; Use clj-kondo to check mutant validity
(require '[clj-kondo.core :as kondo])

(defn valid-clojure? [source]
  (let [result (kondo/run! {:lint ["-"]
                            :config {:output {:format :edn}}}
                           {:in source})]
    (empty? (:findings result))))
```

#### Eastwood

Deep semantic analysis for post-macro-expansion checks:

```bash
lein eastwood  # Run linter
```

### 4.3 Schema-Informed Mutations

Malli and Spec schemas can guide valid mutations:

#### Using Malli Schemas

```clojure
(require '[malli.core :as m])

;; Schema defines valid structure
(def User
  [:map
   [:id :int]
   [:name :string]
   [:email [:maybe :string]]])

;; Schema-informed mutations:
;; 1. Remove required key (:id)
;; 2. Wrong type (:id as string)
;; 3. Add unexpected key
;; 4. Change optional to nil
```

#### Generating Invalid Data

```clojure
(require '[malli.generator :as mg])

;; Generate valid data
(mg/generate User)
;; => {:id 42, :name "Alice", :email "alice@example.com"}

;; For mutations, we want INVALID data that's close to valid
;; This requires custom generators or schema negation
```

### 4.4 Test Framework Integration

#### Kaocha

Modern test runner with plugin architecture:

```clojure
;; tests.edn
#kaocha/v1
{:tests [{:id :unit
          :test-paths ["test"]}]
 :plugins [:kaocha.plugin/capture-output]}
```

```clojure
;; Running tests programmatically
(require '[kaocha.api :as kaocha])

(defn run-tests []
  (kaocha/run {:tests [{:id :unit}]}))
```

#### clojure.test

Standard test framework:

```clojure
(require '[clojure.test :as t])

;; Run all tests in namespace
(t/run-tests 'my.namespace-test)

;; Run specific test
(t/test-var #'my.namespace-test/my-test)
```

---

## 5. Performance Strategies

### 5.1 Mutation Reduction

The number of possible mutants grows quickly. Strategies to reduce:

| Strategy | Description | Reduction |
|----------|-------------|-----------|
| **Selective Mutation** | Use 2-5 best operators | ~Same coverage, huge speedup |
| **Random Sampling** | Test subset of mutants | Proportional to sample |
| **Clustering** | Group similar mutants | Reduces redundancy |
| **Subsumption** | Skip mutants killed by same tests | Arcmutate approach |
| **Extreme Mutation** | Only method-level changes | Far fewer mutants |

**Research Finding:** Selective mutation (using 2-5 operators) achieves almost the same mutation score as full mutation with significant cost reduction.

### 5.2 Incremental Testing

Only re-test what changed:

```
Full mutation testing time: 100%
Incremental (after changes): 5-20%
```

Strategies:
- Track which tests cover which code
- Only mutate changed functions
- Cache test results for unchanged mutants

### 5.3 Parallel Execution

```
┌─────────────┐
│ Mutant Pool │
└──────┬──────┘
       │
   ┌───┴───┐
   ▼       ▼
┌─────┐ ┌─────┐
│ W1  │ │ W2  │  ... Workers
└─────┘ └─────┘
   │       │
   └───┬───┘
       ▼
┌─────────────┐
│   Results   │
└─────────────┘
```

Each worker:
1. Takes a mutant from pool
2. Applies mutation
3. Runs relevant tests
4. Reports killed/survived
5. Repeats

### 5.4 Early Termination

Stop testing a mutant as soon as any test fails:

```clojure
(defn test-mutant [mutant tests]
  (reduce
    (fn [_ test]
      (if (test-fails? test mutant)
        (reduced :killed)
        :survived))
    :survived
    tests))
```

### 5.5 Equivalent Mutant Detection

Avoid wasting time on mutants that are semantically identical to the original:

1. **Compile-time detection:** Compare bytecode/AST
2. **Heuristic detection:** Known equivalent patterns
3. **ML-based detection:** Train classifier on labeled mutants

### 5.6 Subsumption Analysis (Implemented)

Subsumption analysis identifies redundant mutants where killing one implies killing others. This significantly reduces the number of mutants that need testing.

#### 5.6.1 RORG Schema - Relational Operator Replacement with Guard

Research shows that full operator replacement produces many redundant mutants. The RORG schema defines minimal operator sets that achieve the same fault detection with fewer mutants.

**Key insight:** For each relational operator, only 2-3 mutations are needed instead of all 5 possible replacements.

**Relational Operator Subsumption Table:**

| Original | Minimal Mutations | Reduction |
|----------|------------------|-----------|
| `<` | `<=`, `not=`, `false` | 3 instead of 5 (40%) |
| `>` | `>=`, `not=`, `false` | 3 instead of 5 (40%) |
| `<=` | `<`, `=`, `true` | 3 instead of 5 (40%) |
| `>=` | `>`, `=`, `true` | 3 instead of 5 (40%) |
| `=` | `<=`, `>=`, `false` | 3 instead of 5 (40%) |
| `not=` | `<`, `>`, `true` | 3 instead of 5 (40%) |

**Arithmetic Operator Subsumption:**

| Original | Minimal Mutations |
|----------|------------------|
| `+` | `-` only |
| `-` | `+` only |
| `*` | `/` only |
| `/` | `*` only |

**Boolean Operator Subsumption:**

| Original | Minimal Mutations |
|----------|------------------|
| `and` | `or`, first-operand, second-operand |
| `or` | `and`, first-operand, second-operand |

#### 5.6.2 Dominator Mutant Selection

A **dominator mutant** is one with a minimal kill set - no other mutant is harder to kill.

**Definition:** Mutant A dominates mutant B if A's kill set ⊆ B's kill set.
- A is harder to kill (fewer tests can kill it)
- Any test that kills A also kills B
- Testing only dominators is sufficient

**Algorithm:**
```
For each mutant M:
  M is a dominator if no other mutant has a kill set
  that is a proper subset of M's kill set
```

**Example:**
```
Mutant 0: killed by tests {t1, t2}      → DOMINATOR (minimal)
Mutant 1: killed by tests {t1, t2, t3}  → Dominated by 0
Mutant 2: killed by tests {t3}          → DOMINATOR (minimal)
```

Testing only mutants 0 and 2 is sufficient - if they survive, mutant 1 would also survive.

**Typical Reduction:** 30-50% fewer mutants to test while maintaining full coverage.

#### 5.6.3 Kill Matrix Mode

For calibration runs, build a complete kill matrix tracking which tests kill which mutants:

```
           Test1  Test2  Test3
Mutant0      ✓      ✓
Mutant1      ✓      ✓      ✓
Mutant2                    ✓
```

**Uses:**
1. Compute dominator relationships accurately
2. Identify test effectiveness (which tests kill most mutants)
3. Find redundant tests (tests that kill only already-killed mutants)
4. Enable incremental analysis with killed-by tracking

#### 5.6.4 Enhanced Incremental Analysis

Skip mutation re-testing when results can be inferred from previous runs:

| Condition | Action | Reason |
|-----------|--------|--------|
| No previous result | Must test | No history |
| Source file changed | Must test | Mutation may behave differently |
| Killer test unchanged | Skip (infer killed) | Same test would kill again |
| All covering tests unchanged | Skip (infer survived) | Same tests would still not detect |
| Previous timeout, source unchanged | Skip (infer timeout) | Same performance issues |

**Implementation:**
```clojure
(can-skip-mutation? mutation history changed-tests changed-files)
;; Returns {:skip true/false :reason :keyword :inferred-status :status}
```

**Typical Savings:** 60-80% of mutations can be skipped on incremental runs when only a few files change.

---

## 6. Heretic Architecture

### 6.1 Design Principles

1. **Hybrid Approach:** Fast deterministic mutations + smart AI mutations
2. **Clojure-Native:** Source-level, not bytecode
3. **Schema-Aware:** Use Malli/Spec to guide mutations
4. **Incremental:** Only re-test what's needed
5. **Pluggable:** Easy to add new mutation operators

### 6.2 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Heretic                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────┐     ┌──────────────┐     ┌─────────────┐  │
│  │ Code Parser │ ──▶ │  Mutators    │ ──▶ │ Test Runner │  │
│  │ (rewrite-clj)│     │              │     │ (kaocha)    │  │
│  └─────────────┘     └──────────────┘     └─────────────┘  │
│                             │                               │
│                      ┌──────┴──────┐                       │
│                      ▼             ▼                       │
│                Deterministic   AI-powered                  │
│                (fast, cheap)   (slow, smart)               │
│                                                              │
│  ┌─────────────┐     ┌──────────────┐     ┌─────────────┐  │
│  │ Schema      │     │ Equivalent   │     │ Reporter    │  │
│  │ Analyzer    │     │ Detector     │     │             │  │
│  └─────────────┘     └──────────────┘     └─────────────┘  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **Code Parser** | Parse source with rewrite-clj, identify mutation points |
| **Deterministic Mutators** | Rule-based mutations (fast, predictable) |
| **AI Mutators** | LLM-powered semantic mutations |
| **Schema Analyzer** | Extract Malli/Spec schemas to guide mutations |
| **Test Runner** | Execute tests against mutants, report results |
| **Equivalent Detector** | Filter out semantically identical mutants |
| **Reporter** | Generate reports (CLI, HTML, CI integration) |

### 6.4 Mutation Pipeline

```
Source Code
    │
    ▼
┌─────────────────┐
│ 1. Parse        │  rewrite-clj → zipper
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 2. Analyze      │  Find mutation points, extract schemas
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 3. Generate     │  Create mutant candidates
│    Mutants      │  (deterministic first, then AI)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 4. Filter       │  Remove equivalent mutants
│    Equivalents  │  Remove invalid syntax
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 5. Test         │  Run test suite against each mutant
│    Mutants      │  (parallel, early termination)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 6. Report       │  Killed vs survived, mutation score
└─────────────────┘
```

### 6.5 API Sketch

```clojure
(ns heretic.core)

;; Main entry point
(defn mutate
  "Run mutation testing on the given namespaces."
  [opts]
  ;; opts: {:namespaces [...], :mutators [...], :test-runner ...}
  )

;; Configuration
(def default-mutators
  [:boolean-logic
   :comparison-boundary
   :collection-operations
   :nil-handling
   :threading-macros])

;; Mutator protocol
(defprotocol Mutator
  (can-mutate? [this zloc] "Can this mutator handle this location?")
  (mutations [this zloc] "Generate mutations for this location"))

;; AI mutator interface
(defprotocol AIMutator
  (generate-mutations [this context]
    "Generate semantic mutations using AI.
     context: {:source-code, :function-name, :docstring, :schemas}"))
```

### 6.6 AI Integration Design

```clojure
(defn ai-mutate [context]
  (let [prompt (build-prompt context)
        response (call-llm prompt)]
    (parse-mutations response)))

(defn build-prompt [{:keys [source-code function-name docstring schemas]}]
  (str "You are a mutation testing expert. "
       "Given this Clojure function, generate subtle bugs that:\n"
       "1. Compile and run without errors\n"
       "2. Represent realistic developer mistakes\n"
       "3. Change the behavior in ways tests might miss\n\n"
       "Function: " function-name "\n"
       "Docstring: " docstring "\n"
       "Schemas: " schemas "\n"
       "Source:\n```clojure\n" source-code "\n```\n\n"
       "Generate 3-5 mutated versions with explanations."))
```

### 6.7 Reporting

```
Heretic Mutation Testing Report
================================

Namespaces tested: 5
Total mutants: 127
Killed: 98 (77.2%)
Survived: 24 (18.9%)
Equivalent: 5 (3.9%)

Survived Mutants (weak test coverage):
--------------------------------------
1. src/myapp/auth.clj:42
   Original:  (if (:premium user) (* price 0.8) price)
   Mutant:    (if (:premium user) (* price 0.9) price)
   Mutator:   :numeric-literal

2. src/myapp/data.clj:87
   Original:  {:keys [user-id name]}
   Mutant:    {:keys [userId name]}
   Mutator:   :destructuring-keys

...
```

---

## 8. Phase 3 Implementation Learnings

This section captures practical insights from implementing Phase 3 that inform future development.

### 8.1 Concurrency Model

Missionary's structured concurrency proved superior to ExecutorService for mutation testing:

| Aspect | ExecutorService | Missionary |
|--------|-----------------|------------|
| Cancellation | Manual, error-prone | First-class, propagates |
| Supervision | Must implement | Built-in policies |
| Composition | Callback hell | Clean `m/sp`, `m/ap` |
| File parallelism | Thread pools | `m/ap` + `m/amb=` |

**Key pattern:** File-level parallelism uses `m/ap` with `m/amb=` to create N parallel lanes pulling from a shared flow of file groups. Each lane processes files sequentially within, avoiding mutation conflicts.

```clojure
;; Simplified pattern
(m/ap
  (let [file-group (m/?> (m/seed (m/amb= file-groups)))]
    (process-file-mutations file-group)))
```

### 8.2 Operator Reduction Findings

Starting with 81 operators, empirical analysis revealed:

| Preset | Operators | Use Case |
|--------|-----------|----------|
| `:minimal` | 31 | Recommended default (~99% fault detection) |
| `:fast` | 16 | Quick feedback during development |
| `:standard` | 36 | Balanced coverage |
| `:comprehensive` | 81 | Calibration runs only |

**Subsumption impact:** The formal dominance graph eliminates ~40% redundancy. For relational operators, only 2-3 mutations per operator are needed instead of all 5 replacements.

### 8.3 Functional Core / Imperative Shell Effectiveness

The pattern proved exceptionally effective:

| Module | Pure Functions | Side Effects |
|--------|---------------|--------------|
| `controller.clj` | ~95% | Config loading only |
| `clustering.clj` | 100% | None |
| `subsumption.clj` | 100% | None |
| `worker.clj` | ~60% | Execution, timeout handling |
| `core.clj` | ~20% | Entry point, orchestration |

**Benefit:** Pure modules are trivially testable. `controller.clj` has 18 tests covering all orchestration logic without mocking.

### 8.4 Mutant Schemata Trade-offs — REMOVED 2026-06-05

> **The `heretic.schemata` module was removed.** An empirical benchmark
> (`docs/spec.md` §3.4; `docs/validation-results.md` §1) measured its payoff as
> marginal — the saving is capped at ~`(2N−2)·t_reload` *independent of
> covering-test cost*, so the speedup decays from ~6× at near-zero ms/test to
> ~1.1× at 50 ms/test, while carrying a large per-site dispatch tax at high
> mutant density — and it was dead code (never wired into the worker). The
> compile-once / dynamic-var design and the former `mutations/file ≥ 3` heuristic
> no longer apply; the traditional reload-per-mutant path is the only path.

### 8.5 Clustering Strategies (Needs Validation)

Four strategies implemented but require real-world validation:

| Strategy | Grouping | Expected Reduction |
|----------|----------|-------------------|
| `:none` | No clustering | 0% |
| `:operator` | Same operator type | 20-40% |
| `:location` | Same code location | 30-50% |
| `:similarity` | Operator category + context | 40-60% |

**Open question:** Does representative selection actually work? If a representative is killed, are all cluster members truly killed? Needs empirical study.

### 8.6 Equivalent Mutant Detection Limits

Static pattern detection catches obvious cases but has limits:

| Pattern Category | Detection Rate | Examples |
|-----------------|----------------|----------|
| Boundary comparisons | High | `(>= (count x) 0)` |
| Multiply-by-zero | High | `(* x 0)` |
| Function contracts | Medium | `(nil? (str x))` |
| Lazy/eager in context | Medium | `(vec (map f xs))` vs `(vec (mapv f xs))` |
| Threading equivalence | Low | Complex pipelines |
| Semantic equivalence | None | Requires LLM |

**Phase 4 implication:** Hybrid detection (static + LLM) is essential for reducing false survivors.

### 8.7 Test Timing Variability

Real-world test execution times vary significantly:

- Same test can vary 2-5x between runs
- JIT warmup affects early tests
- GC pauses cause outliers
- Parallel execution adds contention

**Recommendation:** Use adaptive timeout (median × 3 + buffer) rather than fixed multiplier. Track timing trends across runs.

---

## 9. References

### 9.1 Foundational Papers

- Jia & Harman (2010). "An Analysis and Survey of the Development of Mutation Testing"
  - http://crest.cs.ucl.ac.uk/fileadmin/crest/sebasepaper/JiaH10.pdf

- "Mutation Testing Advances: An Analysis and Survey" (2018)
  - https://www.sciencedirect.com/science/article/abs/pii/S0065245818300305

### 9.2 Operator Effectiveness

- "Are Mutants a Valid Substitute for Real Faults?" (FSE 2014)
  - https://homes.cs.washington.edu/~mernst/pubs/mutation-effectiveness-fse2014.pdf

- "How Closely are Common Mutation Operators Coupled to Real Faults?" (Chalmers 2023)
  - https://research.chalmers.se/en/publication/536348

### 9.3 AI/LLM Mutation Testing

- muBERT (2022): https://arxiv.org/abs/2203.03289
- MuTAP (2024): https://www.sciencedirect.com/science/article/abs/pii/S0950584924000739
- LLMorpheus (2024): https://www.franktip.org/pubs/llmorpheus2024.pdf
- LLMut (2024): https://arxiv.org/html/2406.09843v4

### 9.4 Industrial Applications

- Meta ACH (2025): https://engineering.fb.com/2025/02/05/security/revolutionizing-software-testing-llm-powered-bug-catchers-meta-ach/
- DeepMind CodeMender (2025): https://deepmind.google/blog/introducing-codemender-an-ai-agent-for-code-security/

### 9.5 Bug Generation

- DeepMutants (2021): https://arxiv.org/abs/2107.06657
- BugLab (Microsoft): https://www.microsoft.com/en-us/research/blog/finding-and-fixing-bugs-with-deep-learning/

### 9.6 Equivalent Mutant Detection

- "LLMs for Equivalent Mutant Detection" (ISSTA 2024): https://arxiv.org/html/2408.01760v1

### 9.7 Higher-Order Mutation

- "Higher Order Mutation Testing: A Systematic Literature Review" (2016)
  - https://www.sciencedirect.com/science/article/abs/pii/S1574013716301095

### 9.8 Clojure Tools

- mutant (archived): https://github.com/jstepien/mutant
- rewrite-clj: https://github.com/clj-commons/rewrite-clj
- clj-kondo: https://github.com/clj-kondo/clj-kondo
- Eastwood: https://github.com/jonase/eastwood
- Malli: https://github.com/metosin/malli

### 9.9 Testing Tools

- PIT: https://pitest.org/
- Stryker: https://stryker-mutator.io/
- cargo-mutants: https://mutants.rs/
- mutmut: https://mutmut.readthedocs.io/
- Infection: https://infection.github.io/

### 9.10 Benchmarks

- Defects4J: https://github.com/rjust/defects4j
- SWE-bench: https://www.swebench.com/
- BugsInPy: https://github.com/soarsmu/BugsInPy

---

## Appendix A: Mutation Operator Quick Reference

### Deterministic Operators

| ID | Name | Example |
|----|------|---------|
| `bool-and-or` | Boolean logic swap | `and` ↔ `or` |
| `bool-eq` | Equality swap | `=` ↔ `not=` |
| `cmp-boundary` | Comparison boundary | `<` ↔ `<=` |
| `coll-endpoints` | Collection endpoints | `first` ↔ `last` |
| `coll-rest-next` | Rest vs next | `rest` ↔ `next` |
| `coll-take-drop` | Take vs drop | `take` ↔ `drop` |
| `nil-seq-empty` | Nil/empty handling | `seq` ↔ `empty?` |
| `nil-check` | Nil check | `nil?` ↔ `some?` |
| `thread-direction` | Threading direction | `->` ↔ `->>` |
| `thread-safety` | Threading nil-safety | `->` ↔ `some->` |
| `kw-namespace` | Keyword namespace | `:user/id` ↔ `:users/id` |
| `kw-qualified` | Keyword qualification | `:user/id` ↔ `:user-id` |
| `lazy-eager` | Lazy vs eager | `map` ↔ `mapv` |
| `hof-filter` | Filter direction | `filter` ↔ `remove` |
| `destruct-keys` | Destructuring keys | `user-id` ↔ `userId` |

### AI-Powered Operators

| ID | Name | Description |
|----|------|-------------|
| `ai-logic-invert` | Business logic inversion | Flip the meaning of conditions |
| `ai-edge-case` | Edge case removal | Remove boundary handling |
| `ai-semantic` | Semantic mutation | Wrong-but-plausible implementation |
| `ai-nilsafe` | Nil safety removal | Remove nil handling |

---

## Appendix B: Project Roadmap

### Phase 1: Foundation ✅ Complete
- [x] Project setup (deps.edn, directory structure)
- [x] rewrite-clj integration
- [x] Basic mutation operators (5-10)
- [x] Simple test runner integration

### Phase 2: Core Features ✅ Complete
- [x] Full deterministic operator set (25+ operators)
- [x] Parallel test execution
- [x] CLI interface
- [x] Basic reporting (HTML, JSON, EDN)

### Phase 3: Optimization ✅ Complete
- [x] **3.1 Timing & Parallel Execution**
  - [x] Test timing collection and ordering
  - [x] Dynamic timeout calculation
  - [x] Early termination on first kill
  - [x] Proven-killer prioritization
- [x] **3.2 Equivalent Mutant Detection**
  - [x] Boundary comparison patterns (count >= 0)
  - [x] Multiply-by-zero patterns
  - [x] Function contract patterns (str never nil)
  - [x] Lazy/eager equivalences in realizing context
  - [x] Collection literal patterns
  - [x] Threading macro equivalences
  - [x] Nil/some swap in negation context
- [x] **3.3 Subsumption Analysis**
  - [x] RORG operator-level subsumption tables
  - [x] Dominator mutant selection
  - [x] Kill matrix mode for calibration
  - [x] Enhanced incremental analysis with killed-by tracking
- [x] **3.4 Incremental Testing**
  - [x] Form-level hash tracking
  - [x] Change detection for source files
  - [x] Trend tracking across runs
- [x] **3.5 Reporting Enhancements**
  - [x] Trend charts in HTML reports
  - [x] Test effectiveness metrics
  - [x] Subsumption statistics

### Phase 4: AI Integration
- [ ] LLM integration layer
- [ ] Prompt engineering for mutations
- [ ] Semantic mutation generation
- [ ] Cost/quality tradeoffs

### Phase 5: Polish
- [ ] Kaocha plugin
- [ ] CI integration (GitHub Actions, etc.)
- [ ] Schema-informed mutations (Malli/Spec)
- [ ] Live dashboard

---

## Appendix C: Complete Mutation Operator Taxonomy

This appendix provides an exhaustive catalog of mutation operators across all major tools and academic research.

### C.1 Academic Foundation: Classic Operator Categories

#### Standard Classification

| Category | Operators | Description |
|----------|-----------|-------------|
| **Expression** | AOR, ROR, COR, LOR, SOR, UOI, UOD, ABS | Modify operators and operands |
| **Statement** | SDL, VDL, CDL, ODL | Delete or modify statements |
| **Replacement** | ASR, LVR, EVR | Replace values/assignments |
| **Object-Oriented** | IHI, IHD, IOD, PNC, PMD, etc. | OO-specific mutations |
| **Exception** | EFD, EHC, EHD, EHI, ETC, ETD | Exception handling |

#### Mothra Operators (22 Original Operators for Fortran)

| Operator | Name | Description |
|----------|------|-------------|
| **AAR** | Array for Array Replacement | Replace array reference with another array |
| **ABS** | Absolute Value Insertion | Wrap expression in `abs()`, `negAbs()`, or `failOnZero()` |
| **ACR** | Array for Constant Replacement | Replace array reference with constant |
| **AOR** | Arithmetic Operator Replacement | Replace `+`, `-`, `*`, `/`, `%` with each other |
| **CAR** | Constant for Array Replacement | Replace constant with array reference |
| **CNR** | Comparable Array Name Replacement | Replace array with comparable array |
| **CRP** | Constant Replacement | Replace constant with another constant |
| **CSR** | Constant for Scalar Replacement | Replace constant with scalar variable |
| **DER** | DO Statement End Replacement | Modify loop end condition |
| **DSA** | DATA Statement Alterations | Modify DATA statements |
| **GLR** | GOTO Label Replacement | Replace GOTO labels |
| **LCR** | Logical Connector Replacement | Replace `&`, `\|`, `^` with each other |
| **ROR** | Relational Operator Replacement | Replace `<`, `<=`, `>`, `>=`, `==`, `!=` |
| **RSR** | RETURN Statement Replacement | Modify RETURN statements |
| **SAN** | Statement Analysis | Analyze statement structure |
| **SAR** | Scalar for Array Replacement | Replace scalar with array reference |
| **SCR** | Scalar for Constant Replacement | Replace scalar variable with constant |
| **SDL** | Statement Deletion | Delete each executable statement |
| **SRC** | Source Constant Replacement | Replace source constant |
| **SVR** | Scalar Variable Replacement | Replace scalar with another scalar |
| **UOI** | Unary Operator Insertion | Insert `+`, `-`, `!`, `~` before expressions |

#### Five Selective Operators (Research-Proven Sufficient)

Research shows these 5 operators achieve ~99% mutation score:

1. **ABS** - Absolute Value Insertion
2. **AOR** - Arithmetic Operator Replacement
3. **LCR** - Logical Connector Replacement
4. **ROR** - Relational Operator Replacement
5. **UOI** - Unary Operator Insertion

---

### C.2 PIT (Pitest) - Java/JVM

#### Default Mutators (DEFAULTS group)

| Mutator | Description | Transformations |
|---------|-------------|-----------------|
| **CONDITIONALS_BOUNDARY** | Boundary condition change | `<` ↔ `<=`, `>` ↔ `>=` |
| **INCREMENTS** | Increment/decrement swap | `++` ↔ `--` (pre and post) |
| **INVERT_NEGS** | Negate inversion | `-i` → `i` |
| **MATH** | Arithmetic replacement | `+` ↔ `-`, `*` ↔ `/`, `%` → `*`, `&` ↔ `\|`, `^` → `&`, `<<` ↔ `>>`, `>>>` → `<<` |
| **NEGATE_CONDITIONALS** | Condition inversion | `==` ↔ `!=`, `<=` ↔ `>`, `>=` ↔ `<` |
| **VOID_METHOD_CALLS** | Void method removal | Remove void method calls |
| **EMPTY_RETURNS** | Empty return values | Return `""`, `Collections.emptyList()`, `Optional.empty()`, `0` |
| **FALSE_RETURNS** | False returns | Return `false` for booleans |
| **TRUE_RETURNS** | True returns | Return `true` for booleans |
| **NULL_RETURNS** | Null returns | Return `null` for objects |
| **PRIMITIVE_RETURNS** | Primitive zero returns | Return `0` for numeric types |

#### Optional Mutators

| Mutator | Description | Transformations |
|---------|-------------|-----------------|
| **CONSTRUCTOR_CALLS** | Constructor replacement | `new Foo()` → `null` |
| **INLINE_CONSTS** | Constant mutation | `1` → `0`, `-1` → `1`, `5` → `-1`, others: +1 |
| **NON_VOID_METHOD_CALLS** | Non-void method removal | Replace with default value |
| **REMOVE_CONDITIONALS** | Condition forcing | Force `true` or `false` |
| **REMOVE_INCREMENTS** | Increment removal | Remove `++`/`--` |

#### Experimental Operators

| Mutator | Description |
|---------|-------------|
| **EXPERIMENTAL_ARGUMENT_PROPAGATION** | Replace method call with matching parameter |
| **EXPERIMENTAL_BIG_INTEGER** | Swap BigInteger method operations (`add` ↔ `subtract`, `multiply` ↔ `divide`) |
| **EXPERIMENTAL_BIG_DECIMAL** | Swap BigDecimal method operations |
| **EXPERIMENTAL_MEMBER_VARIABLE** | Remove member initialization |
| **EXPERIMENTAL_NAKED_RECEIVER** | Replace method call with receiver object |
| **EXPERIMENTAL_SWITCH** | Replace switch labels |

#### AOR - Arithmetic Operator Replacement (4 variants)

| Variant | `+` | `-` | `*` | `/` | `%` |
|---------|-----|-----|-----|-----|-----|
| **AOR1** | `-` | `+` | `/` | `*` | `*` |
| **AOR2** | `*` | `*` | `+` | `+` | `+` |
| **AOR3** | `/` | `/` | `-` | `-` | `-` |
| **AOR4** | `%` | `%` | `%` | `%` | `/` |

#### AOD - Arithmetic Operator Deletion

| Variant | Effect |
|---------|--------|
| **AOD1** | `a + b` → `a` (first operand) |
| **AOD2** | `a + b` → `b` (second operand) |

#### CRCR - Constant Replacement (6 variants)

| Variant | Constant `c` Becomes |
|---------|---------------------|
| **CRCR1** | `1` |
| **CRCR2** | `0` |
| **CRCR3** | `-1` |
| **CRCR4** | `-c` (negation) |
| **CRCR5** | `c + 1` |
| **CRCR6** | `c - 1` |

#### ROR - Relational Operator Replacement (5 variants)

| Variant | `<` | `<=` | `>` | `>=` |
|---------|-----|------|-----|------|
| **ROR1** | `<=` | `<` | `>=` | `>` |
| **ROR2** | `>` | `>=` | `<` | `<=` |
| **ROR3** | `>=` | `>` | `<=` | `<` |
| **ROR4** | `==` | `==` | `==` | `==` |
| **ROR5** | `!=` | `!=` | `!=` | `!=` |

#### UOI - Unary Operator Insertion (4 variants)

| Variant | Variable `a` Becomes |
|---------|---------------------|
| **UOI1** | `a++` (post-increment) |
| **UOI2** | `a--` (post-decrement) |
| **UOI3** | `++a` (pre-increment) |
| **UOI4** | `--a` (pre-decrement) |

#### OBBN - Bitwise Operator Mutation

| Variant | Effect |
|---------|--------|
| **OBBN1** | `&` ↔ `\|` |
| **OBBN2** | `a & b` → `a` |
| **OBBN3** | `a & b` → `b` |

---

### C.3 Arcmutate Extensions (Commercial PIT Plugin)

#### Stream Operations

| Operator | Description | Supported Types |
|----------|-------------|-----------------|
| **REMOVE_DISTINCT** | Remove `distinct()` | Stream, Flux, RxJava |
| **REMOVE_FILTER** | Remove `filter()` | Stream, Flux, RxJava |
| **REMOVE_LIMIT** | Remove `limit()` | Stream |
| **REMOVE_SKIP** | Remove `skip()` | Stream, reactive |
| **REMOVE_SORTED** | Remove `sorted()` | Stream, reactive |

#### Predicate Operations

| Operator | Transformation |
|----------|----------------|
| **REMOVE_PREDICATE_NEGATION** | `pred.negate()` → `pred` |
| **REMOVE_PREDICATE_AND** | `p1.and(p2)` → `p1` |
| **REMOVE_PREDICATE_OR** | `p1.or(p2)` → `p1` |
| **SWAP_PREDICATE_AND** | `p1.and(p2)` → `p1.or(p2)` |
| **SWAP_PREDICATE_OR** | `p1.or(p2)` → `p1.and(p2)` |

#### Method/Parameter Operations

| Operator | Description |
|----------|-------------|
| **CHAINED_CALLS** | Remove builder pattern method calls |
| **VARARGS** | Remove last argument from varargs |
| **ONE_LESS_PARAM** | Swap for overload with fewer params |
| **SWAP_PARAMS** | Swap final two params of same type |
| **SWAP_ALL_MATCH** | `allMatch` ↔ `anyMatch` |

#### Reactive Framework Operations

| Operator | Transformation |
|----------|----------------|
| **REACTIVE_CONCATMAP_TO_FLATMAP** | `concatMap` → `flatMap` |
| **REACTIVE_CONCATMAP_TO_SWITCHMAP** | `concatMap` → `switchMap` |
| **REACTIVE_FLATMAP_TO_CONCATMAP** | `flatMap` → `concatMap` |
| **REACTIVE_FLATMAP_TO_SWITCHMAP** | `flatMap` → `switchMap` |
| **REACTIVE_SWITCHMAP_TO_CONCATMAP** | `switchMap` → `concatMap` |
| **REACTIVE_SWITCHMAP_TO_FLATMAP** | `switchMap` → `flatMap` |

#### EXTREME Operators (Method Body Removal)

| Operator | Return Value |
|----------|--------------|
| **EXTREME_VOID** | Empty method body |
| **EXTREME_NULL** | `return null;` |
| **EXTREME_BOOLEAN** | `return false;` |
| **EXTREME_ZERO** | `return 0;` |
| **EXTREME_EMPTY** | Type-appropriate empty (`""`, `Optional.empty()`, etc.) |

---

### C.4 Stryker - JavaScript/TypeScript/C#/Scala

#### Arithmetic Operators

| Operator | Before | After | JS | .NET | Scala |
|----------|--------|-------|:--:|:----:|:-----:|
| AdditionNegation | `a + b` | `a - b` | ✓ | ✓ | - |
| SubtractionNegation | `a - b` | `a + b` | ✓ | ✓ | - |
| MultiplicationNegation | `a * b` | `a / b` | ✓ | ✓ | - |
| DivisionNegation | `a / b` | `a * b` | ✓ | ✓ | - |
| RemainderToMultiplication | `a % b` | `a * b` | ✓ | ✓ | - |

#### Boolean Literals

| Operator | Before | After | JS | .NET | Scala |
|----------|--------|-------|:--:|:----:|:-----:|
| TrueNegation | `true` | `false` | ✓ | ✓ | ✓ |
| FalseNegation | `false` | `true` | ✓ | ✓ | ✓ |
| NotRemoval | `!(expr)` | `expr` | ✓ | ✓ | - |

#### Equality Operators

| Operator | Before | After | JS | .NET | Scala |
|----------|--------|-------|:--:|:----:|:-----:|
| LessThanBoundary | `<` | `<=` | ✓ | ✓ | - |
| LessThanNegation | `<` | `>=` | ✓ | ✓ | ✓ |
| GreaterThanBoundary | `>` | `>=` | ✓ | ✓ | - |
| GreaterThanNegation | `>` | `<=` | ✓ | ✓ | ✓ |
| EqualityNegation | `==` | `!=` | ✓ | ✓ | ✓ |
| StrictEqualityNegation | `===` | `!==` | ✓ | - | - |

#### Logical Operators

| Operator | Before | After | JS | .NET | Scala |
|----------|--------|-------|:--:|:----:|:-----:|
| AndNegation | `&&` | `\|\|` | ✓ | ✓ | ✓ |
| OrNegation | `\|\|` | `&&` | ✓ | ✓ | ✓ |
| NullCoalescingToAnd | `??` | `&&` | ✓ | - | - |

#### Method Expression - JavaScript

| Operator | Before | After |
|----------|--------|-------|
| EndsWithToStartsWith | `endsWith()` | `startsWith()` |
| ToUpperToLower | `toUpperCase()` | `toLowerCase()` |
| TrimToTrimStart | `trim()` | `trimStart()` |
| SortRemoval | `sort()` | (removed) |
| FilterRemoval | `filter()` | (removed) |
| SomeToEvery | `some()` | `every()` |
| MinToMax | `Math.min()` | `Math.max()` |

#### Method Expression - .NET LINQ

| Operator | Before | After |
|----------|--------|-------|
| FirstToLast | `First()` | `Last()` |
| SingleToFirst | `Single()` | `First()` |
| AllToAny | `All()` | `Any()` |
| SkipToTake | `Skip()` | `Take()` |
| MinToMax | `Min()` | `Max()` |
| SumToCount | `Sum()` | `Count()` |
| OrderByToDesc | `OrderBy()` | `OrderByDescending()` |
| AppendToPrepend | `Append()` | `Prepend()` |
| UnionToIntersect | `Union()` | `Intersect()` |

#### Method Expression - Scala

| Operator | Before | After |
|----------|--------|-------|
| FilterToFilterNot | `filter()` | `filterNot()` |
| ExistsToForall | `exists()` | `forall()` |
| TakeToDrop | `take()` | `drop()` |
| IsEmptyToNonEmpty | `isEmpty` | `nonEmpty` |
| IndexOfToLastIndexOf | `indexOf()` | `lastIndexOf()` |
| MinToMax | `min` | `max` |
| MinByToMaxBy | `minBy()` | `maxBy()` |

#### String Literals

| Operator | Before | After | JS | .NET | Scala |
|----------|--------|-------|:--:|:----:|:-----:|
| FilledToEmpty | `"foo"` | `""` | ✓ | ✓ | ✓ |
| EmptyToFilled | `""` | `"Stryker was here!"` | ✓ | ✓ | ✓ |

#### Regex Mutations (All Platforms)

| Operator | Before | After |
|----------|--------|-------|
| AnchorRemoval | `^abc$` | `abc` |
| CharClassNegation | `[abc]` | `[^abc]` |
| DigitClassNegation | `\d` | `\D` |
| WordClassNegation | `\w` | `\W` |
| WhitespaceNegation | `\s` | `\S` |
| QuantifierRemoval | `a*` | `a` |
| LookaheadNegation | `(?=abc)` | `(?!abc)` |

---

### C.5 cargo-mutants - Rust

#### Binary Operators

| Original | Replacements |
|----------|-------------|
| `==` | `!=` |
| `!=` | `==` |
| `&&` | `\|\|` |
| `\|\|` | `&&` |
| `<` | `==`, `>` |
| `>` | `==`, `<` |
| `<=` | `>` |
| `>=` | `<` |
| `+` | `-`, `*` |
| `-` | `+`, `/` |
| `*` | `+`, `/` |
| `/` | `%`, `*` |
| `%` | `/`, `+` |
| `<<` | `>>` |
| `>>` | `<<` |
| `&` | `\|`, `^` |
| `\|` | `&`, `^` |
| `^` | `&`, `\|` |

#### Assignment Operators

| Original | Replacement |
|----------|-------------|
| `+=` | `-=` |
| `-=` | `+=` |
| `*=` | `/=` |
| `/=` | `%=`, `*=` |
| `%=` | `/=` |
| `&=` | `\|=` |
| `\|=` | `&=` |
| `^=` | `\|=`, `&=` |
| `<<=` | `>>=` |
| `>>=` | `<<=` |

#### Function Body Replacement

| Return Type | Replacement Values |
|-------------|-------------------|
| `()` | `()` |
| Signed integers | `0`, `1`, `-1` |
| Unsigned integers | `0`, `1` |
| `bool` | `true`, `false` |
| `String` | `String::new()`, `"xyzzy".into()` |
| `&str` | `""`, `"xyzzy"` |
| `Option<T>` | `Some(...)`, `None` |
| `Result<T, E>` | `Ok(...)`, `Err(...)` |
| `Vec<T>` | `vec![]`, `vec![...]` |
| Collections | Empty, one-element variants |

#### Other Mutations

| Category | Description |
|----------|-------------|
| **Unary deletion** | Delete `-a`, `!a` |
| **Match arms** | Delete match arms with wildcards |
| **Match guards** | Replace with `true`/`false` |
| **Struct fields** | Delete fields in literals with `..base` |

---

### C.6 mutmut - Python

| Category | Original | Mutant |
|----------|----------|--------|
| **Numbers** | `n` | `n + 1` |
| **Strings** | `"hello"` | `"XXhelloXX"` |
| **Booleans** | `True` | `False` |
| **Assignments** | `a = x` | `a = None` |
| **Keywords** | `is` | `is not` |
| **Keywords** | `in` | `not in` |
| **Keywords** | `break` | `return` |
| **Keywords** | `continue` | `break` |
| **Operators** | `+` | `-` |
| **Operators** | `*` | `/` |
| **Operators** | `//` | `/` |
| **Operators** | `**` | `*` |
| **Operators** | `<<` | `>>` |
| **Operators** | `&` | `\|` |
| **Operators** | `and` | `or` |
| **Comparisons** | `<` | `<=` |
| **Comparisons** | `==` | `!=` |
| **Lambdas** | `lambda: x` | `lambda: None` |
| **String methods** | `lower()` | `upper()` |
| **String methods** | `lstrip()` | `rstrip()` |
| **String methods** | `find()` | `rfind()` |
| **Unary ops** | `not x` | `x` |
| **Unary ops** | `-x` | `x` |
| **Dict args** | `dict(a=b)` | `dict(aXX=b)` |
| **Arg removal** | `foo(a, b)` | `foo(None, b)` |

---

### C.7 Infection - PHP

#### Arithmetic

| Original | Mutant |
|----------|--------|
| `+` | `-` |
| `-` | `+` |
| `*` | `/` |
| `/` | `*` |
| `%` | `*` |
| `**` | `/` |
| `+=`, `-=`, `*=`, `/=`, `%=` | Swapped counterparts |

#### Boolean

| Original | Mutant |
|----------|--------|
| `true` | `false` |
| `false` | `true` |
| `&&` | `\|\|` |
| `\|\|` | `&&` |
| `and` | `or` |
| `or` | `and` |
| `!$a` | `$a` |
| `$a instanceof B` | `true` / `false` |

#### Boundaries

| Original | Mutant |
|----------|--------|
| `>` | `>=` |
| `<` | `<=` |
| `>=` | `>` |
| `<=` | `<` |

#### Negated Conditionals

| Original | Mutant |
|----------|--------|
| `==` | `!=` |
| `===` | `!==` |
| `>` | `<=` |
| `<` | `>=` |

#### Loops

| Original | Mutant |
|----------|--------|
| `break` | `continue` |
| `continue` | `break` |
| `foreach ($var as ...)` | `foreach ([] as ...)` |
| `while ($cond)` | `while (false)` |

#### Return Values

| Original | Mutant |
|----------|--------|
| `return true` | `return false` |
| `return $x` (int) | `return -$x` |
| `return $this` | `return null` |
| `return new Class()` | `new Class(); return null` |

#### Unwrap Functions (Array)

| Original | Mutant |
|----------|--------|
| `array_filter($a)` | `$a` |
| `array_map($f, $a)` | `$a` |
| `array_merge($a, $b)` | `$a` |
| `array_reverse($a)` | `$a` |
| `array_unique($a)` | `$a` |
| `array_values($a)` | `$a` |
| ... (30+ array unwrap operators) | |

#### Unwrap Functions (String)

| Original | Mutant |
|----------|--------|
| `trim($s)` | `$s` |
| `ltrim($s)` | `$s` |
| `rtrim($s)` | `$s` |
| `strtolower($s)` | `$s` |
| `strtoupper($s)` | `$s` |
| `substr($s, $o)` | `$s` |
| ... (15+ string unwrap operators) | |

---

### C.8 muJava Method-Level Operators (19)

#### Arithmetic

| Operator | Name | Transformations |
|----------|------|-----------------|
| **AORB** | Arithmetic Binary | `+` ↔ `-` ↔ `*` ↔ `/` ↔ `%` |
| **AORS** | Arithmetic Short-cut | `+=` ↔ `-=` ↔ `*=` ↔ `/=` ↔ `%=` |
| **AOIU** | Arithmetic Unary Insert | Insert `+`, `-` before numerics |
| **AOIS** | Arithmetic Short-cut Insert | Insert `++`, `--` |
| **AODU** | Arithmetic Unary Delete | Delete unary `+`, `-` |
| **AODS** | Arithmetic Short-cut Delete | Delete `++`, `--` |

#### Relational/Conditional

| Operator | Name | Transformations |
|----------|------|-----------------|
| **ROR** | Relational | `<` ↔ `<=` ↔ `>` ↔ `>=` ↔ `==` ↔ `!=` ↔ `true` ↔ `false` |
| **COR** | Conditional | `&&` ↔ `\|\|` ↔ `&` ↔ `\|` ↔ `^` |
| **COI** | Conditional Insert | Insert `!` before booleans |
| **COD** | Conditional Delete | Delete `!` |

#### Logical/Shift

| Operator | Name | Transformations |
|----------|------|-----------------|
| **LOR** | Logical | `&` ↔ `\|` ↔ `^` |
| **LOI** | Logical Insert | Insert `~` before integrals |
| **LOD** | Logical Delete | Delete `~` |
| **SOR** | Shift | `<<` ↔ `>>` ↔ `>>>` |

#### Deletion

| Operator | Name | Description |
|----------|------|-------------|
| **SDL** | Statement Deletion | Delete statements |
| **VDL** | Variable Deletion | Delete variable references |
| **CDL** | Constant Deletion | Delete constants |
| **ODL** | Operator Deletion | Replace `a op b` with `a` or `b` |
| **ASRS** | Assignment Short-cut | `+=` ↔ all assignment variants |

---

### C.9 muJava Class-Level Operators (28)

#### Encapsulation

| Operator | Description |
|----------|-------------|
| **AMC** | Access Modifier Change (public/protected/private) |

#### Inheritance

| Operator | Description |
|----------|-------------|
| **IHI** | Hiding Variable Insertion |
| **IHD** | Hiding Variable Deletion |
| **IOD** | Overriding Method Deletion |
| **IOP** | Overriding Method Position Change |
| **IOR** | Overridden Method Rename |
| **ISI** | Super Keyword Insertion |
| **ISD** | Super Keyword Deletion |
| **IPC** | Parent Constructor Call |

#### Polymorphism

| Operator | Description |
|----------|-------------|
| **PNC** | New with Child Class Type |
| **PMD** | Member Declaration with Parent Type |
| **PPD** | Parameter with Child Class Type |
| **PCI** | Type Cast Insertion |
| **PCC** | Cast Type Change |
| **PCD** | Type Cast Deletion |
| **PRV** | Reference with Compatible Type |

#### Overloading

| Operator | Description |
|----------|-------------|
| **OMR** | Overloading Method Contents Replace |
| **OMD** | Overloading Method Deletion |
| **OAN** | Argument Number Change |

#### Java-Specific

| Operator | Description |
|----------|-------------|
| **JTI** | `this` Keyword Insertion |
| **JTD** | `this` Keyword Deletion |
| **JSI** | `static` Modifier Insertion |
| **JSD** | `static` Modifier Deletion |
| **JID** | Member Variable Initialization Deletion |
| **JDC** | Default Constructor Create |

#### Object Comparisons

| Operator | Description |
|----------|-------------|
| **EOA** | `a = b` → `a = b.clone()` |
| **EOC** | `==` → `.equals()` |
| **EAM** | Accessor Method Change |
| **EMM** | Modifier Method Change |

---

### C.10 Operator Effectiveness Summary

#### Most Effective (Highest Fault Coupling)

| Operator | Fault Coupling |
|----------|---------------|
| **EMM** (Expression Mutation) | High |
| **ASRS** (Assignment Short-cut) | High |
| **ISD** (Instance/Static Deletion) | High |
| **COI** (Conditional Insertion) | High |
| **PRVO** (Parameter Variable) | High |

#### Least Effective (Low Fault Coupling)

| Operator | Issue |
|----------|-------|
| **ISI** | Often produces compile errors |
| **JTI** | Often equivalent mutants |
| **AMC** | Rarely coupled to faults |
| **OAN** | Low semantic impact |
| **LVR** | Simple constant changes |

#### Deletion Operators (High Efficiency)

Deletion operators produce significantly fewer equivalent mutants while achieving 97% mutation score:

- **SDL** - Statement Deletion
- **AOD** - Arithmetic Operator Deletion
- **LOD** - Logical Operator Deletion
- **ROD** - Relational Operator Deletion

---

### C.11 Cross-Reference: Clojure Equivalents

| Standard Operator | Clojure Equivalent |
|-------------------|-------------------|
| **AOR** | `+` ↔ `-`, `*` ↔ `/` |
| **ROR** | `<` ↔ `<=`, `>` ↔ `>=`, `=` ↔ `not=` |
| **COR/LOR** | `and` ↔ `or` |
| **UOI** | Insert `not`, negate numbers |
| **SDL** | Remove expressions from `do`, `let` |
| **Method Expression** | `first` ↔ `last`, `filter` ↔ `remove`, `take` ↔ `drop` |
| **Stream ops** | `map` ↔ `mapv`, remove `doall` |
| **Null handling** | `nil?` ↔ `some?`, `seq` ↔ `empty?` |

| Clojure-Specific | Description |
|------------------|-------------|
| **Threading** | `->` ↔ `->>`, `->` ↔ `some->` |
| **Keywords** | `:user/id` ↔ `:users/id`, qualified ↔ unqualified |
| **Destructuring** | Wrong keys, missing defaults, wrong nesting |
| **Lazy/Eager** | `map` ↔ `mapv`, add/remove `doall` |
| **Nil punning** | `(when (seq coll))` ↔ `(when coll)` |
