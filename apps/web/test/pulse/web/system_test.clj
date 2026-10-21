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
            [pulse.web.session :as session]
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

;; ---------------------------------------------------------------------------
;; What the configuration says reaches the running portal (ADR-0028)

(def ^:private no-redirects
  "A client that does not follow a redirect: the sign-in sends the browser to the identity provider."
  (delay (http/client {:follow-redirects :never})))

(defn- sign-in-request [base-url]
  (http/get (str base-url "/sign-in") {:throw false :client @no-redirects}))

(defn- query-of [address]
  (into {} (for [pair (str/split (second (str/split address #"\?" 2)) #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [(java.net.URLDecoder/decode ^String k "UTF-8") (java.net.URLDecoder/decode ^String v "UTF-8")])))

(deftest the-addresses-of-the-configuration-are-the-ones-the-sign-in-sends-the-browser-to
  (with-system
    (fn [cfg] (-> cfg
                  (assoc-in [:pulse.web/oidc :base-url] "http://localhost:3399")
                  (assoc-in [:pulse.web/oidc :authorization-uri] "http://localhost:9/realms/x/auth")))
    (fn [base-url _ _ _ _]
      (let [r (sign-in-request base-url)
            location (get-in r [:headers "location"])]
        (is (= 303 (:status r)))
        (is (str/starts-with? location "http://localhost:9/realms/x/auth?"))
        (is (= "http://localhost:3399/oauth2/callback" (get (query-of location) "redirect_uri"))
            "made from the base address of the configuration, and not from where the request came to")
        (is (= "pulse-web" (get (query-of location) "client_id")))))))

(deftest the-limits-of-the-configuration-are-the-limits-of-the-store
  (with-system
    (fn [cfg] (assoc-in cfg [:pulse.web/sessions :max-pending] 2))
    (fn [base-url running _ _ _]
      (dotimes [_ 5] (sign-in-request base-url))
      (is (= {:sessions 0 :pending 2} (session/counts (:pulse.web/sessions running)))
          "five visitors asked to sign in, and the store holds two pending sign-ins"))))

(deftest a-portal-whose-address-is-https-has-a-secure-cookie-and-hsts
  (with-system
    (fn [cfg] (assoc-in cfg [:pulse.web/oidc :base-url] "https://portal.example"))
    (fn [base-url _ _ _ _]
      (let [r (sign-in-request base-url)]
        (is (= "max-age=63072000; includeSubDomains" (get-in r [:headers "strict-transport-security"])))
        (is (re-find #"(?i);\s*secure" (str (get-in r [:headers "set-cookie"]))))))))

(deftest the-secret-of-the-client-is-not-printed-with-the-system
  (with-system
    (fn [_ running _ _ _]
      (is (not (str/includes? (pr-str running) "test-secret")))
      (is (not (str/includes? (pr-str (:pulse.web/oidc running)) "test-secret"))))))
