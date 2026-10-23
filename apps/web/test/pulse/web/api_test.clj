(ns pulse.web.api-test
  "The portal's calls to the api for the person who is signed in (ADR-0028): their own token and the id of the request go with
  each, and what goes wrong is told as what it is. Against a stand-in for the api and the keeper of a session."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.client :as http]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]
            [pulse.web.api :as api])
  (:import (java.time Clock Instant)))

(defn- json-ok [body]
  {:status 200 :body (json/write-value-as-string body)})

(defn- with-api
  "Calls `(f api requests)` with the portal's client of a stand-in that answers with `answer` (a response, or a function of the
  request), which has no token of its own, and the requests the stand-in got."
  [answer f]
  (let [server (stub/start! (fn [request] (if (map? answer) answer (answer request))))
        client (http/client {:base-url (:url server) :timeout-ms 500})]
    (try
      (f client (:requests server))
      (finally
        (http/close! client)
        (stub/stop! server)))))

(defn- a-keeper
  "The keeper of the tokens of a session, which gives the token `access`."
  [access]
  (token/source {:fetch (fn [] {:access-token access :expires-in 300})
                 :clock (Clock/systemUTC)
                 :client-id "pulse-web"
                 :initial {:access-token access :expires-in 300 :asked-at (Instant/now)}}))

(defn- request-of [& {:as fields}]
  (merge {:request-method :get :uri "/" :headers {}
          :session {:tokens (a-keeper "the-token-of-ana")}
          :request-id "visit-42"}
         fields))

(defn- failure-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

;; ---------------------------------------------------------------------------
;; A call

(deftest a-call-carries-the-token-of-the-person-and-the-id-of-the-request
  (with-api (json-ok {:subject "x"})
    (fn [client requests]
      (is (= {:subject "x"} (api/get-json client (request-of) "/api/me")))
      (let [[sent] @requests]
        (is (= "GET" (:method sent)))
        (is (= "/api/me" (:path sent)))
        (is (= "Bearer the-token-of-ana" (get-in sent [:headers "authorization"])))
        (is (= "visit-42" (get-in sent [:headers "x-request-id"])))
        (is (= "application/json" (get-in sent [:headers "accept"])))))))

(deftest each-person-is-asked-for-with-their-own-token
  (with-api (json-ok {})
    (fn [client requests]
      (api/get-json client (request-of :session {:tokens (a-keeper "ana")}) "/api/me")
      (api/get-json client (request-of :session {:tokens (a-keeper "noah")}) "/api/me")
      (is (= ["Bearer ana" "Bearer noah"] (map #(get-in % [:headers "authorization"]) @requests)))
      (testing "and the client of the portal is not changed by it, so the next person is not given the last one's"
        (is (nil? (:token client)))))))

(deftest a-call-has-a-query-that-is-encoded
  (with-api (json-ok {:items []})
    (fn [client requests]
      (api/get-json client (request-of) "/api/patients/pt-ana/observations" {:measurement "blood-pressure" :limit 5})
      (api/get-json client (request-of) "/api/x" {:q "a b&c" :from "2026-10-13T08:00:00+02:00"})
      (is (= ["measurement=blood-pressure&limit=5" "q=a+b%26c&from=2026-10-13T08%3A00%3A00%2B02%3A00"] (map :query @requests)))
      (testing "and no query is no question mark"
        (api/get-json client (request-of) "/api/y")
        (is (nil? (:query (last @requests))))))))

(deftest a-request-with-no-id-sends-none
  (with-api (json-ok {})
    (fn [client requests]
      (api/get-json client (request-of :request-id nil) "/api/me")
      (is (not (contains? (:headers (first @requests)) "x-request-id"))))))

(deftest a-person-with-no-keeper-sends-no-token
  ;; the session of a request that has no person in it: the call must not send somebody else's, or none that looks like one
  (with-api (json-ok {})
    (fn [client requests]
      (api/get-json client (request-of :session {}) "/api/me")
      (is (not (contains? (:headers (first @requests)) "authorization"))))))

;; ---------------------------------------------------------------------------
;; What goes wrong

(deftest a-failure-is-what-the-platform-says-it-is
  (testing "the api answered with a failure"
    (with-api {:status 503 :body (json/write-value-as-string {:title "Service Unavailable" :detail "FHIR is slow."})}
      (fn [client _]
        (let [e (failure-of #(api/get-json client (request-of) "/api/patients/pt-ana/summary"))]
          (is (= ::http/failed (:type (ex-data e))))
          (is (= 503 (:status (ex-data e))))
          (is (= "/api/patients/pt-ana/summary" (:path (ex-data e))))
          (is (= {:title "Service Unavailable" :detail "FHIR is slow."} (:problem (ex-data e))))
          (is (true? (api/failure? e)))))))
  (testing "the api answered with what is not JSON"
    (with-api {:status 200 :body "<html>oops</html>"}
      (fn [client _]
        (let [e (failure-of #(api/get-json client (request-of) "/api/me"))]
          (is (= :bad-response (:reason (ex-data e))))
          (is (true? (api/failure? e)))))))
  (testing "the api was too slow"
    (with-api (fn [_] {:status 200 :body "{}" :delay-ms 1500})
      (fn [client _]
        (let [e (failure-of #(api/get-json client (request-of) "/api/me"))]
          (is (= {:type ::http/unreachable :reason :timeout} (select-keys (ex-data e) [:type :reason])))
          (is (true? (api/failure? e)))))))
  (testing "the api was not there"
    (let [client (http/client {:base-url "http://127.0.0.1:1" :timeout-ms 500 :connect-timeout-ms 500})
          e (try (api/get-json client (request-of) "/api/me") nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:type ::http/unreachable :reason :connect} (select-keys (ex-data e) [:type :reason])))
      (is (true? (api/failure? e)))
      (http/close! client))))

(deftest what-is-not-a-call-to-the-api-that-failed-is-not-a-failure-of-the-api
  (doseq [x [(ex-info "x" {:type :pulse.platform.token/unavailable :reason :rejected})
             (ex-info "x" {:type :something-else})
             (ex-info "x" {})
             (RuntimeException. "x")
             nil]]
    (is (false? (api/failure? x)) (pr-str x))
    (is (false? (api/timeout? x)) (pr-str x))
    (is (false? (api/degradable? x)) (pr-str x))))

(defn- failed [status & {:as more}]
  (ex-info "failed" (merge {:type ::http/failed :status status :path "/api/me"} more)))

(defn- unreachable [reason]
  (ex-info "unreachable" {:type ::http/unreachable :reason reason :path "/api/me"}))

(deftest a-failure-that-a-page-can-go-on-without-is-the-api-failing-on-its-side-or-not-being-there
  (testing "it could not be reached, or was too slow"
    (is (true? (api/degradable? (unreachable :connect))))
    (is (true? (api/degradable? (unreachable :timeout)))))
  (testing "it failed on its own side, or said what is not JSON"
    (doseq [status [500 501 502 503 504 599]]
      (is (true? (api/degradable? (failed status))) (str status)))
    (is (true? (api/degradable? (failed 200 :reason :bad-response)))))
  (testing "anything else is a failure of the whole page: nothing of it is to be trusted"
    (doseq [status [400 401 403 404 409 422 429]]
      (is (false? (api/degradable? (failed status))) (str status)))
    (testing "a status the api never gave, or none"
      (is (false? (api/degradable? (failed nil))))
      (is (false? (api/degradable? (failed 302)))))))

(deftest a-timeout-is-the-client-giving-up-or-the-api-saying-that-something-it-asked-did
  (is (true? (api/timeout? (unreachable :timeout))))
  (is (true? (api/timeout? (failed 504))))
  (is (false? (api/timeout? (unreachable :connect))))
  (doseq [status [400 401 403 404 500 502 503]]
    (is (false? (api/timeout? (failed status))) (str status))))
