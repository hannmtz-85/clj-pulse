(ns pulse.platform.oidc-fake-test
  "The fake OpenID Connect provider of the tests (`pulse.platform.test.oidc`), tested as the thing the portal's tests lean on:
  it must refuse what Keycloak refuses and give what Keycloak gives, or a test of the portal against it proves nothing. The
  tokens it signs go through the platform's own verifier, and the grants through the platform's own client of the token
  endpoint."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.platform.config :as config]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc]
            [pulse.platform.token :as token])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def ^:private signing-key (idp/signing-key "k1"))
(def ^:private issuer "http://localhost:8080/realms/pulse")
(def ^:private redirect-uri "http://localhost:3300/oauth2/callback")
(def ^:private secret "web-secret")
(def ^:private ^String verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
(def ^:private challenge "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

(defn- with-provider
  "Calls `(f server now)` with a fake provider on a clock that `now`, an atom of the epoch second, moves."
  [f]
  (let [server (idp/start! [signing-key])
        now (atom (quot (System/currentTimeMillis) 1000))]
    (try
      (oidc/enable! server {:key signing-key
                            :issuer issuer
                            :now-s #(deref now)
                            :clients {"pulse-web" {:secret secret
                                                   :redirect-uris #{redirect-uri}
                                                   :post-logout-redirect-uris ["http://localhost:3300/*"]}}})
      (f server now)
      (finally (idp/stop! server)))))

(defn- encode [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- authorize-address [server & {:as overrides}]
  (let [params (merge {"response_type" "code" "client_id" "pulse-web" "redirect_uri" redirect-uri "scope" "openid"
                       "state" "state-1" "nonce" "nonce-1" "code_challenge" challenge "code_challenge_method" "S256"}
                      overrides)]
    (str (oidc/authorization-uri server) "?"
         (str/join "&" (for [[k v] params :when (some? v)] (str (encode k) "=" (encode v)))))))

(defn- query-of [address]
  (idp/form-of (second (str/split address #"\?" 2))))

(defn- sign-in!
  "Visits /authorize, and returns the parameters of the redirect it answers with."
  [server & overrides]
  (let [{:keys [status location]} (oidc/visit (apply authorize-address server overrides))]
    (is (= 302 status))
    (assoc (query-of location) ::location location)))

(defn- post-for [server & {:as options}]
  (token/endpoint (merge {:token-uri (:token-uri server) :client-id "pulse-web" :client-secret secret} options)))

(defn- exchange!
  "The code exchange of the portal, with the verifier of the challenge above unless told otherwise."
  [server code & {:keys [verifier-value redirect post] :or {verifier-value verifier redirect redirect-uri}}]
  ((or post (post-for server)) [["grant_type" "authorization_code"] ["code" code]
                                ["redirect_uri" redirect] ["code_verifier" verifier-value]]))

(defn- refresh! [server refresh-token & {:as options}]
  ((post-for server options) [["grant_type" "refresh_token"] ["refresh_token" (config/reveal refresh-token)]]))

(defn- failure-of [f]
  (try (f) (is false "nothing was thrown")
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- verifier-for [server now audience token-type]
  (jwt/verifier {:jwks-source (jwt/jwks-source {:uri (:uri server)}) :issuer issuer :audience audience
                 :token-type token-type :clock #(deref now)}))

;; ---------------------------------------------------------------------------
;; /authorize

(deftest authorize-signs-the-person-in-and-comes-back-with-a-code-and-the-state
  (with-provider
    (fn [server _]
      (let [r (sign-in! server)]
        (is (str/starts-with? (::location r) (str redirect-uri "?state=state-1&session_state=")))
        (is (= "state-1" (get r "state")))
        (is (= issuer (get r "iss")) "Keycloak says who the issuer is, too")
        (is (re-matches #"code-.+" (get r "code")))
        (is (= [(query-of (authorize-address server))] (oidc/authorize-requests server))
            "and the request, as the provider got it, is recorded")))))

(deftest authorize-gives-each-sign-in-its-own-code-and-session
  (with-provider
    (fn [server _]
      (let [a (sign-in! server)
            b (sign-in! server)]
        (is (not= (get a "code") (get b "code")))
        (is (not= (get a "session_state") (get b "session_state")))
        (is (= 2 (count (oidc/sessions server))))))))

(deftest authorize-requires-pkce-as-the-realm-does
  (with-provider
    (fn [server _]
      (doseq [[what overrides] {"no challenge" {"code_challenge" nil}
                                "the plain method" {"code_challenge_method" "plain"}
                                "no method" {"code_challenge_method" nil}}]
        (let [r (apply sign-in! server (mapcat identity overrides))]
          (testing what
            (is (= "invalid_request" (get r "error")))
            (is (= "state-1" (get r "state")) "the state comes back with the error")
            (is (nil? (get r "code")))))))))

(deftest authorize-sends-nobody-to-an-address-the-client-did-not-register
  (with-provider
    (fn [server _]
      (doseq [overrides [{"redirect_uri" "http://evil.example/oauth2/callback"}
                         {"redirect_uri" (str redirect-uri "/../x")}
                         {"redirect_uri" nil}
                         {"client_id" "pulse-other"}]]
        (let [r (oidc/visit (authorize-address server overrides))]
          (is (= 400 (:status r)) (pr-str overrides))
          (is (nil? (:location r)) "an error page, and no redirect")))
      (testing "and the response type must be a code"
        (let [r (sign-in! server "response_type" "token")]
          (is (= "unsupported_response_type" (get r "error"))))))))

(deftest the-person-can-say-no
  (with-provider
    (fn [server _]
      (oidc/deny! server)
      (let [r (sign-in! server)]
        (is (= "access_denied" (get r "error")))
        (is (= "state-1" (get r "state")))
        (is (nil? (get r "code"))))
      (oidc/deny! server false)
      (is (some? (get (sign-in! server) "code")) "and yes again"))))

;; ---------------------------------------------------------------------------
;; The authorization-code grant

(deftest the-challenge-of-the-rfc-is-the-challenge-of-its-verifier
  ;; a known answer, from RFC 7636 appendix B: the fake's S256 is not whatever the test computed
  (with-provider
    (fn [server _]
      (let [code (get (sign-in! server) "code")
            grant (exchange! server code)]
        (is (string? (:access-token grant)))))))

(deftest a-code-is-exchanged-for-the-tokens-of-the-session
  (with-provider
    (fn [server now]
      (let [code (get (sign-in! server) "code")
            {:keys [access-token expires-in refresh-token id-token] :as grant} (exchange! server code)]
        (is (= 300 expires-in))
        (is (config/secret? refresh-token))
        (is (config/secret? id-token))
        (testing "the access token is a bearer token for the api, with the roles of the person"
          (let [claims (:ok (jwt/verify (verifier-for server now "pulse-api" "Bearer") access-token))]
            (is (some? claims))
            (is (= (:sub (:ana oidc/people)) (:sub claims)))
            (is (= "pulse-web" (:azp claims)))
            (is (= "ana.garcia@pulse.test" (:preferred_username claims)))
            (is (= "Ana García" (:name claims)))
            (is (= "Patient/pt-ana" (:fhirUser claims)))
            (is (some #{"patient"} (get-in claims [:realm_access :roles])))
            (is (some #{"offline_access"} (get-in claims [:realm_access :roles])) "next to the roles Keycloak adds of its own")
            (is (= (+ @now 300) (:exp claims)))))
        (testing "the id token is for the client, with the nonce of the sign-in"
          (let [claims (:ok (jwt/verify (verifier-for server now "pulse-web" "ID") (config/reveal id-token)))]
            (is (some? claims))
            (is (= "nonce-1" (:nonce claims)))
            (is (= "Ana García" (:name claims)))
            (is (= "pulse-web" (:aud claims)))))
        (testing "and neither is taken for the other"
          (is (= :bad-audience (get-in (jwt/verify (verifier-for server now "pulse-web" "ID") access-token) [:error :type])))
          (is (= :bad-audience (get-in (jwt/verify (verifier-for server now "pulse-api" "Bearer") (config/reveal id-token)) [:error :type]))))
        (testing "the request was the one the portal makes"
          (let [[request] (idp/token-requests server)]
            (is (= "pulse-web" (idp/client-id-of request)))
            (is (str/includes? (:body request) "grant_type=authorization_code"))))
        (is (= #{:access-token :expires-in :refresh-token :id-token} (set (keys grant))))))))

(deftest no-nonce-in-the-request-is-no-nonce-in-the-id-token
  (with-provider
    (fn [server now]
      (let [code (get (sign-in! server "nonce" nil) "code")
            grant (exchange! server code)
            claims (:ok (jwt/verify (verifier-for server now "pulse-web" "ID") (config/reveal (:id-token grant))))]
        (is (some? claims))
        (is (not (contains? claims :nonce)))))))

(deftest the-person-who-signs-in-is-the-one-it-was-told-to
  (with-provider
    (fn [server now]
      (doseq [[who role] [[:rivera "practitioner"] [:admin "clinic-admin"] [:noah "patient"]]]
        (oidc/sign-in-as! server (get oidc/people who))
        (let [grant (exchange! server (get (sign-in! server) "code"))
              claims (:ok (jwt/verify (verifier-for server now "pulse-api" "Bearer") (:access-token grant)))]
          (is (some #{role} (get-in claims [:realm_access :roles])) (name who))
          (is (= (:sub (get oidc/people who)) (:sub claims))))))))

(deftest a-code-is-refused-unless-everything-about-it-is-as-it-was-asked-for
  (with-provider
    (fn [server _]
      (testing "a wrong verifier: PKCE is checked"
        (let [code (get (sign-in! server) "code")]
          (is (= {:type ::token/unavailable :reason :rejected :status 400 :error "invalid_grant"}
                 (failure-of #(exchange! server code :verifier-value "not-the-verifier"))))))
      (testing "no verifier"
        (let [code (get (sign-in! server) "code")]
          (is (= :rejected (:reason (failure-of #(exchange! server code :verifier-value "")))))))
      (testing "another redirect address than the one the code was given for"
        (let [code (get (sign-in! server) "code")]
          (is (= :rejected (:reason (failure-of #(exchange! server code :redirect "http://localhost:3300/other")))))))
      (testing "a code nobody gave"
        (is (= "invalid_grant" (:error (failure-of #(exchange! server "code-0-invented"))))))
      (testing "a client that is not the one it was given to, even with its own credentials"
        (let [code (get (sign-in! server) "code")]
          (oidc/add-client! server "pulse-other" {:secret "other" :redirect-uris #{redirect-uri}})
          (is (= "invalid_grant" (:error (failure-of #(exchange! server code
                                                                 :post (post-for server :client-id "pulse-other" :client-secret "other")))))))))))

(deftest the-client-must-authenticate
  (with-provider
    (fn [server _]
      (let [code (get (sign-in! server) "code")]
        (doseq [post [(post-for server :client-secret "not-the-secret")
                      (post-for server :client-id "pulse-nobody")]]
          (is (= {:type ::token/unavailable :reason :rejected :status 401 :error "unauthorized_client"}
                 (failure-of #(exchange! server code :post post)))))
        (testing "and the code was not spent by the attempts"
          (is (some? (:access-token (exchange! server code)))))))))

(deftest a-code-used-twice-gives-nothing-and-takes-back-what-it-gave
  ;; as Keycloak does: the spike of ADR-0028 saw the refresh token of the first exchange stop working
  (with-provider
    (fn [server _]
      (let [code (get (sign-in! server) "code")
            grant (exchange! server code)]
        (is (some? (:access-token (refresh! server (:refresh-token grant)))) "it works before the replay")
        (is (= "invalid_grant" (:error (failure-of #(exchange! server code)))))
        (is (= "invalid_grant" (:error (failure-of #(refresh! server (:refresh-token grant))))))))))

;; ---------------------------------------------------------------------------
;; The refresh grant

(deftest a-renewal-gives-new-tokens-by-the-clock-and-a-new-refresh-token-while-the-old-one-still-works
  (with-provider
    (fn [server now]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            _ (swap! now + 240)
            renewed (refresh! server (:refresh-token grant))
            claims (:ok (jwt/verify (verifier-for server now "pulse-api" "Bearer") (:access-token renewed)))]
        (is (some? claims))
        (is (= (+ @now 300) (:exp claims)) "made at the time of the renewal")
        (is (not= (config/reveal (:refresh-token grant)) (config/reveal (:refresh-token renewed))))
        (is (some? (:id-token renewed)))
        (testing "the old refresh token is still good, as Keycloak's is until the session ends"
          (is (some? (:access-token (refresh! server (:refresh-token grant))))))
        (testing "and so is the new one"
          (is (some? (:access-token (refresh! server (:refresh-token renewed))))))
        (testing "the nonce of the sign-in goes on in the id tokens of the session"
          (let [id (:ok (jwt/verify (verifier-for server now "pulse-web" "ID") (config/reveal (:id-token renewed))))]
            (is (= "nonce-1" (:nonce id)))))))))

(deftest a-provider-can-be-told-to-keep-the-refresh-token
  (with-provider
    (fn [server _]
      (oidc/refresh-tokens! server :same)
      (let [grant (exchange! server (get (sign-in! server) "code"))
            renewed (refresh! server (:refresh-token grant))]
        (is (= (config/reveal (:refresh-token grant)) (config/reveal (:refresh-token renewed))))))))

(deftest a-renewal-is-refused-once-the-session-has-ended
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            [sid] (keys (oidc/sessions server))]
        (oidc/end-session! server sid)
        (let [data (failure-of #(refresh! server (:refresh-token grant)))]
          (is (= {:type ::token/unavailable :reason :rejected :status 400 :error "invalid_grant"} data)))))))

(deftest a-renewal-is-refused-for-a-refresh-token-nobody-gave-or-that-another-client-has
  (with-provider
    (fn [server _]
      (is (= "invalid_grant" (:error (failure-of #(refresh! server (config/secret "refresh-0-invented"))))))
      (is (= :rejected (:reason (failure-of #((post-for server) [["grant_type" "refresh_token"]])))) "none at all")
      (let [grant (exchange! server (get (sign-in! server) "code"))]
        (is (= {:status 401 :error "unauthorized_client"}
               (select-keys (failure-of #(refresh! server (:refresh-token grant) :client-secret "wrong")) [:status :error])))))))

(deftest the-grants-the-provider-got-can-be-read-back
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))]
        (refresh! server (:refresh-token grant))
        (refresh! server (:refresh-token grant))
        (is (= 1 (count (oidc/grants server "authorization_code"))))
        (is (= 2 (count (oidc/grants server "refresh_token"))))
        (is (= redirect-uri (get (first (oidc/grants server "authorization_code")) "redirect_uri")))))))

(deftest what-the-test-told-the-token-endpoint-to-say-wins-over-the-provider
  ;; "Keycloak is down" for a test of the portal: the endpoint fails, whatever grant it is asked for
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))]
        (idp/serve-token! server {:status 503 :body "{}"})
        (is (= :server (:reason (failure-of #(refresh! server (:refresh-token grant))))))
        (idp/serve-token! server {:status 200})
        (is (some? (:access-token (refresh! server (:refresh-token grant)))) "and it is back")
        (idp/serve-token! server {:delay-ms 300})
        (is (some? (:access-token (refresh! server (:refresh-token grant)))) "a delay alone changes only the time it takes")))))

(deftest an-answer-that-gives-nothing-leaves-the-request-to-the-provider
  ;; how one fake is both the provider of the people who sign in and the one that gives the service accounts their tokens
  (with-provider
    (fn [server _]
      (idp/serve-token! server {:answer (fn [request]
                                          (when (= "pulse-service" (idp/client-id-of request))
                                            "{\"access_token\":\"for-the-service\",\"expires_in\":300,\"token_type\":\"Bearer\"}"))})
      (is (= "for-the-service" (:access-token ((post-for server :client-id "pulse-service" :client-secret "x")
                                               [["grant_type" "client_credentials"]]))))
      (testing "and a person's grants are the provider's"
        (let [grant (exchange! server (get (sign-in! server) "code"))]
          (is (not= "for-the-service" (:access-token grant)))
          (is (some? (:access-token (refresh! server (:refresh-token grant))))))))))

(deftest the-client-credentials-grant-is-the-fakes-as-before
  (with-provider
    (fn [server _]
      (is (= "fake-token-1" (:access-token ((post-for server) [["grant_type" "client_credentials"]])))))))

;; ---------------------------------------------------------------------------
;; /logout

(deftest logout-ends-the-session-of-the-hint-and-goes-back
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            hint (config/reveal (:id-token grant))
            r (oidc/visit (str (oidc/end-session-uri server) "?id_token_hint=" (encode hint)
                               "&post_logout_redirect_uri=" (encode "http://localhost:3300/signed-out")
                               "&client_id=pulse-web"))]
        (is (= 302 (:status r)))
        (is (= "http://localhost:3300/signed-out" (:location r)))
        (is (= "invalid_grant" (:error (failure-of #(refresh! server (:refresh-token grant))))) "the session is over")
        (is (= [(get (query-of (str "x?id_token_hint=" (encode hint) "&post_logout_redirect_uri=" (encode "http://localhost:3300/signed-out")
                                    "&client_id=pulse-web")) "client_id")]
               (map #(get % "client_id") (oidc/logout-requests server))))))))

(deftest an-expired-hint-signs-out-all-the-same
  (with-provider
    (fn [server now]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            hint (config/reveal (:id-token grant))]
        (swap! now + 3600)
        (let [r (oidc/visit (str (oidc/end-session-uri server) "?id_token_hint=" (encode hint)
                                 "&post_logout_redirect_uri=" (encode "http://localhost:3300/signed-out")))]
          (is (= 302 (:status r))))
        (is (= "invalid_grant" (:error (failure-of #(refresh! server (:refresh-token grant))))))))))

(deftest logout-does-not-send-the-person-anywhere-it-was-not-told-about
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            hint (encode (config/reveal (:id-token grant)))]
        (doseq [target ["http://evil.example/" "http://localhost:3301/signed-out" "https://localhost:3300/signed-out"]]
          (let [r (oidc/visit (str (oidc/end-session-uri server) "?id_token_hint=" hint "&post_logout_redirect_uri=" (encode target)))]
            (is (= 400 (:status r)) target)
            (is (nil? (:location r)) target)))
        (is (some? (:access-token (refresh! server (:refresh-token grant)))) "and it ended nothing")))))

(deftest logout-with-a-hint-it-did-not-sign-is-refused
  (with-provider
    (fn [server _]
      (let [forged (idp/mint (idp/signing-key "other") {:sid "sid-1" :azp "pulse-web" :iss issuer})]
        (doseq [hint [forged "not-a-token" ""]]
          (is (= 400 (:status (oidc/visit (str (oidc/end-session-uri server) "?id_token_hint=" (encode hint)
                                               "&post_logout_redirect_uri=" (encode "http://localhost:3300/signed-out"))))) hint))))))

(deftest logout-with-no-hint-asks-first-and-ends-nothing
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            r (oidc/visit (str (oidc/end-session-uri server) "?client_id=pulse-web&post_logout_redirect_uri="
                               (encode "http://localhost:3300/signed-out")))]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "Do you want to log out?"))
        (is (some? (:access-token (refresh! server (:refresh-token grant)))))
        (testing "and an address the client did not register is refused all the same"
          (is (= 400 (:status (oidc/visit (str (oidc/end-session-uri server) "?client_id=pulse-web&post_logout_redirect_uri="
                                               (encode "http://evil.example/")))))))))))

(deftest logout-hands-back-the-state-it-was-given
  (with-provider
    (fn [server _]
      (let [grant (exchange! server (get (sign-in! server) "code"))
            r (oidc/visit (str (oidc/end-session-uri server) "?id_token_hint=" (encode (config/reveal (:id-token grant)))
                               "&post_logout_redirect_uri=" (encode "http://localhost:3300/signed-out") "&state=bye"))]
        (is (= "http://localhost:3300/signed-out?state=bye" (:location r)))))))

(deftest the-pair-these-tests-use-is-the-one-of-rfc-7636
  ;; appendix B of the RFC: the challenge is the S256 of the verifier, so the exchanges above prove the fake checks
  ;; PKCE with the right function and not with one the test made up
  (is (= challenge (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                    (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier StandardCharsets/US_ASCII))))))
