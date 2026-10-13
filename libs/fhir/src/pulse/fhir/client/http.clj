(ns pulse.fhir.client.http
  "FhirClient over HTTP for any FHIR R4 server (HAPI in this project).

  Uses `babashka.http-client` and `clojure.data.json`, both of which run unchanged on the
  JVM and in Babashka, so the same client serves the apps and `bb seed`."
  (:require [babashka.http-client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c])
  (:import (java.net URI)))

(def ^:private fhir-json "application/fhir+json")

(defn- rebase
  "A `next` link comes from the server's own idea of its address, which may differ from
  how we reach it (container name, port mapping). Keep its path and query, but use the
  origin of `base-url`."
  [base-url link]
  (let [origin (let [u (URI. base-url)]
                 (str (.getScheme u) "://" (.getAuthority u)))
        l (URI. link)]
    (str origin (.getRawPath l) (when-let [q (.getRawQuery l)] (str "?" q)))))

(defn- parse-body [body]
  (when-not (str/blank? body)
    (try (json/read-str body :key-fn keyword)
         (catch Exception _ nil))))

(defn- send-request
  "Performs the request and returns {:status n :body parsed-json-or-nil}."
  [{:keys [no-cache?]} method url body]
  (let [response (http/request (cond-> {:uri url
                                        :method method
                                        :headers (cond-> {"Accept" fhir-json}
                                                   body (assoc "Content-Type" fhir-json)
                                                   no-cache? (assoc "Cache-Control" "no-cache"))
                                        :throw false}
                                 body (assoc :body (json/write-str body))))]
    {:status (:status response) :body (parse-body (:body response))}))

(defn- success? [status] (<= 200 status 299))

(defrecord HttpClient [base-url no-cache?]
  c/FhirClient
  (read-resource [this resource-type id]
    (let [{:keys [status body]} (send-request this :get (str base-url "/" resource-type "/" id) nil)]
      (cond
        (success? status) body
        (#{404 410} status) nil
        :else (throw (c/fhir-error (str "Reading " resource-type "/" id " failed") status body)))))

  (search [this resource-type params]
    (let [first-url (str base-url "/" resource-type "?" (c/query-string params))
          fetch-page (fn [url]
                       (let [{:keys [status body]} (send-request this :get url nil)]
                         (if (success? status)
                           body
                           (throw (c/fhir-error (str "Searching " resource-type " failed") status body)))))]
      (c/paged (fn [url] (fetch-page (if (str/starts-with? url base-url) url (rebase base-url url))))
               first-url)))

  (put! [this resource]
    (let [{:keys [resourceType id]} resource
          {:keys [status body]} (send-request this :put (str base-url "/" resourceType "/" id) resource)]
      (if (success? status)
        (or body resource)
        (throw (c/fhir-error (str "Writing " resourceType "/" id " failed") status body)))))

  (delete! [this resource-type id]
    (let [{:keys [status body]} (send-request this :delete (str base-url "/" resource-type "/" id) nil)]
      (when-not (or (success? status) (#{404 410} status))
        (throw (c/fhir-error (str "Deleting " resource-type "/" id " failed") status body)))
      nil))

  (transact! [this tx]
    (let [{:keys [status body]} (send-request this :post base-url tx)]
      (when-not (success? status)
        (throw (c/fhir-error "Transaction rejected" status body)))
      (when-not (bundle/all-succeeded? body)
        (throw (c/fhir-error "Transaction had failing entries" status body)))
      body))

  (validate [this resource]
    ;; HAPI answers $validate with an OperationOutcome, with a 2xx or a 4xx status
    ;; depending on whether it found errors: both carry the outcome we want.
    (let [{:keys [status body]} (send-request this :post
                                              (str base-url "/" (:resourceType resource) "/$validate")
                                              resource)]
      (if (= "OperationOutcome" (:resourceType body))
        body
        (throw (c/fhir-error "Validation request failed" status body))))))

(defn client
  "An HttpClient for the FHIR base URL, e.g. (client {:base-url \"http://localhost:8090/fhir\"}).
  Searches bypass the server's result cache by default (`:no-cache? true`) so a read
  right after a write sees the write."
  [{:keys [base-url no-cache?] :or {no-cache? true}}]
  (->HttpClient (str/replace base-url #"/+$" "") no-cache?))
