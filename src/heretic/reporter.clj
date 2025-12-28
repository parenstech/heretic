(ns heretic.reporter
  "Terminal output for mutation testing results.

   This module provides functions to calculate mutation scores and
   display mutation testing results in the terminal with ANSI colors.

   Key functions:
   - `mutation-score` - Calculate killed/(killed+survived) ratio
   - `print-summary` - Print summary stats to terminal
   - `print-survivors` - List surviving mutations with details

   No dependencies on other heretic modules.")

;; =============================================================================
;; ANSI Color Codes
;; =============================================================================

(def ^:private ansi-reset "\u001b[0m")
(def ^:private ansi-bold "\u001b[1m")
(def ^:private ansi-green "\u001b[32m")
(def ^:private ansi-red "\u001b[31m")
(def ^:private ansi-yellow "\u001b[33m")
(def ^:private ansi-cyan "\u001b[36m")

(defn- colorize
  "Wrap text in ANSI color codes."
  [color text]
  (str color text ansi-reset))

(defn- green [text] (colorize ansi-green text))
(defn- red [text] (colorize ansi-red text))
(defn- yellow [text] (colorize ansi-yellow text))
(defn- cyan [text] (colorize ansi-cyan text))
(defn- bold [text] (str ansi-bold text ansi-reset))

;; =============================================================================
;; Statistics Calculation
;; =============================================================================

(defn count-by-status
  "Count mutations by status category.

   Returns a map with counts for each status:
   {:killed n :survived n :no-coverage n :timeout n :error n}"
  [results]
  (reduce
   (fn [acc {:keys [status]}]
     (update acc status (fnil inc 0)))
   {:killed 0 :survived 0 :no-coverage 0 :timeout 0 :error 0}
   results))

(defn mutation-score
  "Calculate the mutation score as a ratio of killed to killable mutations.

   Score = killed / (killed + survived)

   Returns a float between 0.0 and 1.0, or nil if there are no killable mutations.

   Note: :no-coverage, :timeout, and :error mutations are excluded from
   the score calculation as they don't represent true test effectiveness."
  [results]
  (let [counts (count-by-status results)
        killed (:killed counts)
        survived (:survived counts)
        total (+ killed survived)]
    (when (pos? total)
      (/ (double killed) total))))

(defn- percentage
  "Convert a ratio to a percentage with specified decimal places."
  ([n total] (percentage n total 0))
  ([n total decimals]
   (if (zero? total)
     0.0
     (let [pct (* 100.0 (/ n total))]
       (if (zero? decimals)
         (Math/round pct)
         (let [factor (Math/pow 10 decimals)]
           (/ (Math/round (* pct factor)) factor)))))))

;; =============================================================================
;; Filtering Functions
;; =============================================================================

(defn survivors
  "Filter results to only surviving mutations."
  [results]
  (filter #(= :survived (:status %)) results))

(defn killed
  "Filter results to only killed mutations."
  [results]
  (filter #(= :killed (:status %)) results))

(defn no-coverage
  "Filter results to mutations with no test coverage."
  [results]
  (filter #(= :no-coverage (:status %)) results))

(defn timeouts
  "Filter results to mutations that timed out."
  [results]
  (filter #(= :timeout (:status %)) results))

(defn errors
  "Filter results to mutations that had errors."
  [results]
  (filter #(= :error (:status %)) results))

;; =============================================================================
;; Formatting Functions
;; =============================================================================

(defn- format-mutation-location
  "Format mutation location as file:line."
  [{:keys [file line]}]
  (str file ":" line))

(defn- format-mutation-change
  "Format the mutation change (original -> replacement)."
  [{:keys [original replacement]}]
  (str original " -> " replacement))

(defn- format-tests-run
  "Format the set of tests that were run."
  [tests-run]
  (if (empty? tests-run)
    "(no tests)"
    (->> tests-run
         (map str)
         (sort)
         (clojure.string/join ", "))))

;; =============================================================================
;; Terminal Output Functions
;; =============================================================================

(defn print-summary
  "Print summary statistics to terminal.

   Displays:
   - Total mutation count
   - Killed count and percentage (green)
   - Survived count and percentage (red)
   - No-coverage/timeout/error counts (yellow)
   - Overall mutation score"
  [results]
  (let [counts (count-by-status results)
        total (count results)
        {:keys [killed survived no-coverage timeout error]} counts
        score (mutation-score results)
        score-pct (when score (percentage (* score 100) 100 1))]

    (println)
    (println (bold "Mutation Testing Results"))
    (println "========================")
    (println)
    (println (str "Total: " total " mutations"))
    (println)

    ;; Killed (green)
    (println (green (format "  Killed:      %3d (%d%%)"
                            killed
                            (percentage killed total))))

    ;; Survived (red)
    (println (red (format "  Survived:    %3d (%d%%)"
                          survived
                          (percentage survived total))))

    ;; No coverage (yellow)
    (when (pos? no-coverage)
      (println (yellow (format "  No Coverage: %3d (%d%%)"
                               no-coverage
                               (percentage no-coverage total)))))

    ;; Timeout (yellow)
    (when (pos? timeout)
      (println (yellow (format "  Timeout:     %3d (%d%%)"
                               timeout
                               (percentage timeout total)))))

    ;; Error (yellow)
    (when (pos? error)
      (println (yellow (format "  Error:       %3d (%d%%)"
                               error
                               (percentage error total)))))

    (println)

    ;; Score
    (if score
      (let [score-str (format "Score: %.1f%%" (* score 100))]
        (println (bold (if (>= score 0.8)
                         (green score-str)
                         (if (>= score 0.6)
                           (yellow score-str)
                           (red score-str))))))
      (println (yellow "Score: N/A (no killable mutations)")))

    (println)))

(defn print-survivors
  "Print details of surviving mutations.

   For each survivor, displays:
   - Index number
   - File and line location
   - Mutation operator and change
   - Tests that were run but did not kill it"
  [results]
  (let [survivor-list (survivors results)]
    (when (seq survivor-list)
      (println)
      (println (bold (red "Surviving Mutations:")))
      (println "-------------------")
      (doseq [[idx result] (map-indexed vector survivor-list)]
        (let [{:keys [mutation tests-run]} result
              {:keys [operator]} mutation]
          (println)
          (println (format "%d. %s - %s (%s)"
                           (inc idx)
                           (cyan (format-mutation-location mutation))
                           (name operator)
                           (format-mutation-change mutation)))
          (println (format "   Not killed by: %s"
                           (format-tests-run tests-run)))))
      (println))))

(defn print-no-coverage
  "Print details of mutations with no test coverage.

   These are mutations that could not be tested because no tests
   exercise the code containing the mutation."
  [results]
  (let [no-cov-list (no-coverage results)]
    (when (seq no-cov-list)
      (println)
      (println (bold (yellow "Mutations Without Coverage:")))
      (println "---------------------------")
      (doseq [[idx result] (map-indexed vector no-cov-list)]
        (let [{:keys [mutation]} result
              {:keys [operator]} mutation]
          (println (format "%d. %s - %s (%s)"
                           (inc idx)
                           (cyan (format-mutation-location mutation))
                           (name operator)
                           (format-mutation-change mutation)))))
      (println))))

(defn print-report
  "Print full mutation testing report.

   Combines summary, survivors, and no-coverage sections."
  [results]
  (print-summary results)
  (print-survivors results)
  (print-no-coverage results))

;; =============================================================================
;; Data Export Functions
;; =============================================================================

(defn summary-data
  "Return summary statistics as a data structure.

   Useful for programmatic access or alternative output formats."
  [results]
  (let [counts (count-by-status results)
        total (count results)
        score (mutation-score results)]
    {:total total
     :counts counts
     :score score
     :score-percentage (when score (* score 100))}))
