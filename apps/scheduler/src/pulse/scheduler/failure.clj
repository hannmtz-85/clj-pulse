(ns pulse.scheduler.failure
  "Which failures end a run of a job. A run goes item by item, a patient or an appointment, and an
  item that fails is counted and the run goes on; but when what failed is the infrastructure the
  run stands on, the next item would fail the same way, and when the run is interrupted it has to
  stop, so the run ends instead (ADR-0022, ADR-0024)."
  (:require [pulse.fhir.client :as c]
            [pulse.platform.http.client :as http]
            [pulse.platform.token :as token])
  (:import (java.io IOException)))

(defn- interruption?
  "Whether `e`, or anything it was caused by, is an InterruptedException: a handler must not
  swallow one (ADR-0020), and a client that wraps it has still been interrupted."
  [e]
  (boolean (some #(instance? InterruptedException %) (take-while some? (iterate ex-cause e)))))

(defn fatal?
  "Whether `e` says that the run has to end and not that one item has a problem: it was
  interrupted, or the infrastructure is out of reach or refuses the job (no token, the api or FHIR
  unreachable or failing, or the api refusing the token)."
  [e]
  (let [{:keys [type status]} (ex-data e)]
    (or (interruption? e)
        (= ::token/unavailable type)
        (= ::http/unreachable type)
        (and (= ::http/failed type) (or (contains? #{401 403} status) (>= (or status 0) 500)))
        (and (= ::c/fhir-error type) (or (nil? status) (>= status 500)))
        (instance? IOException e))))
