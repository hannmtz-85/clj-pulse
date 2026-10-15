(ns pulse.platform.token-keycloak-test
  "Integration test: the scheduler's own access token from the real Keycloak, with the real secret
  of the realm, checked by the token facade.

  Needs the stack (`bb up`) and `bb` on the PATH, and runs with `bb test:it`. The secret is read
  from `.env` by `bb`, as it is for compose and the realm import, and is never printed. Nothing in
  Keycloak is changed."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pulse.platform.config :as config]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.token :as token])
  (:import (java.time Instant)))

(def ^:private issuer (or (System/getenv "KEYCLOAK_ISSUER") "http://localhost:8080/realms/pulse"))

(def ^:private token-uri (str issuer "/protocol/openid-connect/token"))

(def ^:dynamic *jwks* nil)

(defn- with-jwks [f]
  (let [source (jwt/jwks-source {:uri (str issuer "/protocol/openid-connect/certs")})]
    (try (binding [*jwks* source] (f))
         (finally (jwt/close! source)))))

(use-fixtures :once with-jwks)

(def ^:private scheduler-secret
  (delay
    ;; no double quotes in the expression: on Windows they do not survive the trip to bb.exe
    (let [{:keys [exit out err]} (shell/sh "bb" "-e" "(require 'env) (print (env/get-var (name :SCHEDULER_CLIENT_SECRET)))")]
      (when (or (not (zero? exit)) (str/blank? out))
        (throw (ex-info "could not read SCHEDULER_CLIENT_SECRET with bb: is `.env` there (`bb up`)?" {:err err})))
      out)))

(defn- keeper [secret c]
  (token/source {:token-uri token-uri
                 :client-id "pulse-scheduler"
                 :client-secret (config/secret secret)
                 :clock (:clock c)}))

(defn- verified [access-token]
  (:ok (jwt/verify (jwt/verifier {:jwks-source *jwks* :issuer issuer :audience "pulse-api" :token-type "Bearer"})
                   access-token)))

(deftest ^:integration keycloak-gives-the-scheduler-its-token-and-the-keeper-renews-it
  (let [c (clock/clock (str (Instant/now)))
        s (keeper @scheduler-secret c)
        first-token (token/access-token s)
        claims (verified first-token)]
    (testing "a token the facade accepts, of the service account of the scheduler"
      (is (some? claims))
      (is (= "pulse-scheduler" (:azp claims)))
      (is (some #{"rules-engine"} (get-in claims [:realm_access :roles])))
      (is (contains? (set (:aud claims)) "pulse-api"))
      (is (= issuer (:iss claims))))
    (testing "kept while it is fresh"
      (clock/advance! c 200000)
      (is (= first-token (token/access-token s))))
    (testing "renewed early: 240 s into the 300 s it lives, by the clock the keeper was given"
      (clock/advance! c 40000)
      (let [renewed (token/access-token s)]
        (is (not= first-token renewed))
        (is (some? (verified renewed)) "and the new one is good")
        (is (= "pulse-scheduler" (:azp (verified renewed))))))))

(deftest ^:integration keycloak-refuses-a-wrong-secret-and-the-keeper-says-so-without-repeating-it
  (let [wrong "not-the-secret-of-the-scheduler"
        s (keeper wrong (clock/clock (str (Instant/now))))
        e (try (token/access-token s)
               (catch clojure.lang.ExceptionInfo e e))
        said (str/join " " (map (fn [x] (str (ex-message x) " " (pr-str (ex-data x))))
                                (take-while some? (iterate ex-cause e))))]
    (is (= {:type ::token/unavailable :reason :rejected :status 401} (select-keys (ex-data e) [:type :reason :status])))
    (is (string? (:error (ex-data e))) "Keycloak's own code for it")
    (is (not (str/includes? said wrong)))
    (is (false? (token/available? s)))))
