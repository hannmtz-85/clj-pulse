(ns pulse.fhir.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.outcome :as outcome]
            [pulse.fhir.resource :as r]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.time :as time]))

(def ^:private samples
  {"Organization" (r/organization {:id "org-1" :name "Pulse Demo Clinic"})
   "Practitioner" (r/practitioner {:id "pr-1" :given "Elena" :family "Rivera" :gender "female"
                                   :email "dr.rivera@pulse.test" :specialty "Internal medicine"})
   "Patient" (r/patient {:id "pt-1" :given "Ana" :family "García" :gender "female"
                         :birth-date "1968-03-14" :email "ana@pulse.test" :mrn "PULSE-1"
                         :gp "pr-1" :organization "org-1" :city "Valencia" :country "ES"})
   "Observation" (obs/blood-pressure-observation {:id "o-1" :patient "pt-1" :systolic 120 :diastolic 80
                                                  :at "2026-10-13T08:00:00Z"})
   "Condition" (r/condition {:id "c-1" :patient "pt-1" :concept :hypertension :onset "2019-05-02"})
   "MedicationRequest" (r/medication-request {:id "m-1" :patient "pt-1" :practitioner "pr-1"
                                              :concept :lisinopril :dosage "10 mg once daily"
                                              :authored "2025-11-04"})
   "AllergyIntolerance" (r/allergy-intolerance {:id "a-1" :patient "pt-1" :concept :penicillin-g
                                                :reaction "Rash" :recorded "2020-01-01"})
   "Appointment" (r/appointment {:id "ap-1" :patient "pt-1" :practitioner "pr-1" :description "Follow-up"
                                 :start "2026-10-14T10:00:00Z" :end "2026-10-14T10:30:00Z" :minutes 30})
   "Flag" (r/flag {:id "f-1" :patient "pt-1" :text "BP stage 2" :start "2026-10-13T08:00:00Z"})
   "Communication" (r/communication {:id "co-1" :patient "pt-1" :appointment "ap-1"
                                     :sent "2026-10-13T09:00:00Z" :text "Appointment tomorrow at 10:00"})})

(deftest every-builder-output-is-valid
  (doseq [[type resource] samples]
    (testing type
      (is (nil? (schema/explain resource)) (pr-str (schema/validate resource))))))

(deftest a-communication-can-carry-the-identifier-a-conditional-create-goes-by
  (let [identifier {:system "https://pulse.example/identifier/reminder" :value "ap-1/2026-10-14T04:00:00Z"}
        reminder (r/communication {:id "co-1" :patient "pt-1" :appointment "ap-1" :sent "2026-10-13T09:00:00Z"
                                   :text "Appointment tomorrow at 10:00" :identifier identifier})]
    (is (= [identifier] (:identifier reminder)))
    (is (schema/valid? reminder))
    (testing "and has none unless it is given one"
      (is (not (contains? (samples "Communication") :identifier))))
    (testing "an identifier has a value, which is text"
      (is (some? (schema/validate (assoc-in reminder [:identifier 0 :value] 42))))
      (is (some? (schema/validate (update-in reminder [:identifier 0] dissoc :value)))))))

(deftest a-proposed-appointment-is-valid-without-start-and-end
  (let [proposed (r/appointment {:id "ap-2" :patient "pt-1" :practitioner "pr-1"
                                 :description "Review" :requested-start "2026-10-20T00:00:00Z"
                                 :requested-end "2026-10-27T00:00:00Z"})]
    (is (= "proposed" (:status proposed)))
    (is (schema/valid? proposed))))

(deftest invalid-resources-are-rejected-with-an-operation-outcome
  (testing "wrong code in an enum"
    (let [outcome (schema/validate (assoc (samples "Patient") :gender "x"))]
      (is (= "OperationOutcome" (:resourceType outcome)))
      (is (= ["Patient.gender"] (-> outcome :issue first :expression)))
      (is (= "value" (-> outcome :issue first :code)))))
  (testing "missing required element"
    (let [outcome (schema/validate (dissoc (samples "Observation") :status))]
      (is (= "required" (-> outcome :issue first :code)))
      (is (= ["Observation.status"] (-> outcome :issue first :expression)))))
  (testing "indexed paths"
    (let [outcome (schema/validate (assoc-in (samples "Patient") [:name 0 :given] "Ana"))]
      (is (= ["Patient.name[0].given"] (-> outcome :issue first :expression)))))
  (testing "a reference must be relative"
    (is (some? (schema/validate (assoc (samples "Flag") :subject {:reference "not a reference"})))))
  (testing "dates"
    (is (some? (schema/validate (assoc (samples "Patient") :birthDate "14/03/1968"))))
    (is (schema/valid? (assoc (samples "Patient") :birthDate "1968"))))
  (testing "an Observation needs a value or components"
    (let [outcome (schema/validate (dissoc (samples "Observation") :component))]
      (is (= "must have a value[x] or components" (-> outcome :issue first :diagnostics)))))
  (testing "unknown resource types"
    (let [outcome (schema/validate {:resourceType "Unicorn"})]
      (is (= "not-supported" (-> outcome :issue first :code))))))

(deftest operation-outcome-helpers
  (let [outcome (schema/validate (assoc (samples "Patient") :gender "x"))]
    (is (outcome/error? outcome))
    (is (= 1 (count (outcome/errors outcome))))
    (is (re-find #"^Patient\.gender: " (outcome/summary outcome))))
  (is (not (outcome/error? (outcome/operation-outcome [(outcome/issue "warning" "informational" "fyi")])))))

(defspec removing-any-required-element-makes-a-resource-invalid 100
  (prop/for-all [[type required] (gen/elements
                                  [["Patient" :name] ["Patient" :gender] ["Patient" :birthDate]
                                   ["Observation" :status] ["Observation" :code] ["Observation" :subject]
                                   ["Observation" :effectiveDateTime]
                                   ["Condition" :clinicalStatus] ["Condition" :code] ["Condition" :subject]
                                   ["MedicationRequest" :status] ["MedicationRequest" :intent]
                                   ["MedicationRequest" :medicationCodeableConcept]
                                   ["AllergyIntolerance" :patient] ["AllergyIntolerance" :code]
                                   ["Appointment" :status] ["Appointment" :participant]
                                   ["Flag" :status] ["Flag" :code] ["Flag" :subject]
                                   ["Communication" :status] ["Communication" :subject]])]
    (and (schema/valid? (samples type))
         (not (schema/valid? (dissoc (samples type) required))))))

(deftest time-parsing
  (is (= 1791878400000 (time/->millis "2026-10-13T08:00:00Z")))
  (is (= 1791878400000 (time/->millis "2026-10-13T10:00:00+02:00")) "offsets are honoured")
  (is (= 1791849600000 (time/->millis "2026-10-13")) "a date alone is midnight UTC"))
