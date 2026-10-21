(ns pulse.web.csrf-test
  "Requests that change something are the portal's own, or they are refused (ADR-0028, point 6): the check of the origin, which is for
  every request, and the check of the CSRF token, which is for the routes of a patient who is signed in."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.web.csrf :as csrf]
            [pulse.web.session :as session]
            [pulse.web.test.harness :as h :refer [header with-web]]
            [pulse.web.values :as values]
            [ring.middleware.anti-forgery.strategy :as strategy]
            [ring.middleware.session.store :as store]))

(defn- events [response id]
  (filter #(= id (:id %)) (:signals response)))

(def ^:private origin "http://localhost:3300")

;; ---------------------------------------------------------------------------
;; Where a request comes from

(deftest a-request-that-changes-nothing-may-come-from-anywhere
  (doseq [method [:get :head :options]
          o [nil origin "http://evil.example" "null" ""]]
    (is (csrf/origin-allowed? origin {:request-method method :headers (cond-> {} o (assoc "origin" o))}) (str method " " (pr-str o)))))

(deftest a-request-that-changes-something-must-come-from-the-portal-or-say-nothing
  (doseq [method [:post :put :patch :delete]]
    (testing method
      (is (csrf/origin-allowed? origin {:request-method method :headers {"origin" origin}}))
      (is (csrf/origin-allowed? origin {:request-method method :headers {}}) "a client that sends no Origin: the token decides")
      (doseq [bad ["http://evil.example" "null" "" "http://localhost:3301" "https://localhost:3300" "http://localhost"
                   "http://localhost:3300.evil.example" "http://evil.example:3300" "http://sub.localhost:3300"
                   "http://localhost:3300/" "HTTP://LOCALHOST:3300" "http://LOCALHOST:3300" " http://localhost:3300"
                   "http://localhost:3300 http://evil.example" "http://127.0.0.1:3300"]]
        (is (not (csrf/origin-allowed? origin {:request-method method :headers {"origin" bad}})) (pr-str bad))))))

(defspec only-the-origin-of-the-portal-is-the-origin-of-the-portal 300
  (prop/for-all [other (gen/such-that #(not= origin %) gen/string-ascii)]
    (and (csrf/origin-allowed? origin {:request-method :post :headers {"origin" origin}})
         (not (csrf/origin-allowed? origin {:request-method :post :headers {"origin" other}})))))

(deftest an-origin-the-portal-does-not-know-is-refused-before-anything-else-and-logged
  (with-web {}
    (fn [web]
      (doseq [o ["http://evil.example" "null"]]
        (let [r (h/call web :post "/sign-out" :headers {"origin" o})
              logged (first (events r :pulse.web/request-refused))]
          (is (= 403 (:status r)) o)
          (is (str/includes? (:text r) "We could not do that"))
          (is (= :warn (:level logged)))
          (is (= {:reason :origin :method "post" :path "/sign-out" :origin o} (:data logged))))))))

(deftest the-origin-that-is-logged-is-short-and-a-string
  (with-web {}
    (fn [web]
      (let [r (h/call web :post "/sign-out" :headers {"origin" (apply str (repeat 500 "x"))})]
        (is (= 100 (count (:origin (:data (first (events r :pulse.web/request-refused)))))))))))

(deftest the-origin-is-checked-for-the-routes-that-need-nobody-too
  (with-web {:routes [["/test/public" {:post {:authz :public :handler (fn [_] {:status 200 :body "done"})}}]]}
    (fn [web]
      (is (= 403 (:status (h/call web :post "/test/public" :headers {"origin" "http://evil.example"}))))
      (is (= 200 (:status (h/call web :post "/test/public" :headers {"origin" origin}))))
      (is (= 200 (:status (h/call web :post "/test/public"))) "no token is asked of a route that is for anybody"))))

;; ---------------------------------------------------------------------------
;; The token

(defn- token-of
  "The CSRF token of the session of the browser, as the store of `web` has it."
  [web b]
  (:csrf (store/read-session (:sessions web) (h/cookie b session/cookie-name))))

(def ^:private routes
  [["/test/change" {:post {:authz :patient :handler (fn [_] {:status 200 :body "done"})}
                    :put {:authz :patient :handler (fn [_] {:status 200 :body "done"})}
                    :delete {:authz :patient :handler (fn [_] {:status 200 :body "done"})}
                    :get {:authz :patient :handler (fn [_] {:status 200 :body "seen"})}}]])

(deftest a-request-that-changes-something-needs-the-token-of-the-session
  (with-web {:routes routes}
    (fn [web]
      (let [b (h/browser web)]
        (h/sign-in! b)
        (let [token (token-of web b)]
          (testing "in the field of a form"
            (is (= 200 (:status (h/visit! b :post "/test/change" :form {"__anti-forgery-token" token})))))
          (testing "in the header htmx sends"
            (doseq [method [:post :put :delete]]
              (is (= 200 (:status (h/visit! b method "/test/change" :headers {"x-csrf-token" token}))) (name method))))
          (testing "in the other header the library knows"
            (is (= 200 (:status (h/visit! b :post "/test/change" :headers {"x-xsrf-token" token})))))
          (testing "never in the address"
            (is (= 403 (:status (h/visit! b :post "/test/change" :query (str "__anti-forgery-token=" token))))))
          (testing "and a request that changes nothing needs none"
            (is (= "seen" (:text (h/visit! b :get "/test/change"))))))))))

(deftest a-token-that-is-missing-wrong-or-another-persons-is-refused
  (with-web {:routes routes}
    (fn [web]
      (let [one (h/browser web)
            two (h/browser web)]
        (h/sign-in! one)
        (h/sign-in! two)
        (let [mine (token-of web one)
              theirs (token-of web two)]
          (is (not= mine theirs))
          (doseq [[what request] {"none" {}
                                  "an empty one" {:form {"__anti-forgery-token" ""}}
                                  "a wrong one" {:form {"__anti-forgery-token" "wrong"}}
                                  "another person's" {:form {"__anti-forgery-token" theirs}}
                                  "mine with something added" {:form {"__anti-forgery-token" (str mine "x")}}
                                  "mine, shortened" {:form {"__anti-forgery-token" (subs mine 1)}}
                                  "in a header, wrong" {:headers {"x-csrf-token" "wrong"}}
                                  "in a header, another person's" {:headers {"x-csrf-token" theirs}}}]
            (testing what
              (let [r (apply h/visit! one :post "/test/change" (mapcat identity request))
                    logged (first (events r :pulse.web/request-refused))]
                (is (= 403 (:status r)))
                (is (str/includes? (:text r) "We could not do that"))
                (is (= {:reason :csrf-token :method "post" :path "/test/change"} (:data logged)))
                (is (not (str/includes? (:text r) mine)))
                (is (not (str/includes? (pr-str (:signals r)) mine)) "the token is not in the log"))))
          (testing "and the session was not harmed by it"
            (is (= 200 (:status (h/visit! one :post "/test/change" :form {"__anti-forgery-token" mine}))))))))))

(deftest the-token-of-a-session-that-has-gone-is-no-token
  (with-web {:routes routes}
    (fn [web]
      (let [b (h/browser web)]
        (h/sign-in! b)
        (let [old (token-of web b)]
          (h/visit! b :post "/sign-out" :form {"__anti-forgery-token" old})
          (h/sign-in! b)
          (let [r (h/visit! b :post "/test/change" :form {"__anti-forgery-token" old})]
            (is (= 403 (:status r)) "a page of the session before: its token is not the new session's")
            (is (not= old (token-of web b)))))))))

(deftest a-person-whose-session-has-ended-is-told-so-and-not-that-a-token-they-never-had-was-wrong
  (with-web {:routes routes}
    (fn [web]
      (testing "no session at all: sent to sign in"
        (let [r (h/call web :post "/test/change" :headers {"origin" origin "x-csrf-token" "anything"})]
          (is (= 303 (:status r)))))
      (testing "a session that has ended: told so"
        (let [b (h/browser web)]
          (h/sign-in! b)
          (swap! (:cookies b) assoc session/cookie-name "gone")
          (let [r (h/visit! b :post "/test/change" :headers {"x-csrf-token" "anything"})]
            (is (= 401 (:status r)))
            (is (str/includes? (:text r) "Your session has ended")))))
      (testing "htmx is sent to sign in"
        (let [r (h/call web :post "/test/change" :headers {"hx-request" "true" "hx-current-url" (str origin "/") "x-csrf-token" "x"})]
          (is (= 401 (:status r)))
          (is (= "/sign-in" (header r "HX-Redirect"))))))))

(deftest the-token-is-checked-by-a-comparison-that-takes-the-same-time
  (testing "equal strings are equal, and nothing else is"
    (is (values/same? "abc" "abc"))
    (is (not (values/same? "abc" "abd")))
    (is (not (values/same? "abc" "abcd")))
    (is (not (values/same? "" "a")))
    (is (values/same? "" "")))
  (testing "two things that are not there are not equal"
    (is (not (values/same? nil nil)))
    (is (not (values/same? nil "")))
    (is (not (values/same? "" nil)))
    (is (not (values/same? 1 1)))
    (is (not (values/same? :a :a)))))

(defspec same-is-equal-for-strings-and-never-for-anything-else 300
  (prop/for-all [a gen/string a-different (gen/such-that #(not= % "") gen/string)]
    (and (values/same? a a)
         (not (values/same? a (str a a-different)))
         (not (values/same? a nil))
         (not (values/same? nil a)))))

;; ---------------------------------------------------------------------------
;; Where it applies

(deftest only-the-routes-of-a-patient-are-given-the-check-of-the-token
  (let [compile (:compile csrf/middleware)]
    (is (fn? (compile {:authz :patient} nil)))
    (is (nil? (compile {:authz :public} nil)))
    (is (nil? (compile {} nil)))
    (is (nil? (compile {:authz :admin} nil)))))

(deftest asking-for-the-token-writes-nothing-to-a-session
  ;; the strategy of the library would make a session for a visitor who only asked; this one reads what the sign-in made
  (let [request {:session {:csrf "t"}}
        response {:status 200}]
    (is (= "t" (strategy/get-token csrf/strategy request)))
    (is (nil? (strategy/get-token csrf/strategy {:session {}})))
    (is (= response (strategy/write-token csrf/strategy request response "t")))
    (is (= response (strategy/write-token csrf/strategy {} response "other")) "whatever the token, the response is as it was")
    (is (true? (strategy/valid-token? csrf/strategy request "t")))
    (is (not (strategy/valid-token? csrf/strategy request "x")))
    (is (not (strategy/valid-token? csrf/strategy {:session {}} nil)) "no token and no token do not match")
    (is (not (strategy/valid-token? csrf/strategy {:session {}} "t")))))
