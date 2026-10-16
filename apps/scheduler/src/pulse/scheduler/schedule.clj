(ns pulse.scheduler.schedule
  "When a job is due (ADR-0019): schedules as data, and `next-run`, a pure function from a
  schedule and a moment to the next moment the schedule is due. JVM only.

  There are two kinds of schedule, both plain EDN so that the configuration can state them:

    {:type :interval :every-ms 300000}                     every five minutes, on the clock
    {:type :interval :every-ms 3600000 :offset-ms 900000}  every hour, at a quarter past
    {:type :daily :at \"08:30\" :zone \"Europe/Madrid\"}      once a day, at 08:30 in Madrid"
  (:require [malli.core :as m]
            [malli.error :as me])
  (:import (java.time Instant LocalTime ZoneId ZonedDateTime)))

(def ^:private max-interval-ms
  "A week. A job that runs less often is a calendar, not an interval."
  (* 7 24 60 60 1000))

(def ^:private time-of-day
  [:re {:error/message "should be a time of day such as \"08:30\" or \"08:30:15\""}
   #"^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$"])

(def ^:private zone-ids (ZoneId/getAvailableZoneIds))

(def time-zone
  "The schema of the name of a time zone, one that `java.time` knows, such as \"Europe/Madrid\". A
  daily schedule has one, and so do the settings that say in which zone a time is shown."
  [:fn {:error/message "should be the name of a time zone such as \"Europe/Madrid\" or \"UTC\""}
   (fn [x] (and (string? x) (contains? zone-ids x)))])

(def schema
  [:multi {:dispatch :type
           :error/message "should be an :interval or a :daily schedule"}
   [:interval
    [:map {:closed true}
     [:type [:= :interval]]
     [:every-ms [:int {:min 1 :max max-interval-ms}]]
     [:offset-ms {:optional true} [:int {:min 0}]]]]
   [:daily
    [:map {:closed true}
     [:type [:= :daily]]
     [:at time-of-day]
     [:zone time-zone]]]])

(def ^:private schedule?
  (m/validator schema))

(defn valid?
  "True when `schedule` satisfies `schema`."
  [schedule]
  (schedule? schedule))

(defn problems
  "What is wrong with `schedule`, as malli's humanized errors, or nil when nothing is."
  [schedule]
  (some-> (m/explain schema schedule) me/humanize))

(defn- next-interval
  "The next point of the grid `offset + k * every`, k a whole number, counted in milliseconds
  from the epoch. An `after` with a finer fraction counts as the millisecond it falls in, and
  the answer is still strictly after it."
  [{:keys [every-ms offset-ms]} ^Instant after]
  (let [every (long every-ms)
        offset (long (or offset-ms 0))
        t (.toEpochMilli after)]
    (Instant/ofEpochMilli (+ t (- every (Math/floorMod (- t offset) every))))))

(defn- next-daily
  "The first run, strictly after `after`, among those of the days around the date `after` falls
  on in the zone. A day's run is the instant its date and the time of day resolve to, which
  `java.time` settles for the two awkward cases: a time that clocks skip is moved forward by the
  length of the gap, and a time they repeat is the first of the two. The runs of successive days
  come in time order, so the first that is after `after` is the answer.

  The days tried are the day before, because when clocks skip the end of a day (Greenland's go
  from 23:00 to 00:00) its run comes out after midnight, on the next date; that date itself;
  the next; and the one after, because when clocks go back across midnight from a time after it
  (Newfoundland's did, until 2010) the next day's run may already have passed. The tests check
  that no change in the time zone database needs more."
  [{:keys [at zone]} ^Instant after]
  (let [zone (ZoneId/of zone)
        time-of-day (LocalTime/parse at)
        today (.toLocalDate (ZonedDateTime/ofInstant after zone))]
    (->> (range -1 3)
         (map (fn [days] (.toInstant (ZonedDateTime/of (.plusDays today (long days)) time-of-day zone))))
         (filter (fn [^Instant run] (.isAfter run after)))
         first)))

(defn next-run
  "The first instant, strictly after `after`, at which `schedule` is due. `schedule` is data
  (see `schema`; it is not checked again here), and `after` and the answer are
  `java.time.Instant`s. There is no clock and no state, so the same two arguments always give
  the same answer. A run due at `after` itself counts as done: ask from the moment a run ends
  and the answer is the run to wait for next."
  ^Instant [schedule ^Instant after]
  (case (:type schedule)
    :interval (next-interval schedule after)
    :daily (next-daily schedule after)
    (throw (ex-info "Not a known type of schedule" {:schedule schedule}))))
