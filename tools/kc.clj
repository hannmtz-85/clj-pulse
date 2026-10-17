(ns kc
  "Headless Keycloak helpers for development and smoke tests: sign in as a demo user with the
  authorization-code flow and PKCE (what the SPA does in a browser) or as a service account,
  and inspect the resulting tokens."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [env]
            [loginpage]
            [realm])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(def base-url "http://localhost:8080")
(def realm-url (str base-url "/realms/" realm/realm-name))
(def auth-url (str realm-url "/protocol/openid-connect/auth"))
(def token-url (str realm-url "/protocol/openid-connect/token"))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- random-verifier []
  (let [bs (byte-array 48)]
    (.nextBytes (SecureRandom.) bs)
    (b64url bs)))

(defn- challenge [verifier]
  (b64url (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String verifier StandardCharsets/US_ASCII))))

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- form [m]
  (str/join "&" (map (fn [[k v]] (str (url-encode k) "=" (url-encode v))) m)))

(defn decode-jwt
  "The payload (claims) of a JWT as a map with keyword keys. The signature is NOT checked."
  [token]
  (let [payload (second (str/split token #"\."))]
    (json/parse-string (String. (.decode (Base64/getUrlDecoder) ^String payload) StandardCharsets/UTF_8) true)))

(defn- token-request [params]
  (let [{:keys [status body]} (http/post token-url
                                         {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                                          :body (form params)
                                          :throw false})]
    (when-not (= 200 status)
      (throw (ex-info "Token request failed" {:status status :body body})))
    (json/parse-string body true)))

(defn client-credentials
  "Token response for a confidential client's service account."
  [client-id client-secret]
  (token-request {"grant_type" "client_credentials"
                  "client_id" client-id
                  "client_secret" client-secret}))

(defn- cookie-header
  "A `Cookie` request header value built from the Set-Cookie headers of `resp`.
  babashka.http-client has no cookie jar, and Keycloak needs its session cookies back."
  [resp]
  (let [set-cookie (get-in resp [:headers "set-cookie"])
        cookies (cond (nil? set-cookie) [] (string? set-cookie) [set-cookie] :else set-cookie)]
    (str/join "; " (map #(first (str/split % #";")) cookies))))

(defn login
  "Signs in as `username` with the authorization-code flow + PKCE and returns the token
  response ({:access_token ... :id_token ... :refresh_token ...})."
  [{:keys [username password client-id redirect-uri client-secret]
    :or {client-id "pulse-spa" redirect-uri "http://localhost:3000/callback"}}]
  (let [http-client (http/client {:follow-redirects :never})
        verifier (random-verifier)
        page (http/get auth-url
                       {:client http-client
                        :query-params {"client_id" client-id
                                       "redirect_uri" redirect-uri
                                       "response_type" "code"
                                       "scope" "openid"
                                       "state" "smoke"
                                       "code_challenge" (challenge verifier)
                                       "code_challenge_method" "S256"}})
        action (loginpage/form-action (:body page))]
    (when-not action
      (throw (ex-info "Login form not found" {:status (:status page)})))
    (let [resp (http/post action
                          {:client http-client
                           :headers {"Content-Type" "application/x-www-form-urlencoded"
                                     "Cookie" (cookie-header page)}
                           :body (form {"username" username "password" password "credentialId" ""})
                           :throw false})
          location (get-in resp [:headers "location"])
          code (some->> location (re-find #"[?&]code=([^&]+)") second)]
      (when-not code
        (throw (ex-info "Login rejected" {:username username :status (:status resp)})))
      (token-request (cond-> {"grant_type" "authorization_code"
                              "code" code
                              "client_id" client-id
                              "redirect_uri" redirect-uri
                              "code_verifier" verifier}
                       client-secret (assoc "client_secret" client-secret))))))

(defn- audiences [claims]
  (set (flatten [(:aud claims)])))

(def ^:private app-roles (set (map :name realm/roles)))

(defn- person-checks
  "What a token of a demo user must say, for the client `azp` it was issued to."
  ([person claims] (person-checks person claims "pulse-spa"))
  ([{:keys [role fhir-user]} claims azp]
   [;; least privilege: exactly the expected application role, no other
    ["role" (= #{role} (set (filter app-roles (get-in claims [:realm_access :roles]))))]
    ["fhirUser" (= fhir-user (:fhirUser claims))]
    ["audience" (contains? (audiences claims) realm/audience)]
    ["azp" (= azp (:azp claims))]]))

(def ^:private docs-redirect
  "Where Swagger UI of the api returns to after the sign-in (`pulse-docs` allows `/docs/*`)."
  "http://localhost:4000/docs/oauth2-redirect.html")

(defn- docs-client-checks
  "The sign-in of Swagger UI, without a browser: `pulse-docs` gives a practitioner a token the api
  accepts, and refuses a return address outside `/docs/`."
  [password]
  (let [person (first (filter #(= "practitioner" (:role %)) realm/people))
        username (:username person)
        label (str "Swagger UI client pulse-docs, as " username)]
    [(try
       (let [claims (-> (login {:username username :password password
                                :client-id "pulse-docs" :redirect-uri docs-redirect})
                        :access_token decode-jwt)]
         [label (person-checks person claims "pulse-docs")])
       (catch Exception e [label [["sign-in" false] ["error" (ex-message e)]]]))
     (try
       (login {:username username :password password
               :client-id "pulse-docs" :redirect-uri "http://localhost:4000/api/me"})
       ["pulse-docs refuses a return address outside /docs/" [["rejected" false]]]
       (catch Exception _ ["pulse-docs refuses a return address outside /docs/" [["rejected" true]]]))]))

(defn- api-service-checks
  "What the token of the api's own service account must say (ADR-0025): the one application role
  `api-service`, the scheduler as its audience and not the api itself, and the client it was
  issued to."
  []
  (let [label "service account pulse-api"]
    (try
      (let [claims (-> (client-credentials "pulse-api" (env/get-var "API_CLIENT_SECRET"))
                       :access_token decode-jwt)]
        [label
         [["role" (= #{"api-service"} (set (filter app-roles (get-in claims [:realm_access :roles]))))]
          ["audience" (contains? (audiences claims) realm/scheduler-audience)]
          ["not for the api itself" (not (contains? (audiences claims) realm/audience))]
          ["azp" (= "pulse-api" (:azp claims))]]])
      (catch Exception e [label [["sign-in" false] ["error" (ex-message e)]]]))))

(defn smoke
  "Signs in as every demo user and as the service accounts of the scheduler and the api, and checks
  the claims the services rely on. Exits 1 on any failure."
  []
  (let [password (env/get-var "DEMO_USER_PASSWORD")
        results (concat
                 (for [person realm/people]
                   (let [label (:username person)]
                     (try
                       (let [claims (-> (login {:username label :password password}) :access_token decode-jwt)]
                         [label (person-checks person claims)])
                       (catch Exception e [label [["sign-in" false] ["error" (ex-message e)]]]))))
                 (docs-client-checks password)
                 [(try
                    (let [claims (-> (client-credentials "pulse-scheduler" (env/get-var "SCHEDULER_CLIENT_SECRET"))
                                     :access_token decode-jwt)]
                      ["service account pulse-scheduler"
                       [["role" (contains? (set (get-in claims [:realm_access :roles])) "rules-engine")]
                        ["audience" (contains? (audiences claims) realm/audience)]
                        ["azp" (= "pulse-scheduler" (:azp claims))]]])
                    (catch Exception e ["service account pulse-scheduler" [["sign-in" false] ["error" (ex-message e)]]]))
                  (api-service-checks)
                  (try
                    (login {:username "ana.garcia@pulse.test" :password "definitely-wrong"})
                    ["wrong password is rejected" [["rejected" false]]]
                    (catch Exception _ ["wrong password is rejected" [["rejected" true]]]))])
        failed (filter (fn [[_ checks]] (some (fn [[_ ok]] (false? ok)) checks)) results)]
    (doseq [[label checks] results]
      (println (if (some (fn [[_ ok]] (false? ok)) checks) "FAIL" "OK  ") label
               (pr-str (into {} (map (fn [[k v]] [k v])) checks))))
    (println)
    (if (seq failed)
      (do (println (count failed) "check group(s) failed") (System/exit 1))
      (println "All sign-ins and claims verified"))))

(defn print-token
  "Prints an access token for `who`: a demo user's username, or `scheduler` or `api` for the service
  account of one of them. A user's token is the one the clinical workspace gets (client `pulse-spa`),
  or with `pulse-docs` as `client`, the one Swagger UI gets."
  ([who] (print-token who nil))
  ([who client]
   (when (or (str/blank? who) (not (contains? #{nil "pulse-spa" "pulse-docs"} client)))
     (println "usage: bb token <username | scheduler | api> [pulse-docs]")
     (System/exit 2))
   (println (:access_token
             (cond
               (= who "scheduler") (client-credentials "pulse-scheduler" (env/get-var "SCHEDULER_CLIENT_SECRET"))
               (= who "api") (client-credentials "pulse-api" (env/get-var "API_CLIENT_SECRET"))
               (= client "pulse-docs") (login {:username who :password (env/get-var "DEMO_USER_PASSWORD")
                                               :client-id "pulse-docs" :redirect-uri docs-redirect})
               :else (login {:username who :password (env/get-var "DEMO_USER_PASSWORD")}))))))
