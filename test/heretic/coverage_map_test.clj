(ns heretic.coverage-map-test
  "Tests for coverage index building and queries.

   These tests verify:
   - Inverse index construction
   - Query functions (tests-for-location)
   - Source dependency extraction"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [heretic.coverage-map :as coverage]))

;; =============================================================================
;; Inverse Index Building
;; =============================================================================

(deftest test-build-inverse-index-empty
  (testing "Empty input produces empty index"
    (is (= {} (coverage/build-inverse-index [])))))

(deftest test-build-inverse-index-single
  (testing "Single test coverage produces correct index"
    (let [coverage-data [{:coverage {'my.test/test-foo
                                     {12345 #{"3" "3,1"}}}}]
          result (coverage/build-inverse-index coverage-data)]
      (is (= #{'my.test/test-foo}
             (get result [12345 "3"])))
      (is (= #{'my.test/test-foo}
             (get result [12345 "3,1"]))))))

(deftest test-build-inverse-index-multiple-tests
  (testing "Multiple tests hitting same coord accumulate"
    (let [coverage-data [{:coverage {'my.test/test-foo
                                     {12345 #{"3"}}}}
                         {:coverage {'my.test/test-bar
                                     {12345 #{"3"}}}}]
          result (coverage/build-inverse-index coverage-data)]
      (is (= #{'my.test/test-foo 'my.test/test-bar}
             (get result [12345 "3"]))))))

;; =============================================================================
;; Query Functions
;; =============================================================================

(deftest test-tests-for-location-with-coord
  (testing "Returns tests for specific form+coord"
    (let [index {:coord-to-tests {[12345 "3"] #{'test-a 'test-b}
                                  [12345 "4"] #{'test-c}}}]
      (is (= #{'test-a 'test-b}
             (coverage/tests-for-location index 12345 "3")))
      (is (= #{'test-c}
             (coverage/tests-for-location index 12345 "4"))))))

(deftest test-tests-for-location-form-level
  (testing "Returns all tests for form when no coord given"
    ;; Index now includes form-to-tests for O(1) form-level lookup
    (let [index {:coord-to-tests {[12345 "3"] #{'test-a}
                                  [12345 "4"] #{'test-b}
                                  [67890 "1"] #{'test-c}}
                 :form-to-tests {12345 #{'test-a 'test-b}
                                 67890 #{'test-c}}}]
      (is (= #{'test-a 'test-b}
             (coverage/tests-for-location index 12345))))))

(deftest test-tests-for-location-missing
  (testing "Returns empty set for unknown location"
    (let [index {:coord-to-tests {}
                 :form-to-tests {}}]
      (is (= #{} (coverage/tests-for-location index 99999 "1")))
      (is (= #{} (coverage/tests-for-location index 99999))))))

;; =============================================================================
;; Source Dependency Tracking
;; =============================================================================

(deftest test-extract-source-deps
  (testing "Extracts touched source files from coverage"
    ;; Create a temporary source directory with a mock source file
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "heretic-test-" (System/currentTimeMillis)))
          src-dir (io/file temp-dir "src")
          ;; Create namespace directory structure: my/app/core.clj
          ns-dir (io/file src-dir "my" "app")
          source-file (io/file ns-dir "core.clj")]
      (try
        ;; Setup: create directory and file
        (.mkdirs ns-dir)
        (spit source-file "(ns my.app.core)")

        ;; Mock coverage data:
        ;; test-id -> {form-id -> #{coords}}
        (let [coverage {'my.test/test-foo {12345 #{"3" "3,1"}
                                           67890 #{"1"}}}
              ;; Mock forms registry: maps form-ids to namespace info
              forms {12345 {:form/id 12345
                            :form/ns "my.app.core"
                            :form/form '(defn foo [x] (+ x 1))}
                     67890 {:form/id 67890
                            :form/ns "my.app.core"
                            :form/form '(defn bar [y] (* y 2))}}
              source-paths [(.getPath src-dir)]
              result (coverage/extract-source-deps coverage forms source-paths)]
          ;; Should return set with the source file path
          (is (set? result) "Result should be a set")
          (is (= 1 (count result)) "Should find exactly one source file")
          (is (contains? result (.getPath source-file))
              "Should contain the resolved source file path"))
        (finally
          ;; Cleanup: remove temp files
          (when (.exists source-file) (.delete source-file))
          (when (.exists ns-dir) (.delete ns-dir))
          (when (.exists (io/file src-dir "my")) (.delete (io/file src-dir "my")))
          (when (.exists src-dir) (.delete src-dir))
          (when (.exists temp-dir) (.delete temp-dir))))))

  (testing "Returns empty set when no forms match source paths"
    (let [coverage {'my.test/test-foo {12345 #{"3"}}}
          forms {12345 {:form/id 12345
                        :form/ns "nonexistent.namespace"}}
          ;; Empty source paths - no files will be found
          source-paths []]
      (is (= #{} (coverage/extract-source-deps coverage forms source-paths)))))

  (testing "Returns empty set for empty coverage"
    (let [coverage {}
          forms {}
          source-paths ["src"]]
      (is (= #{} (coverage/extract-source-deps coverage forms source-paths)))))

  (testing "Handles multiple namespaces in coverage"
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "heretic-test-multi-" (System/currentTimeMillis)))
          src-dir (io/file temp-dir "src")
          ;; Create two namespace directories
          ns1-dir (io/file src-dir "my" "app")
          ns2-dir (io/file src-dir "my" "util")
          file1 (io/file ns1-dir "core.clj")
          file2 (io/file ns2-dir "helpers.clj")]
      (try
        (.mkdirs ns1-dir)
        (.mkdirs ns2-dir)
        (spit file1 "(ns my.app.core)")
        (spit file2 "(ns my.util.helpers)")

        (let [coverage {'my.test/test-foo {11111 #{"1"}}
                        'my.test/test-bar {22222 #{"2"}}}
              forms {11111 {:form/id 11111
                            :form/ns "my.app.core"}
                     22222 {:form/id 22222
                            :form/ns "my.util.helpers"}}
              source-paths [(.getPath src-dir)]
              result (coverage/extract-source-deps coverage forms source-paths)]
          (is (= 2 (count result)) "Should find both source files")
          (is (contains? result (.getPath file1)))
          (is (contains? result (.getPath file2))))
        (finally
          (when (.exists file1) (.delete file1))
          (when (.exists file2) (.delete file2))
          (when (.exists ns1-dir) (.delete ns1-dir))
          (when (.exists ns2-dir) (.delete ns2-dir))
          (when (.exists (io/file src-dir "my" "app")) (.delete (io/file src-dir "my" "app")))
          (when (.exists (io/file src-dir "my" "util")) (.delete (io/file src-dir "my" "util")))
          (when (.exists (io/file src-dir "my")) (.delete (io/file src-dir "my")))
          (when (.exists src-dir) (.delete src-dir))
          (when (.exists temp-dir) (.delete temp-dir)))))))

;; =============================================================================
;; Uncovered Coordinates
;; =============================================================================

(deftest test-uncovered-coords-empty-index
  (testing "All coords are uncovered when index is empty"
    (let [index {:coord-to-tests {}}
          forms {12345 {:form/emitted-coords #{"1" "2" "3"}}}
          result (coverage/uncovered-coords index forms)]
      (is (= 3 (count result)))
      (is (= #{[12345 "1"] [12345 "2"] [12345 "3"]}
             (set result))))))

(deftest test-uncovered-coords-fully-covered
  (testing "No uncovered coords when all are covered"
    (let [index {:coord-to-tests {[12345 "1"] #{'test-a}
                                  [12345 "2"] #{'test-b}}}
          forms {12345 {:form/emitted-coords #{"1" "2"}}}
          result (coverage/uncovered-coords index forms)]
      (is (empty? result)))))

(deftest test-uncovered-coords-partial-coverage
  (testing "Returns only uncovered coords"
    (let [index {:coord-to-tests {[12345 "1"] #{'test-a}}}
          forms {12345 {:form/emitted-coords #{"1" "2" "3"}}}
          result (coverage/uncovered-coords index forms)]
      (is (= 2 (count result)))
      (is (= #{[12345 "2"] [12345 "3"]}
             (set result))))))

(deftest test-uncovered-coords-multiple-forms
  (testing "Finds uncovered coords across multiple forms"
    (let [index {:coord-to-tests {[12345 "1"] #{'test-a}
                                  [67890 "2"] #{'test-b}}}
          forms {12345 {:form/emitted-coords #{"1" "2"}}
                 67890 {:form/emitted-coords #{"1" "2"}}}
          result (coverage/uncovered-coords index forms)]
      ;; 12345 coord "2" is uncovered, 67890 coord "1" is uncovered
      (is (= 2 (count result)))
      (is (= #{[12345 "2"] [67890 "1"]}
             (set result))))))

(deftest test-uncovered-coords-empty-forms
  (testing "Returns empty for forms with no emitted coords"
    (let [index {:coord-to-tests {}}
          forms {12345 {:form/emitted-coords #{}}}
          result (coverage/uncovered-coords index forms)]
      (is (empty? result)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-rebuild-index-integration
  (testing "rebuild-index! persists correctly from coverage files"
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "heretic-rebuild-test-" (System/currentTimeMillis)))
          heretic-dir (.getPath temp-dir)
          coverage-dir (io/file temp-dir "coverage")]
      (try
        ;; Setup: create heretic directory structure
        (.mkdirs coverage-dir)

        ;; Create mock coverage files
        (spit (io/file coverage-dir "my-app-core-test.edn")
              (pr-str {:test-ns 'my.app.core-test
                       :coverage {'my.app.core-test/test-add {12345 #{"3" "3,1"}}
                                  'my.app.core-test/test-sub {12345 #{"4"}}}
                       :source-deps #{"src/my/app/core.clj"}}))

        (spit (io/file coverage-dir "my-app-util-test.edn")
              (pr-str {:test-ns 'my.app.util-test
                       :coverage {'my.app.util-test/test-helper {67890 #{"1" "2"}}}
                       :source-deps #{"src/my/app/util.clj"}}))

        ;; Rebuild the index
        (coverage/rebuild-index! heretic-dir)

        ;; Verify index file was created
        (let [index-file (io/file temp-dir "index.edn")]
          (is (.exists index-file) "Index file should be created"))

        ;; Load and verify the index
        (let [index (coverage/load-index heretic-dir)]
          (is (some? index) "Index should be loadable")
          (is (contains? index :coord-to-tests))
          (is (contains? index :included-test-ns))
          (is (contains? index :rebuilt-at))

          ;; Verify included namespaces
          (is (= #{'my.app.core-test 'my.app.util-test}
                 (:included-test-ns index)))

          ;; Verify coordinate mapping
          (is (= #{'my.app.core-test/test-add}
                 (get-in index [:coord-to-tests [12345 "3"]])))
          (is (= #{'my.app.core-test/test-add}
                 (get-in index [:coord-to-tests [12345 "3,1"]])))
          (is (= #{'my.app.core-test/test-sub}
                 (get-in index [:coord-to-tests [12345 "4"]])))
          (is (= #{'my.app.util-test/test-helper}
                 (get-in index [:coord-to-tests [67890 "1"]]))))

        (finally
          ;; Cleanup
          (doseq [f (.listFiles coverage-dir)]
            (.delete f))
          (.delete coverage-dir)
          (let [index-file (io/file temp-dir "index.edn")]
            (when (.exists index-file) (.delete index-file)))
          (.delete temp-dir))))))

(deftest test-index-round-trip
  (testing "Index survives round-trip through persistence"
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "heretic-roundtrip-test-" (System/currentTimeMillis)))
          heretic-dir (.getPath temp-dir)
          coverage-dir (io/file temp-dir "coverage")]
      (try
        (.mkdirs coverage-dir)

        ;; Create coverage with various data types
        (spit (io/file coverage-dir "test-ns.edn")
              (pr-str {:test-ns 'test.ns
                       :coverage {'test.ns/test-a {12345 #{"" "3" "3,0" "3,1"}}
                                  'test.ns/test-b {12345 #{"4"} 67890 #{"1,2,3"}}}}))

        ;; Rebuild
        (coverage/rebuild-index! heretic-dir)

        ;; Load
        (let [index (coverage/load-index heretic-dir)]
          ;; Verify query functions work correctly
          (is (= #{'test.ns/test-a}
                 (coverage/tests-for-location index 12345 "")))
          (is (= #{'test.ns/test-a 'test.ns/test-b}
                 (coverage/tests-for-location index 12345)))
          (is (= #{'test.ns/test-b}
                 (coverage/tests-for-location index 67890 "1,2,3"))))

        (finally
          (doseq [f (.listFiles coverage-dir)]
            (.delete f))
          (.delete coverage-dir)
          (let [index-file (io/file temp-dir "index.edn")]
            (when (.exists index-file) (.delete index-file)))
          (.delete temp-dir))))))
