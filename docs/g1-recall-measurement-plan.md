# G1 Recall Measurement — Implementation Plan

**Status:** Design (pre-build). Companion to `validation-plan.md` §3-G1 (the
experimental design) and `validation-results.md` §2 / §2.1 (what is already
measured). This doc is the *buildable* plan: the architecture, the resolved
design decisions, the phases, and the testability gates. It does not restate the
§3-G1 hypotheses/metrics — read that first.

## 1. Problem

Heretic ships an equivalent-mutant filter that is **on by default**
(`:filter-equivalent true`). PR #16 established two facts about it:

- it is **sound** (0 false positives over 635 live mutants — it never drops a
  killable mutant), and
- it flags **0 / 635** mutants on five real targets (its 13 patterns match no
  idiomatic code).

So we know the filter's *output* is empty. We do **not** know the *input* it is
sampling from: **how many of those mutants are actually equivalent.** That number
— the **true equivalent-mutant rate** — is the recall *denominator*, and without
it every statement about G1's value is undecidable:

- If ~**0%** of mutants are truly equivalent, the filter correctly removes
  nothing because there is nothing to remove → G1 is a non-problem in Clojure,
  and the default-on filter is pure overhead.
- If a **non-trivial %** are equivalent, those are false survivors silently
  inflating triage cost, and a stronger sound detector (TCE) has real value.

The root cause of the uncertainty is concrete and long-standing: **no Clojure
equivalent-mutant ground-truth corpus exists** (`validation-plan.md` §1 row 5,
"the heaviest corpus lift"). Recall cannot be computed without it. This plan
builds the minimum corpus + oracle needed to put a number on the denominator,
and a sound TCE detector to see whether non-zero recall is reclaimable.

It is explicitly **not** a plan to make the filter catch more — that would be
premature. It is a plan to decide whether catching more is worth doing at all.

## 2. Scope

**In:**

- Measure the true equivalent-mutant rate on the four coverage-collected,
  predominantly-pure targets (`medley`, `clojure.data.csv`, `honeysql`,
  `lambdaisland/uri`) using a **differential property-based oracle**.
- A **TCE bytecode-identity detector** (Half-3): its recall and FP rate vs the
  oracle's equivalent set.
- Per-operator recall distribution (test the survey's operator-concentration
  claim) and the static filter's recall against the oracle ground truth.
- A sound, reusable **survivor triage byproduct**: each survivor classified
  *equivalent* vs *killable-with-a-coverage-gap* (validates/​refines
  `interpreting-survivors.md`).
- The G1 decision the result forces: keep default-on / demote to opt-in / invest
  in TCE.

**Out (deferred or explicitly excluded):**

- **Impure / IO / higher-order mutants** — the differential oracle is
  applicable only to first-order pure functions. Non-applicable mutants are
  *counted and reported as a coverage fraction*, not labeled (human kappa
  labeling is deferred per `validation-plan.md` §3-G1 STEP 3).
- Targets without collected coverage, and impure-heavy targets (`malli`,
  `instaparse`).
- Productionizing the oracle or TCE into the default run path. This measures;
  wiring a detector in is a separate decision gated on the result.
- G2 (powered subsumption study) and G5 (slow-test corpus) — tracked separately.

## 3. Glossary

| Term | Definition |
|------|------------|
| **Equivalent mutant** | A mutant no input can distinguish from the original — semantically identical, unkillable by any test. |
| **Killable mutant** | A mutant for which *some* input produces observably different behavior (value or thrown class). |
| **Survivor** | A mutant the existing test suite does not kill — precisely the union of the kill-matrix's **survived** (covered but not killed) and **no-coverage** cells: `survivor = survived ∪ no-coverage`. A survivor is *either* equivalent *or* killable-with-a-gap. Equivalents ⊆ survivors. |
| **survived** (cell) | The kill-matrix partition cell for a mutant that *was exercised* by some covering test yet not killed — disjoint from **no-coverage**. Reserve this word for the cell; use **survivor** for the union. |
| **Witness** | A concrete input on which original and mutant differ. A witness is a **sound, definitive** proof of killability. |
| **Differential oracle** | A `test.check`-driven search for a witness: run original vs mutant on the same generated inputs; a difference ⇒ killable; no difference after N trials ⇒ *candidate equivalent*. |
| **Candidate equivalent** | A survivor for which the oracle found no witness in N trials. Sound only as an *upper bound* on equivalence (oracle weakness can only over-count). |
| **TCE** | Trivial Compiler Equivalence: compile original and mutant; if normalized bytecode is identical, the mutant is **provably** equivalent (FP=0 by construction). |
| **True equivalent rate** | `|truly-equivalent| / |total mutants|`, the recall denominator this plan measures. |

## 4. Design decisions

Each fork below is resolved with a status-quo column and the trade-off that
decided it.

### 4.1 How to obtain ground truth

| Option | Soundness | Cost | Coverage | Decision |
|--------|-----------|------|----------|----------|
| **Differential oracle (test.check)** | KILLABLE labels sound (witness); EQUIVALENT labels are conservative upper bounds | Medium (build once, runs automatically) | Pure first-order fns | **Primary** |
| **TCE bytecode-identity** | EQUIVALENT labels sound (FP=0); misses semantic-but-not-syntactic equivalence | Medium | All compilable mutants | **Complementary** (Half-3) — a sound *lower* bound on equivalence |
| Human labeling (≥2 raters, kappa) | Noisy (humans 54–64% accurate on equivalents) | High | Any | **Deferred** — only for oracle-survivors the user later wants resolved |
| Status quo (no measurement) | — | 0 | — | Rejected: leaves G1's value undecidable |

The oracle and TCE **bracket** the truth: the oracle gives an *upper* bound on
the equivalent rate (it may fail to find a witness), TCE gives a *lower* bound
(it only flags syntactically-identical bytecode). A tight bracket is a strong
result; a wide one tells us exactly where human labeling is needed.

### 4.2 Mutant pool: survivors only

| Option | Cost | Decision |
|--------|------|----------|
| All generated mutants | Oracle-test every mutant incl. ones the suite already kills | Rejected — wasteful |
| **Survivors only** (= survived ∪ no-coverage) | Equivalents ⊆ survivors, so the kill-matrix already excludes every killable-and-covered mutant for free | **Chosen** |

The existing kill-matrix runner (`killmatrix.clj`, built for G2/G3/G5) already
partitions mutants into killed / **survived** (covered, not killed) /
no-coverage. Equivalents can only live in the *survivor* set
(`survived ∪ no-coverage`, per §3). Run the oracle on that set only. **Built-in
correctness check:** every *killed* mutant is, by definition, killable — the
oracle must independently find a witness for a sample of them, or its generators
are too weak (see §6).

### 4.3 Input generation (the feasibility crux)

| Option | Domain fit | Effort | Decision |
|--------|-----------|--------|----------|
| Infer generators from arglist arity with a broad structural distribution (ints, strings, kw, vec, map, set, nil, bool, nested) | Good for data-manipulation fns | Low | **Chosen default** |
| Malli/spec schemas where the target declares them | Precise | Low where present, none where absent | **Used opportunistically** (none of the four targets ship specs broadly) |
| Hand-written per-fn generators | Precise | High (per fn) | **Fallback** only for high-value candidate-equivalents that survive N trials |

Rationale: a broad structural generator refutes equivalence for the
*overwhelming majority* of killable mutants cheaply; the residual candidate set
is small, and only *that* set ever needs a precise generator or TCE. Generators
that throw are fine — a thrown-exception *class* is part of the observed
behavior (original throws `ArityException`, mutant returns a value ⇒ witness).

### 4.4 Higher-order / impure mutants

Detected structurally (the mutated fn takes a fn argument, or its body
references IO/atoms/`rand`/`System`). Marked `oracle-not-applicable`, counted
into the **automated-coverage fraction** that every result must report. Not
labeled. This keeps the headline rate honest: "X% equivalent among the Y% of
survivors the oracle can decide."

### 4.5 TCE compile strategy

| Option | Isolation | Decision |
|--------|-----------|----------|
| AOT the single mutated namespace to a temp `classes/` dir, diff `.class` bytes after normalizing gensym counters / line-number tables / `SourceFile` / `__init` ordering | Clean, reuses `compile` | **Chosen** |
| Hook `DynamicClassLoader.defineClass` during the existing clj-reload | Less I/O | Rejected — couples TCE to the reload path; harder to make deterministic |

TCE soundness is conditional on a fixed compiler + settings; run it on an
**un-instrumented** compile of the *target* (target ≠ heretic, so ClojureStorm's
`:instrument-prefixes ["heretic"]` need not touch it — verify in Phase 2).

## 5. Architecture

Small composable vocabulary, mostly pure, plugged into existing infra. No new
long-lived state; per-mutant verdicts persist as append-only EDN (resumable like
the kill-matrix).

```
heretic.oracle.differential
  gen-inputs        (mutant) -> [input ...]          ; §4.3, pure
  observe           (fn inputs) -> [result-or-ex ...]; effoctful (calls fn)
  differential?     (orig-obs mut-obs) -> witness|nil; pure
  classify-survivor (mutant) -> verdict               ; a TAGGED shape (one arm):
    ;;  {:label :killable             :witness input}   ; a distinguishing input
    ;;  {:label :candidate-equivalent :trials  N}       ; no witness in N trials
    ;;  {:label :oracle-not-applicable :reason kw}      ; higher-order / impure (§4.4)
    ;; Each arm carries ONLY its valid fields — the :label discriminates; a
    ;; field's presence is its validity (no always-present-but-sometimes-nil keys).

heretic.oracle.tce
  compile-ns!       (ns paths) -> classes-dir        ; effectful (AOT)
  normalize-class   (bytes) -> canonical-bytes        ; pure
  tce-equivalent?   (mutant) -> bool                  ; sound, FP=0

heretic.oracle.harness            ; orchestration, reuses:
  - killmatrix / runner            -> the survivor set (§4.2)
  - engine/apply-mutation! + revert -> apply mutant, observe, restore
  - scorer: confusion matrix + per-operator recall + bracket [TCE, oracle]
```

`observe` runs the original once (pristine), then applies the mutant
(`apply-mutation!` → reload), observes on the **same** inputs, then reverts —
the exact apply/revert dance the runner already performs, so isolation is
inherited, not reinvented.

## 6. Phases

Each phase has a falsifiable exit gate; a phase that fails its gate stops the
build and reports why (the measurement is worth nothing if the harness is wrong).

- **Phase 0 — Survivor sets.** Run the existing kill-matrix per target; persist
  survived ∪ no-coverage. *Gate:* counts reconcile with the §2.1 totals
  (635 mutants; survivor counts plausible).
- **Phase 1 — Differential oracle.** Build §5's `differential` ns; run over
  survivors. *Gate (correctness, not result):* on a sample of **killed**
  mutants the oracle independently finds a witness ≥ 95% of the time — else the
  generators are too weak and the equivalent rate is untrustworthy. Report the
  oracle-applicable fraction.
- **Phase 2 — TCE detector.** Build §5's `tce` ns. *Gate:* the §2.1 positive
  control shapes (`(+ x 0)`→`(- x 0)`) yield identical normalized bytecode
  (TCE flags them), and a known-killable mutant yields differing bytecode (TCE
  does **not**). Verify the verdict is invariant to ClojureStorm on/off.
- **Phase 3 — Score + reconcile + decide.** Confusion matrix, per-operator
  recall, the [TCE-lower, oracle-upper] equivalent-rate bracket, static-filter
  recall against ground truth, random-drop control. Write `validation-results.md`
  §2.2. State the forced G1 decision.

## 7. Testability

The result's credibility rests on labels that carry their own proof:

- **KILLABLE is self-witnessing.** Every killable verdict ships the witness
  input; anyone can replay `orig(witness) ≠ mut(witness)`. No trust required.
- **The kill-matrix is a free oracle check** (Phase 1 gate): the oracle must
  re-derive killability for already-killed mutants. This catches weak generators
  before they masquerade as equivalence.
- **TCE is sound by construction** and bounded by the positive control
  (Phase 2 gate) — a known equivalent must flag, a known killable must not.
- **The bracket is the honesty mechanism.** We report
  `equivalent-rate ∈ [TCE%, oracle%]`, never a point estimate the method can't
  support. A wide bracket is itself a finding (it localizes where human labeling
  would pay off).
- **Conservative bias is deliberate:** oracle weakness inflates the equivalent
  rate, so an oracle that reports ≈0% equivalent is a *strong* kill of G1 (even a
  weak detector found witnesses for nearly everything).

## 8. Expected outcome & what each result means

Per `validation-plan.md` §3-G1: recall low-single-digit-to-~30%,
operator-concentrated; precision sound. Concretely for the decision:

- **Bracket ≈ [0%, ~0%]** → G1 is dead in Clojure. Demote `:filter-equivalent`
  to opt-in; the survivor-triage byproduct (all survivors are coverage gaps)
  *strengthens* `interpreting-survivors.md`.
- **Bracket non-trivial and TCE recovers most of it** → TCE is the sound
  replacement; plan its integration.
- **Wide bracket (oracle high, TCE low)** → semantic equivalence beyond
  syntactic; the honest output is "human labeling needed here," scoped to a now-
  small set.

Any of the three is a publishable first-for-Clojure number and a definitive G1
decision — which is the point.
