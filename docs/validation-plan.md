# Heretic — Validation Plan & First Findings

This document turns the mutation-testing survey's "here is what the field measured" into Heretic's
"here is exactly what *we* will run." It carries one real result already in hand (the G1 pattern-soundness
audit, §2) and five per-gap experimental designs (§3) with concrete, runnable next steps (§4), shared
methodology guardrails (§5), and the human-call decisions (§6).

Survey reference throughout: `docs/mutation-testing-survey.md` (G1–G5, §1, §3, §4, §5). Code citations are
`file:line` against the live tree.

---

## 1. Prioritized roadmap

Ordered by leverage-vs-effort. "Leverage" = how load-bearing the claim is and whether a result changes
shipped behaviour. "Effort" is the feasibility scout's tier + size.

| # | Gap | What it validates | Tier | Effort | Can run today | Leverage |
|---|-----|-------------------|------|--------|---------------|----------|
| **0** | **G1 soundness audit** | The default-on equivalent filter is unsound (drops killable mutants → inflates score) | **now-in-repo** (analytic) | **done** | **already done — see §2** | **Highest: real correctness bugs, fix now** |
| **1** | **G4 schemata crossover** | The hardcoded `>=3`-per-file schemata gate vs measured wall-clock break-even | **needs-new-code** | **M** | **✅ DONE → `schemata` module DELETED** (marginal payoff; `validation-results.md` §1) | High: self-contained, exercises real code, no corpus |
| **2** | G5 adaptive timeout | Dead `calculate-dynamic-timeout`; missing JVM/ClojureStorm warmup constant; first Clojure timeout-mutant base rate | needs-new-code | L (S for the wiring/formula fix alone) | **✅ WIRED + reviewed** (`validation-results.md` §4); stability/base-rate corpus-gated | High: fixes false-kill flakiness; pure-fn + wiring is small |
| **3** | G2 subsumption / dominator | `:minimal` "~99% / ~40% fewer" vs random baseline + dominator score | needs-corpus | L | **✅ MEASURED on 5 targets — target-dependent (`validation-results.md` §5)** | High: the headline number; instrument now runs |
| **4** | G3 clustering soundness | Static hardness-representative selection beats random rep / equal-size random | needs-corpus | L | **✅ MEASURED on 5 targets — hardness = coin flip (`validation-results.md` §5)** | Medium: depends entirely on the G2 substrate |
| **5** | G1 precision/recall + TCE | Filter P/R/F1 on a labeled corpus; sound bytecode-identity alternative | needs-corpus-and-code | L | No (no Clojure equivalent-mutant corpus exists) | High value, but the heaviest corpus lift |

### Run this first: G4 schemata microbenchmark

Per the feasibility scout, **G4 is the single experiment to run first**. It is the only design that is
fully self-contained: it needs **no ClojureStorm**, **no coverage index**, and **no labeled corpus**, yet it
exercises Heretic's *actual* `heretic.schemata` and `heretic.reloader` code under the real Clojure compiler.
The scout verified the building blocks live: `reloader/reload-mutated-file!` ran 5 reload cycles in 19.8 ms
and `schemata/schematize-file!` + `restore-file!` round-tripped a real transform in 9.9 ms under plain `clj`.
It directly tests a shipped, uncalibrated constant — `should-use-schemata?` fires on `(> (count file-mutations) 2)`
(`src/heretic/schemata.clj:374-384`) — and the survey says no fixed per-unit count threshold is supported by
any literature (`docs/mutation-testing-survey.md` §1, §3-G4). It also surfaces an honest caveat we must report:
schemata is currently **dead code in the production worker** (`src/heretic/worker.clj:58-88` does per-mutant
edit+reload, never calling any schemata fn), so the measured crossover is *prescriptive* (what the gate should
be once wired) not descriptive of current runs.

### Shared-prerequisite dependency note

Three gaps — **G2, G3, and G5's stability/base-rate halves** — all block on the **same two missing pieces**:

1. **A full, no-early-exit kill matrix.** Every live runner path early-exits: `run-tests` stops on the first
   failing test (`src/heretic/runner.clj:237,266`) and `evaluate-mutation` records at most one killer
   (`src/heretic/runner.clj:349`). `subsumption.clj`'s own `build-kill-matrix` docstring admits the resulting
   matrix is degenerate ("at most one killer"). The Kill-Matrix-Mode functions
   (`complete-subsumption-analysis`, `merge-kill-matrices`, `select-minimal-mutants`, `find-dominator-mutants`,
   `src/heretic/subsumption.clj:600-753`) are dead — called only from `test/heretic/subsumption_test.clj`.
   **A ~40-line no-early-exit runner mode (gated behind a `:kill-matrix-mode` flag) unblocks all three.**
   > **✅ BUILT (2026-06-05).** `run-tests` now takes `:no-early-exit?`; `evaluate-mutation` takes
   > `:kill-matrix-mode` and populates `:killed-by-all` (the full killer set); `runner/evaluate-mutations-full`
   > runs the no-early-exit batch; `subsumption/build-full-kill-matrix` + `subsumption/kill-matrix-analysis`
   > turn those results into the exact dominator/minimal-set analysis (feeding the previously-dead
   > `complete-subsumption-analysis`/`find-dominator-mutants`/`select-minimal-mutants`). Unit-tested through the
   > real `run-tests` path (multi-killer mutants), `bb test:fast` 483/0. **G2/G3/G5 now need only the corpus**
   > (item 2) to run; the default early-exit path is unchanged.
2. **A small high-coverage Clojure corpus** with collected ClojureStorm coverage (and, for G1/G2's fault halves,
   a mined real-fault subset). No Clojure mutation benchmark exists (survey §4.5).
   > **✅ PROVEN ACHIEVABLE (2026-06-05).** Both pieces are done for the first targets: the runner is built (§1
   > item 1 above) and the corpus path works — `validation/sample` (in-repo) AND **medley 1.8.1** (vendored from
   > the offline git checkout, ClojureStorm coverage collected, 150 mutations) both ran the full kill-matrix
   > G2/G3 analysis end-to-end (`validation-results.md` §5). Remaining corpus work is just **more targets**
   > (data.json, honeysql) + the suite-size control, not new infrastructure. Setting up medley also fixed a real
   > `.cljc` collector bug (`validation-results.md` §5.5).

Build the no-early-exit runner **once**; G2 derives `:minimal`/`:use-subsumption`/random arms as *sub-selections*
of the one ground-truth `:comprehensive` matrix, and G3 replays its clustering strategies offline against the
same matrix. Build the corpus **once** (medley → data.json → honeysql) and all four corpus-gated gaps share it.

---

## 2. First finding: G1 soundness inventory (a REAL result, not a plan)

> **✅ RESOLVED — see `validation-results.md` §2.** The unsound patterns below were removed/tightened in
> `src/heretic/equivalent.clj`; the filter is now provably sound (zero false positives by construction). Tests
> rewritten to soundness-regression assertions; `bb test:fast` green (479/0). The inventory is retained here as
> the rationale for each change.

This section is **not a plan** — it is the merged output of the three-shard pattern-soundness audit of
`src/heretic/equivalent.clj`. The filter is **default-on** in the run path
(`src/heretic/controller.clj:180` `(:filter-equivalent config true)`, reached via `src/heretic/core.clj:424`),
and **silently drops** any mutant it flags, counting it only as `:equivalent-filtered`
(`src/heretic/controller.clj:289,295`). The audit found this drop path is fed by **mostly unsound patterns**.
**Lead with the unsound ones — these are correctness bugs to fix now.**

### 2.1 Pattern-by-pattern verdict

| Operator (equivalent.clj loc) | Soundness | One-line why | Counterexample (if unsound) | Fix |
|---|---|---|---|---|
| `:swap-plus-minus` (68-76) | **unsound — always-FP** | `(some #(= 0 %) (rest ...))` ignores position/arity; `-` is non-commutative | `(+ 0 n)`→`(- 0 n)`: `(f 5)` = 5 vs −5 | Require exactly 2 operands, literal `0` last; or delete |
| `:swap-minus-plus` (78-84) | **unsound — always-FP** | Same loose guard; `(- 0 x)` negation idiom misfires | `(- 0 x)`→`(+ 0 x)`: `(neg 3)` = −3 vs 3 | Require 2 operands, `0` last |
| `:swap-mult-div` by-one (87-93) | **unsound — always-FP** | Any operand `=1` matches; `/` non-commutative | `(* 1 x)`→`(/ 1 x)`: 5 vs 1/5 | Require 2 operands, `1` last (divisor) |
| `:swap-div-mult` by-one (95-101) | **unsound — always-FP** | Any operand `=1`; dividend-1 reciprocal idiom | `(/ 1 x)`→`(* 1 x)`: 1/4 vs 4 | Require 2 operands, `1` last |
| `:swap-mult-div` mult-by-ZERO (214-220) | **unsound — always-FP** | `*`→`/` makes mutant **throw** divide-by-zero, not return 0 | `(* x 0)`→`(/ x 0)`: 0 vs `ArithmeticException` | **Remove entirely** — premise is categorically wrong |
| `:swap-div-mult` div-ZERO (222-228) | **conditionally-sound** | `(/ 0 x)`==`(* 0 x)` only if divisor provably non-zero literal | `(/ 0 x)` with x=0 throws; mutant returns 0 | Require all later operands non-zero literals; else can't prove |
| `:swap-and-or` (104-112) | **unsound — always-FP** | literal `true` is the and-identity / or-annihilator → max divergence | `(and x true)`→`(or x true)`: `(pick 5)` = true vs 5 | **Remove** — no sound `and`/`or` swap with literal true |
| `:swap-or-and` (114-121) | **unsound — always-FP** | literal `false` is or-identity / and-annihilator | `(or x false)`→`(and x false)`: 5 vs false | **Remove** |
| `:swap-eq-neq` (124-131) | **unsound — context-dep** | `(= a a)`→`(not= a a)` is boolean negation (true↔false); NaN non-reflexive | `(same? 5)`: true vs false; NaN: false vs true | **Remove** — logic inverted |
| `:swap-rest-next` under `some` (136-146) | **conditionally-sound** | `(some p (rest c))`==`(some p (next c))` for the *collection* arg | predicate-position / shadowed `some` leaks | Guard arg position (3rd child) + resolve core/some |
| `:swap-next-rest` under `some` (148-156) | **conditionally-sound** | Symmetric; same value-equivalence | same predicate-position leak | Same positional + resolution guard |
| `:swap-nil-some` in `(not ...)` (160-169) | **unsound — always-FP** | mutant is `(not (some? x))` = exact negation of `(not (nil? x))` | `(present? 5)`: true vs false | **Remove** — `not` does not rescue exact negations |
| `:swap-some-nil` in `(not ...)` (171-180) | **unsound — always-FP** | **author's own comment admits non-equivalence** ("suspicious") yet active | `(absent? nil)`: true vs false | **Remove** — known-bug marker left live |
| `:swap-lt-lte` count≥0 (188-197) | **unsound — always-FP** | original `(< (count x) 0)` const-false, but mutant `(<= ... 0)` true at count=0 | `(never-neg-count? [])`: false vs true | **Remove**; also drop `Math/abs`/`.length` from `non-negative-fn?` |
| `:swap-lte-lt` count≥0 (199-208) | **unsound — always-FP** | mutant `(< ... 0)` always false; original true at count=0 | `(empty-coll? [])`: true vs false | **Remove** |
| `:swap-nil-some` contract (235-242) | **unsound — always-FP** | "nil? always false" → mutant `some?` is **always true** (different constant) | `(bad-str? 5)`: false vs true | **Remove**; `namespace`/`keyword`/`symbol` can return nil |
| `:swap-some-nil` contract (245-252) | **unsound — always-FP** | "some? always true" → mutant `nil?` always false | `(ok-str? 5)`: true vs false | **Remove** |
| `:swap-seq-empty` literal (281-287) | **unsound — always-FP** | `seq`/`empty?` never value-equal; opposite truthiness on empty | `(seq [])`→`(empty? [])`: nil vs true | **Remove** both seq/empty? entries |
| `:swap-empty-seq` literal (290-296) | **unsound — always-FP** | inverse of above | `(empty? [])`→`(seq [])`: true vs nil | **Remove** |
| `:swap-first-last` 1-elt vector (299-305) | **conditionally-sound** | `(first [x])`==`(last [x])`; guard fires only on inline 1-elt vector literal | none for matched set | Acceptable as-is; optionally accept 1-elt list literals |
| `:swap-last-first` 1-elt vector (307-313) | **conditionally-sound** | symmetric; sound for matched set | none | Acceptable as-is |
| `:swap-map-mapv` realized (259-261) | **unsound — context-dep** | `realizing-fn?` wrongly lists `str` (stringifies LazySeq object) and `reduce`/`apply` (short-circuit) | reduce+`reduced` over `(map f [..99])`: 3 vs throw | Drop `str`, `reduce`, `apply` from `realizing-fn?` |
| `:swap-mapv-map` realized (263-265) | **unsound — context-dep** | `str` of vector vs LazySeq diverges | `(str (mapv inc v))` "[2 3 4]" vs "LazySeq@.." | Same fix |
| `:swap-filter-filterv` realized (267-269) | **unsound — context-dep** | same `str`/`reduce` leak; `filterv` over `(range)` hangs | reduce+`reduced` over `(filter even? (range))`: 6 vs hang | Same fix |
| `:swap-filterv-filter` realized (271-273) | **unsound — context-dep** | `str` diverges | "[2 4]" vs "LazySeq@.." | Same fix |
| `:swap-thread-first-last` single-value (320-327) | **sound** | `(-> x)` and `(->> x)` both macroexpand to `x` with zero steps | — | None needed |
| `:swap-some-thread-first` (330-335) | **sound (vacuously — DEAD)** | operator keyword matches no real operator id; never fires | — | Delete or repoint; if repointed it becomes unsound (some-> short-circuits) |
| `simple-equivalent-patterns` file-name heuristic (399-407) | **unsound — context-dep (currently DEAD)** | flags any `0→1` if path matches `default|init|start|begin` (even directory names) | `src/.../initialize.clj` `(atom 0)`→`(atom 1)`: 0 vs 1 | Never wire `quick-equivalent-check`; delete heuristic |

### 2.2 Merged summary counts

Across all three audit shards (9 + 8 + 12 = 29 audited entries; the two file-name/controller-composition
entries in shard 3 are meta-checks, not patterns):

| | Sound | Conditionally-sound | Unsound |
|---|---|---|---|
| **Shard 1** (arithmetic + boolean + eq) | 0 | 1 | 8 |
| **Shard 2** (nil/some + rest/next + boundary + contract) | 0 | 2 | 6 |
| **Shard 3** (collection-literal + lazy/eager + threading + file-name) | 2 | 2 | 7 (+ 1 meta) |
| **Total (zipper patterns)** | **2** | **5** | **~20** |

**Overall verdict: SEVERE / structural false-positive risk.** Only two patterns are unconditionally sound
(`:swap-thread-first-last`, and the vacuously-dead `:swap-some-thread-first`). The two collection-literal
patterns (`:swap-seq-empty`/`:swap-empty-seq`) and the four lazy/eager patterns are **wired into production
today** and produce false positives on common idioms. The recurring root causes:

- **Loose arithmetic guards** ignore operand position/arity while `-` and `/` are non-commutative.
- **Multiply-by-zero** → divide makes the mutant **throw** (the single easiest mutant to kill).
- **Boolean** patterns misread Clojure's value-returning, short-circuiting `and`/`or`: a literal `true`/`false`
  is exactly the identity/annihilator that makes them diverge maximally.
- **Constant-original fallacy**: the author verified the *original* is a constant but never checked the
  *mutant* evaluates to the *same* constant (nil?/some? are exact negations; count<0 / count<=0 flip at count=0).
- **File-name heuristic** has zero semantic basis.

### 2.3 Score-inflation trace through `controller.clj` (the mechanism)

Traced end-to-end and confirmed:

1. `prepare-mutations` (`src/heretic/controller.clj:151-189`) generates all mutations, records
   `total-found`, then at line 182 calls the local `filter-equivalent-mutations`
   (`src/heretic/controller.clj:128-149`), passing `filter-equiv?` defaulted **true** at line 180.
2. That delegates to `equiv/filter-equivalent-mutations` (`src/heretic/equivalent.clj:368-393`), which
   `group-by`s every mutation into `:testable` vs `:equivalent` via `likely-equivalent?`
   (`src/heretic/equivalent.clj:354`), returning `{:mutations testable, :filtered equivalent, :filtered-count n}`.
3. The controller keeps **only** `:mutations` (testable); the `:equivalent` bucket is **discarded**, surviving
   only as the `:filtered-count` integer. `prepare-mutations` returns `:mutations` = testable-only
   (`controller.clj:186`) — the sole set handed to the runner. **Dropped mutants are never compiled, never run.**
4. `runner/summarize-results` (`src/heretic/runner.clj:377-407`) computes
   `mutation-score = killed / (killed + survived)` over the *results of the tested set only*. A filtered mutant
   is in **neither numerator nor denominator**.
5. `aggregate-results` (`src/heretic/controller.clj:289-298`) builds survivors from `results` and tacks on
   `:equivalent-filtered` as a separate count **never folded into the denominator**.

**Net failure mode:** an unsound pattern that flags a **killable-but-surviving** mutant strictly *raises*
`killed/(killed+survived)` (e.g. 9/10 = 0.90 → 9/9 = 1.00 once the lone would-be survivor is dropped) **and**
erases that live mutant from the survivor report — a silent score inflation plus a hidden test-coverage gap.
This is the precise correctness bug the survey (§1, §3-G1) warns is *worse* than a missed optimization: a
sound detector (TCE/EMS) guarantees zero false positives; Heretic's table does not.

**Immediate action (independent of any further experiment):** delete the always-FP entries — start with both
collection-literal seq/empty? entries and the file-name heuristic, then the nil?/some? family, the boolean
`and`/`or` swaps, the count-boundary swaps, the multiply-by-zero entry, and `:swap-eq-neq`. Drop `str`,
`reduce`, `apply` from `realizing-fn?` (`equivalent.clj:35`). Add positional + core-resolution guards to the
two `rest`/`next`-under-`some` entries. This is a prerequisite to trusting any future mutation score.

---

## 3. Per-gap experimental designs (G1–G5)

Each design states hypothesis, what-to-measure, metrics (with guardrails), baselines, corpus, procedure,
infra to reuse vs build, threats, feasibility, and expected outcome.

### G1 — Equivalent-mutant detection: precision/recall, soundness audit, and a sound TCE alternative

**Hypothesis.**
- **H1 (effectiveness):** Treated as a binary classifier with "equivalent" as the positive class, the static
  filter achieves recall in the single-digit-to-~30% band a syntactic detector should reach (survey §3-G1(b):
  TCE ~30% C / ~54% Java), concentrated in a few operators.
- **H2 (soundness — load-bearing):** The filter is **not sound** — its false-positive rate (mutants flagged
  equivalent that are in fact KILLABLE) is > 0, so precision < 100%, and at least one FP is (a) killable by the
  existing suite and, worse, (b) coupled to a real fault. *§2 already supplies the static half of this answer:
  ~20 unsound patterns, every one with a verified counterexample. The empirical half quantifies the realized harm.*

**What to measure.** Half 1 — a MutantBench-style Clojure ground-truth corpus of `{mutant, original,
label∈{equivalent,killable}, label-source, rationale}` triples; run `equiv/likely-equivalent?` over every mutant
via the production zloc path and score a confusion matrix (positive = equivalent). Half 2 (matters more) — of
the mutants the filter flags equivalent, the fraction actually killable (a) by the existing suite, (b) coupled
to a fault; plus the `score(filter on) − score(filter off)` inflation delta on the same pool. Half 3 — whether a
bytecode-identity (TCE) check is implementable and **sound** on Heretic's substrate, and its recall + FP rate.

**Metrics & guardrails.** Recall, precision, F1, **per-operator recall distribution** (test the survey's
operator-concentration finding), killable-FP rate by existing suite, **fault-revealing-FP rate** (survey: only
~2% of killable mutants are fault-revealing — Chekam EMSE 2020), **mutation-score-inflation delta** (time-not-count
discipline does not apply here, but the delta is in score-points users read), TCE recall + FP=0 confirmation,
**human-label reliability (Cohen's/Fleiss' kappa)** plus the automated-oracle-coverage fraction. Guardrail:
the **equal-size random-drop baseline** — the filter removes K mutants; ~100 random K-mutant removals must show
the filter drops killable mutants *no more often* than random (survey §1 bullet 5, §4.3, adapted from sampling
to filtering); and a **suite-size covariate** on any score↔fault relation (Papadakis ICSE 2018).

**Baselines.** Random-drop (K-sized, ≥100 seeds); filter-OFF control (`:filter-equivalent false`,
`controller.clj:180`) as the score-inflation denominator; TCE sound detector as a precision ceiling (FP=0 by
construction); human-label kappa control (≥2 raters).

**Corpus.** Build the missing Clojure equivalent-mutant corpus (medley → clojure.data.json → honeysql →
clojure.core.match; defer malli/instaparse). Effort M-L: mutant generation is cheap; the **layered oracle** is
the cost — a `clojure.test.check` property-based differential oracle decides KILLABLE conclusively for pure fns
(any distinguishing input refutes equivalence), collapsing most manual labeling; only oracle-survivors and impure
mutants need ≥2-human classification. Plus a small hand-seeded/mined real-fault subset for the fault-revealing-FP.

**Procedure.** STEP 0 pin the behaviour (default-on, drop-on-flag — *done in §2.3*). STEP 1 pick targets.
STEP 2 generate the unfiltered pool (`:filter-equivalent false`). STEP 3 build ground truth via the layered
oracle (report oracle-vs-human fraction + kappa). STEP 4 score the filter via the exact production zloc
reconstruction (`parser/parse-file` + `parser/mutation-site->zloc`, `controller.clj:140-143`). STEP 5 the
soundness/FP audit — run each flagged-equivalent mutant with the filter off and count kills. STEP 6 fault
coupling. STEP 7 controls (random-drop, suite-size). STEP 8 TCE prototype (Half 3, below). STEP 9 reconcile.

**Infra — reuse:** `equiv/likely-equivalent?` (`equivalent.clj:354`); `equiv/filter-equivalent-mutations`
(`equivalent.clj:368`); `controller/filter-equivalent-mutations` zloc-fn (`controller.clj:128-149`);
`controller/prepare-mutations` with the filter off (`controller.clj:151`); `engine/apply-mutation!`
(`mutation_engine.clj:134`) + spit/revert (`:186`,`:195`); the runner path for the killable-FP run.
**Build:** corpus harness (generate, oracle, label, persist EDN); scorer (confusion matrix + per-operator recall
+ per-pattern FP attribution); soundness runner; **TCE bytecode-identity detector** (AOT the single mutated
namespace to a temp `classes/` dir or hook `DynamicClassLoader.defineClass` during the existing clj-reload, then
**structurally** diff emitted classes after normalizing gensym counters / line-number tables / source-file
attributes / `__init` ordering); random-drop generator + suite-size regression.

**Threats.** Ground-truth noise (humans 54-64% accurate on true equivalents, §3-G1(c)) — mitigated because a
KILLABLE label always has a witness, so it's sound; only EQUIVALENT labels carry residual risk, which biases
*against* H2 (conservative). Oracle incompleteness for impure/IO mutants. TCE soundness is **conditional** on
fixed compiler + settings — verify the bytecode verdict is invariant with ClojureStorm instrumentation on vs off,
and prefer running TCE on an **un-instrumented** compile of the *target* (the target ≠ heretic, so
`:instrument-prefixes ["heretic"]` need not cover it — confirm).

**Feasibility.** Tier needs-corpus-and-code, effort L, **cannot run fully today** (no corpus). The classifier and
run harness exist; a **partial Half-2(a) result needs NO corpus** — toggle `:filter-equivalent`, run the
flagged-equivalent mutants with the filter off on Heretic's own repo, count kills; any nonzero count refutes H2
immediately (a kill is a definitive witness). The TCE prototype is new but self-contained (M).

**Expected outcome.** Recall low-single-digit-to-~30%, operator-concentrated; **precision measurably < 100%**
(H2 confirmed — §2 already proves the static side); killable-FP small in absolute count but nonzero; score-inflation
delta a few tenths to low-single-digit points; fault-revealing FPs rare but catastrophic if found; TCE feasible at
FP=0 and recommended as the sound replacement/backstop (survey §3-G1(e)).

---

### G2 — Operator subsumption: `:minimal` "~99% / ~40% fewer" vs random + dominator score

**Hypothesis (each sub-claim falsifiable).** On a fixed Clojure mutant pool run with a full no-early-exit matrix:
**H1 (count)** `:minimal` yields 40% ±10% fewer mutants than `:comprehensive`; **H2 (traditional retention)**
`:minimal` retains ≥0.99 of the traditional score; **H3 (dominator retention)** `:minimal` *dominator*-score
retention is materially lower (<0.90; survey predicts E-selective dominators collapse to 0.63-0.79, Ammann FSE 2016);
**H4 (random)** `:minimal` does **not** beat an equal-size random-operator subset by more than the Gopinath ~13%
oracular ceiling (ICSE 2016) — i.e. the bespoke table adds little over random; **H5 (time)** wall-clock reduction
diverges from count reduction (~44%); **H6 (soundness)** on the real-fault subset `:minimal` detects no fewer
faults than `:comprehensive`. *H2-confirming + H4-failing = the number is technically true but the table is
unjustified.*

**What to measure.** Per arm (`:comprehensive`, `:minimal`, `:use-subsumption`, ≥100 random operator-subsets sized
to `:minimal`): mutant count after the equivalent filter; traditional score; **dominator score** (full no-early-exit
matrix → `find-dominator-mutants`); **wall-clock time** (generation + execution, decomposed); real-fault detection.
All retention computed on the **SAME pool** — run `:comprehensive` once as ground truth; reduced arms are
sub-selections (Zhang TOSEM 2022 reduced-set-from-full-matrix protocol), never re-runs.

**Metrics & guardrails.** Count-reduction %, traditional-retention, **dominator-retention**, **Gopinath delta vs
the 13.078% ceiling**, **wall-clock-reduction %** (time-not-count), real-fault-detection retention,
**suite-size-controlled correlation** for any cross-target score↔fault claim.

**Baselines.** Equal-size **random operator-subset** (≥100 seeds) *and* a mutant-count-matched **random mutant-subset**
(strict Gopinath/Zhang same-size); `:comprehensive` full pool as ground truth; suite-size covariate.

**Corpus.** Small high-coverage Clojure targets with collected ClojureStorm coverage (medley first; data.json,
honeysql) + a 10-20 fault buggy/fixed subset. Effort M (corpus) + L (fault subset).

**Procedure.** 0. **Cheap doc-fix today:** `subsumption.clj:41,90` call RORG "Relational Operator Replacement with
**Guard**"; canonical is "**Global**" (survey §3-G2(a)) — also add the caveat that the 3-of-7 ROR subsumption
"does not always hold" and is untested under Clojure truthy/falsey + polymorphic `=`. 1. Set up medley. 2. **Build
the no-early-exit `:kill-matrix-mode` runner** (the one missing piece). 3. Run `:comprehensive` once in matrix mode;
persist `{mutant → #{all killing tests}}` tagged by operator. 4. Compute ground-truth traditional + dominator score.
5-6. Derive `:minimal` and `:use-subsumption` as sub-selections. 7. Random baselines (operator + mutant-count
matched). 8. Gopinath delta. 9. Real-fault detection. 10. Suite-size covariate. 11. Repeat on targets #2-#3.
12. Synthesize the 7 metrics with CIs.

**Infra — reuse:** `controller/resolve-operators` (`controller.clj:39-83`) — single arm switch;
`controller/prepare-mutations` (`controller.clj:151-189`); `operators/operators-for-preset` + presets
(`operators.clj:993-1117`); `subsumption/minimal-operator-set` + `filter-by-operator-subsumption`
(`subsumption.clj:366-396,559-594`); `runner/tests-for-mutation` (`runner.clj:36`) — the full covering-test set;
`subsumption/build-kill-matrix`, `complete-subsumption-analysis`, `find-dominator-mutants`, `select-minimal-mutants`
(`subsumption.clj:600-753,968`) — **dominator math already written and unit-tested**; `runner/summarize-results`
(`runner.clj:377-407`). **Build:** the no-early-exit runner mode (variant of `evaluate-mutation`, `runner.clj:294`,
recording the FULL killer set, gated by `:kill-matrix-mode`); a sub-selection harness; a random-operator generator
(≥100 seeds, deterministic log); a `dominator-score` fn on top of `find-dominator-mutants`; a real-fault harness;
a 7-metric report with CIs.

**Threats.** Coverage-subsumption ≠ exact subsumption (the matrix is covering-test-complete, not suite-complete —
document as optimistic). Matrix runs are slow (no first-kill short-circuit) → small corpora only. Flaky tests
(survey §G5: ~4pp variance, 9% unknown) → run N times, take stable verdicts. Operator-count-matched vs
mutant-count-matched random measure different things — report both. **Clojure-semantics validity gap:** the
relational subsumption table (`subsumption.clj:38-55`) assumes total-order numeric comparison, but `=` is polymorphic
and conditionals are truthy/falsey, so the dominator graph itself may be wrong for Clojure — the empirical matrix is
the check. Equivalent-filter interaction (hold it constant across arms; measure post-filter).

**Feasibility.** needs-corpus-and-code, effort L, **not fully runnable today.** Two blockers: no corpus, and the
measurement instrument doesn't run (dead Kill-Matrix-Mode + degenerate single-killer matrix). The doc-bug fix and a
**Tier-A** demonstration (feed a hand-built full matrix to `complete-subsumption-analysis` + `select-minimal-mutants`
vs an equal-size random baseline) **run today** under plain `clj` (see §4).

**Expected outcome.** H1/H2 likely confirmed on ≥1 target (Offutt 4-selective analog: 99.84% / 41%). **H3 expected to
confirm** (dominator retention <0.90 — reveals the "99%" is redundancy-inflated). **H4 expected to fail or be
marginal** — if `:minimal` beats random by <13%, the bespoke subsumption table (`subsumption.clj:38-282`) is largely
unjustified (the single most important finding). H5 confirm (~44% divergence). H6 uncertain (low power).

---

### G3 — Static hardness-representative selection (clustering) soundness

**Hypothesis.** Clustering does not beat equal-size random by >13% (Gopinath); the **hardness representative** does
not beat a **random representative** from the same cluster.

**What to measure.** From a full no-early-exit kill matrix: per-strategy mutation-score error vs full and vs
equal-size random (`MSE_S`); per-mutant inference error (fraction of non-representatives whose inferred status ≠ true
status — exposes cancellation hidden behind a low aggregate score error); hardness-vs-random representative delta
(per-cluster inference error, sign test); fault-detection loss vs random.

**Metrics & guardrails.** `MSE_S` vs full and vs ≥100-seed random at the same k (Gopinath ratio vs 13%); per-mutant
inference error; hardness-vs-random rep delta (sign test); fault-detection loss vs random (only ~2% of mutants are
fault-revealing, Chekam). Time-not-count when costing the representative set vs the full set (cluster-prep overhead
included).

**Baselines.** Equal-size random k-mutant draw (≥100); random representative within each cluster (≥100); an
**observed-kill-vector clustering** (Jaccard agglomerative cut to matched k) as the upgrade candidate; suite-size
covariate for the fault half.

**Corpus.** Same shared corpus as G2 (medley first) + the mined real-fault subset for the fault half (effort L).

**Procedure.** Build the no-early-exit full-matrix mode (shared with G2/G5). Assemble the matrix and compute
ground-truth `MS_full`. Replay each static strategy offline via `clustering/cluster-mutations`,
`select-representative`, `infer-cluster-results` against true statuses → `MSE_S` + per-mutant inference error.
Compute the equal-size random baseline and the Gopinath ratio. **H3:** for every cluster size ≥2, compare the
hardness representative's inference error against ≥100 random representatives (sign test). **H4:** cluster on
*observed* kill-vectors and compare. Measure wall-clock of the representative set vs full. **H5:** on the real-fault
corpus check whether each fault-coupled mutant's true status is preserved by its representative vs random; control
for suite size. **Verdict:** DROP a strategy if its Gopinath ratio ≤13% AND its H3 sign test is not significant;
REPLACE static selection with kill-vector clustering if H4 beats all static strategies.

**Infra — reuse:** `clustering.clj` pure fns (`cluster-mutations`, `select-representative`, `infer-cluster-results`,
~lines 138-246) — they run today but yield no ground truth without the matrix; the same no-early-exit runner and
matrix assembly as G2. **Build:** the matrix mode (shared); the random/representative/kill-vector/fault harness; the
sign-test + Gopinath-ratio analysis.

**Threats.** No ground truth until the matrix mode exists; clustering quality confounded by the same
coverage-subsumption approximation; small real-fault subset → low power; non-determinism in kill vectors.

**Feasibility.** needs-corpus-and-code, effort L, **cannot run today** — same no-early-exit + corpus prerequisite as
G2. Practical path: build matrix mode (S-M), medley (S), baselines+stats (M), real-fault mining (L).

**Expected outcome (survey-aligned).** Likely that neither clustering nor the static hardness table beats equal-size
random by the Gopinath margin, and that observed-kill-vector clustering (if added) dominates the static strategies —
which would argue for replacing the static hardness table with kill-vector clustering, or dropping the reduction.

---

### G4 — Mutant-schemata payoff threshold (✅ RUN — see `validation-results.md` §1)

> **RESULT (measured, reproduced ×3).** Crossover **N\* = 1**, not the asserted `≥3`, and *not* the survey's
> predicted N\* > 3. The compile/reload crossover is at a single mutation because the real worker does **two**
> reloads per mutant (`apply`+`revert`, `worker.clj:58-88`), which the survey under-modeled as one. Density
> dispatch tax **+666%** at ~1 site/line (H2 confirmed). Correctness gate passed (byte-identical output, 20/20).
> Takeaways: the count gate is mis-calibrated *and* the wrong instrument (density-gate, per decision #6).
>
> **Ecological follow-up DONE** (`validation-results.md` §1, `test-cost-sweep`): schemata's saving is capped at
> `(2N−2)·t_reload` (~tens of ms/file) *independent of test cost*, so the speedup decays 6.06× → 1.10× as
> per-mutant covering-test cost rises 0 → 50 ms. **Verdict: marginal for any realistic suite — prefer NOT wiring
> it; consider deleting the `schemata` module** (decision #6), and in no case re-architect the parallel worker to
> batch-by-file for it. **Decision taken: `heretic.schemata` was DELETED (2026-06-05)** along with its test and
> the benchmark. The design below is preserved for historical reference only.

**Hypothesis.** **H1 (crossover):** there is a per-file mutant count N* above which the schemata arm
(schematize+reload once, then N dynamic-var rebinds) beats the traditional arm (N × [apply + reload + run + revert +
reload]), and N* ≠ 3 in general — specifically N* > 3 because Heretic competes against cheap `clj-reload`
(`reloader.clj:219`), not AOT recompile, so the shipped `>=3` gate (`schemata.clj:383`) fires *before* it pays off.
**H2 (density hazard):** per-rebind execution overhead rises super-linearly with mutant density (each schematized
site adds a `case *active-mutant*` dispatch on the hot path, `schemata.clj:90-122`); at high density the schemata arm
can be a **net slowdown** even at large N (the Vercammen 0.81× / 120% regime). **H3 (predictor):** N* is better
predicted by the file's reload/exec ratio and density than by a flat count.

**What to measure.** **Decomposed WALL-CLOCK** (`System/nanoTime`), never mutant count (survey §4.3: count and time
diverge ~44%). Traditional-arm total = Σ over N of [t_apply + t_reload + t_run + t_revert + t_reload_back].
Schemata-arm total = t_schematize + t_reload_once + Σ over N of [t_rebind + t_run] + t_restore, split into **fixed**
(schematize+reload-once) and **marginal** (per rebind) cost. Measure t_run on the *same* covering tests in both arms.
For density: hold covering-test cost ~constant and vary mutations-per-location and mutations-per-file, measuring the
`case`-dispatch tax `(t_run_schematized − t_run_unschematized)/t_run_unschematized` vs density.

**Metrics & guardrails.** `crossover_N_star`; fixed/marginal decomposition (N* ≈ fixed / (trad_per_mutant −
schemata_marginal); undefined when the denominator ≤ 0 = the density-hazard regime); `reload_to_exec_ratio` (the
survey's true controlled variable — H3); `dispatch_overhead_vs_density` (slope, super-linear? — H2); `net_speedup_ratio`
(>1 = faster); `gate_regret` (extra wall-clock the shipped count-gate incurs vs an oracle always-faster-arm gate).
Guardrails: **equal-cost random gate** (coin-flip the arm per file; the count-gate must beat it), **oracle gate**
upper bound, **suite-size covariate** when modelling N*, **fixed timeout** in both arms (neutralizes the dead
`calculate-dynamic-timeout`), **un-schematized run baseline** to isolate the dispatch tax. **>=10 trials after JVM
warmup, report median + IQR** (survey §G5: ~4pp/run nondeterminism; first reload is especially inflated — warm both
arms before timing).

**Baselines.** Equal-cost random gate; oracle (per-file always-faster-arm) gate; suite-size covariate; fixed-timeout
control; un-schematized run baseline.

**Corpus.** No real-fault corpus needed (pure performance crossover). In-repo today: `validation/src/sample/core.clj`
(+ test + collected coverage at `validation/.heretic/coverage/sample-core-test.edn`), plus Heretic's own
`src/heretic/timing.clj`/`runner.clj` as higher-density real targets. Optional external (medley/honeysql/data.json)
for a density/ratio spread. Effort S in-repo, M for externals.

**Procedure.** 0. Confirm schemata is unused in the live path (`grep -rn heretic.schemata src/` returns only
`schemata.clj`); document `worker.clj:58-88` as the traditional arm verbatim. 1. Build
`bench/heretic/schemata_bench.clj` (a script, not a deftest; `System/nanoTime`; 3 warmup + ≥10 recorded; `(System/gc)`
between arms; sequential). 2-3. Generate the per-file mutant pool (`engine/mutations-for-file`,
`mutation_engine.clj:237`); for each N take the first N at **distinct** locations and a second variant at the **same**
location (separates per-file from per-location density for H2). 4. Traditional arm via the exact worker primitives.
5. Schemata arm via `schematize-file!` once + `with-mutant`/`run-mutation-batch`. **6. Correctness gate (mandatory):
killed/survived verdict must be IDENTICAL between arms before any timing is trusted** — if the `case` wrapper changes
a verdict (e.g. via a value-captured re-export the single schemata-reload doesn't refresh — the exact bug
`reloader.clj:147-217` fixes), the speedup compares non-equivalent work. 7. Density sweep (1,2,4,8,16 at a single hot
location vs distinct locations). 8. Baseline controls. 9. Fit N* ~ ratio + density + suite-size. 10. Recommend a gate.

**Infra — reuse:** `schemata/schematize-file!` (`schemata.clj:245`), `with-mutant` (`:285`), `build-schemata`
(`:191`), `run-mutation-batch` (`:314`), `should-use-schemata?` (`:374`, the predicate under test);
`engine/apply-mutation!` (`mutation_engine.clj:134`) + `revert-mutation!` (`:195`) + `mutations-for-file` (`:237`);
`reloader/init!` (`reloader.clj:43`) + `reload-mutated-file!` (`:219`); `runner/run-tests` (`runner.clj:126`) +
`tests-for-mutation` (`:36`); `coverage-map/load-index`; `worker/evaluate-mutation-impl` (`worker.clj:58-88`) — the
canonical traditional sequence to mirror. **Build:** the bench namespace; the verdict-equality assertion utility; the
N* fit + gate_regret analysis; (optional, separate, S-effort) wire schemata into `evaluate-mutation-impl` behind
`should-use-schemata?` to make the result ecological.

**Threats.** Schemata is **dead code** → results are prescriptive, not descriptive (report the S-effort wiring change).
JVM/JIT warmup + `clojure.test` nondeterminism → warmup discards + ≥10 trials + median/IQR or N* is noise. Coarse
filesystem mtime can make `clj-reload` silently skip a reload (`reloader.clj:219` docstring) → use forced
`reload-mutated-file!` (the harness does). Verdict drift (the mandatory equality gate). Density confound. Generalizability
(validation/sample is tiny — include ≥1 medium/high-density real file or H2 is left untested). Parallelism is out of
scope (per-file sequential cost).

**Feasibility.** **needs-new-code, effort M, CAN RUN TODAY.** No corpus blocker (in-repo coverage exists). The only
missing piece is the bench namespace; all primitives are public and verified live. See §4 for the exact invocation.

**Expected outcome.** Direction of gating is right (MuJava: 1 mutant → 1.05× ≈ no payoff; many → up to 9.37×) but the
**constant 3 is expected wrong (too low)** for Heretic — N* likely ~4-10 on low/medium-density files (H1 confirmed:
the shipped gate fires too eagerly). On high-density files expect the Vercammen net-slowdown regime (ratio < 1 even at
N=20 → motivates density-gating, not count-gating; H2 confirmed). N* should track reload/exec ratio + density far
better than a flat count (H3), giving non-trivial `gate_regret` for the constant-3 gate. A genuine surprise (N* ≤ 3
everywhere, or flat dispatch overhead) would be a publishable Clojure-specific deviation from the C/Java schemata cost
model — none has ever been measured.

---

### G5 — Adaptive per-mutant timeout: false-kill rate, the missing warmup constant, first Clojure timeout-mutant base rate

**Hypothesis.** **H1 (false kills exist, timeout-driven):** under the current fixed 5000 ms/test + 30000 ms/mutation,
re-running on the SAME unchanged revision N=20 times yields a non-empty set of **unstable** mutants (verdict flips
involving `:timeout`). **H2 (the constant, not the multiplier, is the fix):** `clamp(baseline*f + c, floor, cap)` with
the existing f=3.0 and an additive c calibrated to ClojureStorm warmup (~2000-5000 ms) reduces verdict instability vs
both (a) fixed-5000 and (b) the pure-multiplier c=0 formula, **without** moving the dominator/traditional score beyond
the run-to-run noise floor and **without** letting any seeded infinite-loop mutant survive. **H3 (base rate):** the
proportion of Clojure killable mutants killed **only** by timeout is measurable and small (single-digit %) — the first
such number for Clojure (survey §3-G5(e): "no source reports" this).

**What to measure.** For a frozen revision, run N times under several timeout policies; per mutant record the full
verdict distribution (killed-by-assertion / -error / -timeout / survived / no-coverage) plus per-test wall-clock
durations (already emitted as `:test-durations`, `:timed-out`, `:any-timeout`, `runner.clj:152-157`). Primary signals:
verdict **instability** across the N repeats (the field's ground truth for false timeouts, §3-G5(c)); the
**timeout-only-kill** count/identity; **wall-clock** per policy (time-not-count); seeded infinite-loop catch rate
(must be 100%); the baseline-test-duration **warmup tail** (first-exec vs steady-state) to calibrate c.

**Metrics & guardrails.** Unstable-mutant count + verdict-flip rate (**isolate the timeout subclass** from reload/
parallelism flakiness); timeout-only-kill proportion with bootstrap CI; **traditional AND dominator score mean±SD per
policy** (from a full no-early-exit matrix — survey guardrail); **wall-clock per policy** (time-not-count); seeded
infinite-loop catch rate; warmup-tail distribution (p90/p95/max → c = p95); false-kill reduction vs equal-cost controls.
Guardrails: the **c=0 pure-multiplier control** (isolates the additive constant), an **equal-wall-clock-cost framing**
(don't credit a stability gain bought purely by spending more time), and **fixed JVM/hardware/ClojureStorm version**.

**Baselines.** Fixed-5000/30000 status quo; pure-multiplier c=0 (the existing dead-code formula); a generous
fixed-30000 control (residual irreducible noise floor — survey: "10× inflation still leaves ~10% residual"); a clean
un-mutated baseline (separate from the reload-perturbed in-run EMA `timing.edn`).

**Corpus.** Heretic-on-itself today via the existing `bb self-test` sandbox (zero build, proves the harness), then
2-3 external high-coverage fast-suite targets (medley, data.json, honeysql) for the base rate and c generalization.
No real-fault mining needed (instability is measured on the unchanged revision). Effort L (S for the wiring+formula fix
alone).

**Procedure.** 1. Freeze a revision per target. 2. Capture a CLEAN un-mutated baseline (NEW code — none exists;
persist `baseline.edn` separate from the EMA `timing.edn`); set candidate c = p95(warmup-delta), sweep c ∈ {0,1000,
2000,4000,5000}. 3. Build the full no-early-exit matrix harness. 4. Seed K≥3 infinite-loop mutants per target
(expected = caught-by-timeout). 5. Wire `timing/calculate-dynamic-timeout` (`timing.clj:142`) into the per-test path —
extend its opts with `:additive-ms`, call it before `run-test-with-timeout` (`runner.clj:224,505`); set the per-mutation
worker timeout from `estimate-total-duration` × f + c (replacing the flat 30000, `worker.clj:108`). 6. Run the matrix:
for each policy ∈ {fixed-5000, c=0, calibrated +c sweep, generous-30000}, N=20 times. 7. Compute metrics. 8. Decision
rule: smallest c that drives timeout-subclass instability ~0, keeps seeded-hang catch 100%, doesn't move dominator score
beyond the fixed-5000 SD band, at acceptable wall-clock. 9. Replicate on external targets. 10. Land the wiring +
calibrated defaults; keep the harness as a CI regression check.

**Infra — reuse:** `timing/calculate-dynamic-timeout` (`timing.clj:142`, extend opts) + `estimate-total-duration`
(`:171`); the timing plumbing already wired for *ordering* (`controller/load-timing-data`, `build-test-config`,
`save-timing-data!`) so `:timing-data` already reaches `runner.clj:168`; `runner/run-tests` `:test-durations`/`:timed-out`
outputs (`runner.clj:152-157`); `controller/extract-test-durations` (`controller.clj:300`); `subsumption`
Kill-Matrix-Mode for the dominator score once fed a full matrix; `heretic.sandbox` + `bb self-test`;
`runner_test.clj` slow-test fixture as the seed-infinite-loop pattern. **Build:** the N-repeat stability driver; the
clean un-mutated baseline pass; the additive constant + per-test wiring; the no-early-exit matrix mode (shared with
G2/G3); the seeded infinite-loop fixtures; the instability + timeout-only-kill + bootstrap-CI analysis.

**Threats.** Confounded nondeterminism — Heretic's instability has multiple sources (parallel reload `worker.clj:118`,
coarse-mtime clj-reload, parallelism) — **isolate the timeout subclass** and also run sequentially. Baseline
contamination (the in-run EMA `timing.edn` includes reload cost — use a separate clean baseline or c is invalid).
Early-exit blocks a true kill matrix (`runner.clj:247-264,521`) → the dominator guardrail needs the no-early-exit mode.
ClojureStorm perturbs both the measured durations and the warmup tail → c is build/hardware specific; pin and revalidate
on CI. Seeded-loop realism (include both genuine hangs and near-boundary slow mutants). Small-N CI.

**Feasibility.** needs-new-code, effort L, **partial today.** The pure-fn formula comparison (current vs
constant-augmented) runs today under plain `clj` (see §4); the per-test/per-mutant duration + timeout data is already
emitted; the timing plumbing already reaches the call site, so the **wiring change is small (S, shippable
independently)**. The N-repeat stability harness, clean baseline pass, no-early-exit matrix mode, and seeded fixtures
must be built; the external-target base-rate corpus is the heavier lift.

**Expected outcome.** The 3.0× multiplier is fine (between PIT 1.25× and cargo-mutants 5×) — **not the lever**. Fixed-5000
produces a small but non-zero unstable set in the timeout subclass (tests whose first ClojureStorm exec spikes past
5000 ms). The c=0 control is not much better for fast tests (survey §5: floor-only is *less* lenient than PIT's
+4000 ms below the ~3-4 s crossover). The **additive c** (≈ p95 warmup delta, likely ~2000-5000 ms) is what drives
timeout-subclass instability toward zero (the survey's headline: the missing constant, not the multiplier). Adding c
should not move the score beyond the run-to-run SD band; seeded loops stay 100% caught. The Clojure timeout-only-kill
base rate is expected single-digit %. Surprises worth flagging: fixed-5000 shows zero instability (feature unnecessary);
instability dominated by non-timeout nondeterminism (re-scopes the work to reload determinism); or c far larger than
the field's ~4000 ms because ClojureStorm warmup is extreme (itself publishable).

---

## 4. What we can run today

Toolchain is verified working by the feasibility scout (`build_works: true`).

**Toolchain status.**
- **Two classpaths matter.** (1) **Plain `deps.edn`** (org.clojure/clojure 1.12.2) loads the pure modules —
  `schemata`, `reloader`, `subsumption`, `operators`, `mutation-engine`, `parser` — standalone; G4 + the subsumption
  math run here. (2) The **`:collect` alias** (`com.github.flow-storm/clojure` 1.12.0-1, ClojureStorm,
  `-Dclojure.storm.instrumentEnable=true`) is **required** for anything touching coverage:
  `heretic.coverage-map`/`registry.clj` import `clojure.storm.FormRegistry`, so requiring `heretic.controller`/`core`/`runner`
  under plain Clojure throws `ClassNotFoundException clojure.storm.FormRegistry` (verified). Any end-to-end mutation run
  (G2/G5 wall-clock) must use `clj -A:collect`.
- JDK Temurin 25; `bb`/`clj`/`clojure` present; deps resolve from `~/.m2` (no network). A real ClojureStorm coverage
  index already exists on disk at `validation/.heretic/` (index + meta + `coverage/sample-core-test.edn`) for
  `sample.core` — a ready-made tiny target.
- **WARNING:** do **not** `bb heretic:collect` on Heretic's own `src` — the full-suite ClojureStorm collect can **hang**
  (per `bb.edn`/the survey). `bb self-test` is deliberately scoped to `heretic.timing` only. Keep any collect scoped to
  `validation/sample` (12 fns, 14 tests, sub-second).
- **Fast-suite verification:** `bb test:fast` (or run `heretic.subsumption-test` directly: 61 tests / 403 assertions /
  0 failures).

### Runnable today, in priority order

**(1) G4 — schemata-vs-clj-reload microbenchmark (RECOMMENDED FIRST).** No ClojureStorm, no coverage, no corpus.
Generate a synthetic `.clj` with tunable mutant density; for N ∈ {1,2,3,5,10,20} compare wall-clock of N separate
`reloader/reload-mutated-file!` cycles vs one `schematize-file!` + one reload + N `with-mutant` switches +
`restore-file!`, finding the empirical crossover against the hardcoded `>=3` gate (`schemata.clj:383`). Write the
synthetic target **under the repo** (`target/g4-bench/`) to avoid the external-`:paths` deprecation warning.

```
mkdir -p target/g4-bench/src/demo
clj -Sdeps '{:paths ["src" "target/g4-bench/src"] :deps {io.github.tonsky/clj-reload {:mvn/version "0.7.1"} rewrite-clj/rewrite-clj {:mvn/version "1.1.47"} metosin/malli {:mvn/version "0.17.0"}}}' -M scripts/g4_bench.clj
```

The bench (script sketch from the scout) writes `demo.target` with N arithmetic sites, sweeps N and density
(`dense?` ⇒ ~1 site/line, the Vercammen regime), times the per-mutant-reload baseline vs the schematize-once arm, and
prints `N density baseline_ms schemata_ms speedup`. It already notes that per-switch run-time dispatch overhead is
**not** captured by the compile-time comparison alone — add `N × covering-test-runtime × case-dispatch-overhead` per the
density curve to model total payoff. Both round-trips verified live under this exact plain-`clj` classpath.

**(2) G2 — Tier A dominator/minimal math + random baseline (TODAY, plain `clj`).** Proves
`complete-subsumption-analysis` + `select-minimal-mutants` against an equal-size random baseline (the Gopinath
13% guardrail) on a **hand-built full kill matrix** — demonstrating the math end-to-end without the missing runner:

```
clj -Sdeps '{:paths ["src"]}' -M scripts/g2_killmatrix_tierA.clj
```

The sketch builds a `{mutant → #{tests}}` matrix with multiple killers, calls `complete-subsumption-analysis` and
`select-minimal-mutants`, then draws 100 equal-size random subsets and reports the dominator count, reduction %, and
random-equal-size full-cover rate. **Tier B** (the real matrix on `validation/sample.core`) needs the no-early-exit
runner you must write, and runs under `cd validation && clj -A:heretic` against the existing on-disk index.

**(3) G5 — pure-fn timeout formula comparison (TODAY, plain `clj`).** Compares Heretic's current dead-code formula
(`mult 3.0`, no constant) against a PIT/Stryker-style `clamp(baseline×1.5 + 4000, 1000, 30000)` on synthetic or saved
durations, printing the per-test delta and the exact wiring targets:

```
clj -Sdeps '{:paths ["src"]}' -M scripts/g5_timeout_compare.clj
```

Wiring targets the script names: `runner.clj:164/318/452` (per-test `:or {timeout-ms 5000}`) and `worker.clj:108-110`
(per-mutation `:or 30000`). The end-to-end verdict-stability + seeded-infinite-loop half is deferred to a `clj -A:collect`
run.

**(4) G1 — exercise (not measure) the detector (TODAY).** Run `equiv/likely-equivalent?` over `validation/sample`
under plain `clj` to confirm the ~30 patterns load and fire. **Measuring** recall requires the labeled corpus that does
not yet exist — not runnable until built. (The static soundness audit is already done — §2.)

**(5) Whole-pipeline run on `validation/sample.core` (TODAY, heavier, flow-storm classpath).**
`cd validation && clj -A:heretic -M -e "(require '[heretic.core :as h])(h/mutate! (h/load-config))"` runs the full
pipeline against the existing index — the substrate for real `:minimal`-vs-full wall-clock (G2) and timeout effects
(G5) once the no-early-exit mode lands. Recollect first if the index is stale. **Never point this at Heretic's own
`src`** (collect can hang).

> **Not runnable today:** G1 precision/recall (no corpus), G2 Tier B / G3 (need the no-early-exit runner + corpus),
> G5 stability/base-rate (need the harness + corpus). The G2 doc-bug fix (RORG "Guard"→"Global", `subsumption.clj:41,90`)
> and the §2 pattern deletions are editable today with no run.

---

## 5. Methodology guardrails (apply to EVERY experiment)

A compact checklist so no experiment forgets the survey's hard-won discipline:

1. **Measure WALL-CLOCK TIME, not mutant count.** Count and time diverge ~44% (survey §4.3). Any speedup/cost claim is
   in seconds (`System/nanoTime` / `:total-duration-ms`), reported alongside the count.
2. **Equal-size random baseline is mandatory** for any reduction/selection claim (Gopinath ICSE 2016: the oracular
   ceiling over equal-size random is ~13.078%). ≥100 deterministic seeds; report mean + 95% CI. For *filtering* tasks
   (G1), the adapted claim is "drops killable mutants *no more often* than random," not "reduces more."
3. **Report DOMINATOR mutation score, not just traditional**, for any subsumption/selection claim — which requires a
   **full no-early-exit kill matrix** (the shared missing instrument). Traditional retention can be redundancy-inflated.
4. **Control for test-suite size** (number of `deftest` / test-ns LOC) as a covariate in any score↔fault correlation
   (Papadakis ICSE 2018: correlations collapse from ~0.35-0.75 to ~0.05-0.20 once size is controlled). Never a bare
   correlation across targets.
5. **Validate against FAULT DETECTION, not just score preservation.** Only ~2% of killable mutants are fault-revealing
   (Chekam EMSE 2020); a reduction can preserve score yet drop the one fault-revealing mutant. A single such loss is the
   catastrophic outcome.
6. **Account for nondeterminism.** Re-run verdicts N times (≥10-20); treat unstable mutants as "unknown" and report the
   unknown fraction (survey §G5: ~4pp variance, 9% unknown). JVM/JIT warmup discards + median/IQR for timing.
7. **A KILLABLE label is sound (has a witness); an EQUIVALENT label is not.** Bias all ground-truth labeling toward the
   conservative direction; prefer an automated property-based differential oracle over human judgment, and report
   oracle-coverage + kappa for the human residue.
8. **Hold confounds constant across arms** — same mutant pool, same suite, same equivalent-filter setting, same fixed
   timeout, same JVM/hardware/ClojureStorm version. Toggle exactly one variable.

---

## 6. Open risks & decisions (need a human call)

1. **Fix the unsound equivalent patterns now (§2)?** The audit is conclusive — ~20 patterns are unsound with verified
   counterexamples, and the default-on filter silently inflates the mutation score. **Recommendation: delete the
   always-FP entries immediately** (collection-literal seq/empty? + file-name heuristic first, then the nil?/some?
   family, boolean swaps, count-boundary swaps, multiply-by-zero, `:swap-eq-neq`), drop `str`/`reduce`/`apply` from
   `realizing-fn?`, and tighten the two `rest`/`next` guards. This is a correctness fix, not an experiment — it does not
   wait on a corpus. **Decision needed:** delete vs flag-and-disable, and whether to default `:filter-equivalent` to
   `false` until the table is sound.
2. **Corpus scope.** Building a labeled Clojure equivalent-mutant corpus (G1) and a high-coverage + real-fault corpus
   (G2/G3) is the largest lift and gates four of five gaps. **Decision needed:** how many targets (the plan proposes
   medley → data.json → honeysql, deferring malli/instaparse), and whether to hand-seed faults to unblock the
   fault-revealing measurements before mining bug-fix commits.
3. **Add a TCE-style sound detector (G1 Half 3)?** A bytecode-identity check on the existing mutate-by-spit + clj-reload
   substrate could give a provably-sound ~30%-of-equivalents detector at FP=0. **Decision needed:** prototype it as a
   sound *backstop/replacement* for the static table, or keep only a heavily-pruned high-precision static table?
   (Open sub-question: ClojureStorm instrumentation determinism — must verify the verdict is invariant on/off.)
4. **Build the no-early-exit `:kill-matrix-mode` runner?** It is the single ~40-line piece that unblocks G2, G3, and
   G5's dominator/stability halves, but it makes runs far slower (no first-kill short-circuit). **Decision needed:**
   build it (gated behind a flag so normal runs stay fast) — almost certainly yes, given three gaps depend on it.
5. **Replace the static hardness/clustering table (G3) and the bespoke subsumption table (G2)?**
   **MEASURED on 5 targets (`validation-results.md` §5) — the answers diverge:**
   - **G3 (hardness table): retire it.** Hardness-rep beats a random rep on exactly 5/10 target×strategy pairs — a
     coin flip, no reliable signal. Replace with random representative selection or observed-kill-vector
     clustering (the runner now collects `:killed-by-all`, so kill-vector clustering is cheap).
   - **G2 (subsumption preset): DO NOT retire — inconclusive.** `:minimal` vs random ranges −10.7pp (uri) to
     +29pp (data.csv); it clears the ~13pp ceiling on the non-degenerate honeysql/data.csv but loses on uri. The
     effect is target-dependent and confounded by matrix degeneracy (clean small libs → 0% dominator structure).
     **Decision still needs** more large non-degenerate targets + the suite-size control before any change.
   - New roadmap items surfaced (§5.6): make no-early-exit mode resumable/chunked for large targets, and bound
     uninterruptible infinite-loop mutants (hard thread cap or per-mutant process isolation).
6. **Wire schemata into the live worker (G4)?** Schemata is currently dead code, so the measured crossover is
   prescriptive. **Decision needed:** after measuring N*, wire it into `worker/evaluate-mutation-impl` behind a
   *recalibrated* gate (a ratio/density predicate, not the flat `>=3` count), or delete the schemata module entirely if
   the crossover never pays off against cheap clj-reload.

---

*References: `docs/mutation-testing-survey.md` §1, §3 (G1-G5), §4, §5. Key external sources: Offutt TOSEM 1996
(selective mutation), Ammann FSE 2016 (dominator score), Gopinath ICSE 2016 (random baseline / 13.078% ceiling),
Zhang TOSEM 2022 (reduced-set-from-full-matrix), Papadakis ICSE 2018 (suite-size control), Chekam EMSE 2020
(fault-revealing ceiling), Vercammen STVR 2024 (schemata density net-slowdown), Berndt ICSE-SEIP 2024 / Shi ISSTA 2019
(timeout flakiness), Tian ISSTA 2024 (MutantBench P/R/F1 protocol).*
