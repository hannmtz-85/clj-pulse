(ns pulse.platform.http.cors
  "CORS, in house (ADR-0018): an allow-list of exact origins, the preflight answered before the
  router, and the headers that let a browser application on an allowed origin read the answers.
  Plain Ring middleware, with no policy of its own: the origins are the service's to name.

  Never `*`, never an origin that is not on the list, and never credentials: the api authenticates
  with a bearer token in the `Authorization` header, which is not a credential in the sense of
  CORS (cookies, HTTP authentication, client certificates)."
  (:require [clojure.string :as str]
            [pulse.platform.http.headers :as headers]
            [pulse.platform.http.problem :as problem]))

(def origin-re
  "An origin as a browser writes it: a scheme, a host and perhaps a port, with no path, no trailing
  slash and no wildcard."
  #"https?://(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])(?::\d{1,5})?")

(defn origin?
  "Whether `s` is an origin as `origin-re` describes it."
  [s]
  (and (string? s) (boolean (re-matches origin-re s))))

(def defaults
  {:allowed-methods ["GET" "POST" "PATCH"]
   :allowed-headers ["Authorization" "Content-Type" "X-Request-Id"]
   :exposed-headers ["X-Request-Id" "WWW-Authenticate"]
   :max-age-s 600})

(defn- add-vary
  "`response` with `names` added to its `Vary` header, which keeps what it already says."
  [response & names]
  (let [response-headers (:headers response)
        k (headers/header-key response-headers "Vary")
        existing (when k (get response-headers k))
        values (distinct (concat (when existing (str/split existing #"\s*,\s*")) names))]
    (assoc response :headers (-> response-headers
                                 (dissoc k)
                                 (assoc "Vary" (str/join ", " values))))))

(defn- preflight?
  "A preflight is an OPTIONS request that names the origin and the method it is about."
  [{:keys [request-method headers]}]
  (and (= :options request-method)
       (contains? headers "origin")
       (contains? headers "access-control-request-method")))

(defn- preflight-response
  [origin {:keys [allowed-methods allowed-headers max-age-s]}]
  (-> {:status 204
       :headers {"Access-Control-Allow-Origin" origin
                 "Access-Control-Allow-Methods" (str/join ", " allowed-methods)
                 "Access-Control-Allow-Headers" (str/join ", " allowed-headers)
                 "Access-Control-Max-Age" (str max-age-s)}}
      (add-vary "Origin" "Access-Control-Request-Method" "Access-Control-Request-Headers")))

(defn wrap-cors
  "Middleware that lets the browser applications on `:allowed-origins`, a set of origins written
  exactly as a browser sends them, call the service. An origin that is not on the list gets
  nothing: no CORS headers, and a 403 problem to a preflight so that the reason can be found. A
  request with no `Origin` is not about CORS and passes untouched. Options besides the origins,
  with their defaults in `defaults`: `:allowed-methods`, `:allowed-headers`, `:exposed-headers`
  and `:max-age-s`. Throws at once for an origin that is not an origin (a wildcard, a path, a
  trailing slash), since a mistake here is a hole."
  [handler {:keys [allowed-origins] :as options}]
  (when-let [bad (seq (remove origin? allowed-origins))]
    (throw (ex-info "CORS origins must be exact origins such as http://localhost:3000"
                    {:type ::invalid-origin :origins (vec (sort (map str bad)))})))
  (let [allowed? (set allowed-origins)
        options (merge defaults options)
        exposed (str/join ", " (:exposed-headers options))]
    (fn [request]
      (let [origin (get-in request [:headers "origin"])]
        (cond
          (nil? origin)
          (handler request)

          (and (preflight? request) (allowed? origin))
          (preflight-response origin options)

          (preflight? request)
          (-> (problem/response 403 {:detail "This origin may not call the api." :request request})
              (add-vary "Origin"))

          :else
          (let [response (handler request)]
            (cond-> (some-> response (add-vary "Origin"))
              (and response (allowed? origin))
              (headers/assoc-missing {"Access-Control-Allow-Origin" origin
                                      "Access-Control-Expose-Headers" exposed}))))))))
