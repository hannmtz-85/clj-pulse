(ns pulse.web.system-test
  "The portal as a running system: Integrant starts the components of the real configuration (the `test` profile, pointed at
  a fake identity provider and a stand-in for the api), Jetty listens on a free port, and the tests talk to it over HTTP."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.stub :as stub]
            [pulse.web.config :as config]
            [pulse.web.system :as system]))

(def ^:private k1 (idp/signing-key "k1"))

(defn- with-system
  "Calls `(f base-url system idp api answer)` with the portal running against a fake identity provider and a stand-in for the
  api, which answers with what the atom `answer` holds. `change` is a function applied to the configuration before it starts."
  ([f] (with-system identity f))
  ([change f]
   (let [server (idp/start! [k1])
         answer (atom {:body "{\"status\":\"ready\",\"checks\":{}}"})
         api (stub/start! (fn [_] @answer))
         cfg (-> (config/load-config {:profile :test})
                 (assoc-in [:pulse.web/jwks :uri] (:uri server))
                 (assoc-in [:pulse.web/api :base-url] (:url api))
                 change)
         running (system/start cfg)]
     (try
       (f (str "http://127.0.0.1:" (system/port running)) running server api answer)
       (finally
         (system/stop running)
         (stub/stop! api)
         (idp/stop! server))))))

(defn- get-url [url & {:keys [headers]}]
  (http/get url {:throw false :headers (or headers {})}))

(deftest the-system-starts-serves-and-stops
  (let [url (atom nil)]
    (with-system
      (fn [base-url _ _ api _]
        (reset! url base-url)
        (testing "it listens on a port the OS chose"
          (is (re-matches #"http://127\.0\.0\.1:\d+" base-url)))
        (testing "liveness and readiness, over a real socket"
          (is (= {:status "up"} (json/read-value (:body (get-url (str base-url "/healthz"))) json/keyword-keys-object-mapper)))
          (is (= {:status "ready" :checks {:keycloak "up" :api "up"}}
                 (json/read-value (:body (get-url (str base-url "/readyz"))) json/keyword-keys-object-mapper))))
        (testing "readiness asked the api for its own, with no credentials"
          (let [requests (filter #(= "/readyz" (:path %)) @(:requests api))]
            (is (seq requests))
            (is (every? #(not (contains? (:headers %) "authorization")) requests))))
        (testing "a page that is not there is a page, with the policy and the headers"
          (let [r (get-url (str base-url "/nothing"))]
            (is (= 404 (:status r)))
            (is (str/starts-with? (get-in r [:headers "content-type"]) "text/html"))
            (is (str/includes? (:body r) "We cannot find that page"))
            (is (str/includes? (get-in r [:headers "content-security-policy"]) "script-src 'self'"))
            (is (= "no-store" (get-in r [:headers "cache-control"])))
            (is (= "nosniff" (get-in r [:headers "x-content-type-options"])))
            (is (nil? (get-in r [:headers "server"])) "the server does not announce its version")))
        (testing "the files of the portal are served"
          (let [css (get-url (str base-url "/assets/pulse.css"))
                htmx (get-url (str base-url "/assets/htmx.min.js"))]
            (is (= 200 (:status css)))
            (is (str/starts-with? (get-in css [:headers "content-type"]) "text/css"))
            (is (= 200 (:status htmx)))
            (is (str/includes? (:body htmx) "htmx"))))))
    (testing "after stop the port is closed"
      (is (thrown? java.io.IOException (http/get (str @url "/healthz")))))))

(deftest the-portal-is-not-ready-when-the-api-is-not
  (with-system
    (fn [base-url _ _ _ answer]
      (reset! answer {:status 503 :body "{}"})
      (is (= 503 (:status (get-url (str base-url "/readyz"))))))))

(deftest the-system-starts-before-the-api-and-the-identity-provider-do
  ;; nothing is asked of either by starting: a portal can start before Keycloak and the api do, and says it is not ready
  (let [cfg (-> (config/load-config {:profile :test})
                (assoc-in [:pulse.web/jwks :uri] "http://127.0.0.1:1/certs")
                (assoc-in [:pulse.web/api :base-url] "http://127.0.0.1:1"))
        running (system/start cfg)]
    (try
      (let [base-url (str "http://127.0.0.1:" (system/port running))]
        (is (= 200 (:status (get-url (str base-url "/healthz")))))
        (let [r (get-url (str base-url "/readyz"))]
          (is (= 503 (:status r)))
          (is (= {:status "unready" :checks {:keycloak "down" :api "down"}}
                 (json/read-value (:body r) json/keyword-keys-object-mapper)))))
      (finally (system/stop running)))))
