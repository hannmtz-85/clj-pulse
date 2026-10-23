(ns pulse.web.readings-page-test
  "The readings of the portal as a function of the data in the shapes of the api (ADR-0028): what the page shows of a measurement over
  a period, how it says that the api could not give it or cut it, what it offers to look at, and where its buttons lead."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.html :as html]
            [pulse.web.pages.readings :as readings]
            [pulse.web.views :as views]))

(def ^:private links
  {:readings "/readings" :new-reading "/readings/new" :retry "/readings?measurement=blood-pressure&days=30"})

(defn- model [patient kind days] (portal/readings-model patient kind days))

(def ^:private ana-bp (model "pt-ana" :blood-pressure 30))

(defn- buttons [tree]
  (hx/select tree #(and (hx/has-class? % "btn") (#{:a :button} (hx/tag %)))))

(defn- link-named [tree text]
  (first (filter #(= text (hx/words %)) (concat (buttons tree) (hx/by-tag tree :a)))))

(defn- href [element] (:href (hx/attributes element)))

(defn- nav-named [tree label]
  (first (hx/select tree #(and (= :nav (hx/tag %)) (= label (:aria-label (hx/attributes %)))))))

(defn- pills [tree label]
  (for [a (hx/by-tag (nav-named tree label) :a)]
    {:label (hx/words a) :href (href a) :current? (= "page" (:aria-current (hx/attributes a)))}))

(defn- alerts [tree]
  (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-high-soft"))))

(defn- figures [tree] (hx/by-tag tree :figure))

(defn- stats [tree]
  (for [stat (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-card") (hx/has-class? % "p-4") (not (hx/has-class? % "relative"))))]
    (map hx/words (hx/by-tag stat :p))))

(defn- last-readings-table
  "The table of the last readings: the page has the tables of its charts as well, which come first."
  [tree]
  (first (filter #(str/includes? (hx/words (first (hx/by-tag % :caption))) "the last") (hx/by-tag tree :table))))

(defn- table-rows [tree]
  (for [row (hx/by-tag (first (hx/by-tag (last-readings-table tree) :tbody)) :tr)]
    (map hx/words (hx/select row #(#{:th :td} (hx/tag %))))))

(defn- table-caption [tree] (hx/words (first (hx/by-tag (last-readings-table tree) :caption))))

(defn- reading [id at value]
  {:id id :at at :value value :unit "/min" :band "normal" :measurement "heart-rate" :by "Patient/pt-x"})

(defn- heart-rate-model
  "A model of the readings of a heart rate with `items` (newest first, as the api gives them)."
  [items & {:keys [truncated days summary] :or {days 7 truncated false}}]
  {:now data/now-text :kind :heart-rate :days days
   :summary (or summary (data/summary "pt-ana"))
   :series {:items items :truncated truncated}})

;; ---------------------------------------------------------------------------
;; What the page offers

(deftest the-page-is-about-the-readings-of-the-patient-and-has-a-way-to-record-one
  (let [tree (readings/content ana-bp links)]
    (is (= ["My readings"] (map hx/words (hx/by-tag tree :h1))))
    (is (str/includes? (hx/words (first (hx/by-tag tree :header))) "What you and your devices have recorded."))
    (testing "the button to record one takes the measurement that is being looked at"
      (is (= "/readings/new?measurement=blood-pressure" (href (link-named tree "Record a reading"))))
      (is (= "/readings/new?measurement=heart-rate"
             (href (link-named (readings/content (assoc ana-bp :kind :heart-rate) links) "Record a reading")))))
    (testing "and there is none without a page for it"
      (is (nil? (link-named (readings/content ana-bp (dissoc links :new-reading)) "Record a reading"))))))

(deftest the-patient-can-choose-between-the-measurements-they-have-and-the-periods
  (let [tree (readings/content ana-bp links)]
    (is (= [{:label "Blood pressure" :href "/readings?measurement=blood-pressure&days=30" :current? true}
            {:label "Heart rate" :href "/readings?measurement=heart-rate&days=30" :current? false}
            {:label "Weight" :href "/readings?measurement=weight&days=30" :current? false}]
           (pills tree "Measurement")))
    (is (= [{:label "7 days" :href "/readings?measurement=blood-pressure&days=7" :current? false}
            {:label "14 days" :href "/readings?measurement=blood-pressure&days=14" :current? false}
            {:label "30 days" :href "/readings?measurement=blood-pressure&days=30" :current? true}]
           (pills tree "Period")))
    (testing "each is as large as a finger"
      (is (every? #(hx/has-class? % "min-h-11") (concat (hx/by-tag (nav-named tree "Measurement") :a) (hx/by-tag (nav-named tree "Period") :a))))))
  (testing "the current ones are the ones being looked at"
    (let [tree (readings/content (assoc ana-bp :kind :weight :days 7) links)]
      (is (= ["Weight"] (map :label (filter :current? (pills tree "Measurement")))))
      (is (= ["7 days"] (map :label (filter :current? (pills tree "Period"))))))))

(deftest the-measurements-on-offer-are-those-the-patient-has-readings-of
  (testing "what the summary has a reading of, in the order the portal shows them"
    (is (= [:blood-pressure :heart-rate :weight] (readings/offered-kinds (data/summary "pt-ana") nil)))
    (is (= [:blood-pressure :glucose :weight] (readings/offered-kinds (data/summary "pt-liam") nil)))
    (is (= [:heart-rate :spo2] (readings/offered-kinds (data/summary "pt-sofia") nil))))
  (testing "and the one that was asked for, whatever the summary says"
    (is (= [:blood-pressure :heart-rate :glucose :weight] (readings/offered-kinds (data/summary "pt-ana") :glucose)))
    (is (= [:temperature] (readings/offered-kinds (data/summary "pt-noah") :temperature)))
    (is (= [:blood-pressure :heart-rate :weight] (readings/offered-kinds (data/summary "pt-ana") :heart-rate)) "which is there already, once"))
  (testing "a patient who has none is offered nothing"
    (is (= [] (readings/offered-kinds (data/summary "pt-noah") nil))))
  (testing "a reading the summary could not load may be one: its measurement is offered"
    (let [lost (-> (data/summary "pt-noah") (assoc :unavailable ["appointments" "vitals.glucose" "vitals.spo2"]))]
      (is (= [:spo2 :glucose] (readings/offered-kinds lost nil)) "in the order of the portal, not of the summary")
      (is (= [:glucose] (readings/offered-kinds (assoc lost :unavailable ["vitals.glucose" "vitals.nonsense" "alerts"]) nil))
          "and nothing that is not a measurement")))
  (testing "with no summary at all, every measurement: nobody knows which have readings"
    (is (= views/kinds (readings/offered-kinds nil nil)))
    (is (= views/kinds (readings/offered-kinds nil :weight)))))

;; ---------------------------------------------------------------------------
;; The trend

(deftest a-measurement-is-a-chart-for-a-phone-and-another-for-a-desk-with-the-figures-and-the-last-readings
  (let [tree (readings/content ana-bp links)
        [wide narrow] (figures tree)]
    (testing "a card with the name of the measurement and the period"
      (is (= ["Blood pressure"] (map hx/words (hx/select tree #(and (= :h2 (hx/tag %)) (hx/has-class? % "text-base"))))))
      (is (str/includes? (hx/words tree) "Last 30 days")))
    (testing "two charts of the one series, each in the box that shows it on its own width"
      (is (= ["chart-blood-pressure-wide-title" "chart-blood-pressure-narrow-title"]
             (map #(:id (hx/attributes (first (hx/by-tag % :title)))) [wide narrow])))
      (is (= ["0 0 950 320" "0 0 318 260"] (map #(:viewBox (hx/attributes (first (hx/by-tag % :svg)))) [wide narrow])))
      (let [boxes (filter #(and (= :div (hx/tag %)) (or (hx/has-class? % "md:hidden") (hx/has-class? % "md:block"))) (hx/select tree (constantly true)))]
        (is (= [["hidden" "md:block"] ["md:hidden"]] (map #(sort (remove #{"space-y-6"} (hx/classes %))) boxes)))))
    (testing "the desk has the rules and the range of normal values in its margin, the phone does not"
      (is (str/includes? (hx/words wide) "Stage 2 · 160"))
      (is (not (str/includes? (hx/words narrow) "Stage 2 · 160"))))
    (testing "the figures of the period"
      (is (= [["Latest" "177/100 mmHg" "Today, 10:00"]
              ["Highest, 30 days" "177/100 mmHg" "Today, 10:00"]
              ["Lowest, 30 days" "131/82 mmHg" "Sep 30, 22:00"]]
             (stats tree))))
    (testing "the last eight, newest first, each with how it compares with the range"
      (is (= "Blood pressure, the last 8 readings" (table-caption tree)))
      (is (= 8 (count (table-rows tree))))
      (is (= ["Today, 10:00" "177/100 mmHg" "Very high"] (first (table-rows tree))))
      (is (= ["Yesterday, 22:00" "172/102 mmHg" "Very high"] (second (table-rows tree)))))
    (is (= [] (a11y/problems [:main tree])))))

(deftest the-figures-and-the-words-follow-the-period
  (let [tree (readings/content (model "pt-ana" :blood-pressure 7) links)]
    (is (str/includes? (hx/words tree) "Last 7 days"))
    (is (= ["Latest" "Highest, 7 days" "Lowest, 7 days"] (map first (stats tree))))
    (is (= "Blood pressure, last 7 days" (hx/words (first (hx/by-tag (first (figures tree)) :title)))))
    (is (str/includes? (hx/words (first (hx/by-tag (first (figures tree)) :desc))) "Blood pressure over the last 7 days"))))

(deftest the-last-readings-are-as-many-as-there-are-up-to-eight
  (let [items (fn [n] (for [i (range n)] (reading (str "r" i) (str "2026-10-13T11:" (format "%02d" (- 59 i)) ":00Z") (+ 60 i))))
        page (fn [n] (readings/content (heart-rate-model (items n)) links))]
    (is (= [8 "Heart rate, the last 8 readings"] (let [tree (page 12)] [(count (table-rows tree)) (table-caption tree)])) "of twelve, eight")
    (is (= ["Today, 13:59" "Today, 13:58"] (map first (take 2 (table-rows (page 12))))) "newest first")
    (is (= [3 "Heart rate, the last 3 readings"] (let [tree (page 3)] [(count (table-rows tree)) (table-caption tree)])))
    (is (= [1 "Heart rate, the last reading"] (let [tree (page 1)] [(count (table-rows tree)) (table-caption tree)])) "and one is not readings")
    (is (some #{"The last reading"} (map hx/words (hx/by-tag (page 1) :h2))))
    (is (not-any? #{"The last readings"} (map hx/words (hx/by-tag (page 1) :h2))))
    (is (some #{"The last readings"} (map hx/words (hx/by-tag (page 3) :h2))))))

(deftest a-measurement-with-one-number-is-told-by-that-number
  (let [tree (readings/content (heart-rate-model [(reading "a" "2026-10-13T10:00:00Z" 72)
                                                  (reading "b" "2026-10-13T08:00:00Z" 58)
                                                  (reading "c" "2026-10-12T20:00:00Z" 66)])
                               links)]
    (is (= [["Latest" "72 bpm" "Today, 12:00"] ["Highest, 7 days" "72 bpm" "Today, 12:00"] ["Lowest, 7 days" "58 bpm" "Today, 10:00"]]
           (stats tree)))
    (is (= [["Today, 12:00" "72 bpm" "Normal"] ["Today, 10:00" "58 bpm" "Normal"] ["Yesterday, 22:00" "66 bpm" "Normal"]]
           (table-rows tree)))))

(deftest a-reading-without-a-band-has-a-dash-where-the-comparison-would-be
  (let [tree (readings/content (assoc ana-bp :kind :weight) links)]
    (is (every? #(= "–" (last %)) (table-rows tree)) "the weight has no reference range")))

(deftest readings-of-one-moment-are-in-the-same-order-whichever-order-the-api-gave-them-in
  (let [same "2026-10-13T10:00:00Z"
        a (reading "obs-a" same 80)
        b (reading "obs-b" same 60)
        page (fn [items] (readings/content (heart-rate-model items) links))]
    (is (= (page [a b]) (page [b a])) "a server does not order them, and the page must not depend on how it happened to")
    (is (= [["Latest" "60 bpm" "Today, 12:00"] ["Highest, 7 days" "80 bpm" "Today, 12:00"] ["Lowest, 7 days" "60 bpm" "Today, 12:00"]]
           (stats (page [a b]))))))

;; ---------------------------------------------------------------------------
;; A series that was cut

(defn- many [n]
  (for [i (range n)]
    (reading (str "r" i) (str (java.time.Instant/ofEpochSecond (- (.getEpochSecond data/now) (* 30 i)))) (+ 60 (mod i 20)))))

(deftest a-series-the-api-cut-is-shown-as-the-latest-readings-and-says-so
  (let [items (vec (many readings/series-limit))
        tree (readings/content (heart-rate-model items :truncated true :days 30) links)
        oldest (:at (peek items))]
    (is (= 500 (count items)))
    (is (= "2026-10-13T07:50:30Z" oldest) "four hours and a half of readings, every thirty seconds")
    (is (str/includes? (hx/words tree) "Your latest 500 readings, since today, 09:50"))
    (is (not (str/includes? (hx/words tree) "Last 30 days")))
    (testing "the charts are of the stretch the readings cover, and say what they are of"
      (is (= ["Heart rate, latest 500 readings" "Heart rate, latest 500 readings"]
             (map #(hx/words (first (hx/by-tag % :title))) (figures tree))))
      (is (= "Heart rate over the latest 500 readings: between 60 and 79 bpm; the latest, today, 14:00, is 60 bpm."
             (hx/words (first (hx/by-tag (first (figures tree)) :desc))))))
    (testing "the figures say what they are of, and do not say 30 days"
      (is (= ["Latest" "Highest in these readings" "Lowest in these readings"] (map first (stats tree)))))
    (testing "and the pills of the period are still there, since another period is another stretch"
      (is (= ["7 days" "14 days" "30 days"] (map :label (pills tree "Period")))))))

(deftest a-series-that-was-not-cut-is-not-said-to-be
  (let [tree (readings/content (heart-rate-model (many 10) :truncated false :days 14) links)]
    (is (str/includes? (hx/words tree) "Last 14 days"))
    (is (not (str/includes? (hx/words tree) "latest 10 readings")))
    (is (not (str/includes? (hx/words tree) "in these readings")))))

;; ---------------------------------------------------------------------------
;; Nothing to show, and what could not be loaded

(deftest a-measurement-with-no-readings-in-the-period-says-so-and-draws-nothing
  (let [tree (readings/content (heart-rate-model [] :days 14) links)]
    (is (str/includes? (hx/words tree) "No heart rate readings in the last 14 days"))
    (is (str/includes? (hx/words tree) "When you record one, or a device sends one, you will see it here."))
    (is (empty? (figures tree)))
    (is (empty? (hx/by-tag tree :table)))
    (is (empty? (stats tree)))
    (is (empty? (alerts tree)) "it is not a failure")
    (is (= "/readings/new?measurement=heart-rate" (href (link-named tree "Record a reading"))) "the button of the page is there")
    (is (= ["Heart rate"] (map :label (filter :current? (pills tree "Measurement")))))
    (is (= [] (a11y/problems [:main tree])))))

(deftest readings-that-say-nothing-are-no-readings
  (let [tree (readings/content (heart-rate-model [{:id "x" :at "2026-10-13T10:00:00Z" :unit "/min" :measurement "heart-rate"}
                                                  (reading "y" nil 70)
                                                  (reading "z" "later" 70)])
                               links)]
    (is (str/includes? (hx/words tree) "No heart rate readings in the last 7 days"))
    (is (empty? (figures tree)))))

(deftest readings-the-api-could-not-give-are-a-message-and-no-chart-and-no-empty-table
  (let [tree (readings/content (assoc ana-bp :series nil) links)]
    (is (= 1 (count (alerts tree))))
    (is (str/includes? (hx/words (first (alerts tree))) "Your blood pressure readings could not be loaded"))
    (is (str/includes? (hx/words (first (alerts tree))) "The problem is on our side, not yours."))
    (is (not (str/includes? (hx/words (first (alerts tree))) "The rest of the page")) "there is no rest to speak of")
    (is (empty? (figures tree)))
    (is (empty? (hx/by-tag tree :table)))
    (is (empty? (stats tree)))
    (is (not (str/includes? (hx/words tree) "No blood pressure readings")) "which would say that there are none")
    (is (= "/readings?measurement=blood-pressure&days=30" (href (link-named tree "Try again"))) "to try again is to ask for the page again")
    (testing "the choices are still there: another measurement may load"
      (is (= ["Blood pressure" "Heart rate" "Weight"] (map :label (pills tree "Measurement")))))
    (testing "and the way to try again is not there when the page has no address for it"
      (is (nil? (link-named (readings/content (assoc ana-bp :series nil) (dissoc links :retry)) "Try again"))))
    (is (= [] (a11y/problems [:main tree])))))

(deftest a-patient-with-no-readings-at-all-is-told-what-to-do
  (let [tree (readings/content {:now data/now-text :kind nil :days 30 :summary (data/summary "pt-noah") :series nil} links)]
    (is (= ["My readings"] (map hx/words (hx/by-tag tree :h1))))
    (is (str/includes? (hx/words tree) "No readings yet"))
    (is (str/includes? (hx/words tree) "When you record one, or a device sends one, you will see it here and how it compares with the usual range."))
    (is (= ["/readings/new" "/readings/new"] (map href (filter #(= "Record a reading" (hx/words %)) (buttons tree))))
        "the button of the page and the one of the card, to the form with no measurement chosen")
    (is (nil? (nav-named tree "Measurement")) "nothing to choose")
    (is (nil? (nav-named tree "Period")))
    (is (empty? (figures tree)))
    (is (empty? (alerts tree)))
    (is (= [] (a11y/problems [:main tree])))))

;; ---------------------------------------------------------------------------
;; A reading that has just been recorded

(deftest a-reading-that-was-just-recorded-is-told
  (let [saved {:id "obs-1" :measurement "blood-pressure" :at "2026-10-13T11:30:00Z" :systolic 132 :diastolic 88 :unit "mm[Hg]" :band "elevated"}
        tree (readings/content (assoc ana-bp :saved saved) links)
        notice (first (hx/select tree #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-normal-soft"))))]
    (is (some? notice))
    (is (= "status" (:role (hx/attributes notice))) "a screen reader says it when the page opens")
    (is (= "Reading savedBlood pressure, 132/88 mmHg, today, 13:30." (hx/words notice)))
    (testing "it comes after the title and before the choices"
      (is (= [:header :div :div :div] (map hx/tag (filter hx/element? (hx/child-nodes tree)))))
      (is (= notice (second (filter hx/element? (hx/child-nodes tree)))))))
  (testing "of another measurement"
    (let [notice (fn [saved] (hx/words (first (hx/select (readings/content (assoc ana-bp :saved saved) links) #(= "status" (:role (hx/attributes %)))))))]
      (is (= "Reading savedHeart rate, 72 bpm, yesterday, 08:00." (notice {:measurement "heart-rate" :at "2026-10-12T06:00:00Z" :value 72 :unit "/min"})))
      (is (= "Reading savedTemperature, 36.6 °C, today, 09:00." (notice {:measurement "temperature" :at "2026-10-13T07:00:00Z" :value 36.6 :unit "Cel"})))))
  (testing "nothing is said of what is not a reading, or of what has no time to tell"
    (let [notice (fn [saved] (hx/select (readings/content (assoc ana-bp :saved saved) links) #(= "status" (:role (hx/attributes %)))))]
      (is (empty? (notice nil)))
      (is (empty? (notice {:measurement "nonsense" :value 1})))
      (is (empty? (notice {:value 1})))
      (is (= "Reading saved" (hx/words (first (notice {:measurement "heart-rate" :value nil})))) "the title, and no words about a reading it cannot read")
      (is (= "Reading savedHeart rate, 72 bpm." (hx/words (first (notice {:measurement "heart-rate" :value 72 :unit "/min" :at "yesterday"}))))))))

;; ---------------------------------------------------------------------------
;; Whatever the api says is only ever text

(deftest what-the-api-says-is-text-and-nothing-more
  (let [hostile "<script>alert(1)</script>"
        odd (assoc (reading "x" "2026-10-13T10:00:00Z" 70) :unit hostile :band hostile :id hostile)
        text (html/render [:main (readings/content (heart-rate-model [odd]) links)])]
    (is (not (str/includes? text "<script>")))
    (is (str/includes? text "&lt;script&gt;") "it is escaped where it is shown")))

(deftest every-state-of-the-page-passes-the-checks-of-accessibility
  (doseq [[what the-model page-links]
          [["a measurement" ana-bp links]
           ["another period" (model "pt-ana" :heart-rate 7) links]
           ["with no link" ana-bp {:readings "/readings"}]
           ["a series that was cut" (heart-rate-model (vec (many 500)) :truncated true) links]
           ["no readings" (heart-rate-model []) links]
           ["a series that could not be loaded" (assoc ana-bp :series nil) links]
           ["no summary" (assoc ana-bp :summary nil) links]
           ["no readings at all" {:now data/now-text :kind nil :days 30 :summary (data/summary "pt-noah") :series nil} links]
           ["a reading just saved" (assoc ana-bp :saved {:measurement "weight" :value 82.3 :unit "kg" :at data/now-text}) links]
           ["every measurement of the cohort" (model "pt-liam" :glucose 30) links]
           ["oxygen" (model "pt-sofia" :spo2 14) links]]]
    (testing what
      (is (= [] (a11y/problems [:main (readings/content the-model page-links)]))))))
