(ns pulse.api.security-test
  "What a browser is told about the responses of the api (ADR-0018): the security headers on all
  of them, whoever made them, and CORS for the browser application on another origin. The
  middleware are tested alone in `libs/platform`; here they are checked on real responses,
  errors among them, through the whole handler."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.api.security :as security]
            [pulse.api.test.harness :as h :refer [call content-type header with-api]]))

(def ^:private spa h/spa-origin)

(def ^:private boom
  "A route that fails the way a bug does."
  [["/test/boom" {:get {:authz :public
                        :handler (fn [_] (throw (ex-info "boom: secret-detail" {})))}}]])

(defn- request-logged
  "The event of the access log for a response made by `call` that has `status`, or nil."
  [response status]
  (some #(when (and (= :pulse.http/request (:id %)) (= status (get-in % [:data :status]))) %)
        (:signals response)))

;; ---------------------------------------------------------------------------
;; Security headers

(def ^:private api-headers
  "What every response of the api says, written out here and not read from the code."
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "no-referrer"
   "X-Frame-Options" "DENY"
   "Cross-Origin-Resource-Policy" "same-site"
   "Cache-Control" "no-store"
   "Content-Security-Policy" "default-src 'none'; frame-ancestors 'none'"})

(def ^:private never
  "Headers the api never sets: see ADR-0018 for each."
  ["Strict-Transport-Security" "Cross-Origin-Opener-Policy" "Cross-Origin-Embedder-Policy"
   "Access-Control-Allow-Credentials"])

(deftest every-response-carries-the-security-headers-whoever-made-it
  (with-api {:routes boom :fhir h/unreachable-fhir}
    (fn [api]
      (doseq [[label response] [["a success" (call api :get "/healthz")]
                                ["the document of the api" (call api :get "/openapi.json")]
                                ["a 400 of the coercion" (call api :get "/api/patients" :token (h/token-for :admin) :query "limit=0")]
                                ["a 401 of authentication" (call api :get "/api/me")]
                                ["a 403 of authorization" (call api :get "/api/patients" :token (h/token-for :ana))]
                                ["a 404 of the router" (call api :get "/nope")]
                                ["a 405 of the router" (call api :post "/healthz")]
                                ["a 502, the FHIR server being down" (call api :get "/api/me" :token (h/token-for :ana))]
                                ["a 500, from a bug" (call api :get "/test/boom")]]]
        (testing label
          (is (= api-headers (select-keys (:headers response) (keys api-headers))))
          (is (str/includes? (header response "Permissions-Policy") "camera=()"))
          (is (str/includes? (header response "Permissions-Policy") "geolocation=()"))
          (is (not-any? #(contains? (:headers response) %) never)))))))

(deftest the-body-is-what-it-says-it-is-and-nothing-more
  (with-api {}
    (fn [api]
      (doseq [response [(call api :get "/healthz") (call api :get "/api/me") (call api :get "/nope")]]
        (is (some? (content-type response)) "a body always says what it is")
        (is (= "nosniff" (header response "X-Content-Type-Options")) "and a browser believes it")))))

(deftest a-response-made-by-the-router-or-an-error-is-covered-too
  ;; The headers are a layer around the router, not something each endpoint remembers.
  (with-api {:routes boom}
    (fn [api]
      (let [r (call api :get "/test/boom")]
        (is (= 500 (:status r)))
        (is (= "no-store" (header r "Cache-Control")))
        (is (not (str/includes? (:text r) "secret-detail")))))))

(deftest the-origin-of-an-address-is-its-scheme-host-and-port
  (is (= "http://localhost:8080" (security/origin-of "http://localhost:8080/realms/pulse")))
  (is (= "https://id.example" (security/origin-of "https://id.example/realms/pulse?x=1#top")))
  (is (= "http://[::1]:8080" (security/origin-of "http://[::1]:8080/realms/pulse")))
  (is (nil? (security/origin-of "not an address")))
  (is (nil? (security/origin-of nil))))

(deftest the-policy-of-the-docs-names-the-identity-provider-only-when-there-is-one
  (is (str/includes? (security/docs-policy "http://localhost:8080/realms/pulse") "connect-src 'self' http://localhost:8080;"))
  (is (str/includes? (security/docs-policy nil) "connect-src 'self';")))

(defn- policy
  "The Content-Security-Policy of `response` as `{directive #{source ...}}`."
  [response]
  (into {}
        (map (fn [directive]
               (let [[name & sources] (str/split (str/trim directive) #"\s+")]
                 [name (set sources)])))
        (str/split (header response "Content-Security-Policy") #";")))

(deftest the-page-of-swagger-ui-has-its-own-policy
  (with-api {}
    (fn [api]
      (doseq [path ["/docs/" "/docs/swagger-initializer.js" "/docs/oauth2-redirect.html" "/docs/config.json" "/docs"]]
        (testing path
          (let [r (call api :get path)
                directives (policy r)]
            (is (= #{"'none'"} (get directives "default-src")) "nothing is allowed that is not named")
            (is (= #{"'self'"} (get directives "script-src")) "scripts from this server: no inline, no eval")
            (is (= #{"'self'" "'unsafe-inline'"} (get directives "style-src")) "Swagger UI styles its elements from script")
            (is (= #{"'self'" "http://idp.test"} (get directives "connect-src"))
                "this server, and the identity provider that gives the token")
            (is (= #{"'self'" "data:"} (get directives "img-src")))
            (is (= #{"'none'"} (get directives "frame-ancestors")))
            (is (= #{"'none'"} (get directives "base-uri")))
            (is (= #{"'none'"} (get directives "object-src")))
            (is (= #{"'self'"} (get directives "form-action")))
            (is (nil? (header r "Cache-Control")) "the bundle is 1.5 MB: it may be cached")
            (is (= "nosniff" (header r "X-Content-Type-Options")))
            (is (= "DENY" (header r "X-Frame-Options")))
            (is (= "no-referrer" (header r "Referrer-Policy")))
            (is (= "same-site" (header r "Cross-Origin-Resource-Policy")))
            (is (not-any? #(contains? (:headers r) %) never)
                "above all, no Cross-Origin-Opener-Policy: the sign-in popup talks to its opener"))))
      (testing "the document, which is not the page, has the policy of the api"
        (is (= "default-src 'none'; frame-ancestors 'none'"
               (header (call api :get "/openapi.json") "Content-Security-Policy")))
        (is (= "no-store" (header (call api :get "/openapi.json") "Cache-Control")))))))

;; ---------------------------------------------------------------------------
;; CORS

(defn- preflight
  "The preflight a browser sends before a PATCH with a token, from `origin`, to `path`."
  [api path origin]
  (call api :options path :headers {"origin" origin
                                    "access-control-request-method" "PATCH"
                                    "access-control-request-headers" "authorization, content-type"}))

(deftest an-allowed-origin-has-its-preflight-answered-before-anything-else
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls)}
      (fn [api]
        (let [r (preflight api "/api/appointments/appt-any" spa)]
          (is (= 204 (:status r)) "no token was sent, and none was needed")
          (is (= spa (header r "Access-Control-Allow-Origin")))
          (is (= "GET, POST, PATCH" (header r "Access-Control-Allow-Methods")))
          (is (= "Authorization, Content-Type, X-Request-Id" (header r "Access-Control-Allow-Headers")))
          (is (= "600" (header r "Access-Control-Max-Age")))
          (is (str/includes? (header r "Vary") "Origin"))
          (is (not (contains? (:headers r) "Access-Control-Allow-Credentials")))
          (is (= "" (:text r)))
          (is (empty? @calls) "the FHIR server was not asked")
          (is (empty? (h/audit-events r)) "nor was anything decided about anybody")
          (is (some? (header r "X-Request-Id")) "it has a request id like any response")
          (is (= "nosniff" (header r "X-Content-Type-Options")) "and the security headers")
          (is (some? (request-logged r 204)) "and it is in the access log"))))))

(deftest a-preflight-is-not-about-whether-the-route-exists
  (with-api {}
    (fn [api]
      (doseq [path ["/api/me" "/api/patients/pt-ana/observations" "/nope"]]
        (is (= 204 (:status (preflight api path spa))) path)))))

(deftest an-origin-that-is-not-allowed-is-refused-with-a-reason
  (with-api {}
    (fn [api]
      (doseq [origin ["http://evil.example" "http://localhost:3001" "https://localhost:3000"
                      "HTTP://LOCALHOST:3000" "null" "*"]]
        (testing origin
          (let [r (preflight api "/api/me" origin)]
            (is (= 403 (:status r)))
            (is (= "application/problem+json" (content-type r)))
            (is (= "This origin may not call the api." (get-in r [:json :detail])))
            (is (= (header r "X-Request-Id") (get-in r [:json :requestId])))
            (is (= "Origin" (header r "Vary")))
            (is (not-any? #(str/starts-with? % "Access-Control-") (keys (:headers r))))
            (is (some? (request-logged r 403)) "the refusal is in the access log")))))))

(deftest a-request-from-an-allowed-origin-can-be-read-by-it
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/me" :token (h/token-for :ana) :headers {"origin" spa})]
        (is (= 200 (:status r)))
        (is (= spa (header r "Access-Control-Allow-Origin")))
        (is (= "X-Request-Id, WWW-Authenticate" (header r "Access-Control-Expose-Headers")))
        (is (= "Origin" (header r "Vary")))
        (is (= "pt-ana" (get-in r [:json :resource :id])) "the answer itself is untouched")
        (is (not (contains? (:headers r) "Access-Control-Allow-Credentials")))))))

(deftest an-error-can-be-read-by-the-application-that-asked
  ;; An application that cannot read why it was refused cannot ask the user to sign in again.
  (with-api {:routes boom :fhir h/unreachable-fhir}
    (fn [api]
      (doseq [[label status response]
              [["no token" 401 (call api :get "/api/me" :headers {"origin" spa})]
               ["not allowed" 403 (call api :get "/api/patients" :token (h/token-for :ana) :headers {"origin" spa})]
               ["not valid" 400 (call api :get "/api/patients" :token (h/token-for :admin) :query "limit=0" :headers {"origin" spa})]
               ["no such route" 404 (call api :get "/nope" :headers {"origin" spa})]
               ["wrong method" 405 (call api :post "/healthz" :headers {"origin" spa})]
               ["FHIR is down" 502 (call api :get "/api/me" :token (h/token-for :ana) :headers {"origin" spa})]
               ["a bug" 500 (call api :get "/test/boom" :headers {"origin" spa})]]]
        (testing label
          (is (= status (:status response)))
          (is (= spa (header response "Access-Control-Allow-Origin")))
          (is (str/includes? (header response "Access-Control-Expose-Headers") "WWW-Authenticate")))))))

(deftest another-origin-gets-the-same-answer-and-cannot-read-it
  (with-api {}
    (fn [api]
      (let [without (call api :get "/api/me" :token (h/token-for :ana))
            from-evil (call api :get "/api/me" :token (h/token-for :ana) :headers {"origin" "http://evil.example"})]
        (is (= (:status without) (:status from-evil)) "cors is not access control: the token decides")
        (is (= (:json without) (:json from-evil)))
        (is (not (contains? (:headers from-evil) "Access-Control-Allow-Origin")))
        (is (not (contains? (:headers from-evil) "Access-Control-Expose-Headers")))
        (is (= "Origin" (header from-evil "Vary")) "so that no cache gives it to another origin")))))

(deftest a-request-with-no-origin-is-not-about-cors
  (with-api {}
    (fn [api]
      (let [r (call api :get "/api/me" :token (h/token-for :ana))]
        (is (= 200 (:status r)))
        (is (not-any? #(or (str/starts-with? % "Access-Control-") (= "Vary" %)) (keys (:headers r))))))))

(deftest the-origins-that-are-allowed-are-configuration
  (testing "several, each echoed as it was sent"
    (with-api {:cors-origins #{"https://pulse.example" spa}}
      (fn [api]
        (doseq [origin ["https://pulse.example" spa]]
          (is (= origin (header (preflight api "/api/me" origin) "Access-Control-Allow-Origin")) origin))
        (is (= 403 (:status (preflight api "/api/me" "https://other.example")))))))
  (testing "the workspace is not allowed by default magic: only what is listed is"
    (with-api {:cors-origins #{"https://pulse.example"}}
      (fn [api]
        (is (= 403 (:status (preflight api "/api/me" spa)))))))
  (testing "none at all"
    (with-api {:cors-origins #{}}
      (fn [api]
        (is (= 403 (:status (preflight api "/api/me" spa))))
        (is (not (contains? (:headers (call api :get "/healthz" :headers {"origin" spa})) "Access-Control-Allow-Origin")))))))
