(ns pulse.web.routes
  "The portal's routes, as data: path, method, who may see it (`:authz`, see `pulse.web.authz`) and the handler
  (ADR-0028). The pages of a patient come with the steps that make them; what there is now is what any service has, and
  the files the portal serves itself."
  (:require [pulse.web.assets :as assets]
            [pulse.web.health :as health]
            [ring.middleware.not-modified :as not-modified]))

(defn routes
  "The route table. `deps` is `{:jwks j :api a}`: the key cache of the identity provider and the client of the api, which
  readiness asks about."
  [deps]
  [["/healthz" {:get {:authz :public
                      :handler health/liveness}}]
   ["/readyz" {:get {:authz :public
                     :handler (health/readiness deps)}}]
   ;; the files of the portal: the stylesheet, the font, the icon and htmx. `*path` is the name of the file.
   ["/assets/*path" {:authz :public
                     :middleware [not-modified/wrap-not-modified]
                     :get {:handler assets/handler}
                     :head {:handler assets/handler}}]])
