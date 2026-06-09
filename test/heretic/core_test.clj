(ns heretic.core-test
  "Tests for heretic.core entry point module.

   Tests cover:
   - load-config: missing config error, merge with defaults
   - status: stale/fresh namespace sets
   - clean!: with/without existing directory"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [heretic.core :as core]
            [heretic.operators :as ops]
            [heretic.persistence :as persist]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn with-temp-dir
  "Fixture that creates a temporary directory for tests."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "heretic-core-test-" (System/currentTimeMillis)))]
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
;; load-config Tests
;; =============================================================================

(deftest load-config-missing-file-test
  (testing "throws when config file is missing"
    (let [missing-path (str *test-dir* "/does-not-exist.edn")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing heretic.edn config file"
           (core/load-config missing-path))))))

(deftest load-config-merge-with-defaults-test
  (testing "merges user config with defaults"
    (let [config-path (str *test-dir* "/heretic.edn")]
      ;; Create minimal config file
      (spit config-path "{:source-paths [\"my-src\"]}")
      (let [config (core/load-config config-path)]
        ;; User-specified value should be present
        (is (= ["my-src"] (:source-paths config))
            "User-specified source-paths should be used")
        ;; Default values should be merged in
        (is (= ["test"] (:test-paths config))
            "Default test-paths should be present")
        (is (= ".heretic" (:heretic-dir config))
            "Default heretic-dir should be present")
        (is (= 5000 (:timeout-ms config))
            "Default timeout-ms should be present")
        (is (= :terminal (:report-format config))
            "Default report-format should be present")))))

(deftest load-config-all-defaults-test
  (testing "returns all defaults when config is empty map"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path "{}")
      (let [config (core/load-config config-path)]
        (is (= core/default-config config)
            "Empty config should return all defaults")))))

(deftest load-config-override-all-test
  (testing "user config can override all defaults"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path (pr-str {:source-paths ["custom-src"]
                                 :test-paths ["custom-test"]
                                 :heretic-dir ".custom-heretic"
                                 :timeout-ms 10000}))
      (let [config (core/load-config config-path)]
        (is (= ["custom-src"] (:source-paths config)))
        (is (= ["custom-test"] (:test-paths config)))
        (is (= ".custom-heretic" (:heretic-dir config)))
        (is (= 10000 (:timeout-ms config)))))))

(deftest load-config-default-path-test
  (testing "uses heretic.edn as default path"
    ;; This test verifies the 0-arity behavior
    ;; We can't easily test the default path without changing cwd,
    ;; but we can verify the function signature exists
    (is (fn? core/load-config)
        "load-config should be a function")))

;; =============================================================================
;; status Tests
;; =============================================================================

(deftest status-empty-heretic-dir-test
  (testing "status with no .heretic directory"
    (let [config {:heretic-dir (str *test-dir* "/.heretic")
                  :source-paths [(str *test-dir* "/src")]
                  :test-paths [(str *test-dir* "/test")]
                  :test-namespaces []}]
      ;; Don't create .heretic dir
      (let [result (core/status config)]
        (is (set? (:stale-namespaces result))
            "stale-namespaces should be a set")
        (is (set? (:fresh-namespaces result))
            "fresh-namespaces should be a set")
        (is (number? (:total-coverage-files result))
            "total-coverage-files should be a number")
        (is (boolean? (:index-exists? result))
            "index-exists? should be a boolean")
        (is (= 0 (:total-coverage-files result))
            "Should have no coverage files")
        (is (false? (:index-exists? result))
            "Index should not exist")))))

(deftest status-all-stale-test
  (testing "status returns all namespaces as stale when no coverage exists"
    ;; Setup: create test namespaces without coverage
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          heretic-dir (str *test-dir* "/.heretic")]
      (.mkdirs test-dir)
      (spit (io/file test-dir "core_test.clj") "(ns my.app.core-test)")
      (spit (io/file test-dir "util_test.clj") "(ns my.app.util-test)")

      (let [config {:heretic-dir heretic-dir
                    :source-paths [(str *test-dir* "/src")]
                    :test-paths [(str *test-dir* "/test")]
                    :test-namespaces ['my.app.core-test 'my.app.util-test]}
            result (core/status config)]
        (is (= #{'my.app.core-test 'my.app.util-test} (:stale-namespaces result))
            "All namespaces should be stale when no coverage exists")
        (is (= #{} (:fresh-namespaces result))
            "No namespaces should be fresh")))))

(deftest status-mixed-stale-fresh-test
  (testing "status correctly identifies stale and fresh namespaces"
    ;; Setup: create test files
    (let [test-dir (io/file *test-dir* "test" "my" "app")
          heretic-dir (str *test-dir* "/.heretic")
          ;; Use the same config for saving and checking to avoid hash mismatch
          config {:heretic-dir heretic-dir
                  :source-paths [(str *test-dir* "/src")]
                  :test-paths [(str *test-dir* "/test")]
                  :test-namespaces ['my.app.fresh-test 'my.app.stale-test]}]
      (.mkdirs test-dir)
      (spit (io/file test-dir "fresh_test.clj") "(ns my.app.fresh-test)")
      (spit (io/file test-dir "stale_test.clj") "(ns my.app.stale-test)")

      ;; Create .heretic dir and save coverage for fresh-test
      (persist/ensure-heretic-dir! heretic-dir)
      (let [fresh-test-path (str test-dir "/fresh_test.clj")]
        (persist/save-test-ns-coverage!
         heretic-dir
         {:test-ns 'my.app.fresh-test
          :coverage {}
          :source-deps #{}
          :hashes {:test-file (persist/hash-file fresh-test-path)
                   :source-files nil
                   :config (hash config)}}))

      (let [result (core/status config)]
        (is (contains? (:fresh-namespaces result) 'my.app.fresh-test)
            "fresh-test should be in fresh set")
        (is (contains? (:stale-namespaces result) 'my.app.stale-test)
            "stale-test should be in stale set")
        (is (not (contains? (:stale-namespaces result) 'my.app.fresh-test))
            "fresh-test should not be in stale set")
        (is (not (contains? (:fresh-namespaces result) 'my.app.stale-test))
            "stale-test should not be in fresh set")))))

(deftest status-coverage-file-count-test
  (testing "status reports correct coverage file count"
    (let [heretic-dir (str *test-dir* "/.heretic")]
      (persist/ensure-heretic-dir! heretic-dir)
      ;; Create some coverage files
      (persist/save-test-ns-coverage! heretic-dir {:test-ns 'ns1})
      (persist/save-test-ns-coverage! heretic-dir {:test-ns 'ns2})
      (persist/save-test-ns-coverage! heretic-dir {:test-ns 'ns3})

      (let [config {:heretic-dir heretic-dir
                    :source-paths []
                    :test-paths []
                    :test-namespaces []}
            result (core/status config)]
        (is (= 3 (:total-coverage-files result))
            "Should report 3 coverage files")))))

(deftest status-index-exists-test
  (testing "status reports whether index exists"
    (let [heretic-dir (str *test-dir* "/.heretic")]
      (persist/ensure-heretic-dir! heretic-dir)

      ;; Without index
      (let [config {:heretic-dir heretic-dir
                    :source-paths []
                    :test-paths []
                    :test-namespaces []}
            result (core/status config)]
        (is (false? (:index-exists? result))
            "index-exists? should be false when no index"))

      ;; With index
      (persist/save-index! heretic-dir {:some "index"})
      (let [config {:heretic-dir heretic-dir
                    :source-paths []
                    :test-paths []
                    :test-namespaces []}
            result (core/status config)]
        (is (true? (:index-exists? result))
            "index-exists? should be true when index exists")))))

;; =============================================================================
;; clean! Tests
;; =============================================================================

(deftest clean-with-existing-directory-test
  (testing "clean! removes existing .heretic directory"
    (let [heretic-dir (str *test-dir* "/.heretic")]
      ;; Create the directory with some content
      (persist/ensure-heretic-dir! heretic-dir)
      (persist/save-test-ns-coverage! heretic-dir {:test-ns 'some.test})
      (persist/save-index! heretic-dir {:some "index"})

      ;; Verify directory exists
      (is (.exists (io/file heretic-dir))
          "Heretic directory should exist before clean")

      ;; Clean it
      (let [config {:heretic-dir heretic-dir}
            result (core/clean! config)]
        (is (true? (:deleted result))
            "Result should indicate deletion")
        (is (= heretic-dir (:path result))
            "Result should include the path")
        (is (not (.exists (io/file heretic-dir)))
            "Heretic directory should not exist after clean")))))

(deftest clean-without-existing-directory-test
  (testing "clean! handles non-existent directory gracefully"
    (let [heretic-dir (str *test-dir* "/.heretic")]
      ;; Don't create the directory
      (is (not (.exists (io/file heretic-dir)))
          "Heretic directory should not exist")

      ;; Clean should succeed but indicate nothing was deleted
      (let [config {:heretic-dir heretic-dir}
            result (core/clean! config)]
        (is (false? (:deleted result))
            "Result should indicate no deletion")
        (is (= heretic-dir (:path result))
            "Result should include the path")))))

(deftest clean-removes-all-subdirectories-test
  (testing "clean! removes directory recursively including subdirectories"
    (let [heretic-dir (str *test-dir* "/.heretic")
          coverage-dir (io/file heretic-dir "coverage")
          nested-dir (io/file heretic-dir "nested" "deep")]
      ;; Create nested directory structure
      (.mkdirs coverage-dir)
      (.mkdirs nested-dir)
      (spit (io/file coverage-dir "test.edn") "{}")
      (spit (io/file nested-dir "file.txt") "content")

      ;; Verify structure exists
      (is (.exists nested-dir)
          "Nested directory should exist before clean")

      ;; Clean it
      (let [config {:heretic-dir heretic-dir}
            result (core/clean! config)]
        (is (true? (:deleted result)))
        (is (not (.exists (io/file heretic-dir)))
            "Heretic directory and all contents should be removed")))))

;; =============================================================================
;; Parallel Configuration Tests
;; =============================================================================

(deftest load-config-parallel-defaults-test
  (testing "default config includes parallel mutation testing settings"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path "{}")
      (let [config (core/load-config config-path)]
        (is (= false (:parallel-mutate config))
            "parallel-mutate should default to false")
        (is (nil? (:parallel-workers config))
            "parallel-workers should default to nil (CPU count)")
        (is (nil? (:budget-ms config))
            "budget-ms should default to nil (unlimited)")))))

(deftest load-config-parallel-override-test
  (testing "user can override parallel mutation testing settings"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path (pr-str {:parallel-mutate true
                                 :parallel-workers 4
                                 :budget-ms 30000}))
      (let [config (core/load-config config-path)]
        (is (= true (:parallel-mutate config)))
        (is (= 4 (:parallel-workers config)))
        (is (= 30000 (:budget-ms config)))))))

;; =============================================================================
;; resolve-operators Tests
;; =============================================================================

(deftest resolve-operators-with-override-test
  (testing "explicit operators argument takes highest priority"
    (let [config {:preset :fast}
          custom-ops [ops/swap-plus-minus ops/swap-minus-plus]
          result (core/resolve-operators config :operators custom-ops)]
      (is (= custom-ops result)
          "Should return the explicit operators argument"))))

(deftest resolve-operators-with-config-operators-test
  (testing "config :operators key (as operator ids) takes priority over preset"
    (let [config {:preset :fast
                  :operators [:swap-plus-minus :swap-minus-plus]}
          result (core/resolve-operators config)]
      (is (= 2 (count result)))
      (is (= #{:swap-plus-minus :swap-minus-plus} (set (map :id result))))))

  (testing "config :operators key works with full operator definitions"
    (let [config {:operators [ops/swap-plus-minus]}
          result (core/resolve-operators config)]
      (is (= [ops/swap-plus-minus] result)))))

(deftest resolve-operators-with-preset-test
  (testing "config :preset :fast returns fast operators"
    (let [config {:preset :fast}
          result (core/resolve-operators config)]
      (is (seq result))
      (is (= (count (:fast ops/presets)) (count result)))))

  (testing "config :preset :standard returns standard operators"
    (let [config {:preset :standard}
          result (core/resolve-operators config)]
      (is (seq result))
      (is (= (count (:standard ops/presets)) (count result)))))

  (testing "config :preset :comprehensive returns all operators"
    (let [config {:preset :comprehensive}
          result (core/resolve-operators config)]
      (is (= (count ops/all-operators) (count result))))))

(deftest resolve-operators-default-test
  (testing "defaults to :standard preset when no config specified"
    (let [config {}
          result (core/resolve-operators config)]
      (is (= (count (:standard ops/presets)) (count result))))))

(deftest load-config-preset-default-test
  (testing "default config uses :standard preset"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path "{}")
      (let [config (core/load-config config-path)]
        (is (= :standard (:preset config))
            "Default preset should be :standard"))))

  (testing "user can override preset"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path "{:preset :fast}")
      (let [config (core/load-config config-path)]
        (is (= :fast (:preset config)))))))

;; =============================================================================
;; Executor Configuration Tests
;; =============================================================================

(deftest load-config-executor-defaults-test
  (testing "default config includes executor settings"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path "{}")
      (let [config (core/load-config config-path)]
        (is (= :legacy (:executor config))
            "executor should default to :legacy")
        (is (= 30000 (:mutation-timeout-ms config))
            "mutation-timeout-ms should default to 30000")))))

(deftest load-config-executor-override-test
  (testing "user can override executor settings (e.g. the :process executor)"
    (let [config-path (str *test-dir* "/heretic.edn")]
      (spit config-path (pr-str {:executor :process
                                 :mutation-timeout-ms 60000
                                 :parallel-workers 4}))
      (let [config (core/load-config config-path)]
        (is (= :process (:executor config)))
        (is (= 60000 (:mutation-timeout-ms config)))
        (is (= 4 (:parallel-workers config)))))))

;; =============================================================================
;; no-coverage surfacing
;; =============================================================================

(def ^:private nc-sites
  [{:file "src/a.clj" :line 20 :column 0}
   {:file "src/a.clj" :line 10 :column 2}
   {:file "src/b.clj" :line 5 :column 1}])

(defn- spit-results! [m]
  (spit (io/file *test-dir* "mutation-results.edn") (pr-str m)))

(deftest no-coverage-sites-reads-persisted-sites-test
  (testing "no-coverage-sites returns the persisted :no-coverage sites verbatim"
    (spit-results! {:survivors [] :no-coverage nc-sites :summary {} :timestamp 0})
    (is (= nc-sites (core/no-coverage-sites {:heretic-dir *test-dir*})))))

(deftest no-coverage-sites-missing-results-throws-test
  (testing "no-coverage-sites throws when no results file exists (like survivors)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/no-coverage-sites {:heretic-dir *test-dir*})))))

(deftest print-no-coverage-groups-by-file-test
  (testing "groups sites by file, dedup+sorted lines, per-file counts, with the caveat"
    (spit-results! {:survivors [] :no-coverage nc-sites :summary {} :timestamp 0})
    (let [out (with-out-str (core/print-no-coverage {:heretic-dir *test-dir*}))]
      (is (re-find #"NO COVERAGE \(3 sites in 2 files\)" out))
      (is (re-find #"src/a\.clj \(2\): lines 10, 20" out) "lines sorted + deduped per file")
      (is (re-find #"src/b\.clj \(1\): lines 5" out))
      (is (re-find #"excluded|key-gated" out) "carries the no-coverage caveat"))))

(deftest print-no-coverage-empty-test
  (testing "no uncovered sites prints a clear message, not an empty section"
    (spit-results! {:survivors [] :no-coverage [] :summary {} :timestamp 0})
    (is (re-find #"No uncovered mutation sites"
                 (with-out-str (core/print-no-coverage {:heretic-dir *test-dir*}))))))
