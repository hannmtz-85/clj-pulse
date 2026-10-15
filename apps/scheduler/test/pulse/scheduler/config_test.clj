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
  #{:pulse.scheduler/logging :pulse.scheduler/jwks :pulse.scheduler/verifier :pulse.scheduler/jobs
    :pulse.scheduler/executor :pulse.scheduler/handler :pulse.scheduler/server})

(defn- refs [cfg]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (ig/ref? x) (swap! found conj (:key x))) x) cfg)
    @found))

(deftest every-profile-loads-and-is-valid
  (doseq [profile [:dev :test]]
    (testing profile
      (let [cfg (config/load-config {:profile profile})]
        (is (= components (set (keys cfg))))
        (testing "every #ig/ref points at a component that exists"
          (is (every? components (refs cfg)))
          (is (seq (refs cfg))))))))

(deftest profiles-differ-where-they-should
  (let [dev (config/load-config {:profile :dev})
        test-cfg (config/load-config {:profile :test})]
    (is (= 5000 (get-in dev [:pulse.scheduler/server :port])) "the port in the architecture diagram")
    (is (= 0 (get-in test-cfg [:pulse.scheduler/server :port])) "tests take any free port")
    (is (= :warn (get-in test-cfg [:pulse.scheduler/logging :level])))
    (is (= "127.0.0.1" (get-in dev [:pulse.scheduler/server :host])) "it is not reachable from other machines")))

(deftest the-default-profile-is-dev
  (is (= (config/load-config {:profile :dev}) (config/load-config))))

(deftest the-scheduler-accepts-tokens-for-itself-and-for-no-other-service
  ;; The audience is what keeps a token meant for the api from working here (ADR-0020); the api's
  ;; own configuration says "pulse-api".
  (doseq [profile [:dev :test]]
    (is (= "pulse-scheduler" (get-in (config/load-config {:profile profile}) [:pulse.scheduler/verifier :audience]))
        (str profile))))

(deftest the-issuer-is-the-realm-that-tools-realm-generates
  (let [realm (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
                (json/read rdr :key-fn keyword))]
    (is (str/ends-with? (get-in (config/load-config) [:pulse.scheduler/verifier :issuer])
                        (str "/realms/" (:realm realm))))))

(deftest the-jobs-get-time-to-finish-when-the-scheduler-stops
  (is (= 5000 (get-in (config/load-config) [:pulse.scheduler/executor :grace-ms]))))

(defn- problems-with
  "The lines that validation reports for the test profile changed by `f`."
  [f]
  (try (platform/validate! config/schema (f (config/load-config {:profile :test})))
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
    (is (mentions? (problems-with #(dissoc % :pulse.scheduler/executor)) ":pulse.scheduler/executor")))
  (testing "what was written is not repeated: a setting may be a secret"
    (is (not (mentions? (problems-with #(assoc-in % [:pulse.scheduler/verifier :issuer] "hunter2-s3cret"))
                        "hunter2-s3cret")))))

(deftest a-valid-configuration-has-no-problems
  (is (nil? (problems-with identity))))
