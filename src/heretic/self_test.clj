(ns heretic.self-test
  "Self-testing infrastructure for Heretic.

   Heretic tests itself using a git worktree + nREPL approach:
   - Worktree: Isolated copy of the codebase that can be safely mutated
   - nREPL: Fast test execution without JVM startup overhead

   Workflow:
   1. bb self-test:setup - Create worktree
   2. Start REPL in worktree: clj -M:dev:test:clojurestorm
   3. bb self-test --port <PORT> - Run mutation testing
   4. bb self-test:cleanup - Remove worktree

   This avoids the self-corruption problem where mutating Heretic's own
   code would break the mutation engine mid-execution."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heretic.mutation-engine :as engine]
            [heretic.nrepl-runner :as nrepl]
            [heretic.operators :as ops]))

;; =============================================================================
;; File Path Mapping
;; =============================================================================

(defn main-to-worktree-path
  "Convert a path from main repo to worktree path."
  [main-path worktree-root]
  (let [main-root (System/getProperty "user.dir")
        relative (if (str/starts-with? main-path main-root)
                   (subs main-path (inc (count main-root)))
                   main-path)]
    (str worktree-root "/" relative)))

(defn worktree-to-main-path
  "Convert a path from worktree to main repo path."
  [worktree-path worktree-root]
  (let [main-root (System/getProperty "user.dir")
        relative (if (str/starts-with? worktree-path worktree-root)
                   (subs worktree-path (inc (count worktree-root)))
                   worktree-path)]
    (str main-root "/" relative)))

;; =============================================================================
;; Namespace Detection
;; =============================================================================

(defn file-to-namespace
  "Convert a file path to a namespace symbol.

   Example: src/heretic/core.clj -> heretic.core"
  [file-path]
  (when-let [match (re-find #"src/(.+)\.clj$" file-path)]
    (-> (second match)
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn namespace-to-test-ns
  "Convert a source namespace to its test namespace.

   Example: heretic.core -> heretic.core-test"
  [ns-sym]
  (symbol (str ns-sym "-test")))

(defn test-ns-exists?
  "Check if a test namespace exists in the worktree."
  [test-ns worktree-root]
  (let [path (str worktree-root "/test/"
                  (-> (str test-ns)
                      (str/replace "." "/")
                      (str/replace "-" "_"))
                  ".clj")]
    (.exists (io/file path))))

;; =============================================================================
;; Mutation Generation for Worktree
;; =============================================================================

(defn generate-worktree-mutations
  "Generate mutations for files in the worktree.

   Arguments:
   - worktree-root: Path to the worktree
   - opts:
     - :file - Specific file to mutate (relative path)
     - :operators - Operators to use (default: :minimal preset)
     - :limit - Max number of mutations

   Returns sequence of mutations with :file pointing to worktree paths."
  [worktree-root opts]
  (let [target-file (:file opts)
        operators (or (:operators opts) (ops/operators-for-preset :minimal))
        limit (:limit opts)

        ;; If specific file, use it; otherwise scan src/heretic/
        source-paths (if target-file
                       [(str worktree-root "/" target-file)]
                       [(str worktree-root "/src/heretic")])

        mutations (if target-file
                    (engine/mutations-for-file (str worktree-root "/" target-file) operators)
                    (engine/generate-mutations source-paths operators))]
    (cond->> mutations
      limit (take limit))))

;; =============================================================================
;; Self-Test Execution
;; =============================================================================

(defn determine-test-namespaces
  "Determine which test namespaces to run for a mutation."
  [mutation worktree-root]
  (let [file (:file mutation)
        source-ns (file-to-namespace file)
        test-ns (when source-ns (namespace-to-test-ns source-ns))]
    (if (and test-ns (test-ns-exists? test-ns worktree-root))
      [test-ns]
      ;; Fallback: run all heretic tests
      ['heretic.operators-test
       'heretic.parser-test
       'heretic.mutation-engine-test
       'heretic.equivalent-test])))

(defn determine-source-namespaces
  "Determine which source namespaces to reload for a mutation."
  [mutation]
  (let [file (:file mutation)
        source-ns (file-to-namespace file)]
    (if source-ns
      [source-ns]
      [])))

(defn run-self-test!
  "Run mutation testing on Heretic itself.

   Arguments:
   - opts:
     - :port - nREPL port of worktree REPL (required)
     - :worktree-path - Path to worktree (required)
     - :file - Specific file to test (optional)
     - :limit - Max mutations to test (optional)
     - :operators - Operators to use (optional, default :minimal)

   Returns summary map with results."
  [{:keys [port worktree-path file limit operators] :as opts}]
  (println "")
  (println "=== Heretic Self-Test ===")
  (println "")
  (println "Worktree:" worktree-path)
  (println "nREPL port:" port)
  (when file (println "Target file:" file))
  (when limit (println "Mutation limit:" limit))
  (println "")

  ;; Connect to worktree REPL
  (println "Connecting to worktree REPL...")
  (let [connection (nrepl/connect! port)]
    (try
      (if-not (nrepl/connected? connection)
        (do
          (println "ERROR: Could not connect to nREPL on port" port)
          {:error "Connection failed"})

        (do
          (println "Connected!")
          (println "")

          ;; Set up test runner
          (println "Setting up test runner...")
          (nrepl/setup-test-runner! connection)

          ;; Generate mutations
          (println "Generating mutations...")
          (let [mutations (generate-worktree-mutations worktree-path opts)
                total (count mutations)]
            (println "Found" total "mutations")
            (println "")

            (if (zero? total)
              (do
                (println "No mutations to test!")
                {:total 0 :killed 0 :survived 0 :error 0 :mutation-score 1.0})

              ;; Run mutation testing
              (do
                (println "Running mutation tests...")
                (println "")

                (let [start-time (System/currentTimeMillis)
                      results (atom [])
                      killed (atom 0)
                      survived (atom 0)
                      errors (atom 0)]

                  ;; Process each mutation
                  (doseq [[i mutation] (map-indexed vector mutations)]
                    (let [test-ns-syms (determine-test-namespaces mutation worktree-path)
                          source-ns-syms (determine-source-namespaces mutation)

                          ;; Apply mutation, run tests, revert
                          result (nrepl/evaluate-mutation-remote
                                  connection
                                  mutation
                                  test-ns-syms
                                  source-ns-syms
                                  engine/apply-mutation!
                                  engine/revert-mutation!)]

                      (swap! results conj result)

                      ;; Update counters
                      (case (:status result)
                        :killed (swap! killed inc)
                        :survived (swap! survived inc)
                        :error (swap! errors inc)
                        nil)

                      ;; Progress output
                      (let [status-char (case (:status result)
                                          :killed "."
                                          :survived "S"
                                          :error "E"
                                          "?")]
                        (print status-char)
                        (flush)
                        (when (zero? (mod (inc i) 50))
                          (println (format " [%d/%d]" (inc i) total))))))

                  (println "")
                  (println "")

                  (let [duration-ms (- (System/currentTimeMillis) start-time)
                        testable (+ @killed @survived)
                        score (if (pos? testable)
                                (double (/ @killed testable))
                                1.0)]

                    ;; Summary
                    (println "=== Results ===")
                    (println "")
                    (println (format "Total mutations: %d" total))
                    (println (format "Killed:          %d" @killed))
                    (println (format "Survived:        %d" @survived))
                    (println (format "Errors:          %d" @errors))
                    (println (format "Mutation score:  %.1f%%" (* 100.0 score)))
                    (println (format "Duration:        %.1fs" (/ duration-ms 1000.0)))
                    (println "")

                    ;; Show survivors
                    (when (pos? @survived)
                      (println "=== Survivors ===")
                      (println "")
                      (doseq [r (filter #(= :survived (:status %)) @results)]
                        (let [m (:mutation r)]
                          (println (format "  %s:%d  %s -> %s"
                                           (:file m)
                                           (:line m)
                                           (:original m)
                                           (:replacement m)))))
                      (println ""))

                    {:total total
                     :killed @killed
                     :survived @survived
                     :error @errors
                     :mutation-score score
                     :duration-ms duration-ms
                     :results @results})))))))

      (finally
        (println "Disconnecting from worktree REPL...")
        (nrepl/disconnect! connection)))))
