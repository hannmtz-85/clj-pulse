(ns pulse.view.ticks-details-test
  "What a mutation analysis of the axis of time found that the first tests did not hold: a stretch whose end has fewer seconds than its start."
  (:require [clojure.test :refer [deftest is]]
            [pulse.view.format :as fmt]))

(deftest the-last-minute-of-a-stretch-is-there-whatever-the-seconds-of-its-two-ends
  ;; the count of minutes is from the minute the start was cut to: counted from the start itself, a stretch whose start has more seconds
  ;; than its end has one minute fewer than it holds, and loses the last tick
  (is (= ["13:50" "13:55" "14:00"]
         (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:48:50Z") (fmt/millis "2026-10-13T12:00:10Z") 5))))
  (is (= ["14:00"]
         (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:59:59Z") (fmt/millis "2026-10-13T12:00:00Z") 5))))
  (is (= ["13:59" "14:00"]
         (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:58:59Z") (fmt/millis "2026-10-13T12:00:00Z") 1)))))

(deftest a-stretch-that-begins-before-the-epoch-has-its-ticks-on-the-minute-and-on-the-hour
  ;; 23:58:30.500 UTC of the last day of 1969 is 00:58:30.500 of 1 January 1970 in Madrid: the milliseconds that are cut are those of the
  ;; second, and a number of milliseconds before the epoch is negative
  (let [minutes (fmt/minute-ticks (fmt/millis "1969-12-31T23:58:30.500Z") (fmt/millis "1970-01-01T00:01:30Z") 1)
        hours (fmt/hour-ticks (fmt/millis "1969-12-31T22:30:00.500Z") (fmt/millis "1970-01-01T00:30:00Z") 1)]
    (is (= ["00:59" "01:00" "01:01"] (map :label minutes)))
    (is (every? #(zero? (mod (:at %) 60000)) minutes) "each is on the minute")
    (is (= ["00:00" "01:00"] (map :label hours)))
    (is (every? #(zero? (mod (:at %) 3600000)) hours) "each is on the hour")))

(deftest the-last-hour-of-a-stretch-is-there-whatever-the-minutes-of-its-two-ends
  (is (= ["12:00" "13:00" "14:00"]
         (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T09:50:00Z") (fmt/millis "2026-10-13T12:00:10Z") 1))))
  (is (= ["14:00"]
         (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T11:59:59Z") (fmt/millis "2026-10-13T12:00:00Z") 1)))))
