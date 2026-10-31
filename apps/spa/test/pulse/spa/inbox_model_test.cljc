(ns pulse.spa.inbox-model-test
  "What the inbox of alerts shows (ADR-0029, points 6 and 8), from its state: the alerts that need a decision as cards, the ones that
  were acknowledged as rows, the names of the patients, the readings behind each alert, the pills with their counts, what is said when
  something could not be loaded or the page differs from what the api says now, and what a screen reader is told. Pure, so these run on
  the JVM and on Node over the same data as the machine's."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.test.inbox-fixtures :as fx]
            [pulse.view.alerts :as alerts]))

(defn- model
  ([inbox] (model inbox {}))
  ([inbox options] (inbox/model inbox options false)))

(defn- card
  "The card of the alert `id` of the active ones in `m`."
  [m id]
  (first (filter #(= id (:id %)) (get-in m [:active :items]))))

(defn- row [m id]
  (first (filter #(= id (:id %)) (get-in m [:acknowledged :items]))))

;; ---------------------------------------------------------------------------
;; The page

(deftest the-inbox-is-titled-and-described-for-whose-it-is
  (let [practitioner (inbox/model (fx/settled) {} false)
        administrator (inbox/model (fx/settled) {} true)]
    (is (= "Alerts" (:title practitioner)))
    (is (= "Alerts" (:title administrator)))
    (is (= "Raised by the rules from the readings of your patients." (:description practitioner)))
    (is (= "Raised by the rules from the readings of the clinic's patients." (:description administrator)))))

(deftest an-alert-that-needs-a-decision-is-a-card-with-its-rule-and-the-readings-behind-it
  (let [m (model (fx/settled))]
    (is (= {:id "flag-pt-ana-R1"
            :patient-id "pt-ana"
            :name "Ana García"
            :text "BP stage 2"
            :label "BP stage 2 · Ana García"
            :tone :critical
            :since-phrase "yesterday, 22:30"
            :description (alerts/description "BP stage 2")
            :evidence {:state :loaded :lines ["177/100 mmHg, today, 10:00" "172/100 mmHg, yesterday, 10:00" "165/98 mmHg, Oct 11, 10:00"]}
            :ack nil}
           (card m "flag-pt-ana-R1")))
    (is (= {:state :loaded :lines ["90 %, today, 10:00" "92 %, yesterday, 10:00" "93 %, Oct 11, 10:00"]} (:evidence (card m "flag-pt-sofia-R2"))))
    (is (= [:critical :high] (map :tone (get-in m [:active :items]))) "the colour of each is its rule's")
    (is (= 2 (get-in m [:active :total])))))

(deftest the-alerts-are-in-the-order-the-api-gave-them
  (is (= ["flag-pt-ana-R1" "flag-pt-sofia-R2"] (map :id (get-in (model (fx/settled)) [:active :items]))))
  (let [swapped (assoc-in (fx/settled) [:active :data] (fx/page [fx/sofia-alert fx/ana-alert]))]
    (is (= ["flag-pt-sofia-R2" "flag-pt-ana-R1"] (map :id (get-in (model swapped) [:active :items]))) "the workspace does not sort them again")))

(deftest an-alert-acknowledged-is-a-row-with-when-it-was-raised-and-when-it-was-acknowledged
  (let [m (model (fx/settled))]
    (is (= [{:id "flag-pt-liam-R3"
             :patient-id "pt-liam"
             :name "Liam Chen"
             :text "Glucose out of range"
             :label "Glucose out of range · Liam Chen"
             :since-phrase "Oct 11, 22:30"
             :until-phrase "today, 09:40"}]
           (get-in m [:acknowledged :items])))
    (is (= 1 (get-in m [:acknowledged :total])))))

(deftest an-alert-nobody-has-a-rule-for-is-an-alert-all-the-same
  (let [odd (assoc-in (fx/settled) [:active :data] (fx/page [{:id "x" :patient "Patient/pt-ana" :text "Something else" :status "active" :since "2026-10-12T20:30:00Z"}]))
        c (card (model odd) "x")]
    (is (= "Something else" (:text c)))
    (is (= "Something else · Ana García" (:label c)))
    (is (= :high (:tone c)) "it is a thing that needs a look")
    (is (nil? (:description c)) "and no more words than its own")
    (is (nil? (:evidence c)) "nothing says what it watches")))

(deftest an-alert-with-no-text-is-called-alert
  (let [odd (assoc-in (fx/settled) [:active :data] (fx/page [{:id "x" :patient "Patient/pt-ana" :text nil :status "active"}
                                                             {:id "y" :patient "Patient/pt-ana" :text "  " :status "active"}
                                                             {:id "z" :patient "Patient/pt-ana" :text 5 :status "active"}]))
        m (model odd)]
    (is (= ["Alert" "Alert" "Alert"] (map :text (get-in m [:active :items]))))
    (is (= ["Alert · Ana García" "Alert · Ana García" "Alert · Ana García"] (map :label (get-in m [:active :items]))))
    (is (nil? (:since-phrase (card m "x"))) "and no time that can be read")))

(deftest a-time-that-cannot-be-read-is-left-out
  (doseq [since [nil "" "yesterday" 5 "2026-13-45T00:00:00Z"]]
    (let [odd (assoc-in (fx/settled) [:active :data] (fx/page [(assoc fx/ana-alert :since since)]))]
      (is (nil? (:since-phrase (card (model odd) "flag-pt-ana-R1"))) (pr-str since))))
  (let [odd (assoc-in (fx/settled) [:acknowledged :data] (fx/page [(assoc fx/liam-acknowledged :since "x" :until nil)]))
        r (row (model odd) "flag-pt-liam-R3")]
    (is (nil? (:since-phrase r)))
    (is (nil? (:until-phrase r)))))

;; ---------------------------------------------------------------------------
;; The names of the patients

(deftest a-patient-the-clinic-has-no-name-for-is-told-by-their-number
  (let [m (model (assoc-in (fx/settled) [:active :data] (fx/page [(assoc fx/ana-alert :patient "Patient/pt-unknown")])))
        c (card m "flag-pt-ana-R1")]
    (is (= "Patient pt-unknown" (:name c)))
    (is (= "BP stage 2 · Patient pt-unknown" (:label c)))
    (is (= "pt-unknown" (:patient-id c)) "and the chart is still one click away")))

(deftest an-alert-that-names-no-patient-has-no-name-and-no-chart
  (doseq [reference [nil "" "Group/g1" "Patient/pt ana" 5]]
    (let [m (model (assoc-in (fx/settled) [:active :data] (fx/page [(assoc fx/ana-alert :patient reference)])))
          c (card m "flag-pt-ana-R1")]
      (is (nil? (:patient-id c)) (pr-str reference))
      (is (nil? (:name c)) (pr-str reference))
      (is (= "BP stage 2" (:label c)) (pr-str reference))
      (is (nil? (:evidence c)) "and no readings to ask a patient for"))))

(deftest a-patient-with-no-name-in-the-list-is-not-named
  (let [named (assoc-in (fx/settled) [:patients :data :items 1 :name] nil)
        m (model named)]
    (is (= "Not named" (:name (card m "flag-pt-ana-R1"))))
    (is (= "BP stage 2 · Not named" (:label (card m "flag-pt-ana-R1"))))))

(deftest the-names-that-could-not-be-loaded-leave-the-numbers
  (let [m (model (assoc (fx/settled) :patients fx/failed))]
    (is (= :loaded (get-in m [:active :state])) "the alerts are there")
    (is (= ["Patient pt-ana" "Patient pt-sofia"] (map :name (get-in m [:active :items]))))
    (is (= [{:id :names-failed}] (:notices m)))))

(deftest the-names-are-taken-from-the-patients-the-api-listed-once-each
  (let [twice (assoc-in (fx/settled) [:patients :data :items] (conj (get-in fx/patients [:items]) {:id "pt-ana" :name "Another Ana"}))]
    (is (= "Ana García" (:name (card (model twice) "flag-pt-ana-R1"))) "the first")))

;; ---------------------------------------------------------------------------
;; What is on its way and what could not be loaded

(deftest while-the-names-or-the-alerts-are-on-their-way-the-lists-are-grey-shapes
  (let [before (model (inbox/start fx/now))]
    (is (= {:state :loading} (:active before)))
    (is (= {:state :loading} (:acknowledged before))))
  (testing "the alerts are held until the names are there, so that a card does not change its title as it is read"
    (let [m (model (assoc (fx/settled) :patients fx/loading))]
      (is (= {:state :loading} (:active m)))
      (is (= {:state :loading} (:acknowledged m)))))
  (testing "each list on its own"
    (is (= :loading (get-in (model (assoc (fx/settled) :active fx/loading)) [:active :state])))
    (is (= :loaded (get-in (model (assoc (fx/settled) :active fx/loading)) [:acknowledged :state])))))

(deftest a-list-that-could-not-be-loaded-is-lost-and-never-an-empty-one
  (let [m (model (assoc (fx/settled) :active fx/failed))]
    (is (= {:state :lost} (:active m)))
    (is (= :loaded (get-in m [:acknowledged :state])) "the other is there")
    (is (nil? (:items (:active m))))
    (is (= "The alerts could not be loaded" (:live m))))
  (let [m (model (assoc (fx/settled) :acknowledged fx/failed))]
    (is (= {:state :lost} (:acknowledged m)))
    (is (= :loaded (get-in m [:active :state])))))

(deftest the-readings-behind-an-alert-are-said-as-they-are
  (is (= {:state :loading} (:evidence (card (model (assoc-in (fx/settled) [:evidence ["pt-ana" :blood-pressure]] fx/loading)) "flag-pt-ana-R1"))))
  (is (= {:state :failed} (:evidence (card (model (assoc-in (fx/settled) [:evidence ["pt-ana" :blood-pressure]] fx/failed)) "flag-pt-ana-R1"))))
  (testing "readings that were never asked for are on their way, not none"
    (is (= {:state :loading} (:evidence (card (model (assoc (fx/settled) :evidence {})) "flag-pt-ana-R1")))))
  (testing "and an empty series is no lines, said as loaded"
    (is (= {:state :loaded :lines []}
           (:evidence (card (model (assoc-in (fx/settled) [:evidence ["pt-ana" :blood-pressure]] (fx/loaded fx/empty-series))) "flag-pt-ana-R1"))))))

(deftest a-list-that-is-the-latest-of-more-says-how-many-there-are
  (let [m (model (assoc-in (fx/settled) [:active :data] (fx/page [fx/ana-alert fx/sofia-alert] 130)))]
    (is (= 130 (get-in m [:active :total])))
    (is (= 2 (count (get-in m [:active :items]))))
    (is (= 130 (:count (first (filter #(= :active (:id %)) (:filters m))))))))

(deftest what-the-api-sent-that-is-not-an-alert-is-not-shown
  (let [m (model (assoc-in (fx/settled) [:active :data] (fx/page [nil "x" fx/ana-alert {:text "no id"} fx/ana-alert])))]
    (is (= ["flag-pt-ana-R1"] (map :id (get-in m [:active :items]))) "once each, and only what is an alert")))

;; ---------------------------------------------------------------------------
;; The pills

(deftest the-pills-count-the-alerts-and-mark-the-current-one
  (is (= [{:id :active :label "Active" :count 2 :current? false}
          {:id :acknowledged :label "Acknowledged" :count 1 :current? false}
          {:id :all :label "All" :count 3 :current? true}]
         (:filters (model (fx/settled)))))
  (doseq [show [:active :acknowledged :all]]
    (is (= [show] (map :id (filter :current? (:filters (model (fx/settled) {:show show}))))) (pr-str show)))
  (is (= :all (:show (model (fx/settled)))) "all of them is the default")
  (is (= :active (:show (model (fx/settled) {:show :active})))))

(deftest a-count-is-not-known-until-its-list-is-in
  (let [counts #(map :count (:filters (model %)))]
    (is (= [nil nil nil] (counts (inbox/start fx/now))))
    (is (= [nil 1 nil] (counts (assoc (fx/settled) :active fx/failed))))
    (is (= [2 nil nil] (counts (assoc (fx/settled) :acknowledged fx/failed))))
    (is (= [nil 1 nil] (counts (assoc (fx/settled) :active fx/loading))))
    (is (= [nil nil nil] (counts (assoc (fx/settled) :patients fx/loading))) "while the names are on their way the lists are")))

(deftest the-count-of-all-of-them-is-the-sum
  (let [m (model (-> (fx/settled)
                     (assoc-in [:active :data] (fx/page [fx/ana-alert] 7))
                     (assoc-in [:acknowledged :data] (fx/page [] 5))))]
    (is (= [7 5 12] (map :count (:filters m))))))

;; ---------------------------------------------------------------------------
;; What else the page says

(deftest nothing-is-said-when-all-is-well
  (is (= [] (:notices (model (fx/settled)))))
  (is (true? (:settled? (model (fx/settled)))))
  (is (false? (:settled? (model (inbox/start fx/now))))))

(deftest the-page-says-that-the-alerts-have-changed-when-the-last-look-found-others
  (let [m (model (assoc (fx/settled) :latest {:active (fx/loaded fx/active-with-liam)}))]
    (is (= [{:id :changed}] (:notices m)))
    (is (= "The alerts have changed since you opened this page" (:live m)))
    (is (= (get-in (model (fx/settled)) [:active :items]) (get-in m [:active :items])) "and what it shows is not touched")))

(deftest the-page-says-that-it-could-not-look-for-new-alerts
  (let [m (model (assoc-in (fx/settled) [:latest :active] fx/failed))]
    (is (= [{:id :check-failed}] (:notices m)))
    (is (= "We could not check for new alerts" (:live m)))))

(deftest the-page-says-which-readings-could-not-be-loaded-once-it-has-settled
  (let [lost (-> (fx/settled)
                 (assoc-in [:evidence ["pt-ana" :blood-pressure]] fx/failed)
                 (assoc-in [:evidence ["pt-sofia" :spo2]] fx/failed))]
    (is (= [{:id :evidence-failed :count 2}] (:notices (model lost))))
    (is (= [{:id :evidence-failed :count 1}] (:notices (model (assoc-in (fx/settled) [:evidence ["pt-sofia" :spo2]] fx/failed)))))
    (testing "and not while a call is on its way, when it would be offered a retry it cannot have"
      (is (= [] (:notices (model (assoc lost :inflight #{[:evidence "pt-x" :spo2]}))))))))

(deftest every-notice-there-is-comes-in-the-order-of-its-importance
  (let [everything (-> (fx/settled)
                       (assoc :latest {:active (fx/loaded fx/active-with-liam)})
                       (assoc-in [:latest :acknowledged] fx/failed)
                       (assoc :patients fx/failed)
                       (assoc-in [:evidence ["pt-ana" :blood-pressure]] fx/failed))]
    (is (= [:changed :check-failed :names-failed :evidence-failed] (map :id (:notices (model everything)))))))

(deftest what-a-screen-reader-is-told-says-the-most-urgent-thing
  (is (= "2 alerts need a decision" (:live (model (fx/settled)))))
  (is (= "1 alert needs a decision" (:live (model (assoc-in (fx/settled) [:active :data] (fx/page [fx/ana-alert]))))))
  (is (= "No alert needs a decision" (:live (model (assoc-in (fx/settled) [:active :data] fx/none)))))
  (is (= "Loading the alerts" (:live (model (inbox/start fx/now)))))
  (is (= "The alerts could not be loaded" (:live (model (assoc (fx/settled) :active fx/failed)))))
  (testing "what the person did is said first, and then it gives way"
    (is (= "BP stage 2 · Ana García: Alert acknowledged."
           (:live (model (assoc-in (fx/settled) [:latest :active] (fx/loaded fx/active-with-liam)) {:said "BP stage 2 · Ana García: Alert acknowledged."}))))
    (is (= "2 alerts need a decision" (:live (model (fx/settled) {:said nil}))))))

(deftest the-state-of-an-acknowledgement-goes-with-its-alert
  (let [acks {"flag-pt-ana-R1" {:state :sending :label "BP stage 2 · Ana García"}}
        m (model (fx/settled) {:acks acks})]
    (is (= {:state :sending :label "BP stage 2 · Ana García"} (:ack (card m "flag-pt-ana-R1"))))
    (is (nil? (:ack (card m "flag-pt-sofia-R2"))) "and not with the others")))

(deftest the-inbox-before-it-begins-is-every-part-on-its-way
  (let [m (model inbox/not-begun)]
    (is (= {:state :loading} (:active m)))
    (is (= {:state :loading} (:acknowledged m)))
    (is (= "Loading the alerts" (:live m)))
    (is (= [] (:notices m)))))

(deftest what-someone-else-wrote-is-data-and-not-markup
  (let [hostile "<script>alert(1)</script>"
        m (model (-> (fx/settled)
                     (assoc-in [:patients :data :items 1 :name] hostile)
                     (assoc-in [:active :data] (fx/page [{:id "x" :patient "Patient/pt-ana" :text hostile :status "active"}]))))
        c (card m "x")]
    (is (= hostile (:text c)))
    (is (= (str hostile " · " hostile) (:label c)))))
