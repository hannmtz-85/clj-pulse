(ns pulse.view.format-jvm-test
  "What of `pulse.view.format` only the JVM can say (ADR-0029): that the text it writes from its own tables is the text java.time's
  formatters wrote before it was shared with the browser (the code it replaced is the oracle here, copied as it was), that its
  calendar is the JDK's, the language of the machine and the numbers that only the JVM has."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.view.format :as fmt]
            [pulse.view.parity-generator :as generator])
  (:import (java.time Instant LocalDate ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)
           (java.util Locale)))

;; ---------------------------------------------------------------------------
;; The oracle: the formats as they were written with java.time's formatters (pulse.web.format, before the move)

(def ^:private ^ZoneId madrid (ZoneId/of "Europe/Madrid"))

(defn- formatter ^DateTimeFormatter [pattern]
  (.withLocale (DateTimeFormatter/ofPattern pattern) Locale/ENGLISH))

(defn- local ^ZonedDateTime [iso]
  (.atZone (Instant/parse iso) madrid))

(defn- written [pattern iso]
  (.format (local iso) (formatter pattern)))

(defn- oracle-day-ticks [from-millis to-millis every]
  (let [start (.toLocalDate (.atZone (Instant/ofEpochMilli (long from-millis)) madrid))
        end (.toLocalDate (.atZone (Instant/ofEpochMilli (long to-millis)) madrid))
        label (formatter "MMM d")]
    (for [d (range 0 (inc (.between ChronoUnit/DAYS start end)) every)
          :let [date (.plusDays start (long d))
                at (.toEpochMilli (.toInstant (.atStartOfDay date madrid)))]
          :when (<= from-millis at)]
      {:at at :label (.format date label)})))

(defn- oracle-hour-ticks [from-millis to-millis every]
  (let [label (formatter "HH:mm")
        first-hour (.truncatedTo (.atZone (Instant/ofEpochMilli (long from-millis)) madrid) ChronoUnit/HOURS)
        first-millis (.toEpochMilli (.toInstant first-hour))]
    (for [h (range 0 (inc (long (/ (- to-millis first-millis) 3600000))) 1)
          :let [t (.plusHours first-hour (long h))
                at (.toEpochMilli (.toInstant t))]
          :when (and (<= from-millis at) (zero? (mod (.getHour t) every)))]
      {:at at :label (.format t label)})))

(defn- oracle-minute-ticks [from-millis to-millis every]
  (let [label (formatter "HH:mm")
        first-minute (.truncatedTo (.atZone (Instant/ofEpochMilli (long from-millis)) madrid) ChronoUnit/MINUTES)
        first-millis (.toEpochMilli (.toInstant first-minute))]
    (for [m (range 0 (inc (long (/ (- to-millis first-millis) 60000))) 1)
          :let [t (.plusMinutes first-minute (long m))
                at (.toEpochMilli (.toInstant t))]
          :when (and (<= from-millis at) (zero? (mod (.getMinute t) every)))]
      {:at at :label (.format t label)})))

(def ^:private instants generator/sweep)

(def ^:private clock-changes generator/clock-changes)

;; ---------------------------------------------------------------------------

(deftest the-calendar-is-the-jdks
  (testing "every day from 1900 to 2200: the days from the epoch, the date of a day, its weekday, its month and its length"
    (let [days-from-civil @#'fmt/days-from-civil
          civil-from-days @#'fmt/civil-from-days
          weekday @#'fmt/weekday]
      (is (= [] (vec (for [day (concat (range (.toEpochDay (LocalDate/of -300 1 1)) (inc (.toEpochDay (LocalDate/of 300 12 31))))
                                       (range (.toEpochDay (LocalDate/of 1900 1 1)) (inc (.toEpochDay (LocalDate/of 2200 12 31)))))
                           :let [date (LocalDate/ofEpochDay day)
                                 [year month of-month] [(.getYear date) (.getMonthValue date) (.getDayOfMonth date)]]
                           :when (not (and (= day (days-from-civil year month of-month))
                                           (= [year month of-month] (civil-from-days day))
                                           (= (.getValue (.getDayOfWeek date)) (weekday day))
                                           (= (.lengthOfMonth date) (fmt/days-in-month year month))
                                           (= (.isLeapYear date) (fmt/leap-year? year))
                                           (fmt/real-date? year month of-month)))]
                       (str date))))
          "no day of three centuries differs, nor of the six around the year 0")))
  (testing "and the days that are not"
    (is (not (fmt/real-date? 2026 2 29)))
    (is (fmt/real-date? 2028 2 29))
    (is (not (fmt/real-date? 2100 2 29)) "1900 and 2100 are not leap years")
    (is (fmt/real-date? 2000 2 29) "and 2000 is")
    (is (not (fmt/real-date? 2026 4 31)))
    (is (not (fmt/real-date? 2026 13 1)))
    (is (not (fmt/real-date? 2026 0 1)))
    (is (not (fmt/real-date? 2026 1 0)))))

(deftest what-is-written-is-what-java-times-formatters-wrote
  (is (< 2000 (count instants)) "there is a good number of them")
  (is (= [] (vec (for [iso instants
                       :let [millis (fmt/millis iso)]
                       :when (not (and (= (written "HH:mm" iso) (fmt/clock iso))
                                       (= (written "HH:mm:ss" iso) (fmt/clock-seconds iso))
                                       (= (written "MMM d" iso) (fmt/short-date iso))
                                       (= (written "EEEE, d MMMM yyyy" iso) (fmt/long-date iso))
                                       (= (written "MMM yyyy" iso) (fmt/month-year iso))
                                       (= (written "EEE d MMM" iso) (fmt/weekday-date iso))
                                       (= (written "MMM" iso) (fmt/month-abbreviation iso))
                                       (= (written "d" iso) (fmt/day-of-month iso))
                                       (= (written "yyyy-MM-dd'T'HH:mm" iso) (fmt/local-input iso))
                                       (= (.toEpochMilli (Instant/parse iso)) millis)
                                       (= (written "MMM d" (str (.minusMillis (Instant/parse iso) 1))) (fmt/last-date iso))))]
                   iso)))
      "no instant is written otherwise")
  (testing "a date alone, which is a date of birth"
    (is (= [] (vec (for [day (range -20000 25000 7)
                         :let [date (str (LocalDate/ofEpochDay day))]
                         :when (not (and (= (.format (LocalDate/parse date) (formatter "EEEE, d MMMM yyyy")) (fmt/long-date date))
                                         (= (.format (LocalDate/parse date) (formatter "d MMMM yyyy")) (fmt/birth-date date))
                                         (= (.format (LocalDate/parse date) (formatter "MMM yyyy")) (fmt/month-year date))))]
                     date))))))

(deftest the-labels-of-a-day-are-those-of-the-day-in-madrid
  (is (= [] (vec (for [[iso now] (map vector instants (rest (cycle instants)))
                       :let [day (.toLocalDate (local iso))
                             today (.toLocalDate (local now))
                             expected (cond (= day today) "Today"
                                            (= day (.minusDays today 1)) "Yesterday"
                                            (= day (.plusDays today 1)) "Tomorrow"
                                            :else (written "MMM d" iso))]
                       :when (not= expected (fmt/day-label iso now))]
                   [iso now])))))

(deftest an-age-is-the-years-of-a-period
  (is (= [] (vec (for [day (range -20000 20000 13)
                       now (map #(str (Instant/ofEpochMilli (* % 86400000)))
                                [20000 20365 20454 20455 20520])
                       :let [birth (str (LocalDate/ofEpochDay day))
                             today (.toLocalDate (local now))
                             expected (.getYears (java.time.Period/between (LocalDate/parse birth) today))]
                       :when (and (not (neg? expected)) (not= expected (fmt/age birth now)))]
                   [birth now])))))

(deftest ticks-are-those-of-java-time
  (testing "the days: every midnight of the clinic over four years, and stretches across the changes of the clocks"
    (let [from (fmt/millis "2024-12-31T12:00:00Z")
          to (fmt/millis "2029-01-01T12:00:00Z")]
      (is (= (oracle-day-ticks from to 1) (fmt/day-ticks from to 1)))
      (is (= (oracle-day-ticks from to 7) (fmt/day-ticks from to 7))))
    (is (= [] (vec (for [change clock-changes
                         :let [at (fmt/millis change)
                               from (- at (* 40 3600000))
                               to (+ at (* 40 3600000))]
                         every [1 2 3]
                         :when (not= (oracle-day-ticks from to every) (fmt/day-ticks from to every))]
                     [change every])))))
  (testing "the hours and the minutes around each change of the clocks"
    (is (= [] (vec (for [change clock-changes
                         :let [at (fmt/millis change)]
                         [before after] [[2 2] [5 30] [0 3]]
                         every [1 2 3 6]
                         :let [from (- at (* before 3600000) 17000)
                               to (+ at (* after 3600000) 31000)]
                         :when (not= (oracle-hour-ticks from to every) (fmt/hour-ticks from to every))]
                     [change before after every]))))
    (is (= [] (vec (for [change clock-changes
                         :let [at (fmt/millis change)]
                         every [5 15 30 60]
                         :let [from (- at (* 2 3600000) 17000)
                               to (+ at (* 2 3600000) 31000)]
                         :when (not= (oracle-minute-ticks from to every) (fmt/minute-ticks from to every))]
                     [change every]))))))

(defspec the-ticks-of-hours-and-of-minutes-are-in-order-inside-the-stretch-and-on-the-step 200
  (prop/for-all [from (gen/choose (fmt/millis "2026-01-01T00:00:00Z") (fmt/millis "2026-12-31T00:00:00Z"))
                 length (gen/choose 0 (* 36 3600 1000))
                 hours (gen/elements [1 2 3 6 12])
                 minutes (gen/elements [5 10 15 30])]
    (let [to (+ from length)
          hour-ticks (fmt/hour-ticks from to hours)
          minute-ticks (fmt/minute-ticks from to minutes)]
      (and (every? #(<= from (:at %) to) (concat hour-ticks minute-ticks))
           (or (empty? hour-ticks) (apply < (map :at hour-ticks)))
           (or (empty? minute-ticks) (apply < (map :at minute-ticks)))
           (every? #(zero? (mod (:at %) 3600000)) hour-ticks)
           (every? #(zero? (mod (:at %) (* minutes 60000))) minute-ticks)
           ;; none is missed: the count is what the stretch can hold
           (<= (count minute-ticks) (inc (quot length (* minutes 60000))))
           (= (oracle-hour-ticks from to hours) hour-ticks)
           (= (oracle-minute-ticks from to minutes) minute-ticks)))))

(deftest an-instant-is-what-instant-parse-reads
  (testing "the text the api writes, with an offset, a fraction or none"
    (doseq [text ["2026-10-13T08:15:00Z" "2026-10-13T08:15:00.5Z" "2026-10-13T08:15:00.123456789Z" "2026-10-13T08:15:00+01:00"
                  "2026-10-13T08:15:00-05:30" "1970-01-01T00:00:00Z" "2028-02-29T23:59:59Z" "2026-10-13T08:15:00+01:00:30"
                  "2026-10-13T24:00:00Z" "2026-12-31T23:59:60Z"]]
      (is (fmt/instant? text) text)
      (is (= (.toEpochMilli (Instant/parse text)) (fmt/millis text)) text)))
  (testing "and what is not an instant"
    (doseq [text ["2026-10-13" "2026-10-13T08:15Z" "2026-10-13T08:15:00" "2026-02-30T00:00:00Z" "2026-10-13T25:00:00Z" "2026-13-01T00:00:00Z"
                  "2026-10-13T08:15:00+19:00" "" "soon" nil 5 :a ["a"]]]
      (is (not (fmt/instant? text)) (pr-str text)))))

(deftest a-date-is-what-local-date-parse-reads
  (is (fmt/date? "1968-03-14"))
  (is (fmt/date? "2028-02-29"))
  (doseq [text ["2026-02-29" "1968-3-14" "1968-03-14T00:00:00Z" "" nil 5 "March 14"]]
    (is (not (fmt/date? text)) (pr-str text))))

(deftest an-instant-as-text-is-to-the-second-in-utc
  (is (= "2026-10-13T12:00:00Z" (fmt/instant-text 1791892800000)))
  (is (= "2026-10-13T12:00:00Z" (fmt/instant-text 1791892800999)) "the milliseconds are cut")
  (is (= "1969-12-31T23:59:59Z" (fmt/instant-text -1)))
  (is (= "1970-01-01T00:00:00Z" (fmt/instant-text 0))))

(defn- in-locale
  "The result of `f` with the default language of the machine set to `locale`."
  [^Locale locale f]
  (let [before (Locale/getDefault)]
    (try
      (Locale/setDefault locale)
      (f)
      (finally (Locale/setDefault before)))))

(deftest the-language-is-english-whatever-the-machine-speaks
  (doseq [locale [Locale/FRENCH Locale/GERMAN (Locale/forLanguageTag "es-ES")]]
    (in-locale locale
               (fn []
                 (is (= "Tuesday, 13 October 2026" (fmt/long-date "2026-10-13T12:00:00Z")) (str locale))
                 (is (= "14 March 1968" (fmt/birth-date "1968-03-14")) (str locale))
                 (is (= "Oct 13" (fmt/short-date "2026-10-13T12:00:00Z")) (str locale))
                 (is (= "Oct 13" (:label (first (fmt/day-ticks (fmt/millis "2026-10-12T12:00:00Z") (fmt/millis "2026-10-13T12:00:00Z") 1))))
                     (str locale))))))

(deftest a-number-too-big-for-a-whole-number-is-written-as-it-is
  (is (= "1.0E22" (fmt/number 1.0E22)))
  (is (= "138" (fmt/number 138)))
  (is (= "138" (fmt/number 138.0)))
  (is (= "-3" (fmt/number -3)))
  (is (= "0.5" (fmt/number 0.5)))
  (is (= "1/2" (fmt/number 1/2))))

(deftest the-zone-is-the-one-of-the-clinic
  (is (= "Europe/Madrid" (str fmt/zone)))
  (is (str/starts-with? (str (.atZone (Instant/parse "2026-10-13T08:00:00Z") fmt/zone)) "2026-10-13T10:00+02:00")))
