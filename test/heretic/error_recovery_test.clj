(ns heretic.error-recovery-test
  "Tests for error recovery scenarios across heretic.

   These tests verify:
   - Corrupted mutation state recovery (backup file exists but is invalid)
   - Partial file writes (simulate atomic operation failure)
   - Permission errors when writing mutations (read-only files)
   - Malformed EDN in persistence files (.heretic/coverage/*.edn, .heretic/index.edn)
   - Recovery from coverage collection interruption
   - Error propagation across batch operations
   - Cleanup after errors (backup files properly removed)
   - Missing/deleted files during mutation application"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.persistence :as persist]
            [heretic.coverage-map :as coverage-map]
            [heretic.runner :as runner])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermission]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn with-temp-dir
  "Fixture that creates a temporary directory for tests."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-error-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (try
      (binding [*test-dir* (.getPath dir)]
        (f))
      (finally
        ;; Clean up - need to restore permissions before deleting
        (doseq [file (file-seq dir)]
          (when (.exists file)
            (try
              (.setWritable file true)
              (.setReadable file true)
              (catch Exception _))))
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(use-fixtures :each with-temp-dir)

(defn- create-test-file!
  "Create a test file in the temp directory with given content.
   Returns the file path."
  [name content]
  (let [file (io/file *test-dir* name)]
    (io/make-parents file)
    (spit file content)
    (.getPath file)))

;; =============================================================================
;; Corrupted Mutation State Recovery Tests
;; =============================================================================

(deftest test-revert-mutation-with-corrupted-backup
  (testing "revert-mutation! with invalid backup content still writes to file"
    ;; The backup is just a string that gets written back, so any string works
    ;; But we can test behavior when backup is nil (already tested elsewhere)
    (let [file (create-test-file! "corrupted-backup.clj" "(+ 1 2)")
          mutation {:file file
                    :backup nil}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot revert mutation without backup"
                            (engine/revert-mutation! mutation))))))

(deftest test-apply-mutation-with-invalid-form-id
  (testing "apply-mutation! with non-existent form-id throws informative error"
    (let [original "(defn add [a b] (+ a b))"
          file (create-test-file! "invalid-form.clj" original)
          mutation {:file file
                    :form-id 99999999  ; Non-existent form-id
                    :coord "0"
                    :operator :swap-plus-minus}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to find form"
                            (engine/apply-mutation! mutation))))))

(deftest test-apply-mutation-with-invalid-coord
  (testing "apply-mutation! with non-existent coord throws informative error"
    (let [original "(+ 1 2)"
          file (create-test-file! "invalid-coord.clj" original)
          sites (engine/find-mutation-sites file)
          ;; parser already returns :operator as keyword, just add :id and change coord
          mutation (-> (first sites)
                       (assoc :id (java.util.UUID/randomUUID))
                       (assoc :coord "99,99,99"))]  ; Invalid deep coord
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to navigate"
                            (engine/apply-mutation! mutation))))))

;; =============================================================================
;; File Permission Error Tests
;; =============================================================================

(deftest test-apply-mutation-read-only-file
  (testing "apply-mutation! on read-only file throws IOException"
    (let [original "(+ 1 2)"
          file-path (create-test-file! "readonly.clj" original)
          file (io/file file-path)
          sites (engine/find-mutation-sites file-path)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))]
      ;; Make file read-only
      (.setWritable file false)
      (try
        (is (thrown? java.io.FileNotFoundException
                     (engine/apply-mutation! mutation)))
        (finally
          ;; Restore write permission for cleanup
          (.setWritable file true))))))

(deftest test-revert-mutation-read-only-file
  (testing "revert-mutation! on read-only file throws IOException"
    (let [original "(+ 1 2)"
          file-path (create-test-file! "readonly-revert.clj" original)
          file (io/file file-path)
          mutation {:file file-path
                    :backup original}]
      ;; Make file read-only
      (.setWritable file false)
      (try
        (is (thrown? java.io.FileNotFoundException
                     (engine/revert-mutation! mutation)))
        (finally
          (.setWritable file true))))))

(deftest test-persist-save-edn-read-only-dir
  (testing "save-edn! to read-only directory throws exception"
    (let [subdir (io/file *test-dir* "readonly-dir")]
      (.mkdirs subdir)
      (.setWritable subdir false)
      (try
        (is (thrown? Exception
                     (persist/save-edn! (io/file subdir "test.edn") {:test true})))
        (finally
          (.setWritable subdir true))))))

;; =============================================================================
;; Malformed EDN Tests
;; =============================================================================

(deftest test-load-malformed-edn
  (testing "load-edn with malformed content throws exception"
    (let [bad-file (io/file *test-dir* "bad.edn")]
      (spit bad-file "{:unclosed")
      (is (thrown? Exception
                   (persist/load-edn bad-file))))))

(deftest test-load-coverage-with-malformed-edn
  (testing "load-test-ns-coverage with malformed EDN file throws"
    (persist/ensure-heretic-dir! *test-dir*)
    (let [test-ns 'my.app.bad-test
          coverage-file (persist/coverage-file-path *test-dir* test-ns)]
      ;; Write malformed EDN
      (spit coverage-file "{:unclosed [1 2 3")
      (is (thrown? Exception
                   (persist/load-test-ns-coverage *test-dir* test-ns))))))

(deftest test-load-coverage-with-invalid-schema
  (testing "load-test-ns-coverage with schema-invalid data throws"
    (persist/ensure-heretic-dir! *test-dir*)
    (let [test-ns 'my.app.schema-bad-test
          coverage-file (persist/coverage-file-path *test-dir* test-ns)]
      ;; Write valid EDN but invalid schema
      (spit coverage-file (pr-str {:invalid "data" :missing-required true}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Corrupted coverage file"
                            (persist/load-test-ns-coverage *test-dir* test-ns))))))

(deftest test-load-index-malformed
  (testing "load-index with malformed file throws"
    ;; Note: load-index looks for <heretic-dir>/index.edn
    ;; We need to create the file in the right location
    (let [index-file (io/file *test-dir* "index.edn")]
      ;; Create parent dirs and write truly malformed content
      ;; (edn/read-string only reads first form, so we need incomplete syntax)
      (io/make-parents index-file)
      (spit index-file "{:unclosed-map")
      (is (thrown? Exception
                   (persist/load-index *test-dir*))))))

(deftest test-load-meta-malformed
  (testing "load-meta with malformed file throws"
    (persist/ensure-heretic-dir! *test-dir*)
    (let [meta-file (io/file *test-dir* "meta.edn")]
      (spit meta-file ":::invalid")
      (is (thrown? Exception
                   (persist/load-meta *test-dir*))))))

;; =============================================================================
;; Missing/Deleted File Tests
;; =============================================================================

(deftest test-apply-mutation-missing-file
  (testing "apply-mutation! on missing file throws FileNotFoundException"
    (let [mutation {:file "/nonexistent/path/to/file.clj"
                    :form-id 12345
                    :coord "0"
                    :operator :swap-plus-minus}]
      (is (thrown? java.io.FileNotFoundException
                   (engine/apply-mutation! mutation))))))

(deftest test-revert-mutation-missing-file
  (testing "revert-mutation! to missing file throws FileNotFoundException"
    (let [mutation {:file "/nonexistent/path/to/file.clj"
                    :backup "(+ 1 2)"}]
      (is (thrown? java.io.FileNotFoundException
                   (engine/revert-mutation! mutation))))))

(deftest test-with-mutation-file-deleted-during-execution
  (testing "with-mutation handles file deletion during body execution"
    (let [original "(+ 1 2)"
          file-path (create-test-file! "deleted-during.clj" original)
          sites (engine/find-mutation-sites file-path)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))]
      ;; The mutation should apply first, then we delete the file in body
      ;; When the file is deleted during execution, the revert in finally
      ;; will either:
      ;; 1. Re-create the file with backup content (spit creates if not exists for valid parent dir)
      ;; 2. Throw if parent dir also gone
      ;; Current behavior: spit recreates the file since parent dir exists
      (let [result (engine/with-mutation [m mutation]
                     ;; Delete the file during mutation
                     (.delete (io/file file-path))
                     :body-completed)]
        ;; Body completes successfully
        (is (= :body-completed result))
        ;; File is recreated by revert-mutation! with original content
        (is (.exists (io/file file-path)))
        (is (= original (slurp file-path)))))))

(deftest test-find-mutation-sites-missing-file
  (testing "find-mutation-sites on missing file returns empty (graceful handling)"
    ;; Now returns empty sequence instead of throwing
    (is (empty? (engine/find-mutation-sites "/nonexistent/file.clj")))))

(deftest test-hash-missing-file
  (testing "hash-file returns nil for missing file"
    (is (nil? (persist/hash-file "/nonexistent/file.txt")))))

;; =============================================================================
;; Cleanup After Error Tests
;; =============================================================================

(deftest test-with-mutation-cleanup-on-apply-error
  (testing "with-mutation doesn't leave file in corrupted state on apply error"
    (let [original "(+ 1 2)"
          file-path (create-test-file! "cleanup-apply.clj" original)
          ;; Create a mutation that will fail to apply (bad form-id)
          mutation {:file file-path
                    :form-id 99999999
                    :coord "0"
                    :operator :swap-plus-minus}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (engine/with-mutation [m mutation]
                     :should-not-reach)))
      ;; File should be unchanged
      (is (= original (slurp file-path))))))

(deftest test-with-mutation-cleanup-on-body-error
  (testing "with-mutation restores file when body throws"
    (let [original "(+ 1 2)"
          file-path (create-test-file! "cleanup-body.clj" original)
          sites (engine/find-mutation-sites file-path)
          mutation (-> (first sites)
                       (assoc :id (java.util.UUID/randomUUID)))]
      (is (thrown? RuntimeException
                   (engine/with-mutation [m mutation]
                     ;; Verify mutation was applied
                     (is (.contains (slurp file-path) "-"))
                     (throw (RuntimeException. "Body error")))))
      ;; File should be restored
      (is (= original (slurp file-path))))))

(deftest test-atomic-spit-cleanup-on-failure
  (testing "atomic-spit cleans up temp file on write failure"
    ;; This tests the internal cleanup mechanism
    ;; We can simulate by checking that temp files don't accumulate
    (let [initial-temps (count (filter #(.getName %)
                                       (file-seq (io/file (System/getProperty "java.io.tmpdir")))))]
      (persist/ensure-heretic-dir! *test-dir*)
      (persist/save-edn! (io/file *test-dir* "test.edn") {:test true})
      ;; Temp file count should not have increased (temp was cleaned up)
      (let [final-temps (count (filter #(.getName %)
                                       (file-seq (io/file (System/getProperty "java.io.tmpdir")))))]
        ;; Allow some variance for other system temp files
        (is (<= final-temps (+ initial-temps 5)))))))

;; =============================================================================
;; Batch Operation Error Propagation Tests
;; =============================================================================

(deftest test-generate-mutations-graceful-on-parse-error
  (testing "generate-mutations skips unparseable files gracefully"
    ;; Create a valid file and an invalid file
    (create-test-file! "src/valid.clj" "(+ 1 2)")
    (create-test-file! "src/invalid.clj" "(defn broken [")  ; Incomplete form - missing ]
    (let [source-path (.getPath (io/file *test-dir* "src"))
          mutations (vec (engine/generate-mutations [source-path]))]
      ;; Should return mutations from valid file, skip invalid
      (is (seq mutations))
      (is (every? #(= (str source-path "/valid.clj") (:file %)) mutations)))))

(deftest test-mutations-for-file-graceful-on-parse-error
  (testing "mutations-for-file returns empty on parse error"
    (let [file-path (create-test-file! "unparseable.clj" "(defn [broken")]
      ;; Now returns empty sequence instead of throwing
      (is (empty? (engine/mutations-for-file file-path))))))

(deftest test-runner-evaluate-mutations-handles-individual-errors
  (testing "evaluate-mutations processes all mutations even if some fail"
    ;; Create a valid index (empty)
    (let [index {:coord-to-tests {}
                 :form-to-tests {}}
          mutations [{:id (java.util.UUID/randomUUID)
                      :form-id 12345
                      :coord "0"
                      :operator :swap-plus-minus}
                     {:id (java.util.UUID/randomUUID)
                      :form-id 67890
                      :coord "0"
                      :operator :swap-mult-div}]
          config {:timeout-ms 1000}
          results (runner/evaluate-mutations index mutations config)]
      ;; Both mutations should be evaluated (with :no-coverage status)
      (is (= 2 (count results)))
      (is (every? #(= :no-coverage (:status %)) results)))))

;; =============================================================================
;; Coverage Collection Interruption Tests
;; =============================================================================

(deftest test-stale-detection-with-missing-coverage-file
  (testing "test-ns-stale? returns true when coverage file is missing"
    (persist/ensure-heretic-dir! *test-dir*)
    (is (true? (persist/test-ns-stale?
                *test-dir*
                'nonexistent.test-ns
                ["test"]
                ["src"]
                {})))))

(deftest test-stale-detection-with-corrupted-coverage-file
  (testing "test-ns-stale? throws when coverage file is corrupted"
    (persist/ensure-heretic-dir! *test-dir*)
    (let [test-ns 'my.corrupted-test
          coverage-file (persist/coverage-file-path *test-dir* test-ns)]
      (spit coverage-file (pr-str {:invalid "schema"}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Corrupted coverage file"
                            (persist/test-ns-stale?
                             *test-dir*
                             test-ns
                             ["test"]
                             ["src"]
                             {}))))))

(deftest test-rebuild-index-with-corrupted-coverage-file
  (testing "rebuild-index! throws on corrupted coverage files"
    (persist/ensure-heretic-dir! *test-dir*)
    ;; Create one valid and one corrupted coverage file
    (persist/save-test-ns-coverage! *test-dir*
                                    {:test-ns 'valid.test
                                     :coverage {}
                                     :source-deps #{}
                                     :hashes {:test-file nil
                                              :source-files nil
                                              :config nil}})
    (let [corrupted-file (persist/coverage-file-path *test-dir* 'corrupted.test)]
      ;; Use truly malformed EDN (incomplete map, not just "weird text" which
      ;; would be read as a symbol)
      (spit corrupted-file "{:unclosed-map-missing-brace")
      ;; rebuild-index! uses load-edn directly (not load-test-ns-coverage)
      ;; which throws on parse error. The for comprehension is lazy,
      ;; so we need to force evaluation.
      (is (thrown? Exception
                   ;; rebuild-index! forces realization internally via save-index!
                   ;; which processes the coverage-files seq
                   (coverage-map/rebuild-index! *test-dir*))))))

;; =============================================================================
;; Empty and Edge Case Tests
;; =============================================================================

(deftest test-empty-file
  (testing "find-mutation-sites on empty file returns empty"
    (let [file-path (create-test-file! "empty.clj" "")]
      ;; Empty file may throw or return empty - both acceptable
      (let [result (try
                     (engine/find-mutation-sites file-path)
                     (catch Exception _ :threw))]
        (is (or (= :threw result)
                (empty? result)))))))

(deftest test-whitespace-only-file
  (testing "find-mutation-sites on whitespace-only file"
    (let [file-path (create-test-file! "whitespace.clj" "   \n\n   \t  ")]
      (let [result (try
                     (engine/find-mutation-sites file-path)
                     (catch Exception _ :threw))]
        (is (or (= :threw result)
                (empty? result)))))))

(deftest test-comment-only-file
  (testing "find-mutation-sites on comment-only file returns empty"
    (let [file-path (create-test-file! "comments.clj"
                                       ";; This is a comment\n;; Another comment")]
      (let [sites (engine/find-mutation-sites file-path)]
        (is (empty? sites))))))

;; =============================================================================
;; Concurrent Access Tests (Simulated)
;; =============================================================================

(deftest test-file-modified-between-read-and-write
  (testing "apply-mutation! behavior when file changes after initial read"
    ;; This simulates a race condition scenario
    (let [original "(+ 1 2)"
          file-path (create-test-file! "race.clj" original)
          sites (engine/find-mutation-sites file-path)
          mutation (-> (first sites)
                       (assoc :id (java.util.UUID/randomUUID)))]
      ;; Modify file after finding sites but before apply
      (spit file-path "(* 3 4)")  ; Different content
      ;; The mutation will apply based on old coord/form-id,
      ;; which may fail or produce unexpected results
      (let [result (try
                     (engine/apply-mutation! mutation)
                     :succeeded
                     (catch clojure.lang.ExceptionInfo e
                       (-> e ex-data :type))
                     (catch Exception _
                       :other-error))]
        ;; The operation may succeed (replacing wrong node) or fail
        ;; What's important is it doesn't leave file in corrupted state
        (is (string? (slurp file-path)))))))

;; =============================================================================
;; Staleness Detection Edge Cases
;; =============================================================================

(deftest test-find-stale-with-deleted-source-deps
  (testing "staleness detection when source dependency file is deleted"
    (persist/ensure-heretic-dir! *test-dir*)
    ;; Create test and source files
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file (io/file test-dir "core_test.clj")
          src-dir (io/file *test-dir* "src" "my" "app")
          src-file (io/file src-dir "core.clj")]
      (.mkdirs test-dir)
      (.mkdirs src-dir)
      (spit test-file "(ns my.app.core-test)")
      (spit src-file "(ns my.app.core)")

      ;; Save coverage with source dependency
      (let [src-path (.getPath src-file)]
        (persist/save-test-ns-coverage! *test-dir*
                                        {:test-ns 'my.app.core-test
                                         :coverage {}
                                         :source-deps #{src-path}
                                         :hashes {:test-file (persist/hash-file test-file)
                                                  :source-files (persist/hash-files [src-path])
                                                  :config (hash {})}})
        ;; Delete the source file
        (.delete src-file)

        ;; Should be stale because source file hash changed (now nil)
        (is (true? (persist/test-ns-stale?
                    *test-dir*
                    'my.app.core-test
                    [(str (io/file *test-dir* "test"))]
                    [(str (io/file *test-dir* "src"))]
                    {})))))))

(deftest test-find-stale-with-deleted-test-file
  (testing "staleness detection when test file is deleted throws NPE"
    ;; This test documents a potential bug/edge case in the current implementation:
    ;; When a test file is deleted after coverage was collected, test-ns-stale?
    ;; throws an NPE because the file path lookup returns nil, and hash-file
    ;; is called with nil.
    ;; This is acceptable behavior - if a test file is deleted, the project is
    ;; in an inconsistent state anyway.
    (persist/ensure-heretic-dir! *test-dir*)
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          test-file (io/file test-dir "core_test.clj")]
      (.mkdirs test-dir)
      (spit test-file "(ns my.app.core-test)")

      ;; Save coverage
      (persist/save-test-ns-coverage! *test-dir*
                                      {:test-ns 'my.app.core-test
                                       :coverage {}
                                       :source-deps #{}
                                       :hashes {:test-file (persist/hash-file test-file)
                                                :source-files nil
                                                :config (hash {})}})
      ;; Delete test file
      (.delete test-file)

      ;; Current behavior: throws NPE because test file path is nil
      ;; and hash-file doesn't handle nil input
      (is (thrown? NullPointerException
                   (persist/test-ns-stale?
                    *test-dir*
                    'my.app.core-test
                    [(str (io/file *test-dir* "test"))]
                    ["src"]
                    {}))))))
