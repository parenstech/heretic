(ns heretic.oracle.sound
  "Sound (FP=0) equivalence detectors — the LOWER bound of the bracket
   (docs/g1-recall-measurement-plan.md §4.1, Half-3). Unlike the differential
   oracle (which gives an UPPER bound via failure-to-find-a-witness), every
   verdict here is a PROOF of equivalence:

   - read-identity     : the JVM reader (:read-cond :allow) collapses a .cljc form
                         exactly as the compiler does, so if the original and
                         mutated forms READ to = data, the mutation lives in
                         JVM-dead code (e.g. a `#?(:cljs …)` branch) and cannot
                         change a single byte the JVM compiles. This is the cheap,
                         sound dead-branch detector — the dominant equivalent class
                         on .cljc targets (validation-results.md §2.2).
   - macroexpand-identity : if both forms macroexpand-all to = code in the target
                         ns, they compile to the same thing → equivalent (catches a
                         mutation a macro discards). Needs the ns loaded.

   Both are conservative: a false NEGATIVE (failing to prove an equivalent) only
   loosens the lower bound; neither can ever call a killable mutant equivalent."
  (:require [clojure.walk :as walk]
            [heretic.equivalent :as equiv]
            [heretic.mutation-engine :as engine]))

;; read-identity is the production sound predicate (heretic.equivalent/read-identical?);
;; the oracle delegates to it rather than re-defining the one FP=0 rule — a second
;; copy could silently drift from what the filter actually ships.

(defn macroexpand-identical?
  "True when orig-str and mut-str macroexpand-all to = code in ns-sym. Sound:
   identical expansion ⇒ identical compiled code ⇒ equivalent. Returns false on
   any read/expand error (can't prove ⇒ don't claim)."
  [ns-sym orig-str mut-str]
  (try
    (when-let [the-ns (find-ns ns-sym)]
      (binding [*ns* the-ns]
        (= (walk/macroexpand-all (read-string {:read-cond :allow} orig-str))
           (walk/macroexpand-all (read-string {:read-cond :allow} mut-str)))))
    (catch Throwable _ false)))

(defn sound-equivalent
  "Return the proof tag (:read-identity | :macroexpand-identity) if `mutant` is
   PROVABLY equivalent, else nil. Cheap: no test run, no input generation."
  [mutant ns-sym]
  (let [orig (engine/original-form-string mutant)
        mut  (when orig (engine/mutated-form-string mutant orig))]
    (when (and orig mut)
      (cond
        (equiv/read-identical? orig mut)            :read-identity
        (macroexpand-identical? ns-sym orig mut)    :macroexpand-identity
        :else                                       nil))))
