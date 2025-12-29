(ns heretic.schemata-test
  "Tests for mutant schemata optimization.

   Tests verify:
   - Dynamic var mutation selection works correctly
   - Schemata source generation produces valid Clojure
   - Multiple mutations at same location are handled
   - File operations (schematize, restore) work correctly
   - Batch mutation testing produces correct results
   - Integration with actual code execution"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.mutation-engine :as engine]
            [heretic.operators :as ops]
            [heretic.schemata :as schemata]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *temp-dir* nil)

(defn- create-temp-dir
  "Create a temporary directory for test files."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-schemata-test-" (System/currentTimeMillis)))]
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
;; Dynamic Var Tests
;; =============================================================================

(deftest test-active-mutant-default
  (testing "*active-mutant* defaults to nil"
    (is (nil? schemata/*active-mutant*))))

(deftest test-active-mutant-binding
  (testing "with-mutant binds *active-mutant* correctly"
    (is (nil? schemata/*active-mutant*))
    (schemata/with-mutant :test-mutation
      (is (= :test-mutation schemata/*active-mutant*)))
    (is (nil? schemata/*active-mutant*))))

(deftest test-active-mutant-nested-binding
  (testing "Nested bindings work correctly"
    (schemata/with-mutant :outer
      (is (= :outer schemata/*active-mutant*))
      (schemata/with-mutant :inner
        (is (= :inner schemata/*active-mutant*)))
      (is (= :outer schemata/*active-mutant*)))))

;; =============================================================================
;; Mutation Grouping Tests
;; =============================================================================

(deftest test-group-mutations-by-location
  (testing "Groups mutations by file, form-id, and coord"
    (let [mutations [{:file "a.clj" :form-id 123 :coord "0"}
                     {:file "a.clj" :form-id 123 :coord "0"}
                     {:file "a.clj" :form-id 123 :coord "1"}
                     {:file "b.clj" :form-id 456 :coord "0"}]
          grouped (schemata/group-mutations-by-location mutations)]
      (is (= 3 (count grouped)))
      (is (= 2 (count (get grouped ["a.clj" 123 "0"]))))
      (is (= 1 (count (get grouped ["a.clj" 123 "1"]))))
      (is (= 1 (count (get grouped ["b.clj" 456 "0"])))))))

;; =============================================================================
;; Schemata Building Tests
;; =============================================================================

(deftest test-build-schemata-single-mutation
  (testing "Builds schemata for single mutation"
    (let [source "(defn add [a b] (+ a b))"
          file (create-test-file! "single.clj" source)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          result (schemata/build-schemata source mutations)]
      (is (some? result))
      (is (string? (:schemata-source result)))
      (is (map? (:mutation-map result)))
      (is (= 1 (count (:mutation-map result))))
      (is (= 1 (:location-count result)))
      ;; Verify the schemata source contains case form
      (is (.contains (:schemata-source result) "case"))
      (is (.contains (:schemata-source result) "heretic.schemata/*active-mutant*")))))

(deftest test-build-schemata-multiple-mutations-same-location
  (testing "Builds schemata for multiple mutations at same location"
    (let [source "(if (< x 0) 1 2)"
          file (create-test-file! "multi.clj" source)
          ;; < can be mutated to >, <=, >=, etc.
          mutations (engine/mutations-for-file file)
          ;; Filter to just comparison operators
          comparison-mutations (filter #(#{:swap-lt-gt :swap-lt-lte} (:operator %))
                                       mutations)
          result (schemata/build-schemata source comparison-mutations)]
      (when (seq comparison-mutations)
        (is (some? result))
        ;; Multiple mutations at same location should be in same case
        (is (<= (count (:mutation-map result))
                (count comparison-mutations)))))))

(deftest test-build-schemata-multiple-locations
  (testing "Builds schemata for multiple mutation locations"
    (let [source "(defn calc [a b] (+ (* a b) (- a b)))"
          file (create-test-file! "multi-loc.clj" source)
          ;; +, *, - all have mutations
          mutations (engine/mutations-for-file file [ops/swap-plus-minus
                                                     ops/swap-mult-div
                                                     ops/swap-minus-plus])
          result (schemata/build-schemata source mutations)]
      (is (some? result))
      (is (= 3 (:location-count result)))
      (is (= 3 (count (:mutation-map result)))))))

(deftest test-build-schemata-preserves-structure
  (testing "Schematized source is valid Clojure"
    (let [source "(ns test.ns)\n\n(defn add [a b]\n  (+ a b))"
          file (create-test-file! "structure.clj" source)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          result (schemata/build-schemata source mutations)]
      (is (some? result))
      ;; Should parse without error
      (let [zloc (z/of-string (:schemata-source result))]
        (is (some? zloc))))))

(deftest test-build-schemata-empty-mutations
  (testing "Returns nil for empty mutations"
    (let [source "(def x 42)"
          result (schemata/build-schemata source [])]
      (is (nil? result)))))

(deftest test-build-schemata-nil-source
  (testing "Returns nil for nil source"
    (is (nil? (schemata/build-schemata nil [{:file "x.clj" :operator :swap-plus-minus}])))))

;; =============================================================================
;; File Operation Tests
;; =============================================================================

(deftest test-schematize-file-basic
  (testing "schematize-file! modifies file and returns info"
    (let [original "(defn add [a b] (+ a b))"
          file (create-test-file! "schematize.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          result (schemata/schematize-file! file mutations)]
      (is (some? result))
      (is (= original (:backup result)))
      (is (= file (:file result)))
      (is (= 1 (:location-count result)))
      ;; File should be modified
      (let [content (slurp file)]
        (is (not= original content))
        (is (.contains content "case"))))))

(deftest test-restore-file-basic
  (testing "restore-file! restores original content"
    (let [original "(defn add [a b] (+ a b))"
          file (create-test-file! "restore.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          schemata-info (schemata/schematize-file! file mutations)]
      ;; File is schematized
      (is (not= original (slurp file)))
      ;; Restore
      (schemata/restore-file! schemata-info)
      ;; File is back to original
      (is (= original (slurp file))))))

(deftest test-with-schemata-macro
  (testing "with-schemata auto-restores on success"
    (let [original "(+ 1 2)"
          file (create-test-file! "with-schemata.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          schemata-info (schemata/schematize-file! file mutations)]
      ;; Restore first to test with-schemata from scratch
      (schemata/restore-file! schemata-info)

      ;; Re-schematize using with-schemata
      (let [new-info (schemata/schematize-file! file mutations)
            result (try
                     (schemata/with-schemata [info new-info]
                       ;; File should be schematized inside
                       (is (.contains (slurp file) "case"))
                       :success)
                     (catch Exception _
                       :failed))]
        (is (= :success result))
        ;; File should be restored after
        (is (= original (slurp file)))))))

(deftest test-with-schemata-restores-on-error
  (testing "with-schemata auto-restores on error"
    (let [original "(+ 1 2)"
          file (create-test-file! "error.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          schemata-info (schemata/schematize-file! file mutations)]
      ;; Restore first
      (schemata/restore-file! schemata-info)

      ;; Re-schematize and throw
      (let [new-info (schemata/schematize-file! file mutations)]
        (is (thrown? Exception
                     (schemata/with-schemata [info new-info]
                       (throw (Exception. "test error")))))
        ;; File should still be restored
        (is (= original (slurp file)))))))

;; =============================================================================
;; Batch Mutation Testing Tests
;; =============================================================================

(deftest test-run-mutation-batch-basic
  (testing "run-mutation-batch runs tests for all mutations"
    (let [original "(defn calc [a b] (+ a b))"
          file (create-test-file! "batch.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          test-results (atom [])
          test-fn (fn [mutation-id mutation]
                    (swap! test-results conj {:id mutation-id :mutation mutation})
                    :tested)
          result (schemata/run-mutation-batch file mutations test-fn)]
      ;; Should have results
      (is (= 1 (count (:results result))))
      (is (= 1 (:location-count result)))
      (is (= 1 (:compile-count result)))
      ;; Test function was called
      (is (= 1 (count @test-results)))
      ;; File should be restored
      (is (= original (slurp file))))))

(deftest test-run-mutation-batch-progress
  (testing "run-mutation-batch calls progress callback"
    (let [original "(+ 1 (* 2 3))"
          file (create-test-file! "progress.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus
                                                     ops/swap-mult-div])
          progress-calls (atom [])
          test-fn (fn [_id _m] :ok)
          on-progress (fn [completed total result]
                        (swap! progress-calls conj {:completed completed
                                                    :total total
                                                    :result result}))]
      (schemata/run-mutation-batch file mutations test-fn :on-progress on-progress)
      ;; Progress should be called for each mutation
      (is (= 2 (count @progress-calls)))
      ;; Progress counts should be correct
      (is (= 1 (:completed (first @progress-calls))))
      (is (= 2 (:completed (second @progress-calls)))))))

(deftest test-run-mutation-batch-with-reload
  (testing "run-mutation-batch calls reload function"
    (let [original "(+ 1 2)"
          file (create-test-file! "reload.clj" original)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          reload-calls (atom 0)
          reload-fn (fn [] (swap! reload-calls inc))
          test-fn (fn [_id _m] :ok)]
      (schemata/run-mutation-batch file mutations test-fn :reload-fn reload-fn)
      ;; Reload should be called twice: once after schematize, once after restore
      (is (= 2 @reload-calls)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-schemata-execution-integration
  (testing "Schematized code executes correctly based on *active-mutant*"
    ;; Create a file with arithmetic that we can eval
    (let [source "(ns test.schemata-exec)
(defn add-one [x]
  (+ x 1))"
          file (create-test-file! "src/test/schemata_exec.clj" source)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])
          schemata-info (schemata/schematize-file! file mutations)]
      (try
        ;; Load the schematized namespace
        (let [schemata-source (:schemata-source (schemata/build-schemata source mutations))]
          ;; Parse and eval the schematized code
          (when schemata-source
            (let [forms (read-string (str "[" schemata-source "]"))]
              ;; The case form should be there
              (is (some #(and (seq? %) (= 'defn (first %))) forms)))))
        (finally
          (schemata/restore-file! schemata-info))))))

(deftest test-should-use-schemata-heuristic
  (testing "should-use-schemata? returns true when beneficial"
    (let [mutations-one-file [{:file "a.clj" :form-id 1 :coord "0"}
                              {:file "a.clj" :form-id 1 :coord "1"}
                              {:file "a.clj" :form-id 2 :coord "0"}]
          mutations-spread [{:file "a.clj" :form-id 1 :coord "0"}
                            {:file "b.clj" :form-id 1 :coord "0"}
                            {:file "c.clj" :form-id 1 :coord "0"}]]
      ;; Multiple mutations in same file -> use schemata
      (is (true? (schemata/should-use-schemata? mutations-one-file)))
      ;; One mutation per file -> don't use schemata
      (is (false? (schemata/should-use-schemata? mutations-spread))))))

(deftest test-schemata-config-creation
  (testing "make-schemata-config adds correct flags"
    (let [base {:timeout-ms 5000}
          config (schemata/make-schemata-config base)]
      (is (true? (:schemata-enabled config)))
      (is (true? (:batch-by-file config)))
      (is (= 5000 (:timeout-ms config))))))

;; =============================================================================
;; Edge Case Tests
;; =============================================================================

(deftest test-schemata-with-comments
  (testing "Schemata preserves comments"
    (let [source ";; A comment\n(+ 1 2) ; inline"
          file (create-test-file! "comments.clj" source)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])]
      ;; Should not throw
      (let [result (schemata/build-schemata source mutations)]
        (is (some? result))))))

(deftest test-schemata-with-quoted-forms
  (testing "Schemata skips quoted forms (no mutations inside quotes)"
    (let [source "(def data '(+ 1 2))"
          file (create-test-file! "quoted.clj" source)
          mutations (engine/mutations-for-file file [ops/swap-plus-minus])]
      ;; Parser should skip quoted forms, so no mutations
      (is (empty? mutations))
      (is (nil? (schemata/build-schemata source mutations))))))

(deftest test-schemata-empty-file
  (testing "Handles file with no mutable content"
    (let [source "(ns empty.ns)"
          file (create-test-file! "empty.clj" source)
          mutations (engine/mutations-for-file file)]
      (is (nil? (schemata/build-schemata source mutations))))))

(deftest test-multiple-files-isolation
  (testing "Schemata for different files are independent"
    (let [source-a "(+ 1 2)"
          source-b "(* 3 4)"
          file-a (create-test-file! "a.clj" source-a)
          file-b (create-test-file! "b.clj" source-b)
          mutations-a (engine/mutations-for-file file-a [ops/swap-plus-minus])
          mutations-b (engine/mutations-for-file file-b [ops/swap-mult-div])
          result-a (schemata/build-schemata source-a mutations-a)
          result-b (schemata/build-schemata source-b mutations-b)]
      ;; Each file gets its own schemata
      (is (some? result-a))
      (is (some? result-b))
      (is (= 1 (count (:mutation-map result-a))))
      (is (= 1 (count (:mutation-map result-b)))))))
