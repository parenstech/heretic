(ns heretic.coverage-map.workflow-test
  "Tests for coverage collection workflow orchestration.

   Tests verify:
   - extract-source-deps finds touched source files
   - collect-test-namespace! returns proper coverage structure
   - rebuild-index! creates valid index from coverage files
   - collect-and-persist! orchestrates full workflow

   Note: Full integration tests requiring ClojureStorm are in
   heretic.form-bridge-integration-test."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.coverage-map.workflow :as workflow]
            [heretic.persistence :as persist]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *temp-dir* nil)

(defn- create-temp-dir
  "Create a temporary directory for test files."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-workflow-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    dir))

(defn- delete-recursively
  "Delete a directory and all its contents."
  [dir]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))]
      (.delete f))))

(defn temp-dir-fixture
  "Fixture that creates and cleans up a temp directory."
  [f]
  (let [dir (create-temp-dir)]
    (try
      (binding [*temp-dir* dir]
        (f))
      (finally
        (delete-recursively dir)))))

(use-fixtures :each temp-dir-fixture)

(defn- create-test-file!
  "Create a test file in the temp directory with given content.
   Returns the file path."
  [name content]
  (let [file (io/file *temp-dir* name)]
    (io/make-parents file)
    (spit file content)
    (.getPath file)))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-forms
  "Sample form registry for testing."
  {12345 {:form/ns "my.app.core"
          :form/emitted-coords #{"0" "0,0" "0,1"}}
   12346 {:form/ns "my.app.util"
          :form/emitted-coords #{"0" "0,0"}}})

(def sample-coverage
  "Sample coverage data from a test namespace."
  {'my.app-test/test-add {12345 #{"0" "0,0"}}
   'my.app-test/test-sub {12345 #{"0,1"} 12346 #{"0"}}})

;; =============================================================================
;; extract-source-deps Tests
;; =============================================================================

(deftest test-extract-source-deps-finds-files
  (testing "Finds source files for touched namespaces"
    ;; Create source files
    (let [src-dir (io/file *temp-dir* "src")
          _ (.mkdirs (io/file src-dir "my" "app"))
          _ (spit (io/file src-dir "my" "app" "core.clj") "(ns my.app.core)")
          _ (spit (io/file src-dir "my" "app" "util.clj") "(ns my.app.util)")
          source-paths [(.getPath src-dir)]
          deps (#'workflow/extract-source-deps sample-coverage sample-forms source-paths)]
      (is (set? deps) "Should return a set")
      (is (= 2 (count deps)) "Should find both source files")
      (is (some #(.contains % "core.clj") deps) "Should include core.clj")
      (is (some #(.contains % "util.clj") deps) "Should include util.clj"))))

(deftest test-extract-source-deps-handles-missing-files
  (testing "Ignores namespaces without corresponding source files"
    ;; Don't create any files - deps should be empty
    (let [source-paths [(.getPath *temp-dir*)]
          deps (#'workflow/extract-source-deps sample-coverage sample-forms source-paths)]
      (is (empty? deps) "Should return empty set when no files found"))))

(deftest test-extract-source-deps-handles-empty-coverage
  (testing "Returns empty set for empty coverage"
    (let [deps (#'workflow/extract-source-deps {} sample-forms [(.getPath *temp-dir*)])]
      (is (= #{} deps) "Should return empty set for empty coverage"))))

;; =============================================================================
;; rebuild-index! Tests
;; =============================================================================

(deftest test-rebuild-index-creates-valid-index
  (testing "Creates index file from coverage files"
    (let [heretic-dir (.getPath *temp-dir*)]
      ;; Setup: create coverage directory and files
      (persist/ensure-heretic-dir! heretic-dir)
      (persist/save-test-ns-coverage!
       heretic-dir
       {:test-ns 'my.app-test
        :coverage sample-coverage
        :source-deps #{}
        :hashes {}})

      ;; Setup: create meta file
      (persist/save-meta! heretic-dir {:forms {} :form-location-index {}})

      ;; Execute
      (workflow/rebuild-index! heretic-dir)

      ;; Verify
      (let [index (persist/load-index heretic-dir)]
        (is (some? index) "Index should exist after rebuild")
        (is (contains? index :coord-to-tests) "Index should have coord-to-tests")
        (is (contains? index :form-to-tests) "Index should have form-to-tests")
        (is (contains? index :rebuilt-at) "Index should have rebuild timestamp")
        (is (contains? (:included-test-ns index) 'my.app-test)
            "Index should include collected test namespace")))))

(deftest test-rebuild-index-handles-empty-coverage-dir
  (testing "Handles case with no coverage files gracefully"
    (let [heretic-dir (.getPath *temp-dir*)]
      ;; Setup: create empty coverage directory
      (persist/ensure-heretic-dir! heretic-dir)
      (persist/save-meta! heretic-dir {:forms {} :form-location-index {}})

      ;; Execute - should not throw
      (workflow/rebuild-index! heretic-dir)

      ;; Verify
      (let [index (persist/load-index heretic-dir)]
        (is (some? index) "Index should exist")
        (is (= {} (:coord-to-tests index)) "Should have empty coord-to-tests")
        (is (= #{} (:included-test-ns index)) "Should have empty included-test-ns")))))

(deftest test-rebuild-index-merges-multiple-coverage-files
  (testing "Merges coverage from multiple test namespaces"
    (let [heretic-dir (.getPath *temp-dir*)]
      ;; Setup
      (persist/ensure-heretic-dir! heretic-dir)
      (persist/save-test-ns-coverage!
       heretic-dir
       {:test-ns 'ns1-test
        :coverage {'ns1-test/test-a {100 #{"0"}}}})
      (persist/save-test-ns-coverage!
       heretic-dir
       {:test-ns 'ns2-test
        :coverage {'ns2-test/test-b {100 #{"0"} 200 #{"1"}}}})
      (persist/save-meta! heretic-dir {:forms {} :form-location-index {}})

      ;; Execute
      (workflow/rebuild-index! heretic-dir)

      ;; Verify merged index
      (let [index (persist/load-index heretic-dir)]
        (is (= #{'ns1-test 'ns2-test} (:included-test-ns index))
            "Should include both test namespaces")
        ;; Form 100 should have tests from both namespaces
        (is (= #{'ns1-test/test-a 'ns2-test/test-b}
               (get (:coord-to-tests index) [100 "0"]))
            "Should merge tests for same form/coord")))))

;; =============================================================================
;; Integration Tests (without ClojureStorm)
;; =============================================================================

(deftest test-workflow-roundtrip
  (testing "Full roundtrip: save coverage, rebuild index, load"
    (let [heretic-dir (.getPath *temp-dir*)]
      ;; Setup
      (persist/ensure-heretic-dir! heretic-dir)

      ;; Save coverage data
      (let [coverage-data {:test-ns 'roundtrip-test
                           :coverage {'roundtrip-test/test-x {42 #{"0" "1"}}}
                           :source-deps #{}
                           :hashes {:test-file "abc123" :source-files "def456" :config 789}}]
        (persist/save-test-ns-coverage! heretic-dir coverage-data))

      ;; Save metadata
      (persist/save-meta! heretic-dir {:forms {42 {:form/emitted-coords #{"0" "1" "2"}}}
                                       :form-location-index {}})

      ;; Rebuild index
      (workflow/rebuild-index! heretic-dir)

      ;; Load and verify
      (let [index (persist/load-index heretic-dir)
            coverage (persist/load-test-ns-coverage heretic-dir 'roundtrip-test)]
        (is (= #{'roundtrip-test} (:included-test-ns index))
            "Index should include the test namespace")
        (is (= #{'roundtrip-test/test-x}
               (get (:coord-to-tests index) [42 "0"]))
            "Index should have correct test mapping")
        (is (= 'roundtrip-test (:test-ns coverage))
            "Coverage data should be loadable")))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest test-extract-source-deps-multiple-source-paths
  (testing "Searches multiple source paths"
    ;; Create source in second path
    (let [src1 (io/file *temp-dir* "src1")
          src2 (io/file *temp-dir* "src2")
          _ (.mkdirs (io/file src2 "my" "app"))
          _ (spit (io/file src2 "my" "app" "core.clj") "(ns my.app.core)")
          source-paths [(.getPath src1) (.getPath src2)]
          ;; Only test coverage for core namespace
          coverage {'test/test-x {12345 #{"0"}}}
          forms {12345 {:form/ns "my.app.core"}}
          deps (#'workflow/extract-source-deps coverage forms source-paths)]
      (is (= 1 (count deps)) "Should find file in second source path")
      (is (some #(.contains % "src2") deps) "Should find in src2 directory"))))

(deftest test-workflow-preserves-form-location-index
  (testing "Form location index is preserved through rebuild"
    (let [heretic-dir (.getPath *temp-dir*)
          form-loc-idx {["/path/to/file.clj" 10] 12345
                        ["/path/to/other.clj" 20] 12346}]
      ;; Setup
      (persist/ensure-heretic-dir! heretic-dir)
      (persist/save-meta! heretic-dir {:forms {}
                                       :form-location-index form-loc-idx})

      ;; Rebuild
      (workflow/rebuild-index! heretic-dir)

      ;; Verify
      (let [index (persist/load-index heretic-dir)]
        (is (= form-loc-idx (:form-location-index index))
            "Form location index should be preserved")))))
