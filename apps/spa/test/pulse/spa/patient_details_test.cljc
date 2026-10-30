(ns pulse.spa.patient-details-test
  "What a mutation analysis of the chart of a patient found that the first tests did not hold (ADR-0029, point 6): a series that is still
  waiting is not asked for twice, the deadline clears what waits, the calls it stops are in the order of their names, a list that the
  summary names as lost is lost even if it holds something, a reading that says nothing is not 'no readings', and what has begun is
  among the appointments. Pure `.cljc`: the JVM and Node run the same tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.patient-fixtures :as fx]))

(deftest a-series-that-is-still-waiting-is-replaced-and-not-asked-for-twice
  (let [chart (patient/start fx/now {:id "pt-ana" :measurement :glucose :days 14})]
    (is (= [[:series :glucose 14] :patient :summary] (:queue chart)) "nothing has begun: the series is at the head of the queue")
    (let [chosen (patient/choose chart {:measurement :heart-rate :days 30})]
      (is (= [[:series :heart-rate 30] :patient :summary] (:queue chosen)) "the series that waited is gone and the new one is in its place")
      (is (= {:state :loading :measurement :heart-rate :days 30} (:series chosen))))))

(deftest a-series-on-its-way-is-no-longer-counted-when-another-is-chosen
  (let [[chart _] (patient/pump (patient/start fx/now {:id "pt-ana" :measurement :glucose :days 14}) 1)
        chosen (patient/choose chart {:measurement :heart-rate :days 14})]
    (is (= #{:patient :summary} (:inflight chosen)) "the call of the series that was left is not counted among those on their way")
    (is (= [[:series :heart-rate 14]] (:queue chosen)))))

(deftest the-deadline-clears-what-waits-as-well-as-what-is-on-its-way
  (let [chart (patient/start fx/now {:id "pt-ana" :measurement :glucose :days 14})
        expired (patient/expire chart)]
    (is (= [[:series :glucose 14] :patient :summary] (:queue chart)) "all of it waits")
    (is (= [] (:queue expired)))
    (is (= [] (:cancelled expired)) "and nothing was on its way to stop")
    (is (= {:state :failed :kind :timeout} (select-keys (:patient expired) [:state :kind])))
    (is (= {:state :failed :kind :timeout :measurement :glucose :days 14} (:series expired)))
    (is (patient/settled? expired))))

(def ^:private some-tasks
  [:patient :summary [:series :blood-pressure 7] [:series :glucose 30] [:evidence :blood-pressure] [:evidence :spo2] [:evidence :weight]
   [:evidence :glucose] [:evidence :heart-rate] [:evidence :temperature]])

(defspec the-calls-the-deadline-stops-are-in-the-order-of-their-names-whatever-the-set-they-are-kept-in 100
  (prop/for-all [tasks (gen/fmap set (gen/vector (gen/elements some-tasks) 1 10))]
    (= (vec (sort-by str tasks))
       (:cancelled (patient/expire (assoc (fx/settled-ana) :inflight tasks))))))

(deftest a-reading-that-says-nothing-is-a-measurement-to-look-at-and-not-no-readings
  (let [unusable (assoc-in fx/noah-summary [:vitals :heart-rate] {:id "x" :at "2026-10-13T08:00:00Z" :value "seventy" :unit "/min"})
        model (patient/model (assoc (fx/settled-noah) :summary (fx/loaded unusable)))]
    (is (= {:state :loaded :items [] :lost? false} (:vitals model)) "none of them says anything")
    (is (= [:heart-rate] (:offered model)) "but the measurement is offered, since the patient has a reading of it")
    (is (false? (:no-readings? model)))))

(deftest a-list-the-summary-names-as-lost-is-lost-even-if-it-holds-something
  (let [model (patient/model (assoc (fx/settled-ana) :summary (fx/loaded (assoc fx/ana-summary :unavailable ["medications" "appointments"]))))]
    (is (seq (:medications fx/ana-summary)) "the summary holds a list")
    (is (= {:state :lost} (:medications model)))
    (is (= {:state :lost} (:appointments model)))
    (testing "and the others are as they were"
      (is (= :loaded (get-in model [:allergies :state])))
      (is (= :loaded (get-in model [:conditions :state]))))))

(deftest what-has-begun-is-among-the-appointments-between-what-is-coming-and-what-was-cancelled
  (let [appointments [{:id "coming" :status "booked" :description "Next" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"}
                      {:id "begun" :status "booked" :description "Earlier" :start "2026-10-12T08:00:00Z" :end "2026-10-12T08:30:00Z"}
                      {:id "cancelled" :status "cancelled" :description "Never" :start "2026-10-15T08:00:00Z" :end "2026-10-15T08:30:00Z"}]
        model (patient/model (assoc (fx/settled-ana) :summary (fx/loaded (assoc fx/ana-summary :appointments appointments))))]
    (is (= ["coming" "begun" "cancelled"] (map :id (get-in model [:appointments :rows]))))))

(deftest a-patient-who-is-gone-is-not-drawn-from-what-another-call-answered
  (let [chart (-> (patient/start fx/now {:id "pt-maya" :measurement nil :days 14})
                  (patient/pump 1)
                  first
                  (patient/answer :patient {:result :failed :kind :forbidden})
                  (patient/answer :summary {:result :failed :kind :forbidden}))]
    (is (true? (:gone? (patient/model chart))))
    (is (nil? (:series chart)) "the first measurement is not asked for in a chart of someone the api refuses")
    (is (= [] (:queue chart)))))

(deftest only-readings-that-could-not-be-loaded-are-lost-readings
  (let [model (fn [unavailable] (patient/model (assoc (fx/settled-ana) :summary (fx/loaded (assoc fx/ana-summary :unavailable unavailable)))))]
    (is (false? (get-in (model []) [:vitals :lost?])))
    (is (false? (get-in (model ["alerts"]) [:vitals :lost?])) "an alert that could not be loaded is not a reading")
    (is (false? (get-in (model ["medications" "appointments"]) [:vitals :lost?])))
    (is (true? (get-in (model ["alerts" "vitals.spo2"]) [:vitals :lost?])))
    (is (false? (get-in (model [5 nil :vitals]) [:vitals :lost?])) "what is not the name of a section is nothing")))

(deftest what-the-screen-reader-is-told-counts-the-series-and-names-the-patient-only-when-it-is-known
  (let [live (fn [chart] (:live (patient/model chart)))]
    (testing "a series that failed is a part that failed"
      (is (= "The chart of Ana García is loaded. 1 part could not be loaded"
             (live (assoc (fx/settled-ana) :series {:state :failed :kind :timeout :measurement :blood-pressure :days 14})))))
    (testing "a chart with no series, because there is nothing to draw, has no part that failed"
      (is (= "The chart of Noah Kim is loaded" (live (fx/settled-noah)))))
    (testing "a patient who could not be loaded has no name to say"
      (is (= "The chart of the patient is loaded. 1 part could not be loaded"
             (live (assoc (fx/settled-ana) :patient {:state :failed :kind :server}))))
      (is (= "The chart of the patient is loaded. 2 parts could not be loaded"
             (live (-> (fx/settled-ana) (assoc :patient {:state :failed :kind :server}) (assoc :summary {:state :failed :kind :server})))))
      (is (= "Loading the chart of the patient" (live (assoc (fx/settled-ana) :patient {:state :loading})))))))

(deftest a-patient-the-api-refuses-is-asked-for-nothing-more-whichever-answer-comes-first
  (doseq [order [[:summary :patient] [:patient :summary]]]
    (let [chart (reduce (fn [chart task] (patient/answer chart task {:result :failed :kind :forbidden}))
                        (first (patient/pump (patient/start fx/now {:id "pt-maya" :measurement nil :days 14}) 1))
                        order)]
      (is (true? (:gone? (patient/model chart))) (pr-str order))
      (is (nil? (:series chart)) (str (pr-str order) ": the first measurement is not asked for in the chart of someone the api refuses"))
      (is (= [] (:queue chart)) (pr-str order))
      (is (= #{} (:inflight chart)) (pr-str order))))
  (testing "a series that the address asked for is begun with the others, and is dropped when the patient is refused"
    (let [begun (first (patient/pump (patient/start fx/now {:id "pt-maya" :measurement :weight :days 14}) 1))
          refused (patient/answer begun :patient {:result :failed :kind :forbidden})]
      (is (= {:state :loading :measurement :weight :days 14} (:series begun)))
      (is (nil? (:series refused)))
      (is (= #{:summary} (:inflight refused)) "its call is not counted among those on their way")
      (is (nil? (:series (patient/answer refused [:series :weight 14] {:result :ok :body fx/ana-pressure-series}))) "and its late answer is not heard"))))

(deftest a-summary-that-failed-first-waits-for-the-patient-before-it-falls-back-on-a-chart
  (let [chart (-> (first (patient/pump (patient/start fx/now {:id "pt-ana" :measurement nil :days 14}) 1))
                  (patient/answer :summary {:result :failed :kind :server}))]
    (is (nil? (:series chart)))
    (is (= [] (:queue chart)))
    (testing "the patient is there"
      (is (= {:state :loading :measurement :blood-pressure :days 14}
             (:series (patient/answer chart :patient {:result :ok :body fx/ana-resource})))))
    (testing "the patient could not be loaded, which is not the api refusing them"
      (is (= :blood-pressure (:measurement (:series (patient/answer chart :patient {:result :failed :kind :timeout}))))))
    (testing "the patient is refused"
      (is (nil? (:series (patient/answer chart :patient {:result :failed :kind :not-found})))))))

(deftest only-the-api-refusing-a-patient-makes-them-gone
  (doseq [[kind gone?] {:forbidden true :not-found true :server false :timeout false :unreachable false :unexpected false :unauthorized false :refused false}]
    (let [chart (patient/answer (first (patient/pump (patient/start fx/now {:id "pt-x" :measurement nil :days 14}) 1)) :patient {:result :failed :kind kind})]
      (is (= gone? (:gone? (patient/model chart))) (pr-str kind))
      (testing "and a chart of one that is not gone still begins when the summary does not come"
        (let [after (patient/answer chart :summary {:result :failed :kind :server})]
          (is (= (not gone?) (some? (:series after))) (pr-str kind)))))))

(deftest a-series-that-was-asked-for-is-not-dropped-by-the-answer-of-the-patient-or-by-a-refusal-of-the-summary
  (let [begun (first (patient/pump (patient/start fx/now {:id "pt-ana" :measurement :weight :days 14}) 1))]
    (testing "the patient comes: the series is on its way still"
      (let [answered (patient/answer begun :patient {:result :ok :body fx/ana-resource})]
        (is (= {:state :loading :measurement :weight :days 14} (:series answered)))
        (is (contains? (:inflight answered) [:series :weight 14]))))
    (testing "the summary is refused, as the patient is refused, and the series is not asked for a second time"
      (doseq [kind [:forbidden :not-found :server]]
        (let [answered (patient/answer begun :summary {:result :failed :kind kind})]
          (is (= {:state :loading :measurement :weight :days 14} (:series answered)) (pr-str kind))
          (is (= [] (:queue answered)) (pr-str kind))
          (is (contains? (:inflight answered) [:series :weight 14]) (pr-str kind)))))))

(deftest the-words-of-a-loaded-chart-name-the-patient-the-clinic-writes
  (is (= "The chart of Not named is loaded"
         (:live (patient/model (assoc (fx/settled-ana) :patient (fx/loaded (assoc fx/ana-resource :name nil))))))
      "a patient the clinic has not named is named as the page names them")
  (is (= "The chart of Ana García is loaded" (:live (patient/model (fx/settled-ana))))))

(deftest a-chart-that-fell-back-on-the-first-measurement-is-not-moved-by-the-patient-answering-after-a-retry
  (let [chart (-> (first (patient/pump (patient/start fx/now {:id "pt-sofia" :measurement nil :days 14}) 1))
                  (patient/answer :patient {:result :failed :kind :server})
                  (patient/answer :summary {:result :failed :kind :server}))
        retried (patient/retry chart)
        summary-back (patient/answer retried :summary {:result :ok :body fx/sofia-summary})
        patient-back (patient/answer summary-back :patient {:result :ok :body fx/sofia-resource})]
    (is (= :blood-pressure (:measurement (:series chart))) "the chart fell back on the first measurement")
    (is (= :spo2 (:kind (panel/headline fx/sofia-summary))) "though the reading furthest from normal is another")
    (is (= :blood-pressure (:measurement (:series summary-back))))
    (is (= :blood-pressure (:measurement (:series patient-back))) "the chart does not move under the eyes of the person when the patient answers")))
