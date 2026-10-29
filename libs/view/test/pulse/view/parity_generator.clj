(ns pulse.view.parity-generator
  "Makes `pulse.view.parity-table` (ADR-0029, point 2): what `pulse.view.format` answers on the JVM, where java.time reads the calendar,
  for a table of instants chosen to be hard (every quarter of an hour around each change of the clocks of seven years, the edges of
  every month, a spread of moments from the nineties to the sixties), so that the browser, where `Intl.DateTimeFormat` reads it, can be
  held to the same answers on Node. The table is committed; `parity-jvm-test` fails when it is not what this makes, and
  `parity-test`, which is `.cljc`, holds both platforms to it.

  To write it again:
  `clojure -Sdeps '{:paths [\"libs/view/test\"]}' -M:dev -e \"(require 'pulse.view.parity-generator) (pulse.view.parity-generator/write!)\"`"
  (:require [clojure.string :as str]
            [pulse.view.format :as fmt])
  (:import (java.time DayOfWeek Instant LocalDate ZoneId)
           (java.time.temporal ChronoUnit)))

(def ^:private ^ZoneId madrid (ZoneId/of "Europe/Madrid"))

(defn- instants-around
  "The instants every `step-minutes` from `before-hours` before `iso` to `after-hours` after it."
  [iso before-hours after-hours step-minutes]
  (let [at (.toEpochMilli (Instant/parse iso))]
    (for [m (range (- (* before-hours 60)) (inc (* after-hours 60)) step-minutes)]
      (str (Instant/ofEpochMilli (+ at (* m 60000)))))))

(def clock-changes
  "The instants the clocks of Madrid change at, from 2024 to 2030: the last Sundays of March and October, at 01:00 UTC."
  (vec (for [year (range 2024 2031)
             month [3 10]
             :let [last-day (.lengthOfMonth (LocalDate/of (int year) (int month) 1))
                   date (first (filter #(= DayOfWeek/SUNDAY (.getDayOfWeek ^LocalDate %))
                                       (map #(LocalDate/of (int year) (int month) (int %)) (range last-day 0 -1))))]]
         (str date "T01:00:00Z"))))

(defn month-edges
  "The first second of every month of `years` in Madrid, and the second before it and the one after."
  [years]
  (vec (for [year years
             month (range 1 13)
             :let [start (.toInstant (.atStartOfDay (LocalDate/of (int year) (int month) 1) madrid))]
             offset [-1 0 1]]
         (str (.plusSeconds start offset)))))

(defn spread
  "`n` instants spread over 1990 to 2069, to the second, always the same."
  [n]
  (let [from (.toEpochMilli (Instant/parse "1990-01-01T00:00:00Z"))
        to (.toEpochMilli (Instant/parse "2069-12-31T23:59:59Z"))
        random (java.util.Random. 20261028)]
    (vec (for [_ (range n)]
           (str (.truncatedTo (Instant/ofEpochMilli (+ from (long (* (.nextDouble random) (- to from))))) ChronoUnit/SECONDS))))))

(def sweep
  "The instants the sweeps of the JVM test ask about: more than the table holds."
  (vec (distinct (concat (mapcat #(instants-around % 3 3 15) clock-changes) (month-edges [2024 2025 2026 2027 2028 2100]) (spread 1500)))))

(def table-instants
  (vec (distinct (concat (mapcat #(instants-around % 2 2 15) clock-changes) (month-edges [2025 2026 2027 2028 2100]) (spread 150)))))

;; ---------------------------------------------------------------------------

(def ^:private instant-columns
  [:millis :clock :clock-seconds :short-date :long-date :weekday-date :local-input :part-of-day :last-date])

(defn- instant-row [iso]
  [iso (fmt/millis iso) (fmt/clock iso) (fmt/clock-seconds iso) (fmt/short-date iso) (fmt/long-date iso) (fmt/weekday-date iso)
   (fmt/local-input iso) (fmt/part-of-day iso) (fmt/last-date iso)])

(def ^:private nows
  ["2026-10-13T12:00:00Z" "2026-03-29T00:30:00Z" "2026-10-25T00:30:00Z" "2026-12-31T23:30:00Z" "2028-02-29T11:00:00Z"])

(defn- day-label-rows []
  (vec (for [iso (take-nth 5 table-instants)
             now nows]
         [iso now (fmt/day-label iso now) (fmt/when-text iso now) (fmt/when-phrase iso now)])))

(def ^:private birth-dates
  ["1968-03-14" "1968-10-13" "1968-10-14" "1968-12-01" "2026-06-01" "2026-10-13" "2000-02-29" "1996-02-29" "1900-01-01" "1999-12-31"
   "2024-02-29" "2026-03-29" "2026-10-25" "1970-01-01" "1969-12-31"])

(defn- age-rows []
  (vec (for [birth birth-dates
             now nows]
         [birth now (fmt/age birth now)])))

(defn- date-rows []
  (vec (for [date (concat birth-dates ["2028-02-29" "2100-03-01" "1900-02-28" "2026-07-04"])]
         [date (fmt/long-date date) (fmt/birth-date date) (fmt/month-year date)])))

(def ^:private instant-cases
  ["2026-10-13T08:15:00Z" "2026-10-13T08:15:00.5Z" "2026-10-13T08:15:00.123456789Z" "2026-10-13T08:15:00+01:00" "2026-10-13T08:15:00-05:30"
   "1970-01-01T00:00:00Z" "1969-12-31T23:59:59Z" "2028-02-29T23:59:59Z" "2026-10-13T24:00:00Z" "2026-12-31T23:59:60Z"
   "2026-10-13T08:15:00+01:00:30" "2026-10-13t08:15:00z" "2026-10-13" "2026-10-13T08:15Z" "2026-10-13T08:15:00" "2026-02-30T00:00:00Z"
   "2027-02-29T00:00:00Z" "2026-10-13T25:00:00Z" "2026-10-13T24:00:01Z" "2026-10-13T08:60:00Z" "2026-10-13T08:15:60Z"
   "2026-13-01T00:00:00Z" "2026-10-32T00:00:00Z" "2026-10-13T08:15:00+19:00" "2026-10-13T08:15:00+01:60" "" "soon" " 2026-10-13T08:15:00Z"
   "2026-10-13T08:15:00Z " "2026-10-13T08:15:00.Z" "2026-10-13T08:15:00,5Z"
   ;; the edges: the offsets that are the most there can be, the last moment of a day and of a month, the days that a leap year has and the
   ;; ones that a century does not, and the days that a month does not have
   "2026-10-13T08:15:00+18:00" "2026-10-13T08:15:00-18:00" "2026-10-13T08:15:00+18:00:01" "2026-10-13T08:15:00+17:59" "2026-10-13T08:15:00+00:00"
   "2026-10-13T08:15:00-00:00" "2026-10-13T23:59:59Z" "2026-10-13T00:00:00Z" "2026-10-13T23:59:59.999Z" "2026-10-13T24:00:00.5Z"
   "2024-02-29T12:00:00Z" "2000-02-29T12:00:00Z" "1900-02-29T12:00:00Z" "2100-02-29T12:00:00Z" "2026-02-28T12:00:00Z" "2026-04-30T12:00:00Z"
   "2026-04-31T12:00:00Z" "2026-06-31T12:00:00Z" "2026-09-31T12:00:00Z" "2026-11-31T12:00:00Z" "2026-12-31T12:00:00Z" "2026-01-31T12:00:00Z"
   "2026-00-10T12:00:00Z" "2026-10-00T12:00:00Z" "2026-12-32T12:00:00Z" "2026-10-13T23:60:00Z" "2026-10-13T23:59:61Z" "2026-10-13T00:00:60Z"
   "2026-10-13T08:15:00+01:60" "2026-10-13T08:15:00+01:00:60" "2026-10-13T08:15:00+1:00" "2026-10-13T08:15:00+0100" "2026-10-13 08:15:00Z"
   "0001-01-01T00:00:00Z" "9999-12-31T23:59:59Z" "1969-12-31T23:59:59.999Z" "2026-10-13T08:15:00.1234567890Z" "2026-10-13T08:15:00.123456789Z"])

(defn- instant-rows []
  (vec (for [text instant-cases] [text (fmt/instant? text)])))

(defn- parsed-rows
  "The epoch milliseconds of the instants among `instant-cases` that can be read, from the offset and the fraction they have."
  []
  (vec (for [text instant-cases :when (fmt/instant? text)] [text (fmt/millis text)])))

(def ^:private date-cases
  ["1968-03-14" "2028-02-29" "2026-02-29" "1968-3-14" "1968-03-14T00:00:00Z" "" "March 14" "2026-04-31" "0999-01-01"])

(defn- date-case-rows []
  (vec (for [text date-cases] [text (fmt/date? text)])))

(defn- day-starts
  "The instant, in epoch milliseconds, that each day of 2025 to 2028 begins at on the clock of Madrid."
  []
  (let [first-day (.toEpochDay (LocalDate/of 2025 1 1))
        last-day (.toEpochDay (LocalDate/of 2028 12 31))]
    (vec (for [day (range first-day (inc last-day))]
           (.toEpochMilli (.toInstant (.atStartOfDay (LocalDate/ofEpochDay day) madrid)))))))

(defn- ticks-cases
  "The ticks of stretches around the four changes of the clocks of 2026 and 2027."
  []
  (vec (for [change (filter #(re-find #"^202[67]" %) clock-changes)
             :let [at (fmt/millis change)]
             [kind from to every] [[:day-ticks (- at (* 60 3600000) 17000) (+ at (* 60 3600000) 31000) 1]
                                   [:day-ticks (- at (* 60 3600000)) (+ at (* 60 3600000)) 2]
                                   [:hour-ticks (- at (* 5 3600000) 17000) (+ at (* 30 3600000) 31000) 1]
                                   [:hour-ticks (- at (* 5 3600000)) (+ at (* 30 3600000)) 3]
                                   [:hour-ticks (- at (* 2 3600000)) (+ at (* 14 3600000)) 6]
                                   [:minute-ticks (- at (* 2 3600000) 17000) (+ at (* 2 3600000) 31000) 15]
                                   [:minute-ticks (- at 3600000) (+ at 3600000) 5]]
             :let [f (case kind :day-ticks fmt/day-ticks :hour-ticks fmt/hour-ticks :minute-ticks fmt/minute-ticks)]]
         [kind from to every (mapv (juxt :at :label) (f from to every))])))

(def ^:private numbers
  [138 82.4 70.0 1.5 0 -3.0 36.6 5.55555 120.0 0.1 -0.5 1000 99.9 172 104.0 3.14159 100000 12.50])

(defn- number-rows []
  (vec (for [x numbers] [x (fmt/number x)])))

(defn table
  "The table: what `pulse.view.format` answers on the JVM."
  []
  {:instant-columns instant-columns
   :instants (mapv instant-row table-instants)
   :day-labels (day-label-rows)
   :ages (age-rows)
   :dates (date-rows)
   :instant? (instant-rows)
   :parsed (parsed-rows)
   :date? (date-case-rows)
   :day-starts (day-starts)
   :ticks (ticks-cases)
   :numbers (number-rows)})

(def ^:private header
  "(ns pulse.view.parity-table
  \"GENERATED by `pulse.view.parity-generator` (libs/view/test): what `pulse.view.format` answers on the JVM, where java.time reads the
  calendar, for instants chosen to be hard (around each change of the clocks, at the edges of the months), so that the browser, where
  `Intl.DateTimeFormat` reads it, can be held to the same answers on Node (ADR-0029, point 2). `parity-jvm-test` fails when this is not
  what the generator makes. Do not edit it by hand.\")

")

(defn text
  "The source of `pulse.view.parity-table`: one row of each section to a line."
  []
  (let [t (table)
        nl "\n"
        entry (fn [k] (str (pr-str k) nl "   [" (str/join (str nl "    ") (map pr-str (get t k))) "]"))]
    (str header
         "(def table" nl "  {:instant-columns " (pr-str (:instant-columns t)) nl "   "
         (str/join (str nl "   ") (map entry [:instants :day-labels :ages :dates :instant? :parsed :date? :day-starts :ticks :numbers]))
         "})" nl)))

(defn write!
  "Writes `libs/view/test/pulse/view/parity_table.cljc`."
  []
  (spit "libs/view/test/pulse/view/parity_table.cljc" (text))
  (println "written"))
