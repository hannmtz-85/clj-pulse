(ns pulse.api.fhir
  "How the api talks to the FHIR server. A server that cannot answer is not the caller's fault
  and not a bug of ours: it is a 502, with a generic text, and the cause goes to the log. These
  functions turn any failure into that, so no endpoint has to remember to."
  (:require [pulse.fhir.client :as fc]
            [pulse.platform.http.problem :as problem]))

(defn- upstream-failure [e]
  (problem/ex 502 "The FHIR server could not be reached." {:cause e}))

(defn read-resource
  "The resource, or nil when the server has none."
  [fhir resource-type id]
  (try
    (fc/read-resource fhir resource-type id)
    (catch Exception e
      (throw (upstream-failure e)))))

(defn search
  "Every resource matching `params`, as a vector. It is realised here, inside the `try`: a lazy
  sequence would only fail later, when something else forced it."
  [fhir resource-type params]
  (try
    (vec (fc/search fhir resource-type params))
    (catch Exception e
      (throw (upstream-failure e)))))
