(ns pulse.dev-test
  "The api and the scheduler as one system (ADR-0023): the configuration that `bb dev` starts, and the
  acceptance of Phase 5 as a test. The simulated devices send their readings to the FHIR server, the
  rules engine reads them through the api and raises an alert, and the practitioner finds it in the
  inbox of the api, all in one JVM and over HTTP between the two."
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [pulse.api.test.harness :as h]
            [pulse.dev :as dev]
            [pulse.fhir.client :as c]
            [pulse.platform.config :as platform]
            [pulse.platform.test.idp :as idp]
            [pulse.scheduler.executor :as executor])
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

(deftest the-configuration-has-the-components-of-both-apps
  (let [cfg (config :dev "SCHEDULER_CLIENT_SECRET=a-secret-from-dotenv\n")]
    (is (every? #(contains? cfg %) [:pulse.api/server :pulse.api/handler :pulse.api/fhir
                                    :pulse.scheduler/server :pulse.scheduler/executor :pulse.scheduler/jobs
                                    :pulse.scheduler/api :pulse.scheduler/token]))
    (is (= 4000 (get-in cfg [:pulse.api/server :port])))
    (is (= 5000 (get-in cfg [:pulse.scheduler/server :port])))
    (testing "the secret of the scheduler comes from the file, and is a secret"
      (is (= "a-secret-from-dotenv" (platform/reveal (get-in cfg [:pulse.scheduler/token :client-secret]))))
      (is (platform/secret? (get-in cfg [:pulse.scheduler/token :client-secret]))))
    (testing "the scheduler's client of the api comes after the api's server"
      (is (= (ig/ref :pulse.api/server) (get-in cfg [:pulse.scheduler/api :after]))))
    (testing "each reaches HAPI by itself"
      (is (= :http (get-in cfg [:pulse.api/fhir :kind])))
      (is (= :http (get-in cfg [:pulse.scheduler/fhir :kind]))))
    (testing "the devices are simulated and the rules evaluated"
      (is (true? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])))
      (is (true? (get-in cfg [:pulse.scheduler/jobs :evaluate-rules :enabled?]))))))

(deftest a-server-in-memory-is-the-schedulers-too
  (doseq [profile [:lite :test]
          :let [cfg (config profile "SCHEDULER_CLIENT_SECRET=x\n")]]
    (testing profile
      (is (= :memory (get-in cfg [:pulse.api/fhir :kind])))
      (is (= (ig/ref :pulse.api/fhir) (:pulse.scheduler/fhir cfg))
          "an alert written to another server in memory would never be seen by the api"))))

(deftest the-scheduler-uses-its-test-profile-with-the-apis
  (let [cfg (config :test "")]
    (is (= 0 (get-in cfg [:pulse.scheduler/server :port])))
    (is (false? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])))))

(deftest the-simulator-can-be-switched-off-with-a-variable
  (let [cfg (config :dev "SCHEDULER_CLIENT_SECRET=x\nSCHEDULER_SIMULATOR=false\n")]
    (is (false? (get-in cfg [:pulse.scheduler/jobs :simulate-devices :enabled?])))
    (is (true? (get-in cfg [:pulse.scheduler/jobs :evaluate-rules :enabled?])) "and the rules go on")))

(deftest what-a-test-says-is-read-before-dotenv
  (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" "from-the-test"}]
    (let [cfg (config :dev "SCHEDULER_CLIENT_SECRET=from-dotenv\n")]
      (is (= "from-the-test" (platform/reveal (get-in cfg [:pulse.scheduler/token :client-secret])))))))

(deftest without-the-secret-the-apps-say-which-variable-to-set
  (let [e (try (config :dev "OTHER=1\n") (catch clojure.lang.ExceptionInfo e e))]
    (is (platform/invalid-config? e))
    (is (re-find #"SCHEDULER_CLIENT_SECRET" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; The acceptance of Phase 5: devices, alerts, inbox

(def ^:private quiet
  "A schedule that will not fire while the test runs: once a week, on the grid of the epoch."
  {:type :interval :every-ms 604800000})

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- json-text [x] (json/write-value-as-string x))

(defn- with-dev
  "Calls `(f {:system s :api-url url})` with the api and the scheduler started as one system: the test
  profile, a fake identity provider that gives the scheduler a token the api accepts, the api on a
  port that is known, the jobs switched on (and run by the test, not by a schedule that is quiet), and no
  readings in the FHIR server but those that the simulator sends."
  [f]
  (let [idp (idp/start! [h/signing-key])
        port (free-port)
        api-url (str "http://127.0.0.1:" port)
        cfg (-> (dev/config {:profile :test :dotenv "there-is-no-such-file.env" :process-env {}})
                (assoc-in [:pulse.api/jwks :uri] (:uri idp))
                (assoc-in [:pulse.api/verifier :issuer] h/issuer)
                (assoc-in [:pulse.api/server :port] port)
                (assoc-in [:pulse.scheduler/jwks :uri] (:uri idp))
                (assoc-in [:pulse.scheduler/verifier :issuer] h/issuer)
                (assoc-in [:pulse.scheduler/token :token-uri] (:token-uri idp))
                (assoc-in [:pulse.scheduler/api :base-url] api-url)
                (assoc-in [:pulse.scheduler/jobs :evaluate-rules :enabled?] true)
                (assoc-in [:pulse.scheduler/jobs :evaluate-rules :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :simulate-devices :enabled?] true)
                (assoc-in [:pulse.scheduler/jobs :simulate-devices :schedule] quiet))]
    (idp/serve-token! idp {:body (json-text {:access_token (h/token-for :scheduler) :expires_in 300 :token_type "Bearer"})})
    (let [system (ig/init cfg)]
      (try
        (let [fhir (:pulse.api/fhir system)]
          ;; all of them first: a search is lazy, and deleting changes what the next page holds
          (doseq [o (doall (c/search fhir "Observation" {}))]
            (c/delete! fhir "Observation" (:id o))))
        (f {:system system :api-url api-url})
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
