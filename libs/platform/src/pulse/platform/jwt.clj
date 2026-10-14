(ns pulse.platform.jwt
  "Verification of OAuth2 access tokens (signed JWTs) for a resource server. JVM only.

  Nimbus JOSE+JWT does the JOSE work: parsing, choosing the key by `kid`, checking the
  signature, and fetching and caching the identity provider's JWKS. This namespace is the thin
  Clojure facade around it. The token policy (issuer, audience, expiry, not-before, token
  type) is plain Clojure over a claims map, in `check-claims`, so it can be tested without
  keys, network or clock.

  `verify` never throws because of a bad token. It returns data, either `{:ok claims}` or
  `{:error {:type ... :message ...}}`, where `:type` is a key of `errors`. `:message` is for
  the logs; what a client may be told is in `errors` and `challenge`.

  Not covered, on purpose: revocation or introspection (validation is stateless and Keycloak
  access tokens live 5 minutes), an allow-list of `azp` clients, `jti` replay checks, and
  sender-constrained tokens (DPoP, mTLS)."
  (:require [clojure.string :as str])
  (:import (com.nimbusds.jose JOSEException JWSAlgorithm JWSAlgorithm$Family KeySourceException)
           (com.nimbusds.jose.crypto.factories DefaultJWSVerifierFactory)
           (com.nimbusds.jose.jwk JWKMatcher$Builder JWKSelector KeyUse)
           (com.nimbusds.jose.jwk.source JWKSource JWKSourceBuilder RateLimitReachedException)
           (com.nimbusds.jose.proc JWSVerificationKeySelector)
           (com.nimbusds.jose.util DefaultResourceRetriever)
           (com.nimbusds.jwt SignedJWT)
           (java.io Closeable)
           (java.net URI)
           (java.security Key)
           (java.text ParseException)
           (java.util List Map Set)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Errors

(defn- invalid [description]
  {:status 401 :code "invalid_token" :description description})

(def errors
  "Every way a request can fail to authenticate, as data. `:status` is the HTTP status,
  `:code` the RFC 6750 error code (nil when the fault is ours, not the client's) and
  `:description` the generic text a client may see. It never reveals what was expected, such
  as the issuer or the audience."
  {:malformed (invalid "The access token is malformed")
   :unsupported-algorithm (invalid "The access token is signed with an unsupported algorithm")
   :unknown-key (invalid "The access token is signed with an unknown key")
   :bad-signature (invalid "The access token signature is invalid")
   :missing-claim (invalid "The access token lacks a required claim")
   :bad-issuer (invalid "The access token was issued by an unexpected issuer")
   :bad-audience (invalid "The access token is not meant for this API")
   :wrong-token-type (invalid "The token is not an access token")
   :expired (invalid "The access token expired")
   :not-yet-valid (invalid "The access token is not valid yet")
   ;; Keycloak is unreachable and no keys are cached: not the client's fault, so not a 401
   ;; (a 401 would send well-behaved clients into a pointless re-login loop).
   :keys-unavailable {:status 503 :code nil :description "Token verification is temporarily unavailable"}})

(defn- trunc
  "Messages end up in logs and may quote token content, which an attacker controls."
  [s]
  (let [s (str s)]
    (if (> (count s) 200) (str (subs s 0 200) "...") s)))

(defn- error [type message]
  {:type type :message (trunc message)})

(defn- quoted
  "A value as an HTTP quoted-string."
  [s]
  (str "\"" (str/replace s #"([\\\"])" "\\\\$1") "\""))

(defn challenge
  "The `WWW-Authenticate` header value (RFC 6750 §3) for `realm`. With an error `type` it names
  the RFC error and carries the generic description from `errors`. Without one it is the bare
  challenge that answers a request with no credentials: the RFC says not to include an error
  code there (§3.1)."
  ([realm]
   (str "Bearer realm=" (quoted realm)))
  ([realm type]
   (let [{:keys [code description]} (errors type)]
     (cond-> (challenge realm)
       code (str ", error=" (quoted code) ", error_description=" (quoted description))))))

(defn bearer-token
  "The token in an `Authorization` header value of the form `Bearer <token>` (the scheme is
  case-insensitive), or nil when the header is missing, uses another scheme or is not a
  well-formed bearer credential. Callers answer nil with the bare `challenge`."
  [authorization]
  (when (string? authorization)
    (second (re-matches #"(?i)Bearer +([A-Za-z0-9._~+/-]+=*)" authorization))))

;; ---------------------------------------------------------------------------
;; Policy: the claims of a token whose signature is already verified

(defn- audiences [aud]
  (cond (string? aud) #{aud}
        (sequential? aud) (set aud)
        :else #{}))

(defn check-claims
  "Checks the claims (keyword keys) of a token whose signature is already verified. Returns
  nil when the token is acceptable, otherwise the error, `{:type ... :message ...}`. Pure:
  `now` is in epoch seconds.

  Options: `:issuer` and `:audience` (exact matches, `aud` may be a string or an array),
  `:leeway-s` (the clock skew tolerated on `exp` and `nbf`), and optionally `:token-type`,
  checked against the `typ` claim: Keycloak sets it to \"Bearer\" on access tokens and to
  \"ID\" or \"Refresh\" on the others, which are signed with the same keys.

  A token is valid while `nbf - leeway <= now < exp + leeway` (RFC 7519 §4.1.4, §4.1.5)."
  [claims {:keys [issuer audience leeway-s token-type]} now]
  (let [{:keys [iss sub exp nbf aud typ]} claims]
    (cond
      (not (and (string? iss) (string? sub) (number? exp)))
      (error :missing-claim "iss, sub and exp are required")

      (and (some? nbf) (not (number? nbf)))
      (error :malformed "nbf is not a number")

      (not= issuer iss)
      (error :bad-issuer (str "issuer " (pr-str iss)))

      (not (contains? (audiences aud) audience))
      (error :bad-audience (str "audience " (pr-str aud) " does not include " (pr-str audience)))

      (and token-type (not= token-type typ))
      (error :wrong-token-type (str "typ " (pr-str typ) ", expected " (pr-str token-type)))

      (<= (+ exp leeway-s) now)
      (error :expired (str "exp " exp " plus leeway " leeway-s " is not after now " now))

      (and nbf (< now (- nbf leeway-s)))
      (error :not-yet-valid (str "nbf " nbf " minus leeway " leeway-s " is after now " now)))))

;; ---------------------------------------------------------------------------
;; Keys: the identity provider's JWKS

(def jwks-defaults
  "Defaults of `jwks-source`. Nimbus's own HTTP timeouts (0.5 s) are too tight for a Keycloak
  that was just started, and its 50 KB size limit is close to a realm with a few rotated keys."
  {:ttl-ms (* 5 60 1000)
   :refresh-timeout-ms 15000
   :min-refresh-interval-ms 10000
   :outage-ttl-ms (* 60 60 1000)
   :connect-timeout-ms 2000
   :read-timeout-ms 2000
   :max-size-bytes (* 128 1024)})

(defn jwks-source
  "A Nimbus `JWKSource` for the key set published at `:uri` (the `jwks_uri` of the identity
  provider). Options, with defaults in `jwks-defaults`:

  `:ttl-ms`                   how long a fetched set is served without fetching again
  `:refresh-timeout-ms`       how long a request waits for a fetch that another request started
  `:min-refresh-interval-ms`  rate limit on fetches: a fetch opens a window of this length in
                              which one more is allowed, and after that none until it ends. A
                              token with an unknown `kid` makes the source refetch (that is how
                              key rotation is picked up, at once), and this bounds what a flood
                              of forged `kid`s can cost: two fetches per window. Nimbus's own
                              default is 30 s; 10 s shortens how long a start-up or rotation
                              that hit the limit has to wait
  `:outage-ttl-ms`            when a fetch fails, keep serving the last good set for this long, so
                              restarting the identity provider does not stop the API
  `:connect-timeout-ms`, `:read-timeout-ms`, `:max-size-bytes`  limits of the HTTP fetch

  Close it with `close!` when the component stops."
  ^JWKSource [{:keys [uri] :as options}]
  (let [{:keys [ttl-ms refresh-timeout-ms min-refresh-interval-ms outage-ttl-ms
                connect-timeout-ms read-timeout-ms max-size-bytes]} (merge jwks-defaults options)
        retriever (DefaultResourceRetriever. (int connect-timeout-ms) (int read-timeout-ms) (int max-size-bytes))]
    (-> (JWKSourceBuilder/create (.toURL (URI/create uri)) retriever)
        ;; Nimbus 10 turns on refresh-ahead caching by default: a background thread refetches
        ;; before the TTL ends. Off here: a lazy cache has nothing to start or stop and costs
        ;; one short fetch per TTL.
        (.refreshAheadCache false)
        (.cache (long ttl-ms) (long refresh-timeout-ms))
        (.rateLimited (long min-refresh-interval-ms))
        (.outageTolerant (long outage-ttl-ms))
        (.build))))

(defn close!
  "Releases a source made by `jwks-source`."
  [^JWKSource source]
  (when (instance? Closeable source)
    (.close ^Closeable source)))

(defn keys-available?
  "True when signature keys can be had right now, from the cache or by fetching. For `/readyz`."
  [^JWKSource source]
  (try
    (let [selector (JWKSelector. (-> (JWKMatcher$Builder.) (.keyUse KeyUse/SIGNATURE) (.build)))]
      (boolean (seq (.get source selector nil))))
    (catch KeySourceException _ false)))

;; ---------------------------------------------------------------------------
;; Verification

(def ^:private max-token-length
  "Keycloak access tokens are about 1.5 KB. Anything much larger is not worth parsing."
  16384)

(defn- now-s []
  (quot (System/currentTimeMillis) 1000))

(defn verifier
  "A verifier is plain data: build it once and pass it to `verify`. Options:

  `:jwks-source`  a source from `jwks-source` (or any Nimbus `JWKSource`)
  `:issuer`       the exact `iss` to accept
  `:audience`     the value that `aud` must include
  `:algorithms`   accepted signature algorithms, default [\"RS256\"]. Only asymmetric ones are
                  allowed: the JWKS holds public keys, and an HMAC algorithm would invite the
                  classic attack of signing with the public key as the secret
  `:leeway-s`     clock skew tolerated on `exp` and `nbf`, 0 to 300, default 30
  `:token-type`   optional expected `typ` claim, \"Bearer\" for Keycloak access tokens
  `:clock`        a function returning epoch seconds, default the system clock (for tests)"
  [{:keys [jwks-source issuer audience algorithms leeway-s token-type clock]
    :or {algorithms ["RS256"] leeway-s 30 clock now-s}}]
  (let [algs (set (map #(JWSAlgorithm/parse ^String %) algorithms))]
    (when-not (and jwks-source (not (str/blank? issuer)) (not (str/blank? audience)))
      (throw (ex-info "A verifier needs :jwks-source, :issuer and :audience" {:type ::invalid-config})))
    (when-not (and (seq algs) (every? #(.contains JWSAlgorithm$Family/SIGNATURE %) algs))
      (throw (ex-info "Only asymmetric signature algorithms are accepted"
                      {:type ::invalid-config :algorithms algorithms})))
    (when-not (and (integer? leeway-s) (<= 0 leeway-s 300))
      (throw (ex-info ":leeway-s must be between 0 and 300 seconds" {:type ::invalid-config :leeway-s leeway-s})))
    {:jwks-source jwks-source
     :key-selector (JWSVerificationKeySelector. ^Set algs ^JWKSource jwks-source)
     :factory (DefaultJWSVerifierFactory.)
     :policy {:issuer issuer :audience audience :leeway-s leeway-s :token-type token-type}
     :clock clock}))

(defn- ->clj
  "A JSON value as Nimbus returns it (java.util maps and lists) as Clojure data: objects become
  maps with keyword keys, arrays become vectors."
  [x]
  (cond
    (instance? Map x) (into {} (map (fn [[k v]] [(keyword k) (->clj v)])) x)
    (instance? List x) (mapv ->clj x)
    :else x))

(defn- signature-valid? [^DefaultJWSVerifierFactory factory ^SignedJWT jwt ^Key key]
  (try
    (.verify jwt (.createJWSVerifier factory (.getHeader jwt) key))
    (catch JOSEException _ false)))

(defn- signature-error
  "Nil when `jwt` carries a valid signature from one of the keys the identity provider
  publishes for its header, otherwise the error. Nothing is read from the payload before this
  succeeds. The `jku`, `jwk` and `x5c` headers are ignored: keys only come from the JWKS."
  [{:keys [^JWSVerificationKeySelector key-selector factory]} ^SignedJWT jwt]
  (let [header (.getHeader jwt)
        alg (.getAlgorithm header)]
    (if-not (.isAllowed key-selector alg)
      (error :unsupported-algorithm (str "algorithm " (.getName alg) " is not accepted"))
      ;; The selector matches on key type, `use` (so the realm's encryption key is skipped),
      ;; algorithm and `kid`, and refetches the set when the `kid` is not in the cache.
      (let [candidates (.selectJWSKeys key-selector header nil)]
        (cond
          (empty? candidates) (error :unknown-key (str "no signature key for kid " (pr-str (.getKeyID header))))
          (not-any? #(signature-valid? factory jwt %) candidates) (error :bad-signature "the signature does not match")
          :else nil)))))

(defn verify
  "Verifies the compact serialisation `token` and returns `{:ok claims}`, with the claims as a
  keyword-keyed map, or `{:error {:type ... :message ...}}`. See `errors` for the types.

  Order matters: the signature is checked first, and only then are the claims read, so nothing
  an attacker can write into a token is acted upon before it is authenticated."
  [verifier token]
  (try
    (if-not (and (string? token) (<= (count ^String token) max-token-length))
      {:error (error :malformed "the token is missing or too long")}
      (let [jwt (SignedJWT/parse ^String token)]
        (if-let [e (signature-error verifier jwt)]
          {:error e}
          (let [claims (->clj (.toJSONObject (.getJWTClaimsSet jwt)))]
            (if-let [e (check-claims claims (:policy verifier) ((:clock verifier)))]
              {:error e}
              {:ok claims})))))
    (catch ParseException e
      {:error (error :malformed (ex-message e))})
    ;; Fetching is throttled (see `jwks-source`). With a key set in hand, the `kid` is simply
    ;; unknown: the cached keys were tried and a refetch was not allowed. With none (the
    ;; provider was down at start-up and the throttle has used up its allowance) the problem
    ;; is ours, and calling the client's valid token invalid would be wrong.
    (catch RateLimitReachedException _
      {:error (if (keys-available? (:jwks-source verifier))
                (error :unknown-key "unknown kid, and the key set was fetched recently")
                (error :keys-unavailable "no key set yet, and fetching is throttled"))})
    (catch KeySourceException e
      {:error (error :keys-unavailable (ex-message e))})))
