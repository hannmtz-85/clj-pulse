(ns pulse.web.api-test
  "The portal's calls to the api for the person who is signed in (ADR-0028): their own token and the id of the request go with
  each, and what goes wrong is told as what it is. Against a stand-in for the api and the keeper of a session."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.client :as http]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]
            [pulse.web.api :as api]
            [taoensso.telemere :as t])
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

;; ---------------------------------------------------------------------------
;; A reading that is posted

(deftest a-post-carries-the-token-of-the-person-the-id-of-the-request-and-the-body-as-json
  (with-api (json-ok {:id "obs-1"})
    (fn [client requests]
      (is (= {:created {:id "obs-1"}}
             (api/post-json client (request-of) "/api/patients/pt-ana/observations" {:measurement "heart-rate" :value 72})))
      (let [[sent] @requests]
        (is (= "POST" (:method sent)))
        (is (= "/api/patients/pt-ana/observations" (:path sent)))
        (is (= {:measurement "heart-rate" :value 72} (json/read-value (:body sent) json/keyword-keys-object-mapper)))
        (is (= "Bearer the-token-of-ana" (get-in sent [:headers "authorization"])))
        (is (= "visit-42" (get-in sent [:headers "x-request-id"])))
        (is (= "application/json" (get-in sent [:headers "content-type"])))))))

(deftest every-success-is-a-reading-that-was-created
  (doseq [status [200 201 202]]
    (with-api {:status status :body (json/write-value-as-string {:id "x"})}
      (fn [client _]
        (is (= {:created {:id "x"}} (api/post-json client (request-of) "/p" {})) (str status)))))
  (testing "even one with nothing to say"
    (with-api {:status 204 :body ""}
      (fn [client _]
        (is (= {:created nil} (api/post-json client (request-of) "/p" {})))))))

(deftest a-reading-the-api-will-not-take-comes-back-with-what-it-said-was-wrong
  (doseq [status [400 422]]
    (testing (str status)
      (with-api {:status status :body (json/write-value-as-string {:title "x" :status status :errors {:diastolic ["must be between 20 and 200 mmHg"]}})}
        (fn [client _]
          (is (= {:refused {:status status :errors {:diastolic ["must be between 20 and 200 mmHg"]}}}
                 (api/post-json client (request-of) "/p" {}))))))))

(deftest a-refusal-with-no-errors-is-a-refusal-with-none
  (with-api {:status 422 :body (json/write-value-as-string {:title "x"})}
    (fn [client _]
      (is (= {:refused {:status 422 :errors nil}} (api/post-json client (request-of) "/p" {})))))
  (testing "and so is one that is not JSON, or not an object"
    (doseq [body ["<html>no</html>" "[1,2]" "\"text\"" ""]]
      (with-api {:status 422 :body body}
        (fn [client _]
          (is (= {:refused {:status 422 :errors nil}} (api/post-json client (request-of) "/p" {})) (pr-str body)))))))

(deftest any-other-answer-to-a-post-is-a-failure-as-a-get-has-them
  (doseq [status [401 403 404 409 429 500 502 503 504]]
    (with-api {:status status :body (json/write-value-as-string {:title "Failed" :detail "It did." :errors {:x ["y"]}})}
      (fn [client _]
        (let [e (failure-of #(api/post-json client (request-of) "/api/patients/pt-ana/observations" {}))]
          (is (= {:type ::http/failed :status status :path "/api/patients/pt-ana/observations"}
                 (select-keys (ex-data e) [:type :status :path])) (str status))
          (is (= {:title "Failed" :detail "It did."} (:problem (ex-data e))) "and what it said, without the fields")
          (is (true? (api/failure? e))))))))

(deftest what-the-api-says-of-a-failure-is-cut-short
  (with-api {:status 500 :body (json/write-value-as-string {:title (apply str (repeat 500 "x")) :detail 5})}
    (fn [client _]
      (let [e (failure-of #(api/post-json client (request-of) "/p" {}))]
        (is (= {:title (str (apply str (repeat 200 "x")) "...")} (:problem (ex-data e))))))))

(deftest a-failure-with-no-problem-has-none
  (doseq [body ["" "oops" "[]" "null"]]
    (with-api {:status 500 :body body}
      (fn [client _]
        (let [e (failure-of #(api/post-json client (request-of) "/p" {}))]
          (is (= 500 (:status (ex-data e))) (pr-str body))
          (is (not (contains? (ex-data e) :problem)) (pr-str body)))))))

(deftest a-success-that-is-not-json-is-a-failure-and-not-a-reading-that-was-created
  (with-api {:status 201 :body "created"}
    (fn [client _]
      (let [e (failure-of #(api/post-json client (request-of) "/p" {}))]
        (is (= {:type ::http/failed :status 201 :reason :bad-response} (select-keys (ex-data e) [:type :status :reason])))
        (is (true? (api/degradable? e)))))))

(deftest a-post-that-cannot-be-made-is-the-failure-the-platform-says
  (testing "the api was too slow"
    (with-api (fn [_] {:status 201 :body "{}" :delay-ms 1500})
      (fn [client _]
        (is (= {:type ::http/unreachable :reason :timeout}
               (select-keys (ex-data (failure-of #(api/post-json client (request-of) "/p" {}))) [:type :reason]))))))
  (testing "the api was not there"
    (let [client (http/client {:base-url "http://127.0.0.1:1" :timeout-ms 500 :connect-timeout-ms 500})]
      (is (= {:type ::http/unreachable :reason :connect}
             (select-keys (ex-data (failure-of #(api/post-json client (request-of) "/p" {}))) [:type :reason])))
      (http/close! client))))

;; ---------------------------------------------------------------------------
;; Who the person is, and a part that is lost

(deftest the-patient-is-the-record-the-api-says-the-token-is-for
  (is (= "pt-ana" (api/patient-id {:fhirUser "Patient/pt-ana"})))
  (doseq [me [{} {:fhirUser nil} {:fhirUser ""} {:fhirUser "Practitioner/pr-rivera"} {:fhirUser "Patient"} {:fhirUser "Patient/"} nil {:fhirUser 5}]]
    (let [e (failure-of #(api/patient-id me))]
      (is (= ::api/no-patient-record (:type (ex-data e))) (pr-str me)))))

(deftest a-part-that-a-page-can-go-without-is-nil-and-in-the-log
  (let [{:keys [value signals]} (t/with-signals true
                                  (api/degrading "summary" #(throw (failed 503 :path "/api/patients/pt-ana/summary"))))]
    (is (nil? value))
    (is (= [:pulse.web/part-lost] (map :id signals)))
    (is (= {:part "summary" :status 503} (:data (first signals))) "and nothing else of what happened")
    (is (= :warn (:level (first signals)))))
  (testing "a part that was lost because the api was not there has the reason of that"
    (let [{:keys [value signals]} (t/with-signals true (api/degrading "series heart-rate" #(throw (unreachable :timeout))))]
      (is (nil? value))
      (is (= {:part "series heart-rate" :reason :timeout} (:data (first signals)))))))

(deftest a-part-that-loaded-is-what-it-is-and-nothing-is-logged
  (let [{:keys [value signals]} (t/with-signals true (api/degrading "summary" (constantly {:vitals {}})))]
    (is (= {:vitals {}} value))
    (is (empty? signals)))
  (testing "even when what it is is nothing"
    (let [{:keys [value signals]} (t/with-signals true (api/degrading "summary" (constantly nil)))]
      (is (nil? value))
      (is (empty? signals)))))

(deftest a-failure-a-page-cannot-go-on-after-is-thrown-from-the-part
  (doseq [e [(failed 403) (failed 404) (failed 401) (failed 422)
             (ex-info "x" {:type :pulse.platform.token/unavailable :reason :rejected})
             (ex-info "a bug" {})]]
    (let [{:keys [error signals]} (t/with-signals true (api/degrading "summary" #(throw e)))]
      (is (identical? e error) (pr-str (ex-data e)))
      (is (empty? signals) "and the part is not said to be lost: the whole page is"))))

;; ---------------------------------------------------------------------------
;; A post that has more to refuse for

(deftest a-post-can-say-which-other-statuses-are-the-api-refusing-and-not-failing
  (with-api {:status 409 :body (json/write-value-as-string {:title "Conflict" :detail "You have no doctor."})}
    (fn [client _]
      (testing "by default a 409 is a failure, as any other status that is not a success or a refusal of the shape"
        (is (= 409 (:status (ex-data (failure-of #(api/post-json client (request-of) "/p" {})))))))
      (testing "and when the caller says so it is a refusal, with no fields to say"
        (is (= {:refused {:status 409 :errors nil}} (api/post-json client (request-of) "/p" {} #{409}))))
      (testing "but only the statuses it said"
        (is (= 409 (:status (ex-data (failure-of #(api/post-json client (request-of) "/p" {} #{404 422})))))))))
  (testing "the refusals of the shape are always refusals"
    (doseq [status [400 422]]
      (with-api {:status status :body (json/write-value-as-string {:errors {:x ["y"]}})}
        (fn [client _]
          (is (= {:refused {:status status :errors {:x ["y"]}}} (api/post-json client (request-of) "/p" {} #{409})) (str status))))))
  (testing "and what is not one of them is a failure"
    (with-api {:status 404 :body (json/write-value-as-string {:title "Not Found"})}
      (fn [client _]
        (is (= 404 (:status (ex-data (failure-of #(api/post-json client (request-of) "/p" {} #{409}))))))))))

;; ---------------------------------------------------------------------------
;; A change

(deftest a-patch-carries-the-token-of-the-person-the-id-of-the-request-and-the-body-as-json
  (with-api (json-ok {:id "appt-ana-1" :status "cancelled"})
    (fn [client requests]
      (is (= {:changed {:id "appt-ana-1" :status "cancelled"}}
             (api/patch-json client (request-of) "/api/appointments/appt-ana-1" {:action "cancel" :reason "I had to travel."})))
      (let [[sent] @requests]
        (is (= "PATCH" (:method sent)))
        (is (= "/api/appointments/appt-ana-1" (:path sent)))
        (is (= {:action "cancel" :reason "I had to travel."} (json/read-value (:body sent) json/keyword-keys-object-mapper)))
        (is (= "Bearer the-token-of-ana" (get-in sent [:headers "authorization"])))
        (is (= "visit-42" (get-in sent [:headers "x-request-id"])))
        (is (= "application/json" (get-in sent [:headers "content-type"])))))))

(deftest every-success-of-a-patch-is-a-change
  (doseq [status [200 201 202]]
    (with-api {:status status :body (json/write-value-as-string {:id "x"})}
      (fn [client _]
        (is (= {:changed {:id "x"}} (api/patch-json client (request-of) "/p" {})) (str status)))))
  (testing "even one with nothing to say"
    (with-api {:status 204 :body ""}
      (fn [client _]
        (is (= {:changed nil} (api/patch-json client (request-of) "/p" {})))))))

(deftest what-the-api-will-not-do-to-a-patch-comes-back-as-what-it-said
  (doseq [status [400 404 409 422]]
    (testing (str status)
      (with-api {:status status :body (json/write-value-as-string {:title "x" :status status :errors {:reason ["must be 1 to 200 characters"]}})}
        (fn [client _]
          (is (= {:refused {:status status :errors {:reason ["must be 1 to 200 characters"]}}}
                 (api/patch-json client (request-of) "/p" {}))))))
    (testing (str status " with no fields to say")
      (with-api {:status status :body "<html>no</html>"}
        (fn [client _]
          (is (= {:refused {:status status :errors nil}} (api/patch-json client (request-of) "/p" {}))))))))

(deftest any-other-answer-to-a-patch-is-a-failure-as-a-get-has-them
  (doseq [status [401 403 405 429 500 502 503 504]]
    (with-api {:status status :body (json/write-value-as-string {:title "Failed" :detail "It did." :errors {:x ["y"]}})}
      (fn [client _]
        (let [e (failure-of #(api/patch-json client (request-of) "/api/appointments/appt-ana-1" {}))]
          (is (= {:type ::http/failed :status status :path "/api/appointments/appt-ana-1"} (select-keys (ex-data e) [:type :status :path])) (str status))
          (is (= "PATCH /api/appointments/appt-ana-1 answered " (subs (ex-message e) 0 (count "PATCH /api/appointments/appt-ana-1 answered "))) "it says what it was")
          (is (= {:title "Failed" :detail "It did."} (:problem (ex-data e))) "and what it said, without the fields")
          (is (true? (api/failure? e))))))))

(deftest a-success-of-a-patch-that-is-not-json-is-a-failure-and-not-a-change
  (with-api {:status 200 :body "done"}
    (fn [client _]
      (let [e (failure-of #(api/patch-json client (request-of) "/p" {}))]
        (is (= {:type ::http/failed :status 200 :reason :bad-response} (select-keys (ex-data e) [:type :status :reason])))
        (is (str/starts-with? (ex-message e) "PATCH /p answered 200"))
        (is (true? (api/degradable? e)))))))

(deftest a-patch-that-cannot-be-made-is-the-failure-the-platform-says
  (testing "the api was too slow"
    (with-api (fn [_] {:status 200 :body "{}" :delay-ms 1500})
      (fn [client _]
        (is (= {:type ::http/unreachable :reason :timeout}
               (select-keys (ex-data (failure-of #(api/patch-json client (request-of) "/p" {}))) [:type :reason]))))))
  (testing "the api was not there"
    (let [client (http/client {:base-url "http://127.0.0.1:1" :timeout-ms 500 :connect-timeout-ms 500})]
      (is (= {:type ::http/unreachable :reason :connect}
             (select-keys (ex-data (failure-of #(api/patch-json client (request-of) "/p" {}))) [:type :reason])))
      (http/close! client))))

(deftest a-failure-of-a-post-says-it-was-a-post
  (with-api {:status 500 :body ""}
    (fn [client _]
      (is (str/starts-with? (ex-message (failure-of #(api/post-json client (request-of) "/p" {}))) "POST /p answered 500")))))
