(ns heretic.sequential-reload-test
  "Regression coverage for the sequential mutate path's namespace reload.

   The mutate loop spits a mutated source file, reloads it, then runs the
   covering tests. clj-reload's mtime-gated `reload!` silently SKIPS the reload
   when consecutive sub-millisecond spits (apply → revert → next apply) collide
   on one file mtime: the mutated bytecode never enters the JVM, the covering
   test passes against the original code, and the mutant is falsely scored
   `survived` (observed as a 0% score on small/fast projects). The sequential
   path therefore force-reloads via `reloader/reload-mutated-file!`, bypassing
   the mtime gate — mirroring the parallel path in `heretic.worker`.

   This namespace had NO coverage of the reload step before; the parallel path
   was fixed but the sequential path's regression slipped through.

   - `sequential-path-force-reloads-not-mtime-gated` is the deterministic guard:
     it fails if the path reverts to the mtime-gated `reload!`.
   - `sequential-path-kills-fast-mutant` drives the real end-to-end evaluator
     against a fixture namespace and asserts the mutant is killed."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [heretic.core :as core]
            [heretic.mutation-engine :as engine]
            [heretic.reloader :as reloader]
            [heretic.runner :as runner]))

(def ^:private target-ns 'heretic.fixtures.seq-reload-target)
(def ^:private target-file
  (.getPath (io/file "test" "heretic" "fixtures" "seq_reload_target.clj")))

(defn- with-restored-target
  "Run f, then restore the fixture file's original content and reload it, so a
   mutating test cannot leak the mutated definition into later tests."
  [f]
  (let [original (slurp target-file)]
    (try
      (f)
      (finally
        (spit target-file original)
        (require target-ns :reload)))))

(deftest sequential-path-force-reloads-not-mtime-gated
  (testing "evaluate-mutation-with-reload! force-reloads the mutated file, never the mtime-gated reload!"
    (let [forced (atom [])
          mtimed (atom 0)]
      (with-redefs [engine/apply-mutation!         (fn [m] (assoc m :backup "orig"))
                    engine/revert-mutation!        (fn [_] nil)
                    reloader/reload-mutated-file!  (fn [file] (swap! forced conj file) {:success true})
                    reloader/reload!               (fn [& _] (swap! mtimed inc) {:success true})
                    runner/evaluate-mutation       (fn [_idx applied _cfg]
                                                     {:mutation applied :status :killed})]
        (let [mutation {:file "src/sample/math.clj" :operator :swap-plus-minus :form-id 1 :coord "0"}
              result   (#'core/evaluate-mutation-with-reload! {} mutation {})]
          (is (= ["src/sample/math.clj"] @forced)
              "must force-reload the mutated file (bypassing clj-reload's mtime gate)")
          (is (zero? @mtimed)
              "must NOT use the mtime-gated reload!, which silently skips sub-ms-spit collisions")
          (is (= :killed (:status result))))))))

(deftest sequential-path-kills-fast-mutant
  (testing "the real sequential evaluator kills a +→- mutant end-to-end (apply → force-reload → run covering test)"
    (with-restored-target
      (fn []
        (require target-ns :reload)
        (let [plus  (->> (engine/mutations-for-file target-file)
                         (filter #(= :swap-plus-minus (:operator %)))
                         first)
              cover #{'heretic.fixtures.seq-reload-target/cover}
              index {:coord-to-tests {[(:form-id plus) (:coord plus)] cover}
                     :form-to-tests  {(:form-id plus) cover}}
              result (#'core/evaluate-mutation-with-reload! index plus {:timeout-ms 5000})]
          (is (some? plus) "fixture yields a +→- mutation")
          (is (= :killed (:status result))
              "the +→- mutant must be killed: the covering test fails on the reloaded, mutated code"))))))
