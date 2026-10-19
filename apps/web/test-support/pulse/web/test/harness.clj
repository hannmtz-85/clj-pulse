(ns pulse.web.test.harness
  "What the portal's handler tests share: a fake identity provider (the portal asks it for its key set), a stand-in for the
  api, the portal's Ring handler wired to them, and `call` to make a request and read the answer. No server and no
  network beyond localhost.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.http.client :as http]
            [pulse.platform.jwt :as jwt]
            [pulse.platform.test.idp :as idp]
            [pulse.platform.test.stub :as stub]
            [pulse.web.handler :as handler]
            [pulse.web.routes :as routes]
            [taoensso.telemere :as t])
  (:import (java.io File InputStream)))

(def signing-key (idp/signing-key "k1"))

(def issuer
  "The identity provider as a browser reaches it: the origin that the policy of the pages names."
  "http://localhost:8080/realms/pulse")

(def api-ready
  "What the stand-in for the api answers to a readiness probe, until a test changes it."
  {:status 200 :body "{\"status\":\"ready\",\"checks\":{\"keycloak\":\"up\",\"fhir\":\"up\"}}"})

(defn with-web
  "Calls `(f {:handler h :idp server :jwks source :api client :api-answer a :api-requests r})` with a fake identity
  provider, a stand-in for the api, a client of it that has no token, and the portal's handler wired to them, and stops
  it all afterwards. Options: `:routes` (more routes, added to the portal's). The stand-in for the api answers every
  request with what the atom `:api-answer` holds, `api-ready` at first."
  [{:keys [routes]} f]
  (let [server (idp/start! [signing-key])
        source (jwt/jwks-source {:uri (:uri server) :min-refresh-interval-ms 200})
        api-answer (atom api-ready)
        api-stub (stub/start! (fn [_] @api-answer))
        api (http/client {:base-url (:url api-stub)})]
    (try
      (f {:handler (handler/ring-handler (into (routes/routes {:jwks source :api api}) routes)
                                         {:issuer issuer :quiet-paths #{"/healthz" "/readyz"}})
          :idp server
          :jwks source
          :api api
          :api-answer api-answer
          :api-requests (:requests api-stub)})
      (finally
        (http/close! api)
        (stub/stop! api-stub)
        (jwt/close! source)
        (idp/stop! server)))))

(defn bytes-of
  "The bytes of a response body: a string, a file or a stream (what Ring's file responses are)."
  ^bytes [body]
  (cond (nil? body) (byte-array 0)
        (string? body) (.getBytes ^String body "UTF-8")
        (instance? File body) (java.nio.file.Files/readAllBytes (.toPath ^File body))
        (instance? InputStream body) (with-open [in ^InputStream body] (.readAllBytes in))
        :else (.getBytes (str body) "UTF-8")))

(defn call
  "The response to a request, with `:bytes`, `:text` (the body as UTF-8), `:json` (the body as JSON, if it is) and
  `:signals` (what was logged, trapped so nothing is printed) added. Options: `:headers`, `:query` (a query string),
  `:session` (what the session middleware would have put in the request) and `:form` (a map, sent as the parameters of
  a form)."
  [{:keys [handler]} method uri & {:keys [headers query session form]}]
  (let [request (cond-> {:request-method method
                         :uri uri
                         :headers (or headers {})}
                  query (assoc :query-string query)
                  session (assoc :session session)
                  form (-> (assoc :headers (assoc (or headers {}) "content-type" "application/x-www-form-urlencoded"))
                           (assoc :body (io/input-stream (.getBytes ^String (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) form)) "UTF-8")))))
        {:keys [value signals]} (t/with-signals true (handler request))
        data (bytes-of (:body value))
        text (String. data "UTF-8")]
    (assoc value
           :bytes data
           :text text
           :json (try (json/read-value text json/keyword-keys-object-mapper) (catch Exception _ nil))
           :signals signals)))

(defn header [response name]
  (get-in response [:headers name]))

(defn content-type [response]
  (some-> (header response "Content-Type") (str/split #";") first))
