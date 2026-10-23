(ns pulse.web.api
  "The portal's calls to the api, for the person who is signed in (ADR-0028). The portal's client of the api has no token of its
  own: each call is given the keeper of the tokens of the session of the request, so the api sees the patient and decides, as
  it always does, what they may see. The id of the request goes with it in `X-Request-Id`, so that one visit has one id in the
  log of the portal and in the log of the api.

  A call that fails throws what `pulse.platform.http.client` throws: an `::http/unreachable` (the api could not be reached, or
  was too slow) or an `::http/failed` (it answered, and not with a success), or the `::token/unavailable` of the keeper, which
  is not a failure of the api and is for `pulse.web.authz` to answer. A page decides, by `degradable?`, whether it can go on
  without the part that failed; the rest becomes a page for the failure (`pulse.web.errors/api-failure`)."
  (:require [pulse.fhir.reference :as ref]
            [pulse.platform.http.client :as http]
            [pulse.web.session :as session]
            [taoensso.telemere :as t]))

(defn- headers-of [request]
  (when-let [id (:request-id request)] {"X-Request-Id" id}))

(defn get-json
  "The body of a successful GET of `path` (with the `query` map, if there is one) that the person of `request` makes through
  `api`, the portal's client of the api. Throws as described at the top of this namespace."
  ([api request path] (get-json api request path nil))
  ([api request path query]
   (http/call (assoc api :token (session/tokens-of request))
              {:method :get
               :path path
               :query query
               :headers (headers-of request)})))

(def ^:private max-problem-text 200)

(defn- short-text [x]
  (when (string? x)
    (if (> (count x) max-problem-text) (str (subs x 0 max-problem-text) "...") x)))

(defn- failed
  "The `::http/failed` for a `status` of `path` that the api answered with `body`, as `pulse.platform.http.client/call` makes it."
  [status path body]
  (let [problem (when (map? body)
                  (cond-> {}
                    (short-text (:title body)) (assoc :title (short-text (:title body)))
                    (short-text (:detail body)) (assoc :detail (short-text (:detail body)))))]
    (ex-info (str "POST " path " answered " status)
             (cond-> {:type ::http/failed :status status :path path}
               (seq problem) (assoc :problem problem)))))

(defn post-json
  "The outcome of a POST of `body`, as JSON, to `path` that the person of `request` makes through `api`: `{:created answer}` for a
  success, or `{:refused {:status :errors}}` when the api says that it cannot take it (a 400, for the shape of it, or a 422, for
  what cannot be a reading), where `:errors` is what it said was wrong with each field. Anything else throws as `get-json` does."
  [api request path body]
  (let [{:keys [status] answer :body} (http/request (assoc api :token (session/tokens-of request))
                                                    {:method :post :path path :body body :headers (headers-of request)})]
    (cond
      (and (<= 200 status 299) (= ::http/not-json answer))
      (throw (ex-info (str "POST " path " answered " status " with a body that is not JSON")
                      {:type ::http/failed :status status :path path :reason :bad-response}))

      (<= 200 status 299) {:created answer}

      (#{400 422} status) {:refused {:status status :errors (when (map? answer) (:errors answer))}}

      :else (throw (failed status path (when-not (= ::http/not-json answer) answer))))))

(defn patient-id
  "The id of the patient's own record, from what `GET /api/me` answered, or a failure on our side: a patient with no record to show
  is an account the clinic has not finished setting up."
  [me]
  (let [{:keys [type id]} (some-> (:fhirUser me) ref/parse)]
    (if (= "Patient" type)
      id
      (throw (ex-info "The api knows no record of this patient" {:type ::no-patient-record})))))

(defn failure?
  "Whether `e` is a failure of a call to the api: it could not be reached, or it answered with a failure (or with what is not
  JSON, which is a failure too)."
  [e]
  (contains? #{::http/unreachable ::http/failed} (:type (ex-data e))))

(defn timeout?
  "Whether the api failed to answer in time: the call timed out here, or it said (504) that something it asked did."
  [e]
  (let [{:keys [type reason status]} (ex-data e)]
    (or (and (= ::http/unreachable type) (= :timeout reason))
        (= 504 status))))

(defn degradable?
  "Whether a failure of the api is one that a page can go on without, losing the part it was for: the api could not be reached,
  or failed on its own side (a status of 500 or more, or an answer that is not JSON). Anything else, such as the api refusing the
  portal's token, saying that the person may not see it or not knowing what was asked, is a failure of the whole page: no part
  of it is to be trusted."
  [e]
  (let [{:keys [type status reason]} (ex-data e)]
    (boolean (or (= ::http/unreachable type)
                 (and (= ::http/failed type)
                      (or (= :bad-response reason)
                          (and (number? status) (>= status 500))))))))

(defn lost!
  "Records that the part of a page called `part` could not be had: `why` is `{:status :reason}` of what happened to it."
  [part why]
  (t/event! :pulse.web/part-lost {:level :warn :data (assoc why :part part)}))

(defn degrading
  "The value of `(f)`, a call to the api that a page can go on without: when it fails in a way that a page can go on without
  (`degradable?`), nil, and the part called `part` that is lost is in the log. A failure of any other kind is thrown."
  [part f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (degradable? e)
        (do (lost! part (select-keys (ex-data e) [:status :reason])) nil)
        (throw e)))))
