(ns pulse.platform.system-test
  "The shared components, started the way a service starts them: under keys of its own that are
  derived from the platform's."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [malli.core :as m]
            [pulse.platform.config :as config]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.system :as platform]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.token :as token]))

(derive ::jwks :pulse.platform/jwks)
(derive ::verifier :pulse.platform/verifier)
(derive ::server :pulse.platform/server)

(def ^:private k1 (idp/signing-key "k1"))

(deftest a-service-key-derived-from-a-platform-one-starts-and-stops-like-it
  (let [server (idp/start! [k1])
        system (ig/init {::jwks {:uri (:uri server)}
                         ::verifier {:jwks (ig/ref ::jwks)
                                     :issuer "http://idp.test/realms/pulse"
                                     :audience "pulse-test"
                                     :algorithms ["RS256"]
                                     :leeway-s 30
                                     :token-type "Bearer"}})]
    (try
      (let [now (quot (System/currentTimeMillis) 1000)
            token (idp/mint k1 {:iss "http://idp.test/realms/pulse" :aud ["pulse-test"] :sub "u-1" :typ "Bearer"
                                :iat now :exp (+ now 300)})
            verifier (::verifier system)]
        (is (jwt/keys-available? (:jwks-source verifier)) "the verifier was given the key cache")
        (is (= "u-1" (get-in (jwt/verify verifier token) [:ok :sub])) "and it verifies a token")
        (is (contains? (jwt/verify verifier (idp/mint k1 {:iss "http://idp.test/realms/pulse" :aud ["pulse-api"] :sub "u-1"
                                                          :typ "Bearer" :iat now :exp (+ now 300)}))
                       :error)
            "a token for another audience is refused"))
      (finally
        (ig/halt! system)
        (idp/stop! server)))))

(deftest the-server-listens-on-the-port-asked-for-and-stops
  (let [system (ig/init {::server {:handler (fn [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "hello"})
                                   :host "127.0.0.1"
                                   :port 0}})
        url (str "http://127.0.0.1:" (platform/port (::server system)) "/")]
    (try
      (let [r (http/get url {:throw false})]
        (is (= 200 (:status r)))
        (is (= "hello" (:body r)))
        (is (nil? (get-in r [:headers "server"])) "it does not announce its version"))
      (finally
        (ig/halt! system)))
    (is (thrown? java.io.IOException (http/get url)) "after the halt the port is closed")))

(derive ::token :pulse.platform/token-source)

(deftest the-keeper-of-a-token-starts-without-asking-and-gives-a-token-when-asked
  (let [server (idp/start! [k1])
        system (ig/init {::token {:token-uri (:token-uri server)
                                  :client-id "pulse-test"
                                  :client-secret (config/secret "s3cret")
                                  :renew-before-ms 60000
                                  :retry-delay-ms 5000
                                  :timeout-ms 5000
                                  :connect-timeout-ms 2000}})]
    (try
      (is (empty? (idp/token-requests server)) "starting asks nothing of Keycloak")
      (is (= "fake-token-1" (token/access-token (::token system))))
      (is (= 1 (count (idp/token-requests server))))
      (finally
        (ig/halt! system)
        (idp/stop! server)))))

(deftest the-schema-of-a-token-source-is-closed-and-wants-a-secret
  (let [valid {:token-uri "http://localhost:8080/realms/pulse/protocol/openid-connect/token"
               :client-id "pulse-scheduler"
               :client-secret (config/secret "s3cret")
               :renew-before-ms 60000
               :retry-delay-ms 5000
               :timeout-ms 5000
               :connect-timeout-ms 2000}
        schema (platform/token-source-schema "set SOME_VARIABLE")]
    (is (m/validate schema valid))
    (is (m/validate schema (assoc valid :scope "openid")))
    (is (not (m/validate schema (assoc valid :client-secret "plain string"))) "the file must say #secret")
    (is (not (m/validate schema (assoc valid :client-secret nil))))
    (is (not (m/validate schema (assoc valid :retry-delay 5000))) "a misspelt key")
    (is (not (m/validate schema (dissoc valid :client-id))))
    (is (not (m/validate schema (assoc valid :token-uri "keycloak"))))
    (testing "the message names where the secret comes from, and not the value"
      (let [e (try (config/validate! schema (assoc valid :client-secret nil))
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) "set SOME_VARIABLE"))))))

(deftest a-client-has-a-keeper-of-its-own-token-or-none
  (let [client {:base-url "http://localhost:4000" :timeout-ms 10000 :connect-timeout-ms 3000}
        with-keeper (assoc client :token (ig/ref ::token))]
    (testing "a service that calls another as itself names the keeper of its token"
      (is (m/validate platform/service-client-schema with-keeper))
      (is (not (m/validate platform/service-client-schema client)) "and cannot do without it")
      (is (not (m/validate platform/service-client-schema (assoc with-keeper :timeout 1000))) "a misspelt key"))
    (testing "the portal's client of the api has none: each call is given the keeper of a person's session"
      (is (m/validate platform/client-schema client))
      (is (not (m/validate platform/client-schema with-keeper)) "a keeper here would be a mistake, not a setting")
      (is (not (m/validate platform/client-schema (dissoc client :base-url))))
      (is (not (m/validate platform/client-schema (assoc client :base-url "localhost:4000"))))
      (is (not (m/validate platform/client-schema (assoc client :timeout-ms 5))) "a timeout of 5 ms is not one")
      (is (not (m/validate platform/client-schema (assoc client :connect-timeout-ms 90000)))))))

(deftest the-schemas-of-the-components-are-closed
  (let [valid? m/validate]
    (is (valid? platform/server-schema {:handler (ig/ref ::handler) :host "127.0.0.1" :port 0}))
    (is (not (valid? platform/server-schema {:handler (ig/ref ::handler) :host "127.0.0.1" :port 0 :prot 1})) "a misspelt key")
    (is (not (valid? platform/server-schema {:handler (ig/ref ::handler) :host "127.0.0.1" :port 70000})))
    (is (valid? platform/jwks-schema {:uri "http://localhost:8080/certs"}))
    (is (not (valid? platform/jwks-schema {:uri "localhost:8080/certs"})))
    (is (valid? platform/logging-schema {:level :info :format :text :ns-levels {"org.eclipse.jetty.*" :warn}}))
    (is (not (valid? platform/logging-schema {:level :loud :format :text :ns-levels {}})))))
