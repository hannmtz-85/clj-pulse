(ns pulse.design.specimen
  "The pieces the pages of the mock-ups share (ADR-0026): what a reading looks like as a tile and as a chart, made
  from the cohort. This is the layer between the shape of the api and the components of `libs/ui`, which the
  pages of `web` and `spa` will have too."
  (:require [pulse.design.data :as data]
            [pulse.fhir.ranges :as ranges]
            [pulse.ui.chart :as chart]
            [pulse.web.format :as fmt]
            [pulse.web.views :as web-views]))

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

(defn points
  "`[[millis value] ...]` of one `channel` (`:value`, `:systolic` or `:diastolic`) of `readings`."
  [readings channel]
  (for [r readings
        :let [v (get r channel)]
        :when (some? v)]
    [(fmt/millis (:at r)) v]))

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
  "Round numbers over the usual range of `kind`, or of the `values` if they go beyond it; nil for a measurement
  with no usual range, so that the chart chooses."
  [kind values]
  (when-let [[lo hi] (kind-domains kind)]
    (let [{:keys [lo hi]} (chart/nice-ticks (apply min lo values) (apply max hi values) 5)]
      [lo hi])))

(defn- sentence [kind label series-readings days]
  (let [values (for [r series-readings
                     v (if (= :blood-pressure kind) [(:systolic r) (:diastolic r)] [(:value r)])
                     :when (some? v)]
                 v)
        unit (fmt/unit (:unit (last series-readings)))
        newest (last series-readings)]
    (str label " over the last " days " days: between " (fmt/number (apply min values)) " and "
         (fmt/number (apply max values)) " " unit "; the latest, "
         (fmt/when-phrase (:at newest) data/now-text) ", is " (web-views/reading-text kind newest) " " unit ".")))

(defn line-chart
  "The chart of a patient's `kind` over `days` days, in a drawing of `width` by `height` pixels. Options:
  `:id`, `:width`, `:height`, `:ticks-every` (days between the labels of the x axis), `:thresholds?` and
  `:bands?` (the lines of the rules and the shaded normal range, with their words in the right margin)."
  [patient-id kind {:keys [id days width height ticks-every thresholds? bands?]
                    :or {days 7 width 640 height 260 ticks-every 1 thresholds? true bands? true}}]
  (let [{:keys [label]} (web-views/kind-info kind)
        recent (vec (within (data/readings patient-id kind) days))
        to (.toEpochMilli data/now)
        from (- to (* days 24 3600 1000))
        unit (fmt/unit (:unit (last recent)))
        series (if (= :blood-pressure kind)
                 [{:label "Systolic" :unit unit :points (points recent :systolic)}
                  {:label "Diastolic" :unit unit :points (points recent :diastolic)}]
                 [{:label label :unit unit :points (points recent :value)}])
        values (for [s series [_ v] (:points s)] v)
        lines (when thresholds? (get thresholds kind))
        band (when bands? (some-> (kind-bands kind) normal-band))
        domain (y-domain kind values)]
    (chart/line-chart
     (cond-> {:id (or id (str "chart-" (name kind) "-" patient-id "-" width))
              :title (str label ", last " days " days")
              :description (sentence kind label recent days)
              :width width :height height
              :x-domain [from to]
              :x-ticks (if (<= days 2)
                         (fmt/hour-ticks from to 6)
                         (fmt/day-ticks from to ticks-every))
              :x-label (fn [x] (fmt/when-text (str (java.time.Instant/ofEpochMilli (long x))) data/now-text))
              :series series}
       domain (assoc :y-domain domain)
       (seq lines) (assoc :thresholds lines)
       band (assoc :bands [band])))))

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
