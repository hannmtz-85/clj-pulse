(ns pulse.platform.http.headers-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.platform.http.headers :as headers]))

(def ^:private defaults
  {"X-Content-Type-Options" "nosniff"
   "Cache-Control" "no-store"
   "Content-Security-Policy" "default-src 'none'"})

(defn- serving [response]
  (headers/wrap-security-headers (constantly response)
                                 {:headers defaults
                                  :rules [{:prefix "/docs" :headers {"Content-Security-Policy" "default-src 'self'"
                                                                     "Cache-Control" nil}}]}))

(defn- call [handler uri] (handler {:request-method :get :uri uri}))

(deftest every-response-gets-the-defaults
  (testing "whatever it is, and whatever made it"
    (doseq [response [{:status 200 :headers {"Content-Type" "application/json"} :body "{}"}
                      {:status 404 :headers {} :body "nope"}
                      {:status 500}
                      {:status 204 :headers nil}]]
      (let [r (call (serving response) "/api/me")]
        (is (= (:status response) (:status r)))
        (is (= "nosniff" (get-in r [:headers "X-Content-Type-Options"])) (pr-str response))
        (is (= "no-store" (get-in r [:headers "Cache-Control"])))
        (is (= "default-src 'none'" (get-in r [:headers "Content-Security-Policy"])))))))

(deftest the-response-is-otherwise-untouched
  (let [response {:status 200 :headers {"Content-Type" "application/json"} :body "{}"}
        r (call (serving response) "/api/me")]
    (is (= (:body response) (:body r)))
    (is (= "application/json" (get-in r [:headers "Content-Type"])))))

(deftest a-header-the-response-sets-itself-is-kept-whatever-its-case
  (let [r (call (serving {:status 200 :headers {"cache-control" "max-age=60"}}) "/api/me")]
    (is (= "max-age=60" (get-in r [:headers "cache-control"])))
    (is (not (contains? (:headers r) "Cache-Control")) "no second one")
    (is (= "nosniff" (get-in r [:headers "X-Content-Type-Options"])))))

(deftest a-rule-by-path-replaces-the-defaults-it-names
  (let [handler (serving {:status 200 :headers {}})]
    (doseq [uri ["/docs" "/docs/" "/docs/index.html" "/docs/a/b"]]
      (testing uri
        (let [r (call handler uri)]
          (is (= "default-src 'self'" (get-in r [:headers "Content-Security-Policy"])))
          (is (not (contains? (:headers r) "Cache-Control")) "nil removes a default")
          (is (= "nosniff" (get-in r [:headers "X-Content-Type-Options"])) "the others stay"))))
    (testing "by whole segments: these are not under /docs"
      (doseq [uri ["/docsx" "/api/docs" "/doc" "/" "/api/docs/x"]]
        (is (= "default-src 'none'" (get-in (call handler uri) [:headers "Content-Security-Policy"])) uri)
        (is (= "no-store" (get-in (call handler uri) [:headers "Cache-Control"])))))))

(deftest a-handler-that-answers-nothing-stays-nothing
  (is (nil? (call (headers/wrap-security-headers (constantly nil) {:headers defaults}) "/x"))))

(deftest header-names-are-compared-without-regard-to-case
  (is (= "Vary" (headers/header-key {"Vary" "Origin"} "vary")))
  (is (= "vary" (headers/header-key {"vary" "Origin"} "VARY")))
  (is (nil? (headers/header-key {"X-Other" "1"} "Vary")))
  (is (nil? (headers/header-key nil "Vary"))))

(deftest the-origin-of-an-address-is-its-scheme-host-and-port
  (is (= "http://localhost:8080" (headers/origin-of "http://localhost:8080/realms/pulse")))
  (is (= "https://id.example" (headers/origin-of "https://id.example/realms/pulse?x=1#top")))
  (is (= "http://[::1]:8080" (headers/origin-of "http://[::1]:8080/realms/pulse")))
  (is (nil? (headers/origin-of "not an address")))
  (is (nil? (headers/origin-of nil))))
