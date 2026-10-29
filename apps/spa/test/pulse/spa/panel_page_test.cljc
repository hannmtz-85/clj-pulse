(ns pulse.spa.panel-page-test
  "The panel as a page (ADR-0029): what it says in each of its states, that what could not be loaded is said in its place, that a control
  with nothing to lead to is not there, and that every state passes the checks of accessibility. Pure hiccup, so these run on the JVM
  and on Node, over the same clinic as the tests of the model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.panel :as page]
            [pulse.spa.panel :as panel]
            [pulse.spa.test.panel-fixtures :as fx]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(def ^:private links
  {:panel "/" :filter (fn [show] (str "/?show=" (name show)))})

(defn- content
  ([state] (content state {:show :all :q ""}))
  ([state params] (content state params {}))
  ([state params options] (page/content state params (merge {:links links :handlers {:retry (fn [])}} options))))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- table-of [tree] (first (hx/by-tag tree :table)))

(defn- body-rows [tree] (hx/by-tag (first (hx/by-tag (table-of tree) :tbody)) :tr))

(defn- cells [row] (hx/select row #(#{:th :td} (hx/tag %))))

(defn- button-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :button))))

(defn- link-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :a))))

(defn- a-row [tree n] (map words (cells (nth (body-rows tree) n))))

;; ---------------------------------------------------------------------------
;; The panel that has loaded

(deftest the-panel-has-a-heading-the-figures-the-filters-and-the-patients
  (let [tree (content (fx/settled))]
    (is (= ["Your panel"] (map words (hx/by-tag tree :h1))))
    (is (says? tree "The 5 patients you look after, the most urgent first."))
    (is (= [["Patients" "5"] ["Active alerts" "2" "1 acknowledged today"] ["To confirm" "2" "requests for a visit"]
            ["Away from normal" "3" "outside the normal range"]]
           (for [stat (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4") (not (hx/has-class? % "relative"))))]
             (map words (hx/by-tag stat :p)))))
    (is (= ["All5" "With active alerts2" "Away from normal3" "Normal1"]
           (map words (hx/by-tag (first (hx/select tree #(= "Filter the panel" (:aria-label (hx/attributes %))))) :a))))
    (is (= "Patients of your panel, the most urgent first" (words (first (hx/by-tag (table-of tree) :caption)))))
    (is (= ["Patient" "Status" "Latest reading" "Last 14 days" "Alerts" "Next visit"]
           (map words (hx/select (first (hx/by-tag (table-of tree) :thead)) #(= :th (hx/tag %))))))
    (is (= 5 (count (body-rows tree))))))

(deftest a-row-says-who-the-patient-is-and-what-they-are-like
  (let [tree (content (fx/settled))]
    (is (= ["AGAna GarcíaPULSE-0001 · 58 years" "Very high" "177/100 mmHgBlood pressure, today, 10:00" "" "1 active" "Wed 14 Oct, 10:00–10:30"]
           (a-row tree 0)))
    (is (= ["Sofía RossiPULSE-0003 · 66 years" "Low" "90 %Oxygen saturation, today, 10:00" "" "1 active" "–"]
           (map #(str/replace % #"^SR" "") (a-row tree 1)))
        "a low reading, her alert, and no visit")
    (is (= ["Noah KimPULSE-0005 · 25 years" "No readings yet" "–" "–" "None" "–"]
           (map #(str/replace % #"^NK" "") (a-row tree 4)))
        "a new patient has no reading, no trend and no alert")))

(deftest the-trend-of-a-row-is-a-small-chart-with-a-name
  (let [tree (content (fx/settled))
        svgs (fn [n] (hx/by-tag (nth (cells (nth (body-rows tree) n)) 3) :svg))]
    (is (= "Blood pressure over the last 14 days" (:aria-label (hx/attributes (first (svgs 0))))))
    (is (= "Oxygen saturation over the last 14 days" (:aria-label (hx/attributes (first (svgs 1))))))
    (is (empty? (svgs 4)) "a patient with no reading has none")))

(deftest the-name-of-a-patient-is-a-link-only-when-there-is-a-page-to-go-to
  (let [bare (content (fx/settled))
        linked (content (fx/settled) {:show :all :q ""} {:links (assoc links :patient (fn [id] (str "/patients/" id)))})
        first-cell (fn [tree] (first (cells (first (body-rows tree)))))]
    (is (empty? (hx/by-tag (first-cell bare) :a)) "the chart of a patient is not a page of the workspace yet")
    (is (= ["/patients/pt-ana"] (map (comp :href hx/attributes) (hx/by-tag (first-cell linked) :a))))
    (is (empty? (hx/by-tag (table-of bare) :a)))))

(deftest the-reading-does-not-break-in-the-middle-of-its-number-and-its-unit
  (let [tree (content (fx/settled))]
    (is (every? #(hx/has-class? (first (hx/by-tag (nth (cells %) 2) :p)) "whitespace-nowrap") (take 4 (body-rows tree))))
    (is (every? #(hx/has-class? (first (cells %)) "whitespace-nowrap") (body-rows tree)))
    (is (every? #(= [:th "row"] ((juxt hx/tag (comp :scope hx/attributes)) (first (cells %)))) (body-rows tree))
        "the patient heads the row, for a screen reader")))

(deftest a-narrow-screen-leaves-out-the-next-visit
  (let [tree (content (fx/settled))
        header (last (hx/select (first (hx/by-tag (table-of tree) :thead)) #(= :th (hx/tag %))))]
    (is (hx/has-class? header "hidden"))
    (is (hx/has-class? header "xl:table-cell"))
    (is (every? #(hx/has-class? (last (cells %)) "hidden") (body-rows tree)))))

(deftest the-screen-reader-is-told-what-happened-to-the-panel
  (let [status (fn [tree] (first (hx/select tree #(= "status" (:role (hx/attributes %))))))]
    (is (= "Your panel is loaded: 5 patients, the most urgent first" (words (status (content (fx/settled))))))
    (is (hx/has-class? (status (content (fx/settled))) "sr-only") "and it is not shown")
    (is (= "Loading your panel" (words (status (content nil)))))
    (is (= "Your panel could not be loaded" (words (status (content (assoc (fx/settled) :list fx/failed :rows {}))))))))

(deftest the-administrator-has-all-of-the-patients
  (let [tree (content (fx/settled) {:show :all :q ""} {:admin? true})]
    (is (= ["All patients"] (map words (hx/by-tag tree :h1))))
    (is (says? tree "The 5 patients of the clinic, the most urgent first."))
    (is (= "Patients of the clinic, the most urgent first" (words (first (hx/by-tag (table-of tree) :caption)))))))

;; ---------------------------------------------------------------------------
;; Narrowed

(deftest the-filters-are-links-and-the-current-one-is-marked
  (let [tree (content (fx/settled) {:show :alerts :q ""})
        pills (hx/by-tag (first (hx/select tree #(= "Filter the panel" (:aria-label (hx/attributes %))))) :a)]
    (is (= ["/?show=all" "/?show=alerts" "/?show=away" "/?show=normal"] (map (comp :href hx/attributes) pills)))
    (is (= ["With active alerts2"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) pills)))
    (is (= 2 (count (body-rows tree))) "and the table is narrowed to it")
    (is (= "Patients of your panel, the most urgent first" (words (first (hx/by-tag (table-of tree) :caption)))))))

(deftest there-are-no-filters-when-there-is-nowhere-for-them-to-lead
  (let [tree (content (fx/settled) {:show :all :q ""} {:links {:panel "/"}})]
    (is (empty? (hx/select tree #(= "Filter the panel" (:aria-label (hx/attributes %))))))
    (is (seq (body-rows tree)) "the panel is there all the same")))

(deftest nobody-found-is-said-with-a-way-back
  (let [tree (content (fx/settled) {:show :all :q "zzz"})]
    (is (empty? (hx/by-tag tree :table)) "no table of nobody")
    (is (says? tree "No patients match"))
    (is (says? tree "Try another word, or another filter."))
    (is (= "/" (:href (hx/attributes (link-named tree "Show all patients"))))))
  (testing "with nowhere to go, no way back"
    (is (nil? (link-named (content (fx/settled) {:show :all :q "zzz"} {:links {}}) "Show all patients")))))

(deftest a-panel-with-no-patients-says-so
  (let [nobody (-> (fx/settled) (assoc-in [:list :data] {:total 0 :offset 0 :limit 100 :items []}) (assoc :rows {} :order []))]
    (is (says? (content nobody) "You have no patients yet"))
    (is (says? (content nobody) "Patients appear here when you are their doctor."))
    (is (says? (content nobody {:show :all :q ""} {:admin? true}) "There are no patients yet"))
    (is (says? (content nobody {:show :all :q ""} {:admin? true}) "Patients appear here when they are added to the clinic's records."))
    (is (empty? (hx/by-tag (content nobody) :table)))))

(deftest the-panel-says-when-it-is-the-first-of-more
  (is (says? (content (assoc-in (fx/settled) [:list :data :total] 250)) "Showing the first 5 of 250 patients"))
  (is (not (says? (content (fx/settled)) "Showing the first"))))

;; ---------------------------------------------------------------------------
;; Before it is all there, and what could not be loaded

(deftest before-the-list-is-back-the-panel-is-three-rows-of-grey-shapes
  (doseq [state [nil (panel/start fx/now)]
          :let [tree (content state)]]
    (is (= 3 (count (body-rows tree))))
    (is (= "Patients, loading" (words (first (hx/by-tag (table-of tree) :caption)))))
    (is (every? #(= "" (words %)) (mapcat cells (body-rows tree))) "shapes, with no words")
    (is (every? #(= "true" (:aria-hidden (hx/attributes (first (hx/by-tag % :div))))) (mapcat cells (body-rows tree)))
        "that a screen reader does not read: the status above them says what is going on")
    (is (empty? (hx/select tree #(= "Filter the panel" (:aria-label (hx/attributes %))))) "and no filters of nothing")
    (is (= ["–" "–" "–" "–"] (for [stat (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4")))]
                               (second (map words (hx/by-tag stat :p))))))))

(deftest a-patient-whose-data-is-on-its-way-is-a-name-and-grey-shapes
  (let [partly (panel/with-order (panel/answer (first (panel/pump (panel/answer (first (panel/pump (panel/start fx/now) 1)) :list {:result :ok :body fx/patients}) 1))
                                               [:summary "pt-ana"] {:result :ok :body (get fx/summaries "pt-ana")}))
        tree (content partly)
        rows (body-rows tree)]
    (is (= 5 (count rows)))
    (is (= ["Liam ChenPULSE-0002 · 46 years" "" "" "" "" ""] (map #(str/replace % #"^LC" "") (a-row tree 0))) "his name, from the list, and nothing else yet")
    (is (= "Ana GarcíaPULSE-0001 · 58 years" (str/replace (first (a-row tree 1)) #"^AG" "")))
    (is (= "Very high" (second (a-row tree 1))) "the row that is back is there already")
    (is (= "Patients of your panel" (words (first (hx/by-tag (table-of tree) :caption)))) "and it is not yet the most urgent first")
    (is (empty? (hx/by-tag tree :button)) "nothing to try again while it loads")))

(deftest a-list-that-could-not-be-loaded-is-a-message-with-a-way-to-try-again
  (let [retry (fn [])
        tree (content (assoc (fx/settled) :list fx/failed :rows {} :order []) {:show :all :q ""} {:handlers {:retry retry}})]
    (is (says? tree "Your panel could not be loaded"))
    (is (says? tree "The problem is on our side, not yours."))
    (is (not (says? tree "The rest of the panel is up to date.")) "all there was is lost")
    (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))
    (is (empty? (hx/by-tag tree :table)) "no table, which would say that there are no patients")
    (is (not (says? tree "You have no patients yet")))
    (is (empty? (hx/select tree #(= "Filter the panel" (:aria-label (hx/attributes %))))))
    (testing "the administrator is told of the patients"
      (is (says? (content (assoc (fx/settled) :list fx/failed :rows {}) {:show :all :q ""} {:admin? true}) "The patients could not be loaded")))
    (testing "and with no function to call there is no button"
      (is (nil? (button-named (content (assoc (fx/settled) :list fx/failed :rows {}) {:show :all :q ""} {:handlers {}}) "Try again"))))))

(deftest patients-that-could-not-be-loaded-are-said-in-their-rows-and-above-them
  (let [retry (fn [])
        panel (-> (fx/settled)
                  (assoc-in [:rows "pt-liam" :summary] fx/failed)
                  (assoc-in [:rows "pt-noah" :summary] {:state :failed :kind :timeout})
                  (assoc :order ["pt-ana" "pt-sofia" "pt-omar" "pt-liam" "pt-noah"]))
        tree (content panel {:show :all :q ""} {:handlers {:retry retry}})]
    (is (says? tree "2 patients could not be loaded"))
    (is (says? tree "The rest of the panel is up to date. The problem is on our side, not yours."))
    (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))
    (is (= ["Liam ChenPULSE-0002 · 46 years" "Not loaded" "Not loaded" "Not loaded" "Not loaded" "Not loaded"]
           (map #(str/replace % #"^LC" "") (a-row tree 3))) "each cell says it, and none says 'none' or a dash")
    (is (= "Not loaded" (second (a-row tree 4))))
    (is (= 1 (count (filter #(= "Try again" (words %)) (hx/by-tag tree :button)))) "one button for all of them")
    (testing "one patient is a patient"
      (is (says? (content (assoc-in panel [:rows "pt-noah" :summary] (fx/loaded (get fx/summaries "pt-noah")))) "1 patient could not be loaded")))))

(deftest figures-that-could-not-be-loaded-are-said-and-are-not-zero
  (let [tree (content (assoc-in (fx/settled) [:inbox :active] fx/failed))]
    (is (says? tree "Some figures could not be loaded"))
    (is (says? tree "Active alerts–Not loaded"))
    (is (some? (button-named tree "Try again")))))

(deftest a-part-of-a-row-that-could-not-be-loaded-is-said-in-its-cell
  (let [lost (fn [& path-and-value] (let [[path value] [(butlast path-and-value) (last path-and-value)]]
                                      (assoc-in (fx/settled) (into [:rows "pt-ana"] path) value)))
        tree (fn [panel] (content panel))]
    (testing "the series"
      (is (= "Not loaded" (nth (a-row (tree (lost :series fx/failed)) 0) 3)))
      (is (= "Very high" (nth (a-row (tree (lost :series fx/failed)) 0) 1)) "the rest of the row is there"))
    (testing "the alerts"
      (is (= "Not loaded" (nth (a-row (tree (lost :summary :data :alerts nil)) 0) 4))))
    (testing "the appointments"
      (is (= "Not loaded" (nth (a-row (tree (lost :summary :data :appointments nil)) 0) 5))))
    (testing "some of the readings: the others are shown, and it is said"
      (let [row (a-row (tree (lost :summary :data :unavailable ["vitals.heart-rate"])) 0)]
        (is (= "177/100 mmHgBlood pressure, today, 10:00Some readings not loaded" (nth row 2)))))
    (testing "all of the readings: there is nothing to show, and it is not 'no readings yet'"
      (let [panel (-> (fx/settled)
                      (assoc-in [:rows "pt-noah" :summary :data :unavailable] ["vitals.blood-pressure" "vitals.glucose"]))
            row (a-row (tree panel) 4)]
        (is (= "Not loaded" (nth row 1)))
        (is (= "Not loaded" (nth row 2)))))
    (testing "no series because there was nothing to draw: it says so"
      (is (= "None in 14 days" (nth (a-row (tree (lost :series :data :items [])) 0) 3))))))

(deftest what-a-patient-is-called-cannot-add-markup-to-the-page
  (let [hostile (-> (fx/settled)
                    (assoc-in [:list :data :items 1 :name] "<script>alert(1)</script>")
                    (assoc-in [:list :data :items 1 :identifier] "<img src=x onerror=alert(1)>"))
        tree (content hostile)]
    (is (empty? (hx/by-tag tree :script)))
    (is (empty? (hx/by-tag tree :img)))
    (is (says? tree "<script>alert(1)</script>") "the text is the text")))

;; ---------------------------------------------------------------------------
;; The workspace around it

(def ^:private states
  {"before it begins" nil
   "the list on its way" (panel/start fx/now)
   "loaded" (fx/settled)
   "a list that failed" (assoc (fx/settled) :list fx/failed :rows {} :order [])
   "patients that failed" (-> (fx/settled) (assoc-in [:rows "pt-liam" :summary] fx/failed) (assoc-in [:rows "pt-noah" :summary] fx/failed))
   "figures that failed" (assoc-in (fx/settled) [:inbox :requests] fx/failed)
   "no patients" (-> (fx/settled) (assoc-in [:list :data] {:total 0 :offset 0 :limit 100 :items []}) (assoc :rows {} :order []))
   "the first of more" (assoc-in (fx/settled) [:list :data :total] 250)})

(deftest every-state-of-the-panel-passes-the-checks-of-accessibility
  (doseq [[label state] states
          params [{:show :all :q ""} {:show :alerts :q ""} {:show :all :q "zzz"}]
          admin? [false true]
          :let [person (if admin? sam rivera)
                tree (page/page state params {:person person :links links :handlers {:retry (fn []) :sign-out (fn []) :search (fn [_])}})]]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} tree]) {:page? true})) (str label " " (pr-str params) " " admin?))))

(deftest the-workspace-around-the-panel-has-the-search-box-when-it-can-be-used
  (let [search (fn [_])
        tree (page/page (fx/settled) {:show :all :q "ana"} {:person rivera :links links :handlers {:search search}})
        input (first (hx/by-tag tree :input))]
    (is (= "search" (:type (hx/attributes input))))
    (is (= "ana" (:value (hx/attributes input))) "what was typed is in the box, from the address")
    (is (= search (:on-change (hx/attributes input))))
    (is (= "Search patients" (:aria-label (hx/attributes input)))))
  (testing "and not when there is nothing to call"
    (is (empty? (hx/by-tag (page/page (fx/settled) {:show :all :q ""} {:person rivera :links links}) :input)))))

(deftest the-sidebar-has-the-panel-as-the-current-page
  (let [tree (page/page (fx/settled) {:show :all :q ""} {:person rivera :links links})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))
        current (first (hx/select nav #(= "page" (:aria-current (hx/attributes %)))))]
    (is (= "Panel" (words current)))
    (is (= "/" (:href (hx/attributes current))))
    (is (= ["Panel"] (map words (hx/by-tag nav :a))) "and no page that is not there")))
