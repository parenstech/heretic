(ns heretic.reporter
  "Mutation testing result reporting.

   This module provides functions to calculate mutation scores and
   display mutation testing results in terminal, HTML, and JSON formats.

   Key functions:
   - `mutation-score` - Calculate killed/(killed+survived) ratio
   - `print-summary` - Print summary stats to terminal
   - `print-survivors` - List surviving mutations with details
   - `print-diagnosis` - Print automated diagnosis of survivor patterns
   - `generate-html-report` - Generate HTML report with heatmap and trend charts
   - `generate-json-report` - Generate JSON report for programmatic access"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [heretic.diagnosis :as diagnosis]
            [heretic.subsumption :as subsumption]
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
;; Phase Output Functions (Terminal)
;; =============================================================================

(defn print-header
  "Print the mutation testing header banner."
  []
  (println "===================================================================")
  (println "                    Heretic Mutation Testing")
  (println "===================================================================")
  (println))

(defn print-phase
  "Print a phase message."
  [message]
  (println message))

(defn print-collection-result
  "Print collection phase results."
  [{:keys [total-ns stale-ns collected-ns forms duration-ms]}]
  (println)
  (println "Collection complete:")
  (println (format "  Test namespaces: %d total, %d stale, %d collected"
                   total-ns stale-ns collected-ns))
  (println (format "  Forms registered: %d" forms))
  (println (format "  Duration: %dms" duration-ms)))

(defn print-mutation-scan-result
  "Print mutation scan results."
  [total-found filtered-count filter-enabled?]
  (println (format "Found %d mutation sites." total-found))
  (when (and filter-enabled? (pos? filtered-count))
    (println (format "Filtered %d likely equivalent mutations." filtered-count))))

(defn print-mutation-progress
  "Print mutation testing progress indicator."
  [current total status]
  (let [pct (int (* 100.0 (/ current total)))
        status-indicator (case status
                           :killed "X"
                           :survived "x"
                           :no-coverage "o"
                           :timeout "T"
                           :error "!"
                           "?")]
    (print (format "\r[%3d%%] %d/%d mutations tested %s" pct current total status-indicator))
    (flush)))

(defn print-parallel-mode
  "Print parallel mode information."
  [parallel? worker-count]
  (if parallel?
    (println (format "Running mutation tests in parallel (%d workers)..." worker-count))
    (println "Running mutation tests...")))

(defn print-timing-loaded
  "Print timing data loaded message."
  [test-count]
  (println (format "Loaded timing data for %d tests (ordering fastest first)."
                   test-count)))

(defn print-html-report-written
  "Print HTML report path."
  [html-path]
  (println)
  (println (format "HTML report written to: %s" html-path)))

(defn print-edn-report-written
  "Print EDN report path."
  [edn-path]
  (println)
  (println (format "EDN report written to: %s" edn-path)))

(defn print-json-report-written
  "Print JSON report path."
  [json-path]
  (println)
  (println (format "JSON report written to: %s" json-path)))

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

(defn no-coverage-by-file
  "Distinct uncovered mutation sites grouped by file — forms no test in the indexed
   suite reaches (so a mutation there is never run/killed), often the larger latent
   gap than survivors. Returns a vector of {:file :sites :lines} sorted by file
   (`:sites` = distinct file+line+column count; `:lines` = sorted distinct lines).
   Shared by the JSON/EDN/HTML report surfaces."
  [results]
  (->> results
       (filter #(= :no-coverage (:status %)))
       (map #(select-keys (:mutation %) [:file :line :column]))
       distinct
       (group-by :file)
       (sort-by key)
       (mapv (fn [[file sites]]
               {:file file
                :sites (count sites)
                :lines (vec (sort (distinct (map :line sites))))}))))

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
        score (mutation-score results)]

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

(defn print-survivor-hotspots
  "Print top 10 files by number of surviving mutations.

   Helps identify which files need the most test improvement."
  [results]
  (let [survivor-list (survivors results)
        by-file (->> survivor-list
                     (group-by #(get-in % [:mutation :file]))
                     (map (fn [[file mutations]]
                            {:file file
                             :count (count mutations)}))
                     (sort-by :count >)
                     (take 10))]
    (when (seq by-file)
      (println)
      (println (bold "Survivor Hotspots"))
      (println "-----------------")
      (doseq [{:keys [file count]} by-file]
        (println (format "  %3d  %s" count (red file)))))))

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

(defn print-test-effectiveness
  "Print test effectiveness report showing which tests are most effective at killing mutants.

   Displays:
   - Dominant tests with their kill counts
   - Potential optimization savings (what % could be skipped)
   - Tests that ran but never killed any mutants

   Only prints if there are killed mutants to analyze."
  [results]
  (let [stats (subsumption/subsumption-stats results)
        report (subsumption/test-effectiveness-report results)]
    (when (pos? (:total-killed stats))
      (println)
      (println (bold "Test Effectiveness"))
      (println "==================")
      (println)

      ;; Show dominant tests
      (println (cyan "Top Tests by Kill Count:"))
      (doseq [[test-sym kills] (:dominant-tests stats)]
        (println (format "  %s: %d kills"
                         (str test-sym)
                         kills)))
      (println)

      ;; Show potential savings
      (println (format "Potential optimization: %.0f%% of kills from top 5 tests"
                       (* 100 (:potential-savings stats))))
      (println (format "Unique killer tests: %d" (:unique-killers stats)))

      ;; Show ineffective tests if any
      (when (seq (:ineffective-tests report))
        (println)
        (println (yellow "Tests that ran but never killed mutants:"))
        (doseq [test-sym (take 10 (:ineffective-tests report))]
          (println (format "  %s" (str test-sym))))
        (when (> (count (:ineffective-tests report)) 10)
          (println (format "  ... and %d more" (- (count (:ineffective-tests report)) 10)))))

      (println))))

(defn print-diagnosis
  "Print automated diagnosis of survivor patterns.

   Analyzes surviving mutations to identify common test gaps
   and provides actionable suggestions for improvement."
  [results]
  (let [survivor-list (survivors results)]
    (when (seq survivor-list)
      (let [diag (diagnosis/diagnose-survivors survivor-list)]
        (when-let [output (diagnosis/format-diagnosis-terminal diag)]
          (println output))))))

(defn print-report
  "Print full mutation testing report.

   Combines summary, survivors, diagnosis, no-coverage, and test effectiveness sections."
  [results]
  (print-summary results)
  (print-survivors results)
  (print-diagnosis results)
  (print-no-coverage results)
  (print-test-effectiveness results))

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
;; JSON Report Generation
;; =============================================================================

(defn- escape-json-string
  "Escape a string for JSON output."
  [s]
  (when s
    (-> (str s)
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n")
        (str/replace "\r" "\\r")
        (str/replace "\t" "\\t"))))

(defn- to-json-value
  "Convert a Clojure value to JSON string representation."
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (str v)
    (number? v) (str v)
    (string? v) (str "\"" (escape-json-string v) "\"")
    (keyword? v) (str "\"" (name v) "\"")
    (symbol? v) (str "\"" v "\"")
    (map? v) (str "{"
                  (->> v
                       (map (fn [[k val]]
                              (str (to-json-value k) ": " (to-json-value val))))
                       (str/join ", "))
                  "}")
    (sequential? v) (str "["
                         (->> v
                              (map to-json-value)
                              (str/join ", "))
                         "]")
    (set? v) (to-json-value (vec v))
    :else (str "\"" (escape-json-string (str v)) "\"")))

(defn- format-json
  "Pretty-print JSON with indentation."
  [json-str]
  (let [indent-level (atom 0)
        in-string (atom false)
        escape-next (atom false)
        result (StringBuilder.)]
    (doseq [c json-str]
      (cond
        ;; If previous char was escape, just append and reset
        @escape-next
        (do (.append result c)
            (reset! escape-next false))

        ;; Escape character - mark next char as escaped
        (and (= c \\) @in-string)
        (do (.append result c)
            (reset! escape-next true))

        ;; Toggle string state on unescaped quotes
        (= c \")
        (do (.append result c)
            (swap! in-string not))

        ;; Inside string, just append
        @in-string
        (.append result c)

        ;; Opening brace/bracket
        (or (= c \{) (= c \[))
        (do (.append result c)
            (swap! indent-level inc)
            (.append result \newline)
            (dotimes [_ (* 2 @indent-level)]
              (.append result \space)))

        ;; Closing brace/bracket
        (or (= c \}) (= c \]))
        (do (swap! indent-level dec)
            (.append result \newline)
            (dotimes [_ (* 2 @indent-level)]
              (.append result \space))
            (.append result c))

        ;; Comma
        (= c \,)
        (do (.append result c)
            (.append result \newline)
            (dotimes [_ (* 2 @indent-level)]
              (.append result \space)))

        ;; Colon after key
        (= c \:)
        (do (.append result c)
            (.append result \space))

        ;; Skip whitespace (we handle our own)
        (Character/isWhitespace c)
        nil

        ;; Everything else
        :else
        (.append result c)))
    (str result)))

(defn- json-stats-by-file
  "Calculate stats for each file for JSON report."
  [results]
  (->> results
       (group-by #(get-in % [:mutation :file]))
       (reduce-kv
        (fn [acc file rs]
          (let [killed-count (count (filter #(= :killed (:status %)) rs))
                survived-count (count (filter #(= :survived (:status %)) rs))
                no-coverage-count (count (filter #(= :no-coverage (:status %)) rs))
                testable (+ killed-count survived-count)
                score (if (pos? testable) (double (/ killed-count testable)) nil)]
            (assoc acc (or file "(unknown)")
                   {:killed killed-count
                    :survived survived-count
                    :noCoverage no-coverage-count
                    :total (count rs)
                    :score score})))
        {})))

(defn summarize-witness
  "Render a witness (the differential oracle's distinguishing input tuple) as a
   compact, readable string for the reports + mutation-results.edn. Any Throwable
   anywhere in the input is replaced by a tagged summary
   `{:heretic/error <class> :message <msg>}` BEFORE printing, so a full stack
   trace can't bloat the output — a real babel run pr-str'd missionary stack
   traces and blew a ~100 KB EDN to 5.8 MB. The result is also length-capped as a
   backstop against any other oversized value."
  [witness]
  (let [compact (walk/postwalk
                 (fn [x]
                   (if (instance? Throwable x)
                     {:heretic/error (.getName (class x)) :message (ex-message x)}
                     x))
                 witness)
        s   (pr-str compact)
        cap 2000]
    (if (> (count s) cap)
      (str (subs s 0 cap) " …<" (count s) " chars truncated>")
      s)))

(defn- triage-report-fields
  "Report-serialized survivor-triage verdict for a result, or {} when triage did
   not run. `kw` controls how the label keywords serialize — `name` (string) for
   JSON, `identity` (keyword) for EDN, matching how each report renders :operator.
   The witness (arbitrary data) is summarized to a string (Throwables elided,
   length-capped), matching mutation-results.edn. See `heretic.oracle.triage`
   for the tagged-verdict shape."
  [{:keys [triage witness proof reason trials]} kw]
  (cond-> {}
    triage  (assoc :triage (kw triage))
    witness (assoc :witness (summarize-witness witness))
    proof   (assoc :proof (kw proof))
    reason  (assoc :reason (kw reason))
    trials  (assoc :trials trials)))

(defn- survivor-to-json-map
  "Convert a survivor result to a JSON-friendly map (incl. its triage verdict)."
  [{:keys [mutation tests-run] :as result}]
  (let [{:keys [file line operator original replacement column]} mutation]
    (merge {:file file
            :line line
            :column column
            :operator (name operator)
            :original (str original)
            :replacement (str replacement)
            :testsRun (vec (map str tests-run))}
           (triage-report-fields result name))))

(defn json-report-data
  "Generate JSON report data structure.

   Returns a map suitable for JSON serialization with:
   - :summary - Overall statistics
   - :survivors - List of surviving mutations
   - :noCoverage - Uncovered mutation sites grouped by file (the larger latent gap)
   - :diagnosis - Analysis of survivor patterns
   - :byFile - Per-file breakdown"
  [results]
  (let [counts (count-by-status results)
        total (count results)
        score (mutation-score results)
        survivor-list (survivors results)
        diag (diagnosis/diagnose-survivors survivor-list)]
    {:summary {:total total
               :killed (:killed counts)
               :survived (:survived counts)
               :noCoverage (:no-coverage counts)
               :timeout (:timeout counts)
               :error (:error counts)
               :score score}
     :survivors (mapv survivor-to-json-map survivor-list)
     :noCoverage (no-coverage-by-file results)
     :diagnosis (diagnosis/format-diagnosis-data diag)
     :byFile (json-stats-by-file results)}))

(defn generate-json-report
  "Generate JSON mutation testing report.

   Arguments:
   - results: Mutation testing results
   - output-path: Path to write JSON file (e.g., 'target/heretic-report/report.json')

   Returns the output path.

   The JSON structure includes:
   - summary: Overall statistics (total, killed, survived, score, etc.)
   - survivors: List of surviving mutations with file, line, operator details
   - diagnosis: Analysis of survivor patterns with fixes
   - byFile: Results grouped by source file"
  [results output-path]
  (let [report-data (json-report-data results)
        json-str (format-json (to-json-value report-data))]
    ;; Ensure output directory exists
    (io/make-parents output-path)
    (spit output-path json-str)
    output-path))

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
  .triage-badge {
    display: inline-block;
    margin-left: 8px;
    padding: 1px 8px;
    border-radius: 10px;
    font-size: 0.72em;
    font-weight: bold;
    vertical-align: middle;
  }
  .triage-coverage-gap { background: #f8d7da; color: #721c24; }
  .triage-proven-equivalent { background: #e2e3e5; color: #383d41; }
  .triage-candidate-equivalent { background: #fff3cd; color: #856404; }
  .triage-not-applicable, .triage-undetermined { background: #e9ecef; color: #495057; }
  .survivor-witness { font-size: 0.9em; color: #495057; margin-top: 2px; }
  .no-coverage-note { color: #856404; font-size: 0.9em; }
  .no-coverage-list { list-style: none; padding-left: 0; }
  .no-coverage-item {
    background: #fff3cd;
    border-left: 4px solid var(--color-no-coverage);
    padding: 10px 14px;
    margin-bottom: 8px;
    border-radius: 0 4px 4px 0;
  }
  .no-coverage-file { font-family: monospace; font-weight: bold; }
  .no-coverage-lines { font-size: 0.85em; color: #6c757d; margin-top: 3px; }
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
  .diagnosis { margin-bottom: 30px; }
  .diagnosis-list { margin-top: 15px; }
  .diagnosis-item {
    background: #fff3cd;
    border-left: 4px solid #ffc107;
    padding: 15px;
    margin-bottom: 15px;
    border-radius: 0 4px 4px 0;
  }
  .diagnosis-item h3 {
    margin: 0 0 10px 0;
    color: #856404;
    font-size: 1.1em;
  }
  .diagnosis-item p { margin: 5px 0; }
  .diagnosis-item .example {
    background: #f8f9fa;
    padding: 10px;
    margin-top: 10px;
    border-radius: 4px;
    font-size: 0.9em;
    overflow-x: auto;
  }
  .undiagnosed { font-style: italic; color: #6c757d; margin-top: 15px; }
  .code-snippet {
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 0.85em;
    background: #f8f9fa;
    padding: 3px 6px;
    border-radius: 3px;
  }
  .test-effectiveness { margin-bottom: 30px; }
  .test-effectiveness-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
  }
  .test-list { list-style: none; padding: 0; margin: 0; }
  .test-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 12px;
    margin-bottom: 6px;
    border-radius: 4px;
    font-family: monospace;
    font-size: 0.9em;
  }
  .test-item.effective {
    background: #d4edda;
    border-left: 4px solid var(--color-killed);
  }
  .test-item.ineffective {
    background: #f8d7da;
    border-left: 4px solid var(--color-survived);
  }
  .test-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .test-kills {
    font-weight: bold;
    margin-left: 10px;
    padding: 2px 8px;
    background: rgba(0,0,0,0.1);
    border-radius: 3px;
  }
  .subsection-title {
    font-size: 1.1em;
    font-weight: 600;
    color: #495057;
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--color-border);
  }
  .no-data { color: #6c757d; font-style: italic; padding: 10px 0; }
  .trend-section { margin-bottom: 30px; }
  .trend-chart {
    display: flex;
    align-items: flex-end;
    height: 100px;
    gap: 4px;
    padding: 10px 0;
    border-bottom: 2px solid var(--color-border);
  }
  .trend-bar {
    flex: 1;
    min-width: 20px;
    max-width: 40px;
    border-radius: 3px 3px 0 0;
    transition: height 0.3s;
    cursor: pointer;
    position: relative;
  }
  .trend-bar:hover::after {
    content: attr(data-tooltip);
    position: absolute;
    bottom: 100%;
    left: 50%;
    transform: translateX(-50%);
    background: #333;
    color: white;
    padding: 4px 8px;
    border-radius: 4px;
    font-size: 0.75em;
    white-space: nowrap;
  }
  .trend-labels {
    display: flex;
    justify-content: space-between;
    font-size: 0.8em;
    color: #6c757d;
    margin-top: 5px;
  }
  .trend-stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 15px;
    margin-top: 15px;
  }
  .trend-stat {
    text-align: center;
    padding: 10px;
    background: #f8f9fa;
    border-radius: 4px;
  }
  .trend-stat-value { font-size: 1.5em; font-weight: bold; }
  .trend-stat-label { font-size: 0.85em; color: #6c757d; }
  .trend-improving { color: #28a745; }
  .trend-declining { color: #dc3545; }
  .trend-stable { color: #6c757d; }
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
     (for [{:keys [file killed survived score]} by-file]
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

(def ^:private triage-badge-labels
  "Short human label per triage verdict, for the HTML badge."
  {:coverage-gap "COVERAGE GAP"
   :proven-equivalent "EQUIVALENT"
   :candidate-equivalent "LIKELY EQUIV"
   :not-applicable "N/A"
   :undetermined "UNDETERMINED"})

(defn- html-survivors-section
  "Generate HTML for the surviving mutations section (with the triage verdict:
   a per-survivor badge, plus the witness for a coverage-gap / proof for a
   proven-equivalent)."
  [results]
  (let [survivor-list (survivors results)]
    (when (seq survivor-list)
      [:div.card
       [:h2 "Surviving Mutations"]
       [:ul.survivor-list
        (for [{:keys [mutation tests-run triage witness proof reason]} survivor-list]
          (let [{:keys [file line operator original replacement]} mutation]
            [:li.survivor-item
             [:div.survivor-location
              (str file ":" line)
              (when triage
                [:span.triage-badge {:class (str "triage-" (name triage))}
                 (get triage-badge-labels triage (name triage))])]
             [:div.survivor-change
              [:span.code-snippet original]
              " → "
              [:span.code-snippet replacement]
              " ("
              (name operator)
              ")"]
             (when witness
               [:div.survivor-witness "Witness: " [:span.code-snippet (summarize-witness witness)]])
             (when proof
               [:div.survivor-witness "Proof: " (name proof)])
             (when (and reason (not witness) (not proof))
               [:div.survivor-witness "Reason: " (name reason)])
             [:div.survivor-tests
              "Tests: "
              (if (empty? tests-run)
                "(none)"
                (str/join ", " (map str tests-run)))]]))]])))

(defn- html-no-coverage-section
  "Generate HTML for the uncovered-sites section — forms no test in the indexed
   suite reaches (often the larger latent gap than survivors), grouped by file."
  [results]
  (let [by-file (no-coverage-by-file results)]
    (when (seq by-file)
      (let [total-sites (reduce + (map :sites by-file))]
        [:div.card
         [:h2 (format "No Coverage (%d sites in %d files)" total-sites (count by-file))]
         [:p.no-coverage-note
          "Forms no test in the indexed (keyless) suite exercises — a mutation there "
          "is never run. Often the larger latent gap than survivors. Some may be reachable "
          "only by excluded (e.g. key-gated) tests."]
         [:ul.no-coverage-list
          (for [{:keys [file sites lines]} by-file]
            [:li.no-coverage-item
             [:span.no-coverage-file file]
             " — " (format "%d site%s" sites (if (= 1 sites) "" "s"))
             [:div.no-coverage-lines "lines " (str/join ", " lines)]])]]))))

(defn- html-diagnosis-section
  "Generate HTML for the survivor diagnosis section."
  [results]
  (let [survivor-list (survivors results)]
    (when (seq survivor-list)
      (let [diag (diagnosis/diagnose-survivors survivor-list)]
        (when (seq (:patterns diag))
          [:div.card.diagnosis
           [:h2 "Diagnosis"]
           [:p "Common patterns detected in surviving mutations:"]
           [:div.diagnosis-list
            (for [{:keys [pattern-id count diagnosis fix example]} (:patterns diag)]
              [:div.diagnosis-item
               [:h3 (str (name pattern-id) " (" count " survivors)")]
               [:p [:strong "Problem: "] diagnosis]
               [:p [:strong "Fix: "] fix]
               (when example
                 [:pre.example example])])]
           (when (pos? (:undiagnosed-count diag))
             [:p.undiagnosed
              (str (:undiagnosed-count diag) " survivors don't match known patterns. "
                   "See docs/interpreting-survivors.md for manual analysis.")])])))))

(defn- html-test-effectiveness-section
  "Generate HTML for the test effectiveness ranking section.

   Shows:
   - Top tests ranked by mutation kill count
   - Ineffective tests (ran but never killed anything)"
  [results]
  (let [report (subsumption/test-effectiveness-report results)
        effective-tests (:effective-tests report)
        ineffective-tests (:ineffective-tests report)]
    (when (or (seq effective-tests) (seq ineffective-tests))
      [:div.card.test-effectiveness
       [:h2 "Test Effectiveness"]
       [:div.test-effectiveness-grid
        ;; Effective tests (left column)
        [:div
         [:div.subsection-title "Top Tests by Kill Count"]
         (if (seq effective-tests)
           [:ul.test-list
            (for [{:keys [test kills]} effective-tests]
              [:li.test-item.effective
               [:span.test-name (str test)]
               [:span.test-kills (str kills " kills")]])]
           [:div.no-data "No tests killed any mutations"])]
        ;; Ineffective tests (right column)
        [:div
         [:div.subsection-title "Ineffective Tests"]
         (if (seq ineffective-tests)
           [:ul.test-list
            (for [test ineffective-tests]
              [:li.test-item.ineffective
               [:span.test-name (str test)]
               [:span.test-kills "0 kills"]])]
           [:div.no-data "All tests killed at least one mutation"])]]])))

(defn- html-trend-section
  "Generate HTML for the score trend section.

   Shows a bar chart of historical mutation scores with trend indicators.
   Arguments:
   - trend-data: Map with :runs, :score-trend, :avg-score, :best-score, :worst-score"
  [trend-data]
  (when (and trend-data (seq (:runs trend-data)))
    (let [runs (reverse (:runs trend-data))  ; Oldest first for left-to-right
          trend (:score-trend trend-data)
          trend-class (case trend
                        :improving "trend-improving"
                        :declining "trend-declining"
                        "trend-stable")
          trend-icon (case trend
                       :improving "↑"
                       :declining "↓"
                       "→")]
      [:div.card.trend-section
       [:h2 "Score Trend"]
       [:div.trend-chart
        (for [run runs]
          (let [score (:score run)
                height-pct (max 5 (* score 100))
                color (cond
                        (>= score 0.8) "#28a745"
                        (>= score 0.6) "#ffc107"
                        :else "#dc3545")
                tooltip (format "%.1f%% (%d/%d)"
                                (* score 100)
                                (:killed run)
                                (:total run))]
            [:div.trend-bar
             {:style (format "height: %.0f%%; background: %s;" height-pct color)
              :data-tooltip tooltip}]))]
       [:div.trend-labels
        [:span "Oldest"]
        [:span "Most Recent"]]
       [:div.trend-stats
        [:div.trend-stat
         [:div.trend-stat-value {:class trend-class} (str trend-icon " " (name trend))]
         [:div.trend-stat-label "Trend"]]
        [:div.trend-stat
         [:div.trend-stat-value (format "%.1f%%" (* 100 (:avg-score trend-data)))]
         [:div.trend-stat-label "Average"]]
        [:div.trend-stat
         [:div.trend-stat-value.trend-improving (format "%.1f%%" (* 100 (:best-score trend-data)))]
         [:div.trend-stat-label "Best"]]
        [:div.trend-stat
         [:div.trend-stat-value.trend-declining (format "%.1f%%" (* 100 (:worst-score trend-data)))]
         [:div.trend-stat-label "Worst"]]]])))

(defn generate-html-report
  "Generate HTML mutation testing report.

   Arguments:
   - results: Mutation testing results
   - output-path: Path to write HTML file (e.g., 'target/heretic-report/index.html')
   - opts: Optional map with:
     - :trend-data - Historical trend data from heretic.trends/trend-data

   Returns the output path."
  ([results output-path] (generate-html-report results output-path {}))
  ([results output-path opts]
   (let [trend-data (:trend-data opts)
         html-content
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
              (html-trend-section trend-data)
              (html-heatmap-section results)
              (html-test-effectiveness-section results)
              (html-survivors-section results)
              (html-no-coverage-section results)
              (html-diagnosis-section results)]]]))]
     ;; Ensure output directory exists
     (io/make-parents output-path)
     (spit output-path html-content)
     output-path)))

;; =============================================================================
;; EDN Report Generation
;; =============================================================================

(defn- survivor-to-edn-map
  "Convert a survivor result to an EDN-friendly map (incl. its triage verdict)."
  [{:keys [mutation tests-run] :as result}]
  (let [{:keys [file line operator original replacement column]} mutation]
    (merge {:file file
            :line line
            :column column
            :operator operator
            :original (str original)
            :replacement (str replacement)
            :tests-run (vec (sort (map str tests-run)))}
           (triage-report-fields result identity))))

(defn- edn-stats-by-file
  "Calculate stats for each file for EDN report."
  [results]
  (->> results
       (group-by #(get-in % [:mutation :file]))
       (reduce-kv
        (fn [acc file rs]
          (let [killed-count (count (filter #(= :killed (:status %)) rs))
                survived-count (count (filter #(= :survived (:status %)) rs))
                no-coverage-count (count (filter #(= :no-coverage (:status %)) rs))
                timeout-count (count (filter #(= :timeout (:status %)) rs))
                error-count (count (filter #(= :error (:status %)) rs))
                testable (+ killed-count survived-count)
                score (when (pos? testable) (double (/ killed-count testable)))]
            (assoc acc (or file "(unknown)")
                   {:killed killed-count
                    :survived survived-count
                    :no-coverage no-coverage-count
                    :timeout timeout-count
                    :error error-count
                    :total (count rs)
                    :score score})))
        {})))

(defn edn-report-data
  "Generate EDN report data structure.

   Returns a map with:
   - :summary - Overall statistics
   - :survivors - List of surviving mutations
   - :no-coverage - Uncovered mutation sites grouped by file (the larger latent gap)
   - :diagnosis - Analysis of survivor patterns
   - :by-file - Per-file breakdown

   Uses idiomatic Clojure naming (kebab-case) unlike JSON report."
  [results]
  (let [counts (count-by-status results)
        total (count results)
        score (mutation-score results)
        survivor-list (survivors results)
        diag (diagnosis/diagnose-survivors survivor-list)]
    {:summary {:total total
               :killed (:killed counts)
               :survived (:survived counts)
               :no-coverage (:no-coverage counts)
               :timeout (:timeout counts)
               :error (:error counts)
               :score score}
     :survivors (mapv survivor-to-edn-map survivor-list)
     :no-coverage (no-coverage-by-file results)
     :diagnosis (diagnosis/format-diagnosis-data diag)
     :by-file (edn-stats-by-file results)}))

(defn generate-edn-report
  "Generate EDN mutation testing report.

   Arguments:
   - results: Mutation testing results
   - output-path: Path to write EDN file (e.g., 'target/heretic-report/report.edn')

   Returns the output path.

   The EDN structure includes:
   - :summary - Overall statistics (total, killed, survived, score, etc.)
   - :survivors - List of surviving mutations with file, line, operator details
   - :diagnosis - Analysis of survivor patterns with fixes
   - :by-file - Results grouped by source file"
  [results output-path]
  (let [report-data (edn-report-data results)]
    ;; Ensure output directory exists
    (io/make-parents output-path)
    (spit output-path (pr-str report-data))
    output-path))

;; =============================================================================
;; Unified Report Writer
;; =============================================================================

(defn write-report!
  "Write mutation report in the specified format.

   Arguments:
   - results: Mutation testing results
   - format: :terminal, :html, :json, or :edn
   - output-path: Path for file output (ignored for terminal)

   Returns nil for terminal, output path for HTML/JSON/EDN."
  [results format output-path]
  (case format
    :terminal (do (print-report results) nil)
    :html (generate-html-report results (str output-path "/index.html"))
    :json (generate-json-report results (str output-path "/report.json"))
    :edn (generate-edn-report results (str output-path "/report.edn"))
    (throw (ex-info "Unknown report format" {:format format}))))
