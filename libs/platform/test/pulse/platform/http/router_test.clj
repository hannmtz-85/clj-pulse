(ns pulse.platform.http.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.platform.http.middleware :as mw]
            [pulse.platform.http.router :as router]
            [reitit.ring :as ring]
            [taoensso.telemere :as t])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.nio.charset StandardCharsets)))

(def ^:private open
  "An authorization middleware that does nothing: these tests are about the rest of the setup."
  {:name ::open :compile (fn [_ _] nil)})

(def ^:private routes
  [["/things" {:get {:authz :public
                     :parameters {:query [:map [:n :int]]}
                     :responses {200 {:body [:map [:n :int]]}}
                     :handler (fn [{{{:keys [n]} :query} :parameters}] {:status 200 :body {:n n}})}
               :post {:authz :public
                      :parameters {:body [:map [:name :string]]}
                      :handler (fn [{{{:keys [name]} :body} :parameters}] {:status 200 :body {:hello name}})}}]
   ["/nothing" {:get {:authz :public :handler (fn [_] nil)}}]
   ["/lies" {:get {:authz :public
                   :responses {200 {:body [:map [:ok :boolean]]}}
                   :handler (fn [_] {:status 200 :body {:ok "not a boolean"}})}}]
   ["/boom" {:get {:authz :public
                   :handler (fn [_] (throw (ex-info "database password is hunter2" {})))}}]])

(def ^:private app
  (ring/ring-handler (ring/router routes (router/options open))
                     router/default-handler
                     {:middleware [mw/wrap-request-id
                                   [mw/wrap-exceptions {:handlers router/exception-handlers}]]}))

(defn- call
  "The response to a request, with `:json` (the parsed body) and `:ids` (the events logged,
  trapped so nothing is printed) added."
  [method uri & {:keys [query headers body]}]
  (let [{:keys [value signals]} (t/with-signals true
                                  (app (cond-> {:request-method method :uri uri :headers (or headers {})}
                                         query (assoc :query-string query)
                                         body (assoc :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))))))
        text (let [b (:body value)] (if (instance? InputStream b) (slurp b) b))]
    (assoc value :text text :json (json/read-value text json/keyword-keys-object-mapper) :ids (mapv :id signals))))

(deftest json-in-and-json-out
  (let [r (call :get "/things" :query "n=3")]
    (is (= 200 (:status r)))
    (is (= {:n 3} (:json r))))
  (let [r (call :post "/things" :headers {"content-type" "application/json"} :body "{\"name\":\"Ana\"}")]
    (is (= 200 (:status r)))
    (is (= {:hello "Ana"} (:json r)))))

(deftest what-the-router-does-not-know-is-a-problem
  (let [r (call :get "/nope" :headers {"x-request-id" "req-1"})]
    (is (= 404 (:status r)))
    (is (= "application/problem+json" (get-in r [:headers "Content-Type"])))
    (is (= {:type "about:blank" :title "Not Found" :status 404 :detail "No such resource."
            :instance "/nope" :requestId "req-1"}
           (:json r))))
  (testing "a method the route does not have says which it has"
    (let [r (call :delete "/things")]
      (is (= 405 (:status r)))
      (is (= "GET, POST" (get-in r [:headers "Allow"])))))
  (testing "there is no OPTIONS endpoint that nobody wrote an :authz for"
    (is (= 405 (:status (call :options "/things")))))
  (testing "a handler with no answer to give is not acceptable"
    (is (= 406 (:status (call :get "/nothing"))))))

(deftest a-request-that-does-not-match-is-a-400-that-says-which-field
  (let [r (call :get "/things" :query "n=x")]
    (is (= 400 (:status r)))
    (is (= {:n ["should be an integer"]} (get-in r [:json :errors])))
    (is (= "The request does not match what this endpoint accepts." (get-in r [:json :detail]))))
  (testing "a body that is not JSON"
    (let [r (call :post "/things" :headers {"content-type" "application/json"} :body "{\"name\":")]
      (is (= 400 (:status r)))
      (is (= "The request body is malformed." (get-in r [:json :detail]))))))

(deftest our-own-failures-are-a-500-that-says-nothing-and-is-logged
  (testing "a response that does not match its schema"
    (let [r (call :get "/lies")]
      (is (= 500 (:status r)))
      (is (= "An unexpected error occurred." (get-in r [:json :detail])))
      (is (= [:pulse.http/response-coercion-failed] (:ids r)))))
  (testing "an exception, whose message stays in the log"
    (let [r (call :get "/boom")]
      (is (= 500 (:status r)))
      (is (not (re-find #"hunter2" (:text r)))))))

;; ---------------------------------------------------------------------------
;; Deny by default

(defn- valid? [spec]
  (or (= :public spec) (and (map? spec) (contains? spec :role))))

(defn- undeclared [routes]
  (vec (router/endpoints-without (ring/router routes) valid?)))

(deftest an-endpoint-is-found-when-it-has-no-valid-authz
  (is (= [] (undeclared [["/a" {:get {:authz :public :handler identity}}]
                         ["/b" {:authz {:role "admin"} :get {:handler identity} :post {:handler identity}}]])))
  (is (= ["get /a"] (undeclared [["/a" {:get {:handler identity}}]])) "none at all")
  (is (= ["get /a"] (undeclared [["/a" {:get {:authz "everybody" :handler identity}}]])) "not a valid one")
  (testing "a method's own :authz wins over the route's, a route's over its parent's"
    (is (= ["post /a"] (undeclared [["/a" {:authz :public :get {:handler identity} :post {:authz :nobody :handler identity}}]])))
    (is (= [] (undeclared [["/p" {:authz :public} ["/a" {:get {:handler identity}}]]]))))
  (testing "a handler for every method is one endpoint"
    (is (= ["any /a"] (undeclared [["/a" {:handler identity}]])))
    (is (= [] (undeclared [["/a" {:authz :public :handler identity}]]))))
  (testing "every method and every path is named"
    (is (= ["get /a" "post /a" "get /b"]
           (undeclared [["/a" {:get {:handler identity} :post {:handler identity}}]
                        ["/b" {:get {:handler identity}}]])))))
