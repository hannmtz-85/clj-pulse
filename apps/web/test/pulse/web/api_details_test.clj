(ns pulse.web.api-details-test
  "What a mutation analysis of the portal's calls to the api found that the first tests did not hold: the edges of what is a success and of
  how much of what the api says is kept, and the words of the failures."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.client :as http]
            [pulse.platform.test.stub :as stub]
            [pulse.platform.token :as token]
            [pulse.web.api :as api])
  (:import (java.time Clock Instant)))

(defn- with-api [answer f]
  (let [server (stub/start! (fn [_] answer))
        client (http/client {:base-url (:url server) :timeout-ms 500})]
    (try (f client)
         (finally (http/close! client) (stub/stop! server)))))

(defn- request []
  {:request-method :post :uri "/" :headers {} :request-id "visit-1"
   :session {:tokens (token/source {:fetch (fn [] {:access-token "t" :expires-in 300})
                                    :clock (Clock/systemUTC)
                                    :client-id "pulse-web"
                                    :initial {:access-token "t" :expires-in 300 :asked-at (Instant/now)}})}})

(defn- failure-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest what-the-api-says-of-a-failure-is-kept-up-to-200-characters-and-no-more
  (doseq [[length expected] [[199 199] [200 200] [201 203]]]
    (with-api {:status 500 :body (json/write-value-as-string {:title (apply str (repeat length "x"))})}
      (fn [client]
        (let [e (failure-of #(api/post-json client (request) "/p" {}))]
          (is (= expected (count (get-in (ex-data e) [:problem :title]))) (str length)))))))

(deftest what-the-api-says-of-a-failure-is-kept-only-when-it-is-a-title-or-a-detail
  (doseq [body [{:foo 1} {:errors {:x ["y"]}} {} {:title nil :detail 5}]]
    (with-api {:status 500 :body (json/write-value-as-string body)}
      (fn [client]
        (let [e (failure-of #(api/post-json client (request) "/p" {}))]
          (is (not (contains? (ex-data e) :problem)) (pr-str body))))))
  (testing "and a detail alone is kept"
    (with-api {:status 500 :body (json/write-value-as-string {:detail "It broke."})}
      (fn [client]
        (is (= {:detail "It broke."} (:problem (ex-data (failure-of #(api/post-json client (request) "/p" {}))))))))))

(deftest a-success-is-any-status-from-200-to-299
  (doseq [status [200 201 299]]
    (testing (str status)
      (with-api {:status status :body "<html>not json</html>"}
        (fn [client]
          (let [e (failure-of #(api/post-json client (request) "/p" {}))]
            (is (= {:type ::http/failed :status status :reason :bad-response} (select-keys (ex-data e) [:type :status :reason])) "when it is not JSON"))))))
  (doseq [status [300 301]]
    (testing (str status)
      (with-api {:status status :body "{\"id\":\"x\"}"}
        (fn [client]
          (let [e (failure-of #(api/post-json client (request) "/p" {}))]
            (is (= {:type ::http/failed :status status} (select-keys (ex-data e) [:type :status])) "it is not a success")))))))

(deftest a-failure-says-what-it-is-in-words
  (with-api {:status 503 :body "oops"}
    (fn [client]
      (is (= "POST /api/patients/pt-ana/observations answered 503"
             (ex-message (failure-of #(api/post-json client (request) "/api/patients/pt-ana/observations" {})))))))
  (with-api {:status 200 :body "<html>oops</html>"}
    (fn [client]
      (is (= "POST /p answered 200 with a body that is not JSON" (ex-message (failure-of #(api/post-json client (request) "/p" {})))))))
  (is (= "The api knows no record of this patient" (ex-message (failure-of #(api/patient-id {:fhirUser "Practitioner/pr-rivera"}))))))
