(ns pulse.design.format-test
  "How the mock-ups of the workspace say what the scheduler reports: intervals, schedules, durations and the names of numbers."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.design.format :as fmt]))

(deftest an-interval-is-said-the-way-people-say-it
  (is (= ["Every second" "Every 15 seconds" "Every 30 seconds" "Every minute" "Every 5 minutes" "Every hour" "Every 2 hours"]
         (map fmt/interval-text [1000 15000 30000 60000 300000 3600000 7200000])))
  (testing "a number that is not whole in a unit is said in the smaller one"
    (is (= "Every 90 seconds" (fmt/interval-text 90000)))
    (is (= "Every 1500 ms" (fmt/interval-text 1500)))
    (is (= "Every 61 seconds" (fmt/interval-text 61000)))
    (is (= "Every 150 minutes" (fmt/interval-text 9000000)) "two hours and a half")))

(deftest a-schedule-is-an-interval-or-a-time-of-day
  (is (= "Every 30 seconds" (fmt/schedule-text {:type "interval" :everyMs 30000})))
  (is (= "Every day at 08:00 (Europe/Madrid)" (fmt/schedule-text {:type "daily" :at "08:00" :zone "Europe/Madrid"}))))

(deftest a-duration-is-in-milliseconds-seconds-with-a-decimal-or-whole-seconds
  (is (= ["0 ms" "620 ms" "999 ms" "1.0 s" "1.8 s" "9.9 s" "10 s" "10 s" "65 s"]
         (map fmt/duration-text [0 620 999 1000 1840 9900 10012 9950 65000]))))

(deftest a-key-in-camel-case-is-written-as-words
  (is (= ["already raised" "patients" "not applicable" "still active"]
         (map fmt/words [:alreadyRaised :patients :notApplicable "stillActive"])))
  (is (= "ok2 go" (fmt/words :ok2Go)) "a digit followed by a capital is a break too"))
