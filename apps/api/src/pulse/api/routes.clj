(ns pulse.api.routes
  "The api's routes, as data: path, method, who may call it (`:authz`, see `pulse.api.authz`),
  the shape of what it returns, and the handler. Reitit reads this one structure for routing,
  authorization and response checking, so the three cannot drift apart. Handlers are built
  from `deps`, the started components they need."
  (:require [pulse.api.health :as health]
            [pulse.api.me :as me]))

(def ^:private readiness-schema
  [:map
   [:status [:enum "ready" "unready"]]
   [:checks [:map-of :string [:enum "up" "down"]]]])

(defn routes
  "The route table. `deps` is `{:verifier v :fhir c}`."
  [deps]
  [["/healthz" {:get {:authz :public
                      :summary "Liveness: the process is up and serving"
                      :responses {200 {:body [:map [:status [:= "up"]]]}}
                      :handler (health/liveness)}}]
   ["/readyz" {:get {:authz :public
                     :summary "Readiness: the identity provider's keys and the FHIR server answer"
                     :responses {200 {:body readiness-schema}
                                 503 {:body readiness-schema}}
                     :handler (health/readiness deps)}}]
   ["/api"
    ["/me" {:get {:authz {:action :identity/read}
                  :summary "The caller: identity, roles and the FHIR resource that is them"
                  :responses {200 {:body me/schema}}
                  :handler (me/handler deps)}}]]])
