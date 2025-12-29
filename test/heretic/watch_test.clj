(ns heretic.watch-test
  "Tests for heretic.watch module.

   Tests cover:
   - File classification (source vs test files)
   - Status tracking
   - Helper functions"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [heretic.watch :as watch]))

;; =============================================================================
;; Test Config
;; =============================================================================

(def test-config
  {:source-paths ["src"]
   :test-paths ["test"]
   :heretic-dir ".heretic"
   :timeout-ms 1000})

;; =============================================================================
;; Helper Function Tests
;; =============================================================================

(deftest clj-file?-test
  (testing "clj-file? identifies Clojure files"
    (is (#'watch/clj-file? "foo.clj"))
    (is (#'watch/clj-file? "path/to/bar.cljc"))
    (is (not (#'watch/clj-file? "foo.cljs")))
    (is (not (#'watch/clj-file? "foo.txt")))
    (is (not (#'watch/clj-file? nil)))))

(deftest in-path?-test
  (testing "in-path? checks if file is under given paths"
    ;; Use actual paths that exist
    (is (#'watch/in-path? ["."] "deps.edn"))
    (is (#'watch/in-path? ["src"] "src/heretic/core.clj"))
    (is (not (#'watch/in-path? ["nonexistent"] "src/heretic/core.clj")))))

(deftest source-file?-test
  (testing "source-file? identifies source files"
    (is (#'watch/source-file? test-config "src/heretic/core.clj"))
    (is (not (#'watch/source-file? test-config "test/heretic/core_test.clj")))))

(deftest test-file?-test
  (testing "test-file? identifies test files"
    (is (#'watch/test-file? test-config "test/heretic/core_test.clj"))
    (is (not (#'watch/test-file? test-config "src/heretic/core.clj")))))

;; =============================================================================
;; Status Tests
;; =============================================================================

(deftest status-not-running-test
  (testing "status returns not running when watcher is stopped"
    (let [status (watch/status)]
      (is (false? (:running? status))))))

;; Note: Testing start-watch! and stop! requires actual file system watching
;; which is better suited for integration tests. These unit tests focus on
;; the pure helper functions.
