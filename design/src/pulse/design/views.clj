(ns pulse.design.views
  "What the pages of the mock-ups of the workspace show of an appointment, an alert or a patient (ADR-0026): view models made
  from the data in the shapes of the api, as the interfaces will make them from what the api answers. What the patient portal
  shows of an appointment is in `pulse.web.views`, which these build on."
  (:require [clojure.string :as str]
            [pulse.design.data :as data]
            [pulse.ui.tone :as tone]
            [pulse.web.format :as fmt]
            [pulse.web.views :as web-views]))

;; ---------------------------------------------------------------------------
;; Appointments

(defn- patient-of [reference]
  (some-> reference (str/split #"/") second data/patient))

(defn appointment-row
  "An appointment as a page of the workspace shows it: the row of `pulse.web.views/appointment-row`, with the doctor by
  name from the cohort, and `:patient`, the view of the patient."
  [{:keys [patient] :as appointment}]
  (assoc (web-views/appointment-row appointment (data/practitioner-names)) :patient (patient-of patient)))

(defn appointment-rows
  "Every appointment as `appointment-row`s, soonest first: those of the cohort and `data/other-appointments`."
  []
  (->> (concat (data/appointments) data/other-appointments)
       (sort-by #(or (:start %) (:requestedStart %)))
       (map appointment-row)))

(defn appointments-of
  "The appointment rows of a patient, soonest first."
  [patient-id]
  (filter #(= patient-id (get-in % [:patient :id])) (appointment-rows)))

;; ---------------------------------------------------------------------------
;; Alerts

(def rule-descriptions
  "What each rule watches, in the words of the rules themselves, by the title of the alert it raises."
  {"BP stage 2" "Blood pressure at stage 2 (a systolic of 160 or more, or a diastolic of 100 or more) in at least 2 of the last 3 readings of the last 3 days."
   "Low SpO₂" "Oxygen saturation below 92 % in at least 2 readings of the last 24 hours."
   "Glucose out of range" "A glucose above 250 or below 70 mg/dL in the last 24 hours."
   "Rapid weight gain" "A latest weight, from the last 24 hours, 2 kg or more above the lowest of the 72 hours before it, for a patient with an active heart failure (ICD-10-CM I50)."})

(def ^:private alert-kinds
  {"BP stage 2" {:measurement :blood-pressure :tone :critical}
   "Low SpO₂" {:measurement :spo2 :tone :high}
   "Glucose out of range" {:measurement :glucose :tone :high}
   "Rapid weight gain" {:measurement :weight :tone :elevated}})

(defn alert-card
  "An alert as the inbox shows it: the api's fields, and `:patient` (the view of the patient), `:tone` of the rule,
  `:description` of what it watches, `:since-phrase` (`yesterday, 22:30`, for the middle of a sentence), `:evidence` (the
  last readings of what it watches, as lines of text), `:active?`, and for one that was acknowledged `:until-phrase`.
  The api does not say who acknowledged it, and neither does the card."
  [{:keys [id patient text status since until]}]
  (let [{:keys [measurement tone]} (alert-kinds text)
        patient-view (patient-of patient)
        readings (take-last 3 (data/readings (:id patient-view) measurement))
        {:keys [unit]} (last readings)]
    {:id id
     :patient patient-view
     :text text
     :tone tone
     :description (rule-descriptions text)
     :since since
     :since-phrase (fmt/when-phrase since data/now-text)
     :evidence (for [r (reverse readings)]
                 (str (web-views/reading-text measurement r) " " (fmt/unit unit) ", " (fmt/when-phrase (:at r) data/now-text)))
     :active? (= "active" status)
     :until-phrase (some-> until (fmt/when-phrase data/now-text))}))

(defn alert-cards
  "Every alert of the cohort as `alert-card`s, the active ones first and the newest first."
  []
  (->> data/alerts
       (map alert-card)
       (sort-by (juxt (complement :active?) (comp - fmt/millis :since)))))

;; ---------------------------------------------------------------------------
;; Patients

(def ^:private tone-severity {:critical 3 :high 2 :elevated 1 :normal 0})

(defn- band-severity
  "How far from normal a band is, as the api names it: from 0 (normal) to 3 (critical); -1 for no band."
  [band]
  (if-let [tone-key (some-> band tone/band :tone)]
    (tone-severity tone-key)
    -1))

(defn severity
  "How far from normal the worst of a patient's latest readings is, from 0 (normal) to 3 (critical); -1 for a patient
  with no readings."
  [patient-id]
  (band-severity (data/worst-band patient-id)))

(def ^:private kind-order (zipmap data/kinds (range)))

(defn headline
  "The reading that says most about a patient, `{:kind :reading}`: the furthest from normal among their latest, and of
  those equally far the first measurement of `data/kinds`. It is nil for a patient with no readings."
  [patient-id]
  (when-let [vitals (seq (data/vitals patient-id))]
    (let [[kind reading] (first (sort-by (fn [[kind reading]] [(- (band-severity (:band reading))) (kind-order kind)]) vitals))]
      {:kind kind :reading reading})))

(defn active-alerts
  "How many alerts of a patient are active."
  [patient-id]
  (count (filter #(and (= "active" (:status %)) (= (str "Patient/" patient-id) (:patient %))) data/alerts)))

(defn by-urgency
  "`patient-ids` with the most urgent first: the worst reading, then the most active alerts, then the family name."
  [patient-ids]
  (sort-by (fn [id] [(- (severity id)) (- (active-alerts id)) (:family (data/patient id))])
           patient-ids))
