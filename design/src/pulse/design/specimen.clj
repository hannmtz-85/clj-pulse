(ns pulse.design.specimen
  "The pieces the pages of the mock-ups share (ADR-0026): what a reading looks like as a tile and as a chart, made
  from the cohort. This is the layer between the shape of the api and the components of `libs/ui`, which the
  pages of `web` and `spa` will have too. The chart itself is `pulse.web.charts`, which the portal serves too: what
  the cohort gives it here is what the api gives it there."
  (:require [pulse.design.data :as data]
            [pulse.ui.chart :as chart]
            [pulse.web.charts :as charts]
            [pulse.web.format :as fmt]
            [pulse.web.views :as web-views])
  (:import (java.time Instant)))

(defn vital-tile
  "The tile of the latest reading `reading` of `kind`, as it is at the moment of the mock-ups."
  ([kind reading] (vital-tile kind reading nil))
  ([kind reading attributes] (web-views/vital-tile kind reading data/now-text attributes)))

;; ---------------------------------------------------------------------------
;; Charts

(defn within
  "The readings of the last `days` days up to the moment of the mock-ups."
  [readings days]
  (let [from (- (.toEpochMilli data/now) (* days 24 3600 1000))]
    (filter #(>= (fmt/millis (:at %)) from) readings)))

(defn- points
  "`[[millis value] ...]` of one `channel` (`:value`, `:systolic` or `:diastolic`) of `readings`."
  [readings channel]
  (for [r readings
        :let [v (get r channel)]
        :when (some? v)]
    [(fmt/millis (:at r)) v]))

(defn line-chart
  "The chart of a patient's `kind` over `days` days, in a drawing of `width` by `height` pixels. Options:
  `:id`, `:width`, `:height`, `:ticks-every` (days between the labels of the x axis), `:thresholds?` and
  `:bands?` (the lines of the rules and the shaded normal range, with their words in the right margin)."
  [patient-id kind {:keys [id days width height ticks-every thresholds? bands?]
                    :or {days 7 width 640 height 260 ticks-every 1 thresholds? true bands? true}}]
  (let [to (.toEpochMilli data/now)
        from (- to (* days 24 3600 1000))]
    (charts/line-chart
     {:kind kind
      :readings (within (data/readings patient-id kind) days)
      :now data/now-text
      :from (str (Instant/ofEpochMilli from))
      :span (str "last " days " days")
      :id (or id (str "chart-" (name kind) "-" patient-id "-" width))
      :width width :height height
      :ticks (if (<= days 2)
               (fmt/hour-ticks from to 6)
               (fmt/day-ticks from to ticks-every))
      :thresholds? thresholds? :bands? bands?})))

(defn sparkline
  "A small line of the last `days` days of `kind`, toned like the latest reading."
  [patient-id kind days]
  (let [recent (vec (within (data/readings patient-id kind) days))
        channel (if (= :blood-pressure kind) :systolic :value)
        band (some-> (:band (peek recent)) keyword)
        tone (case band
               (:very-high :high-fever) :critical
               (:very-low :high :fever) :high
               (:low :elevated) :elevated
               :neutral)]
    (chart/sparkline {:points (points recent channel) :tone tone
                      :label (str (get-in web-views/kind-info [kind :label]) " over the last " days " days")})))
