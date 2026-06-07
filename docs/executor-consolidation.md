# ADR: Consolidate mutation executors — drop `:missionary`, keep `:legacy` + `:process`

**Status:** Accepted — 2026-06-07
**Area:** `core.clj` executor dispatch, `worker.clj`, `runner_process.clj`, `deps.edn`

## Context

Heretic ships three mutation executors, selected by `:executor` in `heretic.edn`
(`core.clj`):

- **`:legacy`** (default) — `java.util.concurrent` ExecutorService, in-process,
  per-*test* timeout. Dep-free.
- **`:missionary`** — `heretic.worker` (Missionary `m/ap` / `m/amb=`), in-process,
  per-*mutation* timeout + `:skip` / `:retry` / `:abort` supervision. The **sole**
  reason heretic depends on `missionary/missionary`.
- **`:process`** (#9) — forked worker JVM(s) with per-worker filesystem copies and
  force-kill (`destroyForcibly`) on timeout. The only executor that can reclaim an
  *uninterruptible* CPU-loop mutant (`Thread.interrupt`/`future-cancel` can't stop a
  tight loop; `Thread.stop` is gone on JDK 20+).

Three executors is one too many. Which do we keep?

## Decision

Consolidate to **two**: **`:legacy` (default)** + **`:process` (opt-in robustness)**.
**Remove `:missionary`** and the `missionary/missionary` dependency.

## Evidence — full babel benchmark (2026-06-07)

Same target (babel, **1583 mutation sites**, `:preset :fast`), same shared coverage,
sequential, **only the executor varied**:

| executor | killed | survived | no-cov | timeout | score | wall |
|---|---|---|---|---|---|---|
| **`:legacy`** | **856** | 227 | 494 | 4 | **0.790** | **19.6 min** |
| `:missionary` | 824 | 259 | 494 | 4 | 0.761 | 34 min |
| `:process` | 771 | 254 | 494 | 62 | 0.752 | 36 min |

Survivor-set analysis (214 survivors agreed by all three executors):

- **`:missionary`'s kills are a strict *subset* of `:legacy`'s** — 0 mutants killed by
  missionary that legacy missed; it misses 32 that legacy kills; it uniquely kills
  **nothing**. And it is **1.7× slower**.
- **`:process`** misses 40 kills (as survivors) **+ 62 "timeouts"**. Those are *not*
  genuine non-termination — `:legacy` timed out only 4× on the same mutants; they are
  `:process` running covering tests **without early-exit** and hitting the per-mutant
  deadline (60s), turning fast kills into timeouts.
- The cross-executor divergence (~32–40 mutants) is **concentrated in babel's routing
  subsystem** (`quota` / `router` / `circuit` / `retry`) on boolean/predicate
  operators — timing/concurrency-sensitive tests whose verdict depends on the
  execution model (recorded in babel `docs/heretic-findings.md`).

A *scoped* run (cache namespace, 12 mutations) had misleadingly shown
`:legacy` ≡ `:missionary`. Only the full run revealed the strict domination.
**Lesson: verify executor equivalence at scale, not on a small scope.**

## Rationale

- **`:missionary` is strictly dominated** — slower, kills a subset, uniquely kills
  nothing, and is the only consumer of a reactive-streams runtime dependency. Its
  per-mutation timeout + supervision bought *no* accuracy here (it killed fewer, not
  more). Removing it sheds the dependency for zero capability loss.
- **`:legacy`** is the fastest and most sensitive in-process executor, dep-free, and
  already the default.
- **`:process`** is irreplaceable for one job — reclaiming an uninterruptible
  loop/crash mutant by killing the OS process — which neither in-process executor can.
  It stays **opt-in** (`:executor :process`), never the default: it is the slowest and
  most divergent. Follow-up: give it early-exit so it stops converting fast kills into
  per-mutant-deadline timeouts (the 62 above).

## Consequences

- Delete `src/heretic/worker.clj` + `test/heretic/worker_test.clj`.
- Remove the `:missionary` dispatch branch + `run-mutations-missionary` + the require +
  the `:supervision-policy` references from `core.clj`.
- Update `core_test.clj` (the `:executor :missionary` config tests).
- Drop `missionary/missionary` from `deps.edn`.
- `worker-supervision-design.md` (the Missionary file-level pool design) becomes
  historical for the executor — note it is superseded; process isolation lives in
  `process_worker.clj` / `process_pool.clj` / `runner_process.clj`, file-level
  parallelism in `:legacy`.
- Net executor set: **`:legacy` (default) + `:process` (opt-in)**.

## Notes

- This is the resolution of the "is Missionary still useful / should we drop legacy?"
  question: the data says the opposite of the initial instinct — **keep `:legacy`,
  drop `:missionary`**.
- A separate finding the benchmark surfaced: ~2–3% of babel's mutation verdicts are
  execution-sensitive (flaky routing/concurrency tests). That is a babel test-quality
  issue, not a heretic one — heretic's executor diversity merely *surfaced* it.
