(ns heretic.integration-test
  "End-to-end integration tests for Heretic mutation testing workflow.

   These tests verify the full pipeline:
   1. Setup temp project with source files containing mutable operators
   2. Generate mutations from source code
   3. Create a mock coverage index (bypassing ClojureStorm)
   4. Run mutations with test execution
   5. Verify report output contains expected data

   Note: These tests do NOT require ClojureStorm. Coverage index is mocked
   to avoid the instrumentation dependency."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.core :as core]
            [heretic.mutation-engine :as engine]
            [heretic.persistence :as persist]
            [heretic.reporter :as reporter]
            [heretic.runner :as runner]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn- create-temp-dir
  "Create a temporary directory for test files."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-integration-" (System/currentTimeMillis)))]
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
      (binding [*test-dir* dir]
        (f))
      (finally
        (delete-recursively dir)))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- create-file!
  "Create a file in the temp directory with given relative path and content.
   Returns the absolute file path.
   Throws if called outside of temp-dir-fixture."
  [rel-path content]
  (when-not *test-dir*
    (throw (ex-info "create-file! must be called within temp-dir-fixture" {:path rel-path})))
  (let [file (io/file *test-dir* rel-path)]
    (io/make-parents file)
    (spit file content)
    (.getPath file)))

(defn- create-source-file!
  "Create a source file in src/ subdirectory."
  [name content]
  (create-file! (str "src/" name) content))

(defn- create-test-file!
  "Create a test file in test/ subdirectory."
  [name content]
  (create-file! (str "test/" name) content))

(defn- source-path []
  (.getPath (io/file *test-dir* "src")))

(defn- test-path []
  (.getPath (io/file *test-dir* "test")))

(defn- heretic-dir []
  (.getPath (io/file *test-dir* ".heretic")))

(defn- make-config
  "Create a test configuration."
  []
  {:source-paths [(source-path)]
   :test-paths [(test-path)]
   :heretic-dir (heretic-dir)
   :test-namespaces :all
   :timeout-ms 5000})

(defn- build-mock-coverage-index
  "Build a mock coverage index that maps form-ids to test symbols.
   This version works for mutations WITHOUT :coord field.

   Arguments:
   - form-to-tests: map of {form-id -> #{test-sym ...}}

   Returns an index structure compatible with runner/tests-for-mutation."
  [form-to-tests]
  {:form-to-tests form-to-tests
   :coord-to-tests {}})

(defn- build-mock-index-with-coords
  "Build a mock coverage index with coord-level mapping.

   Arguments:
   - coord-to-tests: map of {[form-id coord-str] -> #{test-sym ...}}

   Returns full index structure."
  [coord-to-tests]
  (let [form-to-tests (reduce-kv
                       (fn [acc [form-id _coord] tests]
                         (update acc form-id (fnil into #{}) tests))
                       {}
                       coord-to-tests)]
    {:coord-to-tests coord-to-tests
     :form-to-tests form-to-tests}))

(defn- build-mock-index-for-mutations
  "Build a mock coverage index from actual mutations.

   Arguments:
   - mutations: sequence of mutation records (with :form-id and :coord)
   - test-syms: set of test symbols to map to each mutation

   Returns index where each mutation's [form-id coord] maps to test-syms."
  [mutations test-syms]
  (let [coord-to-tests (into {}
                             (for [m mutations]
                               [[(:form-id m) (:coord m)] test-syms]))
        form-to-tests (reduce-kv
                       (fn [acc [form-id _coord] tests]
                         (update acc form-id (fnil into #{}) tests))
                       {}
                       coord-to-tests)]
    {:coord-to-tests coord-to-tests
     :form-to-tests form-to-tests}))

;; =============================================================================
;; Sample Source Files
;; =============================================================================

(def sample-math-source
  "(ns sample.math)

(defn add [a b]
  (+ a b))

(defn subtract [a b]
  (- a b))

(defn multiply [a b]
  (* a b))")

(def sample-logic-source
  "(ns sample.logic)

(defn both? [x y]
  (and x y))

(defn either? [x y]
  (or x y))

(def enabled true)
(def disabled false)")

(def sample-complex-source
  "(ns sample.complex)

(defn calculate [a b op]
  (case op
    :add (+ a b)
    :sub (- a b)
    :mul (* a b)
    :div (/ a b)))

(defn validate [x y]
  (and (> x 0)
       (or (< y 100)
           (= x y))))")

;; =============================================================================
;; Mutation Discovery Tests
;; =============================================================================

(deftest test-discover-mutations-arithmetic
  (testing "Discovers all arithmetic mutation sites in source file"
    (let [_file (create-source-file! "sample/math.clj" sample-math-source)
          mutations (engine/generate-mutations [(source-path)])]
      ;; Should find: +, -, *
      (is (= 3 (count mutations)))
      (let [op-ids (set (map :operator mutations))]
        (is (= #{:swap-plus-minus :swap-minus-plus :swap-mult-div} op-ids))))))

(deftest test-discover-mutations-boolean-and-logical
  (testing "Discovers boolean and logical mutation sites"
    (let [_file (create-source-file! "sample/logic.clj" sample-logic-source)
          mutations (engine/generate-mutations [(source-path)])]
      ;; Should find: and, or, true, false
      (is (= 4 (count mutations)))
      (let [op-ids (set (map :operator mutations))]
        (is (contains? op-ids :swap-and-or))
        (is (contains? op-ids :swap-or-and))
        (is (contains? op-ids :swap-true-false))
        (is (contains? op-ids :swap-false-true))))))

(deftest test-discover-mutations-complex-source
  (testing "Discovers mutations in complex nested expressions"
    (let [_file (create-source-file! "sample/complex.clj" sample-complex-source)
          mutations (engine/generate-mutations [(source-path)])]
      ;; Should find: +, -, *, /, and, or
      (is (= 6 (count mutations)))
      (let [op-ids (frequencies (map :operator mutations))]
        (is (= 1 (get op-ids :swap-plus-minus)))
        (is (= 1 (get op-ids :swap-minus-plus)))
        (is (= 1 (get op-ids :swap-mult-div)))
        (is (= 1 (get op-ids :swap-div-mult)))
        (is (= 1 (get op-ids :swap-and-or)))
        (is (= 1 (get op-ids :swap-or-and)))))))

(deftest test-discover-mutations-multiple-files
  (testing "Discovers mutations across multiple source files"
    (let [_math (create-source-file! "sample/math.clj" sample-math-source)
          _logic (create-source-file! "sample/logic.clj" sample-logic-source)
          mutations (engine/generate-mutations [(source-path)])]
      ;; math: 3 (+, -, *)
      ;; logic: 4 (and, or, true, false)
      (is (= 7 (count mutations)))

      ;; Verify we have mutations from both files
      (let [files (set (map :file mutations))]
        (is (= 2 (count files)))))))

;; =============================================================================
;; Mutation Application Tests
;; =============================================================================

(deftest test-apply-mutation-modifies-file
  (testing "Applying mutation correctly modifies the source file"
    (let [file (create-source-file! "sample/math.clj" sample-math-source)
          mutations (engine/mutations-for-file file)
          plus-mutation (first (filter #(= :swap-plus-minus (:operator %)) mutations))]

      (is (some? plus-mutation) "Should find a + -> - mutation")

      (engine/with-mutation [applied plus-mutation]
        (let [modified-content (slurp file)]
          ;; The + in add function should now be -
          (is (str/includes? modified-content "(- a b)")
              "File should contain mutated operator")
          (is (not (str/includes? modified-content "(+ a b)"))
              "Original + should be replaced")))

      ;; After with-mutation, file should be reverted
      (is (str/includes? (slurp file) "(+ a b)")
          "File should be reverted after with-mutation"))))

(deftest test-apply-all-mutation-types
  (testing "All mutation types can be applied and reverted"
    (let [content "(ns test.all)
(def a (+ 1 2))
(def b (- 3 4))
(def c (* 5 6))
(def d (/ 7 8))
(def e (and x y))
(def f (or p q))
(def g true)
(def h false)"
          file (create-source-file! "test/all.clj" content)
          mutations (engine/mutations-for-file file)]

      (is (= 8 (count mutations)) "Should find 8 mutation sites")

      ;; Apply each mutation and verify it modifies the file
      (doseq [mutation mutations]
        (engine/with-mutation [applied mutation]
          (let [modified (slurp file)]
            (is (not= content modified)
                (str "Mutation " (:operator mutation) " should modify file"))))
        ;; Verify reversion
        (is (= content (slurp file))
            (str "Mutation " (:operator mutation) " should be reverted"))))))

;; =============================================================================
;; Mock Test Execution Tests
;; =============================================================================

(deftest test-evaluate-mutation-with-mock-index
  (testing "Mutation evaluation with mocked coverage index"
    (let [file (create-source-file! "sample/math.clj" sample-math-source)
          mutations (engine/mutations-for-file file)
          plus-mutation (first (filter #(= :swap-plus-minus (:operator %)) mutations))

          ;; Create mock index that maps this specific mutation to our passing mock test
          mock-index (build-mock-index-for-mutations
                      [plus-mutation]
                      #{'heretic.fixtures.mock-tests/passing-test})]

      ;; Evaluate mutation (without actually applying it - just test runner lookup)
      (let [tests (runner/tests-for-mutation mock-index plus-mutation)]
        (is (= #{'heretic.fixtures.mock-tests/passing-test} tests))))))

(deftest test-evaluate-mutation-killed
  (testing "Mutation is killed when tests fail"
    ;; Use mock test that always fails
    ;; Note: mutation has :coord so we need coord-level mapping
    (let [mutation {:form-id 12345 :coord "0" :operator :swap-plus-minus}
          mock-index (build-mock-index-with-coords
                      {[12345 "0"] #{'heretic.fixtures.mock-tests/failing-test}})
          result (runner/evaluate-mutation mock-index mutation {:timeout-ms 5000})]

      (is (= :killed (:status result))
          "Mutation should be killed when test fails"))))

(deftest test-evaluate-mutation-survived
  (testing "Mutation survives when tests pass"
    ;; Note: mutation has :coord so we need coord-level mapping
    (let [mutation {:form-id 12345 :coord "0" :operator :swap-plus-minus}
          mock-index (build-mock-index-with-coords
                      {[12345 "0"] #{'heretic.fixtures.mock-tests/passing-test}})
          result (runner/evaluate-mutation mock-index mutation {:timeout-ms 5000})]

      (is (= :survived (:status result))
          "Mutation should survive when all tests pass"))))

(deftest test-evaluate-mutation-no-coverage
  (testing "Mutation returns :no-coverage when not covered by tests"
    (let [mock-index (build-mock-coverage-index {})
          mutation {:form-id 99999 :coord "0" :operator :swap-plus-minus}
          result (runner/evaluate-mutation mock-index mutation {:timeout-ms 5000})]

      (is (= :no-coverage (:status result))))))

;; =============================================================================
;; Reporter Tests
;; =============================================================================

(deftest test-reporter-summary-data
  (testing "Reporter correctly summarizes mutation results"
    (let [results [{:mutation {:operator :swap-plus-minus} :status :killed :duration-ms 100}
                   {:mutation {:operator :swap-minus-plus} :status :killed :duration-ms 100}
                   {:mutation {:operator :swap-mult-div} :status :survived :duration-ms 100}
                   {:mutation {:operator :swap-and-or} :status :no-coverage :duration-ms 0}
                   {:mutation {:operator :swap-true-false} :status :timeout :duration-ms 5000}]
          summary (reporter/summary-data results)]

      (is (= 5 (:total summary)))
      (is (= {:killed 2 :survived 1 :no-coverage 1 :timeout 1 :error 0}
             (:counts summary)))
      ;; Score = killed / (killed + survived) = 2/3
      (is (< (Math/abs (- 0.6666666666666666 (:score summary))) 0.001)))))

(deftest test-reporter-count-by-status
  (testing "count-by-status correctly categorizes results"
    (let [results [{:status :killed}
                   {:status :killed}
                   {:status :killed}
                   {:status :survived}
                   {:status :no-coverage}
                   {:status :no-coverage}
                   {:status :timeout}
                   {:status :error}]
          counts (reporter/count-by-status results)]

      (is (= 3 (:killed counts)))
      (is (= 1 (:survived counts)))
      (is (= 2 (:no-coverage counts)))
      (is (= 1 (:timeout counts)))
      (is (= 1 (:error counts))))))

(deftest test-reporter-mutation-score
  (testing "mutation-score calculation"
    (testing "perfect score when all killed"
      (let [results [{:status :killed} {:status :killed}]]
        (is (= 1.0 (reporter/mutation-score results)))))

    (testing "zero score when all survived"
      (let [results [{:status :survived} {:status :survived}]]
        (is (= 0.0 (reporter/mutation-score results)))))

    (testing "no-coverage mutations excluded from score"
      (let [results [{:status :killed}
                     {:status :no-coverage}
                     {:status :no-coverage}]]
        ;; Only 1 killable mutation, which was killed
        (is (= 1.0 (reporter/mutation-score results)))))

    (testing "nil when no killable mutations"
      (let [results [{:status :no-coverage}
                     {:status :timeout}
                     {:status :error}]]
        (is (nil? (reporter/mutation-score results)))))))

(deftest test-reporter-filters
  (testing "Reporter filter functions"
    (let [results [{:status :killed :id 1}
                   {:status :survived :id 2}
                   {:status :no-coverage :id 3}
                   {:status :timeout :id 4}
                   {:status :error :id 5}]]

      (is (= [{:status :killed :id 1}] (reporter/killed results)))
      (is (= [{:status :survived :id 2}] (reporter/survivors results)))
      (is (= [{:status :no-coverage :id 3}] (reporter/no-coverage results)))
      (is (= [{:status :timeout :id 4}] (reporter/timeouts results)))
      (is (= [{:status :error :id 5}] (reporter/errors results))))))

;; =============================================================================
;; Full Pipeline Integration Tests
;; =============================================================================

(deftest test-full-pipeline-without-clojurestorm
  (testing "Full mutation pipeline with mocked coverage"
    ;; Create source file
    (let [file (create-source-file! "sample/math.clj" sample-math-source)
          mutations (engine/mutations-for-file file)]

      (is (= 3 (count mutations)) "Should find 3 mutation sites")

      ;; Build mock index - assume all mutations are covered by the failing test
      ;; (which will kill mutations)
      (let [mock-index (build-mock-index-for-mutations
                        mutations
                        #{'heretic.fixtures.mock-tests/failing-test})]

        ;; Evaluate all mutations
        (let [results (mapv #(runner/evaluate-mutation mock-index % {:timeout-ms 5000})
                            mutations)
              summary (runner/summarize-results results)]

          ;; All should be killed since test fails
          (is (= 3 (:killed summary)))
          (is (= 0 (:survived summary)))
          (is (= 1.0 (:mutation-score summary))))))))

(deftest test-full-pipeline-mixed-results
  (testing "Pipeline with mix of killed and survived mutations"
    (let [file (create-source-file! "sample/math.clj" sample-math-source)
          mutations (vec (engine/mutations-for-file file))]

      (is (= 3 (count mutations)))

      ;; Create index where first mutation is covered by failing test (killed)
      ;; and others are covered by passing test (survived)
      (let [m0 (nth mutations 0)
            m1 (nth mutations 1)
            m2 (nth mutations 2)
            mock-index (build-mock-index-with-coords
                        {[(:form-id m0) (:coord m0)] #{'heretic.fixtures.mock-tests/failing-test}
                         [(:form-id m1) (:coord m1)] #{'heretic.fixtures.mock-tests/passing-test}
                         [(:form-id m2) (:coord m2)] #{'heretic.fixtures.mock-tests/passing-test}})]

        (let [results (mapv #(runner/evaluate-mutation mock-index % {:timeout-ms 5000})
                            mutations)
              summary (runner/summarize-results results)]

          (is (= 1 (:killed summary)))
          (is (= 2 (:survived summary)))
          ;; Score = 1/3 = 0.333...
          (is (< (Math/abs (- 0.333333 (:mutation-score summary))) 0.01)))))))

(deftest test-full-pipeline-no-coverage
  (testing "Pipeline with mutations having no test coverage"
    (let [file (create-source-file! "sample/math.clj" sample-math-source)
          mutations (vec (engine/mutations-for-file file))]

      ;; Empty index - no tests cover any mutations
      (let [mock-index (build-mock-coverage-index {})]

        (let [results (mapv #(runner/evaluate-mutation mock-index % {:timeout-ms 5000})
                            mutations)
              summary (runner/summarize-results results)]

          (is (= 0 (:killed summary)))
          (is (= 0 (:survived summary)))
          (is (= 3 (:no-coverage summary)))
          ;; Score is 1.0 when no testable mutations (perfect by default)
          (is (= 1.0 (:mutation-score summary))))))))

;; =============================================================================
;; Persistence Integration Tests
;; =============================================================================

(deftest test-heretic-dir-operations
  (testing "Heretic directory management"
    (let [config (make-config)]
      ;; Directory should not exist initially
      (is (not (.exists (io/file (heretic-dir)))))

      ;; Ensure creates directory
      (persist/ensure-heretic-dir! (heretic-dir))
      (is (.exists (io/file (heretic-dir))))
      (is (.exists (io/file (heretic-dir) "coverage")))

      ;; Clean removes directory
      (core/clean! config)
      (is (not (.exists (io/file (heretic-dir))))))))

(deftest test-save-and-load-index
  (testing "Index persistence roundtrip"
    (persist/ensure-heretic-dir! (heretic-dir))

    (let [original-index {:coord-to-tests {[123 "0"] #{'test.ns/test1}
                                           [456 "1,0"] #{'test.ns/test2}}
                          :form-to-tests {123 #{'test.ns/test1}
                                          456 #{'test.ns/test2}}
                          :included-test-ns #{'test.ns}
                          :rebuilt-at (System/currentTimeMillis)}]

      (persist/save-index! (heretic-dir) original-index)

      (let [loaded (persist/load-index (heretic-dir))]
        (is (= (:coord-to-tests original-index) (:coord-to-tests loaded)))
        (is (= (:form-to-tests original-index) (:form-to-tests loaded)))
        (is (= (:included-test-ns original-index) (:included-test-ns loaded)))))))

;; =============================================================================
;; Config Loading Tests
;; =============================================================================

(deftest test-load-config-integration
  (testing "Config loading with actual file"
    (let [config-path (create-file! "heretic.edn"
                                    (pr-str {:source-paths ["my-src"]
                                             :test-paths ["my-test"]
                                             :timeout-ms 10000}))]
      (let [config (core/load-config config-path)]
        (is (= ["my-src"] (:source-paths config)))
        (is (= ["my-test"] (:test-paths config)))
        (is (= 10000 (:timeout-ms config)))
        ;; Defaults should be merged in
        (is (= ".heretic" (:heretic-dir config)))))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest test-empty-source-paths
  (testing "Handles empty source directory"
    (let [_empty (create-source-file! ".gitkeep" "")
          mutations (engine/generate-mutations [(source-path)])]
      (is (empty? mutations)))))

(deftest test-source-file-with-no-mutations
  (testing "Handles source file with no mutable operators"
    (let [_file (create-source-file! "sample/pure.clj"
                                     "(ns sample.pure)
(def x 42)
(defn identity-fn [a] a)
(defn const [] \"hello\")")]
      (let [mutations (engine/generate-mutations [(source-path)])]
        (is (empty? mutations))))))

(deftest test-deeply-nested-mutations
  (testing "Finds mutations in deeply nested expressions"
    (let [_file (create-source-file! "sample/nested.clj"
                                     "(ns sample.nested)
(defn deep [a b c d]
  (if (and (or (> a b)
               (< c d))
           true)
    (+ (* a b) (- c d))
    false))")]
      (let [mutations (engine/generate-mutations [(source-path)])]
        ;; Should find: and, or, true, +, *, -, false
        (is (= 7 (count mutations)))))))

(deftest test-mutation-with-special-characters
  (testing "Handles files with special characters in expressions"
    (let [_file (create-source-file! "sample/special.clj"
                                     "(ns sample.special)
(defn check [s]
  (and (string? s)
       (or (empty? s)
           (= s \"test-value\"))))")]
      (let [mutations (engine/generate-mutations [(source-path)])]
        ;; Should find: and, or
        (is (= 2 (count mutations)))))))

;; =============================================================================
;; Runner Integration Tests
;; =============================================================================

(deftest test-runner-summarize-results-empty
  (testing "Summarize handles empty results"
    (let [summary (runner/summarize-results [])]
      (is (= 0 (:total summary)))
      (is (= 0 (:killed summary)))
      (is (= 0 (:survived summary)))
      (is (= 1.0 (:mutation-score summary))))))

(deftest test-runner-batch-evaluation
  (testing "Batch evaluation of multiple mutations"
    (let [mutations [{:form-id 1 :coord "0" :operator :swap-plus-minus}
                     {:form-id 2 :coord "0" :operator :swap-minus-plus}
                     {:form-id 3 :coord "0" :operator :swap-mult-div}]
          ;; Build coord-level index for first two mutations, leave third without coverage
          mock-index (build-mock-index-with-coords
                      {[1 "0"] #{'heretic.fixtures.mock-tests/failing-test}
                       [2 "0"] #{'heretic.fixtures.mock-tests/passing-test}})
          results (runner/evaluate-mutations mock-index mutations {:timeout-ms 5000})]

      (is (= 3 (count results)))
      (is (= :killed (:status (nth results 0))))
      (is (= :survived (:status (nth results 1))))
      (is (= :no-coverage (:status (nth results 2)))))))

;; =============================================================================
;; Status Check Tests
;; =============================================================================

(deftest test-status-with-no-coverage
  (testing "Status check with no coverage data"
    (let [config (make-config)
          result (core/status config)]
      (is (set? (:stale-namespaces result)))
      (is (set? (:fresh-namespaces result)))
      (is (= 0 (:total-coverage-files result)))
      (is (false? (:index-exists? result))))))

(deftest test-status-with-index
  (testing "Status check with existing index"
    (persist/ensure-heretic-dir! (heretic-dir))
    (persist/save-index! (heretic-dir) {:some "data"})

    (let [config (make-config)
          result (core/status config)]
      (is (true? (:index-exists? result))))))
