(ns pulse.platform.token-http-test
  "The keeper of a service's token against a real HTTP server, the fake identity provider's /token
  endpoint: what goes over the wire, the answers it understands, and that the secret is nowhere in
  what comes out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.platform.config :as config]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.token :as token]
            [taoensso.telemere :as t])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def ^:private secret "p@ss:w+rd%")

(def ^:private secret-forms
  "Every way the secret could end up in a string: as it is, form-encoded, and as it travels."
  [secret "p%40ss%3Aw%2Brd%25" "pulse-scheduler:p%40ss%3Aw%2Brd%25"
   (.encodeToString (Base64/getEncoder) (.getBytes "pulse-scheduler:p%40ss%3Aw%2Brd%25" StandardCharsets/UTF_8))])

(defn- with-idp [f]
  (let [server (idp/start! [])]
    (try (f server)
         (finally (idp/stop! server)))))

(defn- source-for [server & {:as options}]
  (let [c (clock/clock "2026-10-15T10:00:00Z")]
    [(token/source (merge {:token-uri (:token-uri server)
                           :client-id "pulse-scheduler"
                           :client-secret (config/secret secret)
                           :clock (:clock c)}
                          options))
     c]))

(defn- failure [s]
  (try (token/access-token s)
       (is false "nothing was thrown")
       (catch clojure.lang.ExceptionInfo e e)))

(defn- all-text-of
  "Everything a failure says, to look through it: message, data, and the same for each cause."
  [e]
  (str/join "\n" (map (fn [x] (str (ex-message x) " " (pr-str (ex-data x)) " " x))
                      (take-while some? (iterate ex-cause e)))))

;; ---------------------------------------------------------------------------
;; The request

(deftest the-request-is-a-client-credentials-grant-with-basic-authentication
  (with-idp
    (fn [server]
      (let [[s _] (source-for server)]
        (is (= "fake-token-1" (token/access-token s)))
        (let [[request :as requests] (idp/token-requests server)]
          (is (= 1 (count requests)))
          (is (= "POST" (:method request)))
          (is (= "grant_type=client_credentials" (:body request)))
          (is (= "application/x-www-form-urlencoded" (get-in request [:headers "content-type"])))
          (is (= "application/json" (get-in request [:headers "accept"])))
          (testing "the id and the secret are form-encoded first, as RFC 6749 says, and then sent as Basic"
            (is (= (str "Basic " (last secret-forms)) (get-in request [:headers "authorization"])))))))))

(deftest an-identity-provider-asked-by-two-clients-can-answer-each-its-own
  ;; the fake's way to be the endpoint that the scheduler and the api both ask (ADR-0025)
  (with-idp
    (fn [server]
      (idp/serve-token! server {:answer (fn [request]
                                          (str "{\"access_token\":\"token-of-" (idp/client-id-of request)
                                               "\",\"expires_in\":300,\"token_type\":\"Bearer\"}"))})
      (let [[scheduler _] (source-for server :client-id "pulse-scheduler")
            [api _] (source-for server :client-id "pulse-api")]
        (is (= "token-of-pulse-scheduler" (token/access-token scheduler)))
        (is (= "token-of-pulse-api" (token/access-token api)))
        (is (= ["pulse-scheduler" "pulse-api"] (map idp/client-id-of (idp/token-requests server)))))))
  (testing "and a request with no credentials is nobody's"
    (is (nil? (idp/client-id-of {:headers {}})))
    (is (nil? (idp/client-id-of {:headers {"authorization" "Bearer abc"}})))))

(deftest a-client-id-with-a-space-is-form-encoded-too
  (with-idp
    (fn [server]
      (let [[s _] (source-for server :client-id "pulse scheduler" :client-secret "s")]
        (token/access-token s)
        (is (= (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes "pulse+scheduler:s" StandardCharsets/UTF_8)))
               (get-in (first (idp/token-requests server)) [:headers "authorization"])))))))

(deftest a-scope-is-asked-for-when-there-is-one
  (with-idp
    (fn [server]
      (let [[s _] (source-for server :scope "openid profile")]
        (token/access-token s)
        (is (= "grant_type=client_credentials&scope=openid+profile" (:body (first (idp/token-requests server)))))))))

(deftest the-secret-may-be-a-plain-string
  (with-idp
    (fn [server]
      (let [[s _] (source-for server :client-secret secret)]
        (is (= "fake-token-1" (token/access-token s)))
        (is (= (str "Basic " (last secret-forms)) (get-in (first (idp/token-requests server)) [:headers "authorization"])))))))

;; ---------------------------------------------------------------------------
;; The endpoint, for any grant (ADR-0028)

(defn- post-for [server & {:as options}]
  (token/endpoint (merge {:token-uri (:token-uri server)
                          :client-id "pulse-web"
                          :client-secret (config/secret secret)}
                         options)))

(defn- refusal [f]
  (try (f)
       (is false "nothing was thrown")
       (catch clojure.lang.ExceptionInfo e e)))

(deftest the-endpoint-posts-the-form-it-is-given-as-the-client
  (with-idp
    (fn [server]
      (let [post (post-for server)
            basic (str "Basic " (.encodeToString (Base64/getEncoder)
                                                 (.getBytes "pulse-web:p%40ss%3Aw%2Brd%25" StandardCharsets/UTF_8)))]
        (post [["grant_type" "refresh_token"] ["refresh_token" "r/1+2=="]])
        (post {"grant_type" "authorization_code" "code" "c o" "redirect_uri" "http://localhost:3300/oauth2/callback"})
        (let [[first-request second-request :as requests] (idp/token-requests server)]
          (is (= 2 (count requests)))
          (testing "form-encoded, and in the order given when it is a sequence of pairs"
            (is (= "grant_type=refresh_token&refresh_token=r%2F1%2B2%3D%3D" (:body first-request)))
            (is (= "grant_type=authorization_code&code=c+o&redirect_uri=http%3A%2F%2Flocalhost%3A3300%2Foauth2%2Fcallback"
                   (:body second-request))))
          (testing "as a form, to the endpoint, with the client's credentials, every time"
            (doseq [request requests]
              (is (= "POST" (:method request)))
              (is (= "application/x-www-form-urlencoded" (get-in request [:headers "content-type"])))
              (is (= "application/json" (get-in request [:headers "accept"])))
              (is (= basic (get-in request [:headers "authorization"]))))))))))

(deftest the-endpoint-gives-the-whole-grant-and-keeps-the-tokens-secret
  (with-idp
    (fn [server]
      (idp/serve-token! server {:body (str "{\"access_token\":\"access-value\",\"expires_in\":300,\"refresh_expires_in\":1800,"
                                           "\"refresh_token\":\"refresh-value\",\"id_token\":\"id-value\",\"token_type\":\"Bearer\"}")})
      (let [grant ((post-for server) {"grant_type" "authorization_code"})]
        (is (= "access-value" (:access-token grant)))
        (is (= 300 (:expires-in grant)))
        (testing "the refresh token and the id token are secrets, wherever the grant ends up"
          (is (config/secret? (:refresh-token grant)))
          (is (config/secret? (:id-token grant)))
          (is (= "refresh-value" (config/reveal (:refresh-token grant))))
          (is (= "id-value" (config/reveal (:id-token grant))))
          (is (not (str/includes? (pr-str grant) "refresh-value")))
          (is (not (str/includes? (pr-str grant) "id-value")))
          (is (not (str/includes? (str grant) "id-value"))))))))

(deftest a-grant-has-only-what-the-endpoint-gave
  (with-idp
    (fn [server]
      (is (= #{:access-token :expires-in} (set (keys ((post-for server) {"grant_type" "client_credentials"}))))
          "the default answer of the fake: a token and its life")
      (idp/serve-token! server {:body "{\"access_token\":\"a\",\"expires_in\":300,\"refresh_token\":42,\"id_token\":null}"})
      (is (= #{:access-token :expires-in} (set (keys ((post-for server) {"grant_type" "refresh_token"}))))
          "a refresh token or an id token that is not a string is not one"))))

(deftest the-endpoint-says-why-a-grant-was-refused
  (with-idp
    (fn [server]
      (idp/serve-token! server {:status 400 :body "{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}"})
      (let [e (refusal #((post-for server) {"grant_type" "refresh_token" "refresh_token" "refresh-value"}))]
        (is (= {:type ::token/unavailable :reason :rejected :status 400 :error "invalid_grant"} (ex-data e)))
        (testing "and nothing of the form, the secret or the answer is in what it says"
          (let [text (all-text-of e)]
            (is (not (str/includes? text "refresh-value")))
            (is (not (str/includes? text "Session not active")))
            (doseq [form secret-forms]
              (is (not (str/includes? text form)) form))))))))

(deftest the-endpoint-is-given-up-on-when-it-is-slow-and-reported-when-it-is-not-there
  (with-idp
    (fn [server]
      (idp/serve-token! server {:delay-ms 1500})
      (is (= :timeout (:reason (ex-data (refusal #((post-for server :timeout-ms 200) {"grant_type" "refresh_token"}))))))))
  (let [server (idp/start! [])
        uri (:token-uri server)]
    (idp/stop! server)
    (let [e (refusal #((token/endpoint {:token-uri uri :client-id "pulse-web" :client-secret secret}) {"grant_type" "refresh_token"}))]
      (is (= :connect (:reason (ex-data e))))
      (is (some? (ex-cause e))))))

;; ---------------------------------------------------------------------------
;; Keeping it

(deftest over-the-wire-a-token-is-kept-and-then-renewed-early
  (with-idp
    (fn [server]
      (let [[s c] (source-for server)]
        (is (= "fake-token-1" (token/access-token s)))
        (clock/advance! c 239999)
        (is (= "fake-token-1" (token/access-token s)))
        (is (= 1 (count (idp/token-requests server))) "one request for the first 239.999 seconds")
        (clock/advance! c 1)
        (is (= "fake-token-2" (token/access-token s)))
        (is (= 2 (count (idp/token-requests server))))))))

;; ---------------------------------------------------------------------------
;; What can go wrong

(deftest a-refusal-says-the-oauth-code-and-not-the-description
  (with-idp
    (fn [server]
      (idp/serve-token! server {:status 401
                                :body "{\"error\":\"unauthorized_client\",\"error_description\":\"Invalid client or Invalid client credentials\"}"})
      (let [[s _] (source-for server)
            e (failure s)]
        (is (= {:type ::token/unavailable :reason :rejected :status 401 :error "unauthorized_client"}
               (ex-data e)))
        (is (not (str/includes? (all-text-of e) "Invalid client")))
        (is (false? (token/available? (first (source-for server)))))))))

(deftest what-the-endpoint-says-is-not-echoed-unless-it-is-an-oauth-code
  (with-idp
    (fn [server]
      (doseq [body ["{\"error\":\"<script>alert(1)</script>\"}"
                    "{\"error\":\"a very long one, longer than any code of the standard, with spaces\"}"
                    "{\"error\":42}"
                    "<html>no JSON</html>"
                    "[1,2,3]"
                    ""]]
        (idp/serve-token! server {:status 400 :body body})
        (let [[s _] (source-for server)
              data (ex-data (failure s))]
          (is (= :rejected (:reason data)) body)
          (is (not (contains? data :error)) body))))))

(deftest a-server-error-is-a-server-error
  (with-idp
    (fn [server]
      (doseq [status [500 502 503]]
        (idp/serve-token! server {:status status :body "<html>Bad gateway, host db-7.internal</html>"})
        (let [[s _] (source-for server)
              e (failure s)]
          (is (= {:type ::token/unavailable :reason :server :status status} (ex-data e)))
          (is (not (str/includes? (all-text-of e) "db-7"))))))))

(deftest an-answer-that-is-not-a-bearer-grant-is-refused
  (with-idp
    (fn [server]
      (doseq [[label body] {"not JSON" "access_token=abc"
                            "a list" "[\"abc\"]"
                            "no token" "{\"expires_in\":300,\"token_type\":\"Bearer\"}"
                            "no lifetime" "{\"access_token\":\"abc\",\"token_type\":\"Bearer\"}"
                            "a lifetime of zero" "{\"access_token\":\"abc\",\"expires_in\":0,\"token_type\":\"Bearer\"}"
                            "another type of token" "{\"access_token\":\"abc\",\"expires_in\":300,\"token_type\":\"mac\"}"}]
        (idp/serve-token! server {:status 200 :body body})
        (let [[s _] (source-for server)]
          (is (= :bad-response (:reason (ex-data (failure s)))) label))))))

(deftest a-token-type-in-any-case-is-fine-and-so-is-none
  (with-idp
    (fn [server]
      (doseq [body ["{\"access_token\":\"abc\",\"expires_in\":300,\"token_type\":\"bearer\"}"
                    "{\"access_token\":\"abc\",\"expires_in\":300}"
                    "{\"access_token\":\"abc\",\"expires_in\":299.5,\"token_type\":\"Bearer\",\"scope\":\"profile\",\"not-before-policy\":0}"]]
        (idp/serve-token! server {:status 200 :body body})
        (let [[s _] (source-for server)]
          (is (= "abc" (token/access-token s)) body))))))

(deftest a-slow-endpoint-is-given-up-on
  (with-idp
    (fn [server]
      (idp/serve-token! server {:delay-ms 1500})
      (let [[s _] (source-for server :timeout-ms 200)
            started (System/nanoTime)
            e (failure s)]
        (is (= :timeout (:reason (ex-data e))))
        (is (< (/ (- (System/nanoTime) started) 1e6) 1200) "and it did not wait for the answer")))))

(deftest an-endpoint-that-is-not-there-cannot-be-reached
  (let [server (idp/start! [])
        uri (:token-uri server)]
    (idp/stop! server)
    (let [s (token/source {:token-uri uri :client-id "pulse-scheduler" :client-secret (config/secret secret)})
          e (failure s)]
      (is (= :connect (:reason (ex-data e))))
      (is (some? (ex-cause e)) "with the cause, for the log"))))

;; ---------------------------------------------------------------------------
;; The secret

(deftest the-secret-is-nowhere-in-what-comes-out
  (with-idp
    (fn [server]
      (testing "when the endpoint refuses, in the exception, its causes and the log"
        (idp/serve-token! server {:status 401 :body "{\"error\":\"invalid_client\"}"})
        (let [[s _] (source-for server)
              {:keys [signals value]} (t/with-signals true (failure s))
              text (str (all-text-of value) (pr-str signals))]
          (is (seq signals))
          (doseq [form secret-forms]
            (is (not (str/includes? text form)) form))))
      (testing "when it is not there at all"
        (let [[s _] (source-for (assoc server :token-uri "http://127.0.0.1:1/token"))
              {:keys [signals value]} (t/with-signals true (failure s))
              text (str (all-text-of value) (pr-str signals))]
          (doseq [form secret-forms]
            (is (not (str/includes? text form)) form))))
      (testing "when all goes well, in the log"
        (idp/serve-token! server {:status 200})
        (let [[s _] (source-for server)
              {:keys [signals]} (t/with-signals true (token/access-token s))
              text (pr-str signals)]
          (is (seq signals))
          (doseq [form secret-forms]
            (is (not (str/includes? text form)) form))))
      (testing "and in the keeper itself, which can be printed"
        (let [[s _] (source-for server)]
          (doseq [form secret-forms]
            (is (not (str/includes? (pr-str s) form)) form)))))))
