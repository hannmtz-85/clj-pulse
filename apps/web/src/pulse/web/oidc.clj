(ns pulse.web.oidc
  "The portal's side of the OpenID Connect authorization-code flow (ADR-0028): small functions of data. The sign-in is our own,
  with PKCE (S256), `state` and `nonce`, and not a library's, because the spike of step 2 showed what a library would have
  to be wrapped in. This namespace makes the values of a sign-in, the addresses the browser is sent to, the code exchange,
  and the keeper of a signed-in person's tokens; the routes that use them are `pulse.web.signin`.

  `oidc` is the component `:pulse.web/oidc`, made by `client`:

    `:client-id`          the portal's client at the identity provider (`pulse-web`)
    `:base-url`           the address the portal is served at, such as `http://localhost:3300`: the redirect addresses
                          are built from it and never from the `Host` header of a request
    `:authorization-uri`  where the browser is sent to sign in, as the browser reaches it
    `:end-session-uri`    where it is sent to sign out
    `:post` and `:clock`  the POST to the token endpoint (`pulse.platform.token/endpoint`, one HTTP client for every
                          person) and the clock of the keepers"
  (:require [clojure.string :as str]
            [pulse.platform.config :as config]
            [pulse.platform.http.client :as http]
            [pulse.platform.token :as token]
            [pulse.web.values :as values])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Clock Instant)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; The values of a sign-in

(defn code-challenge
  "The PKCE challenge of a `verifier`: the S256 of it, as RFC 7636 section 4.2 has it."
  [^String verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier StandardCharsets/US_ASCII))))

(defn new-sign-in
  "The secret values of one sign-in: `{:state :verifier :nonce}`, each of them new. They are kept on the server, in the
  pending sign-in, and only the `state` and the challenge of the verifier go to the browser."
  []
  {:state (values/random-value 24)
   :verifier (values/random-value 32)
   :nonce (values/random-value 24)})

;; ---------------------------------------------------------------------------
;; Addresses

(defn redirect-uri
  "Where the identity provider sends the browser back to after the sign-in: the address the realm registers."
  [{:keys [base-url]}]
  (str base-url "/oauth2/callback"))

(defn post-logout-uri
  "Where the identity provider sends the browser back to after the sign-out: the page that says so."
  [{:keys [base-url]}]
  (str base-url "/signed-out"))

(defn- with-query [address params]
  (str address (if (str/includes? address "?") "&" "?") (http/query-string params)))

(defn authorization-address
  "Where to send a browser to sign a person in, for the values of `sign-in` (`:state`, `:verifier`, `:nonce`): the
  authorization request of the code flow, with the PKCE challenge and not the verifier."
  [{:keys [client-id authorization-uri] :as oidc} {:keys [state verifier nonce]}]
  (with-query authorization-uri
    (array-map :response_type "code"
               :client_id client-id
               :redirect_uri (redirect-uri oidc)
               :scope "openid profile"
               :state state
               :nonce nonce
               :code_challenge (code-challenge verifier)
               :code_challenge_method "S256")))

(defn end-session-address
  "Where to send a browser to sign a person out at the identity provider: with the last `id-token` (a `Secret` or a
  string) as the hint, which ends the session and sends the browser back to the portal; without one, the identity
  provider asks the person to confirm first."
  [{:keys [client-id end-session-uri] :as oidc} id-token]
  (with-query end-session-uri
    (array-map :id_token_hint (some-> id-token config/reveal)
               :post_logout_redirect_uri (post-logout-uri oidc)
               :client_id client-id)))

;; ---------------------------------------------------------------------------
;; The tokens

(defn exchange-code!
  "Exchanges the `code` that the identity provider sent the browser back with for the tokens of the person, with the
  `verifier` of the sign-in. Returns the grant of `pulse.platform.token/endpoint`, or throws what it throws (an
  `::token/unavailable` with a `:reason`)."
  [{:keys [post] :as oidc} code verifier]
  (post [["grant_type" "authorization_code"]
         ["code" code]
         ["redirect_uri" (redirect-uri oidc)]
         ["code_verifier" verifier]]))

(defn keeper
  "The keeper of the tokens of one signed-in person: a `pulse.platform.token/source` that starts with the access token of
  `grant`, which the code exchange gave, and renews it with the refresh grant (ADR-0028), and so the single flight, the
  early renewal and the rule that an expired token is never given are those of every keeper. `asked-at` is the moment the
  code exchange was sent. It is also where the last id token is kept, under `:id-token`: an atom that holds a `Secret`,
  which every renewal replaces, and which signing out needs. The refresh token is replaced by every renewal and is
  nowhere else."
  [{:keys [client-id post ^Clock clock]} grant ^Instant asked-at]
  (let [latest-refresh (atom (:refresh-token grant))
        latest-id (atom (:id-token grant))
        fetch (fn []
                (let [renewed (post [["grant_type" "refresh_token"]
                                     ["refresh_token" (config/reveal @latest-refresh)]])]
                  (when-let [next-refresh (:refresh-token renewed)] (reset! latest-refresh next-refresh))
                  (when-let [next-id (:id-token renewed)] (reset! latest-id next-id))
                  renewed))]
    (assoc (token/source {:fetch fetch
                          :clock clock
                          :client-id client-id
                          :initial (assoc (select-keys grant [:access-token :expires-in]) :asked-at asked-at)})
           :id-token latest-id)))

(defn id-token
  "The last id token the identity provider gave for `keeper`, a `Secret`, or nil."
  [keeper]
  (some-> (:id-token keeper) deref))

(defn rejected?
  "Whether a failure of a keeper (an `ex-info` of type `::token/unavailable`, or its ex-data) says that the identity provider
  refused the refresh token, which means that the session is over: it said so now, or it said so lately and nobody asks again
  yet."
  [failure]
  (let [{:keys [type reason last-reason]} (if (map? failure) failure (ex-data failure))]
    (and (= ::token/unavailable type)
         (or (= :rejected reason)
             (and (= :backing-off reason) (= :rejected last-reason))))))

(defn unavailable?
  "Whether `failure` is a token that could not be had (`::token/unavailable`), for whatever reason."
  [failure]
  (= ::token/unavailable (:type (if (map? failure) failure (ex-data failure)))))

(defn client
  "The `oidc` component for the options of `:pulse.web/oidc`: `:client-id`, `:client-secret`, `:base-url`, `:token-uri`
  (the back channel), `:authorization-uri`, `:end-session-uri`, the timeouts, and `:clock` (the system clock by default)."
  [{:keys [clock] :as options}]
  (-> (select-keys options [:client-id :base-url :authorization-uri :end-session-uri])
      (assoc :post (token/endpoint (select-keys options [:token-uri :client-id :client-secret :timeout-ms :connect-timeout-ms]))
             :clock (or clock (Clock/systemUTC)))))

(defn now
  "The instant of the clock of `oidc`."
  ^Instant [{:keys [^Clock clock]}]
  (.instant clock))
