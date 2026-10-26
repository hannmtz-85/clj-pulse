(ns pulse.spa.http-api-test
  "The calls of the workspace are made to be taken by the api (ADR-0029, point 4): what the workspace writes in its requests is what
  the api reads. These are on the JVM only, since the api is."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.platform.http.cors :as cors]
            [pulse.platform.http.middleware :as middleware]
            [pulse.spa.http :as http]
            [ring.util.codec :as codec]))

(deftest the-api-takes-the-id-of-a-request-as-the-workspace-writes-it
  (doseq [id (repeatedly 100 http/request-id)]
    (is (= id (middleware/request-id {:headers {"x-request-id" id}}))
        "the api keeps it in its log and in its answer: a UUID is not replaced by one of its own")))

(deftest the-api-reads-an-address-as-the-workspace-wrote-it
  (testing "a path"
    (doseq [id ["pt-ana" "a/b c" "García" "x+y" "%2F" "a?b#c" "ünï©ødé €"]]
      (let [segments (rest (re-seq #"[^/]+" (http/path ["api" "patients" id "summary"])))]
        (is (= ["patients" id "summary"] (map codec/url-decode segments))
            (str "an id that is " (pr-str id) " is one segment, and the api reads it as it was")))))
  (testing "a query, in which a plus is what an offset has, and not a space"
    (let [query (http/query-string {:from "2026-10-13T12:00:00+02:00" :q "García López" :measurement :heart-rate})]
      (is (= {"from" "2026-10-13T12:00:00+02:00" "q" "García López" "measurement" "heart-rate"}
             (codec/form-decode query))))))

(deftest the-api-lets-the-workspace-make-its-calls-from-its-origin
  (testing "the headers the workspace sends are among those that the api allows, and the methods too"
    (is (every? (set (map #(.toLowerCase ^String %) (:allowed-headers cors/defaults))) ["authorization" "x-request-id"]))
    (is (contains? (set (:allowed-headers cors/defaults)) "Content-Type"))
    (is (every? (set (:allowed-methods cors/defaults)) ["GET" "POST" "PATCH"]))
    (is (contains? (set (:exposed-headers cors/defaults)) "X-Request-Id"))))
