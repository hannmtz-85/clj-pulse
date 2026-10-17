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

;; ---------------------------------------------------------------------------
;; For the pages of the interfaces

(deftest a-day-with-its-weekday-a-month-and-a-day-of-the-month-for-a-calendar-page
  (is (= "Wed 14 Oct" (fmt/weekday-date "2026-10-14T08:00:00Z")))
  (is (= "Tue 29 Sep" (fmt/weekday-date "2026-09-29T07:00:00Z")))
  (is (= ["Oct" "Sep" "Dec"] (map fmt/month-abbreviation ["2026-10-14T08:00:00Z" "2026-09-29T07:00:00Z" "2026-12-01T08:00:00Z"])))
  (is (= ["14" "5" "29"] (map fmt/day-of-month ["2026-10-14T08:00:00Z" "2026-10-05T08:00:00Z" "2026-09-29T07:00:00Z"]))
      "no zero in front of a single digit")
  (testing "by the day in Madrid"
    (is (= "Thu 15 Oct" (fmt/weekday-date "2026-10-14T22:30:00Z")))
    (is (= "15" (fmt/day-of-month "2026-10-14T22:30:00Z")))
    (is (= "Nov" (fmt/month-abbreviation "2026-10-31T23:30:00Z")))))

(deftest a-time-range-is-two-clocks-and-a-date-range-two-dates
  (is (= "10:00–10:30" (fmt/time-range "2026-10-14T08:00:00Z" "2026-10-14T08:30:00Z")) "with an en dash")
  (is (= "23:30–00:15" (fmt/time-range "2026-10-14T21:30:00Z" "2026-10-14T22:15:00Z")))
  (is (= "Oct 14 to Oct 20" (fmt/date-range "2026-10-14T00:00:00Z" "2026-10-20T00:00:00Z"))))

(deftest a-month-is-written-with-its-year-from-an-instant-or-a-date
  (is (= "Nov 2025" (fmt/month-year "2025-11-04T09:00:00Z")))
  (is (= "May 2019" (fmt/month-year "2019-05-02")))
  (is (= "Jan 2026" (fmt/month-year "2025-12-31T23:30:00Z")) "an instant is the day in Madrid"))

(deftest the-part-of-the-day-is-by-the-clock-of-the-clinic
  (is (= ["morning" "afternoon" "evening"]
         (map fmt/part-of-day ["2026-10-13T06:00:00Z" "2026-10-13T12:00:00Z" "2026-10-13T19:00:00Z"])))
  (testing "the edges: noon and eight in the evening"
    (is (= ["morning" "afternoon"] (map fmt/part-of-day ["2026-10-13T09:59:00Z" "2026-10-13T10:00:00Z"])))
    (is (= ["afternoon" "evening"] (map fmt/part-of-day ["2026-10-13T17:59:00Z" "2026-10-13T18:00:00Z"])))))

(deftest a-moment-in-the-middle-of-a-sentence-has-a-small-letter-for-the-day-and-a-capital-for-a-date
  (is (= "today, 10:00" (fmt/when-phrase "2026-10-13T08:00:00Z" now)))
  (is (= "yesterday, 22:02" (fmt/when-phrase "2026-10-12T20:02:00Z" now)))
  (is (= "tomorrow, 10:00" (fmt/when-phrase "2026-10-14T08:00:00Z" now)))
  (is (= "Oct 10, 10:00" (fmt/when-phrase "2026-10-10T08:00:00Z" now)) "the month keeps its capital"))

(deftest the-seconds-of-a-clock-are-shown-for-the-jobs
  (is (= "14:00:30" (fmt/clock-seconds "2026-10-13T12:00:30Z")))
  (is (= "00:00:05" (fmt/clock-seconds "2026-10-13T22:00:05Z"))))

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
