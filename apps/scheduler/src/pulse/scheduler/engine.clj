(ns pulse.scheduler.engine
  "A run of the rules engine (ADR-0022): every rule over every patient at a moment, and the alerts
  that follow. The rules are pure (`pulse.scheduler.rules`); the data is a `DataSource`
  (`pulse.scheduler.source`); an alert is raised in FHIR once (`pulse.scheduler.alerts`).

  A run goes patient by patient. A failure of the infrastructure ends the run, since the next
  patient would fail the same way; any other failure is counted and the run goes on."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [pulse.fhir.client :as c]
            [pulse.fhir.terminology :as terminology]
            [pulse.platform.http.client :as http]
            [pulse.platform.token :as token]
            [pulse.scheduler.alerts :as alerts]
            [pulse.scheduler.rules :as rules]
            [pulse.scheduler.source :as source]
            [taoensso.telemere :as t])
  (:import (java.io IOException)
           (java.time Instant)))

(defn- fatal?
  "Whether `e` says that the infrastructure is out of reach or refuses the engine, and not that
  one patient has a problem: no token, the api or FHIR unreachable or failing, or the api refusing
  the token."
  [e]
  (let [{:keys [type status]} (ex-data e)]
    (or (= ::token/unavailable type)
        (= ::http/unreachable type)
        (and (= ::http/failed type) (or (contains? #{401 403} status) (>= (or status 0) 500)))
        (and (= ::c/fhir-error type) (or (nil? status) (>= status 500)))
        (instance? IOException e))))

(defn- has-condition?
  "Whether `conditions`, a set of `{:system :code}`, holds one of `system` (a key of
  `pulse.fhir.terminology/systems`) whose code starts with `prefix`."
  [conditions {:keys [system prefix]}]
  (let [uri (terminology/system-uri system)]
    (boolean (some (fn [{condition-system :system code :code}]
                     (and (= uri condition-system) (str/starts-with? (str code) prefix)))
                   conditions))))

(defn- audit-raised!
  "The record that the engine wrote an alert: who, what, for whom and which resource, and the rule,
  never a reading."
  [actor patient-id rule flag-id]
  (t/event! :pulse.audit/write
            {:level :info
             :data {:actor actor
                    :action :alert/write
                    :outcome :created
                    :patient (str "Patient/" patient-id)
                    :resource (str "Flag/" flag-id)
                    :rule (:id rule)}}))

(defn- evaluate-patient
  "What the rules make of one patient at `now`: a vector of `{:rule id :outcome o}` for each rule
  that fired, where `o` is `:created` or `:existing` (an alert for those readings was already
  raised), `:still-active` (there is an active alert for the rule) or `:not-applicable` (the patient
  lacks the condition the rule requires)."
  [{:keys [data fhir rules actor]} ^Instant now patient-id]
  (let [fired (into []
                    (keep (fn [rule]
                            (when-let [found (rules/evaluate rule (source/readings data patient-id (rules/query rule now)) now)]
                              [rule found])))
                    rules)]
    (if (empty? fired)
      []
      (let [existing (delay (alerts/flags-of fhir patient-id))
            conditions (delay (source/conditions data patient-id))]
        (mapv (fn [[rule {:keys [newest-at readings]}]]
                (cond
                  (and (:requires-condition rule)
                       (not (has-condition? @conditions (:requires-condition rule))))
                  {:rule (:id rule) :outcome :not-applicable}

                  (alerts/active? @existing rule patient-id)
                  {:rule (:id rule) :outcome :still-active}

                  :else
                  (let [{:keys [outcome id]} (alerts/raise! fhir (alerts/flag rule patient-id newest-at now))]
                    (when (= :created outcome)
                      (audit-raised! actor patient-id rule id)
                      (t/event! :pulse.rules/alert-raised
                                {:level :info
                                 :data {:rule (:id rule) :patient patient-id :flag id
                                        :readings (count readings) :newest-at (str newest-at)}}))
                    {:rule (:id rule) :outcome outcome :flag id})))
              fired)))))

(defn run!
  "Evaluates the rules over every patient at `now`, an Instant. `deps` is `:data` (a `DataSource`),
  `:fhir` (a FHIR client), `:rules` (by default every rule of Annex C) and `:actor` (who is named in
  the audit event of an alert, such as `{:client \"pulse-scheduler\" :roles [\"rules-engine\"]}`).

  Returns a summary of numbers: `:patients`, `:raised` (alerts created), `:already-raised` (a rule
  fired for readings that already have their alert, which may have been acknowledged),
  `:still-active` (a rule fired and the patient has an active alert for it), `:not-applicable` and
  `:failed` (patients that could not be evaluated, each logged as `:pulse.rules/patient-failed`).
  Throws when the infrastructure fails."
  [{:keys [data] :as deps} now]
  (let [deps (update deps :rules #(or % rules/catalogue))
        outcome->key {:created :raised :existing :already-raised
                      :still-active :still-active :not-applicable :not-applicable}
        patients (source/patient-ids data)]
    (reduce (fn [summary patient-id]
              (try
                (reduce (fn [summary {:keys [outcome]}] (update summary (outcome->key outcome) inc))
                        summary
                        (evaluate-patient deps now patient-id))
                (catch Exception e
                  (when (fatal? e)
                    (throw e))
                  (t/event! :pulse.rules/patient-failed
                            {:level :warn :error e :data {:patient patient-id}})
                  (update summary :failed inc))))
            {:patients (count patients) :raised 0 :already-raised 0 :still-active 0 :not-applicable 0 :failed 0}
            patients)))
