(ns heretic.nrepl-runner-test
  "Tests for nREPL-based remote test execution.

   Note: Most tests here are unit tests for pure functions.
   Integration tests require a running nREPL server and are skipped
   by default. Run with :integration tag to include them."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.nrepl-runner :as nrepl]
            [heretic.self-test :as self-test]))

;; =============================================================================
;; Unit Tests (no nREPL server required)
;; =============================================================================

(deftest test-connect-creates-connection-map
  (testing "connect! returns expected structure"
    ;; We can't actually connect without a server, but we can test the function exists
    ;; and has the right signature by checking it throws when port is invalid
    (is (thrown? Exception (nrepl/connect! -1)))))

(deftest test-disconnect-handles-nil
  (testing "disconnect! handles nil connection gracefully"
    (is (nil? (nrepl/disconnect! nil)))
    (is (nil? (nrepl/disconnect! {})))))

(deftest test-connected-returns-false-for-invalid
  (testing "connected? returns false for invalid connection"
    (is (false? (nrepl/connected? nil)))
    (is (false? (nrepl/connected? {})))
    (is (false? (nrepl/connected? {:client nil})))))

;; =============================================================================
;; Self-Test Module Tests
;; =============================================================================

(deftest test-file-to-namespace
  (testing "file-to-namespace converts paths correctly"
    (is (= 'heretic.core
           (self-test/file-to-namespace "src/heretic/core.clj")))
    (is (= 'heretic.mutation-engine
           (self-test/file-to-namespace "src/heretic/mutation_engine.clj")))
    (is (= 'heretic.nrepl-runner
           (self-test/file-to-namespace "src/heretic/nrepl_runner.clj")))
    (is (nil? (self-test/file-to-namespace "test/heretic/core_test.clj")))
    (is (nil? (self-test/file-to-namespace "README.md")))))

(deftest test-namespace-to-test-ns
  (testing "namespace-to-test-ns appends -test"
    (is (= 'heretic.core-test
           (self-test/namespace-to-test-ns 'heretic.core)))
    (is (= 'heretic.mutation-engine-test
           (self-test/namespace-to-test-ns 'heretic.mutation-engine)))))

(deftest test-main-to-worktree-path
  (testing "main-to-worktree-path converts paths"
    (let [worktree "/tmp/heretic-test"]
      (is (= "/tmp/heretic-test/src/heretic/core.clj"
             (self-test/main-to-worktree-path
              "src/heretic/core.clj" worktree))))))

;; =============================================================================
;; Integration Tests (require running nREPL server)
;; =============================================================================
;; These tests are tagged with ^:integration and skipped by default.
;; To run them:
;; 1. Start an nREPL server: clj -M:dev -m nrepl.cmdline --port 7888
;; 2. Run: clj -M:test --focus-meta :integration

(deftest ^:integration test-connect-and-eval
  (testing "can connect to local nREPL and evaluate code"
    (let [port 7888  ;; Default test port
          conn (nrepl/connect! port)]
      (try
        (is (nrepl/connected? conn))
        (finally
          (nrepl/disconnect! conn))))))

(deftest ^:integration test-reload-namespace
  (testing "can reload a namespace via nREPL"
    (let [port 7888
          conn (nrepl/connect! port)]
      (try
        (let [result (nrepl/reload-namespace conn 'clojure.string)]
          (is (= :ok (:status result))))
        (finally
          (nrepl/disconnect! conn))))))

(deftest ^:integration test-run-tests-remote
  (testing "can run tests via nREPL"
    (let [port 7888
          conn (nrepl/connect! port)]
      (try
        ;; Set up test runner first
        (nrepl/setup-test-runner! conn)

        ;; Run a simple test namespace
        (let [result (nrepl/run-tests-remote conn ['clojure.test-clojure.test-fixtures])]
          (is (= :ok (:status result)))
          (is (map? (:results result)))
          (is (contains? (:results result) :pass)))
        (finally
          (nrepl/disconnect! conn))))))
