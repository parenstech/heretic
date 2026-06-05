(ns heretic.reloader-test
  "Tests for heretic.reloader namespace reloading wrapper.

   Tests cover:
   - init!: initialization, re-initialization, error handling
   - reload!: basic reload, error handling, not-initialized error
   - State management: initialized?, reset-state!

   Note: Full integration tests would require actual namespace files
   and file modifications. These tests focus on the wrapper API behavior."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.reloader :as reloader]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn with-temp-dir
  "Fixture that creates a temporary directory with sample source files."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-reloader-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (try
      (binding [*test-dir* (.getPath dir)]
        (f))
      (finally
        ;; Clean up
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(defn with-clean-state
  "Fixture that resets reloader state before and after each test."
  [f]
  (reloader/reset-state!)
  (try
    (f)
    (finally
      (reloader/reset-state!))))

(use-fixtures :each with-temp-dir with-clean-state)

;; =============================================================================
;; Initialization Tests
;; =============================================================================

(deftest init-basic-test
  (testing "init! initializes successfully with valid source paths"
    (let [result (reloader/init! [*test-dir*])]
      (is (true? (:success result))
          "init! should succeed with valid path")
      (is (true? (reloader/initialized?))
          "initialized? should return true after init!"))))

(deftest init-not-initialized-test
  (testing "initialized? returns false before init!"
    (is (false? (reloader/initialized?))
        "initialized? should return false before init!")))

(deftest init-idempotent-same-dirs-test
  (testing "init! is idempotent when called with same dirs"
    (reloader/init! [*test-dir*])
    (let [result (reloader/init! [*test-dir*])]
      (is (true? (:success result))
          "Repeated init! with same dirs should succeed")
      (is (true? (reloader/initialized?))
          "Should remain initialized"))))

(deftest init-reinitializes-different-dirs-test
  (testing "init! reinitializes when called with different dirs"
    (let [other-dir (str *test-dir* "/other")]
      (.mkdirs (io/file other-dir))
      (reloader/init! [*test-dir*])
      (let [result (reloader/init! [other-dir])]
        (is (true? (:success result))
            "init! with different dirs should succeed")
        (is (true? (reloader/initialized?))
            "Should remain initialized")))))

(deftest init-with-options-test
  (testing "init! accepts clj-reload options"
    (let [result (reloader/init! [*test-dir*]
                                 :no-reload #{'my.app.server}
                                 :output :quiet)]
      (is (true? (:success result))
          "init! with options should succeed"))))

;; =============================================================================
;; Reset State Tests
;; =============================================================================

(deftest reset-state-test
  (testing "reset-state! clears initialization"
    (reloader/init! [*test-dir*])
    (is (true? (reloader/initialized?))
        "Should be initialized before reset")
    (reloader/reset-state!)
    (is (false? (reloader/initialized?))
        "Should not be initialized after reset")))

;; =============================================================================
;; Reload Tests
;; =============================================================================

(deftest reload-not-initialized-test
  (testing "reload! throws when not initialized"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Reloader not initialized"
         (reloader/reload!)))))

(deftest reload-basic-test
  (testing "reload! returns success with empty reloaded when nothing changed"
    ;; Create a minimal namespace file
    (let [src-dir (io/file *test-dir* "src" "test_ns")]
      (.mkdirs src-dir)
      (spit (io/file src-dir "sample.clj")
            "(ns test-ns.sample)\n(def x 1)"))

    (reloader/init! [(str *test-dir* "/src")])
    (let [result (reloader/reload!)]
      (is (true? (:success result))
          "reload! should succeed")
      (is (vector? (:reloaded result))
          "reloaded should be a vector")
      (is (vector? (:unloaded result))
          "unloaded should be a vector"))))

(deftest reload-after-mutation-test
  (testing "reload-after-mutation! delegates to reload!"
    (reloader/init! [*test-dir*])
    (let [result (reloader/reload-after-mutation!)]
      (is (true? (:success result))
          "reload-after-mutation! should succeed")
      (is (contains? result :reloaded)
          "Should have :reloaded key")
      (is (contains? result :unloaded)
          "Should have :unloaded key"))))

(deftest reload-after-revert-test
  (testing "reload-after-revert! delegates to reload!"
    (reloader/init! [*test-dir*])
    (let [result (reloader/reload-after-revert!)]
      (is (true? (:success result))
          "reload-after-revert! should succeed")
      (is (contains? result :reloaded)
          "Should have :reloaded key")
      (is (contains? result :unloaded)
          "Should have :unloaded key"))))

;; =============================================================================
;; Find Test Namespaces Tests
;; =============================================================================

(deftest find-test-namespaces-not-initialized-test
  (testing "find-test-namespaces throws when not initialized"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Reloader not initialized"
         (reloader/find-test-namespaces)))))

(deftest find-test-namespaces-basic-test
  (testing "find-test-namespaces returns set of matching namespaces"
    ;; Create test namespace files
    (let [test-dir (io/file *test-dir* "test" "my" "app")]
      (.mkdirs test-dir)
      (spit (io/file test-dir "core_test.clj")
            "(ns my.app.core-test)")
      (spit (io/file test-dir "util_test.clj")
            "(ns my.app.util-test)"))

    (reloader/init! [(str *test-dir* "/test")])
    (let [result (reloader/find-test-namespaces)]
      (is (set? result)
          "find-test-namespaces should return a set")
      ;; Note: The actual namespace discovery depends on clj-reload's scanning
      ;; This test verifies the API returns the expected type
      )))

(deftest find-test-namespaces-custom-pattern-test
  (testing "find-test-namespaces accepts custom pattern"
    (reloader/init! [*test-dir*])
    (let [result (reloader/find-test-namespaces #".*-spec$")]
      (is (set? result)
          "Should return a set even with custom pattern"))))

;; =============================================================================
;; Integration Test: Reload Cycle
;; =============================================================================

(deftest reload-cycle-integration-test
  (testing "full reload cycle: init -> reload -> mutation -> reload -> revert -> reload"
    ;; Create source structure
    (let [src-dir (io/file *test-dir* "src" "app")]
      (.mkdirs src-dir)
      (spit (io/file src-dir "core.clj")
            "(ns app.core)\n(defn add [a b] (+ a b))"))

    ;; Initialize
    (let [init-result (reloader/init! [(str *test-dir* "/src")])]
      (is (true? (:success init-result))))

    ;; Initial reload (nothing changed)
    (let [reload1 (reloader/reload!)]
      (is (true? (:success reload1))))

    ;; Simulate mutation (modify file)
    (spit (io/file *test-dir* "src" "app" "core.clj")
          "(ns app.core)\n(defn add [a b] (- a b))")  ; Changed + to -

    ;; Reload after mutation
    (let [reload2 (reloader/reload-after-mutation!)]
      (is (true? (:success reload2))
          "Reload after mutation should succeed"))

    ;; Simulate revert (restore original)
    (spit (io/file *test-dir* "src" "app" "core.clj")
          "(ns app.core)\n(defn add [a b] (+ a b))")

    ;; Reload after revert
    (let [reload3 (reloader/reload-after-revert!)]
      (is (true? (:success reload3))
          "Reload after revert should succeed"))))

;; =============================================================================
;; Regression: value-captured re-exports must refresh on reload
;; =============================================================================
;;
;; A namespace that re-exports a var BY VALUE — (def f other/f) — captures the
;; function at load-time. Reloading ONLY the defining namespace leaves the
;; re-export pointing at the pre-mutation function, so a covering test that
;; reaches the mutated code through the re-export runs UN-mutated code and the
;; mutant is falsely scored `survived`. reload-mutated-file! must therefore
;; reload the defining namespace AND its transitive dependents.
;;
;; These tests use on-classpath fixtures (heretic.fixtures.reexport-{core,facade})
;; and init the reloader on the fixtures dir so the dependency graph is parseable.

(def ^:private reexport-core-ns 'heretic.fixtures.reexport-core)
(def ^:private reexport-facade-ns 'heretic.fixtures.reexport-facade)
(def ^:private reexport-fixtures-dir "test/heretic/fixtures")
(def ^:private reexport-core-file
  (.getPath (io/file "test" "heretic" "fixtures" "reexport_core.clj")))

(defn- facade-answer
  "Invoke the value-captured re-export heretic.fixtures.reexport-facade/answer,
   resolved at runtime (no compile-time dependency on the fixture)."
  []
  ((deref (find-var (symbol (name reexport-facade-ns) "answer")))))

(deftest reload-order-includes-reexport-dependents-test
  (testing "reload-order returns the mutated ns plus its by-value re-exporting dependent, dependency-first"
    (require reexport-core-ns :reload)
    (require reexport-facade-ns :reload)
    (reloader/init! [reexport-fixtures-dir])
    (let [order (vec (#'reloader/reload-order reexport-core-ns))]
      (is (some #{reexport-core-ns} order)
          "must include the mutated namespace itself")
      (is (some #{reexport-facade-ns} order)
          "must include the by-value re-exporting dependent")
      (is (< (.indexOf order reexport-core-ns)
             (.indexOf order reexport-facade-ns))
          "the dependency must be ordered before its dependent"))))

(deftest reexport-dependent-refreshes-after-reload-test
  (testing "reload-mutated-file! refreshes a value-captured re-export when the defining ns is mutated"
    (let [original (slurp reexport-core-file)]
      (try
        (require reexport-core-ns :reload)
        (require reexport-facade-ns :reload)
        (reloader/init! [reexport-fixtures-dir])
        (is (= :original (facade-answer))
            "baseline: the facade re-exports the original answer")

        ;; Mutate the DEFINING namespace on disk (as the mutate loop does).
        (spit reexport-core-file
              "(ns heretic.fixtures.reexport-core)\n(defn answer [] :mutated)\n")
        (let [result (reloader/reload-mutated-file! reexport-core-file)]
          (is (true? (:success result))
              "reload-mutated-file! reports success")
          (is (= :mutated (facade-answer))
              (str "the value-captured re-export MUST reflect the mutation; "
                   "if it still returns :original the mutant would falsely survive")))
        (finally
          ;; Restore so the mutation never leaks into other tests.
          (spit reexport-core-file original)
          (require reexport-core-ns :reload)
          (require reexport-facade-ns :reload))))))
