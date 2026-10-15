(ns pulse.scheduler.system-test
  "The scheduler as a running system: Integrant starts the components of the real configuration
  (the `test` profile, pointed at a fake identity provider), Jetty listens on a free port, and the
  tests talk to it over HTTP."
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.test.idp :as idp]
            [pulse.scheduler.config :as config]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.system :as system]))

(def ^:private k1 (idp/signing-key "k1"))

(def ^:private issuer "http://idp.test/realms/pulse")

(defn- with-system
  "Calls `(f base-url system idp)` with the scheduler running against a fake identity provider."
  [f]
  (let [server (idp/start! [k1])
        cfg (-> (config/load-config {:profile :test})
                (assoc-in [:pulse.scheduler/jwks :uri] (:uri server))
                (assoc-in [:pulse.scheduler/token :token-uri] (:token-uri server))
                (assoc-in [:pulse.scheduler/verifier :issuer] issuer))
        running (system/start cfg)]
    (try
      (f (str "http://127.0.0.1:" (system/port running)) running server)
      (finally
        (system/stop running)
        (idp/stop! server)))))

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
      (fn [base-url system server]
        (reset! url base-url)
        (reset! running system)
        (testing "it listens on a port the OS chose"
          (is (re-matches #"http://127\.0\.0\.1:\d+" base-url)))
        (testing "nothing was asked of the token endpoint by starting"
          (is (empty? (idp/token-requests server))))
        (testing "liveness and readiness, over a real socket"
          (is (= {:status "up"} (:json (get-json (str base-url "/healthz")))))
          (is (= {:status "ready" :checks {:keycloak "up" :executor "up" :token "up"}}
                 (:json (get-json (str base-url "/readyz"))))))
        (testing "readiness got a token as the scheduler's client, with the secret of the test profile, and keeps it"
          (get-json (str base-url "/readyz"))
          (let [[request :as requests] (idp/token-requests server)]
            (is (= 1 (count requests)))
            (is (= "grant_type=client_credentials" (:body request)))
            (is (= (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                                  (.getBytes "pulse-scheduler:test-secret" java.nio.charset.StandardCharsets/UTF_8)))
                   (get-in request [:headers "authorization"])))))
        (testing "a request without a token is refused with the challenge"
          (let [r (get-json (str base-url "/jobs"))]
            (is (= 401 (:status r)))
            (is (= "Bearer realm=\"pulse-scheduler\"" (get-in r [:headers "www-authenticate"])))))
        (testing "a token meant for the api is refused"
          (is (= 401 (:status (get-json (str base-url "/jobs") :token (token :aud ["pulse-api" "account"]))))))
        (testing "an administrator's token for the scheduler gets the jobs (none yet)"
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
