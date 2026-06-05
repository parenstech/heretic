# Sandboxed Mutation Runs

**Status:** **Implemented** (branch `sandbox-mutation`) — resolves #2
**Addresses:** [#2 — Heretic modifies source files in place](https://github.com/parenstech/heretic/issues/2)

## Problem

A `mutate` run applies each mutation by **writing it into your real source file**
(`spit` the mutated form → reload → run covering tests → `spit` the original
back). Two reported symptoms:

- **Your working tree is dirty for the whole run** (~10 min for 800 mutations),
  so anything else reading the checkout — your editor, a REPL, another test run —
  sees half-mutated code. In parallel mode several files are mid-mutation at once.
- **A killed process strands a mutation.** The revert runs in a `finally`, which a
  hard `Ctrl-C`/`SIGKILL` skips, leaving a `+`→`-` lying in `src/foo.clj`.

The reporter asked: *"Would it be possible to copy the whole source directory to a
temporary folder?"* — **yes, and that is the approach taken here.**

## The idea: run the whole pipeline against a throwaway copy

Don't change *how* mutations apply — change *where*. Copy the project into a
disposable **sandbox**, run the entire normal pipeline (collect + mutate) with the
sandbox as the working directory, and copy the result artifacts back out. The
user's checkout is never on the write path.

The decisive property: **the mutation engine is unchanged — byte for byte.**
`spit`+reload already handles *every* form type correctly (macros, protocols,
`deftype`/`defrecord`, multimethods, inline, const) because clj-reload reloads
dependents. Moving the writes into a sandbox inherits that uniform correctness for
free:

- no per-form classification, no fallback matrix, no direct-linking guard;
- no false-survivor risk from redefinition semantics;
- near-zero engine blast radius (runner / coverage / form-bridge / reporter all
  run verbatim).

This is the opposite trade-off from applying mutations *in memory* (`eval`-ing the
mutated form into the live JVM): that is faster per-apply but carries a permanent
per-form correctness surface and does not even fully resolve #2. See
[Alternatives considered](#alternatives-considered).

## Non-goals

What this design explicitly does **not** do in v1:

- **No in-memory / `eval`-based mutation.** Applying mutations with zero disk
  writes is faster per-apply but does not fully resolve #2 and carries a permanent
  per-form correctness surface; it could return later only as an opt-in `--fast`
  executor layered *inside* the sandbox. See
  [§Alternatives considered](#alternatives-considered).
- **No crash-safety hardening of the in-place path** (shutdown hook + recovery
  file). The sandbox eliminates the leftover-mutation window by construction, so
  hardening the retained in-place `mutate` is out of scope here. See
  [§Alternatives considered](#alternatives-considered).

## Architecture

A thin orchestrator runs in your project root, sets up an isolated copy, and
**re-execs the existing `clojure -M:heretic mutate` as a subprocess whose working
directory is the sandbox**. The child *is* a normal Heretic run — same deps, same
classpath, same ClojureStorm flags, same runner — only the cwd moved.

```
your project root                         sandbox (.heretic-sandbox/)
─────────────────                         ───────────────────────────
heretic.sandbox/mutate-in-sandbox!
  1. rsync working tree  ───────────────▶ src/  test/  resources/  deps.edn …
  2. spawn child (cwd = sandbox):
       clojure -M:heretic mutate ────────▶ collect!  (ClojureStorm → .heretic/)
       (stdout inherited, streams live)    mutate!   (spit+reload in sandbox)
  3. child exits 0                          .heretic/mutation-results.edn
  4. copy artifacts back  ◀───────────────  target/heretic-report/*
  5. leave sandbox for reuse (or wipe)
```

**Why a subprocess and not the same JVM.** The JVM's classpath and `user.dir` are
fixed at launch; `clj-reload`/`require` read from the classpath, so reloading
sandbox code requires a JVM launched against the sandbox anyway. A
`System/setProperty "user.dir"` rebind is fragile (not honored by all of Java's
path resolution) and — critically — would **not** isolate the *user's own test
code's* file I/O, which runs relative to the real CWD. A subprocess with a real
cwd gives true isolation: the child literally cannot open your files for write.

> Variants considered and rejected: a **two-JVM nREPL drive** (self-test style)
> only avoids the coverage path-mismatch by collapsing all work into the single
> nREPL JVM — at which point nREPL is pure overhead plus a serialization failure
> surface. An **in-process path-redirect** is a mirage (classpath/`user.dir` are
> launch-fixed; it forces a relaunch anyway and demands editing the very code we
> want to leave alone).

### Copy strategy — filesystem copy, **not** `git worktree`

`git worktree add <path> HEAD` checks out the **committed** tree and **silently
drops your uncommitted edits and untracked files** (this is exactly what
`bb self-test:setup` does today). The issue reporter is *actively editing tests* —
testing their last commit instead of their live buffer would be a silent
wrong-results disaster. So:

- **Primary mechanism: a filesystem copy of the live working tree** (`rsync -a`,
  `cp -a` fallback). It captures exactly what's on disk — tracked-modified **and**
  untracked-new files — and works identically for **non-git** projects (Heretic
  runs from arbitrary user projects; many won't be git repos). This is the one
  uniform path for clean / dirty / non-git trees.
- **Copy the classpath roots, derived from `deps.edn :paths` + test paths**, not a
  hardcoded `src`+`test`. Must include `resources/` (and any other declared root)
  or resource-loading tests error or falsely survive in the sandbox.
- **Exclude** `.git`, `target/`, `node_modules/`, the sandbox dir itself, and —
  importantly — the existing `.heretic/` (see next section).
- If the `:heretic` alias references Heretic via a **relative `:local/root`**,
  absolutize that one path in the copied `deps.edn` so the child can resolve
  Heretic's source. (Maven/git coordinates resolve from the shared cache, no
  change needed.)
- Always **print what's under test** (commit SHA + "dirty: N files", or
  "filesystem snapshot") so the user is never surprised about what was mutated.

### Coverage consistency — the load-bearing invariant

`form-bridge` keys the coverage index on **absolute canonical paths**
(`(.getCanonicalPath (io/file src-path file))`) computed against `user.dir` **at
collect time**, and `tests-for-mutation` re-canonicalizes the mutation's `:file`
the same way. They only match when **collect and mutate share one `user.dir`**.

- Running the *whole* pipeline with `user.dir = sandbox` is self-consistent:
  relative `:source-paths`/`:test-paths`/`:heretic-dir` resolve under the sandbox,
  and every canonical key resolves under the sandbox.
- **Do not copy a prebuilt `.heretic/` into the sandbox** — its keys are absolute
  paths under the *original* tree, so sandbox mutations would never match → every
  mutation reports `:no-coverage` (a silent, perfect-looking, useless run). The
  sandbox must run `collect!` itself. (That `.heretic` is gitignored is convenient
  — it's naturally absent from a fresh copy.)

### Reuse — don't re-collect every run

`collect!` (full suite under ClojureStorm) is the expensive phase. Default to a
**persistent sandbox at a stable path** (`<project>/.heretic-sandbox`):

- each run `rsync -a --delete`s the working tree *into* it (cheap, only changed
  bytes; `--delete` removes files you deleted) but **excludes `.heretic/`** so the
  sandbox's own coverage + timing data survive;
- because the sandbox path is stable, the surviving `.heretic` keys still resolve,
  and the existing **incremental/staleness machinery recollects only the
  namespaces whose hashes changed** — no new code, the normal `ensure-coverage!`
  path. Timing data also persists, keeping test-ordering fast across runs.
- **CI / hermetic mode** (`:keep-sandbox false`): one-shot temp dir, full collect,
  wiped on exit.
- `:fresh-sandbox true` forces a clean `rm -rf` + recollect when you suspect drift.

> *Optional future optimization:* when the copied tree is byte-identical to what a
> prior in-place collect saw, copy `.heretic` and **rewrite the absolute path
> prefix** in the index keys (a deterministic string replace) to skip re-collect
> entirely. High-leverage but adds a correctness condition (form-ids must still
> match) — defer past v1.

### Crash safety — correctness by construction

A kill at any point strands a mutation **only in the disposable sandbox**, never
in your files (the child's cwd is the sandbox). Cleanup is **cleanup-on-next-run**:
the next run's `rsync --delete` from your pristine tree overwrites any stranded
edit — self-healing, no revert hook, no `git checkout` dance. The only write into
your tree is the artifact copy-back, which happens **only after the child exits 0**
— a crash never reaches it. `.heretic` writes are already atomic (temp-file +
rename), so the persistent sandbox's index can't be left half-written.

## Gotchas & correctness invariants

- **Coverage path consistency** (above) — the one invariant the design is built
  around. Whole pipeline in the sandbox; never mix user-dir-collect with
  sandbox-mutate.
- **Copy classpath roots, not just `src`/`test`** — include `resources/`; derive
  from `deps.edn :paths`.
- **Capture the dirty buffer + untracked files** — filesystem copy, not a
  git-object checkout.
- **Copy artifacts back** — `.heretic/mutation-results.edn` and
  `target/heretic-report/*` are written relative to the sandbox and would vanish
  on teardown; `heretic survivors` reads `mutation-results.edn` from *your* tree.
  Copy them back (survivor `:file` entries are relative, so they resolve in your
  root unchanged).
- **Watch mode is sandboxed too.** `watch.clj` watches your real paths but routes
  each save through `mutate-in-sandbox!` (scoped to the changed file), reusing the
  persistent sandbox so only the touched namespace re-collects. The file you are
  editing is never modified in place.
- **Offline deps** — the child resolves deps from the shared `~/.m2`/`~/.gitlibs`
  caches via the copied `deps.edn`; already populated by the user's normal run, so
  offline-safe. (Reusing the parent's resolved classpath is a possible
  optimization.)
- **Keep config paths relative** — only `user.dir` moves; never pass absolute
  user-tree source-paths into the child or clj-reload would reload from your tree.

## Configuration & rollout

Three new optional `heretic.edn` keys (consumed only by the orchestrator; no
existing key changes):

| Key | Default | Meaning |
|-----|---------|---------|
| `:sandbox-dir` | `".heretic-sandbox"` | Where the copy lives (gitignore it). Override for tmpfs/faster disk. |
| `:keep-sandbox` | `true` | Persistent reuse (incremental collect). `false` = wipe each run (CI). |
| `:fresh-sandbox` | `false` | Force a clean `rm -rf` + recollect. |

For a **consumer project** (Heretic run against *your* code), the child JVM needs
your ClojureStorm-collect alias, Heretic's source on its classpath, and any test
roots outside `deps.edn :paths`:

| Key | Meaning |
|-----|---------|
| `:sandbox-aliases` | clj aliases the child runs under (default `["collect"]`). |
| `:sandbox-deps` | an `-Sdeps` EDN map merged into the child deps — e.g. inject Heretic's own source: `{:aliases {:heretic-src {:extra-paths ["/abs/heretic/src"]}}}`. |
| `:sandbox-jvm-opts` | extra JVM opts (each gets `-J`), e.g. `["-Xmx4g" "-Dclojure.storm.instrumentAutoPrefixes=false"]`. |
| `:sandbox-extra-paths` | top-level dirs the test classpath needs that aren't under `deps :paths`/source/test — e.g. a `cassettes/` VCR dir. |

These keys have been validated end-to-end against a real consumer project — e.g.
`:sandbox-aliases ["collect" "heretic-src"]` with `:sandbox-deps` injecting
Heretic's own source, `:sandbox-jvm-opts ["-Xmx4g" …]`, and `:sandbox-extra-paths
["cassettes"]` for a VCR fixtures dir — a scoped run scores with the consumer's
working tree untouched.

`heretic:mutate` always runs sandboxed. The mutation engine itself
(`heretic.core/mutate!`, which the sandbox child runs) is still a public function
you can call directly if you ever want an in-place run, but there is no
in-place bb task — sandboxed is the only supported entry point.

### Code changes

| File | Change |
|------|--------|
| `src/heretic/sandbox.clj` *(new)* | The only substantive new code. `mutate-in-sandbox! [config & opts]`: resolve `:sandbox-dir`; `sync-tree!` (rsync/cp of classpath roots ∪ `deps.edn`/`heretic.edn`, with the exclude set); absolutize a relative `:local/root` in the copied `deps.edn`; build child JVM opts from the shared ClojureStorm flag logic; spawn `clojure -M:heretic mutate` with `:dir sandbox`, inheriting stdout; on exit copy back `mutation-results.edn` + reports; keep or wipe per `:keep-sandbox`. **Returns the same result-map shape `mutate!` returns** — `{:total :killed :survived :no-coverage :mutation-score :survivors}`, parsed from the copied-back `mutation-results.edn` — plus sandbox metadata (`:sandbox-dir`, `:reused?`, and the SHA / dirty-status of the code under test), so the run's outcome is an observable value at the boundary, not only on disk. Pure helpers (path resolution, exclude list, local-root rewrite, result-map parse) split out for unit tests. |
| `src/heretic/core.clj` | **No functional change** (only an explanatory comment). The sandbox entry point is `heretic.sandbox/mutate-in-sandbox!`, *not* exposed through `heretic.core`: loading `core` pulls in `heretic.tracer → clojure.storm.*` (needs the storm classpath), and the orchestrator stays lightweight (`clojure.*` only) so it can run without that classpath and spawn the storm child itself. `mutate-in-sandbox!` returns `mutate!`'s result-map shape + `:sandbox` metadata, so callers and tests see the score as a value. |
| `bb.edn` | `heretic.sandbox/storm-jvm-opts` is the single ClojureStorm-flag builder (`:init` requires `heretic.sandbox`; `run-with-clojurestorm`/`dev:repl` call it). `heretic:mutate` runs sandboxed (the only mutate task), `heretic:sandbox-clean` wipes the sandbox. The `self-test:*` worktree machinery is **retired** — `self-test` now runs the sandboxed pipeline on Heretic's own `src`, and `self_test.clj`/`nrepl_runner.clj` are deleted. |
| `README.md`, `.gitignore` | Document the sandbox lifecycle + consumer config keys (also covered in this doc); gitignore `.heretic-sandbox`. |

## Phased plan — all phases implemented

- **Phase 1 — Sandbox orchestration ✅.** `heretic.sandbox/mutate-in-sandbox!`:
  filesystem copy of classpath roots, subprocess re-exec with cwd=sandbox, stream
  output, copy artifacts back, return `mutate!`'s result-map + `:sandbox`
  metadata. Verified: a sandboxed run produces a real mutation score while
  `git status` stays clean throughout; a mid-run `kill` leaves the working tree
  untouched.
- **Phase 2 — Persistent reuse + incremental ✅.** Stable `:sandbox-dir`;
  `rsync -a --delete` syncs the working tree into a kept sandbox while preserving
  its `.heretic`, so the child re-collects only the namespaces whose source
  changed. `:keep-sandbox` (default true) keeps + reuses; `:keep-sandbox false`
  is one-shot/CI; `:fresh-sandbox` forces a clean rebuild. Falls back to a
  dep-free full copy when `rsync` is unavailable.
- **Phase 3 — Sandboxed by default; worktree self-test retired ✅.**
  `heretic:mutate` is sandboxed (the in-place `mutate!` engine still runs — inside the sandbox).
  `self-test` runs the sandboxed pipeline on Heretic's own source (scoped to
  dodge the full-suite ClojureStorm hang); `self_test.clj` / `nrepl_runner.clj`
  and the `self-test:*` worktree tasks are deleted.

## Alternatives considered

### In-memory mutation — evaluated and rejected

The tempting alternative is to never touch disk at all: apply each mutation by
`eval`-ing the rewritten form straight into the live JVM, redefining the var.
Clojure vars are late-bound, so already-compiled callers (including the tests, run
in the same JVM) pick up the new definition immediately. A former `schemata`
prototype (since removed — see `docs/validation-results.md` §1) built the
switch-per-mutant form in memory, and a REPL experiment confirmed the mechanism
works: redefinition propagates, switching is thread-local, and it is ~250× cheaper
per apply than recompiling. So why not it?

**It does not actually resolve #2.** In-memory redefinition only works when the
mutated subform sits inside an ordinary `defn`. It is wrong — *silently* — for a
whole class of enclosing forms, each verified in a REPL:

- **macros** — a redefined macro is invisible to already-compiled callers until
  they are recompiled, so the mutation has no effect → false survivor;
- **`defprotocol` / `deftype` / `defrecord`** — re-evaluating builds a *new* class
  that existing instances are not even `instance?` of, so they keep the original
  method → false survivor;
- **`definline` / `^:inline` / `^:const`** — inlined into callers at compile time,
  same failure as macros;
- and the whole scheme is defeated outright by **direct linking** (callers
  compiled with it ignore var-root changes), which AOT/uberjar builds can enable.

Those cases *must* fall back to the existing `spit`+reload path — which means **for
macros, protocols, types, and friends the in-memory approach still writes to your
source files in place**, dirtying the tree and risking a stranded mutation on a
kill. It removes the disk writes only for the easy subset, leaving #2 unsolved for
the rest; to fully fix #2 it would *still* need the sandbox underneath it.

**And it is expensive to keep correct.** Making it safe requires a per-form
classifier, a fallback matrix, a direct-linking probe, a wrong-namespace guard, a
file-level benignity check, and a parity test that keeps the disk path as a live
oracle forever — a permanent correctness surface where a single misclassification
produces a *silent wrong score* (not a crash), and against which every future
operator and form type must be re-validated. The sandbox needs none of this: it
leaves the engine unchanged, so it inherits the disk path's uniform correctness for
all form types for free. The speed advantage is also modest end-to-end, since
per-apply time is dominated by running the covering tests, not by applying the
mutation.

In-memory could return later purely as an **opt-in `--fast` executor layered
*inside* the sandbox** (so even its disk fallback writes only to the sandbox, never
your tree). It is neither a prerequisite for nor a substitute for this work.

### Crash-safety hardening (shutdown hook + recovery file)

Cheap and complementary, but only narrows the leftover-mutation window for the
*in-place* path. The sandbox eliminates that window entirely by construction, so a
hardening pass is unnecessary once sandboxed runs are the default (still worth
keeping for the retained in-place `mutate`).
