(ns pulse.spa.patient-page-details-test
  "What a mutation analysis of the page of the chart of a patient found that the first tests did not hold (ADR-0029, point 6): the axis of
  the chart begins where the period does (or where a cut series does), the chart for a phone leaves out the thresholds, the lists reach
  the edges of their cards, a request has a clock where a booked appointment has a day, and what is on its way keeps its place. Pure
  hiccup, so these run on the JVM and on Node."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.patient :as page]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.patient-fixtures :as fx]
            [pulse.ui.test.hiccup :as hx]))

(defn- content
  ([state] (content state {}))
  ([state options]
   (page/content state (merge {:links {:panel "/" :chart (fn [{:keys [measurement days]}] (str "/patients/pt-ana?days=" days "&measurement=" (name measurement)))}
                               :handlers {:retry (fn [])}}
                              options))))

(defn- words [tree] (hx/words tree))

(defn- card-named [tree title]
  (first (filter #(= title (words (first (hx/by-tag % :h2)))) (hx/by-tag tree :section))))

(defn- drawing
  "The drawing of the chart whose title is tied by an id that begins `prefix`."
  [tree prefix]
  (first (hx/select tree #(and (= :svg (hx/tag %)) (= "img" (:role (hx/attributes %))) (str/starts-with? (str (:aria-labelledby (hx/attributes %))) prefix)))))

(defn- axis-labels
  "The labels of the axis of time of a drawing."
  [svg]
  (map words (hx/select svg #(and (= :text (hx/tag %)) (= "middle" (:text-anchor (hx/attributes %)))))))

;; ---------------------------------------------------------------------------
;; The axis of time

(deftest the-axis-begins-where-the-period-does-and-not-where-the-first-reading-is
  (let [tree (content (fx/settled-ana))]
    ;; five days of readings in a period of fourteen: the axis is of the fourteen
    (is (= ["Oct 4" "Oct 9"] (axis-labels (drawing tree "chart-blood-pressure-wide"))))
    (is (= ["Oct 4" "Oct 9"] (axis-labels (drawing tree "chart-blood-pressure-narrow"))))))

(deftest a-period-of-seven-days-has-the-axis-of-seven
  (let [tree (content (assoc (fx/settled-ana) :days 7))]
    (is (= ["Oct 8" "Oct 10" "Oct 12"] (axis-labels (drawing tree "chart-blood-pressure-wide")))
        "every second midnight of the seven days: the axis is of the period")))

(deftest a-series-that-was-cut-has-the-axis-of-what-it-holds
  (let [tree (content (assoc-in (fx/settled-ana) [:series :data :truncated] true))]
    (is (= ["Oct 10" "Oct 11" "Oct 12" "Oct 13"] (axis-labels (drawing tree "chart-blood-pressure-wide")))
        "from the oldest reading it has, and not from the start of the period")))

;; ---------------------------------------------------------------------------
;; The chart for a phone

(deftest the-chart-for-a-phone-leaves-out-the-thresholds-and-the-range-and-the-one-for-a-desk-has-them
  (let [tree (content (fx/settled-ana))
        wide (words (drawing tree "chart-blood-pressure-wide"))
        narrow (words (drawing tree "chart-blood-pressure-narrow"))]
    (is (str/includes? wide "Stage 2 · 160"))
    (is (str/includes? wide "Stage 2 · 100"))
    (is (not (str/includes? narrow "Stage 2")) "there is no room in the margin of a phone for their words")
    (testing "nor the shaded range of normal values, which has a word of its own"
      (let [heart (content (fx/settled-ana {:asked :heart-rate}))]
        (is (str/includes? (words (drawing heart "chart-heart-rate-wide")) "Normal"))
        (is (not (str/includes? (words (drawing heart "chart-heart-rate-narrow")) "Normal")))))))

;; ---------------------------------------------------------------------------
;; The card of the chart while it is on its way

(deftest a-chart-that-is-asked-for-is-a-card-of-its-measurement-while-the-summary-is-on-its-way
  (let [tree (content (patient/start fx/now {:id "pt-ana" :measurement :glucose :days 30}))
        card (card-named tree "Glucose")]
    (is (some? card) "the series is asked for at once, and the card is of it")
    (is (str/includes? (words card) "Last 30 days"))
    (is (= ["7 days" "14 days" "30 days"] (map words (hx/by-tag (first (hx/select card #(= "Period" (:aria-label (hx/attributes %))))) :a)))
        "and the period can be chosen")
    (is (empty? (hx/select card #(= "Measurement" (:aria-label (hx/attributes %))))) "the measurements to choose from are not known yet")
    (is (seq (hx/select card #(hx/has-class? % "skeleton"))))))

(deftest a-chart-that-is-not-yet-of-anything-is-a-card-of-readings-with-no-choices
  (let [tree (content (patient/start fx/now {:id "pt-ana" :measurement nil :days 14}))
        card (card-named tree "Readings")]
    (is (some? card))
    (is (empty? (hx/by-tag card :nav)) "nothing can be chosen of what is not known")
    (is (seq (hx/select card #(hx/has-class? % "skeleton"))))))

(deftest the-latest-readings-have-their-heading-while-they-are-on-their-way-and-when-they-could-not-be-loaded
  (doseq [state [(patient/start fx/now {:id "pt-ana" :measurement nil :days 14})
                 (assoc (fx/settled-ana) :summary {:state :failed :kind :server})]]
    (let [section (first (filter #(= "vitals" (:aria-labelledby (hx/attributes %))) (hx/by-tag (content state) :section)))]
      (is (= "Latest readings" (words (first (hx/by-tag section :h2))))))))

;; ---------------------------------------------------------------------------
;; The alerts and the latest readings

(deftest the-readings-behind-an-alert-that-came-back-empty-are-not-claimed
  (let [state (assoc-in (fx/settled-ana) [:evidence :blood-pressure] (fx/loaded {:items [] :truncated false}))
        tree (content state)]
    (is (str/includes? (words tree) "BP stage 2"))
    (is (not (str/includes? (words tree) "The latest readings:")) "it does not say 'The latest readings: .'")))

(deftest some-readings-that-could-not-be-loaded-and-none-shown-is-a-message-and-no-empty-grid
  (let [state (assoc (fx/settled-ana) :summary (fx/loaded (assoc fx/ana-summary
                                                                 :vitals (zipmap [:blood-pressure :heart-rate :glucose :spo2 :weight :temperature] (repeat nil))
                                                                 :unavailable ["vitals.glucose"])))
        section (first (filter #(= "vitals" (:aria-labelledby (hx/attributes %))) (hx/by-tag (content state) :section)))]
    (is (str/includes? (words section) "Some readings could not be loaded"))
    (is (empty? (hx/select section #(and (= :div (hx/tag %)) (hx/has-class? % "md:grid-cols-3")))) "no grid of no tiles")))

;; ---------------------------------------------------------------------------
;; The lists and the appointments

(deftest the-lists-reach-the-edges-of-their-cards-and-the-text-of-a-row-is-not-squeezed-by-its-chip
  (let [tree (content (fx/settled-ana))]
    (doseq [title ["Medication" "Allergies" "Appointments"]
            :let [card (card-named tree title)
                  body (last (hx/select card #(and (= :div (hx/tag %)) (hx/has-class? % "mt-3"))))]]
      (is (some? body) (str title ": the list has no padding of its own, so that its rows reach the edges"))
      (is (not (hx/has-class? body "px-5")) title))
    (testing "an appointment keeps its chip under the text when there is no room"
      (is (every? #(hx/has-class? (first (hx/select % (fn [e] (and (= :div (hx/tag e)) (hx/has-class? e "flex-1"))))) "basis-44")
                  (hx/by-tag (card-named tree "Appointments") :li))))))

(deftest a-booked-appointment-has-its-day-and-a-request-has-a-clock
  (let [appointments [{:id "booked" :status "booked" :description "Check-up" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"}
                      {:id "asked" :status "proposed" :description "First visit" :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"}]
        tree (content (assoc (fx/settled-ana) :summary (fx/loaded (assoc fx/ana-summary :appointments appointments))))
        rows (hx/by-tag (card-named tree "Appointments") :li)
        marker (fn [row] (first (hx/select row #(= "true" (:aria-hidden (hx/attributes %))))))]
    (is (= ["Oct14"] (map (comp #(str/replace % #"\s" "") words) [(marker (first rows))])) "the month and the day")
    (is (= [""] [(words (marker (second rows)))]) "no day to write: a clock")
    (is (seq (hx/by-tag (marker (second rows)) :svg)))
    (is (empty? (hx/by-tag (marker (first rows)) :svg)))))

;; ---------------------------------------------------------------------------
;; The words of the card of the chart in each of its states

(deftest each-state-of-the-card-of-the-chart-says-its-period
  (let [subtitle (fn [state title] (words (first (hx/by-tag (card-named (content state) title) :p))))]
    (testing "on its way"
      (is (= "Last 30 days" (subtitle (patient/start fx/now {:id "pt-ana" :measurement :glucose :days 30}) "Glucose")))
      (is (= "Last 14 days" (subtitle (assoc-in (fx/settled-ana) [:series :state] :loading) "Blood pressure"))))
    (testing "could not be loaded"
      (is (= "Last 7 days" (subtitle (assoc (fx/settled-ana) :days 7 :series {:state :failed :kind :timeout :measurement :blood-pressure :days 7}) "Blood pressure"))))
    (testing "nothing in the period"
      (is (= "Last 30 days" (subtitle (assoc (fx/settled-ana) :days 30 :series (assoc (fx/loaded fx/empty-series) :measurement :blood-pressure :days 30)) "Blood pressure"))))))

(deftest a-period-with-no-readings-suggests-another
  (let [tree (content (assoc-in (fx/settled-ana) [:series :data] fx/empty-series))]
    (is (str/includes? (words tree) "Another period, or another measurement, may have some."))))

(deftest a-chart-with-no-series-because-the-summary-timed-out-says-it-could-not-be-loaded
  (let [chart (-> (patient/start fx/now {:id "pt-ana" :measurement nil :days 14}) patient/expire)
        tree (content chart)
        card (card-named tree "Readings")]
    (is (nil? (:series chart)) "the deadline came before the summary said what to draw")
    (is (some? card))
    (is (str/includes? (words card) "The chart could not be loaded"))
    (is (str/includes? (words tree) "The patient could not be loaded"))))

;; ---------------------------------------------------------------------------
;; The appointments

(deftest an-appointment-with-no-reason-written-is-an-appointment-and-a-cancelled-one-says-why
  (let [appointments [{:id "a" :status "booked" :description nil :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"}
                      {:id "b" :status "cancelled" :description "Check" :start "2026-10-15T08:00:00Z" :end "2026-10-15T08:30:00Z" :reason "I had to travel"}
                      {:id "c" :status "cancelled" :description "Other" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z" :reason "  "}]
        tree (content (assoc (fx/settled-ana) :summary (fx/loaded (assoc fx/ana-summary :appointments appointments))))
        rows (hx/by-tag (card-named tree "Appointments") :li)]
    (is (= "Appointment" (words (first (hx/by-tag (first rows) :p)))) "it is not an empty line")
    ;; the cancelled ones come newest first
    (is (str/includes? (words (nth rows 2)) "Reason: I had to travel"))
    (is (not (str/includes? (words (second rows)) "Reason")) "a reason that is blank is none")))

(deftest a-card-of-readings-that-is-not-yet-of-anything-says-its-period
  (let [card (card-named (content (patient/start fx/now {:id "pt-ana" :measurement nil :days 30})) "Readings")]
    (is (= "Last 30 days" (words (first (hx/by-tag card :p)))))))

(deftest a-list-that-is-on-its-way-reaches-the-edges-of-its-card-too
  (let [tree (content (patient/start fx/now {:id "pt-ana" :measurement nil :days 14}))]
    (doseq [title ["Medication" "Allergies" "Appointments"]
            :let [card (card-named tree title)
                  body (last (hx/select card #(and (= :div (hx/tag %)) (hx/has-class? % "mt-3"))))]]
      (is (some? body) title)
      (is (seq (hx/select card #(hx/has-class? % "skeleton"))) title))))

;; ---------------------------------------------------------------------------
;; Text of any length, and messages in narrow columns (found by looking at the page at 390 px with a name that has no spaces in it)

(def ^:private long-word (apply str (repeat 90 "Ñandú")))

(defn- wraps? [element] (hx/has-class? element "wrap-anywhere"))

(deftest what-someone-wrote-breaks-where-it-must-and-never-widens-the-page
  (let [state (-> (fx/settled-ana)
                  (assoc-in [:patient :data :name] [{:family long-word :given ["Ana"]}])
                  (assoc-in [:patient :data :address] [{:city long-word}])
                  (assoc-in [:summary :data :conditions] [{:id "c" :display long-word :code "I10"}])
                  (assoc-in [:summary :data :medications] [{:id "m" :display long-word :dosage long-word}])
                  (assoc-in [:summary :data :allergies] [{:id "a" :display long-word :reaction long-word}])
                  (assoc-in [:summary :data :alerts] [{:id "x" :text long-word :status "active" :since "2026-10-12T20:30:00Z"}])
                  (assoc-in [:summary :data :appointments] [{:id "p" :status "booked" :description long-word :reason long-word
                                                             :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z"}]))
        tree (content state)
        header (first (hx/by-tag tree :header))
        ;; the alert of a rule nobody knows is the only message on the page
        alert (first (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "rounded-box") (hx/has-class? % "grid-cols-[auto_1fr]"))))
        spans-of (fn [element] (hx/select element #(and (= :span (hx/tag %)) (wraps? %))))]
    (testing "the heading and the line under it"
      (is (seq (spans-of (first (hx/by-tag header :h1)))))
      (is (seq (spans-of (first (hx/by-tag header :p)))) "the number and the city of the patient too"))
    (testing "the breadcrumb"
      (is (seq (spans-of (last (hx/by-tag (first (hx/select tree #(= "Breadcrumb" (:aria-label (hx/attributes %))))) :li))))))
    (testing "the condition is a badge that grows with its text, and breaks it"
      (let [badge (first (hx/select (first (hx/select tree #(= "Conditions" (:aria-label (hx/attributes %))))) #(hx/has-class? % "badge")))]
        (is (every? #(hx/has-class? badge %) ["h-auto" "whitespace-normal" "wrap-anywhere"]))))
    (testing "the alert"
      (is (seq (spans-of (first (hx/by-tag alert :p)))) "its title")
      (is (every? wraps? (rest (hx/by-tag alert :p))) "and the lines under it, which hold what the rule and the readings say"))
    (testing "every line of every row of the lists"
      (doseq [title ["Medication" "Allergies" "Appointments"]
              :let [rows (hx/by-tag (card-named tree title) :li)]]
        (is (seq rows) title)
        (is (every? wraps? (mapcat #(hx/by-tag % :p) rows)) title)))))

(deftest a-message-in-a-narrow-column-has-its-button-under-the-text-and-a-wide-one-beside-it
  (let [tree (content (assoc (fx/settled-ana) :summary {:state :failed :kind :server}))
        messages (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "rounded-box") (hx/has-class? % "grid-cols-[auto_1fr]")))
        by-title (into {} (map (fn [m] [(words (first (hx/by-tag m :p))) m])) messages)
        beside? (fn [title] (hx/has-class? (get by-title title) "sm:grid-cols-[auto_1fr_auto]"))]
    (is (= #{"The conditions could not be loaded" "The alerts could not be loaded" "The latest readings could not be loaded"
             "The medication could not be loaded" "The allergies could not be loaded" "The appointments could not be loaded"}
           (set (keys by-title))))
    (testing "the whole width: the button is at the end of the row"
      (is (every? beside? ["The conditions could not be loaded" "The alerts could not be loaded" "The latest readings could not be loaded"])))
    (testing "a card in a column of a third of the screen: the button is under the text, and is still there"
      (doseq [title ["The medication could not be loaded" "The allergies could not be loaded" "The appointments could not be loaded"]]
        (is (not (beside? title)) title)
        (is (= ["Try again"] (map words (hx/by-tag (get by-title title) :button))) title)))))
