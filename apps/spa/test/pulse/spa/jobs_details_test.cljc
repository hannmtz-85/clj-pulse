(ns pulse.spa.jobs-details-test
  "What a mutation analysis of `pulse.spa.jobs` found that the first tests did not look at: the order in which a result is said when it holds
  all the keys the jobs report, that only a keyword is the name of a count, that a time needs a moment to be said against, and that the runs
  of a job are those of its counts and not of its schedule. Pure, so it runs on the JVM and on Node over the same data."
  (:require [clojure.test :refer [deftest is]]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.test.jobs-fixtures :as fx]))

(defn- row-of
  ([job] (row-of job fx/now))
  ([job now] (first (:rows (jobs/model {:now now :list (fx/loaded {:total 1 :items [job]})})))))

(defn- result-text [result]
  (get-in (row-of (assoc-in fx/evaluate-rules [:lastRun :result] result)) [:last-run :result]))

(deftest what-a-run-reported-is-said-in-the-order-the-jobs-report-it
  (is (= (str "1 patient, 2 appointments, 3 planned, 4 read, 5 readings, 6 raised, 7 written, 8 sent, 9 already raised, "
              "10 already sent, 11 still active, 12 not applicable, 13 abnormal, 14 batches, 15 failed, 16 failed batches, 17 errors")
         (result-text (zipmap [:errors :failedBatches :failed :batches :abnormal :notApplicable :stillActive :alreadySent :alreadyRaised
                               :sent :written :raised :readings :read :planned :appointments :patients]
                              (range 17 0 -1))))
      "from a map that holds them in the reverse order")
  (is (= "3 patients, 2 alpha, 1 zeta" (result-text {:zeta 1 :alpha 2 :patients 3}))
      "a key nobody knows follows the known ones, in alphabetical order"))

(deftest only-a-keyword-is-the-name-of-a-count
  (is (= "3 patients" (result-text {:patients 3 "readings" 4 5 6})) "the api's keys are keywords once the answer is read")
  (is (nil? (result-text {"patients" 3})) "a result with no such key reports nothing")
  (is (nil? (result-text {:patients "3" :note "none"})) "and neither does one whose values are not numbers"))

(deftest a-time-is-not-said-without-a-moment-to-say-it-against
  (let [rows (:rows (jobs/model {:now nil :list (fx/loaded fx/body)}))]
    (is (= 3 (count rows)))
    (is (every? nil? (map :next-run rows)) "when the jobs are due")
    (is (every? nil? (map :since rows)) "since when one is running")
    (is (every? nil? (map #(get-in % [:last-run :started]) rows)) "when the last run began")))

(deftest the-runs-of-a-job-are-those-of-its-counts-and-not-of-its-schedule
  (is (= "214 ok, 0 failed, 0 interrupted, 0 skipped" (:runs (row-of (dissoc fx/evaluate-rules :schedule)))) "with no schedule to tell of")
  (is (nil? (:schedule (row-of (dissoc fx/evaluate-rules :schedule)))))
  (is (nil? (:runs (row-of (dissoc fx/evaluate-rules :counts)))) "and with no counts there are no runs to tell of")
  (is (= "Every 30 seconds" (:schedule (row-of (dissoc fx/evaluate-rules :counts))))))

(deftest a-count-of-one-is-said-in-the-singular
  (is (= "1 patient, 0 raised, 1 already raised" (result-text {:patients 1 :raised 0 :alreadyRaised 1})))
  (is (= "1 appointment, 1 sent, 0 already sent, 0 failed" (result-text {:appointments 1 :sent 1 :alreadySent 0 :failed 0})))
  (is (= "2 appointments, 1 sent" (result-text {:appointments 2 :sent 1}))))
