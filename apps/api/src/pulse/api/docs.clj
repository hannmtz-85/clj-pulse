(ns pulse.api.docs
  "The api's description, and the page that shows it (ADR-0017).

  `openapi-route` is `GET /openapi.json`: an OpenAPI 3.1 document that Reitit generates from the
  route data, so what it says about paths, methods, parameters, bodies and successful responses
  is what the route table already says for routing and for checking requests. What the table says
  elsewhere, `:authz` and `:problems`, `document` adds to each operation: the security
  requirement, the roles that may call it with the scope each has, read from `authz/rules` so that
  the document cannot say what the code does not, and the errors it may answer. The document is
  generated when it is first asked for and kept: the routes do not change while the api runs.

  `ui-handler` serves Swagger UI at `/docs`, from a jar. Neither is an endpoint in the sense of
  `authz/check-routes!`: the document is a public route that is not in itself, the page is a
  handler outside the router, and neither holds clinical data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pulse.api.authz :as authz]
            [reitit.core :as r]
            [reitit.openapi :as openapi]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.trie :as trie]))

;; ---------------------------------------------------------------------------
;; What the document says on its own

(def scheme
  "The name of the security scheme in the document."
  "pulse-sso")

(def ^:private problem-schema
  {:type "object"
   :description "An error, as problem details (RFC 9457). A client can rely on `status`, `title` and, for a 400 or a 422, `errors`."
   :required ["type" "title" "status"]
   :properties
   {:type {:type "string" :description "Always `about:blank`: the status says it all."}
    :title {:type "string" :description "The phrase of the HTTP status."}
    :status {:type "integer" :description "The HTTP status."}
    :detail {:type "string" :description "What went wrong this time, written to be shown."}
    :instance {:type "string" :description "The path of the request."}
    :requestId {:type "string" :description "The id of the request, also in the `X-Request-Id` header: quote it when asking for help."}
    :errors {:type "object"
             :description "For a 400 or a 422: the fields that are wrong, each with what is wrong with it."
             :additionalProperties {:type "array" :items {:type "string"}}}}})

(defn- problem
  "A response that carries a problem, for the reason `description` gives."
  [description & {:as more}]
  (merge {:description description
          :content {"application/problem+json" {:schema {:$ref "#/components/schemas/Problem"}}}}
         more))

(def ^:private standard-problems
  "`components.responses`: the errors that the operations share, by name."
  {"BadRequest" (problem "The request is not shaped as the endpoint accepts it: a missing field, a value of the wrong type or outside its limits, a body that is not JSON. `errors` says which fields are wrong and why.")
   "Unauthorized" (problem "No access token, or one that cannot be accepted: it has expired, was not issued for this api, or is not signed by the identity provider. `WWW-Authenticate` says which."
                           :headers {"WWW-Authenticate" {:description "How to authenticate, and for a token that was refused, why: `Bearer realm=\"pulse-api\", error=\"invalid_token\", error_description=\"…\"`."
                                                         :schema {:type "string"}}})
   "Forbidden" (problem "The token is valid, but its owner may not do this: their role does not allow it, or the record is outside their scope.")
   "NotFound" (problem "There is no such resource.")
   "ServerError" (problem "The api could not complete the request. `502`: a service it depends on could not be reached or failed: the FHIR server, or for the administration endpoints the scheduler. `503`: the identity provider's keys are not available, and `Retry-After` says when to try again. `504`: that service did not answer in time. `500`: an unexpected error.")})

(def ^:private description
  (str/join
   "\n\n"
   ["Remote patient monitoring and appointment coordination on FHIR R4. This is the resource server of Pulse: it checks the access token that Keycloak issued, decides what the caller may see and do, and reads and writes the FHIR server for them. All the data is synthetic."
    "**Try it.** *Authorize* signs you in as one of the demo users (the public client `pulse-docs`, Authorization Code with PKCE) and the requests you send carry the token. Sign in as a patient, as a practitioner and as the clinic administrator to see the same request answered differently."
    "**Access.** An operation lists the roles that may call it and the scope each has: `own` (records about the caller), `panel` (records of the caller's patients, those whose general practitioner they are), `all` (every record) or `identifiers` (every patient, identifiers only). A role that is not listed gets a 403, and so does a record outside the scope."
    "**Errors** are problem details (RFC 9457, `application/problem+json`). A 400 says that the request is not shaped as the endpoint accepts it, a 422 that it has the right shape and its values cannot be right, and a 409 that the state of the resource does not allow the action."]))

(def ^:private tags
  [{:name "Health" :description "Probes for the platform. No sign-in."}
   {:name "Identity" :description "Who the api takes the caller to be."}
   {:name "Patients" :description "The panel, and one patient's record and summary."}
   {:name "Observations" :description "Readings of vital signs: blood pressure, heart rate, oxygen saturation, glucose, weight and temperature."}
   {:name "Appointments" :description "Asking for an appointment, and a clinician's answer."}
   {:name "Alerts" :description "What the rules engine raised about a patient, and acknowledging it."}
   {:name "Administration" :description "How the clinic's own machinery is doing, for the administrator: the scheduler's jobs."}])

(defn- root
  "What describes the api as a whole, in the route data of the document: Reitit puts it at the top
  of what it generates. `issuer` is the identity provider's, as a browser reaches it."
  [issuer]
  (let [issuer (str/replace issuer #"/+$" "")]
    {:openapi "3.1.0"
     :info {:title "Pulse API" :version "0.1.0" :description description}
     :tags tags
     :components
     {:securitySchemes
      {scheme {:type "oauth2"
               :description "Sign in with your Pulse account (Authorization Code with PKCE). `pulse-docs` is a public client: leave `client_secret` empty. The access token that comes back is sent as `Authorization: Bearer …`."
               :flows {:authorizationCode {:authorizationUrl (str issuer "/protocol/openid-connect/auth")
                                           :tokenUrl (str issuer "/protocol/openid-connect/token")
                                           :scopes {"openid" "Sign in. The access token that results is what the api accepts."}}}}}
      :responses standard-problems}}))

;; ---------------------------------------------------------------------------
;; What an operation gets from the route data

(def ^:private role-order [:any :patient :practitioner :clinic-admin :rules-engine])

(defn access
  "The roles that may perform `action` and the scope each has, as `[[\"patient\" \"own\"] ...]`,
  from `authz/rules`. A role that `role-order` does not know goes last, never missing."
  [action]
  (->> (get authz/rules action)
       (sort-by (fn [[role _]] (let [i (.indexOf ^java.util.List role-order role)] (if (neg? i) (count role-order) i))))
       (mapv (fn [[role scope]] [(name role) (name scope)]))))

(defn- access-text [pairs]
  (str "**Who may call it**, and the scope each role has:\n\n"
       (str/join "\n" (for [[role scope] pairs]
                        (str "- " (if (= "any" role) "any signed-in caller" (str "`" role "`")) ": `" scope "`")))))

(defn- paragraphs [& texts]
  (str/join "\n\n" (remove str/blank? texts)))

(defn- response-ref [component]
  {:$ref (str "#/components/responses/" component)})

(defn- by-status
  "Orders the responses of an operation as a reader expects: `200` before `401`, `5XX` last."
  [a b]
  (compare (str a) (str b)))

(defn- optional-when-defaulted
  "A parameter that has a default need not be sent. The route data declares it required all the same,
  because malli applies a default only to a key that is there; the document should not say that a
  client must send it."
  [parameter]
  (cond-> parameter
    (contains? (:schema parameter) :default) (assoc :required false)))

(defn- document-operation
  "`operation`, as Reitit generated it for the route data `endpoint` (the method's own) and
  `data` (the route's), with what `:authz` and `:problems` add."
  [operation endpoint data]
  (let [spec (or (:authz endpoint) (:authz data))
        protected? (map? spec)
        parameters (merge (:parameters data) (:parameters endpoint))
        pairs (when protected? (access (:action spec)))
        derived (cond-> {}
                  protected? (assoc 401 (response-ref "Unauthorized")
                                    403 (response-ref "Forbidden")
                                    "5XX" (response-ref "ServerError"))
                  (and protected? (seq (select-keys parameters [:query :body :form :multipart]))) (assoc 400 (response-ref "BadRequest"))
                  (and protected? (:resource spec)) (assoc 404 (response-ref "NotFound")))
        problems (update-vals (merge (:problems data) (:problems endpoint)) problem)]
    (cond-> operation
      (:parameters operation) (update :parameters (partial mapv optional-when-defaulted))
      protected? (-> (update :description paragraphs (access-text pairs))
                     (assoc :security [{scheme ["openid"]}]
                            :x-pulse-access (apply array-map (mapcat identity pairs))))
      (seq (merge derived problems)) (update :responses #(into (sorted-map-by by-status) (merge derived problems %))))))

(defn- openapi-path
  "The path as the document writes it, by Reitit's own rule: `/patients/:id` is `/patients/{id}`."
  [path opts]
  (-> path (trie/normalize opts) (str/replace #"\{\*" "{")))

(defn document
  "`generated`, the document Reitit made from `router`, with each operation documented from the
  route data (see `document-operation`), the shared problem schema, and nothing of the library's
  own bookkeeping. Operations keep the order of the routes."
  [generated router]
  (let [opts (r/options router)
        routes (into {} (map (fn [[path data]] [(openapi-path path opts) data])) (r/routes router))]
    (-> generated
        (dissoc :x-id)
        (update-in [:components :schemas] #(merge {"Problem" problem-schema} %))
        (update :paths
                (fn [paths]
                  (apply array-map
                         (mapcat (fn [[path operations]]
                                   (let [data (get routes path)]
                                     [path (into {} (map (fn [[method operation]]
                                                           [method (document-operation operation (get data method) data)]))
                                                 operations)]))
                                 paths)))))))

;; ---------------------------------------------------------------------------
;; The document and the page

(defn openapi-route
  "The route of `GET /openapi.json`. `options` is `{:issuer \"https://…/realms/pulse\"}`, the
  identity provider as a browser reaches it, where the sign-in of Swagger UI goes."
  [{:keys [issuer]}]
  (let [generate (openapi/create-openapi-handler)
        kept (atom nil)]
    ["/openapi.json"
     {:get {:authz :public
            :no-doc true
            :openapi (root issuer)
            :handler (fn [request]
                       {:status 200
                        :body (or @kept
                                  (reset! kept (document (:body (generate request)) (::r/router request))))})}}]))

(def ^:private docs-path "/docs")

(def ^:private initializer-resource "pulse/api/swagger-initializer.js")

(defn- initializer-response
  "The script that starts Swagger UI. It is ours and not the jar's, because Swagger UI takes the
  client of the sign-in and PKCE only from a call in script (ADR-0017). Served by name, so that
  which of the two is found does not depend on the order of the classpath."
  []
  (let [script (slurp (or (io/resource initializer-resource)
                          (throw (ex-info "The script of Swagger UI is missing" {:resource initializer-resource}))))]
    {:status 200
     :headers {"Content-Type" "text/javascript; charset=utf-8"}
     :body script}))

(defn ui-handler
  "A Ring handler that serves Swagger UI under `/docs`, for GET and HEAD, and answers nil to
  everything else so that the next handler does. The page, its script and its style come from
  the `ring-swagger-ui` jar; `/docs/config.json` tells it where the document is."
  []
  (let [serve (swagger-ui/create-swagger-ui-handler {:path docs-path
                                                     :url "/openapi.json"
                                                     :config {:validatorUrl nil}})
        initializer (initializer-response)]
    (fn [{:keys [request-method uri] :as request}]
      (when (and (contains? #{:get :head} request-method)
                 (or (= docs-path uri) (str/starts-with? (str uri) (str docs-path "/"))))
        (if (= (str docs-path "/swagger-initializer.js") uri)
          initializer
          (serve request))))))
