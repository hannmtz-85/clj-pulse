(ns pulse.fhir.client.http-test
  "The HTTP client against a local server that can be made slow or absent, so that what happens
  when the FHIR server misbehaves is tested without HAPI."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.http :as http]
            [pulse.fhir.resource :as r])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.net.http HttpTimeoutException)
           (java.nio.charset StandardCharsets)))

(def ^:private patient-json "{\"resourceType\":\"Patient\",\"id\":\"pt-x\"}")

(defn- start-server!
  "A local server that waits `delay-ms` and then answers 200 with a Patient, whatever it is asked.
  Returns {:server s :url base-url}."
  [delay-ms]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              payload (.getBytes ^String patient-json StandardCharsets/UTF_8)]
                          (try
                            (Thread/sleep (long delay-ms))
                            (.add (.getResponseHeaders ex) "Content-Type" "application/fhir+json")
                            (.sendResponseHeaders ex 200 (long (alength payload)))
                            (with-open [out (.getResponseBody ex)] (.write out payload))
                            (catch Exception _ nil)
                            (finally (.close ex)))))))
    (.start server)
    {:server server :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/fhir")}))

(defn- with-server [delay-ms f]
  (let [{:keys [server url]} (start-server! delay-ms)]
    (try (f url)
         (finally (.stop ^HttpServer server 0)))))

(defn- elapsed-ms [f]
  (let [started (System/nanoTime)]
    (try (f) (catch Throwable _ nil))
    (/ (- (System/nanoTime) started) 1e6)))

(deftest a-server-that-answers-in-time-is-read-normally
  (with-server 50
    (fn [url]
      (let [client (http/client {:base-url url :timeout-ms 2000})]
        (is (= "pt-x" (:id (c/read-resource client "Patient" "pt-x"))))))))

(deftest a-slow-server-is-abandoned-after-the-timeout
  (with-server 2000
    (fn [url]
      (let [client (http/client {:base-url url :timeout-ms 200})]
        (is (thrown? HttpTimeoutException (c/read-resource client "Patient" "pt-x")))
        (is (< (elapsed-ms #(c/read-resource client "Patient" "pt-x")) 1000)
            "it does not wait for the server to finish")))))

(deftest every-operation-has-the-timeout
  (with-server 2000
    (fn [url]
      (let [client (http/client {:base-url url :timeout-ms 200})
            patient (r/patient {:id "pt-x" :given "A" :family "B" :gender "other" :birth-date "1990-01-01"})]
        (doseq [[label f] {"read" #(c/read-resource client "Patient" "pt-x")
                           "search" #(doall (c/search client "Patient" {:family "B"}))
                           "put" #(c/put! client patient)
                           "delete" #(c/delete! client "Patient" "pt-x")
                           "transaction" #(c/transact! client {:resourceType "Bundle" :type "transaction" :entry []})
                           "validate" #(c/validate client patient)}]
          (testing label
            (is (thrown? HttpTimeoutException (f)))))))))

(deftest the-timeout-is-per-request
  (testing "each page of a search has its own, so a long search is not cut short by a total"
    (with-server 120
      (fn [url]
        (let [client (http/client {:base-url url :timeout-ms 1000})]
          (is (some? (c/read-resource client "Patient" "pt-x")))
          (is (some? (c/read-resource client "Patient" "pt-x")))
          (is (some? (c/read-resource client "Patient" "pt-x"))))))))

(deftest a-refused-connection-fails-at-once
  (let [{:keys [server url]} (start-server! 0)]
    (.stop ^HttpServer server 0)
    (let [client (http/client {:base-url url})]
      (is (thrown? java.io.IOException (c/read-resource client "Patient" "pt-x")))
      (is (< (elapsed-ms #(c/read-resource client "Patient" "pt-x")) 2000)))))

(deftest the-defaults
  (is (= 10000 (:timeout-ms (http/client {:base-url "http://localhost:8090/fhir"}))))
  (is (= 500 (:timeout-ms (http/client {:base-url "http://localhost:8090/fhir" :timeout-ms 500}))))
  (is (true? (:no-cache? (http/client {:base-url "http://localhost:8090/fhir"}))))
  (testing "a trailing slash on the base URL is dropped"
    (is (= "http://localhost:8090/fhir" (:base-url (http/client {:base-url "http://localhost:8090/fhir///"}))))))
