(ns pulse.platform.token-details-test
  "What the mutation analysis of the portal's sign-in found missing in the tests of the token endpoint (ADR-0028): the endpoint is
  used with no keeper behind it, by the exchange of a code, so what the keeper would have refused it must refuse itself."
  (:require [clojure.test :refer [deftest is]]
            [pulse.platform.config :as config]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.token :as token]))

(defn- with-idp [f]
  (let [server (idp/start! [])]
    (try (f server)
         (finally (idp/stop! server)))))

(defn- post-for [server]
  (token/endpoint {:token-uri (:token-uri server) :client-id "pulse-web" :client-secret (config/secret "s")}))

(deftest the-names-and-the-values-of-a-form-are-both-encoded
  (with-idp
    (fn [server]
      ((post-for server) [["a b&c" "d e=f"]])
      (is (= "a+b%26c=d+e%3Df" (:body (first (idp/token-requests server))))))))

(deftest a-success-that-is-not-a-grant-is-refused-by-the-endpoint-whoever-asks
  ;; the exchange of a code has no keeper to refuse it, and a person must not be signed in with what is no token
  (with-idp
    (fn [server]
      (doseq [[label body] {"not JSON" "access_token=abc"
                            "no token" "{\"expires_in\":300,\"token_type\":\"Bearer\"}"
                            "a token that is blank" "{\"access_token\":\" \",\"expires_in\":300,\"token_type\":\"Bearer\"}"
                            "no lifetime" "{\"access_token\":\"abc\",\"token_type\":\"Bearer\"}"
                            "a lifetime of zero" "{\"access_token\":\"abc\",\"expires_in\":0,\"token_type\":\"Bearer\"}"
                            "a lifetime that is a text" "{\"access_token\":\"abc\",\"expires_in\":\"300\",\"token_type\":\"Bearer\"}"
                            "another type of token" "{\"access_token\":\"abc\",\"expires_in\":300,\"token_type\":\"mac\"}"}]
        (idp/serve-token! server {:status 200 :body body})
        (is (= :bad-response (:reason (ex-data (try ((post-for server) {"grant_type" "authorization_code"})
                                                    (catch clojure.lang.ExceptionInfo e e)))))
            label)))))
