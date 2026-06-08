(ns heretic.oracle.harness
  "Orchestration for the G1 recall measurement (docs/g1-recall-measurement-plan.md
   §5/§6). Generates the mutant pool for a target, loads its namespace(s) + test
   namespace, runs the differential oracle AND the real test suite over every
   mutant, and reports the confusion matrix that separates true equivalents from
   generator misses and coverage gaps.

   Run from the heretic repo with the TARGET's src + test on the classpath:
     clojure -Sdeps '{:paths [\"src\" \"validation/medley/src\" \"validation/medley/test\"]}' \\
       -M -e \"(require 'heretic.oracle.harness)
               (heretic.oracle.harness/-main \\\"validation/medley/src\\\" \\\"medley.core-test\\\")\"
   No ClojureStorm — the oracle calls the target fns directly and runs the suite
   in-memory."
  (:require [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.oracle.differential :as diff]
            [heretic.oracle.suite :as suite]))

(defn run
  "Classify every mutant for `source-paths` with the differential oracle and (when
   `test-ns` is given) the real suite. opts: {:operators :n-trials :seed :limit
   :test-ns}. Returns the aggregate + raw results."
  [{:keys [source-paths operators n-trials seed limit test-ns]
    :or   {operators ops/all-operators n-trials 200 seed 42}}]
  (let [all      (vec (engine/generate-mutations source-paths operators nil))
        muts     (if limit (vec (take limit all)) all)
        files    (distinct (map :file muts))
        file->ns (into {} (map (fn [f] [f (diff/file->ns-sym f)]) files))
        _        (doseq [ns-sym (distinct (vals file->ns))] (when ns-sym (require ns-sym)))
        _        (when test-ns (require test-ns))
        results  (mapv
                  (fn [m]
                    (let [ns-sym (file->ns (:file m))
                          o (try (diff/classify-mutant
                                  m {:n-trials n-trials :seed seed :ns-sym ns-sym})
                                 (catch Throwable t
                                   {:label :error
                                    :error (str (.getName (class t)) ": " (.getMessage t))}))
                          s (when test-ns
                              (try (suite/verdict m ns-sym test-ns {})
                                   (catch Throwable t (keyword (str "suite-err-" (.getSimpleName (class t)))))))]
                      (assoc o :operator (:operator m) :line (:line m)
                             :file (:file m) :suite s)))
                  muts)]
    {:total    (count results)
     :n-trials n-trials :seed seed
     :by-label (frequencies (map :label results))
     :suite?   (boolean test-ns)
     :results  results}))

(defn confusion
  "Confusion matrix over mutants the oracle judged (killable | candidate-equivalent)
   AND the suite gave a clean :killed/:survived verdict."
  [results]
  (let [rs   (filter #(and (#{:killable :candidate-equivalent} (:label %))
                           (#{:killed :survived} (:suite %)))
                     results)
        cell (fn [lab sv] (count (filter #(and (= lab (:label %)) (= sv (:suite %))) rs)))]
    {:applicable (count rs)
     :k-k (cell :killable :killed)             ; agree: killable
     :k-s (cell :killable :survived)           ; COVERAGE GAP: oracle proves killable, suite misses
     :c-k (cell :candidate-equivalent :killed) ; FALSE candidate: generator miss (suite kills)
     :c-s (cell :candidate-equivalent :survived)})) ; JOINT SURVIVOR: true-equivalent candidate

(defn summarize [{:keys [total by-label suite? results] :as r}]
  (let [killable (get by-label :killable 0)
        cand     (get by-label :candidate-equivalent 0)
        na       (get by-label :oracle-not-applicable 0)
        errs     (get by-label :error 0)
        applic   (+ killable cand)]
    (println "=== G1 differential-oracle result ===")
    (println (format "total mutants        : %d" total))
    (println (format "oracle-applicable    : %d (%.0f%%)" applic (* 100.0 (/ (double applic) (max 1 total)))))
    (println (format "  killable (witness) : %d  (SOUND — a witness proves killability)" killable))
    (println (format "  candidate-equiv    : %d  (upper bound on equivalent — incl. generator misses)" cand))
    (println (format "not-applicable       : %d (HOF / impure / non-defn)" na))
    (when (pos? errs) (println (format "errors               : %d" errs)))
    (when suite?
      (let [{:keys [applicable k-k k-s c-k c-s]} (confusion results)
            suite-of (fn [sv] (count (filter #(= sv (:suite %)) results)))
            gate-den (+ k-k c-k)]
        (println)
        (println "=== suite cross-check (in-memory real test suite) ===")
        (println (format "suite verdicts (all mutants): killed=%d survived=%d other=%d"
                         (suite-of :killed) (suite-of :survived)
                         (- total (suite-of :killed) (suite-of :survived))))
        (println (format "confusion matrix over %d oracle-judged ∩ suite-clean mutants:" applicable))
        (println           "                          suite:KILLED   suite:SURVIVED")
        (println (format   "  oracle:killable             %4d           %4d   <- k-s = COVERAGE GAP (oracle finds kill suite missed)" k-k k-s))
        (println (format   "  oracle:candidate-equiv      %4d           %4d   <- c-s = JOINT SURVIVOR (true-equivalent candidate)" c-k c-s))
        (println           "                              ^ c-k = FALSE candidate (generator miss: suite kills it)")
        (println)
        (when (pos? gate-den)
          (println (format "PHASE-1 GATE  oracle-recall on suite-killed (applicable): %d/%d = %.0f%%  (plan target >=95%%)"
                           k-k gate-den (* 100.0 (/ (double k-k) gate-den)))))
        (println (format "TRUE-EQUIVALENT (joint survivors, c-s)         : %d  (= %.1f%% of all %d mutants)"
                         c-s (* 100.0 (/ (double c-s) (max 1 total))) total))
        (println (format "COVERAGE GAPS the oracle surfaced (k-s)        : %d  (suite-survived but provably killable)" k-s))
        (println "joint survivors (oracle candidate-equiv AND suite-survived):")
        (doseq [x (filter #(and (= :candidate-equivalent (:label %)) (= :survived (:suite %))) results)]
          (println "   " (pr-str (select-keys x [:operator :line]))))
        (println "coverage gaps (oracle killable BUT suite-survived):")
        (doseq [x (filter #(and (= :killable (:label %)) (= :survived (:suite %))) results)]
          (println "   " (pr-str (select-keys x [:operator :line]))))))
    r))

(defn -main [& args]
  (let [[src test-ns] args
        source-paths (vec [(or src "validation/medley/src")])]
    (summarize (run (cond-> {:source-paths source-paths}
                      test-ns (assoc :test-ns (symbol test-ns)))))
    (flush)
    (System/exit 0)))
