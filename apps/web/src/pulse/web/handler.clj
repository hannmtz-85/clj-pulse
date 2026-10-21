(ns pulse.web.handler
  "Assembles the portal's Ring handler from the started components (ADR-0028).

  From the outside in: the request id, the access log, the security headers and the exception handler wrap everything,
  including requests that match no route; the log is outside the rest so that it sees what they answer. Then the check of
  where a request that changes something comes from, and the session. Inside the router, each route passes through
  authorization (a page of a patient is for a patient who is signed in), then the parameters of the query and of a form, then
  the check of the CSRF token. What answers when the router finds nothing, and when something fails that nobody expected, is a
  page, not a problem."
  (:require [clojure.string :as str]
            [pulse.platform.http.headers :as headers]
            [pulse.platform.http.middleware :as mw]
            [pulse.web.authz :as authz]
            [pulse.web.csrf :as csrf]
            [pulse.web.errors :as errors]
            [pulse.web.routes :as routes]
            [pulse.web.security :as security]
            [pulse.web.session :as session]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]))

(defn build-router
  "The Reitit router for `routes`. Throws when a route has no valid `:authz`. Reitit answers OPTIONS on every route by
  default, with no `:authz`; that is switched off: every route is one the portal declared."
  [routes]
  (-> (ring/router routes {::ring/default-options-endpoint nil
                           :data {:middleware [authz/middleware
                                               parameters/parameters-middleware
                                               csrf/middleware]}})
      authz/check-routes!))

(defn ring-handler
  "The Ring handler for `routes`, with the whole pipeline around it. `options` is `:issuer` (the identity provider as a
  browser reaches it, which the policy of the pages names), `:quiet-paths` (health probes, logged at debug), `:sessions`
  (the store of the sessions) and `:oidc` (whose `:base-url` is the address the portal is served at: where a request that
  changes something must come from, and whether the cookie and the headers are those of https)."
  [routes {:keys [issuer quiet-paths sessions oidc]}]
  (let [base-url (:base-url oidc)
        secure? (str/starts-with? (str base-url) "https://")]
    (ring/ring-handler
     (build-router routes)
     errors/default-handler
     {:middleware [mw/wrap-request-id
                   [mw/wrap-access-log {:quiet-paths quiet-paths}]
                   [headers/wrap-security-headers (security/header-options issuer {:secure? secure?})]
                   [mw/wrap-exceptions {:unexpected errors/server-error}]
                   [csrf/wrap-origin base-url]
                   [session/wrap-session sessions {:secure? secure?}]]})))

(defn create
  "The portal's Ring handler. `options` is the `:pulse.web/handler` component: `:jwks`, `:api`, `:oidc`, `:sessions`,
  the two verifiers of tokens (`:access-verifier` and `:id-verifier`), `:issuer` and `:quiet-paths`."
  [{:keys [jwks api oidc sessions access-verifier id-verifier] :as options}]
  (ring-handler (routes/routes {:jwks jwks :api api :oidc oidc :sessions sessions
                                :access-verifier access-verifier :id-verifier id-verifier})
                options))
