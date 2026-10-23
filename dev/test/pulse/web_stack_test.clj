(ns pulse.web-stack-test
  "Integration test: the patient portal as `bb dev lite` starts it, against the real Keycloak of the local stack (ADR-0028). A
  patient signs in through the login page of the Pulse theme, as a person does, the portal holds their tokens on the server, the
  api accepts the one it would send, a renewal that is forced is made with the real refresh grant, and signing out ends the
  session at Keycloak too, after which the renewal is refused. A doctor and the administrator are told that the portal is for
  patients and get no session. Nothing here can be seen with the fake provider of the other tests: that the realm's client
  `pulse-web` accepts what the portal sends (PKCE, the Basic authentication, the redirect and the post-logout addresses) and
  gives what it reads (the audiences, the roles, `typ`, the `nonce`, the name).

  Needs the stack (`bb up`), the secrets in `.env`, and the port 3300 free, because the realm registers
  http://localhost:3300/oauth2/callback for the client and nothing else (so `bb web` and `bb dev` must not be running). It runs
  with `bb test:it`. It keeps its FHIR server in memory and changes nothing in Keycloak or HAPI."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [loginpage]
            [pulse.dev :as dev]
            [pulse.platform.config :as config]
            [pulse.platform.token :as token]
            [pulse.web.oidc :as oidc]
            [pulse.web.session :as session]
            [ring.middleware.session.store :as store])
  (:import (java.net URLDecoder URLEncoder)
           (java.time Instant)))

(def ^:private quiet
  "A schedule that will not fire while the test runs: once a week, on the grid of the epoch."
  {:type :interval :every-ms 604800000})

(def ^:private origin
  "What a browser says it is on: the realm registers this address and no other."
  "http://localhost:3300")

(def ^:private portal-url
  "Where the requests go. `localhost` can be the other loopback address, which nobody listens at."
  "http://127.0.0.1:3300")

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(def ^:private no-redirects
  (delay (http/client {:follow-redirects :never})))

(defn- with-portal
  "Calls `(f {:system s :api url :password p})` with the api, the scheduler and the portal started as `bb dev lite` does: the real
  Keycloak, the portal on its own port 3300 and the others on free ports, with the secrets of `.env`."
  [f]
  (let [api-port (free-port)
        scheduler-port (free-port)
        api-url (str "http://127.0.0.1:" api-port)
        scheduler-url (str "http://127.0.0.1:" scheduler-port)
        password (get (dev/environment ".env") "DEMO_USER_PASSWORD")
        cfg (-> (dev/config {:profile :lite})
                (assoc-in [:pulse.api/server :port] api-port)
                (assoc-in [:pulse.api/logging :level] :warn)
                (assoc-in [:pulse.api/scheduler :base-url] scheduler-url)
                (assoc-in [:pulse.scheduler/server :port] scheduler-port)
                (assoc-in [:pulse.scheduler/logging :level] :warn)
                (assoc-in [:pulse.scheduler/api :base-url] api-url)
                (assoc-in [:pulse.scheduler/jobs :evaluate-rules :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :simulate-devices :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :send-reminders :schedule] quiet)
                (assoc-in [:pulse.web/logging :level] :warn)
                (assoc-in [:pulse.web/api :base-url] api-url))
        system (ig/init cfg)]
    (try (f {:system system :api api-url :password password})
         (finally (ig/halt! system)))))

;; ---------------------------------------------------------------------------
;; A browser: its cookies for each of the two servers it talks to, and requests that do not follow redirects

(defn- new-browser []
  {:keycloak (atom {}) :portal (atom {})})

(defn- keep-cookies! [jar response]
  (let [set-cookie (get-in response [:headers "set-cookie"])]
    (doseq [cookie (cond (nil? set-cookie) [] (string? set-cookie) [set-cookie] :else set-cookie)
            :let [[pair & attributes] (str/split cookie #";\s*")
                  [k v] (str/split pair #"=" 2)]]
      (if (some #(re-matches #"(?i)max-age=0+" %) attributes)
        (swap! jar dissoc k)
        (swap! jar assoc k v))))
  response)

(defn- request!
  "A request of the browser, with the cookies of `jar`, which keeps those that come back."
  [jar method url & {:keys [headers form]}]
  (keep-cookies! jar
                 (http/request (cond-> {:method method :uri url :throw false :client @no-redirects
                                        :headers (cond-> (merge {"Accept" "text/html"} headers)
                                                   (seq @jar) (assoc "Cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) @jar))))}
                                 form (assoc :form-params form)))))

(defn- location [response]
  (get-in response [:headers "location"]))

(defn- at-the-portal
  "An address the portal was said to be at, as the address where it can be reached."
  [address]
  (str/replace address origin portal-url))

(defn- params-of [address]
  (into {} (for [pair (str/split (or (second (str/split address #"\?" 2)) "") #"&")
                 :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)
                       ^String k k
                       ^String v (or v "")]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode v "UTF-8")])))

(defn- begin!
  "The browser asks the portal to sign in, is sent to Keycloak and gets its login page. Returns the answers: `:begin` (the
  portal's), `:login-page` (Keycloak's) and the `:action` of its form."
  [browser & {:keys [return-to]}]
  (let [begin (request! (:portal browser) :get
                        (str portal-url "/sign-in" (when return-to (str "?return-to=" (URLEncoder/encode ^String return-to "UTF-8")))))
        login-page (request! (:keycloak browser) :get (location begin))]
    {:begin begin :login-page login-page :action (loginpage/form-action (:body login-page))}))

(defn- sign-in!
  "The browser signs in as `username` through the login page of Keycloak, and comes back to the portal. Returns the answers on
  the way: `:begin`, `:login-page`, `:answer` (Keycloak's to the credentials) and `:callback` (the portal's, or nil)."
  [browser password username & {:keys [return-to]}]
  (let [{:keys [action] :as started} (begin! browser :return-to return-to)
        answer (request! (:keycloak browser) :post action :form {"username" username "password" password "credentialId" ""})
        callback (when (location answer)
                   (request! (:portal browser) :get (at-the-portal (location answer))))]
    (assoc started :answer answer :callback callback)))

(defn- session-of
  "What the portal's store holds for the cookie the browser has, or nil."
  [system browser]
  (store/read-session (:pulse.web/sessions system) (get @(:portal browser) session/cookie-name)))

(defn- api-me [api token]
  (let [r (http/get (str api "/api/me") {:throw false :headers {"Authorization" (str "Bearer " token) "Accept" "application/json"}})]
    (assoc r :json (json/read-value (:body r) json/keyword-keys-object-mapper))))

(defn- forcing-a-renewal!
  "Makes the keeper of a session find its token near its end, as it would five minutes of use from now."
  [keeper]
  (swap! (:state keeper) assoc :renew-at (.minusSeconds (Instant/now) 1)))

(defn- failure-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---------------------------------------------------------------------------
;; A patient

(deftest ^:integration a-patient-signs-in-through-the-login-page-and-the-portal-knows-who-they-are
  (with-portal
    (fn [{:keys [system api password]}]
      (let [browser (new-browser)
            {:keys [login-page action answer callback]} (sign-in! browser password "ana.garcia@pulse.test" :return-to "/")]
        (testing "the login page is the one of the Pulse theme, and the portal is the client that asked for it"
          (is (= 200 (:status login-page)))
          (is (some? action))
          (is (str/includes? (:body login-page) "Pulse")))
        (testing "Keycloak sends the browser back to the portal with a code and the state, and the portal lets the person in"
          (is (= 302 (:status answer)))
          (is (str/starts-with? (location answer) (str origin "/oauth2/callback?")))
          (is (= #{"state" "session_state" "iss" "code"} (set (keys (params-of (location answer))))))
          (is (= 303 (:status callback)))
          (is (= "/" (location callback))))
        (testing "the page of the person is their home, made from what the real api says of them, asked for with their own token"
          (let [home (request! (:portal browser) :get (str portal-url "/"))
                body (:body home)]
            (is (= 200 (:status home)))
            (is (str/includes? body "Account menu of Ana García") "who they are, as the id token of the realm says it")
            (is (re-find #"Good (morning|afternoon|evening), Ana" body) "named as their record names them")
            (is (str/includes? body "Your next appointment"))
            (is (str/includes? body "Hypertension follow-up"))
            (is (str/includes? body "Dr. Elena Rivera") "and their doctor, as the api names them")
            (is (str/includes? body "Your latest readings"))
            (is (str/includes? body "Blood pressure"))
            (is (not (re-find #"Sofía|Liam|Maya|Omar|Noah" body)) "and nobody else")))
        (testing "the session is on the server: the person, the keeper of their tokens and a CSRF token"
          (let [{:keys [person tokens csrf]} (session-of system browser)]
            (is (= "Ana García" (:name person)))
            (is (= "ana.garcia@pulse.test" (:username person)))
            (is (string? (:id person)))
            (is (re-matches #"[A-Za-z0-9_-]{43}" csrf))
            (is (config/secret? (oidc/id-token tokens)))
            (testing "the browser has the cookie of the session and nothing else of the portal's"
              (is (= #{session/cookie-name} (set (keys @(:portal browser))))))
            (testing "the token the portal would send to the api is accepted, and the api knows the patient by it"
              (let [r (api-me api (token/access-token tokens))]
                (is (= 200 (:status r)))
                (is (= "Patient/pt-ana" (get-in r [:json :fhirUser])))
                (is (= ["patient"] (get-in r [:json :roles])))
                (is (= "pulse-web" (get-in r [:json :client])) "the token was issued to the portal")
                (is (= "Dr. Elena Rivera" (get-in r [:json :generalPractitioner :name])))))))))))

(deftest ^:integration a-patient-is-brought-back-to-the-page-they-asked-for
  (with-portal
    (fn [{:keys [password]}]
      (let [browser (new-browser)
            {:keys [callback]} (sign-in! browser password "noah.kim@pulse.test" :return-to "/readings?period=30")]
        (is (= 303 (:status callback)))
        (is (= "/readings?period=30" (location callback)))))))

(deftest ^:integration a-renewal-is-made-with-the-refresh-grant-of-the-realm
  (with-portal
    (fn [{:keys [system api password]}]
      (let [browser (new-browser)]
        (sign-in! browser password "ana.garcia@pulse.test")
        (let [{:keys [tokens]} (session-of system browser)
              first-token (token/access-token tokens)
              first-id (config/reveal (oidc/id-token tokens))]
          (forcing-a-renewal! tokens)
          (let [second-token (token/access-token tokens)]
            (is (not= first-token second-token) "a new access token")
            (is (not= first-id (config/reveal (oidc/id-token tokens))) "and a new id token, which signing out will need")
            (is (= 200 (:status (api-me api second-token))) "which the api accepts"))
          (testing "and again, with the refresh token the realm gave the first time"
            (forcing-a-renewal! tokens)
            (let [third-token (token/access-token tokens)]
              (is (= 200 (:status (api-me api third-token)))))))))))

(deftest ^:integration signing-out-ends-the-session-at-keycloak-too-and-the-renewal-is-refused-after
  (with-portal
    (fn [{:keys [system password]}]
      (let [browser (new-browser)]
        (sign-in! browser password "ana.garcia@pulse.test")
        (let [{:keys [tokens csrf]} (session-of system browser)
              out (request! (:portal browser) :post (str portal-url "/sign-out")
                            :headers {"Origin" origin}
                            :form {"__anti-forgery-token" csrf})]
          (testing "the portal ends the session first and sends the browser to Keycloak with the hint"
            (is (= 303 (:status out)))
            (is (nil? (session-of system browser)))
            (is (empty? @(:portal browser)) "the cookie is gone")
            (is (str/starts-with? (location out) "http://localhost:8080/realms/pulse/protocol/openid-connect/logout?"))
            (is (= #{"id_token_hint" "post_logout_redirect_uri" "client_id"} (set (keys (params-of (location out)))))))
          (testing "Keycloak signs the person out and sends the browser back to the portal, to the page that says so"
            (let [back (request! (:keycloak browser) :get (location out))]
              (is (= 302 (:status back)))
              (is (= (str origin "/signed-out") (location back)))
              (let [page (request! (:portal browser) :get (at-the-portal (location back)))]
                (is (= 200 (:status page)))
                (is (str/includes? (:body page) "You have signed out")))))
          (testing "the tokens of the session can be renewed no more: the realm refuses the refresh token"
            (reset! (:state tokens) {:token "gone" :expires-at (.minusSeconds (Instant/now) 1) :renew-at (.minusSeconds (Instant/now) 61)})
            (let [data (failure-of #(token/access-token tokens))]
              (is (= :rejected (:reason data)))
              (is (= "invalid_grant" (:error data)))
              (is (true? (oidc/rejected? data)))))
          (testing "and Keycloak asks for the password again: the single sign-on session ended"
            (let [{:keys [action login-page]} (begin! browser)]
              (is (= 200 (:status login-page)))
              (is (some? action)))))))))

;; ---------------------------------------------------------------------------
;; Somebody from the clinic

(deftest ^:integration somebody-from-the-clinic-is-told-the-portal-is-for-patients-and-gets-no-session
  (with-portal
    (fn [{:keys [system password]}]
      (doseq [username ["dr.rivera@pulse.test" "admin@pulse.test"]]
        (testing username
          (let [browser (new-browser)
                {:keys [callback]} (sign-in! browser password username)]
            (is (= 403 (:status callback)))
            (is (str/includes? (:body callback) "This portal is for patients"))
            (is (nil? (session-of system browser)))
            (is (empty? @(:portal browser)) "no cookie either")
            (is (= 303 (:status (request! (:portal browser) :get (str portal-url "/")))) "and the page of a patient is not theirs")
            (testing "the way out leads to Keycloak, which asks first, and then back to the portal"
              (let [link (->> (str/split (:body callback) #"href=\"") rest (map #(first (str/split % #"\""))) (map #(str/replace % "&amp;" "&"))
                              (filter #(str/includes? % "/logout")) first)
                    asked (request! (:keycloak browser) :get link)]
                (is (some? link))
                (is (= 200 (:status asked)) "the question whether to sign out")))))))))

;; ---------------------------------------------------------------------------
;; A callback that is not the answer to this sign-in

(deftest ^:integration a-callback-with-another-state-or-used-twice-gets-nothing-and-harms-nothing
  (with-portal
    (fn [{:keys [system password]}]
      (let [browser (new-browser)
            {:keys [action]} (begin! browser)
            answer (request! (:keycloak browser) :post action :form {"username" "ana.garcia@pulse.test" "password" password "credentialId" ""})
            callback (at-the-portal (location answer))
            params (params-of callback)]
        (testing "a callback with a state that is not the sign-in's is refused, and the sign-in is still there"
          (let [r (request! (:portal browser) :get (str portal-url "/oauth2/callback?code=" (get params "code") "&state=another"))]
            (is (= 400 (:status r)))
            (is (str/includes? (:body r) "That sign-in has expired"))
            (is (nil? (:person (session-of system browser))) "nobody was let in")
            (is (some? (:sign-in (session-of system browser))) "and the sign-in of the browser was not harmed")))
        (testing "the real one still works"
          (is (= 303 (:status (request! (:portal browser) :get callback))))
          (is (some? (session-of system browser))))
        (testing "and used again, it gets nothing: the portal does not even ask Keycloak, which would have taken the tokens back"
          (let [r (request! (:portal browser) :get callback)]
            (is (= 400 (:status r)))
            (is (str/includes? (:body r) "That sign-in has expired")))
          (let [{:keys [tokens]} (session-of system browser)]
            (forcing-a-renewal! tokens)
            (is (string? (token/access-token tokens)) "so the session of the first use still renews")))))))
