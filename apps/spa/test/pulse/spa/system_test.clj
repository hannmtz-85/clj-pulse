(ns pulse.spa.system-test
  "The server of the workspace's release as a running system (ADR-0029, point 9): Integrant starts the components of the real
  configuration (the `test` profile, pointed at a directory made for the test), Jetty listens on a free port, and the tests talk to
  it over a real socket."
  (:require [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.spa.config :as config]
            [pulse.spa.system :as system])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- release-directory []
  (let [root (.toFile (Files/createTempDirectory "spa-system-" (make-array FileAttribute 0)))]
    (doseq [[path content] {"index.html" "<!DOCTYPE html><title>Pulse</title><div id=\"app\"></div>"
                            "js/main.77BB12C92D2AE8EA.js" "console.log(1)"}]
      (let [file (io/file root path)]
        (io/make-parents file)
        (spit file content)))
    (str root)))

(defn- with-system [f]
  (let [cfg (-> (config/load-config {:profile :test})
                (assoc-in [:pulse.spa/handler :root] (release-directory)))
        running (system/start cfg)]
    (try
      (f (str "http://127.0.0.1:" (system/port running)))
      (finally (system/stop running)))))

(defn- get-url [url & {:keys [headers]}]
  (http/get url {:throw false :headers (or headers {})}))

(deftest the-system-starts-serves-and-stops
  (let [url (atom nil)]
    (with-system
      (fn [base-url]
        (reset! url base-url)
        (testing "it listens on a port the OS chose"
          (is (re-matches #"http://127\.0\.0\.1:\d+" base-url)))
        (testing "liveness, over a real socket"
          (is (= {"status" "up"} (json/read-value (:body (get-url (str base-url "/healthz")))))))
        (testing "the page, for the root and for a route"
          (doseq [path ["/" "/patients/pt-ana"]]
            (let [response (get-url (str base-url path))]
              (is (= 200 (:status response)) path)
              (is (str/includes? (:body response) "<div id=\"app\">") path)
              (is (str/starts-with? (get-in response [:headers "content-type"]) "text/html") path))))
        (testing "a script, which is kept"
          (let [response (get-url (str base-url "/js/main.77BB12C92D2AE8EA.js"))]
            (is (= "console.log(1)" (:body response)))
            (is (= "public, max-age=31536000, immutable" (get-in response [:headers "cache-control"])))))
        (testing "with the policy on every answer, a missing file among them"
          (doseq [path ["/" "/js/main.77BB12C92D2AE8EA.js" "/missing.js" "/healthz"]]
            (is (str/includes? (get-in (get-url (str base-url path)) [:headers "content-security-policy"]) "default-src 'self'") path)))
        (testing "no way out of the directory over the socket: Jetty refuses an ambiguous path, and what it lets through is not a name"
          (doseq [path ["/%2e%2e/%2e%2e/etc/passwd.txt" "/..%2f..%2fdeps.edn" "/js/..%2f..%2fdeps.edn" "/../../deps.edn"]]
            (is (contains? #{400 404} (:status (get-url (str base-url path)))) path)
            (is (not (str/includes? (:body (get-url (str base-url path))) "paths")) path)))))
    (testing "and it stops"
      (is (thrown? java.net.ConnectException (get-url @url))))))
