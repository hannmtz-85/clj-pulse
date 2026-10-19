(ns pulse.web.handler-test
  "The portal's Ring handler as a whole, with a fake identity provider and a stand-in for the api: liveness and readiness,
  what answers a path that is nothing, a method that is not allowed and a failure, the request id, the access log, and the
  headers every answer carries (ADR-0028)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.platform.test.idp :as idp]
            [pulse.web.test.harness :as h :refer [call header with-web]]))

(defn- events [response id]
  (filter #(= id (:id %)) (:signals response)))

(def ^:private boom-routes
  [["/test/boom" {:get {:authz :public
                        :handler (fn [_] (throw (ex-info "secret internals: the database password is x" {:k 1})))}
                  :post {:authz :public
                         :handler (fn [_] (throw (ex-info "secret internals" {:k 2})))}}]])

;; ---------------------------------------------------------------------------
;; Liveness and readiness

(deftest liveness-says-the-process-is-up
  (with-web {}
    (fn [web]
      (let [r (call web :get "/healthz")]
        (is (= 200 (:status r)))
        (is (= "application/json" (h/content-type r)))
        (is (= {:status "up"} (:json r)))))))

(deftest readiness-asks-the-keys-and-the-api
  (with-web {}
    (fn [web]
      (testing "both answer"
        (let [r (call web :get "/readyz")]
          (is (= 200 (:status r)))
          (is (= "application/json" (h/content-type r)))
          (is (= {:status "ready" :checks {:keycloak "up" :api "up"}} (:json r)))))
      (testing "the api is asked for its own readiness, which is public, and with no credentials"
        (let [[request :as requests] @(:api-requests web)]
          (is (= 1 (count requests)))
          (is (= "GET" (:method request)))
          (is (= "/readyz" (:path request)))
          (is (not (contains? (:headers request) "authorization"))))))))

(deftest readiness-says-which-dependency-is-down
  (with-web {}
    (fn [web]
      (testing "an api that says it is not ready"
        (reset! (:api-answer web) {:status 503 :body "{\"status\":\"unready\"}"})
        (let [r (call web :get "/readyz")]
          (is (= 503 (:status r)))
          (is (= {:status "unready" :checks {:keycloak "up" :api "down"}} (:json r)))))
      (testing "an api that fails"
        (reset! (:api-answer web) {:status 500 :body "oops"})
        (is (= "down" (get-in (call web :get "/readyz") [:json :checks :api]))))
      (testing "and it is up again when the api is"
        (reset! (:api-answer web) h/api-ready)
        (is (= 200 (:status (call web :get "/readyz"))))))))

(deftest readiness-says-when-the-keys-of-the-identity-provider-cannot-be-had
  (with-web {}
    (fn [web]
      (idp/break! (:idp web))
      (let [r (call web :get "/readyz")]
        (is (= 503 (:status r)))
        (is (= {:status "unready" :checks {:keycloak "down" :api "up"}} (:json r)))))))

(deftest the-probes-take-no-other-method
  (with-web {}
    (fn [web]
      (doseq [path ["/healthz" "/readyz"]
              method [:post :put :delete :patch]]
        (let [r (call web method path)]
          (is (= 405 (:status r)) (str method " " path))
          (is (= "GET" (header r "Allow"))))))))

;; ---------------------------------------------------------------------------
;; What answers when there is nothing to show

(deftest a-path-that-is-nothing-is-a-page-that-says-so
  (with-web {}
    (fn [web]
      (doseq [path ["/" "/nothing" "/api/me" "/readings/../x" "/healthz/extra"]]
        (let [r (call web :get path)]
          (is (= 404 (:status r)) path)
          (is (= "text/html" (h/content-type r)))
          (is (str/starts-with? (:text r) "<!DOCTYPE html>"))
          (is (str/includes? (:text r) "We cannot find that page"))
          (is (not (str/includes? (:text r) "application/problem")) "a page for a person, not a problem for a program"))))))

(deftest a-method-that-is-not-allowed-is-a-405-with-the-same-words-and-an-allow-header
  (with-web {:routes [["/test/read" {:get {:authz :public :handler (fn [_] {:status 200 :body "x"})}
                                     :head {:authz :public :handler (fn [_] {:status 200 :body ""})}}]]}
    (fn [web]
      (let [r (call web :post "/test/read")]
        (is (= 405 (:status r)))
        (is (= "GET, HEAD" (header r "Allow")))
        (is (= "text/html" (h/content-type r)))
        (is (str/includes? (:text r) "We cannot find that page"))))))

(deftest a-failure-on-our-side-is-a-page-that-says-so-and-the-cause-is-in-the-log
  (with-web {:routes boom-routes}
    (fn [web]
      (let [r (call web :get "/test/boom" :headers {"x-request-id" "req-500"})
            logged (first (events r :pulse.http/unhandled-exception))]
        (is (= 500 (:status r)))
        (is (= "text/html" (h/content-type r)))
        (is (str/includes? (:text r) "Something went wrong on our side"))
        (is (str/includes? (:text r) "Your information is safe and nothing was lost."))
        (testing "nothing of the failure reaches the person"
          (is (not (str/includes? (:text r) "secret")))
          (is (not (str/includes? (:text r) "database")))
          (is (not (str/includes? (:text r) "ExceptionInfo"))))
        (testing "the cause is logged, as an error, with the path"
          (is (= :error (:level logged)))
          (is (= "secret internals: the database password is x" (ex-message (:error logged))))
          (is (= "/test/boom" (get-in logged [:data :path]))))
        (testing "the page sends the person back to the same page, for a GET"
          (is (str/includes? (:text r) "href=\"/test/boom\"")))))))

(deftest try-again-after-a-failed-post-leads-home-and-not-to-a-page-that-takes-no-get
  (with-web {:routes boom-routes}
    (fn [web]
      (let [r (call web :post "/test/boom")]
        (is (= 500 (:status r)))
        (is (str/includes? (:text r) "Try again"))
        (is (str/includes? (:text r) "href=\"/\""))
        (is (not (str/includes? (:text r) "href=\"/test/boom\"")))))))

(deftest try-again-keeps-the-query-of-the-page-that-failed-and-nothing-that-is-not-a-path
  (with-web {:routes boom-routes}
    (fn [web]
      (let [r (call web :get "/test/boom" :query "period=30&x=%3Cscript%3E")]
        (is (str/includes? (:text r) "href=\"/test/boom?period=30&amp;x=%3Cscript%3E\"")
            "the query comes back as it was sent, written as an attribute of HTML"))
      (testing "an address that is not a path of this server is not a place to try again at"
        (let [r (call web :get "//evil.example/x")]
          (is (not (str/includes? (:text r) "evil.example"))))))))

;; ---------------------------------------------------------------------------
;; What a page is asked with

(deftest the-query-and-a-form-reach-the-handler-as-parameters
  ;; the callback of the sign-in will read `code` and `state` from the query, and every form of the portal is read here
  (with-web {:routes [["/test/echo" {:get {:authz :public :handler (fn [r] {:status 200 :body (pr-str (:query-params r))})}
                                     :post {:authz :public :handler (fn [r] {:status 200 :body (pr-str (select-keys r [:query-params :form-params]))})}}]]}
    (fn [web]
      (is (= {"code" "abc" "state" "x y"} (read-string (:text (call web :get "/test/echo" :query "code=abc&state=x+y")))))
      (is (= {:query-params {"a" "1"} :form-params {"systolic" "132" "diastolic" "88"}}
             (read-string (:text (call web :post "/test/echo" :query "a=1" :form {:systolic "132" :diastolic "88"}))))))))

;; ---------------------------------------------------------------------------
;; The request id and the access log

(deftest every-answer-carries-the-id-of-its-request
  (with-web {:routes boom-routes}
    (fn [web]
      (testing "the one the caller gave, when it is plain"
        (is (= "req-42" (header (call web :get "/healthz" :headers {"x-request-id" "req-42"}) "X-Request-Id"))))
      (testing "a new one otherwise, for a page, a 404 and a failure"
        (doseq [path ["/healthz" "/nothing" "/test/boom"]]
          (is (re-matches #"[0-9a-f-]{36}" (header (call web :get path) "X-Request-Id")) path)))
      (testing "and not one that would end up in a log or a header as something else"
        (is (re-matches #"[0-9a-f-]{36}" (header (call web :get "/healthz" :headers {"x-request-id" "<script>"}) "X-Request-Id")))))))

(deftest the-access-log-has-one-event-per-request-and-the-probes-are-quiet
  (with-web {}
    (fn [web]
      (let [page (events (call web :get "/nothing") :pulse.http/request)
            probe (events (call web :get "/healthz") :pulse.http/request)]
        (is (= 1 (count page)))
        (is (= {:method "GET" :path "/nothing" :status 404} (select-keys (:data (first page)) [:method :path :status])))
        (is (= :info (:level (first page))))
        (is (empty? probe) "a probe is polled all day: it is logged at debug, below the level the portal runs at")))))

;; ---------------------------------------------------------------------------
;; The headers

(def ^:private expected-headers
  ["X-Content-Type-Options" "Referrer-Policy" "X-Frame-Options" "Permissions-Policy"
   "Cross-Origin-Opener-Policy" "Cross-Origin-Resource-Policy" "Cache-Control" "Content-Security-Policy"])

(deftest every-kind-of-answer-carries-the-security-headers
  (with-web {:routes (into boom-routes [["/test/patient" {:get {:authz :patient :handler (fn [_] {:status 200 :body "x"})}}]])}
    (fn [web]
      (doseq [[method path] [[:get "/healthz"] [:get "/readyz"] [:get "/nothing"] [:post "/healthz"]
                             [:get "/test/boom"] [:get "/test/patient"] [:get "/assets/pulse.css"]]
              :let [r (call web method path)]]
        (testing (str method " " path " " (:status r))
          (doseq [name expected-headers]
            (is (some? (header r name)) name)))))))

(deftest the-pages-may-not-be-kept-and-the-files-may-but-must-be-asked-about
  (with-web {}
    (fn [web]
      (is (= "no-store" (header (call web :get "/nothing") "Cache-Control")))
      (is (= "no-store" (header (call web :get "/healthz") "Cache-Control")))
      (is (= "no-cache" (header (call web :get "/assets/pulse.css") "Cache-Control"))))))

(deftest the-server-does-not-say-what-it-is
  (with-web {}
    (fn [web]
      (is (nil? (header (call web :get "/healthz") "Server")))
      (is (nil? (header (call web :get "/healthz") "X-Powered-By"))))))
