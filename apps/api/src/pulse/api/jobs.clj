(ns pulse.api.jobs
  "GET /api/admin/jobs: the status of the scheduler's jobs, for the clinic administrator (ADR-0025).

  The scheduler is another service, with a resource server of its own that accepts only tokens for it.
  The api asks it for `GET /jobs` with the access token of its own service account, never with the
  caller's, and carries the id of the request over in `X-Request-Id`, so that one request has one id
  in both logs. What the scheduler answers is checked against the schema here before it goes on:
  an answer that does not fit is the scheduler's fault and not an error of the api, a 502.

  A scheduler that cannot be asked is not the caller's fault either (see `pulse.api.fhir` for the
  FHIR server): a 502 when it cannot be reached, refuses the api's token, fails, or when the api
  cannot get its own token; a 504 when it is too slow. The text says no more than that, and the
  cause is in the log."
  (:require [malli.core :as m]
            [malli.error :as me]
            [pulse.platform.http.client :as http]
            [pulse.platform.http.problem :as problem]
            [pulse.platform.token :as token]
            [taoensso.telemere :as t]))

(def ^:private nullable-string [:maybe :string])

(def run-schema
  [:map
   [:trigger {:description "What began the run: `schedule`, or `manual` when someone asked for it."} [:enum "schedule" "manual"]]
   [:startedAt {:description "When it began."} :string]
   [:finishedAt {:description "When it ended."} :string]
   [:durationMs {:description "How long it took, in milliseconds."} :int]
   [:outcome {:description "`ok`; `failed`, when the job threw; or `interrupted`, when the scheduler stopped while it ran."} [:enum "ok" "failed" "interrupted"]]
   ;; What a job reports is its own: numbers by name, such as the patients evaluated or the reminders sent
   [:result {:optional true :description "What the job reported, numbers by name: its keys depend on the job."} [:map-of :keyword :any]]
   [:error {:optional true :description "The class of the error that ended a failed run, never its message."} :string]])

(def job-schema
  [:map
   [:id {:description "The id of the job."} :string]
   [:description {:description "What the job does."} :string]
   ;; Open on purpose: its keys depend on the type, `interval` or `daily`
   [:schedule {:description "When it is due: `{type: interval, everyMs}`, every so many milliseconds on the clock, or `{type: daily, at, zone}`, once a day at a time in a time zone."} [:map-of :keyword :any]]
   [:state {:description "`idle`, or `running` (and since when, in `runningSince`)."} [:enum "idle" "running"]]
   [:runningSince {:description "When the run that is under way began; null when the job is idle."} nullable-string]
   [:nextRun {:description "The next time it is due; null when the scheduler is stopping."} nullable-string]
   [:lastRun {:description "How the last run went; null when it has not run yet."} [:maybe run-schema]]
   [:counts {:description "How many runs there have been of each outcome since the scheduler started; `skipped` counts the times it was due and the job was still running."}
    [:map
     [:ok :int]
     [:failed :int]
     [:interrupted :int]
     [:skipped :int]]]])

(def list-schema
  [:map
   [:total {:description "How many jobs the scheduler has."} :int]
   [:items {:description "The jobs, in the order the scheduler has them."} [:vector job-schema]]])

(def ^:private scheduler-answer
  "What the scheduler's `GET /jobs` must answer, for the api to go on with it."
  [:vector job-schema])

(defn- upstream-failure
  "What a failure of the call to the scheduler is for the caller: a 504 when the scheduler was too
  slow, a 502 for any other. The exception stays in the log as the cause."
  [e]
  (let [{:keys [type reason]} (ex-data e)]
    (if (and (= ::http/unreachable type) (= :timeout reason))
      (problem/ex 504 "The scheduler did not answer in time." {:cause e})
      (problem/ex 502 "The scheduler could not be asked." {:cause e}))))

(defn- ask
  "The jobs as the scheduler answers `GET /jobs`, asked for with the api's own token and on behalf of
  the request with the id `request-id`. Throws a problem when it cannot be done."
  [scheduler request-id]
  (try
    (http/call scheduler {:method :get
                          :path "/jobs"
                          :headers (cond-> {} request-id (assoc "X-Request-Id" request-id))})
    (catch clojure.lang.ExceptionInfo e
      (if (contains? #{::http/unreachable ::http/failed ::token/unavailable} (:type (ex-data e)))
        (throw (upstream-failure e))
        (throw e)))))

(defn handler
  "The handler of `GET /api/admin/jobs`. `deps` has the `:scheduler`, a client of the scheduler (see
  `pulse.platform.http.client`) whose token is the api's own."
  [{:keys [scheduler]}]
  (fn [request]
    (let [jobs (ask scheduler (:request-id request))]
      (if (m/validate scheduler-answer jobs)
        {:status 200 :body {:total (count jobs) :items jobs}}
        (do (t/event! :pulse.jobs/unexpected-answer
                      {:level :error
                       :data {:problems (me/humanize (m/explain scheduler-answer jobs))}})
            (throw (problem/ex 502 "The scheduler answered something the api does not understand.")))))))
