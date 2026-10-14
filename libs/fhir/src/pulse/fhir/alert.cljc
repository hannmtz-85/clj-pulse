(ns pulse.fhir.alert
  "The lifecycle of an alert, a FHIR Flag that the rules engine raises (ADR-0016). It is `active`
  until a clinician acknowledges it, and then it is `inactive`, for good. The functions are pure
  and portable: a UI can ask `active?` to decide whether to offer the acknowledgement."
  (:require [pulse.fhir.time :as time]))

(defn active?
  "Whether `flag` is an alert that is still to be acknowledged."
  [flag]
  (= "active" (:status flag)))

(defn- millis
  "The epoch milliseconds of the instant or date string `s`, or nil when it is not one."
  [s]
  (when (string? s)
    (let [ms (try (time/->millis s)
                  (catch #?(:clj Exception :cljs :default) _ nil))]
      (when (and (number? ms) #?(:clj true :cljs (not (js/isNaN ms))))
        ms))))

(defn- end-of
  "When the period of `flag` ends if it is acknowledged at `now`: then, unless that is before it
  began, since a period does not end before it starts."
  [flag now]
  (let [start (get-in flag [:period :start])
        start-ms (millis start)
        now-ms (millis now)]
    (if (and start-ms now-ms (< now-ms start-ms))
      start
      now)))

(defn acknowledge
  "`flag` acknowledged at `now`, an instant string. Returns `{:ok flag'}`, inactive and with its
  period ending when it was acknowledged, or `{:conflict message}` when it is not active. Nothing
  else about it changes. Pure."
  [flag now]
  (if (active? flag)
    {:ok (-> flag
             (assoc :status "inactive")
             (assoc-in [:period :end] (end-of flag now)))}
    {:conflict (str "Only an active alert can be acknowledged; this one is "
                    (or (:status flag) "of no known status") ".")}))
