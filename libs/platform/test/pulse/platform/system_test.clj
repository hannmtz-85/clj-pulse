(ns pulse.platform.system-test
  "The shared components, started the way a service starts them: under keys of its own that are
  derived from the platform's."
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is]]
            [integrant.core :as ig]
            [malli.core :as m]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.system :as platform]
            [pulse.platform.test.idp :as idp]))

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

(deftest the-schemas-of-the-components-are-closed
  (let [valid? m/validate]
    (is (valid? platform/server-schema {:handler (ig/ref ::handler) :host "127.0.0.1" :port 0}))
    (is (not (valid? platform/server-schema {:handler (ig/ref ::handler) :host "127.0.0.1" :port 0 :prot 1})) "a misspelt key")
    (is (not (valid? platform/server-schema {:handler (ig/ref ::handler) :host "127.0.0.1" :port 70000})))
    (is (valid? platform/jwks-schema {:uri "http://localhost:8080/certs"}))
    (is (not (valid? platform/jwks-schema {:uri "localhost:8080/certs"})))
    (is (valid? platform/logging-schema {:level :info :format :text :ns-levels {"org.eclipse.jetty.*" :warn}}))
    (is (not (valid? platform/logging-schema {:level :loud :format :text :ns-levels {}})))))
