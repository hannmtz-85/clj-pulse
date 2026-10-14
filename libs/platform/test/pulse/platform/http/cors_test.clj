(ns pulse.platform.http.cors-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.platform.http.cors :as cors]))

(def ^:private spa "http://localhost:3000")

(defn- app
  "A handler behind the middleware, that counts the requests that reach it and answers with
  `response`."
  [calls response & [options]]
  (cors/wrap-cors (fn [_] (swap! calls inc) response)
                  (merge {:allowed-origins #{spa}} options)))

(defn- request [method & {:as headers}]
  {:request-method method :uri "/api/me" :headers (into {} (map (fn [[k v]] [(name k) v])) headers)})

(defn- preflight [origin]
  (request :options :origin origin :access-control-request-method "PATCH"
           :access-control-request-headers "authorization, content-type"))

(def ^:private ok {:status 200 :headers {"Content-Type" "application/json"} :body "{}"})

(defn- header [response name] (get-in response [:headers name]))

(deftest what-is-an-origin
  (doseq [s ["http://localhost:3000" "https://pulse.example" "http://127.0.0.1:4000" "http://[::1]:3000" "https://a-b.c.example:8443"]]
    (is (cors/origin? s) s))
  (doseq [s ["*" "" nil "null" "localhost:3000" "http://localhost:3000/" "http://localhost:3000/app" "http://*.example.com"
             "ftp://example.com" "http://exa mple.com" "http://localhost:99999x" "http://" "https://example.com?x=1"]]
    (is (not (cors/origin? s)) (pr-str s))))

(deftest a-list-that-has-something-else-in-it-fails-at-once
  (doseq [bad ["*" "http://localhost:3000/" "http://*.example.com" "null"]]
    (let [e (try (cors/wrap-cors identity {:allowed-origins #{spa bad}})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::cors/invalid-origin (:type (ex-data e))) bad)
      (is (= [bad] (:origins (ex-data e)))))))

(deftest a-request-with-no-origin-is-not-about-cors
  (let [calls (atom 0)
        r ((app calls ok) (request :get))]
    (is (= ok r) "untouched")
    (is (= 1 @calls))))

(deftest an-allowed-origin-gets-its-preflight-answered-and-the-service-is-not-asked
  (let [calls (atom 0)
        r ((app calls ok) (preflight spa))]
    (is (= 204 (:status r)))
    (is (= 0 @calls) "the router never sees it")
    (is (= {"Access-Control-Allow-Origin" spa
            "Access-Control-Allow-Methods" "GET, POST, PATCH"
            "Access-Control-Allow-Headers" "Authorization, Content-Type, X-Request-Id"
            "Access-Control-Max-Age" "600"
            "Vary" "Origin, Access-Control-Request-Method, Access-Control-Request-Headers"}
           (:headers r)))
    (is (not (contains? (:headers r) "Access-Control-Allow-Credentials")) "never")
    (is (nil? (:body r)))))

(deftest the-answer-to-a-preflight-can-be-configured
  (let [r ((app (atom 0) ok {:allowed-methods ["GET"] :allowed-headers ["Authorization"] :max-age-s 60})
           (preflight spa))]
    (is (= "GET" (header r "Access-Control-Allow-Methods")))
    (is (= "Authorization" (header r "Access-Control-Allow-Headers")))
    (is (= "60" (header r "Access-Control-Max-Age")))))

(deftest an-origin-that-is-not-allowed-gets-a-403-that-says-so-and-no-cors-headers
  (doseq [origin ["http://evil.example" "http://localhost:3001" "https://localhost:3000" "http://localhost:3000/"
                  "HTTP://LOCALHOST:3000" "null" ""]]
    (testing (pr-str origin)
      (let [calls (atom 0)
            r ((app calls ok) (preflight origin))
            body (json/read-value (:body r) json/keyword-keys-object-mapper)]
        (is (= 403 (:status r)))
        (is (= "application/problem+json" (header r "Content-Type")))
        (is (= "This origin may not call the api." (:detail body)))
        (is (= "Origin" (header r "Vary")))
        (is (not-any? #(str/starts-with? % "Access-Control-Allow") (keys (:headers r))))
        (is (= 0 @calls))))))

(deftest an-options-request-that-is-not-a-preflight-is-not-cors-business
  (testing "no Access-Control-Request-Method"
    (let [calls (atom 0)
          r ((app calls {:status 405 :headers {"Allow" "GET"}}) (request :options :origin spa))]
      (is (= 405 (:status r)))
      (is (= 1 @calls) "it reached the service")
      (is (= spa (header r "Access-Control-Allow-Origin")) "and it is answered as any request of an allowed origin")))
  (testing "no Origin at all"
    (is (= 405 (:status ((app (atom 0) {:status 405}) (request :options)))))))

(deftest a-request-of-an-allowed-origin-can-be-read-by-it
  (let [r ((app (atom 0) ok) (request :get :origin spa))]
    (is (= spa (header r "Access-Control-Allow-Origin")))
    (is (= "X-Request-Id, WWW-Authenticate" (header r "Access-Control-Expose-Headers")))
    (is (= "Origin" (header r "Vary")))
    (is (= "{}" (:body r)))
    (is (= "application/json" (header r "Content-Type")))
    (is (not (contains? (:headers r) "Access-Control-Allow-Credentials")))))

(deftest errors-are-readable-too-or-a-client-could-not-tell-why-it-was-refused
  (let [r ((app (atom 0) {:status 401 :headers {"WWW-Authenticate" "Bearer realm=\"pulse-api\", error=\"invalid_token\""}})
           (request :get :origin spa))]
    (is (= 401 (:status r)))
    (is (= spa (header r "Access-Control-Allow-Origin")))
    (is (str/includes? (header r "Access-Control-Expose-Headers") "WWW-Authenticate"))))

(deftest a-request-of-another-origin-is-answered-as-before-and-cannot-be-read
  (let [calls (atom 0)
        r ((app calls ok) (request :get :origin "http://evil.example"))]
    (is (= 1 @calls) "cors is not access control: the service decides what it answers")
    (is (= 200 (:status r)))
    (is (not (contains? (:headers r) "Access-Control-Allow-Origin")))
    (is (not (contains? (:headers r) "Access-Control-Expose-Headers")))
    (is (= "Origin" (header r "Vary")) "so that no cache gives this answer to another origin")))

(deftest vary-keeps-what-the-response-already-says
  (let [r ((app (atom 0) {:status 200 :headers {"vary" "Accept-Encoding, Origin"}}) (request :get :origin spa))]
    (is (= "Accept-Encoding, Origin" (get-in r [:headers "Vary"])) "no duplicates, one header")
    (is (not (contains? (:headers r) "vary")))))

(deftest a-header-the-service-sets-itself-is-not-overwritten
  (let [r ((app (atom 0) {:status 200 :headers {"Access-Control-Allow-Origin" "http://other.example"}})
           (request :get :origin spa))]
    (is (= "http://other.example" (header r "Access-Control-Allow-Origin")))))

(deftest nothing-is-allowed-when-nothing-is-listed
  (doseq [origins [#{} nil]]
    (let [handler (cors/wrap-cors (fn [_] ok) {:allowed-origins origins})]
      (is (= 403 (:status (handler (preflight spa)))))
      (is (not (contains? (:headers (handler (request :get :origin spa))) "Access-Control-Allow-Origin"))))))

(deftest a-handler-that-answers-nothing-stays-nothing
  (is (nil? ((cors/wrap-cors (fn [_] nil) {:allowed-origins #{spa}}) (request :get :origin spa)))))

(def ^:private gen-origin
  (gen/one-of [(gen/elements [spa "http://localhost:3001" "https://localhost:3000" "http://evil.example" "null" "*" ""])
               (gen/fmap #(str "http://" % ".example") gen/string-alphanumeric)
               gen/string-ascii]))

(defspec only-an-origin-on-the-list-is-ever-echoed-and-it-is-echoed-exactly 400
  (prop/for-all [origin gen-origin
                 preflight? gen/boolean]
    (let [handler (cors/wrap-cors (fn [_] ok) {:allowed-origins #{spa "https://pulse.example"}})
          r (handler (if preflight? (preflight origin) (request :get :origin origin)))
          allowed (get-in r [:headers "Access-Control-Allow-Origin"])]
      (if (contains? #{spa "https://pulse.example"} origin)
        (= origin allowed)
        (nil? allowed)))))
