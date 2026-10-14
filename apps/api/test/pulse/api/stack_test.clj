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

(defn- with-running-api [f]
  (let [cfg (-> (config/load-config {:profile :dev})
                (assoc-in [:pulse.api/server :port] 0)
                (assoc-in [:pulse.api/logging :level] :warn))
        running (system/start cfg)]
    (try (f (str "http://127.0.0.1:" (system/port running)))
         (finally (system/stop running)))))

(deftest ^:integration the-patient-endpoints-against-the-real-stack
  (with-running-api
    (fn [base-url]
      (let [token-of (memoize token-for)
            panel (fn [who & [query]]
                    (get-json (str base-url "/api/patients" (when query (str "?" query))) :token (token-of who)))
            ids (fn [response] (set (map :id (get-in response [:json :items]))))
            patient (fn [who id]
                      (get-json (str base-url "/api/patients/" id) :token (token-of who)))]
        (testing "each role gets the panel Annex A gives it, from HAPI"
          (is (= #{"pt-ana" "pt-liam" "pt-sofia" "pt-omar"} (ids (panel "dr.rivera@pulse.test"))))
          (is (= #{"pt-maya" "pt-noah"} (ids (panel "dr.okafor@pulse.test"))))
          (is (every? (ids (panel "admin@pulse.test")) ["pt-ana" "pt-liam" "pt-sofia" "pt-omar" "pt-maya" "pt-noah"]))
          (is (= 403 (:status (panel "ana.garcia@pulse.test")))))
        (testing "the rules engine sees identifiers only"
          (let [r (panel "scheduler")]
            (is (= 200 (:status r)))
            (is (every? #(= #{:id :identifier} (set (keys %))) (get-in r [:json :items])))))
        (testing "filters, sorting and paging reach HAPI and come back right"
          (is (= #{"pt-ana"} (ids (panel "dr.rivera@pulse.test" "family=garcia"))) "HAPI ignores accents")
          (is (= ["pt-liam" "pt-ana"]
                 (mapv :id (get-in (panel "dr.rivera@pulse.test" "limit=2") [:json :items]))))
          (is (= 4 (get-in (panel "dr.rivera@pulse.test" "limit=1") [:json :total]))))
        (testing "one patient: own, panel, and everyone for the administrator"
          (is (= 200 (:status (patient "ana.garcia@pulse.test" "pt-ana"))))
          (is (= "Patient" (get-in (patient "ana.garcia@pulse.test" "pt-ana") [:json :resourceType])))
          (is (= 403 (:status (patient "ana.garcia@pulse.test" "pt-liam"))))
          (is (= 200 (:status (patient "dr.rivera@pulse.test" "pt-ana"))))
          (is (= 403 (:status (patient "dr.rivera@pulse.test" "pt-maya"))))
          (is (= 200 (:status (patient "admin@pulse.test" "pt-maya"))))
          (is (= 403 (:status (patient "scheduler" "pt-ana")))))
        (testing "a patient that does not exist"
          (is (= 404 (:status (patient "dr.rivera@pulse.test" "pt-ghost"))))
          (is (= 404 (:status (patient "admin@pulse.test" "pt-ghost"))))
          (is (= 403 (:status (patient "ana.garcia@pulse.test" "pt-ghost")))))))))

(deftest ^:integration the-patient-summary-against-the-real-stack
  (with-running-api
    (fn [base-url]
      (let [summary (fn [who id] (get-json (str base-url "/api/patients/" id "/summary") :token (token-for who)))
            started (System/nanoTime)
            ana (summary "dr.rivera@pulse.test" "pt-ana")
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)
            body (:json ana)]
        (testing "Ana, read by her doctor: everything arrives, from about a dozen queries to HAPI"
          (is (= 200 (:status ana)))
          (is (= [] (:unavailable body)))
          (is (= "pt-ana" (get-in body [:patient :id])))
          (is (= "I10" (get-in body [:conditions 0 :code])))
          (is (= ["amlodipine" "lisinopril"] (mapv :display (:medications body))))
          (is (= [] (:allergies body)))
          (is (= "very-high" (get-in body [:vitals :blood-pressure :band])))
          (is (= "/min" (get-in body [:vitals :heart-rate :unit])))
          (is (= "Hypertension follow-up" (get-in body [:appointments 0 :description])))
          (is (= "Practitioner/pr-rivera" (get-in body [:appointments 0 :practitioner])))
          (is (< elapsed-ms 4000) (str "took " (long elapsed-ms) " ms, under the deadline")))
        (testing "Sofia, read by the administrator: an allergy, a requested appointment, low oxygen"
          (let [sofia (:json (summary "admin@pulse.test" "pt-sofia"))]
            (is (= [] (:unavailable sofia)))
            (is (= "penicillin G" (get-in sofia [:allergies 0 :display])))
            (is (= "proposed" (get-in sofia [:appointments 0 :status])))
            (is (contains? #{"low" "very-low"} (get-in sofia [:vitals :spo2 :band])))))
        (testing "Noah, who has no measurements: nothing to show, nothing missing"
          (let [noah (:json (summary "dr.okafor@pulse.test" "pt-noah"))]
            (is (= [] (:unavailable noah)))
            (is (every? nil? (vals (:vitals noah))))))
        (testing "who may read it"
          (is (= 200 (:status (summary "ana.garcia@pulse.test" "pt-ana"))))
          (is (= 403 (:status (summary "ana.garcia@pulse.test" "pt-liam"))))
          (is (= 403 (:status (summary "dr.rivera@pulse.test" "pt-maya"))))
          (is (= 403 (:status (summary "scheduler" "pt-ana"))))
          (is (= 404 (:status (summary "dr.rivera@pulse.test" "pt-ghost")))))))))

(deftest ^:integration the-api-against-the-real-stack
  (with-running-api
    (fn [base-url]
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
          (is (= "The access token signature is invalid" (:detail json))))))))
