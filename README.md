# Heretic

Mutation testing for Clojure with intelligent test selection.

## What is Mutation Testing?

Mutation testing evaluates the quality of your tests by making small changes (mutations) to your source code and checking if your tests catch them. If a test fails, the mutation is "killed" - your tests are doing their job. If all tests pass, the mutation "survived" - you may have a gap in your test coverage.

Unlike code coverage, which only tells you if code was *executed*, mutation testing tells you if code was *actually verified* by your tests.

## How Heretic Works

Heretic uses [ClojureStorm](https://github.com/flow-storm/flow-storm-debugger) (a patched Clojure compiler) to trace which tests execute which code. This enables:

1. **Test-to-code mapping**: Know exactly which tests exercise which expressions
2. **Targeted mutation testing**: Run only relevant tests for each mutation
3. **Incremental collection**: Only recollect coverage when files change

```
+------------------+     +------------------+     +------------------+
|  Your Tests      | --> |  ClojureStorm    | --> |  Coverage Map    |
|  (clojure.test)  |     |  (tracing)       |     |  (test -> code)  |
+------------------+     +------------------+     +------------------+
                                                          |
                                                          v
+------------------+     +------------------+     +------------------+
|  Report          | <-- |  Targeted Tests  | <-- |  Mutations       |
|  (survivors)     |     |  (subset)        |     |  (rewrite-clj)   |
+------------------+     +------------------+     +------------------+
```

## Status

**Phase 1 complete** - Coverage collection is fully functional. Phase 2 (mutation testing) is next.

See [docs/spec.md](docs/spec.md) for the full specification.

## CRITICAL: Self-Instrumentation Warning

**Heretic code must NOT be instrumented by ClojureStorm.** If it is, infinite recursion occurs because tracer callbacks invoke instrumented code, which triggers more callbacks, and so on.

You **must** configure ClojureStorm to skip Heretic namespaces using one of these approaches:

**Option 1: Explicit prefixes (recommended)**
```
-Dclojure.storm.instrumentAutoPrefixes=false
-Dclojure.storm.instrumentOnlyPrefixes=<your-app-prefix>
```

**Option 2: Skip Heretic explicitly**
```
-Dclojure.storm.instrumentSkipPrefixes=heretic
```

Without these flags, collection will hang or stack overflow.

## Installation

Add to your `deps.edn`:

```clojure
{:aliases
 {:heretic
  {:extra-deps {io.github.your-org/heretic {:git/tag "v0.1.0" :git/sha "..."}}
   :extra-paths ["test"]}}}
```

Create a `heretic.edn` config file:

```clojure
{:source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces :all  ; or [my.app.core-test my.app.util-test]

 ;; ClojureStorm instrumentation (limit to your code)
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev"]}
```

## Usage

```bash
# Collect test-to-code coverage
bb heretic:collect

# Show which test namespaces need recollection
bb heretic:status

# Run mutation testing (Phase 2 - not yet implemented)
bb heretic:mutate

# Show surviving mutations
bb heretic:survivors

# Clean coverage data
bb heretic:clean
```

### Options

```bash
# Force full recollection (ignore staleness)
bb heretic:collect --force

# Collect specific test namespaces only
bb heretic:collect --namespaces my.app.core-test,my.app.util-test
```

## Configuration

Full configuration options in `heretic.edn`:

```clojure
{;; Source and test locations
 :source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces :all  ; or list of symbols

 ;; Coverage storage
 :heretic-dir ".heretic"

 ;; ClojureStorm instrumentation
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev" "my-app.test"]

 ;; Mutation operators (Phase 2)
 :mutation-operators [:arithmetic :comparison :boolean :return-values]
 :skip-forms #{comment}
 :timeout-ms 5000

 ;; Reporting
 :report-format :terminal  ; or :html, :json, :edn
 :output-path "target/heretic-report"}
```

## How Coverage Collection Works

1. Tests run one-by-one under ClojureStorm tracing
2. For each expression evaluated, Heretic records: test -> form-id -> coordinates
3. Coverage is stored per test namespace for incremental updates
4. An inverse index maps code locations back to tests

This approach works with any test runner (Kaocha, Cognitect, clojure.test) since it runs tests individually rather than wrapping the test framework.

### Output Structure

After collection, the `.heretic/` directory contains:

```
.heretic/
├── meta.edn           # Form registry from ClojureStorm
├── coverage/
│   └── my.app.core-test.edn   # Per-namespace coverage
└── index.edn          # Inverse index: [form-id coord] → #{tests}
```

## Mutation Operators (Phase 2)

| Category | Original | Mutations |
|----------|----------|-----------|
| Arithmetic | `+` | `-`, `*`, `/` |
| Comparison | `<` | `<=`, `>`, `>=`, `=` |
| Boolean | `and` | `or` |
| Return values | `x` | `nil`, `0`, `""`, `[]` |

See the full list in [docs/spec.md](docs/spec.md#mutation-operators).

## Development

```bash
# Start a REPL
bb dev:repl

# Run tests
bb test

# ClojureStorm REPL (for development)
clj -M:dev:clojurestorm
```

## Dependencies

- [ClojureStorm](https://github.com/flow-storm/clojure) - Instrumented Clojure compiler
- [rewrite-clj](https://github.com/clj-commons/rewrite-clj) - Source code manipulation
- [clj-reload](https://github.com/tonsky/clj-reload) - Namespace reloading

## License

Copyright (c) 2025

Distributed under the Eclipse Public License 2.0.
