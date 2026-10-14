(ns pulse.api.routes
  "The api's routes, as data: path, method, who may call it (`:authz`, see `pulse.api.authz`),
  the shape of what it takes and returns, and the handler. Reitit reads this one structure for
  routing, authorization and request and response checking, so they cannot drift apart. Handlers
  are built from `deps`, the started components they need."
  (:require [pulse.api.alerts :as alerts]
            [pulse.api.appointments :as appointments]
            [pulse.api.health :as health]
            [pulse.api.me :as me]
            [pulse.api.observations :as observations]
            [pulse.api.patients :as patients]
            [pulse.api.summary :as summary]))

(def ^:private readiness-schema
  [:map
   [:status [:enum "ready" "unready"]]
   [:checks [:map-of :string [:enum "up" "down"]]]])

(defn routes
  "The route table. `deps` is `{:verifier v :fhir c :summary-timeout-ms n :clock clock}`; the
  last two are optional."
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
            :handler (summary/handler deps)}}]
    ["/patients/:id/observations"
     {:get {:authz {:action :observation/read :resource (patients/resolver fhir)}
            :summary "A patient's readings of one measurement over a period, newest first"
            :parameters {:path [:map [:id :string]]
                         :query observations/list-parameters}
            :responses {200 {:body observations/list-schema}}
            :handler (observations/list-handler deps)}
      :post {:authz {:action :observation/record :resource (patients/resolver fhir)}
             :summary "Record a reading: the unit is converted and the value checked; the patient and who took it are decided by the server"
             :parameters {:path [:map [:id :string]]
                          :body observations/record-body}
             :responses {201 {:body observations/item-schema}}
             :handler (observations/record-handler deps)}}]
    ["/appointments"
     {:get {:authz {:action :appointment/read}
            :summary "The appointments the caller may see: a patient's own, those a practitioner takes part in, or all"
            :parameters {:query appointments/list-parameters}
            :responses {200 {:body appointments/list-schema}}
            :handler (appointments/list-handler deps)}
      :post {:authz {:action :appointment/request}
             :summary "Ask for an appointment with your doctor: why, and the window in which you would like a time"
             :parameters {:body appointments/request-body}
             :responses {201 {:body appointments/item-schema}}
             :handler (appointments/request-handler deps)}}]
    ["/appointments/:id"
     {:patch {:authz {:action :appointment/manage :resource (appointments/resolver fhir)}
              :summary "Confirm, reschedule or cancel an appointment"
              :parameters {:path [:map [:id :string]]
                           :body appointments/change-body}
              :responses {200 {:body appointments/item-schema}}
              :handler (appointments/change-handler deps)}}]
    ["/alerts"
     {:get {:authz {:action :alert/read}
            :summary "The alerts of the caller's panel (a practitioner's patients, or everyone's for the administrator), active unless asked for those that were acknowledged"
            :parameters {:query alerts/list-parameters}
            :responses {200 {:body alerts/list-schema}}
            :handler (alerts/list-handler deps)}}]
    ["/alerts/:id/ack"
     {:post {:authz {:action :alert/ack :resource (alerts/resolver fhir)}
             :summary "Acknowledge an alert: it becomes inactive and its period ends now"
             :parameters {:path [:map [:id :string]]}
             :responses {200 {:body alerts/item-schema}}
             :handler (alerts/ack-handler deps)}}]]])
