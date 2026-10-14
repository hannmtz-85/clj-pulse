(ns pulse.api.observations
  "The observation endpoints (ADR-0014): a patient's readings of one measurement over a period,
  GET /api/patients/{id}/observations, and recording a new one, POST on the same path.

  Who may do either is decided before this namespace runs (`pulse.api.authz`, ADR-0012). What can
  be recorded, in which units and within which limits is `pulse.fhir.measurement`. This namespace
  is the HTTP side: the shape of the request, the FHIR query, the time a reading can have been
  taken at, the views, the write and its audit."
  (:require [malli.core :as m]
            [pulse.api.audit :as audit]
            [pulse.api.fhir :as fhir]
            [pulse.api.patients :as patients]
            [pulse.api.times :as times]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.ranges :as ranges]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.terminology :as terminology]
            [pulse.platform.http.problem :as problem])
  (:import (java.time Clock Duration Instant)
           (java.time.temporal ChronoUnit)))

;; ---------------------------------------------------------------------------
;; The time of a reading

(def ^:private max-future-minutes
  "How far ahead of the server's clock a reading may claim to have been taken: clocks differ."
  5)

(def ^:private max-age-days
  "How long ago a reading may claim to have been taken: a mistyped year should not rewrite a chart."
  30)

(defn- time-errors
  "`{:at [message]}` when a reading cannot have been taken at `at`, as seen from `now`, else nil."
  [^Instant at ^Instant now]
  (cond
    (.isAfter at (.plus now (Duration/ofMinutes max-future-minutes)))
    {:at ["must not be in the future"]}

    (.isBefore at (.minus now (Duration/ofDays max-age-days)))
    {:at [(str "must not be more than " max-age-days " days ago")]}))

;; ---------------------------------------------------------------------------
;; The reading, as the api shows it

(defn- channel-reading
  "The `{:value :unit}` of one channel of `observation`: of the Observation itself, or of its
  component for a panel such as blood pressure. Nil unless the value is a number: other systems
  write to the same FHIR server, and one reading that is not what it should be must not fail a
  whole series."
  [observation channel]
  (let [reading (if (= :value channel)
                  (obs/value observation)
                  (obs/component-value observation channel))]
    (when (number? (:value reading))
      reading)))

(defn reading-view
  "The reading in `observation`, an Observation of the measurement `kind` (a key of
  `pulse.fhir.measurement/measurements`), as `{:id :at :unit :band}` and its numbers: `:value`,
  or `:systolic` and `:diastolic`. The band is the didactic reference band of
  `pulse.fhir.ranges`, the worst of the two for a blood pressure, and nil when there is none."
  [kind observation]
  (let [{stored-unit :unit :keys [channels]} (get measurement/measurements kind)
        readings (for [[channel {:keys [band]}] channels
                       :let [{:keys [value unit]} (channel-reading observation channel)]]
                   {:channel channel
                    :value value
                    :unit unit
                    :band (when (and band value) (ranges/classify band value unit))})]
    (-> {:id (:id observation) :at (:effectiveDateTime observation)}
        (into (map (juxt :channel :value)) readings)
        (assoc :unit (or (some :unit readings) stored-unit)
               :band (some-> (ranges/worst (keep :band readings)) name)))))

(defn- item-view
  "A reading as the endpoints of this namespace return it: the summary's view, with the name of
  the measurement and who took it (`by`: the patient themselves, or the clinician who entered it)."
  [kind observation]
  (assoc (reading-view kind observation)
         :measurement (name kind)
         :by (get-in observation [:performer 0 :reference])))

(def ^:private nullable-string [:maybe :string])

(def item-schema
  [:map
   [:id :string]
   [:measurement :string]
   [:at nullable-string]
   [:value {:optional true} [:maybe number?]]
   [:systolic {:optional true} [:maybe number?]]
   [:diastolic {:optional true} [:maybe number?]]
   [:unit nullable-string]
   [:band nullable-string]
   [:by nullable-string]])

(def list-schema
  [:map
   [:items [:vector item-schema]]
   [:truncated :boolean]])

;; ---------------------------------------------------------------------------
;; What a request may say

(def ^:private measurement-names
  "The names clients use, one for each measurement that can be recorded."
  (->> (keys measurement/measurements) (map name) sort vec))

(def list-parameters
  ;; The two with a default are not `:optional`: malli's default-value transformer leaves
  ;; optional keys alone, so they would reach the handler as nil.
  [:map
   [:measurement (into [:enum] measurement-names)]
   [:from {:optional true} times/bound]
   [:to {:optional true} times/bound]
   [:sort [:enum {:default "-date"} "-date" "date"]]
   [:limit [:int {:min 1 :max 500 :default 100}]]])

(def ^:private unit-code [:string {:min 1 :max 16}])

(def record-body
  "A reading to record: `measurement`, its number or numbers, and optionally the UCUM `unit` it
  is in and the time `at` it was taken. The shape depends on the measurement, which is why this
  is a `:multi`: a blood pressure is two numbers, everything else is one."
  [:multi {:dispatch :measurement}
   ["blood-pressure"
    [:map
     [:measurement [:= "blood-pressure"]]
     [:systolic number?]
     [:diastolic number?]
     [:unit {:optional true} unit-code]
     [:at {:optional true} times/instant]]]
   [::m/default
    [:map
     [:measurement (into [:enum] (remove #{"blood-pressure"}) measurement-names)]
     [:value number?]
     [:unit {:optional true} unit-code]
     [:at {:optional true} times/instant]]]])

;; ---------------------------------------------------------------------------
;; The series

(defn- no-such-patient []
  (problem/ex 404 "No such patient."))

(defn- bad-parameters
  "The 400 of a request that is wrong in a way the schema cannot say, shaped like the one the
  coercion raises."
  [errors]
  (problem/ex 400 "The request does not match what this endpoint accepts." {:extra {:errors errors}}))

(defn list-handler
  "GET /api/patients/{id}/observations: the readings of one measurement, newest first unless
  `sort=date`, from `from` (inclusive) to `to` (exclusive), at most `limit` of them. One more than
  that is asked for, to know whether the answer was cut (`truncated`); there are no offsets, to go
  further back is to ask again with `to` set to the `at` of the oldest reading received.

  A patient with no readings is an empty series, not a 404; the patient is only looked for when
  there is nothing else to show, so that one that does not exist is a 404 for whoever may look."
  [{fhir-client :fhir}]
  (fn [request]
    (let [{:keys [limit] order :sort :as query} (get-in request [:parameters :query])
          kind (keyword (:measurement query))
          from (some-> (:from query) times/parse-bound)
          to (some-> (:to query) times/parse-bound)
          target (or (patients/target-of request) (throw (no-such-patient)))]
      (when (and from to (not (.isBefore ^Instant from ^Instant to)))
        (throw (bad-parameters {:to ["must be after from"]})))
      (let [params (cond-> {:patient (ref/->str target)
                            :code (terminology/token (get-in measurement/measurements [kind :concept]))
                            :_sort order
                            :_count (min 200 (inc limit))}
                     (or from to) (assoc :date (cond-> [] from (conj (str "ge" from)) to (conj (str "lt" to)))))
            found (fhir/search fhir-client "Observation" params {:limit (inc limit)})]
        (when (and (empty? found)
                   (nil? (fhir/read-resource fhir-client "Patient" (:id target))))
          (throw (no-such-patient)))
        {:status 200
         :body {:items (mapv #(item-view kind %) (take limit found))
                :truncated (> (count found) limit)}}))))

;; ---------------------------------------------------------------------------
;; Recording

(defn- unrecordable
  "The 422 of a reading that has the right shape and cannot be recorded; `errors` is a map from
  the field to what is wrong with it, as in a 400."
  [errors]
  (problem/ex 422 "The reading cannot be recorded as sent." {:extra {:errors errors}}))

(defn record-handler
  "POST /api/patients/{id}/observations: records a reading (ADR-0014). The unit is converted and the
  value checked by `pulse.fhir.measurement`, the time by this namespace. The server decides what
  the client must not: the patient is the one in the path, the performer is the caller's own FHIR
  resource, the status is final and the id is new. The patient must exist. `deps` may hold a `:clock`
  (a `java.time.Clock`, the system's by default)."
  [{fhir-client :fhir clock :clock}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [{caller :identity :as request}]
      (let [body (get-in request [:parameters :body])
            kind (keyword (:measurement body))
            now (Instant/now clock)
            at (or (some-> (:at body) times/parse-instant) (.truncatedTo now ChronoUnit/SECONDS))
            {:keys [ok errors]} (measurement/normalize (assoc body :measurement kind))
            problems (merge-with into (time-errors at now) errors)
            target (or (patients/target-of request) (throw (no-such-patient)))]
        (when (seq problems)
          (throw (unrecordable problems)))
        (when-not (fhir/read-resource fhir-client "Patient" (:id target))
          (throw (no-such-patient)))
        (let [id (str "obs-" (random-uuid))
              observation (measurement/observation ok {:id id
                                                       :patient (:id target)
                                                       :at (str at)
                                                       :performer (some-> (:fhir-user caller) ref/->str)})]
          (fhir/put! fhir-client observation)
          (audit/write! request caller {:action :observation/record
                                        :outcome :created
                                        :patient (ref/->str target)
                                        :resource (str "Observation/" id)})
          {:status 201 :body (item-view kind observation)})))))
