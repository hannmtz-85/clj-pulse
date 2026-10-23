(ns pulse.web.readings-details-test
  "What a mutation analysis of the readings found that the first tests did not hold: where the axis of the chart of a period begins (the
  mock-up and the page are made by the same function, so a test that compares them is blind to a change of it), what the chart for a
  phone leaves out, a clock that has fractions of a second, and the level at which a failure is logged."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.pages.readings :as readings]
            [pulse.web.test.cohort :refer [at! events queries signed-in with-cohort]]
            [pulse.web.test.harness :as h]))

(def ^:private links {:readings "/readings" :new-reading "/readings/new" :retry "/readings"})

(defn- wide-figure [tree] (first (hx/by-tag tree :figure)))

(defn- narrow-figure [tree] (second (hx/by-tag tree :figure)))

(defn- tick-labels
  "The labels of the axis of time of a figure."
  [figure]
  (map hx/words (hx/select (first (hx/by-tag figure :svg)) #(and (= :text (hx/tag %)) (= "middle" (:text-anchor (hx/attributes %)))))))

(defn- plot-left [figure]
  (let [grid (first (hx/select (first (hx/by-tag figure :svg)) #(hx/has-class? % "stroke-chart-grid")))]
    (parse-double (:x1 (hx/attributes grid)))))

(defn- first-x
  "Where the first point of the first line of a figure is."
  [figure]
  (let [path (first (hx/select (first (hx/by-tag figure :svg)) #(and (= :path (hx/tag %)) (= "none" (:fill (hx/attributes %))))))]
    (parse-double (second (re-find #"M(-?[\d.]+)," (:d (hx/attributes path)))))))

;; ---------------------------------------------------------------------------
;; The axis begins where the period does

(deftest the-axis-of-the-chart-of-a-period-begins-at-the-start-of-the-period
  (testing "seven days, from the same hour a week ago"
    (let [tree (readings/content (portal/readings-model "pt-ana" :blood-pressure 7) links)]
      (is (= ["Oct 7" "Oct 8" "Oct 9" "Oct 10" "Oct 11" "Oct 12" "Oct 13"] (tick-labels (wide-figure tree))))))
  (testing "thirty days"
    (let [tree (readings/content (portal/readings-model "pt-ana" :blood-pressure 30) links)]
      (is (= ["Sep 18" "Sep 23" "Sep 28" "Oct 3" "Oct 8" "Oct 13"] (tick-labels (wide-figure tree))))))
  (testing "and a reading that is older than the start of the period is not at the start"
    (let [tree (readings/content (portal/readings-model "pt-ana" :blood-pressure 7) links)]
      (is (< (plot-left (wide-figure tree)) (first-x (wide-figure tree))) "the oldest reading of the week is not on the edge of the axis"))))

(defn- cut-model
  "The readings of a heart rate that the api cut: 500 of them, every 30 seconds up to the moment of the mock-ups."
  [truncated?]
  {:now data/now-text :kind :heart-rate :days 30 :summary (data/summary "pt-ana")
   :series {:items (vec (for [i (range 500)]
                          {:id (str "r" i) :at (str (java.time.Instant/ofEpochSecond (- (.getEpochSecond data/now) (* 30 i))))
                           :measurement "heart-rate" :value (+ 60 (mod i 20)) :unit "/min" :band "normal"}))
            :truncated truncated?}})

(deftest the-axis-of-a-series-that-was-cut-begins-at-its-oldest-reading
  (let [cut (readings/content (cut-model true) links)
        whole (readings/content (cut-model false) links)]
    (is (= (plot-left (wide-figure cut)) (first-x (wide-figure cut))) "the oldest reading is the first thing on the axis")
    (is (= ["10:00" "11:00" "12:00" "13:00" "14:00"] (tick-labels (wide-figure cut))) "of four hours and a half, in hours")
    (testing "and a series that was not cut begins where the period does"
      (is (< (plot-left (wide-figure whole)) (first-x (wide-figure whole))))
      (is (= ["Sep 18" "Sep 23" "Sep 28" "Oct 3" "Oct 8" "Oct 13"] (tick-labels (wide-figure whole)))))))

;; ---------------------------------------------------------------------------
;; The table and the card with nothing in it

(deftest the-table-of-the-last-readings-is-tight-and-says-what-its-columns-are
  (let [tree (readings/content (portal/readings-model "pt-ana" :blood-pressure 30) links)
        table (first (filter #(str/includes? (hx/words (first (hx/by-tag % :caption))) "the last") (hx/by-tag tree :table)))]
    (is (hx/has-class? table "table-sm"))
    (is (= ["When" "Reading" "Compared with the rangeRange"] (map hx/words (hx/by-tag (first (hx/by-tag table :thead)) :th))))))

(deftest the-card-of-a-measurement-with-no-readings-says-the-period
  (let [tree (readings/content {:now data/now-text :kind :glucose :days 14 :summary (data/summary "pt-ana")
                                :series {:items [] :truncated false}}
                               links)]
    (is (str/includes? (hx/words tree) "GlucoseLast 14 days"))))

;; ---------------------------------------------------------------------------
;; The chart for a phone

(deftest the-chart-for-a-phone-leaves-out-the-range-of-normal-values-and-the-one-for-a-desk-has-it
  (let [tree (readings/content (portal/readings-model "pt-ana" :heart-rate 30) links)]
    (is (str/includes? (hx/words (wide-figure tree)) "Normal"))
    (is (not (str/includes? (hx/words (narrow-figure tree)) "Normal")))))

;; ---------------------------------------------------------------------------
;; The mock-up has the period it says

(deftest the-readings-of-the-mock-up-are-those-of-the-period-it-says
  (let [week (portal/readings-model "pt-ana" :blood-pressure 7)
        month (portal/readings-model "pt-ana" :blood-pressure 30)
        ats (fn [model] (map :at (get-in model [:series :items])))]
    (is (< (count (ats week)) (count (ats month))))
    (is (every? #(>= (compare % "2026-10-06T12:00:00Z") 0) (ats week)) "nothing from before the week")
    (is (some #(neg? (compare % "2026-10-06T12:00:00Z")) (ats month)) "and the month has what the week has not")
    (is (= 500 readings/series-limit))))

;; ---------------------------------------------------------------------------
;; Through the handler

(deftest the-moment-the-period-is-counted-from-is-to-the-second
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (at! web "2026-10-13T12:00:00.789Z")
      (let [b (signed-in web)]
        (h/visit! b :get "/readings" :query "days=7")
        (is (= "2026-10-06T12:00:00Z" (get (second (last (queries web))) "from")) "no fraction of a second in what the api is asked")))))

(deftest a-failure-the-page-cannot-go-on-after-is-an-error-in-the-log-and-one-it-can-is-a-warning
  (doseq [[answer level] [[(h/json-answer 503 {:title "Service Unavailable" :status 503}) :warn]
                          [(h/json-answer 403 {:title "Forbidden" :status 403}) :error]
                          [(h/json-answer 404 {:title "Not Found" :status 404}) :error]]]
    (with-cohort "pt-ana" {:record answer} {}
      (fn [web]
        (let [b (signed-in web)
              response (h/visit! b :post "/readings/new"
                                 :form {"__anti-forgery-token" (pulse.web.test.cohort/csrf-of web b) "measurement" "heart-rate" "value" "72"})]
          (is (= [level] (map :level (events response :pulse.web/api-failed))) (str (:status answer))))))))
