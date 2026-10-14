(ns pulse.platform.http.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.auth :as auth]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.idp :as idp]
            [taoensso.telemere :as t]))

(def ^:private k1 (idp/signing-key "k1"))
(def ^:private now 1760000000)
(def ^:private issuer "http://idp.test/realms/pulse")

(def ^:private claims
  {:iss issuer :aud ["pulse-api" "account"] :sub "u1" :typ "Bearer" :iat now :exp (+ now 300)})

(defn- authenticate
  "Runs `authenticate` against a fake identity provider serving `k1`, at the frozen time
  `now`. `token-or-header` is the whole Authorization header value."
  [header & {:keys [break?]}]
  (let [server (idp/start! [k1])
        source (jwt/jwks-source {:uri (:uri server)})]
    (try
      (when break? (idp/break! server))
      (let [verifier (jwt/verifier {:jwks-source source :issuer issuer :audience "pulse-api"
                                    :token-type "Bearer" :clock (constantly now)})
            request {:request-method :get :uri "/secret" :request-id "req-1"
                     :headers (cond-> {} header (assoc "authorization" header))}
            {:keys [value signals]} (t/with-signals true
                                      (auth/authenticate {:verifier verifier :realm "pulse-api"} request))]
        (assoc value :signals signals))
      (finally
        (jwt/close! source)
        (idp/stop! server)))))

(defn- bearer [claims]
  (str "Bearer " (idp/mint k1 claims)))

(defn- problem [{:keys [response]}]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

(deftest a-valid-token-gives-its-claims
  (let [result (authenticate (bearer claims))]
    (is (= "u1" (get-in result [:claims :sub])))
    (is (nil? (:response result)))))

(deftest no-credentials-get-the-bare-challenge
  (doseq [header [nil "Basic dXNlcjpwYXNz" "Bearer" "Bearer a b"]]
    (testing (pr-str header)
      (let [{:keys [response signals]} (authenticate header)]
        (is (= 401 (:status response)))
        (is (= "Bearer realm=\"pulse-api\"" (get-in response [:headers "WWW-Authenticate"]))
            "RFC 6750: no error code when the request had no credentials")
        (is (= "Authentication is required." (:detail (problem {:response response}))))
        (is (= :no-credentials (get-in (first signals) [:data :reason])))))))

(deftest a-bad-token-gets-invalid-token-and-a-generic-reason
  (doseq [[label header expected-type]
          [["garbage" "Bearer abc.def.ghi" :malformed]
           ["expired" (bearer (assoc claims :exp (- now 3600))) :expired]
           ["another audience" (bearer (assoc claims :aud ["other-api"])) :bad-audience]
           ["another issuer" (bearer (assoc claims :iss "http://evil.test")) :bad-issuer]
           ["an ID token" (bearer (assoc claims :typ "ID")) :wrong-token-type]]]
    (testing label
      (let [{:keys [response signals]} (authenticate header)]
        (is (= 401 (:status response)))
        (is (= (jwt/challenge "pulse-api" expected-type) (get-in response [:headers "WWW-Authenticate"])))
        (is (= (get-in jwt/errors [expected-type :description]) (:detail (problem {:response response}))))
        (is (= "req-1" (:requestId (problem {:response response}))))
        (testing "the reason is for the log, and so is its detail"
          (is (= expected-type (get-in (first signals) [:data :reason])))
          (is (= :info (:level (first signals)))))))))

(deftest no-keys-at-all-is-a-503-and-not-the-clients-fault
  (let [{:keys [response signals]} (authenticate (bearer claims) :break? true)]
    (is (= 503 (:status response)))
    (is (nil? (get-in response [:headers "WWW-Authenticate"])) "a challenge would tell the client to re-authenticate")
    (is (= "10" (get-in response [:headers "Retry-After"])))
    (is (= :keys-unavailable (get-in (first signals) [:data :reason])))
    (is (= :warn (:level (first signals))) "this one is ours to look at")))

(deftest the-token-never-reaches-the-log
  (let [token (idp/mint k1 (assoc claims :exp (- now 3600)))
        {:keys [signals]} (authenticate (str "Bearer " token))]
    (is (not-any? #(.contains (pr-str %) token) signals))))
