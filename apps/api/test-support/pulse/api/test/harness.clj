(ns pulse.api.test.harness
  "What the api's handler tests share: a fake identity provider (real signatures, the key set
  served over HTTP), an in-memory FHIR server holding the demo cohort, the api's Ring handler
  wired to them, tokens for the demo people, and `call` to make a request and read the answer.
  No server and no network beyond localhost.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
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

(def signing-key (idp/signing-key "k1"))

(def issuer "http://idp.test/realms/pulse")

(def ^:private cohort (delay (synthetic/generate (synthetic/cohort))))

(defn demo-cohort
  "A fresh in-memory FHIR server holding the demo cohort."
  []
  (memory/memory-client @cohort))

(defn now-s [] (quot (System/currentTimeMillis) 1000))

(defn roles-claim
  "The `realm_access` claim of a token for the application `roles`, with Keycloak's own."
  [& roles]
  {:realm_access {:roles (into ["default-roles-pulse" "offline_access"] (map name) roles)}})

(defn access-token
  "A token signed like Keycloak's. Takes the claims to change, as keyword arguments or one
  map; a nil value removes a claim."
  [& {:as overrides}]
  (idp/mint signing-key
            (into {} (remove (comp nil? val))
                  (merge {:iss issuer :aud ["pulse-api" "account"] :sub "u-1" :typ "Bearer"
                          :azp "pulse-spa" :preferred_username "ana.garcia@pulse.test"
                          :iat (now-s) :exp (+ (now-s) 300)
                          :fhirUser "Patient/pt-ana"
                          :realm_access {:roles ["default-roles-pulse" "patient" "offline_access"]}}
                         overrides))))

(def ^:private people
  "The demo users of the realm (tools/realm.clj), as the claims their tokens carry."
  {:ana {:sub "u-ana" :preferred_username "ana.garcia@pulse.test" :fhirUser "Patient/pt-ana" :roles [:patient]}
   :liam {:sub "u-liam" :preferred_username "liam.chen@pulse.test" :fhirUser "Patient/pt-liam" :roles [:patient]}
   :maya {:sub "u-maya" :preferred_username "maya.patel@pulse.test" :fhirUser "Patient/pt-maya" :roles [:patient]}
   :rivera {:sub "u-rivera" :preferred_username "dr.rivera@pulse.test" :fhirUser "Practitioner/pr-rivera" :roles [:practitioner]}
   :okafor {:sub "u-okafor" :preferred_username "dr.okafor@pulse.test" :fhirUser "Practitioner/pr-okafor" :roles [:practitioner]}
   :admin {:sub "u-admin" :preferred_username "admin@pulse.test" :fhirUser nil :roles [:clinic-admin]}
   :scheduler {:sub "u-scheduler" :preferred_username "service-account-pulse-scheduler" :fhirUser nil
               :azp "pulse-scheduler" :roles [:rules-engine]}})

(defn token-for
  "A token for one of the demo `people` (:ana, :rivera, :admin, :scheduler, ...). Further
  arguments are claims to change."
  [who & {:as overrides}]
  (let [{:keys [roles] :as person} (get people who)]
    (access-token (merge (dissoc person :roles) (apply roles-claim roles) overrides))))

(defn- refused []
  (throw (ex-info "connection refused: secret-host:8090" {})))

(def unreachable-fhir
  "A FHIR server that cannot be reached, whose exceptions say too much."
  (reify fc/FhirClient
    (read-resource [_ _ _] (refused))
    (search [_ _ _] (refused))
    (put! [_ _] (refused))
    (delete! [_ _ _] (refused))
    (transact! [_ _] (refused))
    (validate [_ _] (refused))))

(defn spy
  "A FHIR client that answers like `client` and records every read, search and write made through
  it in the atom `calls`, as `[:read type id]`, `[:search type params]` and `[:put type id]`."
  [client calls]
  (reify fc/FhirClient
    (read-resource [_ resource-type id]
      (swap! calls conj [:read resource-type id])
      (fc/read-resource client resource-type id))
    (search [_ resource-type params]
      (swap! calls conj [:search resource-type params])
      (fc/search client resource-type params))
    (put! [_ resource]
      (swap! calls conj [:put (:resourceType resource) (:id resource)])
      (fc/put! client resource))
    (delete! [_ resource-type id] (fc/delete! client resource-type id))
    (transact! [_ bundle] (fc/transact! client bundle))
    (validate [_ resource] (fc/validate client resource))))

(defn- misbehave!
  "Applies the behaviour for `resource-type`: `:fail` throws, `[:slow ms]` waits first."
  [behaviours resource-type]
  (let [behaviour (get behaviours resource-type)]
    (cond
      (= :fail behaviour) (refused)
      (and (vector? behaviour) (= :slow (first behaviour))) (Thread/sleep (long (second behaviour))))))

(defn misbehaving
  "A FHIR client that answers like `client`, except for the resource types in `behaviours`, a
  map of type to `:fail` (throws) or `[:slow ms]` (waits first). It applies to reads, searches
  and writes."
  [client behaviours]
  (reify fc/FhirClient
    (read-resource [_ resource-type id]
      (misbehave! behaviours resource-type)
      (fc/read-resource client resource-type id))
    (search [_ resource-type params]
      (misbehave! behaviours resource-type)
      (fc/search client resource-type params))
    (put! [_ resource]
      (misbehave! behaviours (:resourceType resource))
      (fc/put! client resource))
    (delete! [_ resource-type id] (fc/delete! client resource-type id))
    (transact! [_ bundle] (fc/transact! client bundle))
    (validate [_ resource] (fc/validate client resource))))

(defn failing-writes
  "A FHIR client that answers reads and searches like `client` and refuses every write, as a server
  that is up but cannot store would, with a message that says too much."
  [client]
  (reify fc/FhirClient
    (read-resource [_ resource-type id] (fc/read-resource client resource-type id))
    (search [_ resource-type params] (fc/search client resource-type params))
    (put! [_ _] (refused))
    (delete! [_ resource-type id] (fc/delete! client resource-type id))
    (transact! [_ _] (refused))
    (validate [_ resource] (fc/validate client resource))))

(defn with-api
  "Calls `(f {:handler h :idp server})` with a fake identity provider and the api's handler
  wired to it. Options: `:fhir` (default: the demo cohort in memory), `:routes` (extra routes,
  added to the api's), `:summary-timeout-ms` (the deadline of a patient summary) and `:clock` (a
  `java.time.Clock`, to say what time it is when a reading is recorded)."
  [{:keys [fhir routes summary-timeout-ms clock]} f]
  (let [server (idp/start! [signing-key])
        source (jwt/jwks-source {:uri (:uri server) :min-refresh-interval-ms 200})
        verifier (jwt/verifier {:jwks-source source :issuer issuer :audience "pulse-api" :token-type "Bearer"})
        api-routes (routes/routes {:verifier verifier :fhir (or fhir (demo-cohort))
                                   :summary-timeout-ms summary-timeout-ms
                                   :clock clock})]
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

(defn call
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

(defn header [response name]
  (get-in response [:headers name]))

(defn content-type [response]
  (some-> (header response "Content-Type") (str/split #";") first))

(defn audit-events
  "The `:pulse.audit/access` events logged while serving a response made by `call`."
  [response]
  (filter #(= :pulse.audit/access (:id %)) (:signals response)))

(defn audit-writes
  "The `:pulse.audit/write` events logged while serving a response made by `call`."
  [response]
  (filter #(= :pulse.audit/write (:id %)) (:signals response)))
