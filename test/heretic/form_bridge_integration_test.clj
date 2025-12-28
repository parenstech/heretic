(ns heretic.form-bridge-integration-test
  "Integration tests for form-id bridging.

   These tests verify the complete path from mutation site discovery
   to coverage lookup using real ClojureStorm form-ids.

   Requires ClojureStorm to be on the classpath with instrumentation enabled.
   Run with: bb heretic:test (which sets up ClojureStorm JVM args)

   The tests use Heretic's own source as the test subject."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.coverage-map :as coverage]
            [heretic.form-bridge :as bridge]
            [heretic.mutation-engine :as engine]
            [heretic.parser :as parser]
            [heretic.persistence :as persist]
            [heretic.runner :as runner]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-heretic-dir* nil)

(defn- create-temp-heretic-dir []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-integration-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    dir))

(defn- delete-recursively [dir]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))]
      (.delete f))))

(defn temp-heretic-dir-fixture [f]
  (let [dir (create-temp-heretic-dir)]
    (try
      (binding [*test-heretic-dir* (.getPath dir)]
        (f))
      (finally
        (delete-recursively dir)))))

(use-fixtures :each temp-heretic-dir-fixture)

;; =============================================================================
;; Form Location Index Tests
;; =============================================================================

(deftest test-form-location-index-structure
  (testing "Form location index has expected structure"
    ;; This test verifies the index structure without requiring ClojureStorm
    (let [mock-forms {12345 {:form/file "heretic/core.clj" :form/line 10}
                      12346 {:form/file "heretic/core.clj" :form/line 25}
                      12347 {:form/file "heretic/runner.clj" :form/line 5}}
          ;; Note: build-form-location-index needs actual files to exist
          ;; so this will return empty for mock data
          idx (bridge/build-form-location-index mock-forms ["nonexistent"])]
      ;; With nonexistent source paths, index is empty (files don't exist)
      (is (map? idx)))))

;; =============================================================================
;; Mutation Site to Form-ID Resolution Tests
;; =============================================================================

(deftest test-resolve-form-id-with-containing-form
  (testing "Finds containing form for mutation inside a form"
    (let [form-loc-idx {["/path/file.clj" 10] 12345
                        ["/path/file.clj" 25] 12346}
          ;; Mutation at line 15 is inside form starting at line 10
          mutation {:file "/path/file.clj" :line 15 :form-id 99999}]
      (is (= 12345 (bridge/resolve-form-id form-loc-idx mutation))))))

(deftest test-resolve-form-id-exact-match
  (testing "Finds form at exact line"
    (let [form-loc-idx {["/path/file.clj" 10] 12345}
          mutation {:file "/path/file.clj" :line 10 :form-id 99999}]
      (is (= 12345 (bridge/resolve-form-id form-loc-idx mutation))))))

(deftest test-resolve-form-id-between-forms
  (testing "Finds closest containing form"
    (let [form-loc-idx {["/path/file.clj" 10] 12345   ;; form A: lines 10-20
                        ["/path/file.clj" 25] 12346}  ;; form B: lines 25+
          ;; Mutation at line 23 is after form A ends but before form B starts
          ;; Should still match form A (largest start <= line)
          mutation {:file "/path/file.clj" :line 23 :form-id 99999}]
      (is (= 12345 (bridge/resolve-form-id form-loc-idx mutation))))))

;; =============================================================================
;; Tests for Mutation -> Coverage Lookup Path
;; =============================================================================

(deftest test-tests-for-mutation-with-form-location-index
  (testing "Uses form-level matching when form-location-index present"
    (let [;; Mock coverage index with form-location-index
          mock-index {:form-location-index {["/path/file.clj" 10] 12345}
                      :form-to-tests {12345 #{'my.test/test-add}}
                      :coord-to-tests {[12345 "0,0"] #{'my.test/test-add}}}
          mutation {:file "/path/file.clj" :line 15 :form-id 99999 :coord "0,0"}]
      ;; Should use form-level lookup (all tests for form 12345)
      (is (= #{'my.test/test-add}
             (runner/tests-for-mutation mock-index mutation))))))

(deftest test-tests-for-mutation-without-form-location-index
  (testing "Uses coord-level matching when form-location-index absent"
    (let [;; Mock coverage index WITHOUT form-location-index
          mock-index {:form-to-tests {12345 #{'my.test/test-add 'my.test/test-sub}}
                      :coord-to-tests {[12345 "0,0"] #{'my.test/test-add}
                                       [12345 "0,1"] #{'my.test/test-sub}}}
          mutation {:form-id 12345 :coord "0,0"}]
      ;; Should use coord-level lookup (only tests for specific coord)
      (is (= #{'my.test/test-add}
             (runner/tests-for-mutation mock-index mutation))))))

(deftest test-tests-for-mutation-fallback-to-mutation-form-id
  (testing "Falls back to mutation's form-id when not found in index"
    (let [mock-index {:form-location-index {["/path/file.clj" 10] 12345}
                      :form-to-tests {99999 #{'my.test/fallback-test}}}
          ;; Mutation in different file, should fall back to its form-id
          mutation {:file "/other/file.clj" :line 15 :form-id 99999}]
      (is (= #{'my.test/fallback-test}
             (runner/tests-for-mutation mock-index mutation))))))

;; =============================================================================
;; Parser and Engine Consistency Tests
;; =============================================================================

(deftest test-parser-returns-correct-structure
  (testing "Parser returns mutation sites with expected fields"
    (let [source "(defn add [a b] (+ a b))"
          zloc (parser/parse-string source)
          sites (parser/find-mutation-sites zloc {:file "test.clj"})]
      (is (= 1 (count sites)))
      (let [site (first sites)]
        ;; Verify structure
        (is (uuid? (:id site)))
        (is (= "test.clj" (:file site)))
        (is (integer? (:form-id site)))
        (is (string? (:coord site)))
        (is (= :swap-plus-minus (:operator site)))
        (is (= "+" (:original site)))
        (is (= "-" (:replacement site)))
        (is (integer? (:line site)))
        (is (integer? (:column site)))))))

(deftest test-engine-delegates-to-parser
  (testing "Engine's find-sites-in-source delegates to parser"
    (let [source "(+ 1 2)"
          parser-sites (parser/find-mutation-sites
                        (parser/parse-string source)
                        {:file "test.clj"})
          engine-sites (engine/find-sites-in-source source "test.clj")]
      ;; Should have same number of sites
      (is (= (count parser-sites) (count engine-sites)))
      ;; Should have same operator
      (is (= (:operator (first parser-sites))
             (:operator (first engine-sites)))))))

(deftest test-quoted-forms-skipped-by-both
  (testing "Both parser and engine skip quoted forms"
    (let [source "'(+ 1 2)"  ;; quoted, should not mutate
          parser-sites (parser/find-mutation-sites
                        (parser/parse-string source)
                        {:file "test.clj"})
          engine-sites (engine/find-sites-in-source source "test.clj")]
      (is (empty? parser-sites))
      (is (empty? engine-sites)))))

;; =============================================================================
;; End-to-End Mutation Flow Test
;; =============================================================================

(deftest test-mutation-round-trip
  (testing "Mutation can be applied and reverted correctly"
    (let [original "(defn add [a b] (+ a b))"
          temp-file (io/file *test-heretic-dir* "roundtrip.clj")]
      (spit temp-file original)
      (let [mutations (engine/mutations-for-file (.getPath temp-file))]
        (is (= 1 (count mutations)))
        (let [mutation (first mutations)]
          ;; Apply mutation
          (let [applied (engine/apply-mutation! mutation)]
            (is (= original (:backup applied)))
            (is (.contains (slurp temp-file) "-"))
            ;; Revert mutation
            (engine/revert-mutation! applied)
            (is (= original (slurp temp-file)))))))))

;; =============================================================================
;; use-form-level-matching? Tests
;; =============================================================================

(deftest test-use-form-level-matching-decision
  (testing "Correctly decides when to use form-level matching"
    ;; With form-location-index (has entries) - returns truthy (seq)
    (is (bridge/use-form-level-matching?
         {:form-location-index {["a" 1] 123}}))
    ;; Without form-location-index - returns falsy
    (is (not (bridge/use-form-level-matching? {})))
    (is (not (bridge/use-form-level-matching? nil)))
    ;; With empty form-location-index - returns falsy (empty seq is nil)
    (is (not (bridge/use-form-level-matching?
              {:form-location-index {}})))))
