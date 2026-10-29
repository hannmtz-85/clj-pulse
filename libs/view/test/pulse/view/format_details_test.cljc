(ns pulse.view.format-details-test
  "What a mutation analysis found the tests of the formats did not notice: the language whatever the machine's, the
  day in Madrid and not in UTC, and the two ends of the ranges of the ticks."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.format :as fmt]))

(defn- clock-of [millis]
  (fmt/clock (fmt/instant-text millis)))

(deftest a-day-of-the-month-has-one-digit-when-it-has-one
  (is (= "Oct 5" (fmt/short-date "2026-10-05T10:00:00Z")))
  (is (= "Oct 5, 12:00" (fmt/when-text "2026-10-05T10:00:00Z" "2026-10-13T12:00:00Z")))
  (is (= ["Oct 8" "Oct 9"] (map :label (fmt/day-ticks (fmt/millis "2026-10-07T12:00:00Z") (fmt/millis "2026-10-09T12:00:00Z") 1)))))

(deftest a-date-is-the-date-in-madrid-and-not-in-utc
  (is (= "Wednesday, 14 October 2026" (fmt/long-date "2026-10-13T22:30:00Z")) "22:30 UTC is 00:30 of the next day there")
  (testing "and today is the day of now in Madrid"
    (is (= "Today" (fmt/day-label "2026-10-13T08:00:00Z" "2026-10-12T23:30:00Z")) "now is already the 13th in Madrid")
    (is (= "Yesterday" (fmt/day-label "2026-10-12T08:00:00Z" "2026-10-12T23:30:00Z")))))

(deftest a-day-tick-on-the-start-or-on-the-end-is-kept
  (let [midnight-oct-8 (fmt/millis "2026-10-07T22:00:00Z")
        midnight-oct-10 (fmt/millis "2026-10-09T22:00:00Z")
        ticks (fmt/day-ticks midnight-oct-8 midnight-oct-10 1)]
    (is (= ["Oct 8" "Oct 9" "Oct 10"] (map :label ticks)) "both ends are midnights of the clinic")
    (is (= [midnight-oct-8 midnight-oct-10] [(:at (first ticks)) (:at (last ticks))]))
    (is (every? #(= "00:00" (clock-of (:at %))) ticks)))
  (testing "one millisecond inside and one outside"
    (let [from (fmt/millis "2026-10-07T22:00:00Z")]
      (is (= ["Oct 9" "Oct 10"] (map :label (fmt/day-ticks (inc from) (fmt/millis "2026-10-09T22:00:00Z") 1))) "the start is just after the midnight of the 8th")
      (is (= ["Oct 8" "Oct 9"] (map :label (fmt/day-ticks from (dec (fmt/millis "2026-10-09T22:00:00Z")) 1))) "the end is just before the midnight of the 10th"))))

(deftest an-hour-tick-before-the-start-is-left-out-and-one-on-the-start-is-kept
  (let [on-the-half (fmt/millis "2026-10-13T08:30:00Z")
        on-the-hour (fmt/millis "2026-10-13T09:00:00Z")]
    (is (= ["11:00" "12:00" "13:00"] (map :label (fmt/hour-ticks on-the-half (fmt/millis "2026-10-13T11:30:00Z") 1)))
        "10:00 is before 10:30")
    (is (= ["11:00" "12:00" "13:00"] (map :label (fmt/hour-ticks on-the-hour (fmt/millis "2026-10-13T11:00:00Z") 1)))
        "from 11:00 to 13:00 in Madrid, both ends")
    (is (= ["12:00"] (map :label (fmt/hour-ticks on-the-half (fmt/millis "2026-10-13T11:30:00Z") 12)))
        "every twelve hours, as the clock of the clinic says")))
