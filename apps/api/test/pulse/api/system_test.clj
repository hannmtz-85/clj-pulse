(ns pulse.api.system-test
  "The api as a running system: Integrant starts the components of the real configuration (the
  `test` profile, pointed at a fake identity provider), Jetty listens on a free port, and the
  tests talk to it over HTTP."
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.api.config :as config]
            [pulse.api.system :as system]
            [pulse.platform.test.idp :as idp]))

(def ^:private k1 (idp/signing-key "k1"))

(defn- with-system
  "Calls `(f base-url)` with the api running against a fake identity provider."
  [f]
  (let [server (idp/start! [k1])
        cfg (-> (config/load-config {:profile :test})
                (assoc-in [:pulse.api/jwks :uri] (:uri server))
                (assoc-in [:pulse.api/verifier :issuer] "http://idp.test/realms/pulse"))
        running (system/start cfg)]
    (try
      (f (str "http://127.0.0.1:" (system/port running)))
      (finally
        (system/stop running)
        (idp/stop! server)))))

(defn- get-json [url & {:keys [token]}]
  (let [response (http/get url {:throw false
                                :headers (cond-> {"Accept" "application/json"}
                                           token (assoc "Authorization" (str "Bearer " token)))})]
    (assoc response :json (json/read-value (:body response) json/keyword-keys-object-mapper))))

(defn- token []
  (let [now (quot (System/currentTimeMillis) 1000)]
    (idp/mint k1 {:iss "http://idp.test/realms/pulse" :aud ["pulse-api" "account"] :sub "u-1" :typ "Bearer"
                  :azp "pulse-spa" :preferred_username "ana.garcia@pulse.test"
                  :iat now :exp (+ now 300) :fhirUser "Patient/pt-ana"
                  :realm_access {:roles ["patient"]}})))

(deftest the-system-starts-serves-and-stops
  (let [url (atom nil)]
    (with-system
      (fn [base-url]
        (reset! url base-url)
        (testing "it listens on a port the OS chose"
          (is (re-matches #"http://127\.0\.0\.1:\d+" base-url)))
        (testing "liveness and readiness, over a real socket"
          (is (= {:status "up"} (:json (get-json (str base-url "/healthz")))))
          (is (= {:status "ready" :checks {:keycloak "up" :fhir "up"}}
                 (:json (get-json (str base-url "/readyz"))))))
        (testing "a request without a token is refused with the challenge"
          (let [r (get-json (str base-url "/api/me"))]
            (is (= 401 (:status r)))
            (is (= "Bearer realm=\"pulse-api\"" (get-in r [:headers "www-authenticate"])))))
        (testing "a request with a token gets the identity and the Patient from the in-memory FHIR server"
          (let [r (get-json (str base-url "/api/me") :token (token))]
            (is (= 200 (:status r)))
            (is (= ["patient"] (get-in r [:json :roles])))
            (is (= "pt-ana" (get-in r [:json :resource :id])))))
        (testing "the server does not announce its version"
          (is (nil? (get-in (get-json (str base-url "/healthz")) [:headers "server"]))))))
    (testing "after stop the port is closed"
      (is (thrown? java.io.IOException (http/get (str @url "/healthz")))))))
