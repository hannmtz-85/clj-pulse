(ns pulse.fhir.observation
  "Building and reading Observation resources.

  FHIR resources are plain maps with keyword keys (the JSON keys, keywordized). The
  interesting part of Observation is `value[x]`: the value lives under one of several
  keys (`valueQuantity`, `valueString`, ...). `value-of` is a multimethod on that key,
  so supporting another type is one `defmethod`."
  (:require [pulse.fhir.coding :as coding]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.terminology :as t]
            [pulse.fhir.time :as time]
            [pulse.fhir.units :as units]))

(defn- category-concept [code]
  {:coding [(assoc (t/status-coding :observation-category code)
                   :display (case code
                              "vital-signs" "Vital Signs"
                              "laboratory" "Laboratory"
                              code))]})

(defn- with-performer
  "`observation` with the `performer` element for the options `m` it was built from: the
  patient, since home measurements are taken by the patient themselves, unless `:performer`
  says otherwise. That is a reference such as \"Practitioner/pr-rivera\", or nil when nobody is
  to be named."
  [observation {:keys [patient] :as m}]
  (let [performer (if (contains? m :performer) (:performer m) (str "Patient/" patient))]
    (cond-> observation
      performer (assoc :performer [{:reference performer}]))))

(defn quantity-observation
  "An Observation holding one Quantity. `concept` is a catalogue key such as :heart-rate;
  `at` is the effective dateTime; `category` is \"vital-signs\" (default) or \"laboratory\".
  `performer` is who took the reading: the patient unless given (a reference, or nil for
  nobody)."
  [{:keys [id patient concept value unit at category] :or {category "vital-signs"} :as m}]
  (-> {:resourceType "Observation"
       :id id
       :status "final"
       :category [(category-concept category)]
       :code (t/codeable-concept concept)
       :subject (ref/reference "Patient" patient)
       :effectiveDateTime at
       :valueQuantity (units/quantity value unit)}
      (with-performer m)))

(defn blood-pressure-observation
  "A blood pressure panel (LOINC 85354-9) with systolic and diastolic components. `performer`
  as in `quantity-observation`."
  [{:keys [id patient systolic diastolic at] :as m}]
  (-> {:resourceType "Observation"
       :id id
       :status "final"
       :category [(category-concept "vital-signs")]
       :code (t/codeable-concept :bp-panel)
       :subject (ref/reference "Patient" patient)
       :effectiveDateTime at
       :component [{:code (t/codeable-concept :systolic)
                    :valueQuantity (units/quantity systolic "mm[Hg]")}
                   {:code (t/codeable-concept :diastolic)
                    :valueQuantity (units/quantity diastolic "mm[Hg]")}]}
      (with-performer m)))

;; ---------------------------------------------------------------------------
;; Reading value[x]

(def ^:private value-kinds
  {:valueQuantity :quantity
   :valueString :string
   :valueBoolean :boolean
   :valueInteger :integer
   :valueCodeableConcept :codeable-concept
   :valueDateTime :date-time})

(defn- value-entry
  "The [key value] entry of the value[x] element of `m` (an Observation or a component)."
  [m]
  (first (filter (fn [[k _]] (re-matches #"value[A-Z].*" (name k))) m)))

(defmulti value-of
  "Canonical form of a value[x] entry: {:kind .. :value .. [:unit ..]}. Dispatches on the
  kind derived from the key (valueQuantity -> :quantity)."
  (fn [[k _]] (get value-kinds k :other)))

(defmethod value-of :quantity [[_ q]]
  {:kind :quantity :value (:value q) :unit (or (:code q) (:unit q))})

(defmethod value-of :codeable-concept [[_ cc]]
  {:kind :codeable-concept :value (coding/display cc)})

(defmethod value-of :default [[k v]]
  {:kind (get value-kinds k :other) :value v})

(defn value
  "The canonical value of an Observation or component, or nil when it has none."
  [m]
  (some-> (value-entry m) value-of))

(defn concept?
  "True when the Observation's code is catalogue concept `k` (for example :heart-rate)."
  [obs k]
  (let [{:keys [system code]} (get t/concepts k)]
    (coding/has-code? (:code obs) system code)))

(defn component-value
  "The canonical value of the component of `obs` whose code is catalogue concept `k`."
  [obs k]
  (let [{:keys [system code]} (get t/concepts k)]
    (some (fn [c] (when (coding/has-code? (:code c) system code) (value c)))
          (:component obs))))

(defn blood-pressure
  "{:systolic n :diastolic n} of a blood pressure panel, or nil when it is not one."
  [obs]
  (let [s (component-value obs :systolic)
        d (component-value obs :diastolic)]
    (when (and s d)
      {:systolic (:value s) :diastolic (:value d)})))

(defn effective-millis
  "Epoch milliseconds of the Observation's effectiveDateTime."
  [obs]
  (time/->millis (:effectiveDateTime obs)))

(defn patient-id
  "The id of the Patient the Observation is about."
  [obs]
  (:id (ref/target (:subject obs))))
