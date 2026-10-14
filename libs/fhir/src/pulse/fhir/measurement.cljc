(ns pulse.fhir.measurement
  "The measurements Pulse records from patients and clinicians (ADR-0014): what each is called,
  which catalogue concept it is, the one unit it is stored in, how precise a stored value is and
  how far a plausible reading can stray. `normalize` checks a submitted reading and puts it in
  the stored unit; `observation` turns the result into a FHIR Observation.

  The plausible limits are a net for typos and wrong units. They are wide on purpose and are not
  clinical limits: unusual readings are what the app is for, and `pulse.fhir.ranges` and the
  rules deal with those."
  (:require [clojure.string :as str]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.units :as units]))

(def measurements
  "The measurements that can be recorded, by the name a client uses (the `:signal` of the demo
  cohort). Each has:

    `:concept`   the catalogue concept it is (`pulse.fhir.terminology`)
    `:unit`      the UCUM unit it is stored in; a reading in another unit is converted to it
    `:decimals`  how many decimals a stored value keeps
    `:category`  the Observation category, when it is not \"vital-signs\"
    `:channels`  its numbers: `:value` for most, `:systolic` and `:diastolic` for blood pressure,
                 which is a panel. Each has its `:plausible` limits, `[min max]` in the stored
                 unit, and the `:band`, the measurement of `pulse.fhir.ranges` that classifies it."
  {:blood-pressure {:concept :bp-panel :unit "mm[Hg]" :decimals 0
                    :channels {:systolic {:plausible [40 300] :band :systolic}
                               :diastolic {:plausible [20 200] :band :diastolic}}}
   :heart-rate {:concept :heart-rate :unit "/min" :decimals 0
                :channels {:value {:plausible [20 300] :band :heart-rate}}}
   :spo2 {:concept :spo2 :unit "%" :decimals 0
          :channels {:value {:plausible [50 100] :band :spo2}}}
   :glucose {:concept :glucose :unit "mg/dL" :decimals 0 :category "laboratory"
             :channels {:value {:plausible [10 1000] :band :glucose}}}
   :weight {:concept :body-weight :unit "kg" :decimals 1
            :channels {:value {:plausible [1 500]}}}
   :temperature {:concept :body-temperature :unit "Cel" :decimals 1
                 :channels {:value {:plausible [25 45] :band :body-temperature}}}})

;; ---------------------------------------------------------------------------
;; Normalizing a submitted reading

(defn- finite-number? [x]
  (and (number? x)
       #?(:clj (Double/isFinite (double x))
          :cljs (js/isFinite x))))

(defn- round-to
  "`x` rounded to `decimals` decimals, half up; a whole number when there are none."
  [x decimals]
  (let [f (#?(:clj Math/pow :cljs js/Math.pow) 10 decimals)
        rounded (/ (#?(:clj Math/round :cljs js/Math.round) (double (* x f))) f)]
    (if (zero? decimals)
      #?(:clj (long rounded) :cljs rounded)
      rounded)))

(defn- accepted-units
  "The UCUM units a reading stored in `stored` may be sent in, sorted."
  [stored]
  (->> (keys units/definitions)
       (filter #(units/compatible? % stored))
       sort
       vec))

(defn- check-channel
  "The number `x` sent for one channel, converted from `unit` to the unit of `spec` (the
  measurement) and rounded: `[:ok n]`, or `[:error message]` when it is missing, not a number
  or not plausible."
  [x unit {stored :unit :keys [decimals]} {[lower upper] :plausible}]
  (cond
    (nil? x) [:error "is required"]
    (not (finite-number? x)) [:error "must be a number"]
    :else (let [n (round-to (units/convert x unit stored) decimals)]
            (if (<= lower n upper)
              [:ok n]
              [:error (str "must be between " lower " and " upper " " (units/label stored)
                           (when (not= unit stored) " once converted"))]))))

(defn- inconsistent
  "The errors of a reading whose channels cannot go together, or nil."
  [{:keys [systolic diastolic]}]
  (when (and systolic diastolic (<= systolic diastolic))
    {:diastolic ["must be lower than the systolic pressure"]}))

(defn normalize
  "Checks a submitted reading and puts it in the unit it is stored in. `submission` is
  `{:measurement :heart-rate :value 72 :unit \"/min\"}` or, for blood pressure,
  `{:measurement :blood-pressure :systolic 150 :diastolic 95 :unit \"mm[Hg]\"}`; `:unit` is
  optional and defaults to the stored one, and keys it does not use are ignored.

  Returns `{:ok reading}`, the measurement, the stored `:unit` and each channel's number
  converted and rounded to the measurement's precision, or `{:errors {field [message ...]}}`
  with one entry for each field that is wrong, named as in the submission. It does not throw,
  whatever it is given. Pure."
  [{:keys [measurement unit] :as submission}]
  (if-let [{stored :unit :keys [channels] :as spec} (get measurements measurement)]
    (let [unit (or unit stored)]
      (if-not (units/compatible? unit stored)
        {:errors {:unit [(str "must be one of " (str/join ", " (accepted-units stored)))]}}
        (let [checked (for [[channel channel-spec] channels]
                        [channel (check-channel (get submission channel) unit spec channel-spec)])
              errors (into {} (keep (fn [[channel [outcome x]]]
                                      (when (= :error outcome) [channel [x]])))
                           checked)
              reading (into {:measurement measurement :unit stored}
                            (map (fn [[channel [_ n]]] [channel n]))
                            checked)]
          (cond
            (seq errors) {:errors errors}
            (inconsistent reading) {:errors (inconsistent reading)}
            :else {:ok reading}))))
    {:errors {:measurement ["is not a measurement that can be recorded"]}}))

;; ---------------------------------------------------------------------------
;; The Observation

(defn observation
  "The Observation that records `reading`, the `:ok` of `normalize`, for the patient with id
  `:patient`. Options: `:id` of the new resource, `:at` when it was taken (an instant string)
  and `:performer`, who took it: the patient unless given, a reference such as
  \"Practitioner/pr-rivera\", or nil for nobody. Throws on a measurement that is not in the
  catalogue."
  [{:keys [measurement unit] :as reading} {:keys [id patient at] :as options}]
  (let [{:keys [concept category channels]} (or (get measurements measurement)
                                                (throw (ex-info "Unknown measurement" {:measurement measurement})))
        common (cond-> {:id id :patient patient :at at}
                 (contains? options :performer) (assoc :performer (:performer options)))]
    (if (contains? channels :value)
      (obs/quantity-observation (cond-> (assoc common :concept concept :value (:value reading) :unit unit)
                                  category (assoc :category category)))
      (obs/blood-pressure-observation (assoc common
                                             :systolic (:systolic reading)
                                             :diastolic (:diastolic reading))))))
