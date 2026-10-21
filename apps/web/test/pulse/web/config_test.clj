(ns pulse.web.config-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [pulse.platform.config :as platform]
            [pulse.web.config :as config]
            [pulse.web.session :as session]))

(def ^:private components
  #{:pulse.web/logging :pulse.web/jwks :pulse.web/access-verifier :pulse.web/id-verifier :pulse.web/api
    :pulse.web/oidc :pulse.web/sessions :pulse.web/handler :pulse.web/server})

(defn- load-config
  "The configuration of `profile`, with the secret of the client set, as a machine that runs the tests may not have it."
  ([] (load-config :dev))
  ([profile]
   (binding [platform/*environment* (merge {"WEB_CLIENT_SECRET" "a-secret"} platform/*environment*)]
     (config/load-config {:profile profile}))))

(defn- refs [cfg]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (ig/ref? x) (swap! found conj (:key x))) x) cfg)
    @found))

(defn- realm []
  (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
    (json/read rdr :key-fn keyword)))

(defn- web-client []
  (first (filter #(= "pulse-web" (:clientId %)) (:clients (realm)))))

(deftest every-profile-loads-and-is-valid
  (doseq [profile [:dev :test]]
    (testing profile
      (let [cfg (load-config profile)]
        (is (= components (set (keys cfg))))
        (testing "every #ig/ref points at a component that exists"
          (is (every? components (refs cfg)))
          (is (seq (refs cfg))))))))

(deftest profiles-differ-where-they-should
  (let [dev (load-config :dev)
        test-cfg (load-config :test)]
    (is (= 3300 (get-in dev [:pulse.web/server :port])) "the port of the portal in the realm: the redirect address of pulse-web")
    (is (= 0 (get-in test-cfg [:pulse.web/server :port])) "tests take any free port")
    (is (= :warn (get-in test-cfg [:pulse.web/logging :level])))
    (is (= "127.0.0.1" (get-in dev [:pulse.web/server :host])) "it is not reachable from other machines")))

(deftest the-default-profile-is-dev
  (is (= (load-config :dev) (load-config))))

(deftest the-portal-asks-the-api-where-the-api-is-by-default-and-has-no-token-for-it
  (let [api (:pulse.web/api (load-config))]
    (is (= "http://localhost:4000" (:base-url api)) "the port of the api")
    (is (not (contains? api :token))
        "the token is the person's, and is given for each call from the keeper of their session (ADR-0028)")
    (is (< (:connect-timeout-ms api) (:timeout-ms api)))))

(deftest the-portal-redirect-address-and-port-are-those-of-the-realm
  ;; The realm registers http://localhost:3300/oauth2/callback for the client pulse-web (tools/realm.clj): the portal
  ;; must listen where Keycloak sends the person back to.
  (let [client (web-client)
        port (get-in (load-config) [:pulse.web/server :port])]
    (is (some? client))
    (is (= [(str "http://localhost:" port "/oauth2/callback")] (:redirectUris client)))
    (is (= [(str "http://localhost:" port)] (:webOrigins client)))))

(deftest the-issuer-is-the-realm-that-tools-realm-generates
  (is (str/ends-with? (get-in (load-config) [:pulse.web/handler :issuer]) (str "/realms/" (:realm (realm))))))

(deftest the-keys-are-those-of-the-same-identity-provider
  (let [cfg (load-config)]
    (is (str/starts-with? (get-in cfg [:pulse.web/jwks :uri]) (get-in cfg [:pulse.web/handler :issuer])))))

(deftest health-probes-are-quiet
  (is (= #{"/healthz" "/readyz"} (get-in (load-config) [:pulse.web/handler :quiet-paths]))))

(deftest the-handler-gets-what-it-is-made-of
  (let [cfg (load-config)]
    (doseq [[k component] {:jwks :pulse.web/jwks :api :pulse.web/api :oidc :pulse.web/oidc :sessions :pulse.web/sessions
                           :access-verifier :pulse.web/access-verifier :id-verifier :pulse.web/id-verifier}]
      (is (= (ig/ref component) (get-in cfg [:pulse.web/handler k])) k))
    (is (= (ig/ref :pulse.web/handler) (get-in cfg [:pulse.web/server :handler])))))

(deftest variables-change-where-the-portal-listens-and-whom-it-asks
  (binding [platform/*environment* {"WEB_PORT" "3301" "WEB_HOST" "0.0.0.0" "API_BASE_URL" "http://api.test:4000"
                                    "WEB_CLIENT_SECRET" "s"
                                    "KEYCLOAK_ISSUER" "https://id.example/realms/pulse"
                                    "KEYCLOAK_JWKS_URI" "http://keycloak:8080/realms/pulse/protocol/openid-connect/certs"}]
    (let [cfg (config/load-config {:profile :dev})]
      (is (= 3301 (get-in cfg [:pulse.web/server :port])))
      (is (= "0.0.0.0" (get-in cfg [:pulse.web/server :host])))
      (is (= "http://api.test:4000" (get-in cfg [:pulse.web/api :base-url])))
      (is (= "https://id.example/realms/pulse" (get-in cfg [:pulse.web/handler :issuer]))
          "as a browser reaches it")
      (is (= "https://id.example/realms/pulse" (get-in cfg [:pulse.web/access-verifier :issuer]))
          "and it is the issuer that the tokens are verified against")
      (is (= "http://keycloak:8080/realms/pulse/protocol/openid-connect/certs" (get-in cfg [:pulse.web/jwks :uri]))
          "and as the portal does, inside a container"))))

(deftest the-port-from-the-environment-is-a-number-or-the-start-up-stops
  (doseq [bad ["often" "-1" "70000"]]
    (binding [platform/*environment* {"WEB_PORT" bad "WEB_CLIENT_SECRET" "s"}]
      (is (thrown? Exception (config/load-config {:profile :dev})) bad))))

;; ---------------------------------------------------------------------------
;; The sign-in (ADR-0028)

(deftest the-two-tokens-of-a-sign-in-are-each-verified-as-what-they-are
  (let [cfg (load-config)
        access (:pulse.web/access-verifier cfg)
        id (:pulse.web/id-verifier cfg)]
    (is (= "pulse-api" (:audience access)) "the access token is for the api")
    (is (= "Bearer" (:token-type access)))
    (is (= "pulse-web" (:audience id)) "and an id token is for the client, the portal")
    (is (= "ID" (:token-type id)))
    (is (= (:issuer access) (:issuer id)) "both are the identity provider's")
    (is (= ["RS256"] (:algorithms access) (:algorithms id)))
    (is (= (ig/ref :pulse.web/jwks) (:jwks access) (:jwks id)))))

(deftest the-portal-is-the-client-pulse-web-of-the-realm
  (let [oidc (:pulse.web/oidc (load-config))
        client (web-client)]
    (is (= (:clientId client) (:client-id oidc)))
    (is (= "http://localhost:3300" (:base-url oidc)) "where the realm sends the browser back to, and the base of the redirect addresses")
    (testing "the addresses of Keycloak, as the realm has them"
      (let [prefix (str "http://localhost:8080/realms/" (:realm (realm)) "/protocol/openid-connect/")]
        (is (= (str prefix "auth") (:authorization-uri oidc)))
        (is (= (str prefix "logout") (:end-session-uri oidc)))
        (is (= (str prefix "token") (:token-uri oidc)))))
    (testing "the redirect and the post-logout addresses are inside what the realm registered"
      (is (= [(str (:base-url oidc) "/oauth2/callback")] (:redirectUris client)))
      (is (= (str (:base-url oidc) "/*") (get-in client [:attributes :post.logout.redirect.uris]))))
    (is (< (:connect-timeout-ms oidc) (:timeout-ms oidc)))))

(deftest the-secret-of-the-client-is-a-secret-that-the-dev-profile-needs
  (testing "read from the variable, and it prints as nothing"
    (let [secret (get-in (load-config :dev) [:pulse.web/oidc :client-secret])]
      (is (platform/secret? secret))
      (is (= "a-secret" (platform/reveal secret)))
      (is (= "#secret[REDACTED]" (pr-str secret)))))
  (testing "without it the portal does not start, and the message says where it comes from and not what it holds"
    (binding [platform/*environment* {"WEB_CLIENT_SECRET" nil}]
      (let [e (try (config/load-config {:profile :dev}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (platform/invalid-config? e))
        (is (some #(and (str/includes? % ":pulse.web/oidc :client-secret") (str/includes? % "WEB_CLIENT_SECRET")) (:problems (ex-data e)))))))
  (testing "a secret that is only white space is none"
    (binding [platform/*environment* {"WEB_CLIENT_SECRET" "   "}]
      (is (thrown? Exception (config/load-config {:profile :dev})))))
  (testing "the tests have one of their own"
    (binding [platform/*environment* {"WEB_CLIENT_SECRET" nil}]
      (is (platform/secret? (get-in (config/load-config {:profile :test}) [:pulse.web/oidc :client-secret]))))))

(deftest variables-change-the-addresses-of-the-sign-in
  (binding [platform/*environment* {"WEB_CLIENT_SECRET" "s"
                                    "WEB_BASE_URL" "https://portal.example"
                                    "KEYCLOAK_AUTHORIZATION_URI" "https://id.example/realms/pulse/protocol/openid-connect/auth"
                                    "KEYCLOAK_END_SESSION_URI" "https://id.example/realms/pulse/protocol/openid-connect/logout"
                                    "KEYCLOAK_TOKEN_URI" "http://keycloak:8080/realms/pulse/protocol/openid-connect/token"}]
    (let [oidc (:pulse.web/oidc (config/load-config {:profile :dev}))]
      (is (= "https://portal.example" (:base-url oidc)))
      (is (= "https://id.example/realms/pulse/protocol/openid-connect/auth" (:authorization-uri oidc)) "as the browser reaches it")
      (is (= "https://id.example/realms/pulse/protocol/openid-connect/logout" (:end-session-uri oidc)))
      (is (= "http://keycloak:8080/realms/pulse/protocol/openid-connect/token" (:token-uri oidc)) "and as the portal does, inside a container"))))

(deftest the-sessions-have-the-limits-of-the-design
  (let [sessions (:pulse.web/sessions (load-config))]
    (is (= (* 30 60 1000) (:idle-ms sessions)) "the idle limit of the realm's SSO session")
    (is (= (* 10 60 60 1000) (:absolute-ms sessions)) "and its maximum")
    (is (= (* 10 60 1000) (:pending-ms sessions)))
    (is (= 1000 (:max-pending sessions)))
    (is (= 10000 (:max-sessions sessions)))
    (testing "which are the defaults of the store, so that a store made with nothing is the same"
      (is (= sessions session/defaults)))))

;; ---------------------------------------------------------------------------
;; Mistakes

(defn- problems-with
  "The lines that validation reports for the test profile changed by `f`."
  [f]
  (try (platform/validate! config/schema (f (load-config :test)))
       nil
       (catch clojure.lang.ExceptionInfo e
         (is (platform/invalid-config? e))
         (:problems (ex-data e)))))

(defn- mentions? [lines & fragments]
  (boolean (some (fn [line] (every? #(str/includes? line %) fragments)) lines)))

(deftest mistakes-are-reported-with-their-place
  (testing "a port out of range"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/server :port] 70000)) ":pulse.web/server :port")))
  (testing "a misspelt key is an error, not a setting that does nothing"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/server :prot] 3300)) ":pulse.web/server :prot" "disallowed"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/handler :isuer] "http://x")) ":pulse.web/handler :isuer" "disallowed")))
  (testing "an issuer that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/handler :issuer] "pulse")) ":pulse.web/handler :issuer" "URL")))
  (testing "an api that is not a URL, and a client with a token it would not use"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/api :base-url] "localhost:4000")) ":pulse.web/api :base-url" "URL"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/api :token] (ig/ref :pulse.web/logging))) ":pulse.web/api :token" "disallowed")))
  (testing "a timeout that is absurd"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/api :timeout-ms] 5)) ":pulse.web/api :timeout-ms"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc :timeout-ms] 5)) ":pulse.web/oidc :timeout-ms")))
  (testing "an unknown log level"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/logging :level] :loud)) ":pulse.web/logging :level")))
  (testing "a component that does not exist"
    (is (mentions? (problems-with #(assoc % :pulse.web/extra {})) ":pulse.web/extra" "disallowed")))
  (testing "a keys address that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/jwks :uri] "keycloak/certs")) ":pulse.web/jwks :uri" "URL"))))

(deftest mistakes-in-the-sign-in-are-reported-with-their-place
  (testing "a base address that is more than an origin: the redirect addresses and the check of the origin are made from it"
    (doseq [bad ["http://localhost:3300/portal" "http://localhost:3300/" "localhost:3300" "http://localhost:3300?x=1"]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc :base-url] bad)) ":pulse.web/oidc :base-url" "origin") bad)))
  (testing "addresses of the identity provider that are not URLs"
    (doseq [k [:authorization-uri :end-session-uri :token-uri]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc k] "keycloak/x")) (str ":pulse.web/oidc " k) "URL") k)))
  (testing "a secret that is a plain string: it would be printed"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc :client-secret] "plain")) ":pulse.web/oidc :client-secret" "secret")))
  (testing "a misspelt key of the client"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc :client-secrt] "x")) ":pulse.web/oidc :client-secrt" "disallowed")))
  (testing "a clock that is not one"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc :clock] "now")) ":pulse.web/oidc :clock" "Clock"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/sessions :clock] 5)) ":pulse.web/sessions :clock" "Clock")))
  (testing "limits of the sessions that make no sense"
    (doseq [[k bad] {:idle-ms 5 :absolute-ms 0 :pending-ms -1 :max-sessions 0 :max-pending 0}]
      (is (mentions? (problems-with #(assoc-in % [:pulse.web/sessions k] bad)) (str ":pulse.web/sessions " k)) k)))
  (testing "a verifier that checks for no token type or algorithm"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/id-verifier :algorithms] [])) ":pulse.web/id-verifier :algorithms")))
  (testing "a client with no name"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/oidc :client-id] "")) ":pulse.web/oidc :client-id")))
  (testing "a handler that is not given everything the sign-in is made of"
    (doseq [k [:oidc :sessions :access-verifier :id-verifier]]
      (is (mentions? (problems-with #(update % :pulse.web/handler dissoc k)) ":pulse.web/handler" (name k) "missing") k))))
