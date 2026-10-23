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
            [pulse.fhir.synthetic :as synthetic]
            [pulse.web.views :as views])
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

(defn practitioner-names
  "The names of the practitioners by their FHIR reference, as the pages look a doctor up: a map from `Practitioner/pr-rivera`
  to `Dr. Elena Rivera`."
  []
  (into {} (for [[id {:keys [name]}] (practitioners)] [(str "Practitioner/" id) name])))

(defn panel-of
  "The patients whose general practitioner is `practitioner-id`, by family name."
  [practitioner-id]
  (filterv #(= practitioner-id (:practitioner %)) (patients)))

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
  "The measurements the interfaces show, in the order they are shown (those of `pulse.web.views`)."
  views/kinds)

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

(def other-appointments
  "Two appointments of Ana that the cohort does not hold, as `GET /api/appointments` shows them: a request that the
  clinic has not answered and one that was cancelled, with the reason she gave. They are for the pages that need a
  patient with a history. Written by hand, because the cohort holds only appointments to come, one for each
  patient."
  [{:id "appt-ana-2" :status "proposed" :description "Medication review"
    :start nil :end nil :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"
    :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}
   {:id "appt-ana-0" :status "cancelled" :description "Blood pressure check"
    :start "2026-09-29T07:00:00Z" :end "2026-09-29T07:30:00Z" :requestedStart nil :requestedEnd nil
    :practitioner "Practitioner/pr-rivera" :reason "I had to travel that week." :patient "Patient/pt-ana"}])

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

;; ---------------------------------------------------------------------------
;; The jobs of the scheduler

(def jobs
  "The jobs of the scheduler as `GET /api/admin/jobs` shows them, fifteen seconds after the moment of the other
  mock-ups (the schedule is a grid from the epoch, so a run begins on a multiple of the interval of its job): the
  device simulator has just begun a run, and the last run of the reminders failed because FHIR was slow. Written by
  hand, because the scheduler is another process."
  {:total 3
   :items [{:id "evaluate-rules"
            :description "Evaluates the clinical rules over the latest readings and raises alerts"
            :schedule {:type "interval" :everyMs 30000}
            :state "idle"
            :runningSince nil
            :nextRun "2026-10-13T12:00:30Z"
            :lastRun {:trigger "schedule" :startedAt "2026-10-13T12:00:00Z" :finishedAt "2026-10-13T12:00:02Z"
                      :durationMs 1840 :outcome "ok"
                      :result {:patients 6 :raised 0 :alreadyRaised 4 :stillActive 3 :notApplicable 5 :failed 0}}
            :counts {:ok 214 :failed 0 :interrupted 0 :skipped 0}}
           {:id "simulate-devices"
            :description "Simulates the readings of the devices of the demo cohort and writes them to FHIR"
            :schedule {:type "interval" :everyMs 15000}
            :state "running"
            :runningSince "2026-10-13T12:00:15Z"
            :nextRun "2026-10-13T12:00:30Z"
            :lastRun {:trigger "schedule" :startedAt "2026-10-13T12:00:00Z" :finishedAt "2026-10-13T12:00:01Z"
                      :durationMs 620 :outcome "ok" :result {:readings 7 :written 7 :failed 0}}
            :counts {:ok 428 :failed 1 :interrupted 0 :skipped 2}}
           {:id "send-reminders"
            :description "Sends a reminder to the patients whose confirmed appointment is about to begin"
            :schedule {:type "interval" :everyMs 60000}
            :state "idle"
            :runningSince nil
            :nextRun "2026-10-13T12:01:00Z"
            :lastRun {:trigger "schedule" :startedAt "2026-10-13T12:00:00Z" :finishedAt "2026-10-13T12:00:10Z"
                      :durationMs 10012 :outcome "failed" :error "HttpTimeoutException"}
            :counts {:ok 105 :failed 1 :interrupted 0 :skipped 0}}]})

;; ---------------------------------------------------------------------------
;; What the api answers a patient

(defn me
  "What `GET /api/me` answers the patient `patient-id`: who they are as the api sees it, their Patient resource and their
  general practitioner, named. The user is the one of the realm, whose name is the email of the patient."
  [patient-id]
  (let [resource (first (filter #(= patient-id (:id %)) (of-type "Patient")))
        view (patient patient-id)
        gp-reference (get-in resource [:generalPractitioner 0 :reference])
        gp (some-> gp-reference reference-id practitioner)]
    {:subject (str "user-" patient-id)
     :username (:email view)
     :client "pulse-web"
     :roles ["patient"]
     :fhirUser (str "Patient/" patient-id)
     :resource resource
     :generalPractitioner (when gp {:reference gp-reference :name (:name gp) :specialty (:specialty gp)})}))

(def ^:private summary-vitals
  "The vital signs of the summary of the api, which has no temperature."
  [:blood-pressure :heart-rate :glucose :spo2 :weight])

(defn- appointments-in-summary [patient-id]
  (->> (concat (appointments-of patient-id)
               (filter #(= (str "Patient/" patient-id) (:patient %)) other-appointments))
       (map #(dissoc % :patient))
       (sort-by #(or (:start %) (:requestedStart %) ""))
       vec))

(defn summary
  "What `GET /api/patients/{id}/summary` answers for `patient-id`, with nothing missing: the patient, the record, the latest
  reading of each vital sign (nil for one the patient has none of), the appointments, the active alerts and no section that
  could not be loaded."
  [patient-id]
  (let [by-display (fn [items] (vec (sort-by #(or (:display %) "") items)))]
    {:patient (select-keys (patient patient-id) [:id :name :family :given :birthDate :gender :identifier])
     :conditions (by-display (conditions patient-id))
     :medications (by-display (medications patient-id))
     :allergies (by-display (allergies patient-id))
     :vitals (into {} (for [kind summary-vitals] [kind (latest patient-id kind)]))
     :appointments (appointments-in-summary patient-id)
     :alerts (->> alerts
                  (filter #(and (= (str "Patient/" patient-id) (:patient %)) (= "active" (:status %))))
                  (map #(select-keys % [:id :text :status :since]))
                  (sort-by #(or (:since %) ""))
                  reverse
                  vec)
     :unavailable []}))

(defn series
  "What `GET /api/patients/{id}/observations` answers for the measurement `kind` of `patient-id`: the newest `limit` readings
  first, each with the name of the measurement and who took it, and whether there were more."
  [patient-id kind limit]
  (let [concept (get-in measurement/measurements [kind :concept])
        found (->> (of-type "Observation")
                   (filter #(and (= patient-id (obs/patient-id %)) (obs/concept? % concept)))
                   (sort-by :effectiveDateTime)
                   reverse
                   (take (inc limit)))]
    {:items (vec (for [observation (take limit found)]
                   (assoc (reading-view kind observation)
                          :measurement (name kind)
                          :by (get-in observation [:performer 0 :reference]))))
     :truncated (> (count found) limit)}))
