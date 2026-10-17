(ns pulse.api.routes
  "The api's routes, as data: path, method, who may call it (`:authz`, see `pulse.api.authz`),
  the shape of what it takes and returns, and the handler. Reitit reads this one structure for
  routing, authorization and request and response checking, and `pulse.api.docs` for the
  document of the api, so they cannot drift apart. Handlers are built from `deps`, the started
  components they need.

  What the document needs and the rest of the data does not say is here too: `:tags` and
  `:description` (the text is CommonMark), a `:description` for each response, and `:problems`,
  the 409 and 422 that an endpoint may answer, with when. The 400, 401, 403, 404 and 5xx follow
  from the rest and are added by `pulse.api.docs`."
  (:require [pulse.api.alerts :as alerts]
            [pulse.api.appointments :as appointments]
            [pulse.api.docs :as docs]
            [pulse.api.health :as health]
            [pulse.api.jobs :as jobs]
            [pulse.api.me :as me]
            [pulse.api.observations :as observations]
            [pulse.api.patients :as patients]
            [pulse.api.summary :as summary]
            [pulse.fhir.appointment :as lifecycle]))

(def ^:private readiness-schema
  [:map
   [:status [:enum "ready" "unready"]]
   [:checks {:description "Each dependency, `up` or `down`."} [:map-of :string [:enum "up" "down"]]]])

(def ^:private patient-path
  [:map [:id [:string {:description "The id of the patient, as in `GET /api/patients`."}]]])

(def ^:private appointment-path
  [:map [:id [:string {:description "The id of the appointment, as in `GET /api/appointments`."}]]])

(def ^:private alert-path
  [:map [:id [:string {:description "The id of the alert, as in `GET /api/alerts`."}]]])

(def ^:private limits lifecycle/limits)

(defn- reason-limit []
  (str "a reason, if given, must be 1 to " (:text-length limits) " characters"))

(defn routes
  "The route table. `deps` is `{:verifier v :fhir c :scheduler s :issuer url :summary-timeout-ms n
  :clock clock}`; the last two are optional. `:scheduler` is the client of the scheduler that
  `GET /api/admin/jobs` asks (see `pulse.api.jobs`)."
  [{:keys [fhir issuer] :as deps}]
  [["/healthz" {:tags ["Health"]
                :get {:authz :public
                      :summary "Liveness: the process is up and serving"
                      :description "Answers while the process serves requests, and looks at nothing else: for a platform that restarts what does not answer."
                      :responses {200 {:description "The process is up."
                                       :body [:map [:status [:= "up"]]]}}
                      :handler (health/liveness)}}]
   ["/readyz" {:tags ["Health"]
               :get {:authz :public
                     :summary "Readiness: the identity provider's keys and the FHIR server answer"
                     :description "For a platform that decides whether to send traffic here: each dependency is asked, all of them under one deadline."
                     :responses {200 {:description "Everything the api needs is up."
                                      :body readiness-schema}
                                 503 {:description "Something is not: `checks` says which."
                                      :body readiness-schema}}
                     :handler (health/readiness deps)}}]
   (docs/openapi-route {:issuer issuer})
   ["/api"
    ["/me" {:tags ["Identity"]
            :get {:authz {:action :identity/read}
                  :summary "The caller: identity, roles, the FHIR resource that is them and, for a patient, their doctor"
                  :description "What the api read from the access token and, for a patient or a practitioner, their own `Patient` or `Practitioner` resource from the FHIR server. For a patient who has a general practitioner, that doctor's name and specialty too, as the clinic writes them."
                  :responses {200 {:description "The caller."
                                   :body me/schema}}
                  :handler (me/handler deps)}}]
    ["/patients"
     {:tags ["Patients"]
      :get {:authz {:action :patient/list}
            :summary "The panel: the patients the caller may see, with filters, sorting and paging"
            :description "A practitioner's panel is the patients whose general practitioner they are; the administrator sees everyone; the rules engine sees every patient's identifier and nothing more. The scope is applied before the query, so nothing outside it is ever fetched."
            :parameters {:query patients/list-parameters}
            :responses {200 {:description "A page of the panel."
                             :body patients/list-schema}}
            :handler (patients/list-handler deps)}}]
    ["/patients/:id"
     {:tags ["Patients"]
      :get {:authz {:action :patient/read :resource (patients/resolver fhir)}
            :summary "One patient's demographics, as a FHIR Patient"
            :parameters {:path patient-path}
            :responses {200 {:description "The `Patient` resource, as the FHIR server has it."
                             :body [:map-of :keyword :any]}}
            :handler (patients/get-handler deps)}}]
    ["/patients/:id/summary"
     {:tags ["Patients"]
      :get {:authz {:action :patient/read :resource (patients/resolver fhir)}
            :summary "One screen about a patient: conditions, medication, allergies, latest vital signs, appointments and alerts"
            :description "About a dozen queries to the FHIR server run at once under one deadline. A section that fails or is late is `null` and named in `unavailable`, never silently empty; the patient itself is required, so without it there is no answer (a 404, or a 504 when the server is too slow)."
            :parameters {:path patient-path}
            :responses {200 {:description "The summary, with a section `null` that could not be loaded."
                             :body summary/schema}}
            :handler (summary/handler deps)}}]
    ["/patients/:id/observations"
     {:tags ["Observations"]
      :get {:authz {:action :observation/read :resource (patients/resolver fhir)}
            :summary "A patient's readings of one measurement over a period, newest first"
            :description "`from` is inclusive and `to` exclusive. At most `limit` readings come back and `truncated` says whether there were more: to go further back, ask again with `to` set to the `at` of the oldest reading received. A patient with no readings in the period has an empty series, not a 404."
            :parameters {:path patient-path
                         :query observations/list-parameters}
            :responses {200 {:description "The readings."
                             :body observations/list-schema}}
            :handler (observations/list-handler deps)}
      :post {:authz {:action :observation/record :resource (patients/resolver fhir)}
             :summary "Record a reading: the unit is converted and the value checked; the patient and who took it are decided by the server"
             :description (str "The number is converted to the unit the measurement is stored in. The server decides what the client must not: the patient is the one in the path, who took the reading is the caller's own FHIR resource, the status is `final` and the id is new. The time may be at most "
                               observations/max-future-minutes " minutes ahead of the server's clock and at most " observations/max-age-days " days back.")
             :parameters {:path patient-path
                          :body observations/record-body}
             :problems {422 "The reading has the right shape and cannot be right: a number outside what a person can have, a unit that does not fit the measurement, a systolic pressure that is not above the diastolic, or a time out of the limits above. `errors` names the fields."}
             :responses {201 {:description "The reading, as recorded."
                              :body observations/item-schema}}
             :handler (observations/record-handler deps)}}]
    ["/appointments"
     {:tags ["Appointments"]
      :get {:authz {:action :appointment/read}
            :summary "The appointments the caller may see: a patient's own, those a practitioner takes part in, or all"
            :parameters {:query appointments/list-parameters}
            :responses {200 {:description "A page of appointments."
                             :body appointments/list-schema}}
            :handler (appointments/list-handler deps)}
      :post {:authz {:action :appointment/request}
             :summary "Ask for an appointment with your doctor: why, and the window in which you would like a time"
             :description "The appointment is `proposed`. The server decides who it is for (the caller), with whom (their general practitioner), its status and its id. A clinician answers it with `PATCH /api/appointments/{id}`."
             :parameters {:body appointments/request-body}
             :problems {409 "The caller has no general practitioner to ask."
                        422 (str "The request has the right shape and cannot be right: the reason must be 1 to " (:text-length limits)
                                 " characters, and the window must end after it starts and in the future, last at most "
                                 (:window-days limits) " days and start within " (:window-ahead-days limits) " days. `errors` names the fields.")}
             :responses {201 {:description "The appointment, `proposed`."
                              :body appointments/item-schema}}
             :handler (appointments/request-handler deps)}}]
    ["/appointments/:id"
     {:tags ["Appointments"]
      :patch {:authz {:action :appointment/manage :resource (appointments/resolver fhir)}
              :summary "Confirm, reschedule or cancel an appointment"
              :description "`confirm` books a `proposed` appointment at the time given, `reschedule` moves a `booked` one, and `cancel` ends a `proposed` or `booked` one for good. For a clinician, an appointment is in their panel when they take part in it. A patient may only `cancel`, and only their own appointment, a request at any time and a booked appointment until it begins; asking for anything else is a 403."
              :parameters {:path appointment-path
                           :body appointments/change-body}
              :problems {409 "The status of the appointment does not allow the action: only a `proposed` appointment can be confirmed, a `booked` one rescheduled, and a `proposed` or `booked` one cancelled. A patient cannot cancel a `booked` appointment that has begun."
                         422 (let [[shortest longest] (:slot-minutes limits)]
                               (str "The command has the right shape and its values cannot be right: a time must be in the future and within "
                                    (:slot-ahead-days limits) " days, and end after it starts, " shortest " to " longest " minutes later; "
                                    (reason-limit) ". `errors` names the fields."))}
              :responses {200 {:description "The appointment, changed."
                               :body appointments/item-schema}}
              :handler (appointments/change-handler deps)}}]
    ["/alerts"
     {:tags ["Alerts"]
      :get {:authz {:action :alert/read}
            :summary "The alerts of the caller's panel (a practitioner's patients, or everyone's for the administrator), active unless asked for those that were acknowledged"
            :parameters {:query alerts/list-parameters}
            :responses {200 {:description "A page of alerts."
                             :body alerts/list-schema}}
            :handler (alerts/list-handler deps)}}]
    ["/alerts/:id/ack"
     {:tags ["Alerts"]
      :post {:authz {:action :alert/ack :resource (alerts/resolver fhir)}
             :summary "Acknowledge an alert: it becomes inactive and its period ends now"
             :parameters {:path alert-path}
             :problems {409 "The alert is not active: it was acknowledged already."}
             :responses {200 {:description "The alert, acknowledged."
                              :body alerts/item-schema}}
             :handler (alerts/ack-handler deps)}}]
    ["/admin/jobs"
     {:tags ["Administration"]
      :get {:authz {:action :jobs/read}
            :summary "The jobs of the scheduler and how they are doing: when each is due, whether it is running, how its last run went"
            :description "The scheduler is a separate service, and the api asks it with the credentials of its own service account, never with the caller's. Starting a job is not possible through the api. A scheduler that cannot be asked, or whose answer is not what it should be, is a `502`; one that is too slow, a `504`."
            :responses {200 {:description "The jobs, in the order the scheduler has them."
                             :body jobs/list-schema}}
            :handler (jobs/handler deps)}}]]])
