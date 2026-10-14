(ns pulse.api.docs-test
  "The description of the api and the page that shows it (ADR-0017), through the whole handler:
  the document that Reitit generates from the route data and `pulse.api.docs` completes, and
  Swagger UI under `/docs`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [jsonista.core :as json]
            [pulse.api.authz :as authz]
            [pulse.api.docs :as docs]
            [pulse.api.observations :as observations]
            [pulse.api.test.harness :as h :refer [call content-type header with-api]]
            [pulse.fhir.appointment :as lifecycle]
            [reitit.ring :as ring]))

;; ---------------------------------------------------------------------------
;; What the document must say, written apart from the code

(def ^:private operations
  "Annex B as a table: the method and the path of each operation, as the document writes them, the
  action it is authorized as (nil for a public one), and every status the document lists for it.
  `5XX` stands for what the api could not do: the FHIR server failing or slow, the keys of the
  identity provider missing."
  [[:get "/healthz" nil #{"200"}]
   [:get "/readyz" nil #{"200" "503"}]
   [:get "/api/me" :identity/read #{"200" "401" "403" "5XX"}]
   [:get "/api/patients" :patient/list #{"200" "400" "401" "403" "5XX"}]
   [:get "/api/patients/{id}" :patient/read #{"200" "401" "403" "404" "5XX"}]
   [:get "/api/patients/{id}/summary" :patient/read #{"200" "401" "403" "404" "5XX"}]
   [:get "/api/patients/{id}/observations" :observation/read #{"200" "400" "401" "403" "404" "5XX"}]
   [:post "/api/patients/{id}/observations" :observation/record #{"201" "400" "401" "403" "404" "422" "5XX"}]
   [:get "/api/appointments" :appointment/read #{"200" "400" "401" "403" "5XX"}]
   [:post "/api/appointments" :appointment/request #{"201" "400" "401" "403" "409" "422" "5XX"}]
   [:patch "/api/appointments/{id}" :appointment/manage #{"200" "400" "401" "403" "404" "409" "422" "5XX"}]
   [:get "/api/alerts" :alert/read #{"200" "400" "401" "403" "5XX"}]
   [:post "/api/alerts/{id}/ack" :alert/ack #{"200" "401" "403" "404" "409" "5XX"}]])

(defn- document
  "The document the api serves, with string keys, since a path is not a good keyword."
  [api]
  (json/read-value (:text (call api :get "/openapi.json"))))

(defn- operation [doc method path]
  (get-in doc ["paths" path (name method)]))

(defn- access-of
  "What the document should say about who may perform `action`, from the rules."
  [action]
  (into {} (map (fn [[role scope]] [(name role) (name scope)])) (get authz/rules action)))

(defn- with-document [f]
  (with-api {} (fn [api] (f (document api)))))

;; ---------------------------------------------------------------------------
;; The document

(deftest the-document-is-public-and-says-what-it-is
  (with-api {}
    (fn [api]
      (let [r (call api :get "/openapi.json")
            doc (json/read-value (:text r))]
        (is (= 200 (:status r)))
        (is (= "application/json" (content-type r)))
        (is (= "3.1.0" (get doc "openapi")))
        (is (= "Pulse API" (get-in doc ["info" "title"])))
        (is (not (str/blank? (get-in doc ["info" "version"]))))
        (is (not (contains? doc "x-id")) "none of the library's own bookkeeping")
        (testing "the same document every time"
          (is (= (:text r) (:text (call api :get "/openapi.json")))))
        (testing "no token is needed, and none is looked at"
          (is (= 200 (:status (call api :get "/openapi.json" :headers {"authorization" "Bearer garbage"})))))
        (testing "it is a document, not an endpoint to call with other methods"
          (is (= 405 (:status (call api :post "/openapi.json")))))))))

(deftest it-lists-the-operations-of-annex-b-and-nothing-else
  (with-document
    (fn [doc]
      (is (= (set (for [[method path] operations] [method path]))
             (set (for [[path methods] (get doc "paths")
                        [method _] methods]
                    [(keyword method) path])))
          "neither the document nor the page of Swagger UI describes itself"))))

(deftest an-operation-says-who-may-call-it
  (with-document
    (fn [doc]
      (doseq [[method path action] operations
              :let [op (operation doc method path)]]
        (testing (str method " " path)
          (if action
            (do (is (= [{"pulse-sso" ["openid"]}] (get op "security")))
                (is (= (access-of action) (get op "x-pulse-access")) "what the rules say")
                (is (str/includes? (get op "description") "Who may call it"))
                (doseq [role (keys (access-of action))]
                  (is (str/includes? (get op "description") (if (= "any" role) "any signed-in caller" (str "`" role "`")))
                      role)))
            (do (is (not (contains? op "security")) "a public operation asks for no sign-in")
                (is (not (contains? op "x-pulse-access")))))))
      ;; The form a reader sees, for a few, written out from Annex A and not from the rules
      (testing "in the form a reader sees"
        (is (= {"practitioner" "panel" "clinic-admin" "all" "rules-engine" "identifiers"}
               (get (operation doc :get "/api/patients") "x-pulse-access")))
        (is (= {"any" "all"} (get (operation doc :get "/api/me") "x-pulse-access")))
        (is (= {"patient" "own"} (get (operation doc :post "/api/appointments") "x-pulse-access")))
        (is (= {"clinic-admin" "all" "practitioner" "panel"}
               (get (operation doc :get "/api/alerts") "x-pulse-access")))))))

(deftest a-role-the-document-does-not-know-by-name-is-still-listed
  ;; The order of the roles is for reading, and must never be a way of leaving one out
  (with-redefs [authz/rules (assoc authz/rules :test/action {:auditor :all :patient :own :any :all})]
    (is (= [["any" "all"] ["patient" "own"] ["auditor" "all"]] (docs/access :test/action)))))

(deftest what-a-route-inherits-is-documented-too
  ;; `:authz` and `:problems` may sit on a parent route or above the methods: the document is made
  ;; from the resolved routes of the router, where what is inherited is already applied.
  (let [handler (fn [_] {:status 200})
        router (ring/router ["/things" {:authz {:action :patient/read :resource (fn [_ _] nil)}
                                        :problems {409 "when it is not ready"}}
                             ["/:id" {:get {:handler handler}
                                      :post {:authz {:action :observation/record}
                                             :handler handler}}]])
        generated {:paths {"/things/{id}" {:get {:responses {200 {:description "ok"}}}
                                           :post {:responses {201 {:description "made"}}}}}}
        operations (get-in (docs/document generated router) [:paths "/things/{id}"])]
    (testing "an operation with no :authz of its own has its parent's"
      (is (= {"patient" "own" "practitioner" "panel" "clinic-admin" "all"}
             (:x-pulse-access (:get operations))))
      (is (= #{200 401 403 404 409 "5XX"} (set (keys (:responses (:get operations)))))
          "a 404 for its :resource, and the problem of the parent"))
    (testing "an operation's own :authz wins, and it still has what the parent says of problems"
      (is (= {"patient" "own" "practitioner" "panel" "rules-engine" "all"}
             (:x-pulse-access (:post operations))))
      (is (contains? (:responses (:post operations)) 409))
      (is (not (contains? (:responses (:post operations)) 404)) "the :resource of the parent is not its own"))))

(deftest an-operation-lists-the-statuses-it-may-answer
  (with-document
    (fn [doc]
      (doseq [[method path _ statuses] operations
              :let [op (operation doc method path)]]
        (testing (str method " " path)
          (is (= statuses (set (keys (get op "responses"))))))))))

(deftest the-problems-an-operation-has-of-its-own-say-when
  (with-document
    (fn [doc]
      (doseq [[method path status] [[:post "/api/patients/{id}/observations" "422"]
                                    [:post "/api/appointments" "409"]
                                    [:post "/api/appointments" "422"]
                                    [:patch "/api/appointments/{id}" "409"]
                                    [:patch "/api/appointments/{id}" "422"]
                                    [:post "/api/alerts/{id}/ack" "409"]]
              :let [response (get-in (operation doc method path) ["responses" status])]]
        (testing (str method " " path " " status)
          (is (not (str/blank? (get response "description"))))
          (is (= {"$ref" "#/components/schemas/Problem"}
                 (get-in response ["content" "application/problem+json" "schema"])))))
      (testing "and the limits it quotes are those of the code, not copies of them"
        (is (str/includes? (get-in (operation doc :post "/api/appointments") ["responses" "422" "description"])
                           (str "at most " (:window-days lifecycle/limits) " days")))
        (is (str/includes? (get (operation doc :post "/api/patients/{id}/observations") "description")
                           (str "at most " observations/max-future-minutes " minutes ahead")))
        (is (str/includes? (get (operation doc :post "/api/patients/{id}/observations") "description")
                           (str "at most " observations/max-age-days " days back")))))))

(deftest every-reference-resolves
  (with-document
    (fn [doc]
      (let [found (atom [])]
        (walk/postwalk (fn [x]
                         (when (and (map? x) (contains? x "$ref"))
                           (swap! found conj (get x "$ref")))
                         x)
                       doc)
        (is (seq @found))
        (is (every? #(str/starts-with? % "#/") @found) "only references within the document")
        (doseq [ref (set @found)]
          (is (some? (get-in doc (rest (str/split ref #"/")))) ref))))))

(deftest every-operation-is-described
  (with-document
    (fn [doc]
      (let [tags (set (map #(get % "name") (get doc "tags")))]
        (is (seq tags))
        (doseq [[method path] operations
                :let [op (operation doc method path)]]
          (testing (str method " " path)
            (is (not (str/blank? (get op "summary"))))
            (is (not (str/blank? (get op "description"))))
            (is (seq (get op "tags")))
            (is (every? tags (get op "tags")) "a tag the document declares")
            (doseq [p (get op "parameters")]
              (is (not (str/blank? (get p "description"))) (get p "name")))
            (doseq [[status response] (get op "responses")
                    :when (not (contains? response "$ref"))]
              (is (not (str/blank? (get response "description"))) (str "response " status)))
            (let [schema (get-in op ["requestBody" "content" "application/json" "schema"])]
              (doseq [branch (or (get schema "oneOf") (when schema [schema]))
                      [field described] (get branch "properties")
                      :when (not (contains? described "const"))]
                (is (not (str/blank? (get described "description"))) (str "field " field))))))))))

(deftest a-parameter-with-a-default-is-not-required
  (with-document
    (fn [doc]
      (let [parameters (fn [method path]
                         (into {} (map (juxt #(get % "name") identity))
                               (get (operation doc method path) "parameters")))]
        (testing "the invariant, for every parameter of every operation"
          (doseq [[method path] operations
                  p (get (operation doc method path) "parameters")]
            (is (not (and (get p "required") (contains? (get p "schema") "default")))
                (str method " " path " " (get p "name")))
            (when (= "path" (get p "in"))
              (is (true? (get p "required")) "a path parameter is always required"))))
        (testing "and the ones that matter"
          (let [patients (parameters :get "/api/patients")]
            (is (every? #(false? (get-in patients [% "required"])) ["family" "sort" "limit" "offset"]))
            (is (= "family" (get-in patients ["sort" "schema" "default"]))))
          (let [series (parameters :get "/api/patients/{id}/observations")]
            (is (true? (get-in series ["measurement" "required"])) "which measurement has no default")
            (is (true? (get-in series ["id" "required"])))
            (is (every? #(false? (get-in series [% "required"])) ["from" "to" "sort" "limit"]))))))))

(deftest a-body-is-described-by-what-the-schema-checks
  (with-document
    (fn [doc]
      (testing "the alternatives of a body that depends on one of its fields do not overlap"
        (let [alternatives (fn [method path]
                             (get-in (operation doc method path) ["requestBody" "content" "application/json" "schema" "oneOf"]))
              dispatch (fn [branch field]
                         (let [d (get-in branch ["properties" field])]
                           (or (get d "const") (set (get d "enum")))))]
          (let [changes (alternatives :patch "/api/appointments/{id}")]
            (is (= [["confirm"] ["reschedule"] ["cancel"]] (mapv #(vector (dispatch % "action")) changes))
                "one for each action, and none that exists only to word an error"))
          (let [readings (alternatives :post "/api/patients/{id}/observations")]
            (is (= 2 (count readings)))
            (is (= "blood-pressure" (dispatch (first readings) "measurement")))
            (is (not (contains? (dispatch (second readings) "measurement") "blood-pressure"))))))
      (testing "a time says it is one, and a text says how long it may be"
        (let [at (get-in (operation doc :post "/api/patients/{id}/observations")
                         ["requestBody" "content" "application/json" "schema" "oneOf" 1 "properties" "at"])
              why (get-in (operation doc :post "/api/appointments")
                          ["requestBody" "content" "application/json" "schema" "properties" "description"])]
          (is (= "date-time" (get at "format")))
          (is (= "string" (get at "type")))
          (is (= (:text-length lifecycle/limits) (get why "maxLength"))
              "what the lifecycle accepts, not the net of the schema"))))))

(deftest the-sign-in-goes-to-the-identity-provider
  (with-document
    (fn [doc]
      (let [scheme (get-in doc ["components" "securitySchemes" "pulse-sso"])
            flow (get-in scheme ["flows" "authorizationCode"])]
        (is (= "oauth2" (get scheme "type")))
        (is (= (str h/issuer "/protocol/openid-connect/auth") (get flow "authorizationUrl")))
        (is (= (str h/issuer "/protocol/openid-connect/token") (get flow "tokenUrl")))
        (is (contains? (get flow "scopes") "openid"))
        (testing "and every operation that needs it names a scheme the document defines"
          (doseq [[_ methods] (get doc "paths")
                  [_ op] methods
                  requirement (get op "security")
                  scheme-name (keys requirement)]
            (is (contains? (get-in doc ["components" "securitySchemes"]) scheme-name))
            (is (every? #(contains? (get flow "scopes") %) (get requirement scheme-name)))))))))

(deftest the-shared-problem-is-what-the-api-sends
  (with-api {}
    (fn [api]
      (let [doc (document api)
            schema (get-in doc ["components" "schemas" "Problem"])
            problem (:json (call api :get "/api/me"))]
        (is (= #{"type" "title" "status"} (set (get schema "required"))))
        (is (every? (set (keys (get schema "properties"))) (map name (keys problem)))
            "every member the api sends is one the document describes")
        (is (every? (set (map name (keys problem))) (get schema "required"))
            "and every one it says is always there is")
        (testing "with the members a 400 adds"
          (let [r (call api :get "/api/patients" :token (h/token-for :admin) :query "limit=0")]
            (is (= 400 (:status r)))
            (is (every? (set (keys (get schema "properties"))) (map name (keys (:json r)))))
            (is (map? (get-in r [:json :errors])))))))))

;; ---------------------------------------------------------------------------
;; Swagger UI

(deftest swagger-ui-is-served-under-docs
  (with-api {}
    (fn [api]
      (testing "/docs goes to /docs/, which is the page"
        (let [r (call api :get "/docs")]
          (is (= 302 (:status r)))
          (is (= "/docs/" (header r "Location"))))
        (let [r (call api :get "/docs/")]
          (is (= 200 (:status r)))
          (is (= "text/html" (content-type r)))
          (is (str/includes? (:text r) "swagger-ui-bundle.js"))
          (is (str/includes? (:text r) "swagger-initializer.js"))
          (is (not (re-find #"<script(?![^>]*\ssrc=)[^>]*>" (:text r)))
              "no script written into the page: its policy allows scripts from this server only")))
      (testing "what the page loads"
        (doseq [[path type] [["/docs/swagger-ui.css" "text/css"]
                             ["/docs/swagger-ui-bundle.js" "text/javascript"]
                             ["/docs/swagger-ui-standalone-preset.js" "text/javascript"]
                             ["/docs/index.css" "text/css"]]]
          (let [r (call api :get path)]
            (is (= 200 (:status r)) path)
            (is (= type (content-type r)) path)))))))

(deftest the-sign-in-comes-back-to-a-page-that-exists
  ;; The jar that reitit-swagger-ui pulls in lacks these two, and the sign-in ends in a 404
  ;; without them: the api depends on a newer one (apps/api/deps.edn).
  (with-api {}
    (fn [api]
      (let [page (call api :get "/docs/oauth2-redirect.html")
            script (call api :get "/docs/oauth2-redirect.js")]
        (is (= 200 (:status page)))
        (is (= "text/html" (content-type page)))
        (is (str/includes? (:text page) "oauth2-redirect.js"))
        (is (= 200 (:status script)))
        (is (= "text/javascript" (content-type script)))
        (is (str/includes? (:text script) "swaggerUIRedirectOauth2"))))))

(deftest the-page-starts-with-our-script-not-the-jars
  (with-api {}
    (fn [api]
      (let [r (call api :get "/docs/swagger-initializer.js")
            script (:text r)]
        (is (= 200 (:status r)))
        (is (= "text/javascript" (content-type r)))
        (is (str/includes? script "initOAuth"))
        (is (str/includes? script "usePkceWithAuthorizationCodeGrant: true"))
        (is (str/includes? script "\"/openapi.json\""))
        (is (not (str/includes? script "petstore")) "the jar's script is not what is served")))))

(deftest the-page-is-told-where-the-document-is-and-not-to-ask-anyone-about-it
  (with-api {}
    (fn [api]
      (let [r (call api :get "/docs/config.json")]
        (is (= 200 (:status r)))
        (is (= "application/json" (content-type r)))
        (is (= "/openapi.json" (get-in r [:json :url])))
        (is (contains? (:json r) :validatorUrl) "present...")
        (is (nil? (get-in r [:json :validatorUrl])) "...and null: the online validator is not called")))))

(def ^:private realm
  (delay (with-open [rdr (io/reader (io/file "infra/keycloak/realm/pulse-realm.json"))]
           (json/read-value rdr json/keyword-keys-object-mapper))))

(defn- redirect-allowed?
  "Whether Keycloak accepts `uri` for a client with these redirect URIs, where a trailing `*` is a
  prefix."
  [patterns uri]
  (boolean (some (fn [pattern]
                   (if (str/ends-with? pattern "*")
                     (str/starts-with? uri (subs pattern 0 (dec (count pattern))))
                     (= pattern uri)))
                 patterns)))

(deftest the-sign-in-is-what-the-realm-offers
  ;; The realm is generated from tools/realm.clj and committed as JSON. The page names the
  ;; client it signs in with and the address it comes back to; if the realm stops agreeing, the
  ;; sign-in fails in a browser and nowhere else.
  (with-api {}
    (fn [api]
      (let [script (:text (call api :get "/docs/swagger-initializer.js"))
            client-id (second (re-find #"clientId:\s*\"([^\"]+)\"" script))
            client (first (filter #(= client-id (:clientId %)) (:clients @realm)))]
        (is (= "pulse-docs" client-id))
        (is (some? client) "a client of that name exists")
        (is (true? (:publicClient client)) "there is no secret in a page")
        (is (true? (:standardFlowEnabled client)))
        (is (false? (:implicitFlowEnabled client)))
        (is (false? (:directAccessGrantsEnabled client)))
        (is (= "S256" (get-in client [:attributes (keyword "pkce.code.challenge.method")]))
            "Keycloak insists on PKCE, as the page uses it")
        (is (redirect-allowed? (:redirectUris client) "http://localhost:4000/docs/oauth2-redirect.html")
            "Swagger UI returns to oauth2-redirect.html next to the page")
        (is (not (redirect-allowed? (:redirectUris client) "http://localhost:4000/api/me")))
        (is (some #{"http://localhost:4000"} (:webOrigins client))
            "so that Keycloak answers the page's request for the token")
        (is (some #(= "pulse-api" (get-in % [:config :included.custom.audience])) (:protocolMappers client))
            "the token it gets is for this api")))))

(deftest the-page-answers-to-reading-and-nothing-else
  (with-api {}
    (fn [api]
      (is (= 200 (:status (call api :head "/docs/"))))
      (doseq [method [:post :put :patch :delete]]
        (is (= 404 (:status (call api method "/docs/"))) (str method)))
      (doseq [method [:post :put :patch :delete]]
        (is (= 404 (:status (call api method "/docs/swagger-initializer.js"))) (str method))))))

(deftest only-the-page-and-what-is-under-it-is-the-page
  (with-api {}
    (fn [api]
      (testing "a path that only starts like it"
        (doseq [path ["/docsindex.html" "/docs-secret" "/docsx/" "/documents"]]
          (is (= 404 (:status (call api :get path))) path)))
      (testing "a way out of the directory, spelt as a client may spell it"
        (doseq [path ["/docs/../pulse/api/config.edn"
                      "/docs/%2e%2e/pulse/api/config.edn"
                      "/docs/..%2fpulse/api/config.edn"
                      "/docs/%2E%2E%2Fpulse%2Fapi%2Fconfig.edn"
                      "/docs/./../pulse/api/config.edn"
                      "/docs/swagger-ui/../../pulse/api/config.edn"
                      "/docs/..\\pulse\\api\\config.edn"
                      "/docs//pulse/api/config.edn"]
                :let [r (call api :get path)]]
          (is (not= 200 (:status r)) path)
          (is (not (str/includes? (:text r) "pulse.api/server")) (str path " does not give the configuration")))))))
