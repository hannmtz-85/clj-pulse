(ns pulse.scheduler.jobs
  "The catalogue of the scheduler's jobs (ADR-0020). Each is a map as `pulse.scheduler.job` says,
  whose handler closes over the components it needs, so the catalogue is built from the started
  system by a function and not read from the configuration. The device simulator and the
  appointment reminders are added here as they are written."
  (:require [pulse.scheduler.engine :as engine]
            [pulse.scheduler.source :as source]))

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

(defn catalogue
  "The jobs to run, a vector, built from `options` (the `:pulse.scheduler/jobs` component): `:api`,
  a client of the api, `:fhir`, a FHIR client, and the settings of each job, such as
  `:evaluate-rules` with its `:schedule` and whether it is `:enabled?`."
  [{:keys [evaluate-rules] :as options}]
  (cond-> []
    (:enabled? evaluate-rules) (conj (evaluate-rules-job options evaluate-rules))))
