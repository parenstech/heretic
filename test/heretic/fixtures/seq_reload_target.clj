(ns heretic.fixtures.seq-reload-target
  "Fixture for heretic.sequential-reload-test: a tiny namespace the test mutates,
   reloads, and runs a covering test against. Deliberately NOT a `*-test`
   namespace, so Kaocha does not execute it directly — but `cover` is a real
   `deftest` (carries :test metadata) so heretic.runner can run it via
   clojure.test/test-var, exactly as it runs a consumer's covering tests."
  (:require [clojure.test :refer [deftest is]]))

(defn add [a b]
  (+ a b))

(deftest cover
  ;; Passes on the original `(+ a b)`; fails once `add` is mutated to `(- a b)`,
  ;; so the sequential evaluator must score the +→- mutant as `killed`.
  (is (= 5 (add 2 3))))
