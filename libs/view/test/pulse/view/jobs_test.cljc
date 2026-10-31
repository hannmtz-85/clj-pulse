(ns pulse.view.jobs-test
  "How the interfaces say what the scheduler reports: intervals, schedules, durations and the names of numbers. The same words on the
  JVM and in the browser, and no failure for what the scheduler did not say."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.jobs :as jobs]))

(deftest an-interval-is-said-the-way-people-say-it
  (is (= ["Every second" "Every 15 seconds" "Every 30 seconds" "Every minute" "Every 5 minutes" "Every hour" "Every 2 hours"]
         (map jobs/interval-text [1000 15000 30000 60000 300000 3600000 7200000])))
  (testing "a number that is not whole in a unit is said in the smaller one"
    (is (= "Every 90 seconds" (jobs/interval-text 90000)))
    (is (= "Every 1500 ms" (jobs/interval-text 1500)))
    (is (= "Every 61 seconds" (jobs/interval-text 61000)))
    (is (= "Every 150 minutes" (jobs/interval-text 9000000)) "two hours and a half")))

(deftest an-interval-that-is-none-is-not-said
  (doseq [ms [nil "30000" 0 -1000 1.5 ##NaN :x [1000]]]
    (is (nil? (jobs/interval-text ms)) (pr-str ms))))

(deftest a-schedule-is-an-interval-or-a-time-of-day
  (is (= "Every 30 seconds" (jobs/schedule-text {:type "interval" :everyMs 30000})))
  (is (= "Every day at 08:00 (Europe/Madrid)" (jobs/schedule-text {:type "daily" :at "08:00" :zone "Europe/Madrid"}))))

(deftest a-schedule-that-is-none-is-not-said
  (doseq [schedule [nil {} {:type "weekly"} {:type "interval"} {:type "interval" :everyMs "x"}
                    {:type "daily"} {:type "daily" :at "08:00"} {:type "daily" :zone "Europe/Madrid"}
                    {:type "daily" :at "" :zone "Europe/Madrid"} {:type "daily" :at "08:00" :zone "  "}
                    {:type "daily" :at 800 :zone "Europe/Madrid"}]]
    (is (nil? (jobs/schedule-text schedule)) (pr-str schedule))))

(deftest a-duration-is-in-milliseconds-seconds-with-a-decimal-or-whole-seconds
  (is (= ["0 ms" "620 ms" "999 ms" "1.0 s" "1.8 s" "9.9 s" "10 s" "10 s" "65 s"]
         (map jobs/duration-text [0 620 999 1000 1840 9900 10012 9950 65000]))
      "a whole second under ten keeps its decimal, here and in the browser")
  (testing "the decimal rounds to the nearest tenth, and the boundary between the two forms is at 9 950"
    (is (= ["1.0 s" "1.0 s" "1.1 s" "9.9 s" "10 s"]
           (map jobs/duration-text [1049 1000 1050 9949 9950])))))

(deftest a-duration-that-is-none-is-not-said
  (doseq [ms [nil "620" -1 :x [620]]]
    (is (nil? (jobs/duration-text ms)) (pr-str ms))))

(deftest a-key-in-camel-case-is-written-as-words
  (is (= ["already raised" "patients" "not applicable" "still active"]
         (map jobs/words [:alreadyRaised :patients :notApplicable "stillActive"])))
  (is (= "ok2 go" (jobs/words :ok2Go)) "a digit followed by a capital is a break too"))

(deftest a-count-is-said-with-the-name-of-what-was-counted-in-the-singular-for-one
  (is (= ["6 patients" "1 patient" "0 patients"] (map #(jobs/counted :patients %) [6 1 0])))
  (is (= ["2 appointments" "1 appointment"] (map #(jobs/counted :appointments %) [2 1])))
  (is (= ["3 readings" "1 reading"] (map #(jobs/counted :readings %) [3 1])))
  (is (= ["2 batches" "1 batch"] (map #(jobs/counted :batches %) [2 1])))
  (is (= ["2 errors" "1 error"] (map #(jobs/counted :errors %) [2 1])))
  (is (= ["2 failed batches" "1 failed batch"] (map #(jobs/counted :failedBatches %) [2 1])) "a key in camel case is words first")
  (testing "what is not a noun is the same for any number"
    (is (= ["1 raised" "2 raised"] (map #(jobs/counted :raised %) [1 2])))
    (is (= ["1 already sent" "1 still active" "1 not applicable" "1 failed" "1 read" "1 written"]
           (map #(jobs/counted % 1) [:alreadySent :stillActive :notApplicable :failed :read :written]))))
  (testing "only one is one"
    (is (= "2 patients" (jobs/counted :patients 2)))
    (is (= "11 patients" (jobs/counted :patients 11)))
    (is (= "1.5 patients" (jobs/counted :patients 1.5)))))
