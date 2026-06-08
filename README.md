# Heretic

<p align="center">
  <img src="heretic-logo.webp" alt="Heretic" width="400">
</p>

---

## ⚠️ EXPERIMENTAL - NOT RELEASED ⚠️

**This library is not ready for use.** It's a demo for the curious. The API, behavior, and everything else is subject to change without notice. Do not depend on this for anything.

---

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
- **Non-destructive** - mutations run in an isolated sandbox copy, so your working tree is never modified (and an interrupted run leaves nothing behind)
- **80+ mutation operators** covering arithmetic, boolean, comparison, collection, nil-handling, threading, and more
- **Clojure-specific mutations** like `->` vs `->>`, `map` vs `mapv`, keyword case conversion
- **Subsumption analysis** to reduce redundant mutations while maintaining fault detection
- **Equivalent mutation detection** to filter out mutations that can't be killed
- **Survivor triage** - classifies each survivor as a real coverage-gap (with the witnessing input your tests miss) vs an unkillable equivalent mutant
- **Incremental testing** - only re-test when source or tests change
- **Watch mode** for continuous feedback during development
- **Parallel execution** across source files
- **HTML/JSON/EDN reports** with source heatmaps and survivor details

## Installation

Add Heretic to your `deps.edn`:

```clojure
{:aliases
 {:heretic
  {:extra-deps {io.github.parenstech/heretic {:git/tag "v0.1.0" :git/sha "..."}
                ;; ClojureStorm replaces the Clojure compiler for coverage collection
                com.github.flow-storm/clojure {:mvn/version "1.12.0-1"}}
   :classpath-overrides {org.clojure/clojure nil}
   :extra-paths ["test"]
   :jvm-opts ["-Dclojure.storm.instrumentEnable=true"
              "-Dclojure.storm.instrumentOnlyPrefixes=my-app"
              "-Dclojure.storm.instrumentSkipPrefixes=my-app.test"]}}}
```

Create a `heretic.edn` configuration file:

```clojure
{:source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces :all  ; or [my.app.core-test my.app.util-test]

 ;; ClojureStorm instrumentation - limit to your code
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev"]

 ;; `mutate`/`watch` run against a sandbox copy; the child JVM reuses this alias
 ;; (which already has ClojureStorm + your test paths + Heretic on the classpath)
 :sandbox-aliases ["heretic"]}
```

## Quick Start

```bash
# Collect test-to-code coverage (one-time, cached)
clj -M:heretic -m heretic.core collect

# Run mutation testing (sandboxed - your working tree is never modified)
clj -M:heretic -m heretic.core mutate

# Watch mode - continuous sandboxed mutation testing on changes
clj -M:heretic -m heretic.core watch
```

## Sandboxed runs

`mutate` and `watch` apply their mutations inside a **disposable copy** of your
project, so a run only ever *reads* your working tree. You can keep editing, run
your own tests, even switch branches while a run is in progress — nothing it does
touches your files, and an interrupted run (Ctrl-C / `kill`) leaves nothing
behind.

```bash
# kick this off and keep working in another terminal — your tree stays clean
clj -M:heretic -m heretic.core mutate
```

The child JVM runs the copy with the aliases in `:sandbox-aliases` (usually your
`:heretic` alias, so it has ClojureStorm + Heretic + your test paths on the
classpath). The sandbox is **persistent and reused** between runs — only the
namespaces whose source changed are re-collected (this needs
[`rsync`](#system-tools); without it Heretic falls back to a full copy each run).

For **CI**, make each run hermetic — a full copy, a full re-collect, wiped when
it finishes:

```clojure
;; heretic.edn
{:keep-sandbox false}
```

For projects whose tests need extra files or dependencies inside the sandbox:

```clojure
;; heretic.edn
{:sandbox-extra-paths ["resources" "cassettes"]   ; copied alongside src/test
 :sandbox-deps {:aliases {:it {:extra-deps {io.example/helper {:mvn/version "1.0"}}}}}
 :sandbox-aliases ["heretic" "it"]}               ; child runs -M:heretic:it
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
| `survivors` | Show surviving mutations from last run |
| `watch` | Continuous mutation testing on file changes |
| `clean` | Remove cached coverage data |

Operator selection is configured in `heretic.edn` (`:preset` or `:operators`), not via the CLI.

### Output

After mutation testing, Heretic reports:

```
Survivor Hotspots
-----------------
   43  src/main/my_app/core.clj
   12  src/main/my_app/util.clj

Mutation Testing Results
========================

Total: 200 mutations

  Killed:      156 (78%)
  Survived:     24 (12%)
  No Coverage:  20 (10%)

Score: 86.7%
```

**Survivor Hotspots** shows files ranked by number of surviving mutations - these need better test coverage. Review individual survivors with the `survivors` command or HTML report.

## Configuration

Full configuration options in `heretic.edn`:

```clojure
{;; Source and test locations
 :source-paths ["src"]
 :test-paths ["test"]
 :test-namespaces :all  ; or list of namespace symbols
 :exclude-test-namespaces #{}  ; skip these even when :test-namespaces is :all
                               ;   (e.g. a test that can't run under instrumentation)

 ;; Coverage storage
 :heretic-dir ".heretic"

 ;; ClojureStorm instrumentation
 :instrument-prefixes ["my-app"]
 :instrument-skip-prefixes ["my-app.dev" "my-app.test"]

 ;; Mutation operators
 :preset :standard      ; :fast, :standard, :minimal, or :comprehensive
 :skip-forms #{comment} ; Forms to skip

 ;; Execution
 :executor :legacy      ; :legacy (fast, in-process; default) or :process (forked
                        ;   worker JVMs that survive infinite-loop/crash mutants)
 :timeout-ms 5000       ; Per-mutant timeout
 :parallel-workers 4    ; Number of parallel workers

 ;; Survivor triage — classify each survivor as a real coverage-gap (with the
 ;; witnessing input your tests miss) vs an unkillable equivalent mutant.
 :triage-survivors true ; default on; false to skip the post-run triage pass

 ;; Sandbox — mutate/watch run against a disposable copy; your tree is never written
 :sandbox-aliases ["heretic"]    ; deps.edn aliases the child JVM runs with — must put
                                 ;   ClojureStorm + Heretic + your test paths on its
                                 ;   classpath (usually your :heretic alias)
 :keep-sandbox true              ; reuse + incrementally re-collect between runs;
                                 ;   false = hermetic full copy each run, wiped after (CI)
 :fresh-sandbox false            ; true = force a clean rebuild + full re-collect this run
 :sandbox-dir ".heretic-sandbox" ; where the copy lives (gitignore it)
 :sandbox-extra-paths []         ; extra top-level dirs to copy beyond src/test
 :sandbox-deps nil               ; extra -Sdeps EDN for the child JVM
 :sandbox-jvm-opts []            ; extra -J JVM opts for the child (e.g. ["-Xmx4g"])

 ;; Reporting
 :report-format :html   ; :terminal, :html, :json, :edn
 :output-path "target/heretic-report"}
```

**Executors.** `:legacy` (default) runs mutants in-process — fastest. `:executor
:process` runs each mutant's tests in a forked worker JVM and force-kills + respawns
it on timeout — the only way to reclaim a mutant that becomes an uninterruptible CPU
loop (`Thread.interrupt` can't; `Thread.stop` is gone on JDK 20+). Under `bb mutate`
the forked workers automatically inherit the sandbox's ClojureStorm classpath, so
`:executor :process` works with no extra config.

### Operator Presets

| Preset | Operators | Use Case |
|--------|-----------|----------|
| `:fast` | 16 | Quick feedback during development |
| `:standard` | 36 | Balanced set for CI (default) |
| `:minimal` | 31 | Subsumption-optimized, ~99% fault detection |
| `:comprehensive` | 81 | Maximum coverage when time permits |

## How It Works

### Coverage Collection

Heretic uses [ClojureStorm](https://github.com/flow-storm/clojure), a patched Clojure compiler, to trace which tests execute which expressions:

1. Tests run one-by-one under ClojureStorm tracing
2. For each expression evaluated, Heretic records: `test -> form-id -> coordinates`
3. Coverage is stored per test namespace for incremental updates
4. An inverse index maps code locations back to tests

### Mutation Testing

Heretic runs against an **isolated sandbox copy** of your project, so applying
mutations never touches your working tree — no dirty files mid-run, and nothing
left behind if a run is killed. The sandbox is created (and reused incrementally,
see [System tools](#system-tools)) before the run; your real source is only ever
read. Inside the sandbox, for each mutation:

1. Look up relevant tests via coverage map
2. Apply mutation using [rewrite-clj](https://github.com/clj-commons/rewrite-clj)
3. Reload namespace with [clj-reload](https://github.com/tonsky/clj-reload)
4. Run targeted tests (stop on first failure)
5. Record result and restore original code

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

Process-level parallelism (pre-forked worker JVMs for crash/infinite-loop
isolation) shipped in #9 — see the `:executor :process` executor.

## Dependencies

### Libraries

- [ClojureStorm](https://github.com/flow-storm/clojure) - Instrumented Clojure compiler
- [rewrite-clj](https://github.com/clj-commons/rewrite-clj) - Source code manipulation
- [clj-reload](https://github.com/tonsky/clj-reload) - Namespace reloading
- [Malli](https://github.com/metosin/malli) - Schema validation

### System tools

- **`rsync`** — *strongly recommended.* Heretic runs mutation testing in an
  isolated **sandbox copy** of your project, so applying mutations never touches
  your working tree (no dirty files mid-run, nothing left behind if a run is
  killed). When `rsync` is on your `PATH`, that sandbox is **reused incrementally**
  between runs: only changed files are synced and the cached coverage index
  (`.heretic/`) is kept, so only the namespaces whose source actually changed are
  re-collected. Without `rsync`, Heretic still works correctly but falls back to a
  full copy **and a full coverage recollection on every run** — and coverage
  collection (the ClojureStorm pass over your test suite) is by far the most
  expensive phase, so runs are much slower. Install it via your package manager
  (`apt install rsync`, `brew install rsync`, …).

## License

Copyright (c) 2025

Distributed under the Eclipse Public License 2.0.
