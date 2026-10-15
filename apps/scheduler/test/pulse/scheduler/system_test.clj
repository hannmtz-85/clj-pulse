(ns pulse.scheduler.system-test
  "The scheduler as a running system: Integrant starts the components of the real configuration
  (the `test` profile, pointed at a fake identity provider and a stand-in for the api), Jetty
  listens on a free port, and the tests talk to it over HTTP."
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.fhir.client :as c]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.stub :as stub]
            [pulse.scheduler.config :as config]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.system :as system]))

(def ^:private k1 (idp/signing-key "k1"))

(def ^:private issuer "http://idp.test/realms/pulse")

(def ^:private no-patients
  (json/write-value-as-string {:total 0 :offset 0 :limit 100 :items []}))

(defn- an-api
  "The answers of a stand-in for the api: ready, and with no patients."
  [{:keys [path]}]
  (if (= "/readyz" path)
    {:body "{\"status\":\"ready\",\"checks\":{}}"}
    {:body no-patients}))

(defn- with-system
  "Calls `(f base-url system idp api)` with the scheduler running against a fake identity provider
  and a stand-in for the api. `change` is a function applied to the configuration before it starts."
  ([f] (with-system identity f))
  ([change f]
   (let [server (idp/start! [k1])
         api (stub/start! an-api)
         cfg (-> (config/load-config {:profile :test})
                 (assoc-in [:pulse.scheduler/jwks :uri] (:uri server))
                 (assoc-in [:pulse.scheduler/token :token-uri] (:token-uri server))
                 (assoc-in [:pulse.scheduler/verifier :issuer] issuer)
                 (assoc-in [:pulse.scheduler/api :base-url] (:url api))
                 change)
         running (system/start cfg)]
     (try
       (f (str "http://127.0.0.1:" (system/port running)) running server api)
       (finally
         (system/stop running)
         (stub/stop! api)
         (idp/stop! server))))))

(defn- get-json [url & {:keys [token]}]
  (let [response (http/get url {:throw false
                                :headers (cond-> {"Accept" "application/json"}
                                           token (assoc "Authorization" (str "Bearer " token)))})]
    (assoc response :json (json/read-value (:body response) json/keyword-keys-object-mapper))))

(defn- token [& {:keys [aud roles] :or {aud ["pulse-scheduler"] roles ["clinic-admin"]}}]
  (let [now (quot (System/currentTimeMillis) 1000)]
    (idp/mint k1 {:iss issuer :aud aud :sub "u-admin" :typ "Bearer" :azp "pulse-api"
                  :preferred_username "admin@pulse.test"
                  :iat now :exp (+ now 300)
                  :realm_access {:roles roles}})))

(deftest the-system-starts-serves-and-stops
  (let [url (atom nil)
        running (atom nil)]
    (with-system
      (fn [base-url system server api]
        (reset! url base-url)
        (reset! running system)
        (testing "it listens on a port the OS chose"
          (is (re-matches #"http://127\.0\.0\.1:\d+" base-url)))
        (testing "nothing was asked of the token endpoint by starting"
          (is (empty? (idp/token-requests server))))
        (testing "liveness and readiness, over a real socket"
          (is (= {:status "up"} (:json (get-json (str base-url "/healthz")))))
          (is (= {:status "ready" :checks {:keycloak "up" :executor "up" :token "up" :api "up" :fhir "up"}}
                 (:json (get-json (str base-url "/readyz"))))))
        (testing "readiness got a token as the scheduler's client, with the secret of the test profile, and keeps it"
          (get-json (str base-url "/readyz"))
          (let [[request :as requests] (idp/token-requests server)]
            (is (= 1 (count requests)) "the check of the token and the call to the api share it")
            (is (= "grant_type=client_credentials" (:body request)))
            (is (= (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                  (.getBytes "pulse-scheduler:test-secret" java.nio.charset.StandardCharsets/UTF_8)))
                   (get-in request [:headers "authorization"])))))
        (testing "and asked the api for its readiness with that token"
          (let [requests (filter #(= "/readyz" (:path %)) @(:requests api))]
            (is (seq requests))
            (is (every? #(= "fake-token-1" (second (re-find #"^Bearer (.+)$" (get-in % [:headers "authorization"]))))
                        requests))))
        (testing "a request without a token is refused with the challenge"
          (let [r (get-json (str base-url "/jobs"))]
            (is (= 401 (:status r)))
            (is (= "Bearer realm=\"pulse-scheduler\"" (get-in r [:headers "www-authenticate"])))))
        (testing "a token meant for the api is refused"
          (is (= 401 (:status (get-json (str base-url "/jobs") :token (token :aud ["pulse-api" "account"]))))))
        (testing "an administrator's token for the scheduler gets the jobs (none: the tests switch the rules job off)"
          (let [r (get-json (str base-url "/jobs") :token (token))]
            (is (= 200 (:status r)))
            (is (= [] (:json r)))))
        (testing "anyone else is forbidden"
          (is (= 403 (:status (get-json (str base-url "/jobs") :token (token :roles ["practitioner"]))))))
        (testing "the security headers arrive, and the server does not announce its version"
          (let [r (get-json (str base-url "/healthz"))]
            (is (= "nosniff" (get-in r [:headers "x-content-type-options"])))
            (is (= "no-store" (get-in r [:headers "cache-control"])))
            (is (nil? (get-in r [:headers "server"])))))
        (is (executor/running? (:pulse.scheduler/executor system)))))
    (testing "after stop the port is closed, and the jobs are stopped"
      (is (thrown? java.io.IOException (http/get (str @url "/healthz"))))
      (is (not (executor/running? (:pulse.scheduler/executor @running)))))))

(def ^:private quiet
  "A schedule that will not fire while a test runs: once a week, on the grid of the epoch."
  {:type :interval :every-ms 604800000})

(defn- run-now [base-url id]
  (http/post (str base-url "/jobs/" id "/run") {:throw false :headers {"Authorization" (str "Bearer " (token))}}))

(defn- eventually [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 10)
            (recur))))))

(deftest the-rules-job-reads-the-patients-through-the-api-with-the-schedulers-token
  (with-system #(-> %
                    (assoc-in [:pulse.scheduler/jobs :evaluate-rules :enabled?] true)
                    (assoc-in [:pulse.scheduler/jobs :evaluate-rules :schedule] quiet))
    (fn [base-url _ server api]
      (let [job (fn [] (first (:json (get-json (str base-url "/jobs") :token (token)))))]
        (testing "enabled, it is in the catalogue, scheduled as it was told"
          (is (= "evaluate-rules" (:id (job))))
          (is (= {:type "interval" :everyMs 604800000} (:schedule (job)))))
        (testing "run by hand, it asks the api for the patients and finds none"
          (is (= 202 (:status (run-now base-url "evaluate-rules"))))
          (is (eventually #(:lastRun (job))))
          (is (= {:trigger "manual" :outcome "ok"
                  :result {:patients 0 :raised 0 :alreadyRaised 0 :stillActive 0 :notApplicable 0 :failed 0}}
                 (-> (job) :lastRun (select-keys [:trigger :outcome :result])))))
        (testing "with the token of the scheduler as the client of the realm, and nobody else's"
          (let [[request :as requests] (filter #(= "/api/patients" (:path %)) @(:requests api))]
            (is (= 1 (count requests)))
            (is (= "Bearer fake-token-1" (get-in request [:headers "authorization"])))
            (is (= "GET" (:method request)))
            (is (= 1 (count (idp/token-requests server))))))))))

(deftest the-simulator-job-writes-the-readings-of-its-ticks-to-the-fhir-server-of-the-scheduler
  (with-system #(-> %
                    (assoc-in [:pulse.scheduler/jobs :simulate-devices :enabled?] true)
                    (assoc-in [:pulse.scheduler/jobs :simulate-devices :schedule] quiet))
    (fn [base-url system _ _]
      (let [job (fn [] (first (filter #(= "simulate-devices" (:id %)) (:json (get-json (str base-url "/jobs") :token (token))))))]
        (testing "enabled, it is in the catalogue"
          (is (= {:type "interval" :everyMs 604800000} (:schedule (job)))))
        (testing "run by hand, it writes the readings of its first tick"
          (is (= 202 (:status (run-now base-url "simulate-devices"))))
          (is (eventually #(:lastRun (job))))
          (is (= {:trigger "manual" :outcome "ok"
                  :result {:planned 7 :abnormal 1 :read 7 :written 7 :batches 1 :failed 0 :failedBatches 0 :errors 0}}
                 (-> (job) :lastRun (select-keys [:trigger :outcome :result]))))
          (is (= 7 (count (c/search (:pulse.scheduler/fhir system) "Observation" {})))
              "in the server the scheduler was given"))))))
