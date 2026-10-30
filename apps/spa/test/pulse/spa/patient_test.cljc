(ns pulse.spa.patient-test
  "The chart of a patient as a machine (ADR-0029, point 6): the calls it makes and in which order, what it does with each answer, the
  measurement it is of, the deadline, and the retry. Pure `.cljc`, so the JVM and Node run the same tests; they take the data from
  `pulse.spa.test.patient-fixtures`, in the shapes of the api."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.patient-fixtures :as fx]))

(def ^:private now fx/now)

(defn- start
  ([] (start {}))
  ([params] (patient/start now (merge {:id "pt-ana" :measurement nil :days 14} params))))

(defn- ok [body] {:result :ok :body body})

(defn- failed [kind] {:result :failed :kind kind})

(defn- pumped
  "The chart with every call that can begin begun, and the calls made."
  [chart]
  (patient/pump chart 1))

(defn- step
  "The chart when `task` is answered with `outcome`, and the calls that answer makes possible begun, as the events do."
  [chart task outcome]
  (first (pumped (patient/answer chart task outcome))))

;; ---------------------------------------------------------------------------
;; The start

(deftest a-chart-begins-with-who-the-patient-is-and-their-summary
  (let [chart (start)]
    (is (= [:patient :summary] (:queue chart)))
    (is (= {:state :loading} (:patient chart)))
    (is (= {:state :loading} (:summary chart)))
    (is (nil? (:series chart)) "what to draw is decided by the summary")
    (is (= {} (:evidence chart)))
    (is (= #{} (:inflight chart)))
    (is (= {:id "pt-ana" :asked nil :days 14 :now now} (select-keys chart [:id :asked :days :now])))))

(deftest a-chart-of-a-measurement-asked-for-draws-it-without-waiting-for-the-summary
  (let [chart (start {:measurement :glucose :days 30})]
    (is (= [[:series :glucose 30] :patient :summary] (:queue chart)) "the series first")
    (is (= {:state :loading :measurement :glucose :days 30} (:series chart)))))

(deftest the-period-is-fourteen-days-unless-it-is-given
  (is (= 14 (:days (patient/start now {:id "pt-ana"}))))
  (is (= [7 14 30] patient/periods))
  (is (= 14 patient/default-days)))

;; ---------------------------------------------------------------------------
;; The calls

(defn- call-for
  "The call among `specs` that is for `task`."
  [specs task]
  (some #(when (= task (nth (:on-success %) 2)) %) specs))

(deftest the-calls-of-a-chart-are-to-the-paths-of-the-patient
  (let [specs (second (pumped (start {:measurement :blood-pressure :days 30})))]
    (is (= 3 (count specs)))
    (is (every? #(= :get (:method %)) specs))
    (is (= ["api" "patients" "pt-ana"] (:path (call-for specs :patient))))
    (is (nil? (:query (call-for specs :patient))))
    (is (= ["api" "patients" "pt-ana" "summary"] (:path (call-for specs :summary))))
    (is (nil? (:query (call-for specs :summary))))
    (let [series (call-for specs [:series :blood-pressure 30])]
      (is (= ["api" "patients" "pt-ana" "observations"] (:path series)))
      (is (= {:measurement "blood-pressure" :from "2026-09-13T12:00:00Z" :limit 500} (:query series))
          "thirty days before now, and the most the api gives"))))

(deftest the-period-of-a-series-counts-back-from-now
  (doseq [[days from] {7 "2026-10-06T12:00:00Z" 14 "2026-09-29T12:00:00Z" 30 "2026-09-13T12:00:00Z"}]
    (is (= from (get-in (patient/request [:series :glucose days] {:id "pt-ana" :now now} 1) [:query :from])) (str days " days"))))

(deftest the-readings-behind-an-alert-are-the-latest-three-of-what-its-rule-watches
  (is (= {:method :get :id [:chart [:evidence :spo2]]
          :on-success [:pulse.spa.patient/succeeded 3 [:evidence :spo2]]
          :on-failure [:pulse.spa.patient/failed 3 [:evidence :spo2]]
          :path ["api" "patients" "pt-sofia" "observations"]
          :query {:measurement "spo2" :limit 3}}
         (patient/request [:evidence :spo2] {:id "pt-sofia" :now now} 3))))

(deftest every-call-says-which-chart-it-belongs-to-so-that-a-late-answer-is-not-taken-for-another
  (let [specs (second (pumped (start {:measurement :glucose})))]
    (is (every? #(= 1 (second (:on-success %))) specs))
    (is (every? #(= 1 (second (:on-failure %))) specs))
    (is (every? #(= (rest (:on-success %)) (rest (:on-failure %))) specs) "the success and the failure name the same task")))

(deftest a-call-is-stopped-by-the-id-of-its-task
  (is (= [:chart :patient] (patient/call-id :patient)))
  (is (= [:chart :summary] (patient/call-id :summary)))
  (is (= [:chart :series] (patient/call-id [:series :glucose 14])) "one series is on its way at a time, whatever it is of")
  (is (= [:chart [:evidence :spo2]] (patient/call-id [:evidence :spo2]))))

(deftest at-most-six-calls-are-on-their-way-at-once
  (let [tasks (vec (concat [:patient :summary [:series :glucose 7]] (for [kind [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature]] [:evidence kind])))
        chart (assoc (start) :queue tasks :inflight #{})
        [after specs] (patient/pump chart 1)]
    (is (= 6 patient/max-in-flight))
    (is (= 6 (count specs)))
    (is (= (take 6 tasks) (map #(nth (:on-success %) 2) specs)) "in the order of the queue")
    (is (= (drop 6 tasks) (:queue after)))
    (is (= (set (take 6 tasks)) (:inflight after)))
    (testing "and one more begins when one is back"
      (let [[more-after more] (patient/pump (update after :inflight disj :patient) 1)]
        (is (= [(nth tasks 6)] (map #(nth (:on-success %) 2) more)))
        (is (= (drop 7 tasks) (:queue more-after)))))))

;; ---------------------------------------------------------------------------
;; The answers: the patient and the summary

(deftest the-patient-is-who-the-api-says
  (let [[chart] (pumped (start))
        answered (patient/answer chart :patient (ok fx/ana-resource))]
    (is (= {:state :loaded :data fx/ana-resource} (:patient answered)))
    (is (= #{:summary} (:inflight answered)) "it is no longer on its way")))

(deftest an-answer-that-is-not-a-patient-is-a-failure-and-not-an-empty-header
  (let [[chart] (pumped (start))]
    (doseq [body [nil {} [] "Patient" {:resourceType "Practitioner"} {:resourceType "Bundle"} 5]]
      (is (= {:state :failed :kind :unexpected} (:patient (patient/answer chart :patient (ok body)))) (pr-str body)))))

(deftest a-failure-keeps-its-kind-so-that-a-patient-who-is-not-yours-can-be-told-from-one-that-could-not-be-loaded
  (let [[chart] (pumped (start))]
    (doseq [kind [:forbidden :not-found :server :timeout :unreachable :unauthorized :unexpected]]
      (is (= {:state :failed :kind kind} (:patient (patient/answer chart :patient (failed kind)))) (pr-str kind)))
    (is (= {:state :failed :kind :unexpected} (:patient (patient/answer chart :patient {:result :failed}))) "of no kind, unexpected")
    (is (= {:state :failed :kind :refused} (:patient (patient/answer chart :patient {:result :refused :kind :refused}))) "a refusal is the kind it says")))

(deftest the-summary-of-a-patient-with-a-headline-reading-asks-for-the-series-of-it-before-anything-else
  (let [[chart] (pumped (start))
        answered (patient/answer chart :summary (ok fx/ana-summary))]
    (is (= {:state :loaded :data fx/ana-summary} (:summary answered)))
    (is (= {:state :loading :measurement :blood-pressure :days 14} (:series answered)) "the pressure is furthest from normal")
    (is (= [[:series :blood-pressure 14] [:evidence :blood-pressure]] (:queue answered)) "the chart first, the alert's readings next")
    (is (= {:blood-pressure {:state :loading}} (:evidence answered)))))

(deftest the-default-measurement-is-the-headline-reading-else-the-first-the-patient-can-be-offered-else-none
  (is (= :blood-pressure (patient/default-kind fx/ana-summary)))
  (is (= :spo2 (patient/default-kind fx/sofia-summary)))
  (is (nil? (patient/default-kind fx/noah-summary)) "a patient with no reading has nothing to draw")
  (testing "a patient whose readings say nothing, or could not be loaded, is offered the first that may be there"
    (is (= :glucose (patient/default-kind (assoc fx/noah-summary :unavailable ["vitals.glucose" "vitals.weight"]))))
    (is (= :heart-rate (patient/default-kind (assoc-in fx/noah-summary [:vitals :heart-rate] {:id "x" :at "2026-10-13T08:00:00Z" :value "seventy"}))))))

(deftest the-summary-of-a-patient-with-readings-that-could-not-be-loaded-draws-the-first-that-may-be-there
  (let [[chart] (pumped (start))
        lost (assoc fx/noah-summary :unavailable ["vitals.spo2" "vitals.glucose"])
        answered (patient/answer chart :summary (ok lost))]
    (is (= :spo2 (:measurement (:series answered))))))

(deftest the-summary-of-a-patient-with-no-reading-draws-nothing
  (let [[chart] (pumped (start))
        answered (patient/answer chart :summary (ok fx/noah-summary))]
    (is (nil? (:series answered)))
    (is (= [] (:queue answered)))
    (is (= {} (:evidence answered)))
    (is (patient/settled? (patient/answer answered :patient (ok fx/noah-resource))))))

(deftest a-measurement-that-was-asked-for-is-not-changed-by-the-summary
  (let [[chart] (pumped (start {:measurement :heart-rate :days 7}))
        answered (patient/answer chart :summary (ok fx/ana-summary))]
    (is (= {:state :loading :measurement :heart-rate :days 7} (:series answered)))
    (is (= [[:evidence :blood-pressure]] (:queue answered)) "no other series is asked for: only the readings behind the alert")))

(deftest an-alert-asks-for-the-readings-of-its-rule-once-each
  (let [two (assoc fx/ana-summary :alerts [{:id "a" :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z"}
                                           {:id "b" :text "BP stage 2" :status "active" :since "2026-10-12T21:30:00Z"}
                                           {:id "c" :text "Low SpO₂" :status "active" :since "2026-10-12T22:30:00Z"}
                                           {:id "d" :text "Something else" :status "active"}])
        answered (patient/answer (first (pumped (start))) :summary (ok two))]
    (is (= [[:series :blood-pressure 14] [:evidence :blood-pressure] [:evidence :spo2]] (:queue answered)))
    (is (= #{:blood-pressure :spo2} (set (keys (:evidence answered)))))))

(deftest a-summary-with-no-alerts-asks-for-no-readings-behind-them
  (let [answered (patient/answer (first (pumped (start))) :summary (ok (assoc fx/ana-summary :alerts [])))]
    (is (= [[:series :blood-pressure 14]] (:queue answered)))
    (is (= {} (:evidence answered))))
  (testing "nor does one whose alerts could not be loaded"
    (let [answered (patient/answer (first (pumped (start))) :summary (ok (assoc fx/ana-summary :alerts nil :unavailable ["alerts"])))]
      (is (= [[:series :blood-pressure 14]] (:queue answered))))))

(deftest a-summary-that-could-not-be-had-draws-the-first-measurement-once-the-patient-is-known-to-be-there
  (let [[chart] (pumped (start))]
    (testing "the patient is there: the chart is of the first measurement, so that it is a chart and not a blank"
      (let [answered (-> chart (patient/answer :patient (ok fx/ana-resource)) (patient/answer :summary (failed :server)))]
        (is (= {:state :failed :kind :server} (:summary answered)))
        (is (= {:state :loading :measurement :blood-pressure :days 14} (:series answered)))
        (is (= [[:series :blood-pressure 14]] (:queue answered)))))
    (testing "the summary came first: the chart waits for the patient to answer, and is then of the first measurement"
      (let [waiting (patient/answer chart :summary (failed :server))]
        (is (nil? (:series waiting)))
        (is (= [] (:queue waiting)))
        (let [answered (patient/answer waiting :patient (ok fx/ana-resource))]
          (is (= {:state :loading :measurement :blood-pressure :days 14} (:series answered)))
          (is (= [[:series :blood-pressure 14]] (:queue answered))))))
    (testing "a patient who could not be loaded, for a reason that is not the api refusing them, has the chart too"
      (let [answered (-> chart (patient/answer :patient (failed :server)) (patient/answer :summary (failed :server)))]
        (is (= :blood-pressure (:measurement (:series answered))))))))

(deftest a-summary-that-could-not-be-had-changes-nothing-when-a-measurement-was-asked-for
  (let [[chart] (pumped (start {:measurement :weight}))
        answered (patient/answer chart :summary (failed :timeout))]
    (is (= {:state :loading :measurement :weight :days 14} (:series answered)))
    (is (= [] (:queue answered)) "the series was begun with the others")))

(deftest an-answer-that-is-not-a-summary-is-a-failure
  (let [chart (patient/answer (first (pumped (start))) :patient (ok fx/ana-resource))]
    (doseq [body [nil [] "summary" 5 []]]
      (let [answered (patient/answer chart :summary (ok body))]
        (is (= {:state :failed :kind :unexpected} (:summary answered)) (pr-str body))
        (is (= :blood-pressure (:measurement (:series answered))) "and the chart is of the first measurement")))))

;; ---------------------------------------------------------------------------
;; The answers: the series and the readings behind an alert

(deftest the-series-is-kept-with-what-it-is-of
  (let [[chart] (pumped (start {:measurement :blood-pressure :days 30}))
        answered (patient/answer chart [:series :blood-pressure 30] (ok fx/ana-pressure-series))]
    (is (= {:state :loaded :data fx/ana-pressure-series :measurement :blood-pressure :days 30} (:series answered)))))

(deftest a-series-that-is-not-one-is-a-failure-and-keeps-what-it-is-of
  (let [[chart] (pumped (start {:measurement :blood-pressure :days 30}))]
    (doseq [body [nil {} [] {:items "none"} {:items nil} "x"]]
      (is (= {:state :failed :kind :unexpected :measurement :blood-pressure :days 30}
             (:series (patient/answer chart [:series :blood-pressure 30] (ok body))))
          (pr-str body)))
    (testing "and so is a failure of the call"
      (is (= {:state :failed :kind :timeout :measurement :blood-pressure :days 30}
             (:series (patient/answer chart [:series :blood-pressure 30] (failed :timeout))))))))

(deftest an-answer-to-a-series-the-chart-has-left-behind-is-not-heard
  (let [[chart] (pumped (start {:measurement :blood-pressure :days 14}))
        chosen (patient/choose chart {:measurement :glucose :days 14})
        late (patient/answer chosen [:series :blood-pressure 14] (ok fx/ana-pressure-series))]
    (is (= {:state :loading :measurement :glucose :days 14} (:series late)) "the chart is of the glucose now")
    (is (= (:series chosen) (:series late)))
    (testing "of another period too"
      (let [other (patient/answer (patient/choose chart {:measurement :blood-pressure :days 30})
                                  [:series :blood-pressure 14] (ok fx/ana-pressure-series))]
        (is (= {:state :loading :measurement :blood-pressure :days 30} (:series other)))))
    (testing "and one for a chart that has none"
      (is (nil? (:series (patient/answer (start) [:series :glucose 14] (ok fx/ana-pressure-series))))))))

(deftest the-readings-behind-an-alert-are-kept-by-measurement
  (let [answered (-> (first (pumped (start)))
                     (patient/answer :summary (ok fx/ana-summary))
                     (patient/answer [:evidence :blood-pressure] (ok fx/ana-pressure-evidence)))]
    (is (= {:state :loaded :data fx/ana-pressure-evidence} (get-in answered [:evidence :blood-pressure]))))
  (testing "a failure of them is theirs alone"
    (let [answered (-> (first (pumped (start)))
                       (patient/answer :summary (ok fx/ana-summary))
                       (patient/answer [:evidence :blood-pressure] (failed :server)))]
      (is (= {:state :failed :kind :server} (get-in answered [:evidence :blood-pressure])))
      (is (= :loaded (get-in answered [:summary :state])))))
  (testing "readings nobody asked for are not kept"
    (let [answered (patient/answer (first (pumped (start))) [:evidence :glucose] (ok fx/ana-pressure-evidence))]
      (is (= {} (:evidence answered))))))

;; ---------------------------------------------------------------------------
;; Choosing another measurement or period

(deftest another-measurement-is-another-series-and-the-one-before-it-is-forgotten
  (let [[chart] (pumped (start {:measurement :blood-pressure}))
        chosen (patient/choose chart {:measurement :glucose :days 14})]
    (is (= {:state :loading :measurement :glucose :days 14} (:series chosen)))
    (is (= [[:series :glucose 14]] (filter vector? (:queue chosen))))
    (is (not-any? #(and (vector? %) (= :blood-pressure (second %))) (:inflight chosen)) "the call of the one before is not counted")
    (is (= {:glucose 14} {:glucose (:days chosen)}))
    (is (= :glucose (:asked chosen)))))

(deftest another-period-is-another-series-of-the-same-measurement
  (let [[chart] (pumped (start {:measurement :blood-pressure :days 14}))
        chosen (patient/choose chart {:measurement :blood-pressure :days 7})]
    (is (= {:state :loading :measurement :blood-pressure :days 7} (:series chosen)))
    (is (= 7 (:days chosen)))))

(deftest what-is-chosen-again-changes-nothing
  (let [[chart] (pumped (start {:measurement :blood-pressure :days 14}))]
    (is (= chart (patient/choose chart {:measurement :blood-pressure :days 14})))))

(deftest only-the-series-is-asked-for-again
  (let [chart (-> (first (pumped (start)))
                  (step :patient (ok fx/ana-resource))
                  (step :summary (ok fx/ana-summary))
                  (step [:series :blood-pressure 14] (ok fx/ana-pressure-series))
                  (step [:evidence :blood-pressure] (ok fx/ana-pressure-evidence)))
        chosen (patient/choose chart {:measurement :heart-rate :days 30})]
    (is (= (:patient chart) (:patient chosen)))
    (is (= (:summary chart) (:summary chosen)))
    (is (= (:evidence chart) (:evidence chosen)))
    (is (= {:state :loading :measurement :heart-rate :days 30} (:series chosen)))
    (is (= [[:series :heart-rate 30]] (:queue chosen)))
    (is (not (patient/settled? chosen)) "the chart waits for it")))

(deftest going-back-to-the-headline-reading-draws-it-again
  (let [chart (-> (first (pumped (start {:measurement :heart-rate})))
                  (patient/answer :summary (ok fx/ana-summary)))
        chosen (patient/choose chart {:measurement nil :days 14})]
    (is (nil? (:asked chosen)))
    (is (= :blood-pressure (:measurement (:series chosen))))))

(deftest a-measurement-chosen-before-the-summary-is-back-is-waited-for-and-not-lost
  (let [[chart] (pumped (start))
        chosen (patient/choose chart {:measurement nil :days 30})]
    (is (nil? (:series chosen)) "there is nothing to draw until the summary says what")
    (is (= 30 (:days chosen)))
    (let [answered (patient/answer chosen :summary (ok fx/ana-summary))]
      (is (= {:state :loading :measurement :blood-pressure :days 30} (:series answered)) "and then it is of the period chosen"))))

(deftest a-patient-with-no-reading-chosen-another-period-still-has-nothing-to-draw
  (let [chart (-> (first (pumped (start)))
                  (patient/answer :summary (ok fx/noah-summary)))
        chosen (patient/choose chart {:measurement nil :days 7})]
    (is (nil? (:series chosen)))
    (is (= 7 (:days chosen)))))

;; ---------------------------------------------------------------------------
;; Settled, the deadline and the retry

(deftest a-chart-has-settled-when-nothing-is-loading
  (is (false? (patient/settled? (start))))
  (is (true? (patient/settled? (fx/settled-ana))))
  (is (true? (patient/settled? (fx/settled-noah))))
  (testing "each part that can be loading keeps it from settling"
    (doseq [path [[:patient] [:summary] [:series] [:evidence :blood-pressure]]]
      (is (false? (patient/settled? (assoc-in (fx/settled-ana) (conj (vec path) :state) :loading))) (pr-str path))))
  (testing "a failure is settled"
    (is (true? (patient/settled? (assoc (fx/settled-ana) :patient fx/forbidden))))
    (is (true? (patient/settled? (assoc (fx/settled-ana) :series {:state :failed :kind :timeout :measurement :blood-pressure :days 14}))))))

(deftest at-the-deadline-what-is-loading-has-failed-and-nothing-waits
  (let [[chart] (pumped (start))
        chart (patient/answer chart :patient (ok fx/ana-resource))
        chart (patient/answer chart :summary (ok fx/ana-summary))
        [chart _] (patient/pump chart 1)
        expired (patient/expire chart)]
    (is (= {:state :loaded :data fx/ana-resource} (:patient expired)) "what is back stays")
    (is (= {:state :loaded :data fx/ana-summary} (:summary expired)))
    (is (= {:state :failed :kind :timeout :measurement :blood-pressure :days 14} (:series expired)))
    (is (= {:state :failed :kind :timeout} (get-in expired [:evidence :blood-pressure])))
    (is (= [] (:queue expired)))
    (is (= #{} (:inflight expired)))
    (is (= [[:evidence :blood-pressure] [:series :blood-pressure 14]] (:cancelled expired)) "the calls to stop, in the order of their names")
    (is (patient/settled? expired))))

(deftest the-deadline-keeps-what-the-series-is-of
  (let [[chart] (pumped (start {:measurement :glucose :days 30}))
        expired (patient/expire chart)]
    (is (= {:state :failed :kind :timeout}
           (select-keys (:summary expired) [:state :kind])))
    (is (= [:glucose 30 :failed] ((juxt :measurement :days :state) (:series expired))) "so that it can be asked for again")))

(deftest a-chart-that-has-settled-has-nothing-to-expire
  (let [expired (patient/expire (fx/settled-ana))]
    (is (= (dissoc expired :cancelled) (fx/settled-ana)))
    (is (= [] (:cancelled expired)))))

(deftest what-failed-is-what-is-asked-for-again
  (is (= [] (patient/failed-tasks (fx/settled-ana))))
  (is (= [:patient :summary [:series :blood-pressure 14] [:evidence :blood-pressure]]
         (patient/failed-tasks (-> (fx/settled-ana)
                                   (assoc :patient {:state :failed :kind :server} :summary {:state :failed :kind :timeout})
                                   (assoc-in [:series :state] :failed)
                                   (assoc-in [:evidence :blood-pressure] {:state :failed :kind :server}))))
      "in this order")
  (is (= [[:series :heart-rate 30]]
         (patient/failed-tasks (assoc (fx/settled-ana {:asked :heart-rate :days 30}) :series {:state :failed :kind :server :measurement :heart-rate :days 30})))))

(deftest a-retry-asks-again-for-what-failed-and-for-nothing-else
  (let [chart (-> (fx/settled-ana)
                  (assoc :summary {:state :failed :kind :server})
                  (assoc-in [:series :state] :failed))
        retried (patient/retry chart)]
    (is (= [:summary [:series :blood-pressure 14]] (:queue retried)))
    (is (= {:state :loading} (:summary retried)))
    (is (= {:state :loading :measurement :blood-pressure :days 14} (:series retried)) "the series is of what it was")
    (is (= (:patient chart) (:patient retried)))
    (is (= (:evidence chart) (:evidence retried)))
    (is (not (patient/settled? retried)))))

(deftest a-retry-of-a-chart-with-nothing-failed-is-the-same-chart
  (is (= (fx/settled-ana) (patient/retry (fx/settled-ana)))))

(deftest a-summary-that-is-asked-for-again-asks-for-what-follows-it
  (let [failed-summary (patient/answer (first (pumped (start {:measurement :heart-rate}))) :summary (failed :server))
        retried (patient/retry failed-summary)
        [asked _] (patient/pump retried 2)
        answered (patient/answer asked :summary (ok fx/ana-summary))]
    (is (= {:blood-pressure {:state :loading}} (:evidence answered)) "the readings behind the alert")
    (is (= :heart-rate (:measurement (:series answered))) "and the measurement asked for stays")))

(deftest a-chart-whose-summary-failed-and-drew-the-first-measurement-keeps-it-when-the-summary-comes
  (let [failed-summary (-> (first (pumped (start))) (patient/answer :patient (ok fx/ana-resource)) (patient/answer :summary (failed :timeout)))
        retried (patient/retry failed-summary)
        answered (patient/answer retried :summary (ok fx/sofia-summary))]
    (is (= :blood-pressure (:measurement (:series answered))) "it does not jump to another measurement under the eyes of the person")))

;; ---------------------------------------------------------------------------
;; Whatever the api answers

(def ^:private odd-bodies
  (gen/one-of [(gen/return nil)
               gen/small-integer
               gen/string
               (gen/vector gen/small-integer 0 3)
               (gen/map gen/keyword gen/small-integer {:max-elements 3})
               (gen/fmap (fn [items] {:items items}) (gen/vector (gen/map gen/keyword gen/string {:max-elements 3}) 0 3))
               (gen/fmap (fn [alerts] (assoc fx/ana-summary :alerts alerts :unavailable ["alerts"]))
                         (gen/vector (gen/one-of [gen/small-integer (gen/return {:text "BP stage 2"}) (gen/return {})]) 0 3))
               (gen/return fx/ana-summary)
               (gen/return fx/ana-resource)
               (gen/return fx/ana-pressure-series)]))

(def ^:private tasks
  (gen/elements [:patient :summary [:series :blood-pressure 14] [:series :glucose 30] [:evidence :blood-pressure] [:evidence :spo2]]))

(def ^:private outcomes
  (gen/one-of [(gen/fmap (fn [body] {:result :ok :body body}) odd-bodies)
               (gen/fmap (fn [kind] {:result :failed :kind kind}) (gen/elements [:server :timeout :forbidden :not-found :unreachable :unauthorized nil]))
               (gen/return {:result :refused :kind :refused})
               (gen/return {})]))

(defspec whatever-the-api-answers-the-chart-is-a-chart 200
  (prop/for-all [steps (gen/vector (gen/tuple tasks outcomes) 0 8)]
    (let [chart (reduce (fn [chart [task outcome]] (patient/answer chart task outcome))
                        (first (pumped (start {:measurement :blood-pressure})))
                        steps)]
      (and (every? #(contains? #{:loading :loaded :failed} (:state %)) [(:patient chart) (:summary chart)])
           (every? #(contains? #{:loading :loaded :failed} (:state %)) (vals (:evidence chart)))
           (map? (patient/model chart))
           (vector? (patient/failed-tasks chart))))))
