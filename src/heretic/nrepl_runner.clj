(ns heretic.nrepl-runner
  "Execute tests via nREPL on a remote REPL.

   This module enables 'remote' mutation testing where:
   - The mutation engine runs in one JVM
   - Tests execute in a separate JVM via nREPL

   Primary use case: Heretic testing itself (self-testing).

   When Heretic mutates its own code, it would corrupt itself mid-execution.
   By running tests in a separate worktree REPL via nREPL, we can safely
   mutate the worktree's code while the mutation engine remains stable.

   Performance: ~1-2s per mutation vs ~15-20s for subprocess JVM startup.

   API:
   - `connect!` - Connect to remote REPL
   - `disconnect!` - Close connection
   - `run-tests-remote` - Execute tests via nREPL
   - `reload-namespace` - Reload a namespace in remote REPL
   - `evaluate-mutation-remote` - Full mutation lifecycle with remote tests"
  (:require [nrepl.core :as nrepl]))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn connect!
  "Connect to a remote nREPL server.

   Arguments:
   - port: nREPL port number
   - opts: Optional map with :host (default \"localhost\"), :timeout-ms (default 30000)

   Returns connection map:
   {:conn <nrepl-connection>
    :client <nrepl-client>
    :session <session-id>
    :port <port>
    :host <host>}"
  ([port]
   (connect! port {}))
  ([port opts]
   (let [host (or (:host opts) "localhost")
         timeout-ms (or (:timeout-ms opts) 30000)
         conn (nrepl/connect :port port :host host)
         client (nrepl/client conn timeout-ms)
         ;; Create a session to ensure consistent state across eval calls
         session-response (nrepl/message client {:op "clone"})
         session-id (-> session-response first :new-session)]
     {:conn conn
      :client client
      :session session-id
      :port port
      :host host})))

(defn disconnect!
  "Close an nREPL connection."
  [connection]
  (when-let [conn (:conn connection)]
    (.close conn)))

(defn connected?
  "Check if connection is still valid by sending a simple eval."
  [connection]
  (boolean
   (try
     (when-let [client (:client connection)]
       (let [response (nrepl/message client {:op "eval"
                                             :code "true"
                                             :session (:session connection)})]
         (some? (first (nrepl/response-values response)))))
     (catch Exception _
       false))))

;; =============================================================================
;; Remote Evaluation
;; =============================================================================

(defn- eval-remote
  "Evaluate code in remote REPL and return parsed result.

   Returns {:status :ok/:error :value <result> :error <msg>}"
  [connection code]
  (try
    (let [response (nrepl/message (:client connection)
                                  {:op "eval"
                                   :code code
                                   :session (:session connection)})
          values (nrepl/response-values response)
          errors (keep :err response)]
      (cond
        (seq errors)
        {:status :error :error (apply str errors)}

        (seq values)
        {:status :ok :value (first values)}

        :else
        {:status :ok :value nil}))
    (catch Exception e
      {:status :error :error (.getMessage e)})))

(defn- eval-and-read
  "Evaluate code in remote REPL and read the result as EDN."
  [connection code]
  (let [result (eval-remote connection code)]
    (if (= :ok (:status result))
      (try
        (assoc result :value (read-string (str (:value result))))
        (catch Exception e
          {:status :error :error (str "Failed to parse result: " (.getMessage e))}))
      result)))

;; =============================================================================
;; Namespace Operations
;; =============================================================================

(defn reload-namespace
  "Reload a namespace in the remote REPL.

   Arguments:
   - connection: nREPL connection from connect!
   - ns-sym: Namespace symbol to reload

   Returns {:status :ok/:error :error <msg>}"
  [connection ns-sym]
  (eval-remote connection
               (pr-str `(require '~ns-sym :reload))))

(defn reload-namespaces
  "Reload multiple namespaces in the remote REPL.

   Returns {:status :ok/:error :reloaded [ns-syms] :failed {ns-sym error}}"
  [connection ns-syms]
  (loop [remaining (seq ns-syms)
         reloaded []
         failed {}]
    (if-not remaining
      {:status (if (empty? failed) :ok :partial)
       :reloaded reloaded
       :failed failed}
      (let [ns-sym (first remaining)
            result (reload-namespace connection ns-sym)]
        (if (= :ok (:status result))
          (recur (next remaining) (conj reloaded ns-sym) failed)
          (recur (next remaining) reloaded (assoc failed ns-sym (:error result))))))))

;; =============================================================================
;; Test Execution
;; =============================================================================

(defn setup-test-runner!
  "Set up the test runner function in the remote REPL.

   Defines a `heretic-run-tests!` function that runs clojure.test
   and returns structured results."
  [connection]
  (eval-remote connection
               "(require '[clojure.test :as t])

                (defn heretic-run-tests!
                  \"Run tests and return structured results.\"
                  [test-ns-syms]
                  (let [results (atom {:pass 0 :fail 0 :error 0})]
                    (binding [t/report (fn [m]
                                         (case (:type m)
                                           :pass (swap! results update :pass inc)
                                           :fail (swap! results update :fail inc)
                                           :error (swap! results update :error inc)
                                           nil))]
                      (doseq [ns-sym test-ns-syms]
                        (try
                          (require ns-sym :reload)
                          (t/test-ns ns-sym)
                          (catch Exception e
                            (swap! results update :error inc)))))
                    (let [r @results]
                      (assoc r :passed? (and (zero? (:fail r)) (zero? (:error r)))))))"))

(defn run-tests-remote
  "Execute tests in the remote REPL.

   Arguments:
   - connection: nREPL connection
   - test-ns-syms: Sequence of test namespace symbols to run
   - namespaces-to-reload: Sequence of source namespaces to reload before testing

   Returns:
   {:status :ok/:error
    :results {:pass n :fail n :error n :passed? bool}
    :duration-ms n}"
  ([connection test-ns-syms]
   (run-tests-remote connection test-ns-syms []))
  ([connection test-ns-syms namespaces-to-reload]
   (let [start-time (System/currentTimeMillis)]
     ;; Reload source namespaces first
     (when (seq namespaces-to-reload)
       (reload-namespaces connection namespaces-to-reload))

     ;; Run tests - use string format to avoid namespace qualification from quasiquote
     (let [code (str "(heretic-run-tests! '" (pr-str (vec test-ns-syms)) ")")
           result (eval-and-read connection code)
           duration-ms (- (System/currentTimeMillis) start-time)]
       (if (= :ok (:status result))
         {:status :ok
          :results (:value result)
          :duration-ms duration-ms}
         {:status :error
          :error (:error result)
          :duration-ms duration-ms})))))

;; =============================================================================
;; Mutation Testing Integration
;; =============================================================================

(defn evaluate-mutation-remote
  "Evaluate a single mutation using remote test execution.

   Workflow:
   1. Apply mutation to file (in worktree)
   2. Reload namespaces in remote REPL
   3. Run tests in remote REPL
   4. Revert mutation
   5. Return result

   Arguments:
   - connection: nREPL connection to test REPL
   - mutation: Mutation record with :file
   - test-ns-syms: Test namespaces to run
   - source-ns-syms: Source namespaces to reload
   - apply-fn: Function to apply mutation (returns mutation with :backup)
   - revert-fn: Function to revert mutation

   Returns:
   {:mutation <mutation>
    :status :killed/:survived/:error
    :tests-run <count>
    :duration-ms n}"
  [connection mutation test-ns-syms source-ns-syms apply-fn revert-fn]
  (let [start-time (System/currentTimeMillis)]
    (try
      ;; Apply mutation
      (let [m-with-backup (apply-fn mutation)]
        (try
          ;; Reload and run tests
          (let [test-result (run-tests-remote connection test-ns-syms source-ns-syms)
                duration-ms (- (System/currentTimeMillis) start-time)]
            (if (= :ok (:status test-result))
              {:mutation mutation
               :status (if (get-in test-result [:results :passed?])
                         :survived
                         :killed)
               :tests-run (+ (get-in test-result [:results :pass] 0)
                             (get-in test-result [:results :fail] 0)
                             (get-in test-result [:results :error] 0))
               :duration-ms duration-ms}
              {:mutation mutation
               :status :error
               :error (:error test-result)
               :duration-ms duration-ms}))
          (finally
            ;; Always revert
            (revert-fn m-with-backup))))
      (catch Exception e
        {:mutation mutation
         :status :error
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)}))))

(defn run-self-test
  "Run mutation testing on Heretic itself using a worktree REPL.

   Arguments:
   - worktree-port: nREPL port of the worktree REPL
   - worktree-path: Path to the worktree (for file mutations)
   - mutations: Sequence of mutations to test
   - test-ns-syms: Test namespaces to run
   - source-ns-syms: Source namespaces to reload
   - opts: Options:
     - :apply-fn - Mutation application function
     - :revert-fn - Mutation reversion function
     - :progress-fn - Optional (fn [i total result]) callback

   Returns:
   {:total n
    :killed n
    :survived n
    :error n
    :mutation-score n
    :results [...]}"
  [worktree-port worktree-path mutations test-ns-syms source-ns-syms opts]
  (let [connection (connect! worktree-port)
        apply-fn (:apply-fn opts)
        revert-fn (:revert-fn opts)
        progress-fn (:progress-fn opts (fn [_ _ _]))
        total (count mutations)]
    (try
      ;; Set up test runner in remote REPL
      (setup-test-runner! connection)

      ;; Run each mutation
      (let [results (doall
                     (map-indexed
                      (fn [i m]
                        (let [result (evaluate-mutation-remote
                                      connection m test-ns-syms source-ns-syms
                                      apply-fn revert-fn)]
                          (progress-fn i total result)
                          result))
                      mutations))
            killed (count (filter #(= :killed (:status %)) results))
            survived (count (filter #(= :survived (:status %)) results))
            error (count (filter #(= :error (:status %)) results))
            testable (+ killed survived)]
        {:total total
         :killed killed
         :survived survived
         :error error
         :mutation-score (if (pos? testable)
                           (double (/ killed testable))
                           1.0)
         :results results})
      (finally
        (disconnect! connection)))))
