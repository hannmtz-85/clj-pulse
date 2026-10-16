(ns pulse.platform.test.idp
  "A fake identity provider for tests, shared by every suite that needs signed tokens: throwaway
  RSA keys, a function that mints tokens, and a small HTTP server that serves a key set at
  /jwks and answers token requests at /token. The server can be told to serve other keys, to fail,
  and it counts the requests it got, which is what lets key caching, rotation and outages be
  tested for real. At /token it records every request (method, headers, body), gives a different
  access token to each, and can be told to answer something else or to be slow.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.string :as str])
  (:import (com.nimbusds.jose JOSEObjectType JWSAlgorithm JWSHeader$Builder)
           (com.nimbusds.jose.crypto RSASSASigner)
           (com.nimbusds.jose.jwk JWK JWKSet KeyUse RSAKey)
           (com.nimbusds.jose.jwk.gen RSAKeyGenerator)
           (com.nimbusds.jwt JWTClaimsSet SignedJWT)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.util ArrayList Base64 Collection LinkedHashMap List Map)))

(defn generate-key
  "A throwaway RSA key with the given `kid`, key use (`KeyUse/SIGNATURE`) and algorithm."
  ^RSAKey [kid key-use alg]
  (.generate (doto (RSAKeyGenerator. 2048)
               (.keyID kid)
               (.keyUse key-use)
               (.algorithm alg))))

(defn signing-key
  "A throwaway RS256 signature key with the given `kid`."
  ^RSAKey [kid]
  (generate-key kid KeyUse/SIGNATURE JWSAlgorithm/RS256))

(defn ->java
  "Clojure data as the plain java.util maps and lists that Nimbus takes."
  [x]
  (cond
    (map? x) (let [m (LinkedHashMap.)]
               (doseq [[k v] x] (.put m (name k) (->java v)))
               m)
    (sequential? x) (ArrayList. ^Collection (mapv ->java x))
    :else x))

(defn mint
  "A compact JWT with `claims` (Clojure data) signed by `key`. Options override the header:
  `:alg` (default RS256) and `:kid` (default the key's; nil leaves it out)."
  ([key claims] (mint key claims {}))
  ([^RSAKey key claims opts]
   (let [alg (get opts :alg JWSAlgorithm/RS256)
         kid (if (contains? opts :kid) (:kid opts) (.getKeyID key))
         header (-> (JWSHeader$Builder. ^JWSAlgorithm alg)
                    (.type JOSEObjectType/JWT)
                    (cond-> kid (.keyID ^String kid))
                    (.build))
         jwt (SignedJWT. header (JWTClaimsSet/parse ^Map (->java claims)))]
     (.sign jwt (RSASSASigner. key))
     (.serialize jwt))))

(defn jwks-json
  "A key set as an identity provider publishes it: public keys only."
  [keys]
  (.toString (JWKSet. ^List (mapv #(.toPublicJWK ^JWK %) keys))))

(defn client-id-of
  "The client a token request authenticated as, from its HTTP Basic header: the id and the secret are
  form-encoded and joined with a colon, as RFC 6749 section 2.3.1 has it. Nil for a request that
  does not carry one. `request` is as `token-requests` has it, with the header names in lower case."
  [request]
  (when-let [[_ credentials] (re-matches #"Basic (.+)" (str (get-in request [:headers "authorization"])))]
    (let [decoded (String. (.decode (Base64/getDecoder) ^String credentials) StandardCharsets/UTF_8)]
      (URLDecoder/decode ^String (first (str/split decoded #":" 2)) "UTF-8"))))

(defn- token-handler
  "The handler of /token: it records the request, waits if it was told to, and answers what it was
  told to, which is by default a new access token for each request."
  [state]
  (reify HttpHandler
    (handle [_ ex]
      (let [^HttpExchange ex ex
            request {:method (.getRequestMethod ex)
                     :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)])) (.getRequestHeaders ex))
                     :body (slurp (.getRequestBody ex))}
            {:keys [token-requests token]} (swap! state update :token-requests conj request)
            {:keys [status delay-ms answer body]} token
            ^String body (cond
                           answer (answer request)
                           body body
                           :else (format "{\"access_token\":\"fake-token-%d\",\"expires_in\":300,\"token_type\":\"Bearer\"}"
                                         (count token-requests)))
            payload (.getBytes body StandardCharsets/UTF_8)]
        (when (pos? delay-ms) (Thread/sleep (long delay-ms)))
        (.add (.getResponseHeaders ex) "Content-Type" "application/json")
        (.sendResponseHeaders ex (int status) (long (alength payload)))
        (with-open [out (.getResponseBody ex)] (.write out payload))
        (.close ex)))))

(defn start!
  "Starts a fake identity provider on a random local port. Its /jwks serves `keys`, and /token
  answers token requests. Returns a map with `:uri` (the JWKS URL) and `:token-uri` for the other
  functions here."
  [keys]
  (let [state (atom {:body (jwks-json keys) :status 200 :hits 0
                     :token {:status 200 :delay-ms 0 :body nil}
                     :token-requests []})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/jwks"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              {:keys [^String body status]} (swap! state update :hits inc)
                              payload (.getBytes body StandardCharsets/UTF_8)
                              ok? (= 200 status)]
                          (.add (.getResponseHeaders ex) "Content-Type" "application/json")
                          (.sendResponseHeaders ex (int status) (if ok? (long (alength payload)) -1))
                          (when ok?
                            (with-open [out (.getResponseBody ex)] (.write out payload)))
                          (.close ex)))))
    (.createContext server "/token" (token-handler state))
    (.start server)
    (let [base (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
      {:server server
       :state state
       :uri (str base "/jwks")
       :token-uri (str base "/token")})))

(defn stop! [idp]
  (.stop ^HttpServer (:server idp) 0))

(defn serve!
  "From now on, serve `keys` (and answer 200)."
  [idp keys]
  (swap! (:state idp) assoc :body (jwks-json keys) :status 200))

(defn break!
  "From now on, answer 503."
  [idp]
  (swap! (:state idp) assoc :status 503))

(defn hits
  "How many requests the key set endpoint has received."
  [idp]
  (:hits @(:state idp)))

(defn serve-token!
  "From now on, answer token requests with `:status` (200), the JSON text `:body` (by default a new
  access token each time) and after `:delay-ms` (0). Instead of a `:body`, an `:answer` is a function
  of the request (see `token-requests`) that gives the JSON text, for an identity provider that is
  asked by more than one client and answers each its own (see `client-id-of`)."
  [idp {:keys [status body delay-ms answer] :or {status 200 delay-ms 0}}]
  (swap! (:state idp) assoc :token {:status status :body body :delay-ms delay-ms :answer answer}))

(defn token-requests
  "The requests the token endpoint has received, oldest first, each `{:method :headers :body}`
  with the header names in lower case."
  [idp]
  (:token-requests @(:state idp)))
