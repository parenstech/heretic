(ns heretic.oracle.triage
  "Survivor triage — classify each surviving mutant so the user knows whether it's
   a real test gap or an unkillable equivalent (docs/coverage-gap-triage-plan.md).
   Reuses the G1 oracle (sound + differential + harvest). Reporting-only: it does
   NOT change the mutation score; it adds a :triage dimension to each survivor.

   classify-survivor returns a TAGGED verdict — exactly one arm, a field present
   only on the arm where it is valid (no nilable :witness/:proof on every map):

     {:triage :proven-equivalent    :proof   <tag>}     ; sound (read/macroexpand identity)
     {:triage :coverage-gap         :witness <args>}    ; sound — the input the suite missed
     {:triage :candidate-equivalent :trials  n}         ; oracle ran, no witness in N
     {:triage :not-applicable       :reason  <kw>}      ; oracle can't replay this mutant
     {:triage :undetermined         :reason  <kw>}      ; the pass couldn't run for this survivor

   `not-applicable` (the mutant's own shape — impure/HOF/non-defn) and
   `undetermined` (the pass couldn't run — e.g. ns not loaded / oracle error) are
   distinct and never substituted, per the plan §3."
  (:require [heretic.mutation-engine :as engine]
            [heretic.oracle.differential :as diff]
            [heretic.oracle.harvest :as harvest]
            [heretic.oracle.sound :as sound])
  (:import [java.util Random]))

(defn classify-survivor
  "Classify one survivor. ctx: {:ns-sym :inputs :n-trials :seed :timeout-ms}.
   Order: sound proof first (cheap, definitive), then the differential oracle on
   the supplied inputs (suite-harvested + perturbed in the real run). Sound on the
   actionable arms — `:coverage-gap` ships a witness, `:proven-equivalent` a proof;
   everything else is the honest unproven middle."
  [survivor {:keys [ns-sym inputs n-trials seed timeout-ms] :or {n-trials 200 seed 42}}]
  (if-not (some-> ns-sym find-ns)
    {:triage :undetermined :reason :ns-not-loaded}
    (if-let [proof (try (sound/sound-equivalent survivor ns-sym) (catch Throwable _ nil))]
      {:triage :proven-equivalent :proof proof}
      (let [v (try (diff/classify-mutant
                    survivor (cond-> {:ns-sym ns-sym :n-trials n-trials :seed seed}
                               inputs     (assoc :inputs inputs)
                               timeout-ms (assoc :timeout-ms timeout-ms)))
                   (catch Throwable t {:label :error
                                       :error (str (.getName (class t)) ": " (.getMessage t))}))]
        (case (:label v)
          :killable              {:triage :coverage-gap         :witness (:witness v)}
          :candidate-equivalent  {:triage :candidate-equivalent :trials  (:trials v)}
          :oracle-not-applicable {:triage :not-applicable       :reason  (:reason v)}
          {:triage :undetermined :reason (if (= :error (:label v)) :oracle-error :unknown)})))))

(defn- survivor-fn-sym [survivor]
  (some-> (engine/original-form-string survivor) diff/form-def-info :name))

(defn triage-survivors
  "Classify each survivor, assoc'ing its tagged :triage verdict. opts:
   {:file->ns :harvested :n-trials :seed :timeout-ms}. Per survivor it resolves
   the namespace (via :file->ns) and the harvested inputs for its fn (via the
   per-ns :harvested map from harvest/harvest-args); each survivor gets an
   order-independent per-mutant seed so the run is reproducible. When :harvested is
   absent the oracle falls back to seeded random generation (weaker — see §2.2)."
  [survivors {:keys [file->ns harvested n-trials seed timeout-ms]
              :or {n-trials 200 seed 42}}]
  (mapv (fn [s]
          (let [ns-sym (get file->ns (:file s))
                fsym   (survivor-fn-sym s)
                rng    (Random. (long (bit-xor (long seed)
                                               (hash [(:file s) (:line s) (:column s) (:operator s)]))))
                inputs (when (and harvested fsym)
                         (harvest/inputs-for rng (get harvested ns-sym) fsym {}))]
            ;; MERGE the tagged verdict into the survivor, so it gains :triage
            ;; (the label) + the one valid arm field (:witness | :proof | :reason
            ;; | :trials) directly — the §4.5 shape.
            (merge s (classify-survivor
                      s (cond-> {:ns-sym ns-sym :n-trials n-trials :seed seed}
                          inputs     (assoc :inputs inputs)
                          timeout-ms (assoc :timeout-ms timeout-ms))))))
        survivors))

(defn summary
  "Counts per :triage label over a triaged survivor seq (for reporting)."
  [triaged]
  (frequencies (map :triage triaged)))
