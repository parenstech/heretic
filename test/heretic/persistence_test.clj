(ns heretic.persistence-test
  "Tests for split storage and atomic file operations.

   These tests verify:
   - Atomic file writes
   - EDN serialization round-trips
   - File hashing for staleness detection
   - Per-namespace coverage file management"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.persistence :as persist]
            [clojure.java.io :as io]))

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

;; TODO: Add tests for staleness when files change

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
