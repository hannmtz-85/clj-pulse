(ns pulse.platform.test.clock
  "A clock that moves only when a test says so, for the code that takes a `java.time.Clock`.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:import (java.time Clock Instant ZoneOffset)))

(defn clock
  "A clock that begins at `start`, an ISO instant: a map of `:clock` and `:now`, the atom holding
  the time."
  [start]
  (let [now (atom (Instant/parse start))]
    {:now now
     :clock (proxy [Clock] []
              (instant [] @now)
              (getZone [] ZoneOffset/UTC)
              (withZone [_] this))}))

(defn advance!
  "Moves the clock forward by `ms` milliseconds."
  [{:keys [now]} ms]
  (swap! now (fn [^Instant instant] (.plusMillis instant ms)))
  nil)

(defn set-time!
  "Moves the clock to `instant`, an ISO string."
  [{:keys [now]} instant]
  (reset! now (Instant/parse instant))
  nil)

(defn instant
  "What time the clock says it is."
  ^Instant [{:keys [now]}]
  @now)
