(ns pulse.platform.http.problem-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.problem :as problem]))

(defn- body [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

(deftest a-problem-is-rfc-9457-json
  (let [response (problem/response 404 {:detail "No such patient."
                                        :request {:uri "/api/patients/x" :request-id "req-9"}})]
    (is (= 404 (:status response)))
    (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))
    (is (string? (:body response)) "encoded here, so no content negotiation can change it")
    (is (= {:type "about:blank"
            :title "Not Found"
            :status 404
            :detail "No such patient."
            :instance "/api/patients/x"
            :requestId "req-9"}
           (body response)))))

(deftest optional-members-are-left-out-when-there-is-nothing-to-say
  (is (= {:type "about:blank" :title "Forbidden" :status 403}
         (body (problem/response 403 {})))))

(deftest headers-and-extensions-are-added
  (let [response (problem/response 405 {:headers {"Allow" "GET"}
                                        :extra {:errors {:n ["should be an integer"]}}})]
    (is (= "GET" (get-in response [:headers "Allow"])))
    (is (= "application/problem+json" (get-in response [:headers "Content-Type"])))
    (is (= {:n ["should be an integer"]} (:errors (body response))))))

(deftest every-status-the-api-uses-has-a-title
  (doseq [status [400 401 403 404 405 406 500 502 503]]
    (is (string? (get problem/titles status)) (str status)))
  (testing "an unknown status still gets a title"
    (is (= "Error" (:title (body (problem/response 418 {})))))))

(deftest ex-carries-what-the-exception-middleware-needs
  (let [cause (RuntimeException. "inner")
        e (problem/ex 502 "Upstream is down." {:extra {:retry true} :headers {"Retry-After" "5"} :cause cause})]
    (is (= {:type ::problem/problem :status 502 :detail "Upstream is down."
            :extra {:retry true} :headers {"Retry-After" "5"}}
           (ex-data e)))
    (is (= "Upstream is down." (ex-message e)))
    (is (identical? cause (ex-cause e)))))
