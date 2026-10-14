(ns pulse.api.stack-test
  "Integration test: the api, started as `bb dev` starts it (the `dev` profile, on any free
  port), against the real Keycloak and HAPI of the local stack. Tokens come from `bb token`,
  the headless authorization-code flow with PKCE that the SPA uses.

  Needs the stack and the demo cohort (`bb up`, `bb seed`) and runs with `bb test:it`. It only
  reads, except `the-observations-...`, `the-appointments-...` and `the-alerts-against-the-real-stack`,
  which write two readings, an appointment and three alerts in HAPI and delete them before they
  end, whatever happens."
  (:require [babashka.http-client :as http]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.api.config :as config]
            [pulse.api.system :as system]
            [pulse.fhir.client :as fc]
            [pulse.fhir.client.http :as fhir-http]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.outcome :as outcome]
            [pulse.fhir.resource :as r])
  (:import (java.time Duration Instant)
           (java.time.temporal ChronoUnit)))

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

(defn- write-json
  "`send!` (`http/post` or `http/patch`) the JSON of `body` to `url`."
  [send! url body & {:keys [token]}]
  (let [response (send! url {:throw false
                             :headers (cond-> {"Accept" "application/json" "Content-Type" "application/json"}
                                        token (assoc "Authorization" (str "Bearer " token)))
                             :body (json/write-value-as-string body)})]
    (assoc response :json (json/read-value (:body response) json/keyword-keys-object-mapper))))

(defn- post-json [url body & opts] (apply write-json http/post url body opts))

(defn- post-nothing
  "POST to `url` with no body, as the acknowledgement of an alert is."
  [url & {:keys [token]}]
  (let [response (http/post url {:throw false
                                 :headers (cond-> {"Accept" "application/json"}
                                            token (assoc "Authorization" (str "Bearer " token)))})]
    (assoc response :json (json/read-value (:body response) json/keyword-keys-object-mapper))))
(defn- patch-json [url body & opts] (apply write-json http/patch url body opts))

(defn- in-days
  "The instant that is `days` and `minutes` from now, as the api takes it."
  [days & [minutes]]
  (str (.plus (.truncatedTo (Instant/now) ChronoUnit/SECONDS)
              (Duration/ofMinutes (+ (* days 24 60) (or minutes 0))))))

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

(deftest ^:integration the-observations-against-the-real-stack
  (let [hapi (fhir-http/client {:base-url (get-in (config/load-config {:profile :dev}) [:pulse.api/fhir :base-url])})
        recorded (atom [])]
    (try
      (with-running-api
        (fn [base-url]
          (let [token-of (memoize token-for)
                series (fn [who id query]
                         (get-json (str base-url "/api/patients/" id "/observations?" query) :token (token-of who)))
                record! (fn [who id body]
                          (let [response (post-json (str base-url "/api/patients/" id "/observations") body
                                                    :token (token-of who))]
                            (when-let [new-id (get-in response [:json :id])]
                              (swap! recorded conj new-id))
                            response))
                items (fn [response] (get-in response [:json :items]))]
            (testing "a series of the demo readings, read from HAPI"
              (let [r (series "dr.rivera@pulse.test" "pt-ana" "measurement=blood-pressure&limit=500")
                    ats (mapv :at (items r))]
                (is (= 200 (:status r)))
                (is (seq ats))
                (is (= (vec (reverse (sort ats))) ats) "newest first")
                (is (every? #(= "mm[Hg]" (:unit %)) (items r)))
                (is (every? #(= "Patient/pt-ana" (:by %)) (items r)))
                (is (= false (get-in r [:json :truncated])))))
            (testing "a period and a limit reach HAPI and come back right"
              (let [all (items (series "dr.rivera@pulse.test" "pt-ana" "measurement=heart-rate&limit=500"))
                    ids (mapv :id all)
                    at (fn [i] (:at (nth all i)))
                    period (series "dr.rivera@pulse.test" "pt-ana"
                                   (str "measurement=heart-rate&limit=500&from=" (at 4) "&to=" (at 1)))
                    cut (series "dr.rivera@pulse.test" "pt-ana" "measurement=heart-rate&limit=3")]
                (is (= (subvec ids 2 5) (mapv :id (items period))) "from is inclusive and to exclusive")
                (is (= (subvec ids 0 3) (mapv :id (items cut))))
                (is (= true (get-in cut [:json :truncated])))))
            (testing "a patient with no readings has an empty series"
              (is (= {:items [] :truncated false}
                     (:json (series "dr.okafor@pulse.test" "pt-noah" "measurement=heart-rate")))))
            (testing "who may read a series"
              (is (= 403 (:status (series "ana.garcia@pulse.test" "pt-liam" "measurement=heart-rate"))))
              (is (= 403 (:status (series "dr.rivera@pulse.test" "pt-maya" "measurement=heart-rate"))))
              (is (= 200 (:status (series "admin@pulse.test" "pt-maya" "measurement=weight"))))
              (is (= 200 (:status (series "scheduler" "pt-maya" "measurement=weight"))))
              (is (= 404 (:status (series "dr.rivera@pulse.test" "pt-ghost" "measurement=heart-rate")))))
            (testing "a patient records a reading for themselves: converted, stored in HAPI and valid there"
              (let [r (record! "ana.garcia@pulse.test" "pt-ana" {:measurement "glucose" :value 6.1 :unit "mmol/L"})
                    id (get-in r [:json :id])
                    stored (when id (fc/read-resource hapi "Observation" id))]
                (is (= 201 (:status r)))
                (is (= {:measurement "glucose" :value 110 :unit "mg/dL" :band "normal" :by "Patient/pt-ana"}
                       (select-keys (:json r) [:measurement :value :unit :band :by])))
                (is (some? stored))
                (is (= {:kind :quantity :value 110 :unit "mg/dL"} (obs/value stored)))
                (is (= [{:reference "Patient/pt-ana"}] (:performer stored)))
                (let [validation (fc/validate hapi stored)]
                  (is (not (outcome/error? validation)) (outcome/summary validation)))
                (is (= id (:id (first (items (series "dr.rivera@pulse.test" "pt-ana" "measurement=glucose&limit=1")))))
                    "and it is the newest glucose reading there is")))
            (testing "a clinician records a pressure in kPa for a patient of their panel"
              (let [r (record! "dr.rivera@pulse.test" "pt-ana" {:measurement "blood-pressure" :systolic 20 :diastolic 12 :unit "kPa"})
                    id (get-in r [:json :id])
                    stored (when id (fc/read-resource hapi "Observation" id))
                    summary (get-json (str base-url "/api/patients/pt-ana/summary") :token (token-of "dr.rivera@pulse.test"))]
                (is (= 201 (:status r)))
                (is (= {:systolic 150 :diastolic 90 :unit "mm[Hg]" :band "high" :by "Practitioner/pr-rivera"}
                       (select-keys (:json r) [:systolic :diastolic :unit :band :by])))
                (is (= [{:reference "Practitioner/pr-rivera"}] (:performer stored)))
                (let [validation (fc/validate hapi stored)]
                  (is (not (outcome/error? validation)) (outcome/summary validation)))
                (is (= id (get-in summary [:json :vitals :blood-pressure :id])) "the newest pressure of the summary")))
            (testing "what may not be recorded is refused and writes nothing"
              (let [before (count @recorded)]
                (is (= 403 (:status (record! "ana.garcia@pulse.test" "pt-liam" {:measurement "heart-rate" :value 70}))))
                (is (= 403 (:status (record! "admin@pulse.test" "pt-ana" {:measurement "heart-rate" :value 70}))))
                (is (= 404 (:status (record! "scheduler" "pt-ghost" {:measurement "heart-rate" :value 70}))))
                (let [r (record! "ana.garcia@pulse.test" "pt-ana" {:measurement "temperature" :value 98.6 :unit "Cel"})]
                  (is (= 422 (:status r)))
                  (is (contains? (get-in r [:json :errors]) :value)))
                (is (= before (count @recorded)) "nothing was created"))))))
      (finally
        (doseq [id @recorded]
          (fc/delete! hapi "Observation" id))))))

(deftest ^:integration the-appointments-against-the-real-stack
  (let [hapi (fhir-http/client {:base-url (get-in (config/load-config {:profile :dev}) [:pulse.api/fhir :base-url])})
        created (atom [])]
    (try
      (with-running-api
        (fn [base-url]
          (let [token-of (memoize token-for)
                url (str base-url "/api/appointments")
                list! (fn [who & [query]] (get-json (str url (when query (str "?" query))) :token (token-of who)))
                request! (fn [who body]
                           (let [response (post-json url body :token (token-of who))]
                             (when-let [new-id (get-in response [:json :id])]
                               (swap! created conj new-id))
                             response))
                change! (fn [who id body] (patch-json (str url "/" id) body :token (token-of who)))
                ids (fn [response] (set (map :id (get-in response [:json :items]))))]
            (testing "each role lists what it should, from HAPI"
              (is (= #{"appt-ana-1"} (ids (list! "ana.garcia@pulse.test"))))
              (is (= #{"appt-ana-1" "appt-liam-1" "appt-sofia-1"} (ids (list! "dr.rivera@pulse.test"))))
              (is (= #{"appt-maya-1" "appt-noah-1"} (ids (list! "dr.okafor@pulse.test"))))
              (is (every? (ids (list! "admin@pulse.test" "limit=100"))
                          ["appt-ana-1" "appt-liam-1" "appt-sofia-1" "appt-maya-1" "appt-noah-1"]))
              (is (= 403 (:status (list! "scheduler")))))
            (testing "the status filter and the sorting reach HAPI"
              (is (= #{"appt-sofia-1"} (ids (list! "dr.rivera@pulse.test" "status=proposed"))))
              (is (= ["appt-ana-1" "appt-liam-1"]
                     (mapv :id (get-in (list! "dr.rivera@pulse.test" "status=booked") [:json :items])))
                  "the soonest first")
              (is (= ["appt-liam-1" "appt-ana-1"]
                     (mapv :id (get-in (list! "dr.rivera@pulse.test" "status=booked&sort=-start") [:json :items])))))
            (testing "a patient asks, a doctor confirms, moves and cancels: HAPI keeps it all, valid there"
              (let [asked (request! "ana.garcia@pulse.test" {:description "Integration test: blood pressure review"
                                                             :requestedStart (in-days 1)
                                                             :requestedEnd (in-days 6)})
                    id (get-in asked [:json :id])
                    stored (fn [] (fc/read-resource hapi "Appointment" id))
                    valid-on-hapi (fn []
                                    (let [validation (fc/validate hapi (stored))]
                                      (is (not (outcome/error? validation)) (outcome/summary validation))))]
                (is (= 201 (:status asked)))
                (is (= {:status "proposed" :practitioner "Practitioner/pr-rivera" :patient "Patient/pt-ana" :start nil}
                       (select-keys (:json asked) [:status :practitioner :patient :start])))
                (is (= ["Patient/pt-ana" "Practitioner/pr-rivera"]
                       (map #(get-in % [:actor :reference]) (:participant (stored)))))
                (valid-on-hapi)
                (testing "the doctor sees it, and the other doctor does not"
                  (is (contains? (ids (list! "dr.rivera@pulse.test" "status=proposed")) id))
                  (is (not (contains? (ids (list! "dr.okafor@pulse.test")) id))))
                (testing "the other doctor may not touch it"
                  (is (= 403 (:status (change! "dr.okafor@pulse.test" id {:action "cancel"})))))
                (testing "confirm"
                  (let [r (change! "dr.rivera@pulse.test" id {:action "confirm" :start (in-days 2) :end (in-days 2 30)})]
                    (is (= 200 (:status r)))
                    (is (= "booked" (get-in r [:json :status])))
                    (is (= "booked" (:status (stored))))
                    (is (= 30 (:minutesDuration (stored))))
                    (is (= ["accepted" "accepted"] (map :status (:participant (stored)))))
                    (valid-on-hapi)))
                (testing "reschedule, by the administrator"
                  (let [r (change! "admin@pulse.test" id {:action "reschedule" :start (in-days 3) :end (in-days 3 45)})]
                    (is (= 200 (:status r)))
                    (is (= 45 (:minutesDuration (stored))))
                    (valid-on-hapi)))
                (testing "the patient sees it booked"
                  (is (= ["booked"] (->> (get-in (list! "ana.garcia@pulse.test") [:json :items])
                                         (filter #(= id (:id %)))
                                         (map :status)))))
                (testing "cancel, with a reason"
                  (let [r (change! "dr.rivera@pulse.test" id {:action "cancel" :reason "Integration test"})]
                    (is (= 200 (:status r)))
                    (is (= {:text "Integration test"} (:cancelationReason (stored))))
                    (valid-on-hapi)))
                (testing "a cancelled appointment is final"
                  (is (= 409 (:status (change! "dr.rivera@pulse.test" id
                                               {:action "confirm" :start (in-days 4) :end (in-days 4 30)})))))
                (testing "the patient's summary says so, with the reason"
                  (let [summary (get-json (str base-url "/api/patients/pt-ana/summary")
                                          :token (token-of "ana.garcia@pulse.test"))
                        mine (first (filter #(= id (:id %)) (get-in summary [:json :appointments])))]
                    (is (= "cancelled" (:status mine)))
                    (is (= "Integration test" (:reason mine)))))))
            (testing "what may not be asked is refused and creates nothing"
              (let [before (count @created)]
                (is (= 403 (:status (request! "dr.rivera@pulse.test"
                                              {:description "x" :requestedStart (in-days 1) :requestedEnd (in-days 6)}))))
                (let [r (request! "ana.garcia@pulse.test" {:description "" :requestedStart (in-days 6) :requestedEnd (in-days 1)})]
                  (is (= 422 (:status r)))
                  (is (= #{:description :requestedEnd} (set (keys (get-in r [:json :errors]))))))
                (is (= 404 (:status (change! "dr.rivera@pulse.test" "appt-ghost" {:action "cancel"}))))
                (is (= before (count @created)) "nothing was created"))))))
      (finally
        (doseq [id @created]
          (fc/delete! hapi "Appointment" id))))))

(deftest ^:integration the-alerts-against-the-real-stack
  ;; The cohort has no alerts (the scheduler raises them), so the test raises three in HAPI and
  ;; removes them at the end. Only those whose id starts with `it-flag-` are looked at, so that
  ;; alerts raised by anything else do not matter.
  (let [hapi (fhir-http/client {:base-url (get-in (config/load-config {:profile :dev}) [:pulse.api/fhir :base-url])})
        raised (atom [])
        raise! (fn [id patient text minutes-ago]
                 (fc/put! hapi (r/flag {:id id :patient patient :text text :start (in-days 0 (- minutes-ago))}))
                 (swap! raised conj id))
        ours (fn [ids] (filterv #(str/starts-with? % "it-flag-") ids))]
    (try
      (with-running-api
        (fn [base-url]
          (let [token-of (memoize token-for)
                list! (fn [who & [query]]
                        (get-json (str base-url "/api/alerts" (when query (str "?" query))) :token (token-of who)))
                ack! (fn [who id] (post-nothing (str base-url "/api/alerts/" id "/ack") :token (token-of who)))
                ids (fn [response] (mapv :id (get-in response [:json :items])))]
            (raise! "it-flag-ana" "pt-ana" "Integration alert (Ana)" 30)
            (raise! "it-flag-liam" "pt-liam" "Integration alert (Liam)" 20)
            (raise! "it-flag-maya" "pt-maya" "Integration alert (Maya)" 10)
            (testing "each role sees the alerts of its panel, the newest first, from HAPI"
              (is (= ["it-flag-liam" "it-flag-ana"] (ours (ids (list! "dr.rivera@pulse.test")))))
              (is (= ["it-flag-maya"] (ours (ids (list! "dr.okafor@pulse.test")))))
              (is (= ["it-flag-maya" "it-flag-liam" "it-flag-ana"] (ours (ids (list! "admin@pulse.test" "limit=100")))))
              (is (= ["it-flag-ana" "it-flag-liam"] (ours (ids (list! "dr.rivera@pulse.test" "sort=since"))))
                  "oldest first, when asked")
              (is (= 403 (:status (list! "ana.garcia@pulse.test"))))
              (is (= 403 (:status (list! "scheduler")))))
            (testing "a practitioner acknowledges an alert of their panel: it is valid on HAPI"
              (let [r (ack! "dr.rivera@pulse.test" "it-flag-ana")
                    stored (fc/read-resource hapi "Flag" "it-flag-ana")
                    validation (fc/validate hapi stored)]
                (is (= 200 (:status r)))
                (is (= "inactive" (get-in r [:json :status])))
                (is (string? (get-in r [:json :until])))
                (is (= "inactive" (:status stored)))
                (is (= (get-in r [:json :until]) (get-in stored [:period :end])))
                (is (not (outcome/error? validation)) (outcome/summary validation))))
            (testing "the other doctor may not, and a second acknowledgement is a 409"
              (is (= 403 (:status (ack! "dr.okafor@pulse.test" "it-flag-liam"))))
              (is (= 409 (:status (ack! "dr.rivera@pulse.test" "it-flag-ana")))))
            (testing "the inbox no longer has it, and the acknowledged ones are listed when asked for"
              (is (= ["it-flag-liam"] (ours (ids (list! "dr.rivera@pulse.test")))))
              (is (= ["it-flag-ana"] (ours (ids (list! "dr.rivera@pulse.test" "status=inactive"))))))
            (testing "and the patient's summary does not show it either"
              (let [summary (get-json (str base-url "/api/patients/pt-ana/summary") :token (token-of "ana.garcia@pulse.test"))]
                (is (not-any? #(= "it-flag-ana" (:id %)) (get-in summary [:json :alerts])))))
            (testing "an alert that does not exist"
              (is (= 404 (:status (ack! "dr.rivera@pulse.test" "it-flag-ghost"))))))))
      (finally
        (doseq [id @raised]
          (fc/delete! hapi "Flag" id))))))

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
