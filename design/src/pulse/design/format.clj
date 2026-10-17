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

(defn- date-of
  "The day of `text`, an instant (in the clinic's zone) or a plain date (`1968-03-14`)."
  ^LocalDate [text]
  (if (re-matches #"\d{4}-\d{2}-\d{2}" text)
    (LocalDate/parse text)
    (.toLocalDate (local text))))

(defn long-date
  "A date such as `Friday, 16 October 2026`, from an instant or a plain date (`1968-03-14`)."
  [text]
  (.format (date-of text) (formatter "EEEE, d MMMM yyyy")))

(defn month-year
  "A month such as `Nov 2025`, from an instant or a plain date."
  [text]
  (.format (date-of text) (formatter "MMM yyyy")))

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

(defn when-phrase
  "`when-text` for the middle of a sentence: `today, 08:15`, `yesterday, 20:02` or `Oct 14, 08:15`."
  [iso now]
  (let [label (day-label iso now)]
    (str (if (#{"Today" "Yesterday" "Tomorrow"} label) (str/lower-case label) label) ", " (clock iso))))

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

(defn weekday-date
  "A day with its weekday, such as `Wed 14 Oct`."
  [iso]
  (.format (local iso) (formatter "EEE d MMM")))

(defn month-abbreviation
  "The month of an instant, such as `Oct`."
  [iso]
  (.format (local iso) (formatter "MMM")))

(defn day-of-month
  "The day of the month of an instant, such as `14`."
  [iso]
  (.format (local iso) (formatter "d")))

(defn time-range
  "The time of day of two instants as a range, such as `10:00–10:30`."
  [start end]
  (str (clock start) "–" (clock end)))

(defn date-range
  "Two instants as a range of days, such as `Oct 14 to Oct 20`."
  [start end]
  (str (short-date start) " to " (short-date end)))

(defn part-of-day
  "`morning`, `afternoon` or `evening`, by the time in the clinic of the instant."
  [iso]
  (let [hour (.getHour (local iso))]
    (cond (< hour 12) "morning"
          (< hour 20) "afternoon"
          :else "evening")))

(defn clock-seconds
  "The time of day of an instant with its seconds, such as `14:00:30`."
  [iso]
  (.format (local iso) (formatter "HH:mm:ss")))

(defn- every-text [n unit]
  (if (= 1 n) (str "Every " unit) (str "Every " n " " unit "s")))

(defn interval-text
  "An interval in milliseconds as people say it: `Every 15 seconds`, `Every minute`, `Every 5 minutes`, `Every hour`."
  [ms]
  (cond
    (pos? (mod ms 1000)) (str "Every " ms " ms")
    (zero? (mod ms 3600000)) (every-text (quot ms 3600000) "hour")
    (zero? (mod ms 60000)) (every-text (quot ms 60000) "minute")
    :else (every-text (quot ms 1000) "second")))

(defn schedule-text
  "A schedule as the api shows it (`{:type \"interval\" :everyMs 30000}` or `{:type \"daily\" :at \"08:00\" :zone ...}`)
  in words."
  [{:keys [type everyMs at zone]}]
  (case type
    "interval" (interval-text everyMs)
    "daily" (str "Every day at " at " (" zone ")")))

(defn duration-text
  "A duration in milliseconds: `620 ms`, `1.8 s`, `10 s`."
  [ms]
  (cond
    (< ms 1000) (str ms " ms")
    (< ms 9950) (str (/ (Math/round (/ ms 100.0)) 10.0) " s")
    :else (str (Math/round (/ ms 1000.0)) " s")))

(defn words
  "A key written in camel case as words: `alreadyRaised` is `already raised`."
  [k]
  (str/lower-case (str/replace (name k) #"([a-z0-9])([A-Z])" "$1 $2")))
