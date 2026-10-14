(ns pulse.platform.http.auth
  "Bearer-token authentication for a resource server, on top of `pulse.platform.jwt`.

  `authenticate` does not decide who may do what; it only answers who is calling, or why the
  call cannot proceed: a 401 with a `WWW-Authenticate` challenge when the credentials are
  missing or bad, and a 503 when the key set is unavailable, which is our fault and not the
  client's (see `jwt/errors`). Authorization stays with the service."
  (:require [pulse.platform.http.problem :as problem]
            [pulse.platform.jwt :as jwt]
            [taoensso.telemere :as t]))

(def ^:private retry-after-s
  "How soon to try again when there are no keys: about the length of the key fetch throttle."
  "10")

(defn- rejection
  "The response for a request that failed authentication with error `type`. The client gets
  the generic text of `jwt/errors`; the specific reason goes to the log, never the token."
  [realm request type message]
  (let [{:keys [status description]} (jwt/errors type)]
    (t/event! :pulse.auth/rejected
              {:level (if (= 503 status) :warn :info)
               :data {:reason type :message message :path (:uri request)}})
    (problem/response status
                      {:detail description
                       :headers (if (= 401 status)
                                  {"WWW-Authenticate" (jwt/challenge realm type)}
                                  {"Retry-After" retry-after-s})
                       :request request})))

(defn- unauthenticated
  "The response for a request with no (usable) bearer credentials: the bare challenge, with no
  error code (RFC 6750 section 3.1)."
  [realm request]
  (t/event! :pulse.auth/rejected {:level :info :data {:reason :no-credentials :path (:uri request)}})
  (problem/response 401 {:detail "Authentication is required."
                         :headers {"WWW-Authenticate" (jwt/challenge realm)}
                         :request request}))

(defn authenticate
  "Checks the bearer token of `request` with `verifier` (from `jwt/verifier`). Returns
  `{:claims claims}` when it is valid, otherwise `{:response response}`, the 401 or 503 to
  send. `realm` goes into the `WWW-Authenticate` challenge."
  [{:keys [verifier realm]} request]
  (if-let [token (jwt/bearer-token (get-in request [:headers "authorization"]))]
    (let [{:keys [ok error]} (jwt/verify verifier token)]
      (if ok
        {:claims ok}
        {:response (rejection realm request (:type error) (:message error))}))
    {:response (unauthenticated realm request)}))
