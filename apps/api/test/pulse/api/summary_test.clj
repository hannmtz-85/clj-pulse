(ns pulse.api.summary-test
  "GET /api/patients/{id}/summary against the demo cohort: what it contains, who may read it, and
  what happens when part of FHIR is slow or down (ADR-0013)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.api.summary :as summary]
            [pulse.api.test.harness :as h :refer [audit-events call with-api]]
            [pulse.fhir.client :as fc]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.ranges :as ranges]
            [pulse.fhir.resource :as r]
            [pulse.platform.http.problem :as problem]))

(defn- summary-of [api who id & options]
  (apply call api :get (str "/api/patients/" id "/summary") :token (h/token-for who) options))

(defn- latest
  "The most recent Observation of `patient-id` with catalogue concept `concept`, found by
  looking through everything the in-memory server holds: a way to the answer that does not go
  through the search the endpoint makes."
  [client patient-id concept]
  (->> (mem/contents client)
       (filter #(and (= "Observation" (:resourceType %))
                     (= (str "Patient/" patient-id) (get-in % [:subject :reference]))
                     (obs/concept? % concept)))
       (apply max-key obs/effective-millis)))

(defn- band [measurement value] (name (ranges/classify measurement value)))

;; ---------------------------------------------------------------------------
;; What it contains

(deftest the-summary-of-a-patient-with-a-hypertension-scenario
  (let [client (h/demo-cohort)]
    (with-api {:fhir client}
      (fn [api]
        (let [r (summary-of api :ana "pt-ana")
              body (:json r)
              bp (latest client "pt-ana" :bp-panel)]
          (is (= 200 (:status r)))
          (is (= "application/json" (h/content-type r)))
          (is (= #{:patient :conditions :medications :allergies :vitals :appointments :alerts :unavailable}
                 (set (keys body))))
          (is (= {:id "pt-ana" :name "Ana García" :family "García" :given ["Ana"]
                  :birthDate "1968-03-14" :gender "female" :identifier "PULSE-0001"}
                 (:patient body)))
          (is (= [{:id "cond-ana-1" :display "Essential (primary) hypertension" :code "I10"
                   :status "active" :onset "2019-05-02"}]
                 (:conditions body)))
          (is (= [{:id "med-ana-2" :display "amlodipine" :dosage "5 mg once daily" :status "active"
                   :authoredOn "2025-11-04T09:00:00Z"}
                  {:id "med-ana-1" :display "lisinopril" :dosage "10 mg once daily" :status "active"
                   :authoredOn "2025-11-04T09:00:00Z"}]
                 (:medications body)))
          (is (= [] (:allergies body)) "none, which is not the same as unknown")
          (is (= [] (:alerts body)))
          (is (= [] (:unavailable body)))
          (testing "the latest blood pressure, with the band of its worse component"
            (let [view (get-in body [:vitals :blood-pressure])
                  {:keys [systolic diastolic]} (obs/blood-pressure bp)]
              (is (= #{:id :at :systolic :diastolic :unit :band} (set (keys view))))
              (is (= (:id bp) (:id view)))
              (is (= (:effectiveDateTime bp) (:at view)))
              (is (= [systolic diastolic] [(:systolic view) (:diastolic view)]))
              (is (= "mm[Hg]" (:unit view)))
              (is (= "very-high" (:band view)) "the scenario: pressure climbing past 160")))
          (testing "the latest heart rate and weight"
            (let [hr (get-in body [:vitals :heart-rate])
                  weight (get-in body [:vitals :weight])
                  expected-hr (latest client "pt-ana" :heart-rate)]
              (is (= #{:id :at :value :unit :band} (set (keys hr))))
              (is (= (:id expected-hr) (:id hr)))
              (is (= "/min" (:unit hr)))
              (is (= (band :heart-rate (:value hr)) (:band hr)))
              (is (= "kg" (:unit weight)))
              (is (nil? (:band weight)) "weight has no reference band")))
          (testing "vital signs that were never measured are null, and not unavailable"
            (is (nil? (get-in body [:vitals :glucose])))
            (is (nil? (get-in body [:vitals :spo2]))))
          (testing "the appointment"
            (let [[appointment :as appointments] (:appointments body)]
              (is (= 1 (count appointments)))
              (is (= {:id "appt-ana-1" :status "booked" :description "Hypertension follow-up"
                      :practitioner "Practitioner/pr-rivera" :requestedStart nil :requestedEnd nil}
                     (select-keys appointment [:id :status :description :practitioner :requestedStart :requestedEnd])))
              (is (string? (:start appointment)))
              (is (string? (:end appointment))))))))))

(deftest the-other-scenarios
  (let [client (h/demo-cohort)]
    (with-api {:fhir client}
      (fn [api]
        (testing "Liam: diabetes, and the glucose reading that was last taken"
          (let [body (:json (summary-of api :admin "pt-liam"))
                expected (latest client "pt-liam" :glucose)
                value (get-in expected [:valueQuantity :value])]
            (is (= "E11.9" (get-in body [:conditions 0 :code])))
            (is (= ["empagliflozin" "metformin"] (mapv :display (:medications body))))
            (is (= {:id (:id expected) :value value :unit "mg/dL" :band (band :glucose value)}
                   (select-keys (get-in body [:vitals :glucose]) [:id :value :unit :band])))))
        (testing "Sofia: an allergy, a requested appointment and low oxygen"
          (let [body (:json (summary-of api :admin "pt-sofia"))
                spo2 (get-in body [:vitals :spo2])]
            (is (= [{:id "alg-sofia-1" :display "penicillin G" :criticality "low" :reaction "Skin rash"
                     :recordedDate "2018-04-12T10:00:00Z"}]
                   (:allergies body)))
            (is (= "proposed" (get-in body [:appointments 0 :status])))
            (is (nil? (get-in body [:appointments 0 :start])))
            (is (string? (get-in body [:appointments 0 :requestedStart])))
            (is (contains? #{"low" "very-low"} (:band spo2)) "the scenario: oxygen under 92")
            (is (= "%" (:unit spo2)))))
        (testing "Maya: heart failure and a weight that has gone up"
          (let [body (:json (summary-of api :admin "pt-maya"))
                expected (latest client "pt-maya" :body-weight)]
            (is (= "I50.9" (get-in body [:conditions 0 :code])))
            (is (= (get-in expected [:valueQuantity :value]) (get-in body [:vitals :weight :value])))))
        (testing "Noah, newly registered: nothing to show, and nothing missing"
          (let [body (:json (summary-of api :admin "pt-noah"))]
            (is (= [] (:conditions body)))
            (is (= [] (:medications body)))
            (is (= [] (:allergies body)))
            (is (= [] (:alerts body)))
            (is (every? nil? (vals (:vitals body))))
            (is (= [] (:unavailable body)))
            (is (= ["proposed"] (mapv :status (:appointments body))))))))))

(deftest alerts-are-the-active-flags-newest-first
  (let [client (h/demo-cohort)]
    (doseq [flag [(r/flag {:id "flag-1" :patient "pt-ana" :text "BP stage 2" :start "2026-10-10T08:00:00Z"})
                  (r/flag {:id "flag-2" :patient "pt-ana" :text "BP stage 2 again" :start "2026-10-13T20:00:00Z"})
                  (assoc (r/flag {:id "flag-3" :patient "pt-ana" :text "Old" :start "2026-09-01T08:00:00Z"})
                         :status "inactive")
                  (r/flag {:id "flag-4" :patient "pt-liam" :text "Someone else's" :start "2026-10-13T08:00:00Z"})]]
      (fc/put! client flag))
    (with-api {:fhir client}
      (fn [api]
        (is (= [{:id "flag-2" :text "BP stage 2 again" :status "active" :since "2026-10-13T20:00:00Z"}
                {:id "flag-1" :text "BP stage 2" :status "active" :since "2026-10-10T08:00:00Z"}]
               (:alerts (:json (summary-of api :admin "pt-ana")))))))))

;; ---------------------------------------------------------------------------
;; Who may read it: as for the patient's record

(deftest who-may-read-which-summary
  (with-api {}
    (fn [api]
      (doseq [[who id expected]
              [[:ana "pt-ana" 200] [:ana "pt-liam" 403] [:liam "pt-liam" 200]
               [:rivera "pt-ana" 200] [:rivera "pt-maya" 403]
               [:okafor "pt-maya" 200] [:okafor "pt-ana" 403]
               [:admin "pt-noah" 200] [:scheduler "pt-ana" 403]]]
        (testing (str who " -> " id)
          (is (= expected (:status (summary-of api who id)))))))))

(deftest a-summary-that-does-not-exist-or-needs-credentials
  (with-api {}
    (fn [api]
      (is (= 404 (:status (summary-of api :admin "pt-ghost"))))
      (is (= 404 (:status (summary-of api :rivera "pt-ghost"))))
      (is (= 403 (:status (summary-of api :ana "pt-ghost"))) "as for the record: patients cannot probe")
      (is (= 404 (:status (summary-of api :admin "a%20b"))))
      (is (= 401 (:status (call api :get "/api/patients/pt-ana/summary")))))))

(deftest reading-a-summary-is-audited-like-reading-the-record
  (with-api {}
    (fn [api]
      (let [r (summary-of api :rivera "pt-ana")]
        (is (= [{:action :patient/read :outcome :allowed :scope :panel :patient "Patient/pt-ana"
                 :path "/api/patients/pt-ana/summary"}]
               (map #(select-keys (:data %) [:action :outcome :scope :patient :path]) (audit-events r))))))))

;; ---------------------------------------------------------------------------
;; Concurrency and deadlines

(def ^:private all-types
  ["Patient" "Condition" "MedicationRequest" "AllergyIntolerance" "Appointment" "Flag" "Observation"])

(deftest the-queries-are-made-at-once
  (let [calls (atom [])
        slow (h/misbehaving (h/spy (h/demo-cohort) calls) (zipmap all-types (repeat [:slow 250])))]
    (with-api {:fhir slow}
      (fn [api]
        (let [started (System/nanoTime)
              r (summary-of api :admin "pt-ana")
              ms (/ (- (System/nanoTime) started) 1e6)]
          (is (= 200 (:status r)))
          (is (= [] (get-in r [:json :unavailable])))
          (is (= {"Patient" 1 "Condition" 1 "MedicationRequest" 1 "AllergyIntolerance" 1
                  "Appointment" 1 "Flag" 1 "Observation" 5}
                 (frequencies (map second @calls)))
              "eleven queries")
          (is (< ms 1500) (str "eleven queries of 250 ms each took " (long ms) " ms; one after another it would be 2750")))))))

(deftest the-queries-are-narrow
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls)}
      (fn [api]
        (summary-of api :admin "pt-ana")
        (let [searches (filter #(= :search (first %)) @calls)
              observation-params (map #(nth % 2) (filter #(= "Observation" (second %)) searches))]
          (testing "five latest readings, one each, newest first: no further page is fetched"
            (is (= 5 (count observation-params)))
            (is (every? #(= {:_count 1 :_sort "-date" :patient "Patient/pt-ana"} (dissoc % :code)) observation-params))
            (is (= 5 (count (set (map :code observation-params))))))
          (testing "only what is current"
            (is (= {:patient "Patient/pt-ana" :clinical-status "active"}
                   (nth (first (filter #(= "Condition" (second %)) searches)) 2)))
            (is (= {:patient "Patient/pt-ana" :status "active"}
                   (nth (first (filter #(= "MedicationRequest" (second %)) searches)) 2)))
            (is (= {:patient "Patient/pt-ana" :_sort "-date" :_count 50}
                   (nth (first (filter #(= "Flag" (second %)) searches)) 2))
                "R4 has no status parameter for Flag: the newest are asked for, the active ones kept")))))))

(deftest a-section-that-fails-is-named-and-the-rest-is-kept
  (with-api {:fhir (h/misbehaving (h/demo-cohort) {"AllergyIntolerance" :fail})}
    (fn [api]
      (let [r (summary-of api :rivera "pt-sofia")
            body (:json r)
            degraded (first (filter #(= :pulse.api/summary-degraded (:id %)) (:signals r)))]
        (is (= 200 (:status r)))
        (is (nil? (:allergies body)) "null, not []: this patient has a penicillin allergy")
        (is (= ["allergies"] (:unavailable body)))
        (is (= "J44.9" (get-in body [:conditions 0 :code])) "the rest is there")
        (is (= 2 (count (:medications body))))
        (is (not (str/includes? (:text r) "secret-host")))
        (testing "and it is logged, with the reason and without the message"
          (is (= :warn (:level degraded)))
          (is (= {:unavailable ["allergies"] :reasons {"allergies" 502}}
                 (select-keys (:data degraded) [:unavailable :reasons]))
              "the status of the problem the guard raised, not its message"))))))

(deftest a-late-section-is-cut-off-at-the-deadline
  (with-api {:fhir (h/misbehaving (h/demo-cohort) {"Flag" [:slow 3000]})
             :summary-timeout-ms 300}
    (fn [api]
      (let [started (System/nanoTime)
            r (summary-of api :admin "pt-ana")
            ms (/ (- (System/nanoTime) started) 1e6)
            degraded (first (filter #(= :pulse.api/summary-degraded (:id %)) (:signals r)))]
        (is (= 200 (:status r)))
        (is (nil? (get-in r [:json :alerts])))
        (is (= ["alerts"] (get-in r [:json :unavailable])))
        (is (= 1 (count (get-in r [:json :conditions]))))
        (is (< ms 1500) (str "the answer did not wait for the 3 s query: " (long ms) " ms"))
        (is (= {"alerts" :timeout} (get-in degraded [:data :reasons])))))))

(deftest every-latest-reading-can-fail-at-once
  (with-api {:fhir (h/misbehaving (h/demo-cohort) {"Observation" :fail})}
    (fn [api]
      (let [body (:json (summary-of api :admin "pt-ana"))]
        (is (= ["vitals.blood-pressure" "vitals.glucose" "vitals.heart-rate" "vitals.spo2" "vitals.weight"]
               (:unavailable body)))
        (is (every? nil? (vals (:vitals body))))
        (is (= 1 (count (:conditions body))))))))

(deftest the-patient-is-required
  (testing "a FHIR server that cannot be reached"
    (with-api {:fhir (h/misbehaving (h/demo-cohort) {"Patient" :fail})}
      (fn [api]
        (let [r (summary-of api :admin "pt-ana")]
          (is (= 502 (:status r)))
          (is (= "application/problem+json" (h/content-type r)))
          (is (not (str/includes? (:text r) "secret-host")))))))
  (testing "one that is too slow"
    (with-api {:fhir (h/misbehaving (h/demo-cohort) {"Patient" [:slow 3000]})
               :summary-timeout-ms 300}
      (fn [api]
        (let [started (System/nanoTime)
              r (summary-of api :admin "pt-ana")
              ms (/ (- (System/nanoTime) started) 1e6)]
          (is (= 504 (:status r)))
          (is (= "The FHIR server did not answer in time." (get-in r [:json :detail])))
          (is (< ms 1500)))))))

(deftest nothing-is-fetched-for-a-caller-who-may-not-read-it
  (let [calls (atom [])]
    (with-api {:fhir (h/spy (h/demo-cohort) calls)}
      (fn [api]
        (is (= 403 (:status (summary-of api :ana "pt-liam"))))
        (is (= 403 (:status (summary-of api :scheduler "pt-ana"))))
        (is (empty? @calls))))))

;; ---------------------------------------------------------------------------
;; Assembling the answer is pure

(def ^:private list-sections [:conditions :medications :allergies :appointments :alerts])
(def ^:private vital-keys (mapv (fn [v] [:vital v]) [:blood-pressure :heart-rate :glucose :spo2 :weight]))

(defn- outcomes
  "Outcomes of a fan-out in which the sections in `failed` failed and the others found nothing."
  [failed]
  (merge {:patient {:ok (r/patient {:id "pt-x" :given "X" :family "Y" :gender "other" :birth-date "1990-01-01"})}}
         (into {} (for [k list-sections] [k (if (failed k) {:error (ex-info "x" {})} {:ok []})]))
         (into {} (for [k vital-keys] [k (if (failed k) {:timeout true} {:ok nil})]))))

(defspec the-unavailable-list-is-exactly-the-sections-that-failed 200
  (prop/for-all [failed (gen/set (gen/elements (into list-sections vital-keys)))]
    (let [body (summary/assemble (outcomes failed))
          label (fn [k] (if (vector? k) (str "vitals." (name (second k))) (name k)))]
      (and (= (vec (sort (map label failed))) (:unavailable body))
           (every? (fn [k] (if (failed k) (nil? (get body k)) (= [] (get body k)))) list-sections)
           (every? (fn [[_ k]] (nil? (get-in body [:vitals k]))) vital-keys)))))

(deftest the-patient-decides-whether-there-is-an-answer-at-all
  (let [problem-of (fn [outcome]
                     (try (summary/assemble (assoc (outcomes #{}) :patient outcome))
                          (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))]
    (is (= 404 (problem-of {:ok nil})))
    (is (= 504 (problem-of {:timeout true})))
    (is (= 502 (problem-of {:error (problem/ex 502 "down")})))
    (is (map? (problem-of {:ok (r/patient {:id "pt-x" :given "X" :family "Y" :gender "other" :birth-date "1990-01-01"})})))))

(deftest the-queries-are-named-for-the-fan-out
  (is (= #{:patient :conditions :medications :allergies :appointments :alerts
           [:vital :blood-pressure] [:vital :heart-rate] [:vital :glucose] [:vital :spo2] [:vital :weight]}
         (set (keys (summary/tasks (h/demo-cohort) "pt-ana" "Patient/pt-ana"))))))
