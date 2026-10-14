(ns pulse.fhir.ranges
  "Didactic reference bands for the measurements Pulse tracks. NOT clinical advice.

  A band is [label lower upper): `lower` is inclusive, `upper` exclusive and nil means
  unbounded. The bands of one measurement are contiguous, so every number falls in exactly
  one of them (this is checked by a property test)."
  (:require [pulse.fhir.units :as units]))

(def measurements
  {:systolic {:unit "mm[Hg]"
              :bands [[:low nil 90] [:normal 90 120] [:elevated 120 140] [:high 140 160] [:very-high 160 nil]]}
   :diastolic {:unit "mm[Hg]"
               :bands [[:low nil 60] [:normal 60 80] [:elevated 80 90] [:high 90 100] [:very-high 100 nil]]}
   :heart-rate {:unit "/min"
                :bands [[:low nil 50] [:normal 50 100] [:high 100 120] [:very-high 120 nil]]}
   :spo2 {:unit "%"
          :bands [[:very-low nil 90] [:low 90 92] [:normal 92 nil]]}
   :glucose {:unit "mg/dL"
             :bands [[:very-low nil 54] [:low 54 70] [:normal 70 140] [:high 140 250] [:very-high 250 nil]]}
   :body-temperature {:unit "Cel"
                      :bands [[:low nil 36.0] [:normal 36.0 37.8] [:fever 37.8 39.0] [:high-fever 39.0 nil]]}})

(defn- in-band? [value [_ lower upper]]
  (and (or (nil? lower) (>= value lower))
       (or (nil? upper) (< value upper))))

(defn classify
  "The band label (e.g. :normal, :very-high) of a measurement, or nil when the
  measurement is not tracked, or when `unit` is not one it can be converted from: a reading
  that cannot be classified is no reason to fail the one that shows it. `unit` is converted to
  the measurement's own unit first; it defaults to that unit."
  ([measurement value] (classify measurement value nil))
  ([measurement value unit]
   (when-let [{base-unit :unit :keys [bands]} (get measurements measurement)]
     (when (or (nil? unit) (= unit base-unit) (units/compatible? unit base-unit))
       (let [v (if (and unit (not= unit base-unit)) (units/convert value unit base-unit) value)]
         (some (fn [band] (when (in-band? v band) (first band))) bands))))))

(def severity
  "How far from normal each band label is, 0 being normal. It lets a caller pick the worse of
  several bands: a blood pressure is as bad as the worse of its two components."
  {:normal 0
   :low 1 :elevated 1
   :very-low 2 :high 2 :fever 2
   :very-high 3 :high-fever 3})

(defn worst
  "The most severe of `bands` (labels as `classify` returns them), or nil for none. Of equal
  severity, the first wins."
  [bands]
  (when (seq bands)
    (reduce (fn [a b] (if (> (severity b) (severity a)) b a)) bands)))
