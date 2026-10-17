(ns pulse.api.me
  "GET /api/me: who the caller is, as the api sees it, and the FHIR resource that is them (the
  Patient for a patient, the Practitioner for a clinician). The identity comes from the verified
  token; the resource is read from the FHIR server. For a patient it also says who their doctor
  is (ADR-0027), because the portal names the doctor beside every appointment and the api gives
  only a reference."
  (:require [clojure.string :as str]
            [pulse.api.fhir :as fhir]
            [pulse.fhir.reference :as ref]))

(def ^:private practitioner-schema
  [:map
   [:reference {:description "The practitioner, as a FHIR reference, such as `Practitioner/pr-rivera`."} :string]
   [:name {:description "As the clinic writes it, such as `Dr. Elena Rivera`."} [:maybe :string]]
   [:specialty {:description "What the practitioner is qualified in, such as `General practice`."} [:maybe :string]]])

(def schema
  [:map
   [:subject {:description "The `sub` claim of the token: the id of the user in the identity provider."} :string]
   [:username [:maybe :string]]
   [:client {:description "The client the token was issued to (`azp`)."} [:maybe :string]]
   [:roles {:description "The application roles of the caller, which decide what they may do."} [:vector :string]]
   [:fhirUser {:description "The FHIR resource that is the caller, such as `Patient/pt-ana`; null for someone who is not in FHIR, like the administrator."} [:maybe :string]]
   ;; A FHIR resource is open data, so not a `:map`: reitit-malli closes every `:map` schema
   ;; and strips the keys it does not declare, which would reduce the resource to {}.
   [:resource {:description "That resource, as the FHIR server has it."} [:maybe [:map-of :keyword :any]]]
   [:generalPractitioner {:description "For a patient, their general practitioner, who every appointment they ask for is with; null for anyone else, and for a patient who has none."}
    [:maybe practitioner-schema]]])

(defn- general-practitioner
  "The doctor of the patient `resource`, as `{:reference :name :specialty}`, or nil: the patient has
  none, the reference is not to a Practitioner, or there is no such Practitioner."
  [fhir-client resource]
  (when-let [reference (get-in resource [:generalPractitioner 0 :reference])]
    (let [{:keys [type id]} (ref/parse reference)]
      (when (= "Practitioner" type)
        (when-let [practitioner (fhir/read-resource fhir-client type id)]
          (let [{:keys [prefix given family]} (get-in practitioner [:name 0])]
            {:reference reference
             :name (not-empty (str/join " " (remove str/blank? (concat prefix given [family]))))
             :specialty (get-in practitioner [:qualification 0 :code :text])}))))))

(defn handler [{fhir-client :fhir}]
  (fn [{caller :identity}]
    (let [{:keys [subject username client roles fhir-user]} caller
          resource (when fhir-user
                     (fhir/read-resource fhir-client (:type fhir-user) (:id fhir-user)))]
      {:status 200
       :body {:subject subject
              :username username
              :client client
              :roles (vec (sort (map name roles)))
              :fhirUser (some-> fhir-user ref/->str)
              :resource resource
              :generalPractitioner (when (= "Patient" (:type fhir-user))
                                     (general-practitioner fhir-client resource))}})))
