(ns pulse.spa.config-test
  "The configuration of the server of the workspace's release (ADR-0029, point 9): every profile loads, the defaults are the
  addresses of the local stack, and they are the ones that the build of the page and the realm have."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me]
            [pulse.platform.config :as platform]
            [pulse.platform.http.headers :as headers]
            [pulse.spa.config :as config]))

(def ^:private components #{:pulse.spa/logging :pulse.spa/handler :pulse.spa/server})

(defn- load-config
  ([] (load-config :dev))
  ([profile] (config/load-config {:profile profile}))
  ([profile environment]
   (binding [platform/*environment* environment]
     (config/load-config {:profile profile}))))

(defn- refs [cfg]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (ig/ref? x) (swap! found conj (:key x))) x) cfg)
    @found))

(defn- spa-client []
  (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
    (first (filter #(= "pulse-spa" (:clientId %)) (:clients (json/read rdr :key-fn keyword))))))

(defn- default-of
  "What the browser's build has for a setting, read from the source that defines it (`goog-define`)."
  [setting]
  (second (re-find (re-pattern (str "goog-define " (name setting) " \"([^\"]*)\""))
                   (slurp "apps/spa/src/pulse/spa/settings.cljs" :encoding "UTF-8"))))

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
    (is (= 3000 (get-in dev [:pulse.spa/server :port])) "the port of the workspace in the realm: the redirect address of pulse-spa")
    (is (= 0 (get-in test-cfg [:pulse.spa/server :port])) "tests take any free port")
    (is (= :warn (get-in test-cfg [:pulse.spa/logging :level])))
    (is (= "127.0.0.1" (get-in dev [:pulse.spa/server :host])) "it is not reachable from other machines")))

(deftest the-default-profile-is-dev
  (is (= (load-config :dev) (load-config))))

(deftest the-defaults-serve-the-release-of-the-build
  (is (= "apps/spa/target/release" (get-in (load-config) [:pulse.spa/handler :root])))
  (is (= "http://localhost:3000" (get-in (load-config) [:pulse.spa/handler :base-url]))))

(deftest the-origins-of-the-policy-are-those-the-page-is-built-to-call
  (let [{:keys [api-base-url issuer]} (:pulse.spa/handler (load-config))]
    (is (= (default-of :api-base-url) api-base-url) "the page calls the api where the policy lets it")
    (is (= (str (default-of :keycloak-url) "/realms/" (default-of :keycloak-realm)) issuer)
        "and the identity provider where the policy lets it")))

(deftest the-address-is-the-one-the-realm-gives-the-client
  (let [client (spa-client)
        base-url (get-in (load-config) [:pulse.spa/handler :base-url])]
    (is (some? client))
    (is (true? (:publicClient client)) "the workspace has no secret")
    (is (= [(str base-url "/*")] (:redirectUris client)))
    (is (= [base-url] (:webOrigins client)))
    (is (= "pulse-spa" (default-of :keycloak-client-id)))
    (is (= (headers/origin-of (get-in (load-config) [:pulse.spa/handler :issuer])) (default-of :keycloak-url)))))

(deftest the-environment-changes-where-it-listens-and-what-it-names
  (let [cfg (load-config :dev {"SPA_PORT" "3100" "SPA_HOST" "0.0.0.0" "SPA_ROOT" "/srv/spa"
                               "SPA_BASE_URL" "https://workspace.pulse.example"
                               "API_BASE_URL" "https://api.pulse.example" "KEYCLOAK_ISSUER" "https://id.pulse.example/realms/pulse"})]
    (is (= 3100 (get-in cfg [:pulse.spa/server :port])))
    (is (= "0.0.0.0" (get-in cfg [:pulse.spa/server :host])))
    (is (= "/srv/spa" (get-in cfg [:pulse.spa/handler :root])))
    (is (= "https://workspace.pulse.example" (get-in cfg [:pulse.spa/handler :base-url])))
    (is (= "https://api.pulse.example" (get-in cfg [:pulse.spa/handler :api-base-url])))
    (is (= "https://id.pulse.example/realms/pulse" (get-in cfg [:pulse.spa/handler :issuer])))))

(deftest a-wrong-configuration-is-refused-in-plain-words
  (doseq [[label environment] {"a base address with a path" {"SPA_BASE_URL" "http://localhost:3000/workspace"}
                               "a base address that is not an address" {"SPA_BASE_URL" "workspace"}
                               "an api that is not an address" {"API_BASE_URL" "not-a-url"}
                               "an identity provider that is not an address" {"KEYCLOAK_ISSUER" "localhost"}
                               "a port that is not one" {"SPA_PORT" "70000"}
                               "an empty root" {"SPA_ROOT" ""}}]
    (testing label
      (let [e (try (load-config :dev environment) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (platform/invalid-config? e))
        (is (not (str/includes? (str (ex-message e)) "a-secret")))))))

(deftest every-map-of-the-configuration-is-closed-so-that-a-misspelt-key-is-an-error
  (let [cfg (load-config)]
    (is (m/validate config/schema cfg))
    (doseq [path [[] [:pulse.spa/logging] [:pulse.spa/handler] [:pulse.spa/server]]]
      (is (not (m/validate config/schema (assoc-in cfg (conj path :nonsense) 1))) (pr-str path)))))

(deftest an-origin-says-what-it-should-be
  (is (m/validate config/origin "http://localhost:3000"))
  (is (m/validate config/origin "https://workspace.pulse.example"))
  (is (= ["should be an http(s) origin with no path, such as http://localhost:3000"] (me/humanize (m/explain config/origin "http://localhost:3000/x"))))
  (is (not (m/validate config/origin "http://localhost:3000/")) "not even a slash"))
