(ns pulse.dev-test
  "The api, the scheduler and the patient portal as one system (ADR-0023): the configuration that `bb dev`
  starts, and the acceptance of Phase 5 as a test. The simulated devices send their readings to the FHIR server, the
  rules engine reads them through the api and raises an alert, and the practitioner finds it in the
  inbox of the api, all in one JVM and over HTTP between the two."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [pulse.api.test.harness :as h]
            [pulse.dev :as dev]
            [pulse.fhir.client :as c]
            [pulse.platform.config :as platform]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.token :as token]
            [pulse.scheduler.executor :as executor]
            [pulse.web.session :as session]
            [ring.middleware.session.store :as store])
  (:import (java.io File)))

;; ---------------------------------------------------------------------------
;; .env

(defn- with-dotenv
  "Calls `(f path)` with the path of a file holding `text`."
  [text f]
  (let [file (File/createTempFile "pulse-dev-test" ".env")]
    (try (spit file text)
         (f (.getPath file))
         (finally (.delete file)))))

(deftest a-dotenv-file-gives-name-value-pairs
  (with-dotenv (str "# a comment\n"
                    "\n"
                    "PLAIN=value\n"
                    "export EXPORTED=another\n"
                    "SPACED = with spaces around \n"
                    "DOUBLE=\"in double quotes\"\n"
                    "SINGLE='in single quotes'\n"
                    "EQUALS=a=b=c\n"
                    "HASH=not#a#comment\n"
                    "  # an indented comment\n"
                    "# COMMENTED=out\n"
                    "  # INDENTED=out\n"
                    "EMPTY=\n"
                    "not a variable\n")
    (fn [path]
      (is (= {"PLAIN" "value"
              "EXPORTED" "another"
              "SPACED" "with spaces around"
              "DOUBLE" "in double quotes"
              "SINGLE" "in single quotes"
              "EQUALS" "a=b=c"
              "HASH" "not#a#comment"
              "EMPTY" ""}
             (dev/read-dotenv path))))))

(deftest no-file-is-no-variables
  (is (= {} (dev/read-dotenv "there-is-no-such-file.env")))
  (is (= {} (dev/environment "there-is-no-such-file.env" {}))))

(deftest the-environment-of-the-process-wins-over-dotenv
  (with-dotenv "FROM_BOTH=from-dotenv\nONLY_IN_DOTENV=yes\n"
    (fn [path]
      (is (= {"ONLY_IN_DOTENV" "yes"} (dev/environment path {"FROM_BOTH" "from-the-process"})))
      (is (= {"FROM_BOTH" "from-dotenv" "ONLY_IN_DOTENV" "yes"} (dev/environment path {}))))))

(deftest the-process-is-the-real-one-unless-it-is-told-otherwise
  (let [variable (first (filter #(re-matches #"[A-Za-z_][A-Za-z0-9_]*" %) (keys (System/getenv))))]
    (is (some? variable) "a variable of the process that a .env file could name")
    (with-dotenv (str variable "=from-dotenv\nONLY_IN_DOTENV=yes\n")
      (fn [path]
        (is (= {"ONLY_IN_DOTENV" "yes"} (dev/environment path)) (str variable " is set in this process"))))))

;; ---------------------------------------------------------------------------
;; The configuration

(defn- config
  "The configuration for `profile` with a `.env` that has `text`, in a process that has no variables."
  [profile text]
  (with-dotenv text
    (fn [path] (dev/config {:profile profile :dotenv path :process-env {}}))))

(def ^:private both-secrets
  "The `.env` of a machine where `bb up` has been run: a secret for each of the three apps."
  (str "SCHEDULER_CLIENT_SECRET=a-secret-from-dotenv\nAPI_CLIENT_SECRET=the-apis-secret-from-dotenv\n"
       "WEB_CLIENT_SECRET=the-portals-secret-from-dotenv\n"))

(deftest the-configuration-has-the-components-of-the-three-apps
  (let [cfg (config :dev both-secrets)]
    (is (every? #(contains? cfg %) [:pulse.api/server :pulse.api/handler :pulse.api/fhir
                                    :pulse.api/token :pulse.api/scheduler
                                    :pulse.scheduler/server :pulse.scheduler/executor :pulse.scheduler/jobs
                                    :pulse.scheduler/api :pulse.scheduler/token
                                    :pulse.web/server :pulse.web/handler :pulse.web/api :pulse.web/jwks
                                    :pulse.web/oidc :pulse.web/sessions :pulse.web/access-verifier :pulse.web/id-verifier]))
    (is (= 4000 (get-in cfg [:pulse.api/server :port])))
    (is (= 5000 (get-in cfg [:pulse.scheduler/server :port])))
    (is (= 3300 (get-in cfg [:pulse.web/server :port])))
    (testing "the secrets of the three come from the file, and are secrets"
      (is (= "a-secret-from-dotenv" (platform/reveal (get-in cfg [:pulse.scheduler/token :client-secret]))))
      (is (platform/secret? (get-in cfg [:pulse.scheduler/token :client-secret])))
      (is (= "the-apis-secret-from-dotenv" (platform/reveal (get-in cfg [:pulse.api/token :client-secret]))))
      (is (platform/secret? (get-in cfg [:pulse.api/token :client-secret])))
      (is (= "the-portals-secret-from-dotenv" (platform/reveal (get-in cfg [:pulse.web/oidc :client-secret]))))
      (is (platform/secret? (get-in cfg [:pulse.web/oidc :client-secret]))))
    (testing "each asks the other where the other is by default: the api's port, and the scheduler's"
      (is (= "http://localhost:4000" (get-in cfg [:pulse.scheduler/api :base-url])))
      (is (= "http://localhost:4000" (get-in cfg [:pulse.web/api :base-url])) "the portal asks the api too")
      (is (= "http://localhost:5000" (get-in cfg [:pulse.api/scheduler :base-url]))))
    (testing "the clients of the api, the scheduler's and the portal's, come after the api's server"
      (is (= (ig/ref :pulse.api/server) (get-in cfg [:pulse.scheduler/api :after])))
      (is (= (ig/ref :pulse.api/server) (get-in cfg [:pulse.web/api :after]))))
    (testing "each reaches HAPI by itself"
      (is (= :http (get-in cfg [:pulse.api/fhir :kind])))
      (is (= :http (get-in cfg [:pulse.scheduler/fhir :kind]))))
    (testing "the devices are simulated, the rules evaluated and the patients reminded"
      (is (true? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])))
      (is (true? (get-in cfg [:pulse.scheduler/jobs :evaluate-rules :enabled?])))
      (is (true? (get-in cfg [:pulse.scheduler/jobs :send-reminders :enabled?]))))))

(deftest a-server-in-memory-is-the-schedulers-too
  (doseq [profile [:lite :test]
          :let [cfg (config profile both-secrets)]]
    (testing profile
      (is (= :memory (get-in cfg [:pulse.api/fhir :kind])))
      (is (= (ig/ref :pulse.api/fhir) (:pulse.scheduler/fhir cfg))
          "an alert written to another server in memory would never be seen by the api"))))

(deftest the-scheduler-and-the-portal-use-their-test-profiles-with-the-apis
  (let [cfg (config :test "")]
    (is (= 0 (get-in cfg [:pulse.scheduler/server :port])))
    (is (= 0 (get-in cfg [:pulse.web/server :port])))
    (is (false? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])))))

(deftest the-simulator-can-be-switched-off-with-a-variable
  (let [cfg (config :dev (str both-secrets "SCHEDULER_SIMULATOR=false\n"))]
    (is (false? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])))
    (is (true? (get-in cfg [:pulse.scheduler/jobs :evaluate-rules :enabled?])) "and the rules go on")))

(deftest the-reminders-can-be-switched-off-with-a-variable
  (let [cfg (config :dev (str both-secrets "SCHEDULER_REMINDERS=false\n"))]
    (is (false? (get-in cfg [:pulse.scheduler/jobs :send-reminders :enabled?])))
    (is (true? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])) "and the devices go on")
    (is (true? (get-in cfg [:pulse.scheduler/jobs :evaluate-rules :enabled?])) "and so do the rules")))

(deftest what-a-test-says-is-read-before-dotenv
  (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" "from-the-test" "API_CLIENT_SECRET" "api-from-the-test"
                                    "WEB_CLIENT_SECRET" "web-from-the-test"}]
    (let [cfg (config :dev "SCHEDULER_CLIENT_SECRET=from-dotenv\nAPI_CLIENT_SECRET=api-from-dotenv\nWEB_CLIENT_SECRET=web-from-dotenv\n")]
      (is (= "from-the-test" (platform/reveal (get-in cfg [:pulse.scheduler/token :client-secret]))))
      (is (= "api-from-the-test" (platform/reveal (get-in cfg [:pulse.api/token :client-secret]))))
      (is (= "web-from-the-test" (platform/reveal (get-in cfg [:pulse.web/oidc :client-secret])))))))

(deftest without-the-secrets-the-apps-say-which-variable-to-set
  (testing "with neither, the api, which is read first, says its own"
    (let [e (try (config :dev "OTHER=1\n") (catch clojure.lang.ExceptionInfo e e))]
      (is (platform/invalid-config? e))
      (is (re-find #"API_CLIENT_SECRET" (ex-message e)))))
  (testing "with the api's and not the scheduler's, the scheduler's"
    (let [e (try (config :dev "API_CLIENT_SECRET=x\n") (catch clojure.lang.ExceptionInfo e e))]
      (is (platform/invalid-config? e))
      (is (re-find #"SCHEDULER_CLIENT_SECRET" (ex-message e)))))
  (testing "with the scheduler's and not the api's, the api's, and how to get it"
    (let [e (try (config :dev "SCHEDULER_CLIENT_SECRET=x\n") (catch clojure.lang.ExceptionInfo e e))]
      (is (platform/invalid-config? e))
      (is (re-find #"API_CLIENT_SECRET" (ex-message e)))
      (is (re-find #"bb env:init" (ex-message e)))))
  (testing "with those two and not the portal's, the portal's, and how to get it"
    (let [e (try (config :dev "SCHEDULER_CLIENT_SECRET=x\nAPI_CLIENT_SECRET=y\n") (catch clojure.lang.ExceptionInfo e e))]
      (is (platform/invalid-config? e))
      (is (re-find #"WEB_CLIENT_SECRET" (ex-message e)))
      (is (re-find #"bb env:init" (ex-message e))))))

;; ---------------------------------------------------------------------------
;; The acceptance of Phase 5: devices, alerts, inbox

(def ^:private quiet
  "A schedule that will not fire while the test runs: once a week, on the grid of the epoch."
  {:type :interval :every-ms 604800000})

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- json-text [x] (json/write-value-as-string x))

(def ^:private no-redirects
  "A client that does not follow a redirect: a browser's every step is a request of the test."
  (delay (http/client {:follow-redirects :never})))

(defn- with-dev
  "Calls `(f {:system s :api-url url :web-url url :idp idp})` with the api, the scheduler and the portal started as one
  system: the test profile, a fake identity provider that gives each of the first two the token of its own service
  account (the scheduler's is for the api, the api's for the scheduler), and the keys of which the portal asks,
  the three on ports that are known, the jobs switched on (and run by the test, not by a schedule that is quiet), and no
  readings in the FHIR server but those that the simulator sends."
  [f]
  (let [idp (idp/start! [h/signing-key])
        port (free-port)
        api-url (str "http://127.0.0.1:" port)
        scheduler-port (free-port)
        web-port (free-port)
        web-url (str "http://127.0.0.1:" web-port)
        cfg (-> (dev/config {:profile :test :dotenv "there-is-no-such-file.env" :process-env {}})
                (assoc-in [:pulse.api/jwks :uri] (:uri idp))
                (assoc-in [:pulse.api/verifier :issuer] h/issuer)
                (assoc-in [:pulse.api/server :port] port)
                (assoc-in [:pulse.api/token :token-uri] (:token-uri idp))
                (assoc-in [:pulse.api/scheduler :base-url] (str "http://127.0.0.1:" scheduler-port))
                (assoc-in [:pulse.scheduler/jwks :uri] (:uri idp))
                (assoc-in [:pulse.scheduler/verifier :issuer] h/issuer)
                (assoc-in [:pulse.scheduler/server :port] scheduler-port)
                (assoc-in [:pulse.scheduler/token :token-uri] (:token-uri idp))
                (assoc-in [:pulse.scheduler/api :base-url] api-url)
                (assoc-in [:pulse.web/jwks :uri] (:uri idp))
                (assoc-in [:pulse.web/access-verifier :issuer] h/issuer)
                (assoc-in [:pulse.web/id-verifier :issuer] h/issuer)
                (assoc-in [:pulse.web/server :port] web-port)
                (assoc-in [:pulse.web/api :base-url] api-url)
                (update :pulse.web/oidc assoc
                        :base-url web-url
                        :authorization-uri (oidc-fake/authorization-uri idp)
                        :end-session-uri (oidc-fake/end-session-uri idp)
                        :token-uri (:token-uri idp))
                (assoc-in [:pulse.scheduler/jobs :evaluate-rules :enabled?] true)
                (assoc-in [:pulse.scheduler/jobs :evaluate-rules :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :simulate-devices :enabled?] true)
                (assoc-in [:pulse.scheduler/jobs :simulate-devices :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :send-reminders :enabled?] true)
                (assoc-in [:pulse.scheduler/jobs :send-reminders :schedule] quiet))]
    ;; the one provider gives the two service accounts their tokens, and signs the portal's people in
    (oidc-fake/enable! idp {:key h/signing-key
                            :issuer h/issuer
                            :clients {"pulse-web" {:secret "test-secret"
                                                   :redirect-uris #{(str web-url "/oauth2/callback")}
                                                   :post-logout-redirect-uris [(str web-url "/*")]}}})
    (idp/serve-token! idp {:answer (fn [request]
                                     (when-let [service (case (idp/client-id-of request)
                                                          "pulse-scheduler" :scheduler
                                                          "pulse-api" :api-service
                                                          nil)]
                                       (json-text {:access_token (h/token-for service)
                                                   :expires_in 300 :token_type "Bearer"})))})
    (let [system (ig/init cfg)]
      (try
        (let [fhir (:pulse.api/fhir system)]
          ;; all of them first: a search is lazy, and deleting changes what the next page holds
          (doseq [o (doall (c/search fhir "Observation" {}))]
            (c/delete! fhir "Observation" (:id o))))
        (f {:system system :api-url api-url :web-url web-url :idp idp})
        (finally
          (ig/halt! system)
          (idp/stop! idp))))))

(defn- runs
  "How many times the job has run, whatever came of it."
  [{:keys [ok failed interrupted]}]
  (+ ok failed interrupted))

(defn- run-job!
  "Runs the job by hand on the executor of the scheduler, waits until it has finished and returns the
  summary it gave. The run must have gone well."
  [system id]
  (let [e (:pulse.scheduler/executor system)
        before (runs (:counts (executor/job-status e id)))]
    (is (= :started (executor/run-now! e id)))
    (let [deadline (+ (System/nanoTime) 10000000000)]
      (loop []
        (let [{:keys [state counts last-run]} (executor/job-status e id)]
          (cond
            (and (= :idle state) (> (runs counts) before))
            (do (is (= :ok (:outcome last-run)) (str id " " (pr-str last-run)))
                (:result last-run))

            (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
            :else (throw (ex-info "The job did not finish" {:job id}))))))))

(defn- get-json [api-url path who]
  (let [r (http/get (str api-url path) {:throw false
                                        :headers {"Authorization" (str "Bearer " (h/token-for who))
                                                  "Accept" "application/json"}})]
    (assoc r :json (json/read-value (:body r) json/keyword-keys-object-mapper))))

(deftest simulated-devices-raise-an-alert-that-the-practitioner-finds-in-the-inbox
  (with-dev
    (fn [{:keys [system api-url]}]
      (testing "before anything, nothing: no readings, no alerts"
        (is (= 0 (:total (:json (get-json api-url "/api/alerts" :rivera))))))
      (testing "two ticks of the simulator, in two different seconds: the first two readings of the episode of Sofía"
        (is (= {:planned 7 :abnormal 1 :written 7 :failed 0}
               (select-keys (run-job! system :simulate-devices) [:planned :abnormal :written :failed])))
        (Thread/sleep 1100)
        (is (= {:planned 8 :abnormal 1 :written 8 :failed 0}
               (select-keys (run-job! system :simulate-devices) [:planned :abnormal :written :failed]))))
      (testing "the api, which reads the same FHIR server, shows them"
        (let [r (get-json api-url "/api/patients/pt-sofia/observations?measurement=spo2" :rivera)]
          (is (= 200 (:status r)))
          (is (= [88 89] (map :value (sort-by :at (:items (:json r))))))))
      (testing "the rules engine reads them through the api and raises the alert"
        (is (= {:patients 6 :raised 1 :failed 0}
               (select-keys (run-job! system :evaluate-rules) [:patients :raised :failed]))))
      (testing "and the practitioner finds it in the inbox of the api"
        (let [r (get-json api-url "/api/alerts" :rivera)
              [alert :as items] (:items (:json r))]
          (is (= 200 (:status r)))
          (is (= 1 (count items)))
          (is (= {:patient "Patient/pt-sofia" :text "Low SpO₂" :status "active"}
                 (select-keys alert [:patient :text :status])))
          (is (= 0 (:total (:json (get-json api-url "/api/alerts" :okafor)))) "and not in that of another practitioner")))
      (testing "once: the engine goes on running and the alert is not raised again"
        (is (= {:raised 0 :still-active 1}
               (select-keys (run-job! system :evaluate-rules) [:raised :still-active])))
        (is (= 1 (:total (:json (get-json api-url "/api/alerts" :rivera)))))))))

(deftest the-patient-with-an-appointment-within-a-day-is-reminded-once
  ;; The cohort puts the appointment of Ana about 20 hours from the moment the system started, those
  ;; of Maya and Liam two and three days away, and the requests of Sofía and Noah have no time yet.
  (with-dev
    (fn [{:keys [system api-url]}]
      (let [fhir (:pulse.api/fhir system)
            reminders (fn [] (vec (c/search fhir "Communication" {})))]
        (testing "before anything, no reminders"
          (is (empty? (reminders))))
        (testing "a run of the job reminds Ana, who is the only one whose appointment is within a day"
          (is (= {:appointments 1 :sent 1 :already-sent 0 :failed 0} (run-job! system :send-reminders)))
          (let [[reminder :as all] (reminders)]
            (is (= 1 (count all)))
            (is (= "Patient/pt-ana" (get-in reminder [:subject :reference])))
            (is (= [{:reference "Appointment/appt-ana-1"}] (:about reminder)))
            (is (re-matches #"Reminder: your appointment with Elena Rivera is on \w+ \d{1,2} \w+ at \d\d:\d\d \(Hypertension follow-up\)\."
                            (get-in reminder [:payload 0 :contentString])))))
        (testing "the appointment the api shows is the one the reminder is about, and it is as it was"
          (let [r (get-json api-url "/api/patients/pt-ana/summary" :rivera)]
            (is (= 200 (:status r)))
            (is (= [["appt-ana-1" "booked"]] (map (juxt :id :status) (get-in r [:json :appointments]))))))
        (testing "once: the job goes on running and the reminder is not sent again"
          (is (= {:appointments 1 :sent 0 :already-sent 1 :failed 0} (run-job! system :send-reminders)))
          (is (= 1 (count (reminders)))))))))

(deftest the-administrator-sees-the-jobs-of-the-scheduler-through-the-api
  ;; ADR-0025, the last step of Phase 5: the api asks the real scheduler, in the same JVM and over HTTP,
  ;; with the token of its own service account, and answers the administrator
  (with-dev
    (fn [{:keys [system api-url]}]
      (let [jobs-of (fn [who] (get-json api-url "/api/admin/jobs" who))
            by-id (fn [r id] (first (filter #(= id (:id %)) (get-in r [:json :items]))))]
        (testing "the three jobs that are switched on, in the order of the catalogue, none of which has run"
          (let [r (jobs-of :admin)]
            (is (= 200 (:status r)))
            (is (= 3 (get-in r [:json :total])))
            (is (= ["evaluate-rules" "simulate-devices" "send-reminders"] (map :id (get-in r [:json :items]))))
            (is (every? #(and (= "idle" (:state %))
                              (nil? (:lastRun %))
                              (= {:ok 0 :failed 0 :interrupted 0 :skipped 0} (:counts %)))
                        (get-in r [:json :items])))
            (is (= {:type "interval" :everyMs 604800000} (:schedule (by-id r "send-reminders"))) "as it was configured")))
        (testing "after a run by hand, the status says how it went"
          (run-job! system :send-reminders)
          (let [job (by-id (jobs-of :admin) "send-reminders")]
            (is (= "manual" (get-in job [:lastRun :trigger])))
            (is (= "ok" (get-in job [:lastRun :outcome])))
            (is (= {:appointments 1 :sent 1 :alreadySent 0 :failed 0} (get-in job [:lastRun :result])))
            (is (= {:ok 1 :failed 0 :interrupted 0 :skipped 0} (:counts job)))))
        (testing "and nobody else sees them"
          (is (= 403 (:status (jobs-of :rivera))))
          (is (= 403 (:status (jobs-of :ana))))
          (is (= 403 (:status (jobs-of :scheduler))))
          (is (= 401 (:status (jobs-of :api-service))) "the token of the api's own service account is for the scheduler")
          (is (= 401 (:status (http/get (str api-url "/api/admin/jobs") {:throw false})))))))))

(deftest the-portal-is-ready-when-the-api-and-the-keys-are-and-serves-its-pages
  ;; Phase 7, step 3 (ADR-0028): the portal in the same JVM as the real api, asking it over HTTP for its readiness
  ;; without credentials, and the keys of the identity provider. It has no page of a patient yet: what there is
  ;; is what any service has, its files, and the page that says that something is not there.
  (with-dev
    (fn [{:keys [web-url]}]
      (let [body (fn [r] (json/read-value (:body r) json/keyword-keys-object-mapper))]
        (testing "liveness and readiness"
          (is (= {:status "up"} (body (http/get (str web-url "/healthz") {:throw false}))))
          (let [r (http/get (str web-url "/readyz") {:throw false})]
            (is (= 200 (:status r)))
            (is (= {:status "ready" :checks {:keycloak "up" :api "up"}} (body r)))))
        (testing "a page that is not there, with the headers of a page of the portal"
          (let [r (http/get (str web-url "/nothing") {:throw false})]
            (is (= 404 (:status r)))
            (is (re-find #"We cannot find that page" (:body r)))
            (is (re-find #"script-src 'self'" (get-in r [:headers "content-security-policy"])))))
        (testing "the page of a person, for somebody who is not signed in, is the sign-in"
          (let [r (http/get (str web-url "/") {:throw false :client @no-redirects})]
            (is (= 303 (:status r)))
            (is (= "/sign-in" (get-in r [:headers "location"])))))
        (testing "and the files that its pages ask for"
          (is (= 200 (:status (http/get (str web-url "/assets/pulse.css") {:throw false}))))
          (is (= 200 (:status (http/get (str web-url "/assets/htmx.min.js") {:throw false})))))))))

;; ---------------------------------------------------------------------------
;; The acceptance of step 4 of Phase 7: a patient signs in at the portal, and the token it keeps is good at the api

(defn- cookie-jar
  "A browser's cookies, by hand: `(remember! jar response)` keeps the cookies a response sets, and drops those it expires;
  `(cookie-header jar)` is what the browser sends back."
  []
  (atom {}))

(defn- remember! [jar response]
  (let [set-cookie (get-in response [:headers "set-cookie"])]
    (doseq [cookie (cond (nil? set-cookie) [] (string? set-cookie) [set-cookie] :else set-cookie)
            :let [[pair & attributes] (str/split cookie #";\s*")
                  [k v] (str/split pair #"=" 2)]]
      (if (some #(re-matches #"(?i)max-age=0+" %) attributes)
        (swap! jar dissoc k)
        (swap! jar assoc k v))))
  response)

(defn- cookie-header [jar]
  (str/join "; " (map (fn [[k v]] (str k "=" v)) @jar)))

(defn- browse
  "A request of a browser with the cookies of `jar`, redirects not followed."
  [jar method url & {:keys [headers form-params]}]
  (remember! jar (http/request (cond-> {:method method :uri url :throw false :client @no-redirects
                                        :headers (cond-> (merge {"Accept" "text/html"} headers)
                                                   (seq @jar) (assoc "Cookie" (cookie-header jar)))}
                                 form-params (assoc :form-params form-params)))))

(deftest a-patient-signs-in-at-the-portal-and-the-token-it-keeps-is-good-at-the-api
  ;; the portal, the real api and the real scheduler in one JVM; the provider is the fake one, which signs Ana in. The portal
  ;; holds her tokens on the server, and the one it would send to the api is accepted by the api, which knows her by it
  (with-dev
    (fn [{:keys [system web-url api-url]}]
      (let [jar (cookie-jar)
            begin (browse jar :get (str web-url "/sign-in"))
            at-the-provider (oidc-fake/visit (get-in begin [:headers "location"]))
            callback (browse jar :get (:location at-the-provider))
            home (browse jar :get (str web-url "/"))
            sessions (:pulse.web/sessions system)
            session (store/read-session sessions (get @jar "pulse-session"))]
        (testing "the browser is sent to the provider, comes back with a code, and is let in"
          (is (= 303 (:status begin)))
          (is (= 302 (:status at-the-provider)))
          (is (= 303 (:status callback)))
          (is (= "/" (get-in callback [:headers "location"])))
          (is (= 200 (:status home)))
          (is (re-find #"Welcome, Ana García" (:body home))))
        (testing "the session is on the server, with her tokens, and the browser has a cookie and nothing else"
          (is (= #{"pulse-session"} (set (keys @jar))))
          (is (= "Ana García" (get-in session [:person :name])))
          (is (= {:sessions 1 :pending 0} (select-keys (session/counts sessions) [:sessions :pending]))))
        (testing "the token the portal would send is accepted by the api, which knows her by it"
          (let [r (http/get (str api-url "/api/me") {:throw false
                                                     :headers {"Authorization" (str "Bearer " (token/access-token (:tokens session)))
                                                               "Accept" "application/json"}})
                me (json/read-value (:body r) json/keyword-keys-object-mapper)]
            (is (= 200 (:status r)))
            (is (= "Patient/pt-ana" (:fhirUser me)))
            (is (= ["patient"] (:roles me)))
            (is (= "pulse-web" (:client me)) "the token was issued to the portal")
            (is (= "Dr. Elena Rivera" (get-in me [:generalPractitioner :name])))))
        (testing "signing out ends the session first, and the provider sends the browser back to the portal"
          (let [out (browse jar :post (str web-url "/sign-out")
                            :headers {"Origin" web-url}
                            :form-params {"__anti-forgery-token" (:csrf session)})
                back (oidc-fake/visit (get-in out [:headers "location"]))
                signed-out (browse jar :get (:location back))]
            (is (= 303 (:status out)))
            (is (= 302 (:status back)))
            (is (= 200 (:status signed-out)))
            (is (re-find #"You have signed out" (:body signed-out)))
            (is (empty? @jar) "the cookie is gone")
            (is (= {:sessions 0 :pending 0} (select-keys (session/counts sessions) [:sessions :pending])))
            (is (= 303 (:status (browse jar :get (str web-url "/")))) "and the page of a patient is not hers")))))))
