(ns heretic.clustering-test
  "Tests for heretic.clustering mutant clustering.

   Tests cover:
   - cluster-mutations: All clustering strategies
   - select-representative: Representative selection logic
   - infer-cluster-results: Result inference from representative
   - expand-cluster-results: Expanding cluster results
   - cluster-stats: Clustering statistics
   - Integration with controller.clj"
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.clustering :as clustering]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def mock-mutations
  "Mock mutations for testing clustering logic."
  [;; Arithmetic mutations on same location
   {:id #uuid "00000000-0000-0000-0000-000000000001"
    :file "src/app/math.clj"
    :line 10
    :form-id 12345
    :coord [0 1]
    :operator :swap-plus-minus
    :original '+
    :replacement '-}

   {:id #uuid "00000000-0000-0000-0000-000000000002"
    :file "src/app/math.clj"
    :line 10
    :form-id 12345
    :coord [0 2]
    :operator :swap-mult-div
    :original '*
    :replacement '/}

   ;; Arithmetic mutation on different location
   {:id #uuid "00000000-0000-0000-0000-000000000003"
    :file "src/app/math.clj"
    :line 20
    :form-id 54321
    :coord [0]
    :operator :swap-plus-minus
    :original '+
    :replacement '-}

   ;; Boolean mutations on same location
   {:id #uuid "00000000-0000-0000-0000-000000000004"
    :file "src/app/logic.clj"
    :line 15
    :form-id 11111
    :coord [0 0]
    :operator :swap-and-or
    :original 'and
    :replacement 'or}

   {:id #uuid "00000000-0000-0000-0000-000000000005"
    :file "src/app/logic.clj"
    :line 15
    :form-id 11111
    :coord [0 0]
    :operator :swap-or-and
    :original 'or
    :replacement 'and}

   ;; Comparison mutations
   {:id #uuid "00000000-0000-0000-0000-000000000006"
    :file "src/app/compare.clj"
    :line 5
    :form-id 22222
    :coord [0]
    :operator :swap-lt-lte
    :original '<
    :replacement '<=}

   {:id #uuid "00000000-0000-0000-0000-000000000007"
    :file "src/app/compare.clj"
    :line 5
    :form-id 22222
    :coord [0]
    :operator :swap-lt-gt
    :original '<
    :replacement '>}

   ;; Collection mutation
   {:id #uuid "00000000-0000-0000-0000-000000000008"
    :file "src/app/coll.clj"
    :line 30
    :form-id 33333
    :coord [1 0]
    :operator :swap-first-last
    :original 'first
    :replacement 'last}])

;; =============================================================================
;; cluster-mutations Tests - :none strategy
;; =============================================================================

(deftest cluster-mutations-none-strategy-test
  (testing "Each mutation is its own cluster with :none strategy"
    (let [clusters (clustering/cluster-mutations mock-mutations :none)]
      (is (= (count mock-mutations) (count clusters))
          "Each mutation should be in its own cluster")
      (is (every? #(= 1 (count (val %))) clusters)
          "Each cluster should have exactly one mutation"))))

(deftest cluster-mutations-none-empty-test
  (testing "Empty mutations return empty clusters"
    (let [clusters (clustering/cluster-mutations [] :none)]
      (is (= {} clusters)))))

;; =============================================================================
;; cluster-mutations Tests - :operator strategy
;; =============================================================================

(deftest cluster-mutations-operator-strategy-test
  (testing "Groups mutations by operator type"
    (let [clusters (clustering/cluster-mutations mock-mutations :operator)]
      ;; swap-plus-minus appears twice
      (is (= 2 (count (get clusters "operator-swap-plus-minus"))))
      ;; swap-mult-div appears once
      (is (= 1 (count (get clusters "operator-swap-mult-div"))))
      ;; swap-and-or appears once
      (is (= 1 (count (get clusters "operator-swap-and-or")))))))

(deftest cluster-mutations-operator-all-accounted-test
  (testing "All mutations are accounted for in operator clusters"
    (let [clusters (clustering/cluster-mutations mock-mutations :operator)
          total-in-clusters (reduce + (map count (vals clusters)))]
      (is (= (count mock-mutations) total-in-clusters)))))

;; =============================================================================
;; cluster-mutations Tests - :location strategy
;; =============================================================================

(deftest cluster-mutations-location-strategy-test
  (testing "Groups mutations by location (file + line + form-id)"
    (let [clusters (clustering/cluster-mutations mock-mutations :location)
          ;; math.clj line 10 has 2 mutations
          math-line-10 (some (fn [[k v]]
                               (when (and (re-find #"math\.clj" k)
                                          (re-find #"-10-" k))
                                 v))
                             clusters)]
      (is (= 2 (count math-line-10))
          "math.clj line 10 should have 2 mutations in same cluster"))))

(deftest cluster-mutations-location-different-lines-test
  (testing "Different lines in same file are separate clusters"
    (let [clusters (clustering/cluster-mutations mock-mutations :location)
          math-clusters (filter (fn [[k _]]
                                  (re-find #"math\.clj" k))
                                clusters)]
      (is (= 2 (count math-clusters))
          "math.clj should have 2 clusters (line 10 and line 20)"))))

;; =============================================================================
;; cluster-mutations Tests - :similarity strategy
;; =============================================================================

(deftest cluster-mutations-similarity-strategy-test
  (testing "Groups mutations by operator category and context"
    (let [clusters (clustering/cluster-mutations mock-mutations :similarity)]
      ;; All arithmetic mutations with same context should be grouped
      (is (seq clusters) "Should produce clusters"))))

(deftest cluster-mutations-similarity-categories-test
  (testing "Similarity groups by operator category"
    (let [clusters (clustering/cluster-mutations mock-mutations :similarity)
          arithmetic-clusters (filter (fn [[k _]]
                                        (re-find #"arithmetic" k))
                                      clusters)
          boolean-clusters (filter (fn [[k _]]
                                     (re-find #"boolean" k))
                                   clusters)]
      ;; Should have some arithmetic clusters
      (is (seq arithmetic-clusters) "Should have arithmetic clusters")
      ;; Should have some boolean clusters
      (is (seq boolean-clusters) "Should have boolean clusters"))))

;; =============================================================================
;; cluster-mutations Tests - Invalid strategy
;; =============================================================================

(deftest cluster-mutations-invalid-strategy-test
  (testing "Invalid strategy throws exception"
    (is (thrown? clojure.lang.ExceptionInfo
                 (clustering/cluster-mutations mock-mutations :invalid)))))

;; =============================================================================
;; select-representative Tests
;; =============================================================================

(deftest select-representative-returns-first-member-test
  (testing "Returns the cluster's first member (G3: the static hardness ranking
            was retired — measured no better than random, validation-results.md §5.3)"
    (let [cluster [{:id 1 :operator :swap-lt-gt}
                   {:id 2 :operator :swap-lt-lte}]
          rep (clustering/select-representative cluster)]
      (is (= 1 (:id rep))
          "Representative is the arbitrary-but-deterministic first member"))))

(deftest select-representative-single-mutation-test
  (testing "Single mutation cluster returns that mutation"
    (let [cluster [{:id 1 :operator :swap-plus-minus}]
          rep (clustering/select-representative cluster)]
      (is (= 1 (:id rep))))))

(deftest select-representative-empty-cluster-test
  (testing "Empty cluster returns nil"
    (let [rep (clustering/select-representative [])]
      (is (nil? rep)))))

(deftest select-representative-deterministic-test
  (testing "Selection is deterministic"
    (let [cluster [{:id 1 :operator :swap-plus-minus}
                   {:id 2 :operator :swap-mult-div}]
          rep1 (clustering/select-representative cluster)
          rep2 (clustering/select-representative cluster)]
      (is (= rep1 rep2) "Same input should give same output"))))

;; =============================================================================
;; infer-cluster-results Tests
;; =============================================================================

(deftest infer-cluster-results-killed-test
  (testing "Killed representative marks all as killed"
    (let [rep-mutation {:id 1 :operator :swap-lt-lte}
          other-mutation {:id 2 :operator :swap-lt-gt}
          cluster [rep-mutation other-mutation]
          rep-result {:mutation rep-mutation
                      :status :killed
                      :killed-by 'test/assertion
                      :test-durations {'test/assertion 100}
                      :tests-run #{'test/assertion}}
          results (clustering/infer-cluster-results rep-result cluster)]
      (is (= 2 (count results)))
      (is (every? #(= :killed (:status %)) results))
      ;; Representative keeps original result
      (is (some #(and (= 1 (get-in % [:mutation :id]))
                      (not (:inferred? %)))
                results))
      ;; Other has inferred flag
      (is (some #(and (= 2 (get-in % [:mutation :id]))
                      (:inferred? %))
                results)))))

(deftest infer-cluster-results-survived-test
  (testing "Survived representative marks all as survived"
    (let [rep-mutation {:id 1 :operator :swap-lt-lte}
          other-mutation {:id 2 :operator :swap-lt-gt}
          cluster [rep-mutation other-mutation]
          rep-result {:mutation rep-mutation
                      :status :survived
                      :killed-by nil
                      :test-durations {}
                      :tests-run #{'test/assertion}}
          results (clustering/infer-cluster-results rep-result cluster)]
      (is (every? #(= :survived (:status %)) results))
      (is (every? #(nil? (:killed-by %)) results)))))

(deftest infer-cluster-results-includes-inferred-from-test
  (testing "Inferred results include reference to representative"
    (let [rep-mutation {:id #uuid "00000000-0000-0000-0000-000000000001"}
          other-mutation {:id #uuid "00000000-0000-0000-0000-000000000002"}
          cluster [rep-mutation other-mutation]
          rep-result {:mutation rep-mutation
                      :status :killed
                      :killed-by 'test/assertion
                      :test-durations {}
                      :tests-run #{}}
          results (clustering/infer-cluster-results rep-result cluster)
          inferred (first (filter :inferred? results))]
      (is (= (:id rep-mutation) (:inferred-from inferred))))))

;; =============================================================================
;; expand-cluster-results Tests
;; =============================================================================

(deftest expand-cluster-results-test
  (testing "Expands all cluster results to flat sequence"
    (let [m1 {:id 1 :operator :swap-plus-minus}
          m2 {:id 2 :operator :swap-plus-minus}
          m3 {:id 3 :operator :swap-and-or}
          cluster-results {"c1" {:representative-result {:mutation m1
                                                         :status :killed
                                                         :killed-by 'test/a
                                                         :test-durations {}
                                                         :tests-run #{}}
                                 :cluster [m1 m2]}
                           "c2" {:representative-result {:mutation m3
                                                         :status :survived
                                                         :killed-by nil
                                                         :test-durations {}
                                                         :tests-run #{}}
                                 :cluster [m3]}}
          expanded (clustering/expand-cluster-results cluster-results)]
      (is (= 3 (count expanded)))
      (is (= 2 (count (filter #(= :killed (:status %)) expanded))))
      (is (= 1 (count (filter #(= :survived (:status %)) expanded)))))))

;; =============================================================================
;; cluster-stats Tests
;; =============================================================================

(deftest cluster-stats-total-mutations-test
  (testing "Counts total mutations correctly"
    (let [clusters {"c1" [{:id 1} {:id 2}]
                    "c2" [{:id 3}]}
          stats (clustering/cluster-stats clusters)]
      (is (= 3 (:total-mutations stats))))))

(deftest cluster-stats-cluster-count-test
  (testing "Counts clusters correctly"
    (let [clusters {"c1" [{:id 1} {:id 2}]
                    "c2" [{:id 3}]}
          stats (clustering/cluster-stats clusters)]
      (is (= 2 (:cluster-count stats))))))

(deftest cluster-stats-reduction-percentage-test
  (testing "Calculates reduction percentage"
    (let [clusters {"c1" [{:id 1} {:id 2} {:id 3} {:id 4}]  ; 4 mutations, 1 rep
                    "c2" [{:id 5} {:id 6}]}                  ; 2 mutations, 1 rep
          ;; Total: 6 mutations, 2 reps = 66.67% reduction
          stats (clustering/cluster-stats clusters)]
      (is (> (:reduction-percentage stats) 60))
      (is (< (:reduction-percentage stats) 70)))))

(deftest cluster-stats-avg-cluster-size-test
  (testing "Calculates average cluster size"
    (let [clusters {"c1" [{:id 1} {:id 2}]  ; size 2
                    "c2" [{:id 3} {:id 4}]} ; size 2
          stats (clustering/cluster-stats clusters)]
      (is (= 2.0 (:avg-cluster-size stats))))))

(deftest cluster-stats-max-cluster-size-test
  (testing "Finds maximum cluster size"
    (let [clusters {"c1" [{:id 1}]
                    "c2" [{:id 2} {:id 3} {:id 4}]
                    "c3" [{:id 5} {:id 6}]}
          stats (clustering/cluster-stats clusters)]
      (is (= 3 (:max-cluster-size stats))))))

(deftest cluster-stats-single-mutation-clusters-test
  (testing "Counts single-mutation clusters"
    (let [clusters {"c1" [{:id 1}]
                    "c2" [{:id 2}]
                    "c3" [{:id 3} {:id 4}]}
          stats (clustering/cluster-stats clusters)]
      (is (= 2 (:single-mutation-clusters stats))))))

(deftest cluster-stats-empty-clusters-test
  (testing "Handles empty clusters"
    (let [stats (clustering/cluster-stats {})]
      (is (= 0 (:total-mutations stats)))
      (is (= 0 (:cluster-count stats)))
      (is (= 0.0 (:reduction-percentage stats))))))

;; =============================================================================
;; extract-representatives Tests
;; =============================================================================

(deftest extract-representatives-test
  (testing "Extracts representatives from all clusters"
    (let [clusters {"c1" [{:id 1 :operator :swap-lt-lte}
                          {:id 2 :operator :swap-lt-gt}]
                    "c2" [{:id 3 :operator :swap-plus-minus}]}
          reps (clustering/extract-representatives clusters)]
      (is (= 2 (count reps)))
      (is (every? :representative (vals reps)))
      (is (every? :cluster (vals reps))))))

(deftest extract-representatives-picks-first-member-test
  (testing "Representative is each cluster's first member (G3: hardness ranking
            retired, no better than random — validation-results.md §5.3)"
    (let [clusters {"c1" [{:id 1 :operator :swap-lt-gt}
                          {:id 2 :operator :swap-lt-lte}]}
          reps (clustering/extract-representatives clusters)
          c1-rep (get-in reps ["c1" :representative])]
      (is (= 1 (:id c1-rep))
          "Arbitrary-but-deterministic: the cluster's first member"))))

;; =============================================================================
;; prepare-clustered-mutations Tests
;; =============================================================================

(deftest prepare-clustered-mutations-none-test
  (testing "Returns proper structure with :none strategy"
    (let [result (clustering/prepare-clustered-mutations mock-mutations :none)]
      (is (map? (:clusters result)))
      (is (map? (:representatives result)))
      (is (vector? (:to-test result)))
      (is (map? (:stats result)))
      (is (= (count mock-mutations) (count (:to-test result)))
          "With :none, all mutations should be tested"))))

(deftest prepare-clustered-mutations-operator-test
  (testing "Reduces testing count with :operator strategy"
    (let [result (clustering/prepare-clustered-mutations mock-mutations :operator)]
      (is (< (count (:to-test result)) (count mock-mutations))
          "Should reduce number of mutations to test"))))

(deftest prepare-clustered-mutations-stats-test
  (testing "Includes clustering statistics"
    (let [result (clustering/prepare-clustered-mutations mock-mutations :operator)]
      (is (pos? (get-in result [:stats :total-mutations])))
      (is (pos? (get-in result [:stats :cluster-count])))
      (is (number? (get-in result [:stats :reduction-percentage]))))))

;; =============================================================================
;; apply-results-to-clusters Tests
;; =============================================================================

(deftest apply-results-to-clusters-test
  (testing "Applies results and expands to all mutations"
    (let [m1 {:id #uuid "00000000-0000-0000-0000-000000000001"
              :operator :swap-lt-lte}
          m2 {:id #uuid "00000000-0000-0000-0000-000000000002"
              :operator :swap-lt-gt}
          representatives {"c1" {:representative m1
                                 :cluster [m1 m2]}}
          results [{:mutation m1
                    :status :killed
                    :killed-by 'test/assertion
                    :test-durations {}
                    :tests-run #{}}]
          expanded (clustering/apply-results-to-clusters representatives results)]
      (is (= 2 (count expanded)))
      (is (every? #(= :killed (:status %)) expanded)))))

;; =============================================================================
;; Strategy Selection Tests
;; =============================================================================

(deftest recommended-strategy-small-test
  (testing "Recommends :none for small mutation counts"
    (is (= :none (clustering/recommended-strategy 10)))
    (is (= :none (clustering/recommended-strategy 49)))))

(deftest recommended-strategy-medium-test
  (testing "Recommends :location for medium mutation counts"
    (is (= :location (clustering/recommended-strategy 50)))
    (is (= :location (clustering/recommended-strategy 150)))))

(deftest recommended-strategy-large-test
  (testing "Recommends :operator for large mutation counts"
    (is (= :operator (clustering/recommended-strategy 200)))
    (is (= :operator (clustering/recommended-strategy 400)))))

(deftest recommended-strategy-very-large-test
  (testing "Recommends :similarity for very large mutation counts"
    (is (= :similarity (clustering/recommended-strategy 500)))
    (is (= :similarity (clustering/recommended-strategy 1000)))))

;; =============================================================================
;; validate-strategy Tests
;; =============================================================================

(deftest validate-strategy-valid-test
  (testing "Valid strategies pass validation"
    (is (= :none (clustering/validate-strategy :none)))
    (is (= :operator (clustering/validate-strategy :operator)))
    (is (= :location (clustering/validate-strategy :location)))
    (is (= :similarity (clustering/validate-strategy :similarity)))))

(deftest validate-strategy-invalid-test
  (testing "Invalid strategies throw exception"
    (is (thrown? clojure.lang.ExceptionInfo
                 (clustering/validate-strategy :invalid)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (clustering/validate-strategy :unknown)))))

;; =============================================================================
;; strategy-descriptions Tests
;; =============================================================================

(deftest strategy-descriptions-defined-test
  (testing "All strategies have descriptions"
    (is (string? (get clustering/strategy-descriptions :none)))
    (is (string? (get clustering/strategy-descriptions :operator)))
    (is (string? (get clustering/strategy-descriptions :location)))
    (is (string? (get clustering/strategy-descriptions :similarity)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest full-clustering-workflow-test
  (testing "Complete clustering workflow"
    (let [;; Step 1: Prepare clustered mutations
          prepared (clustering/prepare-clustered-mutations mock-mutations :operator)

          ;; Step 2: Simulate testing representatives
          mock-results (mapv (fn [m]
                               {:mutation m
                                :status (if (= :swap-plus-minus (:operator m))
                                          :killed
                                          :survived)
                                :killed-by (when (= :swap-plus-minus (:operator m))
                                             'test/arithmetic)
                                :test-durations {}
                                :tests-run #{}})
                             (:to-test prepared))

          ;; Step 3: Expand results
          expanded (clustering/apply-results-to-clusters
                    (:representatives prepared)
                    mock-results)]

      ;; Verify all mutations have results
      (is (= (count mock-mutations) (count expanded))
          "All mutations should have results")

      ;; Verify some are inferred
      (is (some :inferred? expanded)
          "Some results should be inferred")

      ;; Verify the counts make sense
      (let [killed (filter #(= :killed (:status %)) expanded)
            survived (filter #(= :survived (:status %)) expanded)]
        (is (seq killed) "Should have some killed mutations")
        (is (seq survived) "Should have some survived mutations")))))

(deftest clustering-preserves-all-mutations-test
  (testing "Clustering never loses mutations"
    (doseq [strategy [:none :operator :location :similarity]]
      (let [prepared (clustering/prepare-clustered-mutations mock-mutations strategy)
            all-in-clusters (reduce concat (vals (:clusters prepared)))]
        (is (= (count mock-mutations) (count all-in-clusters))
            (str "Strategy " strategy " should preserve all mutations"))))))
