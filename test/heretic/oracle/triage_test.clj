(ns heretic.oracle.triage-test
  "End-to-end test of survivor triage on a temp fixture namespace: each tagged arm
   (coverage-gap+witness / proven-equivalent / not-applicable) is exercised by a
   purpose-built fn, so the classification + the tagged shape are verified without
   a real target/run."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.oracle.triage :as triage])
  (:import [java.io File]))

(deftest classify-survivor-tags-each-arm
  (let [nm (str "heretic.oracle.triagefix" (System/nanoTime))
        f  (File/createTempFile "tri" ".cljc")]
    (try
      ;; line 2: pure + → killable (no test here exercises it) → coverage-gap + witness
      ;; line 3: impure (swap!) → oracle can't replay → not-applicable
      ;; line 4: the + lives in the :cljs branch → JVM-dead → read-identity → proven-equivalent
      (spit f (str "(ns " nm ")\n"
                   "(defn add [a b] (+ a b))\n"
                   "(defn imp [a] (swap! a inc))\n"
                   "(defn dead [x] #?(:clj x :cljs (+ x 9)))\n"))
      (load-file (.getPath f))
      (let [muts (engine/mutations-for-file (.getPath f) ops/all-operators)
            at   (fn [op line] (first (filter #(and (= op (:operator %)) (= line (:line %))) muts)))
            ctx  {:ns-sym (symbol nm) :n-trials 100 :seed 1}]
        (testing "a killable mutation → :coverage-gap with a replayable witness"
          (let [r (triage/classify-survivor (at :swap-plus-minus 2) ctx)]
            (is (= :coverage-gap (:triage r)))
            (is (some? (:witness r)) "ships the witnessing input")
            (is (not (contains? r :proof)) "tagged: no :proof on the coverage-gap arm")))
        (testing "an impure fn's mutation → :not-applicable (oracle can't replay)"
          (let [r (triage/classify-survivor (at :swap-inc-dec 3) ctx)]
            (is (= :not-applicable (:triage r)))
            (is (= :impure (:reason r)))))
        (testing "a #?(:cljs …)-branch mutation → :proven-equivalent (sound)"
          (let [r (triage/classify-survivor (at :swap-plus-minus 4) ctx)]
            (is (= :proven-equivalent (:triage r)))
            (is (= :read-identity (:proof r)))
            (is (not (contains? r :witness)) "tagged: no :witness on the proven-equivalent arm")))
        (testing "an unloaded namespace → :undetermined (never a false claim)"
          (is (= :undetermined (:triage (triage/classify-survivor (at :swap-plus-minus 2)
                                                                  (assoc ctx :ns-sym 'no.such.ns999)))))))
      (finally
        (remove-ns (symbol nm))
        (.delete f)))))

(deftest triage-survivors-merges-verdict-and-summarizes
  (let [nm (str "heretic.oracle.triagefix2" (System/nanoTime))
        f  (File/createTempFile "tri2" ".clj")]
    (try
      (spit f (str "(ns " nm ")\n(defn add [a b] (+ a b))\n"))
      (load-file (.getPath f))
      (let [muts (engine/mutations-for-file (.getPath f) ops/all-operators)
            plus (filterv #(= :swap-plus-minus (:operator %)) muts)
            triaged (triage/triage-survivors plus {:file->ns {(.getPath f) (symbol nm)}
                                                   :n-trials 80 :seed 1})]
        (testing "the verdict is merged onto the survivor (gains :triage directly)"
          (is (= :coverage-gap (:triage (first triaged))))
          (is (= :swap-plus-minus (:operator (first triaged))) "original survivor fields preserved"))
        (testing "summary counts per label"
          (is (= {:coverage-gap 1} (triage/summary triaged)))))
      (finally
        (remove-ns (symbol nm))
        (.delete f)))))
