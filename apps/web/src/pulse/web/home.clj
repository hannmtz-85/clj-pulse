(ns pulse.web.home
  "The home of the portal, `GET /` (ADR-0028): what a patient who is signed in sees first. The portal asks the api, with the
  patient's own token, who they are (`/api/me`: the id of their record, their name and their doctor), then for their summary
  (`/api/patients/{id}/summary`: the latest reading of each measurement and their appointments) and then, at once, for the
  newest readings of each measurement that the summary says they have (`/api/patients/{id}/observations`), which make the
  list of recent ones. The page is `pulse.web.pages.home`, which is a function of what the api answered.

  Who the patient is cannot be done without, and a failure of that call is a page for the failure. The rest can: the api could
  not be reached for the summary, or was too slow, or failed on its own side, and the page is the home with a message where
  the readings and the appointments should be, and a button to try again. A failure of another kind (the api refusing the
  portal's token, say) is a failure of the whole page. A part that is lost is in the log."
  (:require [pulse.fhir.reference :as ref]
            [pulse.platform.fanout :as fanout]
            [pulse.platform.http.client :as http]
            [pulse.web.api :as api]
            [pulse.web.authz :as authz]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.pages.home :as home]
            [pulse.web.return-to :as return-to]
            [pulse.web.session :as session]
            [pulse.web.views :as views]
            [taoensso.telemere :as t])
  (:import (java.time Clock Instant)))

(def default-series-timeout-ms
  "How long the series of the recent readings, all at once, have between them, unless a test says otherwise."
  5000)

(defn- patient-id
  "The id of the patient's own record, from what `/api/me` answered, or a failure on our side: a patient with no record to show
  is an account the clinic has not finished setting up."
  [me]
  (let [{:keys [type id]} (some-> (:fhirUser me) ref/parse)]
    (if (= "Patient" type)
      id
      (throw (ex-info "The api knows no record of this patient" {:type ::no-patient-record})))))

(defn- lost!
  "Records that the part of the page called `part` could not be had: `why` is `{:status :reason}` of what happened to it."
  [part why]
  (t/event! :pulse.web/part-lost {:level :warn :data (assoc why :part part)}))

(defn- summary-of
  "The summary of the patient, or nil when the api could not give it and a page can go on without."
  [api request id]
  (try
    (api/get-json api request (http/path "api" "patients" id "summary"))
    (catch clojure.lang.ExceptionInfo e
      (if (api/degradable? e)
        (do (lost! "summary" (select-keys (ex-data e) [:status :reason])) nil)
        (throw e)))))

(defn- lost-reason
  "Why the outcome of a task of a fan-out is a part that is lost, as `{:status :reason}` for the log: a failure of the api that a
  page can go on without, or a task that was not done in time. Nil when the task went well. Anything else is thrown, for the
  request to answer: a failure of another kind, and a failure of the keeper of the person's token, which is not the api's."
  [outcome]
  (cond
    (contains? outcome :ok) nil
    (:timeout outcome) {:reason :timeout}
    :else (let [e (:error outcome)]
            (if (api/degradable? e)
              (select-keys (ex-data e) [:status :reason])
              (throw e)))))

(defn- recent-of
  "The items of the series of the recent readings: the newest of each measurement of `summary` that has a reading (of none, when
  there is no summary), asked for at once. Nil when any of them could not be had and a page can go on without."
  [api request id summary timeout-ms]
  (let [path (http/path "api" "patients" id "observations")
        outcomes (fanout/run (into {}
                                   (for [kind (views/measured-kinds summary)]
                                     [kind #(api/get-json api request path {:measurement (name kind) :limit home/recent-count})]))
                             {:timeout-ms timeout-ms})
        lost (into {} (keep (fn [[kind outcome]] (when-let [why (lost-reason outcome)] [kind why]))) outcomes)]
    (if (seq lost)
      (do (doseq [[kind why] lost] (lost! (str "series " (name kind)) why))
          nil)
      (into [] (mapcat #(:items (:ok %))) (vals outcomes)))))

(defn handler
  "The handler of `GET /`. `deps` has the `:api`, the portal's client of the api, the `:clock` (a `java.time.Clock`, the
  system's by default) and `:series-timeout-ms`, which the series of the recent readings have between them
  (`default-series-timeout-ms`)."
  [{:keys [api clock series-timeout-ms] :or {series-timeout-ms default-series-timeout-ms}}]
  (let [^Clock clock (or clock (Clock/systemUTC))]
    (fn [request]
      (let [me (api/get-json api request "/api/me")
            id (patient-id me)
            summary (summary-of api request id)
            recent (recent-of api request id summary series-timeout-ms)
            now (str (Instant/now clock))]
        (html/response
         (pages/page {:title "Home"
                      :current :home
                      :person (authz/person request)
                      :csrf-token (session/csrf-token-of request)}
                     (home/content {:now now :me me :summary summary :recent recent}
                                   {:retry (return-to/of-request request)})))))))
