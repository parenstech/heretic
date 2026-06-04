(ns heretic.sandbox-test
  "Tests for heretic.sandbox — the isolated-copy (sandboxed) mutation orchestrator.

   Covers the pure helpers that build the child JVM command, resolve the sandbox
   path, derive the copy-set, and read the result map back. The end-to-end spawn
   path (copy → storm child → score → clean tree) is exercised by an integration
   run, not here (see docs/sandboxed-mutation.md)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heretic.sandbox :as sb])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "heretic-sb-test"
                                      (make-array FileAttribute 0))))

(deftest storm-jvm-opts-test
  (testing "always enables instrumentation"
    (is (= ["-Dclojure.storm.instrumentEnable=true"]
           (sb/storm-jvm-opts {}))))
  (testing "adds only/skip prefix filters when configured"
    (is (= ["-Dclojure.storm.instrumentEnable=true"
            "-Dclojure.storm.instrumentOnlyPrefixes=heretic,app"
            "-Dclojure.storm.instrumentSkipPrefixes=heretic.tracer"]
           (sb/storm-jvm-opts {:instrument-prefixes ["heretic" "app"]
                               :instrument-skip-prefixes ["heretic.tracer"]})))))

(deftest child-code-test
  (testing "mutate all when no files"
    (let [code (sb/child-code nil)]
      (is (str/includes? code "heretic/mutate!"))
      (is (not (str/includes? code ":files")))
      (is (str/includes? code "(System/exit 0)"))))
  (testing "restricts to specific files"
    (is (str/includes? (sb/child-code ["src/a.clj" "src/b.clj"])
                       ":files [\"src/a.clj\" \"src/b.clj\"]"))))

(deftest child-command-test
  (testing "clojure -J<storm> -M:collect -e <code> by default"
    (let [cmd (sb/child-command {} "CODE")]
      (is (= "clojure" (first cmd)))
      (is (= ["-M:collect" "-e" "CODE"] (take-last 3 cmd)))
      (is (some #(str/starts-with? % "-J-Dclojure.storm.instrumentEnable") cmd))))
  (testing "honors custom :sandbox-aliases"
    (is (some #(= "-M:collect:extra" %)
              (sb/child-command {:sandbox-aliases ["collect" "extra"]} "CODE"))))
  (testing "honors :sandbox-jvm-opts (extra -J) and :sandbox-deps (-Sdeps) — real consumer-project shape"
    (let [cmd (sb/child-command {:sandbox-aliases ["collect" "heretic-src"]
                                 :sandbox-jvm-opts ["-Xmx4g" "-Dclojure.storm.instrumentAutoPrefixes=false"]
                                 :sandbox-deps {:aliases {:heretic-src {:extra-paths ["/abs/heretic/src"]}}}}
                                "CODE")]
      (is (some #(= "-J-Xmx4g" %) cmd))
      (is (some #(= "-J-Dclojure.storm.instrumentAutoPrefixes=false" %) cmd))
      (is (= {:aliases {:heretic-src {:extra-paths ["/abs/heretic/src"]}}}
             (edn/read-string (->> cmd (drop-while #(not= "-Sdeps" %)) second)))
          "-Sdeps EDN round-trips")
      (is (= ["-M:collect:heretic-src" "-e" "CODE"] (take-last 3 cmd))))))

(deftest resolve-sandbox-dir-test
  (testing "relative default joined to project root"
    (is (= "/proj/.heretic-sandbox" (sb/resolve-sandbox-dir {} "/proj"))))
  (testing "custom relative dir"
    (is (= "/proj/sbx" (sb/resolve-sandbox-dir {:sandbox-dir "sbx"} "/proj"))))
  (testing "absolute dir used as-is"
    (is (= "/tmp/sb" (sb/resolve-sandbox-dir {:sandbox-dir "/tmp/sb"} "/proj")))))

(deftest sync-entries-test
  (testing "includes existing classpath roots + config files under project-root, drops missing"
    (let [root (temp-dir)]
      (.mkdirs (io/file root "src"))
      (.mkdirs (io/file root "test"))
      (spit (io/file root "deps.edn") (pr-str {:paths ["src" "resources"]}))
      (spit (io/file root "heretic.edn") (pr-str {}))
      (let [entries (set (sb/sync-entries {:source-paths ["src"] :test-paths ["test"]}
                                          (.getPath root)))]
        (is (contains? entries "src"))
        (is (contains? entries "test"))
        (is (contains? entries "deps.edn"))
        (is (contains? entries "heretic.edn"))
        (is (not (contains? entries "resources")) "in deps :paths but absent on disk -> dropped")
        (is (not (contains? entries "does-not-exist"))))
      ;; :sandbox-extra-paths picks up top-level dirs outside src/test (e.g. cassettes)
      (.mkdirs (io/file root "cassettes"))
      (is (contains? (set (sb/sync-entries {:source-paths ["src"] :test-paths ["test"]
                                            :sandbox-extra-paths ["cassettes"]}
                                           (.getPath root)))
                     "cassettes")))))

(deftest absolutize-local-roots-test
  (let [proj (.getPath (temp-dir))]
    (testing "relative :local/root (in :deps and alias :extra-deps) becomes absolute"
      (let [in  {:deps {'foo/bar {:local/root "../foo"}
                        'baz/qux {:mvn/version "1.0"}}
                 :aliases {:dev {:extra-deps {'lib/x {:local/root "libs/x"}}}}}
            out (sb/absolutize-local-roots in proj)]
        (is (.isAbsolute (io/file (get-in out [:deps 'foo/bar :local/root]))))
        (is (= (.getCanonicalPath (io/file proj "../foo"))
               (get-in out [:deps 'foo/bar :local/root])))
        (is (= {:mvn/version "1.0"} (get-in out [:deps 'baz/qux]))
            "non-local coordinates are untouched")
        (is (= (.getCanonicalPath (io/file proj "libs/x"))
               (get-in out [:aliases :dev :extra-deps 'lib/x :local/root])))))
    (testing "absolute :local/root is left unchanged"
      (is (= "/abs/foo"
             (get-in (sb/absolutize-local-roots
                      {:deps {'foo/bar {:local/root "/abs/foo"}}} proj)
                     [:deps 'foo/bar :local/root]))))
    (testing "deps with no local roots are returned unchanged (=)"
      (let [in {:deps {'a/b {:mvn/version "1"}} :paths ["src"]}]
        (is (= in (sb/absolutize-local-roots in proj)))))))

(defn- write-fixture-project!
  "Minimal copyable project under a temp root: src/ + deps.edn + heretic.edn."
  [root config]
  (.mkdirs (io/file root "src" "demo"))
  (spit (io/file root "src" "demo" "core.clj") "(ns demo.core)")
  (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
  (spit (io/file root "heretic.edn") (pr-str config))
  root)

(deftest mutate-in-sandbox!-test
  (testing "copies project, runs the (stubbed) child once in the sandbox, returns mutate!'s result-map + :sandbox metadata, wipes when :keep-sandbox false"
    (let [root (temp-dir)
          sandbox-dir (io/file root ".heretic-sandbox")
          cfg {:source-paths ["src"] :sandbox-dir (.getPath sandbox-dir) :keep-sandbox false}
          _ (write-fixture-project! root cfg)
          calls (atom [])
          result (with-redefs [heretic.sandbox/run-process!
                               (fn [_cmd dir] (swap! calls conj dir) 0)
                               heretic.sandbox/read-summary
                               (fn [_ _] {:total 3 :killed 2 :survived 1 :mutation-score 0.667})]
                   (sb/mutate-in-sandbox! cfg :project-root (.getPath root)))]
      (is (= 1 (count @calls)) "child launched exactly once")
      (is (= (.getPath sandbox-dir) (first @calls)) "child runs with the sandbox as cwd")
      (is (= 2 (:killed result)))
      (is (= 0.667 (:mutation-score result)) "returns mutate!'s result-map shape")
      (is (= 0 (get-in result [:sandbox :exit])))
      (is (false? (get-in result [:sandbox :kept?])))
      (is (not (.exists sandbox-dir)) ":keep-sandbox false -> sandbox wiped after the run")))
  (testing ":error branch when the child produces no summary"
    (let [root (temp-dir)
          cfg {:source-paths ["src"] :sandbox-dir (.getPath (io/file root ".heretic-sandbox")) :keep-sandbox false}
          _ (write-fixture-project! root cfg)
          result (with-redefs [heretic.sandbox/run-process! (fn [_ _] 1)
                               heretic.sandbox/read-summary (fn [_ _] nil)]
                   (sb/mutate-in-sandbox! cfg :project-root (.getPath root)))]
      (is (string? (:error result)))
      (is (= 1 (get-in result [:sandbox :exit]))))))

(deftest clean-sandbox!-test
  (testing "deletes the resolved sandbox dir, returns its path, idempotent when already absent"
    (let [root (temp-dir)
          sandbox-dir (io/file root ".heretic-sandbox")
          cfg {:sandbox-dir (.getPath sandbox-dir)}]
      (.mkdirs (io/file sandbox-dir "src"))
      (spit (io/file sandbox-dir "src" "x.clj") "x")
      (is (.exists sandbox-dir))
      (is (= (.getPath sandbox-dir) (sb/clean-sandbox! cfg)) "returns the sandbox path")
      (is (not (.exists sandbox-dir)) "sandbox deleted")
      (is (= (.getPath sandbox-dir) (sb/clean-sandbox! cfg)) "idempotent — no throw when already gone"))))

(deftest sync-tree-preserves-nested-paths-test
  (testing "a nested classpath root (src/main) lands at sandbox/src/main, not sandbox/main"
    (let [root (temp-dir)
          sandbox-dir (io/file root ".heretic-sandbox")
          cfg {:source-paths ["src/main"] :test-paths ["src/test"]
               :sandbox-dir (.getPath sandbox-dir) :keep-sandbox true}]
      (.mkdirs (io/file root "src" "main" "demo"))
      (spit (io/file root "src" "main" "demo" "core.clj") "(ns demo.core)")
      (.mkdirs (io/file root "src" "test"))
      (spit (io/file root "deps.edn") (pr-str {:paths ["src/main"]}))
      (spit (io/file root "heretic.edn") (pr-str cfg))
      (try
        (with-redefs [heretic.sandbox/run-process! (fn [_ _] 0)
                      heretic.sandbox/read-summary (fn [_ _] {:total 0 :killed 0})]
          (sb/mutate-in-sandbox! cfg :project-root (.getPath root)))
        (is (.exists (io/file sandbox-dir "src" "main" "demo" "core.clj"))
            "nested src/main preserved")
        (is (not (.exists (io/file sandbox-dir "main")))
            "not flattened to sandbox/main")
        (finally (sb/clean-sandbox! cfg))))))

(deftest read-summary-test
  (testing "returns the :summary map mutate! persisted"
    (let [dir (temp-dir)
          hd (io/file dir ".heretic")]
      (.mkdirs hd)
      (spit (io/file hd "mutation-results.edn")
            (pr-str {:survivors []
                     :summary {:total 3 :killed 2 :survived 1 :mutation-score 0.667}}))
      (is (= {:total 3 :killed 2 :survived 1 :mutation-score 0.667}
             (sb/read-summary (.getPath dir) {:heretic-dir ".heretic"})))))
  (testing "nil when no results file"
    (is (nil? (sb/read-summary (.getPath (temp-dir)) {:heretic-dir ".heretic"})))))
