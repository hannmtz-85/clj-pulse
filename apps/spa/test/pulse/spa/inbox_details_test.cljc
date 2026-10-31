(ns pulse.spa.inbox-details-test
  "What a mutation analysis of the inbox of alerts found that its other tests did not look at (ADR-0029, point 8). The page says
  everything twice, once to the eye and once to a screen reader, so a test that asks whether the page says a sentence cannot tell the
  message that says it from the live region that repeats it; these look at the message itself. The same for the layout classes that
  keep a long chip or a list edge to edge, and for the lines that are not there when there is nothing to put in them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.pages.alerts :as page]
            [pulse.spa.pages.parts :as parts]
            [pulse.spa.test.inbox-fixtures :as fx]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private links {:panel "/" :alerts "/alerts" :patient (fn [id] (str "/patients/" id))})

(def ^:private handlers {:retry (fn []) :acknowledge (fn [_ _]) :show-changes (fn [])})

(defn- content
  ([state] (content state {}))
  ([state options] (page/content state (merge {:links links :handlers handlers} options))))

(defn- titles
  "The titles of the messages of a page: what a message puts above its text. (The live region, which is for a screen reader, is another
  kind of paragraph.)"
  [tree]
  (set (for [p (hx/by-tag tree :p) :when (hx/has-class? p "font-semibold")] (hx/words p))))

(defn- live-region [tree]
  (first (filter #(= "status" (:role (hx/attributes %))) (hx/by-tag tree :p))))

(defn- section-named [tree title]
  (first (filter #(= title (hx/words (first (hx/by-tag % :h2)))) (hx/by-tag tree :section))))

(defn- card-of
  "The card inside the section named `title` (the section is the first `section`, the card the next)."
  [tree title]
  (second (hx/by-tag (section-named tree title) :section)))

(defn- body-of-card [card]
  (first (hx/by-tag card :div)))

;; ---------------------------------------------------------------------------
;; The message and the live region

(deftest the-title-of-a-notice-is-in-the-notice-and-not-only-in-what-a-screen-reader-is-told
  (let [changed (content (assoc (fx/settled) :latest {:active (fx/loaded fx/active-with-liam)}))
        look-failed (content (assoc-in (fx/settled) [:latest :active] fx/failed))]
    (testing "the alerts have changed: the title is not the sentence a screen reader hears, which says more"
      (is (contains? (titles changed) "The alerts have changed"))
      (is (= "The alerts have changed since you opened this page" (hx/words (live-region changed)))))
    (testing "the look failed: the title and the live region say the same words, so only the title's own element can tell them apart"
      (is (contains? (titles look-failed) "We could not check for new alerts"))
      (is (= "We could not check for new alerts" (hx/words (live-region look-failed)))))
    (testing "and a page that has nothing to say has neither"
      (is (not (contains? (titles (content (fx/settled))) "The alerts have changed")))
      (is (not (contains? (titles (content (fx/settled))) "We could not check for new alerts"))))))

;; ---------------------------------------------------------------------------
;; The layout of the list of acknowledged alerts

(deftest the-acknowledged-list-is-edge-to-edge-and-its-chips-go-under-the-text-when-they-do-not-fit
  (testing "loaded"
    (let [tree (content (fx/settled))
          card (card-of tree "Acknowledged")
          rows (hx/by-tag card :li)]
      (is (= "mt-3" (:class (hx/attributes (body-of-card card)))) "a list that reaches the edges has no padding of its own")
      (is (= 1 (count rows)))
      (doseq [row rows
              :let [text (first (filter #(hx/has-class? % "min-w-0") (hx/by-tag row :div)))]]
        (is (hx/has-class? text "basis-44") "the text has a basis, so that the chip and the button wrap under it instead of squeezing it"))))
  (testing "on its way"
    (let [card (card-of (content (assoc (fx/settled) :acknowledged fx/loading)) "Acknowledged")]
      (is (= "mt-3" (:class (hx/attributes (body-of-card card)))))
      (is (= 2 (count (hx/by-tag card :li))) "two rows of skeleton"))))

;; ---------------------------------------------------------------------------
;; What is not there when there is nothing to put in it

(defn- paragraphs-of-the-first-row [tree]
  (hx/by-tag (first (hx/by-tag (card-of tree "Acknowledged") :li)) :p))

(deftest an-acknowledged-alert-whose-times-cannot-be-read-has-no-line-of-history
  (let [row (fn [alert] (paragraphs-of-the-first-row (content (assoc-in (fx/settled) [:acknowledged :data] (fx/page [alert])))))]
    (testing "neither time: only the label, and not an empty line under it"
      (is (= ["BP stage 2 · Ana García"] (map hx/words (row (assoc fx/ana-acknowledged :since nil :until nil)))))
      (is (= ["Glucose out of range · Liam Chen"] (map hx/words (row (assoc fx/liam-acknowledged :since "not a time" :until "neither"))))))
    (testing "one of them: that one only"
      (is (= 2 (count (row (assoc fx/liam-acknowledged :since nil)))))
      (is (= 2 (count (row (assoc fx/liam-acknowledged :until nil))))))
    (testing "both: the two, in one line"
      (let [lines (map hx/words (row fx/liam-acknowledged))]
        (is (= 2 (count lines)))
        (is (re-find #"^Raised .*, acknowledged .*$" (second lines)))))))

(deftest the-line-of-the-rule-is-not-there-when-the-alert-says-nothing
  (is (nil? (parts/raised-line nil nil)))
  (is (= "The rule." (hx/words (parts/raised-line nil "The rule."))))
  (is (= "Raised yesterday." (hx/words (parts/raised-line "yesterday" nil))))
  (is (= "Raised yesterday. The rule." (hx/words (parts/raised-line "yesterday" "The rule.")))))

;; ---------------------------------------------------------------------------
;; The button that acknowledges: a handler is a button, an address is a link, and a handler wins

(deftest the-button-that-acknowledges-does-what-the-page-can-do-and-no-more
  (let [clicked (atom nil)
        handler (fn [id label] (reset! clicked [id label]))
        address (fn [id] (str "/alerts?ack=" id))]
    (testing "a handler: a button that calls it with the id and the words that name the alert, and no address"
      (let [button (parts/acknowledge-button "a1" "BP stage 2 · Ana García" nil {:handlers {:acknowledge handler}})]
        (is (= :button (hx/tag button)))
        (is (nil? (:href (hx/attributes button))))
        ((:on-click (hx/attributes button)) :event)
        (is (= ["a1" "BP stage 2 · Ana García"] @clicked))))
    (testing "an address: a link that leads there, and no handler"
      (let [link (parts/acknowledge-button "a1" "BP stage 2 · Ana García" nil {:links {:acknowledge address}})]
        (is (= :a (hx/tag link)))
        (is (= "/alerts?ack=a1" (:href (hx/attributes link))))
        (is (nil? (:on-click (hx/attributes link))))))
    (testing "both: the handler wins, as a page served to a person has one and the mock-up has the other"
      (let [button (parts/acknowledge-button "a1" "BP stage 2 · Ana García" nil {:handlers {:acknowledge handler} :links {:acknowledge address}})]
        (is (= :button (hx/tag button)))
        (is (nil? (:href (hx/attributes button))) "never a link that also sends a command")
        (is (fn? (:on-click (hx/attributes button))))))
    (testing "neither: no button at all"
      (is (nil? (parts/acknowledge-button "a1" "BP stage 2 · Ana García" nil {:handlers {} :links {}})))
      (is (nil? (parts/acknowledge-button "a1" "BP stage 2 · Ana García" nil {}))))))

;; ---------------------------------------------------------------------------
;; The list of acknowledged alerts: its chips, its name and what it says when it could not be loaded

(deftest each-acknowledged-alert-wears-the-chip-that-says-so
  (let [row (first (hx/by-tag (card-of (content (fx/settled)) "Acknowledged") :li))]
    (is (= ["Acknowledged"] (map hx/words (filter #(hx/has-class? % "badge") (hx/by-tag row :span)))))))

(deftest the-list-of-acknowledged-alerts-has-a-name-in-every-state-it-can-be-in
  (doseq [[state part] [["loaded" (fx/loaded fx/acknowledged)] ["on its way" fx/loading]]]
    (let [card (card-of (content (assoc (fx/settled) :acknowledged part)) "Acknowledged")]
      (is (= ["Acknowledged alerts"] (map #(:aria-label (hx/attributes %)) (hx/by-tag card :ul))) state))))

(deftest the-acknowledged-list-that-could-not-be-loaded-says-what-is-up-to-date
  (let [section (section-named (content (assoc (fx/settled) :acknowledged fx/failed)) "Acknowledged")
        text (first (filter #(hx/has-class? % "text-sm") (hx/by-tag section :div)))]
    (is (contains? (titles section) "The acknowledged alerts could not be loaded"))
    (is (= "Everything else is up to date. The problem is on our side, not yours." (hx/words text)))
    (testing "and it shows no list, empty or otherwise"
      (is (empty? (hx/by-tag section :ul))))))

;; ---------------------------------------------------------------------------
;; The patient of an alert: an id a path can carry

(deftest a-reference-names-a-patient-only-by-an-id-that-a-path-can-carry
  (doseq [[reference id] {"Patient/pt-ana" "pt-ana" "Patient/123" "123" "Patient/a.b-C" "a.b-C" "Patient/..a" "..a" "Patient/a.." "a.."
                          (str "Patient/" (apply str (repeat 64 "a"))) (apply str (repeat 64 "a"))}]
    (is (= id (inbox/patient-id reference)) reference))
  (doseq [reference [nil "" "Patient/" "Patient/." "Patient/.." "Patient/pt ana" "Patient/pt/ana" "Patient/pt_ana" "Patient/pt-ana?x" "patient/pt-ana"
                     "Observation/pt-ana" " Patient/pt-ana" "Patient/pt-ana " 7 :Patient/pt-ana ["Patient/pt-ana"]
                     (str "Patient/" (apply str (repeat 65 "a")))]]
    (is (nil? (inbox/patient-id reference)) (pr-str reference))))

(deftest an-alert-of-a-patient-no-path-can-carry-asks-for-no-readings-and-leads-to-no-chart
  (let [odd (assoc fx/ana-alert :id "flag-odd" :patient "Patient/..")
        body (fx/page [odd fx/sofia-alert])
        [started _] (inbox/pump (inbox/start fx/now) 1)
        answered (inbox/answer started :active {:result :ok :body body})]
    (testing "no call is queued for the readings behind it"
      (is (= [[:evidence "pt-sofia" :spo2]] (:queue answered))))
    (testing "and the page has no button to open the chart of a patient who has none"
      (let [tree (content (assoc-in (fx/settled) [:active :data] body))
            card (first (hx/select tree #(= "alert-flag-odd" (:id (hx/attributes %)))))]
        (is (some? card) "the alert is there: it needs a decision all the same")
        (is (empty? (filter #(= "Open the chart" (hx/words %)) (hx/by-tag card :a))))
        (is (some #(= "Open the chart" (hx/words %)) (hx/by-tag (first (hx/select tree #(= "alert-flag-pt-sofia-R2" (:id (hx/attributes %))))) :a))
            "while the alert of a patient who has a chart leads to it")))))

;; ---------------------------------------------------------------------------
;; The cards are keyed by their alert

(deftest each-alert-that-needs-a-decision-is-keyed-by-its-id
  ;; React reconciles by key: with the position of the card as its key, the button that has the focus is handed to the next alert when the
  ;; one above it goes (the real browser found the focus on the button of an alert that the person had not chosen)
  (let [keys-of (fn [state]
                  (map #(:key (meta %)) (hx/select (content state) #(str/starts-with? (str (:id (hx/attributes %))) "alert-"))))]
    (is (= ["flag-pt-ana-R1" "flag-pt-sofia-R2"] (keys-of (fx/settled))))
    (is (= ["flag-pt-liam-R3-b" "flag-pt-ana-R1" "flag-pt-sofia-R2"] (keys-of (assoc (fx/settled) :active (fx/loaded fx/active-with-liam)))))
    (is (= ["flag-pt-sofia-R2"] (keys-of (assoc (fx/settled) :active (fx/loaded fx/active-without-ana)))))))
