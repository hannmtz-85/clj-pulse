(ns pulse.spa.patient-events-test
  "The events of the chart of a patient and of the route that leads to it (ADR-0029, points 3 and 6), as the state they leave and the
  effects they ask for. The effects that reach out of the page (the api, the history, the clock) are replaced by ones that write down
  what they were asked, so nothing leaves Node; and some tests answer the calls the chart makes with the answers of the fixtures, as
  the api would, to see the chart whole. Each test runs in `day8.re-frame/test`'s own re-frame state, which is thrown away after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            ;; the panel is where the person comes from, and goes back to
            [pulse.spa.panel-events]
            [pulse.spa.patient :as patient]
            [pulse.spa.patient-events :as patient-events]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.patient-fixtures :as fx]
            [pulse.view.format :as fmt]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]))

(defn- quietly
  "Runs `f` with re-frame's warning about a handler registered twice put aside: here it is meant."
  [f]
  (let [before (loggers/get-loggers)]
    (loggers/set-loggers! {:warn (fn [& args] (when-not (str/starts-with? (str (first args)) "re-frame: overwriting") (apply (:warn before) args)))})
    (try (f) (finally (loggers/set-loggers! before)))))

(def ^:private asked
  "What the effects were asked, in order: `[effect value]`."
  (atom []))

(defn- note-the-effects!
  "Replaces the effects that reach out of the page by ones that write down what they were asked, and the clock by the moment of the
  fixtures."
  []
  (reset! asked [])
  (quietly
   (fn []
     (doseq [effect [::auth/init ::auth/logout ::fetch/request ::fetch/cancel ::router/start ::router/replace ::router/arrived :dispatch-later]]
       (rf/reg-fx effect (fn [value] (swap! asked conj [effect value]))))
     (rf/reg-cofx ::patient-events/now (fn [cofx _] (assoc cofx :now fx/now))))))

(defn- state [] @db/app-db)

(defn- calls
  "The calls made so far, as their specs."
  []
  (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- tasks-asked
  "The tasks the calls made so far are for, in order."
  []
  (mapv #(nth (:on-success %) 2) (calls)))

(defn- cancelled
  "The ids of the calls that were stopped."
  []
  (set (for [[effect value] @asked :when (= ::fetch/cancel effect)] (:id value))))

(defn- deadlines [] (vec (for [[effect value] @asked :when (= :dispatch-later effect)] value)))

(defn- signed-in! []
  (rf/dispatch [::events/start])
  (rf/dispatch [::events/signed-in {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
                                    "realm_access" {"roles" ["practitioner"]}}]))

(def ^:private ana-chart {:name :patient :params {:id "pt-ana" :measurement nil :days 14}})

(defn- on-the-chart!
  "Signs in and comes to the chart of Ana García, which begins to load (the load of the generation 1); what was asked since is in `@asked`."
  ([] (on-the-chart! ana-chart))
  ([here]
   (note-the-effects!)
   (signed-in!)
   (reset! asked [])
   (rf/dispatch [::events/navigated here])))

(defn- answer!
  "The api answers the call `spec` of the chart of Ana as the fixtures say, as the effect would tell the page."
  [spec]
  (rf/dispatch (conj (:on-success spec) {:status 200 :body (fx/body-of :ana (nth (:on-success spec) 2)) :request-id "r"})))

(defn- answer-everything!
  "Answers every call the chart makes, and the calls that answering makes, until none is left. It gives the number of calls answered."
  []
  (let [answered (atom #{})]
    (loop [guard 0]
      (let [pending (remove #(contains? @answered (nth (:on-success %) 2)) (calls))]
        (when (and (seq pending) (< guard 40))
          (doseq [spec pending]
            (swap! answered conj (nth (:on-success spec) 2))
            (answer! spec))
          (recur (inc guard)))))
    (count @answered)))

;; ---------------------------------------------------------------------------
;; The route

(deftest coming-to-the-chart-of-a-patient-starts-it-and-sets-the-title-without-moving-the-focus
  (rf-test/run-test-sync
   (on-the-chart!)
   (is (= ana-chart (subs/route (state) nil)))
   (is (= [:patient :summary] (tasks-asked)) "who the patient is and their summary: the series waits for the summary")
   (is (= [[::router/arrived {:title "Patient chart · Pulse" :focus? false}]] (filter #(= ::router/arrived (first %)) @asked)))))

(deftest coming-from-the-panel-moves-the-focus-to-the-heading
  (rf-test/run-test-sync
   (on-the-chart! {:name :panel :params {:show :all :q ""}})
   (reset! asked [])
   (rf/dispatch [::events/navigated ana-chart])
   (is (= [[::router/arrived {:title "Patient chart · Pulse" :focus? true}]] (filter #(= ::router/arrived (first %)) @asked)))
   (is (nil? (:panel (state))) "the panel is left")
   (is (some? (:chart (state))) "and the chart begins")))

(deftest the-chart-of-a-measurement-asked-for-begins-with-its-series
  (rf-test/run-test-sync
   (on-the-chart! {:name :patient :params {:id "pt-ana" :measurement :glucose :days 30}})
   (is (= [[:series :glucose 30] :patient :summary] (tasks-asked)))
   (is (= "2026-09-13T12:00:00Z" (get-in (first (calls)) [:query :from])) "thirty days before the moment of the fixtures")))

(deftest the-chart-of-another-patient-is-another-chart-that-leaves-the-first-and-begins-the-second
  (rf-test/run-test-sync
   (on-the-chart!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-sofia" :measurement nil :days 14}}])
   (is (= #{[:chart :patient] [:chart :summary]} (cancelled)) "the calls of the first are stopped")
   (is (= [:patient :summary] (tasks-asked)))
   (is (every? #(= ["api" "patients" "pt-sofia"] (take 3 (:path %))) (calls)) "and the new ones are Sofía's")
   (is (= "pt-sofia" (get-in (state) [:chart :id])))
   (is (= 3 (:chart-generation (state))) "leaving one is a generation, and beginning the other is another")))

(deftest a-patient-who-may-not-open-it-is-asked-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (swap! db/app-db assoc :status :signed-in :person {:id "a1" :name "Ana" :roles #{:patient}})
   (rf/dispatch [::events/navigated ana-chart])
   (is (= [] (calls)) "nothing is asked of the api for them")
   (is (nil? (:chart (state))))))

;; ---------------------------------------------------------------------------
;; Loading

(deftest loading-the-chart-asks-who-the-patient-is-and-for-their-summary-and-sets-the-deadline
  (rf-test/run-test-sync
   (on-the-chart!)
   (is (= [{:ms 10000 :dispatch [:pulse.spa.patient/deadline 1 1]}] (deadlines)) "one deadline, for this load")
   (is (= fx/now (get-in (state) [:chart :now])) "the moment it began")
   (is (= "pt-ana" (get-in (state) [:chart :id])))
   (is (= {:state :loading} (get-in (state) [:chart :patient])))
   (testing "the calls are the ones the machine made, with the generation in the events that hear them"
     (let [spec (first (calls))]
       (is (= [:chart :patient] (:id spec)))
       (is (= ["api" "patients" "pt-ana"] (:path spec)))
       (is (= [:pulse.spa.patient/succeeded 1 :patient] (:on-success spec)))))
   (testing "no token, nothing of the person, in what was asked"
     (is (not (re-find #"eyJ" (pr-str @asked)))))))

(deftest the-real-clock-gives-an-instant-of-the-api
  (rf-test/run-test-sync
   (reset! asked [])
   (quietly (fn []
              (rf/reg-fx ::fetch/request (fn [spec] (swap! asked conj [::fetch/request spec])))
              (rf/reg-fx :dispatch-later (fn [_] nil))))
   (rf/dispatch [:pulse.spa.patient/load {:id "pt-ana" :measurement nil :days 14}])
   (let [now (get-in (state) [:chart :now])]
     (is (fmt/instant? now) now)
     (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" now) "to the second, in UTC, as the api reads it")
     (is (< (js/Math.abs (- (fmt/millis now) (js/Date.now))) 2000)))))

(deftest the-summary-makes-the-chart-ask-for-the-series-of-the-headline-reading-and-the-readings-behind-the-alert
  (rf-test/run-test-sync
   (on-the-chart!)
   (let [summary-call (first (filter #(= :summary (nth (:on-success %) 2)) (calls)))]
     (reset! asked [])
     (answer! summary-call))
   (is (= [[:series :blood-pressure 14] [:evidence :blood-pressure]] (tasks-asked)) "the chart first, then the readings behind the alert")
   (is (= {:state :loading :measurement :blood-pressure :days 14} (get-in (state) [:chart :series])))))

(deftest a-whole-chart-is-answered-and-is-the-chart-of-the-fixtures
  (rf-test/run-test-sync
   (on-the-chart!)
   (is (= 4 (answer-everything!)) "the patient, the summary, the series and the readings behind the alert")
   (is (= [:patient :summary [:series :blood-pressure 14] [:evidence :blood-pressure]] (tasks-asked)))
   (is (= (fx/settled-ana) (:chart (state))))
   (is (patient/settled? (:chart (state))))))

(deftest the-readings-behind-an-alert-are-asked-for-three-at-a-time
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (let [evidence (first (filter #(= [:evidence :blood-pressure] (nth (:on-success %) 2)) (calls)))]
     (is (= {:measurement "blood-pressure" :limit 3} (:query evidence)))
     (is (= [:chart [:evidence :blood-pressure]] (:id evidence))))))

(deftest a-load-that-was-left-does-not-hear-its-answers
  (rf-test/run-test-sync
   (on-the-chart!)
   (let [first-call (first (calls))]
     (rf/dispatch [::events/navigated {:name :not-found :params {}}])
     (is (nil? (:chart (state))))
     (answer! first-call)
     (is (nil? (:chart (state))) "an answer to a chart that was left is not heard"))))

(deftest an-answer-of-an-earlier-load-is-not-the-answer-of-the-one-that-is-open
  (rf-test/run-test-sync
   (on-the-chart!)
   (let [old-call (first (calls))]
     (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-sofia" :measurement nil :days 14}}])
     (rf/dispatch (conj (:on-success old-call) {:status 200 :body fx/ana-resource :request-id "r"}))
     (is (= {:state :loading} (get-in (state) [:chart :patient])) "Sofía's chart does not take Ana's answer"))))

(deftest a-failure-is-a-part-that-failed-and-the-others-go-on
  (rf-test/run-test-sync
   (on-the-chart!)
   (rf/dispatch (conj (:on-failure (first (calls))) {:kind :forbidden}))
   (is (= {:state :failed :kind :forbidden} (get-in (state) [:chart :patient])))
   (answer! (second (calls)))
   (is (= :loaded (get-in (state) [:chart :summary :state])))
   (is (true? (:gone? (patient/model (:chart (state))))) "and a patient the api refuses is gone, whatever the rest says")))

;; ---------------------------------------------------------------------------
;; Choosing another measurement or period

(deftest another-measurement-or-period-asks-for-the-series-again-and-nothing-else
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (reset! asked [])
   (let [chart-before (:chart (state))]
     (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement :heart-rate :days 30}}])
     (is (= [[:series :heart-rate 30]] (tasks-asked)) "only the series")
     (is (= {:state :loading :measurement :heart-rate :days 30} (get-in (state) [:chart :series])))
     (is (= (:patient chart-before) (get-in (state) [:chart :patient])))
     (is (= (:summary chart-before) (get-in (state) [:chart :summary])))
     (is (= (:evidence chart-before) (get-in (state) [:chart :evidence])))
     (is (= [] (filter #(= ::router/arrived (first %)) @asked)) "it is the same page: no title and no focus")
     (is (= 1 (:chart-generation (state))) "the same load"))))

(deftest the-answer-to-the-choice-is-the-chart-of-the-choice
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement :heart-rate :days 30}}])
   (answer! (last (calls)))
   (is (= (fx/settled-ana {:asked :heart-rate :days 30}) (:chart (state))))))

(deftest a-choice-that-is-the-chart-already-asks-for-nothing
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (reset! asked [])
   ;; the person chose the measurement that the headline reading had already made the default
   (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement :blood-pressure :days 14}}])
   (is (= [] (calls)) "the same series is not asked for twice")
   (is (= [] (deadlines)) "and there is nothing to set a deadline for")
   (is (= :blood-pressure (get-in (state) [:chart :asked])) "but the chart now knows that it was asked for")
   (is (= :loaded (get-in (state) [:chart :series :state])))))

(deftest an-answer-to-a-series-that-was-chosen-over-is-not-heard
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement :heart-rate :days 7}}])
   (let [slow (last (calls))]
     (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement :heart-rate :days 30}}])
     (answer! slow)
     (is (= {:state :loading :measurement :heart-rate :days 30} (get-in (state) [:chart :series])) "the chart is of the thirty days")
     (answer! (last (calls)))
     (is (= 30 (get-in (state) [:chart :series :days]))))))

(deftest a-choice-sets-a-deadline-of-its-own-and-the-one-of-the-load-does-nothing-to-it
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement :heart-rate :days 30}}])
   (is (= [{:ms 10000 :dispatch [:pulse.spa.patient/deadline 1 2]}] (deadlines)) "for the choice")
   (testing "the deadline of the load, which comes after the choice, does not fail the series that was asked for"
     (rf/dispatch [:pulse.spa.patient/deadline 1 1])
     (is (= :loading (get-in (state) [:chart :series :state]))))
   (testing "and its own does"
     (rf/dispatch [:pulse.spa.patient/deadline 1 2])
     (is (= {:state :failed :kind :timeout :measurement :heart-rate :days 30} (get-in (state) [:chart :series])))
     (is (contains? (cancelled) [:chart :series]) "and its call is stopped"))))

;; ---------------------------------------------------------------------------
;; The deadline and the retry

(deftest at-the-deadline-what-is-not-back-has-failed-and-its-calls-are-stopped
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer! (first (calls)))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/deadline 1 1])
   (is (= :loaded (get-in (state) [:chart :patient :state])) "what is back stays")
   (is (= {:state :failed :kind :timeout} (get-in (state) [:chart :summary])))
   (is (= #{[:chart :summary]} (cancelled)))
   (is (patient/settled? (:chart (state))))))

(deftest a-deadline-of-another-load-or-of-a-chart-that-has-settled-does-nothing
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (let [before (:chart (state))]
     (reset! asked [])
     (rf/dispatch [:pulse.spa.patient/deadline 1 1])
     (rf/dispatch [:pulse.spa.patient/deadline 7 1])
     (rf/dispatch [:pulse.spa.patient/deadline 1 9])
     (is (= before (:chart (state))))
     (is (= [] @asked)))))

(deftest trying-again-asks-for-what-failed-in-a-new-load-with-a-new-deadline
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer! (first (calls)))
   (rf/dispatch (conj (:on-failure (second (calls))) {:kind :server}))
   ;; with no summary the chart is of the first measurement, so that it is a chart and not a blank
   (is (= [:patient :summary [:series :blood-pressure 14]] (tasks-asked)))
   (answer! (last (calls)))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/retry])
   (is (= [:summary] (tasks-asked)) "what failed, and nothing else")
   (is (= [{:ms 10000 :dispatch [:pulse.spa.patient/deadline 2 2]}] (deadlines)))
   (is (= 2 (:chart-generation (state))))
   (is (= [:pulse.spa.patient/succeeded 2 :summary] (:on-success (first (calls)))) "and its answer is for the new load")
   (testing "its answer makes the chart ask for what follows the summary, but not for another series"
     (answer! (first (calls)))
     (is (= [:summary [:evidence :blood-pressure]] (tasks-asked))))))

(deftest trying-again-while-a-call-is-on-its-way-or-when-nothing-failed-does-nothing
  (rf-test/run-test-sync
   (on-the-chart!)
   (rf/dispatch (conj (:on-failure (first (calls))) {:kind :server}))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/retry])
   (is (= [] @asked) "the summary is still on its way: a new load would make its answer deaf")
   (is (= 1 (:chart-generation (state)))))
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/retry])
   (is (= [] @asked))
   (is (= 1 (:chart-generation (state))))))

(deftest trying-again-when-there-is-no-chart-does-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/retry])
   (is (= [] @asked))))

(deftest leaving-the-chart-stops-what-is-on-its-way-and-forgets-it
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer! (first (calls)))
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (nil? (:chart (state))))
   (is (= #{[:chart :summary]} (cancelled)) "the summary was on its way")
   (is (= 2 (:chart-generation (state))))))

(deftest leaving-when-there-is-no-chart-stops-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [:pulse.spa.patient/leave])
   (is (= [] (for [[effect _] @asked :when (= ::fetch/cancel effect)] effect)))))

(deftest a-choice-for-a-chart-that-is-not-open-does-nothing
  (rf-test/run-test-sync
   (on-the-chart!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/choose {:id "pt-sofia" :measurement :spo2 :days 7}])
   (is (= [] @asked) "another patient's")
   (rf/dispatch [::events/navigated {:name :not-found :params {}}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/choose {:id "pt-ana" :measurement :spo2 :days 7}])
   (is (= [] @asked) "and one for no chart")))

;; ---------------------------------------------------------------------------
;; What nobody is waiting for

(deftest an-answer-when-there-is-no-chart-is-not-heard-even-if-it-is-of-the-generation-there-is
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (reset! asked [])
   (is (nil? (:chart (state))))
   (is (= 0 (get (state) :chart-generation 0)))
   (rf/dispatch [:pulse.spa.patient/succeeded 0 :patient {:status 200 :body fx/ana-resource :request-id "r"}])
   (rf/dispatch [:pulse.spa.patient/failed 0 :patient {:kind :server}])
   (is (nil? (:chart (state))) "a chart is not made of an answer")
   (is (= [] @asked))))

(deftest a-failure-of-a-load-that-was-left-is-not-heard-either
  (rf-test/run-test-sync
   (on-the-chart!)
   (let [old-call (first (calls))]
     (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-sofia" :measurement nil :days 14}}])
     (rf/dispatch (conj (:on-failure old-call) {:kind :forbidden}))
     (is (= {:state :loading} (get-in (state) [:chart :patient])) "Sofía's chart does not take the refusal of Ana's"))))

(deftest the-deadline-of-a-chart-that-was-left-does-nothing
  (rf-test/run-test-sync
   (on-the-chart!)
   (rf/dispatch [::events/navigated {:name :not-found :params {}}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/deadline 1 1])
   (is (nil? (:chart (state))) "a chart is not made of a deadline")
   (is (= [] @asked))))

(deftest a-choice-that-names-no-patient-does-nothing-when-no-chart-is-open
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.patient/choose {:measurement :spo2 :days 7}])
   (is (nil? (:chart (state))) "a chart is not made of a choice")
   (is (= [] @asked))))

(deftest leaving-stops-the-calls-in-the-order-of-their-names
  (rf-test/run-test-sync
   (on-the-chart!)
   (let [tasks #{:patient :summary [:series :glucose 7] [:series :weight 30] [:evidence :spo2] [:evidence :weight] [:evidence :glucose] [:evidence :heart-rate]}]
     (swap! db/app-db assoc-in [:chart :inflight] tasks)
     (reset! asked [])
     (rf/dispatch [:pulse.spa.patient/leave])
     (is (= (mapv patient/call-id (sort-by str tasks))
            (vec (for [[effect value] @asked :when (= ::fetch/cancel effect)] (:id value)))))
     (is (= 8 (count (filter #(= ::fetch/cancel (first %)) @asked)))))))

(deftest a-choice-that-changes-nothing-leaves-the-state-and-the-effects-as-they-were
  (rf-test/run-test-sync
   (on-the-chart!)
   (answer-everything!)
   (let [before (state)]
     (reset! asked [])
     (rf/dispatch [:pulse.spa.patient/choose {:id "pt-ana" :measurement nil :days 14}])
     (is (= before (state)))
     (is (= [] @asked)))))

(deftest a-patient-the-api-refuses-is-asked-for-nothing-more-whichever-answer-comes-first
  (doseq [order [[:summary :patient] [:patient :summary]]]
    (rf-test/run-test-sync
     (on-the-chart! {:name :patient :params {:id "pt-maya" :measurement nil :days 14}})
     (is (= [:patient :summary] (tasks-asked)))
     (doseq [task order]
       (rf/dispatch (conj (:on-failure (first (filter #(= task (nth (:on-failure %) 2)) (calls)))) {:kind :forbidden})))
     (is (= [:patient :summary] (tasks-asked)) (str (pr-str order) ": no series, no readings behind an alert, nothing"))
     (is (true? (:gone? (patient/model (:chart (state))))) (pr-str order))
     (is (patient/settled? (:chart (state)))))))
