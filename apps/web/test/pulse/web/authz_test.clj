(ns pulse.web.authz-test
  "Who may see what in the portal (ADR-0028): routes declare it as data, and the start-up refuses a route that does not. A
  page of a patient is not given to somebody who is not signed in, who is sent to the sign-in and brought back."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.web.authz :as authz]
            [pulse.web.errors :as errors]
            [pulse.web.handler :as handler]
            [pulse.web.test.harness :as h :refer [call header with-web]]
            [reitit.ring :as ring]
            [taoensso.telemere :as t]))

(defn- ok [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "the page"})

(def ^:private routes
  [["/public" {:get {:authz :public :handler ok}}]
   ["/readings" {:get {:authz :patient :handler ok}
                 :post {:authz :patient :handler ok}}]
   ["/fragments/latest" {:get {:authz :patient :handler ok}}]])

;; ---------------------------------------------------------------------------
;; Deny by default

(deftest a-route-that-says-who-may-see-it-is-accepted
  (is (some? (handler/build-router routes)))
  (is (authz/valid-spec? :public))
  (is (authz/valid-spec? :patient)))

(deftest a-route-that-says-nothing-or-something-else-stops-the-start-up
  (doseq [[what bad-routes] {"no :authz at all" [["/oops" {:get {:handler ok}}]]
                             "a nil one" [["/oops" {:get {:authz nil :handler ok}}]]
                             "one that no rule knows" [["/oops" {:get {:authz :admin :handler ok}}]]
                             "the shape of the api's, which has no meaning here" [["/oops" {:get {:authz {:action :patients/read} :handler ok}}]]
                             "a string" [["/oops" {:get {:authz "public" :handler ok}}]]}]
    (testing what
      (let [e (try (handler/build-router (into routes bad-routes)) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= ::authz/missing-authz (:type (ex-data e))))
        (is (= ["get /oops"] (:endpoints (ex-data e))) "it names the route that is the hole")
        (is (str/includes? (ex-message e) "deny by default")))))
  (testing "one method with a rule and another without is a hole all the same"
    (let [e (try (handler/build-router [["/half" {:get {:authz :public :handler ok} :post {:handler ok}}]])
                 nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= ["post /half"] (:endpoints (ex-data e)))))))

(deftest a-rule-on-the-route-is-for-all-its-methods
  (is (some? (handler/build-router [["/shared" {:authz :patient :get {:handler ok} :post {:handler ok}}]]))))

(deftest a-route-that-got-through-anyway-is-refused-to-everybody
  ;; `check-routes!` is not called here, as if a router had been built another way
  (let [router (ring/router [["/oops" {:get {:handler ok}}]]
                            {:data {:middleware [authz/middleware]}})
        handler (ring/ring-handler router errors/default-handler)
        {:keys [value signals]} (t/with-signals true
                                  (handler {:request-method :get :uri "/oops" :headers {} :session {:person {:name "Ana"}}}))]
    (is (= 403 (:status value)) "even for a person who is signed in")
    (is (str/includes? (:body value) "You cannot see this"))
    (is (not (str/includes? (:body value) "the page")))
    (is (= [:error] (map :level (filter #(= :pulse.authz/misconfigured-endpoint (:id %)) signals))))))

;; ---------------------------------------------------------------------------
;; Somebody who is not signed in

(deftest a-public-page-is-for-anybody
  (with-web {:routes routes}
    (fn [web]
      (is (= "the page" (:text (call web :get "/public")))))))

(deftest a-page-of-a-patient-sends-a-visitor-to-the-sign-in-and-brings-them-back
  (with-web {:routes routes}
    (fn [web]
      (testing "a page"
        (let [r (call web :get "/readings")]
          (is (= 303 (:status r)))
          (is (= "/sign-in?return-to=%2Freadings" (header r "Location")))
          (is (= "" (:text r)) "nothing of the page is given")))
      (testing "with its query, which comes back encoded"
        (let [r (call web :get "/readings" :query "period=30&measurement=heart-rate")]
          (is (= "/sign-in?return-to=%2Freadings%3Fperiod%3D30%26measurement%3Dheart-rate" (header r "Location")))))
      (testing "a request that changes something has no page to come back to: they land at the home page"
        (let [r (call web :post "/readings")]
          (is (= 303 (:status r)))
          (is (= "/sign-in" (header r "Location")))))
      (testing "an empty query is no query"
        (let [r (call web :get "/readings" :query "")]
          (is (= "/sign-in?return-to=%2Freadings" (header r "Location"))))))))

(deftest htmx-is-sent-away-from-the-page-and-not-given-a-sign-in-in-a-fragment
  (with-web {:routes routes}
    (fn [web]
      (let [r (call web :get "/fragments/latest"
                    :headers {"hx-request" "true" "hx-current-url" "http://localhost:3300/readings?period=30"})]
        (is (= 401 (:status r)))
        (is (= "/sign-in?return-to=%2Freadings%3Fperiod%3D30" (header r "HX-Redirect"))
            "the page the person was on, which htmx goes to as a whole page")
        (is (nil? (header r "Location")) "a redirect that htmx would follow inside the fragment")
        (is (= "" (:text r))))
      (testing "from the home page there is nothing to come back to"
        (let [r (call web :get "/fragments/latest"
                      :headers {"hx-request" "true" "hx-current-url" "http://localhost:3300/"})]
          (is (= "/sign-in" (header r "HX-Redirect")))))
      (testing "a request that merely says it is not htmx's is a page"
        (let [r (call web :get "/fragments/latest" :headers {"hx-request" "false"})]
          (is (= 303 (:status r))))))))

(deftest a-person-who-is-signed-in-gets-the-page
  (with-web {:routes routes}
    (fn [web]
      (doseq [[method uri] [[:get "/readings"] [:post "/readings"] [:get "/fragments/latest"]]
              :let [r (call web method uri :session {:person {:name "Ana García"}})]]
        (is (= 200 (:status r)) (str method " " uri))
        (is (= "the page" (:text r)))))))

(deftest a-session-without-a-person-is-nobody
  (with-web {:routes routes}
    (fn [web]
      (doseq [session [{} {:csrf "x"} {:person nil} {:pending {:state "s"}}]]
        (is (= 303 (:status (call web :get "/readings" :session session))) (pr-str session))))))

(deftest the-person-is-whoever-the-session-says
  (is (nil? (authz/person {})))
  (is (nil? (authz/person {:session {}})))
  (is (= {:name "Ana"} (authz/person {:session {:person {:name "Ana"}}}))))

(deftest the-sign-in-address-names-the-place-to-come-back-to
  (is (= "/sign-in" (authz/sign-in-address {:request-method :get :uri "/" :headers {}})))
  (is (= "/sign-in?return-to=%2Fappointments" (authz/sign-in-address {:request-method :get :uri "/appointments" :headers {}})))
  (testing "never a place outside the portal"
    (is (= "/sign-in" (authz/sign-in-address {:request-method :get :uri "//evil.example/x" :headers {}})))
    (is (= "/sign-in" (authz/sign-in-address {:request-method :get :uri "/x"
                                              :headers {"hx-request" "true" "hx-current-url" "https://evil.example//x"}})))))

(deftest only-htmx-is-htmx
  (is (true? (authz/htmx? {:headers {"hx-request" "true"}})))
  (doseq [headers [{} {"hx-request" "false"} {"hx-request" "TRUE"} {"hx-request" ""} {"HX-Request" "true"}]]
    (is (false? (authz/htmx? {:headers headers})) (pr-str headers))))
