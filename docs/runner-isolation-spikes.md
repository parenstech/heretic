# Runner isolation & scale — spike evaluation (2026-06-05)

Two runner limitations surfaced from the G2/G3/G5 validation runs (`validation-results.md` §5.6, §6):

- **A — no-early-exit cost / resumability.** The kill-matrix mode runs *every* covering test for *every* mutant,
  so it is slow and may not finish in one process on a large target (tools.cli: ~22 min / 287 mutants, never
  finished).
- **B — uninterruptible infinite-loop mutants.** Some mutants turn bounded code into tight CPU loops. The runner's
  timeout is `future` + `future-cancel` (= `Thread.interrupt`), which **cannot** stop a loop that never hits an
  interruptible point → threads leak, accumulate, and (with non-daemon `future` threads) hang JVM exit.

Five approaches were prototyped **in parallel, each in an isolated repo copy**, run against `validation/sample`
with a seeded infinite-loop mutant, and measured. A judge agent ranked them on the measured evidence.

## What was measured

| Spike | Mechanism | Key measured result | Verdict |
|---|---|---|---|
| **A1** resumable/chunked persistence | append each mutant's result to an NDJSON log; resume skips done; `:chunk i/n` splits deterministically | uninterrupted == kill+resume == 3-way chunk-split → **identical kill-matrix** (hash 638779480); **<1% overhead** (~0.25 ms/mutant) | **✅ LANDED** |
| **B3** forked worker-JVM + `destroyForcibly` | child loads index once, serves mutant-ids over a line protocol; parent kills+respawns on timeout | **reclaims a runaway in 35 ms** (in-process leaks +1 RUNNABLE thread, never reclaimed); **steady-state ~31 ms/mutant vs ~28–31 ms in-process** (≈0 overhead warm); boot/respawn ~2.8 s | **⭐ STRATEGIC — build next** |
| **A2** two-pass (early-exit → no-early-exit on killed only) | classify fast, then full-killers only for killed mutants | matrix **identical** to full; but **net-slower on sample** (98 vs 54 execs) because sample has 1 covering test/mutant; only wins on high-fanout targets | **deferred (opt-in)** |
| **B1** daemon-thread + leak/heap watchdog | run tests on daemon threads; bail when leaks/heap cross a threshold | daemon → **JVM exits cleanly** (exit 0 vs 124 non-daemon); watchdog bails at leak≥4; but **~3× slowdown** per 3 leaked spinners (CPU not reclaimed); heap sensor never fires (leaks are CPU) | **partial — daemon subset LANDED, watchdog rejected** |
| **B2** interrupt → `Thread.stop` escalation | last-resort `.stop()` after interrupt grace | **`Thread.stop()` throws `UnsupportedOperationException` on JDK 20+** (env is Temurin 25); reclaims nothing | **❌ REJECTED** |

## Decisions

### ✅ Landed now

1. **A1 → `heretic.killmatrix`** (`src/heretic/killmatrix.clj`, tests `killmatrix_test.clj`, in `bb test:fast`).
   A pure persistence + resume + chunk + analysis layer with the per-mutant evaluation **injected** (`:evaluate-one`),
   so it loads/tests without ClojureStorm. `run-resumable!` appends each result to an NDJSON log; a re-run skips
   logged mutants; `:chunk [i n]` runs a disjoint slice; `analyze-log` rebuilds `subsumption/kill-matrix-analysis`
   from the log alone. Includes the `assert-unique-keys!` guard the judge flagged (stable key drops the volatile
   UUID). Tests prove uninterrupted == resumed == chunk-split produce the identical matrix. **This makes any long
   kill-matrix run durable and splittable — it directly unblocks tools.cli (run it in chunks).**

2. **Daemon-thread test runner** (`runner/run-test-with-timeout`, the B1 subset). Tests now execute on a daemon
   thread (`run-on-daemon-thread`) instead of a `future`, so a leaked tight-loop mutant no longer **blocks JVM
   exit** (it still burns a core until the JVM dies — reclaiming the CPU mid-run is B3's job). Verified by
   `run-tests-runs-on-daemon-thread-test`.

### ⭐ Strategic — the real fix for limitation B

3. **B3 — forked worker-JVM with `destroyForcibly`.** ✅ **B3a (single worker) BUILT** — `heretic.process-worker`
   (generic spawn + newline-framed EDN protocol, all non-protocol output on stderr, per-request timeout →
   `destroyForcibly` → respawn; 8 layer-1 tests under plain clj), `heretic.process-worker-child` (load index +
   target nses once, serve mutants), `heretic.runner-process/evaluate-mutations-process` (parent holds the
   pristine source + restores it before respawn so a mid-apply kill can't corrupt the tree). Layer-2 smoke on
   `validation/sample`: load-once, a seeded infinite-loop mutant force-killed → `:timeout` while the parent stays
   healthy and finishes the queue, and exact verdict parity with in-process `evaluate-mutation`; ~0 steady-state
   overhead, no orphan JVMs, sample byte-identical.

   ✅ **B3b (N-worker pool) BUILT** — `heretic.process-pool` (generic N-worker pool over a shared key queue,
   per-worker kill+respawn) + `heretic.sandbox/copy-project!` (per-worker project copies incl. the `.heretic`
   index, `:local/root` absolutized to the shared real heretic). Each worker runs in its OWN copy and addresses
   mutants by stable `mutation-key` (each child regenerates mutations in its copy → no cross-copy path remapping
   → every copy self-consistent, the proven single-sandbox property). Wired as `core.clj :executor :process`
   with `:parallel-workers N` (N=1 = the unchanged single-worker path). **Correctness proof (reproduced ×2):
   the N=2 parallel result set EQUALS sequential `evaluate-mutations-full`** — same `:status` + `:killed-by-all`
   for every key (cross-contamination would break equality); a seeded loop mutant is killed+respawned in its
   worker (copy source restored) without corrupting the other; all copies deleted, no orphans. A boot
   `{:tag :ready}` handshake keeps the per-request deadline from counting the child's ~2.8 s ClojureStorm boot
   (that bug was the FAIL→PASS fix). Speedup is target-size dependent — N=2 on the tiny `validation/sample` is
   ~0.73× (two boots + copy overhead dominate sub-second eval); the win needs large targets where boot amortizes.
   **Follow-up:** the parallel==sequential proof ran on a single-namespace target, so the dependent-reload
   contamination path is only argued structurally — validate it on a multi-namespace target (e.g. a re-exporting
   facade, or honeysql) under N>1. Original plan retained below.

   The *only* spike that measurably reclaims a runaway,
   at ~zero steady-state overhead, and its work-queue structure natively hosts process parallelism (the README's
   "Pre-forked worker JVMs") and pairs with A1's chunking. It is the answer to the worker-supervision design doc's
   deferred **Open Question #1**. Implementation plan:
   - New ns `heretic.worker-process` (or extend `heretic.sandbox`, which already has `child-command` + the
     `ProcessBuilder` spawn): a child entry-point that loads the coverage index + target ns **once**, then reads
     mutant requests and writes one verdict per request.
   - **Robust framing** — the spike used stdout lines (fragile to library `*out*` chatter); production needs a
     length-prefixed protocol or a dedicated socket/named pipe, with all non-verdict output on stderr.
   - Parent: a pool of N children consuming a work queue; per-mutant timeout → `.destroyForcibly()` + respawn;
     feed `heretic.killmatrix/run-resumable!` results straight into the log (A1 + B3 compose).
   - Wire as a third `:executor` option in `core.clj` (alongside `:legacy`/`:missionary`); keep the in-process
     executors for the common fast-mutant case.
   - Tests: load-once amortization, kill+respawn reclaims a seeded loop mutant, verdict parity vs in-process.
   - Effort: **L**. Cost is the ~2.8 s boot/respawn (amortized across many mutants per child; only paid again on a
     hang) — acceptable given it is the only correct fix.

### Deferred / rejected

4. **A2 (two-pass): deferred as opt-in.** Correctness is proven (identical matrix), but the speedup is
   coverage-density dependent and was **net-negative on sample** (1 test/mutant). Revisit as an opt-in flag once
   measured on a high-fanout target (many covering tests per mutant) where early-exit short-circuiting actually
   pays; do **not** default it on.
5. **B1 watchdog: rejected** (only the daemon subset landed). Daemon-ness is the useful part; the watchdog does
   not reclaim CPU (~3× slowdown between bails) and its heap sensor is wrong (leaks are CPU, not heap). Superseded
   by B3.
6. **B2 (`Thread.stop` escalation): rejected outright.** `Thread.stop()` is an inert no-op that throws
   `UnsupportedOperationException` on JDK 20+ (env: Temurin 25); `Thread.stop(Throwable)`/`suspend`/`resume` are
   removed. It would ship dead, false-confidence code. The platform fact settles it.

## Net

Limitation **A is solved** for resumability/splittability (A1 landed) and **mitigated** for the JVM-hang
(daemon fix landed). Limitation **B is mitigated** (daemon — no more hung exit) and its **real fix is B3**, the
forked-worker pool, now the recommended next build with measured justification (≈0 steady-state overhead, 35 ms
reclaim).
