(ns pulse.view.parity-test
  "The browser writes what the JVM writes (ADR-0029, point 2). `pulse.view.parity-table` is what `pulse.view.format` answers on the JVM,
  where java.time reads the calendar, for instants chosen to be hard: every quarter of an hour around each change of the clocks of
  seven years, the edges of every month, a spread of moments from the nineties to the sixties. This test is `.cljc`: it runs on the
  JVM, where it is true by construction and shows that the table is read right, and on Node, where `Intl.DateTimeFormat` reads the
  calendar and the same answers have to come out."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.format :as fmt]
            [pulse.view.parity-table :as parity]))

(def ^:private table parity/table)

(defn- difference
  "The rows of `rows` that `f` does not answer as they say (`f` takes a row and answers what it should be)."
  [rows f]
  (vec (remove #(= % (f %)) rows)))

(deftest the-table-is-there-and-is-big-enough-to-mean-something
  (is (< 500 (count (:instants table))))
  (is (< 100 (count (:day-labels table))))
  (is (< 50 (count (:ages table))))
  (is (< 15 (count (:dates table))))
  (is (< 25 (count (:instant? table))))
  (is (= 1461 (count (:day-starts table))) "every day of 2025 to 2028")
  (is (< 20 (count (:ticks table))))
  (is (< 10 (count (:numbers table))))
  (testing "it holds the hard moments: the quarter hours around a change of the clocks, in both directions"
    (let [isos (set (map first (:instants table)))]
      (is (contains? isos "2026-03-29T01:00:00Z") "the clocks go forward")
      (is (contains? isos "2026-10-25T01:00:00Z") "the clocks go back")
      (is (contains? isos "2026-10-25T00:30:00Z") "and the half hour before, which has the same time on the clock as the one after")
      (is (contains? isos "2026-10-25T01:30:00Z")))))

(deftest every-instant-is-read-and-written-as-on-the-jvm
  (is (= [] (difference (:instants table)
                        (fn [[iso]]
                          [iso (fmt/millis iso) (fmt/clock iso) (fmt/clock-seconds iso) (fmt/short-date iso) (fmt/long-date iso)
                           (fmt/weekday-date iso) (fmt/local-input iso) (fmt/part-of-day iso) (fmt/last-date iso)])))))

(deftest a-day-is-called-as-on-the-jvm-from-any-other-day
  (is (= [] (difference (:day-labels table)
                        (fn [[iso now]] [iso now (fmt/day-label iso now) (fmt/when-text iso now) (fmt/when-phrase iso now)])))))

(deftest an-age-is-the-same
  (is (= [] (difference (:ages table) (fn [[birth now]] [birth now (fmt/age birth now)])))))

(deftest a-date-of-birth-is-written-the-same
  (is (= [] (difference (:dates table) (fn [[date]] [date (fmt/long-date date) (fmt/birth-date date) (fmt/month-year date)])))))

(deftest an-instant-is-read-to-the-millisecond-whatever-its-offset-and-its-fraction
  (is (< 20 (count (:parsed table))))
  (is (= [] (difference (:parsed table) (fn [[text]] [text (fmt/millis text)])))))

(deftest a-text-is-an-instant-or-a-date-for-both
  (is (= [] (difference (:instant? table) (fn [[text]] [text (fmt/instant? text)]))))
  (is (= [] (difference (:date? table) (fn [[text]] [text (fmt/date? text)])))))

(deftest every-day-begins-at-the-same-moment
  (let [first-day (fmt/millis "2024-12-31T23:00:00Z")]
    (is (= first-day (first (:day-starts table))) "the first day of 2025 begins at midnight of Madrid, an hour before UTC"))
  (let [from (fmt/millis "2024-12-31T12:00:00Z")
        to (fmt/millis "2028-12-31T12:00:00Z")]
    (is (= (:day-starts table) (mapv :at (fmt/day-ticks from to 1))) "the midnights of four years, the nights of the clocks among them")))

(deftest a-time-on-the-clock-of-the-clinic-is-the-same-instant-for-both
  (is (< 300 (count (:local-instants table))))
  (testing "the table holds the hours the clocks skip and the ones they pass twice, and the texts that are no time"
    (let [texts (set (map first (:local-instants table)))]
      (is (contains? texts "2026-03-29T02:30") "an hour that does not exist")
      (is (contains? texts "2026-10-25T02:30") "an hour that happens twice")
      (is (contains? texts "2026-10-13T24:00"))
      (is (contains? texts "2026-02-30T10:00"))))
  (is (= [] (difference (:local-instants table) (fn [[text]] [text (fmt/local->instant text)])))))

(deftest the-ticks-around-the-changes-of-the-clocks-are-the-same
  (is (= [] (difference (:ticks table)
                        (fn [[kind from to every]]
                          (let [f (case kind :day-ticks fmt/day-ticks :hour-ticks fmt/hour-ticks :minute-ticks fmt/minute-ticks)]
                            [kind from to every (mapv (juxt :at :label) (f from to every))]))))))

(deftest numbers-are-written-the-same
  (is (= [] (difference (:numbers table) (fn [[x]] [x (fmt/number x)])))))
