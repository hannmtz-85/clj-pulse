(ns pulse.api.jobs-test
  "GET /api/admin/jobs (ADR-0025): the administrator sees what the scheduler is doing, through the
  api, which asks the scheduler with the token of its own service account. The scheduler is a
  stand-in HTTP service that answers as a function says and keeps the requests it got, so these tests
  can see what the api sent as well as what it answered. The real scheduler, with the real api, is in
  `pulse.dev-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.api.jobs :as api-jobs]
            [pulse.api.test.harness :as h :refer [audit-events call with-api]]
            [pulse.platform.http.client :as http]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]))

(def ^:private jobs
  "What the scheduler's `GET /jobs` answers: one job that ran well, one that is running now after a
  run that failed, and one that has not run."
  [{:id "evaluate-rules"
    :description "Evaluates the clinical rules over the latest readings and raises alerts"
    :schedule {:type "interval" :everyMs 30000}
    :state "idle" :runningSince nil :nextRun "2026-10-16T07:15:30Z"
    :lastRun {:trigger "schedule" :startedAt "2026-10-16T07:15:00Z" :finishedAt "2026-10-16T07:15:02Z"
              :durationMs 2012 :outcome "ok"
              :result {:patients 6 :raised 0 :alreadyRaised 4 :stillActive 0 :notApplicable 0 :failed 0}}
    :counts {:ok 12 :failed 0 :interrupted 0 :skipped 0}}
   {:id "send-reminders"
    :description "Sends a reminder to the patients whose confirmed appointment is about to begin"
    :schedule {:type "daily" :at "08:30" :zone "Europe/Madrid"}
    :state "running" :runningSince "2026-10-16T07:15:00Z" :nextRun "2026-10-17T06:30:00Z"
    :lastRun {:trigger "manual" :startedAt "2026-10-15T06:30:00Z" :finishedAt "2026-10-15T06:30:01Z"
              :durationMs 1000 :outcome "failed" :error "ExceptionInfo"}
    :counts {:ok 3 :failed 1 :interrupted 0 :skipped 2}}
   {:id "simulate-devices"
    :description "Simulates the readings of the devices of the demo cohort and writes them to FHIR"
    :schedule {:type "interval" :everyMs 15000 :offsetMs 5000}
    :state "idle" :runningSince nil :nextRun nil :lastRun nil
    :counts {:ok 0 :failed 0 :interrupted 0 :skipped 0}}])

(defn- json-text [x] (json/write-value-as-string x))

(defn- the-apis-token
  "The keeper of the api's own token: it gives `the-apis-own-token` and counts how often it was asked."
  []
  (let [fetched (atom 0)]
    [(token/source {:fetch (fn [] (swap! fetched inc) {:access-token "the-apis-own-token" :expires-in 300})
                    :client-id "pulse-api"})
     fetched]))

(defn- with-scheduler
  "Calls `(f api service fetched)` with the api wired to a stand-in for the scheduler that answers as
  `handler` says. `options` are those of the client of the scheduler (`:timeout-ms`, ...)."
  [handler f & {:as options}]
  (let [service (stub/start! handler)
        [own-token fetched] (the-apis-token)
        scheduler (http/client (merge {:base-url (:url service) :token own-token} options))]
    (try
      (with-api {:scheduler scheduler}
        (fn [api] (f api service fetched)))
      (finally
        (http/close! scheduler)
        (stub/stop! service)))))

(defn- the-jobs [_request] {:body (json-text jobs)})

(defn- jobs! [api who & options]
  (apply call api :get "/api/admin/jobs" :token (h/token-for who) options))

(defn- problem-events
  "The `:pulse.http/problem` events logged while a response was served."
  [response]
  (filter #(= :pulse.http/problem (:id %)) (:signals response)))

;; ---------------------------------------------------------------------------
;; The administrator

(deftest the-administrator-sees-the-jobs-of-the-scheduler
  (with-scheduler the-jobs
    (fn [api service _]
      (let [r (jobs! api :admin)]
        (is (= 200 (:status r)))
        (is (= "application/json" (h/content-type r)))
        (is (= {:total 3 :items jobs} (:json r))))
      (testing "after asking the scheduler for its jobs, and for nothing else"
        (let [[request :as requests] @(:requests service)]
          (is (= 1 (count requests)))
          (is (= "GET" (:method request)))
          (is (= "/jobs" (:path request)))
          (is (nil? (:query request))))))))

(deftest a-scheduler-with-no-jobs-is-an-empty-list
  (with-scheduler (fn [_] {:body "[]"})
    (fn [api _ _]
      (let [r (jobs! api :admin)]
        (is (= 200 (:status r)))
        (is (= {:total 0 :items []} (:json r)))))))

(deftest the-api-asks-with-the-token-of-its-own-service-account-and-never-with-the-callers
  (with-scheduler the-jobs
    (fn [api service fetched]
      (let [admin-token (h/token-for :admin)]
        (call api :get "/api/admin/jobs" :token admin-token)
        (call api :get "/api/admin/jobs" :token admin-token)
        (let [authorizations (map #(get-in % [:headers "authorization"]) @(:requests service))]
          (is (= 2 (count authorizations)))
          (is (every? #(= "Bearer the-apis-own-token" %) authorizations))
          (is (not-any? #(str/includes? % admin-token) authorizations)))
        (is (= 1 @fetched) "the token is kept, and not asked for at each request")))))

(deftest the-id-of-the-request-goes-to-the-scheduler-so-that-both-logs-tell-one-story
  (with-scheduler the-jobs
    (fn [api service _]
      (testing "the one the caller sent"
        (let [r (jobs! api :admin :headers {"x-request-id" "req-77"})]
          (is (= "req-77" (h/header r "X-Request-Id")))
          (is (= "req-77" (get-in (last @(:requests service)) [:headers "x-request-id"])))))
      (testing "or the one the api made up, which is the one the caller is told"
        (let [r (jobs! api :admin)
              given (h/header r "X-Request-Id")]
          (is (re-matches #"[0-9a-f-]{36}" given))
          (is (= given (get-in (last @(:requests service)) [:headers "x-request-id"]))))))))

(deftest what-the-scheduler-adds-is-not-passed-on
  ;; the schema is the api's: a field of the scheduler that it does not declare does not reach the caller
  (with-scheduler (fn [_] {:body (json-text (mapv #(assoc % :internalNote "not for the caller") jobs))})
    (fn [api _ _]
      (let [r (jobs! api :admin)]
        (is (= 200 (:status r)))
        (is (= jobs (get-in r [:json :items])))
        (is (not (str/includes? (:text r) "internalNote")))))))

;; ---------------------------------------------------------------------------
;; Who may ask

(deftest nobody-but-the-administrator-may-ask-and-the-scheduler-is-not-bothered
  (with-scheduler the-jobs
    (fn [api service _]
      (doseq [who [:rivera :ana :scheduler]]
        (let [r (jobs! api who)]
          (is (= 403 (:status r)) (name who))
          (is (= "You are not allowed to do this." (get-in r [:json :detail])) "and no word on why")))
      (testing "the token of the api's own service account is for the scheduler, and the api refuses it"
        (is (= 401 (:status (jobs! api :api-service)))))
      (testing "no token, or a bad one"
        (let [r (call api :get "/api/admin/jobs")]
          (is (= 401 (:status r)))
          (is (str/starts-with? (h/header r "WWW-Authenticate") "Bearer realm=\"pulse-api\"")))
        (is (= 401 (:status (call api :get "/api/admin/jobs" :token "abc.def.ghi")))))
      (is (empty? @(:requests service)) "none of them reached the scheduler")
      (is (= 200 (:status (jobs! api :admin)))))))

(deftest only-reading-is-possible-through-the-api
  (with-scheduler the-jobs
    (fn [api service _]
      (doseq [method [:post :put :patch :delete]]
        (is (= 405 (:status (call api method "/api/admin/jobs" :token (h/token-for :admin)))) (name method)))
      (is (= 404 (:status (call api :post "/api/admin/jobs/evaluate-rules/run" :token (h/token-for :admin)))))
      (is (empty? @(:requests service))))))

(deftest every-decision-is-an-audit-event
  (with-scheduler the-jobs
    (fn [api _ _]
      (let [allowed (jobs! api :admin)
            denied (jobs! api :rivera)
            [a] (audit-events allowed)
            [d] (audit-events denied)]
        (is (= 1 (count (audit-events allowed))))
        (is (= :info (:level a)))
        (is (= [:jobs/read :allowed :all] ((juxt #(get-in % [:data :action]) #(get-in % [:data :outcome]) #(get-in % [:data :scope])) a)))
        (is (= "admin@pulse.test" (get-in a [:data :actor :username])))
        (is (= :warn (:level d)))
        (is (= [:jobs/read :denied] ((juxt #(get-in % [:data :action]) #(get-in % [:data :outcome])) d)))))))

(deftest the-api-is-ready-whether-or-not-the-scheduler-is
  ;; the scheduler is an administrative extra: the api works without it, so no probe asks for it
  (let [service (stub/start! the-jobs)
        [own-token _] (the-apis-token)
        scheduler (http/client {:base-url (:url service) :token own-token})]
    (stub/stop! service)
    (try
      (with-api {:scheduler scheduler}
        (fn [api]
          (let [r (call api :get "/readyz")]
            (is (= 200 (:status r)))
            (is (= {:status "ready" :checks {:keycloak "up" :fhir "up"}} (:json r))))))
      (finally (http/close! scheduler)))))

;; ---------------------------------------------------------------------------
;; When the scheduler cannot be asked: not the caller's fault

(defn- leaks?
  "Whether `text` says something about where the scheduler is, how the call failed or what it said."
  [text]
  (boolean (re-find #"127\.0\.0\.1|localhost|Connection|refused|timed out|java\.|Exception|secret|the-apis-own-token" text)))

(deftest a-scheduler-that-is-not-there-is-a-502-with-a-generic-text
  (let [service (stub/start! the-jobs)
        [own-token _] (the-apis-token)
        scheduler (http/client {:base-url (:url service) :token own-token})]
    (stub/stop! service)
    (try
      (with-api {:scheduler scheduler}
        (fn [api]
          (let [r (jobs! api :admin)]
            (is (= 502 (:status r)))
            (is (= "application/problem+json" (h/content-type r)))
            (is (= "The scheduler could not be asked." (get-in r [:json :detail])))
            (is (not (leaks? (:text r))))
            (testing "and the cause is in the log"
              (let [[event :as events] (problem-events r)]
                (is (= 1 (count events)))
                (is (= :error (:level event)))
                (is (= ::http/unreachable (:type (ex-data (ex-cause (:error event))))))
                (is (= :connect (:reason (ex-data (ex-cause (:error event)))))))))))
      (finally (http/close! scheduler)))))

(deftest a-scheduler-that-takes-too-long-is-a-504
  (with-scheduler (fn [_] {:body (json-text jobs) :delay-ms 1500})
    (fn [api _ _]
      (let [r (jobs! api :admin)]
        (is (= 504 (:status r)))
        (is (= "The scheduler did not answer in time." (get-in r [:json :detail])))
        (is (not (leaks? (:text r))))
        (is (= :timeout (:reason (ex-data (ex-cause (:error (first (problem-events r))))))))))
    :timeout-ms 150))

(deftest a-scheduler-that-answers-with-an-error-is-a-502-whatever-the-error
  (doseq [status [400 401 403 404 409 500 502 503]]
    (with-scheduler (fn [_] {:status status
                             :headers {"Content-Type" "application/problem+json"}
                             :body (json-text {:type "about:blank" :title "A title" :status status
                                               :detail "The scheduler says: secret-host:5000 is unhappy"})})
      (fn [api _ _]
        (let [r (jobs! api :admin)]
          (testing (str "the scheduler answered " status)
            (is (= 502 (:status r)))
            (is (= "The scheduler could not be asked." (get-in r [:json :detail])))
            (is (not (str/includes? (:text r) "secret-host")) "what it said is not repeated")
            (is (= status (:status (ex-data (ex-cause (:error (first (problem-events r)))))))
                "but it is in the log")))))))

(deftest an-answer-that-is-not-what-the-scheduler-gives-is-a-502
  (doseq [[label body] {"text" "the scheduler is fine, thanks"
                        "an object and not a list" (json-text {:jobs jobs})
                        "nothing at all" ""
                        "null" "null"
                        "a job with no id" (json-text [(dissoc (first jobs) :id)])
                        "a job in a state nobody knows" (json-text [(assoc (first jobs) :state "sleeping")])
                        "a run with an outcome nobody knows" (json-text [(assoc-in (first jobs) [:lastRun :outcome] "maybe")])
                        "counts that are not numbers" (json-text [(assoc-in (first jobs) [:counts :ok] "twelve")])
                        "counts that lack one" (json-text [(update (first jobs) :counts dissoc :failed)])
                        "counts that are not counts" (json-text [(assoc (first jobs) :counts "twelve")])
                        "a job that is not an object" (json-text ["evaluate-rules"])
                        "a job with an id that is not text" (json-text [(assoc (first jobs) :id 7)])
                        "a job with no description" (json-text [(dissoc (first jobs) :description)])
                        "a job with a description that is not text" (json-text [(assoc (first jobs) :description 7)])
                        "a schedule that is not an object" (json-text [(assoc (first jobs) :schedule "every minute")])
                        "a job with no schedule" (json-text [(dissoc (first jobs) :schedule)])
                        "a time it runs since that is not text" (json-text [(assoc (first jobs) :runningSince 7)])
                        "a next run that is not text" (json-text [(assoc (first jobs) :nextRun 7)])
                        "a job with no next run at all" (json-text [(dissoc (first jobs) :nextRun)])
                        "a last run that is not an object" (json-text [(assoc (first jobs) :lastRun "yesterday")])
                        "a run begun by nobody we know" (json-text [(assoc-in (first jobs) [:lastRun :trigger] "magic")])
                        "a run that began at no time" (json-text [(assoc-in (first jobs) [:lastRun :startedAt] 12)])
                        "a run that ended at no time" (json-text [(update-in (first jobs) [:lastRun] dissoc :finishedAt)])
                        "a run that took no time we can read" (json-text [(assoc-in (first jobs) [:lastRun :durationMs] "fast")])
                        "a result that is not an object" (json-text [(assoc-in (first jobs) [:lastRun :result] [1 2])])
                        "an error that is not text" (json-text [(assoc-in (second jobs) [:lastRun :error] 5)])
                        "one good job and one that is not" (json-text [(first jobs) (dissoc (second jobs) :counts)])}]
    (with-scheduler (fn [_] {:body body})
      (fn [api _ _]
        (let [r (jobs! api :admin)]
          (testing label
            (is (= 502 (:status r)))
            (is (not (leaks? (:text r))))
            (is (= "application/problem+json" (h/content-type r)))))))))

(deftest an-answer-that-does-not-fit-is-said-in-the-log-and-not-to-the-caller
  (with-scheduler (fn [_] {:body (json-text [(assoc (first jobs) :state "sleeping")])})
    (fn [api _ _]
      (let [r (jobs! api :admin)
            event (first (filter #(= :pulse.jobs/unexpected-answer (:id %)) (:signals r)))]
        (is (= 502 (:status r)))
        (is (= "The scheduler answered something the api does not understand." (get-in r [:json :detail])))
        (is (not (str/includes? (:text r) "sleeping")))
        (is (= :error (:level event)))
        (is (str/includes? (pr-str (get-in event [:data :problems])) "state"))))))

(deftest an-api-that-cannot-get-its-own-token-is-a-502-and-asks-the-scheduler-for-nothing
  ;; whatever the reason the token endpoint gives, including that it did not answer in time: the
  ;; scheduler was not asked, so it was not too slow, and a 504 would blame it
  (doseq [reason [:rejected :timeout :connect :server :bad-response :backing-off]]
    (let [service (stub/start! the-jobs)
          own-token (token/source {:fetch (fn [] (throw (ex-info "the token endpoint said no"
                                                                 {:type ::token/unavailable :reason reason})))
                                   :client-id "pulse-api"})
          scheduler (http/client {:base-url (:url service) :token own-token})]
      (try
        (with-api {:scheduler scheduler}
          (fn [api]
            (let [r (jobs! api :admin)]
              (testing (name reason)
                (is (= 502 (:status r)))
                (is (= "The scheduler could not be asked." (get-in r [:json :detail])))
                (is (not (leaks? (:text r))))
                (is (= ::token/unavailable (:type (ex-data (ex-cause (:error (first (problem-events r))))))))
                (is (empty? @(:requests service)) "nothing was sent without a token")))))
        (finally
          (http/close! scheduler)
          (stub/stop! service))))))

(deftest a-failure-that-is-a-bug-is-not-taken-for-the-schedulers
  (doseq [bug [(IllegalStateException. "a bug of ours")
               (ex-info "a bug of ours that says what it is" {:type ::a-bug})
               (ex-info "a bug of ours that says nothing" {})
               (ex-info "a bug of ours that has a status" {:status 503})]]
    (with-redefs [http/call (fn [_ _] (throw bug))]
      (with-api {:scheduler {}}
        (fn [api]
          (let [r (jobs! api :admin)]
            (is (= 500 (:status r)) (str "a bug is a 500 and not a 502 that blames somebody else: " (ex-message bug)))
            (is (= "An unexpected error occurred." (get-in r [:json :detail])))))))))

(deftest without-an-id-of-the-request-none-is-sent
  ;; the handler on its own, as if nothing had given the request an id
  (with-scheduler the-jobs
    (fn [_ service _]
      (let [scheduler (http/client {:base-url (:url service)
                                    :token (first (the-apis-token))})]
        (try
          (is (= 200 (:status ((api-jobs/handler {:scheduler scheduler}) {}))))
          (is (not (contains? (:headers (last @(:requests service))) "x-request-id")))
          (is (= 200 (:status ((api-jobs/handler {:scheduler scheduler}) {:request-id "req-1"}))))
          (is (= "req-1" (get-in (last @(:requests service)) [:headers "x-request-id"])))
          (finally (http/close! scheduler)))))))

;; ---------------------------------------------------------------------------
;; The document

(deftest the-document-of-the-api-describes-the-operation
  ;; with string keys, since a path is not a good keyword
  (with-scheduler the-jobs
    (fn [api _ _]
      (let [doc (json/read-value (:text (call api :get "/openapi.json")))
            operation (get-in doc ["paths" "/api/admin/jobs" "get"])]
        (is (= ["Administration"] (get operation "tags")))
        (is (some #{"Administration"} (map #(get % "name") (get doc "tags"))))
        (is (= {"clinic-admin" "all"} (get operation "x-pulse-access")))
        (is (str/includes? (get operation "description") "`clinic-admin`"))
        (is (str/includes? (get-in operation ["responses" "5XX" "$ref"]) "ServerError"))
        (testing "the answer is described, field by field"
          (let [schema (get-in operation ["responses" "200" "content" "application/json" "schema"])]
            (is (= #{"total" "items"} (set (keys (get schema "properties")))))
            (is (= "How many jobs the scheduler has." (get-in schema ["properties" "total" "description"])))))))))
