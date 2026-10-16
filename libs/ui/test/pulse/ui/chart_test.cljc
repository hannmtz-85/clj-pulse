(ns pulse.ui.chart-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.ui.chart :as chart]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(defn- approx
  "True when `x` is within `epsilon` (1e-5 by default) of `expected`."
  ([expected x] (approx expected x 1e-5))
  ([expected x epsilon]
   (< (Math/abs (- (double expected) (double x))) epsilon)))

;; ---------------------------------------------------------------------------
;; Numbers

(deftest numbers-are-written-short
  (is (= "3" (chart/fmt 3)))
  (is (= "3" (chart/fmt 3.0)))
  (is (= "3.5" (chart/fmt 3.5)))
  (is (= "3.14" (chart/fmt 3.14159)))
  (is (= "0.33" (chart/fmt (/ 1.0 3))))
  (is (= "-2.5" (chart/fmt -2.5)))
  (is (= "0" (chart/fmt 0.0001))))

(deftest ticks-are-round-numbers-that-cover-the-range
  (testing "known cases"
    (is (= [0.0 20.0 40.0 60.0 80.0 100.0] (:ticks (chart/nice-ticks 0 100 6))))
    (is (= [40.0 60.0 80.0 100.0 120.0 140.0] (:ticks (chart/nice-ticks 45 135 5))))
    (is (= [0.0 0.5 1.0] (:ticks (chart/nice-ticks 0 1 3))))
    (is (= [36.0 36.5 37.0 37.5 38.0 38.5 39.0] (:ticks (chart/nice-ticks 36.1 38.9 7))))
    (is (= [62.0 63.0 64.0 65.0 66.0 67.0 68.0] (:ticks (chart/nice-ticks 62.3 67.1 4))))
    (is (= [0.0 500.0 1000.0] (:ticks (chart/nice-ticks 120 980 2))))
    (is (= [-20.0 -10.0 0.0 10.0 20.0] (:ticks (chart/nice-ticks -17 14 4)))))
  (testing "a range of one value is widened"
    (let [{:keys [lo hi]} (chart/nice-ticks 5 5 5)]
      (is (< lo 5 hi))))
  (testing "the first and last are lo and hi"
    (let [t (chart/nice-ticks 45 135 5)]
      (is (= (:lo t) (first (:ticks t))))
      (is (= (:hi t) (last (:ticks t)))))))

(defspec ticks-cover-the-range-and-are-evenly-spaced 500
  (prop/for-all [lo (gen/double* {:min -1000.0 :max 1000.0 :NaN? false :infinite? false})
                 span (gen/double* {:min 0.01 :max 2000.0 :NaN? false :infinite? false})
                 target (gen/choose 2 9)]
    (let [hi (+ lo span)
          {:keys [ticks step]} (chart/nice-ticks lo hi target)
          gaps (map - (rest ticks) ticks)]
      (and (<= (first ticks) (+ lo 1e-6))
           (>= (last ticks) (- hi 1e-6))
           (every? #(approx step %) gaps)
           (pos? step)
           (<= 2 (count ticks))
           (<= (count ticks) (+ (* 2 target) 3))))))

(defspec the-step-is-1-2-2-5-or-5-times-a-power-of-ten 300
  (prop/for-all [lo (gen/double* {:min -500.0 :max 500.0 :NaN? false :infinite? false})
                 span (gen/double* {:min 0.01 :max 1000.0 :NaN? false :infinite? false})]
    (let [step (:step (chart/nice-ticks lo (+ lo span) 5))
          exponent (Math/floor (/ (Math/log step) (Math/log 10)))
          mantissa (/ step (Math/pow 10 exponent))]
      (boolean (some #(approx % mantissa) [1.0 2.0 2.5 5.0 10.0])))))

;; ---------------------------------------------------------------------------
;; A line chart

(def ^:private hour 3600000)

(def ^:private points
  (map-indexed (fn [i v] [(* i hour) v]) [72 75 71 78 90 118 96 80 74 72]))

(def ^:private base
  {:id "hr" :title "Heart rate, last 9 hours"
   :description "Between 71 and 118 beats per minute, 72 now."
   :series [{:label "Heart rate" :unit "bpm" :points points}]})

(defn- series-paths
  "The paths of the lines of the series (the ones with a stroke class of a series)."
  [fig]
  (hx/select fig #(and (= :path (hx/tag %)) (some (fn [c] (str/starts-with? c "stroke-series-")) (hx/classes %)))))

(deftest a-chart-is-a-figure-with-a-title-a-description-and-a-line
  (let [fig (chart/line-chart base)
        svg (first (hx/by-tag fig :svg))
        a (hx/attributes svg)]
    (is (= :figure (hx/tag fig)))
    (is (= "img" (:role a)))
    (is (= "hr-title hr-desc" (:aria-labelledby a)))
    (is (= "0 0 640 260" (:viewBox a)))
    (is (= "Heart rate, last 9 hours" (hx/words (hx/by-id fig "hr-title"))))
    (is (= "Between 71 and 118 beats per minute, 72 now." (hx/words (hx/by-id fig "hr-desc"))))
    (is (= 1 (count (series-paths fig))))
    (is (= [] (a11y/problems fig)))))

(deftest a-chart-needs-an-id-a-title-and-a-description
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs an :id" (chart/line-chart (dissoc base :id))))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs an :id" (chart/line-chart (dissoc base :title))))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"needs an :id" (chart/line-chart (dissoc base :description)))))

(deftest the-size-is-the-callers-and-the-svg-scales-to-its-container
  (let [svg (first (hx/by-tag (chart/line-chart (assoc base :width 320 :height 180)) :svg))
        a (hx/attributes svg)]
    (is (= "0 0 320 180" (:viewBox a)))
    (is (= [320 180] [(:width a) (:height a)]))
    (is (every? (hx/classes svg) ["w-full" "h-auto"]))))

(defn- index-of
  "The position of the first `x` in `coll`."
  [coll x]
  (first (keep-indexed (fn [i v] (when (= v x) i)) coll)))

(deftest every-point-of-the-line-is-inside-the-plot
  (let [path-d (:d (hx/attributes (first (series-paths (chart/line-chart base)))))
        coordinates (map (fn [[_ x y]] [(parse-double x) (parse-double y)])
                         (re-seq #"[ML]([-0-9.]+),([-0-9.]+)" path-d))]
    (is (= 10 (count coordinates)))
    (is (every? (fn [[x y]] (and (<= 48 x 624) (<= 16 y 228))) coordinates) "between the margins")
    (is (= (sort (map first coordinates)) (map first coordinates)) "left to right")
    (testing "a higher value is higher on the page, which is a smaller y"
      (let [ys (map second coordinates)]
        (is (= (index-of (map second points) 118) (index-of ys (apply min ys))))))))

(deftest a-gap-breaks-the-line
  (let [with-gap (assoc-in base [:series 0 :points] [[0 70] [1 72] [2 nil] [3 75] [4 76]])
        d (:d (hx/attributes (first (series-paths (chart/line-chart with-gap)))))]
    (is (= 2 (count (re-seq #"M" d))) "two lines, not one through the gap")
    (is (= 4 (count (re-seq #"[ML]" d))) "four points, one of them not drawn")))

(deftest a-series-has-a-marker-on-each-point-or-on-the-last-one-only
  (let [markers (fn [fig] (hx/select (first (hx/by-tag fig :svg))
                                     #(and (#{:circle :rect :path} (hx/tag %))
                                           (some (fn [c] (str/starts-with? c "fill-series-")) (hx/classes %)))))
        few (chart/line-chart base)
        many (chart/line-chart (assoc-in base [:series 0 :points] (map (fn [i] [i (+ 70 (mod (* 7 i) 11))]) (range 60))))]
    (is (= 10 (count (markers few))) "a marker on every point of a short series")
    (is (= 1 (count (markers many))) "only the last point of a long one")))

(deftest the-first-four-series-differ-in-colour-dash-and-marker
  (let [series (for [n (range 4)] {:label (str "S" n) :points [[0 (+ 10 n)] [1 (+ 12 n)] [2 (+ 11 n)]]})
        fig (chart/line-chart (assoc base :series series))
        lines (series-paths fig)
        colours (map #(first (filter (fn [c] (str/starts-with? c "stroke-series-")) (hx/classes %))) lines)
        dashes (map #(:stroke-dasharray (hx/attributes %)) lines)
        shapes (map hx/tag (hx/select fig #(and (#{:circle :rect :path} (hx/tag %))
                                                (contains? (hx/classes %) "stroke-base-100"))))]
    (is (= ["stroke-series-1" "stroke-series-2" "stroke-series-3" "stroke-series-4"] colours))
    (is (= 4 (count (set dashes))) "no two share a dash, so colour is never the only difference")
    (is (= #{:circle :rect :path} (set shapes)))
    (is (= 4 (count (hx/by-tag (first (hx/by-tag fig :figcaption)) :li))) "and the legend has one entry for each")))

(defn- margin-text
  "The words of the right margin: the text elements of the plot that are in a colour of a tone."
  [fig]
  (hx/select (first (hx/by-tag fig :svg))
             #(and (= :text (hx/tag %)) (some (fn [c] (str/starts-with? c "fill-tone-")) (hx/classes %)))))

(deftest bands-are-shaded-clipped-to-the-domain-and-named-in-the-margin
  (let [fig (chart/line-chart (assoc base :y-domain [40 140]
                                     :bands [{:from 50 :to 100 :tone :normal :label "Normal"}
                                             {:from 200 :to 300 :tone :critical :label "Out of view"}
                                             {:from 0 :to 60 :tone :elevated :label "Low"}]))
        rects (hx/select fig #(and (= :rect (hx/tag %)) (some (fn [c] (str/starts-with? c "fill-tone-")) (hx/classes %))))]
    (is (= 2 (count rects)) "the band outside the domain is left out")
    (is (= ["Normal" "Low"] (map hx/words (margin-text fig))) "from the top")
    (is (hx/has-class? (first rects) "fill-tone-normal-soft"))
    (let [a (hx/attributes (second rects))]
      (is (approx 228 (+ (parse-double (:y a)) (parse-double (:height a)))) "a band that starts below the domain stops at the axis"))
    (testing "the word of a band is at its middle, in the margin and in the ink of its tone"
      (let [normal (first (filter #(= "Normal" (hx/words %)) (margin-text fig)))
            a (hx/attributes normal)
            rect (hx/attributes (first rects))]
        (is (hx/has-class? normal "fill-tone-normal-ink"))
        (is (= "start" (:text-anchor a)))
        (is (> (parse-double (:x a)) (+ (parse-double (:x rect)) (parse-double (:width rect)))) "to the right of the plot")
        (is (approx (+ (parse-double (:y rect)) (/ (parse-double (:height rect)) 2)) (parse-double (:y a)) 0.5))))))

(deftest a-threshold-is-a-dashed-line-and-its-word-is-in-the-margin-in-the-colour-of-its-tone
  (let [fig (chart/line-chart (assoc base :thresholds [{:at 120 :tone :critical :label "Very high · 120"}]))
        line (first (hx/select fig #(and (= :line (hx/tag %)) (hx/has-class? % "stroke-tone-critical-edge"))))
        label (first (margin-text fig))
        plot-right (parse-double (:x2 (hx/attributes line)))]
    (is (some? line))
    (is (some? (:stroke-dasharray (hx/attributes line))))
    (is (= "Very high · 120" (hx/words label)))
    (is (hx/has-class? label "fill-tone-critical-ink"))
    (is (> (parse-double (:x (hx/attributes label))) plot-right) "beside the line, not over the data")
    (is (approx (parse-double (:y1 (hx/attributes line))) (parse-double (:y (hx/attributes label))) 0.5))
    (testing "and the domain makes room for it"
      (let [[_ hi] (chart/domain (assoc base :thresholds [{:at 400 :tone :critical}]))]
        (is (>= hi 400))))
    (testing "a threshold outside the domain is not drawn, and has no word"
      (let [out (chart/line-chart (assoc base :y-domain [60 140] :thresholds [{:at 400 :tone :critical :label "Far"}]))]
        (is (empty? (hx/select out #(and (= :line (hx/tag %)) (hx/has-class? % "stroke-tone-critical-edge")))))
        (is (empty? (margin-text out)))))))

(deftest the-right-margin-grows-to-hold-the-words-and-the-plot-shrinks-by-as-much
  (let [plot-right (fn [fig] (parse-double (:x2 (hx/attributes (first (hx/select fig #(and (= :line (hx/tag %)) (hx/has-class? % "stroke-control-border"))))))))
        without (chart/line-chart base)
        with (chart/line-chart (assoc base :thresholds [{:at 120 :tone :critical :label "A rather long word · 120"}]))
        short (chart/line-chart (assoc base :thresholds [{:at 120 :tone :critical :label "R"}]))
        fixed (chart/line-chart (assoc base :margin {:right 30} :thresholds [{:at 120 :tone :critical :label "A rather long word · 120"}]))]
    (is (= 624.0 (plot-right without)) "640 less the margin of 16")
    (is (< (plot-right with) (- 640 100)) "room for 24 characters")
    (is (> (plot-right with) 300))
    (is (= 621.0 (plot-right short)) "a margin of 12 and the width of the word")
    (is (= 610.0 (plot-right fixed)) "a margin the caller gave is kept")))

(deftest words-in-the-margin-never-overlap
  (let [fig (chart/line-chart (assoc base :y-domain [60 140]
                                     :bands [{:from 50 :to 100 :tone :normal :label "Normal"}]
                                     :thresholds [{:at 100 :tone :critical :label "Stage 2 · 100"}
                                                  {:at 101 :tone :high :label "Near it"}
                                                  {:at 99 :tone :elevated :label "And this"}]))
        ys (sort (map #(parse-double (:y (hx/attributes %))) (margin-text fig)))]
    (is (= 4 (count ys)) "the band and the three thresholds")
    (is (every? #(>= % 14.0) (map - (rest ys) ys)) "at least 14 pixels apart")))

(deftest dodging-keeps-the-order-and-moves-only-what-is-in-the-way
  (let [words (fn [& ys] (map-indexed (fn [i y] {:id i :y y}) ys))]
    (is (= [100 114 128] (map :y (chart/dodge (words 100 105 110) 14 16 228))) "pushed down, one after another")
    (is (= [50 150 250] (map :y (chart/dodge (words 50 150 250) 14 16 300))) "far apart: left where they are")
    (is (= [1 2 0] (map :id (chart/dodge (words 110 100 105) 14 16 228))) "sorted by where they are")
    (testing "the last ones are pulled up when they would go past the bottom"
      (is (= [186 200 214 228] (map :y (chart/dodge (words 220 221 222 223) 14 16 228)))))
    (testing "nothing above the top or below the bottom, and still apart"
      (is (= [16 30 44] (map :y (chart/dodge (words 0 1 2) 14 16 228))))
      (is (= [184 198 212 226] (map :y (chart/dodge (words 300 300 300 300) 14 16 226)))))
    (is (= [] (chart/dodge [] 14 16 228)))
    (is (= [{:y 50}] (chart/dodge [{:y 50}] 14 16 228)))))

(defspec dodged-words-keep-their-order-are-apart-and-in-bounds 300
  (prop/for-all [ys (gen/vector (gen/choose 16 228) 0 8)]
    (let [words (map-indexed (fn [i y] {:id i :y y}) ys)
          placed (chart/dodge words 14 16 228)
          placed-ys (map :y placed)]
      (and (= (count words) (count placed))
           (= (map :id (sort-by :y words)) (map :id placed))
           (every? #(<= 16 % 228) placed-ys)
           (every? #(>= % 14) (map - (rest placed-ys) placed-ys))))))

(defspec words-with-room-do-not-move 200
  (prop/for-all [n (gen/choose 0 7)]
    (let [words (map (fn [i] {:y (+ 16 (* 30 i))}) (range n))]
      (= (map :y words) (map :y (chart/dodge words 14 16 228))))))

(deftest the-domain-is-the-callers-or-round-numbers-around-the-data
  (is (= [10 20] (chart/domain {:y-domain [10 20]})))
  (let [[lo hi] (chart/domain base)]
    (is (<= lo 71))
    (is (>= hi 118))
    (is (zero? (mod lo 10)) "round"))
  (testing "no data"
    (is (= 2 (count (chart/domain {:series []}))))
    (is (every? number? (chart/domain {:series [{:points []}]})))))

(deftest the-labels-of-the-axes-are-the-callers-or-round-numbers
  (let [fig (chart/line-chart (assoc base :x-ticks [{:at 0 :label "08:00"} {:at (* 4 hour) :label "12:00"} {:at (* 8 hour) :label "16:00"}]))
        labels (map hx/words (hx/select fig #(and (= :text (hx/tag %)) (= "middle" (:text-anchor (hx/attributes %))))))]
    (is (= ["08:00" "12:00" "16:00"] labels)))
  (let [fig (chart/line-chart (assoc base :y-domain [60 120]))
        labels (map hx/words (hx/select fig #(and (= :text (hx/tag %)) (= "end" (:text-anchor (hx/attributes %))) (hx/has-class? % "fill-muted"))))]
    (is (= ["60" "70" "80" "90" "100" "110" "120"] (sort-by parse-long labels)))))

(deftest the-last-point-is-larger-and-has-a-ring
  (let [fig (chart/line-chart base)
        marks (hx/select (first (hx/by-tag fig :svg))
                         #(and (= :circle (hx/tag %)) (hx/has-class? % "fill-series-1")))
        radii (map #(parse-double (:r (hx/attributes %))) marks)]
    (is (= 10 (count marks)))
    (is (= (concat (repeat 9 3.0) [5.0]) radii) "the last is the largest, and the newest")
    (is (every? #(hx/has-class? % "stroke-base-100") marks) "a ring of the colour of the card, to lift it off the line")))

(deftest the-numbers-are-also-in-a-table-anyone-can-open
  (let [two (chart/line-chart (assoc base :series [(first (:series base))
                                                   {:label "Resting" :points [[0 60] [(* 2 hour) 61]]}]
                                     :x-label (fn [x] (str "h" (/ x hour)))))
        details (first (hx/by-tag two :details))
        table (first (hx/by-tag details :table))]
    (is (str/includes? (hx/words (first (hx/by-tag details :summary))) "table"))
    (is (= ["Time" "Heart rate" "Resting"] (map hx/words (hx/select (first (hx/by-tag table :thead)) #(= :th (hx/tag %))))))
    (is (= 10 (count (hx/by-tag (first (hx/by-tag table :tbody)) :tr))) "one row for each instant")
    (is (= ["h0" "h1" "h2"] (take 3 (map hx/words (hx/select (first (hx/by-tag table :tbody)) #(and (= :th (hx/tag %)) (= "row" (:scope (hx/attributes %)))))))))
    (is (= ["72" "60" "75" "–" "71" "61"] (take 6 (map hx/words (hx/by-tag (first (hx/by-tag table :tbody)) :td)))) "a dash where a series has no value")
    (is (= [] (a11y/problems two)))))

(deftest a-chart-with-nothing-to-draw-is-still-a-chart
  (doseq [series [[] [{:label "x" :points []}] [{:label "x" :points [[1 5]]}] [{:label "x" :points [[1 nil] [2 nil]]}]]]
    (let [fig (chart/line-chart (assoc base :series series))]
      (is (= :figure (hx/tag fig)) (pr-str series))
      (is (= [] (a11y/problems fig)) (pr-str series)))))

(deftest a-chart-is-the-same-every-time
  (is (= (chart/line-chart base) (chart/line-chart base))))

;; ---------------------------------------------------------------------------
;; A sparkline

(deftest a-sparkline-is-a-line-and-a-dot-on-the-last-point
  (let [s (chart/sparkline {:points (map-indexed vector [3 5 4 8 6]) :tone :high :label "Weight over a week"})
        a (hx/attributes s)]
    (is (= :svg (hx/tag s)))
    (is (= "img" (:role a)))
    (is (= "Weight over a week" (:aria-label a)))
    (is (= "0 0 96 28" (:viewBox a)))
    (is (hx/has-class? (first (hx/by-tag s :path)) "stroke-tone-high-edge"))
    (is (hx/has-class? (first (hx/by-tag s :circle)) "fill-tone-high-edge"))
    (is (= 5 (count (re-seq #"[ML]" (:d (hx/attributes (first (hx/by-tag s :path)))))))))
  (testing "without a label it is decorative"
    (is (= "true" (:aria-hidden (hx/attributes (chart/sparkline {:points [[0 1] [1 2]]}))))))
  (testing "no points, no line and no dot"
    (let [s (chart/sparkline {:points []})]
      (is (empty? (hx/by-tag s :path)))
      (is (empty? (hx/by-tag s :circle))))))
