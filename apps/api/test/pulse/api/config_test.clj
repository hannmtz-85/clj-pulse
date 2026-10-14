(ns pulse.api.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [pulse.api.config :as config]
            [pulse.platform.config :as platform]))

(def ^:private components
  #{:pulse.api/logging :pulse.api/jwks :pulse.api/verifier :pulse.api/fhir
    :pulse.api/handler :pulse.api/server})

(defn- refs [cfg]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (ig/ref? x) (swap! found conj (:key x))) x) cfg)
    @found))

(deftest every-profile-loads-and-is-valid
  (doseq [profile [:dev :lite :test]]
    (testing profile
      (let [cfg (config/load-config {:profile profile})]
        (is (= components (set (keys cfg))))
        (testing "every #ig/ref points at a component that exists"
          (is (every? components (refs cfg)))
          (is (seq (refs cfg))))))))

(deftest profiles-differ-where-they-should
  (let [dev (config/load-config {:profile :dev})
        lite (config/load-config {:profile :lite})
        test-cfg (config/load-config {:profile :test})]
    (is (= :http (get-in dev [:pulse.api/fhir :kind])) "dev talks to HAPI")
    (is (string? (get-in dev [:pulse.api/fhir :base-url])))
    (is (= {:kind :memory} (:pulse.api/fhir lite)) "lite needs no HAPI")
    (is (= {:kind :memory} (:pulse.api/fhir test-cfg)))
    (is (= 0 (get-in test-cfg [:pulse.api/server :port])) "tests take any free port")
    (is (= :warn (get-in test-cfg [:pulse.api/logging :level])))
    (is (= 4000 (get-in lite [:pulse.api/server :port])) "the port in the architecture diagram")))

(deftest the-default-profile-is-dev
  (is (= (config/load-config {:profile :dev}) (config/load-config))))

(deftest the-api-checks-the-audience-of-the-realm
  (is (= "pulse-api" (get-in (config/load-config) [:pulse.api/verifier :audience]))))

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
  (testing "a FHIR server of an unknown kind"
    (is (seq (problems-with #(assoc % :pulse.api/fhir {:kind :carrier-pigeon})))))
  (testing "an HTTP FHIR server needs its URL"
    (is (mentions? (problems-with #(assoc % :pulse.api/fhir {:kind :http}))
                   ":pulse.api/fhir")))
  (testing "a missing component"
    (is (mentions? (problems-with #(dissoc % :pulse.api/server)) ":pulse.api/server"))))

(deftest a-valid-configuration-has-no-problems
  (is (nil? (problems-with identity))))
