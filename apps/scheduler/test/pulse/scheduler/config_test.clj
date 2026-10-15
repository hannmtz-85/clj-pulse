(ns pulse.scheduler.config-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [pulse.platform.config :as platform]
            [pulse.scheduler.config :as config]))

(def ^:private components
  #{:pulse.scheduler/logging :pulse.scheduler/jwks :pulse.scheduler/verifier :pulse.scheduler/token
    :pulse.scheduler/jobs :pulse.scheduler/executor :pulse.scheduler/handler :pulse.scheduler/server})

(def ^:private dev-secret "a-secret-for-the-dev-profile")

(defn- load-config
  "The configuration of `profile`, as it is read on a machine where SCHEDULER_CLIENT_SECRET is set
  to a known value, which the dev profile needs and the test profile does not."
  ([] (load-config :dev))
  ([profile]
   (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" dev-secret}]
     (config/load-config {:profile profile}))))

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
    (is (= 5000 (get-in dev [:pulse.scheduler/server :port])) "the port in the architecture diagram")
    (is (= 0 (get-in test-cfg [:pulse.scheduler/server :port])) "tests take any free port")
    (is (= :warn (get-in test-cfg [:pulse.scheduler/logging :level])))
    (is (= "127.0.0.1" (get-in dev [:pulse.scheduler/server :host])) "it is not reachable from other machines")))

(deftest the-default-profile-is-dev
  (is (= (load-config :dev) (load-config))))

(deftest the-scheduler-accepts-tokens-for-itself-and-for-no-other-service
  ;; The audience is what keeps a token meant for the api from working here (ADR-0020); the api's
  ;; own configuration says "pulse-api".
  (doseq [profile [:dev :test]]
    (is (= "pulse-scheduler" (get-in (load-config profile) [:pulse.scheduler/verifier :audience]))
        (str profile))))

(deftest the-issuer-is-the-realm-that-tools-realm-generates
  (is (str/ends-with? (get-in (load-config) [:pulse.scheduler/verifier :issuer])
                      (str "/realms/" (:realm (realm))))))

(deftest the-jobs-get-time-to-finish-when-the-scheduler-stops
  (is (= 5000 (get-in (load-config) [:pulse.scheduler/executor :grace-ms]))))

;; ---------------------------------------------------------------------------
;; The scheduler's own token (ADR-0021)

(deftest the-token-is-asked-of-the-realm-as-the-confidential-client-of-the-scheduler
  (let [token (get-in (load-config) [:pulse.scheduler/token])
        issuer (get-in (load-config) [:pulse.scheduler/verifier :issuer])]
    (is (= "pulse-scheduler" (:client-id token)))
    (is (= (str issuer "/protocol/openid-connect/token") (:token-uri token))
        "the token endpoint of the realm that issues the tokens the verifier accepts")
    (testing "and the realm has that client, confidential, with a service account"
      (let [client (first (filter #(= "pulse-scheduler" (:clientId %)) (:clients (realm))))]
        (is (some? client))
        (is (false? (:publicClient client)))
        (is (true? (:serviceAccountsEnabled client)))))))

(deftest the-secret-of-the-scheduler-comes-from-the-environment-and-is-never-printed
  (let [cfg (load-config)
        secret (get-in cfg [:pulse.scheduler/token :client-secret])]
    (is (platform/secret? secret))
    (is (= dev-secret (platform/reveal secret)))
    (testing "not in the configuration when it is printed, nor in what Integrant says of a failure"
      (is (not (str/includes? (pr-str cfg) dev-secret)))
      (is (str/includes? (pr-str cfg) "#secret[REDACTED]"))
      (is (not (str/includes? (pr-str (ex-info "build failed" {:value (:pulse.scheduler/token cfg)})) dev-secret))))
    (testing "the test profile has one of its own, so the tests need nothing from the environment"
      (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" nil}]
        (is (= "test-secret" (platform/reveal (get-in (config/load-config {:profile :test})
                                                      [:pulse.scheduler/token :client-secret]))))))))

(deftest without-the-secret-the-scheduler-says-which-variable-to-set
  (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" nil}]
    (let [e (try (config/load-config {:profile :dev})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (platform/invalid-config? e))
      (is (str/includes? (ex-message e) ":pulse.scheduler/token :client-secret"))
      (is (str/includes? (ex-message e) "SCHEDULER_CLIENT_SECRET")))))

(deftest the-token-is-renewed-before-it-expires-and-keycloak-is-not-hammered
  (let [token (get-in (load-config) [:pulse.scheduler/token])]
    (is (= 60000 (:renew-before-ms token)))
    (is (= 5000 (:retry-delay-ms token)))
    (is (< (:timeout-ms token) 10000) "a request to Keycloak is given up on well before a job is")))

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
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/server :port] 70000))
                   ":pulse.scheduler/server :port")))
  (testing "a misspelt key is an error, not a setting that does nothing"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/executor :grace-m] 30))
                   ":pulse.scheduler/executor :grace-m" "disallowed")))
  (testing "a grace period that is absurd"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/executor :grace-ms] -1))
                   ":pulse.scheduler/executor :grace-ms"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/executor :grace-ms] 3600000))
                   ":pulse.scheduler/executor :grace-ms")))
  (testing "no audience"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/verifier :audience] ""))
                   ":pulse.scheduler/verifier :audience")))
  (testing "an issuer that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/verifier :issuer] "pulse"))
                   ":pulse.scheduler/verifier :issuer" "URL")))
  (testing "an unknown log level"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/logging :level] :loud))
                   ":pulse.scheduler/logging :level")))
  (testing "a setting for a job catalogue that takes none"
    (is (mentions? (problems-with #(assoc % :pulse.scheduler/jobs {:every-ms 5}))
                   ":pulse.scheduler/jobs :every-ms" "disallowed")))
  (testing "a component that is not the scheduler's"
    (is (mentions? (problems-with #(assoc % :pulse.api/fhir {:kind :memory}))
                   ":pulse.api/fhir" "disallowed")))
  (testing "a missing component"
    (is (mentions? (problems-with #(dissoc % :pulse.scheduler/executor)) ":pulse.scheduler/executor"))
    (is (mentions? (problems-with #(dissoc % :pulse.scheduler/token)) ":pulse.scheduler/token")))
  (testing "a token endpoint that is not a URL"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/token :token-uri] "keycloak"))
                   ":pulse.scheduler/token :token-uri" "URL")))
  (testing "a secret written as a plain string, which the file must not do"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/token :client-secret] "plain-text-secret"))
                   ":pulse.scheduler/token :client-secret" "secret")))
  (testing "an empty secret"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/token :client-secret] (platform/secret "")))
                   ":pulse.scheduler/token :client-secret")))
  (testing "a misspelt key of the token"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/token :retry-delay] 5000))
                   ":pulse.scheduler/token :retry-delay" "disallowed")))
  (testing "times that are absurd"
    (doseq [k [:renew-before-ms :retry-delay-ms :timeout-ms :connect-timeout-ms]
            bad [-1 100000000]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/token k] bad))
                     (str ":pulse.scheduler/token " k))
          (str k " " bad))))
  (testing "what was written is not repeated: a setting may be a secret"
    (is (not (mentions? (problems-with #(assoc-in % [:pulse.scheduler/token :client-secret] "hunter2-s3cret"))
                        "hunter2-s3cret")))
    (is (not (mentions? (problems-with #(assoc-in % [:pulse.scheduler/verifier :issuer] "hunter2-s3cret"))
                        "hunter2-s3cret")))))

(deftest a-valid-configuration-has-no-problems
  (is (nil? (problems-with identity))))
