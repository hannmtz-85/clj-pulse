(ns pulse.api.config-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [pulse.api.config :as config]
            [pulse.api.identity :as identity]
            [pulse.platform.config :as platform]))

(def ^:private components
  #{:pulse.api/logging :pulse.api/jwks :pulse.api/verifier :pulse.api/token :pulse.api/scheduler
    :pulse.api/fhir :pulse.api/handler :pulse.api/server})

(def ^:private dev-secret "a-secret-for-the-dev-profile")

(defn- load-config
  "The configuration of `profile` (`:dev` by default), as it is read on a machine where API_CLIENT_SECRET
  is set to a known value, which the `dev` and `lite` profiles need and the `test` profile does not."
  ([] (load-config :dev))
  ([profile]
   (binding [platform/*environment* {"API_CLIENT_SECRET" dev-secret}]
     (config/load-config {:profile profile}))))

(defn- refs [cfg]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (ig/ref? x) (swap! found conj (:key x))) x) cfg)
    @found))

(defn- realm []
  (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
    (json/read rdr :key-fn keyword)))

(deftest every-profile-loads-and-is-valid
  (doseq [profile [:dev :lite :test]]
    (testing profile
      (let [cfg (load-config profile)]
        (is (= components (set (keys cfg))))
        (testing "every #ig/ref points at a component that exists"
          (is (every? components (refs cfg)))
          (is (seq (refs cfg))))))))

(deftest profiles-differ-where-they-should
  (let [dev (load-config :dev)
        lite (load-config :lite)
        test-cfg (load-config :test)]
    (is (= :http (get-in dev [:pulse.api/fhir :kind])) "dev talks to HAPI")
    (is (string? (get-in dev [:pulse.api/fhir :base-url])))
    (testing "and gives up on it when it is slow"
      (is (= 10000 (get-in dev [:pulse.api/fhir :timeout-ms])))
      (is (= 3000 (get-in dev [:pulse.api/fhir :connect-timeout-ms]))))
    (is (= {:kind :memory} (:pulse.api/fhir lite)) "lite needs no HAPI")
    (is (= {:kind :memory} (:pulse.api/fhir test-cfg)))
    (is (= 0 (get-in test-cfg [:pulse.api/server :port])) "tests take any free port")
    (is (= :warn (get-in test-cfg [:pulse.api/logging :level])))
    (is (= 4000 (get-in lite [:pulse.api/server :port])) "the port in the architecture diagram")))

(deftest the-default-profile-is-dev
  (is (= (load-config :dev) (load-config))))

(deftest the-summary-has-a-deadline-tighter-than-the-fhir-timeout
  (doseq [profile [:dev :lite :test]]
    (let [cfg (load-config profile)]
      (is (= 4000 (get-in cfg [:pulse.api/handler :summary-timeout-ms])) (str profile))
      (is (< (get-in cfg [:pulse.api/handler :summary-timeout-ms])
             (get-in (load-config :dev) [:pulse.api/fhir :timeout-ms]))
          "the deadline of the fan-out governs the summary; the client timeout protects the other endpoints"))))

(deftest the-api-checks-the-audience-of-the-realm
  (is (= "pulse-api" (get-in (load-config) [:pulse.api/verifier :audience]))))

(deftest the-sign-in-of-the-docs-goes-where-the-tokens-come-from
  ;; Keycloak puts its own address in `iss`, and that is the address a browser reaches it at.
  (doseq [profile [:dev :lite :test]]
    (let [cfg (load-config profile)]
      (is (= (get-in cfg [:pulse.api/verifier :issuer]) (get-in cfg [:pulse.api/handler :issuer]))
          (str profile))))
  (testing "which is the realm that tools/realm.clj generates"
    (is (str/ends-with? (get-in (load-config) [:pulse.api/handler :issuer])
                        (str "/realms/" (:realm (realm)))))))

(deftest only-the-clinical-workspace-may-call-the-api-from-a-browser-by-default
  (doseq [profile [:dev :lite :test]]
    (is (= #{"http://localhost:3000"}
           (get-in (load-config profile) [:pulse.api/handler :cors :allowed-origins]))
        (str profile))))

;; ---------------------------------------------------------------------------
;; The api as a client of the realm, to ask the scheduler for its jobs (ADR-0025)

(deftest the-token-is-asked-of-the-realm-as-the-confidential-client-of-the-api
  (let [cfg (load-config)
        token (:pulse.api/token cfg)
        issuer (get-in cfg [:pulse.api/verifier :issuer])]
    (is (= "pulse-api" (:client-id token)))
    (is (= (str issuer "/protocol/openid-connect/token") (:token-uri token))
        "the token endpoint of the realm that issues the tokens the verifier accepts")
    (testing "and the realm has that client, confidential, with a service account and no one to sign in"
      (let [client (first (filter #(= "pulse-api" (:clientId %)) (:clients (realm))))]
        (is (some? client))
        (is (false? (:publicClient client)))
        (is (true? (:serviceAccountsEnabled client)))
        (is (false? (:standardFlowEnabled client)))
        (is (= "${API_CLIENT_SECRET}" (:secret client)) "a placeholder that Keycloak resolves, never a secret")))
    (testing "whose service account has the one role of its own, which the api knows as a role of the realm"
      (let [account (first (filter #(= "service-account-pulse-api" (:username %)) (:users (realm))))]
        (is (= #{"api-service"} (set (filter (set (map name identity/roles)) (:realmRoles account)))))))))

(deftest the-secret-of-the-api-comes-from-the-environment-and-is-never-printed
  (let [cfg (load-config)
        secret (get-in cfg [:pulse.api/token :client-secret])]
    (is (platform/secret? secret))
    (is (= dev-secret (platform/reveal secret)))
    (testing "not in the configuration when it is printed, nor in what Integrant says of a failure"
      (is (not (str/includes? (pr-str cfg) dev-secret)))
      (is (str/includes? (pr-str cfg) "#secret[REDACTED]"))
      (is (not (str/includes? (pr-str (ex-info "build failed" {:value (:pulse.api/token cfg)})) dev-secret))))
    (testing "the lite profile, which still needs Keycloak, needs it too"
      (is (= dev-secret (platform/reveal (get-in (load-config :lite) [:pulse.api/token :client-secret])))))
    (testing "the test profile has one of its own, so the tests need nothing from the environment"
      (binding [platform/*environment* {"API_CLIENT_SECRET" nil}]
        (is (= "test-secret" (platform/reveal (get-in (config/load-config {:profile :test})
                                                      [:pulse.api/token :client-secret]))))))))

(deftest without-the-secret-the-api-says-which-variable-to-set-and-how
  (binding [platform/*environment* {"API_CLIENT_SECRET" nil}]
    (doseq [profile [:dev :lite]]
      (let [e (try (config/load-config {:profile profile})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (platform/invalid-config? e) (str profile))
        (is (str/includes? (ex-message e) ":pulse.api/token :client-secret") (str profile))
        (is (str/includes? (ex-message e) "API_CLIENT_SECRET") (str profile))
        (is (str/includes? (ex-message e) "bb env:init") "and where to get it")))))

(deftest the-token-is-renewed-before-it-expires-and-keycloak-is-not-hammered
  (let [token (:pulse.api/token (load-config))]
    (is (= 60000 (:renew-before-ms token)))
    (is (= 5000 (:retry-delay-ms token)))
    (is (< (:timeout-ms token) 10000))
    (is (< (:connect-timeout-ms token) (:timeout-ms token)))))

(deftest the-scheduler-is-asked-at-its-port-with-the-apis-own-token
  (let [cfg (load-config)
        scheduler (:pulse.api/scheduler cfg)]
    (is (= "http://localhost:5000" (:base-url scheduler)) "the port of the scheduler in the architecture diagram")
    (is (= (ig/ref :pulse.api/token) (:token scheduler)) "its own token, the one that carries the role api-service")
    (is (< (:connect-timeout-ms scheduler) (:timeout-ms scheduler)))
    (is (<= (:timeout-ms scheduler) 10000) "an administrator is waiting")
    (testing "and the test profile points at the same place until a test says otherwise"
      (is (= (:base-url scheduler) (get-in (load-config :test) [:pulse.api/scheduler :base-url]))))
    (testing "where a variable says it is, as it follows the port of the scheduler"
      (binding [platform/*environment* {"API_CLIENT_SECRET" dev-secret "SCHEDULER_BASE_URL" "http://scheduler.internal:5100"}]
        (is (= "http://scheduler.internal:5100" (get-in (config/load-config {:profile :dev}) [:pulse.api/scheduler :base-url])))))))

(deftest the-handler-is-given-the-scheduler
  (is (= (ig/ref :pulse.api/scheduler) (get-in (load-config) [:pulse.api/handler :scheduler]))))

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
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/server :port] 70000))
                   ":pulse.api/server :port")))
  (testing "a misspelt key is an error, not a setting that does nothing"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/verifier :leway-s] 30))
                   ":pulse.api/verifier :leway-s")))
  (testing "a leeway longer than 5 minutes"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/verifier :leeway-s] 3600))
                   ":pulse.api/verifier :leeway-s")))
  (testing "an issuer that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/verifier :issuer] "pulse"))
                   ":pulse.api/verifier :issuer" "URL")))
  (testing "no accepted algorithms"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/verifier :algorithms] []))
                   ":pulse.api/verifier :algorithms")))
  (testing "an unknown log level"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/logging :level] :loud))
                   ":pulse.api/logging :level")))
  (testing "an origin for CORS that is a wildcard, has a path or ends in a slash"
    (doseq [bad ["*" "http://localhost:3000/" "http://localhost:3000/app" "localhost:3000" "http://*.example.com"]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.api/handler :cors :allowed-origins] #{bad}))
                     ":pulse.api/handler :cors :allowed-origins" "exact origin")
          bad))
    (is (not (mentions? (problems-with #(assoc-in % [:pulse.api/handler :cors :allowed-origins] #{"hunter2-s3cret"}))
                        "hunter2-s3cret"))
        "what was written is not repeated: a setting may be a secret"))
  (testing "origins that are not a set"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/handler :cors :allowed-origins] ["http://localhost:3000"]))
                   ":pulse.api/handler :cors :allowed-origins")))
  (testing "a misspelt CORS key"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/handler :cors] {:allowed-origin #{}}))
                   ":pulse.api/handler :cors :allowed-origin" "disallowed")))
  (testing "no CORS setting at all, or no issuer for the docs"
    (is (mentions? (problems-with #(update % :pulse.api/handler dissoc :cors)) ":pulse.api/handler :cors"))
    (is (mentions? (problems-with #(update % :pulse.api/handler dissoc :issuer)) ":pulse.api/handler :issuer")))
  (testing "an issuer for the docs that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/handler :issuer] "pulse"))
                   ":pulse.api/handler :issuer" "URL")))
  (testing "a FHIR server of an unknown kind"
    (is (seq (problems-with #(assoc % :pulse.api/fhir {:kind :carrier-pigeon})))))
  (testing "an HTTP FHIR server needs its URL"
    (is (mentions? (problems-with #(assoc % :pulse.api/fhir {:kind :http}))
                   ":pulse.api/fhir")))
  (testing "a summary deadline that is absurd"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/handler :summary-timeout-ms] 5))
                   ":pulse.api/handler :summary-timeout-ms"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/handler :summary-timeout-ms] 600000))
                   ":pulse.api/handler :summary-timeout-ms")))
  (testing "a FHIR timeout that is absurd"
    (is (mentions? (problems-with #(assoc % :pulse.api/fhir {:kind :http :base-url "http://localhost:8090/fhir" :timeout-ms 0}))
                   ":pulse.api/fhir")))
  (testing "a scheduler at an address that is not a URL, or a handler that is not given one"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/scheduler :base-url] "localhost:5000"))
                   ":pulse.api/scheduler :base-url" "URL"))
    (is (mentions? (problems-with #(update % :pulse.api/handler dissoc :scheduler))
                   ":pulse.api/handler :scheduler")))
  (testing "times that are absurd, for the scheduler"
    (doseq [k [:timeout-ms :connect-timeout-ms]
            bad [0 100000000]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.api/scheduler k] bad))
                     (str ":pulse.api/scheduler " k))
          (str k " " bad))))
  (testing "a misspelt key of the scheduler"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/scheduler :timeout] 5000))
                   ":pulse.api/scheduler :timeout" "disallowed")))
  (testing "a token endpoint that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/token :token-uri] "keycloak"))
                   ":pulse.api/token :token-uri" "URL")))
  (testing "a secret written as a plain string, which the file must not do, or an empty one"
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/token :client-secret] "plain-text-secret"))
                   ":pulse.api/token :client-secret" "secret"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.api/token :client-secret] (platform/secret "")))
                   ":pulse.api/token :client-secret")))
  (testing "what was written is not repeated: a setting may be a secret"
    (is (not (mentions? (problems-with #(assoc-in % [:pulse.api/token :client-secret] "hunter2-s3cret"))
                        "hunter2-s3cret"))))
  (testing "times that are absurd, for the token"
    (doseq [k [:renew-before-ms :retry-delay-ms :timeout-ms :connect-timeout-ms]
            bad [-1 100000000]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.api/token k] bad))
                     (str ":pulse.api/token " k))
          (str k " " bad))))
  (testing "a missing component"
    (is (mentions? (problems-with #(dissoc % :pulse.api/server)) ":pulse.api/server"))
    (is (mentions? (problems-with #(dissoc % :pulse.api/token)) ":pulse.api/token"))
    (is (mentions? (problems-with #(dissoc % :pulse.api/scheduler)) ":pulse.api/scheduler"))))

(deftest a-valid-configuration-has-no-problems
  (is (nil? (problems-with identity))))
