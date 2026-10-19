(ns pulse.web.config-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [pulse.platform.config :as platform]
            [pulse.web.config :as config]))

(def ^:private components
  #{:pulse.web/logging :pulse.web/jwks :pulse.web/api :pulse.web/handler :pulse.web/server})

(defn- load-config
  ([] (load-config :dev))
  ([profile] (config/load-config {:profile profile})))

(defn- refs [cfg]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (ig/ref? x) (swap! found conj (:key x))) x) cfg)
    @found))

(defn- realm []
  (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
    (json/read rdr :key-fn keyword)))

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
  (let [client (first (filter #(= "pulse-web" (:clientId %)) (:clients (realm))))
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

(deftest the-handler-gets-what-readiness-asks-about
  (let [cfg (load-config)]
    (is (= (ig/ref :pulse.web/jwks) (get-in cfg [:pulse.web/handler :jwks])))
    (is (= (ig/ref :pulse.web/api) (get-in cfg [:pulse.web/handler :api])))
    (is (= (ig/ref :pulse.web/handler) (get-in cfg [:pulse.web/server :handler])))))

(deftest variables-change-where-the-portal-listens-and-whom-it-asks
  (binding [platform/*environment* {"WEB_PORT" "3301" "WEB_HOST" "0.0.0.0" "API_BASE_URL" "http://api.test:4000"
                                    "KEYCLOAK_ISSUER" "https://id.example/realms/pulse"
                                    "KEYCLOAK_JWKS_URI" "http://keycloak:8080/realms/pulse/protocol/openid-connect/certs"}]
    (let [cfg (config/load-config {:profile :dev})]
      (is (= 3301 (get-in cfg [:pulse.web/server :port])))
      (is (= "0.0.0.0" (get-in cfg [:pulse.web/server :host])))
      (is (= "http://api.test:4000" (get-in cfg [:pulse.web/api :base-url])))
      (is (= "https://id.example/realms/pulse" (get-in cfg [:pulse.web/handler :issuer]))
          "as a browser reaches it")
      (is (= "http://keycloak:8080/realms/pulse/protocol/openid-connect/certs" (get-in cfg [:pulse.web/jwks :uri]))
          "and as the portal does, inside a container"))))

(deftest the-port-from-the-environment-is-a-number-or-the-start-up-stops
  (doseq [bad ["often" "-1" "70000"]]
    (binding [platform/*environment* {"WEB_PORT" bad}]
      (is (thrown? Exception (config/load-config {:profile :dev})) bad))))

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
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/api :timeout-ms] 5)) ":pulse.web/api :timeout-ms")))
  (testing "an unknown log level"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/logging :level] :loud)) ":pulse.web/logging :level")))
  (testing "a component that does not exist"
    (is (mentions? (problems-with #(assoc % :pulse.web/extra {})) ":pulse.web/extra" "disallowed")))
  (testing "a keys address that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.web/jwks :uri] "keycloak/certs")) ":pulse.web/jwks :uri" "URL"))))
