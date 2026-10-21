(ns pulse.web.routes
  "The portal's routes, as data: path, method, who may see it (`:authz`, see `pulse.web.authz`) and the handler
  (ADR-0028). What there is is what any service has, the files the portal serves itself, and the sign-in and the sign-out
  (`pulse.web.signin`); the pages of a patient come with the steps that make them."
  (:require [pulse.web.assets :as assets]
            [pulse.web.health :as health]
            [pulse.web.signin :as signin]
            [ring.middleware.not-modified :as not-modified]))

(defn routes
  "The route table. `deps` is `{:jwks :api :oidc :sessions :access-verifier :id-verifier}`: the key cache of the identity
  provider and the client of the api, which readiness asks about, and what the sign-in is made of."
  [deps]
  [;; until the home of the portal is made, the page of a person who is signed in says so, and has the way out
   ["/" {:get {:authz :patient
               :handler signin/home}}]
   ["/sign-in" {:get {:authz :public
                      :handler (signin/sign-in deps)}}]
   ["/oauth2/callback" {:get {:authz :public
                              :handler (signin/callback deps)}}]
   ["/sign-out" {:post {:authz :patient
                        :handler (signin/sign-out deps)}}]
   ["/signed-out" {:get {:authz :public
                         :handler signin/signed-out}}]
   ["/healthz" {:get {:authz :public
                      :handler health/liveness}}]
   ["/readyz" {:get {:authz :public
                     :handler (health/readiness deps)}}]
   ;; the files of the portal: the stylesheet, the font, the icon and htmx. `*path` is the name of the file.
   ["/assets/*path" {:authz :public
                     :middleware [not-modified/wrap-not-modified]
                     :get {:handler assets/handler}
                     :head {:handler assets/handler}}]])
