(ns pulse.web.views-test
  "What the pages of the portal show of the data of the api: the words of a reading and of an appointment, in the shapes the api
  answers with. What a page says is held to what the libraries say: the measurements the api records, the lifecycle of an
  appointment."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.fhir.measurement :as measurement]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.views :as views]))

(def ^:private now "2026-10-13T12:00:00Z")

;; ---------------------------------------------------------------------------
;; Readings

(deftest the-measurements-are-the-ones-the-api-records-each-with-its-name-and-its-icon
  (is (= (set (keys measurement/measurements)) (set views/kinds)) "no more and no fewer than what can be recorded")
  (is (= (count views/kinds) (count (distinct views/kinds))))
  (is (= (set views/kinds) (set (keys views/kind-info))))
  (is (every? #(and (string? (:label %)) (keyword? (:icon %))) (vals views/kind-info)))
  (is (= (count views/kinds) (count (distinct (map :label (vals views/kind-info))))) "a name for each")
  (is (= [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature] views/kinds) "the order they are shown in"))

(deftest a-reading-is-written-as-people-write-it
  (is (= "177/100" (views/reading-text :blood-pressure (data/latest "pt-ana" :blood-pressure))))
  (is (= "82.4" (views/reading-text :weight {:value 82.4})))
  (is (= "70" (views/reading-text :heart-rate {:value 70})))
  (is (= "70" (views/reading-text :heart-rate {:value 70.0})) "a whole number has no decimals")
  (is (= "132/85.5" (views/reading-text :blood-pressure {:systolic 132 :diastolic 85.5}))))

(deftest a-reading-says-something-when-it-has-its-number-or-numbers
  (is (true? (views/usable? :blood-pressure {:systolic 130 :diastolic 80})))
  (is (false? (views/usable? :blood-pressure {:systolic 130 :diastolic nil})))
  (is (false? (views/usable? :blood-pressure {:systolic nil :diastolic 80})))
  (is (false? (views/usable? :blood-pressure {:value 130})) "a blood pressure is two numbers")
  (is (true? (views/usable? :weight {:value 82.4})))
  (is (false? (views/usable? :weight {:value nil})))
  (is (false? (views/usable? :weight {:systolic 130 :diastolic 80})))
  (is (false? (views/usable? :weight {:value "82"})) "a text is not a number")
  (is (false? (views/usable? :weight nil))))

(deftest the-time-of-a-reading-is-shown-when-it-can-be-read
  (is (= "Today, 10:00" (views/when-of {:at "2026-10-13T08:00:00Z"} now)))
  (is (= "Yesterday, 22:00" (views/when-of {:at "2026-10-12T20:00:00Z"} now)))
  (doseq [at [nil "" "2026-10-13" "yesterday" 12 "2026-10-13T08:00:00"]]
    (is (nil? (views/when-of {:at at} now)) (pr-str at))))

(deftest a-tile-shows-the-latest-reading-of-a-patient
  (let [tile (views/vital-tile :blood-pressure (data/latest "pt-ana" :blood-pressure) now)
        text (hx/words tile)]
    (is (str/includes? text "Blood pressure"))
    (is (str/includes? text "177/100 mmHg"))
    (is (str/includes? text "Very high"))
    (is (str/includes? text "Today, 10:00"))
    (is (= [] (a11y/problems tile))))
  (testing "a reading with no band has no chip"
    (is (not (str/includes? (hx/words (views/vital-tile :weight (data/latest "pt-ana" :weight) now)) "Normal"))))
  (testing "attributes go to the tile"
    (is (= "x" (:data-test (hx/attributes (views/vital-tile :heart-rate (data/latest "pt-ana" :heart-rate) now {:data-test "x"})))))))

(deftest a-tile-is-made-of-what-the-reading-has
  (testing "with no time that can be read there is no time under it"
    (let [tile (views/vital-tile :heart-rate {:id "r" :at "2026-10-13" :value 70 :unit "/min" :band "normal"} now)]
      (is (= ["Heart rate" "70 bpm"] (map hx/words (hx/by-tag tile :p))) "the name and the reading, and there is no third line")))
  (testing "with no unit the number stands alone"
    (is (not (str/includes? (hx/words (views/vital-tile :weight {:id "r" :at "2026-10-13T08:00:00Z" :value 70} now)) "kg"))))
  (testing "the unit is written as people write it"
    (is (str/includes? (hx/words (views/vital-tile :spo2 {:id "r" :at "2026-10-13T08:00:00Z" :value 97 :unit "%"} now)) "97 %"))
    (is (str/includes? (hx/words (views/vital-tile :temperature {:id "r" :at "2026-10-13T08:00:00Z" :value 36.6 :unit "Cel"} now)) "36.6 °C"))))

(defn- item [kind at & {:as numbers}]
  (merge {:id (str kind at) :measurement (name kind) :at at :unit "x" :band nil} numbers))

(deftest the-recent-readings-are-the-newest-first-whatever-they-measure
  (let [items [(item :weight "2026-10-12T08:00:00Z" :value 82)
               (item :heart-rate "2026-10-13T08:00:00Z" :value 70)
               (item :blood-pressure "2026-10-13T09:00:00Z" :systolic 130 :diastolic 80)
               (item :heart-rate "2026-10-11T08:00:00Z" :value 71)]]
    (is (= [[:blood-pressure "2026-10-13T09:00:00Z"] [:heart-rate "2026-10-13T08:00:00Z"] [:weight "2026-10-12T08:00:00Z"]]
           (map (fn [{:keys [kind reading]}] [kind (:at reading)]) (views/recent-readings items 3))))
    (is (= 4 (count (views/recent-readings items 10))))
    (is (= [] (views/recent-readings items 0)))
    (is (= [] (views/recent-readings [] 5)))
    (is (= [] (views/recent-readings nil 5)))
    (is (= (set (map :id items)) (set (map (comp :id :reading) (views/recent-readings items 4))))
        "and what comes back is the readings themselves")))

(deftest readings-of-one-moment-come-in-the-order-of-the-measurements
  (let [at "2026-10-13T08:00:00Z"
        items [(item :weight at :value 82) (item :temperature at :value 36) (item :heart-rate at :value 70)
               (item :glucose at :value 90) (item :spo2 at :value 97) (item :blood-pressure at :systolic 130 :diastolic 80)]]
    (is (= views/kinds (map :kind (views/recent-readings items 6))))
    (testing "whatever order they were given in"
      (is (= views/kinds (map :kind (views/recent-readings (reverse items) 6))))
      (is (= views/kinds (map :kind (views/recent-readings (shuffle items) 6)))))))

(deftest a-reading-that-says-nothing-is-not-among-the-recent-ones
  (let [good (item :heart-rate "2026-10-13T08:00:00Z" :value 70)]
    (doseq [bad [(item :heart-rate "2026-10-13T09:00:00Z" :value nil)
                 (item :heart-rate "yesterday" :value 70)
                 (item :heart-rate nil :value 70)
                 (item :heart-rate "2026-10-13" :value 70)
                 (item :blood-pressure "2026-10-13T09:00:00Z" :systolic 130)
                 (assoc (item :heart-rate "2026-10-13T09:00:00Z" :value 70) :measurement "mood")
                 (dissoc (item :heart-rate "2026-10-13T09:00:00Z" :value 70) :measurement)]]
      (is (= [good] (map :reading (views/recent-readings [bad good] 5))) (pr-str bad)))))

(deftest the-measurements-a-summary-has-a-reading-of-are-the-ones-whose-series-are-asked-for
  (is (= [:blood-pressure :heart-rate :weight] (views/measured-kinds (data/summary "pt-ana"))))
  (is (= [:blood-pressure :glucose :weight] (views/measured-kinds (data/summary "pt-liam"))))
  (is (= [:heart-rate :spo2] (views/measured-kinds (data/summary "pt-sofia"))))
  (is (= [] (views/measured-kinds (data/summary "pt-noah"))))
  (is (= [] (views/measured-kinds nil)))
  (is (= [] (views/measured-kinds {:vitals {}})))
  (testing "in the order of the measurements, not of the map"
    (is (= [:heart-rate :glucose :weight]
           (views/measured-kinds {:vitals {:weight {:id "w"} :glucose {:id "g"} :heart-rate {:id "h"} :spo2 nil :blood-pressure nil}})))))

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

(def ^:private doctors {"Practitioner/pr-rivera" "Dr. Elena Rivera"})

(defn- appointment [& {:as fields}]
  (merge {:id "a1" :status "booked" :description "Hypertension follow-up" :start nil :end nil
          :requestedStart nil :requestedEnd nil :practitioner "Practitioner/pr-rivera" :reason nil}
         fields))

(deftest a-booked-appointment-has-its-day-its-time-and-its-doctor
  (is (= {:id "a1" :status "booked" :description "Hypertension follow-up" :doctor "Dr. Elena Rivera"
          :month "Oct" :day "14" :when "Wed 14 Oct, 10:00–10:30" :start "2026-10-14T08:00:00Z"}
         (views/appointment-row (appointment :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z") doctors))))

(deftest a-request-has-the-days-that-were-asked-for-and-no-day
  (let [row (views/appointment-row (appointment :status "proposed" :requestedStart "2026-10-14T00:00:00Z" :requestedEnd "2026-10-20T00:00:00Z") doctors)]
    (is (= "Asked for a visit between Oct 14 and Oct 20" (:asked row)))
    (is (= "2026-10-14T00:00:00Z" (:requested-start row)))
    (is (not-any? #(contains? row %) [:month :day :when :start :reason]))))

(deftest a-booked-appointment-that-kept-the-days-asked-for-is-not-a-request
  (let [row (views/appointment-row (appointment :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z"
                                                :requestedStart "2026-10-14T00:00:00Z" :requestedEnd "2026-10-20T00:00:00Z")
                                   doctors)]
    (is (= "Tue 20 Oct, 10:00–10:30" (:when row)))
    (is (not-any? #(contains? row %) [:asked :requested-start]) "it has its time, and the window it was asked in is not shown"))
  (testing "one with neither a time nor days says neither"
    (let [row (views/appointment-row (appointment :status "pending") doctors)]
      (is (not-any? #(contains? row %) [:asked :requested-start :when :start :month :day])))))

(deftest a-cancelled-appointment-has-the-reason-that-was-given
  (let [row (views/appointment-row (appointment :status "cancelled" :start "2026-09-29T07:00:00Z" :end "2026-09-29T07:30:00Z"
                                                :reason "I had to travel that week.")
                                   doctors)]
    (is (= "cancelled" (:status row)))
    (is (= "I had to travel that week." (:reason row)))
    (is (= "Tue 29 Sep, 09:00–09:30" (:when row)))
    (is (= ["Sep" "29"] ((juxt :month :day) row))))
  (testing "one that has none has no reason"
    (is (not (contains? (views/appointment-row (appointment) doctors) :reason)))))

(deftest the-doctor-is-looked-up-by-reference-and-is-nil-when-it-is-not-there
  (is (= "Dr. Elena Rivera" (:doctor (views/appointment-row (appointment) doctors))))
  (is (nil? (:doctor (views/appointment-row (appointment :practitioner "Practitioner/pr-nobody") doctors))))
  (is (nil? (:doctor (views/appointment-row (appointment :practitioner nil) doctors))))
  (is (nil? (:doctor (views/appointment-row (appointment) {}))))
  (is (nil? (:doctor (views/appointment-row (appointment) nil)))))

(deftest an-appointment-whose-end-or-window-is-missing-or-wrong-still-has-a-row
  (testing "a time with no end is only the time it begins at"
    (doseq [end [nil "" "later" "2026-10-14"]]
      (is (= "Wed 14 Oct, 10:00" (:when (views/appointment-row (appointment :start "2026-10-14T08:00:00Z" :end end) doctors))) (pr-str end))))
  (testing "a request with no end to its window asks from the day it begins"
    (doseq [end [nil "" "later"]]
      (is (= "Asked for a visit between Oct 14 and later" (:asked (views/appointment-row (appointment :status "proposed" :requestedStart "2026-10-14T00:00:00Z" :requestedEnd end) doctors)))
          (pr-str end)))))

(deftest the-next-appointment-is-the-soonest-booked-one-that-has-not-begun-and-else-the-soonest-request
  (let [booked-soon (appointment :id "soon" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z")
        booked-late (appointment :id "late" :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z")
        past (appointment :id "past" :start "2026-10-01T08:00:00Z" :end "2026-10-01T08:30:00Z")
        request-soon (appointment :id "req-soon" :status "proposed" :requestedStart "2026-10-14T00:00:00Z" :requestedEnd "2026-10-20T00:00:00Z")
        request-late (appointment :id "req-late" :status "proposed" :requestedStart "2026-10-18T00:00:00Z" :requestedEnd "2026-10-25T00:00:00Z")
        cancelled (appointment :id "gone" :status "cancelled" :start "2026-10-14T06:00:00Z" :end "2026-10-14T06:30:00Z")
        id-of #(:id (views/next-appointment % now))]
    (is (= "soon" (id-of [booked-late booked-soon past request-soon cancelled])) "the soonest, whatever the order")
    (is (= "soon" (id-of (shuffle [booked-late booked-soon past request-soon request-late cancelled]))))
    (is (= "late" (id-of [booked-late past request-soon])) "a booked one comes before a request, even a request that asks for earlier days")
    (testing "one that has begun is not next, and neither is one that was cancelled"
      (is (= "req-soon" (id-of [past request-late request-soon cancelled])))
      (is (nil? (id-of [past cancelled]))))
    (testing "a request is told by the day it asks for"
      (is (= "req-soon" (id-of [request-late request-soon]))))
    (is (nil? (id-of [])))
    (is (nil? (id-of nil)))
    (testing "the moment it begins is the moment it has begun, as the lifecycle says"
      (is (nil? (views/next-appointment [booked-soon] "2026-10-14T08:00:00Z")))
      (is (= "soon" (:id (views/next-appointment [booked-soon] "2026-10-14T07:59:59Z")))))))

(deftest an-appointment-with-no-time-that-can-be-read-is-never-the-next
  (let [odd [(appointment :id "a" :start "soon" :end "later")
             (appointment :id "b" :start nil)
             (appointment :id "c" :status "proposed" :requestedStart "next week" :requestedEnd "later")
             (appointment :id "d" :status "proposed" :requestedStart nil)]
        good (appointment :id "ok" :status "proposed" :requestedStart "2026-10-14T00:00:00Z" :requestedEnd "2026-10-20T00:00:00Z")]
    (is (nil? (views/next-appointment odd now)))
    (is (= "ok" (:id (views/next-appointment (conj odd good) now))))))

(deftest what-the-cohort-has-is-what-the-patients-are-shown
  (testing "Ana: the confirmed one, with the name of her doctor from what the api says of her"
    (let [summary (data/summary "pt-ana")
          doctors (let [{:keys [reference name]} (:generalPractitioner (data/me "pt-ana"))] {reference name})
          row (views/appointment-row (views/next-appointment (:appointments summary) data/now-text) doctors)]
      (is (= ["appt-ana-1" "Hypertension follow-up" "Wed 14 Oct, 10:00–10:30" "Dr. Elena Rivera"]
             ((juxt :id :description :when :doctor) row)))))
  (testing "Noah: the request that waits"
    (let [summary (data/summary "pt-noah")
          doctors (let [{:keys [reference name]} (:generalPractitioner (data/me "pt-noah"))] {reference name})
          row (views/appointment-row (views/next-appointment (:appointments summary) data/now-text) doctors)]
      (is (= ["appt-noah-1" "Asked for a visit between Oct 14 and Oct 20" "Dr. Chidi Okafor"] ((juxt :id :asked :doctor) row)))))
  (testing "Omar has none"
    (is (nil? (views/next-appointment (:appointments (data/summary "pt-omar")) data/now-text)))))

;; ---------------------------------------------------------------------------
;; The list of appointments

(deftest the-doctors-of-a-patient-are-looked-up-by-the-reference-the-api-gave
  (is (= {"Practitioner/pr-rivera" "Dr. Elena Rivera"} (views/doctors-of (data/me "pt-ana"))))
  (is (= {"Practitioner/pr-okafor" "Dr. Chidi Okafor"} (views/doctors-of (data/me "pt-noah"))))
  (testing "a patient the api names no doctor of has none to look up"
    (doseq [me [{} {:generalPractitioner nil} {:generalPractitioner {:name "Dr. X"}} {:generalPractitioner {}} nil]]
      (is (= {} (views/doctors-of me)) (pr-str me)))))

(def ^:private listed
  "Appointments of the api, each in the section it belongs to at the moment of the mock-ups, in no order."
  [(appointment :id "late" :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z")
   (appointment :id "soon" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z")
   (appointment :id "past" :start "2026-10-01T08:00:00Z" :end "2026-10-01T08:30:00Z")
   (appointment :id "older" :start "2026-09-01T08:00:00Z" :end "2026-09-01T08:30:00Z")
   (appointment :id "ask" :status "proposed" :requestedStart "2026-10-18T00:00:00Z" :requestedEnd "2026-10-25T00:00:00Z")
   (appointment :id "gone" :status "cancelled" :start "2026-09-29T07:00:00Z" :end "2026-09-29T07:30:00Z" :reason "I had to travel.")
   (appointment :id "gone-ask" :status "cancelled" :requestedStart "2026-10-05T00:00:00Z" :requestedEnd "2026-10-09T00:00:00Z")
   (appointment :id "nothing" :status "pending")])

(defn- ids [rows] (mapv :id rows))

(deftest the-appointments-of-a-patient-are-what-is-coming-what-began-and-what-was-cancelled
  (let [{:keys [coming earlier cancelled]} (views/appointment-sections listed doctors now)]
    (testing "what is coming: the booked ones that have not begun and the requests, the soonest first, and what has no time last"
      (is (= ["soon" "ask" "late" "nothing"] (ids coming))))
    (testing "what began, which the system does not know took place, the newest first"
      (is (= ["past" "older"] (ids earlier))))
    (testing "what was cancelled, the newest first: a request by the days it asked for"
      (is (= ["gone-ask" "gone"] (ids cancelled))))
    (testing "and none is lost"
      (is (= (set (map :id listed)) (set (mapcat ids [coming earlier cancelled]))))
      (is (= (count listed) (count (mapcat ids [coming earlier cancelled])))))))

(deftest the-sections-are-made-of-the-rows-a-page-shows
  (let [{:keys [coming cancelled]} (views/appointment-sections listed doctors now)]
    (is (= (views/appointment-row (first listed) doctors) (first (filter #(= "late" (:id %)) coming))))
    (is (= "Dr. Elena Rivera" (:doctor (first coming))) "with the name of the doctor")
    (is (= "I had to travel." (:reason (first (filter #(= "gone" (:id %)) cancelled)))) "and the reason a cancelled one has")))

(deftest the-order-of-the-sections-is-the-same-whatever-order-the-api-gave
  (let [expected (views/appointment-sections listed doctors now)]
    (dotimes [_ 20]
      (is (= expected (views/appointment-sections (shuffle listed) doctors now))))))

(deftest appointments-of-one-moment-go-by-their-ids
  (let [same (fn [id] (appointment :id id :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z"))]
    (is (= ["a" "b" "c"] (ids (:coming (views/appointment-sections [(same "c") (same "a") (same "b")] doctors now)))))
    (is (= ["c" "b" "a"] (ids (:earlier (views/appointment-sections [(same "c") (same "a") (same "b")] doctors "2026-11-01T00:00:00Z"))))
        "the newest first, which for two of one moment is the other way round")))

(deftest an-appointment-is-the-moment-it-begins-an-appointment-that-has-begun
  (let [booked [(appointment :id "x" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z")]]
    (is (= ["x"] (ids (:coming (views/appointment-sections booked doctors "2026-10-14T07:59:59Z")))))
    (is (= ["x"] (ids (:earlier (views/appointment-sections booked doctors "2026-10-14T08:00:00Z")))) "as the lifecycle says")
    (is (= [] (ids (:coming (views/appointment-sections booked doctors "2026-10-14T08:00:00Z"))))))
  (testing "one that was cancelled is cancelled, whether it began or not"
    (doseq [at ["2026-10-01T00:00:00Z" "2026-12-01T00:00:00Z"]]
      (is (= ["gone"] (ids (:cancelled (views/appointment-sections [(appointment :id "gone" :status "cancelled" :start "2026-10-14T08:00:00Z")] doctors at))))
          at))))

(deftest what-other-systems-write-is-never-lost-or-an-error
  (let [odd [(appointment :id "a" :start "soon" :end "later")
             (appointment :id "b" :status "proposed" :requestedStart "next week" :requestedEnd "later")
             (appointment :id "c" :status nil :start nil)
             (appointment :id "d" :status "fulfilled" :start "2026-10-01T08:00:00Z" :end "2026-10-01T08:30:00Z")
             (appointment :id "e" :status "noshow" :start "2026-10-02T08:00:00Z" :end "2026-10-02T08:30:00Z")
             {:id "f"}]
        {:keys [coming earlier cancelled]} (views/appointment-sections odd doctors now)]
    (testing "a time that cannot be read is no time, so it is not an appointment that has begun"
      (is (= #{"a" "b" "c" "f"} (set (ids coming)))))
    (testing "what has begun has begun, whatever its status says"
      (is (= ["e" "d"] (ids earlier))))
    (is (= [] cancelled)))
  (testing "nothing at all is three empty lists"
    (doseq [items [nil []]]
      (is (= {:coming [] :earlier [] :cancelled []} (views/appointment-sections items doctors now))))))

(deftest a-patient-cannot-cancel-what-the-lifecycle-does-not-let-them
  (let [blocker #(views/cancel-blocker % now)]
    (testing "they can cancel a request, and a booked appointment that has not begun"
      (is (nil? (blocker (appointment :status "proposed" :requestedStart "2026-10-14T00:00:00Z"))))
      (is (nil? (blocker (appointment :status "booked" :start "2026-10-14T08:00:00Z")))))
    (testing "not one that is cancelled already"
      (is (= :already-cancelled (blocker (appointment :status "cancelled" :start "2026-10-14T08:00:00Z"))))
      (is (= :already-cancelled (blocker (appointment :status "cancelled" :start "2026-10-01T08:00:00Z")))))
    (testing "not a booked one that has begun: only the clinic can"
      (is (= :begun (blocker (appointment :status "booked" :start "2026-10-13T11:00:00Z"))))
      (is (nil? (views/cancel-blocker (appointment :status "booked" :start "2026-10-13T12:00:01Z") now)))
      (is (= :begun (views/cancel-blocker (appointment :status "booked" :start "2026-10-13T12:00:00Z") now)) "the moment it begins it has begun"))
    (testing "and not what has no status a patient can cancel"
      (doseq [status ["pending" "fulfilled" "noshow" "arrived" "entered-in-error" nil "whatever"]]
        (is (= :not-cancellable (blocker (appointment :status status :start "2026-10-01T08:00:00Z"))) (pr-str status))))
    (testing "a row has what it takes, since it has the status and the start"
      (is (nil? (blocker (views/appointment-row (appointment :status "booked" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z") doctors))))
      (is (= :already-cancelled (blocker (views/appointment-row (appointment :status "cancelled" :start "2026-10-14T08:00:00Z") doctors)))))))

(deftest the-reason-a-patient-cannot-cancel-agrees-with-the-lifecycle-for-every-status-and-time
  (doseq [status ["proposed" "booked" "cancelled" "pending" "fulfilled" nil]
          start [nil "2026-10-01T08:00:00Z" "2026-10-13T11:59:59Z" "2026-10-13T12:00:00Z" "2026-10-13T12:00:01Z" "2026-12-01T08:00:00Z"]
          :let [appointment {:status status :start start}
                allowed? (contains? (lifecycle/available-actions appointment :patient now) :cancel)]]
    (is (= allowed? (nil? (views/cancel-blocker appointment now))) (pr-str [status start]))))
