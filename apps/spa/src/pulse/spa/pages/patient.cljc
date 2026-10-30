(ns pulse.spa.pages.patient
  "The chart of a patient in the clinical workspace (ADR-0029): who they are, the alerts that are active with the rule that raised each
  and the readings behind it, their latest readings, a chart of one measurement over a period with its thresholds, the medication and
  the allergies on file, and their appointments. Pure hiccup of the components of `libs/ui`, with no Reagent, so that the workspace and
  the mock-up of the review make the same page: `content` is what both show, and `page` is the workspace around it.

  What the page is a function of is the state of the chart as `pulse.spa.patient` keeps it, in the shapes of the api, with each part
  `{:state :loading|:loaded|:failed ...}`. **What could not be loaded is said, in its place, and is never shown as empty**: a list the
  summary could not give is a message with a way to try again, and not \"None\". A patient the api refuses or does not know is a page
  that says so and shows nothing else of them.

  What the clinic or another system wrote (a name, a medication, a reason) can be a word of any length, and the page is for a phone too: it is
  shown so that it breaks where it has to (`wrap-anywhere`, which also lets the box that holds it shrink) and never makes the page scroll sideways.

  A control that has nothing to lead to is not there: a pill is there only when `:chart` is among the `links` (a function from
  `{:measurement :days}` to an address), a breadcrumb back to the panel only when `:panel` is, and a button to try again only when
  `:retry` is among the `handlers`. The buttons to acknowledge an alert and to confirm, move or cancel an appointment are not here
  until there are pages for them (the next steps of the phase)."
  (:require [clojure.string :as str]
            [pulse.spa.pages.shell :as shell]
            [pulse.spa.patient :as patient]
            [pulse.spa.person :as person]
            [pulse.ui.component :as c]
            [pulse.view.charts :as charts]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views]))

;; ---------------------------------------------------------------------------
;; What is said of what could not be loaded

(defn- retry-button [handlers]
  (when-let [retry (:retry handlers)]
    (c/button {:variant :outline :size :lg :icon :refresh-cw :on-click retry} "Try again")))

(defn- wrapped
  "`text`, written by someone else, in a box that breaks it wherever it must: a word with no spaces in it does not push the page wide."
  [text]
  [:span {:class "wrap-anywhere"} text])

(defn- not-loaded
  "The message that stands where a part of the page should be when it could not be loaded, as the panel and the portal say it. The button
  is beside the text, and under it (`narrow?`) in a column that has no room for both."
  ([title handlers] (not-loaded title handlers false))
  ([title handlers narrow?]
   (if narrow?
     (c/alert {:tone :high :title title}
              "The problem is on our side, not yours."
              (when-let [button (retry-button handlers)] [:div {:class "mt-3"} button]))
     (c/alert {:tone :high :title title :actions (retry-button handlers)}
              "The problem is on our side, not yours."))))

(defn- section-heading [id text]
  [:h2 {:id id :class "text-base font-semibold"} text])

;; ---------------------------------------------------------------------------
;; Who the patient is

(defn- header [{:keys [title description]} links]
  (list (c/breadcrumbs {:items (concat (when-let [panel (:panel links)] [{:href panel :label "Panel"}])
                                       [{:label (wrapped title)}])})
        (c/page-header {:title (wrapped title) :description (some-> description wrapped)})))

(defn- conditions-row [{:keys [state items]} handlers]
  (case state
    :loading (c/skeleton {:class "h-6 w-64"})
    :lost (not-loaded "The conditions could not be loaded" handlers)
    (if (seq items)
      (into [:ul {:class "flex flex-wrap gap-2" :aria-label "Conditions"}]
            (for [{:keys [display code]} items]
              [:li {:class "max-w-full"}
               (c/badge {:variant :outline :class "h-auto min-h-6 py-0.5 text-left whitespace-normal wrap-anywhere"}
                        (str (views/name-of display) (when-let [code (views/text-of code)] (str " · " code))))]))
      [:p {:class "text-muted"} "No conditions recorded."])))

;; ---------------------------------------------------------------------------
;; The alerts

(defn- alert-block
  "An active alert of the patient, as a message in the colour of its tone: when it was raised and the rule that raised it, and the
  readings behind it."
  [{:keys [text tone since-phrase description evidence]} handlers]
  (c/alert {:tone tone :title (wrapped text) :actions (when (= :failed (:state evidence)) (retry-button handlers))}
           (when (or since-phrase description)
             [:p {:class "wrap-anywhere"} (str/join " " (keep identity [(when since-phrase (str "Raised " since-phrase ".")) description]))])
           (case (:state evidence)
             :loaded (when (seq (:lines evidence))
                       [:p {:class "mt-1"} (str "The latest readings: " (str/join "; " (:lines evidence)) ".")])
             :failed [:p {:class "mt-1"} "The readings behind it could not be loaded."]
             nil)))

(defn- alerts-section [{:keys [state items]} handlers]
  (case state
    :loading (c/skeleton {:class "h-16 w-full"})
    :lost (not-loaded "The alerts could not be loaded" handlers)
    (for [item items]
      (alert-block item handlers))))

;; ---------------------------------------------------------------------------
;; The latest readings

(defn- vitals-section [{:keys [state items lost?]} now handlers]
  (case state
    :loading [:section {:aria-labelledby "vitals"}
              [:div {:class "mb-3"} (section-heading "vitals" "Latest readings")]
              [:div {:class "grid gap-4 md:grid-cols-3"} (repeat 3 (c/skeleton {:class "h-24 w-full"}))]]
    :lost [:section {:aria-labelledby "vitals"}
           [:div {:class "mb-3"} (section-heading "vitals" "Latest readings")]
           (not-loaded "The latest readings could not be loaded" handlers)]
    (when (or (seq items) lost?)
      [:section {:aria-labelledby "vitals"}
       [:div {:class "mb-3"} (section-heading "vitals" "Latest readings")]
       [:div {:class "space-y-4"}
        (when (seq items)
          (into [:div {:class "grid gap-4 md:grid-cols-3"}]
                (for [{:keys [kind reading]} items]
                  (views/vital-tile kind reading now))))
        (when lost? (not-loaded "Some readings could not be loaded" handlers))]])))

;; ---------------------------------------------------------------------------
;; The chart

(defn- phone-only [content] [:div {:class "md:hidden"} content])

(defn- from-tablet [content] [:div {:class "hidden md:block"} content])

(defn- choices
  "The pills that choose the measurement and the period, links to this chart narrowed to the choice."
  [{:keys [kind days offered periods]} chart-link]
  [:div {:class "mb-4 flex flex-wrap items-center justify-between gap-x-6 gap-y-3"}
   (when offered
     (c/pill-nav {:label "Measurement"
                  :items (for [k offered]
                           {:href (chart-link {:measurement k :days days}) :label (get-in views/kind-info [k :label]) :current? (= k kind)})}))
   (c/pill-nav {:label "Period"
                :items (for [d periods]
                         {:href (chart-link {:measurement kind :days d}) :label (str d " days") :current? (= d days)})})])

(defn- drawn
  "The chart for a phone and the one for a desk, of which the page shows the one that fits; nil when none of the readings says anything."
  [{:keys [now]} kind days {:keys [items truncated]}]
  (let [readings (vec (views/usable-series kind items))]
    (when (seq readings)
      (let [cut? (true? truncated)
            span (if cut? (str "latest " (count readings) " readings") (str "last " days " days"))
            from (if cut? (:at (first readings)) (fmt/instant-text (- (fmt/millis now) (* days 86400000))))
            common {:kind kind :readings readings :now now :from from :span span}]
        {:cut? cut?
         :since (when cut? (fmt/when-phrase (:at (first readings)) now))
         :n (count readings)
         :content (list (from-tablet (charts/line-chart (assoc common :id (str "chart-" (name kind) "-wide") :width 600 :height 300 :max-ticks 6)))
                        (phone-only (charts/line-chart (assoc common :id (str "chart-" (name kind) "-narrow") :width 318 :height 260
                                                              :max-ticks 3 :thresholds? false :bands? false))))}))))

(defn- chart-card
  "The chart of one measurement over a period, or what is said instead: that the patient has no reading, that it is on its way, or
  that it could not be loaded."
  [{:keys [kind days series no-readings?] :as m} now links handlers]
  (let [label (get-in views/kind-info [kind :label])
        chart-link (:chart links)
        pills (when (and chart-link kind) (choices m chart-link))]
    (cond
      no-readings?
      (c/card {:title "Readings"}
              (c/empty-state {:icon :activity :title "No readings yet"}
                             "When the patient records one, or a device sends one, you will see it here."))

      (and (nil? series) (nil? (:offered m)))
      (c/card {:title "Readings" :subtitle (str "Last " days " days")}
              (c/skeleton {:class "h-64 w-full"}))

      (nil? series)
      (c/card {:title "Readings"} (not-loaded "The chart could not be loaded" handlers))

      :else
      (case (:state series)
        :loading (c/card {:title label :subtitle (str "Last " days " days")}
                         pills
                         (c/skeleton {:class "h-64 w-full"}))
        :failed (c/card {:title label :subtitle (str "Last " days " days")}
                        pills
                        (not-loaded (str "The " (str/lower-case label) " chart could not be loaded") handlers))
        (let [{:keys [content cut? since n]} (drawn {:now now} kind days (:data series))]
          (if content
            (c/card {:title label
                     :subtitle (if cut? (str "Latest " n " readings, since " since) (str "Last " days " days"))}
                    pills
                    content)
            (c/card {:title label :subtitle (str "Last " days " days")}
                    pills
                    (c/empty-state {:icon (get-in views/kind-info [kind :icon])
                                    :title (str "No " (str/lower-case label) " readings in the last " days " days")}
                                   "Another period, or another measurement, may have some."))))))))

;; ---------------------------------------------------------------------------
;; The record and the appointments

(defn- list-card
  "A card with a list of rows, with what is said when there is none, when the list is on its way and when it could not be loaded."
  [title {:keys [state items rows]} row empty-text handlers]
  (case state
    :loading (c/card {:title title :flush? true}
                     (c/list-group {:aria-label title} (repeat 2 [:li {:class "px-5 py-4"} (c/skeleton {:class "h-10 w-full"})])))
    :lost (not-loaded (str "The " (str/lower-case title) " could not be loaded") handlers true)
    (let [shown (or rows items)]
      (c/card {:title title :flush? true}
              (if (seq shown)
                (c/list-group {:aria-label title} (map row shown))
                [:p {:class "px-5 pb-4 text-muted"} empty-text])))))

(defn- detail [text]
  (when-let [t (views/text-of text)] [:p {:class "text-muted wrap-anywhere"} t]))

(defn- medication-row [{:keys [display dosage]}]
  (c/list-row [:p {:class "font-medium wrap-anywhere"} (views/name-of display)] (detail dosage)))

(defn- allergy-row [{:keys [display reaction]}]
  (c/list-row [:p {:class "font-medium wrap-anywhere"} (views/name-of display)] (detail reaction)))

(defn- appointment-row
  "A row of the appointments of the patient: the day, or a clock for a request, what it is for, when, and its status."
  [{:keys [status description month day reason asked] when-text :when}]
  (c/list-row
   {:leading (if day (c/date-block {:month month :day day}) (c/date-block {:icon :clock}))
    :trailing-wraps? true
    :trailing (views/appointment-chip status)}
   [:p {:class "font-semibold wrap-anywhere"} (or (views/text-of description) "Appointment")]
   (detail (or when-text asked))
   (when-let [r (views/text-of reason)] [:p {:class "text-muted wrap-anywhere"} (str "Reason: " r)])))

;; ---------------------------------------------------------------------------
;; The page

(defn- gone [admin? links]
  (c/card (c/empty-state {:icon :search
                          :title "We cannot find that patient"
                          :heading :h1
                          :actions (when-let [panel (:panel links)]
                                     (c/button {:variant :primary :size :lg :icon-end :arrow-right :href panel} "Go to the panel"))}
                         (if admin?
                           "There is no such patient."
                           "They are not in your panel, or there is no such patient."))))

(defn content
  "The chart of a patient as a page: `state` is the state of the chart (see `pulse.spa.patient`; nil before it begins to load), and `options` are `:admin?` (the
  administrator sees every patient), `:links` (`:panel`, the address of the panel; `:chart`, a function from `{:measurement :days}` to
  the address of this chart narrowed to them) and `:handlers` (`:retry`)."
  [state {:keys [admin? links handlers]}]
  (let [state (or state patient/loading)
        m (patient/model state)
        now (:now state)]
    (if (:gone? m)
      (gone admin? links)
      [:div {:class "space-y-6"}
       (header m links)
       [:p {:class "sr-only" :role "status"} (:live m)]
       (when (= :failed (:patient m)) (not-loaded "The patient could not be loaded" handlers))
       (conditions-row (:conditions m) handlers)
       (alerts-section (:alerts m) handlers)
       (vitals-section (:vitals m) now handlers)
       [:div {:class "grid items-start gap-6 xl:grid-cols-3"}
        [:div {:class "space-y-6 xl:col-span-2"}
         (chart-card m now links handlers)
         [:div {:class "grid items-start gap-6 md:grid-cols-2"}
          (list-card "Medication" (:medications m) medication-row "None." handlers)
          (list-card "Allergies" (:allergies m) allergy-row "None recorded." handlers)]]
        (list-card "Appointments" (:appointments m) appointment-row "None." handlers)]])))

(defn page
  "The workspace around the chart of a patient. `options` are those of `content`, and `:person` (who is signed in), `:me` (what the api
  said of them: see `pulse.spa.person/display`), `:links` (`:panel`, where the page is for the sidebar and the way back) and
  `:handlers` (`:sign-out`, which puts \"Sign out\" in the menu of the person). There is no search box: it would have nothing to search
  here."
  [state {:keys [person me links handlers] :as options}]
  (shell/frame {:current :panel :person person :me me :links links :handlers handlers}
               (content state (assoc options :admin? (person/admin? person)))))
