(ns pulse.web.charts-test
  "The chart of a measurement over a stretch of time, from the readings the api answered (ADR-0028): the axis of time for any stretch,
  the readings that are drawn and the ones that are left out, and the words for a screen reader. What a chart of the cohort looks
  like, measurement by measurement, is held by the tests of the mock-ups (`pulse.design.specimen-test`), which call this."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.charts :as charts]
            [pulse.web.format :as fmt]))

(def ^:private now "2026-10-13T12:00:00Z")

(def ^:private day (* 24 3600 1000))

(defn- millis [instant] (fmt/millis instant))

;; ---------------------------------------------------------------------------
;; The axis of time

(defn- labels [from-text to-text max-ticks]
  (map :label (charts/x-ticks (millis from-text) (millis to-text) max-ticks)))

(deftest the-axis-of-a-stretch-of-days-is-midnights-every-so-many-days
  (testing "30 days, on a desk and on a phone"
    (is (= ["Sep 18" "Sep 23" "Sep 28" "Oct 3" "Oct 8" "Oct 13"] (labels "2026-09-13T12:00:00Z" now 7)))
    (is (= ["Sep 23" "Oct 3" "Oct 13"] (labels "2026-09-13T12:00:00Z" now 3))))
  (testing "14 days"
    (is (= ["Oct 1" "Oct 3" "Oct 5" "Oct 7" "Oct 9" "Oct 11" "Oct 13"] (labels "2026-09-29T12:00:00Z" now 7)))
    (is (= ["Oct 4" "Oct 9"] (labels "2026-09-29T12:00:00Z" now 3))))
  (testing "7 days"
    (is (= ["Oct 7" "Oct 8" "Oct 9" "Oct 10" "Oct 11" "Oct 12" "Oct 13"] (labels "2026-10-06T12:00:00Z" now 7)))
    (is (= ["Oct 8" "Oct 10" "Oct 12"] (labels "2026-10-06T12:00:00Z" now 3))))
  (testing "two days"
    (is (= ["Oct 12" "Oct 13"] (labels "2026-10-11T12:00:00Z" now 3)))))

(deftest the-axis-of-a-stretch-of-hours-is-whole-hours
  (is (= ["10:00" "11:00" "12:00" "13:00" "14:00"] (labels "2026-10-13T07:30:00Z" now 7)) "four hours and a half")
  (is (= ["10:00" "12:00" "14:00"] (labels "2026-10-13T07:30:00Z" now 3)))
  (is (= ["02:00" "04:00" "06:00" "08:00" "10:00" "12:00" "14:00"] (labels "2026-10-12T22:30:00Z" now 7)) "thirteen hours and a half")
  (is (= ["06:00" "12:00"] (labels "2026-10-12T22:30:00Z" now 3)) "and on a phone")
  (testing "the last whole hour is there, whatever the minutes the stretch begins at"
    (is (= ["12:00" "13:00" "14:00"] (labels "2026-10-13T09:30:00Z" now 3)))
    (is (= "14:00" (last (labels "2026-10-13T09:45:00Z" now 7))))))

(deftest the-axis-of-a-short-stretch-is-minutes
  (is (= ["13:50" "13:55" "14:00"] (labels "2026-10-13T11:50:00Z" now 7)) "ten minutes")
  (is (= ["13:40" "13:45" "13:50" "13:55" "14:00"] (labels "2026-10-13T11:40:00Z" now 7)))
  (is (= ["13:40" "13:50" "14:00"] (labels "2026-10-13T11:40:00Z" now 3)))
  (is (= ["12:30" "12:45" "13:00" "13:15" "13:30" "13:45" "14:00"] (labels "2026-10-13T10:30:00Z" now 7)) "an hour and a half")
  (is (= ["13:00" "14:00"] (labels "2026-10-13T10:30:00Z" now 3)) "whole hours when the minutes are too many for a phone")
  (testing "when no step has few enough ticks, it is the coarsest that has two, and not an axis with no labels"
    (is (= ["13:00" "14:00"] (labels "2026-10-13T10:30:00Z" now 1)))
    (is (= ["10:00" "12:00" "14:00"] (labels "2026-10-13T07:30:00Z" now 2)) "every two hours is the coarsest step with two ticks or more: every three has one")))

(deftest each-step-of-the-axis-is-the-one-chosen-when-it-is-the-smallest-that-fits
  (testing "days: every week, and every fortnight, when the smaller steps are too many for a phone"
    (is (= ["Sep 29" "Oct 6" "Oct 13"] (labels "2026-09-22T12:00:00Z" now 3)) "three weeks: a tick a week")
    (is (= ["Sep 17" "Oct 1"] (labels "2026-09-03T12:00:00Z" now 3)) "forty days: a tick a fortnight"))
  (testing "hours: every three"
    (is (= ["03:00" "06:00" "09:00" "12:00"] (labels "2026-10-12T22:30:00Z" now 5))))
  (testing "minutes: every thirty"
    (is (= ["12:30" "13:00" "13:30" "14:00"] (labels "2026-10-13T10:30:00Z" now 4))))
  (testing "the coarsest step with two ticks, when even it has more than the room for"
    (is (= ["Aug 28" "Sep 11" "Sep 25" "Oct 9"] (labels "2026-08-14T12:00:00Z" now 3)) "sixty days on a phone")))

(deftest a-stretch-too-short-for-two-ticks-has-none
  (is (= [] (labels "2026-10-13T11:58:00Z" now 7)))
  (is (= [] (labels "2026-10-13T12:00:00Z" now 7)) "and a stretch of nothing"))

(deftest the-ticks-are-at-the-clinics-midnights-through-the-change-of-the-clocks
  (testing "the clocks went back in the night of Sunday 25 October, so that day has 25 hours and the next midnight is 25 hours on"
    (let [ticks (charts/x-ticks (millis "2026-10-22T10:00:00Z") (millis "2026-10-27T10:00:00Z") 7)]
      (is (= ["Oct 23" "Oct 24" "Oct 25" "Oct 26" "Oct 27"] (map :label ticks)))
      (is (= [(* 24 3600 1000) (* 24 3600 1000) (* 25 3600 1000) (* 24 3600 1000)] (map - (rest (map :at ticks)) (map :at ticks)))))))

(defspec the-ticks-are-in-order-inside-the-stretch-and-few-enough-for-any-stretch-from-ten-minutes-to-a-month 300
  (prop/for-all [from (gen/choose (millis "2026-01-01T00:00:00Z") (millis "2026-12-31T00:00:00Z"))
                 length (gen/one-of [(gen/choose (* 10 60 1000) (* 2 day))
                                     (gen/choose (* 2 day) (* 30 day))])
                 max-ticks (gen/elements [3 7])]
    (let [ticks (charts/x-ticks from (+ from length) max-ticks)
          ats (map :at ticks)]
      (and (<= 2 (count ticks) (inc max-ticks))
           (apply < ats)
           (every? #(<= from % (+ from length)) ats)
           (every? (comp seq :label) ticks)))))

;; ---------------------------------------------------------------------------
;; The chart

(defn- reading [id at value]
  {:id id :at at :value value :unit "/min" :band "normal"})

(def ^:private heart-rate
  [(reading "a" "2026-10-12T08:00:00Z" 62)
   (reading "b" "2026-10-12T20:00:00Z" 71)
   (reading "c" "2026-10-13T08:00:00Z" 66)])

(defn- chart
  ([readings] (chart readings nil))
  ([readings options]
   (charts/line-chart (merge {:kind :heart-rate :readings readings :now now :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "c"} options))))

(defn- plot-edges
  "Where the plot begins and ends on the x axis, from its lines of grid."
  [fig]
  (let [grid (first (hx/select (first (hx/by-tag fig :svg)) #(hx/has-class? % "stroke-chart-grid")))]
    [(parse-double (:x1 (hx/attributes grid))) (parse-double (:x2 (hx/attributes grid)))]))

(defn- line-xs
  "The x of each point of each series of the chart: the series are the paths that are not filled."
  [fig]
  (for [path (hx/select (first (hx/by-tag fig :svg)) #(and (= :path (hx/tag %)) (= "none" (:fill (hx/attributes %)))))]
    (map (comp parse-double second) (re-seq #"(-?[\d.]+),(-?[\d.]+)" (:d (hx/attributes path))))))

(deftest a-chart-says-what-it-covers-in-its-title-and-in-words
  (let [fig (chart heart-rate)]
    (is (= "Heart rate, last 7 days" (hx/words (first (hx/by-tag fig :title)))))
    (is (= "Heart rate over the last 7 days: between 62 and 71 bpm; the latest, today, 10:00, is 66 bpm."
           (hx/words (first (hx/by-tag fig :desc)))))
    (is (= [] (a11y/problems fig))))
  (testing "a stretch that was cut is said in the same words"
    (let [fig (chart heart-rate {:span "latest 500 readings"})]
      (is (= "Heart rate, latest 500 readings" (hx/words (first (hx/by-tag fig :title)))))
      (is (str/starts-with? (hx/words (first (hx/by-tag fig :desc))) "Heart rate over the latest 500 readings: between 62 and 71 bpm;")))))

(deftest a-chart-has-the-id-it-is-given-or-one-of-its-own
  (is (= "x-title" (:id (hx/attributes (first (hx/by-tag (chart heart-rate {:id "x"}) :title))))))
  (is (= "chart-heart-rate-300-title"
         (:id (hx/attributes (first (hx/by-tag (charts/line-chart {:kind :heart-rate :readings heart-rate :now now :from "2026-10-06T12:00:00Z"
                                                                   :span "last 7 days" :width 300})
                                               :title))))))
  (testing "so that two charts of one page do not share ids"
    (let [ids (fn [fig] (set (keep #(:id (hx/attributes %)) (hx/select fig #(contains? (hx/attributes %) :id)))))]
      (is (empty? (set/intersection (ids (chart heart-rate {:id "wide"})) (ids (chart heart-rate {:id "narrow"}))))))))

(deftest a-chart-of-nothing-is-nothing
  (is (nil? (chart [])))
  (is (nil? (chart nil)))
  (testing "and so is a chart of readings that say nothing"
    (is (nil? (chart [{:id "x" :at "2026-10-12T08:00:00Z" :unit "/min"}])) "no number")
    (is (nil? (chart [(reading "x" nil 70)])) "no time")
    (is (nil? (chart [(reading "x" "yesterday" 70)])) "a time that is no time")
    (is (nil? (chart [(reading "x" "2026-10-12" 70)])) "a date alone")
    (is (nil? (chart [(reading "x" "2026-10-12T08:00:00Z" "seventy")])) "a number that is not one")))

(deftest a-reading-that-says-nothing-is-left-out-and-the-others-are-drawn
  (let [odd [(reading "good" "2026-10-12T08:00:00Z" 62)
             {:id "no-number" :at "2026-10-12T09:00:00Z" :unit "/min"}
             (reading "no-time" nil 80)
             (reading "bad-time" "later" 80)
             (reading "good-too" "2026-10-12T10:00:00Z" 70)]
        fig (chart odd)]
    (is (= 2 (count (first (line-xs fig)))))
    (is (= 2 (count (hx/by-tag (first (hx/by-tag fig :tbody)) :tr))) "and the table has the same readings")
    (is (= [] (a11y/problems fig)))))

(deftest a-blood-pressure-needs-both-of-its-numbers
  (let [bp (fn [id at systolic diastolic] {:id id :at at :systolic systolic :diastolic diastolic :unit "mm[Hg]" :band "normal"})
        fig (charts/line-chart {:kind :blood-pressure :now now :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "bp"
                                :readings [(bp "a" "2026-10-12T08:00:00Z" 120 80)
                                           (bp "b" "2026-10-12T09:00:00Z" 130 nil)
                                           (bp "c" "2026-10-12T10:00:00Z" nil 90)
                                           (bp "d" "2026-10-12T11:00:00Z" 140 90)]})]
    (is (= ["Systolic (mmHg)" "Diastolic (mmHg)"] (map hx/words (hx/by-tag (first (hx/by-tag fig :figcaption)) :li))))
    (is (= [2 2] (map count (line-xs fig))) "two readings, each with the two lines")
    (is (str/includes? (hx/words (first (hx/by-tag fig :desc))) "between 80 and 140 mmHg; the latest, yesterday, 13:00, is 140/90 mmHg."))))

(deftest the-readings-are-drawn-from-the-oldest-to-the-newest-whatever-order-they-came-in
  (let [xs (first (line-xs (chart (reverse heart-rate))))]
    (is (= 3 (count xs)))
    (is (apply < xs) "the way the api gives them, newest first, is not the way a line is drawn")
    (is (= xs (first (line-xs (chart heart-rate)))))
    (is (= xs (first (line-xs (chart [(second heart-rate) (last heart-rate) (first heart-rate)])))))))

(deftest the-axis-begins-where-it-is-told-to-and-ends-now
  (let [fig (chart heart-rate {:from "2026-10-12T08:00:00Z"})
        [left right] (plot-edges fig)
        xs (first (line-xs fig))]
    (is (= left (first xs)) "the oldest reading is the first thing on the axis")
    (is (< (last xs) right) "and now is to the right of the newest")
    (testing "a stretch that ends at the newest reading and not at the oldest of the days asked for"
      (let [[l r] (plot-edges (chart heart-rate {:from "2026-09-13T12:00:00Z"}))]
        (is (< l (first (first (line-xs (chart heart-rate {:from "2026-09-13T12:00:00Z"}))))))
        (is (< l r))))))

(deftest a-reading-from-the-future-is-still-on-the-axis
  (let [fig (chart (conj heart-rate (reading "d" "2026-10-13T12:03:00Z" 90)))
        [_ right] (plot-edges fig)
        xs (first (line-xs fig))]
    (is (= right (last xs)) "the clocks of two machines differ by a moment: the axis ends at the newest, which is a little after now")
    (is (every? #(<= % right) xs))))

(deftest a-chart-of-one-reading-or-of-no-time-at-all-is-drawn
  (testing "one reading"
    (let [fig (chart [(first heart-rate)])]
      (is (some? fig))
      (is (= [] (a11y/problems fig)))))
  (testing "an axis that begins where it ends (now, with nothing before it)"
    (let [fig (chart [(reading "x" now 70)] {:from now})]
      (is (some? fig))
      (is (every? #(and (not (Double/isNaN %)) (not (Double/isInfinite %))) (flatten (line-xs fig))))))
  (testing "and one that begins after it (a clock that is behind)"
    (is (some? (chart [(reading "x" now 70)] {:from "2026-10-14T12:00:00Z"})))))

(deftest the-ticks-are-chosen-for-the-stretch-unless-they-are-given
  (let [tick-labels (fn [fig]
                      (map hx/words (hx/select (first (hx/by-tag fig :svg))
                                               #(and (= :text (hx/tag %)) (= "middle" (:text-anchor (hx/attributes %)))))))]
    (is (= ["Oct 7" "Oct 8" "Oct 9" "Oct 10" "Oct 11" "Oct 12" "Oct 13"] (tick-labels (chart heart-rate {:max-ticks 7}))))
    (is (= ["Oct 8" "Oct 10" "Oct 12"] (tick-labels (chart heart-rate {:max-ticks 3}))))
    (is (= ["Oct 8" "Oct 12"] (tick-labels (chart heart-rate {:ticks [{:at (millis "2026-10-07T22:00:00Z") :label "Oct 8"}
                                                                      {:at (millis "2026-10-11T22:00:00Z") :label "Oct 12"}]})))
        "the ones it is given")))

(deftest the-thresholds-and-the-normal-range-can-be-left-out-and-are-for-the-measurements-that-have-them
  (let [words (fn [fig] (set (map hx/words (hx/select (first (hx/by-tag fig :svg))
                                                      #(and (= :text (hx/tag %)) (= "start" (:text-anchor (hx/attributes %))))))))]
    (is (= #{"Normal"} (words (chart heart-rate))))
    (is (= #{} (words (chart heart-rate {:bands? false}))))
    (is (= #{"Stage 2 · 160" "Stage 2 · 100"}
           (words (charts/line-chart {:kind :blood-pressure :now now :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "bp"
                                      :readings [{:id "a" :at "2026-10-12T08:00:00Z" :systolic 120 :diastolic 80 :unit "mm[Hg]" :band "normal"}]}))))
    (is (= #{} (words (charts/line-chart {:kind :blood-pressure :now now :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "bp" :thresholds? false
                                          :readings [{:id "a" :at "2026-10-12T08:00:00Z" :systolic 120 :diastolic 80 :unit "mm[Hg]" :band "normal"}]}))))
    (is (= #{} (words (charts/line-chart {:kind :weight :now now :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "w"
                                          :readings [{:id "a" :at "2026-10-12T08:00:00Z" :value 82.3 :unit "kg" :band nil}]}))))))

(deftest a-reading-beyond-the-usual-range-makes-room-for-itself
  (let [fig (chart (conj heart-rate (reading "high" "2026-10-12T22:00:00Z" 190)))
        labels (->> (hx/select (first (hx/by-tag fig :svg))
                               #(and (= :text (hx/tag %)) (= "end" (:text-anchor (hx/attributes %))) (hx/has-class? % "fill-muted")))
                    (map (comp parse-double hx/words)))]
    (is (>= (apply max labels) 190))))

(deftest the-table-under-the-chart-has-every-reading-with-the-day-it-was-taken-told-from-now
  (let [fig (chart heart-rate)
        rows (hx/by-tag (first (hx/by-tag fig :tbody)) :tr)]
    (is (= [["Yesterday, 10:00" "62"] ["Yesterday, 22:00" "71"] ["Today, 10:00" "66"]]
           (map #(map hx/words (hx/select % (fn [e] (#{:th :td} (hx/tag e))))) rows)))))

(defspec every-reading-inside-the-stretch-is-inside-the-plot-whatever-order-it-comes-in 200
  (prop/for-all [readings (gen/bind (gen/vector (gen/tuple (gen/choose 0 (dec (* 7 24 60))) (gen/choose 40 140)) 1 30)
                                    (fn [pairs]
                                      (gen/shuffle (map-indexed (fn [i [offset value]]
                                                                  (reading (str "r" i)
                                                                           (str (java.time.Instant/ofEpochMilli (+ (millis "2026-10-06T12:00:00Z") (* offset 60000))))
                                                                           value))
                                                                pairs))))]
    (let [fig (chart readings)
          [left right] (plot-edges fig)
          xs (first (line-xs fig))]
      (and (= (count readings) (count xs))
           (every? #(<= left % right) xs)
           (= xs (sort xs))))))
