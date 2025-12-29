(ns heretic.incremental-test
  "Tests for incremental mutation testing support."
  (:require [clojure.test :refer [deftest is testing]]
            [heretic.incremental :as inc]))

(deftest compute-hash-test
  (testing "Hash computation"
    (is (string? (inc/compute-hash "test")))
    (is (= (inc/compute-hash "test") (inc/compute-hash "test")))
    (is (not= (inc/compute-hash "test1") (inc/compute-hash "test2")))))

(deftest compute-form-hash-test
  (testing "Form hash with nil"
    (is (nil? (inc/compute-form-hash nil)))))

(deftest filter-mutations-by-changes-test
  (testing "Filter mutations when no changes"
    (let [mutations [{:file "a.clj" :form-id 0}
                     {:file "b.clj" :form-id 1}]
          change-data {:changed-files #{}
                       :changed-forms {}}
          result (inc/filter-mutations-by-changes mutations change-data)]
      (is (empty? (:mutations result)))
      (is (= 2 (:skipped-count result)))
      (is (= :no-changes (:reason result)))))

  (testing "Filter mutations to changed forms only"
    (let [mutations [{:file "a.clj" :form-id 0}
                     {:file "a.clj" :form-id 1}
                     {:file "b.clj" :form-id 0}]
          change-data {:changed-files #{"a.clj"}
                       :changed-forms {"a.clj" #{0}}}
          result (inc/filter-mutations-by-changes mutations change-data)]
      (is (= 1 (count (:mutations result))))
      (is (= {:file "a.clj" :form-id 0} (first (:mutations result))))
      (is (= 2 (:skipped-count result)))
      (is (= :incremental (:reason result)))))

  (testing "Include mutations when form-id is nil"
    (let [mutations [{:file "a.clj" :form-id nil}]
          change-data {:changed-files #{"a.clj"}
                       :changed-forms {"a.clj" #{0}}}
          result (inc/filter-mutations-by-changes mutations change-data)]
      (is (= 1 (count (:mutations result)))))))

(deftest incremental-stats-test
  (testing "Statistics calculation"
    (let [change-data {:changed-files #{"a.clj" "b.clj"}
                       :new-files #{"b.clj"}
                       :deleted-files #{"c.clj"}}
          stats (inc/incremental-stats change-data 100 30)]
      (is (= 2 (:changed-file-count stats)))
      (is (= 1 (:new-file-count stats)))
      (is (= 1 (:deleted-file-count stats)))
      (is (= 100 (:original-mutations stats)))
      (is (= 30 (:filtered-mutations stats)))
      (is (= 70 (:skipped-mutations stats)))
      (is (= 70.0 (:savings-percentage stats))))))
