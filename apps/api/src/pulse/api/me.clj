(ns pulse.api.me
  "GET /api/me: who the caller is, as the api sees it, and the FHIR resource that is them (the
  Patient for a patient, the Practitioner for a clinician). The identity comes from the verified
  token; the resource is read from the FHIR server."
  (:require [pulse.fhir.client :as fc]
            [pulse.fhir.reference :as ref]
            [pulse.platform.http.problem :as problem]))

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

(defn- linked-resource
  "The FHIR resource behind a `fhirUser` reference, or nil when the server has none. A server
  that cannot answer is a 502: the caller's request was fine, what it depends on was not."
  [fhir {:keys [type id]}]
  (try
    (fc/read-resource fhir type id)
    (catch Exception e
      (throw (problem/ex 502 "The FHIR server could not be reached." {:cause e})))))

(defn handler [{:keys [fhir]}]
  (fn [{caller :identity}]
    (let [{:keys [subject username client roles fhir-user]} caller]
      {:status 200
       :body {:subject subject
              :username username
              :client client
              :roles (vec (sort (map name roles)))
              :fhirUser (some-> fhir-user ref/->str)
              :resource (some->> fhir-user (linked-resource fhir))}})))
