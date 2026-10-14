(ns pulse.api.routes
  "The api's routes, as data: path, method, who may call it (`:authz`, see `pulse.api.authz`),
  the shape of what it takes and returns, and the handler. Reitit reads this one structure for
  routing, authorization and request and response checking, so they cannot drift apart. Handlers
  are built from `deps`, the started components they need."
  (:require [pulse.api.health :as health]
            [pulse.api.me :as me]
            [pulse.api.patients :as patients]
            [pulse.api.summary :as summary]))

(def ^:private readiness-schema
  [:map
   [:status [:enum "ready" "unready"]]
   [:checks [:map-of :string [:enum "up" "down"]]]])

(defn routes
  "The route table. `deps` is `{:verifier v :fhir c :summary-timeout-ms n}`."
  [{:keys [fhir] :as deps}]
  [["/healthz" {:get {:authz :public
                      :summary "Liveness: the process is up and serving"
                      :responses {200 {:body [:map [:status [:= "up"]]]}}
                      :handler (health/liveness)}}]
   ["/readyz" {:get {:authz :public
                     :summary "Readiness: the identity provider's keys and the FHIR server answer"
                     :responses {200 {:body readiness-schema}
                                 503 {:body readiness-schema}}
                     :handler (health/readiness deps)}}]
   ["/api"
    ["/me" {:get {:authz {:action :identity/read}
                  :summary "The caller: identity, roles and the FHIR resource that is them"
                  :responses {200 {:body me/schema}}
                  :handler (me/handler deps)}}]
    ["/patients"
     {:get {:authz {:action :patient/list}
            :summary "The panel: the patients the caller may see, with filters, sorting and paging"
            :parameters {:query patients/list-parameters}
            :responses {200 {:body patients/list-schema}}
            :handler (patients/list-handler deps)}}]
    ["/patients/:id"
     {:get {:authz {:action :patient/read :resource (patients/resolver fhir)}
            :summary "One patient's demographics, as a FHIR Patient"
            :parameters {:path [:map [:id :string]]}
            :responses {200 {:body [:map-of :keyword :any]}}
            :handler (patients/get-handler deps)}}]
    ["/patients/:id/summary"
     {:get {:authz {:action :patient/read :resource (patients/resolver fhir)}
            :summary "One screen about a patient: conditions, medication, allergies, latest vital signs, appointments and alerts"
            :parameters {:path [:map [:id :string]]}
            :responses {200 {:body summary/schema}}
            :handler (summary/handler deps)}}]]])
