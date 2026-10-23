(ns pulse.design.format
  "How the mock-ups of the workspace say what the scheduler reports (ADR-0026): an interval, a schedule, a duration and
  the name of a number in a result, as words. The times, numbers and units that every interface writes are in
  `pulse.web.format`."
  (:require [clojure.string :as str]))

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
