# Heretic

Mutation testing for Clojure with intelligent test selection.

## Why Mutation Testing?

Code coverage tells you if code was *executed*. Mutation testing tells you if it was *actually verified*.

Heretic makes small changes (mutations) to your source code and checks if your tests catch them:

- **Killed** - A test failed. Your tests are doing their job.
- **Survived** - All tests passed. You may have a gap in test coverage.

A high mutation score means your tests don't just run your code - they verify its behavior.

## Why Heretic?

Mutation testing is expensive. A typical tool runs your entire test suite for every mutation, making it impractical for real projects.

Heretic solves this with **test-to-code mapping**: it knows exactly which tests exercise which expressions, so each mutation only runs the tests that matter. This makes mutation testing practical for everyday development.

**Key features:**

- **Coverage-based test selection** via [ClojureStorm](https://github.com/flow-storm/clojure) instrumentation
- **80+ mutation operators** covering arithmetic, boolean, comparison, collection, nil-handling, threading, and more
- **Clojure-specific mutations** like `->` vs `->>`, `map` vs `mapv`, keyword case conversion
- **Subsumption analysis** to reduce redundant mutations while maintaining fault detection
- **Equivalent mutation detection** to filter out mutations that can't be killed
- **Incremental testing** - only re-test when source or tests change
- **Watch mode** for continuous feedback during development
- **Parallel execution** across source files
- **Mutant schemata** optimization - compile all mutations once, select at runtime
- **HTML/JSON/EDN reports** with source heatmaps and survivor details

## Installation

Add Heretic to your `deps.edn`:

```clojure
{:aliases
 {:heretic
  {:extra-deps {io.github.parenstech/heretic {:git/tag "v0.1.0" :git/sha "..."}}
   :extra-paths ["test"]}}}
```

Create a `heretic.edn` configuration file:

```clojure
{:source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces :all  ; or [my.app.core-test my.app.util-test]

 ;; ClojureStorm instrumentation - limit to your code
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev"]}
```

## Quick Start

```bash
# Collect test-to-code coverage (one-time, cached)
clj -M:heretic -m heretic.core collect

# Run mutation testing
clj -M:heretic -m heretic.core mutate

# Watch mode - continuous mutation testing on changes
clj -M:heretic -m heretic.core watch
```

## Usage

### Commands

| Command | Description |
|---------|-------------|
| `collect` | Build test-to-code coverage map |
| `collect --force` | Force full recollection |
| `status` | Show which test namespaces need recollection |
| `mutate` | Run mutation testing |
| `mutate --files src/my_app/core.clj` | Mutate specific files |
| `mutate --operators arithmetic,comparison` | Use specific operator categories |
| `survivors` | Show surviving mutations from last run |
| `watch` | Continuous mutation testing on file changes |
| `clean` | Remove cached coverage data |

### Output

After mutation testing, Heretic reports:

```
Mutation Testing Results
========================
Killed:      98
Survived:    24
Equivalent:  5
No coverage: 10
Score:       80.3%
```

Surviving mutations indicate potential gaps in your tests. Review them to decide if you need additional test cases.

## Configuration

Full configuration options in `heretic.edn`:

```clojure
{;; Source and test locations
 :source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces :all  ; or list of namespace symbols

 ;; Coverage storage
 :heretic-dir ".heretic"

 ;; ClojureStorm instrumentation
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev" "my-app.test"]

 ;; Mutation operators
 :preset :standard      ; :fast, :standard, :minimal, or :comprehensive
 :skip-forms #{comment} ; Forms to skip

 ;; Execution
 :timeout-ms 5000       ; Per-mutant timeout
 :parallel-workers 4    ; Number of parallel workers

 ;; Reporting
 :report-format :html   ; :terminal, :html, :json, :edn
 :output-path "target/heretic-report"}
```

### Operator Presets

| Preset | Operators | Use Case |
|--------|-----------|----------|
| `:fast` | ~16 | Quick feedback during development |
| `:standard` | ~35 | Balanced set for CI (default) |
| `:minimal` | ~30 | Subsumption-optimized, ~99% fault detection |
| `:comprehensive` | 80+ | Maximum coverage when time permits |

## How It Works

### Coverage Collection

Heretic uses [ClojureStorm](https://github.com/flow-storm/clojure), a patched Clojure compiler, to trace which tests execute which expressions:

1. Tests run one-by-one under ClojureStorm tracing
2. For each expression evaluated, Heretic records: `test -> form-id -> coordinates`
3. Coverage is stored per test namespace for incremental updates
4. An inverse index maps code locations back to tests

### Mutation Testing

For each mutation:

1. Look up relevant tests via coverage map
2. Apply mutation using [rewrite-clj](https://github.com/clj-commons/rewrite-clj)
3. Reload namespace with [clj-reload](https://github.com/tonsky/clj-reload)
4. Run targeted tests (stop on first failure)
5. Record result and restore original code

### Mutant Schemata

When multiple mutations target the same file, Heretic uses **mutant schemata** for efficiency:

```clojure
;; Original
(defn calculate [x] (+ x 1))

;; Schematized (all mutations compiled once)
(defn calculate [x]
  (case heretic.schemata/*active-mutant*
    :mut-1-plus-minus (- x 1)
    :mut-1-mult       (* x 1)
    (+ x 1)))  ; default = original
```

This avoids recompilation between mutations - just rebind the dynamic var.

## Mutation Operators

### Arithmetic
`+` <-> `-`, `*` <-> `/`, `inc` <-> `dec`

### Comparison
`<` <-> `>`, `<=` <-> `>=`, `=` <-> `not=`, boundary mutations (`<` <-> `<=`)

### Boolean
`and` <-> `or`, `true` <-> `false`, remove `not`

### Collections
`first` <-> `last`, `rest` <-> `next`, `take` <-> `drop`, `conj` <-> `disj`

### Nil-Handling
`nil?` <-> `some?`, `seq` <-> `empty?`

### Threading
`->` <-> `->>`, `->` <-> `some->`, `->>` <-> `some->>`

### Lazy/Eager
`map` <-> `mapv`, `filter` <-> `filterv`, `for` <-> `doseq`

### Higher-Order Functions
`filter` <-> `remove`, `keep` <-> `filter`

### Return Values
Replace `nil` with `false`, `0`, `[]`, `{}`, `""`

### Constants
`0` <-> `1`, `1` <-> `-1`, common numeric constants

### Destructuring
`:user-id` <-> `:userId`, `:user/id` <-> `:users/id`, qualified <-> unqualified keywords

## ClojureStorm Configuration

Heretic requires ClojureStorm to collect coverage. Configure it via JVM options:

```clojure
;; In deps.edn alias
:jvm-opts ["-Dclojure.storm.instrumentEnable=true"
           "-Dclojure.storm.instrumentOnlyPrefixes=my-app"
           "-Dclojure.storm.instrumentSkipPrefixes=my-app.test"]
```

**Important:** Never instrument Heretic itself, or infinite recursion will occur.

## Reports

### HTML Report

The HTML report includes:
- Mutation score summary
- Source file heatmap (visual killed/survived ratio per file)
- Detailed survivor list with code snippets
- Test effectiveness ranking
- Historical trends

### JSON/EDN Export

Machine-readable output for CI integration:

```clojure
{:summary {:total 127 :killed 98 :survived 24 :score 0.803}
 :by-file {"src/my_app/core.clj" {:total 45 :killed 38}}
 :survivors [{:file "..." :line 42 :operator :swap-plus-minus ...}]}
```

## Future Work

The following features are planned for future releases:

- **AI-powered mutations** - LLM-generated semantic mutations targeting real-world bug patterns
- **AI equivalent detection** - Hybrid static/LLM filtering for surviving mutations
- **Test generation** - LLM suggestions for tests that would kill survivors
- **ClojureScript support** - shadow-cljs integration for browser/Node.js testing
- **Process-level parallelism** - Pre-forked worker JVMs for maximum throughput

## Dependencies

- [ClojureStorm](https://github.com/flow-storm/clojure) - Instrumented Clojure compiler
- [rewrite-clj](https://github.com/clj-commons/rewrite-clj) - Source code manipulation
- [clj-reload](https://github.com/tonsky/clj-reload) - Namespace reloading
- [Malli](https://github.com/metosin/malli) - Schema validation
- [Missionary](https://github.com/leonoel/missionary) - Reactive programming for worker supervision

## License

Copyright (c) 2025

Distributed under the Eclipse Public License 2.0.
