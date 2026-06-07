# Wiring `:executor :process` into `bb mutate` — sandbox-bypass plan

**Status:** Complete — blocker fixed, reachable, early-exit + ergonomic auto-derive done; benchmark + consolidation done (see `docs/executor-consolidation.md`).
**Date:** 2026-06-07
**Relates to:** #9 (built the process executor), #11 (doc reconciliation). Branch: `feat/wire-process-executor`.

---

## 0. Implementation progress (2026-06-07)

Running `:executor :process` end-to-end (rather than reasoning about it) surfaced
a real blocker and simplified the design:

- **Blocker found + fixed.** The process executor keys mutants by
  `heretic.killmatrix/mutation-key`, whose `identity-keys` omitted `:column`. Sibling
  literals share one `:coord` (e.g. three `0`s under one `…,V48` path at columns
  33/41/50), so `assert-unique-keys!` rejected a *valid* run ("Duplicate mutation
  keys") — `:process` could not run on heretic's own `src`. The in-process
  executors never hit this (they key by UUID `:id`). Fix: `identity-keys` now
  includes `:column` + `:replacement` (location + what-it-becomes) — 0 collisions
  across all 3145 src mutations; regression test added in `killmatrix_test.clj`.
- **Reachability proven, and D2 simplified.** With the fix, `:executor :process`
  `:parallel-workers 1` `:child-aliases [:collect]` runs cleanly **through the
  existing `bb mutate` sandbox path** (scoped self-target: 20 mutations, 19 killed,
  0 errors, sandbox exit 0 — matching the in-process self-test). This **resolves
  D2 (below) the simple way**: a single forked worker nested inside the existing
  sandbox copy is working-tree-safe (the *sandbox* copy is the boundary, not the
  real tree) **with no double-copy** (the N=1 path makes no copy of its own). Full
  sandbox-bypass (skipping the outer sandbox) is now only the **N>1 optimization**
  — deferred until parallel `:process` is wanted.
- **Fail-fast guard added.** `:executor :process` without `:child-aliases`/`:child-deps`
  now throws an actionable error instead of the cryptic "no results (child exit 1)".
- **Ergonomic entry DONE (2026-06-07).** `sandbox/derive-process-child-config`
  auto-derives the worker spawn keys (`:child-aliases` / `:child-jvm-opts` /
  `:child-deps`) from the sandbox's own `:sandbox-aliases` / `storm-jvm-opts` /
  `:sandbox-deps` when `:executor :process`. So `bb mutate` with **just**
  `:executor :process` in `heretic.edn` works — no hand-set `:child-*`. Explicit
  `:child-*` keys are still respected. (In-place/non-sandbox runs stay
  config-driven, as planned — the orchestrator can't introspect its own launch.)
  Verified e2e on heretic-self (19/20, zero manual child-config) + unit-tested
  (`sandbox-test/derive-process-child-config-test`).

**Step 1 COMPLETE.** **Step 2** (benchmark `:legacy` / `:missionary` / `:process`)
and **Step 3** (collapse to two executors) were done separately — see
`docs/executor-consolidation.md` (`:missionary` dropped; `:legacy` + `:process`).

---

## 1. Goal

Make `bb mutate` — the command people actually run — **survive infinite-loop and
crashing mutants** by letting it use the forked-worker process executor that #9
built (`:executor :process`). Today that capability exists in the code but is
only reachable from the test harness or a direct library call, not from
`bb mutate`.

The problem it fixes: some mutants turn bounded code into a tight CPU loop (e.g.
`<` → `<=` on a loop bound, `dec` → `inc`). On the JVM these are **unkillable
in-process** — `future-cancel` / `Thread.interrupt` don't stop a CPU-bound loop,
and `Thread.stop` is gone on JDK 20+. The only reliable reclaim is to fork the
mutant's test run into a separate JVM and `destroyForcibly()` the OS process on
timeout. That is `:executor :process`.

| Mechanism | Isolates | Survives infinite-loop mutants? |
|---|---|---|
| **Sandbox** (`mutate-in-sandbox!`, today's `bb mutate` default) | the filesystem — mutations happen in a project *copy* | ❌ one JVM, all mutants in-process |
| **`:executor :process`** (#9, not reachable from `bb mutate`) | filesystem (per-worker copies) **+** the process (kill & respawn) | ✅ |

## 2. Current architecture (what exists, where the gap is)

`bb mutate` runs three nested JVM levels conceptually; today only two exist:

```
bb mutate  (storm-FREE orchestrator: loads only heretic.sandbox)
  └─ sandbox/mutate-in-sandbox!   copies project → sandbox, writes effective config
       └─ storm child JVM (cwd = sandbox copy)
            └─ heretic.core/mutate!  → executor dispatch (in-process today)
```

Relevant code:

- **`bb.edn` `heretic:mutate`** — deliberately storm-free: it spawns a child via
  `sb/mutate-in-sandbox!` precisely so the orchestrator never loads
  `heretic.core` → `heretic.tracer` → `clojure.storm.*`.
- **`src/heretic/sandbox.clj`** — `mutate-in-sandbox!` does `copy-project!`
  (`:222`) + `write-effective-config!` (`:215`, writes the orchestrator's full
  resolved config as the copy's `heretic.edn`), then the storm child runs
  `child-code` (`:62`): `(heretic/mutate! (heretic/load-config) …)`.
- **`src/heretic/core.clj`** — `mutate!` already dispatches on `:executor`
  (`case` at ~`:462`); `:process` → `run-mutations-process` (`:331`). Default is
  `:executor :legacy` (`:80`); `:parallel-workers` (`:73`) selects the worker
  count.
- **`src/heretic/runner_process.clj`** — `:parallel-workers` selects the path:
  - **N = 1 → `evaluate-single-worker` (`:157`)** — one forked worker **over the
    REAL working tree**, snapshot/restore based.
  - **N > 1 → the pool** — **per-worker copies** via `sandbox/copy-project!`,
    real working tree never touched.
  - Workers need their ClojureStorm classpath supplied via `child-spawn-spec`
    (`:57`): `:child-aliases` / `:process-worker-dir` / `:child-deps`.

**The gap is twofold:**

1. **No entry point.** `bb mutate` always calls `mutate-in-sandbox!`; nothing
   routes to `core/mutate!` with `:executor :process`. (`:executor` *does* flow
   through `write-effective-config!`, so if you forced `:process` inside the
   sandbox today, the worker classpath for its forked grandchildren still
   wouldn't be threaded — see #3.)
2. **Classpath threading.** `run-mutations-process` needs `:child-aliases` /
   `:process-worker-dir` so its forked workers can load instrumented (storm)
   code. These must be threaded from `heretic.edn` → core → the worker spawn.

## 3. Core idea: the process executor *replaces* the sandbox

`run-mutations-process`'s own docstring (core.clj:336): *"a DIRECT core path: it
is its own isolation (per-worker copies), so it is invoked WITHOUT the
`heretic.sandbox/mutate-in-sandbox!` wrapper."*

The process executor is a **superset** of the sandbox: per-worker copies give the
same filesystem isolation, plus process-kill isolation. So running `:process`
*inside* the sandbox = **double-copy** (sandbox copy, then each worker copies
again) for one isolation goal. The clean composition is to **bypass the outer
sandbox** when `:executor :process` and let the pool be the isolation.

```
bb mutate  (:executor :process)
  └─ storm-capable runner   (no sandbox wrapper)
       └─ heretic.core/mutate!  → run-mutations-process
            ├─ worker JVM 1  (cwd = its own copy)   ← per-worker isolation
            ├─ worker JVM 2  (cwd = its own copy)
            └─ …                                       kill+respawn on timeout
```

## 4. Key design decisions

### D1 — Opt-in first, default later
Wire `:process` as **opt-in** (`:executor :process` in `heretic.edn`); leave
`:legacy` the default. Once it's exercised e2e on a real target, consider making
it the default in a follow-up (see §8). Keeps this change bounded and reversible.

### D2 — Always copy: preserve the "working tree untouched" guarantee
This is the load-bearing decision. `bb mutate` today guarantees the working tree
is never mutated (sandbox copies first). But the process executor's **N=1 path
(`evaluate-single-worker`) mutates the REAL working tree** (restore-based) — fine
for the documented direct/throwaway-dir use, **not** acceptable for `bb mutate`.

**Resolution:** the bypass path must guarantee every worker — *including the
singleton* — runs against a copy. Recommended: route `bb mutate :executor
:process` through the pool with a **minimum of one copied worker** (treat N=1 as a
1-copy pool), so the real tree is never on the hot path. The in-place
`evaluate-single-worker` stays available for the library/direct use (caller
points it at a throwaway dir), but `bb mutate` never uses it.
*(Confirm during step 1 whether to (a) force a copy in `evaluate-single-worker`
when invoked from `bb mutate`, or (b) always use the pool path with N≥1 copies.)*

### D3 — Thread the worker ClojureStorm classpath
The forked workers load instrumented code, so they need storm on their classpath.
Thread `:child-aliases` (default `[:collect]` — the existing storm alias) and
`:process-worker-dir` from `heretic.edn` → `core/mutate!` →
`run-mutations-process` → `child-spawn-spec`. Today core.clj (~`:480`) calls
`run-mutations-process` with only `{:parallel-workers …}`; it must also pass the
child-classpath keys from config.

### D4 — A storm-capable entry for the `:process` path
The current `bb mutate` orchestrator is storm-free by design, but `core/mutate!`
pulls in `heretic.tracer` → storm, so the `:process` parent **must** run on the
storm classpath (even though only the workers instrument). Implication: the
`:process` branch of `bb mutate` runs under the storm alias (e.g. `clojure -M:collect`),
not the bare `clojure -M` the sandbox orchestrator uses.

## 5. Implementation steps

1. **Confirm the N=1 copy decision (D2).** Read `evaluate-single-worker` /
   `run-pool` end-to-end; pick "force a copy for `bb mutate`" vs "always pool".
   Add/adjust a small option so the bypass path is always working-tree-safe.
2. **Thread worker classpath through core (D3).** In `core.clj`, pass
   `:child-aliases` / `:process-worker-dir` / `:child-deps` from `config` into
   `run-mutations-process` (don't drop them at the `{:parallel-workers …}` call).
   Add sensible defaults (`:child-aliases [:collect]`).
3. **Add the bypass entry point.** Either:
   - a new bb task `heretic:mutate:process` (explicit, lowest blast radius), or
   - branch inside `heretic:mutate` on `(:executor (load-config))`: `:process`
     → run the storm-enabled direct path; else → `mutate-in-sandbox!` (today).
   Recommended: **branch inside `heretic:mutate`** so `:executor :process` in
   `heretic.edn` "just works" from the one command. Run the `:process` branch
   under the storm alias (D4).
4. **Provide a Clojure entry** (e.g. `heretic.core/mutate-process!` or reuse
   `mutate!`) that the bypass task calls, ensuring config carries the worker
   classpath keys and the always-copy guarantee.
5. **Config defaults + validation.** Document and default the new keys; validate
   that `:executor :process` has a resolvable worker classpath (fail fast with a
   clear message if neither `:child-aliases` nor `:child-deps` resolves storm).
6. **Docs.** Update `docs/sandboxed-mutation.md` (when `:process` replaces the
   sandbox), `README` (executor options), and `docs/spec.md` (the relevant
   Phase-4 lines already ticked in #11).

## 6. Config surface (`heretic.edn`)

```clojure
{:executor :process            ; opt-in; default stays :legacy
 :parallel-workers 4           ; N worker JVMs (each its own copy); nil/1 = single copied worker
 :child-aliases [:collect]     ; clj aliases giving workers ClojureStorm + Heretic + deps
 :process-worker-dir "."       ; project root the workers copy from (default: cwd)
 ;; :child-deps {…}            ; alternative to :child-aliases: an -Sdeps map
 :mutation-timeout-ms 30000}   ; per-mutant deadline before destroyForcibly
```

## 7. Verification plan

ClojureStorm makes the full suite prone to hanging, so verify on a **scoped**
target like `bb self-test` does:

1. **Unit** — the worker-classpath threading and always-copy logic are
   plain-`clj` testable (the process-worker/pool harness already injects effects;
   `bb test:fast` covers `process-worker-test` / `process-pool-test`).
2. **E2e, scoped** — run `bb mutate` with `:executor :process` against a small
   target (e.g. `validation/sample`, the #9 smoke target) and assert: working
   tree clean afterwards (`git status`), results produced, no orphan JVMs.
3. **Infinite-loop reclaim** — include a target with a known loop-inducing mutant
   (or inject one) and confirm it resolves as `:timeout` via `destroyForcibly`
   without hanging the run.
4. **Parity** — N=2 process results equal sequential for the scoped target
   (status + `:killed-by-all` per mutant) — #9 already proved this in the harness;
   re-confirm through the `bb mutate` entry.
5. **Regression** — `bb test:fast` stays 520/0; `clj-kondo` 0/0.

## 8. Rollout

- **Phase 1 (this branch):** opt-in `:executor :process` via `bb mutate`, always
  working-tree-safe, verified scoped e2e.
- **Phase 2 (follow-up):** run it on a real target (e.g. babel), measure wall
  time vs the in-process executors (the ~2.8 s child boot amortizes on large
  targets), then decide whether `:process` becomes the recommended/default
  executor for `bb mutate`.

## 9. Risks & open questions

- **Boot cost.** Each worker pays ~2.8 s ClojureStorm boot; on tiny targets the
  process executor is *slower* than in-process. Mitigation: it's opt-in; document
  the trade-off; the boot amortizes on large targets and per-worker.
- **Classpath drift.** If `:child-aliases` doesn't actually put storm + Heretic +
  the target's deps on the worker classpath, workers fail to load instrumented
  code. Mitigation: fail-fast validation (step 5) + a clear error.
- **Double-copy if mis-wired.** If the bypass branch is missed and `:process`
  runs inside the sandbox, you get copy-of-copy. Mitigation: the entry-point
  branch (step 3) is the single decision point; test asserts no sandbox dir is
  created on the `:process` path.
- **Orphan JVMs.** A crashed parent could leak worker JVMs. #9 added a
  shutdown-hook orphan guard; re-confirm it fires on the `bb mutate` path.
- **Open:** D2 resolution (force-copy in single-worker vs always-pool) — decide
  in step 1.

## 10. Out of scope

- Making `:process` the default executor (Phase 2 decision).
- Socket-based distribution (superseded by EDN-over-stdio in #9; see #11).
- Watch mode (`heretic:watch`) on the process executor — a later, analogous wire.
- Any AI/Phase-4 work.
