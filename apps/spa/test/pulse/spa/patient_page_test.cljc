(ns pulse.spa.patient-page-test
  "The chart of a patient as a page (ADR-0029, point 6): what it says in each of its states, that what could not be loaded is said in
  its place and is never shown as empty, that a patient the api refuses shows nothing else, that a control with nothing to lead to is
  not there, and that every state passes the checks of accessibility. Pure hiccup, so these run on the JVM and on Node, over the
  same data as the tests of the machine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.patient :as page]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.patient-fixtures :as fx]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(defn- chart-address
  "Where the pills lead in these tests: the address of the chart, as `pulse.spa.route/patient-path` writes it for the patient of the
  fixtures, but for the defaults."
  [{:keys [measurement days]}]
  (str "/patients/pt-ana?days=" days "&measurement=" (name measurement)))

(def ^:private links {:panel "/" :chart chart-address})

(defn- content
  ([state] (content state {}))
  ([state options] (page/content state (merge {:links links :handlers {:retry (fn [])}} options))))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- button-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :button))))

(defn- link-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :a))))

(defn- card-named
  "The card (a section) of the page whose title is `title`."
  [tree title]
  (first (filter #(= title (words (first (hx/by-tag % :h2)))) (hx/by-tag tree :section))))

(defn- drawings
  "The drawings of charts in `tree`: the `svg`s that are images, and not the icons."
  [tree]
  (hx/select tree #(and (= :svg (hx/tag %)) (= "img" (:role (hx/attributes %))))))

(defn- pills [tree label]
  (hx/by-tag (first (hx/select tree #(= label (:aria-label (hx/attributes %))))) :a))

;; ---------------------------------------------------------------------------
;; The chart of a patient that has loaded

(deftest the-chart-says-who-the-patient-is
  (let [tree (content (fx/settled-ana))]
    (is (= ["Ana García"] (map words (hx/by-tag tree :h1))) "the one heading of the page")
    (is (says? tree "58 years · Female · PULSE-0001 · Madrid"))
    (is (= ["Panel" "Ana García"] (map words (hx/by-tag (first (hx/select tree #(= "Breadcrumb" (:aria-label (hx/attributes %))))) :li))))
    (is (= "/" (:href (hx/attributes (link-named tree "Panel")))) "and the way back")
    (is (= ["Essential (primary) hypertension · I10"] (map words (hx/by-tag (first (hx/select tree #(= "Conditions" (:aria-label (hx/attributes %))))) :li))))))

(deftest an-active-alert-says-what-the-rule-is-and-the-readings-behind-it
  (let [tree (content (fx/settled-ana))]
    (is (says? tree "BP stage 2"))
    (is (says? tree "Raised yesterday, 22:30. Blood pressure at stage 2 (a systolic of 160 or more, or a diastolic of 100 or more) in at least 2 of the last 3 readings of the last 3 days."))
    (is (says? tree "The latest readings: 177/100 mmHg, today, 10:00; 172/100 mmHg, yesterday, 10:00; 165/98 mmHg, Oct 11, 10:00."))
    (testing "and no way to acknowledge it, which is the next step of the phase"
      (is (nil? (button-named tree "Acknowledge")))
      (is (nil? (link-named tree "Acknowledge")))
      (is (nil? (link-named tree "All alerts"))))))

(deftest the-latest-readings-are-tiles-in-the-order-the-measurements-are-shown
  (let [tree (content (fx/settled-ana))
        section (first (filter #(= "vitals" (:aria-labelledby (hx/attributes %))) (hx/by-tag tree :section)))]
    (is (= "Latest readings" (words (first (hx/by-tag section :h2)))))
    (is (says? section "Blood pressure"))
    (is (says? section "177/100"))
    (is (says? section "Heart rate"))
    (is (says? section "74"))
    (is (not (says? section "Glucose")) "a measurement the patient has no reading of has no tile")))

(deftest the-chart-of-the-measurement-has-the-readings-the-thresholds-and-a-name
  (let [tree (content (fx/settled-ana))
        card (card-named tree "Blood pressure")
        svgs (drawings card)]
    (is (some? card))
    (is (says? card "Last 14 days"))
    (is (= 2 (count svgs)) "one for a desk and one for a phone")
    (is (= #{"chart-blood-pressure-wide-title chart-blood-pressure-wide-desc" "chart-blood-pressure-narrow-title chart-blood-pressure-narrow-desc"}
           (set (map (comp :aria-labelledby hx/attributes) svgs)))
        "each drawing has a title and a description of its own")
    (is (says? card "Stage 2 · 160") "the thresholds, named")
    (is (says? card "Blood pressure, last 14 days"))))

(deftest a-chart-for-a-phone-and-one-for-a-desk-are-shown-each-where-it-fits
  (let [card (card-named (content (fx/settled-ana)) "Blood pressure")
        shown (fn [klass] (filter #(hx/has-class? % klass) (hx/by-tag card :div)))]
    (is (seq (shown "md:hidden")))
    (is (seq (shown "md:block")))))

(deftest the-record-and-the-appointments-are-on-the-page
  (let [tree (content (fx/settled-ana))
        medication (card-named tree "Medication")
        allergies (card-named tree "Allergies")
        appointments (card-named tree "Appointments")]
    (is (= ["Lisinopril10 mg once daily"] (map words (hx/by-tag medication :li))) "the name as the clinic wrote it, with a capital")
    (is (= ["PenicillinRash"] (map words (hx/by-tag allergies :li))))
    (is (= 2 (count (hx/by-tag appointments :li))))
    (is (says? appointments "Hypertension follow-up"))
    (is (says? appointments "Wed 14 Oct, 10:00–10:30"))
    (is (says? appointments "Confirmed"))
    (is (says? appointments "Blood pressure check"))
    (is (says? appointments "Cancelled"))
    (testing "the buttons to confirm, move and cancel are not there until they have a page"
      (is (empty? (hx/by-tag appointments :button)))
      (is (empty? (hx/by-tag appointments :a))))))

(deftest the-nothing-recorded-is-said
  (let [tree (content (fx/settled-sofia))]
    (is (says? (card-named tree "Allergies") "None recorded."))
    (is (says? (card-named tree "Medication") "None."))
    (is (says? (card-named tree "Appointments") "None."))))

;; ---------------------------------------------------------------------------
;; The pills

(deftest the-pills-choose-the-measurement-and-the-period
  (let [tree (content (fx/settled-ana))]
    (is (= ["Blood pressure" "Heart rate"] (map words (pills tree "Measurement"))))
    (is (= ["/patients/pt-ana?days=14&measurement=blood-pressure" "/patients/pt-ana?days=14&measurement=heart-rate"]
           (map (comp :href hx/attributes) (pills tree "Measurement"))) "each keeps the period")
    (is (= ["7 days" "14 days" "30 days"] (map words (pills tree "Period"))))
    (is (= ["/patients/pt-ana?days=7&measurement=blood-pressure" "/patients/pt-ana?days=14&measurement=blood-pressure"
            "/patients/pt-ana?days=30&measurement=blood-pressure"]
           (map (comp :href hx/attributes) (pills tree "Period"))) "each keeps the measurement")
    (is (= ["Blood pressure"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (pills tree "Measurement"))))
    (is (= ["14 days"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (pills tree "Period"))))))

(deftest the-current-choice-is-the-one-the-chart-is-of
  (let [tree (content (fx/settled-ana {:asked :heart-rate :days 30}))]
    (is (= ["Heart rate"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (pills tree "Measurement"))))
    (is (= ["30 days"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (pills tree "Period"))))
    (is (says? (card-named tree "Heart rate") "Last 30 days"))))

(deftest there-are-no-pills-when-there-is-nowhere-for-them-to-lead
  (let [tree (content (fx/settled-ana) {:links {:panel "/"}})]
    (is (empty? (pills tree "Measurement")))
    (is (empty? (pills tree "Period")))
    (is (some? (card-named tree "Blood pressure")) "the chart is there all the same")))

(deftest the-pills-stay-while-a-chart-is-on-its-way-so-that-another-can-be-chosen
  (let [tree (content (assoc-in (fx/settled-ana) [:series :state] :loading))]
    (is (seq (pills tree "Period")))
    (is (seq (pills tree "Measurement")))
    (is (empty? (drawings (card-named tree "Blood pressure"))) "and no chart yet: grey shapes")))

;; ---------------------------------------------------------------------------
;; A chart with nothing to draw, or too much

(deftest a-period-with-no-reading-says-so-and-leaves-the-choices
  (let [tree (content (assoc-in (fx/settled-ana) [:series :data] fx/empty-series))]
    (is (says? tree "No blood pressure readings in the last 14 days"))
    (is (empty? (drawings (card-named tree "Blood pressure"))))
    (is (seq (pills tree "Period")) "so that another period can be chosen")))

(deftest readings-that-say-nothing-are-no-readings
  (let [tree (content (assoc-in (fx/settled-ana) [:series :data] {:items [{:id "x" :at "2026-10-13T08:00:00Z" :systolic nil :diastolic nil}] :truncated false}))]
    (is (says? tree "No blood pressure readings in the last 14 days"))))

(deftest a-series-that-was-cut-says-so
  (let [cut (assoc-in (fx/settled-ana) [:series :data :truncated] true)
        tree (content cut)]
    (is (says? tree "Latest 5 readings, since Oct 9, 10:00"))
    (is (not (says? tree "Last 14 days")) "the chart does not claim the days it did not get")
    (is (says? tree "Blood pressure over the latest 5 readings"))))

(deftest a-chart-that-could-not-be-loaded-is-a-message-with-a-way-to-try-again
  (let [retry (fn [])
        tree (content (assoc (fx/settled-ana) :series {:state :failed :kind :timeout :measurement :blood-pressure :days 14}) {:handlers {:retry retry}})]
    (is (says? tree "The blood pressure chart could not be loaded"))
    (is (says? tree "The problem is on our side, not yours."))
    (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))
    (is (empty? (drawings (card-named tree "Blood pressure"))))
    (is (not (says? tree "No blood pressure readings")) "it is not 'none'")
    (is (seq (pills tree "Period")) "and another can be chosen")))

(deftest a-patient-with-nothing-measured-has-nothing-to-draw-and-is-told-so
  (let [tree (content (fx/settled-noah))]
    (is (says? tree "No readings yet"))
    (is (says? tree "When the patient records one, or a device sends one, you will see it here."))
    (is (empty? (drawings tree)))
    (is (empty? (pills tree "Measurement")))
    (is (not-any? #(= "vitals" (:aria-labelledby (hx/attributes %))) (hx/by-tag tree :section)) "and no tiles of nothing")
    (is (says? tree "25 years · Male · PULSE-0005"))
    (is (says? tree "No conditions recorded."))))

;; ---------------------------------------------------------------------------
;; Before it is all there, and what could not be loaded

(deftest before-the-chart-begins-it-is-a-title-and-grey-shapes
  (doseq [state [nil (patient/start fx/now {:id "pt-ana" :measurement nil :days 14})]
          :let [tree (content state)]]
    (is (= ["Patient"] (map words (hx/by-tag tree :h1))))
    (is (= "Loading the chart of the patient" (words (first (hx/select tree #(= "status" (:role (hx/attributes %))))))))
    (is (seq (hx/select tree #(hx/has-class? % "skeleton"))))
    (is (empty? (drawings tree)))
    (is (empty? (hx/by-tag tree :button)) "nothing to try again while it loads")
    (is (not (says? tree "None")) "no list says it is empty before it is known")
    (is (not (says? tree "No conditions recorded.")))))

(deftest a-header-that-could-not-be-loaded-is-said-and-the-rest-is-shown
  (let [retry (fn [])
        tree (content (assoc (fx/settled-ana) :patient {:state :failed :kind :server}) {:handlers {:retry retry}})]
    (is (says? tree "The patient could not be loaded"))
    (is (= ["Patient"] (map words (hx/by-tag tree :h1))))
    (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))
    (is (some? (card-named tree "Blood pressure")) "what did load is there")
    (is (says? tree "BP stage 2"))))

(deftest a-list-that-could-not-be-loaded-is-a-message-in-its-place-and-not-an-empty-list
  (let [retry (fn [])
        lose (fn [key]
               (content (assoc-in (fx/settled-ana) [:summary :data] (-> fx/ana-summary (assoc key nil) (assoc :unavailable [(name key)])))
                        {:handlers {:retry retry}}))]
    (doseq [[key message empty-text] [[:medications "The medication could not be loaded" "None."]
                                      [:allergies "The allergies could not be loaded" "None recorded."]
                                      [:appointments "The appointments could not be loaded" "None."]
                                      [:conditions "The conditions could not be loaded" "No conditions recorded."]
                                      [:alerts "The alerts could not be loaded" "None"]]
            :let [tree (lose key)]]
      (testing (name key)
        (is (says? tree message))
        (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))
        (is (not (says? tree empty-text)) "it does not say that there is nothing")))
    (testing "an alert that could not be loaded is not 'no alerts'"
      (is (not (says? (lose :alerts) "BP stage 2"))))
    (testing "and the rest is there"
      (is (some? (card-named (lose :medications) "Allergies")))
      (is (some? (card-named (lose :medications) "Blood pressure"))))))

(deftest the-latest-readings-that-could-not-be-loaded-are-said
  (let [no-section (content (assoc-in (fx/settled-ana) [:summary :data :vitals] nil))
        no-summary (content (assoc (fx/settled-ana) :summary {:state :failed :kind :server}))
        some-lost (content (assoc-in (fx/settled-ana) [:summary :data :unavailable] ["vitals.glucose"]))]
    (is (says? no-section "The latest readings could not be loaded"))
    (is (says? no-summary "The latest readings could not be loaded"))
    (is (says? some-lost "Some readings could not be loaded"))
    (is (says? some-lost "177/100") "and the others are shown")))

(deftest a-summary-that-could-not-be-loaded-leaves-the-header-and-the-chart
  (let [tree (content (-> (fx/settled-ana) (assoc :summary {:state :failed :kind :server})))]
    (is (= ["Ana García"] (map words (hx/by-tag tree :h1))))
    (is (some? (card-named tree "Blood pressure")))
    (is (says? tree "The alerts could not be loaded"))
    (is (says? tree "The medication could not be loaded"))
    (is (says? tree "The allergies could not be loaded"))
    (is (says? tree "The appointments could not be loaded"))
    (is (says? tree "The conditions could not be loaded"))
    (is (says? tree "The latest readings could not be loaded"))))

(deftest the-readings-behind-an-alert-that-could-not-be-loaded-are-said-with-a-way-to-try-again
  (let [retry (fn [])
        tree (content (assoc-in (fx/settled-ana) [:evidence :blood-pressure] {:state :failed :kind :server}) {:handlers {:retry retry}})]
    (is (says? tree "BP stage 2"))
    (is (says? tree "The readings behind it could not be loaded."))
    (is (not (says? tree "The latest readings:")))
    (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))))

(deftest the-readings-behind-an-alert-that-are-on-their-way-are-not-claimed-to-be-none
  (let [tree (content (assoc-in (fx/settled-ana) [:evidence :blood-pressure] {:state :loading}))]
    (is (says? tree "BP stage 2"))
    (is (not (says? tree "The latest readings:")))
    (is (not (says? tree "could not be loaded")))))

(deftest an-alert-of-a-rule-nobody-knows-has-its-text-and-no-more
  (let [state (assoc-in (fx/settled-ana) [:summary :data :alerts] [{:id "x" :text "Something else" :status "active" :since "2026-10-12T20:30:00Z"}])
        tree (content state)]
    (is (says? tree "Something else"))
    (is (says? tree "Raised yesterday, 22:30."))
    (is (not (says? tree "Blood pressure at stage 2")))
    (is (not (says? tree "The latest readings:")))))

(deftest there-are-no-buttons-to-try-again-when-there-is-nothing-to-call
  (let [lost (assoc (fx/settled-ana) :summary {:state :failed :kind :server})]
    (is (empty? (hx/by-tag (content lost {:handlers {}}) :button)))
    (is (seq (hx/by-tag (content lost) :button)))))

;; ---------------------------------------------------------------------------
;; A patient who is not yours

(deftest a-patient-the-api-refuses-or-does-not-know-shows-nothing-else
  (doseq [kind [:forbidden :not-found]
          :let [tree (content (assoc (fx/settled-ana) :patient {:state :failed :kind kind}))]]
    (testing (pr-str kind)
      (is (= ["We cannot find that patient"] (map words (hx/by-tag tree :h1))))
      (is (says? tree "They are not in your panel, or there is no such patient."))
      (is (= "/" (:href (hx/attributes (link-named tree "Go to the panel")))))
      (testing "and nothing of what the other calls answered"
        (is (not (says? tree "Ana García")))
        (is (not (says? tree "BP stage 2")))
        (is (not (says? tree "Lisinopril")))
        (is (empty? (drawings tree)))
        (is (empty? (hx/by-tag tree :table)))))))

(deftest the-administrator-is-not-told-about-a-panel
  (let [tree (content (assoc (fx/settled-ana) :patient fx/forbidden) {:admin? true})]
    (is (says? tree "There is no such patient."))
    (is (not (says? tree "not in your panel")))))

(deftest with-no-panel-to-go-to-there-is-no-button
  (is (nil? (link-named (content (assoc (fx/settled-ana) :patient fx/forbidden) {:links {}}) "Go to the panel"))))

;; ---------------------------------------------------------------------------
;; What the data holds that a page must not take for markup

(deftest what-the-clinic-wrote-cannot-add-markup-to-the-page
  (let [hostile "<script>alert(1)</script>"
        state (-> (fx/settled-ana)
                  (assoc-in [:patient :data :name] [{:family hostile :given ["<img src=x onerror=alert(1)>"]}])
                  (assoc-in [:summary :data :medications] [{:id "m" :display hostile :dosage hostile}])
                  (assoc-in [:summary :data :conditions] [{:id "c" :display hostile :code hostile}])
                  (assoc-in [:summary :data :allergies] [{:id "a" :display hostile :reaction hostile}])
                  (assoc-in [:summary :data :alerts] [{:id "x" :text hostile :status "active"}])
                  (assoc-in [:summary :data :appointments] [{:id "p" :status hostile :description hostile :reason hostile :start "2026-10-14T08:00:00Z"}]))
        tree (content state)]
    (is (empty? (hx/by-tag tree :script)))
    (is (empty? (hx/by-tag tree :img)))
    (is (says? tree hostile) "the text is the text")))

(deftest a-name-that-is-very-long-wraps-in-the-places-it-can
  (let [long-name (apply str (repeat 120 "Ñandú"))
        tree (content (assoc-in (fx/settled-ana) [:summary :data :medications] [{:id "m" :display long-name :dosage long-name}]))]
    (is (says? tree long-name))
    (is (every? #(hx/has-class? % "min-w-0") (hx/select (first (hx/by-tag (card-named tree "Medication") :ul)) #(= :div (hx/tag %))))
        "the text of a row can shrink")))

;; ---------------------------------------------------------------------------
;; The workspace around it

(def ^:private states
  {"before it begins" nil
   "on its way" (patient/start fx/now {:id "pt-ana" :measurement nil :days 14})
   "with a measurement asked for" (patient/start fx/now {:id "pt-ana" :measurement :glucose :days 30})
   "loaded" (fx/settled-ana)
   "loaded, of another measurement and period" (fx/settled-ana {:asked :heart-rate :days 7})
   "Sofía" (fx/settled-sofia)
   "a patient with nothing measured" (fx/settled-noah)
   "a patient who is not yours" (assoc (fx/settled-ana) :patient fx/forbidden)
   "a patient who could not be loaded" (assoc (fx/settled-ana) :patient {:state :failed :kind :timeout})
   "a series that failed" (assoc (fx/settled-ana) :series {:state :failed :kind :server :measurement :blood-pressure :days 14})
   "a series with nothing in it" (assoc-in (fx/settled-ana) [:series :data] fx/empty-series)
   "a summary that failed" (assoc (fx/settled-ana) :summary {:state :failed :kind :server})
   "evidence that failed" (assoc-in (fx/settled-ana) [:evidence :blood-pressure] {:state :failed :kind :server})
   "a series that was cut" (assoc-in (fx/settled-ana) [:series :data :truncated] true)})

(deftest every-state-of-the-chart-passes-the-checks-of-accessibility
  (doseq [[label state] states
          admin? [false true]
          :let [person (if admin? sam rivera)
                tree (page/page state {:person person :links links :handlers {:retry (fn []) :sign-out (fn [])}})]]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} tree]) {:page? true})) (str label " " admin?))))

(deftest the-workspace-around-the-chart-has-no-search-box-and-the-panel-as-the-current-page
  (let [tree (page/page (fx/settled-ana) {:person rivera :links links :handlers {:sign-out (fn [])}})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))
        current (first (hx/select nav #(= "page" (:aria-current (hx/attributes %)))))]
    (is (empty? (hx/by-tag tree :input)) "a box that searched nothing here would be a control with nothing to do")
    (is (= "Panel" (words current)))
    (is (= ["Panel"] (map words (hx/by-tag nav :a))) "and no page that is not there")))
