(ns heretic.trends
  "Historical trend data for mutation testing.

   Stores results from each mutation run to enable trend visualization
   and progress tracking over time.

   Storage layout:
   .heretic/
   ├── history.edn  # Vector of run summaries (most recent first)

   Main API:
   - `load-history` - Load run history
   - `record-run!` - Add a new run to history
   - `trend-data` - Get data formatted for charts"
  (:require [clojure.java.io :as io]
            [heretic.persistence :as persist]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def max-history-entries
  "Maximum number of historical runs to keep."
  100)

;; =============================================================================
;; Storage
;; =============================================================================

(defn- history-file-path
  "Get path for history data file."
  [heretic-dir]
  (io/file heretic-dir "history.edn"))

(defn load-history
  "Load run history from disk.

   Returns vector of run summaries (most recent first), or empty vector if none."
  [heretic-dir]
  (or (persist/load-edn (history-file-path heretic-dir)) []))

(defn save-history!
  "Save run history to disk."
  [heretic-dir history]
  (persist/save-edn! (history-file-path heretic-dir) history))

;; =============================================================================
;; Recording Runs
;; =============================================================================

(defn- create-run-entry
  "Create a history entry from mutation run results.

   Arguments:
   - results: Summary map from runner/summarize-results
   - opts: Optional extra data to include

   Returns map suitable for history storage."
  [results opts]
  (let [timestamp (java.time.Instant/now)]
    (merge
     {:timestamp (str timestamp)
      :mutation-score (:mutation-score results)
      :total (:total results)
      :killed (:killed results)
      :survived (:survived results)
      :no-coverage (:no-coverage results)
      :timeout (:timeout results)
      :duration-ms (:total-duration-ms results)}
     (select-keys opts [:git-commit :git-branch :preset :operator-count]))))

(defn record-run!
  "Record a mutation run in history.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - results: Summary map from mutation run
   - opts: Optional metadata (git info, preset used, etc.)

   Returns updated history."
  [heretic-dir results opts]
  (let [entry (create-run-entry results opts)
        history (load-history heretic-dir)
        updated (->> (cons entry history)
                     (take max-history-entries)
                     vec)]
    (save-history! heretic-dir updated)
    updated))

;; =============================================================================
;; Trend Data for Charts
;; =============================================================================

(defn trend-data
  "Get trend data formatted for chart visualization.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - n: Number of recent runs to include (default 20)

   Returns:
   {:runs [{:timestamp ... :score ... :killed ... :total ...} ...]
    :score-trend :improving/:declining/:stable
    :avg-score n
    :best-score n
    :worst-score n}"
  ([heretic-dir] (trend-data heretic-dir 20))
  ([heretic-dir n]
   (let [history (load-history heretic-dir)
         runs (take n history)
         scores (map :mutation-score runs)]
     (if (empty? runs)
       {:runs []
        :score-trend :no-data
        :avg-score nil
        :best-score nil
        :worst-score nil}
       (let [avg (/ (reduce + scores) (count scores))
             ;; Compare first half to second half for trend
             half (max 1 (quot (count scores) 2))
             recent (take half scores)
             older (drop half scores)
             recent-avg (if (seq recent) (/ (reduce + recent) (count recent)) avg)
             older-avg (if (seq older) (/ (reduce + older) (count older)) avg)
             trend (cond
                     (> recent-avg (+ older-avg 0.02)) :improving
                     (< recent-avg (- older-avg 0.02)) :declining
                     :else :stable)]
         {:runs (mapv (fn [r]
                        {:timestamp (:timestamp r)
                         :score (:mutation-score r)
                         :killed (:killed r)
                         :survived (:survived r)
                         :total (:total r)
                         :duration-ms (:duration-ms r)})
                      runs)
          :score-trend trend
          :avg-score (double avg)
          :best-score (apply max scores)
          :worst-score (apply min scores)})))))

(defn score-sparkline
  "Generate ASCII sparkline for mutation scores.

   Arguments:
   - heretic-dir: Path to .heretic directory
   - width: Width in characters (default 20)

   Returns string like '▁▂▃▅▇▇▅▃' showing score trend."
  ([heretic-dir] (score-sparkline heretic-dir 20))
  ([heretic-dir width]
   (let [history (load-history heretic-dir)
         ;; Reverse to show oldest-to-newest (left-to-right)
         scores (reverse (take width (map :mutation-score history)))
         bars "▁▂▃▄▅▆▇█"]
     (if (empty? scores)
       ""
       (apply str
              (map (fn [score]
                     ;; Score is 0.0-1.0, map to bar index 0-7
                     (let [idx (min 7 (int (* score 8)))]
                       (nth bars idx)))
                   scores))))))

(defn format-trend-summary
  "Format a human-readable trend summary.

   Arguments:
   - trend-data: Result from `trend-data`

   Returns multi-line string summarizing trends."
  [td]
  (if (= :no-data (:score-trend td))
    "No historical data available. Run mutation testing to start building history."
    (let [trend-str (case (:score-trend td)
                      :improving "↑ Improving"
                      :declining "↓ Declining"
                      :stable "→ Stable")]
      (format "Mutation Score Trend: %s
  Average: %.1f%%
  Best: %.1f%%
  Worst: %.1f%%
  Runs analyzed: %d"
              trend-str
              (* 100 (:avg-score td))
              (* 100 (:best-score td))
              (* 100 (:worst-score td))
              (count (:runs td))))))
