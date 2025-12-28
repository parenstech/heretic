(ns heretic.reporter
  "Mutation testing result reporting.

   This module provides functions to calculate mutation scores and
   display mutation testing results in terminal and HTML formats.

   Key functions:
   - `mutation-score` - Calculate killed/(killed+survived) ratio
   - `print-summary` - Print summary stats to terminal
   - `print-survivors` - List surviving mutations with details
   - `generate-html-report` - Generate HTML report with heatmap

   No dependencies on other heretic modules."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]))

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

;; =============================================================================
;; HTML Report Generation
;; =============================================================================

(def ^:private html-css
  "CSS styles for the HTML report."
  "
  :root {
    --color-killed: #28a745;
    --color-survived: #dc3545;
    --color-no-coverage: #ffc107;
    --color-timeout: #6c757d;
    --color-error: #fd7e14;
    --color-bg: #f8f9fa;
    --color-card: #ffffff;
    --color-border: #dee2e6;
  }
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
    line-height: 1.6;
    margin: 0;
    padding: 20px;
    background: var(--color-bg);
    color: #212529;
  }
  .dashboard {
    max-width: 1200px;
    margin: 0 auto;
  }
  h1 { color: #343a40; margin-bottom: 20px; }
  h2 { color: #495057; border-bottom: 2px solid var(--color-border); padding-bottom: 10px; }
  .card {
    background: var(--color-card);
    border-radius: 8px;
    padding: 20px;
    margin-bottom: 20px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  }
  .summary-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 15px;
    margin-bottom: 30px;
  }
  .stat-box {
    padding: 15px;
    border-radius: 6px;
    text-align: center;
  }
  .stat-box .number { font-size: 2em; font-weight: bold; }
  .stat-box .label { font-size: 0.9em; opacity: 0.8; }
  .killed { background: #d4edda; color: #155724; }
  .survived { background: #f8d7da; color: #721c24; }
  .no-coverage { background: #fff3cd; color: #856404; }
  .timeout { background: #e2e3e5; color: #383d41; }
  .error { background: #ffe5d0; color: #8a4000; }
  .score-box {
    font-size: 3em;
    font-weight: bold;
    text-align: center;
    padding: 30px;
    border-radius: 8px;
    margin-bottom: 30px;
  }
  .score-high { background: #d4edda; color: #155724; }
  .score-medium { background: #fff3cd; color: #856404; }
  .score-low { background: #f8d7da; color: #721c24; }
  .heatmap { margin-bottom: 30px; }
  .heatmap-row {
    display: flex;
    align-items: center;
    margin-bottom: 8px;
    padding: 8px 12px;
    background: var(--color-card);
    border-radius: 4px;
  }
  .heatmap-file { flex: 1; font-family: monospace; font-size: 0.9em; }
  .heatmap-bar {
    width: 200px;
    height: 20px;
    background: #e9ecef;
    border-radius: 3px;
    overflow: hidden;
    margin: 0 10px;
  }
  .heatmap-bar-fill {
    height: 100%;
    transition: width 0.3s;
  }
  .heatmap-stats { font-size: 0.85em; color: #6c757d; min-width: 80px; text-align: right; }
  .survivor-list { list-style: none; padding: 0; }
  .survivor-item {
    background: #fff5f5;
    border-left: 4px solid var(--color-survived);
    padding: 12px 15px;
    margin-bottom: 10px;
    border-radius: 0 4px 4px 0;
  }
  .survivor-location { font-family: monospace; font-weight: bold; }
  .survivor-change { color: #6c757d; margin: 5px 0; }
  .survivor-tests { font-size: 0.85em; color: #868e96; }
  .code-snippet {
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 0.85em;
    background: #f8f9fa;
    padding: 3px 6px;
    border-radius: 3px;
  }
  ")

(defn- results-by-file
  "Group results by file for heatmap visualization."
  [results]
  (->> results
       (group-by #(get-in % [:mutation :file]))
       (map (fn [[file rs]]
              (let [killed-count (count (filter #(= :killed (:status %)) rs))
                    survived-count (count (filter #(= :survived (:status %)) rs))
                    testable (+ killed-count survived-count)
                    score (if (pos? testable) (double (/ killed-count testable)) 1.0)]
                {:file file
                 :total (count rs)
                 :killed killed-count
                 :survived survived-count
                 :score score})))
       (sort-by :score)))

(defn- score-class
  "Return CSS class based on mutation score."
  [score]
  (cond
    (>= score 0.8) "score-high"
    (>= score 0.6) "score-medium"
    :else "score-low"))

(defn- html-summary-section
  "Generate HTML for the summary statistics section."
  [results]
  (let [counts (count-by-status results)
        total (count results)
        score (or (mutation-score results) 1.0)]
    [:div.card
     [:h2 "Summary"]
     [:div {:class (str "score-box " (score-class score))}
      (format "%.1f%%" (* score 100))]
     [:div.summary-grid
      [:div.stat-box.killed
       [:div.number (:killed counts)]
       [:div.label "Killed"]]
      [:div.stat-box.survived
       [:div.number (:survived counts)]
       [:div.label "Survived"]]
      [:div.stat-box.no-coverage
       [:div.number (:no-coverage counts)]
       [:div.label "No Coverage"]]
      [:div.stat-box.timeout
       [:div.number (:timeout counts)]
       [:div.label "Timeout"]]
      [:div.stat-box.error
       [:div.number (:error counts)]
       [:div.label "Error"]]]]))

(defn- html-heatmap-section
  "Generate HTML for the file heatmap section."
  [results]
  (let [by-file (results-by-file results)]
    [:div.card.heatmap
     [:h2 "Files by Mutation Score"]
     (for [{:keys [file total killed survived score]} by-file]
       [:div.heatmap-row
        [:span.heatmap-file (or file "(unknown)")]
        [:div.heatmap-bar
         [:div.heatmap-bar-fill
          {:style (format "width: %.0f%%; background: %s;"
                          (* score 100)
                          (cond (>= score 0.8) "#28a745"
                                (>= score 0.6) "#ffc107"
                                :else "#dc3545"))}]]
        [:span.heatmap-stats
         (format "%d/%d (%.0f%%)" killed (+ killed survived) (* score 100))]])]))

(defn- html-survivors-section
  "Generate HTML for the surviving mutations section."
  [results]
  (let [survivor-list (survivors results)]
    (when (seq survivor-list)
      [:div.card
       [:h2 "Surviving Mutations"]
       [:ul.survivor-list
        (for [{:keys [mutation tests-run]} survivor-list]
          (let [{:keys [file line operator original replacement]} mutation]
            [:li.survivor-item
             [:div.survivor-location (str file ":" line)]
             [:div.survivor-change
              [:span.code-snippet original]
              " → "
              [:span.code-snippet replacement]
              " ("
              (name operator)
              ")"]
             [:div.survivor-tests
              "Tests: "
              (if (empty? tests-run)
                "(none)"
                (str/join ", " (map str tests-run)))]]))]])))

(defn generate-html-report
  "Generate HTML mutation testing report.

   Arguments:
   - results: Mutation testing results
   - output-path: Path to write HTML file (e.g., 'target/heretic-report/index.html')

   Returns the output path."
  [results output-path]
  (let [html-content
        (str
         "<!DOCTYPE html>\n"
         (h/html
          [:html {:lang "en"}
           [:head
            [:meta {:charset "UTF-8"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
            [:title "Heretic Mutation Testing Report"]
            [:style html-css]]
           [:body
            [:div.dashboard
             [:h1 "🧬 Heretic Mutation Testing Report"]
             (html-summary-section results)
             (html-heatmap-section results)
             (html-survivors-section results)]]]))]
    ;; Ensure output directory exists
    (io/make-parents output-path)
    (spit output-path html-content)
    output-path))

(defn write-report!
  "Write mutation report in the specified format.

   Arguments:
   - results: Mutation testing results
   - format: :terminal or :html
   - output-path: Path for HTML output (ignored for terminal)

   Returns nil for terminal, output path for HTML."
  [results format output-path]
  (case format
    :terminal (do (print-report results) nil)
    :html (generate-html-report results (str output-path "/index.html"))
    (throw (ex-info "Unknown report format" {:format format}))))
