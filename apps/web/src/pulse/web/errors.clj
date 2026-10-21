(ns pulse.web.errors
  "What the portal answers when it cannot give a page: a path that matches no route, a method the route does not have and
  a failure on its own side (ADR-0028). The answers are the pages of `pulse.web.pages`, in
  words for a patient, with the status that says what happened. Nothing of the failure itself is in them: the cause is in
  the log."
  (:require [pulse.platform.http.router :as router]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.return-to :as return-to]
            [reitit.ring :as ring]))

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

(def default-handler
  "What answers a path that matches no route (404) and a method the route does not have (405, with `Allow`): the page that
  says that it cannot be found. There is no content negotiation in the portal, so nothing is ever not acceptable."
  (ring/create-default-handler
   {:not-found (fn [request] (failure :not-found request))
    :method-not-allowed (fn [request]
                          (failure :not-found request :status 405 :headers {"Allow" (router/allow-header request)}))}))
