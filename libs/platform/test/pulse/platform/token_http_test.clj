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
