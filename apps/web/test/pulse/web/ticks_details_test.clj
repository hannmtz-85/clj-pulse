(ns pulse.web.ticks-details-test
  "What a mutation analysis of the axis of time found that the first tests did not hold: a stretch whose end has fewer seconds than its start."
  (:require [clojure.test :refer [deftest is]]
            [pulse.web.format :as fmt]))

(deftest the-last-minute-of-a-stretch-is-there-whatever-the-seconds-of-its-two-ends
  ;; the count of minutes is from the minute the start was cut to: counted from the start itself, a stretch whose start has more seconds
  ;; than its end has one minute fewer than it holds, and loses the last tick
  (is (= ["13:50" "13:55" "14:00"]
         (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:48:50Z") (fmt/millis "2026-10-13T12:00:10Z") 5))))
  (is (= ["14:00"]
         (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:59:59Z") (fmt/millis "2026-10-13T12:00:00Z") 5))))
  (is (= ["13:59" "14:00"]
         (map :label (fmt/minute-ticks (fmt/millis "2026-10-13T11:58:59Z") (fmt/millis "2026-10-13T12:00:00Z") 1)))))

(deftest the-last-hour-of-a-stretch-is-there-whatever-the-minutes-of-its-two-ends
  (is (= ["12:00" "13:00" "14:00"]
         (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T09:50:00Z") (fmt/millis "2026-10-13T12:00:10Z") 1))))
  (is (= ["14:00"]
         (map :label (fmt/hour-ticks (fmt/millis "2026-10-13T11:59:59Z") (fmt/millis "2026-10-13T12:00:00Z") 1)))))
