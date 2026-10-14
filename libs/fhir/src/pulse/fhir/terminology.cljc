(ns pulse.fhir.terminology
  "Code systems and the small catalogue of concepts Pulse uses.

  Terminologies: LOINC (observations), ICD-10-CM (conditions), RxNorm (drugs) and UCUM
  (units). SNOMED CT is deliberately not used (see docs/adr/0002). Every code and display
  below was checked against the NLM Clinical Tables and RxNav services on 2026-10-13.
  LOINC content is copyright Regenstrief Institute: see the NOTICE file.")

(def systems
  "Short names for the code system URIs."
  {:loinc "http://loinc.org"
   :icd-10-cm "http://hl7.org/fhir/sid/icd-10-cm"
   :rxnorm "http://www.nlm.nih.gov/research/umls/rxnorm"
   :ucum "http://unitsofmeasure.org"
   :observation-category "http://terminology.hl7.org/CodeSystem/observation-category"
   :condition-clinical "http://terminology.hl7.org/CodeSystem/condition-clinical"
   :condition-verification "http://terminology.hl7.org/CodeSystem/condition-ver-status"
   :condition-category "http://terminology.hl7.org/CodeSystem/condition-category"
   :allergy-clinical "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical"
   :allergy-verification "http://terminology.hl7.org/CodeSystem/allergyintolerance-verification"
   :flag-category "http://terminology.hl7.org/CodeSystem/flag-category"
   :communication-category "http://terminology.hl7.org/CodeSystem/communication-category"
   :identifier-mrn "https://pulse.example/identifier/mrn"})

(def concepts
  "The catalogue: concept key -> {:system :code :display}."
  {;; LOINC: vital signs and glucose
   :bp-panel {:system :loinc :code "85354-9" :display "Blood pressure panel with all children optional"}
   :systolic {:system :loinc :code "8480-6" :display "Systolic blood pressure"}
   :diastolic {:system :loinc :code "8462-4" :display "Diastolic blood pressure"}
   :heart-rate {:system :loinc :code "8867-4" :display "Heart rate"}
   :spo2 {:system :loinc :code "59408-5" :display "Oxygen saturation in Arterial blood by Pulse oximetry"}
   :spo2-arterial {:system :loinc :code "2708-6" :display "Oxygen saturation in Arterial blood"}
   :glucose {:system :loinc :code "2339-0" :display "Glucose [Mass/volume] in Blood"}
   :body-weight {:system :loinc :code "29463-7" :display "Body weight"}
   :body-temperature {:system :loinc :code "8310-5" :display "Body temperature"}
   ;; ICD-10-CM: conditions
   :hypertension {:system :icd-10-cm :code "I10" :display "Essential (primary) hypertension"}
   :type-2-diabetes {:system :icd-10-cm :code "E11.9" :display "Type 2 diabetes mellitus without complications"}
   :copd {:system :icd-10-cm :code "J44.9" :display "Chronic obstructive pulmonary disease, unspecified"}
   :heart-failure {:system :icd-10-cm :code "I50.9" :display "Heart failure, unspecified"}
   ;; RxNorm: ingredients
   :lisinopril {:system :rxnorm :code "29046" :display "lisinopril"}
   :amlodipine {:system :rxnorm :code "17767" :display "amlodipine"}
   :metformin {:system :rxnorm :code "6809" :display "metformin"}
   :furosemide {:system :rxnorm :code "4603" :display "furosemide"}
   :tiotropium {:system :rxnorm :code "69120" :display "tiotropium"}
   :albuterol {:system :rxnorm :code "435" :display "albuterol"}
   :empagliflozin {:system :rxnorm :code "1545653" :display "empagliflozin"}
   :carvedilol {:system :rxnorm :code "20352" :display "carvedilol"}
   :penicillin-g {:system :rxnorm :code "7980" :display "penicillin G"}})

(defn coding
  "The FHIR Coding map of catalogue concept `k`, e.g. (coding :systolic).
  Throws on an unknown concept so typos fail loudly."
  [k]
  (let [{:keys [system code display]} (get concepts k)]
    (when-not code
      (throw (ex-info "Unknown concept" {:concept k})))
    {:system (get systems system) :code code :display display}))

(defn token
  "The search token of catalogue concept `k`, system and code as a FHIR `token` parameter takes
  them: (token :heart-rate) is \"http://loinc.org|8867-4\". Throws on an unknown concept."
  [k]
  (let [{:keys [system code]} (coding k)]
    (str system "|" code)))

(defn codeable-concept
  "The FHIR CodeableConcept of catalogue concept `k`; `text` defaults to its display."
  ([k] (codeable-concept k nil))
  ([k text]
   {:coding [(coding k)]
    :text (or text (:display (get concepts k)))}))

(defn system-uri
  "The URI of a code system given its short name; a string is returned unchanged."
  [system]
  (if (keyword? system) (get systems system) system))

(defn status-coding
  "A Coding in one of the HL7 status code systems, e.g. (status-coding :condition-clinical \"active\")."
  [system code]
  {:system (system-uri system) :code code})

(defn status-concept
  "A CodeableConcept holding a single `status-coding`."
  [system code]
  {:coding [(status-coding system code)]})
