(ns pulse.scheduler.source
  "Where the rules get their data (ADR-0022): the patients, their readings and their conditions.

  The readings come from the api, with the scheduler's own token, so they are the api's: the units
  of the measurement, bounded series, and what the role `rules-engine` is allowed (Annex A). The
  conditions are read from FHIR, since the api gives that role no way to see them."
  (:require [pulse.fhir.client :as c]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.terminology :as terminology]
            [pulse.fhir.units :as units]
            [pulse.platform.http.client :as http])
  (:import (java.time Instant)
           (java.time.format DateTimeParseException)))

(defprotocol DataSource
  (patient-ids [source]
    "The ids of every patient there is.")
  (readings [source patient-id query]
    "The readings that answer `query` (see `pulse.scheduler.rules/query`) for the patient, newest
    first or not, as `{:at instant :value n}` or `{:at instant :systolic n :diastolic n}` in the
    unit the measurement is stored in. A reading that cannot be used is left out.")
  (conditions [source patient-id]
    "The active conditions of the patient, as a set of `{:system uri :code string}`."))

(defn reading-from-item
  "The reading in `item`, one of the api's series for the measurement `kind`, in the unit the
  measurement is stored in; nil when it cannot be used: other systems write to the same server, and
  a reading with no time, with a number missing or in a unit that does not fit must not stop the
  others from being judged."
  [kind item]
  (let [{stored :unit :keys [channels]} (get measurement/measurements kind)
        unit (or (:unit item) stored)
        at (try (some-> (:at item) Instant/parse)
                (catch DateTimeParseException _ nil))
        numbers (for [channel (keys channels)] [channel (get item channel)])]
    (when (and at
               (seq channels)
               (units/compatible? unit stored)
               (every? (comp number? second) numbers))
      (into {:at at}
            ;; a number already in the stored unit is left as it is, not made a double
            (map (fn [[channel x]] [channel (if (= unit stored) x (units/convert x unit stored))]))
            numbers))))

(defn- all-patient-ids
  "Every page of the list of patients, which the role `rules-engine` sees as identifiers only."
  [api]
  (loop [offset 0
         ids []]
    (let [{:keys [total items]} (http/get-json api "/api/patients" {:limit 100 :offset offset :sort "family"})
          ids (into ids (map :id) items)]
      (if (and (seq items) (< (count ids) total))
        (recur (+ offset (count items)) ids)
        ids))))

(defrecord ApiSource [api fhir]
  DataSource
  (patient-ids [_]
    (all-patient-ids api))

  (readings [_ patient-id {:keys [measurement from limit] :or {limit 200}}]
    (let [{:keys [items]} (http/get-json api
                                         (http/path "api" "patients" patient-id "observations")
                                         {:measurement (name measurement)
                                          :from (some-> from str)
                                          ;; newest first: if there are more than `limit`, it is
                                          ;; the oldest that are left out
                                          :sort "-date"
                                          :limit limit})]
      (into [] (keep #(reading-from-item measurement %)) items)))

  (conditions [_ patient-id]
    (into #{}
          (comp (mapcat (comp :coding :code))
                (map (fn [{:keys [system code]}] {:system system :code code})))
          (c/search fhir "Condition"
                    {:patient (str "Patient/" patient-id)
                     :clinical-status (str (terminology/system-uri :condition-clinical) "|active")}))))

(defn api-source
  "The data from `api`, a client of the api (`pulse.platform.http.client`), and `fhir`, a FHIR
  client."
  [api fhir]
  (->ApiSource api fhir))
