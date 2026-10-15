(ns pulse.scheduler.authz
  "Who may call the scheduler (ADR-0020): by role and by action, as data, and deny by default.

  Every endpoint declares what it needs in its own route data, under `:authz`:

    :public                no credentials needed
    {:action :jobs/read}   an action that `rules` knows

  `rules` says which roles may do each action. The middleware built by `middleware` authenticates
  the request (the token must be for this service: see the audience in `pulse.scheduler.config`),
  asks `permits?`, audits the decision and either passes the request on, with `:caller` attached,
  or answers 401, 403 or 503. An endpoint with no valid `:authz` makes `check-routes!` fail at
  start-up, and should one be reached anyway, it answers 403 to everybody."
  (:require [clojure.string :as str]
            [pulse.platform.http.auth :as auth]
            [pulse.platform.http.problem :as problem]
            [pulse.platform.http.router :as router]
            [taoensso.telemere :as t]))

(def roles
  "The application roles of the `pulse` realm (see tools/realm.clj). A token carries them in
  `realm_access.roles` next to Keycloak's own (`default-roles-pulse`, `offline_access`, ...),
  which mean nothing here and are dropped."
  #{"patient" "practitioner" "clinic-admin" "rules-engine"})

(def rules
  "The roles that may do each action. The names are those the api uses for the same things
  (`:jobs/read` is the action of its `GET /api/admin/jobs`)."
  {:jobs/read #{"clinic-admin"}
   :jobs/run #{"clinic-admin"}})

(defn caller
  "Who is calling, from the `claims` of a verified token (keyword keys, as `jwt/verify` returns
  them): `:subject`, `:username`, `:client` (the OAuth client the token was issued to) and
  `:roles`, a set of the application roles in the token, as strings."
  [claims]
  {:subject (:sub claims)
   :username (:preferred_username claims)
   :client (:azp claims)
   :roles (into #{} (filter roles) (get-in claims [:realm_access :roles]))})

(defn permits?
  "Whether `caller` may do `action`. An action with no rule is permitted to nobody. Pure."
  [caller action]
  (boolean (some (get rules action #{}) (:roles caller))))

(defn valid-spec?
  "Whether `spec` is a usable `:authz` value: `:public`, or a map naming an action that `rules`
  knows."
  [spec]
  (or (= :public spec)
      (and (map? spec) (contains? rules (:action spec)))))

(defn check-routes!
  "Returns `router` when every endpoint has a valid `:authz`, otherwise throws, naming the
  endpoints that do not. Called when the router is built, so a forgotten rule is a start-up
  failure and not a hole."
  [router]
  (let [bad (vec (router/endpoints-without router valid-spec?))]
    (if (seq bad)
      (throw (ex-info (str "Endpoints without a valid :authz (deny by default): " (pr-str bad))
                      {:type ::missing-authz :endpoints bad}))
      router)))

;; ---------------------------------------------------------------------------
;; Audit

(defn- audit!
  "One event per decision: who, what, with what outcome, on which request. Denials are warnings."
  [request caller action outcome]
  (t/event! :pulse.audit/access
            {:level (if (= :allowed outcome) :info :warn)
             :data {:actor {:subject (:subject caller)
                            :username (:username caller)
                            :roles (vec (sort (:roles caller)))
                            :client (:client caller)}
                    :action action
                    :outcome outcome
                    :method (some-> (:request-method request) name str/upper-case)
                    :path (:uri request)}}))

;; ---------------------------------------------------------------------------
;; Middleware

(defn- forbidden [request]
  (problem/response 403 {:detail "You are not allowed to do this." :request request}))

(defn- protect
  "Middleware for an endpoint that needs `action`: authenticate, authorize, audit, and attach the
  `:caller` to the request."
  [auth-deps {:keys [action]}]
  (fn [handler]
    (fn [request]
      (let [{:keys [claims response]} (auth/authenticate auth-deps request)]
        (or response
            (let [caller (caller claims)]
              (if (permits? caller action)
                (do (audit! request caller action :allowed)
                    (handler (assoc request :caller caller)))
                (do (audit! request caller action :denied)
                    (forbidden request)))))))))

(defn- deny-all
  "What an endpoint without a valid `:authz` gets, in case `check-routes!` was not used."
  [_handler]
  (fn [request]
    (t/event! :pulse.authz/misconfigured-endpoint {:level :error :data {:path (:uri request)}})
    (forbidden request)))

(defn middleware
  "Reitit middleware that enforces `:authz`, compiled once per endpoint when the router is
  built. `auth-deps` is `{:verifier v :realm r}`, see `pulse.platform.http.auth/authenticate`.
  Public endpoints get no middleware at all."
  [auth-deps]
  {:name ::authz
   :compile (fn [route-data _opts]
              (let [spec (:authz route-data)]
                (cond
                  (= :public spec) nil
                  (valid-spec? spec) (protect auth-deps spec)
                  :else deny-all)))})
