(ns pulse.fhir.client
  "The FhirClient protocol: what Pulse needs from a FHIR server, independent of how it is
  reached. There are two implementations: `pulse.fhir.client.http` (a real server such as
  HAPI) and `pulse.fhir.client.memory` (a fake for tests and the \"lite\" profile). Code
  that takes a FhirClient does not know which one it has."
  (:require [clojure.string :as str]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.outcome :as outcome])
  (:import (java.net URLEncoder)))

(defprotocol FhirClient
  (read-resource [client resource-type id]
    "The resource, or nil when it does not exist or was deleted.")
  (search [client resource-type params]
    "A lazy seq of every matching resource. Pages are fetched on demand by following the
    `next` link of each searchset Bundle. `params` is a map such as {:patient \"Patient/pt-ana\"}.")
  (put! [client resource]
    "Creates or replaces the resource at its own type and id. Returns the stored resource.")
  (delete! [client resource-type id]
    "Deletes the resource. Returns nil.")
  (transact! [client bundle]
    "Executes a transaction Bundle atomically. Returns the response Bundle; throws when
    the server rejects it.")
  (validate [client resource]
    "Validates a resource on the server (`$validate`). Returns an OperationOutcome."))

(defn- encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn query-string
  "Encodes a params map as a query string. A sequential value repeats the parameter (the
  FHIR meaning of AND); a collection of [k v] pairs keeps order and duplicates."
  [params]
  (str/join "&"
            (for [[k v] params
                  v (if (sequential? v) v [v])]
              (str (encode (name k)) "=" (encode v)))))

(defn fhir-error
  "An exception for a failed FHIR interaction. When the server sent an OperationOutcome it
  is kept in the ex-data under :outcome and summarised in the message."
  [message status outcome-body]
  (ex-info (if (and (map? outcome-body) (= "OperationOutcome" (:resourceType outcome-body)))
             (str message ": " (outcome/summary outcome-body))
             message)
           {:type ::fhir-error :status status :outcome outcome-body}))

(defn paged
  "A lazy seq of the resources of a paginated search.

  `fetch-page` takes a URL and returns the searchset Bundle found there; `first-url`
  is where the search starts. This is `clojure.core/iteration` doing what it was made
  for: each step is one page, the continuation token is the `next` link, and a page
  without a next link ends the sequence."
  [fetch-page first-url]
  ;; Flattening by hand instead of `(sequence cat ...)` or `mapcat`: `sequence` with a
  ;; transducer is chunked (it pulls up to 32 elements at once) and `mapcat` spreads its
  ;; first arguments with `apply`, both of which would fetch pages nobody asked for.
  ;; `rest`, unlike `next`, leaves the lazy tail (the next page fetch) unrealized.
  (letfn [(flatten-pages [pages]
            (lazy-seq
             (when-let [s (seq pages)]
               (concat (first s) (flatten-pages (rest s))))))]
    (flatten-pages
     (iteration fetch-page
                :somef (comp seq bundle/resources)
                :vf (comp vec bundle/resources)
                :kf bundle/next-url
                :initk first-url))))

(defn find-one
  "The first resource matching `params`, or nil."
  [client resource-type params]
  (first (search client resource-type (assoc params :_count 1))))
