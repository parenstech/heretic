# Survivor Triage (coverage-gap detection) — Implementation Plan

**Status:** Design (pre-build). Builds on the G1 recall measurement
(`validation-results.md` §2.2) and reuses its oracle (`src/heretic/oracle/*`).
Companion to `interpreting-survivors.md`, whose central assumption this feature
makes *decidable*. This doc is the buildable plan: architecture, resolved
decisions, phases, testability. The integration risk (running the oracle inside
heretic's ClojureStorm/sandbox run) is the load-bearing question — Phase 0 spikes it.

## 1. Problem

When a mutation **survives** (no test killed it), heretic reports it as an
undifferentiated entry in the survivor list, and `interpreting-survivors.md`
tells the user every survivor is a test gap to fix. But the G1 measurement
showed that is **false for a real fraction**: on medley **~5–13%** of mutants are
*truly equivalent* — no test could ever kill them (e.g. a mutation in a
`#?(:cljs …)` branch). So today's survivor list **conflates two opposite things**:

- **Coverage gaps** — killable mutants the suite simply doesn't exercise. The
  user *should* write a test. heretic gives them no help finding the input.
- **Equivalent mutants** — unkillable. The user *should not* spend any time;
  there is no test to write. heretic flags them as gaps anyway.

The cost is two-sided: wasted triage on equivalents, and no leverage on the real
gaps (the user must reverse-engineer a distinguishing input by hand).

The root cause is a *capability* the suite-based run structurally lacks:
deciding "equivalent vs coverage-gap" requires searching **beyond the suite's
inputs** for a distinguishing witness. The differential oracle already does
exactly this (§2.2) and is **sound on the killable side** — a witness is a proof.
But it lives as a standalone research harness, not wired into the run. This plan
productionizes it: classify each survivor, and for a coverage gap, hand the user
**the witnessing input the suite missed**.

This is *not* a mutation-score change (equivalents are already counted as
survivors; adjusting the denominator is the pre-run equivalent *filter*'s job,
not this post-run *triage*). It is a reporting/leverage feature.

## 2. Scope

**In:**

- A post-run **triage pass** over the survivor set that classifies each survivor:
  `proven-equivalent` / `candidate-equivalent` / `coverage-gap` (with witness) /
  `undetermined` / `not-applicable`.
- **Sound** coverage-gap labels: a coverage-gap is only emitted with a concrete
  witnessing input (a proof the mutant is killable).
- Surfacing the classification in the `survivors` command and the JSON/EDN/HTML
  reports — coverage gaps first (actionable), with the witness; equivalents
  de-emphasised.
- Reuse of the existing oracle (`sound`, `differential`, `harvest`) with
  suite-seeded inputs (the §2.2 trio).

**Out (deferred / explicitly excluded):**

- **Mutation-score adjustment** — triage labels survivors; it does not move
  killed/survived/no-coverage counts. (A future "exclude proven-equivalents from
  the denominator" is a separate decision belonging to the equivalent *filter*.)
- **HOF / impure survivors** — the oracle can't replay them; reported as plain
  survivors tagged `not-applicable` (no regression vs today).
- **Generative (`test.check`) suites** — harvesting + the suite cross-check don't
  scale to them yet (§2.2 limitation); on such targets the pass runs in
  best-effort mode and degrades to `undetermined`, never blocking the run.
- **TCE bytecode-identity** (Half-3) — would widen the sound-equivalent set; not
  required for triage.

## 3. Glossary

| Term | Definition |
|------|------------|
| **Survivor** | A mutant the suite did not kill (`status :survived`). The triage input set. |
| **Witness** | An input on which original and mutant observably differ — a **sound proof** the mutant is killable. |
| **Coverage gap** | A survivor for which the oracle finds a witness: killable, but the suite's inputs miss it. The user should add a test; the witness shows what input. |
| **Proven-equivalent** | A survivor proven unkillable by a sound detector (`read-identity` / `macroexpand-identity`). Not a gap. |
| **Candidate-equivalent** | A survivor with no witness in N trials *and* no sound proof — likely equivalent, but unproven (the honest middle). |
| **Not-applicable** | The oracle *cannot replay this mutant*: impure / higher-order / non-`defn` (per-mutant). Reported as today's plain survivor. (= the oracle's `:oracle-not-applicable` verdict.) |
| **Undetermined** | The oracle *pass could not run* for this survivor (e.g. a generative `test.check` suite the harvest can't seed) — distinct from `not-applicable`, which is the mutant's own shape. Also the catch-all for an oracle error. |
| **Triage** | The post-run classification of survivors into the above. |

The classification is a closed five-label set — `proven-equivalent`, `coverage-gap`,
`candidate-equivalent`, `not-applicable`, `undetermined` — used verbatim in §1, §2,
§4.5, §5, and §6. `not-applicable` (mutant can't be replayed) and `undetermined`
(pass couldn't run) are deliberately distinct and never substituted for each other.

## 4. Design decisions

### 4.1 Where the oracle runs

| Option | Reuses run state | Risk | Decision |
|--------|------------------|------|----------|
| **In-JVM post-pass** in the sandbox run (the target nses + test ns are already loaded) | Yes | Must coexist with ClojureStorm instrumentation during the eval-rebind | **Chosen — pending the Phase-0 spike** |
| Separate plain-Clojure subprocess on the survivor set | No (re-loads) | Clean (no storm), but re-derives load + needs source paths | **Fallback if Phase 0 shows storm interferes** |
| Status quo (no triage) | — | — | Rejected: leaves survivors undifferentiated |

The oracle was built to run *without* ClojureStorm; heretic's run *is*
instrumented. **Phase 0 is a hard spike**: does the in-memory eval-rebind-restore
(`differential/classify-mutant`) produce the same verdict under a storm-instrumented
JVM as under plain Clojure? If yes, the in-JVM post-pass is far cheaper (state is
loaded). If storm's compile hooks corrupt the rebind or slow it intolerably, fall
back to the subprocess. The decision is **gated on evidence**, not assumed.

### 4.2 Oracle inputs

| Option | Reach | Decision |
|--------|-------|----------|
| **Suite-harvested args + perturbations** (the §2.2 trio) | Solved by construction | **Chosen** — reuse `harvest` |
| Random generation only | Weak (50% gate, §2.2) | Rejected — too weak alone |

### 4.3 Classification when neither a witness nor a sound proof is found

| Option | Honesty | Decision |
|--------|---------|----------|
| Call it `coverage-gap` (assume killable) | Unsound — no witness | Rejected |
| Call it `equivalent` (assume unkillable) | Unsound — no proof | Rejected |
| **`candidate-equivalent`** (oracle ran, no witness in N) / **`not-applicable`** (can't replay this mutant) / **`undetermined`** (pass couldn't run) | Honest — claims only what's proven | **Chosen** |

Coverage-gap requires a witness; proven-equivalent requires a sound proof.
Everything else is labelled as the unproven middle — never over-claimed.

### 4.4 Score impact

Triage is **reporting only** in v1 (§2 Out). Survivors keep their `:survived`
status and the mutation score is unchanged; the labels are an added dimension.

### 4.5 Output surface

`survivors` command: group by classification, **coverage gaps first** with their
witness, proven-equivalents last (collapsed). JSON/EDN report: each survivor gains
the tagged `:triage` verdict from §5 — the arm carries only its valid field
(`:witness` on `coverage-gap`, `:proof` on `proven-equivalent`), so consumers
dispatch on `:triage` rather than nil-checking. HTML: a column / badge.
`interpreting-survivors.md`: reframed — "first, is it even a gap?".

## 5. Architecture

Reuse the oracle vocabulary; add one orchestration ns + thin reporter hooks. No
new sound logic (the predicates exist and are verified).

```
heretic.oracle.triage                      ; NEW — the post-run classifier
  ;; returns a TAGGED verdict — exactly one arm; a field is present only on the
  ;; arm where it's valid (no nilable :witness/:proof on every map):
  classify-survivor (survivor ctx) -> one of
    {:triage :proven-equivalent    :proof   <tag>}     [sound]   ; sound/sound-equivalent
    {:triage :coverage-gap         :witness <input>}   [sound]   ; differential :killable
    {:triage :candidate-equivalent :trials  n}                   ; differential, no witness in N
    {:triage :not-applicable       :reason  <kw>}                ; differential :oracle-not-applicable
    {:triage :undetermined         :reason  <kw>}                ; pass couldn't run (generative suite / error)
  triage-survivors (survivors ctx) -> survivors with the :triage verdict assoc'd
    (harvest once per ns via harvest/harvest-args; reuse across survivors;
     if the harvest/pass can't run for an ns → its survivors get :undetermined)

reuses: oracle.sound, oracle.differential, oracle.harvest, mutation-engine
wires into:
  core.clj   — after survivor-list is computed, run triage (guarded by a config
               flag :triage-survivors, default on), assoc :triage onto results
  reporter.clj — print-survivors / survivors / report writers render :triage
```

`ctx` carries the loaded `ns-sym` per file + the `test-ns` + harvested inputs +
N-trials/seed — assembled once from the run config, threaded per survivor.

## 6. Phases

- **Phase 0 — storm-coexistence spike (HARD GATE).** Run
  `differential/classify-mutant` + `sound/sound-equivalent` on a handful of known
  survivors *inside a ClojureStorm-instrumented JVM* (the run's actual
  environment). *Gate:* verdicts match the plain-Clojure oracle (the §2.2 medley
  numbers) AND the namespace is restored intact afterwards. Pass → §4.1 in-JVM
  path. Fail → switch to the subprocess fallback before building Phase 1.
- **Phase 1 — triage module + run wiring.** Build `oracle.triage`; wire into
  `core.clj` behind `:triage-survivors`. *Gate:* on medley, the 7 dead-branch
  survivors classify `proven-equivalent`; a seeded known coverage-gap classifies
  `coverage-gap` with a replayable witness; impure/HOF survivors → `undetermined`
  (no crash).
- **Phase 2 — report surface.** `survivors` command + JSON/EDN/HTML render the
  label + witness, gaps first. *Gate:* `survivors` output shows the three buckets;
  the JSON carries `:triage`/`:witness`/`:proof`; a golden-file test pins the shape.
- **Phase 3 — docs.** Reframe `interpreting-survivors.md` around the triage; note
  the generative-suite degradation.

## 7. Testability

- **Coverage-gap labels are self-witnessing** — each ships the input; anyone can
  replay `orig(witness) ≠ mut(witness)`. Sound, no trust required.
- **Proven-equivalent is sound** — `read-identity`/`macroexpand-identity` proofs,
  already verified (`validation-results.md` §2.2, the soundness invariant).
- **The medley corpus is the regression oracle** — Phase 1's gate reuses the §2.2
  ground truth (7 dead-branch equivalents, 8 coverage gaps) as fixtures; the
  triage must reproduce that split.
- **Honest middle** — `candidate-equivalent`/`undetermined` claim nothing
  unproven, so a weak oracle under-claims (never mislabels a gap as equivalent or
  vice-versa). The failure direction is conservative by construction.
- **Phase 0 is itself a test** — verdict-equality under storm-on vs storm-off is a
  falsifiable pass/fail before any wiring is built.

## 8. Expected outcome

On a typical run, survivors split into a short **coverage-gap** list (each with a
concrete input to test) + a collapsed **equivalent** list the user can ignore +
an **undetermined** remainder (today's behavior, no regression). The headline
value: heretic stops telling users to "go write a test" for unkillable mutants,
and for the real gaps it hands over the exact input the suite missed — turning
`interpreting-survivors.md`'s manual pattern-matching into a witnessed, sound
classification.
