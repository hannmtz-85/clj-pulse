(ns pulse.view.format
  "How the interfaces write times, units and numbers (ADR-0028, ADR-0029): the way a person reads them. Times are those of the
  clinic, in the zone of Madrid, and in English whatever the language of the machine; a day is `Today`, `Yesterday` or its date.
  Everything here is a pure function of an instant written as text (`2026-10-13T08:15:00Z`) and, for a day, of the instant
  that is now, which the caller gives: nothing reads a clock. The mock-ups of the review use these functions too, so that
  what is reviewed is what is served.

  It is `.cljc` so that the portal (on the JVM), the clinical workspace (in the browser) and the mock-ups (under Babashka)
  write the same text. **Only the reading of the calendar differs by platform**: the JVM asks java.time what the date and the
  time of an instant are in Madrid, and the browser asks `Intl.DateTimeFormat` (`formatToParts`, which gives numbers); the text
  itself is written here, from tables of the English names of the months and the days, so that no platform's locale data decides
  a word. A table of instants (the nights the clocks change, midnights, the last second of a month) is held on the JVM and on
  Node to give the same answers (`pulse.view.parity-test`).

  An instant is text as the api writes it: a date and a time to the second with an offset (`2026-10-13T08:15:00Z`); a plain date
  (`1968-03-14`) is what a date of birth is. What is neither makes a function throw, as `Instant/parse` does on the JVM:
  `instant?` and `date?` say first."
  (:require [clojure.string :as str])
  #?(:clj (:import (java.time Instant LocalDate ZoneId ZonedDateTime)
                   (java.time.temporal ChronoUnit))))

;; ---------------------------------------------------------------------------
;; The calendar: days, weekdays and the names of the months and the days. Pure, and the same on every platform.

(defn- days-from-civil
  "The number of days from 1970-01-01 to the date `year`-`month`-`day` (negative before it) in the proleptic Gregorian calendar:
  Howard Hinnant's algorithm."
  [year month day]
  (let [y (if (<= month 2) (dec year) year)
        era (quot (if (neg? y) (- y 399) y) 400)
        yoe (- y (* era 400))
        mp (mod (+ month 9) 12)
        doy (+ (quot (+ (* 153 mp) 2) 5) (dec day))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- civil-from-days
  "`[year month day]` of the date `days` days from 1970-01-01: the inverse of `days-from-civil`."
  [days]
  (let [z (+ days 719468)
        era (quot (if (neg? z) (- z 146096) z) 146097)
        doe (- z (* era 146097))
        yoe (quot (- (+ (- doe (quot doe 1460)) (quot doe 36524)) (quot doe 146096)) 365)
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp (quot (+ (* 5 doy) 2) 153)
        month (if (< mp 10) (+ mp 3) (- mp 9))
        year (+ yoe (* era 400))]
    [(if (<= month 2) (inc year) year)
     month
     (inc (- doy (quot (+ (* 153 mp) 2) 5)))]))

(defn- weekday
  "The day of the week of the date `days` days from 1970-01-01, from 1 (Monday) to 7 (Sunday). 1970-01-01 was a Thursday."
  [days]
  (inc (mod (+ days 3) 7)))

(defn leap-year?
  "Whether `year` is a leap year."
  [year]
  (and (zero? (mod year 4)) (or (pos? (mod year 100)) (zero? (mod year 400)))))

(defn days-in-month
  "How many days `month` (1 to 12) of `year` has."
  [year month]
  (case (int month)
    2 (if (leap-year? year) 29 28)
    (4 6 9 11) 30
    31))

(defn real-date?
  "Whether `year`-`month`-`day` is a day of the calendar."
  [year month day]
  (and (<= 1 month 12) (<= 1 day (days-in-month year month))))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June" "July" "August" "September" "October" "November" "December"])

(def ^:private month-abbreviations
  (mapv #(subs % 0 3) month-names))

(def ^:private weekday-names
  ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(def ^:private weekday-abbreviations
  (mapv #(subs % 0 3) weekday-names))

(defn- two-digits [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- four-digits [n]
  (let [text (str n)]
    (str (subs "0000" (min 4 (count text))) text)))

;; ---------------------------------------------------------------------------
;; The platform: what an instant is, and what it is on the clock of Madrid

#?(:clj
   (def ^ZoneId zone
     "The clock of the clinic. The JVM only: the browser reads it by name."
     (ZoneId/of "Europe/Madrid")))

#?(:cljs
   (def ^:private instant-pattern
     ;; what `Instant/parse` reads of the text the api writes: seconds are not optional, and the offset is `Z` or `+hh:mm`
     #"^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{0,9}))?([Zz]|[+-]\d{2}:\d{2}(?::\d{2})?)$"))

#?(:cljs
   (defn- parse-instant
     "The epoch milliseconds of `x`, text such as `2026-10-13T08:15:00Z`, or a throw: it reads what `Instant/parse` reads, so that
     both platforms say the same of a text. A leap second (23:59:60) is the second before it, and 24:00:00 is the next midnight."
     [x]
     (let [[_ year month day hour minute sec fraction offset] (when (string? x) (re-matches instant-pattern x))
           fail #(throw (ex-info "Text could not be parsed as an instant" {:text x}))]
       (when-not year (fail))
       (let [year (js/parseInt year 10)
             month (js/parseInt month 10)
             day (js/parseInt day 10)
             hour (js/parseInt hour 10)
             minute (js/parseInt minute 10)
             sec (js/parseInt sec 10)
             end-of-day? (and (= 24 hour) (zero? minute) (zero? sec) (or (nil? fraction) (every? #{\0} fraction)))
             leap-second? (and (= 23 hour) (= 59 minute) (= 60 sec))
             hour (if end-of-day? 0 hour)
             sec (if leap-second? 59 sec)
             sign (if (= \- (first offset)) -1 1)
             [offset-hours offset-minutes offset-seconds] (if (#{"Z" "z"} offset)
                                                            [0 0 0]
                                                            (map #(js/parseInt % 10) (re-seq #"\d{2}" offset)))
             offset-seconds (or offset-seconds 0)
             offset-total (* sign (+ (* 3600 offset-hours) (* 60 offset-minutes) offset-seconds))]
         (when-not (and (real-date? year month day)
                        (<= 0 hour 23) (<= 0 minute 59) (<= 0 sec 59)
                        (<= 0 offset-minutes 59) (<= 0 offset-seconds 59)
                        (<= -64800 offset-total 64800))
           (fail))
         (+ (* (+ (days-from-civil year month day) (if end-of-day? 1 0)) 86400000)
            (* hour 3600000) (* minute 60000) (* sec 1000)
            ;; the milliseconds are the first three digits of the fraction
            (if fraction (js/parseInt (subs (str fraction "000") 0 3) 10) 0)
            (* -1000 offset-total))))))

(defn millis
  "An instant (text) as epoch milliseconds."
  [iso]
  #?(:clj (.toEpochMilli (Instant/parse iso))
     :cljs (parse-instant iso)))

(defn instant?
  "Whether `x` is an instant written as text, such as `2026-10-13T08:15:00Z`: a date alone is not, nor is anything that is not
  text. Other systems write to the same FHIR server, so a page that shows a time it was given checks first."
  [x]
  #?(:clj (boolean (and (string? x)
                        (try (Instant/parse x) true
                             (catch java.time.format.DateTimeParseException _ false))))
     :cljs (boolean (and (string? x)
                         (try (parse-instant x) true
                              (catch :default _ false))))))

(defn- plain-date
  "`[year month day]` of `text`, a plain date such as `1968-03-14`, or a throw."
  [text]
  #?(:clj (let [date (LocalDate/parse text)]
            [(.getYear date) (.getMonthValue date) (.getDayOfMonth date)])
     :cljs (let [[_ year month day] (when (string? text) (re-matches #"(\d{4})-(\d{2})-(\d{2})" text))
                 [year month day] (map #(js/parseInt % 10) [year month day])]
             (if (and year (real-date? year month day))
               [year month day]
               (throw (ex-info "Text could not be parsed as a date" {:text text}))))))

(defn date?
  "Whether `x` is a plain date written as text, such as `1968-03-14`."
  [x]
  (boolean (and (string? x)
                (try (plain-date x) true
                     (catch #?(:clj Exception :cljs :default) _ false)))))

#?(:cljs
   (def ^:private ^js clock-of-the-clinic
     ;; `hourCycle` h23 and not `hour12 false`, which some engines write as 24:00 for midnight
     (js/Intl.DateTimeFormat. "en-US" #js {:timeZone "Europe/Madrid" :hourCycle "h23" :year "numeric" :month "numeric" :day "numeric"
                                           :hour "numeric" :minute "numeric" :second "numeric"})))

(defn- local-fields
  "`[year month day hour minute second]` of the instant `millis` on the clock of the clinic."
  [millis]
  #?(:clj (let [^ZonedDateTime t (.atZone (Instant/ofEpochMilli (long millis)) zone)]
            [(.getYear t) (.getMonthValue t) (.getDayOfMonth t) (.getHour t) (.getMinute t) (.getSecond t)])
     :cljs (let [parts (.formatToParts clock-of-the-clinic (js/Date. millis))
                 field (fn [type] (some (fn [^js part] (when (= type (.-type part)) (js/parseInt (.-value part) 10))) parts))]
             [(field "year") (field "month") (field "day") (mod (field "hour") 24) (field "minute") (field "second")])))

(defn- ymd
  "`[year month day]` of the instant `millis` on the clock of the clinic."
  [millis]
  (vec (take 3 (local-fields millis))))

#?(:cljs
   (defn- offset-at
     "How many milliseconds the clock of the clinic is ahead of UTC at the instant `millis`, a whole number of seconds."
     [millis]
     (let [[year month day hour minute sec] (local-fields millis)]
       (- (+ (* (days-from-civil year month day) 86400000) (* hour 3600000) (* minute 60000) (* sec 1000))
          millis))))

(defn- start-of-day
  "The instant, in epoch milliseconds, that the day `year`-`month`-`day` begins at on the clock of the clinic."
  [year month day]
  #?(:clj (.toEpochMilli (.toInstant (.atStartOfDay (LocalDate/of (int year) (int month) (int day)) zone)))
     ;; the clocks change at 01:00 UTC, which is well after a midnight of the clinic and three hours after the sample taken here, so
     ;; the offset at the sample is the offset of the midnight
     :cljs (let [wall (* (days-from-civil year month day) 86400000)]
             (- wall (offset-at (- wall 10800000))))))

(defn- date-of
  "`[year month day]` of `text`: a plain date (`1968-03-14`) or an instant (in the clinic's zone)."
  [text]
  (if (re-matches #"\d{4}-\d{2}-\d{2}" text)
    (plain-date text)
    (ymd (millis text))))

(defn instant-text
  "The instant `millis` (epoch milliseconds) as the text the api reads, to the second, in UTC: `2026-10-13T08:15:00Z`."
  [millis]
  #?(:clj (str (.truncatedTo (Instant/ofEpochMilli (long millis)) ChronoUnit/SECONDS))
     :cljs (str/replace (.toISOString (js/Date. (- millis (mod millis 1000)))) #"\.000Z$" "Z")))

;; ---------------------------------------------------------------------------
;; Times and dates

(defn clock
  "The time of day of an instant, such as `08:15`."
  [iso]
  (let [[_ _ _ hour minute] (local-fields (millis iso))]
    (str (two-digits hour) ":" (two-digits minute))))

(defn clock-seconds
  "The time of day of an instant with its seconds, such as `14:00:30`."
  [iso]
  (let [[_ _ _ hour minute sec] (local-fields (millis iso))]
    (str (two-digits hour) ":" (two-digits minute) ":" (two-digits sec))))

(defn- short-date-at [millis]
  (let [[_ month day] (ymd millis)]
    (str (month-abbreviations (dec month)) " " day)))

(defn short-date
  "A date such as `Oct 16`."
  [iso]
  (short-date-at (millis iso)))

(defn long-date
  "A date such as `Friday, 16 October 2026`, from an instant or a plain date (`1968-03-14`)."
  [text]
  (let [[year month day] (date-of text)]
    (str (weekday-names (dec (weekday (days-from-civil year month day)))) ", " day " " (month-names (dec month)) " " (four-digits year))))

(defn month-year
  "A month such as `Nov 2025`, from an instant or a plain date."
  [text]
  (let [[year month] (date-of text)]
    (str (month-abbreviations (dec month)) " " (four-digits year))))

(defn birth-date
  "A date of birth such as `14 March 1968`."
  [text]
  (let [[year month day] (plain-date text)]
    (str day " " (month-names (dec month)) " " (four-digits year))))

(defn day-label
  "`Today`, `Yesterday` or `Oct 14`, for the day of `iso` as seen on the day of `now`."
  [iso now]
  (let [[year month day] (ymd (millis iso))
        days (days-from-civil year month day)
        [now-year now-month now-day] (ymd (millis now))
        today (days-from-civil now-year now-month now-day)]
    (cond (= days today) "Today"
          (= days (dec today)) "Yesterday"
          (= days (inc today)) "Tomorrow"
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
  (let [[birth-year birth-month birth-day] (plain-date birth-date-text)
        [year month day] (ymd (millis now))]
    (- year birth-year (if (or (< month birth-month) (and (= month birth-month) (< day birth-day))) 1 0))))

(defn weekday-date
  "A day with its weekday, such as `Wed 14 Oct`."
  [iso]
  (let [[year month day] (ymd (millis iso))]
    (str (weekday-abbreviations (dec (weekday (days-from-civil year month day)))) " " day " " (month-abbreviations (dec month)))))

(defn month-abbreviation
  "The month of an instant, such as `Oct`."
  [iso]
  (let [[_ month] (ymd (millis iso))]
    (month-abbreviations (dec month))))

(defn day-of-month
  "The day of the month of an instant, such as `14`."
  [iso]
  (let [[_ _ day] (ymd (millis iso))]
    (str day)))

(defn time-range
  "The time of day of two instants as a range, such as `10:00–10:30`."
  [start end]
  (str (clock start) "–" (clock end)))

(defn last-date
  "A date such as `Oct 20` for the last day of a period that ends at `iso`: the day of the moment just before it, so that a
  period that ends at midnight ends on the day before (the form that asks for a visit says its last day, and the period ends
  when that day does)."
  [iso]
  (short-date-at (dec (millis iso))))

(defn date-range
  "Two instants, the start and the end of a period, as a range of days, such as `Oct 14 to Oct 20`: the day it starts and the
  last day it covers (see `last-date`)."
  [start end]
  (str (short-date start) " to " (last-date end)))

(defn part-of-day
  "`morning`, `afternoon` or `evening`, by the time in the clinic of the instant."
  [iso]
  (let [[_ _ _ hour] (local-fields (millis iso))]
    (cond (< hour 12) "morning"
          (< hour 20) "afternoon"
          :else "evening")))

(defn local-input
  "An instant as the value of a field of a form for a date and a time (`datetime-local`), which has no zone: the date and the
  time on the clinic's clock, to the minute, such as `2026-10-13T14:00`."
  [iso]
  (let [[year month day hour minute] (local-fields (millis iso))]
    (str (four-digits year) "-" (two-digits month) "-" (two-digits day) "T" (two-digits hour) ":" (two-digits minute))))

;; ---------------------------------------------------------------------------
;; The axis of time of a chart

(defn day-ticks
  "Ticks for the x axis of a chart: midnight of the clinic's day, every `every` days, between two moments in epoch
  milliseconds, as `{:at :label}` with labels such as `Oct 7`."
  [from-millis to-millis every]
  (let [[start-year start-month start-day] (ymd from-millis)
        [end-year end-month end-day] (ymd to-millis)
        first-day (days-from-civil start-year start-month start-day)
        span (- (days-from-civil end-year end-month end-day) first-day)]
    (for [d (range 0 (inc span) every)
          :let [[year month day] (civil-from-days (+ first-day d))
                at (start-of-day year month day)]
          :when (<= from-millis at)]
      {:at at :label (str (month-abbreviations (dec month)) " " day)})))

(defn hour-ticks
  "Ticks every `every` hours of the clinic's clock between two moments in epoch milliseconds, labelled `08:00`."
  [from-millis to-millis every]
  (let [[_ _ _ _ minute sec] (local-fields from-millis)
        first-millis (- from-millis (* minute 60000) (* sec 1000) (mod from-millis 1000))]
    (for [h (range 0 (inc (long (/ (- to-millis first-millis) 3600000))) 1)
          :let [at (+ first-millis (* h 3600000))
                [_ _ _ hour minute] (local-fields at)]
          :when (and (<= from-millis at) (zero? (mod hour every)))]
      {:at at :label (str (two-digits hour) ":" (two-digits minute))})))

(defn minute-ticks
  "Ticks every `every` minutes of the clinic's clock between two moments in epoch milliseconds, labelled `08:15`."
  [from-millis to-millis every]
  (let [[_ _ _ _ _ sec] (local-fields from-millis)
        first-millis (- from-millis (* sec 1000) (mod from-millis 1000))]
    (for [m (range 0 (inc (long (/ (- to-millis first-millis) 60000))) 1)
          :let [at (+ first-millis (* m 60000))
                [_ _ _ hour minute] (local-fields at)]
          :when (and (<= from-millis at) (zero? (mod minute every)))]
      {:at at :label (str (two-digits hour) ":" (two-digits minute))})))

;; ---------------------------------------------------------------------------
;; Numbers and units

(defn number
  "A number as text: a whole number with no decimals, and the others as they are."
  [x]
  (if (and (== x #?(:clj (Math/rint (double x)) :cljs (js/Math.round x))) (not (str/includes? (str x) "E")))
    (str (long x))
    (str x)))

(def unit-labels
  "The unit of a UCUM code as people write it."
  {"mm[Hg]" "mmHg" "/min" "bpm" "%" "%" "mg/dL" "mg/dL" "kg" "kg" "Cel" "°C"})

(defn unit
  "A UCUM unit as people write it: `mmHg`, `bpm`, `°C`."
  [code]
  (get unit-labels code code))
