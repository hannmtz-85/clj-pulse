(ns pulse.ui.chart
  "Charts as SVG hiccup (ADR-0026): a function from data to a figure, with no script. It is accessible by
  construction: the SVG has a role, a title and a description, a series is told from another by its dash and its
  marker as well as by its colour, and the same numbers are in a table under the chart that anyone can open.

  A chart is drawn for a width: the text is the size it is at that width, so the portal draws a compact chart
  for a phone and a wide one for a desk and shows the one that fits. The colours are the tokens `series-1` to
  `series-4`, `chart-grid` and the tones, so a chart follows the theme.

  The classes are written whole, in tables, because Tailwind finds them by reading this file."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Numbers

(defn- tidy
  "`x` rounded to two decimals, so that a path is short and carries no noise of floating point."
  [x]
  (/ (Math/round (* (double x) 100.0)) 100.0))

(defn fmt
  "A number as the text of an SVG attribute: at most two decimals, and no `.0` at the end."
  [x]
  (let [s (str (tidy x))]
    (if (str/ends-with? s ".0") (subs s 0 (- (count s) 2)) s)))

(defn nice-ticks
  "Round numbers that cover the range from `lo` to `hi`, in about `target` intervals: `{:ticks :step :lo :hi}`,
  where `:lo` and `:hi` are the first and last tick (a range of one value is widened by one on each side). The
  step is 1, 2, 5 or 10 times a power of ten, the one nearest to the range divided by `target`, as d3 chooses
  it."
  [lo hi target]
  (let [lo (double lo)
        hi (double hi)
        [lo hi] (if (= lo hi) [(- lo 1.0) (+ hi 1.0)] [lo hi])
        raw (/ (- hi lo) (max 1 target))
        magnitude (Math/pow 10 (Math/floor (/ (Math/log raw) (Math/log 10))))
        error (/ raw magnitude)
        step (* magnitude (cond (>= error (Math/sqrt 50)) 10.0
                                (>= error (Math/sqrt 10)) 5.0
                                (>= error (Math/sqrt 2)) 2.0
                                :else 1.0))
        from (long (Math/floor (/ lo step)))
        to (long (Math/ceil (/ hi step)))
        ticks (mapv #(/ (Math/round (* % step 1e6)) 1e6) (range from (inc to)))]
    {:ticks ticks :step step :lo (first ticks) :hi (peek ticks)}))

(defn- linear
  "A function from the domain `[d0 d1]` to the range `[r0 r1]`. A domain of one value maps to the middle."
  [[d0 d1] [r0 r1]]
  (let [span (- d1 d0)]
    (fn [v]
      (if (zero? span)
        (/ (+ r0 r1) 2.0)
        (+ r0 (* (- r1 r0) (/ (- v d0) span)))))))

;; ---------------------------------------------------------------------------
;; The look of the parts, whole classes in tables

(def ^:private series-stroke
  {1 "stroke-series-1" 2 "stroke-series-2" 3 "stroke-series-3" 4 "stroke-series-4"})

(def ^:private series-fill
  {1 "fill-series-1" 2 "fill-series-2" 3 "fill-series-3" 4 "fill-series-4"})

(def ^:private series-dash
  "How the line of each series is dashed, so that it is not told by colour alone."
  {1 nil 2 "7 4" 3 "2 4" 4 "9 3 2 3"})

(def ^:private series-marker
  {1 :circle 2 :square 3 :diamond 4 :triangle})

(def ^:private band-fill
  {:normal "fill-tone-normal-soft" :elevated "fill-tone-elevated-soft" :high "fill-tone-high-soft"
   :critical "fill-tone-critical-soft" :info "fill-tone-info-soft"})

(def ^:private band-text
  {:normal "fill-tone-normal-ink" :elevated "fill-tone-elevated-ink" :high "fill-tone-high-ink"
   :critical "fill-tone-critical-ink" :info "fill-tone-info-ink"})

(def ^:private threshold-stroke
  {:normal "stroke-tone-normal-edge" :elevated "stroke-tone-elevated-edge" :high "stroke-tone-high-edge"
   :critical "stroke-tone-critical-edge" :info "stroke-tone-info-edge"})

(def ^:private sparkline-stroke
  {:normal "stroke-tone-normal-edge" :elevated "stroke-tone-elevated-edge" :high "stroke-tone-high-edge"
   :critical "stroke-tone-critical-edge" :info "stroke-tone-info-edge" :neutral "stroke-series-1"})

(def ^:private sparkline-fill
  {:normal "fill-tone-normal-edge" :elevated "fill-tone-elevated-edge" :high "fill-tone-high-edge"
   :critical "fill-tone-critical-edge" :info "fill-tone-info-edge" :neutral "fill-series-1"})

;; ---------------------------------------------------------------------------
;; Shapes

(defn- path-d
  "The `d` of a polyline through `coordinates`, a sequence of `[x y]` or nil for a gap: a gap starts a new line."
  [coordinates]
  (->> coordinates
       (partition-by nil?)
       (remove #(nil? (first %)))
       (map (fn [run]
              (str "M" (str/join " L" (map (fn [[x y]] (str (fmt x) "," (fmt y))) run)))))
       (str/join " ")))

(defn- marker
  "The mark of a point: a circle, a square, a diamond or a triangle of radius `r`, with `attributes`."
  [shape cx cy r attributes]
  (case shape
    :circle [:circle (assoc attributes :cx (fmt cx) :cy (fmt cy) :r (fmt r))]
    :square [:rect (assoc attributes :x (fmt (- cx r)) :y (fmt (- cy r)) :width (fmt (* 2 r)) :height (fmt (* 2 r)))]
    :diamond (let [d (* r 1.35)]
               [:path (assoc attributes :d (str "M" (fmt cx) "," (fmt (- cy d)) " L" (fmt (+ cx d)) "," (fmt cy)
                                                " L" (fmt cx) "," (fmt (+ cy d)) " L" (fmt (- cx d)) "," (fmt cy) " Z"))])
    :triangle (let [h (* r 1.25)]
                [:path (assoc attributes :d (str "M" (fmt cx) "," (fmt (- cy h)) " L" (fmt (+ cx h)) "," (fmt (+ cy (* h 0.8)))
                                                 " L" (fmt (- cx h)) "," (fmt (+ cy (* h 0.8))) " Z"))])))

;; ---------------------------------------------------------------------------
;; A line chart

(def ^:private default-margin {:top 16 :right 16 :bottom 32 :left 48})

(def ^:private max-markers
  "A series with more points than this has a marker on its last point only."
  24)

(def ^:private label-gap
  "The least distance, in pixels, between two words in the right margin."
  14)

(def ^:private char-width
  "About how wide a character of a label of 12 px is, a little generous, to make room for the words of the margin."
  6.6)

(defn- series-color [index series]
  (or (:color series) (inc index)))

(defn- all-values [series]
  (for [s series
        [_ v] (:points s)
        :when (some? v)]
    v))

(defn domain
  "The y domain `[lo hi]` of the chart `spec`: `:y-domain` when it has one, and otherwise round numbers that cover
  every value of every series and every threshold."
  [{:keys [y-domain series thresholds y-ticks] :or {y-ticks 5}}]
  (if y-domain
    (vec y-domain)
    (let [values (concat (all-values series) (map :at thresholds))
          {:keys [lo hi]} (nice-ticks (if (seq values) (apply min values) 0) (if (seq values) (apply max values) 1) y-ticks)]
      [lo hi])))

(defn- push-down
  "The words, in order, each at least `gap` below the one before."
  [gap words]
  (reduce (fn [placed word]
            (conj placed (update word :y #(if-let [previous (peek placed)] (max % (+ (:y previous) gap)) %))))
          []
          words))

(defn- pull-up
  "The words, in order, each at least `gap` above the one after, and the last no lower than `bottom`."
  [gap bottom words]
  (vec (reduce (fn [placed word]
                 (cons (update word :y min (if-let [following (first placed)] (- (:y following) gap) bottom))
                       placed))
               ()
               (rseq (vec words)))))

(defn dodge
  "Moves the words of a column apart so that none is on another, keeping their order: `labels` is a collection of maps
  with a `:y`, and the result is a vector of them sorted by it, at least `gap` apart and between `top` and `bottom` as
  far as there is room. A word that has room where it is does not move."
  [labels gap top bottom]
  (->> (sort-by :y labels)
       (push-down gap)
       (pull-up gap bottom)
       ;; when there is not room for all of them, the top and the gaps win over the bottom
       (map #(update % :y max top))
       (push-down gap)))

(defn- margin-words
  "The words that go in the right margin, with where each belongs in the data: the label of each band, at its
  middle, and of each threshold, at its value, for the ones that have a label and are in the domain."
  [bands thresholds [y-lo y-hi]]
  (concat
   (for [{:keys [from to tone label]} bands
         :let [lo (max from y-lo) hi (min to y-hi)]
         :when (and label (< lo hi))]
     {:at (/ (+ lo hi) 2.0) :text label :class (str (band-text tone) " text-xs font-medium")})
   (for [{:keys [at tone label]} thresholds
         :when (and label (<= y-lo at y-hi))]
     {:at at :text label :class (str (band-text tone) " text-xs font-medium")})))

(defn- legend-item [index series]
  (let [n (series-color index series)
        shape (series-marker n)]
    [:li {:class "inline-flex items-center gap-2"}
     [:svg {:width 30 :height 12 :viewBox "0 0 30 12" :aria-hidden "true"}
      [:line (cond-> {:x1 2 :x2 28 :y1 6 :y2 6 :stroke-width 2 :stroke-linecap "round" :class (series-stroke n)}
               (series-dash n) (assoc :stroke-dasharray (series-dash n)))]
      (marker shape 15 6 3.5 {:class (series-fill n)})]
     [:span (:label series) (when (:unit series) [:span {:class "text-muted"} (str " (" (:unit series) ")")])]]))

(defn- data-table [{:keys [series x-label table-label]}]
  (let [xs (->> series (mapcat #(map first (:points %))) distinct sort)
        lookup (for [s series] (into {} (:points s)))]
    [:details {:class "mt-3 text-sm"}
     [:summary {:class "cursor-pointer text-primary"} (or table-label "View the numbers as a table")]
     [:div {:class "mt-2 max-h-64 overflow-auto rounded-box border border-base-300" :role "region"
            :aria-label (or table-label "The numbers of the chart") :tabindex "0"}
      [:table {:class "table table-sm"}
       [:caption {:class "sr-only"} (or table-label "The numbers of the chart")]
       [:thead [:tr
                [:th {:scope "col"} "Time"]
                (for [s series] [:th {:scope "col" :class "text-right"} (:label s)])]]
       (into [:tbody]
             (for [x xs]
               [:tr
                [:th {:scope "row" :class "font-medium"} ((or x-label str) x)]
                (for [m lookup] [:td {:class "text-right tabular-nums"} (if-some [v (get m x)] (str v) "–")])]))]]]))

(defn- series-marks
  "The line of a series and its markers: a small one on each point when there are few, and a large one with a ring
  on the last."
  [n coordinates]
  (let [drawn (remove nil? coordinates)
        shape (series-marker n)
        look (str (series-fill n) " stroke-base-100")
        [last-x last-y] (last drawn)]
    [:g
     [:path (cond-> {:d (path-d coordinates) :fill "none" :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                     :class (series-stroke n)}
              (series-dash n) (assoc :stroke-dasharray (series-dash n)))]
     (when (<= (count drawn) max-markers)
       (for [[cx cy] (butlast drawn)]
         (marker shape cx cy 3 {:class look :stroke-width 1.5})))
     (when last-x
       (marker shape last-x last-y 5 {:class look :stroke-width 2}))]))

(defn line-chart
  "A line chart as a `figure`. The spec:

    :id           for the ids that tie the title and the description to the SVG (required)
    :title        what the chart is, and :description what it shows, in a sentence, for a screen reader
    :width :height  the drawing, in pixels (640 by 260 by default); the SVG scales to its container
    :margin       a map over {:top 16 :right 16 :bottom 32 :left 48}; the right margin grows to hold the words of
                  the bands and the thresholds, unless the map has a :right
    :x-domain     [lo hi] (the extent of the data by default) and :x-ticks, a vector of {:at :label}
    :y-domain     [lo hi] (round numbers around the data and the thresholds by default) and :y-ticks, how many
    :bands        shaded ranges, [{:from :to :tone :label}], the label in the right margin
    :thresholds   dashed lines, [{:at :tone :label}], the label in the right margin
    :series       [{:label :unit :points [[x y] ...] :color 1-4}]; a nil y is a gap
    :x-label      a function from an x to the text of its row in the table
    :table-label  the name of the table (\"View the numbers as a table\" by default)

  A series has the color, the dash and the marker of its number, 1 to 4, in the order they are given unless
  `:color` says otherwise. Words in the right margin that would overlap are moved apart."
  [{:keys [id title description width height margin x-domain x-ticks series bands thresholds y-ticks]
    :or {width 640 height 260 y-ticks 5}
    :as spec}]
  (when-not (and id title description)
    (throw (ex-info "A chart needs an :id, a :title and a :description" {:id id :title title})))
  (let [[y-lo y-hi] (domain spec)
        words (margin-words bands thresholds [y-lo y-hi])
        widest (apply max 0 (map #(* char-width (count (:text %))) words))
        {:keys [top right bottom left]} (let [m (merge default-margin margin)]
                                          (if (or (contains? margin :right) (zero? widest))
                                            m
                                            (assoc m :right (max (:right m) (+ 12 (Math/ceil widest))))))
        plot-l left
        plot-r (- width right)
        plot-t top
        plot-b (- height bottom)
        xs (for [s series [x _] (:points s)] x)
        [x-lo x-hi] (or x-domain (if (seq xs) [(apply min xs) (apply max xs)] [0 1]))
        sx (linear [x-lo x-hi] [plot-l plot-r])
        sy (linear [y-lo y-hi] [plot-b plot-t])
        y-tick-values (filter #(<= y-lo % y-hi) (:ticks (nice-ticks y-lo y-hi y-ticks)))
        x-tick-list (or x-ticks (map (fn [t] {:at t :label (fmt t)}) (:ticks (nice-ticks x-lo x-hi 6))))
        placed (dodge (map #(assoc % :y (sy (:at %))) words) label-gap plot-t plot-b)]
    [:figure {:class "w-full"}
     [:svg {:viewBox (str "0 0 " width " " height) :width width :height height :role "img"
            :aria-labelledby (str id "-title " id "-desc") :class "h-auto w-full max-w-full"}
      [:title {:id (str id "-title")} title]
      [:desc {:id (str id "-desc")} description]
      ;; reference ranges
      (for [{:keys [from to tone]} bands
            :let [lo (max from y-lo) hi (min to y-hi)]
            :when (< lo hi)]
        [:rect {:x (fmt plot-l) :y (fmt (sy hi)) :width (fmt (- plot-r plot-l)) :height (fmt (- (sy lo) (sy hi)))
                :class (band-fill tone) :opacity "0.7"}])
      ;; grid and the numbers of the y axis
      (for [v y-tick-values
            :let [yy (sy v)]]
        [:g
         [:line {:x1 (fmt plot-l) :x2 (fmt plot-r) :y1 (fmt yy) :y2 (fmt yy) :stroke-width 1 :class "stroke-chart-grid"}]
         [:text {:x (fmt (- plot-l 8)) :y (fmt yy) :dy "0.32em" :text-anchor "end" :class "fill-muted text-xs tabular-nums"} (fmt v)]])
      ;; the x axis
      [:line {:x1 (fmt plot-l) :x2 (fmt plot-r) :y1 (fmt plot-b) :y2 (fmt plot-b) :stroke-width 1 :class "stroke-control-border"}]
      (for [{:keys [at label]} x-tick-list
            :let [xx (sx at)]]
        [:g
         [:line {:x1 (fmt xx) :x2 (fmt xx) :y1 (fmt plot-b) :y2 (fmt (+ plot-b 5)) :stroke-width 1 :class "stroke-control-border"}]
         [:text {:x (fmt xx) :y (fmt (+ plot-b 21)) :text-anchor "middle" :class "fill-muted text-xs tabular-nums"} label]])
      ;; thresholds
      (for [{:keys [at tone]} thresholds
            :when (<= y-lo at y-hi)
            :let [yy (sy at)]]
        [:line {:x1 (fmt plot-l) :x2 (fmt plot-r) :y1 (fmt yy) :y2 (fmt yy) :stroke-width 1.5 :stroke-dasharray "6 4"
                :class (threshold-stroke tone)}])
      ;; the series
      (for [[index s] (map-indexed vector series)]
        (series-marks (series-color index s)
                      (map (fn [[x y]] (when (some? y) [(sx x) (sy y)])) (:points s))))
      ;; the words of the bands and the thresholds, in the right margin
      (for [{:keys [y text] word-class :class} placed]
        [:text {:x (fmt (+ plot-r 8)) :y (fmt y) :dy "0.32em" :text-anchor "start" :class word-class} text])]
     (into [:figcaption {:class "mt-2 flex flex-wrap gap-x-5 gap-y-1 text-sm"}]
           (map-indexed legend-item series))
     (data-table spec)]))

;; ---------------------------------------------------------------------------
;; A sparkline

(defn sparkline
  "A small line with no axes, for a row of a table: `:points` as `[[x y] ...]`, `:tone` (the colour of the line, as
  the tone of the latest reading), `:width` and `:height` (96 by 28 by default) and `:label`, what a screen reader
  says (\"Heart rate over the last 7 days\"). The last point has a dot."
  [{:keys [points width height tone label] :or {width 96 height 28 tone :neutral}}]
  (let [points (filter (comp some? second) points)
        xs (map first points)
        ys (map second points)
        pad 4
        sx (linear (if (seq xs) [(apply min xs) (apply max xs)] [0 1]) [pad (- width pad)])
        sy (linear (if (seq ys) [(apply min ys) (apply max ys)] [0 1]) [(- height pad) pad])
        coordinates (map (fn [[x y]] [(sx x) (sy y)]) points)
        [lx ly] (last coordinates)]
    [:svg (cond-> {:viewBox (str "0 0 " width " " height) :width width :height height :class "shrink-0"}
            label (assoc :role "img" :aria-label label)
            (not label) (assoc :aria-hidden "true"))
     (when (seq coordinates)
       [:path {:d (path-d coordinates) :fill "none" :stroke-width 1.75 :stroke-linecap "round" :stroke-linejoin "round"
               :class (get sparkline-stroke tone)}])
     (when lx
       [:circle {:cx (fmt lx) :cy (fmt ly) :r "2.5" :class (get sparkline-fill tone)}])]))
