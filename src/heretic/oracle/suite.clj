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
            [heretic.mutation-engine :as engine])
  (:import [java.io StringWriter]))

(defn verdict
  "Return :killed | :survived for `mutant` against `test-ns`, or a reason keyword
   when the mutant can't be formed. A mutant that won't compile, or whose tests
   fail/error, or that hangs past `suite-timeout-ms`, is :killed (all three would
   fail CI). opts: {:suite-timeout-ms}."
  [mutant ns-sym test-ns {:keys [suite-timeout-ms] :or {suite-timeout-ms 10000}}]
  (let [the-ns   (some-> ns-sym find-ns)
        orig-str (engine/original-form-string mutant)
        mut-str  (when orig-str (engine/mutated-form-string mutant orig-str))]
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

(defn- run-test-vars
  "Run exactly `test-vars` (with their namespaces' fixtures), capturing fail+error
   counts. Returns {:fail n :error n} or ::timeout."
  [test-vars suite-timeout-ms]
  (let [fut (future
              (binding [test/*report-counters* (ref test/*initial-report-counters*)
                        test/*test-out* (StringWriter.)]
                (test/test-vars test-vars)
                (select-keys @test/*report-counters* [:fail :error])))]
    (deref fut suite-timeout-ms ::timeout)))

(defn verdict-selected
  "Like `verdict` but runs only the COVERING tests for `mutant` (looked up in the
   coverage `index` via heretic.runner/tests-for-mutation) instead of the whole
   suite — the scaling fix for large / generative suites. :no-coverage when the
   index maps the mutant to no tests. The runner is resolved lazily so this ns
   stays loadable without ClojureStorm; pass an `index` only under a classpath
   where heretic.coverage-map loads (the :heretic alias). opts: {:suite-timeout-ms}."
  [mutant ns-sym index {:keys [suite-timeout-ms] :or {suite-timeout-ms 10000}}]
  (let [tests-for-mutation (requiring-resolve 'heretic.runner/tests-for-mutation)
        test-vars (->> (tests-for-mutation index mutant)
                       (keep (fn [s] (try (requiring-resolve s) (catch Throwable _ nil))))
                       vec)
        the-ns   (some-> ns-sym find-ns)
        orig-str (engine/original-form-string mutant)
        mut-str  (when orig-str (engine/mutated-form-string mutant orig-str))]
    (cond
      (empty? test-vars)                  :no-coverage
      (nil? the-ns)                       :ns-not-loaded
      (or (nil? orig-str) (nil? mut-str)) :no-form
      :else
      (try
        (let [ev (try (binding [*ns* the-ns]
                        (eval (read-string {:read-cond :allow} mut-str)))
                      :ok
                      (catch Throwable t t))]
          (if (instance? Throwable ev)
            :killed
            (let [r (run-test-vars test-vars suite-timeout-ms)]
              (cond
                (= r ::timeout)                     :killed
                (pos? (+ (:fail r 0) (:error r 0))) :killed
                :else                               :survived))))
        (finally
          (binding [*ns* the-ns]
            (try (eval (read-string {:read-cond :allow} orig-str))
                 (catch Throwable _ nil))))))))
