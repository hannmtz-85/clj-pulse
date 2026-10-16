(ns pulse.design.data-test
  "The data of the mock-ups is what the api will give: the same shapes, checked against the functions and the schemas
  of the api itself, and a moment at which the cohort looks the way a clinic would."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [pulse.api.alerts :as api-alerts]
            [pulse.api.appointments :as api-appointments]
            [pulse.api.observations :as api-observations]
            [pulse.design.data :as data]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]))

(def ^:private madrid (java.time.ZoneId/of "Europe/Madrid"))

(deftest the-cohort-is-the-one-of-the-demo
  (is (= ["Chen" "García" "Haddad" "Kim" "Patel" "Rossi"] (map :family (data/patients))))
  (is (= ["pt-liam" "pt-ana" "pt-omar" "pt-noah" "pt-maya" "pt-sofia"] (map :id (data/patients))))
  (is (= #{"pr-rivera" "pr-okafor"} (set (keys (data/practitioners)))))
  (is (= "Dr. Elena Rivera" (:name (data/practitioner "pr-rivera"))))
  (let [ana (data/patient "pt-ana")]
    (is (= "Ana García" (:name ana)))
    (is (= "PULSE-0001" (:identifier ana)))
    (is (= "1968-03-14" (:birthDate ana)))
    (is (= "Valencia" (:city ana)))
    (is (= "pr-rivera" (:practitioner ana))))
  (is (nil? (data/patient "pt-nobody"))))

(deftest the-moment-is-a-tuesday-afternoon-in-madrid
  (let [at (.atZone data/now madrid)]
    (is (= java.time.DayOfWeek/TUESDAY (.getDayOfWeek at)))
    (is (= 14 (.getHour at))))
  (is (= data/now-text (str data/now))))

(deftest a-reading-is-exactly-what-the-api-makes-of-an-observation
  (let [kinds (for [[kind {:keys [concept]}] measurement/measurements] [kind concept])
        observations (filter #(= "Observation" (:resourceType %)) (data/resources))
        compared (for [o observations
                       [kind concept] kinds
                       :when (obs/concept? o concept)]
                   [(data/reading-view kind o) (api-observations/reading-view kind o)])]
    (is (< 600 (count compared)) "every reading of the cohort is compared")
    (is (every? (fn [[mine theirs]] (= mine theirs)) compared))
    (is (every? #(contains? (first %) :band) compared))))

(deftest an-appointment-is-exactly-what-the-api-shows
  (let [appointments (filter #(= "Appointment" (:resourceType %)) (data/resources))]
    (is (= 5 (count appointments)))
    (doseq [a appointments]
      (is (= (api-appointments/view a) (dissoc (data/appointment-view a) :patient)) (:id a))
      (is (re-matches #"Patient/pt-\w+" (:patient (data/appointment-view a)))))))

(deftest the-appointments-fall-on-working-days-at-the-hours-a-clinic-keeps
  (let [booked (filter :start (data/appointments))]
    (is (= 3 (count booked)))
    (doseq [{:keys [id start]} booked
            :let [at (.atZone (java.time.Instant/parse start) madrid)]]
      (is (not (#{java.time.DayOfWeek/SATURDAY java.time.DayOfWeek/SUNDAY} (.getDayOfWeek at))) id)
      (is (<= 9 (.getHour at) 17) (str id " at " at)))
    (testing "the others are requests, with the window that was asked for and no time"
      (let [requests (remove :start (data/appointments))]
        (is (= ["appt-noah-1" "appt-sofia-1"] (sort (map :id requests))))
        (is (every? #(and (:requestedStart %) (:requestedEnd %)) requests))
        (is (every? #(= "proposed" (:status %)) requests))))
    (testing "soonest first"
      (is (= ["appt-noah-1" "appt-ana-1" "appt-maya-1" "appt-sofia-1" "appt-liam-1"] (map :id (data/appointments)))))
    (is (= ["appt-ana-1"] (map :id (data/appointments-of "pt-ana"))))
    (is (empty? (data/appointments-of "pt-omar")))))

(deftest an-alert-has-the-shape-the-api-gives
  (is (= 4 (count data/alerts)))
  (doseq [alert data/alerts]
    (is (m/validate api-alerts/item-schema alert) (:id alert))
    (is (= #{:id :patient :text :status :since :until} (set (keys alert))) "and nothing the api does not give")
    (is (#{"active" "inactive"} (:status alert)))
    (is (= (nil? (:until alert)) (= "active" (:status alert))) "an acknowledged one has the time of it"))
  (is (= ["Rapid weight gain" "BP stage 2" "Low SpO₂" "Glucose out of range"] (map :text data/alerts))
      "the titles of the four rules of Annex C")
  (is (= 3 (count (filter #(= "active" (:status %)) data/alerts)))))

(deftest what-the-patients-have-is-what-the-story-of-the-cohort-says
  (testing "who has which measurements"
    (is (= #{:blood-pressure :heart-rate :weight} (set (keys (data/vitals "pt-ana")))))
    (is (= #{:glucose :blood-pressure :weight} (set (keys (data/vitals "pt-liam")))))
    (is (= #{:spo2 :heart-rate} (set (keys (data/vitals "pt-sofia")))))
    (is (= #{:blood-pressure :heart-rate :weight} (set (keys (data/vitals "pt-omar")))))
    (is (= {} (data/vitals "pt-noah")) "a new patient has no readings: the empty state of the interface"))
  (testing "where each stands, as the api names the band"
    (is (= "very-high" (data/worst-band "pt-ana")))
    (is (= "low" (data/worst-band "pt-sofia")))
    (is (= "normal" (data/worst-band "pt-omar")))
    (is (nil? (data/worst-band "pt-noah"))))
  (testing "the latest reading is the newest, and is not in the future"
    (let [ana (data/latest "pt-ana" :blood-pressure)]
      (is (= {:systolic 177 :diastolic 100 :unit "mm[Hg]" :band "very-high"}
             (select-keys ana [:systolic :diastolic :unit :band])))
      (is (not (.isAfter (java.time.Instant/parse (:at ana)) data/now))))
    (is (nil? (data/latest "pt-noah" :weight))))
  (testing "a month of readings, oldest first"
    (let [readings (data/readings "pt-ana" :blood-pressure)]
      (is (= 59 (count readings)))
      (is (= (sort (map :at readings)) (map :at readings))))
    (is (= 30 (count (data/readings "pt-ana" :weight))))
    (is (= [] (data/readings "pt-noah" :weight)))))

(deftest the-record-of-a-patient-is-in-the-shape-of-the-summary
  (is (= ["Essential (primary) hypertension"] (map :display (data/conditions "pt-ana"))))
  (is (= ["I10"] (map :code (data/conditions "pt-ana"))))
  (is (= #{"active"} (set (map :status (data/conditions "pt-ana")))))
  (is (= 2 (count (data/medications "pt-ana"))))
  (is (some #(= "10 mg once daily" (:dosage %)) (data/medications "pt-ana")))
  (is (= 1 (count (data/allergies "pt-sofia"))))
  (is (= "Skin rash" (:reaction (first (data/allergies "pt-sofia")))))
  (is (empty? (data/allergies "pt-ana"))))
