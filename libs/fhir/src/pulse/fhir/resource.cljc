(ns pulse.fhir.resource
  "Constructors for the FHIR R4 resources Pulse writes (other than Observation, which has
  its own namespace). Each takes a flat map of facts and returns the resource as plain data;
  optional facts that are absent leave their elements out."
  (:require [pulse.fhir.reference :as ref]
            [pulse.fhir.terminology :as t]))

(defn organization [{:keys [id name]}]
  {:resourceType "Organization" :id id :active true :name name})

(defn practitioner
  [{:keys [id given family gender email specialty]}]
  (cond-> {:resourceType "Practitioner"
           :id id
           :active true
           :name [{:use "official" :family family :given [given] :prefix ["Dr."]}]}
    gender (assoc :gender gender)
    email (assoc :telecom [{:system "email" :value email :use "work"}])
    specialty (assoc :qualification [{:code {:text specialty}}])))

(defn patient
  "`gp` is the id of the general practitioner, `organization` the id of the clinic."
  [{:keys [id given family gender birth-date email mrn gp organization city country]}]
  (cond-> {:resourceType "Patient"
           :id id
           :active true
           :name [{:use "official" :family family :given [given]}]
           :gender gender
           :birthDate birth-date}
    mrn (assoc :identifier [{:system (:identifier-mrn t/systems) :value mrn}])
    email (assoc :telecom [{:system "email" :value email :use "home"}])
    (or city country) (assoc :address [(cond-> {} city (assoc :city city) country (assoc :country country))])
    gp (assoc :generalPractitioner [(ref/reference "Practitioner" gp)])
    organization (assoc :managingOrganization (ref/reference "Organization" organization))))

(defn condition
  "A confirmed, active problem-list Condition. `concept` is a catalogue key."
  [{:keys [id patient concept onset]}]
  (cond-> {:resourceType "Condition"
           :id id
           :clinicalStatus (t/status-concept :condition-clinical "active")
           :verificationStatus (t/status-concept :condition-verification "confirmed")
           :category [(t/status-concept :condition-category "problem-list-item")]
           :code (t/codeable-concept concept)
           :subject (ref/reference "Patient" patient)}
    onset (assoc :onsetDateTime onset)))

(defn medication-request
  "An active order for catalogue medication `concept`. R4 shape: `medicationCodeableConcept`
  (R5 turns this into a CodeableReference, see docs/03-extras-backlog.md X-18)."
  [{:keys [id patient practitioner concept dosage authored]}]
  (cond-> {:resourceType "MedicationRequest"
           :id id
           :status "active"
           :intent "order"
           :medicationCodeableConcept (t/codeable-concept concept)
           :subject (ref/reference "Patient" patient)}
    practitioner (assoc :requester (ref/reference "Practitioner" practitioner))
    authored (assoc :authoredOn authored)
    dosage (assoc :dosageInstruction [{:text dosage}])))

(defn allergy-intolerance
  [{:keys [id patient concept reaction recorded]}]
  (cond-> {:resourceType "AllergyIntolerance"
           :id id
           :clinicalStatus (t/status-concept :allergy-clinical "active")
           :verificationStatus (t/status-concept :allergy-verification "confirmed")
           :type "allergy"
           :category ["medication"]
           :criticality "low"
           :code (t/codeable-concept concept)
           :patient (ref/reference "Patient" patient)}
    recorded (assoc :recordedDate recorded)
    reaction (assoc :reaction [{:manifestation [{:text reaction}] :severity "mild"}])))

(defn appointment
  "A booked appointment (`start`/`end` instants) or, when `requested-start` and
  `requested-end` are given instead, a proposed one asked for by the patient."
  [{:keys [id patient practitioner description start end minutes requested-start requested-end]}]
  (let [booked? (some? start)]
    (cond-> {:resourceType "Appointment"
             :id id
             :status (if booked? "booked" "proposed")
             :description description
             :participant [{:actor (ref/reference "Patient" patient)
                            :status "accepted"}
                           {:actor (ref/reference "Practitioner" practitioner)
                            :status (if booked? "accepted" "needs-action")}]}
      booked? (assoc :start start :end end)
      (and booked? minutes) (assoc :minutesDuration minutes)
      (not booked?) (assoc :requestedPeriod [{:start requested-start :end requested-end}]))))

(defn flag
  "A clinical alert about a patient, raised by the rules engine. `text` is the alert title."
  [{:keys [id patient text start identifier]}]
  (cond-> {:resourceType "Flag"
           :id id
           :status "active"
           :category [{:coding [(assoc (t/status-coding :flag-category "clinical") :display "Clinical")]}]
           :code {:text text}
           :subject (ref/reference "Patient" patient)
           :period {:start start}}
    identifier (assoc :identifier [identifier])))

(defn communication
  "A completed reminder sent to a patient about an appointment."
  [{:keys [id patient appointment sent text]}]
  {:resourceType "Communication"
   :id id
   :status "completed"
   :category [{:coding [(assoc (t/status-coding :communication-category "reminder") :display "Reminder")]}]
   :subject (ref/reference "Patient" patient)
   :recipient [(ref/reference "Patient" patient)]
   :about [(ref/reference "Appointment" appointment)]
   :sent sent
   :payload [{:contentString text}]})
