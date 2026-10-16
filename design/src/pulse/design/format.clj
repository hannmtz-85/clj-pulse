(ns pulse.design.format
  "How the mock-ups write times, units and numbers: the way the interfaces will. Times are those of the clinic,
  in the zone of Madrid, and in English; a day is `Today`, `Yesterday` or its date."
  (:require [clojure.string :as str])
  (:import (java.time Instant LocalDate ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def ^ZoneId zone (ZoneId/of "Europe/Madrid"))

(defn- formatter ^DateTimeFormatter [pattern]
  (.withLocale (DateTimeFormatter/ofPattern pattern) Locale/ENGLISH))

(defn- local ^ZonedDateTime [iso]
  (.atZone (Instant/parse iso) zone))

(defn clock
  "The time of day of an instant, such as `08:15`."
  [iso]
  (.format (local iso) (formatter "HH:mm")))

(defn short-date
  "A date such as `Oct 16`."
  [iso]
  (.format (local iso) (formatter "MMM d")))

(defn long-date
  "A date such as `Friday, 16 October 2026`, from an instant or a plain date (`1968-03-14`)."
  [text]
  (let [date (if (re-matches #"\d{4}-\d{2}-\d{2}" text)
               (LocalDate/parse text)
               (.toLocalDate (local text)))]
    (.format date (formatter "EEEE, d MMMM yyyy"))))

(defn birth-date
  "A date of birth such as `14 March 1968`."
  [text]
  (.format (LocalDate/parse text) (formatter "d MMMM yyyy")))

(defn day-label
  "`Today`, `Yesterday` or `Oct 14`, for the day of `iso` as seen on the day of `now`."
  [iso now]
  (let [day (.toLocalDate (local iso))
        today (.toLocalDate (local now))]
    (cond (= day today) "Today"
          (= day (.minusDays today 1)) "Yesterday"
          (= day (.plusDays today 1)) "Tomorrow"
          :else (short-date iso))))

(defn when-text
  "`Today, 08:15`, `Yesterday, 20:02` or `Oct 14, 08:15`."
  [iso now]
  (str (day-label iso now) ", " (clock iso)))

(defn age
  "The age in whole years on the day of `now`."
  [birth-date-text now]
  (.getYears (java.time.Period/between (LocalDate/parse birth-date-text) (.toLocalDate (local now)))))

(defn millis
  "An instant (text) as epoch milliseconds."
  [iso]
  (.toEpochMilli (Instant/parse iso)))

(defn day-ticks
  "Ticks for the x axis of a chart: midnight of the clinic's day, every `every` days, between two moments in epoch
  milliseconds, as `{:at :label}` with labels such as `Oct 7`."
  [from-millis to-millis every]
  (let [start (.toLocalDate (.atZone (Instant/ofEpochMilli (long from-millis)) zone))
        end (.toLocalDate (.atZone (Instant/ofEpochMilli (long to-millis)) zone))
        label (formatter "MMM d")]
    (for [d (range 0 (inc (.between java.time.temporal.ChronoUnit/DAYS start end)) every)
          :let [date (.plusDays start (long d))
                at (.toEpochMilli (.toInstant (.atStartOfDay date zone)))]
          :when (<= from-millis at)]
      {:at at :label (.format date label)})))

(defn hour-ticks
  "Ticks every `every` hours of the clinic's clock between two moments in epoch milliseconds, labelled `08:00`."
  [from-millis to-millis every]
  (let [label (formatter "HH:mm")
        first-hour (.truncatedTo (.atZone (Instant/ofEpochMilli (long from-millis)) zone) java.time.temporal.ChronoUnit/HOURS)]
    (for [h (range 0 (inc (long (/ (- to-millis from-millis) 3600000))) 1)
          :let [t (.plusHours first-hour (long h))
                at (.toEpochMilli (.toInstant t))]
          :when (and (<= from-millis at) (zero? (mod (.getHour t) every)))]
      {:at at :label (.format t label)})))

(defn number
  "A number as text: a whole number with no decimals, and the others as they are."
  [x]
  (if (and (== x (Math/rint (double x))) (not (str/includes? (str x) "E")))
    (str (long x))
    (str x)))

(def unit-labels
  "The unit of a UCUM code as people write it."
  {"mm[Hg]" "mmHg" "/min" "bpm" "%" "%" "mg/dL" "mg/dL" "kg" "kg" "Cel" "°C"})

(defn unit
  "A UCUM unit as people write it: `mmHg`, `bpm`, `°C`."
  [code]
  (get unit-labels code code))
