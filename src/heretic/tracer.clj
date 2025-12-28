(ns heretic.tracer
  "ClojureStorm instrumentation callbacks for coverage collection.

   This namespace integrates with ClojureStorm's Emitter and Tracer APIs
   to receive callbacks whenever instrumented code executes. Coverage
   data is accumulated per-test and can be retrieved after each test runs.

   ClojureStorm coordinate format:
   - Coordinates are passed as strings: \"3,2,1\" representing path into AST
   - Unordered forms (maps/sets): hash-based strings like \"K12345\" or \"V12345\"
   - Empty string \"\" for function return coordinates

   Usage:
   1. Call (init!) to set up ClojureStorm callbacks
   2. Call (reset-current-coverage!) before each test
   3. Run the test (coverage is recorded automatically)
   4. Call (get-current-coverage) to retrieve recorded hits"
  (:require [clojure.string :as str]))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private current-coverage
  "Atom of {form-id #{coords}} for the currently running test.
   Reset between tests via reset-current-coverage!"
  (atom {}))

(def ^:private initialized?
  "Track whether ClojureStorm callbacks have been initialized"
  (atom false))

;; =============================================================================
;; Coverage Recording
;; =============================================================================

(defn- record-hit!
  "Record a coverage hit for the current test.
   Called by ClojureStorm for each expression evaluation.
   Coordinates are already strings from ClojureStorm (e.g., \"3,2,1\")."
  [form-id coord]
  (swap! current-coverage
         update form-id
         (fnil conj #{})
         coord))

;; =============================================================================
;; Public API
;; =============================================================================

(defn init!
  "Initialize ClojureStorm instrumentation with Heretic's callbacks.

   Sets up the tracer to call record-hit! for each expression evaluation.
   This enables coverage collection without runtime overhead when not collecting.

   Requires ClojureStorm JVM args:
   -Dclojure.storm.instrumentEnable=true

   Returns true if initialization succeeded, false if already initialized."
  []
  ;; TODO: Implement ClojureStorm initialization
  ;; This requires the ClojureStorm JAR to be on the classpath
  ;;
  ;; (Emitter/setInstrumentationEnable true)
  ;; (Emitter/setFnCallInstrumentationEnable false)
  ;; (Emitter/setFnReturnInstrumentationEnable true)
  ;; (Emitter/setExprInstrumentationEnable true)
  ;; (Emitter/setBindInstrumentationEnable false)
  ;;
  ;; (Tracer/setTraceFnsCallbacks
  ;;  {:trace-expr-fn      (fn [_ _ coord form-id] (record-hit! form-id coord))
  ;;   :trace-fn-return-fn (fn [_ _ coord form-id] (record-hit! form-id coord))
  ;;   :trace-fn-unwind-fn (fn [_ _ coord form-id] (record-hit! form-id coord))})
  ;;
  (if @initialized?
    false
    (do
      (reset! initialized? true)
      ;; TODO: Actual ClojureStorm initialization
      (throw (ex-info "ClojureStorm initialization not yet implemented"
                      {:hint "Requires ClojureStorm on classpath with :clojurestorm alias"})))))

(defn initialized?*
  "Check if tracer has been initialized."
  []
  @initialized?)

(defn get-current-coverage
  "Return coverage accumulated for the current test.

   Returns map of {form-id #{coord-strings}} where:
   - form-id is the ClojureStorm form identifier
   - coord-strings are stringified coordinates like \"3,2,1\""
  []
  @current-coverage)

(defn reset-current-coverage!
  "Clear coverage for the next test.
   Call this before running each test."
  []
  (reset! current-coverage {}))

(defn shutdown!
  "Disable coverage collection.
   Call when done collecting to reduce overhead."
  []
  ;; TODO: Disable ClojureStorm callbacks
  ;; (Emitter/setInstrumentationEnable false)
  (reset! initialized? false))
