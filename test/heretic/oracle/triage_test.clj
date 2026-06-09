(ns heretic.oracle.triage-test
  "End-to-end test of survivor triage on a temp fixture namespace: every tagged arm
   (coverage-gap+witness / proven-equivalent / candidate-equivalent / not-applicable
   / undetermined — incl. both the :ns-not-loaded and :oracle-error reasons) is
   exercised by a purpose-built fn, so the classification + the tagged shape are
   verified without a real target/run."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.oracle.differential :as diff]
            [heretic.oracle.triage :as triage])
  (:import [java.io File]))

(deftest classify-survivor-tags-each-arm
  (let [nm (str "heretic.oracle.triagefix" (System/nanoTime))
        f  (File/createTempFile "tri" ".cljc")]
    (try
      ;; line 2: pure + → killable (no test here exercises it) → coverage-gap + witness
      ;; line 3: impure (swap!) → oracle can't replay → not-applicable
      ;; line 4: the + lives in the :cljs branch → JVM-dead → read-identity → proven-equivalent
      ;; line 5: both if-branches identical → true→false changes nothing → candidate-equivalent
      (spit f (str "(ns " nm ")\n"
                   "(defn add [a b] (+ a b))\n"
                   "(defn imp [a] (swap! a inc))\n"
                   "(defn dead [x] #?(:clj x :cljs (+ x 9)))\n"
                   "(defn const [a] (if true a a))\n"))
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
        (testing "no distinguishing input in N trials → :candidate-equivalent + :trials"
          (let [r (triage/classify-survivor (at :swap-true-false 5) ctx)]
            (is (= :candidate-equivalent (:triage r)))
            (is (= 100 (:trials r)) "the trial count is the number of inputs tried")
            (is (not (contains? r :witness)) "tagged: no :witness on the candidate-equivalent arm")))
        (testing "an unloaded namespace → :undetermined :ns-not-loaded (never a false claim)"
          (let [r (triage/classify-survivor (at :swap-plus-minus 2) (assoc ctx :ns-sym 'no.such.ns999))]
            (is (= :undetermined (:triage r)))
            (is (= :ns-not-loaded (:reason r)))))
        (testing "an oracle that throws → :undetermined :oracle-error (distinct from :not-applicable)"
          (with-redefs [diff/classify-mutant (fn [& _] (throw (ex-info "boom" {})))]
            (let [r (triage/classify-survivor (at :swap-plus-minus 2) ctx)]
              (is (= :undetermined (:triage r)))
              (is (= :oracle-error (:reason r)))))))
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
