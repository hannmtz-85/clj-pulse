(ns pulse.design.specimen-test
  "The tiles and the charts the pages are made of: what a reading looks like from the cohort."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.specimen :as specimen]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(deftest a-reading-is-written-as-people-write-it
  (is (= "177/100" (specimen/reading-text :blood-pressure (data/latest "pt-ana" :blood-pressure))))
  (is (= "82.4" (specimen/reading-text :weight {:value 82.4})))
  (is (= "70" (specimen/reading-text :heart-rate {:value 70}))))

(deftest a-tile-shows-the-latest-reading-of-a-patient
  (let [tile (specimen/vital-tile :blood-pressure (data/latest "pt-ana" :blood-pressure))
        text (hx/words tile)]
    (is (str/includes? text "Blood pressure"))
    (is (str/includes? text "177/100 mmHg"))
    (is (str/includes? text "Very high"))
    (is (str/includes? text "Today, 10:00"))
    (is (= [] (a11y/problems tile))))
  (testing "a reading with no band has no chip"
    (is (not (str/includes? (hx/words (specimen/vital-tile :weight (data/latest "pt-ana" :weight))) "Normal"))))
  (testing "attributes go to the tile"
    (is (= "x" (:data-test (hx/attributes (specimen/vital-tile :heart-rate (data/latest "pt-ana" :heart-rate) {:data-test "x"})))))))

(def ^:private kinds-of
  {"pt-ana" [:blood-pressure :heart-rate :weight]
   "pt-liam" [:glucose]
   "pt-sofia" [:spo2]
   "pt-omar" [:heart-rate]})

(deftest every-measurement-of-the-cohort-has-a-chart-that-passes-the-accessibility-checks
  (doseq [[patient kinds] kinds-of
          kind kinds
          days [2 7 30]
          :let [fig (specimen/line-chart patient kind {:days days :id (str "c-" patient "-" (name kind) "-" days)})]]
    (is (= :figure (hx/tag fig)) (str patient kind days))
    (is (= [] (a11y/problems fig)) (str patient " " kind " " days))
    (is (str/includes? (hx/words (first (hx/by-tag fig :desc))) (str "last " days " days")))))

(defn- y-labels
  "The numbers on the y axis of a chart."
  [fig]
  (->> (hx/select (first (hx/by-tag fig :svg))
                  #(and (= :text (hx/tag %)) (= "end" (:text-anchor (hx/attributes %))) (hx/has-class? % "fill-muted")))
       (map hx/words)
       (map parse-double)))

(deftest the-chart-of-a-measurement-is-drawn-over-its-usual-range-and-the-data-is-inside-it
  (doseq [[patient kind channel] [["pt-ana" :blood-pressure :systolic] ["pt-ana" :blood-pressure :diastolic]
                                  ["pt-ana" :heart-rate :value] ["pt-liam" :glucose :value] ["pt-sofia" :spo2 :value]]
          :let [fig (specimen/line-chart patient kind {:days 30 :id "d"})
                labels (y-labels fig)
                values (keep channel (data/readings patient kind))]]
    (is (<= (apply min labels) (apply min values)) (str patient kind channel))
    (is (>= (apply max labels) (apply max values)) (str patient kind channel)))
  (testing "wider than the data, to give it a context"
    (let [labels (y-labels (specimen/line-chart "pt-omar" :heart-rate {:days 7 :id "e"}))]
      (is (<= (apply min labels) 40))
      (is (>= (apply max labels) 140)))))

(defn- dashed-lines [fig]
  (hx/select (first (hx/by-tag fig :svg)) #(and (= :line (hx/tag %)) (:stroke-dasharray (hx/attributes %)))))

(defn- margin-words [fig]
  (map hx/words (hx/select (first (hx/by-tag fig :svg))
                           #(and (= :text (hx/tag %)) (= "start" (:text-anchor (hx/attributes %)))))))

(deftest the-lines-of-the-rules-and-the-normal-range-are-drawn-where-they-watch
  (testing "blood pressure: stage 2 of rule R1, on both numbers"
    (let [fig (specimen/line-chart "pt-ana" :blood-pressure {:days 30 :id "a"})]
      (is (= 2 (count (dashed-lines fig))))
      (is (= ["Stage 2 · 160" "Stage 2 · 100"] (margin-words fig)))))
  (testing "glucose: above 250 and below 70 (R3), with its normal range"
    (let [fig (specimen/line-chart "pt-liam" :glucose {:days 7 :id "b"})]
      (is (= 2 (count (dashed-lines fig))))
      (is (= #{"High · 250" "Low · 70" "Normal"} (set (margin-words fig))))))
  (testing "oxygen saturation: below 92 (R2)"
    (let [fig (specimen/line-chart "pt-sofia" :spo2 {:days 14 :id "c"})]
      (is (= 1 (count (dashed-lines fig))))
      (is (= #{"Low · 92" "Normal"} (set (margin-words fig))))))
  (testing "heart rate has a normal range and no rule; weight has neither"
    (is (= ["Normal"] (margin-words (specimen/line-chart "pt-omar" :heart-rate {:days 7 :id "d"}))))
    (is (empty? (dashed-lines (specimen/line-chart "pt-omar" :heart-rate {:days 7 :id "d2"}))))
    (is (empty? (margin-words (specimen/line-chart "pt-maya" :weight {:days 14 :id "e"})))))
  (testing "and they can be left out"
    (let [fig (specimen/line-chart "pt-ana" :blood-pressure {:days 30 :id "f" :thresholds? false :bands? false})]
      (is (empty? (dashed-lines fig)))
      (is (empty? (margin-words fig))))))

(deftest blood-pressure-has-two-series-and-the-others-one
  (is (= ["Systolic (mmHg)" "Diastolic (mmHg)"]
         (map hx/words (hx/by-tag (first (hx/by-tag (specimen/line-chart "pt-ana" :blood-pressure {:days 7 :id "g"}) :figcaption)) :li))))
  (is (= ["Heart rate (bpm)"]
         (map hx/words (hx/by-tag (first (hx/by-tag (specimen/line-chart "pt-ana" :heart-rate {:days 7 :id "h"}) :figcaption)) :li)))))

(deftest the-description-says-what-the-chart-shows-in-words
  (let [text (hx/words (first (hx/by-tag (specimen/line-chart "pt-ana" :blood-pressure {:days 30 :id "i"}) :desc)))]
    (is (str/includes? text "Blood pressure over the last 30 days"))
    (is (str/includes? text "between "))
    (is (str/includes? text "the latest, today, 10:00, is 177/100 mmHg"))))

(deftest the-table-of-a-chart-has-the-times-of-the-clinic
  (let [fig (specimen/line-chart "pt-ana" :heart-rate {:days 2 :id "j"})
        rows (hx/by-tag (first (hx/by-tag (first (hx/by-tag fig :details)) :tbody)) :tr)
        first-heading (hx/words (first (hx/by-tag (first rows) :th)))]
    (is (= 4 (count rows)) "two readings a day, for two days, less the one that is still to come... of the last 48 hours")
    (is (re-matches #"(Yesterday|Today|Oct \d+), \d\d:\d\d" first-heading))))

(deftest a-sparkline-takes-the-tone-of-the-latest-reading
  (let [stroke (fn [patient kind]
                 (first (filter #(str/starts-with? % "stroke-")
                                (hx/classes (first (hx/by-tag (specimen/sparkline patient kind 14) :path))))))]
    (is (= "stroke-tone-critical-edge" (stroke "pt-ana" :blood-pressure)) "very high")
    (is (= "stroke-tone-elevated-edge" (stroke "pt-sofia" :spo2)) "low")
    (is (= "stroke-series-1" (stroke "pt-omar" :blood-pressure)) "normal: no tone")
    (is (= "stroke-series-1" (stroke "pt-maya" :weight)) "no band: no tone"))
  (is (= "Blood pressure over the last 14 days"
         (:aria-label (hx/attributes (specimen/sparkline "pt-ana" :blood-pressure 14))))))
