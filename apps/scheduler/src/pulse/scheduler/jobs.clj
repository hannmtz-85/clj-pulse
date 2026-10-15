(ns pulse.scheduler.jobs
  "The catalogue of the scheduler's jobs (ADR-0020). Each is a map as `pulse.scheduler.job` says,
  whose handler closes over the components it needs, so the catalogue is built from the started
  system by a function and not read from the configuration. The appointment reminders are added
  here as they are written."
  (:require [pulse.fhir.synthetic :as synthetic]
            [pulse.scheduler.engine :as engine]
            [pulse.scheduler.simulator :as simulator]
            [pulse.scheduler.source :as source])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

(def service-actor
  "Who the audit trail names for what the scheduler writes: its client in the realm and its role."
  {:client "pulse-scheduler" :roles ["rules-engine"]})

(defn- evaluate-rules-job
  "The job that evaluates the rules of Annex C (ADR-0022) over the latest readings. A run in which
  some patient could not be evaluated is a failed run, so that it shows as one; the others were
  evaluated all the same."
  [{:keys [api fhir]} {:keys [schedule]}]
  (let [deps {:data (source/api-source api fhir) :fhir fhir :actor service-actor}]
    {:id :evaluate-rules
     :description "Evaluates the clinical rules over the latest readings and raises alerts"
     :schedule schedule
     :handler (fn [{:keys [started-at]}]
                (let [summary (engine/run! deps started-at)]
                  (when (pos? (:failed summary))
                    (throw (ex-info "Some patients could not be evaluated" {:summary summary})))
                  summary))}))

(defn- simulate-devices-job
  "The job that simulates the devices of the demo cohort (ADR-0023): one run is one tick of the
  simulated world, whose readings go to FHIR. The tick is counted from the start of the scheduler,
  and an episode of the scenario begins at the tick it says. A run in which a reading could not be
  written is a failed run."
  [{:keys [fhir]} {:keys [schedule batch-size]}]
  (let [scenario (simulator/scenario (synthetic/cohort))
        last-tick (atom -1)]
    {:id :simulate-devices
     :description "Simulates the readings of the devices of the demo cohort and writes them to FHIR"
     :schedule schedule
     :handler (fn [{:keys [^Instant started-at]}]
                (let [n (swap! last-tick inc)
                      summary (simulator/tick! fhir scenario n (.truncatedTo started-at ChronoUnit/SECONDS)
                                               {:batch-size batch-size})]
                  (when (pos? (+ (:failed summary) (:errors summary)))
                    (throw (ex-info "Some readings could not be written" {:summary summary})))
                  summary))}))

(defn catalogue
  "The jobs to run, a vector, built from `options` (the `:pulse.scheduler/jobs` component): `:api`,
  a client of the api, `:fhir`, a FHIR client, and the settings of each job, such as
  `:evaluate-rules` and `:simulate-devices` with their `:schedule` and whether they are `:enabled?`."
  [{:keys [evaluate-rules simulate-devices] :as options}]
  (cond-> []
    (:enabled? evaluate-rules) (conj (evaluate-rules-job options evaluate-rules))
    (:enabled? simulate-devices) (conj (simulate-devices-job options simulate-devices))))
