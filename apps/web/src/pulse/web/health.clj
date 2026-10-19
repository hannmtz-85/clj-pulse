(ns pulse.web.health
  "Liveness and readiness of the portal. Liveness says the process is serving; readiness says the things it needs answer:
  the identity provider's signing keys, without which nobody can be signed in, and the api, which every page of a
  patient is read from. The answers are JSON, for the platform that asks, and not pages."
  (:require [jsonista.core :as json]
            [pulse.platform.http.client :as http]
            [pulse.platform.http.health :as health]
            [pulse.platform.jwt :as jwt]))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string body)})

(defn liveness
  "The handler of `/healthz`."
  [_request]
  (json-response 200 {:status "up"}))

(defn checks
  "The readiness checks, a map of name to a function of no arguments. The api is asked for its own readiness, which is a
  public endpoint, so the portal's client of it asks with no token."
  [{:keys [jwks api]}]
  {:keycloak #(jwt/keys-available? jwks)
   :api #(= 200 (:status (http/request api {:method :get :path "/readyz"})))})

(defn readiness
  "The handler of `/readyz`: each dependency is asked, all of them under one deadline."
  [deps]
  (let [checks (checks deps)]
    (fn [_request]
      (let [{:keys [status body]} (health/readiness-response (health/run-checks checks {}))]
        (json-response status body)))))
