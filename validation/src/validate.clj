(ns validate
  "Validation script for ClojureStorm API assumptions.
   Run with: clj -A:storm -M -m validate"
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test :as t])
  (:import [clojure.storm Emitter Tracer FormRegistry]))

;;; Coverage accumulator (similar to clofidence approach)

(def ^:dynamic *captured-coords*
  "Captured coordinates during test execution.
   Structure: {:form-id -> {:coords [...all coord values...]
                            :coord-types [...type of each coord...]}}"
  (atom {}))

(defn record-coord!
  "Record a coordinate hit with type information"
  [form-id coord source]
  (swap! *captured-coords* update form-id
         (fn [m]
           (-> (or m {:coords [] :coord-types [] :sources []})
               (update :coords conj coord)
               (update :coord-types conj (type coord))
               (update :sources conj source)))))

;;; ClojureStorm Setup

(defn setup-instrumentation!
  "Configure ClojureStorm callbacks"
  []
  (println "\n=== Setting up ClojureStorm instrumentation ===")

  ;; Enable instrumentation
  (Emitter/setInstrumentationEnable true)
  (Emitter/setFnCallInstrumentationEnable true)
  (Emitter/setFnReturnInstrumentationEnable true)
  (Emitter/setExprInstrumentationEnable true)
  (Emitter/setBindInstrumentationEnable false)

  ;; Set up tracer callbacks
  (Tracer/setTraceFnsCallbacks
   {:trace-expr-fn
    (fn [_result _throwable coord form-id]
      (record-coord! form-id coord :trace-expr))

    :trace-fn-return-fn
    (fn [_result _throwable coord form-id]
      (record-coord! form-id coord :trace-fn-return))

    :trace-fn-unwind-fn
    (fn [_result _throwable coord form-id]
      (record-coord! form-id coord :trace-fn-unwind))})

  (println "Instrumentation callbacks configured"))

;;; FormRegistry Inspection

(defn inspect-form-registry
  "Inspect what FormRegistry.getAllForms returns"
  []
  (println "\n=== FormRegistry Inspection ===")

  (let [all-forms (FormRegistry/getAllForms)]
    (println "getAllForms return type:" (type all-forms))
    (println "getAllForms count:" (count all-forms))

    (when (seq all-forms)
      (println "\n--- First few forms structure ---")
      (doseq [[idx form-entry] (take 3 (map-indexed vector all-forms))]
        (println "\n--- Form" idx "---")
        (println "Entry type:" (type form-entry))

        (cond
          ;; If it's a MapEntry (from a map)
          (instance? clojure.lang.MapEntry form-entry)
          (let [[form-id form-data] form-entry]
            (println "Form ID (key):" form-id "type:" (type form-id))
            (println "Form data (val) type:" (type form-data))
            (when (map? form-data)
              (println "Form data keys:" (keys form-data))
              (doseq [[k v] form-data]
                (println "  " k "=>" (if (coll? v)
                                       (str (type v) " count:" (count v))
                                       v)))))

          ;; If it's a map directly
          (map? form-entry)
          (do
            (println "Form entry keys:" (keys form-entry))
            (doseq [[k v] form-entry]
              (println "  " k "=>"
                       (cond
                         (and (coll? v) (not (string? v)))
                         (str (type v) " " (if (< (count (str v)) 100) v "..."))

                         :else v))))

          ;; Other
          :else
          (println "Unknown structure:" (pr-str form-entry)))))

    ;; Find sample.core forms specifically
    (println "\n--- sample.core forms ---")
    (let [sample-forms (filter
                        (fn [entry]
                          (let [form-data (if (instance? clojure.lang.MapEntry entry)
                                            (val entry)
                                            entry)
                                ns-str (cond
                                         (map? form-data) (or (:form/ns form-data)
                                                              (get form-data "form/ns"))
                                         :else nil)]
                            (when ns-str
                              (str/starts-with? (str ns-str) "sample"))))
                        all-forms)]
      (println "Found" (count sample-forms) "forms in sample namespace")

      (when (seq sample-forms)
        (println "\n--- Detailed sample forms with emitted-coords check ---")
        (doseq [entry (take 5 sample-forms)]
          (let [form-data (if (instance? clojure.lang.MapEntry entry)
                            (val entry)
                            entry)]
            (println "\n  Form:" (:form/id form-data))
            (println "    NS:" (:form/ns form-data))
            (println "    Def-kind:" (:form/def-kind form-data))
            (println "    Line:" (:form/line form-data))
            ;; Check for emitted-coords in various places
            (println "    Has :form/emitted-coords key?" (contains? form-data :form/emitted-coords))
            (println "    Form meta keys:" (keys (meta (:form/form form-data))))
            (when-let [ec (-> form-data :form/form meta :clojure.storm/emitted-coords)]
              (println "    Emitted coords from meta:" ec))
            (println "    Form (first 60 chars):" (subs (pr-str (:form/form form-data)) 0
                                                         (min 60 (count (pr-str (:form/form form-data))))))))))))

;;; Coordinate Format Analysis

(defn analyze-captured-coords
  "Analyze the coordinates captured during test execution"
  []
  (println "\n=== Coordinate Format Analysis ===")
  (let [all-coords @*captured-coords*]
    (println "Captured coords for" (count all-coords) "form IDs")
    (when (seq all-coords)
      (let [all-types (set (mapcat :coord-types (vals all-coords)))
            all-coord-list (mapcat :coords (vals all-coords))
            unique-coords (set all-coord-list)
            empty-coords (filter #(= "" %) unique-coords)
            single-index-coords (filter #(re-matches #"^\d+$" %) unique-coords)
            multi-index-coords (filter #(re-matches #"^\d+(,\d+)+$" %) unique-coords)
            hash-coords-list (filter #(or (re-find #"K\d+" %) (re-find #"V\d+" %)) unique-coords)
            other-coords (remove #(or (= "" %)
                                      (re-matches #"^\d+$" %)
                                      (re-matches #"^\d+(,\d+)+$" %)
                                      (re-find #"K\d+" %)
                                      (re-find #"V\d+" %))
                                 unique-coords)]
        (println "\nCoordinate types seen:" all-types)
        (println "Total coordinate hits:" (count all-coord-list))
        (println "Unique coordinates:" (count unique-coords))
        (println "\n--- Coordinate Structure Analysis ---")
        (println "Empty string coords (fn returns):" (count empty-coords))
        (println "Single index coords (e.g., '3'):" (count single-index-coords))
        (println "Multi-index coords (e.g., '3,2,1'):" (count multi-index-coords))
        (println "Hash-based coords (K/V prefix):" (count hash-coords-list))
        (println "Other format coords:" (count other-coords))
        (when (seq other-coords)
          (println "  Other coords:" (take 5 other-coords)))
        (println "\n--- Sample coordinates by depth ---")
        (let [by-depth (group-by #(if (= "" %) 0 (inc (count (filter #{\,} %)))) unique-coords)]
          (doseq [[depth coords] (sort-by key by-depth)]
            (println (format "  Depth %d: %d coords, samples: %s"
                             depth (count coords) (str/join ", " (take 3 coords))))))
        (println "\n--- Looking for hash-based coordinates ---")
        (if (seq hash-coords-list)
          (do
            (println "Found" (count hash-coords-list) "hash-based coordinates:")
            (doseq [c (take 10 hash-coords-list)]
              (println "  " (pr-str c))))
          (println "No hash-based coordinates found"))
        (println "\n--- Sample form-id -> coords mapping ---")
        (let [[form-id data] (first all-coords)]
          (println "Form ID:" form-id)
          (println "Unique coords for this form:" (count (set (:coords data))))
          (println "Coords:" (take 10 (set (:coords data)))))
        (println "\n--- Hash coordinate format analysis ---")
        (doseq [c (take 10 hash-coords-list)]
          (let [parts (str/split c #",")]
            (println (format "  %s -> parts: %s" c (pr-str parts)))
            (doseq [[idx p] (map-indexed vector parts)]
              (println (format "    [%d] %s (type: %s)"
                               idx p
                               (cond
                                 (re-matches #"^\d+$" p) "index"
                                 (str/starts-with? p "K") "key-hash"
                                 (str/starts-with? p "V") "value-hash"
                                 :else "unknown"))))))))))

;;; Run Tests and Capture Coverage

(defn run-tests-with-coverage!
  "Load test namespace and run tests while capturing coverage"
  []
  (println "\n=== Running Tests with Coverage ===")

  ;; Reset captured coords
  (reset! *captured-coords* {})

  ;; Load the test namespace (this will instrument it due to JVM opts)
  (println "Loading sample.core-test...")
  (require 'sample.core-test :reload)

  ;; Run the tests
  (println "Running tests...")
  (let [result (t/run-tests 'sample.core-test)]
    (println "\nTest results:" result)))

;;; Main entry point

(defn test-single-form-lookup
  "Test FormRegistry/getForm with a specific form ID"
  []
  (println "\n=== Testing FormRegistry/getForm ===")
  (let [all-forms (FormRegistry/getAllForms)
        sample-form (first (filter #(= "sample.core" (:form/ns %)) all-forms))]
    (when sample-form
      (let [form-id (:form/id sample-form)]
        (println "Looking up form ID:" form-id)
        (let [looked-up (FormRegistry/getForm form-id)]
          (println "getForm result type:" (type looked-up))
          (println "getForm result keys:" (when (map? looked-up) (keys looked-up)))
          (println "Same as from getAllForms?" (= sample-form looked-up)))))))

(defn -main
  "Run the validation"
  [& _args]
  (println "============================================")
  (println "ClojureStorm API Validation")
  (println "============================================")

  ;; 1. Setup instrumentation
  (setup-instrumentation!)

  ;; 2. Inspect FormRegistry before loading anything
  (println "\n>>> FormRegistry BEFORE loading sample code <<<")
  (inspect-form-registry)

  ;; 3. Load sample code (will be instrumented)
  (println "\n=== Loading sample.core namespace ===")
  (require 'sample.core :reload)

  ;; 4. Inspect FormRegistry after loading
  (println "\n>>> FormRegistry AFTER loading sample code <<<")
  (inspect-form-registry)

  ;; 5. Test single form lookup
  (test-single-form-lookup)

  ;; 6. Run tests and capture coordinates
  (run-tests-with-coverage!)

  ;; 7. Analyze captured coordinates
  (analyze-captured-coords)

  (println "\n============================================")
  (println "Validation Complete")
  (println "============================================")

  ;; Exit
  (System/exit 0))
