(ns pulse.api.appointments
  "The appointment endpoints (ADR-0015, ADR-0027): the list, GET /api/appointments; a patient's
  request for one, POST on the same path; and what is done to one, PATCH /api/appointments/{id}:
  a clinician confirms, moves or cancels it, and a patient may cancel their own.

  Whose appointments a caller may act on is decided before this namespace runs
  (`pulse.api.authz`, ADR-0012). What each kind of person may do to them, what an appointment can
  become and within which limits, is the lifecycle, `pulse.fhir.appointment`. This namespace is
  the HTTP side: the shape of the requests, the FHIR queries, the views, the writes and their
  audit."
  (:require [pulse.api.audit :as audit]
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
   [:status {:description "`proposed` (asked for, no time yet), `booked` or `cancelled`."} nullable-string]
   [:description {:description "Why the patient asked for it."} nullable-string]
   [:start {:description "The time it is booked at; null until a clinician confirms it."} nullable-string]
   [:end nullable-string]
   [:requestedStart {:description "The window in which the patient asked to be given a time."} nullable-string]
   [:requestedEnd nullable-string]
   [:practitioner {:description "The practitioner, as a FHIR reference."} nullable-string]
   [:reason {:description "Why it was cancelled, if it was and a reason was given."} nullable-string]])

(def item-schema (conj view-schema [:patient {:description "The patient, as a FHIR reference."} nullable-string]))

(def list-schema
  [:map
   [:total {:description "How many appointments the caller may see that match, whatever the page."} :int]
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
   [:status {:optional true :description "Only the appointments in this status."} [:enum "proposed" "booked" "cancelled"]]
   [:sort {:description "By the time they are, or are asked to be: `start` (the soonest first) or `-start`. Ties are broken by id."} [:enum {:default "start"} "start" "-start"]]
   [:limit {:description "How many appointments to return, at most."} [:int {:min 1 :max 100 :default 50}]]
   [:offset {:description "How many appointments to skip: the start of the page."} [:int {:min 0 :default 0}]]])

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
   [:description {:description (str "Why the patient asks for an appointment: 1 to " (:text-length lifecycle/limits) " characters.")}
    [:string {:max 1000 :json-schema/maxLength (:text-length lifecycle/limits)}]]
   [:requestedStart {:description "When the window begins: a date and time with its offset."} times/instant]
   [:requestedEnd {:description "When the window ends. It must be in the future."} times/instant]])

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
  "What is done to an appointment: confirm it or move it (`start` and `end`), which only a clinician
  may, or cancel it, for a `reason` if they like, which a patient may too (ADR-0027). The shape
  depends on the action, which is why this is a `:multi`.
  A body that names no action is an error of the `action` field, worded as for an enum of the
  three: the message is the `:multi`'s own, so that no branch of the schema exists only for it
  (it would overlap the others in the description of the api)."
  [:multi {:dispatch :action
           :error/message "should be either \"confirm\", \"reschedule\" or \"cancel\""}
   ["confirm" [:map
               [:action [:= "confirm"]]
               [:start {:description "When the appointment begins."} times/instant]
               [:end {:description "When it ends."} times/instant]]]
   ["reschedule" [:map
                  [:action [:= "reschedule"]]
                  [:start {:description "When the appointment now begins."} times/instant]
                  [:end {:description "When it now ends."} times/instant]]]
   ["cancel" [:map
              [:action [:= "cancel"]]
              [:reason {:optional true :description (str "Why it is cancelled: 1 to " (:text-length lifecycle/limits) " characters.")}
               [:string {:max 1000 :json-schema/maxLength (:text-length lifecycle/limits)}]]]]])

(defn actor-kind
  "The kind of person `caller` is to the lifecycle (ADR-0027): a `:clinician` if they are a
  practitioner or the administrator, and otherwise the `:patient` that the route let in."
  [caller]
  (if (some (:roles caller) [:practitioner :clinic-admin]) :clinician :patient))

(defn change-handler
  "PATCH /api/appointments/{id}: applies the action to the appointment that authorization read, so
  the decision and the change are about the same version. What the kind of person who asks may do
  is the lifecycle's to say, and an action they may not take is a 403 (audited as a denial, with
  the command named; the reason is for the log and not for the client). What the status, or the
  time, does not allow is a 409 and times that cannot be right are a 422. All four come from
  `pulse.fhir.appointment/change`. `deps` may hold a `:clock`."
  [{fhir-client :fhir clock :clock}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [{caller :identity {:keys [appointment patient] :as descriptor} :descriptor :as request}]
      (let [body (get-in request [:parameters :body])
            action (keyword (:action body))
            command (cond-> {:action action}
                      (:start body) (assoc :start (str (times/parse-instant (:start body))))
                      (:end body) (assoc :end (str (times/parse-instant (:end body))))
                      (some? (:reason body)) (assoc :reason (:reason body)))
            {:keys [ok forbidden conflict errors]}
            (lifecycle/change appointment command (str (Instant/now clock)) (actor-kind caller))]
        (cond
          forbidden (do (audit/access! request caller :appointment/manage :denied
                                       {:scope (:scope request) :reason :action-not-allowed-for-actor :change action}
                                       descriptor)
                        (throw (problem/ex 403 "You are not allowed to do this.")))
          conflict (throw (problem/ex 409 conflict))
          errors (throw (unprocessable "The appointment cannot be changed as asked." errors))
          :else (do (fhir/put! fhir-client ok)
                    (audit/write! request caller {:action :appointment/manage
                                                  :outcome :updated
                                                  :change action
                                                  :patient patient
                                                  :resource (str "Appointment/" (:id ok))})
                    {:status 200 :body (item-view ok)}))))))
