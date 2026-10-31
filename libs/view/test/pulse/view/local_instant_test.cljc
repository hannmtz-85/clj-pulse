(ns pulse.view.local-instant-test
  "A date and a time on the clock of the clinic, as an instant (`fmt/local->instant`, the way back of `fmt/local-input`): what a person types
  in the fields of a form for them, which has no zone, becomes what the api reads. The JVM reads it with the rule of java.time for a time with
  no zone and the browser finds it from the offsets of the clinic's clock, so everything here runs on both, and
  `pulse.view.parity-test` holds them to each other around every change of the clocks of seven years."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.view.format :as fmt]))

(deftest a-time-is-the-time-on-the-clock-of-the-clinic
  (is (= "2026-10-13T08:00:00Z" (fmt/local->instant "2026-10-13T10:00")) "summer time: two hours ahead of UTC")
  (is (= "2026-12-01T09:00:00Z" (fmt/local->instant "2026-12-01T10:00")) "winter time: one hour")
  (is (= "2026-10-12T22:30:00Z" (fmt/local->instant "2026-10-13T00:30")) "the date in Madrid is not the date in UTC")
  (is (= "2026-10-13T08:00:30Z" (fmt/local->instant "2026-10-13T10:00:30")) "seconds are kept")
  (is (= "2026-10-13T08:00:30Z" (fmt/local->instant "2026-10-13T10:00:30.987654321")) "and the fraction of a second is dropped")
  (is (= "2026-10-13T08:00:30Z" (fmt/local->instant "2026-10-13T10:00:30.5")) "whatever its length"))

(deftest an-hour-the-clocks-skip-is-the-same-time-after-the-gap
  (testing "on 29 March 2026 the clocks go from 02:00 to 03:00, and there is no 02:30"
    (is (= "2026-03-29T00:59:00Z" (fmt/local->instant "2026-03-29T01:59")) "the last minute before the gap")
    (is (= "2026-03-29T01:00:00Z" (fmt/local->instant "2026-03-29T02:00")) "the first minute of the gap is 03:00")
    (is (= "2026-03-29T01:30:00Z" (fmt/local->instant "2026-03-29T02:30")) "02:30 is 03:30, an hour later on the wall")
    (is (= "2026-03-29T01:59:59Z" (fmt/local->instant "2026-03-29T02:59:59")))
    (is (= "2026-03-29T01:00:00Z" (fmt/local->instant "2026-03-29T03:00")) "and 03:00 is the same moment as 02:00")
    (is (= "2026-03-29T02:00:00Z" (fmt/local->instant "2026-03-29T04:00")))))

(deftest an-hour-the-clocks-pass-twice-is-the-first-time-it-happens
  (testing "on 25 October 2026 the clocks go from 03:00 back to 02:00, and 02:30 happens twice"
    (is (= "2026-10-24T23:59:00Z" (fmt/local->instant "2026-10-25T01:59")) "the last minute before the hour that is repeated")
    (is (= "2026-10-25T00:00:00Z" (fmt/local->instant "2026-10-25T02:00")) "the first 02:00, in summer time")
    (is (= "2026-10-25T00:30:00Z" (fmt/local->instant "2026-10-25T02:30")) "the first 02:30, not the one an hour later")
    (is (= "2026-10-25T00:59:59Z" (fmt/local->instant "2026-10-25T02:59:59")))
    (is (= "2026-10-25T02:00:00Z" (fmt/local->instant "2026-10-25T03:00")) "03:00 is in winter time and happens once")
    (is (= "2026-10-25T03:00:00Z" (fmt/local->instant "2026-10-25T04:00")))))

(deftest the-same-is-true-in-the-other-years
  (testing "2027: the last Sundays of March and October are the 28th and the 31st"
    (is (= "2027-03-28T01:00:00Z" (fmt/local->instant "2027-03-28T02:00")))
    (is (= "2027-03-28T01:30:00Z" (fmt/local->instant "2027-03-28T02:30")))
    (is (= "2027-10-30T23:30:00Z" (fmt/local->instant "2027-10-31T01:30")))
    (is (= "2027-10-31T00:30:00Z" (fmt/local->instant "2027-10-31T02:30")) "the first of the two"))
  (testing "the day before and the day after a change are ordinary days"
    (is (= "2026-03-28T01:30:00Z" (fmt/local->instant "2026-03-28T02:30")))
    (is (= "2026-03-30T00:30:00Z" (fmt/local->instant "2026-03-30T02:30")))
    (is (= "2026-10-24T00:30:00Z" (fmt/local->instant "2026-10-24T02:30")))
    (is (= "2026-10-26T01:30:00Z" (fmt/local->instant "2026-10-26T02:30")))))

(deftest what-is-not-a-time-on-the-clock-is-nothing
  (doseq [text [nil "" "soon" 5 :x ["2026-10-13T10:00"]
                "2026-10-13" "2026-10-13T" "T10:00" "2026-10-13T10" "2026-10-13T1:00"
                " 2026-10-13T10:00" "2026-10-13T10:00 " "2026-10-13 10:00" "2026-10-13t10:00"
                "2026-10-13T10:00Z" "2026-10-13T10:00+02:00" "26-10-13T10:00" "2026-1-13T10:00"
                "2026-10-13T10:00:30." "2026-10-13T10:00:30,5" "2026-10-13T10:00:30.1234567890"
                "999-10-13T10:00" "12026-10-13T10:00" "2026-10-13T10:00:3"]]
    (is (nil? (fmt/local->instant text)) (pr-str text))))

(deftest what-is-written-like-a-time-and-is-not-one-is-nothing
  (testing "a day that the calendar does not have"
    (doseq [text ["2026-02-29T10:00" "2026-02-30T10:00" "2026-04-31T10:00" "2026-13-01T10:00" "2026-00-10T10:00" "2026-10-00T10:00"
                  "2026-10-32T10:00" "1900-02-29T10:00" "2100-02-29T10:00"]]
      (is (nil? (fmt/local->instant text)) text)))
  (testing "a day that a leap year has"
    (is (= "2028-02-29T09:00:00Z" (fmt/local->instant "2028-02-29T10:00")))
    (is (= "1996-02-29T09:00:00Z" (fmt/local->instant "1996-02-29T10:00")))
    (is (= "2000-02-29T09:00:00Z" (fmt/local->instant "2000-02-29T10:00")) "a year of a century that a fourth hundred divides"))
  (testing "a moment that a clock does not have: not 24:00, not a leap second, not the sixtieth minute"
    (doseq [text ["2026-10-13T24:00" "2026-10-13T24:00:00" "2026-10-13T23:59:60" "2026-10-13T23:60" "2026-10-13T25:00"
                  "2026-10-13T10:60" "2026-10-13T10:00:61"]]
      (is (nil? (fmt/local->instant text)) text)))
  (testing "the edges of what a clock has"
    (is (= "2026-10-13T21:59:59Z" (fmt/local->instant "2026-10-13T23:59:59")))
    (is (= "2026-10-12T22:00:00Z" (fmt/local->instant "2026-10-13T00:00")))))

(deftest the-edges-of-the-calendar-are-read
  (is (= "2026-12-31T22:59:00Z" (fmt/local->instant "2026-12-31T23:59")) "the last minute of a year, in winter time")
  (is (= "2026-12-31T23:00:00Z" (fmt/local->instant "2027-01-01T00:00")))
  (is (= "2026-06-30T21:59:59Z" (fmt/local->instant "2026-06-30T23:59:59")))
  (is (= "1970-01-01T00:00:00Z" (fmt/local->instant "1970-01-01T01:00")) "the epoch is at 01:00 in Madrid")
  (is (= "1969-12-31T23:59:00Z" (fmt/local->instant "1970-01-01T00:59")) "and a minute before it is before it"))

(defspec the-clock-reads-back-the-time-it-was-given #?(:clj 300 :cljs 40)
  (prop/for-all [seconds (gen/choose 631152000 3155673600)]
    (let [iso (fmt/instant-text (* 1000 seconds))
          text (fmt/local-input iso)
          result (fmt/local->instant text)
          minute (let [ms (fmt/millis iso)] (- ms (mod ms 60000)))]
      (and (string? result)
           ;; the clock of the clinic reads the text back, to the minute and with no seconds
           (= text (fmt/local-input result))
           (zero? (mod (fmt/millis result) 60000))
           ;; it is the moment itself, or, when the clocks had gone back and the text is one that happens twice, the first of the two
           (contains? #{0 3600000} (- minute (fmt/millis result)))))))
