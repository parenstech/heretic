(ns heretic.process-worker-test
  "LAYER-1 tests for the generic forked-worker harness — deterministic, NO
   ClojureStorm. They drive a trivial echo/spin child (heretic.fixtures.echo-worker)
   under plain `clj` and assert the four properties the spike flagged as risky:

   (a) ONE warm child serves K requests in order (load-once amortization);
   (b) a HANGING request triggers destroyForcibly + the harness RESPAWNS and
       finishes the remaining requests;
   (c) NO orphan child process survives (the Process is not alive afterwards);
   (d) a child that prints JUNK to stdout does not corrupt the protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.process-worker :as pw]))

;; The fixture child runs under a plain clj with src+test on the classpath. These
;; tests are launched that way (bb test:fast / `clj -Sdeps {:paths [src test]}`),
;; so the child inherits the same classpath via a fresh `clojure -M` invocation.
(def echo-spec
  {:main 'heretic.fixtures.echo-worker
   :deps {:paths ["src" "test"]}})

;; ---------------------------------------------------------------------------
;; build-command (pure)
;; ---------------------------------------------------------------------------

(deftest build-command-shapes-test
  (testing ":main with no aliases -> bare -M -m"
    (is (= ["clojure" "-M" "-m" "my.main"]
           (pw/build-command {:main 'my.main}))))
  (testing "aliases fold into the -M flag"
    (is (= ["clojure" "-M:storm:test" "-m" "my.main"]
           (pw/build-command {:main 'my.main :aliases [:storm :test]}))))
  (testing ":jvm-opts get -J, :deps become -Sdeps, :args append after main"
    (is (= ["clojure" "-J-Xmx1g" "-Sdeps" (pr-str {:paths ["src"]})
            "-M" "-m" "my.main" "extra"]
           (pw/build-command {:main 'my.main
                              :jvm-opts ["-Xmx1g"]
                              :deps {:paths ["src"]}
                              :args ["extra"]}))))
  (testing ":code path uses -e"
    (is (= ["clojure" "-M" "-e" "(println 1)"]
           (pw/build-command {:code "(println 1)"}))))
  (testing "missing :main and :code throws"
    (is (thrown? Exception (pw/build-command {})))))

;; ---------------------------------------------------------------------------
;; (a) one warm child serves K requests in order (load-once)
;; ---------------------------------------------------------------------------

(deftest one-warm-child-serves-k-in-order-test
  (let [reqs (mapv (fn [i] {:op :echo :n i}) (range 8))
        results (pw/run-requests echo-spec reqs {:timeout-ms 30000})]
    (testing "K results in request order"
      (is (= (mapv :n reqs) (mapv :n results)))
      (is (every? #(= :verdict (:tag %)) results)))
    (testing "served counter is 1..K from a SINGLE child (monotonic, no respawn)"
      (is (= (vec (range 1 9)) (mapv :served results))))))

(deftest ping-reports-single-pid-test
  (let [results (pw/run-requests echo-spec
                                 (repeat 5 {:op :ping})
                                 {:timeout-ms 30000})
        pids (set (map :pid results))]
    (testing "all 5 pings answered by the SAME child pid (warm, load-once)"
      (is (= 5 (count results)))
      (is (= 1 (count pids)) (str "expected one pid, got " pids)))))

;; ---------------------------------------------------------------------------
;; (b) hanging request -> destroyForcibly + respawn finishes the queue
;; ---------------------------------------------------------------------------

(deftest hang-triggers-kill-and-respawn-test
  (let [reqs [{:op :echo :n 0}
              {:op :echo :n 1}
              {:op :spin}            ; uninterruptible tight loop -> must be killed
              {:op :echo :n 3}
              {:op :echo :n 4}]
        results (pw/run-requests echo-spec reqs {:timeout-ms 3000
                                                 :on-timeout :respawn})]
    (testing "all 5 requests produce a result, in order"
      (is (= 5 (count results))))
    (testing "the spin request is recorded as a timeout"
      (is (= :timeout (:tag (nth results 2))))
      (is (= {:op :spin} (:request (nth results 2)))))
    (testing "requests before AND after the hang are real verdicts"
      (is (= :verdict (:tag (nth results 0))))
      (is (= :verdict (:tag (nth results 1))))
      (is (= :verdict (:tag (nth results 3))))
      (is (= :verdict (:tag (nth results 4))))
      (is (= [0 1 3 4] (mapv :n [(nth results 0) (nth results 1)
                                 (nth results 3) (nth results 4)]))))
    (testing "the post-respawn child is FRESH: its served counter restarts at 1"
      ;; pre-hang child served n=0 then n=1 (served 1,2); after respawn the new
      ;; child serves n=3 then n=4 with served 1,2 again.
      (is (= [1 2] (mapv :served [(nth results 0) (nth results 1)])))
      (is (= [1 2] (mapv :served [(nth results 3) (nth results 4)]))))))

(deftest hang-with-on-timeout-stop-skips-rest-test
  (let [reqs [{:op :echo :n 0} {:op :spin} {:op :echo :n 2}]
        results (pw/run-requests echo-spec reqs {:timeout-ms 2500
                                                 :on-timeout :stop})]
    (is (= 3 (count results)))
    (is (= :verdict (:tag (nth results 0))))
    (is (= :timeout (:tag (nth results 1))))
    (is (= :skipped (:tag (nth results 2))))))

;; ---------------------------------------------------------------------------
;; (c) no orphan child survives
;; ---------------------------------------------------------------------------

(deftest no-orphan-child-survives-normal-test
  (let [captured (atom nil)]
    (pw/with-worker echo-spec
      (fn [send!]
        (reset! captured @(deref #'heretic.process-worker/live-children))
        (is (= :verdict (:tag (send! {:op :echo :n 1} 30000))))))
    ;; After with-worker returns, every tracked child must be dead.
    (is (every? #(not (.isAlive ^Process %)) @captured)
        "child process must not be alive after with-worker returns")))

(deftest no-orphan-child-survives-after-hang-test
  ;; Even when a child is force-killed mid-run and respawned, NOTHING is left
  ;; alive once run-requests returns.
  (let [reqs [{:op :spin} {:op :echo :n 1}]
        _ (pw/run-requests echo-spec reqs {:timeout-ms 2500})
        live @(deref #'heretic.process-worker/live-children)]
    (is (every? #(not (.isAlive ^Process %)) live)
        "no spawned child (original or respawn) may survive run-requests")
    ;; And the harness untracked them all.
    (is (empty? live) "live-children set must be empty after run-requests")))

;; ---------------------------------------------------------------------------
;; (d) junk on stdout does not corrupt the protocol
;; ---------------------------------------------------------------------------

(deftest junk-stdout-does-not-desync-test
  (let [reqs [{:op :echo :n 0}
              {:op :junk :n 1}      ; prints 2 junk lines, then the real verdict
              {:op :echo :n 2}
              {:op :junk :n 3}
              {:op :echo :n 4}]
        results (pw/run-requests echo-spec reqs {:timeout-ms 30000})]
    (testing "all verdicts arrive in order despite interleaved junk stdout"
      (is (= 5 (count results)))
      (is (every? #(= :verdict (:tag %)) results))
      (is (= [0 1 2 3 4] (mapv :n results)))
      ;; served stays a clean 1..5 -> same warm child, no desync/respawn.
      (is (= [1 2 3 4 5] (mapv :served results))))))
