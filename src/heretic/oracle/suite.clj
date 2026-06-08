(ns heretic.oracle.suite
  "In-memory mutation-test verdict: run a target's REAL test suite against one
   mutant, with no ClojureStorm, no coverage index, and no file write. Reuses the
   differential oracle's in-memory form rewrite — eval the mutated top-level form
   (which rebinds the target var; var indirection means the suite picks up the
   mutant), run the test namespace, then restore.

   This is the Phase-0/Phase-1 cross-check (docs/g1-recall-measurement-plan.md):
   joined with the differential-oracle verdict it yields the confusion matrix that
   separates true equivalents from generator misses and surfaces coverage gaps."
  (:require [clojure.test :as test]
            [heretic.oracle.differential :as diff])
  (:import [java.io StringWriter]))

(defn verdict
  "Return :killed | :survived for `mutant` against `test-ns`, or a reason keyword
   when the mutant can't be formed. A mutant that won't compile, or whose tests
   fail/error, or that hangs past `suite-timeout-ms`, is :killed (all three would
   fail CI). opts: {:suite-timeout-ms}."
  [mutant ns-sym test-ns {:keys [suite-timeout-ms] :or {suite-timeout-ms 10000}}]
  (let [the-ns   (some-> ns-sym find-ns)
        orig-str (diff/original-form-string mutant)
        mut-str  (when orig-str (diff/mutated-form-string mutant orig-str))]
    (cond
      (nil? the-ns)                       :ns-not-loaded
      (or (nil? orig-str) (nil? mut-str)) :no-form
      :else
      (try
        (let [ev (try (binding [*ns* the-ns]
                        (eval (read-string {:read-cond :allow} mut-str)))
                      :ok
                      (catch Throwable t t))]
          (if (instance? Throwable ev)
            :killed                                   ; won't compile ⇒ every covering test errors
            (let [fut (future (binding [test/*test-out* (StringWriter.)]
                                (test/run-tests test-ns)))
                  r   (deref fut suite-timeout-ms ::timeout)]
              (if (= r ::timeout)
                (do (future-cancel fut) :killed)      ; a hanging test would time out in CI ⇒ killed
                (if (pos? (+ (:fail r 0) (:error r 0)))
                  :killed
                  :survived)))))
        (finally
          (binding [*ns* the-ns]
            (try (eval (read-string {:read-cond :allow} orig-str))
                 (catch Throwable _ nil))))))))
