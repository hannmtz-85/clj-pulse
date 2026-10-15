(ns pulse.scheduler.routes
  "The scheduler's routes, as data: path, method, who may call it (`:authz`, see
  `pulse.scheduler.authz`), the shape of what it takes and returns, and the handler (ADR-0020).
  The status of the jobs is shown as camelCase JSON with the times as ISO strings, like the
  api's own views. Handlers are built from `deps`, the started components they need."
  (:require [clojure.string :as str]
            [pulse.fhir.client :as fhir]
            [pulse.platform.http.client :as http]
            [pulse.platform.http.health :as health]
            [pulse.platform.http.problem :as problem]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.token :as token]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.job :as job]))

;; ---------------------------------------------------------------------------
;; How the status of a job is shown

(defn- schedule-view [{:keys [type every-ms offset-ms at zone]}]
  (case type
    :interval (cond-> {:type "interval" :everyMs every-ms}
                offset-ms (assoc :offsetMs offset-ms))
    :daily {:type "daily" :at at :zone zone}))

(defn- camel
  "The key `k`, such as :already-raised, as :alreadyRaised."
  [k]
  (let [[head & more] (str/split (name k) #"-")]
    (keyword (apply str head (map str/capitalize more)))))

(defn- run-view [{:keys [trigger started-at finished-at duration-ms outcome result error]}]
  (cond-> {:trigger (name trigger)
           :startedAt (str started-at)
           :finishedAt (str finished-at)
           :durationMs duration-ms
           :outcome (name outcome)}
    ;; what a job returns is flat (see `executor/summary`) and in Clojure's style: its keys are
    ;; written as the rest are
    result (assoc :result (update-keys result camel))
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
  "Readiness asks for the identity provider's keys, without which no token can be checked, that the
  executor is running, and that the scheduler can identify itself: that it can get its own access
  token, from the cache or from Keycloak (ADR-0021). A wrong secret shows here and not as jobs that
  fail. It asks too for what the jobs read and write: that the api says it is ready (which means it
  can check the token and reach FHIR), and that FHIR answers."
  [{:keys [verifier executor api fhir] own-token :token}]
  (let [checks {:keycloak #(jwt/keys-available? (:jwks-source verifier))
                :executor #(executor/running? executor)
                :token #(token/available? own-token)
                :api #(= 200 (:status (http/request api {:method :get :path "/readyz"})))
                ;; asking for one Patient exercises the whole path to the database behind the server
                :fhir #(do (doall (take 1 (fhir/search fhir "Patient" {:_count 1})))
                           true)}]
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
  "The route table. `deps` is `{:verifier v :executor e :token t :api a :fhir f}`: the verifier of
  access tokens, the executor of the jobs, the keeper of the scheduler's own token and its clients
  of the api and of FHIR (which readiness asks about)."
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
