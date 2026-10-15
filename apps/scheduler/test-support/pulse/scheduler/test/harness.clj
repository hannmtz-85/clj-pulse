(ns pulse.scheduler.test.harness
  "What the scheduler's handler tests share: a fake identity provider (real signatures, the key
  set served over HTTP), an executor in a world of fake time, the scheduler's Ring handler wired
  to them, tokens for the demo people, and `call` to make a request and read the answer. No server
  and no network beyond localhost.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.idp :as idp]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.handler :as handler]
            [pulse.scheduler.routes :as routes]
            [pulse.scheduler.test.time :as time]
            [taoensso.telemere :as t])
  (:import (java.io InputStream)))

(def signing-key (idp/signing-key "k1"))

(def issuer "http://idp.test/realms/pulse")

(def audience "pulse-scheduler")

(defn now-s [] (quot (System/currentTimeMillis) 1000))

(defn roles-claim
  "The `realm_access` claim of a token for the application `roles`, with Keycloak's own."
  [& roles]
  {:realm_access {:roles (into ["default-roles-pulse" "offline_access"] (map name) roles)}})

(defn access-token
  "A token for the scheduler, signed like Keycloak's. Takes the claims to change, as keyword
  arguments or one map; a nil value removes a claim."
  [& {:as overrides}]
  (idp/mint signing-key
            (into {} (remove (comp nil? val))
                  (merge {:iss issuer :aud [audience] :sub "u-1" :typ "Bearer"
                          :azp "pulse-api" :preferred_username "admin@pulse.test"
                          :iat (now-s) :exp (+ (now-s) 300)
                          :realm_access {:roles ["default-roles-pulse" "clinic-admin" "offline_access"]}}
                         overrides))))

(def ^:private people
  "Who might call the scheduler, as the claims their tokens carry (see tools/realm.clj)."
  {:admin {:sub "u-admin" :preferred_username "admin@pulse.test" :roles [:clinic-admin]}
   :rivera {:sub "u-rivera" :preferred_username "dr.rivera@pulse.test" :roles [:practitioner]}
   :ana {:sub "u-ana" :preferred_username "ana.garcia@pulse.test" :roles [:patient]}
   :scheduler {:sub "u-scheduler" :preferred_username "service-account-pulse-scheduler"
               :azp "pulse-scheduler" :roles [:rules-engine]}})

(defn token-for
  "A token for the scheduler for one of the demo `people` (:admin, :rivera, :ana, :scheduler).
  Further arguments are claims to change."
  [who & {:as overrides}]
  (let [{:keys [roles] :as person} (get people who)]
    (access-token (merge (dissoc person :roles) (apply roles-claim roles) overrides))))

(defn with-scheduler
  "Calls `(f {:handler h :idp server :executor e :world w})` with a fake identity provider, an
  executor in a world of fake time that begins at `:start`, and the scheduler's handler wired to
  them, and stops it all afterwards. Options: `:jobs` (none by default), `:workers` (run on the
  spot by default), `:start` (an ISO instant, as in `pulse.scheduler.test.time/world`) and
  `:routes` (extra routes, added to the scheduler's)."
  [{:keys [jobs workers start routes] :or {start "2026-10-15T10:02:13Z"}} f]
  (let [server (idp/start! [signing-key])
        source (jwt/jwks-source {:uri (:uri server) :min-refresh-interval-ms 200})
        verifier (jwt/verifier {:jwks-source source :issuer issuer :audience audience :token-type "Bearer"})
        world (time/world start)
        running (executor/start (or jobs []) {:clock (:clock world)
                                              :scheduler (:scheduler world)
                                              :workers (or workers (time/direct-workers))
                                              :grace-ms 100})]
    (try
      (f {:handler (handler/ring-handler (into (routes/routes {:verifier verifier :executor running}) routes)
                                         {:verifier verifier :realm "pulse-scheduler"
                                          :quiet-paths #{"/healthz" "/readyz"}})
          :idp server
          :executor running
          :world world})
      (finally
        (executor/stop! running)
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
  logged, trapped so nothing is printed) added. Options: `:token`, `:headers` and `:query` (a
  query string)."
  [{:keys [handler]} method uri & {:keys [token headers query]}]
  (let [request (cond-> {:request-method method
                         :uri uri
                         :headers (cond-> (or headers {})
                                    token (assoc "authorization" (str "Bearer " token)))}
                  query (assoc :query-string query))
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
