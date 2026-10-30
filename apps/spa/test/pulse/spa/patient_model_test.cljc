(ns pulse.spa.patient-model-test
  "What the chart of a patient shows (ADR-0029, point 6), from the state the machine keeps: who the patient is, which measurement the
  chart is of, each list of the summary, the alerts with the words of their rules and the readings behind them, the appointments, and
  what is said of what could not be loaded. Pure `.cljc`: the JVM and Node run the same tests, over the data of
  `pulse.spa.test.patient-fixtures`."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.patient-fixtures :as fx]))

(defn- with-summary [chart data] (assoc chart :summary (fx/loaded data)))

(defn- ana-with
  "The chart of Ana García, settled, with the summary changed by `f`."
  [f]
  (with-summary (fx/settled-ana) (f fx/ana-summary)))

;; ---------------------------------------------------------------------------
;; Who the patient is

(deftest the-header-is-the-name-and-what-the-clinic-knows-of-the-patient
  (let [m (patient/model (fx/settled-ana))]
    (is (false? (:gone? m)))
    (is (= "Ana García" (:title m)))
    (is (= "58 years · Female · PULSE-0001 · Madrid" (:description m)))
    (is (= :loaded (:patient m)))))

(deftest a-patient-with-less-on-file-has-a-shorter-line
  (is (= "25 years · Male · PULSE-0005" (:description (patient/model (fx/settled-noah)))) "no address, no city")
  (let [resource-of (fn [changes] (:description (patient/model (assoc (fx/settled-ana) :patient (fx/loaded (merge fx/ana-resource changes))))))]
    (is (= "58 years · Female · PULSE-0001" (resource-of {:address nil})))
    (is (= "58 years · PULSE-0001 · Madrid" (resource-of {:gender nil})))
    (is (= "58 years · Female · Madrid" (resource-of {:identifier nil})))
    (is (= "Female · PULSE-0001 · Madrid" (resource-of {:birthDate nil})))
    (is (nil? (resource-of {:birthDate nil :gender nil :identifier nil :address nil})) "nothing to say, no line")))

(deftest a-year-is-one-year
  (is (= "1 year · Female · PULSE-0001 · Madrid"
         (:description (patient/model (assoc (fx/settled-ana) :patient (fx/loaded (assoc fx/ana-resource :birthDate "2025-10-01")))))))
  (is (= "0 years · Female · PULSE-0001 · Madrid"
         (:description (patient/model (assoc (fx/settled-ana) :patient (fx/loaded (assoc fx/ana-resource :birthDate "2026-03-01"))))))))

(deftest what-other-systems-wrote-oddly-is-left-out-of-the-header
  (let [odd (fn [changes] (patient/model (assoc (fx/settled-ana) :patient (fx/loaded (merge fx/ana-resource changes)))))]
    (testing "a birth date that is no date has no age"
      (doseq [birth ["soon" "1968" "1968-13-40" 5 nil {} []]]
        (is (= "Female · PULSE-0001 · Madrid" (:description (odd {:birthDate birth}))) (pr-str birth))))
    (testing "fields that are not what they should be say nothing"
      (is (= "58 years" (:description (odd {:gender 5 :identifier "PULSE-1" :address {:city "Madrid"}}))))
      (is (= "58 years · Female" (:description (odd {:identifier [{:value 5}] :address [{:city ""}]})))))
    (testing "a name that is not a list, or is empty, is not named"
      (doseq [name [nil [] "Ana" [{}] [{:family " "}] [nil] {:family "García"}]]
        (is (= "Not named" (:title (odd {:name name}))) (pr-str name))))
    (testing "a name is given names and family name, as the clinic writes them"
      (is (= "Ana María García" (:title (odd {:name [{:family "García" :given ["Ana" "María"]}]}))))
      (is (= "García" (:title (odd {:name [{:family "García"}]}))))
      (is (= "Ana" (:title (odd {:name [{:given ["Ana"]}]}))))
      (is (= "Ana García" (:title (odd {:name [{:family "García" :given ["Ana" "" 5 nil]}]})))))))

(deftest while-the-patient-is-on-its-way-the-page-has-a-title-and-nothing-else-about-them
  (let [m (patient/model (patient/start fx/now {:id "pt-ana" :measurement nil :days 14}))]
    (is (= "Patient" (:title m)))
    (is (nil? (:description m)))
    (is (= :loading (:patient m)))
    (is (false? (:gone? m)))))

;; ---------------------------------------------------------------------------
;; A patient who is not yours, or is none

(deftest a-patient-the-api-refuses-or-does-not-know-is-gone-and-nothing-else-is-shown-of-them
  (doseq [kind [:forbidden :not-found]]
    (let [m (patient/model (assoc (fx/settled-ana) :patient {:state :failed :kind kind}))]
      (is (true? (:gone? m)) (pr-str kind))
      (is (= "Patient" (:title m)))
      (is (= "That patient could not be found" (:live m)) "and it does not say which of the two it was")))
  (testing "a failure of another kind is a header that could not be loaded"
    (doseq [kind [:server :timeout :unreachable :unexpected :unauthorized :refused]]
      (let [m (patient/model (assoc (fx/settled-ana) :patient {:state :failed :kind kind}))]
        (is (false? (:gone? m)) (pr-str kind))
        (is (= :failed (:patient m)))))))

;; ---------------------------------------------------------------------------
;; What the chart is of

(deftest the-chart-is-of-the-measurement-and-the-period-it-has
  (let [m (patient/model (fx/settled-ana))]
    (is (= :blood-pressure (:kind m)))
    (is (= 14 (:days m)))
    (is (= [7 14 30] (:periods m)))
    (is (= :loaded (get-in m [:series :state]))))
  (let [m (patient/model (fx/settled-ana {:asked :heart-rate :days 30}))]
    (is (= :heart-rate (:kind m)))
    (is (= 30 (:days m)))))

(deftest the-measurements-to-choose-between-are-those-the-patient-has-readings-of-and-the-one-shown
  (is (= [:blood-pressure :heart-rate] (:offered (patient/model (fx/settled-ana)))))
  (is (= [:blood-pressure :heart-rate :glucose] (:offered (patient/model (fx/settled-ana {:asked :glucose})))) "and the one asked for")
  (is (= [:heart-rate :spo2] (:offered (patient/model (fx/settled-sofia)))))
  (is (= [] (:offered (patient/model (fx/settled-noah)))))
  (testing "none to choose while the summary is on its way, all of them when it could not be had"
    (is (nil? (:offered (patient/model (assoc (fx/settled-ana) :summary {:state :loading})))))
    (is (= [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature]
           (:offered (patient/model (assoc (fx/settled-ana) :summary {:state :failed :kind :server})))))))

(deftest a-patient-with-nothing-measured-has-no-readings-and-one-whose-readings-were-lost-may-have-some
  (is (true? (:no-readings? (patient/model (fx/settled-noah)))))
  (is (false? (:no-readings? (patient/model (fx/settled-ana)))))
  (is (false? (:no-readings? (patient/model (with-summary (fx/settled-noah) (assoc fx/noah-summary :unavailable ["vitals.glucose"]))))) "a reading that could not be loaded may be there")
  (is (false? (:no-readings? (patient/model (assoc (fx/settled-noah) :summary {:state :loading})))))
  (is (false? (:no-readings? (patient/model (assoc (fx/settled-noah) :summary {:state :failed :kind :server}))))))

;; ---------------------------------------------------------------------------
;; The lists of the summary

(deftest the-lists-of-the-record-are-what-the-summary-has-on-file
  (let [m (patient/model (fx/settled-ana))]
    (is (= ["Essential (primary) hypertension"] (map :display (:items (:conditions m)))))
    (is (= ["lisinopril"] (map :display (:items (:medications m)))))
    (is (= ["penicillin"] (map :display (:items (:allergies m)))))))

(deftest a-list-that-is-empty-is-an-empty-list-and-one-that-is-lost-is-lost
  (let [m (patient/model (fx/settled-noah))]
    (is (= {:state :loaded :items []} (:conditions m)))
    (is (= {:state :loaded :items []} (:medications m)))
    (is (= {:state :loaded :items []} (:allergies m))))
  (testing "the summary names what it could not load"
    (let [m (patient/model (ana-with #(assoc % :medications nil :unavailable ["medications"])))]
      (is (= {:state :lost} (:medications m)))
      (is (= :loaded (:state (:conditions m))) "and the rest is there")))
  (testing "a list that is not there is lost, though the summary does not say so"
    (let [m (patient/model (ana-with #(dissoc % :allergies)))]
      (is (= {:state :lost} (:allergies m)))))
  (testing "a list that is not a list is lost"
    (doseq [value ["none" 5 {:a 1} true]]
      (is (= {:state :lost} (:conditions (patient/model (ana-with #(assoc % :conditions value))))) (pr-str value))))
  (testing "what is in a list and is not an item is left out"
    (let [m (patient/model (ana-with #(assoc % :conditions [nil "x" 5 {:id "c" :display "Asthma"}])))]
      (is (= ["Asthma"] (map :display (:items (:conditions m))))))))

(deftest while-the-summary-is-on-its-way-every-list-is-loading-and-when-it-failed-every-list-is-lost
  (let [loading (patient/model (assoc (fx/settled-ana) :summary {:state :loading}))
        lost (patient/model (assoc (fx/settled-ana) :summary {:state :failed :kind :server}))]
    (doseq [k [:conditions :medications :allergies :alerts :appointments :vitals]]
      (is (= :loading (get-in loading [k :state])) (pr-str k))
      (is (= :lost (get-in lost [k :state])) (pr-str k)))))

(deftest the-latest-readings-are-those-that-say-something-in-the-order-the-measurements-are-shown
  (let [m (patient/model (fx/settled-ana))]
    (is (= :loaded (get-in m [:vitals :state])))
    (is (= [:blood-pressure :heart-rate] (map :kind (get-in m [:vitals :items]))))
    (is (false? (get-in m [:vitals :lost?]))))
  (testing "a reading that says nothing is left out"
    (let [m (patient/model (ana-with #(assoc-in % [:vitals :glucose] {:id "g" :at "2026-10-13T08:00:00Z" :value nil})))]
      (is (= [:blood-pressure :heart-rate] (map :kind (get-in m [:vitals :items]))))))
  (testing "the summary says which it could not load"
    (let [m (patient/model (ana-with #(assoc % :unavailable ["vitals.glucose" "alerts"])))]
      (is (true? (get-in m [:vitals :lost?])))))
  (testing "a summary with no vitals has lost them"
    (is (= :lost (get-in (patient/model (ana-with #(dissoc % :vitals))) [:vitals :state])))))

;; ---------------------------------------------------------------------------
;; The alerts

(deftest an-active-alert-is-told-by-its-rule-with-the-readings-behind-it
  (let [alert (first (get-in (patient/model (fx/settled-ana)) [:alerts :items]))]
    (is (= "flag-pt-ana-R1" (:id alert)))
    (is (= "BP stage 2" (:text alert)))
    (is (= :critical (:tone alert)))
    (is (= "yesterday, 22:30" (:since-phrase alert)))
    (is (= "Blood pressure at stage 2 (a systolic of 160 or more, or a diastolic of 100 or more) in at least 2 of the last 3 readings of the last 3 days."
           (:description alert)))
    (is (= {:state :loaded :lines ["177/100 mmHg, today, 10:00" "172/100 mmHg, yesterday, 10:00" "165/98 mmHg, Oct 11, 10:00"]}
           (:evidence alert)))))

(deftest the-readings-behind-an-alert-are-loading-or-lost-in-their-own-time
  (let [evidence-of (fn [chart] (:evidence (first (get-in (patient/model chart) [:alerts :items]))))]
    (is (= {:state :loading} (evidence-of (assoc-in (fx/settled-ana) [:evidence :blood-pressure] {:state :loading}))))
    (is (= {:state :failed} (evidence-of (assoc-in (fx/settled-ana) [:evidence :blood-pressure] {:state :failed :kind :server}))))
    (is (= {:state :loading} (evidence-of (assoc (fx/settled-ana) :evidence {}))) "readings nobody has begun to ask for are on their way")))

(deftest an-alert-of-no-known-rule-is-an-alert-with-no-words-of-a-rule
  (let [m (patient/model (ana-with #(assoc % :alerts [{:id "x" :text "Something else" :status "active" :since "2026-10-12T20:30:00Z"}])))
        alert (first (get-in m [:alerts :items]))]
    (is (= "Something else" (:text alert)))
    (is (= :high (:tone alert)))
    (is (nil? (:description alert)))
    (is (nil? (:evidence alert)))))

(deftest an-alert-with-no-text-or-no-time-is-still-an-alert
  (let [m (patient/model (ana-with #(assoc % :alerts [{:id "x" :status "active"} {:id "y" :text " " :since "soon"} {:id "z" :text 5}])))
        alerts (get-in m [:alerts :items])]
    (is (= ["Alert" "Alert" "Alert"] (map :text alerts)))
    (is (every? nil? (map :since-phrase alerts)))
    (is (every? nil? (map :evidence alerts)))))

(deftest a-patient-with-no-alert-has-none-and-one-whose-alerts-were-lost-has-lost-them
  (is (= {:state :loaded :items []} (:alerts (patient/model (fx/settled-noah)))))
  (is (= {:state :lost} (:alerts (patient/model (ana-with #(assoc % :alerts nil :unavailable ["alerts"])))))))

;; ---------------------------------------------------------------------------
;; The appointments

(deftest the-appointments-are-what-is-coming-then-what-has-begun-then-what-was-cancelled
  (let [rows (get-in (patient/model (fx/settled-ana)) [:appointments :rows])]
    (is (= ["appt-ana-1" "appt-ana-0"] (map :id rows)))
    (is (= ["booked" "cancelled"] (map :status rows)))
    (is (= "Wed 14 Oct, 10:00–10:30" (:when (first rows))))))

(deftest a-request-has-the-days-it-asks-for-and-no-time
  (let [rows (get-in (patient/model (with-summary (fx/settled-noah)
                                      (assoc fx/noah-summary :appointments [{:id "r" :status "proposed" :description "First visit"
                                                                             :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"}])))
                     [:appointments :rows])]
    (is (= ["r"] (map :id rows)))
    (is (re-find #"^Asked for a visit between " (:asked (first rows))))))

(deftest appointments-are-lost-when-they-could-not-be-loaded-and-none-is-none
  (is (= {:state :loaded :rows []} (:appointments (patient/model (fx/settled-sofia)))))
  (is (= {:state :lost} (:appointments (patient/model (ana-with #(assoc % :appointments nil :unavailable ["appointments"])))))))

;; ---------------------------------------------------------------------------
;; What a screen reader is told

(deftest the-screen-reader-is-told-what-happened-to-the-chart
  (is (= "Loading the chart of the patient" (:live (patient/model (patient/start fx/now {:id "pt-ana" :days 14})))))
  (is (= "Loading the chart of Ana García" (:live (patient/model (assoc (fx/settled-ana) :summary {:state :loading})))))
  (is (= "The chart of Ana García is loaded" (:live (patient/model (fx/settled-ana)))))
  (is (= "The chart of Ana García is loaded. 1 part could not be loaded"
         (:live (patient/model (assoc (fx/settled-ana) :summary {:state :failed :kind :server})))))
  (is (= "The chart of Ana García is loaded. 2 parts could not be loaded"
         (:live (patient/model (-> (fx/settled-ana) (assoc :summary {:state :failed :kind :server}) (assoc-in [:evidence :blood-pressure] {:state :failed :kind :server}))))))
  (is (= "That patient could not be found" (:live (patient/model (assoc (fx/settled-ana) :patient fx/forbidden))))))

(deftest settled-is-the-machines
  (is (true? (:settled? (patient/model (fx/settled-ana)))))
  (is (false? (:settled? (patient/model (assoc (fx/settled-ana) :summary {:state :loading}))))))
