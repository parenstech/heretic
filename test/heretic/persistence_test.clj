(ns heretic.persistence-test
  "Tests for split storage and atomic file operations.

   These tests verify:
   - Atomic file writes
   - EDN serialization round-trips
   - File hashing for staleness detection
   - Per-namespace coverage file management"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.persistence :as persist]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn with-temp-dir
  "Fixture that creates a temporary directory for tests."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (try
      (binding [*test-dir* (.getPath dir)]
        (f))
      (finally
        ;; Clean up
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(use-fixtures :each with-temp-dir)

;; =============================================================================
;; Atomic File Operations
;; =============================================================================

(deftest test-save-load-edn-roundtrip
  (testing "EDN data survives save/load cycle"
    (let [path (io/file *test-dir* "test.edn")
          data {:foo "bar" :nums [1 2 3] :set #{:a :b}}]
      (persist/save-edn! path data)
      (is (= data (persist/load-edn path))))))

(deftest test-load-edn-missing-file
  (testing "Loading non-existent file returns nil"
    (is (nil? (persist/load-edn (io/file *test-dir* "does-not-exist.edn"))))))

(deftest test-save-creates-parent-dirs
  (testing "Save creates parent directories as needed"
    (let [path (io/file *test-dir* "a" "b" "c" "test.edn")
          data {:test true}]
      (persist/save-edn! path data)
      (is (.exists path))
      (is (= data (persist/load-edn path))))))

;; =============================================================================
;; File Hashing
;; =============================================================================

(deftest test-hash-file
  (testing "Same content produces same hash"
    (let [f1 (io/file *test-dir* "f1.txt")
          f2 (io/file *test-dir* "f2.txt")]
      (spit f1 "hello world")
      (spit f2 "hello world")
      (is (= (persist/hash-file f1) (persist/hash-file f2)))))

  (testing "Different content produces different hash"
    (let [f1 (io/file *test-dir* "f1.txt")
          f2 (io/file *test-dir* "f2.txt")]
      (spit f1 "hello")
      (spit f2 "world")
      (is (not= (persist/hash-file f1) (persist/hash-file f2))))))

(deftest test-hash-file-missing
  (testing "Hash of missing file is nil"
    (is (nil? (persist/hash-file (io/file *test-dir* "missing.txt"))))))

(deftest test-hash-files-order-independent
  (testing "Hash of multiple files is order-independent"
    (let [f1 (io/file *test-dir* "f1.txt")
          f2 (io/file *test-dir* "f2.txt")]
      (spit f1 "aaa")
      (spit f2 "bbb")
      (is (= (persist/hash-files [(.getPath f1) (.getPath f2)])
             (persist/hash-files [(.getPath f2) (.getPath f1)]))))))

;; =============================================================================
;; Per-Namespace Coverage Files
;; =============================================================================

(deftest test-coverage-file-path
  (testing "Generates correct path for namespace"
    (let [path (persist/coverage-file-path *test-dir* 'my.app.core-test)]
      (is (= "my-app-core-test.edn" (.getName path)))
      (is (.contains (.getPath path) "coverage")))))

(deftest test-save-load-test-ns-coverage
  (testing "Coverage data survives save/load"
    (persist/ensure-heretic-dir! *test-dir*)
    (let [data {:test-ns 'my.app.core-test
                :coverage {'my.app.core-test/test-foo
                           {12345 #{"3" "3,1"}}}
                :source-deps #{"src/my/app/core.clj"}
                :hashes {:test-file "abc123"
                         :source-files "def456"
                         :config "ghi789"}}]
      (persist/save-test-ns-coverage! *test-dir* data)
      (is (= data (persist/load-test-ns-coverage *test-dir* 'my.app.core-test))))))

(deftest test-list-coverage-files
  (testing "Lists all coverage files"
    (persist/ensure-heretic-dir! *test-dir*)
    (persist/save-test-ns-coverage! *test-dir* {:test-ns 'ns1})
    (persist/save-test-ns-coverage! *test-dir* {:test-ns 'ns2})
    (let [files (persist/list-coverage-files *test-dir*)]
      (is (= 2 (count files))))))

;; =============================================================================
;; Staleness Detection
;; =============================================================================

(deftest test-stale-when-no-coverage
  (testing "Namespace is stale when no coverage file exists"
    (persist/ensure-heretic-dir! *test-dir*)
    (is (true? (persist/test-ns-stale?
                *test-dir*
                'my.app.core-test
                ["test"]
                ["src"]
                {})))))

(deftest test-stale-when-test-file-changes
  (testing "Namespace is stale when test file changes"
    ;; Setup: create a test file and coverage with its hash
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file (io/file test-dir "core_test.clj")]
      (.mkdirs test-dir)
      (spit test-file "(ns my.app.core-test)")

      ;; Save coverage with current hash
      (persist/ensure-heretic-dir! *test-dir*)
      (let [current-hash (persist/hash-file test-file)]
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.core-test
                                         :coverage {}
                                         :source-deps #{}
                                         :hashes {:test-file current-hash
                                                  :source-files nil
                                                  :config (hash {})}}))

      ;; Not stale initially
      (is (false? (persist/test-ns-stale?
                   *test-dir*
                   'my.app.core-test
                   [(str (io/file *test-dir* "test"))]
                   ["src"]
                   {})))

      ;; Modify the test file
      (spit test-file "(ns my.app.core-test)\n(deftest new-test)")

      ;; Now it should be stale
      (is (true? (persist/test-ns-stale?
                  *test-dir*
                  'my.app.core-test
                  [(str (io/file *test-dir* "test"))]
                  ["src"]
                  {}))))))

(deftest test-stale-when-source-deps-change
  (testing "Namespace is stale when source dependencies change"
    ;; Setup: create source and test files
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file (io/file test-dir "core_test.clj")
          src-dir (io/file *test-dir* "src" "my" "app")
          src-file (io/file src-dir "core.clj")]
      (.mkdirs test-dir)
      (.mkdirs src-dir)
      (spit test-file "(ns my.app.core-test)")
      (spit src-file "(ns my.app.core)")

      ;; Save coverage with current hashes
      (persist/ensure-heretic-dir! *test-dir*)
      (let [test-hash (persist/hash-file test-file)
            src-path (.getPath src-file)
            source-hash (persist/hash-files [src-path])]
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.core-test
                                         :coverage {}
                                         :source-deps #{src-path}
                                         :hashes {:test-file test-hash
                                                  :source-files source-hash
                                                  :config (hash {})}}))

      ;; Not stale initially
      (is (false? (persist/test-ns-stale?
                   *test-dir*
                   'my.app.core-test
                   [(str (io/file *test-dir* "test"))]
                   [(str (io/file *test-dir* "src"))]
                   {})))

      ;; Modify the source file
      (spit src-file "(ns my.app.core)\n(defn foo [] 42)")

      ;; Now it should be stale
      (is (true? (persist/test-ns-stale?
                  *test-dir*
                  'my.app.core-test
                  [(str (io/file *test-dir* "test"))]
                  [(str (io/file *test-dir* "src"))]
                  {}))))))

(deftest test-stale-when-config-changes
  (testing "Namespace is stale when config changes"
    ;; Setup: create test file
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file (io/file test-dir "core_test.clj")]
      (.mkdirs test-dir)
      (spit test-file "(ns my.app.core-test)")

      ;; Save coverage with specific config hash
      (persist/ensure-heretic-dir! *test-dir*)
      (let [config {:source-paths ["src"]}
            test-hash (persist/hash-file test-file)]
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.core-test
                                         :coverage {}
                                         :source-deps #{}
                                         :hashes {:test-file test-hash
                                                  :source-files nil
                                                  :config (hash config)}}))

      ;; Not stale with same config
      (is (false? (persist/test-ns-stale?
                   *test-dir*
                   'my.app.core-test
                   [(str (io/file *test-dir* "test"))]
                   ["src"]
                   {:source-paths ["src"]})))

      ;; Stale with different config
      (is (true? (persist/test-ns-stale?
                  *test-dir*
                  'my.app.core-test
                  [(str (io/file *test-dir* "test"))]
                  ["src"]
                  {:source-paths ["src"] :new-option true}))))))

(deftest test-fresh-when-nothing-changed
  (testing "Namespace is fresh when nothing has changed"
    ;; Setup: create test file and source file with dependencies
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file (io/file test-dir "core_test.clj")
          src-dir (io/file *test-dir* "src" "my" "app")
          src-file (io/file src-dir "core.clj")
          config {:source-paths ["src"]}]
      (.mkdirs test-dir)
      (.mkdirs src-dir)
      (spit test-file "(ns my.app.core-test (:require [my.app.core]))")
      (spit src-file "(ns my.app.core)\n(defn foo [] 42)")

      ;; Save coverage with all current hashes
      (persist/ensure-heretic-dir! *test-dir*)
      (let [test-hash (persist/hash-file test-file)
            src-path (.getPath src-file)
            source-hash (persist/hash-files [src-path])]
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.core-test
                                         :coverage {'my.app.core-test/test-foo {12345 #{"3" "3,1"}}}
                                         :source-deps #{src-path}
                                         :hashes {:test-file test-hash
                                                  :source-files source-hash
                                                  :config (hash config)}}))

      ;; Verify it's fresh immediately after saving
      (is (false? (persist/test-ns-stale?
                   *test-dir*
                   'my.app.core-test
                   [(str (io/file *test-dir* "test"))]
                   [(str (io/file *test-dir* "src"))]
                   config))
          "Should be fresh immediately after coverage collection")

      ;; Verify it remains fresh on subsequent checks (simulating re-run)
      (is (false? (persist/test-ns-stale?
                   *test-dir*
                   'my.app.core-test
                   [(str (io/file *test-dir* "test"))]
                   [(str (io/file *test-dir* "src"))]
                   config))
          "Should remain fresh on repeated staleness checks"))))

(deftest test-find-stale-test-namespaces
  (testing "find-stale-test-namespaces returns correct set"
    ;; Setup: create two test namespaces, one fresh and one stale
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file-1 (io/file test-dir "core_test.clj")
          test-file-2 (io/file test-dir "util_test.clj")]
      (.mkdirs test-dir)
      (spit test-file-1 "(ns my.app.core-test)")
      (spit test-file-2 "(ns my.app.util-test)")

      (persist/ensure-heretic-dir! *test-dir*)
      (let [test-paths [(str (io/file *test-dir* "test"))]
            config {}]
        ;; Save coverage for core-test (will be fresh)
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.core-test
                                         :coverage {}
                                         :source-deps #{}
                                         :hashes {:test-file (persist/hash-file test-file-1)
                                                  :source-files nil
                                                  :config (hash config)}})

        ;; Save coverage for util-test, then modify the file (will be stale)
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.util-test
                                         :coverage {}
                                         :source-deps #{}
                                         :hashes {:test-file (persist/hash-file test-file-2)
                                                  :source-files nil
                                                  :config (hash config)}})

        ;; Modify util-test to make it stale
        (spit test-file-2 "(ns my.app.util-test)\n(deftest new-test)")

        ;; Also add a namespace with no coverage file (always stale)
        (spit (io/file test-dir "other_test.clj") "(ns my.app.other-test)")

        ;; Find stale namespaces
        (let [stale (persist/find-stale-test-namespaces
                     *test-dir*
                     ['my.app.core-test 'my.app.util-test 'my.app.other-test]
                     test-paths
                     ["src"]
                     config)]
          ;; core-test is fresh, util-test and other-test are stale
          (is (not (contains? stale 'my.app.core-test)))
          (is (contains? stale 'my.app.util-test))
          (is (contains? stale 'my.app.other-test)))))))

;; =============================================================================
;; Directory Management
;; =============================================================================

(deftest test-ensure-heretic-dir
  (testing "Creates heretic directory structure"
    (persist/ensure-heretic-dir! *test-dir*)
    (is (.exists (io/file *test-dir* "coverage")))))

(deftest test-clean-heretic-dir
  (testing "Removes heretic directory"
    (persist/ensure-heretic-dir! *test-dir*)
    (persist/save-edn! (io/file *test-dir* "test.edn") {:test true})
    (is (true? (persist/clean-heretic-dir! *test-dir*)))
    (is (not (.exists (io/file *test-dir*))))))
