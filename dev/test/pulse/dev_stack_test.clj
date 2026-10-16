(ns pulse.dev-stack-test
  "Integration test: the api and the scheduler as `bb dev lite` starts them, against the real Keycloak of
  the local stack (ADR-0025). The administrator asks the api for the jobs of the scheduler; the api asks
  the scheduler with the token of its own service account, which Keycloak issues to the client
  `pulse-api`, and the scheduler checks it against Keycloak's keys. Nothing here can be seen with the
  fake identity provider of the other tests: that the realm gives that token the audience of the
  scheduler and the role `api-service`, and gives the others neither.

  Needs the stack (`bb up`) with the realm of ADR-0025 loaded (`bb reset`), `bb` on the PATH and the
  secrets in `.env`; it runs with `bb test:it`. It keeps its FHIR server in memory and changes nothing in
  Keycloak or HAPI."
  (:require [babashka.http-client :as http]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [jsonista.core :as json]
            [pulse.dev :as dev]))

(def ^:private quiet
  "A schedule that will not fire while the test runs: once a week, on the grid of the epoch."
  {:type :interval :every-ms 604800000})

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(def ^:private token-of
  "An access token for a demo user, or for `scheduler` or `api`, the service accounts. Signing in is the
  slow part, so each token is fetched once (they live for 5 minutes)."
  (memoize
   (fn [who]
     (let [{:keys [exit out err]} (shell/sh "bb" "token" who)]
       (when-not (zero? exit)
         (throw (ex-info "`bb token` failed: is the stack up (`bb up`) and the realm loaded (`bb reset`)?"
                         {:who who :err err})))
       (str/trim out)))))

(defn- get-json [url who]
  (let [response (http/get url {:throw false
                                :headers (cond-> {"Accept" "application/json"}
                                           who (assoc "Authorization" (str "Bearer " (token-of who))))})]
    (assoc response :json (try (json/read-value (:body response) json/keyword-keys-object-mapper)
                               (catch Exception _ nil)))))

(defn- with-both
  "Calls `(f {:api url :scheduler url})` with the two apps started as one system on free ports: the profile
  `lite`, whose FHIR server is in memory, and the real Keycloak. The secrets come from `.env`."
  [f]
  (let [api-port (free-port)
        scheduler-port (free-port)
        api-url (str "http://127.0.0.1:" api-port)
        scheduler-url (str "http://127.0.0.1:" scheduler-port)
        cfg (-> (dev/config {:profile :lite})
                (assoc-in [:pulse.api/server :port] api-port)
                (assoc-in [:pulse.api/logging :level] :warn)
                (assoc-in [:pulse.api/scheduler :base-url] scheduler-url)
                (assoc-in [:pulse.scheduler/server :port] scheduler-port)
                (assoc-in [:pulse.scheduler/logging :level] :warn)
                (assoc-in [:pulse.scheduler/api :base-url] api-url)
                (assoc-in [:pulse.scheduler/jobs :evaluate-rules :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :simulate-devices :schedule] quiet)
                (assoc-in [:pulse.scheduler/jobs :send-reminders :schedule] quiet))
        system (ig/init cfg)]
    (try (f {:api api-url :scheduler scheduler-url})
         (finally (ig/halt! system)))))

(deftest ^:integration the-administrator-sees-the-jobs-through-the-api-with-the-tokens-of-the-real-realm
  (with-both
    (fn [{:keys [api]}]
      (let [r (get-json (str api "/api/admin/jobs") "admin@pulse.test")]
        (testing "the administrator gets the three jobs of the scheduler, asked for by the api"
          (is (= 200 (:status r)))
          (is (= 3 (get-in r [:json :total])))
          (is (= ["evaluate-rules" "simulate-devices" "send-reminders"] (map :id (get-in r [:json :items]))))
          (is (every? #(= "idle" (:state %)) (get-in r [:json :items])))))
      (testing "and nobody else"
        (is (= 403 (:status (get-json (str api "/api/admin/jobs") "dr.rivera@pulse.test"))))
        (is (= 403 (:status (get-json (str api "/api/admin/jobs") "ana.garcia@pulse.test"))))
        (is (= 403 (:status (get-json (str api "/api/admin/jobs") "scheduler"))))
        (is (= 401 (:status (get-json (str api "/api/admin/jobs") nil))))))))

(deftest ^:integration the-realm-keeps-each-token-to-the-service-it-is-for
  (with-both
    (fn [{:keys [api scheduler]}]
      (testing "the api's own token is the one the scheduler accepts, to read and to nothing more"
        (is (= 200 (:status (get-json (str scheduler "/jobs") "api"))))
        (let [run (http/post (str scheduler "/jobs/evaluate-rules/run")
                             {:throw false :headers {"Authorization" (str "Bearer " (token-of "api"))}})]
          (is (= 403 (:status run)) "it cannot start a job")))
      (testing "the tokens meant for the api are not accepted by the scheduler, whoever they belong to"
        (is (= 401 (:status (get-json (str scheduler "/jobs") "admin@pulse.test"))))
        (is (= 401 (:status (get-json (str scheduler "/jobs") "scheduler")))))
      (testing "and the api does not accept its own token, which is for the scheduler"
        (is (= 401 (:status (get-json (str api "/api/me") "api"))))
        (is (= 401 (:status (get-json (str api "/api/admin/jobs") "api"))))))))
