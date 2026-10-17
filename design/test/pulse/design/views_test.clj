(ns pulse.design.views-test
  "What the pages of the mock-ups show of an appointment, an alert or a patient: view models made from the data in the
  shapes of the api."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.views :as views]
            [pulse.scheduler.rules :as rules]
            [pulse.ui.test.hiccup :as hx]))

;; ---------------------------------------------------------------------------
;; Appointments

(deftest the-chip-of-an-appointment-says-its-status-in-words
  (is (= ["Confirmed" "Requested" "Pending" "Cancelled" "Completed" "Noshow"]
         (map (comp hx/words views/appointment-chip) ["booked" "proposed" "pending" "cancelled" "fulfilled" "noshow"]))
      "a status it does not know is shown as it is, in words")
  (testing "a confirmed appointment is normal, a request is information, and what is over is a plain label"
    (is (hx/has-class? (views/appointment-chip "booked") "bg-tone-normal-soft"))
    (is (hx/has-class? (views/appointment-chip "proposed") "bg-tone-info-soft"))
    (is (hx/has-class? (views/appointment-chip "pending") "bg-tone-info-soft"))
    (is (hx/has-class? (views/appointment-chip "cancelled") "badge-outline"))
    (is (hx/has-class? (views/appointment-chip "fulfilled") "badge-outline"))
    (is (hx/has-class? (views/appointment-chip "noshow") "badge-outline")))
  (testing "each has an icon of its own, so that the colour is not the only difference"
    (is (= 1 (count (hx/by-tag (views/appointment-chip "booked") :svg))))
    (is (not= (hx/by-tag (views/appointment-chip "booked") :svg) (hx/by-tag (views/appointment-chip "proposed") :svg))))
  (testing "attributes go to the chip"
    (is (hx/has-class? (views/appointment-chip "booked" {:class "mt-1"}) "mt-1"))
    (is (hx/has-class? (views/appointment-chip "cancelled" {:class "mt-1"}) "mt-1"))))

(deftest a-booked-appointment-has-its-day-its-time-and-its-doctor
  (let [row (first (filter #(= "appt-ana-1" (:id %)) (views/appointment-rows)))]
    (is (= {:id "appt-ana-1" :status "booked" :description "Hypertension follow-up"
            :doctor "Dr. Elena Rivera" :month "Oct" :day "14" :when "Wed 14 Oct, 10:00–10:30" :start "2026-10-14T08:00:00Z"}
           (dissoc row :patient)))
    (is (= "Ana García" (:name (:patient row))))
    (is (= "pt-ana" (:id (:patient row))))))

(deftest a-request-has-the-days-that-were-asked-for-and-no-day
  (let [row (first (filter #(= "appt-noah-1" (:id %)) (views/appointment-rows)))]
    (is (= "Asked for a visit between Oct 14 and Oct 20" (:asked row)))
    (is (= "2026-10-14T00:00:00Z" (:requested-start row)))
    (is (= "Dr. Chidi Okafor" (:doctor row)))
    (is (not-any? #(contains? row %) [:month :day :when :start :reason]))))

(deftest a-booked-appointment-that-kept-the-days-asked-for-is-not-a-request
  (let [booked {:id "appt-x" :status "booked" :description "Review" :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z"
                :requestedStart "2026-10-14T00:00:00Z" :requestedEnd "2026-10-20T00:00:00Z"
                :practitioner "Practitioner/pr-rivera" :patient "Patient/pt-ana"}
        row (views/appointment-row booked)]
    (is (= "Tue 20 Oct, 10:00–10:30" (:when row)))
    (is (not-any? #(contains? row %) [:asked :requested-start]) "it has its time, and the window it was asked in is not shown"))
  (testing "one with neither a time nor days says neither"
    (let [row (views/appointment-row {:id "appt-y" :status "pending" :description "Review"
                                      :practitioner "Practitioner/pr-rivera" :patient "Patient/pt-ana"})]
      (is (not-any? #(contains? row %) [:asked :requested-start :when :start :month :day])))))

(deftest a-cancelled-appointment-has-the-reason-that-was-given
  (let [row (first (filter #(= "appt-ana-0" (:id %)) (views/appointment-rows)))]
    (is (= "cancelled" (:status row)))
    (is (= "I had to travel that week." (:reason row)))
    (is (= "Tue 29 Sep, 09:00–09:30" (:when row)))
    (is (= "Sep" (:month row)))
    (is (= "29" (:day row))))
  (testing "one that has none has no reason"
    (is (not-any? #(contains? % :reason) (filter #(not= "appt-ana-0" (:id %)) (views/appointment-rows))))))

(deftest the-appointments-are-those-of-the-cohort-and-the-ones-written-by-hand-soonest-first
  (is (= ["appt-ana-0" "appt-noah-1" "appt-ana-1" "appt-maya-1" "appt-sofia-1" "appt-liam-1" "appt-ana-2"]
         (map :id (views/appointment-rows))))
  (is (= (+ (count (data/appointments)) (count data/other-appointments)) (count (views/appointment-rows)))))

(deftest the-appointments-of-a-patient-are-the-ones-that-are-theirs
  (is (= [["appt-ana-0" "cancelled"] ["appt-ana-1" "booked"] ["appt-ana-2" "proposed"]]
         (map (juxt :id :status) (views/appointments-of "pt-ana"))))
  (is (= ["appt-noah-1"] (map :id (views/appointments-of "pt-noah"))))
  (is (empty? (views/appointments-of "pt-omar")))
  (is (empty? (views/appointments-of "pt-nobody"))))

;; ---------------------------------------------------------------------------
;; Alerts

(deftest an-alert-card-says-which-patient-which-rule-and-the-readings-behind-it
  (let [cards (into {} (map (juxt :id identity)) (views/alert-cards))
        ana (get cards "flag-pt-ana-R1")]
    (is (= "BP stage 2" (:text ana)))
    (is (= :critical (:tone ana)))
    (is (= "Ana García" (:name (:patient ana))))
    (is (= "yesterday, 22:30" (:since-phrase ana)))
    (is (true? (:active? ana)))
    (is (nil? (:until-phrase ana)))
    (is (= ["177/100 mmHg, today, 10:00" "172/102 mmHg, yesterday, 22:00" "165/106 mmHg, yesterday, 10:00"]
           (:evidence ana)) "the newest first")
    (is (= (views/rule-descriptions "BP stage 2") (:description ana)))
    (testing "what the api does not say, the card does not say"
      (is (not-any? #(contains? ana %) [:by :acknowledged-by])))))

(deftest the-rules-have-the-tones-of-their-urgency
  (let [cards (into {} (map (juxt (comp :text) identity)) (views/alert-cards))]
    (is (= {"BP stage 2" :critical "Low SpO₂" :high "Glucose out of range" :high "Rapid weight gain" :elevated}
           (update-vals cards :tone)))))

(deftest the-readings-behind-an-alert-are-of-what-its-rule-watches
  (let [cards (into {} (map (juxt :text identity)) (views/alert-cards))]
    (is (every? #(str/includes? % "%") (:evidence (get cards "Low SpO₂"))))
    (is (every? #(str/includes? % "mg/dL") (:evidence (get cards "Glucose out of range"))))
    (is (every? #(str/includes? % "kg") (:evidence (get cards "Rapid weight gain"))))
    (is (= ["90 %, today, 10:00" "91 %, yesterday, 22:00" "90 %, yesterday, 10:00"] (:evidence (get cards "Low SpO₂"))))
    (testing "a day that is not today or yesterday keeps the capital of its month"
      (is (= ["75.8 kg, today, 10:00" "74.6 kg, yesterday, 10:00" "73.7 kg, Oct 11, 10:00"]
             (:evidence (get cards "Rapid weight gain")))))))

(deftest an-acknowledged-alert-has-the-time-of-it
  (let [liam (first (filter #(= "flag-pt-liam-R3" (:id %)) (views/alert-cards)))]
    (is (false? (:active? liam)))
    (is (= "today, 09:40" (:until-phrase liam)))
    (is (= "Oct 11, 22:30" (:since-phrase liam)))))

(deftest the-inbox-has-the-active-alerts-first-and-the-newest-first
  (is (= ["flag-pt-maya-R4" "flag-pt-ana-R1" "flag-pt-sofia-R2" "flag-pt-liam-R3"] (map :id (views/alert-cards))))
  (is (= [true true true false] (map :active? (views/alert-cards)))))

(deftest what-a-rule-watches-is-said-in-the-words-of-the-rule
  (is (= (set (map :title rules/catalogue)) (set (keys views/rule-descriptions))))
  (doseq [{:keys [title doc]} rules/catalogue]
    (is (= (str/replace doc #"\s+" " ") (get views/rule-descriptions title)) title)))

;; ---------------------------------------------------------------------------
;; Patients

(deftest how-far-from-normal-a-patient-is
  (is (= {"pt-ana" 3 "pt-sofia" 1 "pt-liam" 1 "pt-maya" 1 "pt-omar" 0 "pt-noah" -1}
         (into {} (map (juxt identity views/severity)) ["pt-ana" "pt-sofia" "pt-liam" "pt-maya" "pt-omar" "pt-noah"]))))

(deftest the-headline-of-a-patient-is-the-reading-furthest-from-normal
  (is (= {:kind :blood-pressure :reading (data/latest "pt-ana" :blood-pressure)} (views/headline "pt-ana")))
  (is (= {:kind :spo2 :reading (data/latest "pt-sofia" :spo2)} (views/headline "pt-sofia"))
      "an oxygen saturation that is low beats a heart rate that is normal")
  (is (= :blood-pressure (:kind (views/headline "pt-liam"))) "a pressure that is elevated beats a glucose that is normal")
  (is (= :blood-pressure (:kind (views/headline "pt-omar"))) "of readings equally normal, the first measurement")
  (is (nil? (views/headline "pt-noah")) "a patient with no readings has none"))

(deftest the-alerts-of-a-patient-that-are-active
  (is (= {"pt-ana" 1 "pt-sofia" 1 "pt-maya" 1 "pt-liam" 0 "pt-omar" 0 "pt-noah" 0}
         (into {} (map (juxt identity views/active-alerts)) ["pt-ana" "pt-sofia" "pt-maya" "pt-liam" "pt-omar" "pt-noah"]))
      "the alert of Liam was acknowledged"))

(deftest the-most-urgent-patient-comes-first
  (is (= ["pt-ana" "pt-maya" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"]
         (views/by-urgency ["pt-noah" "pt-sofia" "pt-maya" "pt-liam" "pt-omar" "pt-ana"]))
      "the worst reading, then the alerts, then the family name; no readings last")
  (is (= ["pt-sofia" "pt-liam"] (views/by-urgency ["pt-liam" "pt-sofia"])) "the same severity: the one with an alert")
  (is (= [] (views/by-urgency []))))
