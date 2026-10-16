(ns pulse.design.format-test
  "How the mock-ups write times and numbers: in the zone of the clinic, in English, and the same on every machine."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.design.format :as fmt]))

(def ^:private now "2026-10-13T12:00:00Z")

(defn- clock-of [millis]
  (fmt/clock (str (java.time.Instant/ofEpochMilli millis))))

(deftest a-time-is-the-time-of-the-clinic
  (is (= "10:00" (fmt/clock "2026-10-13T08:00:00Z")) "summer time: two hours ahead of UTC")
  (is (= "09:00" (fmt/clock "2026-12-01T08:00:00Z")) "winter time: one hour")
  (is (= "Oct 13" (fmt/short-date "2026-10-13T08:00:00Z")))
  (testing "the date is the date in Madrid, not in UTC"
    (is (= "Oct 14" (fmt/short-date "2026-10-13T22:30:00Z")))
    (is (= "00:30" (fmt/clock "2026-10-13T22:30:00Z")))))

(deftest dates-are-written-in-full-in-english
  (is (= "Tuesday, 13 October 2026" (fmt/long-date "2026-10-13T12:00:00Z")))
  (is (= "Thursday, 14 March 1968" (fmt/long-date "1968-03-14")))
  (is (= "14 March 1968" (fmt/birth-date "1968-03-14"))))

(deftest a-day-is-today-yesterday-tomorrow-or-its-date
  (is (= "Today" (fmt/day-label "2026-10-13T08:00:00Z" now)))
  (is (= "Yesterday" (fmt/day-label "2026-10-12T20:00:00Z" now)))
  (is (= "Tomorrow" (fmt/day-label "2026-10-14T08:00:00Z" now)))
  (is (= "Oct 10" (fmt/day-label "2026-10-10T08:00:00Z" now)))
  (testing "by the day in Madrid: 22:30 UTC is already the next day there"
    (is (= "Today" (fmt/day-label "2026-10-12T22:30:00Z" now)))
    (is (= "Tomorrow" (fmt/day-label "2026-10-13T22:30:00Z" now))))
  (is (= "Today, 10:00" (fmt/when-text "2026-10-13T08:00:00Z" now)))
  (is (= "Yesterday, 22:02" (fmt/when-text "2026-10-12T20:02:00Z" now)))
  (is (= "Oct 10, 10:00" (fmt/when-text "2026-10-10T08:00:00Z" now))))

(deftest an-age-is-in-whole-years-on-the-day
  (is (= 58 (fmt/age "1968-03-14" now)) "the birthday has passed this year")
  (is (= 57 (fmt/age "1968-12-01" now)) "and this one has not")
  (is (= 58 (fmt/age "1968-10-13" now)) "on the day itself")
  (is (= 57 (fmt/age "1968-10-14" now)) "the day before")
  (is (= 0 (fmt/age "2026-06-01" now))))

(deftest numbers-and-units-are-written-the-way-people-do
  (is (= ["138" "82.4" "70" "1.5" "0"] (map fmt/number [138 82.4 70.0 1.5 0])))
  (is (= "-3" (fmt/number -3.0)))
  (is (= ["mmHg" "bpm" "%" "mg/dL" "kg" "°C"] (map fmt/unit ["mm[Hg]" "/min" "%" "mg/dL" "kg" "Cel"])))
  (is (= "mol" (fmt/unit "mol")) "a unit it does not know is left as it is"))

(deftest an-instant-is-in-epoch-milliseconds
  (is (= 1760356800000 (fmt/millis "2025-10-13T12:00:00Z")))
  (is (= 0 (fmt/millis "1970-01-01T00:00:00Z"))))

(deftest the-days-of-an-axis-are-midnights-of-the-clinic
  (let [from (fmt/millis "2026-10-07T12:00:00Z")
        to (fmt/millis "2026-10-13T12:00:00Z")
        ticks (fmt/day-ticks from to 1)]
    (is (= ["Oct 8" "Oct 9" "Oct 10" "Oct 11" "Oct 12" "Oct 13"] (map :label ticks))
        "from the first midnight after the start; none before it")
    (is (every? #(= "00:00" (clock-of (:at %))) ticks))
    (is (every? #(<= from (:at %) to) ticks))
    (is (= ["Oct 9" "Oct 11" "Oct 13"] (map :label (fmt/day-ticks from to 2))) "every second day")
    (is (= (sort (map :at ticks)) (map :at ticks)) "in order"))
  (testing "a day that is 25 hours long is still one tick, at midnight"
    (let [ticks (fmt/day-ticks (fmt/millis "2026-10-25T00:00:00Z") (fmt/millis "2026-10-27T12:00:00Z") 1)]
      (is (= ["Oct 26" "Oct 27"] (map :label ticks)))
      (is (every? #(= "00:00" (clock-of (:at %))) ticks))))
  (testing "a range of less than a day may have none"
    (is (= [] (fmt/day-ticks (fmt/millis "2026-10-13T08:00:00Z") (fmt/millis "2026-10-13T12:00:00Z") 1)))))

(deftest the-hours-of-an-axis-are-on-the-clock-of-the-clinic
  (let [ticks (fmt/hour-ticks (fmt/millis "2026-10-12T12:00:00Z") (fmt/millis "2026-10-13T12:00:00Z") 6)]
    (is (= ["18:00" "00:00" "06:00" "12:00"] (map :label ticks)))
    (is (= [21600000] (distinct (map - (rest (map :at ticks)) (map :at ticks)))) "six hours apart"))
  (is (= ["00:00" "03:00" "06:00" "09:00" "12:00"]
         (map :label (fmt/hour-ticks (fmt/millis "2026-10-12T22:00:00Z") (fmt/millis "2026-10-13T10:00:00Z") 3)))))
