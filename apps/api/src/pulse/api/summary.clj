(ns pulse.api.summary
  "GET /api/patients/{id}/summary: what a clinician or the patient needs on one screen (ADR-0013).

  About a dozen FHIR queries run at once under one deadline (`pulse.platform.fanout`). The
  patient is required. Every other section may fail or be late; then it is `null` in the answer
  and named in `unavailable`, so that a section that could not be loaded is never mistaken for
  one that is empty (`[]`, or a vital sign that is `null` and not listed).

  Who may read which patient is decided before this namespace runs (`pulse.api.authz`)."
  (:require [pulse.api.appointments :as appointments]
            [pulse.api.fhir :as fhir]
            [pulse.api.observations :as observations]
            [pulse.api.patients :as patients]
            [pulse.fhir.coding :as coding]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.terminology :as terminology]
            [pulse.platform.fanout :as fanout]
            [pulse.platform.http.problem :as problem]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; The views: FHIR resources as small, display-ready maps

(defn- condition-view [condition]
  {:id (:id condition)
   :display (coding/display (:code condition))
   :code (coding/code-in (:code condition) :icd-10-cm)
   :status (get-in condition [:clinicalStatus :coding 0 :code])
   :onset (:onsetDateTime condition)})

(defn- medication-view [request]
  {:id (:id request)
   :display (coding/display (:medicationCodeableConcept request))
   :dosage (get-in request [:dosageInstruction 0 :text])
   :status (:status request)
   :authoredOn (:authoredOn request)})

(defn- allergy-view [allergy]
  {:id (:id allergy)
   :display (coding/display (:code allergy))
   :criticality (:criticality allergy)
   :reaction (get-in allergy [:reaction 0 :manifestation 0 :text])
   :recordedDate (:recordedDate allergy)})

(defn- alert-view [flag]
  {:id (:id flag)
   :text (get-in flag [:code :text])
   :status (:status flag)
   :since (get-in flag [:period :start])})

(def ^:private vital-signs
  "The vital signs shown, by the name of their measurement in `pulse.fhir.measurement`: what to
  search for and how to classify each comes from there."
  [:blood-pressure :heart-rate :glucose :spo2 :weight])

;; ---------------------------------------------------------------------------
;; The queries

(def ^:private alert-limit
  "R4 gives Flag no `status` search parameter, so the newest alerts are fetched and the active
  ones kept afterwards. A patient's active alerts are among their newest."
  50)

(defn tasks
  "The queries of a summary, as a map for `fanout/run`: the patient, five lists, and the
  latest reading of each vital sign (a query of one: only the first page is fetched)."
  [fhir-client patient-id patient-ref]
  (merge
   {:patient #(fhir/read-resource fhir-client "Patient" patient-id)
    :conditions #(fhir/search fhir-client "Condition" {:patient patient-ref :clinical-status "active"})
    :medications #(fhir/search fhir-client "MedicationRequest" {:patient patient-ref :status "active"})
    :allergies #(fhir/search fhir-client "AllergyIntolerance" {:patient patient-ref})
    :appointments #(fhir/search fhir-client "Appointment" {:patient patient-ref})
    :alerts #(fhir/search fhir-client "Flag" {:patient patient-ref :_sort "-date" :_count alert-limit}
                          {:limit alert-limit})}
   (into {}
         (for [vital vital-signs
               :let [code (terminology/token (get-in measurement/measurements [vital :concept]))]]
           [[:vital vital] #(fhir/find-one fhir-client "Observation"
                                           {:patient patient-ref :code code :_sort "-date"})]))))

;; ---------------------------------------------------------------------------
;; Assembling the answer

(defn- label
  "The name of a section in `unavailable`."
  [k]
  (if (vector? k)
    (str "vitals." (name (second k)))
    (name k)))

(defn- failure-reason [outcome]
  (cond (:timeout outcome) :timeout
        (:error outcome) (or (:status (ex-data (:error outcome)))
                             (.getSimpleName (class (:error outcome))))))

(defn- patient-or-throw
  "The patient of the fan-out, or the problem to answer with."
  [outcome]
  (cond
    (:timeout outcome) (throw (problem/ex 504 "The FHIR server did not answer in time."))
    (:error outcome) (throw (:error outcome))
    (nil? (:ok outcome)) (throw (problem/ex 404 "No such patient."))
    :else (:ok outcome)))

(defn assemble
  "The body of the answer from the outcomes of the fan-out (a map from `tasks`' keys to
  `{:ok v}`, `{:error e}` or `{:timeout true}`). Pure, apart from throwing the problem when the
  patient itself is missing or could not be read."
  [outcomes]
  (let [patient (patient-or-throw (:patient outcomes))
        loaded? (fn [k] (contains? (get outcomes k) :ok))
        value (fn [k] (get-in outcomes [k :ok]))
        listing (fn [k view order]
                  (when (loaded? k)
                    (vec (sort-by order (map view (value k))))))
        vitals (into {}
                     (for [vital vital-signs
                           :let [k [:vital vital]]]
                       [vital (when-let [observation (and (loaded? k) (value k))]
                                (observations/reading-view vital observation))]))]
    {:patient (patients/summary patient)
     :conditions (listing :conditions condition-view #(or (:display %) ""))
     :medications (listing :medications medication-view #(or (:display %) ""))
     :allergies (listing :allergies allergy-view #(or (:display %) ""))
     :vitals vitals
     :appointments (listing :appointments appointments/view #(or (:start %) (:requestedStart %) ""))
     :alerts (when (loaded? :alerts)
               (->> (value :alerts)
                    (filter #(= "active" (:status %)))
                    (map alert-view)
                    (sort-by #(or (:since %) ""))
                    reverse
                    vec))
     :unavailable (->> (dissoc outcomes :patient)
                       (remove (fn [[k _]] (loaded? k)))
                       (map (comp label key))
                       sort
                       vec)}))

;; ---------------------------------------------------------------------------
;; The endpoint

(def ^:private nullable-string [:maybe :string])

(def ^:private reading-schema
  [:maybe [:map [:id :string] [:at nullable-string] [:value [:maybe number?]]
           [:unit nullable-string] [:band nullable-string]]])

(def ^:private unloaded
  "What every section that can be missing says about it."
  "Null when this section could not be loaded in time: it is then named in `unavailable`.")

(def schema
  [:map
   [:patient patients/row-schema]
   [:conditions {:description (str "The patient's conditions. " unloaded)}
    [:maybe [:vector [:map [:id :string] [:display nullable-string] [:code nullable-string]
                      [:status nullable-string] [:onset nullable-string]]]]]
   [:medications {:description (str "The patient's medication. " unloaded)}
    [:maybe [:vector [:map [:id :string] [:display nullable-string] [:dosage nullable-string]
                      [:status nullable-string] [:authoredOn nullable-string]]]]]
   [:allergies {:description (str "The patient's allergies and intolerances. " unloaded)}
    [:maybe [:vector [:map [:id :string] [:display nullable-string] [:criticality nullable-string]
                      [:reaction nullable-string] [:recordedDate nullable-string]]]]]
   [:vitals {:description "The latest reading of each vital sign, each null when there is none or it could not be loaded."}
    [:map
     [:blood-pressure [:maybe [:map [:id :string] [:at nullable-string] [:systolic [:maybe number?]]
                               [:diastolic [:maybe number?]] [:unit :string] [:band nullable-string]]]]
     [:heart-rate reading-schema]
     [:glucose reading-schema]
     [:spo2 reading-schema]
     [:weight reading-schema]]]
   [:appointments {:description (str "The patient's appointments, the soonest first. " unloaded)}
    [:maybe [:vector appointments/view-schema]]]
   [:alerts {:description (str "The patient's active alerts, the newest first. " unloaded)}
    [:maybe [:vector [:map [:id :string] [:text nullable-string] [:status nullable-string]
                      [:since nullable-string]]]]]
   [:unavailable {:description "The sections that could not be loaded in time, by name. Empty when the summary is complete."}
    [:vector :string]]])

(def ^:private default-timeout-ms 4000)

(defn handler
  "GET /api/patients/{id}/summary. `deps` is `{:fhir client :summary-timeout-ms n}`."
  [{fhir-client :fhir timeout-ms :summary-timeout-ms}]
  (fn [request]
    (let [target (or (patients/target-of request)
                     (throw (problem/ex 404 "No such patient.")))
          outcomes (fanout/run (tasks fhir-client (:id target) (ref/->str target))
                               {:timeout-ms (or timeout-ms default-timeout-ms)})
          body (assemble outcomes)]
      (when (seq (:unavailable body))
        (t/event! :pulse.api/summary-degraded
                  {:level :warn
                   :data {:unavailable (:unavailable body)
                          :reasons (into {} (map (fn [[k outcome]] [(label k) (failure-reason outcome)]))
                                         (remove (fn [[_ outcome]] (contains? outcome :ok))
                                                 (dissoc outcomes :patient)))}}))
      {:status 200 :body body})))
