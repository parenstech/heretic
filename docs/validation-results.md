# Heretic — Validation Results (measured)

A running log of empirical results from the validation program (`docs/validation-plan.md`).
Each entry replaces an *asserted* number with a *measured* one, or records a finding. Numbers here
were produced in-repo and independently re-run; raw output and the reproduction command are included so
any result can be checked.

| Gap | Status | Headline result | Artifact |
|-----|--------|-----------------|----------|
| **G1** soundness | **✅ FIXED** | ~20 unsound patterns removed/tightened; sound-only filter; tests green | this doc §2 + `validation-plan.md` §2 |
| **G1** wiring + recall | **🐞 FIXED + MEASURED** (5 targets) | filter was a **silent no-op** in prod (`mutation-site->zloc` ignored `:form-id` → 0% site resolution); fixed (0%→**100%**); measured recall **0 / 635 mutants** (158 on covered ops) — sound but ~zero recall on idiomatic Clojure | this doc §2.1 |
| **G1** true-equivalent rate | **MEASURED** (medley) | sound lower bound + suite-seeded differential oracle + suite cross-check: true-equivalent **bracket [4.7%, 13.3%]** (7/150 *proven* by read-identity — dead `.cljc` branches; +13 unproven joint survivors), **operator-concentrated**; suite-seeding took the Phase-1 gate **50%→100%** (0 generator misses); soundness invariant 0 violations; +8 sound coverage gaps | this doc §2.2 |
| **G4** schemata crossover | **done → module DELETED** | Marginal (speedup→1 as test cost grows); `heretic.schemata` removed | this doc §1 |
| G2 subsumption | **measured** (5 targets) | **target-dependent** (Δ −11pp … +29pp vs random); beats random on non-degenerate honeysql/data.csv, loses on uri → inconclusive, do NOT retire | this doc §5 |
| G3 clustering | **measured** (5 targets) | hardness-rep beats random on exactly **5/10** target×strategy pairs = a coin flip, no reliable signal → retire the hardness table | this doc §5 |
| G5 timeout | **wired + stability run** | per-test adaptive timeout + additive constant landed; **0 false kills**, timeout-only-kill base rate **3.7%** (first for Clojure) | this doc §4 + §6 |

---

## 1. G4 — Mutant-schemata payoff crossover (MEASURED → module removed)

> **Outcome: `heretic.schemata` was deleted (2026-06-05) on the basis of this measurement.** The benchmark
> (`bench/g4_schemata_bench.clj`) was removed with it. The numbers below are the recorded result; the method is
> described in "What we ran." Reproducing now would require restoring the module from git history.

**Question.** Heretic enables mutant-schemata only when a file has `(> (count file-mutations) 2)`, i.e.
**≥3 mutations/file** (`src/heretic/schemata.clj:374-384`, `should-use-schemata?`). That constant is asserted,
never measured. The survey (`docs/mutation-testing-survey.md` §3-G4) said no fixed count threshold is supported
by the literature, and *predicted N\* > 3* on the reasoning that Heretic competes against cheap `clj-reload`
rather than AOT recompilation.

**What we ran.** A microbenchmark (`bench/g4_schemata_bench.clj`) comparing, for one file with N arithmetic
mutation sites, the two arms exactly as the production worker performs them:

- **Traditional arm** — mirrors `evaluate-mutation-impl` (`src/heretic/worker.clj:58-88`) *verbatim*: for each
  mutation `apply-mutation! → reload-mutated-file! → run → revert-mutation! → reload-mutated-file!`. The second
  reload is in the worker's `finally` (so a mutant can't leak into the next), giving **two reloads per mutant**.
- **Schemata arm** — `schematize-file!` once (all N) → reload once → `N × (with-mutant id (run))` →
  `restore-file!` → reload once. **Two reloads total** for the whole batch.

Methodology: 3 discarded warmups + 12 recorded trials per point; `System/gc` between arms; arms sequential;
the file is reset to a pristine master string before every measured op; median + min/max reported. A
**per-mutant correctness gate** verifies the traditional-arm output value equals the schemata-arm output value
for the same active mutant *before* any timing is trusted. Loads under plain `deps.edn` — **no ClojureStorm,
no coverage index, no corpus** needed.

### Reproduce

The benchmark `bench/g4_schemata_bench.clj` was deleted along with the `heretic.schemata` module it exercised.
To re-run, restore both from git history (`git show <pre-removal-sha>:src/heretic/schemata.clj` and the bench
file) and invoke `clj -Sdeps '{:paths ["src" "target/g4-bench/src" "bench"] :deps {…}}' -M -m g4-schemata-bench`
under plain Clojure (no ClojureStorm needed). The arms and methodology are described under "What we ran" above.

### Results (independently re-run; two-run spread shown)

Correctness gate: **PASS** — all 20 swept mutations produced byte-identical output in both arms (including the
`*`→`/` mutants that yield ratios). Zero exclusions.

| N  | traditional_ms (median) | schemata_ms (median) | speedup |
|----|------------------------:|---------------------:|--------:|
| 1  | 8.8 | 7.4 | **1.19×** |
| 2  | 15.2 | 9.9 | 1.54× |
| 3  | 26.7 | 9.1 | 2.95× |
| 5  | 31.4 | 10.3 | 3.06× |
| 10 | 53.1 | 11.7 | 4.55× |
| 20 | 95.9 | 25.8 | 3.71× |

**Empirical crossover N\* = 1** (reproduced across three independent runs). Schemata is faster than the
traditional loop *even at a single mutation*, and the speedup grows to ~3–4.5× before flattening at N=20.

**Cost decomposition (medians):** `t_reload` ≈ **2.1 ms** (one `reload-mutated-file!`), `t_apply` ≈ **0.43 ms**
(one `apply-mutation!` write), `t_schematize` ≈ **20 ms** (one `schematize-file!` for N=20, ~1 ms/site). This
explains N\*=1: the traditional arm pays **2·t_reload ≈ 4.5 ms of reload per mutant**, while the schemata arm
pays one reload for the whole batch; dropping the second reload already outweighs the small single-site
schematize cost at N=1. The flattening at N=20 is `t_schematize` growing ~linearly with site count.

**Density dispatch tax (H2): +666%.** With all 25 sites schematized and a mutant live, calling the target fn in
a 1e6-iteration tight loop runs ~536 ms vs ~70 ms un-schematized — the `case *active-mutant*` dispatch on every
hot-path site adds ~6–7× per-call runtime at this extreme (~1 site/line) density. Confirms the survey's
Vercammen density-hazard warning.

### Interpretation — three findings

1. **The asserted `≥3` gate is mis-calibrated, and in the *opposite* direction from the survey's prediction.**
   The compile/reload crossover is **N\*=1**, not >3. The survey under-modeled the traditional arm as one reload
   per mutant; the *real* worker does **two** (mutate + revert), so even a single mutant benefits from the
   single-reload batch. On the compile/reload axis the gate should fire at N≥1, not N≥3 — the constant `3`
   forgoes a 1.2–1.5× win at N=1–2.

2. **A count gate is the wrong instrument; density-gate instead.** The +666% dispatch tax means that on hot,
   high-density covering tests the schemata arm can become a *net slowdown* despite winning the reload race. The
   total payoff is `reload_savings − dispatch_tax_on_covering_tests`, which a per-file count cannot see. This
   matches the survey (§3-G4) and the plan's open decision #6: replace `(> count 2)` with a predicate over
   *(reload/exec ratio, site density)*.

3. **The absolute saving is modest when covering tests are slow** (the normal case). The benchmark isolates
   compile/reload cost with a trivial 1-call run; both arms run the *same* N covering tests in production, so the
   test cost cancels in the difference. Schemata's benefit is essentially `(2N−2)·t_reload ≈ (2N−2)·2.1 ms` — a
   small fraction of total wall-clock once covering tests dominate. So N\*=1 says schemata *never hurts on the
   compile axis*, but it only *materially* helps when reloads are a meaningful share of run time (fast tests,
   many mutants/file) **and** density is low enough that the dispatch tax stays below the reload savings.

**Caveat — prescriptive, not descriptive.** Schemata is currently **dead code in the production worker**
(`grep -rn heretic.schemata src/` matches only `schemata.clj`; the worker always does per-mutant edit+reload).
So this crossover is *what the gate should be once schemata is wired in*, not a description of current runs.

### Ecological follow-up — does schemata earn its keep with real covering tests?

The crossover above isolates compile/reload cost with a trivial 1-call run. The decisive question for shipping
is what happens once *real covering tests* run: both arms run the same N tests, so test cost is paid identically
and can only **dilute** schemata's fixed reload saving. The benchmark's `test-cost-sweep` raises the per-mutant
covering-test cost W (at N=10) and measures the effect:

| W (per-mutant test cost) | traditional (ms) | schemata (ms) | speedup | schemata saving |
|---|---|---|---|---|
| 0 ms | 67 | 11 | **6.06×** | 56 ms |
| 2 ms | 87 | 33 | 2.62× | 54 ms |
| 10 ms | 175 | 118 | 1.48× | 57 ms |
| 50 ms | 566 | 513 | **1.10×** | 53 ms |

**The absolute saving is essentially constant at ~53–57 ms** — it is exactly the `(2N−2) ≈ 18` reloads that
schemata avoids, `× t_reload ≈ 2.8 ms`. It does **not** grow with test cost. So the *speedup ratio* collapses
toward 1.0 as covering tests get slower: schemata is a 6× win when tests are ~free, but only **+10% by the time
each covering test costs 50 ms** (still a fast test). For a realistic suite where covering tests take tens to
hundreds of ms, schemata buys a single-digit-percent wall-clock reduction — while carrying the +666% density
dispatch tax as downside risk and a whole dead module's worth of complexity.

### Recommendation (now settled by the ecological sweep)

- **The `≥3` constant in `should-use-schemata?` is unsupported** — drop it. On the compile axis the crossover is
  N≈1; on the total-wall-clock axis the gain depends on the *covering-test/reload ratio*, not a mutant count.
- **Schemata's value is marginal for any realistic suite.** Its saving is capped at `(2N−2)·t_reload` (~tens of
  ms per file), independent of test cost, so once covering tests cost ≳10 ms the win is <1.5× and shrinking. The
  honest call (plan decision #6) is therefore one of:
  - **(preferred) Don't wire it; consider deleting the `schemata` module.** It is dead code today, the upside is
    single-digit-% for typical suites, and the +666% density dispatch tax is a real downside on hot/dense files.
    Deleting removes a whole subsystem (and the AOT-incompatibility caveat) for almost no lost performance.
  - **If kept**, wire it behind a predicate over *(reloads-saved × t_reload) vs expected test time* AND *site
    density* — never the flat count — so it only activates for fast-test, many-mutant, low-density files and
    never on the dispatch-tax regime. This is strictly more code than it's worth given the measured ceiling.
- Either way, **do not re-architect the parallel worker to batch-by-file for schemata** — the measured payoff
  does not justify the complexity/risk. This is the load-bearing decision left for a human (plan decision #6).

---

*Method references: `docs/validation-plan.md` §3-G4 (design), §5 (guardrails); `docs/mutation-testing-survey.md`
§3-G4 (Vercammen STVR 2024 density net-slowdown; MuJava speedup-vs-count; Stryker low-N break-even).*

---

## 2. G1 — equivalent-filter soundness fix (APPLIED)

**Finding (from `validation-plan.md` §2).** The default-on equivalent-mutant filter (`equivalent.clj`,
reached via `controller.clj:180`) flagged ~20 of ~29 patterns **unsoundly** — it could mark a *killable*
mutant equivalent, which drops it from the run and **inflates the mutation score** (since the dropped mutant
leaves both numerator and denominator of `killed/(killed+survived)`). The existing test suite *encoded the
bug as correct* — e.g. it asserted `(= x x)`→`(not= x x)` was "equivalent" (it is `true` vs `false`).

**Fix applied.** Rewrote `src/heretic/equivalent.clj` to a **provably-sound-only** pattern set:

- **Tightened** the four arithmetic-identity patterns to binary calls with the identity element in the *tail*
  position (`binary-identity?`): `(+ x 0)`/`(* x 1)` stay flagged (genuinely equivalent), while the
  counterexamples `(+ 0 x)`, `(* 1 x)`, `(+ x 0 y)` are now correctly **not** flagged.
- **Removed** every unsalvageable pattern: boolean `and`/`or` swaps, `=`↔`not=`, both `nil?`/`some?` families,
  the `count`-boundary comparison swaps, `seq`/`empty?` swaps, the multiply/divide-by-**zero** swaps (the
  mutant *throws*), the dead `some->`↔`->` entry, and the file-name heuristic (`simple-equivalent-patterns`
  now empty). Deleted the now-unused private helpers (`non-negative-fn?`, `never-nil-fn?`, `literal-non-nil?`).
- **Hardened** `realizing-fn?` — dropped `str`, `reduce`, `apply`, and `doall` (each observable: `str`
  stringifies a LazySeq differently; `reduce` can short-circuit / diverge; `apply` forwards the typed coll;
  `doall` preserves input type so `(doall (map ..))` is a seq vs a vector). Kept only type-normalizing
  contexts (`vec into count dorun frequencies group-by sort sort-by`).
- **Kept** the genuinely-sound patterns: `rest`↔`next` as the collection arg of `some` (now positionally
  guarded via `some-collection-arg?`), `first`↔`last` on a 1-element vector literal, lazy/eager swaps inside a
  type-normalizing realizer, and single-value threading `(-> x)`↔`(->> x)`.

**Tests.** Rewrote `test/heretic/equivalent_test.clj`: kept the valid true-positives, converted each
removed-pattern assertion to a **soundness-regression** (must-never-flag) assertion, and added the §2
counterexamples as explicit regression tests (`(+ 0 x)`, `(* x 0)`, `(= x x)`, `(seq [])`,
`(< (count x) 0)`, `(doall (map ..))`, …). Result: **52 tests / 84 assertions, 0 failures**.

**Verification.** Full fast suite green: **`bb test:fast` → 479 tests / 1865 assertions / 0 failures**. Also
fixed a *pre-existing* latent bug this surfaced — `test/heretic/parser_test.clj` used `clojure.set/subset?`
without requiring `clojure.set`, so `bb test:fast` failed on a fresh load order regardless of this change;
added the missing `[clojure.set]` require.

**Residual / follow-up.** This makes the filter *sound* (zero false positives by construction) at the cost of
**recall** — the survey (§3-G1) notes a sound static detector should expect only single-digit-to-~30% recall.
The realized-recall measurement (§2.1 below) is now done.

---

## 2.1 G1 — the filter was inert, and its recall is zero on real code (FIXED + MEASURED)

Attempting to measure the §2 filter's realized recall on the validation corpus surfaced a **second, separate
defect**: the soundness rewrite made the filter *correct*, but the filter **never actually ran** in production.

**Bug — the filter was a silent no-op.** A mutation's `:coord` is **relative to its top-level form** (identified
by `:form-id`); `mutation_engine/apply-mutation!` anchors to that form via `find-form-by-id` *before* applying
the coord (`mutation_engine.clj:145-155`). But `parser/mutation-site->zloc` — the navigation the production
filter relies on (`controller/filter-equivalent-mutations`, `controller.clj:140-144`) — navigated the
form-relative coord **straight from the file root**, skipping the `:form-id` anchor. That resolves only the
*first* top-level form; for a mutation in any later form, `coord->zloc` returns **nil**, and
`equiv/filter-equivalent-mutations` treats a nil zloc as `:testable`. So on every multi-form file (i.e. every
real source file) the filter resolved **0%** of sites and flagged **0** mutations — it was dead weight. The unit
tests never caught it because they hand-build single-form zlocs (`(+ 1 (- 2 3))`), where file-root == the one
form, so navigation happens to succeed.

**Fix.** `parser/mutation-site->zloc` now anchors by `:form-id` (new public `parser/find-form-by-id`) before
applying the coord, exactly mirroring `apply-mutation!`; `mutation_engine` was refactored to reuse
`parser/find-form-by-id` (its private duplicate deleted — single source of truth for form navigation). Fall-back
to file-root navigation is preserved for sites with no `:form-id`. Regression test
`parser_test/test-mutation-site->zloc-multi-form` (a 3-form fixture, sites in distinct forms — fails pre-fix).
`bb test:fast` → **523 tests / 2088 assertions / 0 failures**; `clj-kondo` clean.

**Effect of the fix — site resolution 0% → 100%.** Re-running the production-identical zloc-fn
(`parse-file` → `mutation-site->zloc`) across five vendored targets:

| target | total mutants | zloc resolved (pre-fix → post-fix) | mutants on covered operators | flagged equivalent |
|--------|--------------:|:----------------------------------:|-----------------------------:|-------------------:|
| `clojure.data.csv` | 13 | 0% → **100%** | 2 | **0** |
| `medley` | 77 | 0% → **100%** | 31 | **0** |
| `honeysql` | 459 | 0% → **100%** | 102 | **0** |
| `lambdaisland/uri` | 73 | 0% → **100%** | 18 | **0** |
| `multins` (synthetic) | 13 | 0% → **100%** | 5 | **0** |
| **total** | **635** | — | **158** | **0** |

**Measured recall on real code: 0.** Even now that the filter runs, and even though **158 / 635** mutants use an
operator the sound patterns *cover* (`first`/`last` 75, `rest`/`next` 45, `->`/`->>` 24, `+`/`-` 11, `*`/`/` 2,
…), **not one** matches the required syntactic shape. The sound patterns demand contrived contexts that idiomatic
Clojure does not contain: a **literal** identity element in tail position (`(+ x 0)`), a **single-element vector
literal** (`(first [x])`), `rest`/`next` as the collection arg of `some`, a `map`/`mapv` swap immediately inside
a type-normalizing realizer (`(vec (map f xs))`), or a step-less thread (`(-> x)`). Real `first`/`rest`/`+` calls
operate on *bound* values, so they never qualify. This is the strong-negative end of the literature's
"single-digit-to-~30%, operator-concentrated" range (survey §3-G1): on these pure small/medium libraries the
realized recall is **0**.

**Positive control (the matcher is not vacuously dead).** To prove the 0 is corpus-driven, not a still-broken
matcher, a synthetic file containing the exact sound shapes —
`(+ x 0)`, `(* x 1)`, `(some pos? (rest xs))`, `(first [42])`, plus a non-equivalent `(+ x y)` control — runs
through the full fixed path: **4 / 4 equivalent shapes flagged** (plus-minus, mult-div, rest-next, first-last);
the `(+ x y)` control stays testable. The end-to-end path flags correctly when the shape is present; the corpus
simply lacks the shapes.

**Soundness, re-confirmed.** The prime directive held under real load: the now-live filter was exercised on 635
real mutants across 5 targets and flagged **0** — i.e. zero false positives, and (here) zero true positives too.

### Reproduce

```bash
# Per target (has a :heretic alias + collected .heretic index):
cd validation/<target>          # data.csv | medley | honeysql | uri | multins
clojure -M:heretic -e "$(cat /tmp/g1-diagnostic.clj)"   # generate → resolve zloc → filter (no test exec)
# Positive control (pure modules, base deps — no ClojureStorm):
cd <repo-root> && clojure -M -e "$(cat /tmp/g1-positive-control.clj)"
```

The diagnostic counts total mutants, zloc-resolution rate (the fix's effect), covered-operator mutants, and
flagged mutants; the positive control asserts the four synthetic equivalent shapes are flagged.

**Open decision (sharpened).** The filter is sound *and* (post-fix) genuinely runs — but it removes **0**
mutants on real code, so it is currently pure overhead + documentation. Three options: (a) keep it default-on as
cheap insurance (it adds one parse per mutant but is provably safe); (b) demote it to opt-in, since it earns
nothing on idiomatic code; (c) invest in a **TCE-style bytecode-identity** detector for real, sound recall — the
G1 Half-2/Half-3 work in `validation-plan.md` §3-G1, which this result re-motivates (static pattern recall is 0,
so dynamic/bytecode equivalence is the only path to non-trivial G1 payoff in Clojure).

---

## 2.2 G1 — the true equivalent-mutant rate (differential oracle + suite cross-check)

§2.1 measured the filter's *output* (0 flagged). This measures the **denominator** PR #16 left open: of all
mutants, how many are *actually* equivalent? Built per `docs/g1-recall-measurement-plan.md` (planreview Ready, PR #18).

**What was built (`src/heretic/oracle/*`).** Three small, mostly-pure namespaces, run with no ClojureStorm:
- `gen.clj` — a **seeded** (`java.util.Random` as a value, not an ambient RNG — the planreview guardrail),
  dependency-free structural generator. Small numeric ranges + short collections so `<`/`<=`, arithmetic, and
  `first`/`rest`/`take` distinctions are probable.
- `differential.clj` — the **differential oracle**. Extracts the mutant's top-level form IN MEMORY (no file write,
  no clj-reload), evals the mutated form to rebind the target var, observes original vs mutant on the same inputs
  (each call under a hard timeout — mutants introduce infinite loops; **realized via `pr-str`** so lazy-seq errors
  are caught AND type differences are observable, e.g. `map`→`mapv` prints `(…)` vs `[…]`), short-circuits at the
  first **witness**, then restores. A witness ⇒ **provably KILLABLE** (sound). No witness in N=200 trials ⇒
  *candidate-equivalent* (upper bound only). First-order **pure** fns only — a conservative static purity gate
  (`pure-defn?`) excludes interop/IO/atoms/nondeterminism; HOF / impure are `:oracle-not-applicable`.
- `suite.clj` + `harness.clj` — the **suite cross-check**: run the target's REAL test suite against each mutant
  in memory (var indirection means the suite picks up the rebound mutant), joined with the oracle verdict into a
  confusion matrix. No file mutation.

**Strengthened with the trio** (a naive first cut measured a weak **50%** Phase-1 gate — see the before/after below):
- `sound.clj` — the **sound (FP=0) lower bound**. `read-identity`: the JVM reader (`:read-cond :allow`) collapses a
  `.cljc` form exactly as the compiler does, so if the original and mutant READ to `=` data the mutation is in
  JVM-dead code (a `#?(:cljs …)` branch) and is *provably* equivalent. Plus `macroexpand-identity`. Every verdict
  is a proof of equivalence, not a failure-to-find.
- `harvest.clj` — **suite-seeded fuzzing**. Spy the target fns during one suite run to capture the REAL argument
  tuples (which reach the mutation site by construction), then feed those + small structural perturbations to the
  oracle. Reach is solved; the search only has to find infection nearby.
- `suite/verdict-selected` — **covering-test selection**. Run only the tests the coverage index maps to each
  mutant (`runner/tests-for-mutation`), not the whole suite — the scaling lever for large suites.

**Result on medley** (150 mutants, full operator set; deterministic, seed 42, N=200; `bb test:fast` 535/0).
The trio's effect, naive → strengthened:

| metric | naive oracle | **with the trio** |
|---|--:|--:|
| Phase-1 gate (oracle recall on suite-killed) | 50% (32/64) | **100% (64/64)** |
| generator misses (candidate-equiv the suite kills) | 32 | **0** |
| oracle killable (sound witnesses) | 38 | **72** |
| candidate-equivalent | 49 | **13** |
| sound lower bound (proven equivalent) | — | **7** |
| **true-equivalent bracket** | [0, ≤11.3%] | **[4.7%, 13.3%]** |

Strengthened confusion matrix (the 85 oracle-judged ∩ suite-clean mutants):

| | suite KILLED | suite SURVIVED |
|---|--:|--:|
| **oracle killable** | 64 | **8** ← coverage gaps |
| **oracle candidate-equiv** | **0** ← no generator misses | **13** ← joint survivors |

- **Suite-seeded fuzzing fixed the generator** — the Phase-1 gate went **50% → 100%** (zero generator misses):
  feeding the oracle the suite's real reaching arguments + perturbations makes it as strong as the curated suite
  at killing. The naive 50% is the recorded "before," and the reason input generation must be reach-aware.
- **The bracket is now closed and trustworthy: [4.7%, 13.3%].** The **lower** bound is **sound** — 7/150 mutants
  *proven* equivalent by `read-identity`, all `#?(:cljs …)` dead branches (e.g. `medley.core.cljc:195`, `:100`).
  The **upper** bound is 7 proven + 13 *unproven joint survivors* (killed by neither a 100%-gate oracle nor the
  suite) — concentrated, as the survey predicted, in `map`↔`mapv` via `apply` (`:125`,`:356`), terminal
  `rest`↔`next` (`join :259`, `:350`,`:356`), `keep`↔`filter` (`:354`), and `replace-nil-*` (`:200`–`:238`).
- **Soundness invariant holds: 0 violations.** No `read-identity`-proven mutant was killed by the suite (it never
  could be — the code is JVM-dead), empirically confirming the sound detector on 150 real mutants.
- **Byproduct (sound): 8 coverage gaps** — mutants medley's suite *survives* but the oracle *proves killable*
  (the stronger inputs found 2 more than the naive run). Real test holes; the equivalent-vs-coverage-gap split is
  the principled survivor triage `interpreting-survivors.md` assumes.

**Scope + limitation.** medley is the headline because its suite is fast plain `clojure.test`. Covering-test
selection (`verdict-selected`) is **built and mechanism-validated** (a 3-mutant uri slice runs correctly through
the coverage index), but a *full* generative (`test.check`) target still doesn't complete in budget: selection
cuts the test *count* per mutant, yet each covering **property** test is itself expensive, so generative targets
need test.check **trial-capping** *on top of* selection (uri full did not finish; the slowness is generative
covering tests + leaked CPU-loop threads from non-terminating mutants). That capping is the remaining lever for
uri/honeysql. The sound lower bound here is read/macroexpand-identity; full **TCE bytecode-identity** (Half-3)
would tighten it further but is not built.

**What it means for G1.** There *is* a small, real equivalent population in idiomatic Clojure — bracketed
**[4.7%, 13.3%]** on medley, with the proven floor entirely **dead `.cljc` platform branches**. It is dominated by
classes the current static filter (`equivalent.clj`) **cannot** match — dead `.cljc` branches above all, plus
realization-context `map`/`mapv` and terminal `rest`/`next` whose idiomatic forms the filter's narrow syntactic
guards miss (PR #16: 0/635 caught). The filter's zero recall is not because equivalents are absent — its patterns
don't model the equivalents that occur. The cheapest sound win is a **`.cljc` dead-branch detector** (now built as
`sound/read-identity` — it proves the single largest class); TCE (Half-3) would extend the floor.

### Reproduce

```bash
# medley — full pipeline (sound + suite-seeded oracle + whole-suite cross-check); fast plain-clojure.test:
clojure -Sdeps '{:paths ["src" "validation/medley/src" "validation/medley/test"]}' \
  -M -e "(require 'heretic.oracle.harness)
         (heretic.oracle.harness/-main \"validation/medley/src\" \"medley.core-test\")"
# with covering-test selection (3rd arg = .heretic dir) — needs flow-storm on the classpath to load the
# coverage index, instrumentation OFF (we only READ the pre-collected index):
clojure -J-Dclojure.storm.instrumentEnable=false -J-Dclojure.storm.instrumentAutoPrefixes=false \
  -Sdeps '{:paths ["src" "validation/uri/src" "validation/uri/test"]
           :deps {com.github.flow-storm/clojure {:mvn/version "1.12.0-9"}
                  org.clojure/test.check {:mvn/version "1.1.1"}}
           :classpath-overrides {org.clojure/clojure nil}}' \
  -M -e "(require 'heretic.oracle.harness)
         (heretic.oracle.harness/-main \"validation/uri/src\" \"lambdaisland.uri-test\" \"validation/uri/.heretic\")"
```

### Acted on the finding — `read-identity` wired into the default filter

This measurement found that the shipping equivalent filter caught **0** of the real
equivalents, and that the only equivalent class which actually occurs is JVM-dead
`.cljc` branches. So we **wired `read-identity` into the production filter** rather
than demote it: `equiv/read-identical?` (sound, FP=0) is now a second pass in
`controller/filter-equivalent-mutations`, after the static patterns, using the
in-memory form extraction promoted to `mutation-engine` (`original-form-string` /
`mutated-form-string`, shared with the oracle). `:filter-equivalent` stays
**default-on** — now justified, because it finally removes something.

**Verified in the production pipeline (medley, all operators):** `prepare-mutations`
with the filter **off** drops 0/150; with the filter **on** drops **7/150** — exactly
the `read-identity`-proven dead-branch equivalents. Before this change both were 0.
So the default filter went from inert to soundly removing the real equivalent class.
(The static 13 patterns still run first; they continue to contribute ~0 on idiomatic
code, kept only because they're sound and cheap.) Tests: `equiv/read-identical?` +
`engine/original-form-string`/`mutated-form-string` + a `.cljc` dead-branch
integration test; `bb test:fast` 538/0.

---

## 3. Kill-matrix runner — the G2/G3/G5 blocker (BUILT)

**Blocker.** G2 (dominator/subsumption reduction), G3 (clustering soundness), and G5's dominator-score guardrail
all need a **full kill matrix** — for every mutant, the *complete* set of tests that kill it. The live runner
early-exits on the first failing test (`runner.clj`), so it can only ever record one killer per mutant; the
exact-subsumption machinery in `subsumption.clj` (`complete-subsumption-analysis`, `find-dominator-mutants`,
`select-minimal-mutants`) was therefore dead code, callable only from tests.

**Built (no behaviour change to normal runs):**

- **`runner/run-tests`** gained `:no-early-exit?` — when set, it runs *every* covering test instead of stopping
  at the first failure, so `:failed`/`:errored` hold the complete killer set. Default path unchanged.
- **`runner/evaluate-mutation`** gained `:kill-matrix-mode` — sets `:no-early-exit?` and adds `:killed-by-all`
  (the union of failing + erroring tests) to each result, alongside the existing single `:killed-by`.
- **`runner/evaluate-mutations-full`** — runs the no-early-exit batch over a mutation set.
- **`subsumption/build-full-kill-matrix`** — turns `:killed-by-all` results into the exact
  `{mutant → #{killing-test-indices}}` matrix (degrades gracefully to single-killer when `:killed-by-all` is
  absent). **`subsumption/kill-matrix-analysis`** ties it to the dominator/minimal-set analysis in one call,
  finally exercising the previously-dead functions.

**Tested** through the *real* execution path (mutants killed by two distinct test vars, not mocked results):
`run-tests-no-early-exit-collects-all-killers`, `evaluate-mutation-kill-matrix-mode-collects-all-killers`,
`build-full-kill-matrix-uses-all-killers`, `kill-matrix-analysis-dominators-and-minimal` (verifies dominators,
greedy cover, and a 66.7% reduction on a hand-built 3-mutant matrix). Suite green: `bb test:fast` **483/0**;
`runner-test` **117/0** on a flow-storm classpath.

**What's left for G2/G3 to actually run:** only the **corpus** (`validation-plan.md` §1 dependency note item 2 +
§6 decision #2) — a small high-coverage Clojure target with collected ClojureStorm coverage. The end-to-end
driver is then thin: for each mutant `apply-mutation! → reload → evaluate-mutation {:kill-matrix-mode true} →
revert` (the worker's existing loop plus the one flag), then `subsumption/kill-matrix-analysis` over the results,
comparing `:minimal`/clustered/random arms against the full-pool dominator score.

**G5** still additionally needs the (separate, small) timeout wiring: route `timing/calculate-dynamic-timeout`
into `runner.clj`/`worker.clj` with the missing additive warmup constant (`validation-plan.md` §3-G5).

---

## 4. G5 — adaptive per-mutant timeout (WIRED)

**Done.** The dead `timing/calculate-dynamic-timeout` is now live, with the additive warmup constant the survey
identified as the missing piece (the 3.0× multiplier was already fine):

- **`timing/calculate-dynamic-timeout`** gained `:additive-ms` (default **4000**, the PIT/Stryker-style JVM/JIT
  warmup constant). Formula: `clamp(estimated*multiplier + additive-ms, base, max)`. No-estimate path keeps the
  conservative fallback (30000), deliberately *not* tighter than before (a too-tight timeout on unknown data is
  a false KILL — the field's worse error).
- **`runner/run-tests`** computes an **adaptive per-test timeout** via a new `per-test-timeout` helper, but
  ONLY when `:timing-data` has an estimate for that specific test; otherwise it uses the flat `:timeout-ms`
  verbatim. So a 50 ms test gets `50*3+4000 = 4150` ms of headroom instead of a flat 5000; the **default path
  (no timing data) is byte-for-byte unchanged**.
- **`worker.clj`** `mutation-timeout-ms` derives the per-mutation budget from `estimate-total-duration ×
  factor + constant`, **floored at the old flat 30000** (only ever raises it), capped at 120000.

**Adversarial review** (separate agent) confirmed the default path is preserved and the formula is correct, and
caught **two issues, both now fixed**: (1) the clamp applied the cap before the floor, so a configured flat
timeout above 30000 would collapse *below* itself — fixed by applying the floor last in both
`calculate-dynamic-timeout` and the worker helper (floor is authoritative; verified `base 50000 > cap 30000 →
50000`); (2) the worker helper had no committed tests — added six (`mutation-timeout-*-test`).

**Tests:** timing-test (+5: additive default/explicit/floor/cap/no-estimate), runner-test (+5: flat-preserved,
adaptive-rescues-a-slow-test, no-estimate-fallback, additive-override, evaluate-mutation-rescue), worker-test
(+6). Green: `bb test:fast` **488/0**; runner+worker+timing+collector on flow-storm **205/0**.

**Still open (corpus-gated):** the *false-kill stability* measurement (re-run a frozen revision N times, count
timeout-verdict flips) and the first Clojure timeout-mutant base rate — `validation-plan.md` §3-G5.

---

## 5. G2 & G3 — measured across 5 targets (REVISED: target-dependent)

The kill-matrix analysis (§3) now has **5 successful targets**: in-repo `validation/sample`, plus four external
libraries vendored from the offline `~/.gitlibs` checkouts — **medley 1.8.1**, **honeysql** (`honey.sql.helpers`),
**clojure.data.csv**, **lambdaisland/uri**. A sixth, **clojure.tools.cli**, did not finish (intractable — §5.6).
Each ran the full no-early-exit matrix → `kill-matrix-analysis` → G2 (`:minimal` vs full vs ≥20-seed random) +
G3 (hardness-rep vs random-rep), and restored its mutated source byte-perfect (working tree clean). Reproducers
under `validation/<target>/experiments/`.

> **The larger sample REVISES the 2-target conclusion.** With only sample + medley, both bespoke tables looked
> like they didn't beat random. Across 5 targets the picture is **target-dependent**, dominated by whether the
> kill matrix has real subsumption *structure*. Honest verdict in §5.4.

### 5.1 The matrices — degeneracy is the key variable

| target | mut | killed | surv | no-cov | timeout | **dominator reduction** | trad. score |
|---|---|---|---|---|---|---|---|
| sample.core | 57 | 44 | 10 | 3 | 0 | **0%** (degenerate) | 0.815 |
| medley.core | 150 | 108 | 38 | 0 | 4 | **0%** (degenerate) | 0.740 |
| honeysql `helpers` | 141 | 61 | 28 | 52 | 0 | **65.6%** | 0.685 |
| clojure.data.csv | 40 | 29 | 6 | 5 | 0 | **44.8%** | 0.829 |
| lambdaisland/uri | 101 | 61 | 29 | 0 | 5 | **29.5%** | 0.678 |

**Finding (matrix degeneracy):** the two earliest targets (sample, medley) had **0% dominator reduction** — every
killed mutant its own dominator, killed by a *disjoint* single test, so there is no subsumption structure to
exploit. The three `~/.gitlibs` targets have **real structure** (30–66% reduction): their mutants share killers.
Degeneracy is a property of small, clean, well-factored libraries, and it *flattens* any G2 effect — which is why
the 2-target read was misleading.

### 5.2 G2 — `:minimal` vs equal-size random (Gopinath ~13pp guardrail)

| target | `:minimal` count-red | min. dom-retention | random dom-retention | **Δpp** | exceeds 13pp? |
|---|---|---|---|---|---|
| sample | 52.3% | 0.477 | 0.349 | +12.8 | no |
| medley | 49.1% | 0.509 | 0.455 | +5.4 | no |
| **honeysql** | 37.7% | 0.667 | 0.451 | **+21.5** | **yes** |
| **data.csv** | 24.1% | 0.875 | 0.585 | **+29.0** | **yes** |
| **uri** | 65.6% | 0.326 | 0.433 | **−10.7** | no (negative) |

**Finding (revised):** the gap ranges **−10.7pp to +29pp** (mean ≈ +11.6 ≈ *right at* the ceiling). On the two
non-degenerate targets where it wins (honeysql, data.csv) `:minimal` **clears** the ceiling; on a third
non-degenerate target (uri) it is **worse than random**. So the bespoke RORG preset is **not reliably** better or
worse than random — its value is **target-dependent**, and the earlier "not justified" claim was a small-sample
artifact of two degenerate targets. (Count reductions are real ~24–65%; traditional retention >1.0 persists as a
small-pool artifact.)

### 5.3 G3 — static hardness representative vs random representative

Across all 5 targets × 2 strategies (10 pairs), the hand-coded operator-"hardness" representative beats a *random*
representative from the same cluster on **exactly 5 of 10** pairs:

| | operator strategy | location strategy |
|---|---|---|
| sample | worse | **better** |
| medley | worse | worse |
| honeysql | worse | worse |
| data.csv | **better** | **better** |
| uri | **better** | **better** |

**Finding:** hardness-rep vs random-rep is **a coin flip (5/10)** — the elaborate static hardness table carries
**no reliable signal** over picking a random representative. This supports the survey's G3 critique (hardness is a
*dynamic* per-mutant property, not operator identity). Aggregate cluster score-error stays low (0.06–0.14) but
masks **12–46% per-mutant inference error** (cancellation), strongest on honeysql's operator strategy (46%).

### 5.4 Verdict (revised across 5 targets)

- **G2 — inconclusive / target-dependent.** Not the clean "doesn't beat random" the two degenerate targets
  suggested: with real subsumption structure the `:minimal` preset can clearly help (honeysql +21.5pp, data.csv
  +29pp) *or* hurt (uri −10.7pp). A firm verdict needs more **large, non-degenerate** targets and the suite-size
  control. **Do not retire the subsumption preset on the strength of these numbers** — they don't support it.
- **G3 — the hardness ranking is no better than random** (5/10, a wash). This *does* support **plan §6 #5** for
  G3 specifically: replace the static `operator-hardness` table with random representative selection or
  *observed-kill-vector* clustering (the runner already collects `:killed-by-all`, so kill-vector clustering is
  now cheap to try).
- **Methodological headline:** matrix **degeneracy** (disjoint singleton kills on clean small libs) is the
  dominant confound — any subsumption/clustering study must report it and prefer targets where mutants share
  killers. All numbers remain *illustrative single-file targets*, not a powered study.

### 5.5 Side-finding: a real `.cljc` collector bug (fixed)

Standing up medley surfaced a genuine heretic bug: `collector/file->ns-symbol` read test-file ns forms with a
bare `(read rdr)` — **no `{:read-cond :allow}`** — so a `.cljc` test ns carrying a reader conditional (medley's
`(ns medley.core-test #?(:clj (:import …)) …)`) threw "Conditional read not allowed", was swallowed by the
surrounding catch, and the namespace was **silently dropped** from discovery → 0 tests collected → empty index.
The collector explicitly targets `.cljc` (`clojure-file?`), so this broke `.cljc`-tested projects under
`:test-namespaces :all`. **Fixed** to `(read {:read-cond :allow} rdr)` (verified: a reader-conditional ns now
parses). This is why heretic could mutation-test medley at all.

### 5.6 Side-finding: no-early-exit intractability + uninterruptible mutants (tools.cli blocked)

The **tools.cli** target did not finish, and *why* is itself a result. Two compounding limits of the
no-early-exit kill-matrix mode on a larger target (287 mutants, up to ~19 covering tests per coord):

1. **Cost.** No-early-exit runs *every* covering test for *every* mutant, so the full matrix needed ~22 min —
   over the single-run ceiling. Larger targets need **resumable / chunked execution with persisted partials**, or
   process-level parallelism.
2. **Uninterruptible infinite-loop mutants.** Some mutants (`swap-next-rest`, `swap-map-mapv`,
   `replace-nil-false`) turn bounded traversals into tight CPU loops. The runner's timeout is
   `deref future + future-cancel`, which **cannot interrupt** a non-interruptible loop — the threads leak and
   accumulate, inflating later mutations past the abort guard. Robust mutation testing of such code needs
   stronger isolation (a hard thread bound, or per-mutant process isolation). The G5 run (§6) hit the same issue
   and worked around it with a fresh JVM per pass.

Both are concrete, recordable limitations of the current runner on real-world-scale targets — candidates for the
roadmap, not blockers for the small/medium targets that succeeded.

> **Addressed (2026-06-05) — see `docs/runner-isolation-spikes.md`.** Five fixes were prototyped in parallel and
> measured. **Landed:** (1) `heretic.killmatrix` — resumable/chunked kill-matrix persistence (cost A is now
> durable + splittable; unblocks tools.cli via chunks); (2) a **daemon-thread test runner** so a leaked loop
> mutant no longer hangs JVM exit. **Strategic next build:** B3, a forked worker-JVM pool with `destroyForcibly`
> (the only measured fix that *reclaims* a runaway, at ≈0 steady-state overhead). **Rejected:** `Thread.stop`
> escalation (inert/removed on JDK 20+).

---

## 6. G5 — false-kill stability run (MEASURED)

The corpus-gated half of G5 (validation-plan.md §3-G5): does the timeout mechanism produce **false kills**
(verdict flips involving `:timeout` across repeated runs of an unchanged revision), and what is the Clojure
**timeout-only-kill base rate** that no published source reports? Run on the frozen **medley** revision (the one
target with real `:timeout` verdicts), `validation/medley/experiments/g5_stability.clj`.

**Result — clean and stable:**

| metric | value |
|---|---|
| repeats (fresh JVM each) | 4 passes (2× fixed-5000, 1× adaptive+4000, 1× c=0) |
| killed / survived / **timeout** | 108 / 38 / **4** — *identical every pass, every policy* |
| **overall verdict-unstable mutants** | **0** (zero flips across all passes) |
| **false-timeout count** (timeout↔non-timeout flips) | **0** |
| **timeout-only-kill base rate (Clojure, first reported)** | **4 / 108 = 3.7%** of killed mutants |
| the 4 timeout mutants | `swap-next-rest` ×3, `swap-first-last` ×1 — **genuine infinite loops**, stably caught |

**Findings:**

- **Zero false kills.** Across 4 passes and 3 timeout policies the verdicts were byte-identical. The 4 timeouts are
  *genuine non-termination* (sequence-traversal swaps that loop forever), caught stably at every timeout level —
  i.e. the timeout mechanism is doing its job, not flaking.
- **First Clojure timeout-only-kill base rate: 3.7%.** A small but real number; no prior source reports one.
- **Policy comparison — the additive constant is insurance this corpus doesn't need.** fixed-5000, adaptive
  (`est×3+4000 = 4300 ms`), and c=0 (`1000 ms` floor) all produced the *same* 4 timeouts and *zero* extra
  timeouts — medley's tests are fast enough that even the bare 1000 ms floor clips no correct test, so there are
  **no warmup-driven false timeouts here to differentiate the policies**. c=0 just ran ~3× faster (~66 s vs
  ~190 s/pass) because the infinite-loop mutants resolve at the lower timeout — a *cost* difference, not a
  correctness one. The +4000 ms constant remains the right default (it costs nothing on fast tests and protects
  slow/warming first-executions, which this corpus lacks), but on *this* corpus it is a confirmed **null result**
  for false kills — honestly reported as such.
- **Methodology limit (same as §5.6):** N-repeat no-early-exit is intractable with uninterruptible loop mutants
  (thread leakage → OOM/slowdown); mitigated by a fresh JVM per pass and using the cheaper early-exit verdict
  (the flip-stability question needs only the per-mutant verdict, not the full killer set). N is therefore modest
  (4 passes) — enough for the 0-flips finding, not a large-N study.

**Still open:** a slow-test corpus (or seeded near-boundary slow tests) would be needed to actually *exercise* the
warmup constant and show it preventing a false timeout — medley can't, because nothing in it is slow enough.
