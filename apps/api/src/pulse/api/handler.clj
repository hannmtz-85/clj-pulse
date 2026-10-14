(ns pulse.api.handler
  "Assembles the api's Ring handler from the started components.

  From the outside in: the request id, the access log and the exception handler wrap
  everything, including requests that match no route. Inside the router, each endpoint passes
  through authorization (which authenticates), then content negotiation, then request and
  response coercion against the schemas in its route data. Authorization comes before the
  request body is parsed: nobody gets to feed JSON to the parser without a valid token."
  (:require [clojure.string :as str]
            [malli.error :as me]
            [muuntaja.core :as m]
            [pulse.api.authz :as authz]
            [pulse.api.routes :as routes]
            [pulse.platform.http.middleware :as mw]
            [pulse.platform.http.problem :as problem]
            [reitit.coercion.malli :as malli]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [taoensso.telemere :as t]))

(def ^:private json-only
  "Muuntaja's defaults also speak EDN and Transit; the api speaks JSON until a client needs more."
  (m/create (update m/default-options :formats select-keys ["application/json"])))

(defn build-router
  "The Reitit router for `routes`. Throws when an endpoint has no valid `:authz`. `auth-deps` is
  `{:verifier v :realm r}`."
  [routes auth-deps]
  (-> (ring/router routes
                   {;; Reitit answers OPTIONS on every route by default, with no `:authz`. Off:
                    ;; every endpoint is one we declared.
                    ::ring/default-options-endpoint nil
                    :data {:coercion malli/coercion
                           :muuntaja json-only
                           :middleware [(authz/middleware auth-deps)
                                        muuntaja/format-middleware
                                        parameters/parameters-middleware
                                        coercion/coerce-request-middleware
                                        coercion/coerce-response-middleware]}})
      authz/check-routes!))

;; ---------------------------------------------------------------------------
;; Errors

(defn- allow-header
  "The methods a matched route supports, for a 405."
  [request]
  (->> (:result (::r/match request))
       (keep (fn [[method endpoint]] (when (and endpoint (not= :any method)) method)))
       (map (comp str/upper-case name))
       sort
       (str/join ", ")))

(def ^:private default-handler
  (ring/create-default-handler
   {:not-found
    (fn [request]
      (problem/response 404 {:detail "No such resource." :request request}))
    :method-not-allowed
    (fn [request]
      (problem/response 405 {:detail "This resource does not support that method."
                             :headers {"Allow" (allow-header request)}
                             :request request}))
    :not-acceptable
    (fn [request]
      (problem/response 406 {:detail "The response cannot be produced in an acceptable format."
                             :request request}))}))

(def ^:private exception-handlers
  "Failures that carry their own meaning, by the `:type` of their ex-data."
  {:reitit.coercion/request-coercion
   (fn [e request]
     ;; Humanized messages say what is wrong with which field, never what the client sent.
     (problem/response 400 {:detail "The request does not match what this endpoint accepts."
                            :extra {:errors (me/humanize (ex-data e))}
                            :request request}))

   ;; A response that does not match its own schema is our bug, not the client's.
   :reitit.coercion/response-coercion
   (fn [e request]
     (t/event! :pulse.http/response-coercion-failed
               {:level :error :error e :data {:path (:uri request)}})
     (problem/response 500 {:detail "An unexpected error occurred." :request request}))

   :muuntaja/decode
   (fn [_e request]
     (problem/response 400 {:detail "The request body is malformed." :request request}))})

(defn ring-handler
  "The Ring handler for `routes`, with the whole pipeline around it. `options` is `:verifier`,
  `:realm` (for `WWW-Authenticate`) and `:quiet-paths` (health probes, logged at debug)."
  [routes {:keys [verifier realm quiet-paths]}]
  (ring/ring-handler
   (build-router routes {:verifier verifier :realm realm})
   default-handler
   {:middleware [mw/wrap-request-id
                 [mw/wrap-access-log {:quiet-paths quiet-paths}]
                 [mw/wrap-exceptions {:handlers exception-handlers}]]}))

(defn create
  "The api's Ring handler. `options` is the `:pulse.api/handler` component: `:verifier`,
  `:fhir`, `:realm` and `:quiet-paths`."
  [{:keys [verifier fhir] :as options}]
  (ring-handler (routes/routes {:verifier verifier :fhir fhir}) options))
