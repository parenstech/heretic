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
   4. Call (get-current-coverage) to retrieve recorded hits

   Requires ClojureStorm JVM args:
   -Dclojure.storm.instrumentEnable=true
   -Dclojure.storm.instrumentOnlyPrefixes=<your-app-prefix>"
  (:import [clojure.storm Emitter Tracer]))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private current-coverage
  "Atom of {form-id #{coords}} for the currently running test.
   Reset between tests via reset-current-coverage!

   Structure: {<form-id Long> #{<coord String> ...}}

   Example:
   {12345 #{\"3\" \"3,1\" \"3,2\"}
    12346 #{\"\" \"1\" \"2,1\"}}"
  (atom {}))

(def ^:private initialized?
  "Track whether ClojureStorm callbacks have been initialized."
  (atom false))

;; =============================================================================
;; Coverage Recording
;; =============================================================================

(defn record-hit!
  "Record a coverage hit for the current test.

   Called by ClojureStorm tracer callbacks for each expression evaluation.
   Coordinates are already strings from ClojureStorm (e.g., \"3,2,1\" or
   \"\" for function return).

   Parameters:
   - form-id: Long identifier for the form (from ClojureStorm FormRegistry)
   - coord: String coordinate within the form (e.g., \"3,2,1\", \"K12345\", \"\")"
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

   Configures ClojureStorm Emitter settings:
   - Enable instrumentation globally
   - Disable function call tracing (not needed for coverage)
   - Enable function return tracing (captures fn exit points)
   - Enable expression tracing (captures all subexpressions)
   - Disable binding tracing (not needed for coverage)

   Sets up Tracer callbacks:
   - :trace-expr-fn - Called for each expression evaluation
   - :trace-fn-return-fn - Called when a function returns normally
   - :trace-fn-unwind-fn - Called when a function unwinds due to exception

   Callback signature: (fn [result throwable coord form-id] ...)
   - result: Expression value (nil for unwind)
   - throwable: Exception if unwinding, nil otherwise
   - coord: String coordinate (e.g., \"3,2,1\")
   - form-id: Long form identifier

   Returns true if initialization succeeded, false if already initialized.

   Note: Requires ClojureStorm on classpath. Start JVM with:
   -Dclojure.storm.instrumentEnable=true"
  []
  (if @initialized?
    false
    (do
      ;; Configure what gets instrumented
      (Emitter/setInstrumentationEnable true)
      (Emitter/setFnCallInstrumentationEnable false)
      (Emitter/setFnReturnInstrumentationEnable true)
      (Emitter/setExprInstrumentationEnable true)
      (Emitter/setBindInstrumentationEnable false)

      ;; Set up tracer callbacks to record coverage hits
      ;; Callback signature: (fn [result throwable coord form-id] ...)
      (Tracer/setTraceFnsCallbacks
       {:trace-expr-fn      (fn [_result _throwable coord form-id]
                              (record-hit! form-id coord))
        :trace-fn-return-fn (fn [_result _throwable coord form-id]
                              (record-hit! form-id coord))
        :trace-fn-unwind-fn (fn [_result _throwable coord form-id]
                              (record-hit! form-id coord))})

      (reset! initialized? true)
      true)))

(defn initialized?*
  "Check if tracer has been initialized.

   Returns true if init! has been called successfully."
  []
  @initialized?)

(defn get-current-coverage
  "Return coverage accumulated for the current test.

   Returns map of {form-id #{coord-strings}} where:
   - form-id: Long ClojureStorm form identifier
   - coord-strings: Set of string coordinates like \"3,2,1\", \"K12345\", or \"\"

   Example return value:
   {12345 #{\"3\" \"3,1\" \"3,2\"}
    12346 #{\"\" \"1\" \"2,1\"}}"
  []
  @current-coverage)

(defn reset-current-coverage!
  "Clear coverage for the next test.

   Call this before running each test to start with a fresh coverage map.
   Returns nil."
  []
  (reset! current-coverage {})
  nil)

(defn shutdown!
  "Disable coverage collection.

   Disables ClojureStorm instrumentation and resets initialization state.
   Call when done collecting to reduce runtime overhead.

   Returns nil."
  []
  (when @initialized?
    (Emitter/setInstrumentationEnable false))
  (reset! initialized? false)
  nil)
