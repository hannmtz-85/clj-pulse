(ns pulse.spa.test.patient-fixtures
  "The chart of a patient as the tests need it, in the shapes the api answers (ADR-0029): Ana García and Sofía Rossi of the clinic of
  `pulse.spa.test.panel-fixtures`, at the same moment (14:00 on Tuesday 13 October 2026 in Madrid), with what the chart asks the api for:
  the Patient resource, the summary with the record on file, the series of a measurement and the latest readings behind an alert. It is
  not a namespace of tests (its name does not end in `-test`) and it is `.cljc`, so that the JVM and Node tests alike take their data
  from here."
  (:require [pulse.spa.test.panel-fixtures :as panel-fx]))

(def now panel-fx/now)

(def ana-resource
  "`GET /api/patients/pt-ana`: the Patient as the FHIR server has it."
  {:resourceType "Patient"
   :id "pt-ana"
   :name [{:family "García" :given ["Ana"]}]
   :gender "female"
   :birthDate "1968-03-14"
   :identifier [{:system "https://pulse.example/mrn" :value "PULSE-0001"}]
   :address [{:city "Madrid" :country "ES"}]
   :generalPractitioner [{:reference "Practitioner/pr-rivera"}]})

(def sofia-resource
  {:resourceType "Patient"
   :id "pt-sofia"
   :name [{:family "Rossi" :given ["Sofía"]}]
   :gender "female"
   :birthDate "1959-11-21"
   :identifier [{:system "https://pulse.example/mrn" :value "PULSE-0003"}]
   :address [{:city "Getafe"}]})

(def noah-resource
  {:resourceType "Patient"
   :id "pt-noah"
   :name [{:family "Kim" :given ["Noah"]}]
   :gender "male"
   :birthDate "2001-01-09"
   :identifier [{:value "PULSE-0005"}]})

(def ana-summary
  "`GET /api/patients/pt-ana/summary`: a very high blood pressure, an active alert, a visit to come and one cancelled, and the record."
  (assoc (get panel-fx/summaries "pt-ana")
         :conditions [{:id "c-ana-1" :display "Essential (primary) hypertension" :code "I10" :status "active" :onset "2019-05-01T00:00:00Z"}]
         :medications [{:id "m-ana-1" :display "lisinopril" :dosage "10 mg once daily" :status "active" :authoredOn "2025-11-04T09:00:00Z"}]
         :allergies [{:id "al-ana-1" :display "penicillin" :criticality "high" :reaction "Rash" :recordedDate "2010-01-01"}]))

(def sofia-summary
  (assoc (get panel-fx/summaries "pt-sofia")
         :conditions [{:id "c-sofia-1" :display "Chronic obstructive pulmonary disease" :code "J44.9" :status "active" :onset "2018-02-01T00:00:00Z"}]))

(def noah-summary
  "A new patient: nothing on file and nothing measured."
  (get panel-fx/summaries "pt-noah"))

(def ana-pressure-series
  "`GET /api/patients/pt-ana/observations?measurement=blood-pressure...`: five days, the newest first."
  (panel-fx/series :blood-pressure [[150 92] [158 96] [165 98] [172 100] [177 100]] :band "very-high" :unit "mm[Hg]"))

(def ana-pressure-evidence
  "The latest three readings of the blood pressure, the newest first."
  (update ana-pressure-series :items (comp vec (partial take 3))))

(def sofia-spo2-series (panel-fx/series :spo2 [95 94 93 92 90] :band "low" :unit "%"))

(def sofia-spo2-evidence (update sofia-spo2-series :items (comp vec (partial take 3))))

(def ana-heart-rate-series (panel-fx/series :heart-rate [72 74 73 75 74] :unit "/min"))

(def empty-series {:items [] :truncated false})

(def forbidden {:state :failed :kind :forbidden})

(defn loaded [data]
  {:state :loaded :data data})

(defn settled-ana
  "The chart of Ana García when every part has loaded: the state the page shows when all went well."
  ([] (settled-ana {}))
  ([{:keys [asked days] :or {days 14}}]
   (let [kind (or asked :blood-pressure)]
     {:now now
      :id "pt-ana"
      :asked asked
      :days days
      :patient (loaded ana-resource)
      :summary (loaded ana-summary)
      :series (assoc (loaded (if (= :blood-pressure kind) ana-pressure-series ana-heart-rate-series)) :measurement kind :days days)
      :evidence {:blood-pressure (loaded ana-pressure-evidence)}
      :queue []
      :inflight #{}})))

(defn settled-sofia
  "The chart of Sofía Rossi when every part has loaded: a low oxygen saturation and the alert for it."
  []
  {:now now
   :id "pt-sofia"
   :asked nil
   :days 14
   :patient (loaded sofia-resource)
   :summary (loaded sofia-summary)
   :series (assoc (loaded sofia-spo2-series) :measurement :spo2 :days 14)
   :evidence {:spo2 (loaded sofia-spo2-evidence)}
   :queue []
   :inflight #{}})

(defn settled-noah
  "The chart of Noah Kim when every part has loaded: a patient with nothing measured, so there is nothing to draw."
  []
  {:now now
   :id "pt-noah"
   :asked nil
   :days 14
   :patient (loaded noah-resource)
   :summary (loaded noah-summary)
   :series nil
   :evidence {}
   :queue []
   :inflight #{}})

(defn body-of
  "What the api answers for the task `task` of the chart of `patient` (`:ana` or `:sofia`), or nil for one the fixtures have no answer
  for."
  [patient task]
  (let [kind (if (vector? task) (second task) nil)
        type (if (vector? task) (first task) task)]
    (case [patient type]
      [:ana :patient] ana-resource
      [:ana :summary] ana-summary
      [:ana :series] (case kind :blood-pressure ana-pressure-series :heart-rate ana-heart-rate-series empty-series)
      [:ana :evidence] (case kind :blood-pressure ana-pressure-evidence empty-series)
      [:sofia :patient] sofia-resource
      [:sofia :summary] sofia-summary
      [:sofia :series] (if (= :spo2 kind) sofia-spo2-series empty-series)
      [:sofia :evidence] (if (= :spo2 kind) sofia-spo2-evidence empty-series)
      nil)))
