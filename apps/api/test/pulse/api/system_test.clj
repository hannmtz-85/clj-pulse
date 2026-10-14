(ns pulse.api.system-test
  "The api as a running system: Integrant starts the components of the real configuration (the
  `test` profile, pointed at a fake identity provider), Jetty listens on a free port, and the
  tests talk to it over HTTP."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
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
                (assoc-in [:pulse.api/verifier :issuer] "http://idp.test/realms/pulse")
                ;; the same address, as in the configuration file, where one refers to the other
                (assoc-in [:pulse.api/handler :issuer] "http://idp.test/realms/pulse"))
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

(deftest the-document-the-page-and-the-headers-arrive-over-a-real-socket
  (with-system
    (fn [base-url]
      (testing "the document of the api"
        (let [r (get-json (str base-url "/openapi.json"))]
          (is (= 200 (:status r)))
          (is (= "3.1.0" (get-in r [:json :openapi])))
          (is (= "http://idp.test/realms/pulse/protocol/openid-connect/auth"
                 (get-in r [:json :components :securitySchemes :pulse-sso :flows :authorizationCode :authorizationUrl])))))
      (testing "the page of Swagger UI, with its own policy"
        (let [r (http/get (str base-url "/docs/") {:throw false})]
          (is (= 200 (:status r)))
          (is (str/includes? (get-in r [:headers "content-type"]) "text/html"))
          (is (str/includes? (get-in r [:headers "content-security-policy"]) "connect-src 'self' http://idp.test;"))
          (is (nil? (get-in r [:headers "cache-control"])))
          (is (str/includes? (:body r) "swagger-initializer.js")))
        (let [r (http/get (str base-url "/docs/swagger-initializer.js") {:throw false})]
          (is (= 200 (:status r)))
          (is (str/includes? (:body r) "pulse-docs"))))
      (testing "the security headers, on a success and on an error"
        (doseq [path ["/healthz" "/api/me" "/nope"]]
          (let [r (get-json (str base-url path))]
            (is (= "nosniff" (get-in r [:headers "x-content-type-options"])) path)
            (is (= "no-store" (get-in r [:headers "cache-control"])) path)
            (is (= "default-src 'none'; frame-ancestors 'none'" (get-in r [:headers "content-security-policy"])) path))))
      (testing "the preflight of the clinical workspace, answered with no token"
        (let [r (http/request {:method :options
                               :uri (str base-url "/api/appointments/appt-1")
                               :throw false
                               :headers {"Origin" "http://localhost:3000"
                                         "Access-Control-Request-Method" "PATCH"
                                         "Access-Control-Request-Headers" "authorization, content-type"}})]
          (is (= 204 (:status r)))
          (is (= "http://localhost:3000" (get-in r [:headers "access-control-allow-origin"])))
          (is (= "GET, POST, PATCH" (get-in r [:headers "access-control-allow-methods"])))))
      (testing "and the refusal of another origin"
        (let [r (http/request {:method :options
                               :uri (str base-url "/api/me")
                               :throw false
                               :headers {"Origin" "http://evil.example"
                                         "Access-Control-Request-Method" "GET"}})]
          (is (= 403 (:status r)))
          (is (nil? (get-in r [:headers "access-control-allow-origin"]))))))))

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
