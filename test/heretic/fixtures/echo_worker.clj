(ns heretic.fixtures.echo-worker
  "Trivial generic forked-worker child for heretic.process-worker LAYER-1 tests.

   NO heretic/ClojureStorm coupling — it runs under plain `clj` so the risky
   spawn / framing / timeout / kill / respawn machinery is deterministically
   testable. It reads ONE EDN request map per line from stdin and writes its
   verdict(s) to stdout per the process-worker protocol:

   - normal request  {:op :echo :n N}  -> one verdict line {:tag :verdict :n N ...}
   - load-once probe {:op :ping}        -> {:tag :verdict :pid <pid> :served K}
                                           (`served` is this child's monotonic
                                           request count, so a test can prove ONE
                                           warm child served K requests in order)
   - hang            {:op :spin}         -> never replies (tight CPU loop) — the
                                           parent must destroyForcibly + respawn
   - junk-then-reply {:op :junk}         -> prints a NON-verdict line to STDOUT
                                           first, then the real verdict, proving a
                                           stray stdout line can't desync framing

   All diagnostics go to STDERR; STDOUT carries ONLY verdict lines."
  (:require [clojure.edn :as edn]))

(defn- pid []
  (try (.pid (java.lang.ProcessHandle/current)) (catch Throwable _ -1)))

(defn -main [& _]
  (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
        out System/out
        served (atom 0)
        emit! (fn [m] (.println out (pr-str m)) (.flush out))]
    (binding [*out* *err*]
      (println "[echo-worker] started pid" (pid)))
    (loop []
      (when-let [line (.readLine rdr)]
        (let [req (try (edn/read-string line) (catch Exception _ nil))
              n (swap! served inc)]
          (case (:op req)
            :spin
            ;; Tight uninterruptible CPU loop — the parent's only recourse is to
            ;; kill the process. Burn the volatile so the JIT can't elide it.
            (let [x (volatile! 0)]
              (loop [] (vswap! x unchecked-inc) (recur)))

            :junk
            (do
              ;; A stray, NON-verdict stdout line. The parent reader must ignore
              ;; it (not a map with :tag :verdict) and still pick up the verdict.
              (.println out "this is not edn { unbalanced")
              (.println out (pr-str {:tag :not-a-verdict :noise true}))
              (.flush out)
              (emit! {:tag :verdict :op :junk :n (:n req) :served n}))

            :ping
            (emit! {:tag :verdict :op :ping :pid (pid) :served n})

            ;; default :echo — echoes :n AND :key (the pool addresses by :key, so
            ;; a verdict must carry the request's key back for keyed aggregation).
            (emit! {:tag :verdict :op :echo :n (:n req) :key (:key req)
                    :pid (pid) :served n})))
        (recur)))))
