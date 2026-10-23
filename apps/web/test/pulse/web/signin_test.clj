(ns pulse.web.signin-test
  "Signing in and out of the portal (ADR-0028), over the whole handler and against the fake OpenID Connect provider: a browser
  with cookies goes where the portal sends it, the provider signs in whoever the test said, and the browser comes back with
  a code. What is checked is what the design promises: the identity provider is not asked unless the `state` is the one of a
  sign-in this browser began; a sign-in can happen once; both tokens are verified; only a patient gets a session, and it is a
  new one; and what goes wrong is a page, with the cause in the log and nothing of the provider's words in the page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.config :as config]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.oidc :as oidc-fake]
            [pulse.platform.token :as token]
            [pulse.web.oidc :as oidc]
            [pulse.web.session :as session]
            [pulse.web.signin :as signin]
            [pulse.web.test.harness :as h :refer [header with-web]]
            [ring.middleware.session.store :as store])
  (:import (com.nimbusds.jwt SignedJWT)
           (java.net URLEncoder)
           (java.util.concurrent CountDownLatch)))

(def ^:private ana (:ana oidc-fake/people))

(defn- events [response id]
  (filter #(= id (:id %)) (:signals response)))

(defn- params-of
  "The parameters of the query of an address."
  [address]
  (idp/form-of (second (str/split address #"\?" 2))))

(defn- pending-of
  "The pending sign-in that the browser's cookie leads to in the store of `web`, or nil."
  [web browser]
  (:sign-in (store/read-session (:sessions web) (h/cookie browser session/cookie-name))))

(defn- session-of [web browser]
  (store/read-session (:sessions web) (h/cookie browser session/cookie-name)))

(defn- counts [web]
  (session/counts (:sessions web)))

(defn- token-endpoint-calls
  "How many requests the token endpoint of the provider has had."
  [web]
  (count (idp/token-requests (:idp web))))

(defn- seconds-of [^String jwt-text claim]
  (let [claims (.getJWTClaimsSet (SignedJWT/parse jwt-text))]
    (case claim
      :iat (.getEpochSecond (.toInstant (.getIssueTime claims))))))

;; ---------------------------------------------------------------------------
;; Beginning

(deftest asking-to-sign-in-sends-the-browser-to-the-provider-with-the-values-of-a-sign-in
  (with-web {}
    (fn [web]
      (let [b (h/browser web)
            r (h/visit! b :get "/sign-in")
            location (header r "Location")
            params (params-of location)
            pending (pending-of web b)]
        (is (= 303 (:status r)))
        (is (str/starts-with? location (str (oidc-fake/authorization-uri (:idp web)) "?")))
        (testing "the authorization request of the code flow, with PKCE"
          (is (= {"response_type" "code" "client_id" "pulse-web" "redirect_uri" (str h/base-url "/oauth2/callback")
                  "scope" "openid profile" "code_challenge_method" "S256"}
                 (select-keys params ["response_type" "client_id" "redirect_uri" "scope" "code_challenge_method"])))
          (is (= (:state pending) (get params "state")))
          (is (= (:nonce pending) (get params "nonce")))
          (is (= (oidc/code-challenge (:verifier pending)) (get params "code_challenge")))
          (is (<= 43 (count (:verifier pending)) 128) "a verifier as RFC 7636 has it"))
        (testing "the verifier is on the server and nowhere else"
          (is (not (str/includes? (pr-str (select-keys r [:headers :body])) (:verifier pending)))))
        (testing "the values are different every time"
          (let [b2 (h/browser web)
                pending2 (do (h/visit! b2 :get "/sign-in") (pending-of web b2))]
            (is (every? #(not= (% pending) (% pending2)) [:state :verifier :nonce]))))
        (testing "a pending sign-in is an entry of the store, and not a session"
          (is (= {:sessions 0 :pending 2} (counts web)))
          (is (nil? (:person (session-of web b)))))
        (testing "where to come back to: the home page, if nothing else was asked"
          (is (= "/" (:return-to pending))))))))

(deftest the-cookie-of-the-session-is-the-one-the-design-says
  (with-web {}
    (fn [web]
      (let [r (h/visit! (h/browser web) :get "/sign-in")
            [cookie :as cookies] (let [c (header r "Set-Cookie")] (if (string? c) [c] c))
            attributes (set (map str/lower-case (rest (str/split cookie #";\s*"))))]
        (is (= 1 (count cookies)))
        (is (str/starts-with? cookie (str session/cookie-name "=")))
        (is (contains? attributes "httponly") "no script can read it")
        (is (contains? attributes "samesite=lax") "it has to come with the redirect back from the provider")
        (is (contains? attributes "path=/"))
        (is (not-any? #(or (str/starts-with? % "max-age") (str/starts-with? % "expires")) attributes)
            "a session cookie: it goes with the browser")
        (is (not (contains? attributes "secure")) "the portal is served over http here")
        (is (re-matches #"[A-Za-z0-9_-]{43}" (second (re-matches #"[^=]+=([^;]+).*" cookie))) "256 bits, written to be safe in a header")))))

(deftest a-place-to-come-back-to-is-kept-only-when-it-is-a-path-of-the-portal
  (with-web {}
    (fn [web]
      (doseq [[asked kept] {"/readings?period=30" "/readings?period=30"
                            "/appointments" "/appointments"
                            "//evil.example/x" "/"
                            "https://evil.example/" "/"
                            "/\\evil.example" "/"
                            "javascript:alert(1)" "/"
                            "" "/"}]
        (let [b (h/browser web)]
          (h/visit! b :get "/sign-in" :query (str "return-to=" (URLEncoder/encode ^String asked "UTF-8")))
          (is (= kept (:return-to (pending-of web b))) (pr-str asked))))
      (testing "and when nothing is asked"
        (let [b (h/browser web)]
          (h/visit! b :get "/sign-in")
          (is (= "/" (:return-to (pending-of web b)))))))))

(deftest asking-again-makes-a-new-pending-sign-in-and-the-old-cookie-is-not-kept
  (with-web {}
    (fn [web]
      (let [b (h/browser web)]
        (h/visit! b :get "/sign-in")
        (let [first-cookie (h/cookie b session/cookie-name)
              first-pending (pending-of web b)]
          (h/visit! b :get "/sign-in")
          (is (not= first-cookie (h/cookie b session/cookie-name)))
          (is (not= (:state first-pending) (:state (pending-of web b))))
          (is (nil? (store/read-session (:sessions web) first-cookie)) "the first one is gone, and not left to fill the store")
          (is (= {:sessions 0 :pending 1} (counts web))))))))

(deftest a-cookie-the-browser-made-up-is-never-a-key-of-the-store
  ;; session fixation: an attacker who plants a cookie must not know the key of the session that comes of it
  (with-web {}
    (fn [web]
      (let [b (h/browser web)]
        (swap! (:cookies b) assoc session/cookie-name "chosen-by-the-attacker")
        (h/visit! b :get "/sign-in")
        (is (not= "chosen-by-the-attacker" (h/cookie b session/cookie-name)))
        (is (nil? (store/read-session (:sessions web) "chosen-by-the-attacker")))))))

(deftest somebody-who-is-signed-in-already-is-sent-on-and-not-to-the-provider
  (with-web {}
    (fn [web]
      (let [b (h/browser web)]
        (h/sign-in! b)
        (let [asked (count (oidc-fake/authorize-requests (:idp web)))
              r (h/visit! b :get "/sign-in" :query "return-to=%2Fappointments")]
          (is (= 303 (:status r)))
          (is (= "/appointments" (header r "Location")))
          (is (= asked (count (oidc-fake/authorize-requests (:idp web)))) "the provider was not asked again")
          (is (= {:sessions 1 :pending 0} (counts web)))))
      (testing "and not to a place outside the portal"
        (let [b (h/browser web)]
          (h/sign-in! b)
          (is (= "/" (header (h/visit! b :get "/sign-in" :query "return-to=%2F%2Fevil.example") "Location"))))))))

;; ---------------------------------------------------------------------------
;; Coming back: a patient signs in

(deftest a-patient-signs-in-and-lands-where-they-were-going
  (with-web {}
    (fn [web]
      (let [b (h/browser web)
            begin (h/visit! b :get "/sign-in" :query "return-to=%2Freadings%3Fperiod%3D30")
            pending-cookie (h/cookie b session/cookie-name)
            provider (h/at-the-provider (header begin "Location"))
            callback (h/follow! b (:location provider))
            data (session-of web b)]
        (is (= 302 (:status provider)))
        (is (str/starts-with? (:location provider) (str h/base-url "/oauth2/callback?state=")))
        (is (= 303 (:status callback)))
        (is (= "/readings?period=30" (header callback "Location")) "to the place the person was going to")
        (testing "the session is a new entry, under a new key: nothing from before the sign-in survives it"
          (is (not= pending-cookie (h/cookie b session/cookie-name)))
          (is (nil? (store/read-session (:sessions web) pending-cookie)))
          (is (= {:sessions 1 :pending 0} (counts web))))
        (testing "it holds the person, the keeper of their tokens and a CSRF token, and nothing else"
          (is (= #{:person :tokens :csrf} (set (keys data))))
          (is (= {:id (:sub ana) :username "ana.garcia@pulse.test" :name "Ana García"} (:person data)))
          (is (re-matches #"[A-Za-z0-9_-]{43}" (:csrf data)))
          (is (string? (token/access-token (:tokens data))))
          (is (config/secret? (oidc/id-token (:tokens data))) "the id token, which signing out needs, is a secret"))
        (testing "the cookie is replaced by the one of the new session"
          (is (str/includes? (str/join "; " (h/set-cookies callback)) (str session/cookie-name "=" (h/cookie b session/cookie-name)))))
        (testing "the code was exchanged once, as the design says, with the verifier of this sign-in"
          (let [[grant :as grants] (oidc-fake/grants (:idp web) "authorization_code")]
            (is (= 1 (count grants)))
            (is (= (str h/base-url "/oauth2/callback") (get grant "redirect_uri")))
            (is (= (get (params-of (:location provider)) "code") (get grant "code")))
            (is (= (oidc/code-challenge (get grant "code_verifier")) (get (params-of (header begin "Location")) "code_challenge")))))
        (testing "and the person is in: the page says so"
          (let [home (h/visit! b :get "/")]
            (is (= 200 (:status home)))
            (is (str/includes? (:text home) "Welcome, Ana"))
            (is (str/includes? (:text home) "Account menu of Ana García"))))))))

(deftest a-sign-in-is-an-audit-event-and-nothing-secret-is-in-the-log
  (with-web {}
    (fn [web]
      (let [b (h/browser web)
            {:keys [begin provider callback]} (h/sign-in! b)
            [audit :as audits] (events callback :pulse.audit/access)
            logged (pr-str (:signals callback))]
        (is (= 1 (count audits)))
        (is (= :info (:level audit)))
        (is (= {:actor {:subject (:sub ana) :username "ana.garcia@pulse.test" :roles ["patient"] :client "pulse-web"}
                :action :portal/sign-in
                :outcome :allowed
                :method "GET"
                :path "/oauth2/callback"}
               (:data audit)))
        (testing "no code, no state, no token, no secret"
          (let [session (session-of web b)
                everything (str logged (pr-str (:signals begin)))]
            (doseq [secret [(get (params-of (:location provider)) "code")
                            (get (params-of (:location provider)) "state")
                            (token/access-token (:tokens session))
                            (config/reveal (oidc/id-token (:tokens session)))
                            (:csrf session)
                            h/client-secret]]
              (is (not (str/includes? everything secret)) secret))))))))

(deftest every-sign-in-is-a-new-session-and-two-people-do-not-share-one
  (with-web {}
    (fn [web]
      (let [one (h/browser web)
            two (h/browser web)]
        (h/sign-in! one)
        (oidc-fake/sign-in-as! (:idp web) (:noah oidc-fake/people))
        (h/sign-in! two)
        (is (not= (h/cookie one session/cookie-name) (h/cookie two session/cookie-name)))
        (is (= "Ana García" (:name (:person (session-of web one)))))
        (is (= "Noah Kim" (:name (:person (session-of web two)))))
        (is (not= (:csrf (session-of web one)) (:csrf (session-of web two))))
        (is (not= (:tokens (session-of web one)) (:tokens (session-of web two))))))))

(deftest the-name-is-the-one-the-id-token-gives-and-a-hostile-one-is-text
  (with-web {}
    (fn [web]
      (oidc-fake/sign-in-as! (:idp web) (assoc ana :given-name "<img src=x onerror=alert(1)>" :family-name "García"))
      (let [b (h/browser web)]
        (h/sign-in! b)
        (let [home (h/visit! b :get "/")]
          (is (= 200 (:status home)))
          (is (not (str/includes? (:text home) "<img")))
          (is (str/includes? (:text home) "&lt;img src=x onerror=alert(1)&gt; García")))))))

;; ---------------------------------------------------------------------------
;; Coming back, and not from a sign-in this browser began

(defn- begun
  "A browser that has asked to sign in and been sent to the provider, which has signed Ana in: `[browser callback-address]`."
  [web]
  (let [b (h/browser web)
        begin (h/visit! b :get "/sign-in")
        provider (h/at-the-provider (header begin "Location"))]
    [b (:location provider)]))

(defn- expired-page? [response]
  (and (= 400 (:status response))
       (str/includes? (:text response) "That sign-in has expired")))

(deftest a-callback-with-no-sign-in-behind-it-asks-the-provider-nothing
  (with-web {}
    (fn [web]
      (testing "from a browser that never asked"
        (let [r (h/call web :get "/oauth2/callback" :query "code=abc&state=xyz")]
          (is (expired-page? r))
          (is (zero? (token-endpoint-calls web)))))
      (testing "with no code and no state at all"
        (let [r (h/call web :get "/oauth2/callback")]
          (is (expired-page? r))
          (is (zero? (token-endpoint-calls web)))))
      (testing "from a browser with a cookie that leads nowhere"
        (let [b (h/browser web)]
          (swap! (:cookies b) assoc session/cookie-name "not-a-session")
          (is (expired-page? (h/visit! b :get "/oauth2/callback" :query "code=abc&state=xyz")))
          (is (zero? (token-endpoint-calls web)))))
      (is (= {:sessions 0 :pending 0} (counts web))))))

(deftest a-callback-whose-state-is-not-the-one-of-the-sign-in-asks-the-provider-nothing
  ;; login CSRF: somebody else's code, brought to this browser by a link
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)
            params (params-of callback)]
        (doseq [[what state] {"another state" "another-state"
                              "none" nil
                              "an empty one" ""
                              "the right one with something added" (str (get params "state") "x")
                              "the right one, shortened" (subs (get params "state") 1)}]
          (testing what
            (let [r (h/visit! b :get "/oauth2/callback"
                              :query (str "code=" (get params "code") (when state (str "&state=" state))))]
              (is (expired-page? r))
              (is (zero? (token-endpoint-calls web))))))
        (testing "and the real sign-in of the browser was not harmed: it can go on"
          (is (= {:sessions 0 :pending 1} (counts web)))
          (is (= 303 (:status (h/follow! b callback)))))))))

(deftest a-callback-cannot-be-used-twice
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)
            old-cookie (h/cookie b session/cookie-name)
            first-try (h/follow! b callback)]
        (is (= 303 (:status first-try)))
        (is (= 1 (token-endpoint-calls web)))
        (testing "by the same browser, now signed in"
          (is (expired-page? (h/follow! b callback)))
          (is (= 1 (token-endpoint-calls web)) "the provider was not asked again, and so did not take back the tokens"))
        (testing "nor by a browser that kept the old cookie"
          (let [replay (h/browser web)]
            (swap! (:cookies replay) assoc session/cookie-name old-cookie)
            (is (expired-page? (h/follow! replay callback)))
            (is (= 1 (token-endpoint-calls web)))))
        (testing "and the person is still signed in"
          (is (= 200 (:status (h/visit! b :get "/")))))))))

(deftest callbacks-that-arrive-at-the-same-time-make-one-session
  (with-web {}
    (fn [web]
      (dotimes [_ 5]
        (let [[b callback] (begun web)
              before (token-endpoint-calls web)
              n 8
              start (CountDownLatch. 1)
              cookie (str session/cookie-name "=" (h/cookie b session/cookie-name))
              sessions-before (:sessions (counts web))
              uri (java.net.URI. ^String callback)
              futures (doall (for [_ (range n)]
                               (future (.await start)
                                       (h/call web :get (.getRawPath uri) :query (.getRawQuery uri) :headers {"cookie" cookie}))))
              _ (.countDown start)
              responses (mapv deref futures)]
          (is (= 1 (count (filter #(= 303 (:status %)) responses))) "one of them signed the person in")
          (is (= (dec n) (count (filter expired-page? responses))) "and the rest were told that the sign-in is over")
          (is (every? #(empty? (h/set-cookies %)) (filter expired-page? responses))
              "without a word about the cookie, which is the new session's now")
          (is (= 1 (- (token-endpoint-calls web) before)) "the code was exchanged once")
          (is (= (inc sessions-before) (:sessions (counts web)))))))))

(deftest a-sign-in-that-takes-too-long-is-over
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)]
        (clock/advance! (:clock web) (* 10 60 1000))
        (is (expired-page? (h/follow! b callback)) "ten minutes after it began")
        (is (zero? (token-endpoint-calls web))))
      (testing "but not a minute before"
        (let [[b callback] (begun web)]
          (clock/advance! (:clock web) (dec (* 10 60 1000)))
          (is (= 303 (:status (h/follow! b callback)))))))))

(deftest what-the-provider-says-is-in-the-log-and-not-in-the-page
  (with-web {}
    (fn [web]
      (let [b (h/browser web)
            begin (h/visit! b :get "/sign-in")
            _ (oidc-fake/deny! (:idp web))
            provider (h/at-the-provider (header begin "Location"))
            r (h/follow! b (:location provider))
            [refusal] (events r :pulse.web/sign-in-refused)]
        (is (= "access_denied" (get (params-of (:location provider)) "error")))
        (is (= 400 (:status r)))
        (is (str/includes? (:text r) "We could not sign you in"))
        (is (not (str/includes? (:text r) "access_denied")))
        (is (not (str/includes? (:text r) "The person said no")))
        (is (= {:reason :provider-error :kind :sign-in-failed :error "access_denied"} (:data refusal)))
        (is (= :warn (:level refusal)))
        (is (zero? (token-endpoint-calls web)) "no code, no exchange")
        (testing "the sign-in was used, and the cookie goes: the person begins again"
          (is (= {:sessions 0 :pending 0} (counts web)))
          (is (str/includes? (str/join "; " (h/set-cookies r)) "Max-Age=0"))
          (is (nil? (h/cookie b session/cookie-name))))))))

(deftest text-from-the-provider-is-logged-only-when-it-is-plain
  (with-web {}
    (fn [web]
      (doseq [[error shown] {"access_denied" "access_denied"
                             "<script>alert(1)</script>" nil
                             (apply str (repeat 300 "a")) (apply str (repeat 100 "a"))
                             "line\nbreak" nil}]
        (let [[b callback] (begun web)
              state (get (params-of callback) "state")
              r (h/visit! b :get "/oauth2/callback" :query (str "error=" (URLEncoder/encode ^String error "UTF-8") "&state=" state))
              logged (:error (:data (first (events r :pulse.web/sign-in-refused))))]
          (is (= 400 (:status r)))
          (is (= shown logged) (pr-str error)))))))

(deftest a-callback-with-a-state-but-no-code-is-a-failed-sign-in
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)
            r (h/visit! b :get "/oauth2/callback" :query (str "state=" (get (params-of callback) "state")))]
        (is (= 400 (:status r)))
        (is (str/includes? (:text r) "We could not sign you in"))
        (is (= :no-code (:reason (:data (first (events r :pulse.web/sign-in-refused))))))
        (is (zero? (token-endpoint-calls web)))))))

;; ---------------------------------------------------------------------------
;; The provider says no, or cannot say

(defn- sign-in-with
  "Signs a browser in with the token endpoint told to say what `answer` says (a function of the request that gives the JSON
  text), and returns the portal's answer to the callback."
  [web answer]
  (let [[b callback] (begun web)]
    (idp/serve-token! (:idp web) {:answer answer})
    [b (h/follow! b callback)]))

(deftest a-code-the-provider-refuses-is-a-failed-sign-in
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)]
        (idp/serve-token! (:idp web) {:status 400 :body "{\"error\":\"invalid_grant\",\"error_description\":\"Code not valid\"}"})
        (let [r (h/follow! b callback)
              refusal (first (events r :pulse.web/sign-in-refused))]
          (is (= 400 (:status r)))
          (is (str/includes? (:text r) "We could not sign you in"))
          (is (not (str/includes? (:text r) "Code not valid")))
          (is (= {:reason :code-rejected :kind :sign-in-failed :status 400 :error "invalid_grant"} (:data refusal)))
          (is (= {:sessions 0 :pending 0} (counts web))))))))

(deftest a-provider-that-cannot-be-had-is-sign-in-not-being-available
  (doseq [[what serve] {"a server error" {:status 503 :body "<html>down</html>"}
                        "an answer that is not a grant" {:status 200 :body "not json"}
                        "a grant without a token" {:status 200 :body "{\"expires_in\":300,\"token_type\":\"Bearer\"}"}
                        "a client that is refused" {:status 500 :body "{}"}}]
    (testing what
      (with-web {}
        (fn [web]
          (let [[b callback] (begun web)]
            (idp/serve-token! (:idp web) serve)
            (let [r (h/follow! b callback)]
              (is (= 503 (:status r)))
              (is (str/includes? (:text r) "Sign-in is not available right now"))
              (is (str/includes? (:text r) "Try again"))
              (is (= {:sessions 0 :pending 0} (counts web))))))))))

(deftest a-provider-that-does-not-answer-in-time-is-the-same
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)]
        (idp/serve-token! (:idp web) {:delay-ms 2600})
        (let [started (System/nanoTime)
              r (h/follow! b callback)]
          (is (= 503 (:status r)))
          (is (= :timeout (:reason (:data (first (events r :pulse.web/sign-in-refused))))))
          (is (< (/ (- (System/nanoTime) started) 1e6) 2500) "and the portal did not wait for it"))))))

(deftest keys-that-cannot-be-had-are-the-provider-not-being-available-and-not-a-bad-token
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)]
        (idp/break! (:idp web))
        (let [r (h/follow! b callback)]
          (is (= 503 (:status r)))
          (is (str/includes? (:text r) "Sign-in is not available right now"))
          (is (= :keys-unavailable (:reason (:data (first (events r :pulse.web/sign-in-refused)))))))))))

;; ---------------------------------------------------------------------------
;; Both tokens are verified

(defn- grant-json
  "The JSON text of a grant of the token endpoint for the code of `request`, with the tokens of the claims in `access` and `id`
  merged over a valid pair (a nil value drops the claim). `:key` signs them (the provider's, by default)."
  [web request {:keys [access id key no-id-token?]}]
  (let [now (.getEpochSecond (clock/instant (:clock web)))
        code (get (idp/form-of (:body request)) "code")
        nonce (:nonce (get (oidc-fake/codes (:idp web)) code))
        base {:exp (+ now 300) :iat now :iss h/issuer :sub (:sub ana)}
        drop-nils (fn [m] (into {} (remove (comp nil? val)) m))
        access-claims (drop-nils (merge base {:aud ["pulse-api" "account"] :typ "Bearer" :azp "pulse-web" :sid "sid-1"
                                              :preferred_username "ana.garcia@pulse.test"
                                              :realm_access {:roles ["patient"]}}
                                        access))
        id-claims (drop-nils (merge base {:aud "pulse-web" :typ "ID" :azp "pulse-web" :nonce nonce :name "Ana García"} id))
        signer (or key h/signing-key)]
    (json/write-value-as-string
     (cond-> {:access_token (idp/mint signer access-claims)
              :expires_in 300 :refresh_expires_in 1800 :refresh_token "refresh-1" :token_type "Bearer"}
       (not no-id-token?) (assoc :id_token (idp/mint signer id-claims))))))

(defn- refused-with
  "Signs in with a grant made from `options` (see `grant-json`) and returns the portal's answer."
  [web options]
  (second (sign-in-with web (fn [request] (grant-json web request options)))))

(deftest a-pair-of-tokens-that-is-all-right-signs-the-person-in
  ;; the control of the tests below: what they change is the only thing wrong
  (with-web {}
    (fn [web]
      (is (= 303 (:status (refused-with web {})))))))

(deftest a-token-that-is-not-what-it-must-be-is-no-sign-in
  (doseq [[what options reason]
          [["an access token for another audience" {:access {:aud ["account"]}} :access-token-bad-audience]
           ["an access token that is not a bearer token" {:access {:typ "ID"}} :access-token-wrong-token-type]
           ["an access token from another issuer" {:access {:iss "http://evil.example/realms/pulse"}} :access-token-bad-issuer]
           ["an access token that has expired" {:access {:exp 1}} :access-token-expired]
           ["an access token without a subject" {:access {:sub nil}} :access-token-missing-claim]
           ["an id token for another client" {:id {:aud "pulse-spa"}} :id-token-bad-audience]
           ["an id token that is the access token" {:id {:aud ["pulse-api"] :typ "Bearer"}} :id-token-bad-audience]
           ["an id token of the wrong type" {:id {:typ "Bearer"}} :id-token-wrong-token-type]
           ["an id token from another issuer" {:id {:iss "http://evil.example/realms/pulse"}} :id-token-bad-issuer]
           ["an id token that has expired" {:id {:exp 1}} :id-token-expired]
           ["an id token with another nonce" {:id {:nonce "somebody-elses"}} :nonce-mismatch]
           ["an id token with no nonce" {:id {:nonce nil}} :nonce-mismatch]
           ["an id token of another person" {:id {:sub "somebody-else"}} :tokens-of-two-people]
           ["tokens signed with a key the provider does not publish" {:key (idp/signing-key "k1")} :access-token-bad-signature]
           ["no id token" {:no-id-token? true} :no-id-token]]]
    (testing what
      (with-web {}
        (fn [web]
          (let [r (refused-with web options)
                refusal (first (events r :pulse.web/sign-in-refused))]
            (is (= 400 (:status r)))
            (is (str/includes? (:text r) "We could not sign you in"))
            (is (= reason (:reason (:data refusal))))
            (is (= {:sessions 0 :pending 0} (counts web)) "no session")
            (is (empty? (events r :pulse.audit/access)) "nobody was let in or turned away: nobody was identified")))))))

(deftest the-id-token-of-the-person-and-not-the-access-token-gives-the-name
  (with-web {}
    (fn [web]
      (let [[b r] (sign-in-with web (fn [request]
                                      (grant-json web request {:access {:name "From the access token"} :id {:name "From the id token"}})))]
        (is (= 303 (:status r)))
        (is (= "From the id token" (:name (:person (session-of web b))))))
      (testing "and when it has none, the access token's, and then the user name"
        (let [[b _] (sign-in-with web (fn [request] (grant-json web request {:access {:name "Access name"} :id {:name nil}})))]
          (is (= "Access name" (:name (:person (session-of web b))))))
        (let [[b _] (sign-in-with web (fn [request] (grant-json web request {:id {:name "  "}})))]
          (is (= "ana.garcia@pulse.test" (:name (:person (session-of web b))))))
        (let [[b _] (sign-in-with web (fn [request] (grant-json web request {:access {:preferred_username nil} :id {:name nil}})))]
          (is (= "Patient" (:name (:person (session-of web b))))))))))

;; ---------------------------------------------------------------------------
;; Only a patient

(deftest somebody-from-the-clinic-is-told-the-portal-is-not-for-them-and-gets-no-session
  (doseq [[who role] [[:rivera "practitioner"] [:admin "clinic-admin"]]]
    (testing (name who)
      (with-web {}
        (fn [web]
          (oidc-fake/sign-in-as! (:idp web) (get oidc-fake/people who))
          (let [b (h/browser web)
                {:keys [callback]} (h/sign-in! b)
                person (get oidc-fake/people who)
                [audit :as audits] (events callback :pulse.audit/access)]
            (is (= 403 (:status callback)))
            (is (str/includes? (:text callback) "This portal is for patients"))
            (is (str/includes? (:text callback) "sign out and use the workspace instead"))
            (testing "no session: nothing in the store, no cookie, and the page of a patient is not theirs"
              (is (= {:sessions 0 :pending 0} (counts web)))
              (is (nil? (h/cookie b session/cookie-name)))
              (is (= 303 (:status (h/visit! b :get "/")))))
            (testing "the way out leads to the provider, which asks first: there is no id token to give it"
              (let [out (->> (str/split (:text callback) #"href=\"") rest (map #(first (str/split % #"\""))) (filter #(str/includes? % "/logout")) first)
                    params (params-of (str/replace out "&amp;" "&"))]
                (is (str/starts-with? out (oidc-fake/end-session-uri (:idp web))))
                (is (= {"client_id" "pulse-web" "post_logout_redirect_uri" (str h/base-url "/signed-out")} params))
                (is (not (contains? params "id_token_hint")))
                (is (= 200 (:status (h/at-the-provider (str/replace out "&amp;" "&")))) "which is the question whether to sign out")))
            (testing "the refusal is in the log as information: nothing went wrong"
              (let [[refused] (events callback :pulse.web/sign-in-refused)]
                (is (= :info (:level refused)))
                (is (= {:reason :not-a-patient :kind :not-for-patients} (:data refused)))))
            (testing "the refusal is audited, as a denial, with who was refused"
              (is (= 1 (count audits)))
              (is (= :warn (:level audit)))
              (is (= {:actor {:subject (:sub person) :username (:username person) :roles [role] :client "pulse-web"}
                      :action :portal/sign-in :outcome :denied :reason :not-a-patient :method "GET" :path "/oauth2/callback"}
                     (:data audit))))))))))

(deftest a-person-with-more-than-one-role-who-is-a-patient-is-a-patient
  (with-web {}
    (fn [web]
      (oidc-fake/sign-in-as! (:idp web) (assoc ana :roles ["practitioner" "patient"]))
      (let [{:keys [callback]} (h/sign-in! (h/browser web))]
        (is (= 303 (:status callback)))
        (is (= ["patient" "practitioner"] (get-in (first (events callback :pulse.audit/access)) [:data :actor :roles])))))))

(deftest the-roles-that-keycloak-adds-of-its-own-make-nobody-a-patient
  (with-web {}
    (fn [web]
      (oidc-fake/sign-in-as! (:idp web) (assoc ana :roles []))
      (let [{:keys [callback]} (h/sign-in! (h/browser web))]
        (is (= 403 (:status callback)) "default-roles-pulse, offline_access and uma_authorization are not the role")
        (is (= [] (get-in (first (events callback :pulse.audit/access)) [:data :actor :roles])))))))

;; ---------------------------------------------------------------------------
;; Signing out

(defn- csrf-of [web browser]
  (:csrf (session-of web browser)))

(deftest signing-out-ends-the-session-first-and-sends-the-browser-to-the-provider-with-the-hint
  (with-web {}
    (fn [web]
      (let [b (h/browser web)
            _ (h/sign-in! b)
            keeper (:tokens (session-of web b))
            id-token (config/reveal (oidc/id-token keeper))
            r (h/visit! b :post "/sign-out" :form {"__anti-forgery-token" (csrf-of web b)})
            location (header r "Location")
            params (params-of location)]
        (is (= 303 (:status r)))
        (is (= 1 (count (events r :pulse.web/signed-out))) "it is in the log")
        (is (str/starts-with? location (str (oidc-fake/end-session-uri (:idp web)) "?")))
        (is (= {"id_token_hint" id-token "post_logout_redirect_uri" (str h/base-url "/signed-out") "client_id" "pulse-web"} params))
        (testing "the session is gone from the store and the cookie from the browser, whatever the provider does next"
          (is (= {:sessions 0 :pending 0} (counts web)))
          (is (nil? (h/cookie b session/cookie-name)))
          (is (str/includes? (str/join "; " (h/set-cookies r)) "Max-Age=0")))
        (testing "the provider ends its session and sends the browser back to the page that says so"
          (let [back (h/at-the-provider location)]
            (is (= 302 (:status back)))
            (is (= (str h/base-url "/signed-out") (:location back)))
            (is (every? :ended? (vals (oidc-fake/sessions (:idp web)))))
            (let [page (h/follow! b (:location back))]
              (is (= 200 (:status page)))
              (is (str/includes? (:text page) "You have signed out")))))
        (testing "the tokens of the session are no good any more: the provider refuses to renew them"
          (reset! (:state keeper) nil)
          (is (= :rejected (:reason (try (token/access-token keeper) nil
                                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
        (testing "and the person is out: the page of a patient is not theirs"
          (is (= 303 (:status (h/visit! b :get "/")))))))))

(deftest the-hint-is-the-last-id-token-the-provider-gave
  (with-web {}
    (fn [web]
      (let [b (h/browser web)
            _ (h/sign-in! b)
            keeper (:tokens (session-of web b))
            first-id (config/reveal (oidc/id-token keeper))]
        (clock/advance! (:clock web) 250000)
        (token/access-token keeper)
        (let [renewed-id (config/reveal (oidc/id-token keeper))
              r (h/visit! b :post "/sign-out" :form {"__anti-forgery-token" (csrf-of web b)})
              hint (get (params-of (header r "Location")) "id_token_hint")]
          (is (not= first-id renewed-id) "the renewal gave a new one")
          (is (= renewed-id hint))
          (is (> (seconds-of hint :iat) (seconds-of first-id :iat))))))))

(deftest signing-out-needs-the-token-and-a-person
  (with-web {}
    (fn [web]
      (let [b (h/browser web)]
        (h/sign-in! b)
        (testing "no token, a wrong one: refused, and the person is still in"
          (doseq [form [{} {"__anti-forgery-token" "wrong"} {"__anti-forgery-token" ""}]]
            (let [r (h/visit! b :post "/sign-out" :form form)]
              (is (= 403 (:status r)) (pr-str form))
              (is (str/includes? (:text r) "We could not do that"))
              (is (nil? (header r "Location")))
              (is (= {:sessions 1 :pending 0} (counts web))))))
        (testing "a link does nothing: it is a GET, and the page says so"
          (let [r (h/visit! b :get "/sign-out")]
            (is (= 405 (:status r)))
            (is (= "POST" (header r "Allow")))
            (is (= {:sessions 1 :pending 0} (counts web)))))
        (testing "the token in the header works too, as htmx sends it"
          (let [r (h/visit! b :post "/sign-out" :headers {"x-csrf-token" (csrf-of web b)})]
            (is (= 303 (:status r)))
            (is (= {:sessions 0 :pending 0} (counts web)))))))))

(deftest somebody-who-is-not-in-cannot-sign-out-and-is-sent-to-sign-in
  (with-web {}
    (fn [web]
      (let [r (h/call web :post "/sign-out")]
        (is (= 303 (:status r)))
        (is (= "/sign-in" (header r "Location")))
        (is (zero? (count (oidc-fake/logout-requests (:idp web)))))))))

(deftest the-page-after-signing-out-is-for-anybody
  (with-web {}
    (fn [web]
      (let [r (h/call web :get "/signed-out")]
        (is (= 200 (:status r)))
        (is (str/includes? (:text r) "You have signed out"))
        (is (str/includes? (:text r) "href=\"/sign-in\""))
        (is (= "no-store" (header r "Cache-Control")))))))

;; ---------------------------------------------------------------------------
;; The page of a person who is in

(deftest the-page-of-a-person-who-is-in-has-the-way-out-and-the-token-for-it
  (with-web {}
    (fn [web]
      (let [b (h/browser web)]
        (h/sign-in! b)
        (let [home (h/visit! b :get "/")
              token (csrf-of web b)]
          (is (= 200 (:status home)))
          (is (= "no-store" (header home "Cache-Control")) "it is a patient's page")
          (is (str/includes? (:text home) (str "name=\"__anti-forgery-token\" type=\"hidden\" value=\"" token "\""))
              "the form of signing out carries the token of the session")
          (is (str/includes? (:text home) "action=\"/sign-out\""))
          (is (str/includes? (:text home) "X-CSRF-Token") "and htmx has it for the requests it makes")
          (testing "nobody else gets it"
            (is (= 303 (:status (h/call web :get "/"))))))))))

;; ---------------------------------------------------------------------------
;; Served over https

(def ^:private https-url "https://portal.example")

(deftest a-portal-served-over-https-has-its-cookie-its-headers-and-its-addresses-to-match
  (with-web {:base-url https-url}
    (fn [web]
      (testing "every answer tells the browser to keep to https"
        (is (= "max-age=63072000; includeSubDomains" (header (h/call web :get "/healthz") "Strict-Transport-Security")))
        (is (= "max-age=63072000; includeSubDomains" (header (h/call web :get "/nothing") "Strict-Transport-Security"))))
      (let [b (h/browser web)
            begin (h/visit! b :get "/sign-in")]
        (testing "the cookie is only for https"
          (is (contains? (set (map str/lower-case (rest (str/split (first (h/set-cookies begin)) #";\s*")))) "secure")))
        (testing "the redirect address is the portal's, as the realm registered it, and not the one of the request"
          (is (= (str https-url "/oauth2/callback") (get (params-of (header begin "Location")) "redirect_uri"))))
        (testing "the person signs in all the same, and the cookie of the session is for https too"
          (let [provider (h/at-the-provider (header begin "Location"))
                callback (h/follow! b (:location provider))]
            (is (str/starts-with? (:location provider) (str https-url "/oauth2/callback?")))
            (is (= 303 (:status callback)))
            (is (contains? (set (map str/lower-case (rest (str/split (first (h/set-cookies callback)) #";\s*")))) "secure"))))
        (testing "a request that changes something must say it comes from the https origin"
          (let [csrf (:csrf (session-of web b))]
            (is (= 403 (:status (h/visit! b :post "/sign-out" :headers {"origin" h/base-url} :form {"__anti-forgery-token" csrf})))
                "the address of a portal served over http is not this one")
            (is (= 303 (:status (h/visit! b :post "/sign-out" :headers {"origin" https-url} :form {"__anti-forgery-token" csrf}))))))))))

(deftest a-portal-served-over-http-sends-neither-hsts-nor-a-secure-cookie
  (with-web {}
    (fn [web]
      (is (nil? (header (h/call web :get "/healthz") "Strict-Transport-Security")))
      (is (not (str/includes? (str/lower-case (str/join ";" (h/set-cookies (h/call web :get "/sign-in")))) "secure"))))))

;; ---------------------------------------------------------------------------
;; What nobody expected, what was said twice, and the reasons in the log

(deftest a-failure-that-is-not-a-refusal-is-a-failure-on-our-side-and-not-a-refusal
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)]
        (with-redefs [oidc/exchange-code! (fn [& _] (throw (ex-info "something else broke" {:k 1})))]
          (let [r (h/follow! b callback)]
            (is (= 500 (:status r)))
            (is (str/includes? (:text r) "Something went wrong on our side"))
            (is (empty? (events r :pulse.web/sign-in-refused)) "a bug is not a person who was turned away")
            (is (= "something else broke" (ex-message (:error (first (events r :pulse.http/unhandled-exception))))))))))))

(deftest a-parameter-that-comes-twice-is-none
  (with-web {}
    (fn [web]
      (testing "a code twice is no code"
        (let [[b callback] (begun web)
              state (get (params-of callback) "state")
              r (h/visit! b :get "/oauth2/callback" :query (str "state=" state "&code=a&code=b"))]
          (is (= 400 (:status r)))
          (is (str/includes? (:text r) "We could not sign you in"))
          (is (= :no-code (:reason (:data (first (events r :pulse.web/sign-in-refused))))))
          (is (zero? (token-endpoint-calls web)))))
      (testing "an error twice is logged as nothing"
        (let [[b callback] (begun web)
              state (get (params-of callback) "state")
              r (h/visit! b :get "/oauth2/callback" :query (str "state=" state "&error=a&error=b"))
              [refused] (events r :pulse.web/sign-in-refused)]
          (is (= 400 (:status r)))
          (is (= :provider-error (:reason (:data refused))))
          (is (not (contains? (:data refused) :error)))))
      (testing "a state twice is not the state"
        (let [[b callback] (begun web)
              state (get (params-of callback) "state")
              r (h/visit! b :get "/oauth2/callback" :query (str "state=" state "&state=" state "&code=a"))]
          (is (expired-page? r))
          (is (zero? (token-endpoint-calls web))))))))

(deftest the-reason-a-callback-was-turned-away-is-in-the-log-as-a-warning
  (with-web {}
    (fn [web]
      (testing "a browser that has no sign-in"
        (let [r (h/call web :get "/oauth2/callback" :query "code=abc&state=xyz")
              [logged] (events r :pulse.web/sign-in-refused)]
          (is (= :warn (:level logged)))
          (is (= {:reason :no-pending-sign-in :kind :sign-in-expired} (:data logged)))))
      (testing "a browser whose sign-in has another state"
        (let [[b _] (begun web)
              r (h/visit! b :get "/oauth2/callback" :query "code=abc&state=another")
              [logged] (events r :pulse.web/sign-in-refused)]
          (is (= :warn (:level logged)))
          (is (= {:reason :state-mismatch :kind :sign-in-expired} (:data logged))))))))

(deftest a-callback-that-lost-the-race-for-the-sign-in-says-so-and-leaves-the-cookie-alone
  ;; the request that won made the new cookie; this one's answer may be the one the browser gets last
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)]
        (with-redefs [session/consume! (fn [& _] nil)]
          (let [r (h/follow! b callback)]
            (is (expired-page? r))
            (is (empty? (h/set-cookies r)))
            (is (= :pending-sign-in-taken (:reason (:data (first (events r :pulse.web/sign-in-refused))))))
            (is (zero? (token-endpoint-calls web)))))))))

(deftest the-first-token-counts-its-life-from-before-the-exchange
  (with-web {}
    (fn [web]
      (let [[b callback] (begun web)
            exchange! oidc/exchange-code!]
        ;; the exchange takes a hundred seconds by the clock of the test
        (with-redefs [oidc/exchange-code! (fn [& args] (clock/advance! (:clock web) 100000) (apply exchange! args))]
          (h/follow! b callback))
        (let [keeper (:tokens (session-of web b))
              renewals #(count (oidc-fake/grants (:idp web) "refresh_token"))]
          (clock/advance! (:clock web) 139999)
          (token/access-token keeper)
          (is (zero? (renewals)) "239.999 seconds after the request was sent, the margin has not begun")
          (clock/advance! (:clock web) 1)
          (token/access-token keeper)
          (is (= 1 (renewals)) "and at 240 it has"))))))

(deftest the-callback-gives-a-new-session-and-goes-only-to-a-path-of-the-portal-whatever-the-store-held
  ;; the place to come back to is made safe when the sign-in begins and again when it ends, so a store that held another is
  ;; no way out of the portal; and the session is asked to be a new entry under a new key, whatever the browser came with
  (with-web {}
    (fn [web]
      (let [deps (select-keys web [:oidc :sessions :access-verifier :id-verifier])
            values (oidc/new-sign-in)
            pending-key (store/write-session (:sessions web) nil {:sign-in (assoc values :return-to "//evil.example/x")})
            {:keys [location]} (h/at-the-provider (oidc/authorization-address (:oidc web) values))
            response ((signin/callback deps) {:request-method :get :uri "/oauth2/callback" :headers {}
                                              :query-params (params-of location)
                                              :session (store/read-session (:sessions web) pending-key)
                                              :session/key pending-key})]
        (is (= 303 (:status response)))
        (is (= "/" (get-in response [:headers "Location"])) "and not the address the store held")
        (is (true? (:recreate (meta (:session response)))))
        (is (= #{:person :tokens :csrf} (set (keys (:session response)))))))))
