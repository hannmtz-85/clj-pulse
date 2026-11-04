(ns smoke-checks-test
  "What `bb smoke` decides from what it reads (ADR-0030): the options, the containers, the issuer of a token asked at the back channel, the
  discovery document, the jobs and the name of the script. The asking is `smoke`, which needs the containers."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [smoke-checks :as s]))

(deftest the-options
  (is (= {:timeout-seconds 150 :browser? true} (s/parse-args [])))
  (is (= {:timeout-seconds 150 :browser? false} (s/parse-args ["--no-browser"])))
  (is (= {:timeout-seconds 30 :browser? true} (s/parse-args ["--timeout-seconds" "30"])))
  (is (= {:timeout-seconds 5 :browser? false} (s/parse-args ["--no-browser" "--timeout-seconds" "5"])))
  (doseq [bad [["--nope"] ["--timeout-seconds"] ["--timeout-seconds" "x"] ["--timeout-seconds" "0"] ["--timeout-seconds" "-3"] ["api"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"I do not know what to do with" (s/parse-args bad)) (pr-str bad)))
  (is (= "I do not know what to do with \"--nope\". The options are --timeout-seconds N and --no-browser."
         (ex-message (try (s/parse-args ["--nope"]) (catch clojure.lang.ExceptionInfo e e))))))

(def ^:private api {:Service "api" :State "running" :Health "healthy"})

(deftest the-containers-are-read-from-either-format-of-compose
  (let [containers [api {:Service "spa" :State "running" :Health "healthy"} {:Service "keycloak" :State "running" :Health ""}]]
    (testing "one document for each container"
      (is (= [{:service "api" :state "running" :health "healthy"}
              {:service "spa" :state "running" :health "healthy"}
              {:service "keycloak" :state "running" :health nil}]
             (s/parse-ps (str (apply str (interpose "\n" (map json/write-str containers))) "\n")))))
    (testing "one array"
      (is (= 3 (count (s/parse-ps (json/write-str containers))))))
    (testing "nothing"
      (is (= [] (s/parse-ps "")))
      (is (= [] (s/parse-ps nil)))
      (is (= [] (s/parse-ps "\n\n"))))))

(deftest a-container-must-be-there-running-and-healthy
  (let [ps (fn [& containers] (s/parse-ps (json/write-str containers)))]
    (is (= [] (s/container-problems ["api" "spa"] (ps api {:Service "spa" :State "running" :Health "healthy"}))))
    (is (= ["web has no container"] (s/container-problems ["api" "web"] (ps api))))
    (is (= ["api is exited, not running"] (s/container-problems ["api"] (ps (assoc api :State "exited")))))
    (is (= ["api is in no state, not running"] (s/container-problems ["api"] (ps (dissoc api :State)))))
    (is (= ["api is starting, not healthy"] (s/container-problems ["api"] (ps (assoc api :Health "starting")))))
    (is (= ["api is unhealthy, not healthy"] (s/container-problems ["api"] (ps (assoc api :Health "unhealthy")))))
    (is (= ["api is without a health check, not healthy"] (s/container-problems ["api"] (ps (assoc api :Health "")))))
    (is (= ["api has no container" "scheduler has no container" "web has no container" "spa has no container"]
           (s/container-problems s/services [])))
    (testing "the services of the profile"
      (is (= ["api" "scheduler" "web" "spa"] s/services)))))

(deftest a-container-that-was-killed-or-restarted-is-reported
  (is (= [] (s/restart-problems "api" false 0)))
  (is (= ["api was killed for lack of memory"] (s/restart-problems "api" true 0)))
  (is (= ["api was restarted 1 time"] (s/restart-problems "api" false 1)))
  (is (= ["api was restarted 3 times"] (s/restart-problems "api" false 3)))
  (is (= 2 (count (s/restart-problems "api" true 2))))
  (is (= [] (s/restart-problems "api" nil nil)) "what could not be read is not a restart"))

(deftest a-token-asked-at-the-back-channel-says-it-was-issued-where-a-browser-reaches-the-identity-provider
  (is (= "http://localhost:8080/realms/pulse" s/issuer))
  (is (= [] (s/issuer-problems "the api" {:iss "http://localhost:8080/realms/pulse"})))
  (is (= ["the token of the api says it was issued by http://keycloak:8080/realms/pulse, not http://localhost:8080/realms/pulse"]
         (s/issuer-problems "the api" {:iss "http://keycloak:8080/realms/pulse"})))
  (is (= ["the token of the scheduler has no issuer"] (s/issuer-problems "the scheduler" {})))
  (is (= ["the token of the api has no issuer"] (s/issuer-problems "the api" nil))))

(def ^:private discovery
  {:issuer "http://localhost:8080/realms/pulse"
   :token_endpoint "http://keycloak:8080/realms/pulse/protocol/openid-connect/token"
   :jwks_uri "http://keycloak:8080/realms/pulse/protocol/openid-connect/certs"
   :authorization_endpoint "http://localhost:8080/realms/pulse/protocol/openid-connect/auth"})

(deftest the-discovery-document-splits-the-front-channel-from-the-back-channel
  (is (= [] (s/discovery-problems discovery)))
  (is (= ["the issuer is \"http://keycloak:8080/realms/pulse\""]
         (s/discovery-problems (assoc discovery :issuer "http://keycloak:8080/realms/pulse"))))
  (is (= ["the token endpoint is \"http://localhost:8080/realms/pulse/protocol/openid-connect/token\", not at http://keycloak:8080"]
         (s/discovery-problems (assoc discovery :token_endpoint "http://localhost:8080/realms/pulse/protocol/openid-connect/token"))))
  (is (= ["the keys are at \"http://localhost:8080/realms/pulse/protocol/openid-connect/certs\", not at http://keycloak:8080"]
         (s/discovery-problems (assoc discovery :jwks_uri "http://localhost:8080/realms/pulse/protocol/openid-connect/certs"))))
  (is (= ["the authorization endpoint is \"http://keycloak:8080/realms/pulse/protocol/openid-connect/auth\", which a browser does not reach"]
         (s/discovery-problems (assoc discovery :authorization_endpoint "http://keycloak:8080/realms/pulse/protocol/openid-connect/auth"))))
  (testing "a prefix that is not the host"
    (is (seq (s/discovery-problems (assoc discovery :token_endpoint "http://keycloak:8080.evil/token")))))
  (is (= 4 (count (s/discovery-problems {})))))

(def ^:private ok-run {:outcome "ok"})

(defn- job [id run & {:as counts}] {:id id :lastRun run :counts (merge {:ok 1 :failed 0 :interrupted 0 :skipped 0} counts)})

(deftest the-jobs-have-run-when-each-has-a-last-run
  (is (true? (s/jobs-settled? 3 {:items [(job "a" ok-run) (job "b" ok-run) (job "c" ok-run)]})))
  (is (not (s/jobs-settled? 3 {:items [(job "a" ok-run) (job "b" nil) (job "c" ok-run)]})) "one has not run yet")
  (is (not (s/jobs-settled? 3 {:items [(job "a" ok-run) (job "b" ok-run)]})) "there are fewer than there should be")
  (is (not (s/jobs-settled? 3 {:items []})))
  (is (not (s/jobs-settled? 3 nil))))

(deftest a-job-that-has-failed-is-reported-however-well-it-ran-last
  (is (= [] (s/jobs-problems {:items [(job "a" ok-run) (job "b" ok-run)]})))
  (is (= ["a: the last run was \"failed\""] (s/jobs-problems {:items [(job "a" {:outcome "failed"})]})))
  (is (= ["a: the last run was nil"] (s/jobs-problems {:items [(job "a" nil)]})))
  (is (= ["a: 1 failed and 0 interrupted"] (s/jobs-problems {:items [(job "a" ok-run :failed 1)]})) "ok now, after a failure")
  (is (= ["a: 0 failed and 2 interrupted"] (s/jobs-problems {:items [(job "a" ok-run :interrupted 2)]}))))

(deftest the-script-of-the-page-is-the-one-with-the-hash-in-its-name
  (is (= "main.FC753F2B0EC2BAC3C2E49D6A5404976A.js"
         (s/script-name "<head><script src=\"/js/main.FC753F2B0EC2BAC3C2E49D6A5404976A.js\" defer></script></head>")))
  (is (nil? (s/script-name "<script src=\"/js/main.js\"></script>")) "the development page")
  (is (nil? (s/script-name nil)))
  (is (nil? (s/script-name "<script src=\"/js/main.12345.js\"></script>")) "a hash is eight digits at least"))
