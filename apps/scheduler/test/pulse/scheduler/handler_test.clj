(ns pulse.scheduler.handler-test
  "The scheduler's Ring handler, whole: middleware, router, authentication, authorization,
  coercion and error responses, through the shared harness (a fake identity provider, an executor
  in a world of fake time)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.platform.test.idp :as idp]
            [pulse.scheduler.authz :as authz]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.handler :as handler]
            [pulse.scheduler.test.harness :as h :refer [access-token call content-type header with-scheduler]]
            [pulse.scheduler.test.time :as time])
  (:import (java.util.concurrent Executors)))

(def ^:private every-minute {:type :interval :every-ms 60000})

(defn- a-job [id schedule handler]
  {:id id :description (str "The job " (name id)) :schedule schedule :handler handler})

(def ^:private jobs
  [(a-job :every-minute every-minute (fn [_] {:evaluated 6}))
   (a-job :morning {:type :daily :at "08:30" :zone "Europe/Madrid"} (fn [_] nil))])

(defn- eventually [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 1)
            (recur))))))

;; ---------------------------------------------------------------------------
;; Public endpoints

(deftest liveness-and-readiness
  (with-scheduler {}
    (fn [api]
      (let [live (call api :get "/healthz")
            ready (call api :get "/readyz")]
        (is (= 200 (:status live)))
        (is (= {:status "up"} (:json live)))
        (is (= "application/json" (content-type live)))
        (is (= 200 (:status ready)))
        (is (= {:status "ready" :checks {:keycloak "up" :executor "up" :token "up"}} (:json ready)))))))

(deftest readiness-names-the-dependency-that-is-down
  (testing "the identity provider's keys"
    (with-scheduler {}
      (fn [api]
        (idp/break! (:idp api))
        (let [ready (call api :get "/readyz")]
          (is (= 503 (:status ready)))
          (is (= {:status "unready" :checks {:keycloak "down" :executor "up" :token "up"}} (:json ready)))))))
  (testing "the executor, once it is stopped"
    (with-scheduler {}
      (fn [api]
        (executor/stop! (:executor api))
        (let [ready (call api :get "/readyz")]
          (is (= 503 (:status ready)))
          (is (= {:status "unready" :checks {:keycloak "up" :executor "down" :token "up"}} (:json ready)))))))
  (testing "the scheduler's own token, which Keycloak will not give: a wrong secret shows here"
    (with-scheduler {:token-fetch (fn [] (throw (ex-info "refused" {:type :pulse.platform.token/unavailable
                                                                    :reason :rejected :status 401})))}
      (fn [api]
        (let [ready (call api :get "/readyz")]
          (is (= 503 (:status ready)))
          (is (= {:status "unready" :checks {:keycloak "up" :executor "up" :token "down"}} (:json ready)))
          (is (not (str/includes? (:text ready) "refused")) "and the reason is not told"))))))

(deftest readiness-asks-for-the-token-once-and-then-uses-the-one-it-has
  (let [asked (atom 0)]
    (with-scheduler {:token-fetch (fn [] (swap! asked inc) {:access-token "t" :expires-in 300})}
      (fn [api]
        (dotimes [_ 5] (call api :get "/readyz"))
        (is (= 1 @asked) "a probe that is polled all day does not ask Keycloak each time")
        (time/advance! (:world api) 240000)
        (call api :get "/readyz")
        (is (= 2 @asked) "until the token is near its end")))))

(deftest public-endpoints-ignore-credentials
  (with-scheduler {}
    (fn [api]
      (is (= 200 (:status (call api :get "/healthz" :headers {"authorization" "Bearer garbage"}))))
      (is (= 200 (:status (call api :get "/healthz" :token (access-token :exp 1))))))))

(deftest health-probes-are-quiet-in-the-log
  (with-scheduler {}
    (fn [api]
      (is (empty? (filter #(= :pulse.http/request (:id %)) (:signals (call api :get "/healthz"))))
          "debug, below the default level")
      (is (= 1 (count (filter #(= :pulse.http/request (:id %))
                              (:signals (call api :get "/jobs" :token (h/token-for :admin))))))))))

;; ---------------------------------------------------------------------------
;; Errors the router itself raises

(deftest unknown-routes-and-methods-are-problems-too
  (with-scheduler {}
    (fn [api]
      (let [missing (call api :get "/nope" :headers {"x-request-id" "req-1"})]
        (is (= 404 (:status missing)))
        (is (= "application/problem+json" (content-type missing)))
        (is (= "req-1" (header missing "X-Request-Id")))
        (is (= {:type "about:blank" :title "Not Found" :status 404 :detail "No such resource."
                :instance "/nope" :requestId "req-1"}
               (:json missing))))
      (let [wrong (call api :post "/healthz")]
        (is (= 405 (:status wrong)))
        (is (= "GET" (header wrong "Allow"))))
      (let [wrong (call api :get "/jobs/every-minute/run" :token (h/token-for :admin))]
        (is (= 405 (:status wrong)))
        (is (= "POST" (header wrong "Allow"))))
      (testing "there is no OPTIONS endpoint that nobody wrote an :authz for"
        (is (= 405 (:status (call api :options "/jobs"))))))))

;; ---------------------------------------------------------------------------
;; Authentication: only tokens for this service

(deftest a-request-without-credentials-gets-the-challenge
  (with-scheduler {}
    (fn [api]
      (let [r (call api :get "/jobs")]
        (is (= 401 (:status r)))
        (is (= "Bearer realm=\"pulse-scheduler\"" (header r "WWW-Authenticate")))
        (is (= "application/problem+json" (content-type r)))))))

(deftest a-token-meant-for-the-api-is-not-accepted
  ;; The audience is what keeps each service from being made to act with a token meant for the
  ;; other (ADR-0020): the administrator's token as the api receives it has the api's audience.
  (with-scheduler {}
    (fn [api]
      (let [r (call api :get "/jobs" :token (h/token-for :admin :aud ["pulse-api" "account"]))]
        (is (= 401 (:status r)))
        (is (str/includes? (header r "WWW-Authenticate") "error=\"invalid_token\""))
        (is (not (str/includes? (:text r) "pulse-scheduler")) "what was expected is not told"))
      (is (= 401 (:status (call api :post "/jobs/every-minute/run"
                                :token (h/token-for :admin :aud ["pulse-api"])))))
      (testing "while the same person with a token for the scheduler is welcome"
        (is (= 200 (:status (call api :get "/jobs" :token (h/token-for :admin)))))
        (is (= 200 (:status (call api :get "/jobs"
                                  :token (h/token-for :admin :aud ["pulse-api" "pulse-scheduler"])))))))))

(deftest tokens-that-are-not-good-are-refused
  (with-scheduler {}
    (fn [api]
      (doseq [[label token] {"expired" (h/token-for :admin :exp 1)
                             "another issuer" (h/token-for :admin :iss "http://evil.test/realms/pulse")
                             "garbage" "abc.def.ghi"}]
        (is (= 401 (:status (call api :get "/jobs" :token token))) label)))))

;; ---------------------------------------------------------------------------
;; Authorization: only the administrator

(deftest only-the-administrator-may-see-the-jobs-and-run-them
  (with-scheduler {:jobs jobs}
    (fn [api]
      (doseq [who [:rivera :ana :scheduler]]
        (testing (name who)
          (let [list (call api :get "/jobs" :token (h/token-for who))
                run (call api :post "/jobs/every-minute/run" :token (h/token-for who))]
            (is (= 403 (:status list)))
            (is (= 403 (:status run)))
            (is (= "You are not allowed to do this." (get-in list [:json :detail])) "and no word on why"))))
      (is (nil? (get-in (executor/job-status (:executor api) :every-minute) [:last-run])) "nothing was run")
      (is (= 200 (:status (call api :get "/jobs" :token (h/token-for :admin)))))
      (is (= 202 (:status (call api :post "/jobs/every-minute/run" :token (h/token-for :admin))))))))

(deftest every-decision-is-an-audit-event
  (with-scheduler {:jobs jobs}
    (fn [api]
      (let [allowed (call api :get "/jobs" :token (h/token-for :admin))
            denied (call api :post "/jobs/every-minute/run" :token (h/token-for :rivera))
            [a] (h/audit-events allowed)
            [d] (h/audit-events denied)]
        (is (= 1 (count (h/audit-events allowed))))
        (is (= :info (:level a)))
        (is (= {:actor {:subject "u-admin" :username "admin@pulse.test" :roles ["clinic-admin"] :client "pulse-api"}
                :action :jobs/read :outcome :allowed :method "GET" :path "/jobs"}
               (:data a)))
        (is (= :warn (:level d)))
        (is (= {:actor {:subject "u-rivera" :username "dr.rivera@pulse.test" :roles ["practitioner"] :client "pulse-api"}
                :action :jobs/run :outcome :denied :method "POST" :path "/jobs/every-minute/run"}
               (:data d)))))))

;; ---------------------------------------------------------------------------
;; GET /jobs

(deftest the-jobs-are-listed-as-json
  (with-scheduler {:jobs jobs}
    (fn [api]
      (let [r (call api :get "/jobs" :token (h/token-for :admin))]
        (is (= 200 (:status r)))
        (is (= "application/json" (content-type r)))
        (is (= [{:id "every-minute" :description "The job every-minute"
                 :schedule {:type "interval" :everyMs 60000}
                 :state "idle" :runningSince nil :nextRun "2026-10-15T10:03:00Z" :lastRun nil
                 :counts {:ok 0 :failed 0 :interrupted 0 :skipped 0}}
                {:id "morning" :description "The job morning"
                 :schedule {:type "daily" :at "08:30" :zone "Europe/Madrid"}
                 :state "idle" :runningSince nil :nextRun "2026-10-16T06:30:00Z" :lastRun nil
                 :counts {:ok 0 :failed 0 :interrupted 0 :skipped 0}}]
               (:json r))))
      (testing "after the time has come"
        (time/advance! (:world api) 47000)
        (let [job (first (:json (call api :get "/jobs" :token (h/token-for :admin))))]
          (is (= {:trigger "schedule" :startedAt "2026-10-15T10:03:00Z" :finishedAt "2026-10-15T10:03:00Z"
                  :durationMs 0 :outcome "ok" :result {:evaluated 6}}
                 (:lastRun job)))
          (is (= "2026-10-15T10:04:00Z" (:nextRun job)))
          (is (= 1 (get-in job [:counts :ok]))))))))

(deftest a-failed-job-shows-the-class-of-the-error-and-not-the-message
  (with-scheduler {:jobs [(a-job :broken every-minute (fn [_] (throw (ex-info "the password is hunter2" {}))))]}
    (fn [api]
      (time/advance! (:world api) 47000)
      (let [r (call api :get "/jobs" :token (h/token-for :admin))]
        (is (= {:trigger "schedule" :startedAt "2026-10-15T10:03:00Z" :finishedAt "2026-10-15T10:03:00Z"
                :durationMs 0 :outcome "failed" :error "clojure.lang.ExceptionInfo"}
               (get-in r [:json 0 :lastRun])))
        (is (not (str/includes? (:text r) "hunter2")))))))

(deftest no-jobs-is-an-empty-list
  (with-scheduler {}
    (fn [api]
      (let [r (call api :get "/jobs" :token (h/token-for :admin))]
        (is (= 200 (:status r)))
        (is (= [] (:json r)))))))

;; ---------------------------------------------------------------------------
;; POST /jobs/{id}/run

(deftest running-a-job-starts-it-and-answers-with-its-status
  (with-scheduler {:jobs jobs}
    (fn [api]
      (let [r (call api :post "/jobs/every-minute/run" :token (h/token-for :admin))]
        (is (= 202 (:status r)))
        (is (= "every-minute" (get-in r [:json :id])))
        (is (= {:trigger "manual" :startedAt "2026-10-15T10:02:13Z" :finishedAt "2026-10-15T10:02:13Z"
                :durationMs 0 :outcome "ok" :result {:evaluated 6}}
               (get-in r [:json :lastRun])))
        (is (= "2026-10-15T10:03:00Z" (get-in r [:json :nextRun])) "the schedule is where it was")))))

(deftest the-events-of-a-run-carry-the-id-of-the-request-that-asked-for-it
  (with-scheduler {:jobs jobs}
    (fn [api]
      (let [r (call api :post "/jobs/every-minute/run" :token (h/token-for :admin)
                    :headers {"x-request-id" "req-9"})
            ours (filter #(#{:pulse.scheduler/manual-run :pulse.scheduler/job-finished} (:id %)) (:signals r))]
        (is (= 2 (count ours)))
        (is (every? #(= "req-9" (get-in % [:ctx :request-id])) ours))))))

(deftest a-job-that-is-running-cannot-be-run-again
  (let [gate (promise)
        entered (atom 0)]
    (with-scheduler {:workers (Executors/newVirtualThreadPerTaskExecutor)
                     :jobs [(a-job :slow every-minute (fn [_] (swap! entered inc) @gate nil))]}
      (fn [api]
        (let [first-run (call api :post "/jobs/slow/run" :token (h/token-for :admin))]
          (is (= 202 (:status first-run)))
          (is (eventually #(= 1 @entered)))
          (is (= "running" (get-in first-run [:json :state])))
          (is (= "2026-10-15T10:02:13Z" (get-in first-run [:json :runningSince]))))
        (let [again (call api :post "/jobs/slow/run" :token (h/token-for :admin))]
          (is (= 409 (:status again)))
          (is (= "application/problem+json" (content-type again)))
          (is (= "The job is already running." (get-in again [:json :detail]))))
        (is (= 1 @entered))
        (deliver gate :go)
        (is (eventually #(= :idle (:state (executor/job-status (:executor api) :slow)))))
        (is (= 202 (:status (call api :post "/jobs/slow/run" :token (h/token-for :admin))))
            "and once it is over it can")))))

(deftest a-job-that-does-not-exist-is-a-404
  (with-scheduler {:jobs jobs}
    (fn [api]
      (let [r (call api :post "/jobs/nope/run" :token (h/token-for :admin))]
        (is (= 404 (:status r)))
        (is (= "No such job." (get-in r [:json :detail])))))))

(deftest an-id-that-cannot-be-one-is-a-400
  (with-scheduler {:jobs jobs}
    (fn [api]
      (doseq [bad ["Not_An_Id" "UPPER" "1st" "a.b" "-a"]]
        (let [r (call api :post (str "/jobs/" bad "/run") :token (h/token-for :admin))]
          (is (= 400 (:status r)) bad)
          (is (seq (get-in r [:json :errors :id])) bad))))))

(deftest nothing-can-be-run-once-the-scheduler-is-stopping
  (with-scheduler {:jobs jobs}
    (fn [api]
      (executor/stop! (:executor api))
      (let [r (call api :post "/jobs/every-minute/run" :token (h/token-for :admin))]
        (is (= 503 (:status r)))
        (is (= "The scheduler is stopping." (get-in r [:json :detail])))))))

;; ---------------------------------------------------------------------------
;; Headers and a router with a hole

(deftest the-security-headers-are-on-every-response
  (with-scheduler {:jobs jobs}
    (fn [api]
      (doseq [[label r] {"a success" (call api :get "/jobs" :token (h/token-for :admin))
                         "a public endpoint" (call api :get "/healthz")
                         "a 401" (call api :get "/jobs")
                         "a 403" (call api :get "/jobs" :token (h/token-for :ana))
                         "a 404" (call api :get "/nope")}]
        (testing label
          (is (= "nosniff" (header r "X-Content-Type-Options")))
          (is (= "no-store" (header r "Cache-Control")))
          (is (= "default-src 'none'; frame-ancestors 'none'" (header r "Content-Security-Policy")))
          (is (= "DENY" (header r "X-Frame-Options")))))
      (testing "and no CORS: no browser calls the scheduler"
        (let [r (call api :options "/jobs" :headers {"origin" "http://localhost:3000"
                                                     "access-control-request-method" "GET"})]
          (is (nil? (header r "Access-Control-Allow-Origin"))))))))

(deftest an-endpoint-without-authz-does-not-even-build
  (let [e (try (handler/ring-handler [["/open" {:get {:handler (constantly {:status 200})}}]]
                                     {:verifier nil :realm "pulse-scheduler" :quiet-paths #{}})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::authz/missing-authz (:type (ex-data e))))
    (is (= ["get /open"] (:endpoints (ex-data e))))))
