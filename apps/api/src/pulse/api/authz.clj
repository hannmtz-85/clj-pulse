(ns pulse.api.authz
  "Authorization by scope, deny by default (ADR-0004, ADR-0012).

  Every endpoint declares what it needs in its own route data, under `:authz`:

    :public                               no credentials needed
    {:action :patient/read                the action, a key of `rules`
     :resource (fn [request scope] ...)}  optional: for an endpoint on one resource, builds the
                                          descriptor `authorize` judges (see below)

  `rules` is Annex A of the plan as data: for each action, the scope each role has. `authorize`
  is a pure function of the caller, the action and a description of the resource. The
  middleware built by `middleware` authenticates the request, asks `authorize`, audits the
  decision and either passes the request on, with `:identity`, `:scope` and the `:descriptor` of
  the resource attached, or answers 401, 403, 404 or 503.

  The default is no. An endpoint with no valid `:authz` makes `check-routes!` fail at start-up,
  and should one be reached anyway, it answers 403 to everybody."
  (:require [pulse.api.audit :as audit]
            [pulse.api.identity :as identity]
            [pulse.fhir.reference :as ref]
            [pulse.platform.http.auth :as auth]
            [pulse.platform.http.problem :as problem]
            [pulse.platform.http.router :as platform-router]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; The matrix

(def rules
  "What each role may do with each action, as a scope:

    :own          records whose patient is the caller's own `fhirUser`
    :panel        records of patients whose `generalPractitioner` includes the caller
    :all          every record
    :identifiers  every patient, but only their identifiers

  A role that is not listed has no access; `:any` stands for every authenticated caller. The
  rows follow Annex A of the plan, plus `:appointment/read`, implied by Annex B. Whose
  appointments a caller may act on is `:appointment/manage`; what each kind of person may do to
  them (a patient may only cancel, and only until it begins) is the lifecycle's to say, in
  `pulse.fhir.appointment/transitions` (ADR-0027)."
  {:identity/read {:any :all}
   :patient/list {:practitioner :panel :clinic-admin :all :rules-engine :identifiers}
   :patient/read {:patient :own :practitioner :panel :clinic-admin :all}
   :observation/read {:patient :own :practitioner :panel :clinic-admin :all :rules-engine :all}
   :observation/record {:patient :own :practitioner :panel :rules-engine :all}
   :appointment/read {:patient :own :practitioner :panel :clinic-admin :all}
   :appointment/request {:patient :own}
   :appointment/manage {:patient :own :practitioner :panel :clinic-admin :all}
   :alert/read {:practitioner :panel :clinic-admin :all}
   :alert/ack {:practitioner :panel :clinic-admin :all}
   :alert/write {:rules-engine :all}
   :jobs/read {:clinic-admin :all}})

(def ^:private scope-rank
  "Which scope wins when a caller's roles give different ones for the same action."
  {:own 1 :identifiers 2 :panel 3 :all 4})

(defn- usable?
  "A scope about the caller's own FHIR resource only counts if they have one of the right type.
  Without that, a practitioner whose token lacks `fhirUser` would have no panel to restrict to."
  [scope caller]
  (case scope
    :own (= "Patient" (get-in caller [:fhir-user :type]))
    :panel (= "Practitioner" (get-in caller [:fhir-user :type]))
    true))

(defn scope-of
  "The widest scope `caller` (an identity) has for `action`, or nil for none, including when
  there is no rule for the action. Pure."
  [caller action]
  (when-let [rule (get rules action)]
    (let [scopes (->> (cons :any (:roles caller))
                      (keep rule)
                      (filter #(usable? % caller)))]
      (when (seq scopes)
        (apply max-key scope-rank scopes)))))

(defn- fhir-ref [caller]
  (some-> (:fhir-user caller) ref/->str))

(defn permits?
  "Whether `scope` covers `resource` for `caller`. `resource` describes it as
  `{:patient \"Patient/pt-ana\" :practitioners #{\"Practitioner/pr-rivera\"}}`: the patient it
  belongs to, and the patient's practitioners. Pure."
  [scope caller resource]
  (let [me (fhir-ref caller)]
    (boolean
     (case scope
       (:all :identifiers) true
       :own (and me (= me (:patient resource)))
       :panel (and me (contains? (:practitioners resource) me))
       false))))

(defn authorize
  "The decision for `caller` performing `action` on `resource`: `{:allow? true :scope s}`, or
  `{:allow? false :reason r}` with the reason for the logs and never for the client. With a nil
  `resource` only the role is checked, and the scope in the answer is what the endpoint must
  restrict itself to (a collection returns only what the scope covers). Pure."
  [caller action resource]
  (let [scope (scope-of caller action)]
    (cond
      (not (contains? rules action)) {:allow? false :reason :no-rule-for-action}
      (nil? scope) {:allow? false :reason :role-not-allowed}
      (nil? resource) {:allow? true :scope scope}
      (permits? scope caller resource) {:allow? true :scope scope}
      :else {:allow? false :reason :outside-scope :scope scope})))

;; ---------------------------------------------------------------------------
;; Route data

(defn valid-spec?
  "Whether `spec` is a usable `:authz` value: `:public`, or a map naming an action that `rules`
  knows and, if it has one, a `:resource` function."
  [spec]
  (or (= :public spec)
      (and (map? spec)
           (contains? rules (:action spec))
           (let [resource (:resource spec)] (or (nil? resource) (ifn? resource))))))

(defn check-routes!
  "Returns `router` when every endpoint has a valid `:authz`, otherwise throws, naming the
  endpoints that do not. Called when the router is built, so a forgotten rule is a start-up
  failure and not a hole."
  [router]
  (let [bad (vec (platform-router/endpoints-without router valid-spec?))]
    (if (seq bad)
      (throw (ex-info (str "Endpoints without a valid :authz (deny by default): " (pr-str bad))
                      {:type ::missing-authz :endpoints bad}))
      router)))

;; ---------------------------------------------------------------------------
;; Middleware

(defn- forbidden [request]
  (problem/response 403 {:detail "You are not allowed to do this." :request request}))

(defn- protect
  "Middleware for an endpoint that needs `action`: authenticate, find the caller's scope, build
  the resource descriptor if the endpoint has one (only when the caller has a scope at all, so
  nobody triggers a lookup they have no right to), authorize, audit, and attach `:identity`, `:scope`
  and the `:descriptor` to the request. The descriptor is what the endpoint's `:resource` function
  built, and it may carry more than `authorize` looks at, such as the resource itself, so that an
  endpoint that changes it works on the very version the decision was about."
  [auth-deps {:keys [action resource]}]
  (fn [handler]
    (fn [request]
      (let [{:keys [claims response]} (auth/authenticate auth-deps request)]
        (or response
            (let [caller (identity/from-claims claims)
                  scope (scope-of caller action)
                  descriptor (when (and scope resource) (resource request scope))]
              (if (and scope resource (nil? descriptor))
                (do (audit/access! request caller action :not-found {:scope scope :reason :no-such-resource} nil)
                    (problem/response 404 {:detail "No such resource." :request request}))
                (let [decision (authorize caller action descriptor)]
                  (if (:allow? decision)
                    (do (audit/access! request caller action :allowed decision descriptor)
                        (handler (assoc request :identity caller :scope (:scope decision) :descriptor descriptor)))
                    (do (audit/access! request caller action :denied decision descriptor)
                        (forbidden request)))))))))))

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
