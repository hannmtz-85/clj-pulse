(ns pulse.design.data
  "The data of the mock-ups (ADR-0026): the synthetic cohort at a fixed moment, in the shapes the api gives it
  (the reading of `pulse.api.observations/reading-view`, the appointment of `pulse.api.appointments/view`, the
  alert of `pulse.api.alerts`), so that a page made from it is the page that will be made from the api. A test
  in the design suite checks that they are the same.

  The moment is a Tuesday at 14:00 in Madrid, so that the appointments of the cohort fall on working days and
  at hours a clinic keeps."
  (:require [clojure.string :as str]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.ranges :as ranges]
            [pulse.fhir.synthetic :as synthetic])
  (:import (java.time Instant)))

(def now-text "2026-10-13T12:00:00Z")

(def ^Instant now (Instant/parse now-text))

(def ^:private cohort-resources
  (delay (synthetic/generate (synthetic/cohort) {:end-instant now :days 30})))

(defn resources [] @cohort-resources)

(defn- of-type [resource-type]
  (filter #(= resource-type (:resourceType %)) (resources)))

(defn- reference-id [reference]
  (some-> reference (str/split #"/") second))

;; ---------------------------------------------------------------------------
;; People

(defn- patient-view [patient]
  (let [{:keys [family given]} (get-in patient [:name 0])
        gp (reference-id (get-in patient [:generalPractitioner 0 :reference]))]
    {:id (:id patient)
     :name (str/join " " (concat given [family]))
     :family family
     :given (vec given)
     :birthDate (:birthDate patient)
     :gender (:gender patient)
     :identifier (get-in patient [:identifier 0 :value])
     :city (get-in patient [:address 0 :city])
     :email (get-in patient [:telecom 0 :value])
     :practitioner gp}))

(defn patients
  "Every patient, by family name."
  []
  (->> (of-type "Patient") (map patient-view) (sort-by :family) vec))

(defn patient
  "The patient with this id (`pt-ana`)."
  [id]
  (first (filter #(= id (:id %)) (patients))))

(defn practitioners
  "The practitioners by id: `{:id :name :specialty}`."
  []
  (into {}
        (for [p (of-type "Practitioner")
              :let [{:keys [prefix given family]} (get-in p [:name 0])]]
          [(:id p) {:id (:id p)
                    :name (str/join " " (concat prefix given [family]))
                    :family family
                    :specialty (get-in p [:qualification 0 :code :text])}])))

(defn practitioner [id] (get (practitioners) id))

;; ---------------------------------------------------------------------------
;; Readings

(defn- channel-reading [observation channel]
  (let [reading (if (= :value channel)
                  (obs/value observation)
                  (obs/component-value observation channel))]
    (when (number? (:value reading))
      reading)))

(defn reading-view
  "The reading in `observation`, a measurement of `kind`, as the api shows it: `{:id :at :unit :band}` and its
  numbers (`:value`, or `:systolic` and `:diastolic`)."
  [kind observation]
  (let [{stored-unit :unit :keys [channels]} (get measurement/measurements kind)
        readings (for [[channel {:keys [band]}] channels
                       :let [{:keys [value unit]} (channel-reading observation channel)]]
                   {:channel channel
                    :value value
                    :unit unit
                    :band (when (and band value) (ranges/classify band value unit))})]
    (-> {:id (:id observation) :at (:effectiveDateTime observation)}
        (into (map (juxt :channel :value)) readings)
        (assoc :unit (or (some :unit readings) stored-unit)
               :band (some-> (ranges/worst (keep :band readings)) name)))))

(defn readings
  "The readings of `patient-id` of the measurement `kind`, oldest first, in the shape of `reading-view`."
  [patient-id kind]
  (let [concept (get-in measurement/measurements [kind :concept])]
    (->> (of-type "Observation")
         (filter #(and (= patient-id (obs/patient-id %)) (obs/concept? % concept)))
         (sort-by :effectiveDateTime)
         (mapv #(reading-view kind %)))))

(defn latest
  "The newest reading of `kind`, or nil."
  [patient-id kind]
  (peek (readings patient-id kind)))

(def kinds
  "The measurements the interfaces show, in the order they are shown."
  [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature])

(defn vitals
  "The latest reading of each measurement the patient has: `{kind reading}`."
  [patient-id]
  (into {}
        (for [kind kinds
              :let [reading (latest patient-id kind)]
              :when reading]
          [kind reading])))

(defn worst-band
  "The most severe band among the latest readings of a patient, as the name the api uses, or nil."
  [patient-id]
  (some-> (ranges/worst (keep (comp #(some-> % keyword) :band) (vals (vitals patient-id)))) name))

;; ---------------------------------------------------------------------------
;; Clinical record

(defn conditions [patient-id]
  (for [c (of-type "Condition")
        :when (= patient-id (reference-id (get-in c [:subject :reference])))]
    {:id (:id c)
     :display (get-in c [:code :coding 0 :display])
     :code (get-in c [:code :coding 0 :code])
     :status (get-in c [:clinicalStatus :coding 0 :code])
     :onset (:onsetDateTime c)}))

(defn medications [patient-id]
  (for [m (of-type "MedicationRequest")
        :when (= patient-id (reference-id (get-in m [:subject :reference])))]
    {:id (:id m)
     :display (get-in m [:medicationCodeableConcept :coding 0 :display])
     :dosage (get-in m [:dosageInstruction 0 :text])
     :status (:status m)
     :authoredOn (:authoredOn m)}))

(defn allergies [patient-id]
  (for [a (of-type "AllergyIntolerance")
        :when (= patient-id (reference-id (get-in a [:patient :reference])))]
    {:id (:id a)
     :display (get-in a [:code :coding 0 :display])
     :criticality (:criticality a)
     :reaction (get-in a [:reaction 0 :manifestation 0 :text])
     :recordedDate (:recordedDate a)}))

;; ---------------------------------------------------------------------------
;; Appointments

(defn- participant-ref [appointment resource-type]
  (some (fn [p]
          (let [reference (get-in p [:actor :reference])]
            (when (and reference (str/starts-with? reference (str resource-type "/")))
              reference)))
        (:participant appointment)))

(defn appointment-view
  "An appointment as the api shows it, with whose it is."
  [appointment]
  {:id (:id appointment)
   :status (:status appointment)
   :description (:description appointment)
   :start (:start appointment)
   :end (:end appointment)
   :requestedStart (get-in appointment [:requestedPeriod 0 :start])
   :requestedEnd (get-in appointment [:requestedPeriod 0 :end])
   :practitioner (participant-ref appointment "Practitioner")
   :reason (get-in appointment [:cancelationReason :text])
   :patient (participant-ref appointment "Patient")})

(defn appointments
  "Every appointment, soonest first (a request with no time yet by the start of the time it asks for)."
  []
  (->> (of-type "Appointment")
       (map appointment-view)
       (sort-by #(or (:start %) (:requestedStart %)))
       vec))

(defn appointments-of [patient-id]
  (filterv #(= (str "Patient/" patient-id) (:patient %)) (appointments)))

;; ---------------------------------------------------------------------------
;; Alerts

(def alerts
  "What the rules engine has raised for the cohort, as `GET /api/alerts` shows it: one for each rule of Annex C,
  the glucose one acknowledged this morning. Written by hand, because the cohort holds none (the scheduler
  raises them)."
  [{:id "flag-pt-maya-R4" :patient "Patient/pt-maya" :text "Rapid weight gain" :status "active"
    :since "2026-10-13T08:30:00Z" :until nil}
   {:id "flag-pt-ana-R1" :patient "Patient/pt-ana" :text "BP stage 2" :status "active"
    :since "2026-10-12T20:30:00Z" :until nil}
   {:id "flag-pt-sofia-R2" :patient "Patient/pt-sofia" :text "Low SpO₂" :status "active"
    :since "2026-10-12T08:30:00Z" :until nil}
   {:id "flag-pt-liam-R3" :patient "Patient/pt-liam" :text "Glucose out of range" :status "inactive"
    :since "2026-10-11T20:30:00Z" :until "2026-10-13T07:40:00Z"}])
