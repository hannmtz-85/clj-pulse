(ns pulse.spa.panel-model-test
  "What the panel shows (ADR-0029, point 6), from its state: the figures, the filters and their counts, the rows narrowed by a filter and
  a search, what could not be loaded and when it is said, and the words a screen reader is told. These run on the JVM and on Node over
  the same clinic (`pulse.spa.test.panel-fixtures`)."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.panel :as panel]
            [pulse.spa.test.panel-fixtures :as fx]))

(defn- model
  ([panel] (model panel {}))
  ([panel params] (model panel params false))
  ([panel params admin?] (panel/model panel params admin?)))

(defn- ids [m] (mapv :id (:rows m)))

(defn- stat [m id] (first (filter #(= id (:id %)) (:stats m))))

(defn- counts [m] (mapv :count (:filters m)))

(deftest the-panel-of-a-practitioner-says-who-it-is-for-and-how-many
  (let [m (model (fx/settled))]
    (is (= "Your panel" (:title m)))
    (is (= "The 5 patients you look after, the most urgent first." (:description m)))
    (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (ids m)))
    (is (true? (:settled? m)))
    (is (= [] (:notices m)))
    (is (nil? (:empty m)))
    (is (nil? (:footer m)))
    (is (= "Your panel is loaded: 5 patients, the most urgent first" (:live m)))))

(deftest the-panel-of-the-administrator-is-the-clinics
  (let [m (model (fx/settled) {} true)]
    (is (= "All patients" (:title m)))
    (is (= "The 5 patients of the clinic, the most urgent first." (:description m)))
    (is (= "The patients are loaded: 5 patients, the most urgent first" (:live m)))
    (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (ids m)) "the same rows")))

(deftest the-description-is-grammatical-for-one-patient
  (let [one (-> (fx/settled)
                (assoc-in [:list :data :items] [(first (:items fx/patients))])
                (assoc-in [:list :data :total] 1)
                (assoc :rows {"pt-liam" (get (fx/rows) "pt-liam")} :order ["pt-liam"]))]
    (is (= "The patient you look after." (:description (model one))))
    (is (= "The patient of the clinic." (:description (model one {} true))))
    (is (= "Your panel is loaded: 1 patient" (:live (model one))))))

(deftest the-four-figures-are-what-the-api-and-the-rows-say
  (let [m (model (fx/settled))]
    (is (= [{:id :patients :label "Patients" :icon :users :value "5"}
            {:id :alerts :label "Active alerts" :icon :bell-ring :value "2" :tone :critical :hint "1 acknowledged today"}
            {:id :requests :label "To confirm" :icon :calendar-clock :value "2" :hint "requests for a visit"}
            {:id :away :label "Away from normal" :icon :triangle-alert :value "3" :hint "outside the normal range"}]
           (:stats m)))
    (testing "the alerts are in the colour of what needs attention only when there are some"
      (is (nil? (:tone (stat (model (assoc-in (fx/settled) [:inbox :active :data :total] 0)) :alerts)))))
    (testing "an alert acknowledged yesterday is not acknowledged today"
      (let [panel (assoc-in (fx/settled) [:inbox :acknowledged :data :items 0 :until] "2026-10-12T10:00:00Z")]
        (is (= "0 acknowledged today" (:hint (stat (model panel) :alerts))))))
    (testing "the day is the day in Madrid: 22:30 UTC the night before is already today"
      (let [panel (assoc-in (fx/settled) [:inbox :acknowledged :data :items 0 :until] "2026-10-12T22:30:00Z")]
        (is (= "1 acknowledged today" (:hint (stat (model panel) :alerts))))))
    (testing "an acknowledgement with no time that can be read is not counted"
      (let [panel (assoc-in (fx/settled) [:inbox :acknowledged :data :items] [{:id "x" :until "soon"} {:id "y"} {:id "z" :until nil}])]
        (is (= "0 acknowledged today" (:hint (stat (model panel) :alerts))))))))

(deftest the-filters-count-the-patients-they-narrow-to
  (let [m (model (fx/settled))]
    (is (= [:all :alerts :away :normal] (map :id (:filters m))))
    (is (= ["All" "With active alerts" "Away from normal" "Normal"] (map :label (:filters m))))
    (is (= [5 2 3 1] (counts m)) "Ana and Sofía have alerts; Ana, Sofía and Liam are away from normal; Omar is normal; Noah has no reading")
    (is (= [true false false false] (map :current? (:filters m))))
    (testing "the one that is current is the one asked for"
      (is (= [false true false false] (map :current? (:filters (model (fx/settled) {:show :alerts})))))
      (is (= [false false true false] (map :current? (:filters (model (fx/settled) {:show :away})))))
      (is (= [false false false true] (map :current? (:filters (model (fx/settled) {:show :normal})))))
      (is (= [false false false false] (map :current? (:filters (model (fx/settled) {:show :nonsense})))) "none, for what is none"))))

(deftest a-filter-narrows-the-rows-and-keeps-their-order
  (is (= ["pt-ana" "pt-sofia"] (ids (model (fx/settled) {:show :alerts}))))
  (is (= ["pt-ana" "pt-sofia" "pt-liam"] (ids (model (fx/settled) {:show :away}))))
  (is (= ["pt-omar"] (ids (model (fx/settled) {:show :normal}))) "a patient with no reading is not normal: nothing says so")
  (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (ids (model (fx/settled) {:show :all}))))
  (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (ids (model (fx/settled) {:show :nonsense}))) "what is none is all"))

(deftest a-search-narrows-the-rows-too-and-works-with-a-filter
  (is (= ["pt-ana"] (ids (model (fx/settled) {:q "garcia"}))))
  (is (= ["pt-sofia"] (ids (model (fx/settled) {:q "SOFIA"}))))
  (is (= ["pt-omar"] (ids (model (fx/settled) {:q "pulse-0004"}))))
  (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (ids (model (fx/settled) {:q "pulse"}))) "all their numbers have it")
  (is (= ["pt-ana" "pt-sofia"] (ids (model (fx/settled) {:q "a" :show :alerts}))) "any letter that is in the name, or in the number")
  (is (= [] (ids (model (fx/settled) {:q "liam" :show :alerts}))) "Liam has no alert")
  (testing "the counts of the filters are those of the patients, and not of what the search leaves"
    (is (= [5 2 3 1] (counts (model (fx/settled) {:q "garcia"}))))))

(deftest a-search-or-a-filter-that-finds-nobody-is-told-so-and-is-not-an-empty-panel
  (let [m (model (fx/settled) {:q "zzz"})]
    (is (= [] (ids m)))
    (is (= :no-match (:empty m)))
    (is (nil? (:footer m))))
  (is (= :no-match (:empty (model (fx/settled) {:show :alerts :q "omar"}))))
  (is (nil? (:empty (model (fx/settled) {:q "ana"})))))

(deftest a-panel-with-no-patients-says-so
  (let [empty-panel (-> (fx/settled)
                        (assoc-in [:list :data] {:total 0 :offset 0 :limit 100 :items []})
                        (assoc :rows {} :order []))
        m (model empty-panel)]
    (is (= :no-patients (:empty m)))
    (is (nil? (:description m)))
    (is (= "Your panel is loaded: 0 patients" (:live m)))
    (is (= [0 0 0 0] (counts m)) "none is none, and not unknown")))

(deftest the-panel-says-when-it-is-the-first-of-more
  (let [more (assoc-in (fx/settled) [:list :data :total] 250)]
    (is (= "Showing the first 5 of 250 patients" (:footer (model more))))
    (is (= "Showing the first 5 of 250 patients" (:footer (model more {:q "ana"}))))
    (is (nil? (:footer (model (fx/settled)))) "nothing to say when it is all of them")))

;; ---------------------------------------------------------------------------
;; Before it is all there

(deftest before-the-panel-begins-it-is-loading-and-has-nothing
  (let [m (model nil)]
    (is (= "Your panel" (:title m)))
    (is (nil? (:description m)))
    (is (= [] (:rows m)))
    (is (= [] (:notices m)))
    (is (nil? (:empty m)))
    (is (false? (:settled? m)))
    (is (= "Loading your panel" (:live m)))
    (is (= ["–" "–" "–" "–"] (map :value (:stats m))))
    (is (= ["Loading" "Loading" "Loading" "Loading"] (map :hint (:stats m))))
    (is (= [nil nil nil nil] (counts m)))
    (is (= "Loading the patients" (:live (model nil {} true))))))

(defn- after-the-list-for-model []
  (first (panel/pump (panel/answer (first (panel/pump (panel/start fx/now) 1)) :list {:result :ok :body fx/patients}) 1)))

(deftest while-the-panel-loads-the-rows-are-where-the-list-had-them-and-the-counts-are-not-guessed
  (let [partly (panel/answer (after-the-list-for-model) [:summary "pt-ana"] {:result :ok :body (get fx/summaries "pt-ana")})
        m (model partly)]
    (is (false? (:settled? m)))
    (is (= ["pt-liam" "pt-ana" "pt-omar" "pt-noah" "pt-sofia"] (ids m)))
    (is (= [:loading :loaded :loading :loading :loading] (map :state (:rows m))))
    (is (= [5 nil nil nil] (counts m)) "all of them are known, and the rest are not, yet")
    (is (= "Loading your panel" (:live m)))
    (is (= {:id :away :label "Away from normal" :icon :triangle-alert :value "–" :hint "Loading"} (stat m :away)))
    (is (= [] (:notices m)) "nothing is said to have failed while it is on its way")
    (testing "the figures that have come are there"
      (is (= "5" (:value (stat m :patients)))))))

;; ---------------------------------------------------------------------------
;; What could not be loaded

(deftest a-list-that-could-not-be-loaded-is-said-and-is-not-an-empty-panel
  (let [m (model (-> (fx/settled) (assoc :list fx/failed :rows {} :order [])))]
    (is (= [{:id :list-failed}] (:notices m)))
    (is (= [] (:rows m)))
    (is (nil? (:empty m)) "it is not that there are no patients")
    (is (nil? (:description m)))
    (is (= "Your panel could not be loaded" (:live m)))
    (is (= "The patients could not be loaded" (:live (model (assoc (fx/settled) :list fx/failed :rows {}) {} true))))
    (is (= {:id :patients :label "Patients" :icon :users :value "–" :hint "Not loaded"} (stat m :patients)))
    (is (= {:id :away :label "Away from normal" :icon :triangle-alert :value "–" :hint "Not loaded"} (stat m :away))
        "and no count of the patients that are away from normal, since there are no patients to count")
    (is (nil? (:count (first (:filters m)))))
    (testing "the figures that did come are there all the same"
      (is (= "2" (:value (stat m :alerts)))))))

(deftest patients-that-could-not-be-loaded-are-said-once-the-panel-has-settled-and-the-counts-are-not-given
  (let [panel (-> (fx/settled)
                  (assoc-in [:rows "pt-liam" :summary] fx/failed)
                  (assoc-in [:rows "pt-noah" :summary] {:state :failed :kind :timeout})
                  (assoc :order ["pt-ana" "pt-sofia" "pt-omar" "pt-liam" "pt-noah"]))
        m (model panel)]
    (is (= [{:id :rows-failed :count 2}] (:notices m)))
    (is (= ["pt-ana" "pt-sofia" "pt-omar" "pt-liam" "pt-noah"] (ids m)) "in the order they were put in")
    (is (= [5 nil nil nil] (counts m)) "how many are away from normal is not known: some could not be loaded")
    (is (= {:id :away :label "Away from normal" :icon :triangle-alert :value "2" :hint "2 patients not loaded"} (stat m :away))
        "the ones that could be told")
    (is (= "Your panel is loaded: 5 patients, the most urgent first. 2 patients could not be loaded" (:live m)))
    (is (= [:failed :failed] (map :state (take-last 2 (:rows m)))))
    (testing "one patient is not 'patients'"
      (let [one-less (assoc-in panel [:rows "pt-noah" :summary] (fx/loaded (get fx/summaries "pt-noah")))]
        (is (= "1 patient not loaded" (:hint (stat (model one-less) :away))))
        (is (= [{:id :rows-failed :count 1}] (:notices (model one-less))))))
    (testing "a patient who could not be loaded is among 'all', and in no other filter"
      (is (= ["pt-ana" "pt-sofia" "pt-omar" "pt-liam" "pt-noah"] (ids (model panel {:show :all}))))
      (is (= ["pt-ana" "pt-sofia"] (ids (model panel {:show :alerts}))))
      (is (= ["pt-ana" "pt-sofia"] (ids (model panel {:show :away}))))
      (is (= ["pt-omar"] (ids (model panel {:show :normal}))))
      (is (= ["pt-liam"] (ids (model panel {:q "chen"}))) "but a search finds them by their name"))))

(deftest figures-that-could-not-be-loaded-are-said-and-are-not-zero
  (let [m (model (assoc-in (fx/settled) [:inbox :active] fx/failed))]
    (is (= [{:id :figures-failed}] (:notices m)))
    (is (= {:id :alerts :label "Active alerts" :icon :bell-ring :value "–" :hint "Not loaded"} (stat m :alerts)) "no tone either")
    (is (= "2" (:value (stat m :requests)))))
  (is (= {:id :requests :label "To confirm" :icon :calendar-clock :value "–" :hint "Not loaded"}
         (stat (model (assoc-in (fx/settled) [:inbox :requests] fx/failed)) :requests)))
  (testing "the alerts acknowledged today are not needed for the alerts, and what is missing is said"
    (let [m (model (assoc-in (fx/settled) [:inbox :acknowledged] fx/failed))]
      (is (= [{:id :figures-failed}] (:notices m)))
      (is (= "Acknowledged today: not loaded" (:hint (stat m :alerts))))
      (is (= "2" (:value (stat m :alerts))))))
  (testing "nothing is said of a failure while a call is still on its way"
    (let [m (model (-> (fx/settled) (assoc-in [:inbox :active] fx/failed) (assoc-in [:inbox :requests] fx/loading)))]
      (is (= [] (:notices m)))
      (is (false? (:settled? m))))))

(deftest a-part-of-the-panel-that-failed-and-another-that-is-on-its-way-leave-no-notice-until-it-settles
  (let [m (model (assoc-in (fx/settled) [:rows "pt-ana" :summary] fx/failed))]
    (is (= [{:id :rows-failed :count 1}] (:notices m))))
  (let [m (model (-> (fx/settled) (assoc-in [:rows "pt-ana" :summary] fx/failed) (assoc-in [:rows "pt-liam" :summary] fx/loading)))]
    (is (= [] (:notices m)))))
