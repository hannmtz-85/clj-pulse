(ns pulse.platform.jwt-keycloak-test
  "Integration test: access tokens issued by the real Keycloak, checked by the token facade.

  Needs the stack (`bb up`) and `bb` on the PATH, and runs with `bb test:it`. Signing in goes
  through `bb token`, the headless authorization-code flow with PKCE that the SPA uses. Nothing
  in Keycloak is changed."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pulse.platform.jwt :as jwt])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def ^:private issuer (or (System/getenv "KEYCLOAK_ISSUER") "http://localhost:8080/realms/pulse"))

(def ^:dynamic *source* nil)

(defn- with-source [f]
  (let [source (jwt/jwks-source {:uri (str issuer "/protocol/openid-connect/certs")})]
    (try (binding [*source* source] (f))
         (finally (jwt/close! source)))))

(use-fixtures :once with-source)

(def ^:private token-for
  "An access token for a demo user, or for `scheduler`, its service account. Signing in is the
  slow part, so each token is fetched once (they live for 5 minutes)."
  (memoize
   (fn [who]
     (let [{:keys [exit out err]} (shell/sh "bb" "token" who)]
       (when-not (zero? exit)
         (throw (ex-info "`bb token` failed: is the stack up (`bb up`)?" {:who who :err err})))
       (str/trim out)))))

(defn- verifier [& {:as options}]
  (jwt/verifier (merge {:jwks-source *source*
                        :issuer issuer
                        :audience "pulse-api"
                        :token-type "Bearer"}
                       options)))

(defn- error-type [result] (get-in result [:error :type]))

(defn- b64 [^String s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes s StandardCharsets/UTF_8)))

(defn- unb64 [^String s]
  (String. (.decode (Base64/getUrlDecoder) s) StandardCharsets/UTF_8))

(deftest ^:integration keycloak-tokens-verify
  (doseq [[who role fhir-user azp]
          [["ana.garcia@pulse.test" "patient" "Patient/pt-ana" "pulse-spa"]
           ["dr.rivera@pulse.test" "practitioner" "Practitioner/pr-rivera" "pulse-spa"]
           ["admin@pulse.test" "clinic-admin" nil "pulse-spa"]
           ["scheduler" "rules-engine" nil "pulse-scheduler"]]]
    (testing who
      (let [{:keys [ok error]} (jwt/verify (verifier) (token-for who))]
        (is (nil? error))
        (is (= issuer (:iss ok)))
        (is (contains? (set (:aud ok)) "pulse-api"))
        (is (= "Bearer" (:typ ok)))
        (is (= azp (:azp ok)))
        (is (= fhir-user (:fhirUser ok)))
        (is (some #{role} (get-in ok [:realm_access :roles])))))))

(deftest ^:integration keycloak-tokens-are-rejected-when-they-should-be
  (let [token (token-for "ana.garcia@pulse.test")
        [h p s] (str/split token #"\.")
        i (quot (count s) 2)]
    (testing "the realm's key set is reachable, and its encryption key does not get in the way"
      (is (true? (jwt/keys-available? *source*))))
    (testing "a token meant for another API"
      (is (= :bad-audience (error-type (jwt/verify (verifier :audience "other-api") token)))))
    (testing "a token from another issuer"
      (is (= :bad-issuer (error-type (jwt/verify (verifier :issuer "http://evil.test/realms/pulse") token)))))
    (testing "an hour later, past the 5 minutes of the access token plus the leeway"
      (let [later #(+ (quot (System/currentTimeMillis) 1000) 3600)]
        (is (= :expired (error-type (jwt/verify (verifier :clock later) token))))))
    (testing "one character of the signature changed"
      (let [flipped (str (subs s 0 i) (if (= \A (nth s i)) "B" "A") (subs s (inc i)))]
        (is (= :bad-signature (error-type (jwt/verify (verifier) (str/join "." [h p flipped])))))))
    (testing "a role edited after signing"
      (let [forged (b64 (str/replace (unb64 p) "\"patient\"" "\"clinic-admin\""))]
        (is (not= p forged) "the payload really contains the role")
        (is (= :bad-signature (error-type (jwt/verify (verifier) (str/join "." [h forged s])))))))))
