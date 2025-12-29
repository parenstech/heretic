(ns heretic.mutation-engine-test
  "Tests for mutation engine.

   Tests verify:
   - Mutation site discovery finds all applicable operators
   - apply-mutation! correctly modifies files and stores backup
   - revert-mutation! restores original content
   - with-mutation macro handles cleanup on error
   - generate-mutations scans source directories"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *temp-dir* nil)

(defn- create-temp-dir
  "Create a temporary directory for test files."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-test-" (System/currentTimeMillis)))]
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
;; Mutation Site Discovery Tests
;; =============================================================================

(deftest test-find-mutation-sites-arithmetic
  (testing "Finds arithmetic operator mutation sites"
    (let [content "(defn add [a b] (+ a b))"
          file (create-test-file! "add.clj" content)
          sites (engine/find-mutation-sites file)]
      (is (= 1 (count sites))
          "Should find exactly one mutation site for single + operator")
      (let [site (first sites)]
        (is (= file (:file site))
            "Mutation site should reference source file")
        (is (= :swap-plus-minus (:operator site))
            "Operator should be swap-plus-minus for + symbol")
        (is (= "+" (:original site))
            "Original value should be + symbol as string")
        (is (number? (:line site))
            "Line number should be present")
        (is (number? (:column site))
            "Column number should be present")))))

(deftest test-find-mutation-sites-multiple
  (testing "Finds multiple mutation sites in a file"
    (let [content "(defn calc [a b] (+ (* a b) (- a b)))"
          file (create-test-file! "calc.clj" content)
          sites (engine/find-mutation-sites file)]
      ;; Should find: +, *, -
      (is (= 3 (count sites)))
      (let [op-ids (set (map :operator sites))]
        (is (contains? op-ids :swap-plus-minus))
        (is (contains? op-ids :swap-mult-div))
        (is (contains? op-ids :swap-minus-plus))))))

(deftest test-find-mutation-sites-boolean
  (testing "Finds boolean mutation sites"
    (let [content "(def flag true)"
          file (create-test-file! "flag.clj" content)
          sites (engine/find-mutation-sites file)]
      (is (= 1 (count sites)))
      (is (= :swap-true-false (:operator (first sites)))))))

(deftest test-find-mutation-sites-logical
  (testing "Finds logical operator mutation sites"
    (let [content "(if (and x y) 1 2)"
          file (create-test-file! "logic.clj" content)
          sites (engine/find-mutation-sites file)]
      ;; Finds: and, 1 (3 variants), 2 (3 variants) = 5+ sites
      (is (>= (count sites) 1))
      (is (some #(= :swap-and-or (:operator %)) sites)))))

(deftest test-find-mutation-sites-nested
  (testing "Finds mutation sites in nested forms"
    (let [content "(defn nested []
                     (if true
                       (and (> x 0) (or a b))
                       false))"
          file (create-test-file! "nested.clj" content)
          sites (engine/find-mutation-sites file)]
      ;; Should find: true, and, > (2 variants), 0 (3 variants), or, false = 8+ total
      (is (>= (count sites) 6))
      (let [op-ids (set (map :operator sites))]
        (is (contains? op-ids :swap-true-false))
        (is (contains? op-ids :swap-and-or))
        (is (contains? op-ids :swap-gt-lt))
        (is (contains? op-ids :swap-gt-gte))
        (is (contains? op-ids :swap-or-and))
        (is (contains? op-ids :swap-false-true))))))

(deftest test-find-mutation-sites-empty
  (testing "Returns empty for file with no mutation sites"
    (let [content "(def x 42)"
          file (create-test-file! "empty.clj" content)
          sites (engine/find-mutation-sites file)]
      (is (empty? sites)))))

;; =============================================================================
;; Apply Mutation Tests
;; =============================================================================

(deftest test-apply-mutation-basic
  (testing "Applies mutation and stores backup"
    (let [original "(defn add [a b] (+ a b))"
          file (create-test-file! "apply.clj" original)
          sites (engine/find-mutation-sites file)
          ;; parser already returns :operator as keyword, just add :id
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))
          result (engine/apply-mutation! mutation)]
      ;; Check backup is stored
      (is (= original (:backup result))
          "Backup should contain original file content for revert")
      ;; Check file was modified
      (let [modified (slurp file)]
        (is (not= original modified)
            "File content should be different after mutation")
        (is (.contains modified "-")
            "Mutated file should contain - (replacement for +)")
        (is (not (.contains modified "(+ a b)"))
            "Mutated file should not contain original (+ a b)")))))

(deftest test-apply-mutation-preserves-structure
  (testing "Mutation preserves file structure"
    (let [original "(ns my.ns)\n\n(defn add [a b]\n  (+ a b))"
          file (create-test-file! "structure.clj" original)
          sites (engine/find-mutation-sites file)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))
          _ (engine/apply-mutation! mutation)
          modified (slurp file)]
      ;; Should still have ns declaration and newlines
      (is (.contains modified "(ns my.ns)"))
      (is (.contains modified "(defn add")))))

(deftest test-apply-mutation-unknown-operator
  (testing "Throws for unknown operator"
    (let [content "(+ 1 2)"
          file (create-test-file! "unknown.clj" content)
          mutation {:file file
                    :coord "0"
                    :operator :nonexistent-operator}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown operator"
                            (engine/apply-mutation! mutation))))))

;; =============================================================================
;; Revert Mutation Tests
;; =============================================================================

(deftest test-revert-mutation-basic
  (testing "Reverts mutation to original content"
    (let [original "(defn add [a b] (+ a b))"
          file (create-test-file! "revert.clj" original)
          sites (engine/find-mutation-sites file)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))
          applied (engine/apply-mutation! mutation)]
      ;; Verify mutation was applied
      (is (not= original (slurp file)))
      ;; Revert
      (engine/revert-mutation! applied)
      ;; Verify original restored
      (is (= original (slurp file))))))

(deftest test-revert-mutation-removes-backup
  (testing "Revert returns mutation without backup"
    (let [original "(+ 1 2)"
          file (create-test-file! "backup.clj" original)
          sites (engine/find-mutation-sites file)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))
          applied (engine/apply-mutation! mutation)
          reverted (engine/revert-mutation! applied)]
      (is (contains? applied :backup))
      (is (not (contains? reverted :backup))))))

(deftest test-revert-mutation-no-backup
  (testing "Throws when reverting without backup"
    (let [mutation {:file "some/file.clj"
                    :coord "0"
                    :operator :swap-plus-minus}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot revert mutation without backup"
                            (engine/revert-mutation! mutation))))))

;; =============================================================================
;; with-mutation Macro Tests
;; =============================================================================

(deftest test-with-mutation-auto-revert
  (testing "with-mutation automatically reverts on success"
    (let [original "(+ 1 2)"
          file (create-test-file! "with.clj" original)
          sites (engine/find-mutation-sites file)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))
          result (engine/with-mutation [m mutation]
                   ;; File should be mutated here
                   (let [content (slurp file)]
                     (is (.contains content "-"))
                     :success))]
      (is (= :success result))
      ;; File should be reverted
      (is (= original (slurp file))))))

(deftest test-with-mutation-revert-on-error
  (testing "with-mutation reverts on error"
    (let [original "(+ 1 2)"
          file (create-test-file! "error.clj" original)
          sites (engine/find-mutation-sites file)
          mutation (assoc (first sites) :id (java.util.UUID/randomUUID))]
      (is (thrown? RuntimeException
                   (engine/with-mutation [m mutation]
                     ;; File should be mutated
                     (is (.contains (slurp file) "-"))
                     (throw (RuntimeException. "Test error")))))
      ;; File should still be reverted
      (is (= original (slurp file))))))

;; =============================================================================
;; Generate Mutations Tests
;; =============================================================================

(deftest test-generate-mutations-single-file
  (testing "Generates mutations from a directory"
    (let [content "(defn add [a b] (+ a b))"
          _ (create-test-file! "src/my/app.clj" content)
          source-path (.getPath (io/file *temp-dir* "src"))
          mutations (engine/generate-mutations [source-path])]
      (is (= 1 (count mutations)))
      (let [m (first mutations)]
        (is (some? (:id m)))
        (is (instance? java.util.UUID (:id m)))
        (is (= :swap-plus-minus (:operator m)))
        (is (= "+" (:original m)))))))

(deftest test-generate-mutations-multiple-files
  (testing "Generates mutations from multiple files"
    (let [_ (create-test-file! "src/a.clj" "(+ 1 2)")
          _ (create-test-file! "src/b.clj" "(* 3 4)")
          source-path (.getPath (io/file *temp-dir* "src"))
          mutations (engine/generate-mutations [source-path])]
      ;; Now finds constant operators too: 1, 2, 3, 4 each have multiple mutations
      (is (>= (count mutations) 2))
      (let [op-ids (set (map :operator mutations))]
        (is (contains? op-ids :swap-plus-minus))
        (is (contains? op-ids :swap-mult-div))))))

(deftest test-generate-mutations-filter-operators
  (testing "Filters by specified operators"
    (let [_ (create-test-file! "src/mixed.clj" "(+ 1 (* 2 3))")
          source-path (.getPath (io/file *temp-dir* "src"))
          mutations (engine/generate-mutations [source-path] [ops/swap-plus-minus])]
      (is (= 1 (count mutations)))
      (is (= :swap-plus-minus (:operator (first mutations)))))))

(deftest test-generate-mutations-empty-dir
  (testing "Handles empty directory"
    (let [empty-dir (io/file *temp-dir* "empty")]
      (.mkdirs empty-dir)
      (let [mutations (engine/generate-mutations [(.getPath empty-dir)])]
        (is (empty? mutations))))))

(deftest test-generate-mutations-nonexistent-dir
  (testing "Handles nonexistent directory"
    (let [mutations (engine/generate-mutations ["/nonexistent/path"])]
      (is (empty? mutations)))))

;; =============================================================================
;; mutations-for-file Tests
;; =============================================================================

(deftest test-mutations-for-file
  (testing "Generates mutations for a single file"
    (let [content "(defn math [x] (+ x (* x x)))"
          file (create-test-file! "single.clj" content)
          mutations (engine/mutations-for-file file)]
      (is (= 2 (count mutations)))
      (is (every? #(instance? java.util.UUID (:id %)) mutations))
      (is (every? #(= file (:file %)) mutations)))))

;; =============================================================================
;; count-mutations Tests
;; =============================================================================

(deftest test-count-mutations
  (testing "Counts mutations without generating all"
    (let [_ (create-test-file! "src/count.clj" "(+ 1 (* 2 (- 3 4)))")
          source-path (.getPath (io/file *temp-dir* "src"))
          count (engine/count-mutations [source-path])]
      ;; Now finds constant operators too: 1, 2, 3, 4 each have multiple mutations
      (is (>= count 3)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-full-mutation-workflow
  (testing "Complete mutation workflow: generate, apply, revert"
    (let [original "(defn foo [x y]
                      (if (and x y)
                        (+ x y)
                        (* x y)))"
          file (create-test-file! "workflow.clj" original)
          mutations (engine/mutations-for-file file)]
      ;; Should have at least mutations for: and, +, *
      ;; With RORG operators, there are more (replace-and-false, etc.)
      (is (>= (count mutations) 3))

      ;; Apply and revert each mutation
      (doseq [mutation mutations]
        (engine/with-mutation [m mutation]
          ;; Verify mutation was applied
          (let [content (slurp file)]
            (is (not= original content)))))

      ;; File should be back to original after all mutations
      (is (= original (slurp file))))))

(deftest test-mutation-location-info
  (testing "Mutations include correct location info"
    (let [content "(defn add [a b]\n  (+ a b))"
          file (create-test-file! "location.clj" content)
          mutations (engine/mutations-for-file file)
          m (first mutations)]
      (is (some? (:line m)))
      (is (some? (:column m)))
      (is (some? (:coord m)))
      (is (some? (:form-id m)))
      (is (string? (:coord m))))))

;; =============================================================================
;; Mutation Survivor Tests
;; =============================================================================
;; These tests are specifically designed to kill mutation survivors.

(deftest test-generate-mutations-excludes-non-clj-files
  (testing "Line 89: and -> or mutation killer - must be both a file AND end with .clj"
    ;; Create a regular file without .clj extension
    (let [_ (create-test-file! "src/readme.txt" "(+ 1 2)")
          source-path (.getPath (io/file *temp-dir* "src"))
          mutations (engine/generate-mutations [source-path])]
      ;; If and->or mutation, .txt file would be included (it IS a file)
      (is (empty? mutations)
          "Non-.clj files must not generate mutations"))))

(deftest test-generate-mutations-excludes-directories-ending-in-clj
  (testing "Line 89: and -> or mutation killer - must be a file, not directory"
    ;; Create a directory that ends in .clj (weird but valid)
    (let [dir-path (io/file *temp-dir* "src" "weird.clj")
          _ (.mkdirs dir-path)
          source-path (.getPath (io/file *temp-dir* "src"))
          mutations (engine/generate-mutations [source-path])]
      ;; If and->or mutation, directory would be included (it ends with .clj)
      (is (empty? mutations)
          "Directories ending in .clj must not generate mutations"))))

(deftest test-find-form-by-id-exact-match
  (testing "Lines 110, 114: = vs <= mutation killer - form-id must match exactly"
    ;; Create a file with multiple top-level forms
    (let [content "(def a 1)\n(def b (+ 2 3))\n(def c 4)"
          file (create-test-file! "multi-form.clj" content)
          mutations (engine/mutations-for-file file)]
      ;; Find the mutation for the + operator
      (let [plus-mutation (first (filter #(= :swap-plus-minus (:operator %)) mutations))]
        (is (some? plus-mutation)
            "Should find + mutation")
        ;; Apply the mutation - it should modify only the second form
        (let [applied (engine/apply-mutation! plus-mutation)
              modified (slurp file)]
          (try
            ;; The mutation should have replaced + with - in the second form only
            (is (.contains modified "(def b (- 2 3))")
                "Mutation should modify the exact form, not first form with <= hash")
            ;; First and third forms should be unchanged
            (is (.contains modified "(def a 1)")
                "First form should be unchanged")
            (is (.contains modified "(def c 4)")
                "Third form should be unchanged")
            (finally
              (engine/revert-mutation! applied))))))))

(deftest test-find-form-by-id-single-form-exact-match
  (testing "Line 114: = vs <= mutation killer - single form must match exactly"
    ;; Create a file with a single form
    (let [content "(+ 1 2)"
          file (create-test-file! "single-form.clj" content)
          mutations (engine/mutations-for-file file)
          mutation (first (filter #(= :swap-plus-minus (:operator %)) mutations))]
      (is (some? mutation)
          "Should find + mutation")
      ;; Verify the mutation can be applied successfully (exact match works)
      (let [applied (engine/apply-mutation! mutation)
            modified (slurp file)]
        (try
          (is (= "(- 1 2)" modified)
              "Single form mutation should work with exact form-id match")
          (finally
            (engine/revert-mutation! applied)))))))
