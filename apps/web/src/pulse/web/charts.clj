(ns pulse.web.charts
  "The charts of the interfaces (ADR-0028): one measurement over a stretch of time, drawn from the readings the api answered. The
  drawing is `pulse.ui.chart`; this is what a reading needs to become one: which series a measurement has, the range it is drawn
  over, the band of its normal values and the lines of the rules, the words for a screen reader, and the labels of the axis. Pure,
  and loaded by Babashka for the mock-ups of the review, which give it the synthetic cohort, so that what is reviewed is what is
  served."
  (:require [pulse.fhir.ranges :as ranges]
            [pulse.ui.chart :as chart]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; What is drawn behind and over the series

(def ^:private kind-bands
  "The reference range that is shaded behind a series: the normal band of `pulse.fhir.ranges`."
  {:heart-rate :heart-rate :spo2 :spo2 :glucose :glucose :temperature :body-temperature})

(defn- normal-band
  "The shaded range of the normal band of the measurement `band-key`, with a word."
  [band-key]
  (when-let [[_ lower upper] (some #(when (= :normal (first %)) %) (get-in ranges/measurements [band-key :bands]))]
    {:from (or lower -1000) :to (or upper 1000) :tone :normal :label "Normal"}))

(def ^:private thresholds
  "The lines of the rules of Annex C, drawn on the chart of the measurement they watch, with the word beside each."
  {:blood-pressure [{:at 160 :tone :critical :label "Stage 2 · 160"}
                    {:at 100 :tone :high :label "Stage 2 · 100"}]
   :spo2 [{:at 92 :tone :critical :label "Low · 92"}]
   :glucose [{:at 250 :tone :critical :label "High · 250"}
             {:at 70 :tone :critical :label "Low · 70"}]})

(def ^:private kind-domains
  "The range each measurement is drawn over, wide enough to give a reading its context. It grows to hold the data."
  {:blood-pressure [60 200] :heart-rate [40 140] :spo2 [85 100] :glucose [50 300] :temperature [35 41]})

(defn- y-domain
  "Round numbers over the usual range of `kind`, or of the `values` if they go beyond it; nil for a measurement with no usual
  range, so that the chart chooses."
  [kind values]
  (when-let [[lo hi] (kind-domains kind)]
    (let [{:keys [lo hi]} (chart/nice-ticks (apply min lo values) (apply max hi values) 5)]
      [lo hi])))

;; ---------------------------------------------------------------------------
;; The axis of time

(def ^:private day-millis (* 24 3600 1000))

(defn x-ticks
  "Ticks for the axis of time between two moments in epoch milliseconds: midnights of the clinic's day every 1, 2, 5, 7, 10 or 14
  days, and, in a stretch of less than two days, minutes every 5, 10, 15 or 30 and whole hours every 1, 2, 3, 6 or 12. The smallest
  step with no more than `max-ticks` ticks (and at least two) is the one; when even the largest has more, it is the largest with
  two or more, since an axis with labels that are a little close is better than one with none. Empty when no step has two."
  [from to max-ticks]
  (let [candidates (if (< (- to from) (* 2 day-millis))
                     (concat (for [minutes [5 10 15 30]] #(fmt/minute-ticks from to minutes))
                             (for [hours [1 2 3 6 12]] #(fmt/hour-ticks from to hours)))
                     (for [days [1 2 5 7 10 14]] #(fmt/day-ticks from to days)))
        tickings (filter #(<= 2 (count %)) (map #(vec (%)) candidates))]
    (or (first (filter #(<= (count %) max-ticks) tickings))
        (last tickings)
        [])))

;; ---------------------------------------------------------------------------
;; The chart

(defn- points
  "`[[millis value] ...]` of one `channel` (`:value`, `:systolic` or `:diastolic`) of `readings`."
  [readings channel]
  (for [reading readings]
    [(fmt/millis (:at reading)) (get reading channel)]))

(defn- sentence
  "What the chart shows, for a screen reader: the range of the numbers and the latest reading."
  [kind label readings span now]
  (let [values (for [reading readings
                     value (if (= :blood-pressure kind) [(:systolic reading) (:diastolic reading)] [(:value reading)])]
                 value)
        unit (fmt/unit (:unit (last readings)))
        newest (last readings)]
    (str label " over the " span ": between " (fmt/number (apply min values)) " and " (fmt/number (apply max values)) " " unit
         "; the latest, " (fmt/when-phrase (:at newest) now) ", is " (views/reading-text kind newest) " " unit ".")))

(defn line-chart
  "The chart of the readings of one measurement, in a drawing of `width` by `height` pixels, or nil when none of them says anything.
  The options:

    `:kind`         the measurement, such as `:heart-rate`
    `:readings`     its readings in the shape of the api, in any order (those that have no time or no number are left out)
    `:now`          the instant it is, as text: the end of the axis of time, unless a reading is later, and the day the words of
                    the table are told from
    `:from`         the instant the axis begins at, as text
    `:span`         what the chart covers, in a phrase that follows `the`: `last 30 days` or `latest 500 readings`
    `:id`           for the ids that tie the title and the description to the drawing
    `:width` `:height`  the drawing (640 by 260 pixels)
    `:ticks`        the ticks of the axis of time as `{:at :label}`, or, without them, `:max-ticks` (6) of the ones `x-ticks` chooses
    `:thresholds?` `:bands?`  the lines of the rules and the shaded normal range, with their words in the right margin (both on)"
  [{:keys [kind readings now from span id width height ticks max-ticks thresholds? bands?]
    :or {width 640 height 260 max-ticks 6 thresholds? true bands? true}}]
  (let [readings (vec (views/usable-series kind readings))]
    (when (seq readings)
      (let [{:keys [label]} (views/kind-info kind)
            from-ms (fmt/millis from)
            ;; a reading from the future, by a moment (the clocks of two machines differ), is still on the axis
            to-ms (max (fmt/millis now) (fmt/millis (:at (peek readings))))
            unit (fmt/unit (:unit (peek readings)))
            series (if (= :blood-pressure kind)
                     [{:label "Systolic" :unit unit :points (points readings :systolic)}
                      {:label "Diastolic" :unit unit :points (points readings :diastolic)}]
                     [{:label label :unit unit :points (points readings :value)}])
            values (for [s series [_ v] (:points s)] v)
            lines (when thresholds? (get thresholds kind))
            band (when bands? (some-> (kind-bands kind) normal-band))
            domain (y-domain kind values)]
        (chart/line-chart
         (cond-> {:id (or id (str "chart-" (name kind) "-" width))
                  :title (str label ", " span)
                  :description (sentence kind label readings span now)
                  :width width :height height
                  :x-domain [from-ms to-ms]
                  :x-ticks (or ticks (x-ticks from-ms to-ms max-ticks))
                  :x-label (fn [x] (fmt/when-text (str (Instant/ofEpochMilli (long x))) now))
                  :series series}
           domain (assoc :y-domain domain)
           lines (assoc :thresholds lines)
           band (assoc :bands [band])))))))
