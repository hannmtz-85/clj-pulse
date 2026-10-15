(ns pulse.platform.http.router
  "The Reitit setup that the Pulse services with a JSON interface share (ADR-0011, ADR-0020): the
  options of a router, the answers for what the router does not know, the problems that failures
  with a meaning of their own become, and the traversal that finds the endpoints that have not
  said who may call them. Mechanisms only: who may do what, and which headers a response gets,
  are for each service to say."
  (:require [clojure.string :as str]
            [malli.error :as me]
            [muuntaja.core :as m]
            [pulse.platform.http.problem :as problem]
            [reitit.coercion.malli :as malli]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [taoensso.telemere :as t]))

(def json-only
  "Muuntaja's defaults also speak EDN and Transit; the services speak JSON until a client needs more."
  (m/create (update m/default-options :formats select-keys ["application/json"])))

(defn options
  "The options for `reitit.ring/router`. `authz` is the Reitit middleware that authenticates and
  authorizes an endpoint from its route data; it comes first, so nobody gets to feed JSON to the
  parser without a valid token. After it come content negotiation, query and form parameters,
  and the checking of requests and responses against the schemas in the route data.

  Reitit answers OPTIONS on every route by default, with no `:authz`. That is switched off: every
  endpoint is one the service declared."
  [authz]
  {::ring/default-options-endpoint nil
   :data {:coercion malli/coercion
          :muuntaja json-only
          :middleware [authz
                       muuntaja/format-middleware
                       parameters/parameters-middleware
                       coercion/coerce-request-middleware
                       coercion/coerce-response-middleware]}})

;; ---------------------------------------------------------------------------
;; Who may call what: the traversal behind "deny by default"

(def ^:private http-methods [:get :head :post :put :delete :connect :options :trace :patch])

(defn endpoints
  "`[method path authz]` for every endpoint of `router`. A method's own `:authz` wins over the
  one it inherits from the route or its parents; `:any` is a handler given for all methods."
  [router]
  (for [[path data] (r/routes router)
        method (concat (filter data http-methods) (when (:handler data) [:any]))]
    [method path (or (:authz (get data method)) (:authz data))]))

(defn endpoints-without
  "The endpoints of `router` whose `:authz` does not satisfy `valid?`, each as a string such as
  \"get /api/me\". A service calls it when the router is built and refuses to start if the
  answer is not empty: an endpoint that says nothing about who may call it is a hole."
  [router valid?]
  (for [[method path spec] (endpoints router)
        :when (not (valid? spec))]
    (str (name method) " " path)))

;; ---------------------------------------------------------------------------
;; What the router does not know, and what goes wrong inside it

(defn- allow-header
  "The methods a matched route supports, for a 405."
  [request]
  (->> (:result (::r/match request))
       (keep (fn [[method endpoint]] (when (and endpoint (not= :any method)) method)))
       (map (comp str/upper-case name))
       sort
       (str/join ", ")))

(def default-handler
  "What answers a path that matches no route (404), a method the route does not have (405, with
  `Allow`) and a format the client cannot be given (406), as problems."
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

(def exception-handlers
  "Failures that carry their own meaning, by the `:type` of their ex-data, for
  `pulse.platform.http.middleware/wrap-exceptions`."
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
