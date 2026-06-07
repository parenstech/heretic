(ns heretic.killmatrix-test
  "Tests for heretic.killmatrix — resumable/chunked kill-matrix persistence.

   The driver's effects are injected (:evaluate-one), so the whole layer is
   exercised here without ClojureStorm: a deterministic stub evaluator gives each
   mutation a fixed killer set, and we assert that an uninterrupted run, a
   kill+resume, and a chunk-split all reassemble the IDENTICAL kill-matrix."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [heretic.killmatrix :as km]))

;; =============================================================================
;; Fixtures: synthetic mutations + a deterministic stub evaluator
;; =============================================================================

(def mutations
  ;; 9 distinct mutation sites across 2 files. :id is the volatile UUID-like field
  ;; that the stable key must IGNORE.
  (vec (for [[i [file form coord op line]]
             (map-indexed vector
                          [["a.clj" 1 "1" :swap-plus-minus 1]
                           ["a.clj" 1 "2" :swap-lt-lte 1]
                           ["a.clj" 2 "1" :swap-and-or 3]
                           ["a.clj" 2 "1,1" :swap-eq-neq 3]
                           ["a.clj" 3 "1" :swap-plus-minus 5]
                           ["b.clj" 4 "1" :swap-lt-lte 1]
                           ["b.clj" 4 "2" :swap-and-or 1]
                           ["b.clj" 5 "1" :replace-0-to-1 2]
                           ["b.clj" 5 "2" :replace-1-to-0 2]])]
         {:id (str "uuid-" i) :file file :form-id form :coord coord :operator op :line line})))

(defn stub-evaluate-one
  "Deterministic killer assignment giving the matrix real dominator structure:
   the :killed-by-all set is keyed off the operator so some mutants share killers."
  [m]
  (let [killers (case (:operator m)
                  :swap-plus-minus #{'t/a 't/b}
                  :swap-lt-lte     #{'t/a}            ; subset of {t/a t/b} -> dominator
                  :swap-and-or     #{'t/a 't/b 't/c}
                  :swap-eq-neq     #{'t/c}
                  :replace-0-to-1  #{'t/d}
                  :replace-1-to-0  #{}                ; survived
                  nil)]
    (if (seq killers)
      {:status :killed :killed-by (first (sort killers)) :killed-by-all killers :eval-ms 1}
      {:status :survived :killed-by nil :killed-by-all nil :eval-ms 1})))

(defn- tmp-log []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "heretic-km-test-" (System/nanoTime) ".ndjson"))))

(defn- fingerprint
  "Canonical fingerprint of a kill-matrix analysis for equality assertions."
  [{:keys [dominators minimal-mutants stats]}]
  {:dominator-count (count dominators)
   :minimal-cover (count minimal-mutants)
   :stats stats})

;; =============================================================================
;; Stable identity / uniqueness
;; =============================================================================

(deftest mutation-key-ignores-uuid-test
  (testing "the stable key is UUID-independent and order-stable"
    (let [m (first mutations)]
      (is (= (km/mutation-key m)
             (km/mutation-key (assoc m :id "different-uuid"))))
      (is (not= (km/mutation-key (nth mutations 0))
                (km/mutation-key (nth mutations 1)))))))

(deftest assert-unique-keys-test
  (testing "passes on distinct sites, throws on a collision"
    (is (= mutations (km/assert-unique-keys! mutations)))
    (let [collide (conj mutations (assoc (first mutations) :id "dup"))]
      (is (thrown? clojure.lang.ExceptionInfo (km/assert-unique-keys! collide))))))

(deftest mutation-key-distinguishes-same-coord-different-column-test
  (testing "sibling literals share a :coord but differ by :column — distinct keys, no false collision"
    ;; Regression: three `0`s under one coord path (e.g. runner.clj `0`s at one
    ;; "…,V48" coord, columns 33/41/50) all match on file/form-id/coord/operator/line.
    ;; Keying without :column collapsed them and assert-unique-keys! wrongly rejected
    ;; a valid run through the :process executor.
    (let [base {:file "a.clj" :form-id 1 :coord "4,1,V48" :operator :replace-0-to-1
                :original "0" :replacement "1" :line 90}
          siblings [(assoc base :column 30)
                    (assoc base :column 38)
                    (assoc base :column 47)]]
      (is (= 3 (count (distinct (map km/mutation-key siblings))))
          "each sibling literal gets a distinct key")
      (is (= siblings (km/assert-unique-keys! siblings))
          "assert-unique-keys! accepts the distinct sites")))
  (testing "one operator emitting different replacements at one column stays distinct"
    (let [at {:file "a.clj" :form-id 1 :coord "2,1" :operator :replace-nil-x
              :line 5 :column 7 :original ""}]
      (is (not= (km/mutation-key (assoc at :replacement "0"))
                (km/mutation-key (assoc at :replacement "false"))))))
  (testing "still collides on a genuine duplicate (identical location + replacement)"
    (let [m {:file "a.clj" :form-id 1 :coord "1" :operator :swap-plus-minus
             :line 1 :column 3 :original "+" :replacement "-"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (km/assert-unique-keys! [m (assoc m :id "other-uuid")]))))))

;; =============================================================================
;; Chunking
;; =============================================================================

(deftest select-chunk-partitions-exactly-test
  (testing "n chunks partition the ordered coll: union = all, pairwise disjoint, balanced"
    (let [ordered (km/order-mutations mutations)
          n 3
          chunks (mapv #(km/select-chunk ordered % n) (range n))]
      (is (= (set ordered) (set (apply concat chunks))) "union covers everything")
      (is (= (count ordered) (reduce + (map count chunks))) "no overlap (counts sum)")
      (is (apply distinct? (mapcat #(map km/mutation-key %) chunks)) "no duplicate keys across chunks")
      (is (<= (- (apply max (map count chunks)) (apply min (map count chunks))) 1)
          "chunk sizes differ by at most 1"))))

;; =============================================================================
;; Log round-trip
;; =============================================================================

(deftest log-roundtrip-and-garbage-tolerance-test
  (testing "append then load returns the entries; a trailing garbage line is skipped"
    (let [log (tmp-log)]
      (try
        (km/append-result! log (first mutations) (stub-evaluate-one (first mutations)))
        (km/append-result! log (second mutations) (stub-evaluate-one (second mutations)))
        (is (= 2 (count (km/load-log log))))
        ;; simulate a power-loss truncated final line
        (spit log "{:key \"truncated\" :status :kil" :append true)
        (is (= 2 (count (km/load-log log))) "garbage trailing line is skipped, not fatal")
        (finally (io/delete-file log true))))))

;; =============================================================================
;; The headline guarantees: uninterrupted == resumed == chunk-split
;; =============================================================================

(deftest uninterrupted-run-test
  (testing "a full run logs every mutation exactly once"
    (let [log (tmp-log)]
      (try
        (let [r (km/run-resumable! {:mutations mutations :log-path log
                                    :evaluate-one stub-evaluate-one :fresh? true})]
          (is (= (count mutations) (:ran r)))
          (is (= 0 (:skipped r)))
          (is (= (count mutations) (count (km/load-log log)))))
        (finally (io/delete-file log true))))))

(deftest kill-then-resume-matches-uninterrupted-test
  (testing "kill after K, resume the rest -> identical matrix to an uninterrupted run"
    (let [base-log (tmp-log)
          resume-log (tmp-log)]
      (try
        ;; uninterrupted baseline
        (km/run-resumable! {:mutations mutations :log-path base-log
                            :evaluate-one stub-evaluate-one :fresh? true})
        ;; interrupted: run only the first 4, then resume the remainder
        (let [r1 (km/run-resumable! {:mutations mutations :log-path resume-log
                                     :evaluate-one stub-evaluate-one :fresh? true :limit 4})
              r2 (km/run-resumable! {:mutations mutations :log-path resume-log
                                     :evaluate-one stub-evaluate-one})]
          (is (= 4 (:ran r1)))
          (is (= (- (count mutations) 4) (:ran r2)) "resume runs exactly the remainder")
          (is (= 4 (:skipped r2)))
          (is (= (km/done-keys (km/load-log base-log))
                 (km/done-keys (km/load-log resume-log))) "same keyset")
          (is (= (fingerprint (km/analyze-log base-log))
                 (fingerprint (km/analyze-log resume-log))) "IDENTICAL kill-matrix"))
        (finally (io/delete-file base-log true) (io/delete-file resume-log true))))))

(deftest chunk-split-reassembles-identical-matrix-test
  (testing "running 3 disjoint chunks into one log -> identical matrix to a full run"
    (let [full-log (tmp-log)
          chunk-log (tmp-log)]
      (try
        (km/run-resumable! {:mutations mutations :log-path full-log
                            :evaluate-one stub-evaluate-one :fresh? true})
        ;; three chunks append into the SAME log (separate processes would too)
        (doseq [i (range 3)]
          (km/run-resumable! {:mutations mutations :log-path chunk-log
                              :evaluate-one stub-evaluate-one :chunk [i 3]}))
        (let [full (km/load-log full-log)
              chunked (km/load-log chunk-log)]
          (is (= (count full) (count chunked)) "no missing or duplicate mutants")
          (is (apply distinct? (map :key chunked)) "no cross-chunk duplicate keys")
          (is (= (km/done-keys full) (km/done-keys chunked)) "same keyset")
          (is (= (fingerprint (km/analyze-log full-log))
                 (fingerprint (km/analyze-log chunk-log))) "IDENTICAL kill-matrix"))
        (finally (io/delete-file full-log true) (io/delete-file chunk-log true))))))

(deftest resume-on-complete-log-is-noop-test
  (testing "re-running a complete log evaluates nothing"
    (let [log (tmp-log)]
      (try
        (km/run-resumable! {:mutations mutations :log-path log
                            :evaluate-one stub-evaluate-one :fresh? true})
        (let [r (km/run-resumable! {:mutations mutations :log-path log
                                    :evaluate-one (fn [_] (throw (ex-info "should not run" {})))})]
          (is (= 0 (:ran r)))
          (is (= (count mutations) (:skipped r))))
        (finally (io/delete-file log true))))))

(deftest analyze-log-recovers-dominator-structure-test
  (testing "kill-matrix-analysis over the log finds the expected dominator structure"
    (let [log (tmp-log)]
      (try
        (km/run-resumable! {:mutations mutations :log-path log
                            :evaluate-one stub-evaluate-one :fresh? true})
        (let [{:keys [dominators minimal-mutants stats]} (km/analyze-log log)]
          ;; 7 killed (2 survived: replace-1-to-0 #{} and ... only replace-1-to-0),
          ;; with real subsumption structure -> fewer dominators than killed.
          (is (pos? (count dominators)))
          (is (< (count dominators) (:total-mutants stats))
              "shared killers -> some mutants are dominated (non-degenerate)")
          (is (pos? (count minimal-mutants))))
        (finally (io/delete-file log true))))))
