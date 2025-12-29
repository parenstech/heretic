(ns heretic.timing
  "Test execution timing tracking for optimized test ordering.

   Tracks the execution time of each test and uses historical data
   to order tests by speed (fastest first). This maximizes early
   termination benefit in mutation testing - if a fast test kills
   a mutation, we don't need to run slower tests.

   Storage layout:
   .heretic/
   ├── timing.edn    # Test execution times {test-sym -> {:duration-ms n :runs n}}

   Main API:
   - `load-timing` - Load timing data from disk
   - `save-timing!` - Save timing data to disk
   - `record-timing` - Update timing data with new measurements
   - `order-tests-by-speed` - Order tests by historical execution time"
  (:require [heretic.persistence :as persist]
            [clojure.java.io :as io]))

;; =============================================================================
;; Timing Data Storage
;; =============================================================================

(defn timing-file-path
  "Get path for the timing data file."
  [heretic-dir]
  (io/file heretic-dir "timing.edn"))

(defn load-timing
  "Load timing data from disk.

   Returns map of test-sym -> {:duration-ms n :runs n}
   where duration-ms is the average duration and runs is the count.
   Returns nil if no timing file exists."
  [heretic-dir]
  (persist/load-edn (timing-file-path heretic-dir)))

(defn save-timing!
  "Save timing data to disk atomically."
  [heretic-dir timing-data]
  (persist/save-edn! (timing-file-path heretic-dir) timing-data))

;; =============================================================================
;; Timing Data Updates
;; =============================================================================

(defn- update-test-timing
  "Update timing for a single test with a new measurement.

   Uses exponential moving average to smooth out variance while
   giving more weight to recent measurements. This helps adapt
   to tests that change in execution time over time."
  [existing-entry new-duration-ms]
  (if existing-entry
    (let [{:keys [duration-ms runs]} existing-entry
          ;; Use exponential moving average with alpha=0.3
          ;; This gives ~86% weight to last 5 measurements
          alpha 0.3
          new-avg (+ (* alpha new-duration-ms)
                     (* (- 1 alpha) duration-ms))]
      {:duration-ms (long new-avg)
       :runs (inc runs)})
    ;; First measurement
    {:duration-ms new-duration-ms
     :runs 1}))

(defn record-timing
  "Update timing data with new measurements from a test run.

   Arguments:
   - existing-timing: Current timing data (map of test-sym -> entry)
   - test-durations: Map of test-sym -> duration-ms from recent run

   Returns updated timing data."
  [existing-timing test-durations]
  (reduce-kv
   (fn [acc test-sym duration-ms]
     (update acc test-sym update-test-timing duration-ms))
   (or existing-timing {})
   test-durations))

(defn record-timing!
  "Update and persist timing data with new measurements.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - test-durations: Map of test-sym -> duration-ms from recent run

   Returns updated timing data."
  [heretic-dir test-durations]
  (when (seq test-durations)
    (let [existing (or (load-timing heretic-dir) {})
          updated (record-timing existing test-durations)]
      (save-timing! heretic-dir updated)
      updated)))

;; =============================================================================
;; Test Ordering
;; =============================================================================

(defn order-tests-by-speed
  "Order a collection of test symbols by historical execution time.

   Tests with timing data are sorted fastest-first.
   Tests without timing data are placed at the end in arbitrary order.

   Arguments:
   - test-syms: Collection of test symbols to order
   - timing-data: Timing data map (or nil if no data exists)

   Returns vector of test symbols ordered by speed (fastest first)."
  [test-syms timing-data]
  (if (or (nil? timing-data) (empty? timing-data))
    ;; No timing data - return in original order (as vector for consistency)
    (vec test-syms)
    (let [test-syms-set (set test-syms)
          ;; Separate tests with and without timing data
          {with-timing true without-timing false}
          (group-by #(contains? timing-data %) test-syms-set)
          ;; Sort tests with timing by duration (fastest first)
          sorted-with-timing (sort-by #(get-in timing-data [% :duration-ms] Long/MAX_VALUE)
                                      with-timing)]
      ;; Combine: timed tests first (sorted), untimed tests last
      (vec (concat sorted-with-timing without-timing)))))

(defn get-estimated-duration
  "Get estimated duration for a test, or nil if unknown.

   Arguments:
   - timing-data: Timing data map
   - test-sym: Test symbol

   Returns duration in milliseconds or nil."
  [timing-data test-sym]
  (get-in timing-data [test-sym :duration-ms]))

;; =============================================================================
;; Dynamic Timeout Calculation
;; =============================================================================

(defn calculate-dynamic-timeout
  "Calculate dynamic timeout based on historical test duration.

   Uses the formula: timeout = max(base-timeout, duration * multiplier)

   Arguments:
   - timing-data: Timing data map (or nil)
   - test-sym: Test symbol
   - opts: Options map:
     - :base-timeout-ms - Minimum timeout (default 1000ms)
     - :multiplier - Multiplier for historical duration (default 3.0)
     - :max-timeout-ms - Maximum timeout cap (default 30000ms)

   Returns timeout in milliseconds."
  [timing-data test-sym opts]
  (let [{:keys [base-timeout-ms multiplier max-timeout-ms]
         :or {base-timeout-ms 1000
              multiplier 3.0
              max-timeout-ms 30000}} opts
        estimated (get-estimated-duration timing-data test-sym)]
    (if estimated
      ;; Use historical data: timeout = duration * multiplier, clamped
      (-> (* estimated multiplier)
          (max base-timeout-ms)
          (min max-timeout-ms)
          long)
      ;; No data, use max timeout (conservative)
      max-timeout-ms)))

(defn estimate-total-duration
  "Estimate total duration for running a set of tests.

   Arguments:
   - timing-data: Timing data map (or nil)
   - test-syms: Collection of test symbols
   - default-per-test: Default duration for tests without timing data (default 1000ms)

   Returns estimated total milliseconds."
  [timing-data test-syms default-per-test]
  (let [default (or default-per-test 1000)]
    (reduce
     (fn [total test-sym]
       (+ total (or (get-estimated-duration timing-data test-sym) default)))
     0
     test-syms)))
