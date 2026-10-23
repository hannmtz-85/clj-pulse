(ns pulse.web.routes
  "The portal's routes, as data: path, method, who may see it (`:authz`, see `pulse.web.authz`) and the handler
  (ADR-0028). What there is is what any service has, the files the portal serves itself, the sign-in and the sign-out
  (`pulse.web.signin`), and the pages of a patient, which come with the steps that make them: the home and the readings so far."
  (:require [pulse.web.assets :as assets]
            [pulse.web.health :as health]
            [pulse.web.home :as home]
            [pulse.web.pages :as pages]
            [pulse.web.readings :as readings]
            [pulse.web.signin :as signin]
            [ring.middleware.not-modified :as not-modified]))

(defn routes
  "The route table. `deps` is `{:jwks :api :oidc :sessions :access-verifier :id-verifier :clock}`: the key cache of the identity
  provider and the client of the api, which readiness asks about and the pages read from, what the sign-in is made of, and the
  clock the pages tell the time by (optional: the system's)."
  [deps]
  [["/" {:get {:authz :patient
               :handler (home/handler deps)}}]
   [pages/readings-path {:get {:authz :patient
                               :handler (readings/trend deps)}}]
   [pages/new-reading-path {:get {:authz :patient
                                  :handler (readings/form deps)}
                            :post {:authz :patient
                                   :handler (readings/record deps)}}]
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
