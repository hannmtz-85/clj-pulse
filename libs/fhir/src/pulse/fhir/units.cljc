(ns pulse.fhir.units
  "UCUM units used by Pulse: building FHIR Quantity maps and converting between units.

  Every unit is described by data: a dimension, and a linear map to the dimension's base
  unit (base = value * factor + offset). Converting is then the same two steps for all
  units, and adding a unit is one entry in `definitions`."
  (:require [pulse.fhir.terminology :as t]))

(def definitions
  "UCUM code -> {:dim :factor :offset :label}. `:label` is the human-readable FHIR `unit`."
  {"kg" {:dim :mass :factor 1.0 :label "kg"}
   "g" {:dim :mass :factor 0.001 :label "g"}
   "[lb_av]" {:dim :mass :factor 0.45359237 :label "lb"}
   "Cel" {:dim :temperature :factor 1.0 :offset 0.0 :label "°C"}
   "[degF]" {:dim :temperature :factor (/ 5.0 9.0) :offset (/ -160.0 9.0) :label "°F"}
   "K" {:dim :temperature :factor 1.0 :offset -273.15 :label "K"}
   "mm[Hg]" {:dim :pressure :factor 1.0 :label "mmHg"}
   "kPa" {:dim :pressure :factor 7.500616827 :label "kPa"}
   ;; Glucose only: 1 mmol/L = 18.0156 mg/dL (molar mass 180.156 g/mol).
   "mg/dL" {:dim :glucose-concentration :factor 1.0 :label "mg/dL"}
   "mmol/L" {:dim :glucose-concentration :factor 18.0156 :label "mmol/L"}
   "/min" {:dim :rate :factor 1.0 :label "beats/minute"}
   "%" {:dim :fraction :factor 1.0 :label "%"}
   "kg/m2" {:dim :body-mass-index :factor 1.0 :label "kg/m²"}})

(defn known?
  "True when `unit` (a UCUM code) is defined."
  [unit]
  (contains? definitions unit))

(defn dimension
  "The dimension keyword of `unit`, or nil."
  [unit]
  (:dim (get definitions unit)))

(defn label
  "The human-readable label of `unit` (the FHIR Quantity `unit`), falling back to the code."
  [unit]
  (:label (get definitions unit) unit))

(defn compatible?
  "True when both units are defined and measure the same dimension."
  [a b]
  (let [da (dimension a)]
    (boolean (and da (= da (dimension b))))))

(defn quantity
  "A FHIR Quantity map: (quantity 120 \"mm[Hg]\")."
  [value unit]
  {:value value
   :unit (label unit)
   :system (:ucum t/systems)
   :code unit})

(defn convert
  "Converts `value` from UCUM unit `from` to `to`. Throws when a unit is unknown or the
  units measure different dimensions."
  [value from to]
  (when-not (compatible? from to)
    (throw (ex-info "Cannot convert between these units" {:from from :to to})))
  (let [{f1 :factor o1 :offset} (get definitions from)
        {f2 :factor o2 :offset} (get definitions to)
        base (+ (* value f1) (or o1 0.0))]
    (/ (- base (or o2 0.0)) f2)))
