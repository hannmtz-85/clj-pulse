(ns pulse.view.jobs
  "How the interfaces say what the scheduler reports (ADR-0026, ADR-0029): an interval, a schedule, a duration and the name of a number
  in a result, as words. `.cljc`, so that the clinical workspace (in the browser) and the mock-ups of the review (under Babashka) write
  the same text. The times, numbers and units that every interface writes are in `pulse.view.format`.

  Every function answers nil for what it cannot say: other services can sit behind the address of the scheduler, and a page leaves
  out what it cannot say instead of failing for it."
  (:require [clojure.string :as str]))

(defn- every-text [n unit]
  (if (= 1 n) (str "Every " unit) (str "Every " n " " unit "s")))

(defn- whole-ms?
  "Whether `ms` is a number of milliseconds an interval can be: a positive whole number."
  [ms]
  (and (number? ms) (pos? ms) (== ms (Math/floor (double ms)))))

(defn interval-text
  "An interval in milliseconds as people say it: `Every 15 seconds`, `Every minute`, `Every 5 minutes`, `Every hour`. A number that is not
  whole in a unit is said in the smaller one. Nil for what is not a positive whole number of milliseconds."
  [ms]
  (when (whole-ms? ms)
    (cond
      (pos? (mod ms 1000)) (str "Every " ms " ms")
      (zero? (mod ms 3600000)) (every-text (quot ms 3600000) "hour")
      (zero? (mod ms 60000)) (every-text (quot ms 60000) "minute")
      :else (every-text (quot ms 1000) "second"))))

(defn schedule-text
  "A schedule as the api shows it (`{:type \"interval\" :everyMs 30000}` or `{:type \"daily\" :at \"08:00\" :zone ...}`) in words, or
  nil for one that is neither."
  [{:keys [type everyMs at zone]}]
  (case type
    "interval" (interval-text everyMs)
    "daily" (when (and (string? at) (not (str/blank? at)) (string? zone) (not (str/blank? zone)))
              (str "Every day at " at " (" zone ")"))
    nil))

(defn duration-text
  "A duration in milliseconds: `620 ms`, `1.8 s`, `10 s`. Under ten seconds the seconds have a decimal, written the same on the JVM and
  in the browser (`1.0 s`, which JavaScript would write `1`). Nil for what is not a number of milliseconds that is not negative."
  [ms]
  (when (and (number? ms) (not (neg? ms)))
    (cond
      (< ms 1000) (str ms " ms")
      (< ms 9950) (let [tenths (Math/round (/ (double ms) 100.0))]
                    (str (quot tenths 10) "." (rem tenths 10) " s"))
      :else (str (Math/round (/ (double ms) 1000.0)) " s"))))

(defn words
  "A key written in camel case as words: `alreadyRaised` is `already raised`."
  [k]
  (str/lower-case (str/replace (name k) #"([a-z0-9])([A-Z])" "$1 $2")))

(def ^:private singulars
  "The keys of a result that are nouns, in the singular."
  {"patients" "patient" "appointments" "appointment" "readings" "reading" "batches" "batch" "errors" "error" "failed batches" "failed batch"})

(defn counted
  "A number that a run reported, with the name of what it counted: `6 patients`, and `1 patient` for one. A key that is not a noun
  (`raised`, `already sent`) is the same for any number."
  [k n]
  (let [text (words k)]
    (str n " " (if (== 1 n) (get singulars text text) text))))
