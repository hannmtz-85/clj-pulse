(ns pulse.platform.token
  "A service's own access token, got with the OAuth client-credentials grant, kept and renewed
  before it expires (ADR-0021). JVM only.

  `source` makes the keeper of one client's token; `access-token` gives the token to put in an
  `Authorization: Bearer` header. There is no thread: a token is asked for when a call finds there is
  none or that it is near its end, and not before.

    fresh        more than the margin left: returned as it is, with no I/O
    near its end the margin has begun: the first caller asks for a new token and the others go on
                 using this one, which is still valid, without waiting
    none         never asked, or expired: one caller asks and the rest wait for that answer

  A token is never returned after it has expired. When Keycloak cannot be asked, a caller that has
  a valid token gets it; one that has none gets an `ex-info` of type `::unavailable`, and nobody asks
  again until the retry delay is over."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.config :as config]
            [taoensso.telemere :as t])
  (:import (java.io IOException)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Clock Duration Instant)
           (java.util Base64)
           (java.util.concurrent.locks ReentrantLock)))

;; ---------------------------------------------------------------------------
;; Asking the token endpoint

(defn- unavailable
  "An exception of type `::unavailable`. `reason` says why, `data` adds to its ex-data. Neither the
  message nor the data may hold the secret, the request or the body of an answer."
  ([reason message data] (unavailable reason message data nil))
  ([reason message data cause]
   (ex-info message (merge {:type ::unavailable :reason reason} data) cause)))

(defn- form-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- basic-credentials
  "The credentials of HTTP Basic as RFC 6749 section 2.3.1 has them: the id and the secret are
  form-encoded, then joined with a colon and Base64-encoded."
  [client-id client-secret]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (str (form-encode client-id) ":" (form-encode client-secret))
                              StandardCharsets/UTF_8)))

(def ^:private oauth-error-code #"[A-Za-z_]{1,64}")

(defn- read-answer
  "The grant in an answer of the token endpoint, as `{:access-token :expires-in}` (the checking of
  its values is for the keeper), or an `::unavailable` for any other answer."
  [^HttpResponse response]
  (let [status (.statusCode response)
        body (try (json/read-value ^String (.body response) json/keyword-keys-object-mapper)
                  (catch Exception _ nil))]
    (cond
      (= 200 status)
      (let [{:keys [access_token expires_in token_type]} (when (map? body) body)]
        (if (or (nil? token_type) (and (string? token_type) (.equalsIgnoreCase "Bearer" ^String token_type)))
          {:access-token access_token :expires-in expires_in}
          (throw (unavailable :bad-response "The token endpoint did not give a bearer token" {:status status}))))

      (<= 400 status 499)
      (throw (unavailable :rejected "The token endpoint refused the client"
                          (cond-> {:status status}
                            ;; the OAuth error code, such as invalid_client: a short fixed vocabulary
                            (and (map? body) (string? (:error body)) (re-matches oauth-error-code (:error body)))
                            (assoc :error (:error body)))))

      :else
      (throw (unavailable :server "The token endpoint failed" {:status status})))))

(defn http-fetch
  "A function of no arguments that asks the token endpoint for a token with the client-credentials
  grant and returns `{:access-token s :expires-in seconds}`, or throws an `::unavailable`. Options:

    `:token-uri`           the URL of the token endpoint
    `:client-id`           the client's id
    `:client-secret`       its secret, a `pulse.platform.config/Secret` or a string
    `:scope`               optional
    `:timeout-ms`          for the whole request (5000)
    `:connect-timeout-ms`  for the connection (2000)"
  [{:keys [token-uri client-id client-secret scope timeout-ms connect-timeout-ms]
    :or {timeout-ms 5000 connect-timeout-ms 2000}}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                   (.build))
        body (str "grant_type=client_credentials" (when scope (str "&scope=" (form-encode scope))))
        authorization (str "Basic " (basic-credentials client-id (config/reveal client-secret)))]
    (fn []
      (let [request (-> (HttpRequest/newBuilder (URI/create token-uri))
                        (.timeout (Duration/ofMillis timeout-ms))
                        (.header "Content-Type" "application/x-www-form-urlencoded")
                        (.header "Accept" "application/json")
                        (.header "Authorization" authorization)
                        (.POST (HttpRequest$BodyPublishers/ofString body))
                        (.build))
            response (try
                       (.send client request (HttpResponse$BodyHandlers/ofString))
                       (catch HttpTimeoutException e
                         (throw (unavailable :timeout "The token endpoint did not answer in time" {} e)))
                       (catch IOException e
                         (throw (unavailable :connect "The token endpoint could not be reached" {} e))))]
        (read-answer response)))))

;; ---------------------------------------------------------------------------
;; Keeping the token

(defn source
  "The keeper of one client's token. Options: those of `http-fetch`, or `:fetch`, a function of
  no arguments that returns `{:access-token :expires-in}` instead (for tests), and

    `:clock`            a `java.time.Clock` (the system clock, UTC)
    `:renew-before-ms`  how long before it expires a token is renewed (60000), at most half of its
                        lifetime
    `:retry-delay-ms`   how long after a failure nobody asks again (5000)"
  [{:keys [fetch clock renew-before-ms retry-delay-ms client-id]
    :or {renew-before-ms 60000 retry-delay-ms 5000}
    :as options}]
  {:fetch (or fetch (http-fetch options))
   :clock (or clock (Clock/systemUTC))
   :renew-before-ms renew-before-ms
   :retry-delay-ms retry-delay-ms
   :client-id client-id
   :lock (ReentrantLock.)
   ;; nil, then {:token :expires-at :renew-at :retry-at :last-reason}
   :state (atom nil)})

(defn- standing
  "How the token in `state` stands at `now`: `:fresh`, `:near-its-end` or `:none` (there is none,
  or it has expired)."
  [{:keys [token expires-at renew-at]} ^Instant now]
  (cond
    (nil? token) :none
    (not (.isBefore now ^Instant expires-at)) :none
    (.isBefore now ^Instant renew-at) :fresh
    :else :near-its-end))

(defn- may-ask?
  "Whether the retry delay after the last failure is over."
  [{:keys [retry-at]} ^Instant now]
  (or (nil? retry-at) (not (.isBefore now ^Instant retry-at))))

(defn- valid-grant? [{:keys [access-token expires-in]}]
  (and (string? access-token)
       (not (str/blank? access-token))
       (number? expires-in)
       (pos? expires-in)))

(defn- renew!
  "Asks for a new token and keeps it. The caller holds the lock. Returns the new state, or records
  when to ask again and throws an `::unavailable`. An interruption is not a failure of the endpoint."
  [{:keys [fetch ^Clock clock state retry-delay-ms renew-before-ms client-id]}]
  (let [asked-at (.instant clock)]
    (try
      (let [grant (fetch)]
        (when-not (valid-grant? grant)
          (throw (unavailable :bad-response "The token endpoint did not give an access token" {})))
        (let [lifetime-ms (long (* 1000 (:expires-in grant)))
              margin-ms (min renew-before-ms (quot lifetime-ms 2))
              expires-at (.plusMillis asked-at lifetime-ms)
              renewed {:token (:access-token grant)
                       :expires-at expires-at
                       :renew-at (.minusMillis expires-at margin-ms)}]
          (reset! state renewed)
          (t/event! :pulse.token/renewed
                    {:level :info
                     :data {:client client-id
                            :expires-in-s (:expires-in grant)
                            :renew-in-s (quot (- lifetime-ms margin-ms) 1000)}})
          renewed))
      (catch InterruptedException e
        (throw e))
      (catch Throwable e
        (let [failure (if (= ::unavailable (:type (ex-data e)))
                        e
                        (unavailable :unexpected "The token could not be obtained" {} e))
              {:keys [reason status error]} (ex-data failure)]
          (swap! state assoc
                 :retry-at (.plusMillis (.instant clock) retry-delay-ms)
                 :last-reason reason)
          (t/event! :pulse.token/failed
                    {:level :warn
                     :error (when (= :unexpected reason) e)
                     :data (cond-> {:client client-id :reason reason}
                             status (assoc :status status)
                             error (assoc :error error))})
          (throw failure))))))

(defn- renew-if-near-its-end!
  "Renews the token if no one else is doing so and the retry delay is over. It leaves the failure
  to the log: the caller has a valid token."
  [{:keys [^ReentrantLock lock ^Clock clock state] :as source}]
  (when (.tryLock lock)
    (try
      (let [now (.instant clock)
            current @state]
        (when (and (= :near-its-end (standing current now)) (may-ask? current now))
          (try
            (renew! source)
            (catch clojure.lang.ExceptionInfo e
              (when-not (= ::unavailable (:type (ex-data e)))
                (throw e))))))
      (finally
        (.unlock lock)))))

(defn- renew-when-there-is-none!
  "Waits for the lock, so that of many callers one asks and the others use its answer."
  [{:keys [^ReentrantLock lock ^Clock clock state] :as source}]
  (.lockInterruptibly lock)
  (try
    (let [now (.instant clock)
          current @state]
      (if (= :none (standing current now))
        (if (may-ask? current now)
          (:token (renew! source))
          (throw (unavailable :backing-off "The token endpoint failed lately and is not asked again yet"
                              {:last-reason (:last-reason current)})))
        (:token current)))
    (finally
      (.unlock lock))))

(defn access-token
  "The access token of the client, as a string, never one that has expired. Throws an `ex-info`
  of type `::unavailable`, with a `:reason`, when there is none to be had; see the namespace."
  [{:keys [^Clock clock state] :as source}]
  (let [current @state
        now (.instant clock)]
    (case (standing current now)
      :fresh (:token current)
      :near-its-end (do (renew-if-near-its-end! source)
                        ;; Asking can take as long as the timeout, so look again: the token that
                        ;; was valid a moment ago may have expired meanwhile
                        (let [after @state]
                          (if (= :none (standing after (.instant clock)))
                            (renew-when-there-is-none! source)
                            (:token after))))
      :none (renew-when-there-is-none! source))))

(defn available?
  "Whether a token can be had now, from the cache or from the token endpoint."
  [source]
  (try
    (boolean (access-token source))
    (catch clojure.lang.ExceptionInfo e
      (if (= ::unavailable (:type (ex-data e)))
        false
        (throw e)))))
