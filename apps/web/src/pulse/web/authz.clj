(ns pulse.web.authz
  "Who may see what in the portal (ADR-0028): as data on each route, and deny by default.

  Every route declares what it needs under `:authz`:

    :public    anybody
    :patient   a patient who is signed in

  A route that says neither makes `check-routes!` fail at start-up, and should one be reached anyway, it answers as if
  nobody were allowed. A person who is not signed in is sent to the sign-in, and brought back to the page they asked
  for: a 303 to `/sign-in` for a page, and for a request that htmx made, a 401 with an `HX-Redirect` header, so that htmx
  leaves the page and never puts a sign-in into a fragment of it.

  Who is signed in is the `:person` in the session of the request. The session itself, the sign-in and the cookie are the
  next step; until then nobody is."
  (:require [pulse.platform.http.client :as http]
            [pulse.platform.http.router :as router]
            [pulse.web.errors :as errors]
            [pulse.web.return-to :as return-to]
            [taoensso.telemere :as t]))

(def specs
  "What a route may say under `:authz`."
  #{:public :patient})

(defn valid-spec?
  "Whether `spec` is a usable `:authz` value."
  [spec]
  (contains? specs spec))

(defn check-routes!
  "Returns `router` when every endpoint has a valid `:authz`, otherwise throws, naming the endpoints that do not. Called
  when the router is built, so a forgotten rule is a start-up failure and not a hole."
  [router]
  (let [bad (vec (router/endpoints-without router valid-spec?))]
    (if (seq bad)
      (throw (ex-info (str "Endpoints without a valid :authz (deny by default): " (pr-str bad))
                      {:type ::missing-authz :endpoints bad}))
      router)))

(defn person
  "The person who is signed in, as the session of `request` has them, or nil."
  [request]
  (get-in request [:session :person]))

(defn htmx?
  "Whether htmx made `request`."
  [request]
  (= "true" (get-in request [:headers "hx-request"])))

(defn sign-in-address
  "Where to send a person who must sign in before they can have the page that `request` asks for."
  [request]
  (let [back (return-to/of-request request)
        query (http/query-string {:return-to (when (not= return-to/home back) back)})]
    (str "/sign-in" (when query (str "?" query)))))

(defn- unauthenticated
  "The answer to a request for a page of a patient from somebody who is not signed in."
  [request]
  (let [address (sign-in-address request)]
    (if (htmx? request)
      {:status 401 :headers {"HX-Redirect" address} :body ""}
      {:status 303 :headers {"Location" address} :body ""})))

(defn- protect
  "Middleware for a route that needs a patient who is signed in."
  [handler]
  (fn [request]
    (if (person request)
      (handler request)
      (unauthenticated request))))

(defn- deny-all
  "What a route without a valid `:authz` gets, in case `check-routes!` was not used."
  [_handler]
  (fn [request]
    (t/event! :pulse.authz/misconfigured-endpoint {:level :error :data {:path (:uri request)}})
    (errors/failure :forbidden request)))

(def middleware
  "Reitit middleware that enforces `:authz`, compiled once for each route when the router is built. A public route gets
  none at all."
  {:name ::authz
   :compile (fn [route-data _opts]
              (let [spec (:authz route-data)]
                (cond
                  (= :public spec) nil
                  (= :patient spec) protect
                  :else deny-all)))})
