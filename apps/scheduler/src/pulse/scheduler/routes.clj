(ns pulse.scheduler.routes
  "The scheduler's routes, as data: path, method, who may call it (`:authz`, see
  `pulse.scheduler.authz`), the shape of what it takes and returns, and the handler (ADR-0020).
  The status of the jobs is shown as camelCase JSON with the times as ISO strings, like the
  api's own views. Handlers are built from `deps`, the started components they need."
  (:require [pulse.platform.http.health :as health]
            [pulse.platform.http.problem :as problem]
            [pulse.platform.jwt :as jwt]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.job :as job]))

;; ---------------------------------------------------------------------------
;; How the status of a job is shown

(defn- schedule-view [{:keys [type every-ms offset-ms at zone]}]
  (case type
    :interval (cond-> {:type "interval" :everyMs every-ms}
                offset-ms (assoc :offsetMs offset-ms))
    :daily {:type "daily" :at at :zone zone}))

(defn- run-view [{:keys [trigger started-at finished-at duration-ms outcome result error]}]
  (cond-> {:trigger (name trigger)
           :startedAt (str started-at)
           :finishedAt (str finished-at)
           :durationMs duration-ms
           :outcome (name outcome)}
    result (assoc :result result)
    error (assoc :error error)))

(defn job-view
  "A job's entry of `pulse.scheduler.executor/status` as the JSON the scheduler answers with."
  [{:keys [id description schedule state running-since next-run last-run counts]}]
  {:id (name id)
   :description description
   :schedule (schedule-view schedule)
   :state (name state)
   :runningSince (some-> running-since str)
   :nextRun (some-> next-run str)
   :lastRun (some-> last-run run-view)
   :counts counts})

(def ^:private run-schema
  [:map
   [:trigger [:enum "schedule" "manual"]]
   [:startedAt :string]
   [:finishedAt :string]
   [:durationMs :int]
   [:outcome [:enum "ok" "failed" "interrupted"]]
   [:result {:optional true} [:map-of :keyword :any]]
   [:error {:optional true} :string]])

(def ^:private job-schema
  [:map
   [:id :string]
   [:description :string]
   [:schedule [:map-of :keyword :any]]
   [:state [:enum "idle" "running"]]
   [:runningSince [:maybe :string]]
   [:nextRun [:maybe :string]]
   [:lastRun [:maybe run-schema]]
   [:counts [:map [:ok :int] [:failed :int] [:interrupted :int] [:skipped :int]]]])

(def ^:private readiness-schema
  [:map
   [:status [:enum "ready" "unready"]]
   [:checks [:map-of :string [:enum "up" "down"]]]])

(def ^:private job-path
  [:map [:id [:re {:error/message "should be the id of a job, such as evaluate-rules"}
              (re-pattern (str "^" job/id-pattern "$"))]]])

;; ---------------------------------------------------------------------------
;; Handlers

(defn- liveness [_request]
  {:status 200 :body {:status "up"}})

(defn- readiness
  "Readiness asks for the identity provider's keys, without which no token can be checked, and
  that the executor is running."
  [{:keys [verifier executor]}]
  (let [checks {:keycloak #(jwt/keys-available? (:jwks-source verifier))
                :executor #(executor/running? executor)}]
    (fn [_request]
      (health/readiness-response (health/run-checks checks {})))))

(defn- list-jobs [{:keys [executor]}]
  (fn [_request]
    {:status 200 :body (mapv job-view (executor/status executor))}))

(defn- run-job [{:keys [executor]}]
  (fn [request]
    (let [id (keyword (get-in request [:parameters :path :id]))]
      (case (executor/run-now! executor id)
        :started {:status 202 :body (job-view (executor/job-status executor id))}
        :already-running (problem/response 409 {:detail "The job is already running." :request request})
        :unknown-job (problem/response 404 {:detail "No such job." :request request})
        :stopped (problem/response 503 {:detail "The scheduler is stopping." :request request})))))

(defn routes
  "The route table. `deps` is `{:verifier v :executor e}`."
  [deps]
  [["/healthz" {:get {:authz :public
                      :responses {200 {:body [:map [:status [:= "up"]]]}}
                      :handler liveness}}]
   ["/readyz" {:get {:authz :public
                     :responses {200 {:body readiness-schema}
                                 503 {:body readiness-schema}}
                     :handler (readiness deps)}}]
   ["/jobs" {:get {:authz {:action :jobs/read}
                   :responses {200 {:body [:vector job-schema]}}
                   :handler (list-jobs deps)}}]
   ["/jobs/:id/run" {:post {:authz {:action :jobs/run}
                            :parameters {:path job-path}
                            :responses {202 {:body job-schema}}
                            :handler (run-job deps)}}]])
