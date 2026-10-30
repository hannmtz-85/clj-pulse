(ns pulse.web.pages.readings
  "The readings of the portal (ADR-0028): the trend of one measurement over a period, as the chart of a phone or of a desk, the
  figures that sum it up and the table of the last ones. It is a function of the data in the shapes of the api, and the mock-ups
  of the review give it the synthetic cohort, so that what is reviewed is what is served. Pure hiccup, free of Ring.

  A page of the portal is `content` here, the part that goes in `main`. The `model` is

    `:now`      the instant it is, as text
    `:kind`     the measurement shown (`:heart-rate`), or nil for a patient who has no readings at all
    `:days`     the period, one of `periods`
    `:summary`  what `GET /api/patients/{id}/summary` answered, or nil when it could not be had: it says which measurements the
                patient has readings of, and so which have a place among the choices (see `pulse.view.views/offered-kinds`)
    `:series`   what `GET /api/patients/{id}/observations` answered for `:kind`, the newest `series-limit` readings of the period,
                or nil when it could not be had
    `:saved`    the reading the patient has just recorded, as the api answered, to say so (see `pulse.web.readings`)

  and `links` says where the buttons lead: `:readings` (this page, which the choices of the measurement and of the period are
  addresses of), `:new-reading` (the page to record one, which the button takes the measurement to) and `:retry`. A button
  whose link is not there is left out (see `pulse.web.pages.parts`).

  What the api could not give is said, and never shown as an empty chart; and what it cut is said too: the api gives at most
  `series-limit` readings, so a stretch with more is shown as the latest of them, and the figures say that they are of these."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.view.charts :as charts]
            [pulse.view.format :as fmt]
            [pulse.web.pages.parts :as parts]
            [pulse.view.views :as views])
  (:import (java.time Duration Instant)))

(def periods
  "The periods the patient can look at, in days."
  [7 14 30])

(def default-days
  "The period shown when none is asked for."
  30)

(def series-limit
  "How many readings are asked of the api: the most it gives."
  500)

(def table-rows
  "How many of the last readings the table has."
  8)

;; ---------------------------------------------------------------------------
;; What the model says

(defn- from-text
  "The instant the chart begins at: the start of the period, or, for a series that was cut, its oldest reading."
  [now days truncated? readings]
  (if truncated?
    (:at (first readings))
    (str (.minus (Instant/parse now) (Duration/ofDays days)))))

(defn- address
  "The address of this page for a measurement and a period."
  [links kind days]
  (str (:readings links) "?measurement=" (name kind) "&days=" days))

(defn- record-address
  "Where the button to record a reading goes: the page to record one, for the measurement being looked at."
  [links kind]
  (when-let [href (:new-reading links)]
    (if kind (str href "?measurement=" (name kind)) href)))

;; ---------------------------------------------------------------------------
;; The parts of the page

(defn- header [links kind]
  (c/page-header {:title "My readings"
                  :description "What you and your devices have recorded."
                  :actions (when-let [href (record-address links kind)]
                             (c/button {:size :lg :icon :plus :href href} "Record a reading"))}))

(defn- saved-notice
  "The message that a reading has just been recorded, in the words of what the api stored. Nil when `saved` is not a reading that
  can be told."
  [saved now]
  (let [kind (some-> (:measurement saved) keyword)]
    (when (and kind (contains? views/kind-info kind))
      (c/alert {:tone :normal :title "Reading saved" :role "status"}
               (when (views/usable? kind saved)
                 (str (get-in views/kind-info [kind :label]) ", " (views/reading-text kind saved) " " (fmt/unit (:unit saved))
                      (when (fmt/instant? (:at saved)) (str ", " (fmt/when-phrase (:at saved) now))) "."))))))

(defn- choices [{:keys [summary kind days]} links]
  [:div {:class "flex flex-wrap items-center justify-between gap-x-6 gap-y-3"}
   (c/pill-nav {:label "Measurement" :size :lg
                :items (for [k (views/offered-kinds summary kind)]
                         {:href (address links k days) :label (get-in views/kind-info [k :label]) :current? (= k kind)})})
   (c/pill-nav {:label "Period" :size :lg
                :items (for [d periods]
                         {:href (address links kind d) :label (str d " days") :current? (= d days)})})])

(defn- phone-only [content] [:div {:class "md:hidden"} content])

(defn- from-tablet [content] [:div {:class "hidden md:block"} content])

(defn- charts-of
  "The chart for a phone and the one for a desk, of which the page shows the one that fits."
  [{:keys [now kind]} readings span from]
  (let [common {:kind kind :readings readings :now now :from from :span span}]
    (list (from-tablet (charts/line-chart (assoc common :id (str "chart-" (name kind) "-wide") :width 950 :height 320 :max-ticks 7)))
          (phone-only (charts/line-chart (assoc common :id (str "chart-" (name kind) "-narrow") :width 318 :height 260
                                                :max-ticks 3 :thresholds? false :bands? false))))))

(defn- stats-row [kind readings {:keys [now days]} truncated?]
  (let [key-of #(or (:systolic %) (:value %))
        text (fn [reading] (str (views/reading-text kind reading) " " (fmt/unit (:unit reading))))
        latest (peek readings)
        highest (apply max-key key-of readings)
        lowest (apply min-key key-of readings)
        hint (fn [reading] (fmt/when-text (:at reading) now))]
    [:div {:class "grid gap-4 sm:grid-cols-3"}
     (c/stat {:label "Latest" :value (text latest) :hint (hint latest)})
     (c/stat {:label (if truncated? "Highest in these readings" (str "Highest, " days " days")) :value (text highest) :hint (hint highest)})
     (c/stat {:label (if truncated? "Lowest in these readings" (str "Lowest, " days " days")) :value (text lowest) :hint (hint lowest)})]))

(defn- readings-table [kind readings now]
  (let [shown (vec (take table-rows (rseq readings)))
        n (count shown)
        label (get-in views/kind-info [kind :label])]
    [:section {:aria-labelledby "last"}
     [:div {:class "mb-3"} (parts/section-heading "last" (if (= 1 n) "The last reading" "The last readings"))]
     (c/data-table
      {:caption (if (= 1 n) (str label ", the last reading") (str label ", the last " n " readings"))
       :dense? true
       :columns [{:key :at :label "When" :header? true :class "whitespace-nowrap"}
                 {:key :value :label "Reading" :align :end :class "whitespace-nowrap"}
                 {:key :band :label (list [:span {:class "hidden sm:inline"} "Compared with the range"] [:span {:class "sm:hidden"} "Range"])}]
       :rows (for [reading shown]
               {:at (fmt/when-text (:at reading) now)
                :value (str (views/reading-text kind reading) " " (fmt/unit (:unit reading)))
                :band (or (c/band-badge (:band reading) {:size :sm}) "–")})})]))

(defn- trend
  "What there is to see of one measurement: its chart and figures and table, or what to say instead."
  [{:keys [now kind days series] :as model} links]
  (let [label (get-in views/kind-info [kind :label])]
    (if (nil? series)
      (parts/not-loaded (str "Your " (str/lower-case label) " readings could not be loaded") links {:rest-of-page? false})
      (let [readings (vec (views/usable-series kind (:items series)))
            truncated? (boolean (:truncated series))]
        (if (empty? readings)
          (c/card {:title label :subtitle (str "Last " days " days")}
                  (c/empty-state {:icon (get-in views/kind-info [kind :icon])
                                  :title (str "No " (str/lower-case label) " readings in the last " days " days")}
                                 "When you record one, or a device sends one, you will see it here."))
          (let [span (if truncated? (str "latest " (count readings) " readings") (str "last " days " days"))
                from (from-text now days truncated? readings)]
            [:div {:class "space-y-6"}
             (c/card {:title label
                      :subtitle (if truncated?
                                  (str "Your latest " (count readings) " readings, since " (fmt/when-phrase (:at (first readings)) now))
                                  (str "Last " days " days"))}
                     (charts-of model readings span from))
             (stats-row kind readings model truncated?)
             (readings-table kind readings now)]))))))

;; ---------------------------------------------------------------------------
;; The page

(defn content
  "The readings: the choice of the measurement and of the period, the trend of the one chosen, and, if the patient has just recorded
  a reading, the message that says so. For a patient who has no readings at all (`:kind` is nil), what to do first. `model` and
  `links` are described at the top of this namespace."
  [{:keys [now kind saved] :as model} links]
  (if (nil? kind)
    [:div {:class "space-y-6"}
     (header links nil)
     (saved-notice saved now)
     (parts/no-readings-card links)]
    [:div {:class "space-y-6"}
     (header links kind)
     (saved-notice saved now)
     (choices model links)
     (trend model links)]))
