(ns pulse.web.oidc-test
  "The portal's side of the OpenID Connect flow (ADR-0028): the values of a sign-in, the addresses the browser is sent to, the exchange
  of a code and the keeper of a person's tokens, against the fake provider and a clock that the test moves."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.platform.config :as config]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.token :as token]
            [pulse.web.oidc :as oidc]
            [pulse.web.test.harness :refer [with-web]]
            [pulse.web.values :as values])
  (:import (java.time Clock)
           (java.util.concurrent CountDownLatch)))

(def ^:private rfc-verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
(def ^:private rfc-challenge "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

(def ^:private sample
  {:client-id "pulse-web"
   :base-url "http://localhost:3300"
   :authorization-uri "http://localhost:8080/realms/pulse/protocol/openid-connect/auth"
   :end-session-uri "http://localhost:8080/realms/pulse/protocol/openid-connect/logout"})

(defn- query [address]
  (idp/form-of (second (str/split address #"\?" 2))))

;; ---------------------------------------------------------------------------
;; The values of a sign-in

(deftest the-challenge-is-the-s256-of-the-verifier-as-the-rfc-has-it
  (is (= rfc-challenge (oidc/code-challenge rfc-verifier)) "the example of RFC 7636, appendix B")
  (is (not= (oidc/code-challenge "a") (oidc/code-challenge "b")))
  (is (re-matches #"[A-Za-z0-9_-]{43}" (oidc/code-challenge "anything")) "256 bits of hash, written without padding"))

(deftest random-values-are-unguessable-and-safe-in-an-address
  (is (re-matches #"[A-Za-z0-9_-]{43}" (values/random-value 32)) "32 bytes: the verifier of the RFC has 43 to 128 characters")
  (is (re-matches #"[A-Za-z0-9_-]{32}" (values/random-value 24)))
  (is (= "" (values/random-value 0)))
  (is (= 5000 (count (set (repeatedly 5000 #(values/random-value 24))))) "no value comes twice"))

(deftest a-sign-in-has-its-three-secrets-each-new
  (let [a (oidc/new-sign-in)
        b (oidc/new-sign-in)]
    (is (= #{:state :verifier :nonce} (set (keys a))))
    (is (<= 43 (count (:verifier a)) 128))
    (is (every? #(not= (% a) (% b)) [:state :verifier :nonce]))
    (is (= 3 (count (set (vals a)))) "and they are not one value used three times")))

;; ---------------------------------------------------------------------------
;; Addresses

(deftest the-redirect-addresses-are-made-from-the-address-of-the-portal
  (is (= "http://localhost:3300/oauth2/callback" (oidc/redirect-uri sample)))
  (is (= "http://localhost:3300/signed-out" (oidc/post-logout-uri sample)))
  (is (= "https://portal.example/oauth2/callback" (oidc/redirect-uri {:base-url "https://portal.example"}))))

(deftest the-authorization-request-is-the-code-flow-with-pkce-and-says-nothing-secret
  (let [values {:state "st" :verifier rfc-verifier :nonce "no"}
        address (oidc/authorization-address sample values)]
    (is (str/starts-with? address "http://localhost:8080/realms/pulse/protocol/openid-connect/auth?"))
    (is (= {"response_type" "code" "client_id" "pulse-web" "redirect_uri" "http://localhost:3300/oauth2/callback"
            "scope" "openid profile" "state" "st" "nonce" "no" "code_challenge" rfc-challenge "code_challenge_method" "S256"}
           (query address)))
    (is (not (str/includes? address rfc-verifier)) "the verifier stays on the server")
    (testing "in the order the specifications show it"
      (is (= ["response_type" "client_id" "redirect_uri" "scope" "state" "nonce" "code_challenge" "code_challenge_method"]
             (map #(first (str/split % #"=")) (str/split (second (str/split address #"\?")) #"&")))))))

(deftest what-is-in-an-address-is-encoded
  (let [address (oidc/authorization-address sample {:state "a b&c=d+e/f" :verifier rfc-verifier :nonce "x#y"})]
    (is (= "a b&c=d+e/f" (get (query address) "state")))
    (is (= "x#y" (get (query address) "nonce")))
    (is (= 1 (count (re-seq #"&state=" address))) "and cannot add a parameter")))

(deftest the-end-session-address-comes-with-the-hint-and-the-place-to-come-back-to
  (testing "with the last id token, a secret or a string"
    (doseq [hint [(config/secret "the.id.token") "the.id.token"]]
      (let [address (oidc/end-session-address sample hint)]
        (is (str/starts-with? address "http://localhost:8080/realms/pulse/protocol/openid-connect/logout?"))
        (is (= {"id_token_hint" "the.id.token" "post_logout_redirect_uri" "http://localhost:3300/signed-out" "client_id" "pulse-web"}
               (query address))))))
  (testing "without one, the provider asks the person first"
    (is (= {"post_logout_redirect_uri" "http://localhost:3300/signed-out" "client_id" "pulse-web"}
           (query (oidc/end-session-address sample nil))))))

;; ---------------------------------------------------------------------------
;; What a failure says

(deftest a-refusal-of-the-provider-is-told-from-the-provider-not-being-there
  (let [failure (fn [data] (ex-info "x" (merge {:type ::token/unavailable} data)))]
    (testing "rejected: now, and lately"
      (is (true? (oidc/rejected? (failure {:reason :rejected}))))
      (is (true? (oidc/rejected? (failure {:reason :backing-off :last-reason :rejected}))))
      (is (true? (oidc/rejected? (ex-data (failure {:reason :rejected}))))) "or its data")
    (testing "not rejected: it did not answer, or failed, or was not understood, or has not been asked"
      (doseq [data [{:reason :timeout} {:reason :connect} {:reason :server} {:reason :bad-response} {:reason :unexpected}
                    {:reason :backing-off :last-reason :server} {:reason :backing-off :last-reason :timeout}
                    {:reason :backing-off} {}
                    ;; a refusal that is told of lately says so when it is backing off, and in no other way
                    {:reason :timeout :last-reason :rejected} {:reason :server :last-reason :rejected}]]
        (is (false? (oidc/rejected? (failure data))) (pr-str data))
        (is (true? (oidc/unavailable? (failure data))) (pr-str data))))
    (testing "and what is not a token that could not be had is neither"
      (doseq [x [(ex-info "x" {:type :something-else :reason :rejected}) (ex-info "x" {}) (RuntimeException. "x") nil {:reason :rejected}]]
        (is (false? (oidc/rejected? x)) (pr-str x))
        (is (false? (oidc/unavailable? x)) (pr-str x))))))

;; ---------------------------------------------------------------------------
;; The exchange, and the keeper, against the provider

(defn- signed-in
  "A sign-in as the portal makes it, up to the grant: `{:grant :keeper :asked-at :values}`."
  [web]
  (let [values (oidc/new-sign-in)
        {:keys [location]} (oidc-fake/visit (oidc/authorization-address (:oidc web) values))
        code (get (query location) "code")
        asked-at (oidc/now (:oidc web))
        grant (oidc/exchange-code! (:oidc web) code (:verifier values))]
    {:grant grant :asked-at asked-at :values values :code code
     :keeper (oidc/keeper (:oidc web) grant asked-at)}))

(defn- refresh-grants [web]
  (oidc-fake/grants (:idp web) "refresh_token"))

(defn- failure-of [f]
  (try (f) (is false "nothing was thrown") (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-code-is-exchanged-with-the-redirect-address-and-the-verifier-of-the-sign-in
  (with-web {}
    (fn [web]
      (let [{:keys [grant values code]} (signed-in web)
            [form :as forms] (oidc-fake/grants (:idp web) "authorization_code")]
        (is (string? (:access-token grant)))
        (is (= 300 (:expires-in grant)))
        (is (config/secret? (:refresh-token grant)))
        (is (config/secret? (:id-token grant)))
        (is (= 1 (count forms)))
        (is (= {"grant_type" "authorization_code" "code" code "redirect_uri" "http://localhost:3300/oauth2/callback"
                "code_verifier" (:verifier values)}
               form))))))

(deftest an-exchange-that-fails-throws-what-the-platform-throws
  (with-web {}
    (fn [web]
      (testing "a code nobody gave"
        (is (= {:type ::token/unavailable :reason :rejected :status 400 :error "invalid_grant"}
               (failure-of #(oidc/exchange-code! (:oidc web) "code-0-invented" "verifier")))))
      (testing "a verifier that is not the one"
        (let [values (oidc/new-sign-in)
              code (get (query (:location (oidc-fake/visit (oidc/authorization-address (:oidc web) values)))) "code")]
          (is (= :rejected (:reason (failure-of #(oidc/exchange-code! (:oidc web) code "not-the-verifier")))))))
      (testing "a provider that is down"
        (idp/serve-token! (:idp web) {:status 503 :body "{}"})
        (is (= :server (:reason (failure-of #(oidc/exchange-code! (:oidc web) "c" "v")))))))))

(deftest the-keeper-starts-with-the-first-token-and-asks-for-nothing
  (with-web {}
    (fn [web]
      (let [{:keys [grant keeper]} (signed-in web)]
        (is (= (:access-token grant) (token/access-token keeper)))
        (is (empty? (refresh-grants web)))
        (testing "and the id token is the first one"
          (is (= (config/reveal (:id-token grant)) (config/reveal (oidc/id-token keeper))))
          (is (config/secret? (oidc/id-token keeper))))))))

(deftest the-keeper-renews-with-the-refresh-token-when-the-token-is-near-its-end
  (with-web {}
    (fn [web]
      (let [{:keys [grant keeper]} (signed-in web)
            refresh-token (config/reveal (:refresh-token grant))]
        (clock/advance! (:clock web) 239999)
        (is (= (:access-token grant) (token/access-token keeper)) "a millisecond before the margin of a minute")
        (is (empty? (refresh-grants web)))
        (clock/advance! (:clock web) 1)
        (let [renewed (token/access-token keeper)]
          (is (not= (:access-token grant) renewed))
          (is (= [{"grant_type" "refresh_token" "refresh_token" refresh-token}] (refresh-grants web))
              "the refresh grant, with the refresh token of the exchange and nothing else"))
        (is (= 1 (count (refresh-grants web))))))))

(deftest every-renewal-uses-the-refresh-token-the-last-one-gave
  (with-web {}
    (fn [web]
      (let [{:keys [grant keeper]} (signed-in web)]
        (dotimes [_ 3]
          (clock/advance! (:clock web) 250000)
          (token/access-token keeper))
        (let [used (map #(get % "refresh_token") (refresh-grants web))]
          (is (= 3 (count used)))
          (is (= (config/reveal (:refresh-token grant)) (first used)))
          (is (= 3 (count (set used))) "a new one each time, since the provider gives a new one each time"))))))

(deftest a-provider-that-keeps-the-refresh-token-is-asked-with-it-again
  (with-web {}
    (fn [web]
      (oidc-fake/refresh-tokens! (:idp web) :same)
      (let [{:keys [grant keeper]} (signed-in web)]
        (dotimes [_ 2]
          (clock/advance! (:clock web) 250000)
          (token/access-token keeper))
        (is (= [(config/reveal (:refresh-token grant))] (distinct (map #(get % "refresh_token") (refresh-grants web)))))))))

(deftest the-id-token-of-the-keeper-is-the-last-one-the-provider-gave
  (with-web {}
    (fn [web]
      (let [{:keys [grant keeper]} (signed-in web)
            first-id (config/reveal (:id-token grant))]
        (clock/advance! (:clock web) 250000)
        (token/access-token keeper)
        (let [second-id (config/reveal (oidc/id-token keeper))]
          (is (not= first-id second-id))
          (is (config/secret? (oidc/id-token keeper))))
        (clock/advance! (:clock web) 250000)
        (token/access-token keeper)
        (is (not= first-id (config/reveal (oidc/id-token keeper))))))))

(deftest the-life-of-the-first-token-is-counted-from-the-moment-the-code-was-exchanged
  (with-web {}
    (fn [web]
      (let [values (oidc/new-sign-in)
            code (get (query (:location (oidc-fake/visit (oidc/authorization-address (:oidc web) values)))) "code")
            asked-at (oidc/now (:oidc web))
            ;; the exchange takes 100 seconds (the clock moves while the provider thinks about it)
            grant (do (clock/advance! (:clock web) 100000)
                      (oidc/exchange-code! (:oidc web) code (:verifier values)))
            keeper (oidc/keeper (:oidc web) grant asked-at)]
        (clock/advance! (:clock web) 139999)
        (is (empty? (refresh-grants web)))
        (token/access-token keeper)
        (is (empty? (refresh-grants web)) "239.999 seconds after the exchange was sent")
        (clock/advance! (:clock web) 1)
        (token/access-token keeper)
        (is (= 1 (count (refresh-grants web))))))))

(deftest a-keeper-whose-session-ended-at-the-provider-says-so-once-its-token-has-expired
  (with-web {}
    (fn [web]
      (let [{:keys [keeper]} (signed-in web)]
        (doseq [sid (keys (oidc-fake/sessions (:idp web)))] (oidc-fake/end-session! (:idp web) sid))
        (clock/advance! (:clock web) 250000)
        (is (string? (token/access-token keeper)) "near its end, the renewal is refused and the caller has the token he has")
        (clock/advance! (:clock web) 51000)
        (let [data (failure-of #(token/access-token keeper))]
          (is (= {:type ::token/unavailable :reason :rejected :status 400 :error "invalid_grant"} data))
          (is (true? (oidc/rejected? data))))
        (testing "and nobody asks again at once, which says the same"
          (let [data (failure-of #(token/access-token keeper))]
            (is (= :backing-off (:reason data)))
            (is (true? (oidc/rejected? data)))))))))

(deftest a-keeper-whose-provider-is-down-says-it-is-not-rejected
  (with-web {}
    (fn [web]
      (let [{:keys [keeper]} (signed-in web)]
        (idp/serve-token! (:idp web) {:status 503 :body "{}"})
        (clock/advance! (:clock web) 301000)
        (let [data (failure-of #(token/access-token keeper))]
          (is (= :server (:reason data)))
          (is (false? (oidc/rejected? data)))
          (is (true? (oidc/unavailable? data))))
        (testing "and when it is back, the keeper works again"
          (idp/serve-token! (:idp web) {:status 200})
          (clock/advance! (:clock web) 5000)
          (is (string? (token/access-token keeper))))))))

(deftest twenty-callers-with-a-token-near-its-end-make-one-renewal
  (with-web {}
    (fn [web]
      (let [{:keys [grant keeper]} (signed-in web)
            n 20
            go (CountDownLatch. 1)]
        (idp/serve-token! (:idp web) {:delay-ms 300})
        (clock/advance! (:clock web) 250000)
        (let [futures (doall (for [_ (range n)] (future (.await go) (token/access-token keeper))))
              _ (.countDown go)
              tokens (mapv deref futures)]
          (is (= 1 (count (refresh-grants web))))
          (is (every? string? tokens))
          (is (<= (count (set tokens)) 2) "the token it had, and the new one")
          (is (contains? (set tokens) (:access-token grant)) "most of them went on with the one they had, still good"))))))

(deftest the-keeper-prints-without-its-tokens
  (with-web {}
    (fn [web]
      (let [{:keys [grant keeper]} (signed-in web)
            printed (pr-str keeper)]
        (is (= "#token-source[pulse-web]" printed))
        (doseq [secret [(:access-token grant) (config/reveal (:refresh-token grant)) (config/reveal (:id-token grant))]]
          (is (not (str/includes? printed secret))))
        (is (not (str/includes? (pr-str {:tokens keeper}) (:access-token grant))))))))

;; ---------------------------------------------------------------------------
;; The component

(deftest the-component-has-a-post-to-the-token-endpoint-and-a-clock
  (let [c (oidc/client {:client-id "pulse-web" :client-secret (config/secret "s") :base-url "http://localhost:3300"
                        :authorization-uri "http://localhost:8080/a" :end-session-uri "http://localhost:8080/l"
                        :token-uri "http://localhost:8080/t" :timeout-ms 1000 :connect-timeout-ms 500})]
    (is (fn? (:post c)))
    (is (instance? Clock (:clock c)) "the system clock if none is given")
    (is (= #{:client-id :base-url :authorization-uri :end-session-uri :post :clock} (set (keys c)))
        "and not the secret, which only the function that uses it holds")
    (is (not (str/includes? (pr-str (dissoc c :post :clock)) "\"s\""))))
  (testing "a clock that is given is the clock"
    (let [fake (:clock (clock/clock "2026-01-01T00:00:00Z"))
          c (oidc/client {:client-id "pulse-web" :client-secret "s" :base-url "http://localhost:3300"
                          :authorization-uri "http://localhost:8080/a" :end-session-uri "http://localhost:8080/l"
                          :token-uri "http://localhost:8080/t" :clock fake})]
      (is (identical? fake (:clock c)))
      (is (= (java.time.Instant/parse "2026-01-01T00:00:00Z") (oidc/now c))))))

(defspec the-address-of-a-sign-in-says-the-same-whatever-the-values-hold 200
  (prop/for-all [state gen/string-ascii nonce gen/string-ascii]
    (let [address (oidc/authorization-address sample {:state state :verifier rfc-verifier :nonce nonce})
          parsed (query address)]
      (and (= state (get parsed "state"))
           (= nonce (get parsed "nonce"))
           (= rfc-challenge (get parsed "code_challenge"))
           (= 8 (count parsed))))))
