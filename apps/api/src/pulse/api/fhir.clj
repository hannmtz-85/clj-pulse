(ns pulse.api.fhir
  "How the api talks to the FHIR server. A server that cannot answer is not the caller's fault
  and not a bug of ours: it is a 502 (a 504 when it was too slow), with a generic text, and the
  cause goes to the log. These functions turn any failure into that, so no endpoint has to
  remember to."
  (:require [pulse.fhir.client :as fc]
            [pulse.platform.http.problem :as problem])
  (:import (java.net.http HttpTimeoutException)))

(defn- upstream-failure [e]
  (if (instance? HttpTimeoutException e)
    (problem/ex 504 "The FHIR server did not answer in time." {:cause e})
    (problem/ex 502 "The FHIR server could not be reached." {:cause e})))

(defn- guarded
  "Calls `f`, turning what it throws into a problem. An interruption is not a failure of the
  server: someone cancelled the call, so the interrupt flag is put back and it goes on."
  [f]
  (try
    (f)
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (throw e))
    (catch Exception e
      (throw (upstream-failure e)))))

(defn read-resource
  "The resource, or nil when the server has none."
  [fhir resource-type id]
  (guarded #(fc/read-resource fhir resource-type id)))

(defn find-one
  "The first resource matching `params`, or nil. Only the first page is fetched."
  [fhir resource-type params]
  (guarded #(fc/find-one fhir resource-type params)))

(defn put!
  "Creates or replaces `resource` at its own type and id; returns what the server stored. A
  server that refuses what the api built is no more the caller's doing than one that is down,
  so it is a 502 as well, and the server's own words stay in the log."
  [fhir resource]
  (guarded #(fc/put! fhir resource)))

(defn search
  "The resources matching `params`, as a vector of at most `:limit` (default 1000: a search
  that matches more than that is a mistake to find out about, not memory to spend). It is
  realised here, inside the guard: a lazy sequence would only fail later, when something else
  forced it."
  ([fhir resource-type params]
   (search fhir resource-type params {}))
  ([fhir resource-type params {:keys [limit] :or {limit 1000}}]
   (guarded #(vec (take limit (fc/search fhir resource-type params))))))
