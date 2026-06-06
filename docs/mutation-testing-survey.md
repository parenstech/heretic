# Mutation Testing — State of the Art & Existing Projects (Survey for Heretic)

> **Purpose.** Heretic ships five optimizations whose payoff numbers are currently *asserted, not measured*: (G1) static equivalent-mutant detection, (G2) operator subsumption (`:minimal` preset), (G3) mutant clustering + representative selection, (G4) mutant-schemata threshold (applied only when a file has ≥3 mutations), and (G5) an adaptive per-mutant timeout that exists as code but is unused. This survey assembles the published, measured evidence the field has on each, so the asserted numbers can be replaced with calibrated ones. Throughout, **measured** = a number a primary study reports; **asserted** = a number Heretic states without local measurement. Claims the adversarial fact-checkers flagged are footnoted in §5 and should be down-weighted.

---

## 1. Executive summary

- **No published source defines a fixed "minimum mutants per file" schemata break-even threshold** like Heretic's ≥3; the literature models payoff as a function of the **compile/exec time ratio** and **mutant density (mutants per line)**, so Heretic's ≥3 is defensible folklore but uncalibrated ([Vercammen et al. 2024](https://onlinelibrary.wiley.com/doi/10.1002/stvr.1865)).
- **Schemata can be a *net slowdown***: Vercammen measured **0.81× (slower than unoptimized)** on dense CppCheck (0.564 mutants/LOPC) because switch-guard instrumentation added **120% execution overhead** — Heretic's dynamic-var switch carries the same density risk and should density-gate, not just count-gate.
- The closest direct evidence for Heretic's count-threshold direction: MuJava measured a class with **1 mutant → 1.05× speedup** vs **77–96 mutants → 9.37×** — few mutants per unit ≈ no payoff ([Ma/Offutt/Kwon 2005](https://www.albany.edu/faculty/offutt/research/papers/mujava.pdf)).
- Heretic's `:minimal` preset asserts **~40% fewer mutants at ~99% detection**; the near-exact measured analog is Offutt **4-selective: 99.84% mutation score at 41% reduction** — but this is *Fortran/Mothra, mean over 10 small programs*, and collapses to **88.71% at 60%** reduction, so it is plausible, **not a guaranteed constant** ([Jia & Harman survey](https://mutationtesting.uni.lu/TR-09-06.pdf)).
- **Random mutant sampling is a brutally strong baseline.** The maximum advantage of *any* reduction strategy over equal-size random sampling — *with perfect oracular kill knowledge* — is a mean of only **13.078%** (17.545% on distinguished mutants; ceiling 58.2%); real strategies do worse ([Gopinath et al., ICSE 2016](https://rahul.gopinath.org/resources/icse2016/gopinath2016on.pdf)). **Heretic's clustering and `:minimal` must beat a same-size random baseline or they are unjustified.**
- **Heretic's hand-coded operator "hardness" table (G3) is on the weakest ground of the five.** "Hardness" is empirically a *dynamic per-mutant* property (reachability × infection × propagation), not reliably predicted by operator identity, and there is **no evidence that "hardest-to-kill = best representative"** ([Visser et al., ASE 2016](https://dl.acm.org/doi/10.1145/2970276.2970345)). State-of-the-art representative selection clusters on *observed kill-vectors* or learned features (Cerebro, DM#), not static labels.
- **Soundness ceiling for any representative selection (G3):** only **~2% of killable mutants are fault-revealing**, and even the *ideal* minimal/subsuming set is only **~17% fault-revealing** — so any clustering scheme can silently drop the rare fault-revealing mutant ([Chekam et al., EMSE 2020](https://arxiv.org/abs/1803.07901)). Validate against **fault detection**, not just mutation-score preservation.
- **A sound static equivalent detector (G1) should expect single-digit-to-~30% recall, not high recall**, and that slice is operator-concentrated. TCE is sound (zero false positives, modulo fixed compiler settings) and detects **~30% of equivalents in C / ~54% in Java** — but **94.8% of its Java hits come from one operator (AOIS)**; remove AOIS and it finds **~4%** ([Papadakis et al. 2015](http://web4.cs.ucl.ac.uk/staff/Y.Jia/resources/papers/PapadakisJHT2015.pdf); [Kintis et al. TSE 2018](https://orbilu.uni.lu/bitstream/10993/31623/1/07882714.pdf); [UW PLSE/ISSTA 2024](https://homes.cs.washington.edu/~rjust/publ/equi_mutants_ems_issta_2024.pdf)).
- **Heretic's G1 detector is NOT sound by design** — its own source comments call some patterns "suspicious" (e.g. `swap-some-nil`), so it can produce false positives that *hide live mutants and inflate the score* — the exact failure mode TCE/EMS avoid. It is unit-tested for correctness only; no precision/recall measured.
- **Heretic's 3.0× adaptive timeout (G5) is within the field's safe band** (PIT 1.25×, cargo-mutants 5×, mutmut 15×) but **omits the additive constant every production tool adds** (PIT +4000ms, Stryker +5000ms, mutmut +1.0s) to absorb JVM/JIT warmup — to which Heretic, running on ClojureStorm, is *more* exposed.
- **G5 is confirmed dead code.** `calculate-dynamic-timeout` (`timing.clj:142`, multiplier 3.0, clamp [1000, 30000]) is called only by `timing_test.clj`; the runner hardcodes **5000ms** (`runner.clj:141`) and the worker **30000ms** (`worker.clj:108`). The timing data already flows through the runner, so wiring it in is the concrete fix.
- **The #1 methodological constraint for *all* of G1–G5:** mutation-score ↔ real-fault correlations collapse from **~0.35–0.75 to ~0.05–0.20 once test-suite size is controlled** — any Heretic correlation experiment must use partial correlation / regression with suite size as covariate or it will overstate gains ([Papadakis et al., ICSE 2018](https://coinse.github.io/publications/pdfs/Papadakis2018hi.pdf)).
- **There is no Clojure mutation-testing benchmark.** Every cited number is C/Java/Fortran; none of G1–G5 has a Clojure base rate (especially the Clojure-specific operators `->`/`->>`, `map`/`mapv`, keyword-case). Heretic must build a Clojure real-fault corpus to validate any of this — itself a research contribution.

---

## 2. The tool landscape

### 2.1 Comparison of production / reference tools

| Tool | Language | Coverage-based test selection | Schemata / bytecode | Timeout strategy | Equivalent-mutant handling | Operator reduction |
|---|---|---|---|---|---|---|
| **PIT / pitest** ([src](https://github.com/hcoles/pitest/blob/master/pitest/src/main/java/org/pitest/mutationtest/build/PercentAndConstantTimeoutStrategy.java)) | Java/JVM | Yes — per-test line coverage, tests ordered by speed, early-exit on kill | In-memory **ASM bytecode** mutation (no recompile, no schemata) | `round(normal×1.25) + 4000ms` | None (auto) | DEFAULTS operator group |
| **Stryker** (JS/.NET/4s) ([cfg](https://stryker-mutator.io/docs/stryker-js/configuration/)) | JS/TS, C#, Scala | Yes (`perTest`) | **Full mutant schemata** (mutation switching since 4.0) via global active-mutant flag; v6 hot reload | `netTime×1.5 + 5000ms + overhead` | None (auto); `ignoreStatic` for static mutants | Mutator config |
| **cargo-mutants** ([docs](https://mutants.rs/timeouts.html)) | Rust | **No** — package scope only | Compiles each mutant separately | `max(5×baseline, 20s)` | None | None |
| **Gremlins / go-mutesting** ([src](https://github.com/go-gremlins/gremlins/blob/v0.6.0/internal/engine/executor.go)) | Go | Coverage run first; NOT_COVERED reported | — | **coverage-run × 3** (default coefficient `DefaultTimeoutCoefficient = 3`) | None | Operator exclusion |
| **mutmut** ([docs](https://mutmut.readthedocs.io/)) | Python | Coverage to skip lines | AST round-trip | `(baseline + 1.0s) × 15.0` | None | None |
| **Infection** ([docs](https://infection.github.io/guide/usage.html)) | PHP | Yes (run only covered) | AST-based | **Flat 10s** (warns: set above test runtime) | None | None |
| **Major** ([site](https://mutation-testing.org/)) | Java | Per-test mutation coverage; tests prioritized by runtime | Compiler-integrated schemata-style | runtime + timeoutOffset minimum | None | Selective operators |
| **Mull** ([repo](https://github.com/mull-project/mull)) | LLVM/C/C++ | — | **Bitcode schemata** (all mutants → one binary) | — | Static AST junk/equivalent filter | — |
| **universalmutator** ([repo](https://github.com/agroce/universalmutator)) | Multi (regexp) | — | — | — | **TCE** to discard redundant/equivalent by default | — |
| **jstepien/mutant** ([repo](https://github.com/jstepien/mutant)) | **Clojure** | **No** — runs full suite per mutation | Source-form mutation | Full suite | None | None |
| **Cerebro** (research) ([arXiv](https://arxiv.org/abs/2112.14151)) | C/Java | — | — | — | ML predicts subsuming subset (~10% dominate) | Static subsuming selection |

### 2.2 Where Heretic sits — and whether it is novel

**Each of Heretic's two pillars is established prior art individually; their *combination* is genuinely novel for Clojure.**

- **Coverage-guided per-mutant test selection** traces directly to **Major** ([Just, ISSTA 2014](https://homes.cs.washington.edu/~rjust/publ/major_issta_2014.pdf)) and **PIT**: gather per-test coverage, run only tests reaching the mutant, order by ascending runtime, early-exit on first kill. Major demonstrated this at **>200,000 LOC / 150,000 mutants**. PIT's PR #534 documents a *correctness pitfall* Heretic's subexpression mapping must also avoid: line-level mutant↔test pairing was **unsound vs. blocks/instructions in the presence of exceptions**, forcing instruction-level mapping. Whether ClojureStorm's subexpression coverage has the same exception/block soundness hazard is **unverified**.
- **Source/AST mutation + per-function coverage selection** is established for other dynamic languages — **Cosmic Ray (Python)** uses the `ast` module plus per-function instrumentation. So Heretic's *pattern* is proven elsewhere; it is a first-for-Clojure *instance*, not a new paradigm.
- **Clojure competition is effectively nil.** The only prior dedicated tool, **jstepien/mutant**, is **archived (read-only since 2025-06-05)**, self-labelled "wildly experimental," and runs the **full suite per mutation with no coverage selection** — exactly the gap Heretic fills. The ClojureVerse community's only other answer was "maybe run PIT on bytecode."
- **The enabling substrate** — ClojureStorm / FlowStorm (a patched compiler emitting tracing bytecode) — already has a coverage precedent in **[Clofidence](https://github.com/flow-storm/clofidence)** (per-form test-coverage reports), strong evidence Heretic's coverage source is sound and a cross-check target.

**Net:** Heretic is the state of the art for Clojure *by default*. Its distinctive features (subexpression-level ClojureStorm coverage + `clj-reload` + Clojure-idiom operators + a schemata layer driven by a dynamic var) have no Clojure precedent — but every payoff number for those features is borrowed from C/Java/Fortran and must be re-measured.

---

## 3. Per-gap state of the art

### G1 — Static equivalent-mutant detection (false-positive / false-negative rates)

**(a) SOTA methods.** Four families, all benchmarked against small hand-classified corpora because equivalence is undecidable in general:
1. **Trivial Compiler Equivalence (TCE)** — compile each mutant; declare equivalent iff object code is byte-identical to the original (or to another mutant = duplicate). *Conditionally sound* (zero false positives modulo fixed compiler settings), low recall ([Papadakis et al. 2015](http://web4.cs.ucl.ac.uk/staff/Y.Jia/resources/papers/PapadakisJHT2015.pdf); [Kintis et al. TSE 2018](https://orbilu.uni.lu/bitstream/10993/31623/1/07882714.pdf)). Shipped in **universalmutator**.
2. **Constraint/SAT-based** (Offutt & Pan; **Medusa**, [Kushigian et al. ICST 2019](https://homes.cs.washington.edu/~rjust/publ/medusa_icst_2019.pdf)) — model the kill condition; UNSAT proves equivalence. Sound but limited to FO-definable fragments (heap/loops/method calls break it).
3. **Static data-flow / syntactic patterns** — **Heretic's family.** Kintis MEDIC/TeD; the **EMS** work ([UW PLSE 2025](https://uwplse.org/2025/01/06/ems.html)) used **10 sound suppression-rule groups** (from manually examining 1,992 mutants) to suppress **8,776 equivalents across 19 Java projects in 1.3h — >4× TCE+Soot's yield at ~1/2,200 the time**, sound except one `.equals` exception.
4. **ML/LLM learned classifiers** (Tian et al. ISSTA 2024; CPL 2025) — highest raw accuracy (**P/R/F1 ≈ 94/82/87% → 95/85/89%** on Java) but **not sound** and need labeled training data Clojure lacks.

**(b) Measured numbers.**
- TCE recall: **~30% (C) / ~54% (Java)** of known equivalents; prevalence detected **7.4% (C) / 5.7% (Java)** of *all* mutants, plus duplicates **21% (C) / 5.4% (Java)** ([Kintis TSE 2018](https://orbilu.uni.lu/bitstream/10993/31623/1/07882714.pdf)). The 54% is **operator-skewed: 94.8% from AOIS; ~4% without it** ([ISSTA 2024](https://homes.cs.washington.edu/~rjust/publ/equi_mutants_ems_issta_2024.pdf)).
- **Base rate of equivalent mutants** (the denominator): **8–25% of all mutants in C**; **~12.7% Java**, **~5.75% for human-created Java mutants** ([Mutation 2024](https://arxiv.org/html/2404.09241v1)).
- TCE false-positive rate: **0**, conditional on fixed compiler/optimization settings.
- Compiler-based baselines on MutantBench Java: TCE-Soot **P 51.3 / R 51.8 / F1 50.8**; TCE-Javac **40.6 / 38.2 / 39.3** ([Tian et al. 2024](https://arxiv.org/html/2408.01760v1)).

**(c) How the field benchmarks it.** Hand-classified ground-truth corpora — the canonical **MutantBench** (4,400 pairs, 1,416 equivalent; Tian preprocessed to 3,302 Java method-level pairs, ~15% equivalent). Metric: treat "equivalent" as the positive class, report **precision/recall/F1**; sound methods report recall only (precision = 100% by construction). Caveat: humans label equivalence unreliably — only **54–64% accurate on true equivalents** — so ground truth itself carries noise.

**(d) Heretic asserted vs. evidence.** Heretic asserts effectiveness implicitly (unit-tested for *correctness only*; `equivalent.clj` has ~30 syntactic patterns + 1 file-name heuristic). **No precision or recall has ever been measured.** The evidence says a sound static detector in this family should expect **single-digit-to-~30% recall**, concentrated in a few operators — so a flat ~30-rule table is the right *shape* only if rules target high-yield operators. **Worse, Heretic's rules are not all sound** — its own comments flag patterns as "suspicious" (`equivalent.clj:180`: *"(not (some? x)) is not equivalent to (nil? x), but the swap pattern is suspicious"*) — creating a false-positive risk (over-filtering live mutants → inflated score) that TCE/EMS explicitly avoid.

**(e) Verdict: UNKNOWN, with a correctness risk.** Effectiveness is unmeasured; the soundness property TCE/EMS guarantee is *not* present. **Concrete path:** a bytecode-identity (TCE-style) check fits Heretic's ClojureStorm/JVM architecture and would give a provably-sound ~30%-of-equivalents detector "for free," measurable against a constructed Clojure corpus.

---

### G2 — Subsumption reduction (`:minimal` preset: "~40% fewer / ~99%")

**(a) SOTA methods.** *Operator-level* selective mutation (drop low-value operators) and *mutant-level* subsumption (compute the dominator/minimal set from the kill matrix). The canonical sufficient set is the **5-operator "E-selective" set: ABS, AOR, LCR, ROR, UOI** ([Offutt et al., TOSEM 1996](https://dl.acm.org/doi/10.1145/227607.227610)). **RORG** (Relational Operator Replacement *Global*) is the standard ROR schema: **3 of 7 mutants** suffice per relational operator ([dextool](https://github.com/joakim-brannstrom/dextool/blob/master/plugin/mutate/doc/design/mutations.md)).

**(b) Measured numbers** (Offutt N-selective on 22-operator Mothra/Fortran, mean over 10 programs, via [Jia & Harman](https://mutationtesting.uni.lu/TR-09-06.pdf)):

| Strategy | Mutation score retained | Mutant reduction |
|---|---|---|
| 2-selective | **99.99%** | 24% |
| **4-selective** | **99.84%** | **41%** ← closest analog to Heretic's ~40%/~99% |
| 6-selective | **88.71%** | 60% |
| Wong & Mathur (ABS+ROR) | −5% score | **80%** |
| Namin (28 of 108 C ops) | predicts effectiveness | **92%** |

Mutant-level subsumption is far more aggressive: only **~10.2% of C mutants are subsuming** (Cerebro CoreUtils; **26.8% for Java**, so dataset-dependent). Static subsuming prediction is high-precision/low-recall: Cerebro **P 0.85 / R 0.33 / MCC 0.46**.

**(c) How the field benchmarks it.** The **exact subsumption relation requires a full kill matrix — every test × every mutant, no early-exit** ([Zhang et al., TOSEM 2022](https://arxiv.org/abs/2102.02978)); coverage-based selection yields only *coverage-subsumption*, an approximation. Build the **Dynamic Mutant Subsumption Graph (DMSG)**, report **dominator mutation score** (not raw score), and **always compare against a same-size random baseline**. Critically, selective mutation retains traditional score but **NOT** dominator score — E-selective dominator scores are only **0.63–0.79** ([Ammann et al., FSE 2016](https://www.albany.edu/faculty/offutt/research/papers/selectiveMut-FSE2016.pdf)) — so "99% detection" measured naively is inflated by redundancy.

**(d) Heretic asserted vs. evidence.** Heretic asserts **~40% fewer / ~99% detection**. The 4-selective row (41% / 99.84%) is a near-exact *numerical* match — but (i) it is corpus-specific Fortran, (ii) detection collapses sharply past ~41% reduction, (iii) it is *traditional* score, not dominator score, and (iv) random sampling rivals it. The match is real but should be read as a *historical data point*, not validation that Heretic's preset hits these numbers on Clojure.

**(e) Verdict: PLAUSIBLE but unproven, and likely OPTIMISTIC as stated.** The number has a strong literature analog, but it is unmeasured on Clojure, conflates traditional with dominator score, and has no random-baseline control. **Also reconcile the docs:** RORG's canonical name is "Global," not Heretic's gloss "Guard," and the 3-of-7 subsumption "does not always hold" (Lindstrom & Marki) — untested under Clojure's truthy/falsey + polymorphic comparison semantics. **To measure G2:** run one full no-early-exit pass, compare `:minimal` vs full killed-sets on the same pool, report dominator-score retention and reduction vs. random.

---

### G3 — Mutant clustering + representative selection (hand-coded "hardness" table)

**(a) SOTA methods.** Three approaches, none resembling a static operator-hardness table: (1) **dynamic subsumption** from the observed kill matrix (disjoint/dominator mutants); (2) **behavior-based clustering** on observed kill-vectors / output signatures (DM#, spectral clustering); (3) **learned fault-revealing ranking** (FaRM ranks mutants by fault-revelation probability, [Chekam et al. EMSE 2020](https://arxiv.org/abs/1803.07901)).

**(b) Measured numbers.**
- **The hard ceiling:** max advantage of *any* reduction strategy over equal-size random sampling, with oracular kill knowledge, is **mean 13.078%** (17.545% distinguished; theoretical 58.2%) over 39 Java projects ([Gopinath, ICSE 2016](https://rahul.gopinath.org/resources/icse2016/gopinath2016on.pdf)). "Blind random sampling … is highly effective … surprisingly little room for improvement."
- **Random ≥ operator selection** at predicting full score; **5% sampling → ~99% correlation, time cut to 6.54%** ([Zhang et al., ASE 2013](https://users.ece.utexas.edu/~gligoric/papers/ZhangETAL13BetterTogether.pdf)).
- **Soundness ceiling:** only **~2% of killable mutants are fault-revealing**; even the ideal minimal/subsuming set is **~17% fault-revealing**; FaRM reveals **+23% to +34% more faults** than random/selective baselines ([Chekam](https://arxiv.org/abs/1803.07901)).
- **Behavior-based clustering pays off (directional):** DM# = **28.38% time reduction at 0.72% score error**, with 11.78×/15.16×/114.36× *lower* error than random selection — **but in a DNN-mutation context (FFT of model outputs)**, not source-code mutation ([arXiv 2510.02718](https://arxiv.org/html/2510.02718v1)).
- "Hardness" driver: **reachability** dominates; the operator is only one of three contributors (reachability, operator, oracle) ([Visser et al., ASE 2016](https://dl.acm.org/doi/10.1145/2970276.2970345)).

**(c) How the field benchmarks it.** Full kill matrix → reduced set → report **score error / rank correlation** (R², Kendall τ, Spearman ρ) and cost reduction, averaged over **many random seeds against equal-size random sampling**. For soundness, the gold metric is **fault detection on held-out real faults**, not score preservation.

**(d) Heretic asserted vs. evidence.** Heretic ranks operators by a **static, hand-coded hardness table** and assumes hardest-to-kill = best representative — *only simulated, never validated on real code*. The evidence directly undermines both premises: hardness is a **dynamic per-mutant** property weakly correlated with operator identity, and **no paper supports "hardest = best representative."** Fault-revealing mutants are a *different ~2% slice* than the hard/subsuming mutants.

**(e) Verdict: OPTIMISTIC / unsupported as designed.** The static operator-hardness premise conflicts with the empirical finding that hardness is dynamic. **Concrete path:** Heretic already runs tests per mutant, so it *has* kill-vectors — cluster on **observed behavior** (or adopt FaRM-style learned fault-revelation ranking) instead of a static label, and benchmark against equal-size random + against fault detection. If clustering cannot beat random by a wide margin, the complexity is unjustified.

---

### G4 — Mutant-schemata payoff threshold (applied only when a file has ≥3 mutations)

**(a) SOTA methods.** Mutant schemata = inject all mutations of a unit into one program guarded by a runtime switch (Heretic: a dynamic var), compiling once. The alternative is **per-mutant in-memory bytecode swap** (PIT), which sidesteps the threshold question entirely.

**(b) Measured numbers.**
- Speedups: **Untch 4.1×** ([1993](https://www.albany.edu/faculty/offutt/research/papers/schema.pdf)); **MuJava 5.51× avg (combined), 6.79× MSG-only** ([2005](https://www.albany.edu/faculty/offutt/research/papers/mujava.pdf)); **Wang 6.46–14.00× (Java)**; Vercammen C/C++ **0.81×–5.16×**; Stryker **20–70%**.
- **Closest count-threshold evidence:** MuJava per-class — **1 mutant → 1.05×** vs **77–96 mutants → 9.37×**. Gain rises steeply with mutants/unit but the crossover count is never pinned.
- **Net-slowdown warning:** Vercammen CppCheck **0.81×** (slower than unoptimized) at 0.564 mutants/LOPC; switch-guard execution overhead **0.15–0.16% (<0.1/LOPC) → 17.44% (0.241/LOPC) → 120.29% (0.564/LOPC)** — grows superlinearly with density. Switch-case recommended over ternary for dense units ([Vercammen 2024](https://onlinelibrary.wiley.com/doi/10.1002/stvr.1865)).
- **Size of the prize:** recompilation was **4.3 of 11.8 days (~36%)** of an unoptimized 55,000-mutant run; schemata's *compile* cost is "only slightly longer" than one baseline compile.
- The only explicit low-N break-even is **Stryker's illustrative (not benchmarked) example: 3 mutants @ 10s compile = 30s without schemata vs ~15s with → net 15s saved at just 3 mutants** ([Stryker4s](https://stryker-mutator.io/blog/mutation-switching/)).

**(c) How the field benchmarks it.** Decompose wall-clock = generation + compilation + execution; sweep projects across a **mutant-density** spectrum; the controlled variables are **compile/exec ratio** and **mutants-per-line**.

**(d) Heretic asserted vs. evidence.** Heretic's ≥3-mutations gate (`schemata.clj`) is a **flat count constant**. **No source supports a fixed per-unit count threshold** — payoff is a function of compile/exec ratio and density. Critically, **all literature speedups are vs. ahead-of-time recompilation; Heretic competes against cheap `clj-reload`**, so the gap schemata fills is *smaller* and Heretic's real break-even count is **probably higher than the AOT tools imply** — and is entirely unmeasured for any reloadable language.

**(e) Verdict: UNKNOWN crossover; the *direction* is right, the *constant* is uncalibrated, and there is a density hazard.** MuJava confirms few-mutants→negligible-gain, so gating is sensible; but Heretic should (i) **benchmark `schemata_compile_once` vs `N × clj-reload` across N = 1,2,3,5,10,20** per representative `.clj` to find the empirical crossover, and (ii) **density-gate** dense files (which can hit CppCheck-style net slowdown), not count-gate alone.

> **MEASURED (update — see `docs/validation-results.md` §1).** This experiment was subsequently run in-repo. The
> compile/reload crossover is **N\* = 1**, which *refutes* this section's prediction that Heretic's break-even is
> "probably higher than the AOT tools imply" (§3-G4(d)). The error in the prediction: it modeled the traditional
> arm as one reload per mutant, but the real worker (`worker.clj:58-88`) does **two** (`apply` + `revert`), so
> even a single mutant benefits from the one-reload schemata batch. The density hazard (ii) was **confirmed**
> (+666% dispatch overhead at ~1 site/line). Net: density-gate, not count-gate — and the absolute saving is small
> once covering tests dwarf the ~2 ms reload, so schemata's value vs cheap `clj-reload` is itself in question.

---

### G5 — Adaptive per-mutant timeout (`calculate-dynamic-timeout` exists; runner uses fixed 5000ms)

**(a) SOTA methods.** Every production tool computes `timeout = baseline_time × factor + constant`, with a floor — **never a bare fixed constant** — measuring un-mutated baseline first. A timed-out mutant is always counted **KILLED/detected** (a CI hang is observable), so the asymmetric cost of a too-tight timeout is a **false KILL (inflated score)**, not a missed bug — hence every tool biases generous via the additive constant.

**(b) Measured numbers** (defaults, source-confirmed):

| Tool | Formula | Factor | Constant / floor |
|---|---|---|---|
| **PIT** | `round(normal×f) + c` | **1.25** | **+4000ms** |
| **StrykerJS** | `netTime×f + c + overhead` | **1.5** | **+5000ms** |
| **cargo-mutants** | `max(f×baseline, floor)` | **5×** | floor **20s** |
| **mutmut** | `(baseline + c)×f` | **15.0** | +1.0s |
| **Gremlins** | `coverage-run × f` | **3** | — |
| **Infection** | flat | — | **10s** (warns: false positives) |
| **Heretic** | `clamp(duration×f, floor, cap)` | **3.0** | floor **1000**, cap **30000**, *no additive constant* |

Empirical grounding: timeouts cause **70% of flaky failures**; baseline-scaled timeouts removed **80% of timeout-flakiness at 25% lower median timeout** ([SAP HANA, ICSE-SEIP 2024](https://arxiv.org/pdf/2402.05223)); a naive 10× inflation still leaves **~10% residual** timeout-flakiness. Non-determinism makes mutation scores vary **~4 percentage points** across runs, with **9% of mutant-test executions "unknown"** ([Shi et al., ISSTA 2019](https://mir.cs.illinois.edu/marinov/publications/ShiETAL19FlakyMutation.pdf)). PIT explicitly documents JVM **classloading/JIT warmup** inflating the first test "several seconds," and recommends raising the *constant*, not the factor — directly relevant to ClojureStorm.

**(c) How the field benchmarks it.** Run the un-mutated suite (dry run), record per-test baseline; re-run the same revision N times to measure verdict stability; the false-timeout ground truth is a mutant killed-by-timeout in some runs but survived/killed-by-assertion in others. Seed infinite-loop mutants deliberately to verify they are always caught.

**(d) Heretic asserted vs. evidence.** Heretic's **3.0× multiplier is squarely in the safe band** (between PIT 1.25× and cargo-mutants 5×) — *not* the weak point. The two real gaps: (i) **no additive constant** — a pure multiplier gives a 400ms JVM test only 1200ms, vs PIT's `400×1.25+4000 = 4500ms` of warmup headroom; the 1000ms floor is thin protection on the JVM; (ii) **it is dead code** — confirmed: `timing.clj:142` defines it (defaults multiplier 3.0, base 1000, max 30000), called only by `timing_test.clj`; `runner.clj:141` uses `:timeout-ms 5000`, `worker.clj:108` uses `30000`.

**(e) Verdict: multiplier PLAUSIBLE; formula INCOMPLETE; feature UNUSED (G5 confirmed).** Recommended replacement: `timeout = clamp(baseline × 1.5..3.0 + ~4000ms, ~1000–2000ms, ~30000ms)` — add a PIT/Stryker-style constant rather than relying on the floor — and **wire the existing fn into the runner** (the `:timing-data` plumbing already flows through). Heretic could also contribute the **first measured proportion of timeout-mutants for Clojure**, which no source reports.

---

## 4. Benchmarking methodology & candidate datasets

**The field has a reusable playbook Heretic can adopt almost wholesale.**

1. **Real-fault benchmark = ground truth.** [Defects4J 2.0](https://github.com/rjust/defects4j) — **800+ Java faults across 17 projects**, each a buggy/fixed pair with triggering tests; [CoREBench](http://www.comp.nus.edu.sg/~release/corebench/) is the C analog. Mutant↔real-fault coupling is **73% (Just 2014, 357 faults) to 80.7% (Defects4J 2.0, 337 faults)**, with **~17% of faults coupled to no mutant** — an upper bound on any tool's detectability ([Just et al., FSE 2014](https://homes.cs.washington.edu/~rjust/publ/mutants_real_faults_fse_2014.pdf)).
2. **Control for test-suite size — the load-bearing rule.** Mutation-score↔fault correlations drop from **~0.35–0.75 to ~0.05–0.20** once suite size is controlled ([Papadakis et al., ICSE 2018](https://coinse.github.io/publications/pdfs/Papadakis2018hi.pdf)). Use partial correlation / regression with size as covariate for *any* G1–G5 correlation.
3. **Cost-reduction protocol (G2–G4).** Treat the full mutant set's score as ground truth; measure the reduced set's **Mutation Score Error** and **Order Preservation (OP/EROP)**; **always** include a same-size random baseline (generate the random reduction ~100× and average) ([Zhang et al., TOSEM 2022](https://arxiv.org/abs/2102.02978)). Note **mutant-count and wall-clock-time diverge ~44% on average (range 19–91%)** — so G4 must measure *time*, not count.
4. **Operator/node usefulness, measured not asserted (G3).** Google's at-scale deployment ranks nodes by *historical* mutant performance; **arid-node filtering cut the unproductive ratio from 85% to 11%** over ~17M mutants / >24,000 developers ([Petrovic et al., TSE 2021](https://homes.cs.washington.edu/~rjust/publ/practical_mutation_testing_tse_2021.pdf)). This is the gold standard for replacing Heretic's hand-coded hardness table.
5. **Clojure corpus — Heretic must build it.** No Clojure mutation benchmark exists. Candidate well-tested OSS targets: **`clojure.core` itself, malli, honeysql, medley, instaparse, clojure.data.json, clojure.core.match** ([top-100 Clojure](https://github.com/EvanLi/Github-Ranking/blob/master/Top100/Clojure.md)). Pick high-coverage/fast suites first; **mine bug-fix commits with regression tests** to bootstrap a Clojure real-fault set for G1/coupling. Cross-check Heretic's test→code mapping against **[Cloverage](https://github.com/cloverage/cloverage)**; benchmark speed/effectiveness against **jstepien/mutant**.

---

## 5. Verification caveats — what NOT to trust at face value

The adversarial fact-checkers flagged the following. Treat these as **lower-confidence** or **corrected**:

- **Gremlins "exactly 3.0× matches Heretic" (supported, with caveats).** The numeric default *is* 3 and the formula is correct, but (a) the cited docs page renders the default as `0` ("enforce built-in default"); the literal `3` lives in source constant `DefaultTimeoutCoefficient`; (b) **the unreleased main branch raised it to 5**, so future Gremlins will diverge from 3.0×.
- **Schemata "= Heretic design" + arXiv 1908.01540 (partially-supported).** The Untch "over 300% (≈4×)" and Vercammen "2×–30×" figures are real, but the **cited URL (1908.01540) is the *Mull* paper, not the Untch/Offutt or Vercammen papers** — wrong-URL misattribution. The "= Heretic design" gloss is an unverifiable editorial assertion. Correct sources: Untch/Offutt/Harrold ISSTA 1993; [Vercammen arXiv 2210.17215 / STVR 2024](https://arxiv.org/abs/2210.17215).
- **Offutt selective numbers attributed to Mresa & Bottaci STVR 1999 (partially-supported).** The 99.99%/24% and 99.84%/41% figures are genuine but originate from **Offutt et al. (ICSE'93 / TOSEM 1996)**, not Mresa & Bottaci — citation misattribution. The "16 of 22 Fortran operators give >60% reduction" framing is **not corroborated**. The "E-selective 5-operator set = ~60% reduction" pairing is also imprecise — the survey ties 60% to *6-selective*.
- **The 5-operator set "FSE 2016 restates / inherit the numbers" (partially-supported).** FSE 2016 (Ammann/Kurtz/Offutt) is *counterevidence* to selective mutation (its dominator-score result is negative), not an endorsement; "inherit the measured numbers" is an unsupported inference. The five operators and ~99.5% figure are real (1996 TOSEM, Fortran, small programs).
- **TCE "sound / zero false positives / provably equivalent" (partially-supported).** Overstated: TCE is **conditionally sound relative to fixed compiler+settings**, not a formal proof — the paper itself says you must know deployment compiler settings to be "absolutely sure." The "conservative" quote could not be verified verbatim. TCE compares *machine/object code*, not raw bytecode.
- **TCE Java figures attributed to the 2015 ICSE paper (partially-supported).** The 5.7%/5.4%/~54% Java numbers come from the **2018 TSE extension (Kintis et al.)**, not the C-only 2015 ICSE paper. Use [orbilu.uni.lu/.../07882714.pdf](https://orbilu.uni.lu/bitstream/10993/31623/1/07882714.pdf) for Java.
- **LLM detector "improves TCE by 75% / CPL improves Tian to 95.3" (partially-supported).** The +75/19/13% deltas are **Tian et al.'s alone** (CPL reports no TCE comparison); CPL's apples-to-apples gain over its own UniXCoder baseline is **+2.24pp F1**, not a clean jump over Tian's headline (different test-set balance and epochs).
- **PIT "3.0× is 2.4× more lenient than PIT's 1.25×" (partially-supported).** True only *asymptotically*. Because PIT adds a flat +4000ms while Heretic's 1000ms is only a floor, **PIT's total timeout is actually *more* lenient than Heretic's for typical fast tests** (they cross over near ~3–4s baseline). The 2.4× describes the multiplier ratio, not overall leniency.
- **cargo-mutants build timeout "2× baseline" (partially-supported).** The build timeout is **2× baseline × number of jobs, min 20s** — the "× jobs" and floor were dropped. The *test* timeout (5×, 20s floor) and the rationale quote are exact.
- **Untch 1993 "negligible compared to compiling each mutant" quote (partially-supported).** That quoted sentence is **not in the paper** (fabricated/paraphrased presented as a verbatim quote); the *substance* (compile-once is worth it) is supported. All numbers (4.1×, 385 mutants) check out.
- **Cerebro "reduces mutant counts ~35–68%" (partially-supported).** The 68% is **fewer *equivalent* mutants** (a cost factor), not a 68% cut in total mutant count — a conflation. The "~35%" lower bound has no clear source. The 90% fewer executions and 2× subsuming-kills are accurate.
- **FaRM author list (partially-supported).** The fifth author is **Koushik Sen, not Mark Harman** (Harman is only an internal citation). All numbers (2%, 17%, +23–34%) are exact.
- **Zhang TOSEM "exact subsumption needs no early-exit" + "Heretic G2 lacks it" (partially-supported).** "No early-exit" is the agent's gloss, not paper text (though a defensible inference). The "Heretic lacks it" claim is project-specific and **not supportable from the cited paper** — Heretic's own `research.md` reportedly documents a Kill Matrix Mode.
- **Lower-confidence / unverified-in-primary-source figures** (use with caution, re-verify before load-bearing citation): the **SSHOM "35–45% reduction, +5.6–12% effectiveness"** (low confidence, not primary-verified); **DM# "0.72% MSE / 28.38% / 44% count-vs-time divergence"** (surfaced via search summaries of the reduction-evaluation literature, not a primary DM# fetch); the **<5% subsuming / 9% disjoint / >60% inflation** subsumption ratios (paywalled ISSTA/SCAM 2016, search-synthesized); the **Visser "What Makes Killing a Mutant Hard" regression coefficients** (ACM paywall — the qualitative "reachability dominates" finding is solid, the quantitative split is not pinned); the **DynaMut "18–66% code-size overhead"** (primary PDF returned 403). The Gopinath **tr2** "~5% advantage over random" figure is wrong — the related ICSE'16 study says **10%**, not 5%.

---

## 6. Annotated reading list

### Cross-cutting / methodology
- **[Just et al., "Are Mutants a Valid Substitute for Real Faults?" FSE 2014](https://homes.cs.washington.edu/~rjust/publ/mutants_real_faults_fse_2014.pdf)** — Establishes mutant↔real-fault coupling (73%); the foundational case that mutation testing measures something real.
- **[Papadakis et al., "Are Mutation Scores Correlated with Real Fault Detection?" ICSE 2018](https://coinse.github.io/publications/pdfs/Papadakis2018hi.pdf)** — *Must-read*: correlations collapse once suite size is controlled. Defines the partial-correlation discipline for all of G1–G5.
- **[Zhang et al., "Mutant Reduction Evaluation: What is There and What is Missing?" TOSEM 2022](https://arxiv.org/abs/2102.02978)** — The reduction-evaluation protocol: OP/EROP metrics, the necessity of a random baseline, coverage-subsumption as kill-matrix approximation.
- **[Petrovic et al., "Practical Mutation Testing at Scale: A View from Google," TSE 2021](https://homes.cs.washington.edu/~rjust/publ/practical_mutation_testing_tse_2021.pdf)** — Industrial reality: 85%→11% unproductive-mutant suppression via historically-ranked arid nodes; the template for validating operator usefulness empirically.

### G1 — equivalent detection
- **[Papadakis et al., "Trivial Compiler Equivalence," ICSE 2015](http://web4.cs.ucl.ac.uk/staff/Y.Jia/resources/papers/PapadakisJHT2015.pdf)** / **[Kintis et al., TSE 2018 (Java extension)](https://orbilu.uni.lu/bitstream/10993/31623/1/07882714.pdf)** — The sound-detector standard; ~30% (C)/~54% (Java) recall, 0 FP modulo compiler settings. Use the TSE version for Java numbers.
- **[EMS — "Equivalent Mutant Suppression in Java," UW PLSE / ISSTA 2024](https://homes.cs.washington.edu/~rjust/publ/equi_mutants_ems_issta_2024.pdf)** — The closest architectural analog to Heretic: a small set of *sound* static suppression rules, with honest soundness-exception disclosure and the AOIS-concentration finding.
- **[Tian et al., "LLMs for Equivalent Mutant Detection," ISSTA 2024](https://arxiv.org/html/2408.01760v1)** — The full P/R/F1 table across compiler/ML/tree-NN/LLM families; the upper bound (and why it needs labeled data Clojure lacks).

### G2 — subsumption / selective mutation
- **[Offutt et al., "An Experimental Determination of Sufficient Mutant Operators," TOSEM 1996](https://dl.acm.org/doi/10.1145/227607.227610)** — Origin of the 5-operator E-selective set.
- **[Jia & Harman, "An Analysis and Survey of the Development of Mutation Testing," TR-09-06 / TSE 2011](https://mutationtesting.uni.lu/TR-09-06.pdf)** — Source of the N-selective table (24/41/60% reductions); the canonical survey.
- **[Ammann et al., "Analyzing the Validity of Selective Mutation with Dominator Mutants," FSE 2016](https://www.albany.edu/faculty/offutt/research/papers/selectiveMut-FSE2016.pdf)** — Why traditional score ≠ dominator score; mandates dominator-aware metrics.
- **[Garg et al., "Cerebro: Static Subsuming Mutant Selection," arXiv 2112.14151 / TSE 2022](https://arxiv.org/abs/2112.14151)** — Static subsuming prediction; the 10.2% subsuming fraction, 0.85/0.33 precision/recall reality.

### G3 — clustering / representative selection
- **[Gopinath et al., "On the Limits of Mutation Reduction Strategies," ICSE 2016](https://rahul.gopinath.org/resources/icse2016/gopinath2016on.pdf)** — *The* result: 13.078% oracular ceiling over random. Read before building any clustering scheme.
- **[Chekam et al., "Selecting Fault Revealing Mutants" (FaRM), EMSE 2020](https://arxiv.org/abs/1803.07901)** — The soundness ceiling (2%/17% fault-revealing) and the learned-ranking alternative to a hardness table.
- **[Visser et al., "What Makes Killing a Mutant Hard," ASE 2016](https://dl.acm.org/doi/10.1145/2970276.2970345)** — Hardness is dynamic (reachability/infection/propagation), not operator identity — the direct challenge to Heretic's static table. *(Quantitative coefficients paywalled.)*
- **[Zhang et al., "Better Together: Operator-Based + Random Mutant Selection," ASE 2013](https://users.ece.utexas.edu/~gligoric/papers/ZhangETAL13BetterTogether.pdf)** — 5% sampling → 99% correlation; the random-baseline benchmark.

### G4 — schemata cost model
- **[Vercammen et al., "Mutation Testing Optimisations using the Clang Front-end," STVR 2024 / arXiv 2210.17215](https://onlinelibrary.wiley.com/doi/10.1002/stvr.1865)** — The only modern cost-model decomposition; the two break-even factors, the density-driven net-slowdown (0.81×/120%), and the switch-vs-ternary recommendation.
- **[Ma, Offutt, Kwon, "MuJava," STVR 2005](https://www.albany.edu/faculty/offutt/research/papers/mujava.pdf)** — Per-class speedup-vs-mutant-count data (1.05× at 1 mutant → 9.37× at 77–96); the best proxy for Heretic's per-file threshold.
- **[Untch, Offutt, Harrold, "Mutation Analysis Using Mutant Schemata," ISSTA 1993](https://www.albany.edu/faculty/offutt/research/papers/schema.pdf)** — The original 4.1× compile-once result.
- **[Stryker4s "Mutation switching" blog](https://stryker-mutator.io/blog/mutation-switching/)** — The only explicit low-N (3-mutant) break-even argument; illustrative, not benchmarked.

### G5 — adaptive timeouts
- **[PIT `PercentAndConstantTimeoutStrategy.java`](https://github.com/hcoles/pitest/blob/master/pitest/src/main/java/org/pitest/mutationtest/build/PercentAndConstantTimeoutStrategy.java)** — Source-confirmed `round(normal×1.25)+4000ms`; the JVM reference formula.
- **[StrykerJS configuration](https://stryker-mutator.io/docs/stryker-js/configuration/)** / **[cargo-mutants timeouts](https://mutants.rs/timeouts.html)** / **[mutmut docs](https://mutmut.readthedocs.io/)** — The factor+constant+floor spectrum (1.5/+5000; 5×/20s; 15×/+1s).
- **[Berndt et al., "Taming Timeout Flakiness: SAP HANA," ICSE-SEIP 2024](https://arxiv.org/pdf/2402.05223)** — Strongest measured evidence that baseline-scaled timeouts beat fixed/inflated ones (70% of flakiness; −80% at −25% median timeout).
- **[Shi et al., "Mitigating the Effects of Flaky Tests on Mutation Testing," ISSTA 2019](https://mir.cs.illinois.edu/marinov/publications/ShiETAL19FlakyMutation.pdf)** — Quantifies how a single fixed-timeout run destabilizes verdicts (4pp variance, 9% unknown).

### Clojure substrate & competition
- **[jstepien/mutant](https://github.com/jstepien/mutant)** (archived) — Heretic's only predecessor; proof of no real Clojure competition.
- **[Clofidence](https://github.com/flow-storm/clofidence)** / **[FlowStorm](https://github.com/flow-storm/flow-storm-debugger)** — The ClojureStorm coverage precedent underpinning Heretic's test→code mapping.
- **[Defects4J](https://github.com/rjust/defects4j)** / **[Top-100 Clojure OSS](https://github.com/EvanLi/Github-Ranking/blob/master/Top100/Clojure.md)** — The benchmark template and the candidate Clojure target corpus.
