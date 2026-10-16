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
    :pulse.scheduler/api :pulse.scheduler/fhir
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
;; What the rules engine reads from and writes to (ADR-0022)

(deftest the-engine-reads-through-the-api-with-the-schedulers-token
  (let [cfg (load-config)
        api (:pulse.scheduler/api cfg)]
    (is (= "http://localhost:4000" (:base-url api)) "the port of the api")
    (is (= (ig/ref :pulse.scheduler/token) (:token api)) "its own token, the one that carries the role rules-engine")
    (is (< (:connect-timeout-ms api) (:timeout-ms api)))
    (testing "and the test profile points at the same place until a test says otherwise"
      (is (= (:base-url api) (get-in (load-config :test) [:pulse.scheduler/api :base-url]))))))

(deftest the-engine-writes-alerts-to-the-fhir-server-of-the-api
  (let [dev (load-config)]
    (is (= {:kind :http :base-url "http://localhost:8090/fhir" :timeout-ms 10000 :connect-timeout-ms 3000}
           (:pulse.scheduler/fhir dev)))
    (testing "the tests have a server in memory"
      (is (= {:kind :memory} (:pulse.scheduler/fhir (load-config :test)))))))

(deftest the-rules-are-evaluated-every-half-minute-locally-and-never-by-the-tests
  (is (= {:enabled? true :schedule {:type :interval :every-ms 30000}}
         (get-in (load-config) [:pulse.scheduler/jobs :evaluate-rules]))
      "an alert is there within a minute of a reading")
  (testing "a deployment says how often, with a variable"
    (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" dev-secret
                                      "SCHEDULER_EVALUATE_EVERY_MS" "300000"}]
      (is (= {:type :interval :every-ms 300000}
             (get-in (config/load-config {:profile :dev}) [:pulse.scheduler/jobs :evaluate-rules :schedule])))))
  (testing "which the tests run by hand, and do not want to run by itself"
    (is (= false (get-in (load-config :test) [:pulse.scheduler/jobs :evaluate-rules :enabled?])))
    (is (= (get-in (load-config) [:pulse.scheduler/jobs :evaluate-rules :schedule])
           (get-in (load-config :test) [:pulse.scheduler/jobs :evaluate-rules :schedule])))))

(deftest a-variable-that-is-not-a-sound-number-of-milliseconds-stops-the-start-up
  (doseq [[variable job] [["SCHEDULER_EVALUATE_EVERY_MS" :evaluate-rules]
                          ["SCHEDULER_REMINDERS_EVERY_MS" :send-reminders]]
          bad ["often" "0" "-5" "99999999999"]]
    (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" dev-secret
                                      variable bad}]
      (let [e (try (config/load-config {:profile :dev}) nil (catch Exception e e))]
        (is (some? e) (str variable " " bad " was accepted"))
        (is (not (str/includes? (str (ex-message e)) dev-secret)) bad)
        (when (not= "often" bad)
          (is (platform/invalid-config? e) (str bad " is a number, and the schedule says it is not sound"))
          (is (str/includes? (ex-message e) (str ":pulse.scheduler/jobs " job " :schedule :every-ms")) (str variable " " bad)))))))

(deftest the-devices-are-simulated-every-quarter-of-a-minute-except-in-the-tests
  (is (= {:enabled? true :schedule {:type :interval :every-ms 15000} :batch-size 20}
         (get-in (load-config) [:pulse.scheduler/jobs :simulate-devices])))
  (testing "which a variable switches off, or on"
    (doseq [[value enabled?] [["false" false] ["true" true] ["FALSE" false] ["TRUE" true]]]
      (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" dev-secret
                                        "SCHEDULER_SIMULATOR" value}]
        (is (= enabled? (get-in (config/load-config {:profile :dev}) [:pulse.scheduler/jobs :simulate-devices :enabled?]))
            value))))
  (testing "and the tests do not want it, whatever the variable says"
    (is (false? (get-in (load-config :test) [:pulse.scheduler/jobs :simulate-devices :enabled?])))
    (binding [platform/*environment* {"SCHEDULER_SIMULATOR" "true"}]
      (is (false? (get-in (config/load-config {:profile :test}) [:pulse.scheduler/jobs :simulate-devices :enabled?]))))))

(deftest the-reminders-are-sent-every-minute-except-in-the-tests
  (is (= {:enabled? true :schedule {:type :interval :every-ms 60000} :window-hours 24 :zone "Europe/Madrid"}
         (get-in (load-config) [:pulse.scheduler/jobs :send-reminders]))
      "a reminder goes out within a minute of the appointment coming within a day of its time")
  (testing "which a variable switches off, or on"
    (doseq [[value enabled?] [["false" false] ["true" true] ["FALSE" false] ["TRUE" true]]]
      (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" dev-secret
                                        "SCHEDULER_REMINDERS" value}]
        (is (= enabled? (get-in (config/load-config {:profile :dev}) [:pulse.scheduler/jobs :send-reminders :enabled?]))
            value))))
  (testing "and says how often, with another"
    (binding [platform/*environment* {"SCHEDULER_CLIENT_SECRET" dev-secret
                                      "SCHEDULER_REMINDERS_EVERY_MS" "300000"}]
      (is (= {:type :interval :every-ms 300000}
             (get-in (config/load-config {:profile :dev}) [:pulse.scheduler/jobs :send-reminders :schedule])))))
  (testing "and the tests do not want it, whatever the variable says"
    (is (false? (get-in (load-config :test) [:pulse.scheduler/jobs :send-reminders :enabled?])))
    (binding [platform/*environment* {"SCHEDULER_REMINDERS" "true"}]
      (is (false? (get-in (config/load-config {:profile :test}) [:pulse.scheduler/jobs :send-reminders :enabled?]))))
    (testing "but they run it by hand with the same settings otherwise"
      (is (= (dissoc (get-in (load-config) [:pulse.scheduler/jobs :send-reminders]) :enabled?)
             (dissoc (get-in (load-config :test) [:pulse.scheduler/jobs :send-reminders]) :enabled?))))))

(deftest the-catalogue-and-the-readiness-probe-use-the-same-clients
  (let [cfg (load-config)]
    (is (= (ig/ref :pulse.scheduler/api) (get-in cfg [:pulse.scheduler/jobs :api])
           (get-in cfg [:pulse.scheduler/handler :api])))
    (is (= (ig/ref :pulse.scheduler/fhir) (get-in cfg [:pulse.scheduler/jobs :fhir])
           (get-in cfg [:pulse.scheduler/handler :fhir])))))

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
  (testing "a setting the job catalogue does not know"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :every-ms] 5))
                   ":pulse.scheduler/jobs :every-ms" "disallowed")))
  (testing "a job that is misspelt, or switched on without saying when"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :evaluate-rule] {:enabled? true}))
                   ":pulse.scheduler/jobs :evaluate-rule" "disallowed"))
    (is (mentions? (problems-with #(update-in % [:pulse.scheduler/jobs :evaluate-rules] dissoc :schedule))
                   ":pulse.scheduler/jobs :evaluate-rules :schedule"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :evaluate-rules :enabled?] "yes"))
                   ":pulse.scheduler/jobs :evaluate-rules :enabled?")))
  (testing "a schedule that is not one"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :evaluate-rules :schedule] {:type :interval :every-ms 0}))
                   ":pulse.scheduler/jobs :evaluate-rules :schedule"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :evaluate-rules :schedule] {:type :hourly}))
                   ":pulse.scheduler/jobs :evaluate-rules :schedule")))
  (testing "the simulator with a batch that is nothing or too much, or no schedule"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :simulate-devices :batch-size] 0))
                   ":pulse.scheduler/jobs :simulate-devices :batch-size"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :simulate-devices :batch-size] 101))
                   ":pulse.scheduler/jobs :simulate-devices :batch-size"))
    (is (mentions? (problems-with #(update-in % [:pulse.scheduler/jobs :simulate-devices] dissoc :schedule))
                   ":pulse.scheduler/jobs :simulate-devices :schedule"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :simulate-devices :enabled?] "yes"))
                   ":pulse.scheduler/jobs :simulate-devices :enabled?"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :simulate-devices :rate] 3))
                   ":pulse.scheduler/jobs :simulate-devices :rate" "disallowed")))
  (testing "the reminders with a window that is nothing or longer than a week, a zone that does not exist, or no schedule"
    (doseq [window [0 -1 169 1.5 "24"]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :send-reminders :window-hours] window))
                     ":pulse.scheduler/jobs :send-reminders :window-hours")
          (str window)))
    (doseq [window [1 168]]
      (is (nil? (problems-with #(assoc-in % [:pulse.scheduler/jobs :send-reminders :window-hours] window)))
          (str window " is the shortest and the longest that are allowed")))
    (doseq [zone ["Madrid" "Europe/Nowhere" "" nil :utc]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :send-reminders :zone] zone))
                     ":pulse.scheduler/jobs :send-reminders :zone" "time zone")
          (pr-str zone)))
    (is (mentions? (problems-with #(update-in % [:pulse.scheduler/jobs :send-reminders] dissoc :schedule))
                   ":pulse.scheduler/jobs :send-reminders :schedule"))
    (is (mentions? (problems-with #(update-in % [:pulse.scheduler/jobs :send-reminders] dissoc :zone))
                   ":pulse.scheduler/jobs :send-reminders :zone"))
    (is (mentions? (problems-with #(update-in % [:pulse.scheduler/jobs :send-reminders] dissoc :window-hours))
                   ":pulse.scheduler/jobs :send-reminders :window-hours"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :send-reminders :enabled?] "yes"))
                   ":pulse.scheduler/jobs :send-reminders :enabled?"))
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/jobs :send-reminders :channel] :email))
                   ":pulse.scheduler/jobs :send-reminders :channel" "disallowed")))
  (testing "the api and FHIR at addresses that are not URLs"
    (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/api :base-url] "localhost:4000"))
                   ":pulse.scheduler/api :base-url" "URL"))
    (is (mentions? (problems-with #(assoc % :pulse.scheduler/fhir {:kind :http :base-url "hapi" :timeout-ms 1000 :connect-timeout-ms 1000}))
                   ":pulse.scheduler/fhir :base-url" "URL")))
  (testing "a kind of FHIR server that does not exist"
    (is (mentions? (problems-with #(assoc % :pulse.scheduler/fhir {:kind :tape}))
                   ":pulse.scheduler/fhir")))
  (testing "times that are absurd, for the api"
    (doseq [k [:timeout-ms :connect-timeout-ms]
            bad [0 100000000]]
      (is (mentions? (problems-with #(assoc-in % [:pulse.scheduler/api k] bad))
                     (str ":pulse.scheduler/api " k))
          (str k " " bad))))
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
