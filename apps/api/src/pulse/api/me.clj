(ns pulse.api.me
  "GET /api/me: who the caller is, as the api sees it, and the FHIR resource that is them (the
  Patient for a patient, the Practitioner for a clinician). The identity comes from the verified
  token; the resource is read from the FHIR server."
  (:require [pulse.api.fhir :as fhir]
            [pulse.fhir.reference :as ref]))

(def schema
  [:map
   [:subject :string]
   [:username [:maybe :string]]
   [:client [:maybe :string]]
   [:roles [:vector :string]]
   [:fhirUser [:maybe :string]]
   ;; A FHIR resource is open data, so not a `:map`: reitit-malli closes every `:map` schema
   ;; and strips the keys it does not declare, which would reduce the resource to {}.
   [:resource [:maybe [:map-of :keyword :any]]]])

(defn handler [{fhir-client :fhir}]
  (fn [{caller :identity}]
    (let [{:keys [subject username client roles fhir-user]} caller]
      {:status 200
       :body {:subject subject
              :username username
              :client client
              :roles (vec (sort (map name roles)))
              :fhirUser (some-> fhir-user ref/->str)
              :resource (when fhir-user
                          (fhir/read-resource fhir-client (:type fhir-user) (:id fhir-user)))}})))
