(ns pulse.web.csrf
  "Requests that change something are the portal's own, or they are refused (ADR-0028, point 6). Two checks, which an attack has
  to pass both of:

    the origin   a request that changes something (not a GET, a HEAD or an OPTIONS) and that says where it comes from, in its
                 `Origin` header, must say it is from the portal. A browser says so for a form it posts and for a request
                 htmx makes; one that comes from another site says that site, and one from a page that has no origin
                 (a sandboxed frame, a file) says `null`, which is refused too. A request with no `Origin` header is not
                 from a browser that sends it, so the token decides
    the token    the page of a signed-in person carries the CSRF token of their session, in a hidden field of a form or in the
                 `X-CSRF-Token` header that htmx sends (set once, with `hx-headers` on the body). The token is made at the
                 sign-in and lives in the session, so it is compared with the session's own, in constant time. It is
                 checked by `ring-anti-forgery`, with this strategy: nothing is written to a session by asking for it

  The first check is for every request, and runs before anything else; the second is for the routes that are a patient's, after
  the check of who is signed in, so that a person whose session ended is sent to sign in again, and is not told that a token
  they never had was wrong. `SameSite=Lax` on the cookie is a third line, and not one this relies on."
  (:require [pulse.platform.http.headers :as headers]
            [pulse.web.errors :as errors]
            [pulse.web.session :as session]
            [pulse.web.values :as values]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.anti-forgery.strategy :as strategy]
            [taoensso.telemere :as t]))

(def safe-methods
  "The methods that change nothing."
  #{:get :head :options})

(defn origin-allowed?
  "Whether `request` may come from `allowed`, the origin of the portal (\"http://localhost:3300\"): it changes nothing, or says
  nothing of where it comes from, or says it is from the portal. `Origin: null` is a request that is from nowhere."
  [allowed {:keys [request-method headers]}]
  (let [origin (get headers "origin")]
    (or (contains? safe-methods request-method)
        (nil? origin)
        (= allowed origin))))

(defn wrap-origin
  "Refuses, with the page for it, a request that changes something and does not come from `base-url`, the address the portal is
  served at. Only the origin of the address is compared (\"http://localhost:3300\")."
  [handler base-url]
  (let [allowed (headers/origin-of base-url)]
    (fn [request]
      (if (origin-allowed? allowed request)
        (handler request)
        (do (t/event! :pulse.web/request-refused
                      {:level :warn
                       :data {:reason :origin :method (name (:request-method request)) :path (:uri request)
                              ;; what the browser said, which an attacker chose: short, and as a string
                              :origin (let [origin (str (get-in request [:headers "origin"]))]
                                        (subs origin 0 (min 100 (count origin))))}})
            (errors/failure :request-refused request))))))

(def strategy
  "The strategy of `ring-anti-forgery` for this portal: the token is the one made at the sign-in, in the session, and nothing
  is written to a session by asking for it."
  (reify strategy/Strategy
    (get-token [_ request] (session/csrf-token-of request))
    (valid-token? [_ request token] (values/same? token (session/csrf-token-of request)))
    (write-token [_ _ response _] response)))

(defn- refuse-token [request]
  (t/event! :pulse.web/request-refused
            {:level :warn
             :data {:reason :csrf-token :method (name (:request-method request)) :path (:uri request)}})
  (errors/failure :request-refused request))

(def middleware
  "Reitit middleware that checks the CSRF token of a request that changes something on the routes that are a patient's
  (`:authz :patient`). It must come after the check of who is signed in and after the one that reads the parameters of a form,
  where the token may be."
  {:name ::csrf
   :compile (fn [route-data _opts]
              (when (= :patient (:authz route-data))
                (fn [handler]
                  (anti-forgery/wrap-anti-forgery handler {:strategy strategy
                                                           :error-handler refuse-token}))))})
