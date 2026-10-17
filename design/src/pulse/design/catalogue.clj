(ns pulse.design.catalogue
  "The components and the charts of the style tile (ADR-0026): every component of `libs/ui`, as the pages will use
  it, with the synthetic cohort."
  (:require [pulse.design.chrome :as chrome]
            [pulse.design.data :as data]
            [pulse.design.format :as fmt]
            [pulse.design.specimen :as specimen]
            [pulse.design.tile :as tile]
            [pulse.ui.component :as c]))

(defn- frame [{:keys [pages themes]} file title & content]
  (apply chrome/page {:title title :pages pages :file file :themes themes} content))

(defn- demo
  "A surface for showing components, with a small title."
  ([title & content]
   [:div {:class "rounded-box border border-base-300 bg-base-100 p-5 shadow-card"}
    (when title [:h3 {:class "mb-4 text-sm font-semibold text-muted"} title])
    (into [:div] content)]))

(defn- row [& content]
  (into [:div {:class "flex flex-wrap items-center gap-3"}] content))

;; ---------------------------------------------------------------------------
;; Pieces

(def ^:private primary-kind
  {"pt-ana" :blood-pressure "pt-liam" :glucose "pt-sofia" :spo2 "pt-omar" :blood-pressure
   "pt-maya" :weight "pt-noah" nil})

(defn- active-alerts [patient-id]
  (count (filter #(and (= "active" (:status %)) (= (str "Patient/" patient-id) (:patient %))) data/alerts)))

(defn- last-reading-at [patient-id]
  (when-let [times (seq (map :at (vals (data/vitals patient-id))))]
    (apply max-key fmt/millis times)))

(defn- panel-row [{:keys [id name identifier birthDate]}]
  (let [band (data/worst-band id)
        kind (primary-kind id)
        alerts (active-alerts id)]
    {:patient [:div {:class "flex items-center gap-3"}
               (c/avatar {:name name})
               [:div
                [:p {:class "font-medium"} name]
                [:p {:class "text-xs text-muted"} identifier]]]
     :age (fmt/age birthDate data/now-text)
     :status (if band
               (c/band-badge band {:size :sm})
               (c/tone-badge {:tone :info :icon :clock :size :sm} "No readings yet"))
     :last (if-let [at (last-reading-at id)] (fmt/when-text at data/now-text) "–")
     :trend (when kind (specimen/sparkline id kind 14))
     :alerts (if (pos? alerts)
               (c/tone-badge {:tone :critical :icon :bell-ring :size :sm} (str alerts " active"))
               [:span {:class "text-muted"} "None"])}))

(defn panel-table
  "The clinician's panel as a table."
  []
  (c/data-table
   {:caption "Patients of the panel"
    :columns [{:key :patient :label "Patient" :header? true}
              {:key :age :label "Age" :align :end}
              {:key :status :label "Status"}
              {:key :last :label "Last reading"}
              {:key :trend :label "Last 14 days"}
              {:key :alerts :label "Alerts"}]
    :rows (map panel-row (data/patients))}))

;; ---------------------------------------------------------------------------
;; Components

(defn- buttons []
  (list
   (demo "Variants"
         (row (c/button "Primary")
              (c/button {:variant :neutral} "Neutral")
              (c/button {:variant :outline} "Outline")
              (c/button {:variant :subtle} "Subtle")
              (c/button {:variant :ghost} "Ghost")
              (c/button {:variant :danger} "Danger")
              (c/button {:variant :link} "Link")))
   (demo "Sizes: 24, 32, 40 and 48 px high. The portal uses the largest, for a target of 44 px or more"
         (row (c/button {:size :xs} "Extra small")
              (c/button {:size :sm} "Small")
              (c/button "Medium")
              (c/button {:size :lg} "Large")))
   (demo "With an icon, loading, disabled, full width"
         (row (c/button {:icon :calendar-plus} "Book a visit")
              (c/button {:icon-end :arrow-right :variant :outline} "Continue")
              (c/button {:loading? true} "Saving")
              (c/button {:disabled true} "Disabled")
              (c/icon-button {:icon :refresh-cw :label "Refresh" :variant :ghost})
              (c/icon-button {:icon :x :label "Close" :variant :outline :size :sm}))
         [:div {:class "mt-4"} (c/button {:block? true :size :lg :icon :send} "Send the reading")])))

(defn- tones []
  (list
   (demo "The five tones, with the icon and the word that go with them"
         (row (for [tone [:normal :elevated :high :critical :info]] (c/tone-badge {:tone tone})))
         [:div {:class "mt-3"}
          (row (for [tone [:normal :elevated :high :critical :info]] (c/tone-badge {:tone tone :size :sm})))])
   (demo "The bands of a reading, as the api names them"
         (row (for [band ["normal" "low" "elevated" "very-low" "high" "fever" "very-high" "high-fever"]]
                (c/band-badge band))))
   (demo "Badges"
         (row (c/badge "Neutral") (c/badge {:variant :primary} "Primary") (c/badge {:variant :accent} "Accent")
              (c/badge {:variant :outline} "Outline")))))

(defn- alerts []
  (demo "Messages"
        [:div {:class "space-y-3"}
         (c/alert {:tone :critical :title "Blood pressure at stage 2"
                   :actions (c/button {:size :sm :variant :outline} "Review")}
                  "Two of the last three readings are 160 or more (172/104 today at 10:00).")
         (c/alert {:tone :high :title "Oxygen saturation is low"} "92 % or less in two readings of the last 24 hours.")
         (c/alert {:tone :elevated :title "Your weight went up"} "2.1 kg in three days. Tell your care team if you are short of breath.")
         (c/alert {:tone :normal :title "Reading saved"} "Your care team can see it now.")
         (c/alert {:tone :info :title "Your visit is on Friday"} "Dr. Rivera, 10:00. We will remind you the day before.")]))

(defn- tiles []
  (let [ana (data/vitals "pt-ana")]
    [:div {:class "grid gap-4 sm:grid-cols-2 lg:grid-cols-3"}
     (specimen/vital-tile :blood-pressure (:blood-pressure ana))
     (specimen/vital-tile :heart-rate (:heart-rate ana))
     (specimen/vital-tile :weight (:weight ana))
     (specimen/vital-tile :glucose (:glucose (data/vitals "pt-liam")))
     (specimen/vital-tile :spo2 (:spo2 (data/vitals "pt-sofia")))
     (specimen/vital-tile :blood-pressure (:blood-pressure (data/vitals "pt-omar")))]))

(defn- forms []
  [:div {:class "grid gap-5 lg:grid-cols-2"}
   (c/card {:title "Record a reading" :subtitle "A form with an error"}
           [:form {:class "flex flex-col gap-4" :action "#" :method "post"}
            (c/select-field {:id "measurement" :name "measurement" :label "Measurement" :value "blood-pressure"
                             :options [["blood-pressure" "Blood pressure"] ["heart-rate" "Heart rate"]
                                       ["spo2" "Oxygen saturation"] ["glucose" "Glucose"] ["weight" "Weight"]]})
            [:div {:class "grid grid-cols-2 gap-4"}
             (c/text-field {:id "systolic" :name "systolic" :label "Systolic (mmHg)" :type "number" :value "132"
                            :inputmode "numeric"})
             (c/text-field {:id "diastolic" :name "diastolic" :label "Diastolic (mmHg)" :type "number" :value "310"
                            :error "Enter a number between 20 and 200." :inputmode "numeric"})]
            (c/text-field {:id "taken" :name "taken" :label "Taken at" :type "datetime-local" :value "2026-10-13T10:15"})
            (c/textarea-field {:id "note" :name "note" :label "Note" :optional? true :hint "Only your care team can read it."})
            (c/checkbox-field {:id "rest" :name "rest" :label "I was resting" :hint "Sitting for at least five minutes." :checked true})
            [:div {:class "flex gap-2"}
             (c/button {:type "submit"} "Save reading")
             (c/button {:variant :ghost} "Cancel")]])
   (c/card {:title "Settings" :subtitle "Switches, radio buttons and a plain field"}
           [:form {:class "flex flex-col gap-5" :action "#" :method "post"}
            (c/text-field {:id "email" :name "email" :label "Email" :type "email" :value "ana.garcia@pulse.test"
                           :hint "We write only about your appointments and your alerts." :autocomplete "email"})
            (c/radio-group {:name "language" :legend "Language" :value "es"
                            :options [["en" "English"] ["es" "Español"]]})
            (c/toggle-field {:id "remind" :name "remind" :label "Remind me before a visit" :hint "A day before, by email." :checked true})
            (c/toggle-field {:id "alerts-email" :name "alerts-email" :label "Email me when an alert is raised"})
            [:div (c/button {:type "submit" :variant :outline} "Save settings")]])])

(defn- tables []
  (list
   (panel-table)
   [:div {:class "mt-6"}
    (c/tab-nav {:label "Sections of the record"
                :items [{:href "#" :label "Overview" :current? true}
                        {:href "#" :label "Vitals"}
                        {:href "#" :label "Appointments"}
                        {:href "#" :label "Record"}]})]))

(defn- people-and-states []
  [:div {:class "grid gap-5 lg:grid-cols-3"}
   (demo "People"
         (row (c/avatar {:name "Ana García" :size :sm}) (c/avatar {:name "Ana García"}) (c/avatar {:name "Ana García" :size :lg}))
         [:ul {:class "mt-4 space-y-3"}
          (for [p (take 3 (data/patients))]
            [:li {:class "flex items-center gap-3"}
             (c/avatar {:name (:name p)})
             [:div [:p {:class "text-sm font-medium"} (:name p)]
              [:p {:class "text-xs text-muted"} (str (:city p) " · " (fmt/age (:birthDate p) data/now-text) " years")]]])])
   (c/card {:title "An empty list"}
           (c/empty-state {:icon :bell-off :title "No alerts"
                           :actions (c/button {:variant :outline :size :sm} "See acknowledged alerts")}
                          "Nothing needs a decision right now. New alerts appear here as the rules raise them."))
   (demo "Loading"
         [:div {:class "flex items-center gap-3"} (c/spinner {:label "Loading the panel"}) [:span {:class "text-sm text-muted"} "Loading the panel"]]
         [:div {:class "mt-5 space-y-3"}
          (c/skeleton {:class "h-6 w-2/3"})
          (c/skeleton {:class "h-4 w-full"})
          (c/skeleton {:class "h-4 w-5/6"})])])

(defn- figures-and-lists []
  (list
   [:div {:class "grid gap-4 sm:grid-cols-2 xl:grid-cols-4"}
    (c/stat {:label "Patients" :value "4" :icon :users})
    (c/stat {:label "Active alerts" :value "2" :icon :bell-ring :tone :critical :hint "1 acknowledged today"})
    (c/stat {:label "To confirm" :value "2" :icon :calendar-clock :hint "requests for a visit"})
    (c/stat {:label "Readings" :value "18" :icon :activity :hint "in the last 24 hours"})]
   (c/card {:title "Rows of a list" :subtitle "A date, a clock for what has no day yet, an icon or a person at the start; a chip and buttons at the end" :flush? true}
           (c/list-group
            {:aria-label "Examples of rows"}
            (list
             (c/list-row {:leading (c/date-block {:month "Oct" :day "14"})
                          :trailing (list (c/tone-badge {:tone :normal :icon :calendar-check} "Confirmed")
                                          (c/button {:variant :outline :icon :calendar-x} "Cancel appointment"))}
                         [:p {:class "font-semibold"} "Hypertension follow-up"]
                         [:p {:class "text-sm text-muted"} "Wed 14 Oct, 10:00–10:30 · Dr. Elena Rivera"])
             (c/list-row {:leading (c/date-block {:icon :clock})
                          :trailing (list (c/tone-badge {:tone :info :icon :clock} "Requested")
                                          (c/button {:variant :ghost :icon :x} "Cancel request"))}
                         [:p {:class "font-semibold"} "Medication review"]
                         [:p {:class "text-sm text-muted"} "Asked for a visit between Oct 20 and Oct 27 · Dr. Elena Rivera"])
             (c/list-row {:leading (c/icon-disc {:icon :pill}) :trailing (c/badge {:variant :outline} "Since Nov 2025")}
                         [:p {:class "font-medium"} "Lisinopril"]
                         [:p {:class "text-sm text-muted"} "10 mg once daily"])
             (c/list-row {:leading (c/icon-disc {:icon :activity :variant :muted}) :trailing (c/band-badge "very-high")}
                         [:p {:class "font-medium"} "Blood pressure"]
                         [:p {:class "text-sm text-muted"} "177/100 mmHg · Today, 10:00"]))))
   [:div {:class "grid gap-4 md:grid-cols-2"}
    (demo "A person" (c/person {:name "Dr. Elena Rivera" :detail "Internal medicine" :size :lg}))
    (demo "Round icons" (row (c/icon-disc {:icon :pill}) (c/icon-disc {:icon :stethoscope :size :lg})
                             (c/icon-disc {:icon :clock :variant :muted}) (c/icon-disc {:icon :clock :size :lg :variant :muted})))]))

(defn- navigation []
  (list
   (demo "Pills that filter a list, for a desk and for a finger (44 px high)"
         (c/pill-nav {:label "Filter the panel"
                      :items [{:href "#" :label "All" :count 4 :current? true} {:href "#" :label "With active alerts" :count 2}
                              {:href "#" :label "Normal" :count 1}]})
         [:div {:class "mt-4"}
          (c/pill-nav {:label "Period" :size :lg
                       :items [{:href "#" :label "7 days"} {:href "#" :label "14 days"} {:href "#" :label "30 days" :current? true}]})])
   (demo "Breadcrumbs and search"
         (c/breadcrumbs {:items [{:href "#" :label "Panel"} {:href "#" :label "Patients"} {:label "Ana García"}]})
         [:div {:class "mt-4 max-w-md"} (c/search-field {:id "demo-search" :name "q" :label "Search patients" :placeholder "Search patients"})])
   (demo "The menu of the person who is signed in, open"
         [:div {:class "flex h-40 justify-end"}
          (c/user-menu {:open true :name "Dr. Elena Rivera"
                        :items [{:href "#" :label "Profile" :icon :user} {:href "#" :label "Sign out" :icon :log-out}]})])))

(defn components-page [context]
  (frame context "components.html" "Components"
         (tile/intro "Components"
                     "Everything the pages are made of, as they will be used: the same functions in the patient portal and in the clinical workspace. The theme choice at the top right changes all of it.")
         (tile/section "buttons" "Buttons" nil (buttons))
         (tile/section "tones" "Tones and bands"
                       "A reading away from normal has three degrees, and each has its own icon and its own word, so that colour is never the only difference."
                       (tones))
         (tile/section "messages" "Messages" nil (alerts))
         (tile/section "tiles" "Readings"
                       "The latest reading of each measurement. A reading away from normal also has a thick edge on the left."
                       (tiles))
         (tile/section "forms" "Forms" "A label above, a hint below, an error with an icon and a sentence. The border of a field has 3:1 against the page."
                       (forms))
         (tile/section "tables" "Tables and tabs" "The clinician's panel. The sparkline takes the tone of the latest reading; a patient with no readings says so."
                       (tables))
         (tile/section "states" "People and states" nil (people-and-states))
         (tile/section "figures" "Figures, lists and rows"
                       "The figures of a summary, and the rows that the lists of appointments, medication and readings are made of."
                       (figures-and-lists))
         (tile/section "navigation" "Navigation"
                       "Filters, the path to a page, search and the menu of the person. The dialogs are in the mock-ups: Cancel an appointment and Confirm an appointment."
                       (navigation))))

;; ---------------------------------------------------------------------------
;; Charts

(defn- chart-card [title subtitle chart-hiccup]
  (c/card {:title title :subtitle subtitle} chart-hiccup))

(defn charts-page [context]
  (frame context "charts.html" "Charts"
         (tile/intro "Charts"
                     "A chart is an SVG drawn for a width, with the colours of the theme. A line is told from another by its dash and its marker as well as by its colour, and the numbers are in a table anyone can open under it.")
         (tile/section "bp" "Blood pressure, a month"
                       "Two series with the lines of rule R1, and the 14 days in which Ana's pressure climbed."
                       (chart-card "Blood pressure" "Ana García · last 30 days"
                                   (specimen/line-chart "pt-ana" :blood-pressure
                                                        {:days 30 :width 1000 :height 320 :ticks-every 5 :id "bp-wide"})))
         (tile/section "narrow" "Drawn for the width"
                       "On a phone the chart is drawn at 340 pixels, not the wide one made smaller, so the text stays the same size."
                       [:div {:class "grid items-start gap-5 md:grid-cols-[22rem_1fr]"}
                        [:div {:class "max-w-sm"}
                         (chart-card "Blood pressure" "Compact, 340 px"
                                     (specimen/line-chart "pt-ana" :blood-pressure
                                                          {:days 14 :width 340 :height 240 :ticks-every 4 :id "bp-compact"
                                                           :thresholds? false :bands? false}))]
                        (chart-card "Blood pressure" "Wide, 640 px"
                                    (specimen/line-chart "pt-ana" :blood-pressure
                                                         {:days 14 :width 640 :height 260 :ticks-every 2 :id "bp-medium"}))])
         (tile/section "others" "Other measurements"
                       "The normal band is shaded behind the line, and the thresholds of the rules are dashed."
                       [:div {:class "grid gap-5 lg:grid-cols-2"}
                        (chart-card "Heart rate" "Omar Haddad · last 7 days"
                                    (specimen/line-chart "pt-omar" :heart-rate {:days 7 :width 520 :height 250 :id "hr-omar"}))
                        (chart-card "Glucose" "Liam Chen · last 7 days"
                                    (specimen/line-chart "pt-liam" :glucose {:days 7 :width 520 :height 250 :id "glucose-liam"}))
                        (chart-card "Oxygen saturation" "Sofía Rossi · last 14 days"
                                    (specimen/line-chart "pt-sofia" :spo2 {:days 14 :width 520 :height 250 :ticks-every 2 :id "spo2-sofia"}))
                        (chart-card "Weight" "Maya Patel · last 14 days"
                                    (specimen/line-chart "pt-maya" :weight {:days 14 :width 520 :height 250 :ticks-every 2 :id "weight-maya"}))])))
