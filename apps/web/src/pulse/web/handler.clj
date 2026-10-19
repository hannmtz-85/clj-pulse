(ns pulse.web.handler
  "Assembles the portal's Ring handler from the started components (ADR-0028).

  From the outside in: the request id, the access log, the security headers and the exception handler wrap everything,
  including requests that match no route; the log is outside the rest so that it sees what they answer. Inside the
  router, each route passes through authorization (a page of a patient is for a patient who is signed in), then the
  parameters of the query and of a form. What answers when the router finds nothing, and when something fails that nobody
  expected, is a page, not a problem."
  (:require [pulse.platform.http.headers :as headers]
            [pulse.platform.http.middleware :as mw]
            [pulse.web.authz :as authz]
            [pulse.web.errors :as errors]
            [pulse.web.routes :as routes]
            [pulse.web.security :as security]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]))

(defn build-router
  "The Reitit router for `routes`. Throws when a route has no valid `:authz`. Reitit answers OPTIONS on every route by
  default, with no `:authz`; that is switched off: every route is one the portal declared."
  [routes]
  (-> (ring/router routes {::ring/default-options-endpoint nil
                           :data {:middleware [authz/middleware
                                               parameters/parameters-middleware]}})
      authz/check-routes!))

(defn ring-handler
  "The Ring handler for `routes`, with the whole pipeline around it. `options` is `:issuer` (the identity provider as a
  browser reaches it, which the policy of the pages names) and `:quiet-paths` (health probes, logged at debug)."
  [routes {:keys [issuer quiet-paths]}]
  (ring/ring-handler
   (build-router routes)
   errors/default-handler
   {:middleware [mw/wrap-request-id
                 [mw/wrap-access-log {:quiet-paths quiet-paths}]
                 [headers/wrap-security-headers (security/header-options issuer)]
                 [mw/wrap-exceptions {:unexpected errors/server-error}]]}))

(defn create
  "The portal's Ring handler. `options` is the `:pulse.web/handler` component: `:jwks`, `:api`, `:issuer` and
  `:quiet-paths`."
  [{:keys [jwks api] :as options}]
  (ring-handler (routes/routes {:jwks jwks :api api}) options))
