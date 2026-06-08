(ns heretic.oracle.harness
  "Orchestration for the G1 recall measurement (docs/g1-recall-measurement-plan.md
   §5/§6), strengthened per the §2.2 trio:
     1. sound.clj        — sound lower bound (read/macroexpand identity).
     2. harvest.clj      — suite-seeded + perturbed oracle inputs (reach solved).
     3. suite/verdict-selected — covering-test selection (scales to big/generative
                            suites) when a coverage index is supplied.

   Pipeline per mutant: PROVE equivalent (sound, cheap) → else differential oracle
   on harvested+perturbed inputs → real-suite cross-check → confusion matrix +
   the [sound-lower, joint-upper] bracket.

   Fast suite (whole-suite, no ClojureStorm):
     clojure -Sdeps '{:paths [\"src\" \"validation/medley/src\" \"validation/medley/test\"]}' \\
       -M -e \"(require 'heretic.oracle.harness)
               (heretic.oracle.harness/-main \\\"validation/medley/src\\\" \\\"medley.core-test\\\")\"
   Selection (big/generative suite — run under the target's :heretic alias so the
   coverage index loads), pass the .heretic dir as a 3rd arg."
  (:require [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.oracle.differential :as diff]
            [heretic.oracle.harvest :as harvest]
            [heretic.oracle.sound :as sound]
            [heretic.oracle.suite :as suite])
  (:import [java.util Random]))

(defn- mutant-fn-sym [m]
  (some-> (engine/original-form-string m) diff/form-def-info :name))

(defn run
  "Full pipeline. opts: {:source-paths :test-ns :heretic-dir :operators :n-trials
   :seed :perturb-k :limit}. :test-ns enables the suite cross-check + harvesting;
   :heretic-dir enables covering-test selection. Returns aggregate + raw results."
  [{:keys [source-paths test-ns heretic-dir operators n-trials seed perturb-k limit]
    :or   {operators ops/all-operators n-trials 200 seed 42 perturb-k 8}}]
  (let [all      (vec (engine/generate-mutations source-paths operators nil))
        muts     (if limit (vec (take limit all)) all)
        files    (distinct (map :file muts))
        file->ns (into {} (map (fn [f] [f (diff/file->ns-sym f)]) files))
        nses     (distinct (vals file->ns))
        _        (doseq [ns-sym nses] (when ns-sym (require ns-sym)))
        _        (when test-ns (require test-ns))
        index    (when heretic-dir
                   ((requiring-resolve 'heretic.coverage-map/load-index) heretic-dir))
        harvested (when test-ns
                    (into {} (map (fn [ns-sym] [ns-sym (harvest/harvest-args ns-sym test-ns {})]) nses)))
        results  (mapv
                  (fn [m]
                    (let [ns-sym (file->ns (:file m))
                          fsym   (mutant-fn-sym m)
                          proof  (sound/sound-equivalent m ns-sym)
                          o (if proof
                              {:label :sound-equivalent :proof proof}
                              (let [rng (Random. (long (bit-xor (long seed)
                                                                (hash [(:file m) (:line m) (:column m) (:operator m)]))))
                                    inputs (when harvested
                                             (harvest/inputs-for rng (get harvested ns-sym) fsym {:perturb-k perturb-k}))]
                                (try (diff/classify-mutant m {:n-trials n-trials :seed seed :ns-sym ns-sym :inputs inputs})
                                     (catch Throwable t {:label :error :error (str (.getName (class t)) ": " (.getMessage t))}))))
                          s (when test-ns
                              (try (if index
                                     (suite/verdict-selected m ns-sym index {})
                                     (suite/verdict m ns-sym test-ns {}))
                                   (catch Throwable t (keyword (str "suite-err-" (.getSimpleName (class t)))))))]
                      (assoc o :operator (:operator m) :line (:line m) :file (:file m) :suite s)))
                  muts)]
    {:total (count results) :n-trials n-trials :seed seed
     :selection? (boolean index) :suite? (boolean test-ns)
     :by-label (frequencies (map :label results))
     :results results}))

(defn confusion [results]
  (let [judged (filter #(and (#{:killable :candidate-equivalent} (:label %))
                             (#{:killed :survived} (:suite %))) results)
        cell (fn [lab sv] (count (filter #(and (= lab (:label %)) (= sv (:suite %))) judged)))]
    {:judged (count judged)
     :k-k (cell :killable :killed)   :k-s (cell :killable :survived)
     :c-k (cell :candidate-equivalent :killed) :c-s (cell :candidate-equivalent :survived)}))

(defn summarize [{:keys [total by-label suite? selection? results] :as r}]
  (let [proven (get by-label :sound-equivalent 0)
        killable (get by-label :killable 0)
        cand (get by-label :candidate-equivalent 0)
        na   (get by-label :oracle-not-applicable 0)
        errs (get by-label :error 0)]
    (println "=== G1 measurement (sound lower bound + differential oracle + suite cross-check) ===")
    (println (format "total mutants          : %d   (n-trials=%d, %s suite%s)" total (:n-trials r)
                     (if suite? "with" "no") (if selection? ", covering-test selection" "")))
    (println (format "PROVEN equivalent (sound): %d  (read/macroexpand identity — FP=0)" proven))
    (println (format "oracle killable (witness): %d  (sound)" killable))
    (println (format "oracle candidate-equiv   : %d" cand))
    (println (format "oracle not-applicable    : %d" na))
    (when (pos? errs) (println (format "errors                   : %d" errs)))
    (when suite?
      (let [{:keys [k-k k-s c-k c-s judged]} (confusion results)
            of (fn [sv] (count (filter #(= sv (:suite %)) results)))
            gate-den (+ k-k c-k)
            ;; soundness invariant: a PROVEN-equivalent mutant the suite KILLS would be a bug
            bad (filter #(and (= :sound-equivalent (:label %)) (= :killed (:suite %))) results)
            joint c-s
            lo proven
            hi (+ proven joint)]
        (println)
        (println (format "suite verdicts (all)     : killed=%d survived=%d no-coverage=%d"
                         (of :killed) (of :survived) (of :no-coverage)))
        (println (format "confusion (%d judged): k/k=%d  k/s=%d(coverage-gap)  c/k=%d(gen-miss)  c/s=%d(joint-surv)"
                         judged k-k k-s c-k c-s))
        (when (pos? gate-den)
          (println (format "PHASE-1 GATE (oracle recall on suite-killed): %d/%d = %.0f%%"
                           k-k gate-den (* 100.0 (/ (double k-k) gate-den)))))
        (println (format "TRUE-EQUIVALENT BRACKET  : [%d, %d] = [%.1f%%, %.1f%%] of %d mutants"
                         lo hi (* 100.0 (/ (double lo) (max 1 total))) (* 100.0 (/ (double hi) (max 1 total))) total))
        (println (format "  sound-proven (lower)   : %d ; +unproven joint survivors (upper): %d" lo joint))
        (println (format "COVERAGE GAPS (oracle kills, suite misses): %d" k-s))
        (println (format "SOUNDNESS INVARIANT (proven-equiv suite-KILLED, must be 0): %d %s"
                         (count bad) (if (zero? (count bad)) "OK" (str "VIOLATED " (mapv #(select-keys % [:operator :line]) bad)))))
        (println "unproven joint survivors (candidate-equiv ∩ suite-survived):")
        (doseq [x (filter #(and (= :candidate-equivalent (:label %)) (= :survived (:suite %))) results)]
          (println "   " (pr-str (select-keys x [:operator :line]))))))
    r))

(defn -main [& args]
  (let [[src test-ns heretic-dir] args]
    (summarize (run (cond-> {:source-paths [(or src "validation/medley/src")]}
                      test-ns     (assoc :test-ns (symbol test-ns))
                      heretic-dir (assoc :heretic-dir heretic-dir))))
    (flush)
    (System/exit 0)))
