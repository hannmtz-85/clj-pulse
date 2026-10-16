(ns pulse.api.handler
  "Assembles the api's Ring handler from the started components.

  From the outside in: the request id, the access log, the security headers (ADR-0018), CORS
  and the exception handler wrap everything, including requests that match no route. The log
  is outside the rest so that it sees what they answer, preflights and refusals of an origin
  among it; CORS is outside the exception handler so that an error carries the headers that let
  the browser application read it. Inside the router, each endpoint passes through
  authorization (which authenticates), then content negotiation, then request and response
  coercion against the schemas in its route data. Authorization comes before the request body is
  parsed: nobody gets to feed JSON to the parser without a valid token.

  The page of Swagger UI under `/docs` is not an endpoint: it is a handler that comes before the
  router's own answer for a path that matches no route (ADR-0017)."
  (:require [pulse.api.authz :as authz]
            [pulse.api.docs :as docs]
            [pulse.api.routes :as routes]
            [pulse.api.security :as security]
            [pulse.platform.http.cors :as cors]
            [pulse.platform.http.headers :as headers]
            [pulse.platform.http.middleware :as mw]
            [pulse.platform.http.router :as router]
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
  `:realm` (for `WWW-Authenticate`), `:quiet-paths` (health probes, logged at debug), `:issuer`
  (the identity provider as a browser reaches it, which the page of Swagger UI may call) and
  `:cors` (`{:allowed-origins #{...}}`, see `pulse.platform.http.cors`; no origins by default)."
  [routes {:keys [verifier realm quiet-paths issuer] cors-options :cors}]
  (ring/ring-handler
   (build-router routes {:verifier verifier :realm realm})
   (ring/routes (docs/ui-handler) router/default-handler)
   {:middleware [mw/wrap-request-id
                 [mw/wrap-access-log {:quiet-paths quiet-paths}]
                 [headers/wrap-security-headers (security/header-options issuer)]
                 [cors/wrap-cors cors-options]
                 [mw/wrap-exceptions {:handlers router/exception-handlers}]]}))

(defn create
  "The api's Ring handler. `options` is the `:pulse.api/handler` component: `:verifier`,
  `:fhir`, `:scheduler`, `:realm`, `:issuer`, `:cors`, `:quiet-paths` and `:summary-timeout-ms`. A
  `:clock` (a `java.time.Clock`) may be given too, which the configuration does not do: it is for
  tests."
  [{:keys [verifier fhir scheduler issuer summary-timeout-ms clock] :as options}]
  (ring-handler (routes/routes {:verifier verifier :fhir fhir :scheduler scheduler :issuer issuer
                                :summary-timeout-ms summary-timeout-ms :clock clock})
                options))
