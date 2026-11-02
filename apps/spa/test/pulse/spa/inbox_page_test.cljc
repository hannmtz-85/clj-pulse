(ns pulse.spa.inbox-page-test
  "The inbox of alerts as a page (ADR-0029, points 6 and 8): what it says in each of its states, that what could not be loaded is said in
  its place and is never shown as empty, that the page says when the alerts have changed instead of changing them, that a control with
  nothing to lead to is not there, that a button that sends is busy until the api has answered, and that every state passes the checks of
  accessibility. Pure hiccup, so these run on the JVM and on Node, over the same data as the tests of the machine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.pages.alerts :as page]
            [pulse.spa.test.inbox-fixtures :as fx]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(defn- filter-address [show]
  (str "/alerts" (when (#{:active :acknowledged} show) (str "?show=" (name show)))))

(def ^:private links {:panel "/" :alerts "/alerts" :filter filter-address :patient (fn [id] (str "/patients/" id))})

(defn- acknowledge [_id _label])

(def ^:private handlers {:retry (fn []) :acknowledge acknowledge :show-changes (fn [])})

(defn- content
  ([state] (content state {}))
  ([state options] (page/content state (merge {:links links :handlers handlers} options))))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- button-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :button))))

(defn- link-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :a))))

(defn- section-named
  "The section of the page whose heading is `title`."
  [tree title]
  (first (filter #(= title (words (first (hx/by-tag % :h2)))) (hx/by-tag tree :section))))

(defn- labelled-sections
  "The sections of the page that have a heading: the two lists. (A card is a section too.)"
  [tree]
  (filter #(:aria-labelledby (hx/attributes %)) (hx/by-tag tree :section)))

(defn- alert-named
  "The card of the alert `id`."
  [tree id]
  (first (hx/select tree #(= (str "alert-" id) (:id (hx/attributes %))))))

(defn- pills [tree]
  (hx/by-tag (first (hx/select tree #(= "Show" (:aria-label (hx/attributes %))))) :a))

;; ---------------------------------------------------------------------------
;; The inbox that has loaded

(deftest the-inbox-says-what-it-is-and-how-many-alerts-need-a-decision
  (let [tree (content (fx/settled))]
    (is (= ["Alerts"] (map words (hx/by-tag tree :h1))) "the one heading of the page")
    (is (says? tree "Raised by the rules from the readings of your patients."))
    (is (= ["2 alerts need a decision"] (map words (hx/select tree #(= "status" (:role (hx/attributes %)))))) "told to a screen reader")
    (is (= ["Needs a decision" "Acknowledged"] (map #(words (first (hx/by-tag % :h2))) (labelled-sections tree))))
    (is (= ["needs" "done"] (map (comp :aria-labelledby hx/attributes) (labelled-sections tree))))
    (is (= ["needs" "done"] (map (comp :id hx/attributes) (hx/by-tag tree :h2))) "so that the focus has a heading to go to")))

(deftest the-administrator-is-told-whose-alerts-they-are
  (is (says? (content (fx/settled) {:admin? true}) "Raised by the rules from the readings of the clinic's patients."))
  (is (not (says? (content (fx/settled) {:admin? true}) "your patients"))))

(deftest an-alert-that-needs-a-decision-says-its-rule-when-it-was-raised-and-the-readings-behind-it
  (let [card (alert-named (content (fx/settled)) "flag-pt-ana-R1")]
    (is (some? card))
    (is (says? card "BP stage 2 · Ana García"))
    (is (says? card "Raised yesterday, 22:30. Blood pressure at stage 2 (a systolic of 160 or more, or a diastolic of 100 or more) in at least 2 of the last 3 readings of the last 3 days."))
    (is (says? card "The readings behind it"))
    (is (= ["177/100 mmHg, today, 10:00" "172/100 mmHg, yesterday, 10:00" "165/98 mmHg, Oct 11, 10:00"]
           (map words (hx/by-tag card :li))) "the newest first, each with its time")
    (is (hx/has-class? card "border-tone-critical-edge") "in the colour of its rule")))

(defn- cards-of
  "The cards of the alerts in `section`, in the order they are in."
  [section]
  (hx/select section #(str/starts-with? (str (:id (hx/attributes %))) "alert-")))

(deftest the-alerts-are-cards-in-the-order-the-api-gave-them
  (let [cards (cards-of (section-named (content (fx/settled)) "Needs a decision"))]
    (is (= ["alert-flag-pt-ana-R1" "alert-flag-pt-sofia-R2"] (map (comp :id hx/attributes) cards)))
    (is (= ["BP stage 2 · Ana García" "Low SpO₂ · Sofía Rossi"]
           (map #(words (first (hx/select % (fn [node] (hx/has-class? node "font-semibold"))))) cards)))))

(deftest every-alert-has-buttons-to-acknowledge-it-and-to-open-the-chart-of-the-patient
  (let [tree (content (fx/settled))
        ana (alert-named tree "flag-pt-ana-R1")
        sofia (alert-named tree "flag-pt-sofia-R2")
        acknowledge-ana (button-named ana "Acknowledge")]
    (is (some? acknowledge-ana))
    (is (= "ack-flag-pt-ana-R1" (:id (hx/attributes acknowledge-ana))))
    (is (= "Acknowledge BP stage 2 · Ana García" (:aria-label (hx/attributes acknowledge-ana))) "so that the buttons of a page do not all say the same")
    (is (fn? (:on-click (hx/attributes acknowledge-ana))))
    (is (= "Acknowledge Low SpO₂ · Sofía Rossi" (:aria-label (hx/attributes (button-named sofia "Acknowledge")))))
    (is (= "/patients/pt-ana" (:href (hx/attributes (link-named ana "Open the chart")))))
    (is (= "Open the chart of Ana García" (:aria-label (hx/attributes (link-named ana "Open the chart")))))
    (is (= "/patients/pt-sofia" (:href (hx/attributes (link-named sofia "Open the chart")))))))

(deftest pressing-the-button-acknowledges-that-alert-and-tells-how-the-page-names-it
  (let [pressed (atom [])
        tree (content (fx/settled) {:handlers (assoc handlers :acknowledge (fn [id label] (swap! pressed conj [id label])))})]
    ((:on-click (hx/attributes (button-named (alert-named tree "flag-pt-sofia-R2") "Acknowledge"))) nil)
    ((:on-click (hx/attributes (button-named (alert-named tree "flag-pt-ana-R1") "Acknowledge"))) nil)
    (is (= [["flag-pt-sofia-R2" "Low SpO₂ · Sofía Rossi"] ["flag-pt-ana-R1" "BP stage 2 · Ana García"]] @pressed))))

(deftest an-alert-that-was-acknowledged-is-a-row-with-when-it-was-raised-and-when-it-was-acknowledged
  (let [section (section-named (content (fx/settled)) "Acknowledged")
        rows (hx/by-tag section :li)]
    (is (= 1 (count rows)))
    (is (says? section "Glucose out of range · Liam Chen"))
    (is (says? section "Raised Oct 11, 22:30, acknowledged today, 09:40"))
    (is (says? section "Acknowledged"))
    (is (= "/patients/pt-liam" (:href (hx/attributes (link-named section "Open the chart")))))
    (is (empty? (hx/by-tag section :button)) "there is nothing more to decide about it")
    (is (= "Acknowledged alerts" (:aria-label (hx/attributes (first (hx/by-tag section :ul))))))))

;; ---------------------------------------------------------------------------
;; The pills

(deftest the-pills-choose-what-the-inbox-shows-and-count-it
  (let [tree (content (fx/settled))]
    (is (= ["Active2" "Acknowledged1" "All3"] (map words (pills tree))))
    (is (= ["/alerts?show=active" "/alerts?show=acknowledged" "/alerts"] (map (comp :href hx/attributes) (pills tree))))
    (is (= ["All3"] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (pills tree))) "all of them is the default")))

(deftest the-current-pill-is-the-one-the-inbox-is-narrowed-to
  (doseq [[show current] {:active "Active2" :acknowledged "Acknowledged1" :all "All3"}]
    (is (= [current] (keep #(when (= "page" (:aria-current (hx/attributes %))) (words %)) (pills (content (fx/settled) {:show show})))) (pr-str show))))

(deftest narrowing-the-inbox-shows-one-of-its-two-lists
  (let [active (content (fx/settled) {:show :active})
        acknowledged (content (fx/settled) {:show :acknowledged})]
    (is (some? (section-named active "Needs a decision")))
    (is (nil? (section-named active "Acknowledged")))
    (is (nil? (section-named acknowledged "Needs a decision")))
    (is (some? (section-named acknowledged "Acknowledged")))
    (is (seq (pills active)) "and the pills stay, so that another can be chosen")))

(deftest there-are-no-pills-when-there-is-nowhere-for-them-to-lead
  (is (empty? (hx/select (content (fx/settled) {:links (dissoc links :filter)}) #(= "Show" (:aria-label (hx/attributes %))))))
  (is (some? (section-named (content (fx/settled) {:links (dissoc links :filter)}) "Needs a decision")) "the alerts are there all the same"))

(deftest the-pills-have-no-counts-before-the-lists-are-in
  (is (= ["Active" "Acknowledged" "All"] (map words (pills (content (inbox/start fx/now))))))
  (is (= ["Active" "Acknowledged1" "All"] (map words (pills (content (assoc (fx/settled) :active fx/failed)))))))

;; ---------------------------------------------------------------------------
;; Nothing to decide, and nothing acknowledged

(deftest when-nothing-needs-a-decision-the-page-says-so-and-not-that-it-has-not-loaded
  (let [tree (content (assoc-in (fx/settled) [:active :data] fx/none))
        section (section-named tree "Needs a decision")]
    (is (says? section "No alerts"))
    (is (says? section "Nothing needs a decision right now. New alerts appear here as the rules raise them."))
    (is (empty? (cards-of section)))
    (is (empty? (hx/by-tag section :button)))
    (is (nil? (link-named section "See acknowledged alerts")) "the acknowledged ones are below")
    (is (= ["No alert needs a decision"] (map words (hx/select tree #(= "status" (:role (hx/attributes %)))))))))

(deftest narrowed-to-the-active-ones-the-empty-inbox-leads-to-the-acknowledged
  (let [tree (content (assoc-in (fx/settled) [:active :data] fx/none) {:show :active})]
    (is (= "/alerts?show=acknowledged" (:href (hx/attributes (link-named tree "See acknowledged alerts")))))
    (testing "but not when there are none of those"
      (is (nil? (link-named (content (-> (fx/settled) (assoc-in [:active :data] fx/none) (assoc-in [:acknowledged :data] fx/none)) {:show :active})
                            "See acknowledged alerts"))))
    (testing "nor when the page cannot lead there"
      (is (nil? (link-named (content (assoc-in (fx/settled) [:active :data] fx/none) {:show :active :links (dissoc links :filter)})
                            "See acknowledged alerts"))))))

(deftest when-nothing-has-been-acknowledged-the-page-says-so
  (let [section (section-named (content (assoc-in (fx/settled) [:acknowledged :data] fx/none)) "Acknowledged")]
    (is (says? section "Nothing has been acknowledged yet."))
    (is (empty? (hx/by-tag section :li)))))

;; ---------------------------------------------------------------------------
;; Before it is all there, and what could not be loaded

(deftest before-the-inbox-begins-it-is-a-title-and-grey-shapes
  (doseq [state [nil (inbox/start fx/now)]
          :let [tree (content state)]]
    (is (= ["Alerts"] (map words (hx/by-tag tree :h1))))
    (is (= ["Loading the alerts"] (map words (hx/select tree #(= "status" (:role (hx/attributes %)))))))
    (is (seq (hx/select tree #(hx/has-class? % "skeleton"))))
    (is (empty? (hx/by-tag tree :button)) "nothing to acknowledge or to try again while it loads")
    (is (not (says? tree "No alerts")) "no list says that it is empty before it is known")
    (is (not (says? tree "Nothing has been acknowledged yet.")))))

(deftest a-list-that-could-not-be-loaded-is-a-message-in-its-place-and-not-an-empty-list
  (let [retry (fn [])
        lost-active (content (assoc (fx/settled) :active fx/failed) {:handlers {:retry retry}})
        lost-done (content (assoc (fx/settled) :acknowledged fx/failed) {:handlers {:retry retry}})]
    (is (says? (section-named lost-active "Needs a decision") "The alerts could not be loaded"))
    (is (says? (section-named lost-active "Needs a decision") "Everything else is up to date. The problem is on our side, not yours."))
    (is (= retry (:on-click (hx/attributes (button-named (section-named lost-active "Needs a decision") "Try again")))))
    (is (not (says? lost-active "No alerts")) "it does not say that there is nothing")
    (is (says? (section-named lost-active "Acknowledged") "Glucose out of range") "and the other list is there")
    (is (says? (section-named lost-done "Acknowledged") "The acknowledged alerts could not be loaded"))
    (is (= retry (:on-click (hx/attributes (button-named (section-named lost-done "Acknowledged") "Try again")))))
    (is (not (says? lost-done "Nothing has been acknowledged yet.")))
    (is (says? (section-named lost-done "Needs a decision") "BP stage 2"))))

(deftest there-is-no-button-to-try-again-when-there-is-nothing-to-call
  (let [lost (assoc (fx/settled) :active fx/failed)]
    (is (nil? (button-named (content lost {:handlers {}}) "Try again")))
    (is (some? (button-named (content lost) "Try again")))))

(deftest the-readings-behind-an-alert-are-said-as-they-are
  (let [state (fn [part] (assoc-in (fx/settled) [:evidence ["pt-ana" :blood-pressure]] part))
        ana (fn [part] (alert-named (content (state part)) "flag-pt-ana-R1"))]
    (is (says? (ana fx/failed) "The readings behind it could not be loaded."))
    (testing "on their way they are grey shapes, not none and not lost"
      (is (seq (hx/select (ana fx/loading) #(hx/has-class? % "skeleton"))))
      (is (not (says? (ana fx/loading) "The readings behind it")))
      (is (not (says? (ana fx/loading) "could not be loaded"))))
    (testing "and none is no list"
      (is (not (says? (ana (fx/loaded fx/empty-series)) "The readings behind it"))))))

(deftest an-alert-of-a-rule-nobody-knows-has-its-text-and-no-more
  (let [tree (content (assoc-in (fx/settled) [:active :data] (fx/page [{:id "x" :patient "Patient/pt-ana" :text "Something else" :status "active" :since "2026-10-12T20:30:00Z"}])))
        card (alert-named tree "x")]
    (is (says? card "Something else · Ana García"))
    (is (says? card "Raised yesterday, 22:30."))
    (is (not (says? card "The readings behind it")))
    (is (not (says? card "Blood pressure at stage 2")))
    (is (some? (button-named card "Acknowledge")) "it can be acknowledged all the same")))

(deftest a-list-that-is-the-latest-of-more-says-so
  (let [tree (content (-> (fx/settled)
                          (assoc-in [:active :data] (fx/page [fx/ana-alert fx/sofia-alert] 130))
                          (assoc-in [:acknowledged :data] (fx/page [fx/liam-acknowledged] 240))))]
    (is (says? (section-named tree "Needs a decision") "Showing the latest 2 of 130 alerts."))
    (is (says? (section-named tree "Acknowledged") "Showing the latest 1 of 240 acknowledged alerts."))
    (is (not (says? (content (fx/settled)) "Showing the latest")) "and nothing when it is all of them")))

;; ---------------------------------------------------------------------------
;; What the page says besides the alerts

(deftest the-page-says-that-the-alerts-have-changed-and-lets-the-person-show-them
  (let [shown (atom 0)
        state (assoc (fx/settled) :latest {:active (fx/loaded fx/active-with-liam)})
        tree (content state {:handlers (assoc handlers :show-changes (fn [] (swap! shown inc)))})]
    (is (says? tree "The alerts have changed"))
    (is (says? tree "Alerts were raised or acknowledged since you opened this page."))
    ((:on-click (hx/attributes (button-named tree "Show the changes"))))
    (is (= 1 @shown))
    (testing "what is shown is what was shown"
      (is (= 2 (count (cards-of (section-named tree "Needs a decision")))))
      (is (nil? (alert-named tree "flag-pt-liam-R3-b"))))
    (testing "and with no way to show them there is no button"
      (is (nil? (button-named (content state {:handlers {}}) "Show the changes")))
      (is (says? (content state {:handlers {}}) "The alerts have changed")))
    (testing "nothing is said when nothing has changed"
      (is (not (says? (content (fx/settled)) "The alerts have changed"))))))

(deftest the-page-says-that-it-could-not-look-for-new-alerts
  (let [retry (fn [])
        tree (content (assoc-in (fx/settled) [:latest :active] fx/failed) {:handlers {:retry retry}})]
    (is (says? tree "We could not check for new alerts"))
    (is (says? tree "What you see may be out of date. The problem is on our side, not yours."))
    (is (= retry (:on-click (hx/attributes (button-named tree "Try again")))))
    (is (some? (alert-named tree "flag-pt-ana-R1")) "and what it shows is still there")))

(deftest the-page-says-when-the-names-or-the-readings-could-not-be-loaded
  (let [names (content (assoc (fx/settled) :patients fx/failed))
        readings (content (-> (fx/settled)
                              (assoc-in [:evidence ["pt-ana" :blood-pressure]] fx/failed)
                              (assoc-in [:evidence ["pt-sofia" :spo2]] fx/failed)))
        reading (content (assoc-in (fx/settled) [:evidence ["pt-sofia" :spo2]] fx/failed))]
    (is (says? names "The names of the patients could not be loaded"))
    (is (says? names "The alerts are up to date. The problem is on our side, not yours."))
    (is (says? names "BP stage 2 · Patient pt-ana") "the alerts are there, told by the number of the patient")
    (is (says? readings "The readings behind 2 alerts could not be loaded"))
    (is (says? reading "The readings behind 1 alert could not be loaded"))
    (is (says? readings "The rest of the page is up to date. The problem is on our side, not yours."))))

;; ---------------------------------------------------------------------------
;; Acknowledging

(deftest a-button-is-busy-from-the-click-until-the-page-has-been-read-again
  (doseq [state [:sending :settling]
          :let [tree (content (fx/settled) {:acks {"flag-pt-ana-R1" {:state state :label "BP stage 2 · Ana García"}}})
                busy (button-named (alert-named tree "flag-pt-ana-R1") "Acknowledge")]]
    (testing (name state)
      (is (true? (:disabled (hx/attributes busy))) "it cannot be pressed")
      (is (= "true" (:aria-busy (hx/attributes busy))) "and says that it is busy")
      (is (some? (first (hx/select busy #(hx/has-class? % "loading")))) "with a spinner")
      (is (false? (boolean (:disabled (hx/attributes (button-named (alert-named tree "flag-pt-sofia-R2") "Acknowledge"))))) "the others are free"))))

(deftest an-alert-that-was-not-acknowledged-says-why-and-its-button-is-free-again
  (let [message "We could not confirm that the alert was acknowledged. Check the list before you try again."
        tree (content (fx/settled) {:acks {"flag-pt-ana-R1" {:state :failed :label "BP stage 2 · Ana García" :message message}}})
        card (alert-named tree "flag-pt-ana-R1")]
    (is (says? card message))
    (is (not (:disabled (hx/attributes (button-named card "Acknowledge")))))
    (is (not (says? (alert-named tree "flag-pt-sofia-R2") "We could not confirm")) "only that alert says it")))

(deftest the-mock-up-leads-the-button-where-the-handler-would-act
  (let [tree (content (fx/settled) {:handlers {:retry (fn [])} :links (assoc links :acknowledge (fn [id] (str "/alerts#" id)))})
        button (link-named (alert-named tree "flag-pt-ana-R1") "Acknowledge")]
    (is (= "/alerts#flag-pt-ana-R1" (:href (hx/attributes button))))
    (is (nil? (button-named tree "Acknowledge")))))

(deftest with-nothing-to-acknowledge-with-there-is-no-button
  (let [tree (content (fx/settled) {:handlers {:retry (fn [])}})]
    (is (nil? (button-named tree "Acknowledge")))
    (is (nil? (link-named tree "Acknowledge")))
    (is (some? (link-named tree "Open the chart")) "the other buttons are there")))

(deftest with-no-chart-to-lead-to-there-is-no-button-to-open-it
  (let [tree (content (fx/settled) {:links (dissoc links :patient)})]
    (is (nil? (link-named tree "Open the chart")))
    (is (some? (button-named tree "Acknowledge")))))

(deftest an-alert-with-no-buttons-has-no-place-for-them
  (let [tree (content (fx/settled) {:handlers {} :links (dissoc links :patient)})
        card (alert-named tree "flag-pt-ana-R1")]
    (is (empty? (hx/by-tag card :button)))
    (is (empty? (hx/by-tag card :a)))
    (is (not (hx/has-class? card "sm:grid-cols-[auto_1fr_auto]")) "a message with no actions has no column for them")))

;; ---------------------------------------------------------------------------
;; What the data holds that a page must not take for markup

(deftest what-the-clinic-wrote-cannot-add-markup-to-the-page
  (let [hostile "<script>alert(1)</script>"
        state (-> (fx/settled)
                  (assoc-in [:patients :data :items 1 :name] hostile)
                  (assoc-in [:active :data] (fx/page [{:id "x" :patient "Patient/pt-ana" :text hostile :status "active"}]))
                  (assoc-in [:acknowledged :data] (fx/page [{:id "y" :patient "Patient/pt-ana" :text hostile :status "inactive"}])))
        tree (content state)]
    (is (empty? (hx/by-tag tree :script)))
    (is (says? tree hostile) "the text is the text")))

(deftest a-name-that-is-very-long-wraps-in-the-places-it-can
  (let [long-name (apply str (repeat 120 "Ñandú"))
        tree (content (assoc-in (fx/settled) [:patients :data :items 1 :name] long-name))]
    (is (says? tree long-name))
    (is (seq (hx/select (alert-named tree "flag-pt-ana-R1") #(hx/has-class? % "wrap-anywhere"))) "the title can break anywhere")
    (is (seq (hx/select (section-named tree "Needs a decision") #(hx/has-class? % "min-w-0"))) "and the box of the text can shrink")))

;; ---------------------------------------------------------------------------
;; The workspace around it

(def ^:private states
  {"before it begins" nil
   "on its way" (inbox/start fx/now)
   "loaded" (fx/settled)
   "nothing needs a decision" (assoc-in (fx/settled) [:active :data] fx/none)
   "nothing acknowledged" (assoc-in (fx/settled) [:acknowledged :data] fx/none)
   "nothing at all" (-> (fx/settled) (assoc-in [:active :data] fx/none) (assoc-in [:acknowledged :data] fx/none))
   "the alerts could not be loaded" (assoc (fx/settled) :active fx/failed)
   "the acknowledged ones could not be loaded" (assoc (fx/settled) :acknowledged fx/failed)
   "the names could not be loaded" (assoc (fx/settled) :patients fx/failed)
   "the readings could not be loaded" (assoc-in (fx/settled) [:evidence ["pt-ana" :blood-pressure]] fx/failed)
   "the readings are on their way" (assoc-in (fx/settled) [:evidence ["pt-ana" :blood-pressure]] fx/loading)
   "the alerts have changed" (assoc (fx/settled) :latest {:active (fx/loaded fx/active-with-liam)})
   "the look failed" (assoc-in (fx/settled) [:latest :active] fx/failed)
   "a list that was cut" (assoc-in (fx/settled) [:active :data :total] 130)})

(deftest every-state-of-the-inbox-passes-the-checks-of-accessibility
  (doseq [[label state] states
          admin? [false true]
          show [:all :active :acknowledged]
          acks [nil {"flag-pt-ana-R1" {:state :sending}} {"flag-pt-ana-R1" {:state :failed :message "The alert was not acknowledged."}}]
          :let [person (if admin? sam rivera)
                tree (page/page state {:person person :show show :acks acks :links links :handlers (assoc handlers :sign-out (fn []))})]]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} tree]) {:page? true})) (str label " " admin? " " show " " (boolean acks)))))

(deftest the-workspace-around-the-inbox-has-the-alerts-as-the-current-page-and-the-count
  (let [tree (page/page (fx/settled) {:person rivera :alert-count 2 :links links :handlers {:sign-out (fn [])}})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))
        current (first (hx/select nav #(= "page" (:aria-current (hx/attributes %)))))]
    (is (empty? (hx/by-tag tree :input)) "a box that searched nothing here would be a control with nothing to do")
    (is (= "Alerts 2" (words current)) "with the count that the navigation holds")
    (is (= ["Panel" "Alerts 2"] (map words (hx/by-tag nav :a))) "and no page that is not there")))
