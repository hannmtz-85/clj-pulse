(ns pulse.spa.inbox-test
  "The machine of the inbox of alerts (ADR-0029, points 6 and 8): the calls that fill it and the order they are made in, how the answers
  are taken, the deadline, the retry, looking again while the inbox is open without moving what is shown, and showing what was found.
  Pure, so these run on the JVM and on Node over the same data."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.panel :as panel]
            [pulse.spa.test.inbox-fixtures :as fx]))

(defn- ok [body] {:result :ok :body body})

(defn- failure [kind] {:result :failed :kind kind})

(defn- pumped
  "The inbox and the calls that `inbox/pump` starts, for the load of the generation `gen`."
  ([inbox] (pumped inbox 1))
  ([inbox gen] (inbox/pump inbox gen)))

(defn- loaded-inbox
  "An inbox whose three first calls have been answered: the active alerts bring the readings behind them, which are queued."
  []
  (let [[started _] (pumped (inbox/start fx/now))]
    (-> started
        (inbox/answer :active (ok fx/active))
        (inbox/answer :acknowledged (ok fx/acknowledged))
        (inbox/answer :patients (ok fx/patients)))))

;; ---------------------------------------------------------------------------
;; The calls

(deftest the-inbox-begins-with-the-alerts-and-the-patients
  (let [started (inbox/start fx/now)]
    (is (= fx/now (:now started)))
    (is (= [:active :acknowledged :patients] (:queue started)))
    (is (= #{} (:inflight started)))
    (is (every? #(= {:state :loading} (get started %)) [:active :acknowledged :patients]))
    (is (= {} (:evidence started)) "the readings wait for the alerts")
    (is (= {} (:latest started)))
    (is (false? (:adopt? started)))))

(deftest the-first-calls-are-the-two-lists-and-the-names
  (let [[started calls] (pumped (inbox/start fx/now) 7)]
    (is (= [] (:queue started)))
    (is (= #{:active :acknowledged :patients} (:inflight started)))
    (is (= [{:method :get :id [:inbox :active] :path ["api" "alerts"] :query {:status "active" :sort "-since" :limit 100}
             :on-success [:pulse.spa.inbox/succeeded 7 :active] :on-failure [:pulse.spa.inbox/failed 7 :active]}
            {:method :get :id [:inbox :acknowledged] :path ["api" "alerts"] :query {:status "inactive" :sort "-since" :limit 100}
             :on-success [:pulse.spa.inbox/succeeded 7 :acknowledged] :on-failure [:pulse.spa.inbox/failed 7 :acknowledged]}
            {:method :get :id [:inbox :patients] :path ["api" "patients"] :query {:limit 100 :sort "family"}
             :on-success [:pulse.spa.inbox/succeeded 7 :patients] :on-failure [:pulse.spa.inbox/failed 7 :patients]}]
           calls))))

(deftest the-readings-behind-an-alert-are-asked-for-by-patient-and-measurement
  (is (= {:method :get :id [:inbox [:evidence "pt-ana" :blood-pressure]]
          :path ["api" "patients" "pt-ana" "observations"] :query {:measurement "blood-pressure" :limit 3}
          :on-success [:pulse.spa.inbox/succeeded 3 [:evidence "pt-ana" :blood-pressure]]
          :on-failure [:pulse.spa.inbox/failed 3 [:evidence "pt-ana" :blood-pressure]]}
         (inbox/request [:evidence "pt-ana" :blood-pressure] 3)))
  (is (= ["api" "patients" "pt-sofia" "observations"] (:path (inbox/request [:evidence "pt-sofia" :spo2] 3))))
  (is (= {:measurement "spo2" :limit 3} (:query (inbox/request [:evidence "pt-sofia" :spo2] 3)))))

(deftest a-look-again-is-the-same-question-with-an-id-of-its-own
  (is (= {:method :get :id [:inbox [:check :active]] :path ["api" "alerts"] :query {:status "active" :sort "-since" :limit 100}
          :on-success [:pulse.spa.inbox/succeeded 3 [:check :active]] :on-failure [:pulse.spa.inbox/failed 3 [:check :active]]}
         (inbox/request [:check :active] 3)))
  (is (= {:method :get :id [:inbox [:check :acknowledged]] :path ["api" "alerts"] :query {:status "inactive" :sort "-since" :limit 100}
          :on-success [:pulse.spa.inbox/succeeded 3 [:check :acknowledged]] :on-failure [:pulse.spa.inbox/failed 3 [:check :acknowledged]]}
         (inbox/request [:check :acknowledged] 3)))
  (testing "so that it never stops the call that loads what is shown"
    (is (not= (:id (inbox/request :active 3)) (:id (inbox/request [:check :active] 3))))))

(deftest the-id-of-a-call-is-the-one-the-effect-stops-it-by
  (doseq [task [:active :acknowledged :patients [:evidence "pt-ana" :blood-pressure] [:check :active]]]
    (is (= (:id (inbox/request task 1)) (inbox/call-id task)) (pr-str task))))

(deftest no-more-than-six-calls-are-on-their-way-at-once
  (is (= 6 inbox/max-in-flight))
  (let [many (assoc (inbox/start fx/now) :queue (vec (for [n (range 10)] [:evidence (str "pt-" n) :spo2])))
        [started calls] (pumped many)]
    (is (= 6 (count calls)))
    (is (= 6 (count (:inflight started))))
    (is (= 4 (count (:queue started))) "the rest wait their turn")
    (is (= [[:evidence "pt-0" :spo2] [:evidence "pt-1" :spo2]] (take 2 (map #(nth (:on-success %) 2) calls))) "in the order they were queued"))
  (testing "a call that is answered makes room for the next"
    (let [many (assoc (inbox/start fx/now) :queue (vec (for [n (range 8)] [:evidence (str "pt-" n) :spo2])) :evidence
                      (into {} (for [n (range 8)] [[(str "pt-" n) :spo2] {:state :loading}])))
          [started _] (pumped many)
          answered (inbox/answer started [:evidence "pt-0" :spo2] (ok fx/empty-series))
          [next-started calls] (pumped answered)]
      (is (= 1 (count calls)))
      (is (= [:evidence "pt-6" :spo2] (nth (:on-success (first calls)) 2)))
      (is (= 6 (count (:inflight next-started)))))))

(deftest the-limits-are-the-ones-of-the-panel-and-the-api
  (is (= 100 inbox/list-limit))
  (is (= panel/deadline-ms inbox/deadline-ms))
  (is (= 10000 inbox/deadline-ms)))

;; ---------------------------------------------------------------------------
;; The answers

(deftest the-active-alerts-bring-the-readings-behind-them
  (let [[started _] (pumped (inbox/start fx/now))
        answered (inbox/answer started :active (ok fx/active))]
    (is (= (fx/loaded fx/active) (:active answered)))
    (is (= #{:acknowledged :patients} (:inflight answered)))
    (is (= [[:evidence "pt-ana" :blood-pressure] [:evidence "pt-sofia" :spo2]] (:queue answered)) "one call for each patient and measurement")
    (is (= {["pt-ana" :blood-pressure] {:state :loading} ["pt-sofia" :spo2] {:state :loading}} (:evidence answered)))))

(deftest the-readings-are-asked-for-once-for-each-patient-and-measurement-whatever-the-alerts
  (let [[started _] (pumped (inbox/start fx/now))
        twice (fx/page [fx/ana-alert (assoc fx/ana-alert :id "flag-pt-ana-R1-b") fx/sofia-alert])
        answered (inbox/answer started :active (ok twice))]
    (is (= [[:evidence "pt-ana" :blood-pressure] [:evidence "pt-sofia" :spo2]] (:queue answered))
        "two alerts of one rule for one patient are told by one set of readings")))

(deftest an-alert-that-has-no-rule-or-no-patient-has-no-readings-to-ask-for
  (let [[started _] (pumped (inbox/start fx/now))
        odd (fx/page [{:id "a" :patient "Patient/pt-ana" :text "Something else" :status "active" :since "2026-10-12T20:30:00Z"}
                      {:id "b" :patient nil :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z"}
                      {:id "c" :patient "Group/g1" :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z"}
                      {:id "d" :patient "Patient/pt ana" :text "BP stage 2" :status "active" :since "2026-10-12T20:30:00Z"}
                      {:id "e" :patient "Patient/pt-ana" :text nil :status "active" :since "2026-10-12T20:30:00Z"}
                      {:id "f" :patient 5 :text "BP stage 2" :status "active"}])
        answered (inbox/answer started :active (ok odd))]
    (is (= :loaded (get-in answered [:active :state])) "they are alerts all the same")
    (is (= [] (:queue answered)))
    (is (= {} (:evidence answered)))))

(deftest what-is-not-an-alert-is-not-asked-about
  (let [[started _] (pumped (inbox/start fx/now))
        answered (inbox/answer started :active (ok (fx/page [nil "x" 5 {:patient "Patient/pt-ana" :text "BP stage 2"} (assoc fx/ana-alert :id "")
                                                             (assoc fx/ana-alert :id nil) fx/ana-alert])))]
    (is (= [[:evidence "pt-ana" :blood-pressure]] (:queue answered)) "only the alert that has an id")))

(deftest the-list-of-alerts-is-the-api-s-once-each-and-in-its-order
  (is (= [fx/ana-alert fx/sofia-alert] (inbox/alert-items fx/active)))
  (is (= [fx/ana-alert fx/sofia-alert] (inbox/alert-items (fx/page [fx/ana-alert fx/sofia-alert fx/ana-alert]))) "an alert twice is shown once")
  (is (= [fx/sofia-alert fx/ana-alert] (inbox/alert-items (fx/page [fx/sofia-alert fx/ana-alert]))) "and the order is not changed")
  (is (= [] (inbox/alert-items nil)))
  (is (= [] (inbox/alert-items {:items "x"}))))

(deftest a-patient-is-told-by-the-reference-of-an-alert
  (is (= "pt-ana" (inbox/patient-id "Patient/pt-ana")))
  (is (= "a.b-1" (inbox/patient-id "Patient/a.b-1")))
  (is (= (apply str (repeat 64 "a")) (inbox/patient-id (str "Patient/" (apply str (repeat 64 "a"))))))
  (doseq [reference [nil "" "Patient/" "Patient" "Practitioner/pr-rivera" "patient/pt-ana" "Patient/pt ana" "Patient/pt/ana" "Patient/pt_ana"
                     "Patient/pt-ana " " Patient/pt-ana" "Patient/pt-ana\n" 5 :Patient/pt-ana ["Patient/pt-ana"]
                     (str "Patient/" (apply str (repeat 65 "a")))]]
    (is (nil? (inbox/patient-id reference)) (pr-str reference))))

(deftest the-names-and-the-acknowledged-alerts-are-taken-as-they-come
  (let [[started _] (pumped (inbox/start fx/now))
        answered (-> started
                     (inbox/answer :acknowledged (ok fx/acknowledged))
                     (inbox/answer :patients (ok (fx/page []))))]
    (is (= (fx/loaded fx/acknowledged) (:acknowledged answered)))
    (is (= (fx/loaded (fx/page [])) (:patients answered)))
    (is (= [] (:queue answered)) "and bring nothing else to ask for")))

(deftest an-answer-that-is-a-failure-is-a-failed-part-and-never-an-empty-one
  (let [[started _] (pumped (inbox/start fx/now))]
    (doseq [task [:active :acknowledged :patients]
            kind [:server :timeout :unreachable :forbidden]]
      (is (= {:state :failed :kind kind} (get (inbox/answer started task (failure kind)) task)) (pr-str [task kind])))
    (testing "a failure with no kind is an unexpected one"
      (is (= {:state :failed :kind :unexpected} (:active (inbox/answer started :active {:result :failed})))))
    (testing "and brings nothing to ask for"
      (is (= [] (:queue (inbox/answer started :active (failure :server))))))))

(deftest an-answer-that-is-not-what-the-api-answers-is-an-unexpected-failure
  (let [[started _] (pumped (inbox/start fx/now))]
    (doseq [[task body] [[:active "text"] [:active {:items []}] [:active {:total 0}] [:active {:total "2" :items []}] [:active {:total 1.5 :items []}]
                         [:active {:total 0 :items "x"}] [:active nil] [:active []]
                         [:acknowledged {:items []}] [:acknowledged nil]
                         [:patients {:total 1}] [:patients {:items []}] [:patients nil]
                         [[:check :active] {:items []}] [[:check :acknowledged] "x"]]
            :let [answered (inbox/answer (update started :inflight conj task) task (ok body))
                  part (get-in answered (if (vector? task) [:latest (second task)] [task]))]]
      (is (= {:state :failed :kind :unexpected} part) (pr-str [task body])))
    (testing "the readings need only their items"
      (let [asked (-> started (assoc-in [:evidence ["pt-ana" :blood-pressure]] {:state :loading}))]
        (is (= :unexpected (get-in (inbox/answer asked [:evidence "pt-ana" :blood-pressure] (ok {:total 1})) [:evidence ["pt-ana" :blood-pressure] :kind])))
        (is (= :loaded (get-in (inbox/answer asked [:evidence "pt-ana" :blood-pressure] (ok {:items []})) [:evidence ["pt-ana" :blood-pressure] :state])))))))

(deftest the-answer-about-readings-nobody-asks-for-is-dropped
  (let [inbox (loaded-inbox)
        late (inbox/answer inbox [:evidence "pt-liam" :glucose] (ok fx/liam-glucose-evidence))]
    (is (not (contains? (:evidence late) ["pt-liam" :glucose])) "it does not appear out of nowhere")
    (is (= (:evidence inbox) (:evidence late)))))

(deftest an-answer-takes-its-call-out-of-the-ones-on-their-way
  (let [[started _] (pumped (inbox/start fx/now))]
    (is (= #{:acknowledged :patients} (:inflight (inbox/answer started :active (ok fx/active)))))
    (is (= #{:active :acknowledged} (:inflight (inbox/answer started :patients (failure :server)))))))

;; ---------------------------------------------------------------------------
;; Settled, the deadline and the retry

(deftest the-inbox-has-settled-when-nothing-is-loading-waiting-or-on-its-way
  (is (true? (inbox/settled? (fx/settled))))
  (is (false? (inbox/settled? (inbox/start fx/now))))
  (is (false? (inbox/settled? (first (pumped (inbox/start fx/now))))))
  (testing "a part that is loading"
    (doseq [path [[:active] [:acknowledged] [:patients] [:evidence ["pt-ana" :blood-pressure]]]]
      (is (false? (inbox/settled? (assoc-in (fx/settled) path fx/loading))) (pr-str path))))
  (testing "a part that failed has settled"
    (doseq [path [[:active] [:acknowledged] [:patients] [:evidence ["pt-ana" :blood-pressure]]]]
      (is (true? (inbox/settled? (assoc-in (fx/settled) path fx/failed))) (pr-str path))))
  (testing "a task that is waiting or on its way, though no part is loading"
    (is (false? (inbox/settled? (assoc (fx/settled) :queue [[:check :active]]))))
    (is (false? (inbox/settled? (assoc (fx/settled) :inflight #{[:check :active]}))))))

(deftest at-the-deadline-what-is-loading-has-failed-and-nothing-waits
  (let [[started _] (pumped (inbox/start fx/now))
        answered (inbox/answer started :acknowledged (ok fx/acknowledged))
        expired (inbox/expire answered)]
    (is (= {:state :failed :kind :timeout} (:active expired)))
    (is (= {:state :failed :kind :timeout} (:patients expired)))
    (is (= (fx/loaded fx/acknowledged) (:acknowledged expired)) "what had come is kept")
    (is (= [] (:queue expired)))
    (is (= #{} (:inflight expired)))
    (is (= [:active :patients] (:cancelled expired)) "the calls that were on their way, to be stopped")
    (is (true? (inbox/settled? (dissoc expired :cancelled))))))

(deftest at-the-deadline-the-readings-that-did-not-come-have-failed-and-those-that-did-are-kept
  (let [inbox (-> (loaded-inbox)
                  (inbox/pump 1)
                  first
                  (inbox/answer [:evidence "pt-ana" :blood-pressure] (ok fx/ana-evidence)))
        expired (inbox/expire inbox)]
    (is (= :loaded (get-in expired [:evidence ["pt-ana" :blood-pressure] :state])))
    (is (= {:state :failed :kind :timeout} (get-in expired [:evidence ["pt-sofia" :spo2]])))
    (is (= [[:evidence "pt-sofia" :spo2]] (:cancelled expired)))))

(deftest at-the-deadline-the-calls-to-stop-are-in-the-order-of-their-names
  (let [inbox (assoc (inbox/start fx/now) :queue [] :inflight #{:patients :active [:check :acknowledged] [:evidence "pt-b" :spo2] [:evidence "pt-a" :spo2]}
                     :evidence {["pt-a" :spo2] fx/loading ["pt-b" :spo2] fx/loading})]
    (is (= [:active :patients [:check :acknowledged] [:evidence "pt-a" :spo2] [:evidence "pt-b" :spo2]] (:cancelled (inbox/expire inbox))))))

(deftest at-the-deadline-a-look-that-is-not-back-has-failed-and-what-was-shown-stays
  (let [inbox (-> (fx/settled) (inbox/after-ack fx/now) (inbox/pump 1) first)
        expired (inbox/expire inbox)]
    (is (= {:state :failed :kind :timeout} (get-in expired [:latest :active])))
    (is (= {:state :failed :kind :timeout} (get-in expired [:latest :acknowledged])))
    (is (= (:active (fx/settled)) (:active expired)) "what is shown is not touched")
    (is (false? (:adopt? expired)) "and nothing is waiting to be shown")
    (is (= [[:check :acknowledged] [:check :active]] (:cancelled expired)))))

(deftest a-look-that-came-back-before-the-deadline-is-kept-at-the-deadline
  (let [inbox (-> (fx/settled)
                  (inbox/check fx/now)
                  (inbox/pump 1)
                  first
                  (inbox/answer [:check :active] (ok fx/active-with-liam)))
        expired (inbox/expire inbox)]
    (is (= (fx/loaded fx/active-with-liam) (get-in expired [:latest :active])))
    (is (= {:state :failed :kind :timeout} (get-in expired [:latest :acknowledged])))))

(deftest what-failed-is-asked-for-again-in-order
  (let [inbox (-> (fx/settled)
                  (assoc :patients fx/failed :active fx/failed :acknowledged fx/failed)
                  (assoc-in [:evidence ["pt-sofia" :spo2]] fx/failed)
                  (assoc-in [:evidence ["pt-ana" :blood-pressure]] fx/failed)
                  (assoc-in [:latest :acknowledged] fx/failed)
                  (assoc-in [:latest :active] fx/failed))]
    (is (= [:patients :active :acknowledged
            [:evidence "pt-ana" :blood-pressure] [:evidence "pt-sofia" :spo2]
            [:check :active] [:check :acknowledged]]
           (inbox/failed-tasks inbox)) "the lists and the names first, then the readings, then the looks")
    (is (= [] (inbox/failed-tasks (fx/settled))))
    (is (= [] (inbox/failed-tasks (inbox/start fx/now))) "what is loading has not failed")))

(deftest a-retry-asks-again-for-what-failed-and-for-nothing-else
  (let [failed-inbox (-> (fx/settled) (assoc :active fx/failed) (assoc-in [:evidence ["pt-ana" :blood-pressure]] fx/failed))
        retried (inbox/retry failed-inbox)]
    (is (= fx/loading (:active retried)))
    (is (= fx/loading (get-in retried [:evidence ["pt-ana" :blood-pressure]])))
    (is (= (:acknowledged failed-inbox) (:acknowledged retried)) "what loaded stays")
    (is (= [:active [:evidence "pt-ana" :blood-pressure]] (:queue retried)))
    (is (= [] (:queue (inbox/retry (fx/settled)))) "nothing failed, nothing is asked")))

(deftest a-look-that-failed-is-asked-again-and-stays-as-it-was-until-the-answer
  (let [inbox (assoc-in (fx/settled) [:latest :active] fx/failed)
        retried (inbox/retry inbox)]
    (is (= [[:check :active]] (:queue retried)))
    (is (= fx/failed (get-in retried [:latest :active])) "so that the page keeps saying so until the new look is back")
    (is (false? (inbox/settled? retried)))))

(deftest the-alerts-that-came-back-after-a-retry-bring-their-readings
  (let [failed-inbox (-> (fx/settled) (assoc :active fx/failed) (assoc :evidence {}))
        [started _] (pumped (inbox/retry failed-inbox))
        answered (inbox/answer started :active (ok fx/active))]
    (is (= [[:evidence "pt-ana" :blood-pressure] [:evidence "pt-sofia" :spo2]] (:queue answered)))))

;; ---------------------------------------------------------------------------
;; Looking again

(deftest looking-again-asks-for-both-lists-and-changes-nothing-that-is-shown
  (let [inbox (fx/settled)
        later "2026-10-13T12:00:30Z"
        checked (inbox/check inbox later)]
    (is (= [[:check :active] [:check :acknowledged]] (:queue checked)))
    (is (= later (:now checked)) "the moment of the page is the moment of the look")
    (doseq [k [:active :acknowledged :patients :evidence :latest]]
      (is (= (get inbox k) (get checked k)) (str k " is as it was")))
    (is (false? (:adopt? checked)))
    (testing "and the calls are the ones of the lists, with an id of their own"
      (let [[_ calls] (pumped checked 4)]
        (is (= [[:inbox [:check :active]] [:inbox [:check :acknowledged]]] (map :id calls)))
        (is (= [{:status "active" :sort "-since" :limit 100} {:status "inactive" :sort "-since" :limit 100}] (map :query calls)))))))

(deftest a-look-that-is-begun-stops-the-one-before-it
  (let [asked (-> (fx/settled) (inbox/check fx/now) (inbox/pump 1) first)
        again (inbox/check asked "2026-10-13T12:01:00Z")]
    (is (= #{[:check :active] [:check :acknowledged]} (:inflight asked)))
    (is (= #{} (:inflight again)) "it is not on its way any more: the new call has the same id and stops it")
    (is (= [[:check :active] [:check :acknowledged]] (:queue again)) "and it is not queued twice")
    (is (= #{[:check :active] [:check :acknowledged]} (:inflight (first (pumped again)))))))

(deftest a-look-keeps-what-was-waiting-before-it
  (let [waiting (assoc (fx/settled) :queue [[:evidence "pt-x" :spo2]] :inflight #{:patients})
        checked (inbox/check waiting fx/now)]
    (is (= [[:evidence "pt-x" :spo2] [:check :active] [:check :acknowledged]] (:queue checked)))
    (is (= #{:patients} (:inflight checked)))))

(deftest what-a-look-finds-is-kept-apart-and-nothing-shown-moves
  (let [asked (-> (fx/settled) (inbox/check fx/now) (inbox/pump 1) first)
        answered (-> asked
                     (inbox/answer [:check :active] (ok fx/active-with-liam))
                     (inbox/answer [:check :acknowledged] (ok fx/acknowledged)))]
    (is (= (fx/loaded fx/active-with-liam) (get-in answered [:latest :active])))
    (is (= (fx/loaded fx/acknowledged) (get-in answered [:latest :acknowledged])))
    (is (= (fx/loaded fx/active) (:active answered)) "what is shown is what was shown")
    (is (= (:evidence (fx/settled)) (:evidence answered)) "and no readings are asked for yet")
    (is (= [] (:queue answered)))
    (is (true? (inbox/settled? answered)))))

(deftest the-page-is-told-when-the-alerts-that-need-a-decision-are-other-than-the-ones-it-shows
  (let [with-latest (fn [latest] (assoc (fx/settled) :latest {:active latest}))]
    (testing "one more, one less, other ones"
      (is (true? (inbox/changed? (with-latest (fx/loaded fx/active-with-liam)))))
      (is (true? (inbox/changed? (with-latest (fx/loaded fx/active-without-ana)))))
      (is (true? (inbox/changed? (with-latest (fx/loaded (fx/page [fx/ana-alert fx/liam-alert])))))))
    (testing "the same ones, however they come"
      (is (false? (inbox/changed? (with-latest (fx/loaded fx/active)))))
      (is (false? (inbox/changed? (with-latest (fx/loaded (fx/page [fx/sofia-alert fx/ana-alert])))))) "in another order")
    (testing "more of them than the list holds is a change all the same"
      (is (true? (inbox/changed? (with-latest (fx/loaded (fx/page [fx/ana-alert fx/sofia-alert] 130)))))))
    (testing "a look that has not come, or failed, is no change"
      (is (false? (inbox/changed? (fx/settled))))
      (is (false? (inbox/changed? (with-latest fx/loading))))
      (is (false? (inbox/changed? (with-latest fx/failed)))))
    (testing "nor is a change of the acknowledged ones alone"
      (is (false? (inbox/changed? (assoc (fx/settled) :latest {:acknowledged (fx/loaded fx/acknowledged-with-ana)})))))
    (testing "nor when nothing is shown to differ from"
      (is (false? (inbox/changed? (assoc (with-latest (fx/loaded fx/active-with-liam)) :active fx/failed))))
      (is (false? (inbox/changed? (assoc (with-latest (fx/loaded fx/active-with-liam)) :active fx/loading)))))))

(deftest a-look-that-failed-is-said
  (is (false? (inbox/check-failed? (fx/settled))))
  (is (true? (inbox/check-failed? (assoc-in (fx/settled) [:latest :active] fx/failed))))
  (is (true? (inbox/check-failed? (assoc-in (fx/settled) [:latest :acknowledged] fx/failed))))
  (is (false? (inbox/check-failed? (assoc-in (fx/settled) [:latest :active] (fx/loaded fx/active)))))
  (is (false? (inbox/check-failed? (assoc-in (fx/settled) [:latest :active] fx/loading)))))

;; ---------------------------------------------------------------------------
;; Showing what was found

(deftest showing-the-changes-replaces-the-lists-and-asks-for-the-readings-of-the-new-alerts-only
  (let [inbox (assoc (fx/settled) :latest {:active (fx/loaded fx/active-with-liam) :acknowledged (fx/loaded fx/acknowledged-with-ana)})
        adopted (inbox/adopt inbox)]
    (is (= (fx/loaded fx/active-with-liam) (:active adopted)))
    (is (= (fx/loaded fx/acknowledged-with-ana) (:acknowledged adopted)))
    (is (= {} (:latest adopted)) "what was found is shown, and is not kept apart any more")
    (is (= [[:evidence "pt-liam" :glucose]] (:queue adopted)) "Ana's and Sofía's readings are not asked for again")
    (is (= fx/loading (get-in adopted [:evidence ["pt-liam" :glucose]])))
    (is (= (get-in inbox [:evidence ["pt-ana" :blood-pressure]]) (get-in adopted [:evidence ["pt-ana" :blood-pressure]])) "those it has are kept")
    (is (false? (:adopt? adopted)))
    (is (false? (inbox/changed? adopted)))))

(deftest only-what-came-back-is-shown-and-what-failed-is-left-for-the-page-to-say
  (let [inbox (assoc (fx/settled) :latest {:active (fx/loaded fx/active-with-liam) :acknowledged fx/failed})
        adopted (inbox/adopt inbox)]
    (is (= (fx/loaded fx/active-with-liam) (:active adopted)))
    (is (= (fx/loaded fx/acknowledged) (:acknowledged adopted)) "the list that did not come is the one that was shown")
    (is (= {:acknowledged fx/failed} (:latest adopted)))
    (is (true? (inbox/check-failed? adopted))))
  (testing "and when nothing came back nothing is shown"
    (let [inbox (assoc (fx/settled) :latest {:active fx/failed :acknowledged fx/failed})]
      (is (= (dissoc inbox :adopt?) (dissoc (inbox/adopt inbox) :adopt?))))))

(deftest showing-what-was-found-when-the-alerts-are-not-loaded-asks-for-nothing
  (let [inbox (-> (fx/settled) (assoc :active fx/failed :evidence {}) (assoc :latest {:acknowledged (fx/loaded fx/acknowledged-with-ana)}))
        adopted (inbox/adopt inbox)]
    (is (= fx/failed (:active adopted)))
    (is (= [] (:queue adopted)))
    (is (= (fx/loaded fx/acknowledged-with-ana) (:acknowledged adopted)))))

(deftest after-acknowledging-an-alert-the-inbox-looks-again-and-shows-what-it-finds
  (let [inbox (inbox/after-ack (fx/settled) "2026-10-13T12:00:10Z")]
    (is (true? (:adopt? inbox)))
    (is (= [[:check :active] [:check :acknowledged]] (:queue inbox)))
    (is (= "2026-10-13T12:00:10Z" (:now inbox)))
    (is (false? (inbox/adopt-now? inbox)) "not before the looks are back")
    (let [asked (first (pumped inbox))
          half (inbox/answer asked [:check :active] (ok fx/active-without-ana))
          back (inbox/answer half [:check :acknowledged] (ok fx/acknowledged-with-ana))]
      (is (false? (inbox/adopt-now? half)) "one of the two is back")
      (is (true? (inbox/adopt-now? back)) "both are")
      (let [adopted (inbox/adopt back)]
        (is (= (fx/loaded fx/active-without-ana) (:active adopted)))
        (is (= (fx/loaded fx/acknowledged-with-ana) (:acknowledged adopted)))
        (is (false? (inbox/adopt-now? adopted)))))))

(deftest a-look-that-was-not-asked-for-by-the-person-is-never-shown-by-itself
  (let [asked (-> (fx/settled) (inbox/check fx/now) (inbox/pump 1) first)
        back (-> asked
                 (inbox/answer [:check :active] (ok fx/active-with-liam))
                 (inbox/answer [:check :acknowledged] (ok fx/acknowledged)))]
    (is (true? (inbox/checks-done? back)))
    (is (false? (inbox/adopt-now? back)))))

(deftest the-looks-are-done-when-none-is-waiting-or-on-its-way
  (is (true? (inbox/checks-done? (fx/settled))))
  (is (false? (inbox/checks-done? (inbox/check (fx/settled) fx/now))))
  (is (false? (inbox/checks-done? (first (pumped (inbox/check (fx/settled) fx/now))))))
  (testing "calls that are not looks do not count"
    (is (true? (inbox/checks-done? (assoc (fx/settled) :queue [[:evidence "pt-x" :spo2]] :inflight #{:patients}))))))

;; ---------------------------------------------------------------------------
;; Where the focus goes

(deftest the-focus-goes-to-the-heading-of-the-alerts-that-need-a-decision-unless-an-acknowledgement-was-not-done
  (testing "after an acknowledgement that went through, the button that had the focus is gone"
    (is (= "#needs" (inbox/focus-target (fx/settled) nil)))
    (is (= "#needs" (inbox/focus-target (fx/settled) {:alerts {"flag-pt-ana-R1" {:state :sending}}})))
    (is (= "#needs" (inbox/focus-target (assoc (fx/settled) :active (fx/loaded fx/none)) {:alerts {"flag-pt-ana-R1" {:state :failed :message "No."}}}))
        "an alert that failed and is not on the page any more has no button to give the focus to"))
  (testing "after one that was not done, the alert is still there with its message and a free button, and that is where the person was"
    (let [acks {:alerts {"flag-pt-ana-R1" {:state :failed :message "No."}}}]
      (is (= "#ack-flag-pt-ana-R1" (inbox/focus-target (fx/settled) acks)))))
  (testing "the first of them, in the order the page shows them"
    (let [acks {:alerts {"flag-pt-ana-R1" {:state :failed :message "No."} "flag-pt-sofia-R2" {:state :failed :message "No."}}}]
      (is (= "#ack-flag-pt-ana-R1" (inbox/focus-target (fx/settled) acks)))
      (is (= "#ack-flag-pt-sofia-R2" (inbox/focus-target (assoc (fx/settled) :active (fx/loaded (fx/page [fx/sofia-alert fx/ana-alert]))) acks))))))
