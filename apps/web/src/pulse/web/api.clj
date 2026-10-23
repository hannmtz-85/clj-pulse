(ns pulse.web.api
  "The portal's calls to the api, for the person who is signed in (ADR-0028). The portal's client of the api has no token of its
  own: each call is given the keeper of the tokens of the session of the request, so the api sees the patient and decides, as
  it always does, what they may see. The id of the request goes with it in `X-Request-Id`, so that one visit has one id in the
  log of the portal and in the log of the api.

  A call that fails throws what `pulse.platform.http.client` throws: an `::http/unreachable` (the api could not be reached, or
  was too slow) or an `::http/failed` (it answered, and not with a success), or the `::token/unavailable` of the keeper, which
  is not a failure of the api and is for `pulse.web.authz` to answer. A page decides, by `degradable?`, whether it can go on
  without the part that failed; the rest becomes a page for the failure (`pulse.web.errors/api-failure`)."
  (:require [pulse.platform.http.client :as http]
            [pulse.web.session :as session]))

(defn get-json
  "The body of a successful GET of `path` (with the `query` map, if there is one) that the person of `request` makes through
  `api`, the portal's client of the api. Throws as described at the top of this namespace."
  ([api request path] (get-json api request path nil))
  ([api request path query]
   (http/call (assoc api :token (session/tokens-of request))
              {:method :get
               :path path
               :query query
               :headers (when-let [id (:request-id request)] {"X-Request-Id" id})})))

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
