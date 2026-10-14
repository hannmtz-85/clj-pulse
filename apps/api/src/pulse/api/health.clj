(ns pulse.api.health
  "Liveness and readiness. Liveness says the process is serving; readiness says the things it
  needs answer: the identity provider's signing keys, without which no token can be checked,
  and the FHIR server."
  (:require [pulse.fhir.client :as fc]
            [pulse.platform.http.health :as health]
            [pulse.platform.jwt :as jwt]))

(defn liveness []
  (fn [_request]
    {:status 200 :body {:status "up"}}))

(defn checks
  "The readiness checks, a map of name to a function of no arguments."
  [{:keys [verifier fhir]}]
  {:keycloak #(jwt/keys-available? (:jwks-source verifier))
   ;; Asking for one Patient exercises the whole path to the database behind the server.
   :fhir #(do (doall (take 1 (fc/search fhir "Patient" {:_count 1})))
              true)})

(defn readiness [deps]
  (let [checks (checks deps)]
    (fn [_request]
      (health/readiness-response (health/run-checks checks {})))))
