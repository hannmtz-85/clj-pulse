(ns pulse.api.alerts
  "The alert endpoints (ADR-0016): the inbox, GET /api/alerts, and acknowledging an alert,
  POST /api/alerts/{id}/ack. An alert is a FHIR Flag that the rules engine raises by writing to
  FHIR itself; this namespace only lists and acknowledges them.

  Who may do either is decided before this namespace runs (`pulse.api.authz`, ADR-0012). That an
  alert goes from active to inactive when it is acknowledged is `pulse.fhir.alert`. This namespace
  is the HTTP side: the FHIR queries that stay within the scope, the view, the write and its audit."
  (:require [clojure.string :as str]
            [pulse.api.audit :as audit]
            [pulse.api.fhir :as fhir]
            [pulse.api.patients :as patients]
            [pulse.fhir.alert :as lifecycle]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.time :as time]
            [pulse.platform.http.problem :as problem])
  (:import (java.time Clock Instant)))

;; ---------------------------------------------------------------------------
;; An alert, as the api shows it

(defn- patient-ref
  "The reference to the patient `flag` is about, or nil when it is about something else."
  [flag]
  (when (= "Patient" (:type (ref/target (:subject flag))))
    (:reference (:subject flag))))

(defn- item-view
  "An alert as the summary shows it, with whose it is and, once it is acknowledged, when."
  [flag]
  {:id (:id flag)
   :patient (patient-ref flag)
   :text (get-in flag [:code :text])
   :status (:status flag)
   :since (get-in flag [:period :start])
   :until (get-in flag [:period :end])})

(def ^:private nullable-string [:maybe :string])

(def item-schema
  [:map
   [:id :string]
   [:patient nullable-string]
   [:text nullable-string]
   [:status nullable-string]
   [:since nullable-string]
   [:until nullable-string]])

(def list-schema
  [:map
   [:total :int]
   [:offset :int]
   [:limit :int]
   [:items [:vector item-schema]]])

;; ---------------------------------------------------------------------------
;; Authorization on one alert

(defn resolver
  "The `:resource` function of the endpoints on one alert: reads the Flag and describes it for
  `authorize`. An alert belongs to the panel of its patient, so a practitioner's request needs the
  patient as well, to know their general practitioners; the administrator's does not. The Flag
  itself is in the descriptor, as `:alert`, for the endpoint that changes it. Nil when there is no
  such Flag, when it is about something that is not a patient, and for an id that cannot be a FHIR
  id."
  [fhir-client]
  (fn [request scope]
    (when-let [{:keys [type id]} (ref/parse (str "Flag/" (get-in request [:path-params :id])))]
      (when-let [flag (fhir/read-resource fhir-client type id)]
        (when-let [patient (patient-ref flag)]
          (if (= :panel scope)
            (when-let [described (fhir/read-resource fhir-client "Patient" (:id (ref/parse patient)))]
              (assoc (patients/describe described) :alert flag))
            {:patient patient :alert flag}))))))

;; ---------------------------------------------------------------------------
;; The inbox

(def ^:private group-size
  "How many patients are named in one query: more would make a long URL."
  50)

(defn- flags-of
  "The flags the scope covers, newest first within each query. A practitioner's are those of the
  patients whose general practitioner they are: the panel is found first, and then its flags are
  asked for with an OR list of references, a group of patients at a time. An empty panel asks for
  nothing, since an empty filter would mean everyone. The administrator's are all of them."
  [fhir-client scope caller]
  (let [flags (fn [params] (fhir/search fhir-client "Flag" (assoc params :_sort "-date" :_count 200)))]
    (case scope
      :all (flags {})
      :panel (let [panel (fhir/search fhir-client "Patient" {:general-practitioner (ref/->str (:fhir-user caller))})]
               (->> panel
                    (map #(str "Patient/" (:id %)))
                    (partition-all group-size)
                    (mapcat #(flags {:patient (str/join "," %)}))
                    vec))
      (throw (ex-info "Alerts have no meaning for this scope" {:scope scope})))))

(defn- since-ms
  "When the alert was raised, in epoch milliseconds; one that does not say counts as the oldest."
  [flag]
  (or (some-> (get-in flag [:period :start]) time/->millis) Long/MIN_VALUE))

(defn- ordered
  "`flags` by when they were raised, `-since` (the newest first) or `since`. Ties are broken by id,
  so that a page of the result is the same every time."
  [order flags]
  (let [sorted (sort-by (juxt since-ms :id) flags)]
    (if (= "-since" order) (reverse sorted) sorted)))

(def list-parameters
  ;; The four with a default are not `:optional`: malli's default-value transformer leaves
  ;; optional keys alone, so they would reach the handler as nil.
  [:map
   [:status [:enum {:default "active"} "active" "inactive"]]
   [:sort [:enum {:default "-since"} "-since" "since"]]
   [:limit [:int {:min 1 :max 100 :default 50}]]
   [:offset [:int {:min 0 :default 0}]]])

(defn list-handler
  "GET /api/alerts: the alerts of the caller's scope that are `active` (or `inactive`: those that
  were acknowledged). R4 gives Flag no `status` search parameter, so the flags of the scope are
  fetched and the status is applied here: a filter on what the scope already limited, not a way
  around it. Sorting and paging happen here too."
  [{fhir-client :fhir}]
  (fn [{caller :identity scope :scope {{:keys [status limit offset] order :sort} :query} :parameters}]
    (let [alerts (->> (flags-of fhir-client scope caller)
                      (filter #(= status (:status %)))
                      (ordered order))]
      {:status 200
       :body {:total (count alerts)
              :offset offset
              :limit limit
              :items (->> alerts (drop offset) (take limit) (mapv item-view))}})))

;; ---------------------------------------------------------------------------
;; Acknowledging

(defn ack-handler
  "POST /api/alerts/{id}/ack: acknowledges the alert that authorization read, so that the decision
  and the change are about the same version. An alert that is not active is a 409 from
  `pulse.fhir.alert/acknowledge`. `deps` may hold a `:clock` (a `java.time.Clock`, the system's by
  default)."
  [{fhir-client :fhir clock :clock}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [{caller :identity {:keys [alert patient]} :descriptor :as request}]
      (let [{:keys [ok conflict]} (lifecycle/acknowledge alert (str (Instant/now clock)))]
        (when conflict
          (throw (problem/ex 409 conflict)))
        (fhir/put! fhir-client ok)
        (audit/write! request caller {:action :alert/ack
                                      :outcome :updated
                                      :change :acknowledge
                                      :patient patient
                                      :resource (str "Flag/" (:id ok))})
        {:status 200 :body (item-view ok)}))))
