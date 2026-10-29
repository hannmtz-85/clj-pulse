(ns pulse.view.ticks-test
  "The axis of time of a chart and the value of a field for a date and a time (ADR-0028): ticks on the clock of the clinic between
  two moments, whatever the minutes they begin at, and a moment written the way a browser writes it."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.format :as fmt]))

(deftest the-last-hour-of-an-axis-is-there-whatever-the-minutes-it-begins-at
  (testing "a stretch that begins in the middle of an hour has the hours that follow, up to the last one"
    (is (= ["12:00" "13:00" "14:00"]
           (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T09:30:00Z") (fmt/millis "2026-10-13T12:00:00Z") 1))))
    (is (= ["12:00" "14:00"]
           (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T09:30:00Z") (fmt/millis "2026-10-13T12:00:00Z") 2)))))
  (testing "and none beyond it"
    (is (= ["12:00" "13:00"]
           (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T09:30:00Z") (fmt/millis "2026-10-13T11:59:59Z") 1))))
    (is (= ["14:00"] (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T11:30:00Z") (fmt/millis "2026-10-13T12:00:00Z") 1)))
        "one that is the end itself")
    (is (= [] (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T11:00:01Z") (fmt/millis "2026-10-13T11:59:59Z") 1))))))

(deftest the-minutes-of-an-axis-are-on-the-clock-of-the-clinic
  (let [from (fmt/millis "2026-10-13T11:50:00Z")
        to (fmt/millis "2026-10-13T12:00:00Z")
        ticks (fmt/minute-ticks from to 5)]
    (is (= ["13:50" "13:55" "14:00"] (map :label ticks)))
    (is (= ["13:50" "14:00"] (map :label (fmt/minute-ticks from to 10))))
    (is (= ["14:00"] (map :label (fmt/minute-ticks from to 15))))
    (is (= [300000] (distinct (map - (rest (map :at ticks)) (map :at ticks)))) "five minutes apart"))
  (testing "from the first one after the start to the last one before the end, both included when they are on the minute"
    (is (= ["13:50" "13:55" "14:00"]
           (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:48:30Z") (fmt/millis "2026-10-13T12:00:30Z") 5))))
    (is (= [] (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:56:00Z") (fmt/millis "2026-10-13T11:59:00Z") 5)))))
  (testing "at the hour of the clinic, and not of Greenwich"
    (is (= ["02:00"] (map :label (fmt/minute-ticks (fmt/millis "2026-01-15T00:55:00Z") (fmt/millis "2026-01-15T01:05:00Z") 60)))
        "winter: one hour ahead")
    (is (= ["03:00"] (map :label (fmt/minute-ticks (fmt/millis "2026-07-15T00:55:00Z") (fmt/millis "2026-07-15T01:05:00Z") 60)))
        "summer: two")))

(deftest a-time-for-a-field-for-a-date-and-a-time-is-the-time-on-the-clock-of-the-clinic-to-the-minute
  (is (= "2026-10-13T14:00" (fmt/local-input "2026-10-13T12:00:00Z")))
  (is (= "2026-10-13T14:00" (fmt/local-input "2026-10-13T12:00:59Z")) "the seconds are left out")
  (is (= "2026-01-15T09:05" (fmt/local-input "2026-01-15T08:05:00Z")) "winter")
  (is (= "2026-07-15T10:05" (fmt/local-input "2026-07-15T08:05:00Z")) "summer")
  (is (= "2026-10-14T00:30" (fmt/local-input "2026-10-13T22:30:00Z")) "the day of the clinic, and not of Greenwich")
  (testing "the times of the night the clocks go back are told as they were, and 02:30 is the time of two moments"
    (is (= "2026-10-25T01:59" (fmt/local-input "2026-10-24T23:59:00Z")))
    (is (= "2026-10-25T02:30" (fmt/local-input "2026-10-25T00:30:00Z")))
    (is (= "2026-10-25T02:30" (fmt/local-input "2026-10-25T01:30:00Z")))
    (is (= "2026-10-25T03:00" (fmt/local-input "2026-10-25T02:00:00Z"))))
  (testing "and the night they go forward skips 02:xx"
    (is (= "2026-03-29T01:59" (fmt/local-input "2026-03-29T00:59:00Z")))
    (is (= "2026-03-29T03:00" (fmt/local-input "2026-03-29T01:00:00Z")))))
