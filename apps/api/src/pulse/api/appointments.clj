(ns pulse.api.appointments
  "The appointment endpoints (ADR-0015): the list, GET /api/appointments; a patient's request
  for one, POST on the same path; and what a clinician does to one, PATCH /api/appointments/{id}.

  Who may do each is decided before this namespace runs (`pulse.api.authz`, ADR-0012). The
  lifecycle, what an appointment can become and within which limits, is `pulse.fhir.appointment`.
  This namespace is the HTTP side: the shape of the requests, the FHIR queries, the views, the
  writes and their audit."
  (:require [malli.core :as m]
            [pulse.api.audit :as audit]
            [pulse.api.fhir :as fhir]
            [pulse.api.times :as times]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.time :as time]
            [pulse.platform.http.problem :as problem])
  (:import (java.time Clock Instant)))

;; ---------------------------------------------------------------------------
;; An appointment, as the api shows it

(defn- participant-ref
  "The reference of the participant of `appointment` whose resource is a `type`, or nil."
  [appointment type]
  (some (fn [{:keys [actor]}]
          (when (= type (:type (ref/target actor)))
            (:reference actor)))
        (:participant appointment)))

(defn view
  "An appointment as the patient's summary shows it: its status, its time or the window that was
  asked for, the practitioner, and why it was cancelled, if it was."
  [appointment]
  {:id (:id appointment)
   :status (:status appointment)
   :description (:description appointment)
   :start (:start appointment)
   :end (:end appointment)
   :requestedStart (get-in appointment [:requestedPeriod 0 :start])
   :requestedEnd (get-in appointment [:requestedPeriod 0 :end])
   :practitioner (participant-ref appointment "Practitioner")
   :reason (get-in appointment [:cancelationReason :text])})

(defn- item-view
  "An appointment as the endpoints of this namespace show it: the summary's view, and whose it is."
  [appointment]
  (assoc (view appointment) :patient (participant-ref appointment "Patient")))

(def ^:private nullable-string [:maybe :string])

(def view-schema
  [:map
   [:id :string]
   [:status nullable-string]
   [:description nullable-string]
   [:start nullable-string]
   [:end nullable-string]
   [:requestedStart nullable-string]
   [:requestedEnd nullable-string]
   [:practitioner nullable-string]
   [:reason nullable-string]])

(def item-schema (conj view-schema [:patient nullable-string]))

(def list-schema
  [:map
   [:total :int]
   [:offset :int]
   [:limit :int]
   [:items [:vector item-schema]]])

;; ---------------------------------------------------------------------------
;; Authorization on one appointment

(defn resolver
  "The `:resource` function of the endpoints on one appointment: reads it and describes it for
  `authorize`. For an appointment the panel is the appointments the practitioner takes part in
  (ADR-0015), so the descriptor is its patient and its practitioners and needs no other read.
  The appointment itself is in it too, as `:appointment`, for the endpoint that changes it. Nil
  when there is no such appointment, and for an id that cannot be a FHIR id."
  [fhir-client]
  (fn [request _scope]
    (when-let [{:keys [type id]} (ref/parse (str "Appointment/" (get-in request [:path-params :id])))]
      (when-let [appointment (fhir/read-resource fhir-client type id)]
        {:patient (participant-ref appointment "Patient")
         :practitioners (into #{}
                              (comp (map :actor)
                                    (filter #(= "Practitioner" (:type (ref/target %))))
                                    (keep :reference))
                              (:participant appointment))
         :appointment appointment}))))

;; ---------------------------------------------------------------------------
;; The list

(defn- when-ms
  "When `appointment` is, or is asked to be, in epoch milliseconds; one with no time at all comes last."
  [appointment]
  (or (some-> (:start appointment) time/->millis)
      (some-> (get-in appointment [:requestedPeriod 0 :start]) time/->millis)
      Long/MAX_VALUE))

(defn- ordered
  "`appointments` by the time they are or are asked to be, `start` or `-start`. Ties are broken by
  id, so that a page of the result is the same every time."
  [order appointments]
  (let [sorted (sort-by (juxt when-ms :id) appointments)]
    (if (= "-start" order) (reverse sorted) sorted)))

(def list-parameters
  ;; The three with a default are not `:optional`: malli's default-value transformer leaves
  ;; optional keys alone, so they would reach the handler as nil.
  [:map
   [:status {:optional true} [:enum "proposed" "booked" "cancelled"]]
   [:sort [:enum {:default "start"} "start" "-start"]]
   [:limit [:int {:min 1 :max 100 :default 50}]]
   [:offset [:int {:min 0 :default 0}]]])

(defn list-handler
  "GET /api/appointments. A patient gets their own, a practitioner those they take part in, the
  administrator all of them: the scope is the query, so nothing outside it is fetched. Sorting and
  paging happen here, on what the scope already limited."
  [{fhir-client :fhir}]
  (fn [{caller :identity scope :scope {{:keys [status limit offset] order :sort} :query} :parameters}]
    (let [me (some-> (:fhir-user caller) ref/->str)
          params (cond-> (case scope
                           :own {:patient me}
                           :panel {:actor me}
                           :all {}
                           (throw (ex-info "Appointments have no meaning for this scope" {:scope scope})))
                   status (assoc :status status))
          appointments (ordered order (fhir/search fhir-client "Appointment" params))]
      {:status 200
       :body {:total (count appointments)
              :offset offset
              :limit limit
              :items (->> appointments (drop offset) (take limit) (mapv item-view))}})))

;; ---------------------------------------------------------------------------
;; A request

(def request-body
  "What a patient asks for: why, and the window in which they would like to be given a time. The
  limits on all three are those of `pulse.fhir.appointment`, which answers with a 422."
  [:map
   [:description [:string {:max 1000}]]
   [:requestedStart times/instant]
   [:requestedEnd times/instant]])

(defn- unprocessable [message errors]
  (problem/ex 422 message {:extra {:errors errors}}))

(defn- general-practitioner
  "The id of the first practitioner among the general practitioners of `patient`, or nil."
  [patient]
  (some (fn [{:keys [reference]}]
          (let [target (ref/parse reference)]
            (when (= "Practitioner" (:type target))
              (:id target))))
        (:generalPractitioner patient)))

(defn request-handler
  "POST /api/appointments: the caller, a patient, asks for an appointment with their general
  practitioner. The server decides who it is for, with whom, its status and its id; the patient
  decides why and when. `deps` may hold a `:clock` (a `java.time.Clock`, the system's by default)."
  [{fhir-client :fhir clock :clock}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [{caller :identity :as request}]
      (let [body (get-in request [:parameters :body])
            asked {:description (:description body)
                   :requestedStart (str (times/parse-instant (:requestedStart body)))
                   :requestedEnd (str (times/parse-instant (:requestedEnd body)))}]
        (when-let [errors (lifecycle/request-errors asked (str (Instant/now clock)))]
          (throw (unprocessable "The appointment cannot be requested as sent." errors)))
        (let [patient-id (:id (:fhir-user caller))
              patient (or (fhir/read-resource fhir-client "Patient" patient-id)
                          (throw (problem/ex 404 "No such patient.")))
              practitioner (or (general-practitioner patient)
                               (throw (problem/ex 409 "You have no general practitioner to ask for an appointment.")))
              id (str "appt-" (random-uuid))
              appointment (lifecycle/proposal (assoc asked :id id :patient patient-id :practitioner practitioner))]
          (fhir/put! fhir-client appointment)
          (audit/write! request caller {:action :appointment/request
                                        :outcome :created
                                        :patient (str "Patient/" patient-id)
                                        :resource (str "Appointment/" id)})
          {:status 201 :body (item-view appointment)})))))

;; ---------------------------------------------------------------------------
;; A change

(def change-body
  "What a clinician does to an appointment: confirm it or move it (`start` and `end`), or cancel
  it, for a `reason` if they like. The shape depends on the action, which is why this is a `:multi`."
  [:multi {:dispatch :action}
   ["confirm" [:map [:action [:= "confirm"]] [:start times/instant] [:end times/instant]]]
   ["reschedule" [:map [:action [:= "reschedule"]] [:start times/instant] [:end times/instant]]]
   ["cancel" [:map [:action [:= "cancel"]] [:reason {:optional true} [:string {:max 1000}]]]]
   [::m/default [:map [:action [:enum "confirm" "reschedule" "cancel"]]]]])

(defn change-handler
  "PATCH /api/appointments/{id}: applies the action to the appointment that authorization read, so
  the decision and the change are about the same version. What the status does not allow is a 409
  and times that cannot be right are a 422, both from `pulse.fhir.appointment/change`. `deps` may
  hold a `:clock`."
  [{fhir-client :fhir clock :clock}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [{caller :identity {:keys [appointment patient]} :descriptor :as request}]
      (let [body (get-in request [:parameters :body])
            action (keyword (:action body))
            command (cond-> {:action action}
                      (:start body) (assoc :start (str (times/parse-instant (:start body))))
                      (:end body) (assoc :end (str (times/parse-instant (:end body))))
                      (some? (:reason body)) (assoc :reason (:reason body)))
            {:keys [ok conflict errors]} (lifecycle/change appointment command (str (Instant/now clock)))]
        (cond
          conflict (throw (problem/ex 409 conflict))
          errors (throw (unprocessable "The appointment cannot be changed as asked." errors))
          :else (do (fhir/put! fhir-client ok)
                    (audit/write! request caller {:action :appointment/manage
                                                  :outcome :updated
                                                  :change action
                                                  :patient patient
                                                  :resource (str "Appointment/" (:id ok))})
                    {:status 200 :body (item-view ok)}))))))
