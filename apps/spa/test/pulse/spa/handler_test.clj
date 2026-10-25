(ns pulse.spa.handler-test
  "The server of the workspace's release through its whole handler (ADR-0029, point 9): the policy is on every response, the answers
  of the files, the health probe, the request id, and what a failure looks like."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [pulse.spa.handler :as handler]
            [pulse.spa.security :as security]
            [pulse.spa.files :as files]
            [taoensso.telemere :as t])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.time.format DateTimeFormatter)))

(def ^:dynamic *root* nil)

(defn- with-release [f]
  (let [root (.toFile (Files/createTempDirectory "spa-handler-" (make-array FileAttribute 0)))]
    (doseq [[path content] {"index.html" "<!DOCTYPE html><title>Pulse</title>"
                            "js/main.77BB12C92D2AE8EA.js" "console.log(1)"
                            "pulse.css" "body{}"}]
      (let [file (io/file root path)]
        (io/make-parents file)
        (spit file content)))
    (binding [*root* (str root)]
      (f))))

(use-fixtures :once with-release)

(def ^:private settings {:api-base-url "http://localhost:4000" :issuer "http://localhost:8080/realms/pulse"})

(defn- app
  ([] (app nil))
  ([options]
   (handler/create (merge {:root *root* :base-url "http://localhost:3000" :quiet-paths #{"/healthz"}} settings options))))

(defn- call [handler uri & [method headers]]
  (handler {:request-method (or method :get) :uri uri :headers (or headers {})}))

(def ^:private policy-headers
  (:headers (security/header-options settings)))

(deftest the-policy-is-on-every-response-whoever-made-it
  (let [app (app)]
    (doseq [[label response] {"the page" (call app "/")
                              "a route" (call app "/patients/pt-ana")
                              "a script" (call app "/js/main.77BB12C92D2AE8EA.js")
                              "a stylesheet" (call app "/pulse.css")
                              "a file that is not there" (call app "/missing.js")
                              "a method that is not allowed" (call app "/" :post)
                              "the health probe" (call app "/healthz")}]
      (testing label
        (doseq [header ["Content-Security-Policy" "X-Content-Type-Options" "Referrer-Policy" "X-Frame-Options"
                        "Permissions-Policy" "Cross-Origin-Opener-Policy" "Cross-Origin-Resource-Policy"]]
          (is (= (get policy-headers header) (get-in response [:headers header])) header))))))

(deftest a-file-says-for-how-long-it-may-be-kept-and-the-rest-is-not-kept
  (let [app (app)]
    (is (= "no-cache" (get-in (call app "/") [:headers "Cache-Control"])))
    (is (= "public, max-age=31536000, immutable" (get-in (call app "/js/main.77BB12C92D2AE8EA.js") [:headers "Cache-Control"])))
    (is (= "no-store" (get-in (call app "/missing.js") [:headers "Cache-Control"])))
    (is (= "no-store" (get-in (call app "/healthz") [:headers "Cache-Control"])))))

(deftest the-health-probe-says-that-the-server-is-up
  (let [response (call (app) "/healthz")]
    (is (= 200 (:status response)))
    (is (= {"status" "up"} (json/read-value (:body response))))
    (is (= "application/json" (get-in response [:headers "Content-Type"])))))

(deftest a-file-not-changed-since-it-was-asked-for-is-not-sent-again
  (let [app (app)
        first-answer (call app "/pulse.css")
        modified (get-in first-answer [:headers "Last-Modified"])]
    (is (some? modified) "a file says when it was made")
    (let [again (call app "/pulse.css" :get {"if-modified-since" modified})]
      (is (= 304 (:status again)))
      (is (nil? (:body again))))
    (let [stale (call app "/pulse.css" :get {"if-modified-since" (.format DateTimeFormatter/RFC_1123_DATE_TIME (.atZone (Instant/parse "2000-01-01T00:00:00Z") java.time.ZoneOffset/UTC))})]
      (is (= 200 (:status stale))))))

(deftest every-request-has-an-id
  (let [app (app)
        a (call app "/")
        b (call app "/")]
    (is (re-matches #"[0-9a-f-]{36}" (get-in a [:headers "X-Request-Id"])))
    (is (not= (get-in a [:headers "X-Request-Id"]) (get-in b [:headers "X-Request-Id"])))
    (testing "and the one a caller sends is kept"
      (is (= "from-the-caller" (get-in (call app "/" :get {"x-request-id" "from-the-caller"}) [:headers "X-Request-Id"]))))))

(deftest a-failure-is-a-problem-and-has-the-policy-too
  (let [{:keys [value signals]} (t/with-signals true
                                  (with-redefs [files/handler (fn [_] (fn [_] (throw (ex-info "the disk is on fire" {:secret "x"}))))]
                                    (call (app) "/pulse.css")))]
    (is (= 500 (:status value)))
    (is (= (get policy-headers "Content-Security-Policy") (get-in value [:headers "Content-Security-Policy"])))
    (is (str/includes? (get-in value [:headers "Content-Type"]) "problem+json"))
    (is (not (str/includes? (:body value) "on fire")) "what went wrong is in the log and not in the answer")
    (is (some #(= :error (:level %)) signals) "and it was logged")))

(deftest https-gets-hsts-and-http-does-not
  (is (= "max-age=63072000; includeSubDomains"
         (get-in (call (app {:base-url "https://workspace.pulse.example"}) "/") [:headers "Strict-Transport-Security"])))
  (is (nil? (get-in (call (app) "/") [:headers "Strict-Transport-Security"])))
  (is (nil? (get-in (call (app {:base-url nil}) "/") [:headers "Strict-Transport-Security"]))))

(deftest the-origins-of-the-policy-are-the-ones-the-handler-is-given
  (let [response (call (app {:api-base-url "https://api.pulse.example" :issuer "https://id.pulse.example/realms/pulse"}) "/")]
    (is (str/includes? (get-in response [:headers "Content-Security-Policy"]) "connect-src 'self' https://api.pulse.example https://id.pulse.example;"))))
