(ns pulse.api.stack-test
  "Integration test: the api, started as `bb dev` starts it (the `dev` profile, on any free
  port), against the real Keycloak and HAPI of the local stack. Tokens come from `bb token`,
  the headless authorization-code flow with PKCE that the SPA uses.

  Needs the stack and the demo cohort (`bb up`, `bb seed`) and runs with `bb test:it`. It only
  reads."
  (:require [babashka.http-client :as http]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.api.config :as config]
            [pulse.api.system :as system]))

(defn- token-for [who]
  (let [{:keys [exit out err]} (shell/sh "bb" "token" who)]
    (when-not (zero? exit)
      (throw (ex-info "`bb token` failed: is the stack up (`bb up`)?" {:who who :err err})))
    (str/trim out)))

(defn- get-json [url & {:keys [token]}]
  (let [response (http/get url {:throw false
                                :headers (cond-> {"Accept" "application/json"}
                                           token (assoc "Authorization" (str "Bearer " token)))})]
    (assoc response :json (json/read-value (:body response) json/keyword-keys-object-mapper))))

(deftest ^:integration the-api-against-the-real-stack
  (let [cfg (-> (config/load-config {:profile :dev})
                (assoc-in [:pulse.api/server :port] 0)
                (assoc-in [:pulse.api/logging :level] :warn))
        running (system/start cfg)
        base-url (str "http://127.0.0.1:" (system/port running))]
    (try
      (testing "Keycloak's keys and HAPI both answer"
        (is (= {:status "ready" :checks {:keycloak "up" :fhir "up"}}
               (:json (get-json (str base-url "/readyz"))))))
      (testing "each demo role is identified, and gets the FHIR resource that is them"
        (doseq [[who role fhir-user client]
                [["ana.garcia@pulse.test" "patient" "Patient/pt-ana" "pulse-spa"]
                 ["dr.rivera@pulse.test" "practitioner" "Practitioner/pr-rivera" "pulse-spa"]
                 ["admin@pulse.test" "clinic-admin" nil "pulse-spa"]
                 ["scheduler" "rules-engine" nil "pulse-scheduler"]]]
          (testing who
            (let [{:keys [status json]} (get-json (str base-url "/api/me") :token (token-for who))]
              (is (= 200 status))
              (is (= [role] (:roles json)))
              (is (= client (:client json)))
              (is (= fhir-user (:fhirUser json)))
              (if fhir-user
                (let [[type id] (str/split fhir-user #"/")]
                  (is (= type (get-in json [:resource :resourceType])))
                  (is (= id (get-in json [:resource :id]))))
                (is (nil? (:resource json))))))))
      (testing "a token with one character of its signature changed is refused"
        (let [token (token-for "ana.garcia@pulse.test")
              i (- (count token) 5)
              flipped (str (subs token 0 i) (if (= \A (nth token i)) "B" "A") (subs token (inc i)))
              {:keys [status headers json]} (get-json (str base-url "/api/me") :token flipped)]
          (is (= 401 status))
          (is (str/includes? (get headers "www-authenticate") "invalid_token"))
          (is (= "The access token signature is invalid" (:detail json)))))
      (finally
        (system/stop running)))))
