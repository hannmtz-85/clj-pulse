(ns pulse.design.data-test
  "The data of the mock-ups is what the api will give: the same shapes, checked against the functions and the schemas
  of the api itself, and a moment at which the cohort looks the way a clinic would."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [pulse.api.alerts :as api-alerts]
            [pulse.api.appointments :as api-appointments]
            [pulse.api.jobs :as api-jobs]
            [pulse.api.me :as api-me]
            [pulse.api.observations :as api-observations]
            [pulse.api.summary :as api-summary]
            [pulse.design.data :as data]
            [pulse.fhir.client.memory :as memory]
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

(deftest the-panel-of-a-practitioner-is-the-patients-whose-doctor-they-are
  (is (= ["pt-liam" "pt-ana" "pt-omar" "pt-sofia"] (map :id (data/panel-of "pr-rivera"))) "by family name")
  (is (= ["pt-noah" "pt-maya"] (map :id (data/panel-of "pr-okafor"))))
  (is (empty? (data/panel-of "pr-nobody"))))

(deftest the-appointments-written-by-hand-have-the-shape-the-api-gives
  (is (= ["appt-ana-2" "appt-ana-0"] (map :id data/other-appointments)))
  (doseq [appointment data/other-appointments]
    (is (m/validate api-appointments/item-schema appointment) (:id appointment))
    (is (= #{:id :status :description :start :end :requestedStart :requestedEnd :practitioner :reason :patient}
           (set (keys appointment)))
        "and nothing the api does not give"))
  (testing "they are Ana's, with her doctor, and do not collide with the cohort"
    (is (every? #(= "Patient/pt-ana" (:patient %)) data/other-appointments))
    (is (every? #(= "Practitioner/pr-rivera" (:practitioner %)) data/other-appointments))
    (is (empty? (filter (set (map :id data/other-appointments)) (map :id (data/appointments))))))
  (testing "a request has the days asked for and no time, and a cancelled appointment has its time and its reason"
    (let [[request cancelled] data/other-appointments]
      (is (= "proposed" (:status request)))
      (is (and (:requestedStart request) (:requestedEnd request) (nil? (:start request)) (nil? (:end request))))
      (is (nil? (:reason request)))
      (is (= "cancelled" (:status cancelled)))
      (is (= "I had to travel that week." (:reason cancelled)))
      (is (.isBefore (java.time.Instant/parse (:start cancelled)) data/now) "it was before the moment of the mock-ups"))))

(defn- instant ^java.time.Instant [text] (java.time.Instant/parse text))

(def ^:private ^java.time.Instant jobs-moment (instant "2026-10-13T12:00:15Z"))

(deftest the-jobs-are-in-the-shape-the-api-gives
  (is (m/validate api-jobs/list-schema data/jobs))
  (is (= (count (:items data/jobs)) (:total data/jobs)))
  (is (= ["evaluate-rules" "simulate-devices" "send-reminders"] (map :id (:items data/jobs)))))

(deftest the-jobs-tell-a-story-that-holds-at-the-moment-they-are-seen
  (let [items (:items data/jobs)]
    (testing "a job is running exactly when it says since when"
      (doseq [{:keys [id state runningSince]} items]
        (is (= (= "running" state) (some? runningSince)) id)))
    (testing "everything that happened is before the moment, and what is due is after it"
      (doseq [{:keys [id nextRun runningSince lastRun]} items]
        (is (.isAfter (instant nextRun) jobs-moment) (str id " is due later"))
        (when runningSince (is (not (.isAfter (instant runningSince) jobs-moment)) id))
        (is (not (.isAfter (instant (:finishedAt lastRun)) jobs-moment)) id)
        (is (not (.isAfter (instant (:startedAt lastRun)) (instant (:finishedAt lastRun)))) id)))
    (testing "the schedule is a grid from the epoch, so every run begins on a multiple of the interval"
      (doseq [{:keys [id schedule nextRun runningSince lastRun]} items
              :let [every (:everyMs schedule)]
              start (remove nil? [nextRun runningSince (:startedAt lastRun)])]
        (is (zero? (mod (.toEpochMilli (instant start)) every)) (str id " at " start))))
    (testing "the last run took as long as it says, to the second"
      (doseq [{:keys [id lastRun]} items
              :let [seconds (/ (- (.toEpochMilli (instant (:finishedAt lastRun))) (.toEpochMilli (instant (:startedAt lastRun)))) 1000.0)]]
        (is (<= (Math/abs (- seconds (/ (:durationMs lastRun) 1000.0))) 1.0) id)))
    (testing "a run that failed says the class of its error and no result, and one that went well has a result"
      (is (= ["ok" "ok" "failed"] (map (comp :outcome :lastRun) items)))
      (is (= "HttpTimeoutException" (get-in (last items) [:lastRun :error])))
      (is (nil? (get-in (last items) [:lastRun :result])))
      (is (every? #(map? (get-in % [:lastRun :result])) (butlast items))))
    (testing "the counts are those of a scheduler that has been up a while"
      (is (= [214 428 105] (map (comp :ok :counts) items)))
      (is (= [0 1 1] (map (comp :failed :counts) items))))))

;; ---------------------------------------------------------------------------
;; What the api answers a patient, from the same cohort

(def ^:private fhir
  "The cohort of the mock-ups in a FHIR server, which the handlers of the api are given."
  (delay (memory/memory-client (data/resources))))

(defn- me-of-api [id]
  (let [identity {:subject (str "user-" id) :username (:email (data/patient id)) :client "pulse-web" :roles #{:patient}
                  :fhir-user {:type "Patient" :id id}}]
    (:body ((api-me/handler {:fhir @fhir}) {:identity identity}))))

(defn- summary-of-api [id]
  (:body ((api-summary/handler {:fhir @fhir :summary-timeout-ms 5000}) {:path-params {:id id}})))

(defn- series-of-api [id kind limit]
  (:body ((api-observations/list-handler {:fhir @fhir})
          {:path-params {:id id} :parameters {:query {:measurement (name kind) :sort "-date" :limit limit}}})))

(deftest what-the-api-answers-who-a-patient-is-is-what-the-mock-ups-have
  (doseq [{:keys [id]} (data/patients)]
    (testing id
      (is (m/validate api-me/schema (data/me id)))
      (is (= (me-of-api id) (data/me id)) "the same as the handler of the api makes of the same cohort")))
  (testing "the doctor is named as the clinic writes it"
    (is (= {:reference "Practitioner/pr-rivera" :name "Dr. Elena Rivera" :specialty "Internal medicine"}
           (:generalPractitioner (data/me "pt-ana"))))
    (is (= "Dr. Chidi Okafor" (get-in (data/me "pt-noah") [:generalPractitioner :name])))))

(deftest what-the-api-answers-the-summary-of-a-patient-is-what-the-mock-ups-have
  (let [by-the-hand (set (map :id data/other-appointments))
        by-the-cohort (fn [summary] (vec (remove #(contains? by-the-hand (:id %)) (:appointments summary))))]
    (doseq [{:keys [id]} (data/patients)
            :let [mine (data/summary id)
                  theirs (summary-of-api id)]]
      (testing id
        (is (m/validate api-summary/schema mine) "the shape the api gives, the parts written by hand included")
        (is (= (select-keys theirs [:patient :conditions :medications :allergies :vitals :unavailable])
               (select-keys mine [:patient :conditions :medications :allergies :vitals :unavailable]))
            "the record and the latest readings, as the handler of the api makes them of the same cohort")
        (is (= (:appointments theirs) (by-the-cohort mine)) "and the appointments of the cohort")
        (is (= [] (:unavailable mine)) "nothing that could not be loaded"))))
  (testing "what the cohort does not hold is written by hand: Ana has two more appointments and an alert"
    (let [ana (data/summary "pt-ana")]
      (is (= ["appt-ana-0" "appt-ana-1" "appt-ana-2"] (map :id (:appointments ana))) "soonest first")
      (is (= ["flag-pt-ana-R1"] (map :id (:alerts ana))))
      (is (empty? (:alerts (data/summary "pt-liam"))) "the alert of Liam was acknowledged, and is not active")))
  (testing "a vital sign the patient has no reading of is null, and the summary has no temperature"
    (is (= #{:blood-pressure :heart-rate :glucose :spo2 :weight} (set (keys (:vitals (data/summary "pt-ana"))))))
    (is (= [:blood-pressure :heart-rate :weight] (sort-by {:blood-pressure 0 :heart-rate 1 :weight 2} (keep (fn [[k v]] (when v k)) (:vitals (data/summary "pt-ana"))))))
    (is (every? nil? (vals (:vitals (data/summary "pt-noah")))))))

(deftest what-the-api-answers-a-series-is-what-the-mock-ups-have
  (doseq [{:keys [id]} (data/patients)
          kind (keys measurement/measurements)]
    (let [mine (data/series id kind 5)]
      (is (m/validate api-observations/list-schema mine) (str id " " kind))
      (is (= (series-of-api id kind 5) mine) (str id " " kind))))
  (testing "the newest first, at most the limit, and whether there were more"
    (let [{:keys [items truncated]} (data/series "pt-ana" :blood-pressure 5)]
      (is (= 5 (count items)))
      (is (true? truncated))
      (is (= (reverse (sort (map :at items))) (map :at items)))
      (is (= "blood-pressure" (:measurement (first items)))))
    (is (= {:items [] :truncated false} (data/series "pt-noah" :weight 5)))
    (is (false? (:truncated (data/series "pt-ana" :weight 500))))))
