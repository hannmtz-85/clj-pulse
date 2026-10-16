(ns pulse.ui.chart-details-test
  "The details of the geometry of a chart that a mutation analysis found nothing noticed: the shapes of the markers,
  where a lone point is, what is left out of the margin and of the table, and the edges of the choices."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.chart :as chart]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private base
  {:id "c" :title "A chart" :description "What it shows."
   :series [{:label "Heart rate" :unit "bpm" :points (map-indexed (fn [i v] [i v]) [72 75 71 78 90 118 96 80 74 72])}]})

(defn- approx
  ([expected x] (approx expected x 1e-6))
  ([expected x epsilon]
   (< (Math/abs (- (double expected) (double x))) epsilon)))

(defn- plot-svg [fig] (first (hx/by-tag fig :svg)))

(defn- margin-words [fig]
  (hx/select (plot-svg fig)
             #(and (= :text (hx/tag %)) (some (fn [c] (str/starts-with? c "fill-tone-")) (hx/classes %)))))

;; ---------------------------------------------------------------------------
;; Numbers

(deftest the-step-is-the-one-nearest-to-the-range-divided-by-the-target
  (is (= [0.0 5.0 10.0 15.0 20.0 25.0 30.0] (:ticks (chart/nice-ticks 0 30 5))) "6 a step: 5, not 10")
  (is (= [0.0 10.0 20.0 30.0 40.0] (:ticks (chart/nice-ticks 0 36 5))) "7.2 a step: 10")
  (is (= [0.0 10.0] (:ticks (chart/nice-ticks 0 10 0))) "a target of none is one interval, not a division by zero"))

(deftest the-domain-of-the-data-is-in-round-numbers-of-about-five-intervals
  (is (= [70.0 120.0] (chart/domain base)))
  (is (= [60.0 120.0] (chart/domain (assoc base :y-ticks 2)))))

(deftest dodging-pulls-a-word-in-from-outside-and-keeps-the-top-and-the-gaps-when-there-is-no-room
  (let [words (fn [& ys] (map-indexed (fn [i y] {:id i :y y}) ys))]
    (is (= [228] (map :y (chart/dodge (words 300) 14 16 228))) "no word below the bottom")
    (is (= [16] (map :y (chart/dodge (words 0) 14 16 228))) "none above the top")
    (testing "too many for the room: the first is at the top, they are 14 apart and the last goes past the bottom"
      (let [placed (chart/dodge (apply words (repeat 10 20)) 14 16 100)]
        (is (= (map #(+ 16 (* 14 %)) (range 10)) (map :y placed)))
        (is (= (range 10) (map :id placed)) "and in order")))))

;; ---------------------------------------------------------------------------
;; Shapes

(defn- last-point
  "The last `[x y]` of the path `d`."
  [d]
  (let [[_ x y] (last (re-seq #"[ML]([-0-9.]+),([-0-9.]+)" d))]
    [(parse-double x) (parse-double y)]))

(defn- four-series []
  (chart/line-chart (assoc base :series (for [n (range 4)]
                                          {:label (str "S" n) :points [[0 (+ 10 (* 20 n))] [10 (+ 20 (* 20 n))]]})
                           :x-domain [0 10] :y-domain [0 100])))

(defn- series-end
  "The last point of the line of the series `n` (1 to 4) of `fig`."
  [fig n]
  (let [path (first (hx/select (plot-svg fig) #(and (= :path (hx/tag %)) (hx/has-class? % (str "stroke-series-" n)))))]
    (last-point (:d (hx/attributes path)))))

(defn- last-marker
  "The last marker of the series `n`: the element of the colour of the series that has the ring."
  [fig n]
  (last (hx/select (plot-svg fig)
                   #(and (hx/has-class? % (str "fill-series-" n)) (hx/has-class? % "stroke-base-100")))))

(deftest each-marker-is-centred-on-the-point-and-has-the-size-of-its-shape
  (let [fig (four-series)
        fmt chart/fmt]
    (testing "series 1: a circle of radius 5"
      (let [[x y] (series-end fig 1)
            a (hx/attributes (last-marker fig 1))]
        (is (= :circle (hx/tag (last-marker fig 1))))
        (is (= [(fmt x) (fmt y) "5"] [(:cx a) (:cy a) (:r a)]))))
    (testing "series 2: a square of 10 by 10"
      (let [[x y] (series-end fig 2)
            a (hx/attributes (last-marker fig 2))]
        (is (= :rect (hx/tag (last-marker fig 2))))
        (is (= [(fmt (- x 5)) (fmt (- y 5)) "10" "10"] [(:x a) (:y a) (:width a) (:height a)]))))
    (testing "series 3: a diamond that reaches 6.75 from the point in each direction"
      (let [[x y] (series-end fig 3)
            d (:d (hx/attributes (last-marker fig 3)))]
        (is (= (str "M" (fmt x) "," (fmt (- y 6.75)) " L" (fmt (+ x 6.75)) "," (fmt y)
                    " L" (fmt x) "," (fmt (+ y 6.75)) " L" (fmt (- x 6.75)) "," (fmt y) " Z")
               d))))
    (testing "series 4: a triangle, 6.25 to each side and 6.25 up, with its base 5 below the point"
      (let [[x y] (series-end fig 4)
            d (:d (hx/attributes (last-marker fig 4)))]
        (is (= (str "M" (fmt x) "," (fmt (- y 6.25)) " L" (fmt (+ x 6.25)) "," (fmt (+ y 5))
                    " L" (fmt (- x 6.25)) "," (fmt (+ y 5)) " Z")
               d))))))

(deftest a-lone-point-is-in-the-middle-of-the-plot
  (let [fig (chart/line-chart (assoc base :series [{:label "x" :points [[5 100]]}]))
        marker (hx/attributes (last-marker fig 1))]
    (is (= (chart/fmt (/ (+ 48 624) 2)) (:cx marker)) "between the left margin of 48 and the right edge at 624")
    (is (= 1 (count (hx/select (plot-svg fig) #(hx/has-class? % "fill-series-1")))))))

(deftest the-lines-of-a-path-are-apart
  (let [d (:d (hx/attributes (first (hx/select (plot-svg (chart/line-chart (assoc-in base [:series 0 :points] [[0 70] [1 72] [2 nil] [3 75] [4 76]])))
                                               #(and (= :path (hx/tag %)) (hx/has-class? % "stroke-series-1"))))))]
    (is (str/includes? d " M") "a space before the second line")
    (is (re-find #"^M[-0-9.]+,[-0-9.]+ L[-0-9.]+,[-0-9.]+ M[-0-9.]+,[-0-9.]+ L" d))))

(deftest a-series-has-a-marker-on-each-of-24-points-and-on-the-last-only-from-25
  (let [markers (fn [n] (count (hx/select (plot-svg (chart/line-chart (assoc-in base [:series 0 :points]
                                                                                (map (fn [i] [i (+ 70 (mod (* 7 i) 11))]) (range n)))))
                                          #(hx/has-class? % "fill-series-1"))))]
    (is (= 24 (markers 24)))
    (is (= 1 (markers 25)))))

;; ---------------------------------------------------------------------------
;; Words, bands and thresholds

(deftest the-word-of-a-band-is-at-the-middle-of-the-part-that-is-in-view
  (let [fig (chart/line-chart (assoc base :y-domain [40 140]
                                     :bands [{:from 50 :to 100 :tone :normal :label "Normal"}
                                             {:from 0 :to 60 :tone :elevated :label "Low"}]))
        word (fn [text] (first (filter #(= text (hx/words %)) (margin-words fig))))
        y (fn [text] (parse-double (:y (hx/attributes (word text)))))]
    (is (approx 153.8 (y "Normal") 0.01) "the middle of 50 to 100, over a plot from 16 to 228 for 40 to 140")
    (is (approx 206.8 (y "Low") 0.01) "the middle of 40 to 60, not of 0 to 60")
    (testing "the shaded part is the same"
      (let [rects (hx/select (plot-svg fig) #(and (= :rect (hx/tag %)) (hx/has-class? % "fill-tone-elevated-soft")))
            a (hx/attributes (first rects))]
        (is (approx 228 (+ (parse-double (:y a)) (parse-double (:height a)))))
        (is (approx 42.4 (parse-double (:height a)) 0.01) "20 units of 100, over 212 pixels")
        (is (= "0.7" (:opacity a)))))))

(deftest a-band-or-a-threshold-with-no-label-has-no-word-and-leaves-the-margin-as-it-was
  (let [fig (chart/line-chart (assoc base :y-domain [60 140]
                                     :bands [{:from 50 :to 100 :tone :normal}]
                                     :thresholds [{:at 120 :tone :critical}]))
        right (fn [f] (parse-double (:x2 (hx/attributes (first (hx/select (plot-svg f) #(and (= :line (hx/tag %)) (hx/has-class? % "stroke-control-border"))))))))]
    (is (empty? (margin-words fig)))
    (is (= 624.0 (right fig)) "the usual margin of 16")
    (is (= 1 (count (hx/select (plot-svg fig) #(and (= :line (hx/tag %)) (hx/has-class? % "stroke-tone-critical-edge"))))) "but the line is there")
    (is (= 1 (count (hx/select (plot-svg fig) #(and (= :rect (hx/tag %)) (hx/has-class? % "fill-tone-normal-soft"))))) "and so is the band")))

(deftest a-threshold-below-or-above-the-domain-is-not-drawn-and-has-no-word
  (doseq [at [10 200]]
    (let [fig (chart/line-chart (assoc base :y-domain [60 140] :thresholds [{:at at :tone :critical :label "Far"}]))]
      (is (empty? (margin-words fig)) (str at))
      (is (empty? (hx/select (plot-svg fig) #(and (= :line (hx/tag %)) (hx/has-class? % "stroke-tone-critical-edge")))) (str at)))))

(deftest only-the-numbers-in-the-domain-are-on-the-axis
  (let [fig (chart/line-chart (assoc base :y-domain [64 96]))
        labels (map hx/words (hx/select (plot-svg fig)
                                        #(and (= :text (hx/tag %)) (= "end" (:text-anchor (hx/attributes %))) (hx/has-class? % "fill-muted"))))]
    (is (= ["65" "70" "75" "80" "85" "90" "95"] (sort-by parse-long labels)) "the round numbers inside 64 to 96, and not 60 or 100")))

;; ---------------------------------------------------------------------------
;; The table

(deftest the-rows-of-the-table-are-in-the-order-of-x-whatever-the-order-of-the-points
  (let [fig (chart/line-chart (assoc base :series [{:label "A" :points [[3 30] [1 10] [2 20]]}] :x-label #(str "x" %)))
        rows (hx/by-tag (first (hx/by-tag (first (hx/by-tag fig :details)) :tbody)) :tr)]
    (is (= ["x1" "x2" "x3"] (map #(hx/words (first (hx/by-tag % :th))) rows)))
    (is (= ["10" "20" "30"] (map #(hx/words (first (hx/by-tag % :td))) rows)))))

(deftest the-table-has-the-name-the-caller-gives-it
  (let [fig (chart/line-chart (assoc base :table-label "Readings of the week"))
        details (first (hx/by-tag fig :details))
        region (first (hx/select details #(= "region" (:role (hx/attributes %)))))]
    (is (= "Readings of the week" (hx/words (first (hx/by-tag details :summary)))))
    (is (= "Readings of the week" (:aria-label (hx/attributes region))))
    (is (= "Readings of the week" (hx/words (first (hx/by-tag details :caption))))))
  (testing "and a name of its own when it has none"
    (let [details (first (hx/by-tag (chart/line-chart base) :details))]
      (is (= "View the numbers as a table" (hx/words (first (hx/by-tag details :summary)))))
      (is (= "The numbers of the chart" (hx/words (first (hx/by-tag details :caption))))))))

;; ---------------------------------------------------------------------------
;; A sparkline

(deftest a-sparkline-keeps-inside-its-box-with-room-for-the-dot-and-has-the-highest-value-at-the-top
  (let [s (chart/sparkline {:points [[0 1] [1 5] [2 3]] :width 96 :height 28})
        d (:d (hx/attributes (first (hx/by-tag s :path))))
        coordinates (map (fn [[_ x y]] [(parse-double x) (parse-double y)]) (re-seq #"[ML]([-0-9.]+),([-0-9.]+)" d))]
    (is (= [[4.0 24.0] [48.0 4.0] [92.0 14.0]] coordinates) "padded by 4 on each side; the lowest at the bottom, the highest at the top")
    (testing "and the dot is on the last point"
      (let [dot (hx/attributes (first (hx/by-tag s :circle)))]
        (is (= ["92" "14"] [(:cx dot) (:cy dot)]))))))

(deftest a-sparkline-skips-the-gaps
  (let [s (chart/sparkline {:points [[0 1] [1 nil] [2 3] [3 nil]]})
        d (:d (hx/attributes (first (hx/by-tag s :path))))]
    (is (= 2 (count (re-seq #"[ML]" d))))
    (is (= 1 (count (re-seq #"M" d))) "one line, as if the gaps were not there")
    (is (= ["92" "4"] (let [dot (hx/attributes (first (hx/by-tag s :circle)))] [(:cx dot) (:cy dot)]))
        "and the dot is on the last point that has a value")))
