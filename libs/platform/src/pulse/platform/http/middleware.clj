(ns pulse.platform.http.middleware
  "Ring middleware every Pulse service wants, written to be composed in this order, outermost
  first: `wrap-request-id`, `wrap-access-log`, `wrap-exceptions`. They work on plain Ring
  maps and know nothing about routing."
  (:require [clojure.string :as str]
            [pulse.platform.http.problem :as problem]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; Request id

(def ^:private request-id-re #"[A-Za-z0-9._-]{1,64}")

(defn request-id
  "The id of this request: the client's `X-Request-Id` when it is short and plain, otherwise
  a new UUID. A value chosen by the client ends up in logs and in responses, so anything
  else is replaced."
  [request]
  (let [given (get-in request [:headers "x-request-id"])]
    (if (and (string? given) (re-matches request-id-re given))
      given
      (str (random-uuid)))))

(defn wrap-request-id
  "Gives each request an id: on the request as `:request-id`, in the log context of every
  event raised while it is handled, and on the response as `X-Request-Id`."
  [handler]
  (fn [request]
    (let [id (request-id request)]
      (t/with-ctx+ {:request-id id}
        (some-> (handler (assoc request :request-id id))
                (assoc-in [:headers "X-Request-Id"] id))))))

;; ---------------------------------------------------------------------------
;; Access log

(defn wrap-access-log
  "One event per request: method, path, status and duration. Paths in `:quiet-paths` (health
  probes, which are polled all day) are logged at `:debug` instead of `:info`; server errors
  at `:warn`."
  [handler {:keys [quiet-paths] :or {quiet-paths #{}}}]
  (fn [request]
    (let [started (System/nanoTime)
          response (handler request)
          status (:status response)]
      (t/event! :pulse.http/request
                {:level (cond (contains? quiet-paths (:uri request)) :debug
                              (and status (>= status 500)) :warn
                              :else :info)
                 :data {:method (str/upper-case (name (:request-method request)))
                        :path (:uri request)
                        :status status
                        :duration-ms (Math/round (/ (- (System/nanoTime) started) 1e6))}})
      response)))

;; ---------------------------------------------------------------------------
;; Exceptions

(defn- problem-response [e request]
  (let [{:keys [status detail extra headers]} (ex-data e)]
    (t/event! :pulse.http/problem
              {:level (if (>= status 500) :error :info)
               :error (when (>= status 500) e)
               :data {:status status :detail detail :path (:uri request)}})
    (problem/response status {:detail detail :extra extra :headers headers :request request})))

(defn- default-unexpected
  "The answer to a failure nobody expected: a 500 problem with a generic text."
  [request]
  (problem/response 500 {:detail "An unexpected error occurred." :request request}))

(defn- unexpected-response [e request render]
  (t/event! :pulse.http/unhandled-exception
            {:level :error
             :error e
             :data {:method (str/upper-case (name (:request-method request))) :path (:uri request)}})
  (render request))

(defn wrap-exceptions
  "Turns whatever a handler throws into a problem response, so no request ever ends in a bare
  500 or a stack trace. Exceptions made by `problem/ex` keep their status and detail; any
  other becomes a 500 with a generic text and is logged with its cause.

  `:handlers` maps the `:type` in an exception's ex-data to `(fn [exception request])`
  returning a response, for failures that carry their own meaning (a coercion error is a 400,
  say). `:unexpected` is `(fn [request])` returning the response for any other failure, which is
  logged all the same: a service whose answers are pages and not problems, such as the portal's,
  gives its page for a failure on its side. Errors the JVM cannot recover from (out of memory)
  are not caught."
  [handler {:keys [handlers unexpected] :or {unexpected default-unexpected}}]
  (fn [request]
    (try
      (handler request)
      (catch VirtualMachineError e
        (throw e))
      (catch Throwable e
        (let [type (:type (ex-data e))]
          (cond
            (contains? handlers type) ((get handlers type) e request)
            (= ::problem/problem type) (problem-response e request)
            :else (unexpected-response e request unexpected)))))))
