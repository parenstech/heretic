(ns heretic.form-bridge-test
  "Tests for form-id bridging between mutation sites and ClojureStorm coverage.

   Tests verify:
   - Building form-location-index from forms data
   - Finding containing forms by file and line
   - Resolving mutation form-ids to ClojureStorm form-ids
   - Form-level vs coord-level matching decisions"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.form-bridge :as bridge]))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-forms
  "Sample forms data mimicking ClojureStorm FormRegistry output."
  {12345 {:form/file "heretic/core.clj"
          :form/line 10
          :form/ns "heretic.core"}
   12346 {:form/file "heretic/core.clj"
          :form/line 25
          :form/ns "heretic.core"}
   12347 {:form/file "heretic/runner.clj"
          :form/line 5
          :form/ns "heretic.runner"}
   ;; Form without file/line (shouldn't be indexed)
   12348 {:form/ns "heretic.other"}})

;; =============================================================================
;; build-form-location-index Tests
;; =============================================================================

(deftest test-build-form-location-index-empty
  (testing "Returns empty map for empty forms"
    (is (= {} (bridge/build-form-location-index {} ["src"])))))

(deftest test-build-form-location-index-filters-incomplete
  (testing "Filters out forms without file or line"
    (let [forms {1 {:form/file "a.clj" :form/line 10}
                 2 {:form/file "b.clj"}  ;; no line
                 3 {:form/line 5}         ;; no file
                 4 {}}]                   ;; neither
      ;; Only form 1 should be indexed (if file exists)
      ;; Since file doesn't exist, result is empty
      (is (= {} (bridge/build-form-location-index forms ["nonexistent"]))))))

(deftest test-build-form-location-index-absolute-path
  (testing "an absolute :form/file (e.g. a load-file'd script) does not crash the build (issue #3)"
    ;; clojure.java.io/file throws \"not a relative path\" when an absolute child is
    ;; resolved against a source-path; the index must tolerate the absolute paths
    ;; ClojureStorm records for load-file'd files.
    (let [tmp (java.io.File/createTempFile "heretic-fb" ".clj")
          abs (.getCanonicalPath tmp)]
      (try
        (is (= {[abs 7] 99}
               (bridge/build-form-location-index {99 {:form/file abs :form/line 7}} ["src"]))
            "an existing absolute path is used directly")
        (finally (.delete tmp)))))
  (testing "an absolute :form/file that doesn't exist is skipped, not thrown on"
    (is (= {} (bridge/build-form-location-index
               {1 {:form/file "/no/such/heretic/abs/path.clj" :form/line 1}} ["src"])))))

;; =============================================================================
;; find-containing-form Tests
;; =============================================================================

(deftest test-find-containing-form-exact-match
  (testing "Finds form with exact line match"
    (let [index {["/path/to/file.clj" 10] 12345
                 ["/path/to/file.clj" 25] 12346}]
      (is (= 12345 (bridge/find-containing-form index "/path/to/file.clj" 10)))
      (is (= 12346 (bridge/find-containing-form index "/path/to/file.clj" 25))))))

(deftest test-find-containing-form-within-form
  (testing "Finds containing form when mutation is inside the form"
    (let [index {["/path/to/file.clj" 10] 12345   ;; form starts at line 10
                 ["/path/to/file.clj" 25] 12346}] ;; form starts at line 25
      ;; Mutation at line 15 is inside form starting at line 10
      (is (= 12345 (bridge/find-containing-form index "/path/to/file.clj" 15)))
      ;; Mutation at line 20 is still inside form starting at line 10
      (is (= 12345 (bridge/find-containing-form index "/path/to/file.clj" 20)))
      ;; Mutation at line 30 is inside form starting at line 25
      (is (= 12346 (bridge/find-containing-form index "/path/to/file.clj" 30))))))

(deftest test-find-containing-form-not-found
  (testing "Returns nil when no containing form exists"
    (let [index {["/path/to/file.clj" 10] 12345}]
      ;; Mutation before first form
      (is (nil? (bridge/find-containing-form index "/path/to/file.clj" 5)))
      ;; Different file
      (is (nil? (bridge/find-containing-form index "/path/to/other.clj" 15))))))

(deftest test-find-containing-form-nil-inputs
  (testing "Handles nil inputs gracefully"
    (let [index {["/path/to/file.clj" 10] 12345}]
      (is (nil? (bridge/find-containing-form nil "/path/to/file.clj" 15)))
      (is (nil? (bridge/find-containing-form index nil 15)))
      (is (nil? (bridge/find-containing-form index "/path/to/file.clj" nil))))))

;; =============================================================================
;; resolve-form-id Tests
;; =============================================================================

(deftest test-resolve-form-id-with-index
  (testing "Resolves using form-location-index when available"
    (let [index {["/path/to/file.clj" 10] 12345}
          mutation {:file "/path/to/file.clj"
                    :line 15
                    :form-id 99999}]  ;; mutation's own form-id
      ;; Should find 12345 from index, not use 99999
      (is (= 12345 (bridge/resolve-form-id index mutation))))))

(deftest test-resolve-form-id-fallback
  (testing "Falls back to mutation's form-id when not found in index"
    (let [index {["/path/to/file.clj" 10] 12345}
          mutation {:file "/path/to/other.clj"  ;; different file
                    :line 15
                    :form-id 99999}]
      ;; Should fall back to 99999
      (is (= 99999 (bridge/resolve-form-id index mutation))))))

(deftest test-resolve-form-id-no-index
  (testing "Uses mutation's form-id when index is nil"
    (let [mutation {:file "/path/to/file.clj"
                    :line 15
                    :form-id 99999}]
      (is (= 99999 (bridge/resolve-form-id nil mutation))))))

(deftest test-resolve-form-id-missing-file-line
  (testing "Uses mutation's form-id when file or line missing"
    (let [index {["/path/to/file.clj" 10] 12345}]
      ;; No file
      (is (= 99999 (bridge/resolve-form-id index {:line 15 :form-id 99999})))
      ;; No line
      (is (= 99999 (bridge/resolve-form-id index {:file "/path/to/file.clj" :form-id 99999}))))))

;; =============================================================================
;; use-form-level-matching? Tests
;; =============================================================================

(deftest test-use-form-level-matching-with-index
  (testing "Returns truthy when form-location-index present and non-empty"
    (let [index {:form-location-index {["/path" 10] 12345}
                 :coord-to-tests {}}]
      (is (bridge/use-form-level-matching? index)))))

(deftest test-use-form-level-matching-without-index
  (testing "Returns falsy when form-location-index absent"
    (let [index {:coord-to-tests {}}]
      (is (not (bridge/use-form-level-matching? index))))
    (is (not (bridge/use-form-level-matching? nil)))
    (is (not (bridge/use-form-level-matching? {})))))

(deftest test-use-form-level-matching-empty-index
  (testing "Returns falsy when form-location-index is empty"
    (let [index {:form-location-index {}
                 :coord-to-tests {}}]
      ;; Empty seq is nil, which is falsy
      (is (not (bridge/use-form-level-matching? index))))))
