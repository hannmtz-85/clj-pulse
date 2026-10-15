(ns pulse.scheduler.handler
  "Assembles the scheduler's Ring handler from the started components (ADR-0020).

  From the outside in: the request id, the access log, the security headers and the exception
  handler wrap everything, including requests that match no route; the log is outside the rest
  so that it sees what they answer. Inside the router, each endpoint passes through
  authorization (which authenticates), then content negotiation, then request and response
  coercion against the schemas in its route data. There is no CORS: no browser calls the
  scheduler."
  (:require [pulse.platform.http.headers :as headers]
            [pulse.platform.http.middleware :as mw]
            [pulse.platform.http.router :as router]
            [pulse.scheduler.authz :as authz]
            [pulse.scheduler.routes :as routes]
            [pulse.scheduler.security :as security]
            [reitit.ring :as ring]))

(defn build-router
  "The Reitit router for `routes`, with the setup the services share (see
  `pulse.platform.http.router`). Throws when an endpoint has no valid `:authz`. `auth-deps` is
  `{:verifier v :realm r}`."
  [routes auth-deps]
  (-> (ring/router routes (router/options (authz/middleware auth-deps)))
      authz/check-routes!))

(defn ring-handler
  "The Ring handler for `routes`, with the whole pipeline around it. `options` is `:verifier`,
  `:realm` (for `WWW-Authenticate`) and `:quiet-paths` (health probes, logged at debug)."
  [routes {:keys [verifier realm quiet-paths]}]
  (ring/ring-handler
   (build-router routes {:verifier verifier :realm realm})
   router/default-handler
   {:middleware [mw/wrap-request-id
                 [mw/wrap-access-log {:quiet-paths quiet-paths}]
                 [headers/wrap-security-headers {:headers security/headers}]
                 [mw/wrap-exceptions {:handlers router/exception-handlers}]]}))

(defn create
  "The scheduler's Ring handler. `options` is the `:pulse.scheduler/handler` component:
  `:verifier`, `:executor`, `:realm` and `:quiet-paths`."
  [{:keys [verifier executor] :as options}]
  (ring-handler (routes/routes {:verifier verifier :executor executor}) options))
