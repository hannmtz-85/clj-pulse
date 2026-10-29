(ns pulse.spa.panel-test
  "The panel of the workspace, without a browser (ADR-0029, point 6): the calls it makes and how many at a time, what each answer does
  to it, the deadline and the retry, the order of urgency, and what the panel shows. These run on the JVM and on Node over the same
  clinic (`pulse.spa.test.panel-fixtures`)."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.panel :as panel]
            [pulse.spa.test.panel-fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; A server to ask: what the api answers each call, from the fixtures

(defn- task-of [call]
  (second (:id call)))

(defn- body-of
  "What the api answers the task `task`."
  [task]
  (cond
    (= :list task) fx/patients
    (= :active task) fx/active-alerts
    (= :acknowledged task) fx/acknowledged-alerts
    (= :requests task) fx/requests
    (= :summary (first task)) (get fx/summaries (second task))
    (= :series (first task)) (get fx/series-by-patient (second task))))

(defn- ok [task] {:result :ok :body (body-of task)})

(defn- drive-on
  "Answers the calls of `panel` (a panel that has just been pumped, with `calls` started) until none is on its way, in the load
  `generation`: `pick` chooses which task of those in flight is answered next (from a vector of them, in the order of their names),
  and `outcome-of` what it is answered. `{:panel :requested :most}`: the panel put in order, every task that was asked for, and the
  most that were ever on their way at once."
  [panel calls generation pick outcome-of]
  (loop [panel panel
         requested (mapv task-of calls)
         most (count (:inflight panel))
         steps 0]
    (cond
      (empty? (:inflight panel)) {:panel (panel/with-order panel) :requested requested :most most}
      (< 200 steps) (throw (ex-info "the panel never settled" {:requested requested}))
      :else (let [task (pick (vec (sort-by str (:inflight panel))))
                  [next-panel started] (panel/pump (panel/answer panel task (outcome-of task)) generation)]
              (recur next-panel (into requested (map task-of) started) (max most (count (:inflight next-panel))) (inc steps))))))

(defn- drive
  "A panel that begins now and is answered until it settles: see `drive-on`."
  [pick outcome-of]
  (let [[started calls] (panel/pump (panel/start fx/now) 1)]
    (drive-on started calls 1 pick outcome-of)))

(defn- after-the-list
  "A panel whose list is back and whose first calls are on their way: six in flight, two waiting."
  []
  (first (panel/pump (panel/answer (first (panel/pump (panel/start fx/now) 1)) :list (ok :list)) 1)))

;; ---------------------------------------------------------------------------
;; The calls

(deftest the-first-calls-are-the-list-the-alerts-and-the-requests
  (let [[panel calls] (panel/pump (panel/start fx/now) 7)]
    (is (= [:list :active :acknowledged :requests] (map task-of calls)))
    (is (= #{:list :active :acknowledged :requests} (:inflight panel)))
    (is (= [] (:queue panel)))
    (is (= [{:method :get :id [:panel :list] :path ["api" "patients"] :query {:limit 100 :sort "family"}
             :on-success [:pulse.spa.panel/succeeded 7 :list] :on-failure [:pulse.spa.panel/failed 7 :list]}
            {:method :get :id [:panel :active] :path ["api" "alerts"] :query {:status "active" :limit 1}
             :on-success [:pulse.spa.panel/succeeded 7 :active] :on-failure [:pulse.spa.panel/failed 7 :active]}
            {:method :get :id [:panel :acknowledged] :path ["api" "alerts"] :query {:status "inactive" :limit 100}
             :on-success [:pulse.spa.panel/succeeded 7 :acknowledged] :on-failure [:pulse.spa.panel/failed 7 :acknowledged]}
            {:method :get :id [:panel :requests] :path ["api" "appointments"] :query {:status "proposed" :limit 1}
             :on-success [:pulse.spa.panel/succeeded 7 :requests] :on-failure [:pulse.spa.panel/failed 7 :requests]}]
           calls)
        "each says which load it belongs to, and which part")))

(deftest the-calls-for-a-patient-name-the-patient-and-the-reading
  (let [panel (panel/start fx/now)]
    (is (= {:method :get :id [:panel [:summary "pt-ana"]] :path ["api" "patients" "pt-ana" "summary"]
            :on-success [:pulse.spa.panel/succeeded 3 [:summary "pt-ana"]] :on-failure [:pulse.spa.panel/failed 3 [:summary "pt-ana"]]}
           (panel/request [:summary "pt-ana"] panel 3)))
    (is (= {:method :get :id [:panel [:series "pt-ana" :blood-pressure]] :path ["api" "patients" "pt-ana" "observations"]
            :query {:measurement "blood-pressure" :from "2026-09-29T12:00:00Z" :limit 500}
            :on-success [:pulse.spa.panel/succeeded 3 [:series "pt-ana" :blood-pressure]]
            :on-failure [:pulse.spa.panel/failed 3 [:series "pt-ana" :blood-pressure]]}
           (panel/request [:series "pt-ana" :blood-pressure] panel 3))
        "the fourteen days are counted back from the moment the panel began")
    (is (= "spo2" (get-in (panel/request [:series "pt-sofia" :spo2] panel 1) [:query :measurement])))))

(deftest no-more-than-six-calls-are-on-their-way-and-the-next-begins-when-one-ends
  (let [[started _] (panel/pump (panel/start fx/now) 1)
        listed (panel/answer started :list (ok :list))
        [busy calls] (panel/pump listed 1)]
    (is (= 3 (count (:inflight listed))) "the list is back, and the alerts and the requests are not")
    (is (= [[:summary "pt-liam"] [:summary "pt-ana"] [:summary "pt-omar"]] (map task-of calls)) "three places are free, in the order of the list")
    (is (= panel/max-in-flight (count (:inflight busy))))
    (is (= [[:summary "pt-noah"] [:summary "pt-sofia"]] (:queue busy)) "and the rest wait")
    (testing "a call that ends makes room for exactly one"
      (let [[after next-calls] (panel/pump (panel/answer busy :requests (ok :requests)) 1)]
        (is (= [[:summary "pt-noah"]] (map task-of next-calls)))
        (is (= panel/max-in-flight (count (:inflight after))))))
    (testing "and with nothing waiting, nothing is started"
      (let [[_ nothing] (panel/pump (first (panel/pump (panel/start fx/now) 1)) 1)]
        (is (= [] nothing))))))

(deftest the-series-of-the-reading-that-says-most-comes-before-the-summaries-that-wait
  (let [busy (after-the-list)
        after (panel/answer busy [:summary "pt-ana"] (ok [:summary "pt-ana"]))]
    (is (= [:series "pt-ana" :blood-pressure] (first (:queue after))) "before [:summary pt-noah] and the other that waits")
    (is (= {:state :loading} (get-in after [:rows "pt-ana" :series])))
    (is (= {:state :loaded :data (get fx/summaries "pt-ana")} (get-in after [:rows "pt-ana" :summary])))
    (testing "a patient with no reading that says anything has no series to ask for"
      (let [noah (panel/answer (after-the-list) [:summary "pt-omar"] (ok [:summary "pt-omar"]))
            none (panel/answer noah :requests (ok :requests))]
        (is (= {:state :loading} (get-in noah [:rows "pt-omar" :series])) "Omar has readings, so he has a series")
        (is (= [[:summary "pt-noah"] [:summary "pt-sofia"]] (rest (:queue none))))
        (let [empty-one (panel/answer (after-the-list) [:summary "pt-ana"] {:result :ok :body (get fx/summaries "pt-noah")})]
          (is (nil? (get-in empty-one [:rows "pt-ana" :series])) "a summary with no reading: none to ask for")
          (is (= [[:summary "pt-noah"] [:summary "pt-sofia"]] (:queue empty-one))))))))

(deftest a-whole-panel-is-thirteen-calls-and-never-more-than-six-at-once
  (doseq [[label pick] {"the first that was started" first "the last" peek}
          :let [{:keys [panel requested most]} (drive pick ok)]]
    (testing label
      (is (= 13 (count requested)) "the list, the alerts and the requests, five summaries and four series")
      (is (= (count requested) (count (distinct requested))) "none is asked for twice")
      (is (<= most panel/max-in-flight))
      (is (= panel/max-in-flight most) "and they do go up to the limit")
      (is (panel/settled? panel))
      (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (:order panel)))
      (is (= (fx/settled) panel) "the same panel as the fixture, loaded"))))

(deftest the-answers-in-any-order-make-the-same-panel
  (let [by-first (drive first ok)
        by-last (drive peek ok)
        by-name (drive #(nth % (quot (count %) 2)) ok)]
    (is (= (:panel by-first) (:panel by-last) (:panel by-name)))))

;; ---------------------------------------------------------------------------
;; What goes wrong

(deftest a-patient-that-fails-fails-alone-and-goes-last
  (let [outcome-of (fn [task] (if (= [:summary "pt-ana"] task) {:result :failed :kind :server} (ok task)))
        {:keys [panel requested]} (drive first outcome-of)]
    (is (= {:state :failed :kind :server} (get-in panel [:rows "pt-ana" :summary])))
    (is (nil? (get-in panel [:rows "pt-ana" :series])) "and nothing is asked about her reading, which is not known")
    (is (= 12 (count requested)))
    (is (= {:state :loaded :data (get fx/summaries "pt-sofia")} (get-in panel [:rows "pt-sofia" :summary])))
    (is (= ["pt-sofia" "pt-liam" "pt-omar" "pt-noah" "pt-ana"] (:order panel)) "the one that did not arrive is last")
    (is (panel/settled? panel))))

(deftest a-series-that-fails-leaves-the-rest-of-the-patient
  (let [outcome-of (fn [task] (if (= [:series "pt-ana" :blood-pressure] task) {:result :failed :kind :timeout} (ok task)))
        {:keys [panel]} (drive first outcome-of)]
    (is (= {:state :failed :kind :timeout} (get-in panel [:rows "pt-ana" :series])))
    (is (= :loaded (get-in panel [:rows "pt-ana" :summary :state])))
    (is (= "pt-ana" (first (:order panel))) "she is still the most urgent: her summary says so")))

(deftest an-answer-that-is-not-what-the-api-answers-is-a-failure-and-not-an-empty-part
  (let [panel (first (panel/pump (panel/start fx/now) 1))]
    (doseq [[task bodies] {:list [{:items "x"} {:total 1} {:items [] :total "3"} "text" nil [] 5]
                           :active [{} {:total "2"} nil "x" []]
                           :acknowledged [{:total 1} {:items []} nil]
                           :requests [{} nil]}
            body bodies
            :let [after (panel/answer panel task {:result :ok :body body})]]
      (is (= {:state :failed :kind :unexpected} (get-in after (if (= :list task) [:list] [:inbox task]))) (pr-str task body))
      (is (= {} (:rows after)) "and nothing was made of it"))
    (testing "a summary and a series that are no maps"
      (let [listed (panel/answer panel :list (ok :list))]
        (is (= {:state :failed :kind :unexpected} (get-in (panel/answer listed [:summary "pt-ana"] {:result :ok :body "x"}) [:rows "pt-ana" :summary])))
        (is (= {:state :failed :kind :unexpected}
               (get-in (panel/answer listed [:series "pt-ana" :blood-pressure] {:result :ok :body {:items nil}}) [:rows "pt-ana" :series])))))))

(deftest a-failure-is-of-the-kind-it-was
  (let [panel (first (panel/pump (panel/start fx/now) 1))]
    (doseq [kind [:unreachable :timeout :server :forbidden :not-found :unauthorized :unexpected]]
      (is (= {:state :failed :kind kind} (:list (panel/answer panel :list {:result :failed :kind kind})))))
    (is (= {:state :failed :kind :unexpected} (:list (panel/answer panel :list {:result :failed}))) "of no kind: unexpected")))

(deftest a-patient-with-an-id-that-is-not-one-is-not-a-row
  (let [panel (first (panel/pump (panel/start fx/now) 1))
        odd {:total 6 :items [{:id "pt-a" :name "A"} {:id "" :name "no id"} {:name "none"} "text" nil {:id "pt-a" :name "again"} {:id 7}]}
        after (panel/answer panel :list {:result :ok :body odd})]
    (is (= ["pt-a"] (keys (:rows after))) "an id that is text, once")
    (is (= [[:summary "pt-a"]] (:queue after)))
    (is (= [{:id "pt-a" :name "A"}] (panel/patients odd)))))

(deftest at-the-deadline-what-is-not-back-has-failed-and-nothing-waits
  (let [busy (after-the-list)
        asked (:inflight busy)
        expired (panel/expire busy)]
    (is (= 6 (count asked)))
    (is (panel/settled? expired))
    (is (= [] (:queue expired)))
    (is (= #{} (:inflight expired)))
    (is (= (set asked) (set (:cancelled expired))) "the calls that were on their way are to be stopped")
    (testing "every part that was loading, failed with a timeout, and the ones that were back stay"
      (is (= {:state :loaded :data fx/patients} (:list expired)))
      (is (= 5 (count (:rows expired))) "including the two summaries that had not been asked for")
      (is (every? #(= {:state :failed :kind :timeout} (:summary %)) (vals (:rows expired))))
      (is (every? #(= {:state :failed :kind :timeout} %) (vals (:inbox expired))))))
  (testing "a part that is back is not touched"
    (let [done (:panel (drive first ok))
          expired (panel/expire done)]
      (is (= done (dissoc expired :cancelled)))
      (is (= [] (:cancelled expired)))))
  (testing "a list that never came fails with a timeout too"
    (let [expired (panel/expire (first (panel/pump (panel/start fx/now) 1)))]
      (is (= {:state :failed :kind :timeout} (:list expired)))
      (is (= #{:list :active :acknowledged :requests} (set (:cancelled expired)))))))

(deftest a-retry-asks-again-for-what-failed-and-keeps-the-order-until-it-settles
  (let [outcome-of (fn [task] (if (#{[:summary "pt-ana"] :requests} task) {:result :failed :kind :server} (ok task)))
        {:keys [panel]} (drive first outcome-of)
        again (panel/retry panel)
        [busy calls] (panel/pump again 2)]
    (is (= [:requests [:summary "pt-ana"]] (panel/failed-tasks panel)) "the alerts and requests first, then the patients")
    (is (= [:requests [:summary "pt-ana"]] (map task-of calls)))
    (is (= {:state :loading} (get-in again [:inbox :requests])) "the part says it is loading, and no more of what went wrong")
    (is (= {:state :loading} (get-in again [:rows "pt-ana" :summary])))
    (is (not (panel/settled? again)))
    (is (= (:order panel) (:order again)) "the rows stay where they were while it loads")
    (testing "and when it is answered, the panel is whole and in order again"
      (let [finished (drive-on busy calls 2 first ok)]
        (is (= (fx/settled) (:panel finished)))
        (is (= [[:series "pt-ana" :blood-pressure]] (filter #(= :series (first %)) (rest (:requested finished))))
            "the series of the patient whose summary came late is asked for then")))))

(deftest when-the-list-failed-it-begins-again-and-keeps-the-alerts-and-requests-that-came
  (let [{:keys [panel]} (drive first (fn [task] (if (= :list task) {:result :failed :kind :unreachable} (ok task))))
        again (panel/retry panel)
        [_ calls] (panel/pump again 2)]
    (is (= [:list] (panel/failed-tasks panel)))
    (is (= [:list] (map task-of calls)) "only the list is asked for")
    (is (= {:state :loaded :data fx/active-alerts} (get-in again [:inbox :active])))
    (is (= {:state :loading} (:list again)))))

(deftest a-panel-with-nothing-failed-has-nothing-to-retry
  (is (= [] (panel/failed-tasks (:panel (drive first ok))))))

;; ---------------------------------------------------------------------------
;; Who says most

(deftest the-headline-is-the-reading-furthest-from-normal-and-the-first-measurement-on-a-tie
  (is (= :blood-pressure (:kind (panel/headline (get fx/summaries "pt-ana")))) "very high beats normal")
  (is (= :glucose (:kind (panel/headline (get fx/summaries "pt-liam")))) "elevated beats normal, and glucose comes before weight")
  (is (= :blood-pressure (:kind (panel/headline (get fx/summaries "pt-omar")))) "all normal: the first measurement")
  (is (nil? (panel/headline (get fx/summaries "pt-noah"))) "no reading, no headline")
  (testing "a reading with no number says nothing, whatever band it has"
    (let [summary {:vitals (assoc fx/vitals-none
                                  :blood-pressure {:id "x" :at "2026-10-13T08:00:00Z" :systolic nil :diastolic 100 :band "very-high"}
                                  :heart-rate {:id "y" :at "2026-10-13T08:00:00Z" :value 74 :unit "/min" :band "normal"})}]
      (is (= :heart-rate (:kind (panel/headline summary))))))
  (testing "a band the workspace does not know is no band at all"
    (let [summary {:vitals (assoc fx/vitals-none
                                  :glucose {:id "g" :at "2026-10-13T08:00:00Z" :value 100 :unit "mg/dL" :band "strange"}
                                  :weight {:id "w" :at "2026-10-13T08:00:00Z" :value 80 :unit "kg" :band "elevated"})}]
      (is (= :weight (:kind (panel/headline summary))))))
  (testing "of two equally far, the first of the measurements"
    (let [summary {:vitals (assoc fx/vitals-none
                                  :weight {:id "w" :at "2026-10-13T08:00:00Z" :value 80 :unit "kg" :band "high"}
                                  :spo2 {:id "s" :at "2026-10-13T08:00:00Z" :value 85 :unit "%" :band "very-low"})}]
      (is (= :spo2 (:kind (panel/headline summary))) "spo2 comes before weight in the order of the measurements")))
  (testing "what is not a summary has no headline"
    (doseq [odd [nil {} {:vitals nil} {:vitals "x"} {:vitals {:blood-pressure "x"}} {:vitals {:blood-pressure 5}}]]
      (is (nil? (panel/headline odd)) (pr-str odd)))))

(deftest the-order-of-urgency-is-the-worst-reading-then-the-alerts-then-the-name
  (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (panel/urgency-order (fx/settled))))
  (testing "of two equally far from normal, the one with more alerts"
    (let [more (assoc-in (fx/settled) [:rows "pt-liam" :summary :data :alerts]
                         [{:id "a" :text "x" :status "active"} {:id "b" :text "y" :status "active"}])]
      (is (= ["pt-ana" "pt-liam" "pt-sofia" "pt-omar" "pt-noah"] (panel/urgency-order more)))))
  (testing "of two equally urgent, the family name, whatever the accents and the case"
    (let [normal (get fx/summaries "pt-omar")
          patient (fn [id family] {:id id :name (str "P " family) :family family :given ["P"]})
          panel {:now fx/now
                 :list (fx/loaded {:total 3 :items [(patient "a" "Zapata") (patient "b" "Ñandú") (patient "c" "abad")]})
                 :rows {"a" {:summary (fx/loaded normal)} "b" {:summary (fx/loaded normal)} "c" {:summary (fx/loaded normal)}}
                 :inbox {} :queue [] :inflight #{}}]
      (is (= ["c" "b" "a"] (panel/urgency-order panel)) "abad, Ñandú, Zapata: not the order of the codes of the letters")))
  (testing "a row that is not loaded comes after the ones that are, whatever its name"
    (is (= ["pt-ana" "pt-sofia" "pt-omar" "pt-noah" "pt-liam"]
           (panel/urgency-order (assoc-in (fx/settled) [:rows "pt-liam" :summary] fx/failed))))
    (is (= ["pt-sofia" "pt-liam" "pt-omar" "pt-noah" "pt-ana"]
           (panel/urgency-order (assoc-in (fx/settled) [:rows "pt-ana" :summary] fx/loading))))))

(deftest the-rows-keep-the-order-of-the-list-until-the-panel-settles
  (let [partly (panel/with-order (panel/answer (after-the-list) [:summary "pt-ana"] (ok [:summary "pt-ana"])))]
    (is (not (panel/settled? partly)))
    (is (nil? (:order partly)))
    (is (= ["pt-liam" "pt-ana" "pt-omar" "pt-noah" "pt-sofia"] (map :id (:rows (panel/model partly {} false)))) "the order of the list")
    (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (map :id (:rows (panel/model (:panel (drive first ok)) {} false)))))))

;; ---------------------------------------------------------------------------
;; A row

(defn- row-of [id & [panel]]
  (let [panel (or panel (fx/settled))]
    (panel/row (some #(when (= id (:id %)) %) (:items fx/patients)) (get-in panel [:rows id]) (:now panel))))

(deftest a-row-says-who-the-patient-is-and-what-they-are-like-now
  (let [ana (row-of "pt-ana")]
    (is (= {:id "pt-ana" :name "Ana García" :identifier "PULSE-0001" :family "García" :given "Ana" :age 58
            :state :loaded :failure nil :band "very-high" :severity 3 :vitals-lost? false :alerts 1
            :visits-known? true :next-visit "Wed 14 Oct, 10:00–10:30"}
           (dissoc ana :headline :trend)))
    (is (= :blood-pressure (get-in ana [:headline :kind])))
    (is (= 177 (get-in ana [:headline :reading :systolic])))))

(deftest a-row-has-the-trend-of-the-reading-that-says-most
  (let [{:keys [state points tone label]} (:trend (row-of "pt-ana"))]
    (is (= :loaded state))
    (is (= [150 158 165 172 177] (map second points)) "the systolic pressure, oldest first")
    (is (= (sort (map first points)) (map first points)))
    (is (= :critical tone) "the colour of the band of the latest reading")
    (is (= "Blood pressure over the last 14 days" label)))
  (is (= :elevated (:tone (:trend (row-of "pt-sofia")))) "a low oxygen saturation")
  (is (= :neutral (:tone (:trend (row-of "pt-omar")))) "normal")
  (is (= "Oxygen saturation over the last 14 days" (:label (:trend (row-of "pt-sofia")))))
  (is (= [150 160 158 170 168] (map second (:points (:trend (row-of "pt-liam"))))) "the value of a reading that is not a pressure")
  (testing "a patient with no reading has no trend"
    (is (nil? (:trend (row-of "pt-noah")))))
  (testing "a reading that is cut by the limit of the series says so"
    (let [panel (assoc-in (fx/settled) [:rows "pt-ana" :series :data :truncated] true)]
      (is (= "Blood pressure, the latest 500 readings" (:label (:trend (row-of "pt-ana" panel)))))))
  (testing "while the series is on its way, or when it failed, the row says so and has no points"
    (is (= {:state :loading} (:trend (row-of "pt-ana" (assoc-in (fx/settled) [:rows "pt-ana" :series] fx/loading)))))
    (is (= {:state :failed} (:trend (row-of "pt-ana" (assoc-in (fx/settled) [:rows "pt-ana" :series] fx/failed)))))
    (is (= {:state :loading} (:trend (row-of "pt-ana" (update-in (fx/settled) [:rows "pt-ana"] dissoc :series)))) "or not asked for yet"))
  (testing "readings that say nothing are not points"
    (let [items [{:id "1" :at "2026-10-12T08:00:00Z" :systolic 150 :diastolic 90 :measurement "blood-pressure"}
                 {:id "2" :at "soon" :systolic 160 :diastolic 90 :measurement "blood-pressure"}
                 {:id "3" :at "2026-10-12T09:00:00Z" :systolic nil :diastolic 90 :measurement "blood-pressure"}
                 {:id "4" :at "2026-10-13T09:00:00Z" :systolic 170 :diastolic 90 :measurement "blood-pressure"}]
          panel (assoc-in (fx/settled) [:rows "pt-ana" :series :data :items] items)]
      (is (= [150 170] (map second (:points (:trend (row-of "pt-ana" panel)))))))))

(deftest a-row-that-is-not-loaded-says-so
  (let [loading (row-of "pt-ana" (assoc-in (fx/settled) [:rows "pt-ana" :summary] fx/loading))
        failed (row-of "pt-ana" (assoc-in (fx/settled) [:rows "pt-ana" :summary] {:state :failed :kind :timeout}))]
    (is (= :loading (:state loading)))
    (is (= :failed (:state failed)))
    (is (= :timeout (:failure failed)))
    (doseq [row [loading failed]]
      (is (= "Ana García" (:name row)) "who they are, from the list")
      (is (= 58 (:age row)))
      (is (nil? (:alerts row)) "and not none")
      (is (nil? (:headline row)))
      (is (= -1 (:severity row)))
      (is (nil? (:trend row)))
      (is (nil? (:next-visit row))))
    (is (nil? (:failure loading)))))

(deftest a-row-says-what-the-summary-could-not-say
  (let [lost (fn [& names] (assoc-in (fx/settled) [:rows "pt-ana" :summary :data :unavailable] (vec names)))]
    (is (true? (:vitals-lost? (row-of "pt-ana" (lost "vitals.heart-rate")))))
    (is (false? (:vitals-lost? (row-of "pt-ana" (lost "conditions" "medications")))) "the record is not what the panel reads")
    (is (false? (:vitals-lost? (row-of "pt-ana")))))
  (testing "the alerts that could not be loaded are not none"
    (let [panel (assoc-in (fx/settled) [:rows "pt-ana" :summary :data :alerts] nil)]
      (is (nil? (:alerts (row-of "pt-ana" panel))))))
  (testing "nor are the appointments"
    (let [panel (assoc-in (fx/settled) [:rows "pt-ana" :summary :data :appointments] nil)
          row (row-of "pt-ana" panel)]
      (is (false? (:visits-known? row)))
      (is (nil? (:next-visit row))))))

(deftest the-next-visit-is-the-soonest-that-is-booked-and-has-not-begun
  (let [visits (fn [& appointments] (:next-visit (row-of "pt-ana" (assoc-in (fx/settled) [:rows "pt-ana" :summary :data :appointments] (vec appointments)))))
        booked (fn [id start end] {:id id :status "booked" :start start :end end})]
    (is (= "Wed 14 Oct, 10:00–10:30" (visits (booked "a" "2026-10-14T08:00:00Z" "2026-10-14T08:30:00Z"))))
    (is (= "Wed 14 Oct, 10:00–10:30" (visits (booked "b" "2026-10-20T08:00:00Z" "2026-10-20T08:30:00Z") (booked "a" "2026-10-14T08:00:00Z" "2026-10-14T08:30:00Z")))
        "the soonest, whatever the order")
    (is (nil? (visits (booked "past" "2026-10-12T08:00:00Z" "2026-10-12T08:30:00Z"))) "one that has begun is not next")
    (is (nil? (visits (booked "now" "2026-10-13T12:00:00Z" "2026-10-13T12:30:00Z"))) "the moment it begins it has begun")
    (is (nil? (visits {:id "r" :status "proposed" :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"})) "a request has no time")
    (is (nil? (visits {:id "c" :status "cancelled" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"})))
    (is (nil? (visits (booked "odd" "soon" "later") "text" nil 5)) "what is not an appointment, or has no time that can be read, is not")))

(deftest data-the-api-gave-that-is-odd-does-not-break-a-row
  (let [odd-patient {:id "x" :name nil :family 7 :given "Ana" :birthDate "no" :identifier 5}
        row (panel/row odd-patient {:summary (fx/loaded {:vitals "x" :alerts "x" :appointments 5 :unavailable ["vitals.glucose" 7 nil]})} fx/now)]
    (is (= "Not named" (:name row)))
    (is (nil? (:identifier row)))
    (is (nil? (:family row)))
    (is (nil? (:given row)))
    (is (nil? (:age row)) "a date of birth that cannot be read is no age")
    (is (nil? (:alerts row)))
    (is (false? (:visits-known? row)))
    (is (true? (:vitals-lost? row)) "the one that is a text, and the rest are left out")
    (is (nil? (:headline row))))
  (testing "a patient that is not a map has no row to be made, and the list does not hold it"
    (is (= [] (panel/patients {:items ["x" nil 5]})))))

;; ---------------------------------------------------------------------------
;; Search

(deftest a-search-finds-the-name-or-the-number-whatever-the-case-and-the-accents
  (let [ana (row-of "pt-ana")
        sofia (row-of "pt-sofia")
        matches? (fn [row q] (panel/matches? row q))]
    (is (matches? ana ""))
    (is (matches? ana "   "))
    (is (matches? ana "ana"))
    (is (matches? ana "ANA"))
    (is (matches? ana "garcia") "García, without the accent")
    (is (matches? ana "García"))
    (is (matches? ana "gARCÍA ana") "every word, in any order")
    (is (matches? ana "ana garcia"))
    (is (matches? ana "pulse-0001") "the number of the patient")
    (is (matches? ana "0001"))
    (is (matches? sofia "sofia") "Sofía, without the accent")
    (is (matches? sofia "SOFÍA ROSSI"))
    (is (not (matches? ana "liam")))
    (is (not (matches? ana "ana liam")) "every word has to be in it")
    (is (not (matches? ana "pulse-0002")))
    (is (not (matches? sofia "ana")) "Sofía has an a, but not the word")))

(deftest a-text-is-compared-without-case-and-without-accents
  (is (= "garcia nunez" (panel/fold (str "Garc" (char 0xED) "a N" (char 0xFA) (char 0xF1) "ez"))))
  (is (= "sofia" (panel/fold (str "SOF" (char 0xCD) "A"))))
  (is (= "pulse-0001" (panel/fold "PULSE-0001")))
  (is (= "" (panel/fold nil)))
  (is (= "5" (panel/fold 5))))
