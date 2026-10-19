(ns pulse.platform.http.middleware-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.platform.http.middleware :as mw]
            [pulse.platform.http.problem :as problem]
            [taoensso.telemere :as t]))

(def ^:private uuid-re #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- request [& {:as options}]
  (merge {:request-method :get :uri "/x" :headers {}} options))

(defn- problem-body [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

;; ---------------------------------------------------------------------------
;; Request id

(deftest a-plain-client-id-is-kept
  (is (= "demo-123" (mw/request-id (request :headers {"x-request-id" "demo-123"}))))
  (is (= "a.b_C-9" (mw/request-id (request :headers {"x-request-id" "a.b_C-9"})))))

(deftest anything-else-is-replaced
  (doseq [bad [nil "" "has space" "semi;colon" "new\nline" (apply str (repeat 65 "a")) "<script>"]]
    (is (re-matches uuid-re (mw/request-id (request :headers {"x-request-id" bad}))) (pr-str bad)))
  (is (re-matches uuid-re (mw/request-id (request)))))

(defspec the-id-is-always-plain-and-short 300
  (prop/for-all [given (gen/one-of [gen/string gen/string-ascii gen/string-alphanumeric])]
    (let [id (mw/request-id (request :headers {"x-request-id" given}))]
      (boolean (re-matches #"[A-Za-z0-9._-]{1,64}" id)))))

(deftest the-id-reaches-the-handler-the-log-context-and-the-response
  (let [seen (atom nil)
        handler (mw/wrap-request-id
                 (fn [request]
                   (reset! seen {:request-id (:request-id request)
                                 :ctx-signal (t/with-signal true (t/event! :pulse.test/inside {:level :info}))})
                   {:status 200 :headers {"X-Other" "1"} :body "ok"}))
        response (handler (request :headers {"x-request-id" "req-42"}))]
    (is (= "req-42" (:request-id @seen)))
    (is (= {:request-id "req-42"} (:ctx (:ctx-signal @seen))) "every event of the request carries it")
    (is (= "req-42" (get-in response [:headers "X-Request-Id"])))
    (is (= "1" (get-in response [:headers "X-Other"])) "other headers are untouched")
    (testing "and it is gone from the context afterwards"
      (is (nil? (:ctx (t/with-signal true (t/event! :pulse.test/outside {:level :info}))))))))

;; ---------------------------------------------------------------------------
;; Access log

(defn- logged
  "The `:pulse.http/request` signals raised while `handler` serves `request`."
  [handler request]
  (->> (:signals (t/with-signals true (handler request)))
       (filter #(= :pulse.http/request (:id %)))))

(deftest one-event-per-request
  (let [handler (mw/wrap-access-log (fn [_] {:status 200}) {})
        [signal & more] (logged handler (request :request-method :post :uri "/api/things"))]
    (is (empty? more))
    (is (= :info (:level signal)))
    (is (= {:method "POST" :path "/api/things" :status 200} (select-keys (:data signal) [:method :path :status])))
    (is (nat-int? (:duration-ms (:data signal))))))

(deftest server-errors-are-warnings
  (let [handler (mw/wrap-access-log (fn [_] {:status 503}) {})]
    (is (= [:warn] (map :level (logged handler (request)))))))

(deftest client-errors-are-not-warnings
  (let [handler (mw/wrap-access-log (fn [_] {:status 404}) {})]
    (is (= [:info] (map :level (logged handler (request)))))))

(deftest health-probes-are-quiet
  (let [handler (mw/wrap-access-log (fn [_] {:status 200}) {:quiet-paths #{"/healthz"}})]
    (is (empty? (logged handler (request :uri "/healthz"))) "debug is below the default level")
    (is (= 1 (count (logged handler (request :uri "/other")))))))

;; ---------------------------------------------------------------------------
;; Exceptions

(defn- handled
  "The response and the signals when `handler` (wrapped) serves `request`."
  [throwing & [options]]
  (let [handler (mw/wrap-exceptions (fn [_] (throw throwing)) (or options {}))
        {:keys [value signals]} (t/with-signals true (handler (request :request-id "req-7")))]
    {:response value :signals signals}))

(deftest an-unexpected-exception-is-a-generic-500-and-a-logged-error
  (let [{:keys [response signals]} (handled (ex-info "secret internals: db password is x" {:k 1}))
        body (problem-body response)]
    (is (= 500 (:status response)))
    (is (= "An unexpected error occurred." (:detail body)))
    (is (= "req-7" (:requestId body)))
    (testing "nothing from the exception reaches the client"
      (is (not (str/includes? (:body response) "secret")))
      (is (not (str/includes? (:body response) "internals"))))
    (testing "the cause is logged"
      (let [signal (first (filter #(= :pulse.http/unhandled-exception (:id %)) signals))]
        (is (= :error (:level signal)))
        (is (= "secret internals: db password is x" (ex-message (:error signal))))))))

(deftest a-problem-exception-keeps-its-status-and-detail
  (let [{:keys [response signals]} (handled (problem/ex 404 "No such patient." {:headers {"X-Why" "gone"}}))
        body (problem-body response)]
    (is (= 404 (:status response)))
    (is (= "No such patient." (:detail body)))
    (is (= "gone" (get-in response [:headers "X-Why"])))
    (is (= [:info] (map :level (filter #(= :pulse.http/problem (:id %)) signals))) "a client error is not an error")))

(deftest a-server-side-problem-is-logged-as-an-error-with-its-cause
  (let [cause (RuntimeException. "upstream refused")
        {:keys [response signals]} (handled (problem/ex 502 "Upstream is down." {:cause cause}))
        signal (first (filter #(= :pulse.http/problem (:id %)) signals))]
    (is (= 502 (:status response)))
    (is (= :error (:level signal)))
    (is (some? (:error signal)))))

(deftest handlers-by-type-take-precedence
  (let [handlers {::teapot (fn [e request]
                             (problem/response 418 {:detail (str "short and stout: " (:uri request) " " (ex-message e))}))}
        {:keys [response]} (handled (ex-info "boom" {:type ::teapot}) {:handlers handlers})]
    (is (= 418 (:status response)))
    (is (= "short and stout: /x boom" (:detail (problem-body response))))))

(deftest a-service-can-answer-an-unexpected-failure-in-its-own-way
  ;; The portal's answers are pages and not problems (ADR-0028): what it gives for a failure of its own is
  ;; its page, and the failure is logged all the same.
  (let [seen (atom nil)
        render (fn [request]
                 (reset! seen request)
                 {:status 500 :headers {"Content-Type" "text/html"} :body "<h1>Something went wrong</h1>"})
        {:keys [response signals]} (handled (ex-info "secret internals" {:k 1}) {:unexpected render})
        signal (first (filter #(= :pulse.http/unhandled-exception (:id %)) signals))]
    (is (= {:status 500 :headers {"Content-Type" "text/html"} :body "<h1>Something went wrong</h1>"} response))
    (is (= "req-7" (:request-id @seen)) "it is given the request, to say its id or to link back to it")
    (testing "and the cause is logged, as it is for a problem"
      (is (= :error (:level signal)))
      (is (= "secret internals" (ex-message (:error signal)))))
    (testing "failures that carry their own meaning keep it"
      (let [handlers {::teapot (fn [_ _] {:status 418 :body "short and stout"})}
            {:keys [response]} (handled (ex-info "boom" {:type ::teapot}) {:handlers handlers :unexpected render})]
        (is (= 418 (:status response)))))
    (testing "a problem exception is still a problem"
      (is (= 404 (:status (:response (handled (problem/ex 404 "No such patient.") {:unexpected render}))))))))

(deftest a-normal-response-passes-through
  (let [handler (mw/wrap-exceptions (fn [_] {:status 201 :body "made"}) {})]
    (is (= {:status 201 :body "made"} (handler (request))))))

(deftest errors-the-jvm-cannot-recover-from-are-not-swallowed
  (let [handler (mw/wrap-exceptions (fn [_] (throw (OutOfMemoryError. "simulated"))) {})]
    (is (thrown? OutOfMemoryError (handler (request))))))
