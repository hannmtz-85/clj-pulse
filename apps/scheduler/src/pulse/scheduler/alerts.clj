(ns pulse.scheduler.alerts
  "The alerts that the rules engine raises, as FHIR Flags (ADR-0022, ADR-0016). An alert is one
  for a rule, a patient and the newest reading that supports it: its identifier is
  `R1/pt-ana/2026-10-13T08:00:00Z`. The parts are joined with a slash because the id of a FHIR
  resource cannot hold one, so the alert of one patient is never taken for another's.

  Raising it is a conditional create, which the server answers with a 201 if there was no alert with
  that identifier and with a 200, leaving it as it is, if there was one, active or acknowledged."
  (:require [clojure.string :as str]
            [pulse.fhir.alert :as alert]
            [pulse.fhir.client :as c]
            [pulse.fhir.resource :as resource]))

(def identifier-system
  "The system of the identifier of an alert."
  "https://pulse.example/identifier/alert")

(def rule-system
  "The system of the code of the rule that raised an alert."
  "https://pulse.example/CodeSystem/alert-rule")

(defn- key-prefix [rule patient-id]
  (str (:id rule) "/" patient-id "/"))

(defn alert-key
  "The identifier value of the alert of `rule` for the patient with id `patient-id`, supported by
  readings up to `newest-at`."
  [rule patient-id newest-at]
  (str (key-prefix rule patient-id) newest-at))

(defn flag
  "The Flag that raises the alert of `rule` for `patient-id` at `raised-at`, supported by readings
  up to `newest-at`. It is active, has the title of the rule as its text and its code as a coding,
  and has no id: the server gives it one."
  [rule patient-id newest-at raised-at]
  (-> (resource/flag {:id "unused"
                      :patient patient-id
                      :text (:title rule)
                      :start (str raised-at)
                      :identifier {:system identifier-system
                                   :value (alert-key rule patient-id newest-at)}})
      (dissoc :id)
      (assoc-in [:code :coding] [{:system rule-system :code (:id rule) :display (:title rule)}])))

(defn flags-of
  "The newest alerts of the patient with id `patient-id`, at most `limit` (100)."
  ([fhir patient-id] (flags-of fhir patient-id 100))
  ([fhir patient-id limit]
   (into [] (take limit)
         (c/search fhir "Flag" {:patient (str "Patient/" patient-id) :_sort "-date" :_count (min limit 100)}))))

(defn active?
  "Whether, among `flags`, there is an active alert of `rule` for `patient-id`: whatever readings
  it was raised for, since there is to be one at a time."
  [flags rule patient-id]
  (let [prefix (key-prefix rule patient-id)]
    (boolean
     (some (fn [f]
             (and (alert/active? f)
                  (some (fn [{:keys [system value]}]
                          (and (= identifier-system system)
                               (str/starts-with? (str value) prefix)))
                        (:identifier f))))
           flags))))

(defn raise!
  "Raises `flag` (see `flag`) unless there is one with its identifier, with `c/create-once!`.
  Returns `{:outcome :created :id \"123\"}` or `{:outcome :existing :id \"123\"}`, the id being that
  of the alert the server has. A failed transaction throws."
  [fhir flag]
  (c/create-once! fhir flag))
