(ns pulse.platform.jwt-test
  "Unit tests of the token facade. They need no network and no Keycloak: tokens are minted here
  with throwaway RSA keys, and the key set is served by a small HTTP server on a random local
  port (`pulse.platform.test.idp`), which is what lets caching, key rotation and outages be
  tested for real. The real Keycloak is covered by `jwt-keycloak-test` (`bb test:it`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.idp :as idp :refer [break! hits mint serve!]])
  (:import (com.nimbusds.jose JWEAlgorithm JWSAlgorithm JWSHeader$Builder)
           (com.nimbusds.jose.crypto MACSigner)
           (com.nimbusds.jose.jwk JWKSet KeyUse)
           (com.nimbusds.jose.jwk.source ImmutableJWKSet)
           (com.nimbusds.jwt JWTClaimsSet SignedJWT)
           (java.nio.charset StandardCharsets)
           (java.util Base64 List Map)))

;; ---------------------------------------------------------------------------
;; Fixtures: keys, tokens and a fake identity provider

(def ^:private k1 (idp/signing-key "k1"))
(def ^:private k2 (idp/signing-key "k2"))
(def ^:private k3 (idp/signing-key "k3"))
;; Keycloak publishes an encryption key next to its signature key; tokens must never use it.
(def ^:private enc-key (idp/generate-key "enc" KeyUse/ENCRYPTION JWEAlgorithm/RSA_OAEP))
;; Never published by the fake identity provider.
(def ^:private rogue (idp/signing-key "rogue"))

(defn- b64 [^String s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes s StandardCharsets/UTF_8)))

(defn- unb64 [^String s]
  (String. (.decode (Base64/getUrlDecoder) s) StandardCharsets/UTF_8))

(def ^:private now 1760000000)
(def ^:private issuer "http://idp.test/realms/pulse")
(def ^:private audience "pulse-api")

(def ^:private claims
  "The shape of a Keycloak access token."
  {:iss issuer
   :aud [audience "account"]
   :sub "9f1c0a52"
   :azp "pulse-spa"
   :typ "Bearer"
   :iat now
   :exp (+ now 300)
   :fhirUser "Patient/pt-ana"
   :realm_access {:roles ["patient" "offline_access"]}})

(defn- with-verifier
  "Calls `(f idp verifier)` with a fake identity provider serving `keys` and a verifier that
  reads from it, with its clock frozen at `now`. `jwks-options` and `verifier-options` are
  merged over the defaults of `jwt/jwks-source` and `jwt/verifier`."
  [keys jwks-options verifier-options f]
  (let [server (idp/start! keys)
        source (jwt/jwks-source (merge {:uri (:uri server)} jwks-options))]
    (try
      (f server (jwt/verifier (merge {:jwks-source source
                                      :issuer issuer
                                      :audience audience
                                      :token-type "Bearer"
                                      :clock (constantly now)}
                                     verifier-options)))
      (finally
        (jwt/close! source)
        (idp/stop! server)))))

(defn- error-type [result]
  (get-in result [:error :type]))

;; ---------------------------------------------------------------------------
;; Policy: check-claims is pure

(def ^:private policy {:issuer issuer :audience audience :leeway-s 30 :token-type "Bearer"})

(deftest claims-policy
  (doseq [[label expected change]
          [["a good token" nil identity]
           ["expired, just past the leeway" :expired #(assoc % :exp (- now 30))]
           ["expired, just within the leeway" nil #(assoc % :exp (- now 29))]
           ["not valid yet, just past the leeway" :not-yet-valid #(assoc % :nbf (+ now 31))]
           ["not valid yet, just within the leeway" nil #(assoc % :nbf (+ now 30))]
           ["nbf is not a number" :malformed #(assoc % :nbf "soon")]
           ["another issuer" :bad-issuer #(assoc % :iss "http://evil.test/realms/pulse")]
           ["issuer with a trailing slash" :bad-issuer #(assoc % :iss (str issuer "/"))]
           ["no issuer" :missing-claim #(dissoc % :iss)]
           ["no audience" :bad-audience #(dissoc % :aud)]
           ["another audience" :bad-audience #(assoc % :aud ["account"])]
           ["audience as a plain string" nil #(assoc % :aud audience)]
           ["audience that merely contains ours" :bad-audience #(assoc % :aud (str audience "-evil"))]
           ["audience of the wrong type" :bad-audience #(assoc % :aud {:x 1})]
           ["no subject" :missing-claim #(dissoc % :sub)]
           ["no expiry" :missing-claim #(dissoc % :exp)]
           ["expiry as a string" :missing-claim #(assoc % :exp "1760000300")]
           ["an ID token" :wrong-token-type #(assoc % :typ "ID")]
           ["a refresh token" :wrong-token-type #(assoc % :typ "Refresh")]
           ["no token type" :wrong-token-type #(dissoc % :typ)]]]
    (testing label
      (is (= expected (:type (jwt/check-claims (change claims) policy now))))))
  (testing "the token type is only checked when configured"
    (is (nil? (jwt/check-claims (assoc claims :typ "ID") (dissoc policy :token-type) now)))))

(defn- claims-with-times [exp nbf]
  (cond-> {:iss issuer :aud audience :sub "s" :exp exp :typ "Bearer"}
    nbf (assoc :nbf nbf)))

(defspec validity-window-is-nbf-to-exp-widened-by-the-leeway 1000
  ;; The oracle is the RFC 7519 window, written as an interval: nbf - leeway <= now < exp + leeway.
  (prop/for-all [now (gen/choose 1000 5000)
                 exp (gen/choose 1000 5000)
                 nbf (gen/one-of [(gen/return nil) (gen/choose 1000 5000)])
                 leeway (gen/choose 0 300)]
    (let [error (jwt/check-claims (claims-with-times exp nbf) (assoc policy :leeway-s leeway) now)
          in-window? (and (<= (- (or nbf now) leeway) now) (< now (+ exp leeway)))]
      (and (= in-window? (nil? error))
           (or in-window? (contains? #{:expired :not-yet-valid} (:type error)))))))

(defspec more-leeway-never-rejects-what-less-leeway-accepts 500
  (prop/for-all [now (gen/choose 1000 5000)
                 exp (gen/choose 1000 5000)
                 nbf (gen/one-of [(gen/return nil) (gen/choose 1000 5000)])
                 leeway (gen/choose 0 300)
                 extra (gen/choose 0 300)]
    (let [accepted? (fn [l] (nil? (jwt/check-claims (claims-with-times exp nbf) (assoc policy :leeway-s l) now)))]
      (or (not (accepted? leeway)) (accepted? (+ leeway extra))))))

;; ---------------------------------------------------------------------------
;; Verification

(deftest a-valid-token-gives-its-claims-as-clojure-data
  (with-verifier [k1 enc-key] {} {}
    (fn [_ v]
      (is (= claims (:ok (jwt/verify v (mint k1 claims))))
          "nothing is lost or renamed, nested objects and arrays included"))))

(deftest signature-and-key-errors
  (with-verifier [k1 k2 enc-key] {} {}
    (fn [_ v]
      (let [token (mint k1 claims)
            [h p s] (str/split token #"\.")
            secret (.getBytes (str (.toPublicJWK k1)) StandardCharsets/UTF_8)
            hs256 (doto (SignedJWT. (-> (JWSHeader$Builder. JWSAlgorithm/HS256) (.keyID "k1") (.build))
                                    (JWTClaimsSet/parse ^Map (idp/->java claims)))
                    (.sign (MACSigner. secret)))]
        (doseq [[label expected candidate]
                [["a good token" nil token]
                 ["signed by a key the provider does not publish, claiming a known kid" :bad-signature (mint rogue claims {:kid "k1"})]
                 ["a kid the provider does not publish" :unknown-key (mint k1 claims {:kid "nope"})]
                 ["the provider's encryption key used to sign" :unknown-key (mint enc-key claims)]
                 ["no kid, signed by a published key" nil (mint k1 claims {:kid nil})]
                 ["no kid, signed by an unknown key" :bad-signature (mint rogue claims {:kid nil})]
                 ["HS256 with the public key as the secret" :unsupported-algorithm (.serialize hs256)]
                 ["RS512 where only RS256 is accepted" :unsupported-algorithm (mint k1 claims {:alg JWSAlgorithm/RS512})]
                 ["alg none" :malformed (str (b64 "{\"alg\":\"none\",\"typ\":\"JWT\"}") "." p ".")]
                 ["a role edited after signing" :bad-signature
                  (str/join "." [h (b64 (str/replace (unb64 p) "patient" "clinic-admin")) s])]
                 ["a truncated signature" :bad-signature (str/join "." [h p (subs s 0 (- (count s) 12))])]
                 ["a signature of the wrong key: the kid names another published key" :bad-signature (mint k1 claims {:kid "k2"})]
                 ["no signature at all (an unsecured JWT shape)" :malformed (str h "." p ".")]]]
          (testing label
            (is (= expected (error-type (jwt/verify v candidate))))))))))

(deftest malformed-input
  (with-verifier [k1] {} {}
    (fn [_ v]
      (doseq [[label input] [["nil" nil]
                             ["a number" 42]
                             ["empty" ""]
                             ["one segment" "abc"]
                             ["three segments of garbage" "a.b.c"]
                             ["a JWE-shaped string" "a.b.c.d.e"]
                             ["an Authorization header, not a token" "Bearer abc.def.ghi"]
                             ["valid base64 that is not JSON" (str/join "." (map b64 ["not json" "not json" "sig"]))]
                             ["far too long" (apply str (repeat 20000 "a"))]]]
        (testing label
          (is (= :malformed (error-type (jwt/verify v input)))))))))

(deftest claims-are-only-read-after-the-signature-checks-out
  (with-verifier [k1] {} {}
    (fn [_ v]
      (testing "an expired token forged with a wrong key is a bad signature, not an expired token"
        (is (= :bad-signature (error-type (jwt/verify v (mint rogue (assoc claims :exp (- now 3600)) {:kid "k1"}))))))
      (testing "a properly signed one is judged on its claims"
        (is (= :expired (error-type (jwt/verify v (mint k1 (assoc claims :exp (- now 3600)))))))
        (is (= :bad-audience (error-type (jwt/verify v (mint k1 (assoc claims :aud ["other-api"]))))))
        (is (= :bad-issuer (error-type (jwt/verify v (mint k1 (assoc claims :iss "http://evil.test")))))))
      (testing "time comes from the verifier's clock"
        (is (nil? (error-type (jwt/verify v (mint k1 (assoc claims :exp (+ now 10)))))))))))

(deftest verifier-configuration-is-validated
  (let [source (ImmutableJWKSet. (JWKSet. ^List [(.toPublicJWK k1)]))
        base {:jwks-source source :issuer issuer :audience audience}]
    (is (map? (jwt/verifier base)))
    (doseq [[label options] [["no key source" {:jwks-source nil}]
                             ["no issuer" {:issuer nil}]
                             ["a blank audience" {:audience " "}]
                             ["no algorithms" {:algorithms []}]
                             ["an HMAC algorithm" {:algorithms ["HS256"]}]
                             ["alg none" {:algorithms ["none"]}]
                             ["a negative leeway" {:leeway-s -1}]
                             ["a leeway of an hour" {:leeway-s 3600}]]]
      (testing label
        (is (= ::jwt/invalid-config
               (try (jwt/verifier (merge base options))
                    nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ---------------------------------------------------------------------------
;; Authorization header and WWW-Authenticate

(deftest bearer-header
  (is (= "abc.def.ghi" (jwt/bearer-token "Bearer abc.def.ghi")))
  (is (= "abc" (jwt/bearer-token "bearer abc")) "the scheme is case-insensitive")
  (is (= "abc" (jwt/bearer-token "BEARER   abc")))
  (doseq [header [nil "" "Bearer" "Bearer " "Basic dXNlcjpwYXNz" "Bearer a b" "Bearer \"q\"" "Bearer a\r\nX-Injected: 1" 42]]
    (is (nil? (jwt/bearer-token header)) (pr-str header))))

(deftest www-authenticate
  (is (= "Bearer realm=\"pulse-api\"" (jwt/challenge "pulse-api"))
      "no credentials: no error code (RFC 6750 section 3.1)")
  (is (= "Bearer realm=\"pulse-api\", error=\"invalid_token\", error_description=\"The access token expired\""
         (jwt/challenge "pulse-api" :expired)))
  (is (= "Bearer realm=\"pulse-api\"" (jwt/challenge "pulse-api" :keys-unavailable))
      "a failure of ours is not reported as an invalid token")
  (is (= "Bearer realm=\"a\\\"b\\\\c\"" (jwt/challenge "a\"b\\c")) "quotes and backslashes are escaped")
  (testing "every error has a status, and a description that reveals nothing configured"
    (doseq [[type {:keys [status description]}] jwt/errors]
      (is (#{401 503} status) (str type))
      (is (not (str/includes? description audience)) (str type))
      (is (not (str/includes? description "idp.test")) (str type)))))

;; ---------------------------------------------------------------------------
;; The key set: caching, rotation, outages

(deftest the-key-set-is-fetched-once-and-cached
  (with-verifier [k1 enc-key] {} {}
    (fn [idp v]
      (let [token (mint k1 claims)]
        (is (every? :ok (repeatedly 50 #(jwt/verify v token))))
        (is (= 1 (hits idp)) "fifty verifications, one fetch")))))

(deftest key-rotation-is-picked-up-without-restarting
  ;; Nimbus's rate limit lets a fetch and one more through per window: enough for the first
  ;; load and one refresh, which is exactly what a rotation needs.
  (with-verifier [k1] {:min-refresh-interval-ms 400} {}
    (fn [idp v]
      (is (:ok (jwt/verify v (mint k1 claims))))
      (is (= 1 (hits idp)))
      (testing "the provider rotates: an unknown kid is picked up at once"
        (serve! idp [k1 k2])
        (is (:ok (jwt/verify v (mint k2 claims))))
        (is (= 2 (hits idp))))
      (testing "a second rotation inside the same window is held back"
        (serve! idp [k1 k2 k3])
        (is (= :unknown-key (error-type (jwt/verify v (mint k3 claims)))))
        (is (= 2 (hits idp))))
      (Thread/sleep 500)
      (testing "and picked up once the window has passed"
        (is (:ok (jwt/verify v (mint k3 claims))))
        (is (= 3 (hits idp))))
      (testing "tokens of the older keys never needed a fetch"
        (is (:ok (jwt/verify v (mint k1 claims))))
        (is (:ok (jwt/verify v (mint k2 claims))))
        (is (= 3 (hits idp)))))))

(deftest forged-kids-cannot-flood-the-identity-provider
  (with-verifier [k1] {} {}
    (fn [idp v]
      (is (every? #{:unknown-key}
                  (map #(error-type (jwt/verify v (mint k1 claims {:kid (str "forged-" %)}))) (range 100))))
      (is (= 2 (hits idp)) "one hundred unknown kids: the first load and one refresh, no more")
      (is (:ok (jwt/verify v (mint k1 claims))) "and real tokens are unaffected"))))

(deftest the-last-good-key-set-survives-an-identity-provider-outage
  (with-verifier [k1] {:ttl-ms 100 :min-refresh-interval-ms 50} {}
    (fn [idp v]
      (let [token (mint k1 claims)]
        (is (:ok (jwt/verify v token)))
        (break! idp)
        (Thread/sleep 250)
        (testing "the cache has expired, the refetch fails, and the stale keys are still served"
          (is (:ok (jwt/verify v token)))
          (is (< 1 (hits idp)) "a refetch was attempted")
          (is (true? (jwt/keys-available? (:jwks-source v)))))))))

(deftest cold-start-without-an-identity-provider
  (with-verifier [k1] {:min-refresh-interval-ms 100} {}
    (fn [idp v]
      (break! idp)
      (testing "no keys at all is our problem, not an invalid token"
        (is (= :keys-unavailable (error-type (jwt/verify v (mint k1 claims)))))
        (is (false? (jwt/keys-available? (:jwks-source v)))))
      (serve! idp [k1])
      (Thread/sleep 200)
      (testing "and it recovers by itself"
        (is (:ok (jwt/verify v (mint k1 claims))))
        (is (true? (jwt/keys-available? (:jwks-source v))))))))

(deftest cold-start-then-recovery-inside-the-rate-limit-window
  ;; Two failed fetches use up the window's allowance. When the provider is back, a third
  ;; attempt is refused by the limiter, and that must not look like a bad token: with no keys
  ;; at all, the answer stays "unavailable" (503) until the window ends.
  (with-verifier [k1] {:min-refresh-interval-ms 400} {}
    (fn [idp v]
      (break! idp)
      (is (= :keys-unavailable (error-type (jwt/verify v (mint k1 claims)))))
      (is (= :keys-unavailable (error-type (jwt/verify v (mint k1 claims)))))
      (serve! idp [k1])
      (is (= :keys-unavailable (error-type (jwt/verify v (mint k1 claims))))
          "never :unknown-key, which would tell clients that their valid token is bad")
      (is (false? (jwt/keys-available? (:jwks-source v))))
      (Thread/sleep 500)
      (is (:ok (jwt/verify v (mint k1 claims))) "and it recovers by itself"))))

(deftest concurrent-verification-is-safe-while-the-cache-expires
  (with-verifier [k1] {:ttl-ms 30 :min-refresh-interval-ms 10} {}
    (fn [_ v]
      (let [token (mint k1 claims)
            results (doall (pmap (fn [_] (doall (repeatedly 60 #(do (Thread/sleep 3) (jwt/verify v token)))))
                                 (range 8)))]
        (is (every? :ok (apply concat results)))))))

;; ---------------------------------------------------------------------------
;; Robustness: whatever arrives, the answer is data

(def ^:private static-verifier
  "No network: a fixed key set."
  (delay (jwt/verifier {:jwks-source (ImmutableJWKSet. (JWKSet. ^List [(.toPublicJWK k1)]))
                        :issuer issuer
                        :audience audience
                        :token-type "Bearer"
                        :clock (constantly now)})))

(def ^:private token-like-garbage
  (gen/one-of [gen/string
               gen/string-ascii
               (gen/fmap #(str/join "." %) (gen/vector gen/string-alphanumeric 0 6))
               (gen/fmap #(str/join "." (map b64 %)) (gen/vector gen/string-ascii 3))
               (gen/fmap #(str/join "." (map b64 %)) (gen/vector gen/string-alphanumeric 3))]))

(defn- an-error? [result]
  (and (map? result)
       (not (contains? result :ok))
       (contains? jwt/errors (error-type result))))

(defspec verify-answers-with-data-whatever-it-is-given 500
  (prop/for-all [token token-like-garbage]
    (an-error? (jwt/verify @static-verifier token))))

(def ^:private b64url-chars
  (vec "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"))

(def ^:private valid-token (delay (mint k1 claims)))

(defspec no-edit-to-the-header-or-payload-is-accepted 500
  ;; Any one-character change to the signed part changes the bytes that were signed, so the
  ;; signature no longer matches. (The last character of a segment is skipped: it carries
  ;; padding bits, and changing it can decode to the same bytes.)
  (prop/for-all [segment (gen/choose 0 1)
                 position (gen/choose 0 100000)
                 replacement (gen/elements b64url-chars)]
    (let [parts (str/split @valid-token #"\.")
          part (nth parts segment)
          i (mod position (dec (count part)))
          edited (if (= replacement (nth part i))
                   part
                   (str (subs part 0 i) replacement (subs part (inc i))))
          token (str/join "." (assoc parts segment edited))]
      (or (= edited part)
          (an-error? (jwt/verify @static-verifier token))))))
