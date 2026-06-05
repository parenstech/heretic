(ns heretic.process-pool-test
  "LAYER-1 tests for the generic N-worker pool (B3b) — deterministic, NO
   ClojureStorm. They drive the trivial echo/spin child (heretic.fixtures.echo-worker)
   across N pooled children under plain `clj` and assert the properties the spike
   flagged as risky for a process pool:

   (a) N warm children drain ONE shared queue — every key served EXACTLY ONCE
       (no duplicates, no drops), results keyed back correctly;
   (b) a HANGING request in ONE worker triggers destroyForcibly + respawn of THAT
       worker, and ALL the other keys still get served by the pool;
   (c) NO orphan child survives (the whole live-children set is empty afterwards);
   (d) the on-respawn hook fires with the killed worker's INDEX.

   The pool returns results in REQUEST order (re-sorted from completion order), so
   a keyed request set comes back aligned with its input."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.process-pool :as pool]))

(defn- result-key
  "The request key of a pool result — a verdict carries it as :key, a timeout/dead/
   skipped marker carries it under :request."
  [r]
  (or (:key r) (get-in r [:request :key])))

(defn- index-by-key [results]
  (into {} (map (fn [r] [(result-key r) r]) results)))

(def echo-spec
  {:main 'heretic.fixtures.echo-worker
   :deps {:paths ["src" "test"]}})

;; ---------------------------------------------------------------------------
;; (a) N children drain ONE shared queue, each key served exactly once
;; ---------------------------------------------------------------------------

(deftest pool-serves-all-keys-exactly-once-test
  (let [keys (mapv #(str "k" %) (range 20))
        reqs (mapv (fn [k] {:op :echo :n k :key k}) keys)
        results (pool/run-pool (repeat 3 echo-spec) reqs {:timeout-ms 30000})]
    (testing "one result per request, in request order"
      (is (= (count reqs) (count results)))
      (is (= keys (mapv :n results)) "results aligned to request order")
      (is (= keys (mapv :key results)) "each verdict echoes its request key"))
    (testing "every key served EXACTLY once (no dup, no drop)"
      (is (= (set keys) (set (map :key results))))
      (is (= (count keys) (count (distinct (map :key results))))))
    (testing "verdicts came from MORE than one child pid (real parallelism)"
      (let [pids (set (map :pid results))]
        (is (>= (count pids) 2) (str "expected >=2 worker pids, got " pids))
        (is (<= (count pids) 3) "no more than N distinct workers")))))

(deftest pool-of-one-degenerate-test
  (testing "N=1 pool behaves like a single warm child draining the queue"
    (let [reqs (mapv (fn [i] {:op :echo :n i :key i}) (range 6))
          results (pool/run-pool [echo-spec] reqs {:timeout-ms 30000})]
      (is (= (mapv :n reqs) (mapv :n results)))
      (is (= 1 (count (set (map :pid results)))) "one child served all 6"))))

;; ---------------------------------------------------------------------------
;; (b) one key hangs in one worker -> that worker killed+respawned; all keys
;;     still served exactly once across the pool
;; ---------------------------------------------------------------------------

(deftest hang-in-one-worker-respawns-and-pool-finishes-test
  (let [;; 12 echo keys + 1 spin key that hangs whichever worker grabs it.
        echo-keys (mapv #(str "e" %) (range 12))
        reqs (-> (mapv (fn [k] {:op :echo :n k :key k}) echo-keys)
                 ;; Put the hang in the MIDDLE so work is dispatched before AND
                 ;; after the kill+respawn.
                 (->> (split-at 6))
                 ((fn [[a b]] (vec (concat a [{:op :spin :key "HANG"}] b)))))
        respawns (atom [])
        results (pool/run-pool
                 (repeat 2 echo-spec) reqs
                 {:timeout-ms 2500
                  :on-timeout :respawn
                  :on-respawn (fn [idx req] (swap! respawns conj [idx (:key req)]))})
        by-key (index-by-key results)]
    (testing "every request produced exactly one result, each key present once"
      (is (= (count reqs) (count results)))
      (is (= (set (map :key reqs)) (set (map result-key results)))
          "every request key appears exactly once across results"))
    (testing "the spin key is recorded as a timeout (the worker was force-killed)"
      (is (= :timeout (:tag (by-key "HANG")))))
    (testing "ALL 12 echo keys are real verdicts, served exactly once"
      (doseq [k echo-keys]
        (is (= :verdict (:tag (by-key k))) (str "echo key " k " must be a verdict"))
        (is (= k (:n (by-key k))) (str "echo key " k " echoed its n"))))
    (testing "the on-respawn hook fired for the killed worker with its index + the hung key"
      (is (= 1 (count @respawns)) "exactly one respawn")
      (let [[idx k] (first @respawns)]
        (is (#{0 1} idx) "respawn carried a valid worker index")
        (is (= "HANG" k) "respawn carried the killed request's key")))
    (testing "no orphan child survives the pool run"
      (let [live @(deref #'heretic.process-worker/live-children)]
        (is (empty? live) "live-children must be empty after run-pool")))))

;; ---------------------------------------------------------------------------
;; (c)/(d) on-timeout :stop — killed worker NOT respawned, surviving worker
;;         still drains the remaining queue; nothing left alive
;; ---------------------------------------------------------------------------

(deftest stop-policy-other-workers-still-drain-test
  (let [echo-keys (mapv #(str "s" %) (range 10))
        reqs (vec (concat [{:op :spin :key "HANG"}]
                          (mapv (fn [k] {:op :echo :n k :key k}) echo-keys)))
        results (pool/run-pool (repeat 2 echo-spec) reqs
                               {:timeout-ms 2500 :on-timeout :stop})
        by-key (index-by-key results)]
    (testing "every request has exactly one result"
      (is (= (count reqs) (count results))))
    (testing "the hung key is a timeout; the killed worker is NOT respawned but the OTHER worker drains the rest"
      (is (= :timeout (:tag (by-key "HANG"))))
      (doseq [k echo-keys]
        (is (= :verdict (:tag (by-key k)))
            (str "key " k " must still be served by the surviving worker"))))
    (testing "no orphan child survives"
      (is (empty? @(deref #'heretic.process-worker/live-children))))))
