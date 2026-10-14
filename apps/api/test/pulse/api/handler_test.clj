(ns pulse.api.handler-test
  "The api's Ring handler, whole: middleware, router, authorization, coercion and error
  responses, with a fake identity provider (real signatures, real key set over HTTP) and an
  in-memory FHIR server holding the demo cohort. No server and no network beyond localhost."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.api.authz :as authz]
            [pulse.api.handler :as handler]
            [pulse.api.routes :as routes]
            [pulse.fhir.client :as fc]
            [pulse.fhir.client.memory :as memory]
            [pulse.fhir.synthetic :as synthetic]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.idp :as idp]
            [taoensso.telemere :as t])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private k1 (idp/signing-key "k1"))
(def ^:private issuer "http://idp.test/realms/pulse")

(def ^:private cohort (delay (synthetic/generate (synthetic/cohort))))

(defn- now-s [] (quot (System/currentTimeMillis) 1000))

(defn- access-token
  "A token signed like Keycloak's. Overrides replace claims; a nil value removes one."
  [& {:as overrides}]
  (idp/mint k1 (into {} (remove (comp nil? val))
                     (merge {:iss issuer :aud ["pulse-api" "account"] :sub "u-1" :typ "Bearer"
                             :azp "pulse-spa" :preferred_username "ana.garcia@pulse.test"
                             :iat (now-s) :exp (+ (now-s) 300)
                             :fhirUser "Patient/pt-ana"
                             :realm_access {:roles ["default-roles-pulse" "patient" "offline_access"]}}
                            overrides))))

(defn- roles [& roles]
  {:realm_access {:roles (into ["default-roles-pulse" "offline_access"] (map name) roles)}})

(defn- refused []
  (throw (ex-info "connection refused: secret-host:8090" {})))

(def ^:private unreachable-fhir
  "A FHIR server that cannot be reached, whose exceptions say too much."
  (reify fc/FhirClient
    (read-resource [_ _ _] (refused))
    (search [_ _ _] (refused))
    (put! [_ _] (refused))
    (delete! [_ _ _] (refused))
    (transact! [_ _] (refused))
    (validate [_ _] (refused))))

(defn- with-api
  "Calls `(f {:handler h :idp server})` with a fake identity provider and the api's handler
  wired to it. Options: `:fhir` (default: the demo cohort in memory) and `:routes` (extra
  routes, added to the api's)."
  [{:keys [fhir routes]} f]
  (let [server (idp/start! [k1])
        source (jwt/jwks-source {:uri (:uri server) :min-refresh-interval-ms 200})
        verifier (jwt/verifier {:jwks-source source :issuer issuer :audience "pulse-api" :token-type "Bearer"})
        fhir (or fhir (memory/memory-client @cohort))
        api-routes (routes/routes {:verifier verifier :fhir fhir})]
    (try
      (f {:handler (handler/ring-handler (into api-routes routes)
                                         {:verifier verifier :realm "pulse-api"
                                          :quiet-paths #{"/healthz" "/readyz"}})
          :idp server})
      (finally
        (jwt/close! source)
        (idp/stop! server)))))

(defn- text-of [body]
  (cond (nil? body) ""
        (string? body) body
        (instance? InputStream body) (slurp body)
        :else (str body)))

(defn- parse [text]
  (try (json/read-value text json/keyword-keys-object-mapper)
       (catch Exception _ nil)))

(defn- call
  "The response to a request, with `:text`, `:json` (parsed body) and `:signals` (what was
  logged, trapped so nothing is printed) added. Options: `:token`, `:headers`, `:query` (a
  query string) and `:body` (a string, sent as JSON)."
  [{:keys [handler]} method uri & {:keys [token headers query body]}]
  (let [request (cond-> {:request-method method
                         :uri uri
                         :headers (cond-> (or headers {})
                                    token (assoc "authorization" (str "Bearer " token))
                                    body (assoc "content-type" "application/json"))}
                  query (assoc :query-string query)
                  body (assoc :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))))
        {:keys [value signals]} (t/with-signals true (handler request))
        text (text-of (:body value))]
    (assoc value :text text :json (parse text) :signals signals)))

(defn- header [response name]
  (get-in response [:headers name]))

(defn- content-type [response]
  (some-> (header response "Content-Type") (str/split #";") first))

;; ---------------------------------------------------------------------------
;; Public endpoints

(deftest liveness-and-readiness
  (with-api {}
    (fn [api]
      (let [live (call api :get "/healthz")
            ready (call api :get "/readyz")]
        (is (= 200 (:status live)))
        (is (= {:status "up"} (:json live)))
        (is (= "application/json" (content-type live)))
        (is (= 200 (:status ready)))
        (is (= {:status "ready" :checks {:keycloak "up" :fhir "up"}} (:json ready)))))))

(deftest readiness-names-the-dependency-that-is-down
  (testing "the identity provider's keys"
    (with-api {}
      (fn [api]
        (idp/break! (:idp api))
        (let [ready (call api :get "/readyz")]
          (is (= 503 (:status ready)))
          (is (= {:status "unready" :checks {:keycloak "down" :fhir "up"}} (:json ready)))))))
  (testing "the FHIR server, without saying why"
    (with-api {:fhir unreachable-fhir}
      (fn [api]
        (let [ready (call api :get "/readyz")]
          (is (= 503 (:status ready)))
          (is (= {:status "unready" :checks {:keycloak "up" :fhir "down"}} (:json ready)))
          (is (not (str/includes? (:text ready) "secret-host"))))))))

(deftest public-endpoints-ignore-credentials
  (with-api {}
    (fn [api]
      (is (= 200 (:status (call api :get "/healthz" :headers {"authorization" "Bearer garbage"}))))
      (is (= 200 (:status (call api :get "/healthz" :token (access-token :exp 1))))))))

(deftest health-probes-are-quiet-in-the-log
  (with-api {}
    (fn [api]
      (is (empty? (filter #(= :pulse.http/request (:id %)) (:signals (call api :get "/healthz"))))
          "debug, below the default level")
      (is (= 1 (count (filter #(= :pulse.http/request (:id %)) (:signals (call api :get "/api/me")))))))))

;; ---------------------------------------------------------------------------
;; Errors the router itself raises

(deftest unknown-routes-and-methods-are-problems-too
  (with-api {}
    (fn [api]
      (let [missing (call api :get "/nope" :headers {"x-request-id" "req-1"})]
        (is (= 404 (:status missing)))
        (is (= "application/problem+json" (content-type missing)))
        (is (= "req-1" (header missing "X-Request-Id")))
        (is (= {:type "about:blank" :title "Not Found" :status 404 :detail "No such resource."
                :instance "/nope" :requestId "req-1"}
               (:json missing))))
      (let [wrong (call api :post "/healthz")]
        (is (= 405 (:status wrong)))
        (is (= "GET" (header wrong "Allow")))
        (is (= "application/problem+json" (content-type wrong))))
      (testing "there is no OPTIONS endpoint that nobody wrote an :authz for"
        (is (= 405 (:status (call api :options "/api/me"))))
        (is (= 405 (:status (call api :options "/healthz"))))))))

;; ---------------------------------------------------------------------------
;; /api/me: authentication

(deftest me-without-credentials
  (with-api {}
    (fn [api]
      (doseq [headers [{} {"authorization" "Basic dXNlcjpwYXNz"} {"authorization" "Bearer"}]]
        (let [r (call api :get "/api/me" :headers headers)]
          (is (= 401 (:status r)) (pr-str headers))
          (is (= "Bearer realm=\"pulse-api\"" (header r "WWW-Authenticate")))
          (is (= "application/problem+json" (content-type r))))))))

(deftest me-with-a-bad-token
  (with-api {}
    (fn [api]
      (doseq [[label token expected]
              [["garbage" "abc.def.ghi" :malformed]
               ["expired" (access-token :exp (- (now-s) 3600)) :expired]
               ["another audience" (access-token :aud ["other-api"]) :bad-audience]
               ["another issuer" (access-token :iss "http://evil.test/realms/pulse") :bad-issuer]
               ["an ID token" (access-token :typ "ID" :aud "pulse-spa") :bad-audience]
               ["an unknown key" (idp/mint k1 {:iss issuer} {:kid "nope"}) :unknown-key]
               ["tampered" (let [[h p s] (str/split (access-token) #"\.")]
                             (str/join "." [h p (str (subs s 0 10) (if (= \A (nth s 10)) "B" "A") (subs s 11))]))
                :bad-signature]]]
        (testing label
          (let [r (call api :get "/api/me" :token token)]
            (is (= 401 (:status r)))
            (is (= (jwt/challenge "pulse-api" expected) (header r "WWW-Authenticate")))
            (is (= (get-in jwt/errors [expected :description]) (:detail (:json r))))))))))

(deftest me-when-the-keys-cannot-be-had
  (with-api {}
    (fn [api]
      (idp/break! (:idp api))
      (let [r (call api :get "/api/me" :token (access-token))]
        (is (= 503 (:status r)) "our problem, not an invalid token")
        (is (nil? (header r "WWW-Authenticate")))
        (is (= "10" (header r "Retry-After")))
        (is (= "Service Unavailable" (:title (:json r))))))))

;; ---------------------------------------------------------------------------
;; /api/me: the identity and the linked FHIR resource

(deftest me-for-a-patient
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/me" :token (access-token))
            body (:json r)]
        (is (= 200 (:status r)))
        (is (= "application/json" (content-type r)))
        (is (= {:subject "u-1" :username "ana.garcia@pulse.test" :client "pulse-spa"
                :roles ["patient"] :fhirUser "Patient/pt-ana"}
               (select-keys body [:subject :username :client :roles :fhirUser])))
        (testing "the whole FHIR resource comes back, not an empty map"
          (is (= "Patient" (get-in body [:resource :resourceType])))
          (is (= "pt-ana" (get-in body [:resource :id])))
          (is (seq (get-in body [:resource :name]))))))))

(deftest me-for-a-practitioner
  (with-api {}
    (fn [api]
      (let [body (:json (call api :get "/api/me"
                              :token (access-token (merge (roles :practitioner)
                                                          {:fhirUser "Practitioner/pr-rivera"
                                                           :preferred_username "dr.rivera@pulse.test"}))))]
        (is (= ["practitioner"] (:roles body)))
        (is (= "Practitioner/pr-rivera" (:fhirUser body)))
        (is (= "Practitioner" (get-in body [:resource :resourceType])))
        (is (= "pr-rivera" (get-in body [:resource :id])))))))

(deftest me-for-the-admin-and-the-scheduler
  (with-api {}
    (fn [api]
      (let [admin (:json (call api :get "/api/me"
                               :token (access-token (merge (roles :clinic-admin) {:fhirUser nil}))))
            scheduler (:json (call api :get "/api/me"
                                   :token (access-token (merge (roles :rules-engine)
                                                               {:fhirUser nil :azp "pulse-scheduler"
                                                                :preferred_username "service-account-pulse-scheduler"}))))]
        (is (= ["clinic-admin"] (:roles admin)))
        (is (nil? (:fhirUser admin)))
        (is (nil? (:resource admin)))
        (is (= ["rules-engine"] (:roles scheduler)))
        (is (= "pulse-scheduler" (:client scheduler)))
        (is (nil? (:resource scheduler)))))))

(deftest me-for-someone-with-no-application-role
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/me" :token (access-token (merge (roles) {:fhirUser nil})))]
        (is (= 200 (:status r)) "authenticated is enough for /api/me")
        (is (= [] (:roles (:json r))) "Keycloak's own default roles mean nothing here")))))

(deftest me-when-the-fhir-server-has-no-such-resource
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/me" :token (access-token :fhirUser "Patient/pt-ghost"))]
        (is (= 200 (:status r)))
        (is (= "Patient/pt-ghost" (:fhirUser (:json r))))
        (is (nil? (:resource (:json r))))))))

(deftest me-when-the-fhir-server-is-down
  (with-api {:fhir unreachable-fhir}
    (fn [api]
      (let [r (call api :get "/api/me" :token (access-token))]
        (is (= 502 (:status r)))
        (is (= "application/problem+json" (content-type r)))
        (is (= "The FHIR server could not be reached." (:detail (:json r))))
        (is (not (str/includes? (:text r) "secret-host")) "the cause is logged, not shown")
        (is (some #(= :pulse.http/problem (:id %)) (:signals r)))))))

(deftest the-request-id-ties-the-response-the-body-and-the-log-together
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/me" :headers {"x-request-id" "trace-me"})
            logged (first (filter #(= :pulse.auth/rejected (:id %)) (:signals r)))]
        (is (= "trace-me" (header r "X-Request-Id")))
        (is (= "trace-me" (:requestId (:json r))))
        (is (= {:request-id "trace-me"} (:ctx logged)))))))

;; ---------------------------------------------------------------------------
;; Authorization by role

(deftest a-rule-that-asks-for-a-role-is-enforced
  (with-redefs [authz/rules (assoc authz/rules :chart/read {:roles #{:practitioner}})]
    (with-api {:routes [["/api/chart" {:get {:authz {:action :chart/read}
                                             :handler (fn [{caller :identity}]
                                                        {:status 200 :body {:reader (:subject caller)}})}}]]}
      (fn [api]
        (let [patient (call api :get "/api/chart" :token (access-token))
              clinician (call api :get "/api/chart" :token (access-token (roles :practitioner)))
              denial (first (filter #(= :pulse.authz/denied (:id %)) (:signals patient)))]
          (is (= 403 (:status patient)))
          (is (= "You are not allowed to do this." (:detail (:json patient))) "no hint of why")
          (is (= {:subject "u-1" :action :chart/read :reason :role-not-allowed :path "/api/chart"}
                 (:data denial)) "the reason goes to the log")
          (is (= 200 (:status clinician)))
          (is (= "u-1" (:reader (:json clinician))))
          (testing "authentication still comes first"
            (is (= 401 (:status (call api :get "/api/chart"))))))))))

(deftest an-endpoint-without-authz-does-not-even-build
  (let [e (try (handler/ring-handler [["/open" {:get {:handler (constantly {:status 200})}}]]
                                     {:verifier nil :realm "pulse-api" :quiet-paths #{}})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::authz/missing-authz (:type (ex-data e))))
    (is (= ["get /open"] (:endpoints (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Bodies, parameters and failures, through routes added for the purpose

(def ^:private test-routes
  [["/test/echo" {:get {:authz :public
                        :parameters {:query [:map [:n :int]]}
                        :responses {200 {:body [:map [:n :int]]}}
                        :handler (fn [{{{:keys [n]} :query} :parameters}]
                                   {:status 200 :body {:n n}})}
                  :post {:authz :public
                         :parameters {:body [:map [:name :string]]}
                         :handler (fn [{{{:keys [name]} :body} :parameters}]
                                    {:status 200 :body {:hello name}})}}]
   ["/test/boom" {:get {:authz :public
                        :handler (fn [_] (throw (ex-info "database password is hunter2" {})))}}]
   ["/test/lies" {:get {:authz :public
                        :responses {200 {:body [:map [:ok :boolean]]}}
                        :handler (fn [_] {:status 200 :body {:ok "yes"}})}}]])

(def ^:private guarded-routes
  "Needs an action that only the test that uses it defines: anywhere else the router would
  refuse to build, which is the point of deny by default."
  [["/test/guarded" {:post {:authz {:action :test/guarded}
                            :parameters {:body [:map [:name :string]]}
                            :handler (fn [_] {:status 200 :body {}})}}]])

(deftest request-coercion
  (with-api {:routes test-routes}
    (fn [api]
      (let [good (call api :get "/test/echo" :query "n=5")
            bad (call api :get "/test/echo" :query "n=supersecret")
            missing (call api :get "/test/echo")]
        (is (= {:n 5} (:json good)) "the string is coerced to the declared type")
        (is (= 400 (:status bad)))
        (is (= "application/problem+json" (content-type bad)))
        (is (= {:n ["should be an integer"]} (get-in bad [:json :errors])))
        (is (not (str/includes? (:text bad) "supersecret")) "what the client sent is not echoed back")
        (is (= 400 (:status missing)))
        (is (= {:n ["missing required key"]} (get-in missing [:json :errors])))))))

(deftest request-bodies
  (with-api {:routes test-routes}
    (fn [api]
      (is (= {:hello "Ana"} (:json (call api :post "/test/echo" :body "{\"name\":\"Ana\"}"))))
      (let [malformed (call api :post "/test/echo" :body "{\"name\":")
            wrong (call api :post "/test/echo" :body "{\"name\":42}")]
        (is (= 400 (:status malformed)))
        (is (= "The request body is malformed." (:detail (:json malformed))))
        (is (= 400 (:status wrong)))
        (is (= {:name ["should be a string"]} (get-in wrong [:json :errors])))))))

(deftest nobody-gets-to-feed-the-parser-without-a-token
  (with-redefs [authz/rules (assoc authz/rules :test/guarded {:roles :any})]
    (with-api {:routes guarded-routes}
      (fn [api]
        (testing "a malformed body without a token is a 401, not a parse error"
          (is (= 401 (:status (call api :post "/test/guarded" :body "{\"name\":")))))
        (testing "with a token the body is looked at"
          (is (= 400 (:status (call api :post "/test/guarded" :body "{\"name\":" :token (access-token)))))
          (is (= 200 (:status (call api :post "/test/guarded" :body "{\"name\":\"Ana\"}" :token (access-token))))))))))

(deftest unexpected-failures
  (with-api {:routes test-routes}
    (fn [api]
      (testing "an exception in a handler is a generic 500, logged with its cause"
        (let [r (call api :get "/test/boom" :headers {"x-request-id" "boom-1"})
              logged (first (filter #(= :pulse.http/unhandled-exception (:id %)) (:signals r)))]
          (is (= 500 (:status r)))
          (is (= "An unexpected error occurred." (:detail (:json r))))
          (is (not (str/includes? (:text r) "hunter2")))
          (is (= "database password is hunter2" (ex-message (:error logged))))
          (is (= {:request-id "boom-1"} (:ctx logged)))))
      (testing "a response that does not match its own schema is our bug, so a 500"
        (let [r (call api :get "/test/lies")]
          (is (= 500 (:status r)))
          (is (= "An unexpected error occurred." (:detail (:json r))))
          (is (some #(= :pulse.http/response-coercion-failed (:id %)) (:signals r))))))))
