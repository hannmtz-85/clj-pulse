(ns pulse.api.audit
  "The audit trail (ADR-0012, ADR-0014): events about who did what to clinical data, and never
  the data itself. There are two kinds, both carrying the actor and the request's method and
  path (the request id is in the log context):

    `access!`  a decision of authorization, allowed or not, raised by `pulse.api.authz`
    `write!`   a write that the FHIR server has accepted, raised by the endpoint that made it

  Denials and misses are warnings, the rest information."
  (:require [clojure.string :as str]
            [taoensso.telemere :as t]))

(def ^:private unaudited
  "Actions that are not about clinical data."
  #{:identity/read})

(defn- actor [caller]
  {:subject (:subject caller)
   :username (:username caller)
   :roles (vec (sort (map name (:roles caller))))
   :client (:client caller)})

(defn- about-request [request]
  {:method (some-> (:request-method request) name str/upper-case)
   :path (:uri request)})

(defn access!
  "One event per decision on clinical data, allowed or not (ADR-0012): who, what, on which
  patient, with what outcome (`:allowed`, `:denied` or `:not-found`) and why. `decision` is what
  `pulse.api.authz/authorize` answered and `descriptor` the resource it judged, or nil."
  [request caller action outcome {:keys [scope reason]} descriptor]
  (when-not (contains? unaudited action)
    (t/event! :pulse.audit/access
              {:level (if (= :allowed outcome) :info :warn)
               :data (cond-> (merge {:actor (actor caller) :action action :outcome outcome}
                                    (about-request request))
                       (:patient descriptor) (assoc :patient (:patient descriptor))
                       scope (assoc :scope scope)
                       reason (assoc :reason reason))})))

(defn write!
  "One event for a write the FHIR server has accepted: who, the action they were allowed, the
  outcome (`:created` or `:updated`), the patient and the reference of the resource that was
  written, such as \"Observation/obs-1\". When one action covers several things, `:change` names
  which was done (`:confirm`, `:cancel`): a verb, never a value. Never what the resource holds."
  [request caller {:keys [action outcome patient resource change]}]
  (t/event! :pulse.audit/write
            {:level :info
             :data (cond-> (merge {:actor (actor caller)
                                   :action action
                                   :outcome outcome
                                   :patient patient
                                   :resource resource}
                                  (about-request request))
                     change (assoc :change change))}))
