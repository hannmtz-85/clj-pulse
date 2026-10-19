(ns pulse.platform.http.client
  "A client of another Pulse service, for a service that is itself a client of the realm (ADR-0021):
  the api as the scheduler calls it, the scheduler as the api will call it. Each request carries
  the caller's own access token, got from its keeper (`pulse.platform.token`); what comes back is
  data. The keeper may be a service's, made once, or a person's, as the portal's (ADR-0028): a
  client is plain data, so the portal puts the keeper of the person's session in it for each call
  (`(assoc client :token keeper)`). A client with no keeper sends no `Authorization` header, which is
  what a call to a public endpoint, such as readiness, needs. JVM only.

  Three ways to fail, each an `ex-info` with a `:type` and no secret in it:

    `::unreachable`            the service was not reached (`:reason` is `:connect` or `:timeout`)
    `::failed`                 it answered, and not with a success (`:status`, and the `:problem` it
                               gave, `{:title :detail}`, when it gave one: RFC 9457); also a success
                               whose body is not JSON (`:reason :bad-response`)
    `:pulse.platform.token/unavailable`  there was no token to send, so nothing was sent"
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.token :as token])
  (:import (java.io IOException)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpRequest$Builder
                          HttpResponse HttpResponse$BodyHandlers HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(defn client
  "A client of the service at `:base-url`, such as \"http://localhost:4000\". Options: `:token`, the
  keeper of the caller's access token (none: no `Authorization` header is sent), `:timeout-ms` for a
  whole request (5000) and `:connect-timeout-ms` for the connection (2000)."
  [{:keys [base-url token timeout-ms connect-timeout-ms]
    :or {timeout-ms 5000 connect-timeout-ms 2000}}]
  {:base-url (str/replace (str base-url) #"/+$" "")
   :token token
   :timeout-ms timeout-ms
   :http (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofMillis connect-timeout-ms))
             (.build))})

(defn close!
  "Stops `client` taking requests; the ones in flight run to completion."
  [client]
  (.shutdown ^HttpClient (:http client)))

;; ---------------------------------------------------------------------------
;; Addresses

(defn- encode [s]
  (-> (URLEncoder/encode (str s) StandardCharsets/UTF_8)
      ;; in a path a space is %20, and + is itself
      (str/replace "+" "%20")))

(defn path
  "A path from segments, each encoded, so that an id with a slash or a space in it stays one segment:
  (path \"api\" \"patients\" id \"observations\")."
  [& segments]
  (str "/" (str/join "/" (map encode segments))))

(defn query-string
  "A query string from a map, or nil for an empty one. A sequential value repeats the parameter, a
  nil one leaves it out. In a query string a plus is a space, which is how `URLEncoder` writes one
  and how a server reads it, so a plus in a value (an offset such as +02:00) comes out as %2B."
  [params]
  (let [pairs (for [[k v] params
                    v (if (sequential? v) v [v])
                    :when (some? v)]
                (str (URLEncoder/encode (name k) StandardCharsets/UTF_8) "="
                     (URLEncoder/encode (str v) StandardCharsets/UTF_8)))]
    (when (seq pairs)
      (str/join "&" pairs))))

;; ---------------------------------------------------------------------------
;; Requests

(def ^:private max-problem-text 200)

(defn- short-text [x]
  (when (string? x)
    (if (> (count x) max-problem-text) (str (subs x 0 max-problem-text) "...") x)))

(defn- json-body [^HttpResponse response]
  (let [text ^String (.body response)]
    (when-not (str/blank? text)
      (try (json/read-value text json/keyword-keys-object-mapper)
           (catch Exception _ ::not-json)))))

(defn- problem-of
  "`{:title :detail}` of a problem that the service gave, or nil."
  [body]
  (when (map? body)
    (let [problem (cond-> {}
                    (short-text (:title body)) (assoc :title (short-text (:title body)))
                    (short-text (:detail body)) (assoc :detail (short-text (:detail body))))]
      (when (seq problem) problem))))

(def ^:private own-headers
  "The headers this client sets itself, in lower case. A caller's request never sets them: a client
  with no token must not send an `Authorization` that someone else put in a request."
  #{"authorization" "accept" "content-type"})

(defn request
  "Makes a request and returns `{:status n :body parsed}`, whatever the status: the body is parsed
  JSON with keyword keys, or nil when there is none. `request` is `{:method :get :path \"/api/me\"
  :query {...} :body {...} :headers {...}}`; a body is sent as JSON, and `:headers` are more
  headers to send, such as `X-Request-Id` to carry the id of a request from one service to the
  other. The ones this client sets itself (`Authorization`, `Accept` and `Content-Type`) are not
  for the caller to change, and are left out of `:headers`. Throws what is said at the top for a
  service that cannot be reached or a token that cannot be had."
  [{:keys [base-url timeout-ms ^HttpClient http] own-token :token} {:keys [method path query body headers]}]
  (let [query (when (map? query) (query-string query))
        uri (URI/create (str base-url path (when query (str "?" query))))
        publisher (if (some? body)
                    (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body))
                    (HttpRequest$BodyPublishers/noBody))
        ^HttpRequest$Builder builder (reduce-kv (fn [^HttpRequest$Builder b k v]
                                                  (if (own-headers (str/lower-case (name k)))
                                                    b
                                                    (.header b (name k) (str v))))
                                                (-> (HttpRequest/newBuilder uri)
                                                    (.timeout (Duration/ofMillis timeout-ms)))
                                                (or headers {}))
        ^HttpRequest$Builder builder (.setHeader builder "Accept" "application/json")
        ^HttpRequest$Builder builder (if (some? own-token)
                                       (.setHeader builder "Authorization" (str "Bearer " (token/access-token own-token)))
                                       builder)
        request (-> (cond-> builder (some? body) (.setHeader "Content-Type" "application/json"))
                    (.method (str/upper-case (name method)) publisher)
                    (.build))
        ^HttpResponse response (try
                                 (.send http request (HttpResponse$BodyHandlers/ofString))
                                 (catch HttpTimeoutException e
                                   (throw (ex-info (str "The service did not answer in time: " (name method) " " path)
                                                   {:type ::unreachable :reason :timeout :path path} e)))
                                 (catch IOException e
                                   (throw (ex-info (str "The service could not be reached: " (name method) " " path)
                                                   {:type ::unreachable :reason :connect :path path} e))))]
    {:status (.statusCode response)
     :body (json-body response)}))

(defn call
  "Like `request`, for a request that is meant to succeed: returns the parsed body of a 2xx answer
  (nil when there is none) and throws an `::failed` for anything else, with the `:status`, the
  `:path` and the `:problem` the service gave."
  [client {:keys [method path] :as the-request}]
  (let [{:keys [status body]} (request client the-request)
        description (str (str/upper-case (name method)) " " path)]
    (cond
      (not (<= 200 status 299))
      (throw (ex-info (str description " answered " status)
                      (cond-> {:type ::failed :status status :path path}
                        (problem-of body) (assoc :problem (problem-of body)))))

      (= ::not-json body)
      (throw (ex-info (str description " answered " status " with a body that is not JSON")
                      {:type ::failed :status status :path path :reason :bad-response}))

      :else body)))

(defn get-json
  "The parsed body of a successful GET of `path`, with the `query` map if there is one."
  ([client path] (get-json client path nil))
  ([client path query]
   (call client {:method :get :path path :query query})))

(defn post-json
  "The parsed body of a successful POST of `body`, as JSON, to `path`."
  ([client path] (post-json client path nil))
  ([client path body]
   (call client {:method :post :path path :body body})))
