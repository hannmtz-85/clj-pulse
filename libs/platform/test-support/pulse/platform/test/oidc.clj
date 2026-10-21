(ns pulse.platform.test.oidc
  "A fake OpenID Connect provider for the tests of whatever signs a person in (the portal, ADR-0028): what Keycloak
  does for it, on top of the fake identity provider of `pulse.platform.test.idp`, which keeps its key set and its token
  endpoint. `enable!` gives that server three more things:

    /authorize   signs in whoever `sign-in-as!` said (there is no login page) and redirects back with a one-use code and
                 the `state`; or with `access_denied` after `deny!`. PKCE (S256) is required, as the realm requires it
                 of the client `pulse-web`, and the redirect address must be one the client registered
    /token       the authorization-code grant (the client authenticates with HTTP Basic, the code is used once, the
                 redirect address is the one it was given for, and the verifier is the one whose S256 is the challenge)
                 and the refresh grant. A code used twice ends the session it gave, as Keycloak does. The tokens are
                 signed JWTs with the claims Keycloak's have: an access token with `typ` Bearer, the audience of the
                 api and the roles in `realm_access`, an id token with `typ` ID, the audience of the client and the
                 `nonce`, and an opaque refresh token. The refresh token that comes back with a renewal is a new one
                 (as Keycloak's, whose old one still works until the session ends) or the same, as `refresh-tokens!` says
    /logout      ends the session of the `id_token_hint` (any hint it signed, expired or not) and redirects to the
                 `post_logout_redirect_uri` if the client registered it, or shows a confirmation page when there is
                 no hint, and ends nothing

  What the tests see: `authorize-requests` and `logout-requests` (the parameters the provider got), and
  `pulse.platform.test.idp/token-requests`. A session can be ended from outside, as Keycloak's can (`end-session!`).
  Time is a function of the options, so that a test with a clock of its own gets tokens that are good by that clock.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.test.idp :as idp])
  (:import (com.nimbusds.jose.crypto RSASSAVerifier)
           (com.nimbusds.jose.jwk RSAKey)
           (com.nimbusds.jwt SignedJWT)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; The people of the demo realm

(def people
  "The people the fake provider can sign in, as the realm has them."
  {:ana {:sub "5d1f0c64-0000-4000-8000-00000000a0a1" :username "ana.garcia@pulse.test" :given-name "Ana" :family-name "García"
         :roles ["patient"] :fhir-user "Patient/pt-ana"}
   :noah {:sub "5d1f0c64-0000-4000-8000-00000000a0a2" :username "noah.kim@pulse.test" :given-name "Noah" :family-name "Kim"
          :roles ["patient"] :fhir-user "Patient/pt-noah"}
   :rivera {:sub "5d1f0c64-0000-4000-8000-00000000b0b1" :username "dr.rivera@pulse.test" :given-name "Elena" :family-name "Rivera"
            :roles ["practitioner"] :fhir-user "Practitioner/pr-rivera"}
   :admin {:sub "5d1f0c64-0000-4000-8000-00000000c0c1" :username "admin@pulse.test" :given-name "Sam" :family-name "Admin"
           :roles ["clinic-admin"]}})

;; ---------------------------------------------------------------------------
;; Small things

(defn- s256 [^String verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier StandardCharsets/US_ASCII))))

(defn- encode ^String [x]
  (URLEncoder/encode (str x) StandardCharsets/UTF_8))

(defn- query-string [params]
  (str/join "&" (for [[k v] params :when (some? v)]
                  (str (encode k) "=" (encode v)))))

(defn- with-query [^String address params]
  (let [query (query-string params)]
    (if (str/blank? query)
      address
      (str address (if (str/includes? address "?") "&" "?") query))))

(defn- respond!
  "Answers `exchange` with `status`, the `headers` and the text `body`, and closes it."
  [^HttpExchange exchange status headers ^String body]
  (let [payload (.getBytes (or body "") StandardCharsets/UTF_8)]
    (doseq [[k v] headers] (.add (.getResponseHeaders exchange) k v))
    (.sendResponseHeaders exchange (int status) (if (zero? (alength payload)) -1 (long (alength payload))))
    (when (pos? (alength payload))
      (with-open [out (.getResponseBody exchange)] (.write out payload)))
    (.close exchange)))

(defn- redirect! [^HttpExchange exchange address]
  (respond! exchange 302 {"Location" address} nil))

(defn- oidc-state [idp]
  (:oidc @(:state idp)))

(defn- configuration [idp]
  (:config (oidc-state idp)))

;; ---------------------------------------------------------------------------
;; The tokens

(defn- display-name [{:keys [given-name family-name]}]
  (str given-name " " family-name))

(defn- person-claims [person]
  (cond-> {:preferred_username (:username person)
           :name (display-name person)
           :given_name (:given-name person)
           :family_name (:family-name person)
           :email (:username person)
           :email_verified true}
    (:fhir-user person) (assoc :fhirUser (:fhir-user person))))

(defn- access-token [{:keys [key issuer audience access-ttl-s now-s]} person sid client-id]
  (let [now (now-s)]
    (idp/mint key (merge {:exp (+ now access-ttl-s) :iat now :jti (str (random-uuid)) :iss issuer
                          :aud [audience "account"] :sub (:sub person) :typ "Bearer" :azp client-id :sid sid :acr "1"
                          :scope "openid email profile"
                          :realm_access {:roles (into ["default-roles-pulse" "offline_access" "uma_authorization"] (:roles person))}}
                         (person-claims person)))))

(defn- id-token [{:keys [key issuer access-ttl-s now-s]} person sid client-id nonce]
  (let [now (now-s)]
    (idp/mint key (cond-> (merge {:exp (+ now access-ttl-s) :iat now :auth_time now :jti (str (random-uuid)) :iss issuer
                                  :aud client-id :sub (:sub person) :typ "ID" :azp client-id :sid sid :acr "1"}
                                 (person-claims person))
                    nonce (assoc :nonce nonce)))))

(defn- grant-answer
  "The answer of the token endpoint to a grant that was accepted: the tokens of a session, with a refresh token
  that is `refresh-token`."
  [config person sid client-id nonce refresh-token]
  {:status 200
   :body (json/write-value-as-string
          {:access_token (access-token config person sid client-id)
           :expires_in (:access-ttl-s config)
           :refresh_expires_in (:refresh-ttl-s config)
           :refresh_token refresh-token
           :token_type "Bearer"
           :id_token (id-token config person sid client-id nonce)
           :not-before-policy 0
           :session_state sid
           :scope "openid email profile"})})

(defn- refused [status error description]
  {:status status
   :body (json/write-value-as-string {:error error :error_description description})})

(defn- invalid-grant [description]
  (refused 400 "invalid_grant" description))

(def ^:private unauthorized-client
  (refused 401 "unauthorized_client" "Invalid client or Invalid client credentials"))

;; ---------------------------------------------------------------------------
;; The session at the provider

(defn end-session!
  "Ends the session `sid` at the provider, as a sign-out somewhere else, or an administrator, would: its refresh
  tokens stop working."
  [idp sid]
  (swap! (:state idp) assoc-in [:oidc :sessions sid :ended?] true)
  nil)

(defn sessions
  "The sessions the provider has, a map of `sid` to `{:person :client-id :ended?}`."
  [idp]
  (:sessions (oidc-state idp)))

(defn- next-id! [idp prefix]
  (str prefix "-" (get-in (swap! (:state idp) update-in [:oidc :n] inc) [:oidc :n]) "-" (random-uuid)))

(defn- authenticate
  "The client a token request authenticated as, `{:id :secret ...}`, or nil."
  [idp request]
  (let [[id secret] (idp/credentials-of request)
        client (get-in (configuration idp) [:clients id])]
    (when (and client (= secret (:secret client)))
      (assoc client :id id))))

(defn- code-grant [idp]
  (fn [request]
    (if-let [client (authenticate idp request)]
      (let [form (idp/form-of (:body request))
            code (get form "code")
            [before _] (swap-vals! (:state idp) (fn [state]
                                                  (cond-> state
                                                    (get-in state [:oidc :codes code]) (assoc-in [:oidc :codes code :used?] true))))
            entry (get-in before [:oidc :codes code])
            config (configuration idp)]
        (cond
          (nil? entry)
          (invalid-grant "Code not valid")

          ;; a replayed code takes back what it gave: the session it began is ended
          (:used? entry)
          (do (end-session! idp (:sid entry))
              (invalid-grant "Code not valid"))

          (not= (:id client) (:client-id entry))
          (invalid-grant "Code not valid")

          (not= (:redirect-uri entry) (get form "redirect_uri"))
          (invalid-grant "Incorrect redirect_uri")

          (or (str/blank? (get form "code_verifier")) (not= (:challenge entry) (s256 (get form "code_verifier"))))
          (invalid-grant "PKCE verification failed")

          :else
          (let [refresh-token (next-id! idp "refresh")]
            (swap! (:state idp) assoc-in [:oidc :refresh refresh-token] (:sid entry))
            (grant-answer config (:person entry) (:sid entry) (:id client) (:nonce entry) refresh-token))))
      unauthorized-client)))

(defn- refresh-grant [idp]
  (fn [request]
    (if-let [client (authenticate idp request)]
      (let [form (idp/form-of (:body request))
            refresh-token (get form "refresh_token")
            {:keys [refresh sessions refresh-mode config]} (oidc-state idp)
            sid (get refresh refresh-token)
            session (get sessions sid)]
        (cond
          (or (nil? session) (not= (:id client) (:client-id session)))
          (invalid-grant "Invalid refresh token")

          (:ended? session)
          (invalid-grant "Session not active")

          :else
          (let [renewed (if (= :same refresh-mode) refresh-token (next-id! idp "refresh"))]
            (swap! (:state idp) assoc-in [:oidc :refresh renewed] sid)
            (grant-answer config (:person session) sid (:id client) (:nonce session) renewed))))
      unauthorized-client)))

;; ---------------------------------------------------------------------------
;; The endpoints a browser visits

(defn- registered-redirect? [client uri]
  (contains? (:redirect-uris client) uri))

(defn- registered-logout-redirect?
  "Whether `uri` is one the client registered to be sent to after signing out; a registered address may end in `*`."
  [client uri]
  (boolean (some (fn [pattern]
                   (if (str/ends-with? pattern "*")
                     (str/starts-with? uri (subs pattern 0 (dec (count pattern))))
                     (= pattern uri)))
                 (:post-logout-redirect-uris client))))

(defn- authorize-handler [idp]
  (reify HttpHandler
    (handle [_ exchange]
      (let [^HttpExchange exchange exchange
            params (idp/form-of (.getRawQuery (.getRequestURI exchange)))
            {:keys [config deny? person]} (oidc-state idp)
            client (get-in config [:clients (get params "client_id")])
            redirect-uri (get params "redirect_uri")
            state (get params "state")
            back (fn [extra] (redirect! exchange (with-query redirect-uri (into [["state" state]] extra))))]
        (swap! (:state idp) update-in [:oidc :authorize-requests] conj params)
        (cond
          ;; nowhere safe to send the person to: an error page, and no redirect
          (or (nil? client) (not (registered-redirect? client redirect-uri)))
          (respond! exchange 400 {"Content-Type" "text/plain"} "Invalid parameter: redirect_uri")

          (not= "code" (get params "response_type"))
          (back [["error" "unsupported_response_type"]])

          ;; the realm requires PKCE of this client
          (or (str/blank? (get params "code_challenge")) (not= "S256" (get params "code_challenge_method")))
          (back [["error" "invalid_request"] ["error_description" "Missing parameter: code_challenge_method"]])

          deny?
          (back [["error" "access_denied"] ["error_description" "The person said no"]])

          :else
          (let [code (next-id! idp "code")
                sid (next-id! idp "sid")]
            (swap! (:state idp) (fn [s]
                                  (-> s
                                      (assoc-in [:oidc :codes code] {:client-id (get params "client_id")
                                                                     :redirect-uri redirect-uri
                                                                     :challenge (get params "code_challenge")
                                                                     :nonce (get params "nonce")
                                                                     :sid sid
                                                                     :person person})
                                      (assoc-in [:oidc :sessions sid] {:person person
                                                                       :client-id (get params "client_id")
                                                                       :nonce (get params "nonce")}))))
            (back [["session_state" sid] ["iss" (:issuer config)] ["code" code]])))))))

(defn- verified-claims
  "The claims of `token` when it is a JWT the provider signed (expired or not: Keycloak takes an expired id token as
  the hint of a sign-out), otherwise nil."
  [{:keys [^RSAKey key]} ^String token]
  (try
    (let [jwt (SignedJWT/parse token)]
      (when (.verify jwt (RSASSAVerifier. (.toRSAPublicKey key)))
        (into {} (map (fn [[k v]] [(keyword k) v])) (.getClaims (.getJWTClaimsSet jwt)))))
    (catch Exception _ nil)))

(def ^:private confirmation-page
  "<!DOCTYPE html><html><head><title>Logging out</title></head><body><h1>Do you want to log out?</h1></body></html>")

(defn- logout-handler [idp]
  (reify HttpHandler
    (handle [_ exchange]
      (let [^HttpExchange exchange exchange
            params (idp/form-of (.getRawQuery (.getRequestURI exchange)))
            config (configuration idp)
            hint (get params "id_token_hint")
            claims (when hint (verified-claims config hint))
            client (get-in config [:clients (or (get params "client_id") (:azp claims))])
            target (get params "post_logout_redirect_uri")]
        (swap! (:state idp) update-in [:oidc :logout-requests] conj params)
        (cond
          (and hint (nil? claims))
          (respond! exchange 400 {"Content-Type" "text/plain"} "Invalid parameter: id_token_hint")

          (and target (not (and client (registered-logout-redirect? client target))))
          (respond! exchange 400 {"Content-Type" "text/plain"} "Invalid redirect uri")

          ;; no hint: it would ask whether to sign out, and sign out if the person said yes
          (nil? hint)
          (respond! exchange 200 {"Content-Type" "text/html"} confirmation-page)

          :else
          (do (end-session! idp (:sid claims))
              (if target
                (redirect! exchange (with-query target [["state" (get params "state")]]))
                (respond! exchange 200 {"Content-Type" "text/plain"} "You are logged out"))))))))

;; ---------------------------------------------------------------------------
;; Setting it up

(defn enable!
  "Makes the fake identity provider `idp` an OpenID Connect provider; see the namespace. Options:

    `:key`                         the RSA key that signs the tokens (the one whose public part `idp` serves)
    `:issuer`                      the `iss` of the tokens
    `:clients`                     a map of client id to `{:secret s :redirect-uris #{...}
                                   :post-logout-redirect-uris [...]}`
    `:audience`                    the audience of the access tokens (\"pulse-api\")
    `:now-s`                       a function of no arguments, the epoch second the tokens are made at (the clock)
    `:access-ttl-s`, `:refresh-ttl-s`  300 and 1800

  Returns `idp`. Who is signed in is Ana until `sign-in-as!` says otherwise."
  [idp {:keys [audience now-s access-ttl-s refresh-ttl-s]
        :or {audience "pulse-api" access-ttl-s 300 refresh-ttl-s 1800
             now-s #(quot (System/currentTimeMillis) 1000)}
        :as options}]
  (swap! (:state idp) assoc :oidc {:config (assoc (select-keys options [:key :issuer :clients])
                                                  :audience audience :now-s now-s
                                                  :access-ttl-s access-ttl-s :refresh-ttl-s refresh-ttl-s)
                                   :person (:ana people)
                                   :deny? false
                                   :refresh-mode :new
                                   :codes {}
                                   :sessions {}
                                   :refresh {}
                                   :authorize-requests []
                                   :logout-requests []
                                   :n 0})
  (let [^HttpServer server (:server idp)]
    (.createContext server "/authorize" (authorize-handler idp))
    (.createContext server "/logout" (logout-handler idp)))
  (idp/add-grant! idp "authorization_code" (code-grant idp))
  (idp/add-grant! idp "refresh_token" (refresh-grant idp))
  idp)

(defn add-client!
  "Registers another client with the provider, `{:secret s :redirect-uris #{...} :post-logout-redirect-uris [...]}`."
  [idp client-id client]
  (swap! (:state idp) assoc-in [:oidc :config :clients client-id] client)
  nil)

(defn authorization-uri
  "Where the provider's `/authorize` is."
  [idp]
  (str (:base-url idp) "/authorize"))

(defn end-session-uri
  "Where the provider's `/logout` is."
  [idp]
  (str (:base-url idp) "/logout"))

(defn sign-in-as!
  "From now on, `/authorize` signs in `person`, a map as those of `people`."
  [idp person]
  (swap! (:state idp) assoc-in [:oidc :person] person)
  nil)

(defn deny!
  "From now on, `/authorize` answers that the person said no (`access_denied`); `(deny! idp false)` makes it sign in
  again."
  ([idp] (deny! idp true))
  ([idp deny?]
   (swap! (:state idp) assoc-in [:oidc :deny?] deny?)
   nil))

(defn refresh-tokens!
  "What a renewal gives as the refresh token: `:new` (the default, as Keycloak's) or `:same`."
  [idp mode]
  (swap! (:state idp) assoc-in [:oidc :refresh-mode] mode)
  nil)

(defn codes
  "The authorization codes the provider has given, a map of code to `{:client-id :redirect-uri :challenge :nonce :sid :person
  :used?}`: for a test that makes tokens of its own and needs the `nonce` of the sign-in they are for."
  [idp]
  (:codes (oidc-state idp)))

(defn authorize-requests
  "The parameters of every request `/authorize` got, oldest first."
  [idp]
  (:authorize-requests (oidc-state idp)))

(defn logout-requests
  "The parameters of every request `/logout` got, oldest first."
  [idp]
  (:logout-requests (oidc-state idp)))

(defn grants
  "The forms the token endpoint got with the grant `grant-type`, oldest first, as maps of name to value."
  [idp grant-type]
  (filter #(= grant-type (get % "grant_type"))
          (map #(idp/form-of (:body %)) (idp/token-requests idp))))

;; ---------------------------------------------------------------------------
;; A browser

(def ^:private browser
  (delay (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.build))))

(defn visit
  "Visits `address` as a browser at the provider does, without following the redirect it may answer with:
  `{:status n :location s :body text}`, where `:location` is the address it redirects to, or nil."
  [address]
  (let [response (.send ^HttpClient @browser
                        (-> (HttpRequest/newBuilder (URI/create address)) (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :location (.orElse (.firstValue (.headers response) "Location") nil)
     :body (.body response)}))
