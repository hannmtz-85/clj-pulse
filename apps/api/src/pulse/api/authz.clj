(ns pulse.api.authz
  "Authorization, deny by default (ADR-0004).

  Every endpoint declares what it needs in its own route data, under `:authz`:

    :public                              no credentials needed
    {:action :patient/read              the action being performed, a key of `rules`
     :resource (fn [request] ...)}      optional: what it is performed on, for `authorize`

  `authorize` is a pure function of the caller, the action and the resource. The middleware
  built by `middleware` authenticates the request, calls it and either passes the request on
  with `:identity` attached or answers 401, 403 or 503.

  The default is no. An endpoint with no valid `:authz` makes `check-routes!` fail at start-up,
  and should one be reached anyway, it answers 403 to everybody."
  (:require [clojure.set :as set]
            [pulse.api.identity :as identity]
            [pulse.platform.http.auth :as auth]
            [pulse.platform.http.problem :as problem]
            [reitit.core :as r]
            [taoensso.telemere :as t]))

(def rules
  "What each action requires. `:roles` is `:any` (any authenticated caller) or a set of roles,
  any one of which is enough. An action that is not listed is denied to everyone."
  {:identity/read {:roles :any}})

(defn authorize
  "The decision for `caller` (an identity) performing `action` on `resource`: `{:allow? true}`
  or `{:allow? false :reason r}`, where the reason is for the logs and never for the client.
  Pure."
  [caller action _resource]
  (let [{:keys [roles]} (get rules action)]
    (cond
      (nil? roles) {:allow? false :reason :no-rule-for-action}
      (= :any roles) {:allow? true}
      (seq (set/intersection (:roles caller) roles)) {:allow? true}
      :else {:allow? false :reason :role-not-allowed})))

;; ---------------------------------------------------------------------------
;; Route data

(defn valid-spec?
  "Whether `spec` is a usable `:authz` value: `:public`, or a map naming an action that
  `rules` knows."
  [spec]
  (or (= :public spec)
      (and (map? spec) (contains? rules (:action spec)))))

(def ^:private http-methods [:get :head :post :put :delete :connect :options :trace :patch])

(defn- endpoints
  "`[method path authz]` for every endpoint of `router`. A method's own `:authz` wins over the
  one it inherits from the route or its parents; `:any` is a handler given for all methods."
  [router]
  (for [[path data] (r/routes router)
        method (concat (filter data http-methods) (when (:handler data) [:any]))]
    [method path (or (:authz (get data method)) (:authz data))]))

(defn check-routes!
  "Returns `router` when every endpoint has a valid `:authz`, otherwise throws, naming the
  endpoints that do not. Called when the router is built, so a forgotten rule is a start-up
  failure and not a hole."
  [router]
  (let [bad (for [[method path spec] (endpoints router)
                  :when (not (valid-spec? spec))]
              (str (name method) " " path))]
    (if (seq bad)
      (throw (ex-info (str "Endpoints without a valid :authz (deny by default): " (pr-str (vec bad)))
                      {:type ::missing-authz :endpoints (vec bad)}))
      router)))

;; ---------------------------------------------------------------------------
;; Middleware

(defn- forbidden [request]
  (problem/response 403 {:detail "You are not allowed to do this." :request request}))

(defn- protect
  "Middleware for an endpoint that needs `action`: authenticate, authorize, attach `:identity`."
  [auth-deps {:keys [action resource]}]
  (fn [handler]
    (fn [request]
      (let [{:keys [claims response]} (auth/authenticate auth-deps request)]
        (if response
          response
          (let [caller (identity/from-claims claims)
                decision (authorize caller action (when resource (resource request)))]
            (if (:allow? decision)
              (handler (assoc request :identity caller))
              (do (t/event! :pulse.authz/denied
                            {:level :info
                             :data {:subject (:subject caller)
                                    :action action
                                    :reason (:reason decision)
                                    :path (:uri request)}})
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
