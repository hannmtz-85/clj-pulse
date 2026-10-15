(ns pulse.platform.http.client-test
  "The client of another service, against a stand-in server: what goes over the wire, what comes
  back, and the three ways to fail."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.client :as client]
            [pulse.platform.test.clock :as clock]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]))

(def ^:private the-token "the-access-token-of-the-caller")

(defn- keeper
  "A keeper of a token, on a clock of its own: `[keeper clock fetches]`."
  []
  (let [c (clock/clock "2026-10-15T10:00:00Z")
        fetches (atom 0)]
    [(token/source {:fetch (fn [] (swap! fetches inc) {:access-token (str the-token "-" @fetches) :expires-in 300})
                    :clock (:clock c)
                    :client-id "pulse-test"})
     c
     fetches]))

(defn- json-text [x] (json/write-value-as-string x))

(defn- with-service
  "Calls `(f client stub fetches clock)` with a client of a stand-in service that answers as
  `handler` says."
  [handler f & {:as options}]
  (let [service (stub/start! handler)
        [own-token c fetches] (keeper)]
    (try
      (f (client/client (merge {:base-url (:url service) :token own-token} options))
         service fetches c)
      (finally (stub/stop! service)))))

(defn- failure [f]
  (try (f)
       (is false "nothing was thrown")
       (catch clojure.lang.ExceptionInfo e e)))

(defn- said
  "Everything a failure says, to look through it."
  [e]
  (str/join "\n" (map (fn [x] (str (ex-message x) " " (pr-str (ex-data x))))
                      (take-while some? (iterate ex-cause e)))))

;; ---------------------------------------------------------------------------
;; The wire

(deftest a-get-carries-the-token-and-returns-the-body-as-data
  (with-service (fn [_] {:body (json-text {:total 2 :items [{:id "pt-ana" :identifier "MRN-1"}]})})
    (fn [c service _ _]
      (is (= {:total 2 :items [{:id "pt-ana" :identifier "MRN-1"}]}
             (client/get-json c "/api/patients" {:limit 100 :offset 0})))
      (let [[request :as requests] @(:requests service)]
        (is (= 1 (count requests)))
        (is (= "GET" (:method request)))
        (is (= "/api/patients" (:path request)))
        (is (= "limit=100&offset=0" (:query request)))
        (is (= (str "Bearer " the-token "-1") (get-in request [:headers "authorization"])))
        (is (= "application/json" (get-in request [:headers "accept"])))))))

(deftest a-post-sends-its-body-as-json
  (with-service (fn [_] {:status 202 :body (json-text {:id "evaluate-rules" :state "running"})})
    (fn [c service _ _]
      (is (= {:id "evaluate-rules" :state "running"}
             (client/post-json c "/jobs/evaluate-rules/run" {:reason "a test" :n 3})))
      (let [request (first @(:requests service))]
        (is (= "POST" (:method request)))
        (is (= {:reason "a test" :n 3} (json/read-value (:body request) json/keyword-keys-object-mapper)))
        (is (= "application/json" (get-in request [:headers "content-type"])))))))

(deftest a-post-without-a-body-sends-none
  (with-service (fn [_] {:status 202 :body (json-text {:ok true})})
    (fn [c service _ _]
      (is (= {:ok true} (client/post-json c "/jobs/evaluate-rules/run")))
      (is (= "" (:body (first @(:requests service)))))
      (is (nil? (get-in (first @(:requests service)) [:headers "content-type"]))))))

(deftest a-body-that-is-false-is-still-a-body
  (with-service (fn [_] {:status 202 :body (json-text {:ok true})})
    (fn [c service _ _]
      (is (= {:ok true} (client/post-json c "/jobs/evaluate-rules/run" false)))
      (is (= "false" (:body (first @(:requests service)))))
      (is (= "application/json" (get-in (first @(:requests service)) [:headers "content-type"]))))))

(deftest a-trailing-slash-in-the-base-url-is-dropped
  (let [service (stub/start! (fn [_] {:body (json-text {})}))
        [own-token _ _] (keeper)]
    (try
      (let [c (client/client {:base-url (str (:url service) "///") :token own-token})]
        (client/get-json c "/healthz")
        (is (= "/healthz" (:path (first @(:requests service))))))
      (finally (stub/stop! service)))))

(deftest what-is-in-a-query-is-encoded
  (is (nil? (client/query-string {})))
  (is (nil? (client/query-string nil)))
  (is (= "a=1&b=x" (client/query-string {:a 1 "b" "x"})))
  (is (= "a=1" (client/query-string {:a 1 :b nil})) "a nil leaves the parameter out")
  (is (= "from=2026-10-15T10%3A00%3A00%2B02%3A00" (client/query-string {:from "2026-10-15T10:00:00+02:00"}))
      "a plus in a value is a plus and not a space")
  (is (= "q=a+b%26c%3Dd" (client/query-string {:q "a b&c=d"})))
  (is (= "a%26b=1&c+d=2" (client/query-string {"a&b" 1 "c d" 2})) "a name is encoded as a value is")
  (is (= "s=a&s=b" (client/query-string {:s ["a" "b"]})) "a list repeats the parameter"))

(deftest a-query-reaches-the-service-as-it-was-meant
  (with-service (fn [_] {:body (json-text {})})
    (fn [c service _ _]
      (client/get-json c "/api/patients/pt-ana/observations"
                       {:measurement "blood-pressure" :from "2026-10-15T10:00:00Z" :sort "date"})
      (is (= "measurement=blood-pressure&from=2026-10-15T10%3A00%3A00Z&sort=date"
             (:query (first @(:requests service))))))))

(deftest segments-of-a-path-are-encoded-one-by-one
  (is (= "/api/patients/pt-ana/observations" (client/path "api" "patients" "pt-ana" "observations")))
  (is (= "/api/patients/a%2Fb/x" (client/path "api" "patients" "a/b" "x")) "a slash in an id stays in its segment")
  (is (= "/api/patients/a%20b" (client/path "api" "patients" "a b")) "a space is %20 in a path")
  (is (= "/api/patients/a%2Bb" (client/path "api" "patients" "a+b")) "a plus is written out, not left to be read as a space")
  (is (= "/api/patients/%C3%A9" (client/path "api" "patients" "é"))))

;; ---------------------------------------------------------------------------
;; The token

(deftest the-token-is-asked-for-once-and-sent-every-time
  (with-service (fn [_] {:body (json-text {})})
    (fn [c service fetches clock]
      (client/get-json c "/a")
      (client/get-json c "/b")
      (is (= 1 @fetches))
      (is (= [(str "Bearer " the-token "-1")] (distinct (map #(get-in % [:headers "authorization"]) @(:requests service)))))
      (testing "and renewed when it is near its end"
        (clock/advance! clock 240000)
        (client/get-json c "/c")
        (is (= 2 @fetches))
        (is (= (str "Bearer " the-token "-2") (get-in (last @(:requests service)) [:headers "authorization"])))))))

(deftest without-a-token-nothing-is-sent
  (let [service (stub/start! (fn [_] {:body (json-text {})}))
        broken (token/source {:fetch (fn [] (throw (ex-info "refused" {:type ::token/unavailable :reason :rejected :status 401})))
                              :clock (:clock (clock/clock "2026-10-15T10:00:00Z"))})]
    (try
      (let [c (client/client {:base-url (:url service) :token broken})
            e (failure #(client/get-json c "/api/me"))]
        (is (= ::token/unavailable (:type (ex-data e))) "the failure of the token is the failure of the call")
        (is (= :rejected (:reason (ex-data e))))
        (is (empty? @(:requests service)) "and the service was not asked"))
      (finally (stub/stop! service)))))

(deftest the-token-is-in-no-failure
  (with-service (fn [_] {:status 403 :body (json-text {:title "Forbidden" :detail "You are not allowed to do this."})})
    (fn [c _ _ _]
      (let [e (failure #(client/get-json c "/api/admin/jobs"))]
        (is (not (str/includes? (said e) the-token)))))))

;; ---------------------------------------------------------------------------
;; What the service says

(deftest request-returns-the-status-of-any-answer
  (with-service (fn [_] {:status 404 :body (json-text {:title "Not Found"})})
    (fn [c _ _ _]
      (is (= {:status 404 :body {:title "Not Found"}}
             (client/request c {:method :get :path "/nope"}))))))

(deftest an-empty-answer-is-no-data
  (with-service (fn [_] {:status 204 :body ""})
    (fn [c _ _ _]
      (is (nil? (client/get-json c "/api/nothing")))
      (is (= {:status 204 :body nil} (client/request c {:method :get :path "/api/nothing"}))))))

(deftest an-answer-that-is-not-a-success-is-a-failure-with-its-problem
  (doseq [status [400 401 403 404 409 422 500 502 503]]
    (with-service (fn [_] {:status status
                           :headers {"Content-Type" "application/problem+json"}
                           :body (json-text {:type "about:blank" :title "A title" :detail "What went wrong." :status status
                                             :instance "/api/x" :requestId "req-1"})})
      (fn [c _ _ _]
        (let [e (failure #(client/get-json c "/api/x"))]
          (is (= {:type ::client/failed :status status :path "/api/x"
                  :problem {:title "A title" :detail "What went wrong."}}
                 (ex-data e)) (str status))
          (is (= (str "GET /api/x answered " status) (ex-message e))))))))

(deftest only-a-2xx-answer-is-a-success
  ;; `request` is replaced to give any status: a stand-in server cannot send a 1xx as its answer
  (doseq [status [100 199 300 302 304 399 400 500]]
    (with-redefs [client/request (fn [_ _] {:status status :body {:ok true}})]
      (is (= {:type ::client/failed :status status :path "/x"} (ex-data (failure #(client/get-json {} "/x"))))
          (str status))))
  (doseq [status [200 201 202 204 299]]
    (with-redefs [client/request (fn [_ _] {:status status :body {:ok true}})]
      (is (= {:ok true} (client/get-json {} "/x")) (str status)))))

(deftest a-problem-that-is-too-long-is-cut
  (with-service (fn [_] {:status 500 :body (json-text {:title "Boom" :detail (apply str (repeat 1000 "x"))})})
    (fn [c _ _ _]
      (let [e (failure #(client/get-json c "/api/x"))]
        (is (< (count (get-in (ex-data e) [:problem :detail])) 250))
        (is (str/ends-with? (get-in (ex-data e) [:problem :detail]) "..."))))))

(deftest a-problem-is-cut-after-two-hundred-characters-and-not-before
  (doseq [[n expected] [[199 (apply str (repeat 199 "x"))]
                        [200 (apply str (repeat 200 "x"))]
                        [201 (str (apply str (repeat 200 "x")) "...")]
                        [1000 (str (apply str (repeat 200 "x")) "...")]]]
    (with-service (fn [_] {:status 500 :body (json-text {:title "Boom" :detail (apply str (repeat n "x"))})})
      (fn [c _ _ _]
        (let [e (failure #(client/get-json c "/api/x"))]
          (is (= expected (get-in (ex-data e) [:problem :detail])) (str n " characters")))))))

(deftest what-the-service-says-without-a-problem-is-not-repeated
  (doseq [body ["<html>Bad gateway: host db-7.internal, password hunter2</html>" "" "[1,2]" "{\"error\":42}"]]
    (with-service (fn [_] {:status 502 :body body})
      (fn [c _ _ _]
        (let [e (failure #(client/get-json c "/api/x"))]
          (is (= {:type ::client/failed :status 502 :path "/api/x"} (ex-data e)) body)
          (is (not (str/includes? (said e) "hunter2"))))))))

(deftest a-success-that-is-not-json-is-a-failure
  (with-service (fn [_] {:status 200 :headers {"Content-Type" "text/html"} :body "<html>login</html>"})
    (fn [c _ _ _]
      (let [e (failure #(client/get-json c "/api/x"))]
        (is (= {:type ::client/failed :status 200 :path "/api/x" :reason :bad-response} (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; A service that is not there

(deftest a-service-that-is-not-listening-cannot-be-reached
  (let [service (stub/start! (fn [_] {}))
        url (:url service)
        [own-token _ _] (keeper)]
    (stub/stop! service)
    (let [c (client/client {:base-url url :token own-token})
          e (failure #(client/get-json c "/api/me"))]
      (is (= {:type ::client/unreachable :reason :connect :path "/api/me"} (ex-data e)))
      (is (some? (ex-cause e))))))

(deftest a-service-that-is-slow-is-given-up-on
  (with-service (fn [_] {:delay-ms 1500 :body (json-text {})})
    (fn [c _ _ _]
      (let [started (System/nanoTime)
            e (failure #(client/get-json c "/api/me"))]
        (is (= {:type ::client/unreachable :reason :timeout :path "/api/me"} (ex-data e)))
        (is (< (/ (- (System/nanoTime) started) 1e6) 1200) "without waiting for the answer")))
    :timeout-ms 200))

(deftest a-client-that-is-closed-sends-nothing-more
  (with-service (fn [_] {:body (json-text {:ok true})})
    (fn [c service _ _]
      (is (= {:ok true} (client/get-json c "/api/me")))
      (client/close! c)
      (is (some? (failure #(client/get-json c "/api/me"))))
      (is (= 1 (count @(:requests service))) "the second request never left"))))
