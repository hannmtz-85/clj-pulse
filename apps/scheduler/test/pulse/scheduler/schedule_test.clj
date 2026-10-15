(ns pulse.scheduler.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.scheduler.schedule :as schedule :refer [next-run]])
  (:import (java.time Duration Instant LocalDate LocalDateTime LocalTime ZoneId ZonedDateTime)
           (java.time.temporal ChronoUnit)
           (java.time.zone ZoneOffsetTransition ZoneRules)))

(defn- run
  "The next run of `s` after the moment written as `after`, written the same way."
  [s after]
  (str (next-run s (Instant/parse after))))

;; ---------------------------------------------------------------------------
;; Examples: what each kind of schedule says

(deftest an-interval-is-due-on-the-clock
  (let [every-5-minutes {:type :interval :every-ms 300000}]
    (is (= "2026-10-15T10:05:00Z" (run every-5-minutes "2026-10-15T10:02:13Z")))
    (is (= "2026-10-15T10:10:00Z" (run every-5-minutes "2026-10-15T10:05:00Z"))
        "strictly after: a run due at that very moment is done")
    (is (= "2026-10-15T10:05:00Z" (run every-5-minutes "2026-10-15T10:04:59.999999999Z")))
    (is (= "2026-10-15T10:05:00Z" (run every-5-minutes "2026-10-15T10:00:00.0000005Z"))
        "a fraction of a millisecond does not move the grid")))

(deftest an-offset-moves-the-grid
  (let [quarter-past {:type :interval :every-ms 3600000 :offset-ms 900000}]
    (is (= "2026-10-15T10:15:00Z" (run quarter-past "2026-10-15T10:00:00Z")))
    (is (= "2026-10-15T11:15:00Z" (run quarter-past "2026-10-15T10:15:00Z")))
    (is (= "2026-10-15T11:15:00Z" (run quarter-past "2026-10-15T10:59:59Z")))))

(deftest the-grid-is-counted-from-the-epoch
  (testing "so it reaches back before it"
    (is (= "1970-01-01T00:00:00Z" (run {:type :interval :every-ms 60000} "1969-12-31T23:59:30Z")))
    (is (= "1969-12-31T23:59:00Z" (run {:type :interval :every-ms 60000} "1969-12-31T23:58:01Z"))))
  (testing "and a weekly interval is due on Thursdays, as the epoch was one"
    (is (= "2026-10-22T00:00:00Z" (run {:type :interval :every-ms 604800000} "2026-10-15T10:00:00Z")))))

(def ^:private madrid {:type :daily :at "08:30" :zone "Europe/Madrid"})

(deftest a-daily-run-is-at-the-clock-time-of-its-zone
  (testing "Madrid is two hours ahead of UTC in summer and one in winter"
    (is (= "2026-10-15T06:30:00Z" (run madrid "2026-10-15T05:00:00Z")))
    (is (= "2026-12-02T07:30:00Z" (run madrid "2026-12-01T12:00:00Z"))))
  (testing "strictly after: a run due at that very moment is done"
    (is (= "2026-10-16T06:30:00Z" (run madrid "2026-10-15T06:30:00Z"))))
  (testing "the same time of day in other zones"
    (is (= "2026-10-15T12:30:00Z" (run {:type :daily :at "08:30" :zone "America/New_York"} "2026-10-15T12:00:00Z")))
    (is (= "2026-10-15T03:00:00Z" (run {:type :daily :at "08:30" :zone "Asia/Kolkata"} "2026-10-15T00:00:00Z"))))
  (testing "a time may have seconds"
    (is (= "2026-10-15T23:59:59Z" (run {:type :daily :at "23:59:59" :zone "UTC"} "2026-10-15T23:59:58.5Z"))))
  (testing "the leap day is a day"
    (let [noon {:type :daily :at "12:00" :zone "UTC"}]
      (is (= "2028-02-29T12:00:00Z" (run noon "2028-02-28T12:00:00Z")))
      (is (= "2028-03-01T12:00:00Z" (run noon "2028-02-29T12:00:00Z")))
      (is (= "2027-03-01T12:00:00Z" (run noon "2027-02-28T12:00:00Z"))))))

(def ^:private small-hours {:type :daily :at "02:30" :zone "Europe/Madrid"})

(deftest a-time-the-clocks-skip-runs-right-after-the-gap
  ;; On 2026-03-29 clocks in Madrid go from 02:00 to 03:00, at 01:00 UTC: there is no 02:30.
  (is (= "2026-03-29T01:30:00Z" (run small-hours "2026-03-28T12:00:00Z")) "that is 03:30 on the wall")
  (is (= "2026-03-30T00:30:00Z" (run small-hours "2026-03-29T01:30:00Z")) "and the next day it is 02:30 again"))

(deftest a-time-the-clocks-repeat-runs-once
  ;; On 2026-10-25 clocks in Madrid go from 03:00 back to 02:00, at 01:00 UTC: 02:30 happens twice.
  (is (= "2026-10-25T00:30:00Z" (run small-hours "2026-10-24T12:00:00Z")) "the first 02:30")
  (is (= "2026-10-26T01:30:00Z" (run small-hours "2026-10-25T00:30:00Z"))
      "the second one, an hour later, is not a run"))

(deftest a-time-skipped-at-the-end-of-a-day-runs-just-after-midnight
  ;; On 2024-03-31 clocks in Greenland go from 23:00 to 00:00, at 01:00 UTC: there is no 23:30
  ;; on the 30th, so the run of the 30th is at 00:30 on the 31st, and the 31st has its own at 23:30.
  (let [late {:type :daily :at "23:30" :zone "America/Nuuk"}]
    (is (= "2024-03-31T01:30:00Z" (run late "2024-03-30T12:00:00Z")))
    (is (= "2024-03-31T01:30:00Z" (run late "2024-03-31T01:10:00Z"))
        "asked after midnight, the run of the day before is still to come")
    (is (= "2024-04-01T00:30:00Z" (run late "2024-03-31T01:30:00Z")))))

(deftest a-time-repeated-as-the-clocks-go-back-across-midnight-runs-once
  ;; Until 2010 clocks in Newfoundland went back at 00:01 on Sunday to 23:01 on Saturday, so 00:00
  ;; happened at the end of one pass through the evening and the start of the next. The run is the
  ;; first: at 00:00 summer time, which is 02:30 UTC on 2006-10-29.
  (let [midnight {:type :daily :at "00:00" :zone "America/St_Johns"}]
    (is (= "2006-10-29T02:30:00Z" (run midnight "2006-10-28T12:00:00Z")))
    (is (= "2006-10-30T03:30:00Z" (run midnight "2006-10-29T03:00:00Z"))
        "and once the clocks are back, in the second pass, the run for that Sunday is already behind")))

(deftest a-day-that-never-happened-has-no-run-of-its-own
  ;; Samoa went from the 29th of December 2011 to the 31st: the 30th did not exist. The run of the
  ;; 30th is that of the 31st, so there is one run, and a day later in real time.
  (let [afternoon {:type :daily :at "13:00" :zone "Pacific/Apia"}]
    (is (= "2011-12-29T23:00:00Z" (run afternoon "2011-12-29T12:00:00Z")))
    (is (= "2011-12-30T23:00:00Z" (run afternoon "2011-12-29T23:00:00Z")))
    (is (= "2011-12-31T23:00:00Z" (run afternoon "2011-12-30T23:00:00Z")))))

(deftest an-unknown-type-of-schedule-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a known type of schedule"
                        (next-run {:type :monthly} (Instant/now)))))

;; ---------------------------------------------------------------------------
;; The data: what a schedule may say

(deftest what-a-schedule-may-say
  (testing "valid"
    (doseq [s [{:type :interval :every-ms 1}
               {:type :interval :every-ms 604800000 :offset-ms 0}
               {:type :daily :at "00:00" :zone "UTC"}
               {:type :daily :at "23:59:59" :zone "America/St_Johns"}]]
      (is (schedule/valid? s) (pr-str s))
      (is (nil? (schedule/problems s)) (pr-str s))))
  (testing "not valid, and said in words"
    (let [not-a-time {:at ["should be a time of day such as \"08:30\" or \"08:30:15\""]}]
      (doseq [[s expected] [[{:type :interval :every-ms 0} {:every-ms ["should be at least 1"]}]
                            [{:type :interval} {:every-ms ["missing required key"]}]
                            [{:type :interval :every-ms 1.5} {:every-ms ["should be an integer"]}]
                            [{:type :interval :every-ms 604800001} {:every-ms ["should be at most 604800000"]}]
                            [{:type :interval :every-ms 5 :offset-ms -1} {:offset-ms ["should be at least 0"]}]
                            [{:type :interval :every-ms 5 :every 5} {:every ["disallowed key"]}]
                            [{:type :daily :at "8:30" :zone "UTC"} not-a-time]
                            [{:type :daily :at "24:00" :zone "UTC"} not-a-time]
                            [{:type :daily :at "08:30"} {:zone ["missing required key"]}]
                            [{:type :hourly} {:type ["should be an :interval or a :daily schedule"]}]]]
        (is (not (schedule/valid? s)) (pr-str s))
        (is (= expected (schedule/problems s)) (pr-str s)))))
  (testing "a zone is a name of a region, not an offset"
    (doseq [zone ["+02:00" "Mars/Olympus" "" nil :utc]]
      (is (not (schedule/valid? {:type :daily :at "08:30" :zone zone})) (pr-str zone))))
  (testing "something that is not even a map"
    (doseq [s [nil "daily" 42 [:daily]]]
      (is (not (schedule/valid? s)) (pr-str s)))))

;; ---------------------------------------------------------------------------
;; Helpers for the properties

(defn- zone-rules ^ZoneRules [zone]
  (.getRules (ZoneId/of zone)))

(defn- wall
  "`moment` on the clocks of `zone`."
  ^ZonedDateTime [^Instant moment zone]
  (ZonedDateTime/ofInstant moment (ZoneId/of zone)))

(defn- resolved
  "The moment that the time of day `at` on `date` is, on the clocks of `zone`: java.time settles
  the two awkward cases, a time the clocks skip (moved on by the length of the gap) and a time
  they repeat (the first of the two)."
  ^Instant [zone ^LocalDate date at]
  (.toInstant (ZonedDateTime/of date (LocalTime/parse at) (ZoneId/of zone))))

(defn- day-of-run
  "The date whose time of day came out as `moment`, or nil if none did, so `moment` is not a
  run. It is the date `moment` falls on, or the day before when the clocks skipped the time at
  the end of that day and it came out after midnight."
  [{:keys [at zone]} ^Instant moment]
  (let [date (.toLocalDate (wall moment zone))
        day-before (.minusDays date 1)]
    (cond
      (= moment (resolved zone date at)) date
      (= moment (resolved zone day-before at)) day-before)))

(defn- offsets-that-day
  "How many ways the clocks of `zone` have of saying `at` on `date`: none if they skip it, two
  if they repeat it, otherwise one."
  [zone date at]
  (count (.getValidOffsets (zone-rules zone) (LocalDateTime/of date (LocalTime/parse at)))))

(defn- first-run-by-scanning
  "The answer to `next-run` found the slow way, with no idea of which days to try: every minute
  after `after`, for up to 50 hours (a skipped day makes the longest wait), asking whether a run
  is due then. Valid for times of day without seconds, in zones whose offsets are whole minutes."
  [s ^Instant after]
  (let [minute (Duration/ofMinutes 1)
        first-minute (.plus (.truncatedTo after ChronoUnit/MINUTES) minute)]
    (->> (iterate #(.plus ^Instant % minute) first-minute)
         (take (* 50 60))
         (filter #(day-of-run s %))
         first)))

(defn- minutes-between [^Instant from ^Instant to]
  (.toMinutes (Duration/between from to)))

;; ---------------------------------------------------------------------------
;; Generators. The moments are generated to the nanosecond, between 2000 and 2040.

(def ^:private ^Instant from-2000 (Instant/parse "2000-01-01T00:00:00Z"))
(def ^:private ^Instant until-2040 (Instant/parse "2040-01-01T00:00:00Z"))

(def ^:private gen-moment
  (gen/fmap (fn [[seconds nanos]] (Instant/ofEpochSecond seconds nanos))
            (gen/tuple (gen/choose (.getEpochSecond from-2000) (.getEpochSecond until-2040))
                       (gen/choose 0 999999999))))

(def ^:private gen-any-moment
  "Any moment from 1843 to 2096: before the epoch as well."
  (gen/fmap (fn [[seconds nanos]] (Instant/ofEpochSecond seconds nanos))
            (gen/tuple (gen/choose -4000000000 4000000000)
                       (gen/choose 0 999999999))))

(def ^:private week-ms 604800000)

(def ^:private gen-interval
  (gen/let [every (gen/frequency [[1 (gen/return 1)]
                                  [2 (gen/choose 1 1000)]
                                  [2 (gen/elements [60000 300000 3600000 86400000 week-ms])]
                                  [2 (gen/choose 1 week-ms)]])
            offset (gen/one-of [(gen/return nil) (gen/choose 0 (* 2 week-ms))])]
    (cond-> {:type :interval :every-ms every}
      offset (assoc :offset-ms offset))))

;; Zones that change their clocks in different ways: at midnight (Santiago, Havana, Beirut and
;; Cairo, where they go back from 00:00 to 23:00 of the day before), at the end of the day
;; (Greenland, from 23:00 to 00:00), by half an hour or three quarters, by one hour in the other
;; direction (Casablanca), and not at all. Newfoundland went back across midnight until 2010.
(def ^:private zones
  ["UTC" "Europe/Madrid" "Europe/London" "America/New_York" "America/St_Johns" "America/Sao_Paulo"
   "America/Santiago" "America/Havana" "America/Nuuk" "America/Scoresbysund" "Asia/Beirut"
   "Africa/Cairo" "Africa/Casablanca" "Asia/Kolkata" "Asia/Kathmandu" "Asia/Tokyo"
   "Australia/Lord_Howe" "Pacific/Chatham" "Pacific/Auckland"])

(defn- hh-mm
  "The time of day `minutes` after midnight, as the text of a schedule."
  [minutes]
  (format "%02d:%02d" (quot minutes 60) (mod minutes 60)))

(defn- case-around
  "A daily schedule in `zone` and a moment, built around `change` of its clocks: a time of day
  that the change skips or repeats, `minute-in-change` minutes into the stretch it affects
  (-1 is the last minute of it), and a moment `minutes-from-change` minutes from the change."
  [zone ^ZoneOffsetTransition change minute-in-change minutes-from-change]
  (let [^LocalDateTime first-affected (if (.isGap change) (.getDateTimeBefore change) (.getDateTimeAfter change))
        minutes-affected (quot (Math/abs (.getSeconds (.getDuration change))) 60)
        affected (.plusMinutes first-affected (mod minute-in-change minutes-affected))]
    {:schedule {:type :daily
                :at (hh-mm (+ (* 60 (.getHour affected)) (.getMinute affected)))
                :zone zone}
     :after (.plus (.getInstant change) (Duration/ofMinutes minutes-from-change))}))

(def ^:private gen-minute-in-change
  "Anywhere in the stretch a change affects, and often its first or last minute."
  (gen/frequency [[1 (gen/choose 0 1439)] [1 (gen/elements [0 -1])]]))

(def ^:private gen-minutes-from-change
  "From the day before a change to a few hours after it, and often close to it."
  (gen/frequency [[1 (gen/choose -1500 240)] [2 (gen/choose -60 130)]]))

(def ^:private gen-daily-case
  "A daily schedule and a moment. Changes of clocks are rare in a year, so three cases in four
  are built around one in the zone; the rest are a time of day and a moment chosen freely."
  (gen/let [zone (gen/elements zones)
            moment gen-moment
            any-minute (gen/choose 0 1439)
            minute-in-change gen-minute-in-change
            minutes-from-change gen-minutes-from-change
            around-a-change? (gen/frequency [[3 (gen/return true)] [1 (gen/return false)]])]
    (if-let [change (when around-a-change? (.nextTransition (zone-rules zone) moment))]
      (case-around zone change minute-in-change minutes-from-change)
      {:schedule {:type :daily :at (hh-mm any-minute) :zone zone}
       :after moment})))

(def ^:private gen-case
  (gen/one-of [(gen/fmap (fn [[s moment]] {:schedule s :after moment}) (gen/tuple gen-interval gen-any-moment))
               gen-daily-case]))

;; Every change of clocks in every zone the JDK knows, between 2000 and 2040. The two families
;; below are the ones that make `next-run` look at the day before the date of `after`, and at the
;; day after next. A random zone and moment hardly ever reach them, so cases are built from them.

(def ^:private tzdb-changes
  (vec (for [zone (sort (ZoneId/getAvailableZoneIds))
             :let [rules (zone-rules zone)]
             ^ZoneOffsetTransition change (take-while #(and % (.isBefore (.getInstant ^ZoneOffsetTransition %) until-2040))
                                                      (iterate #(.nextTransition rules (.getInstant ^ZoneOffsetTransition %))
                                                               (.nextTransition rules from-2000)))]
         {:zone zone :change change})))

(defn- date-of ^LocalDate [^LocalDateTime date-time]
  (.toLocalDate date-time))

(def ^:private late-gaps
  "The changes that skip a time which then comes out on a later date than the one that skipped it:
  Greenland's clocks go from 23:00 to 00:00, and Samoa skipped a whole day."
  (filterv (fn [{:keys [^ZoneOffsetTransition change]}]
             (and (.isGap change)
                  (not= (date-of (.getDateTimeBefore change)) (date-of (.getDateTimeAfter change)))))
           tzdb-changes))

(def ^:private late-overlaps
  "The changes that put the clocks back to an earlier date, from a time after midnight: those of
  Newfoundland, which went from 00:01 to 23:01. Not those from exactly midnight, as Cairo's go:
  the clocks never showed the later date before they went back."
  (filterv (fn [{:keys [^ZoneOffsetTransition change]}]
             (let [^LocalDateTime before (.getDateTimeBefore change)]
               (and (.isOverlap change)
                    (.isAfter (date-of before) (date-of (.getDateTimeAfter change)))
                    (not= LocalTime/MIDNIGHT (.toLocalTime before)))))
           tzdb-changes))

(defn- gen-case-at
  "A case built around one of `changes` (see `tzdb-changes`)."
  [changes]
  (gen/let [{:keys [zone change]} (gen/elements changes)
            minute-in-change gen-minute-in-change
            minutes-from-change gen-minutes-from-change]
    (case-around zone change minute-in-change minutes-from-change)))

;; ---------------------------------------------------------------------------
;; Properties

(defspec an-interval-run-is-the-next-point-of-the-grid 300
  (prop/for-all [s gen-interval
                 after gen-any-moment]
    (let [due (next-run s after)
          every (:every-ms s)
          offset (:offset-ms s 0)]
      (and (.isAfter due after)
           (zero? (mod (- (.toEpochMilli due) offset) every))
           (not (.isAfter (.minusMillis due every) after))))))

(defspec successive-interval-runs-are-one-period-apart 200
  (prop/for-all [s gen-interval
                 after gen-any-moment]
    (let [runs (take 6 (rest (iterate #(next-run s %) after)))]
      (= [(Duration/ofMillis (:every-ms s))]
         (distinct (map #(Duration/between %1 %2) runs (rest runs)))))))

(defspec a-daily-run-is-the-first-the-slow-way-finds 400
  (prop/for-all [{:keys [schedule after]} gen-daily-case]
    (= (next-run schedule after) (first-run-by-scanning schedule after))))

(defspec a-time-skipped-at-the-end-of-a-day-is-found-after-midnight 200
  (prop/for-all [{:keys [schedule after]} (gen-case-at late-gaps)]
    (= (next-run schedule after) (first-run-by-scanning schedule after))))

(defspec a-time-repeated-as-the-clocks-go-back-across-midnight-is-found 200
  (prop/for-all [{:keys [schedule after]} (gen-case-at late-overlaps)]
    (= (next-run schedule after) (first-run-by-scanning schedule after))))

(defspec a-daily-run-is-at-the-time-asked-for-or-after-a-gap 300
  (prop/for-all [{:keys [schedule after]} gen-daily-case]
    (let [{:keys [at zone]} schedule
          on-the-wall (wall (next-run schedule after) zone)
          date (.toLocalDate on-the-wall)]
      (or (= (LocalTime/parse at) (.toLocalTime on-the-wall))
          ;; the clocks skipped it on that date, or on the day before and it came out after midnight
          (zero? (offsets-that-day zone date at))
          (zero? (offsets-that-day zone (.minusDays date 1) at))))))

(defspec a-daily-schedule-runs-once-for-each-day 100
  (prop/for-all [{:keys [schedule after]} gen-daily-case]
    (let [runs (take 10 (rest (iterate #(next-run schedule %) after)))
          days (map #(day-of-run schedule %) runs)]
      (and (every? some? days)
           ;; one day after another: none is missed, none comes twice
           (= days (take 10 (iterate #(.plusDays ^LocalDate % 1) (first days))))
           ;; a day lasts 24 hours, or 23 or 25 where the clocks change by an hour
           (every? #(<= (* 23 60) % (* 25 60)) (map minutes-between runs (rest runs)))))))

(defspec time-never-goes-back-for-any-schedule 300
  (prop/for-all [{:keys [schedule after]} gen-case
                 other gen-any-moment]
    (let [[early late] (sort [after other])]
      (not (.isAfter (next-run schedule early) (next-run schedule late))))))

;; ---------------------------------------------------------------------------
;; What the generators reach, and what the time zone database holds

(defn- days-ahead
  "How many days after the date of `after`, on the clocks of the zone, the day of the next run is.
  Usually 0 or 1; -1 for the run of the day before that comes out after midnight, and 2 when the
  next day's run has already been, because the clocks went back across midnight."
  [{:keys [schedule after]}]
  (.between ChronoUnit/DAYS
            (.toLocalDate (wall after (:zone schedule)))
            (day-of-run schedule (next-run schedule after))))

(deftest the-generators-reach-the-changes-of-clocks
  ;; A property over zones is worth what its generator produces, so count it: how many of the
  ;; cases are for a day on which the clocks skip the time asked for, or repeat it.
  (let [cases (gen/sample gen-daily-case 1000)
        tally (frequencies (for [{:keys [schedule after]} cases
                                 :let [{:keys [at zone]} schedule
                                       day (.toLocalDate (wall (next-run schedule after) zone))]]
                             (offsets-that-day zone day at)))]
    ;; The generator gives about 150 and 120 in 1000, with a spread of about 11
    (is (< 60 (get tally 0 0)) "days with a time the clocks skip")
    (is (< 60 (get tally 2 0)) "days with a time the clocks repeat")))

(deftest the-generators-reach-the-days-next-run-tries
  (let [tally (fn [generator] (frequencies (map days-ahead (gen/sample generator 600))))]
    ;; The generators give about 55 and 25 in 600 (the second is one minute of the hour, at the
    ;; right moment), and a free choice of zone and moment gives almost none.
    (is (< 25 (get (tally (gen-case-at late-gaps)) -1 0))
        "the run of the day before, which comes out after midnight")
    (is (< 8 (get (tally (gen-case-at late-overlaps)) 2 0))
        "the day after next, because the next day's run came before the clocks went back")
    (testing "and the ordinary days"
      (let [ordinary (tally gen-daily-case)]
        (is (< 100 (get ordinary 0 0)))
        (is (< 100 (get ordinary 1 0)))))))

(deftest the-days-next-run-tries-are-enough-for-every-change-of-clocks
  ;; `next-run` tries the day before the date of `after` and the two after it. That covers a time
  ;; skipped by less than a day, which comes out on the same date or the next, and clocks that go
  ;; back across at most one date. If a future version of the time zone database breaks either,
  ;; this fails and the candidates must grow.
  (is (< 10000 (count tzdb-changes)) "the whole database was read")
  (is (seq late-gaps))
  (is (seq late-overlaps))
  (testing "no change moves the clocks by more than a day"
    (is (every? (fn [{:keys [^ZoneOffsetTransition change]}]
                  (<= (Math/abs (.getSeconds (.getDuration change))) 86400))
                tzdb-changes)))
  (testing "no change puts the clocks back by more than one date"
    (is (every? (fn [{:keys [^ZoneOffsetTransition change]}]
                  (<= (.between ChronoUnit/DAYS
                                (date-of (.getDateTimeAfter change))
                                (date-of (.getDateTimeBefore change)))
                      1))
                tzdb-changes))))
