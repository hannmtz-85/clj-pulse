(ns pulse.web.charts-details-test
  "What a mutation analysis of the chart found that the first tests did not hold: the unit that a chart says is the newest reading's, and
  where the range of normal values is shaded."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.charts :as charts]))

(deftest the-unit-of-a-series-is-the-one-of-its-newest-reading
  ;; other systems write to the same server, and a series may not be in one unit: the chart says the unit that it ends in
  (let [fig (charts/line-chart {:kind :weight :now "2026-10-13T12:00:00Z" :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "w"
                                :readings [{:id "a" :at "2026-10-10T08:00:00Z" :value 80 :unit "[lb_av]"}
                                           {:id "b" :at "2026-10-12T08:00:00Z" :value 82 :unit "kg"}]})]
    (is (= ["Weight (kg)"] (map hx/words (hx/by-tag (first (hx/by-tag fig :figcaption)) :li))))
    (is (= "Weight over the last 7 days: between 80 and 82 kg; the latest, yesterday, 10:00, is 82 kg."
           (hx/words (first (hx/by-tag fig :desc)))))))

(defn- y-of
  "A function from a value to the y of the drawing, from two of the numbers on the y axis."
  [fig]
  (let [labels (for [t (hx/select (first (hx/by-tag fig :svg))
                                  #(and (= :text (hx/tag %)) (= "end" (:text-anchor (hx/attributes %))) (hx/has-class? % "fill-muted")))]
                 [(parse-double (hx/words t)) (parse-double (:y (hx/attributes t)))])
        [[v0 y0] [v1 y1]] [(first labels) (last labels)]]
    (fn [v] (+ y0 (* (- v v0) (/ (- y1 y0) (- v1 v0)))))))

(defn- band-of
  "The top and the bottom of the shaded range of normal values of a figure, as y of the drawing."
  [fig]
  (let [rect (first (hx/select (first (hx/by-tag fig :svg)) #(and (= :rect (hx/tag %)) (hx/has-class? % "fill-tone-normal-soft"))))
        y (parse-double (:y (hx/attributes rect)))]
    [y (+ y (parse-double (:height (hx/attributes rect))))]))

(defn- near? [expected actual] (< (Math/abs (- expected actual)) 0.1))

(deftest the-range-of-normal-values-is-shaded-from-its-lower-limit-to-its-upper-one
  (testing "a heart rate: 50 to 100"
    (let [fig (charts/line-chart {:kind :heart-rate :now "2026-10-13T12:00:00Z" :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "h"
                                  :readings (for [i (range 5)] {:id (str "h" i) :at (format "2026-10-%02dT08:00:00Z" (+ 7 i)) :value (+ 60 (* 5 i)) :unit "/min"})})
          y (y-of fig)
          [top bottom] (band-of fig)]
      (is (near? (y 100) top))
      (is (near? (y 50) bottom))))
  (testing "an oxygen saturation: 92 and up, to the top of the chart"
    (let [fig (charts/line-chart {:kind :spo2 :now "2026-10-13T12:00:00Z" :from "2026-10-06T12:00:00Z" :span "last 7 days" :id "s"
                                  :readings (for [i (range 5)] {:id (str "s" i) :at (format "2026-10-%02dT08:00:00Z" (+ 7 i)) :value (+ 93 i) :unit "%"})})
          y (y-of fig)
          [top bottom] (band-of fig)]
      (is (near? (y 100) top))
      (is (near? (y 92) bottom)))))
