(ns pulse.spa.test.inbox-fixtures
  "The inbox of alerts as the tests need it, in the shapes the api answers (ADR-0029): the clinic of `pulse.spa.test.panel-fixtures` at the
  same moment (14:00 on Tuesday 13 October 2026 in Madrid), with two alerts that need a decision, Ana's blood pressure and Sofía's low
  oxygen saturation, one that Liam's doctor acknowledged this morning, and the readings behind the first two. It is not a namespace of
  tests (its name does not end in `-test`) and it is `.cljc`, so that the JVM and Node tests alike take their data from here."
  (:require [pulse.spa.test.panel-fixtures :as panel-fx]
            [pulse.spa.test.patient-fixtures :as patient-fx]))

(def now panel-fx/now)

(defn loaded [data]
  {:state :loaded :data data})

(def loading {:state :loading})

(def failed {:state :failed :kind :server})

(defn page
  "What `GET /api/alerts` answers for `items`: the page of them, `total` being how many there are in all (the number of `items` unless
  it is said)."
  ([items] (page items (count items)))
  ([items total] {:total total :offset 0 :limit 100 :items (vec items)}))

(def ana-alert
  {:id "flag-pt-ana-R1" :patient "Patient/pt-ana" :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z" :until nil})

(def sofia-alert
  {:id "flag-pt-sofia-R2" :patient "Patient/pt-sofia" :text "Low SpO₂" :status "active" :since "2026-10-12T08:30:00Z" :until nil})

(def liam-alert
  "An alert of Liam's that was raised after the inbox was loaded."
  {:id "flag-pt-liam-R3-b" :patient "Patient/pt-liam" :text "Glucose out of range" :status "active" :since "2026-10-13T11:45:00Z" :until nil})

(def liam-acknowledged
  {:id "flag-pt-liam-R3" :patient "Patient/pt-liam" :text "Glucose out of range" :status "inactive"
   :since "2026-10-11T20:30:00Z" :until "2026-10-13T07:40:00Z"})

(def ana-acknowledged
  "Ana's alert once it has been acknowledged, at 13:55 (Madrid)."
  (assoc ana-alert :status "inactive" :until "2026-10-13T11:55:00Z"))

(def active (page [ana-alert sofia-alert]))

(def acknowledged (page [liam-acknowledged]))

(def active-with-liam (page [liam-alert ana-alert sofia-alert]))

(def active-without-ana (page [sofia-alert]))

(def acknowledged-with-ana (page [ana-acknowledged liam-acknowledged]))

(def none (page []))

(def patients panel-fx/patients)

(def empty-series patient-fx/empty-series)

(def ana-evidence patient-fx/ana-pressure-evidence)

(def evidence
  "The readings behind the active alerts, by the patient and the measurement the rule of each watches."
  {["pt-ana" :blood-pressure] patient-fx/ana-pressure-evidence
   ["pt-sofia" :spo2] patient-fx/sofia-spo2-evidence})

(def liam-glucose-evidence
  (panel-fx/series :glucose [150 160 158 170 168] :band "elevated" :unit "mg/dL"))

(defn settled
  "The inbox when every part has loaded: the state the page shows when all went well."
  []
  {:now now
   :active (loaded active)
   :acknowledged (loaded acknowledged)
   :patients (loaded panel-fx/patients)
   :evidence (update-vals evidence loaded)
   :latest {}
   :adopt? false
   :queue []
   :inflight #{}})

(defn body-of
  "What the api answers for the task `task` of the inbox, as the fixtures have it, or nil for one they have no answer for."
  [task]
  (let [type (if (vector? task) (first task) task)]
    (case type
      :active active
      :acknowledged acknowledged
      :patients panel-fx/patients
      :check (case (second task) :active active :acknowledged acknowledged)
      :evidence (let [[_ id kind] task]
                  (or (get evidence [id kind])
                      (when (= ["pt-liam" :glucose] [id kind]) liam-glucose-evidence)
                      patient-fx/empty-series))
      nil)))
