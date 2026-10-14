(ns pulse.api.patients
  "The patient endpoints: the panel, GET /api/patients, and one patient's demographics,
  GET /api/patients/{id}. Who may see which patient is decided by `pulse.api.authz`
  (ADR-0012); this namespace says what each scope means for the query, and never fetches more
  than the scope covers."
  (:require [clojure.string :as str]
            [pulse.api.fhir :as fhir]
            [pulse.fhir.reference :as ref]
            [pulse.platform.http.problem :as problem])
  (:import (java.text Collator)
           (java.util Locale)))

;; ---------------------------------------------------------------------------
;; One patient

(defn target-of
  "`{:type \"Patient\" :id id}` for the `{id}` of the request, or nil when it cannot be a FHIR id.
  Such an id cannot name a patient, and it must not reach a URL sent to the FHIR server."
  [request]
  (ref/parse (str "Patient/" (get-in request [:path-params :id]))))

(defn resolver
  "The `:resource` function of the endpoints on a single patient: describes the patient for
  `authorize`. FHIR is read only for the panel scope, which needs the patient's practitioners;
  `:own` and `:all` are decided from the id alone. Returns nil when there is no such patient."
  [fhir-client]
  (fn [request scope]
    (when-let [target (target-of request)]
      (let [patient-ref (ref/->str target)]
        (if (= :panel scope)
          (when-let [patient (fhir/read-resource fhir-client (:type target) (:id target))]
            {:patient patient-ref
             :practitioners (into #{} (keep :reference) (:generalPractitioner patient))})
          {:patient patient-ref})))))

(defn get-handler [{fhir-client :fhir}]
  (fn [request]
    (let [{:keys [type id]} (target-of request)]
      (or (when id
            (when-let [patient (fhir/read-resource fhir-client type id)]
              {:status 200 :body patient}))
          (throw (problem/ex 404 "No such patient."))))))

;; ---------------------------------------------------------------------------
;; The panel

(def ^:private collator (Collator/getInstance Locale/ROOT))

(defn- text-key
  "Names sort the way people expect: accents and case are not what decides the order."
  [s]
  (.getCollationKey ^Collator collator (or s "")))

(defn- family-of [patient] (get-in patient [:name 0 :family]))
(defn- given-of [patient] (get-in patient [:name 0 :given 0]))

(defn- ordered
  "`patients` sorted by `order`, one of the values of the `sort` parameter. Ties are broken by
  name and id so a page of the result is the same every time."
  [order patients]
  (let [by-name (juxt (comp text-key family-of) (comp text-key given-of) :id)
        key-fn (case order
                 ("family" "-family") by-name
                 ("birthDate" "-birthDate") (fn [p] (into [(or (:birthDate p) "")] (by-name p))))
        sorted (sort-by key-fn patients)]
    (if (str/starts-with? order "-") (reverse sorted) sorted)))

(defn- identifier-of [patient]
  (get-in patient [:identifier 0 :value]))

(defn summary
  "What a row of the panel shows, and the summary of one patient starts with."
  [patient]
  (let [{:keys [family given]} (get-in patient [:name 0])]
    {:id (:id patient)
     :name (str/join " " (concat given [family]))
     :family family
     :given (vec given)
     :birthDate (:birthDate patient)
     :gender (:gender patient)
     :identifier (identifier-of patient)}))

(def row-schema
  [:map
   [:id :string]
   [:name :string]
   [:family [:maybe :string]]
   [:given [:vector :string]]
   [:birthDate [:maybe :string]]
   [:gender [:maybe :string]]
   [:identifier [:maybe :string]]])

(defn- identifiers-only [patient]
  {:id (:id patient) :identifier (identifier-of patient)})

(defn- search-params
  "The FHIR query for the caller's scope, plus the filters they asked for. The scope is applied
  by the server: nothing outside it is ever fetched."
  [scope caller family]
  (cond-> (case scope
            :panel {:general-practitioner (ref/->str (:fhir-user caller))}
            (:all :identifiers) {}
            (throw (ex-info "The panel has no meaning for this scope" {:scope scope})))
    family (assoc :family family)))

(def list-parameters
  ;; The three with a default are not `:optional`: malli's default-value transformer leaves
  ;; optional keys alone, so they would reach the handler as nil.
  [:map
   [:family {:optional true} [:string {:min 1 :max 64}]]
   [:sort [:enum {:default "family"} "family" "-family" "birthDate" "-birthDate"]]
   [:limit [:int {:min 1 :max 100 :default 20}]]
   [:offset [:int {:min 0 :default 0}]]])

(def list-schema
  [:map
   [:total :int]
   [:offset :int]
   [:limit :int]
   [:items [:vector [:map-of :keyword :any]]]])

(defn list-handler
  "GET /api/patients. A practitioner gets their panel, an administrator everyone, the rules
  engine everyone's identifiers. Sorting and paging happen here, on what the scope already
  limited: a panel is dozens of patients, not millions."
  [{fhir-client :fhir}]
  (fn [{caller :identity scope :scope {{:keys [family limit offset] order :sort} :query} :parameters}]
    (let [patients (ordered order (fhir/search fhir-client "Patient" (search-params scope caller family)))]
      {:status 200
       :body {:total (count patients)
              :offset offset
              :limit limit
              :items (->> patients
                          (drop offset)
                          (take limit)
                          (mapv (if (= :identifiers scope) identifiers-only summary)))}})))
