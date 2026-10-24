(ns pulse.web.errors
  "What the portal answers when it cannot give a page: a path that matches no route, a method the route does not have and
  a failure on its own side (ADR-0028). The answers are the pages of `pulse.web.pages`, in
  words for a patient, with the status that says what happened. Nothing of the failure itself is in them: the cause is in
  the log."
  (:require [pulse.platform.http.router :as router]
            [pulse.web.api :as api]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.return-to :as return-to]
            [reitit.ring :as ring]
            [taoensso.telemere :as t]))

(defn failure
  "The response for a `kind` of failure of `pulse.web.pages/error-kinds`, for `request`, with `status` (the kind's own by
  default) and more `headers` if there are any. \"Try again\" leads to the same page when that was a GET to ask for again,
  and to the home page otherwise. `sign-in-href` is where a button that signs in leads to (the sign-in, with the place to
  come back to), and `sign-out-href` where one that signs out does (the identity provider's, which asks first)."
  [kind request & {:keys [status headers sign-in-href sign-out-href]}]
  (let [response (html/response (or status (pages/status-of kind))
                                (pages/error-page kind (cond-> {:retry-href (return-to/of-request request)}
                                                         sign-in-href (assoc :sign-in-href sign-in-href)
                                                         sign-out-href (assoc :sign-out-href sign-out-href))))]
    (cond-> response
      headers (update :headers merge headers))))

(defn server-error
  "The page for a failure nobody expected, for `pulse.platform.http.middleware/wrap-exceptions`."
  [request]
  (failure :server-error request))

(defn log-api-failure!
  "Records `e`, a failure of a call to the api (`pulse.web.api/failure?`): how it failed, where, and what the api said of it, which
  is for the log and never for a page. A failure that a page can go on after is a warning, and one it cannot an error."
  [e]
  (let [{:keys [type status reason path problem]} (ex-data e)]
    (t/event! :pulse.web/api-failed
              {:level (if (api/degradable? e) :warn :error)
               :data (cond-> {:failure (name type) :path path}
                       status (assoc :status status)
                       reason (assoc :reason reason)
                       problem (assoc :problem problem))})))

(defn attempt
  "`{:done (f)}`, or `{:failed e}` when `f` fails in a way that a page can go on after (`pulse.web.api/degradable?`): the failure is
  in the log. Another failure is thrown."
  [f]
  (try
    {:done (f)}
    (catch clojure.lang.ExceptionInfo e
      (if (api/degradable? e)
        (do (log-api-failure! e) {:failed e})
        (throw e)))))

(defn gateway-status
  "The status of a page that answers a failure of the api that it can go on after: a gateway timeout when the api was too slow, a
  bad gateway otherwise."
  [e]
  (if (api/timeout? e) 504 502))

(defn api-failure
  "The page for `e`, a failure of a call to the api (`pulse.web.api/failure?`), or nil when it is not one. The api said that the
  person may not see it (403): `forbidden`. Any other failure is on our side, whoever's it is, and says so, with the status
  that tells what happened between the portal and the api: 504 when the api was too slow or said that something it asked was,
  502 otherwise. What it said is in the log, never in the page."
  [request e]
  (when (api/failure? e)
    (log-api-failure! e)
    (if (= 403 (:status (ex-data e)))
      (failure :forbidden request)
      (failure :server-error request :status (if (api/timeout? e) 504 502)))))

(def default-handler
  "What answers a path that matches no route (404) and a method the route does not have (405, with `Allow`): the page that
  says that it cannot be found. There is no content negotiation in the portal, so nothing is ever not acceptable."
  (ring/create-default-handler
   {:not-found (fn [request] (failure :not-found request))
    :method-not-allowed (fn [request]
                          (failure :not-found request :status 405 :headers {"Allow" (router/allow-header request)}))}))
