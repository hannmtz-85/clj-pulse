(ns pulse.design.workspace
  "The mock-ups of the clinical workspace (ADR-0026), the interface of `spa`: what a practitioner sees of their panel,
  and what the administrator sees besides. The practitioner is Dr. Elena Rivera, whose panel has Ana (a very high blood
  pressure), Sofía (a low oxygen saturation), Liam and Omar; the administrator is Sam Admin. Each page is a function
  from the context of the build to the document of its frame."
  (:require [clojure.string :as str]
            [pulse.design.data :as data]
            [pulse.design.format :as fmt]
            [pulse.design.mockup :as mockup]
            [pulse.design.specimen :as specimen]
            [pulse.design.views :as views]
            [pulse.fhir.appointment :as appointment]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout])
  (:import (java.time LocalDate)))

(def practitioner-id "pr-rivera")

(defn- link [file] (mockup/href file))

(def charts
  "The charts there is a mock-up of, by patient: the file of each. They are the panel of the practitioner."
  {"pt-ana" "workspace-patient.html"
   "pt-sofia" "workspace-patient-sofia.html"
   "pt-liam" "workspace-patient-liam.html"
   "pt-omar" "workspace-patient-omar.html"})

(defn- chart-link [patient-id]
  (link (get charts patient-id "workspace-panel.html")))

;; ---------------------------------------------------------------------------
;; The frame

(defn- alerts-of
  "The alert cards of a practitioner's panel, or of every patient for `nil`."
  [for-practitioner]
  (filter #(or (nil? for-practitioner) (= for-practitioner (get-in % [:patient :practitioner]))) (views/alert-cards)))

(defn- nav
  "The groups of the sidebar: the administrator has one more, with the jobs."
  [admin? active-alerts]
  (cond-> [{:items [{:id :panel :href (link "workspace-panel.html") :label "Panel" :icon :layout-dashboard}
                    {:id :alerts :href (link "workspace-alerts.html") :label "Alerts" :icon :bell
                     :count (when (pos? active-alerts) active-alerts) :count-label (str active-alerts " active")}
                    {:id :appointments :href (link "workspace-appointments.html") :label "Appointments" :icon :calendar}]}]
    admin? (conj {:heading "Administration"
                  :items [{:id :jobs :href (link "workspace-jobs.html") :label "Jobs" :icon :server}]})))

(defn- page
  "The frame of a page of the workspace: the shell with the sidebar and the header, and the content in it. `:admin?`
  signs in as the administrator, who sees every patient."
  [{:keys [title current admin?]} & content]
  (let [doctor (data/practitioner practitioner-id)
        person (if admin?
                 {:name "Sam Admin" :detail "Clinic administrator"}
                 {:name (:name doctor) :detail (:specialty doctor)})
        active (count (filter :active? (alerts-of (when-not admin? practitioner-id))))]
    (mockup/frame-document
     {:title title}
     (apply layout/workspace-shell
            {:current current
             :nav (nav admin? active)
             :search {:id "search" :name "q" :label "Search patients" :placeholder "Search patients"}
             :user {:name (:name person) :items [{:href (link "login.html") :label "Sign out" :icon :log-out}]}
             :user-detail (:detail person)}
            content))))

(defn- section-heading [id text]
  [:h2 {:id id :class "text-base font-semibold"} text])

;; ---------------------------------------------------------------------------
;; The panel

(defn- latest-cell [patient-id]
  (if-let [{:keys [kind reading]} (views/headline patient-id)]
    [:div
     [:p {:class "font-medium whitespace-nowrap"} (str (specimen/reading-text kind reading) " " (fmt/unit (:unit reading)))]
     [:p {:class "text-xs text-muted"} (str (get-in specimen/kind-info [kind :label]) ", " (fmt/when-phrase (:at reading) data/now-text))]]
    "–"))

(defn- next-visit [patient-id]
  (or (:when (first (filter #(= "booked" (:status %)) (views/appointments-of patient-id)))) "–"))

(defn- panel-row [patient-id]
  (let [{:keys [name identifier birthDate]} (data/patient patient-id)
        band (data/worst-band patient-id)
        {:keys [kind]} (views/headline patient-id)
        alerts (views/active-alerts patient-id)]
    {:patient [:a {:href (chart-link patient-id) :class "flex items-center gap-3 hover:underline"}
               (c/avatar {:name name})
               [:span [:span {:class "block font-medium"} name]
                [:span {:class "block text-xs font-normal text-muted"} (str identifier " · " (fmt/age birthDate data/now-text) " years")]]]
     :status (if band
               (c/band-badge band {:size :sm})
               (c/tone-badge {:tone :info :icon :clock :size :sm} "No readings yet"))
     :latest (latest-cell patient-id)
     :trend (when kind (specimen/sparkline patient-id kind 14))
     :alerts (if (pos? alerts)
               (c/tone-badge {:tone :critical :icon :bell-ring :size :sm} (str alerts " active"))
               [:span {:class "text-muted"} "None"])
     :next (next-visit patient-id)}))

(defn- panel-table [patient-ids]
  (c/data-table
   {:caption "Patients of your panel, the most urgent first"
    :columns [{:key :patient :label "Patient" :header? true :class "whitespace-nowrap"}
              {:key :status :label "Status"}
              {:key :latest :label "Latest reading"}
              {:key :trend :label "Last 14 days"}
              {:key :alerts :label "Alerts"}
              {:key :next :label "Next visit" :class "hidden xl:table-cell"}]
    :rows (map panel-row patient-ids)}))

(defn- readings-in-the-last-day [patient-ids]
  (reduce + (for [id patient-ids kind data/kinds]
              (count (specimen/within (data/readings id kind) 1)))))

(defn panel
  "The panel of a practitioner: the figures that need a look, a filter, and the patients with the most urgent first."
  [_context]
  (let [ids (views/by-urgency (map :id (data/panel-of practitioner-id)))
        with-alerts (count (filter #(pos? (views/active-alerts %)) ids))
        away (count (filter #(pos? (views/severity %)) ids))
        acknowledged (count (remove :active? (alerts-of practitioner-id)))
        requests (count (filter #(and (= "proposed" (:status %)) (= practitioner-id (get-in % [:patient :practitioner])))
                                (views/appointment-rows)))]
    (page {:title "Panel" :current :panel}
          [:div {:class "space-y-6"}
           (c/page-header {:title "Your panel"
                           :description (str "The " (count ids) " patients you look after, the most urgent first.")})
           [:div {:class "grid gap-4 sm:grid-cols-2 xl:grid-cols-4"}
            (c/stat {:label "Patients" :value (str (count ids)) :icon :users})
            (c/stat {:label "Active alerts" :value (str with-alerts) :icon :bell-ring :tone (when (pos? with-alerts) :critical)
                     :hint (str acknowledged " acknowledged today")})
            (c/stat {:label "To confirm" :value (str requests) :icon :calendar-clock :hint "requests for a visit"})
            (c/stat {:label "Readings" :value (str (readings-in-the-last-day ids)) :icon :activity :hint "in the last 24 hours"})]
           (c/pill-nav {:label "Filter the panel"
                        :items [{:href "#" :label "All" :count (count ids) :current? true}
                                {:href "#" :label "With active alerts" :count with-alerts}
                                {:href "#" :label "Away from normal" :count away}
                                {:href "#" :label "Normal" :count (- (count ids) away)}]})
           (panel-table ids)])))

;; ---------------------------------------------------------------------------
;; The chart of a patient

(def ^:private chart-options
  "How the chart of the headline measurement is drawn, by measurement."
  {:blood-pressure {:days 30 :ticks-every 5}})

(defn- conditions-row [patient-id]
  (if-let [conditions (seq (data/conditions patient-id))]
    (into [:ul {:class "flex flex-wrap gap-2" :aria-label "Conditions"}]
          (for [{:keys [display code]} conditions]
            [:li (c/badge {:variant :outline} (str display " · " code))]))
    [:p {:class "text-muted"} "No conditions recorded."]))

(defn- alert-block
  "An alert of this patient, as a message in the colour of its tone, with the way to acknowledge it."
  [{:keys [tone text since-phrase description evidence]}]
  (c/alert {:tone tone :title text
            :actions (list (c/button {:size :sm :icon :check :href (link "workspace-alerts.html")} "Acknowledge")
                           (c/button {:variant :outline :size :sm :href (link "workspace-alerts.html")} "All alerts"))}
           [:p (str "Raised " since-phrase ". " description)]
           [:p {:class "mt-1"} (str "The latest readings: " (str/join "; " evidence) ".")]))

(defn- vitals-grid [patient-id]
  (let [vitals (data/vitals patient-id)]
    (into [:div {:class "grid gap-4 md:grid-cols-3"}]
          (for [kind data/kinds :let [reading (get vitals kind)] :when reading]
            (specimen/vital-tile kind reading)))))

(defn- chart-card [patient-id kind]
  (let [{:keys [days ticks-every] :or {days 14 ticks-every 2}} (chart-options kind)
        label (get-in specimen/kind-info [kind :label])]
    (c/card {:title label :subtitle (str "Last " days " days")}
            (specimen/line-chart patient-id kind {:days days :width 600 :height 300 :ticks-every ticks-every
                                                  :id (str "chart-" (name kind) "-" patient-id)}))))

(defn- appointment-actions
  "The buttons an appointment gets from its status: what a clinician may do with it is `pulse.fhir.appointment`'s to say."
  [status]
  (let [actions (appointment/available-actions {:status status})]
    (list (when (:confirm actions) (c/button {:size :sm :icon :calendar-check :href (link "workspace-confirm.html")} "Confirm"))
          (when (:reschedule actions) (c/button {:variant :outline :size :sm :icon :calendar-clock :href "#"} "Reschedule"))
          (when (:cancel actions) (c/button {:variant :ghost :size :sm :icon :calendar-x :href "#"} "Cancel")))))

(defn- appointment-row
  "A row of a list of appointments of the workspace: the day, or a clock for a request, the patient (unless the list is
  the patient's own), what it is, its status and what a clinician may do."
  ([row] (appointment-row row {}))
  ([{:keys [status description month day reason asked patient] when-text :when} {:keys [show-patient?] :or {show-patient? true}}]
   (c/list-row
    {:leading (if day (c/date-block {:month month :day day}) (c/date-block {:icon :clock}))
     :trailing-wraps? true
     :trailing (list (views/appointment-chip status) (appointment-actions status))}
    [:p {:class "font-semibold"} description]
    [:p {:class "text-muted"}
     (when show-patient?
       (list [:a {:href (chart-link (:id patient)) :class "text-base-content underline"} (:name patient)] " · "))
     (or when-text asked)]
    (when reason [:p {:class "text-muted"} (str "Reason: " reason)]))))

(defn- side-card [title rows empty-text]
  (c/card {:title title :flush? true}
          (if (seq rows)
            (c/list-group {:aria-label title} rows)
            [:p {:class "px-5 pb-4 text-muted"} empty-text])))

(defn- chart-page [patient-id]
  (let [{:keys [name birthDate gender identifier city]} (data/patient patient-id)
        {:keys [kind]} (views/headline patient-id)]
    (page {:title (str "Chart of " name) :current :panel}
          [:div {:class "space-y-6"}
           (c/breadcrumbs {:items [{:href (link "workspace-panel.html") :label "Panel"} {:label name}]})
           (c/page-header {:title name
                           :description (str (fmt/age birthDate data/now-text) " years · " (str/capitalize gender) " · " identifier " · " city)})
           (conditions-row patient-id)
           (for [alert (filter #(and (:active? %) (= patient-id (get-in % [:patient :id]))) (views/alert-cards))]
             (alert-block alert))
           [:section {:aria-labelledby "vitals"}
            [:div {:class "mb-3"} (section-heading "vitals" "Latest readings")]
            (vitals-grid patient-id)]
           [:div {:class "grid items-start gap-6 xl:grid-cols-3"}
            [:div {:class "space-y-6 xl:col-span-2"}
             (chart-card patient-id kind)
             [:div {:class "grid items-start gap-6 md:grid-cols-2"}
              (side-card "Medication"
                         (for [{:keys [display dosage]} (data/medications patient-id)]
                           (c/list-row [:p {:class "font-medium"} (str/capitalize display)] [:p {:class "text-muted"} dosage]))
                         "None.")
              (side-card "Allergies"
                         (for [{:keys [display reaction]} (data/allergies patient-id)]
                           (c/list-row [:p {:class "font-medium"} display] [:p {:class "text-muted"} reaction]))
                         "None recorded.")]]
            ;; what is to come first, and what was cancelled last
            (side-card "Appointments"
                       (for [row (sort-by #(if (= "cancelled" (:status %)) 1 0) (views/appointments-of patient-id))]
                         (appointment-row row {:show-patient? false}))
                       "None.")]])))

(defn patient
  "The page function of the chart of `patient-id`."
  [patient-id]
  (fn [_context] (chart-page patient-id)))

;; ---------------------------------------------------------------------------
;; The inbox of alerts

(defn- active-alert [{:keys [id tone text since-phrase description evidence patient]}]
  (c/alert {:id id :tone tone :title (str text " · " (:name patient))
            :actions (list (c/button {:size :sm :icon :check :href "#"} "Acknowledge")
                           (c/button {:variant :outline :size :sm :href (chart-link (:id patient))} "Open the chart"))}
           [:p (str "Raised " since-phrase ". " description)]
           [:p {:class "mt-2 font-medium"} "The readings behind it"]
           (into [:ul {:class "list-disc pl-5"}] (for [line evidence] [:li line]))))

(defn- acknowledged-alert [{:keys [text since-phrase until-phrase patient]}]
  (c/list-row
   {:leading (c/icon-disc {:icon :check :variant :muted})
    :trailing-wraps? true
    :trailing (list (c/badge {:variant :outline} "Acknowledged")
                    (c/button {:variant :ghost :size :sm :href (chart-link (:id patient))} "Open the chart"))}
   [:p {:class "font-medium"} (str text " · " (:name patient))]
   [:p {:class "text-muted"} (str "Raised " since-phrase ", acknowledged " until-phrase)]))

(defn alerts
  "The alert inbox of a practitioner: what needs a decision, with the readings behind it and the way to acknowledge it,
  and what has been acknowledged."
  [_context]
  (let [cards (alerts-of practitioner-id)
        active (filter :active? cards)
        acknowledged (remove :active? cards)]
    (page {:title "Alerts" :current :alerts}
          [:div {:class "space-y-6"}
           (c/page-header {:title "Alerts" :description "Raised by the rules from the readings of your patients."})
           (c/pill-nav {:label "Show"
                        :items [{:href "#" :label "Active" :count (count active)}
                                {:href "#" :label "Acknowledged" :count (count acknowledged)}
                                {:href "#" :label "All" :count (count cards) :current? true}]})
           [:section {:aria-labelledby "needs"}
            [:div {:class "mb-3"} (section-heading "needs" "Needs a decision")]
            (into [:div {:class "space-y-4"}] (map active-alert active))]
           [:section {:aria-labelledby "done"}
            [:div {:class "mb-3"} (section-heading "done" "Acknowledged")]
            (c/card {:flush? true} (c/list-group {:aria-label "Acknowledged alerts"} (map acknowledged-alert acknowledged)))]])))

;; ---------------------------------------------------------------------------
;; Appointments

(defn- panel-appointments
  "The appointments of the panel of the practitioner, as rows."
  []
  (filter #(= practitioner-id (get-in % [:patient :practitioner])) (views/appointment-rows)))

(defn- appointment-section [id title rows]
  [:section {:aria-labelledby id}
   [:div {:class "mb-3"} (section-heading id title)]
   (c/card {:flush? true} (c/list-group {:aria-label title} (map appointment-row rows)))])

(defn- appointments-content [rows]
  (let [with-status (fn [status] (filter #(= status (:status %)) rows))]
    [:div {:class "space-y-6"}
     (c/page-header {:title "Appointments" :description "Requests to answer and visits to come."})
     (c/tab-nav {:label "Appointments by status"
                 :items [{:href "#" :label (str "All (" (count rows) ")") :current? true}
                         {:href "#" :label (str "Requests (" (count (with-status "proposed")) ")")}
                         {:href "#" :label (str "Booked (" (count (with-status "booked")) ")")}
                         {:href "#" :label (str "Cancelled (" (count (with-status "cancelled")) ")")}]})
     (appointment-section "requests" "Requests to answer" (with-status "proposed"))
     (appointment-section "booked" "Booked" (with-status "booked"))
     (appointment-section "cancelled" "Cancelled" (with-status "cancelled"))]))

(defn appointments
  "The appointments of a practitioner's panel by status: the requests that wait for an answer, what is booked, and what
  was cancelled, each with what the lifecycle allows."
  [_context]
  (page {:title "Appointments" :current :appointments}
        (appointments-content (panel-appointments))))

(defn- three-days-after
  "The date, as `2026-10-19`, three days after the first day of the window of a request."
  [window-start]
  (str (.plusDays (LocalDate/parse (subs window-start 0 10)) 3)))

(defn confirm
  "The appointments with the form to confirm a request: the time that is given to it."
  [_context]
  (let [rows (panel-appointments)
        {:keys [description patient asked requested-start]} (first (filter #(= "proposed" (:status %)) rows))]
    (page {:title "Confirm an appointment" :current :appointments}
          (appointments-content rows)
          (c/dialog {:id "confirm-appointment" :title "Confirm the appointment" :open? true
                     :actions (list (c/button {:variant :ghost :href (link "workspace-appointments.html")} "Not now")
                                    (c/button {:icon :calendar-check :href (link "workspace-appointments.html")} "Confirm"))}
                    [:p {:class "text-base font-medium"} description]
                    [:p {:class "text-base text-muted"} (:name patient)]
                    [:p {:class "text-base text-muted"} asked]
                    [:div {:class "mt-4 grid gap-4 sm:grid-cols-3"}
                     (c/text-field {:id "day" :name "day" :label "Day" :type "date" :value (three-days-after requested-start)})
                     (c/text-field {:id "start" :name "start" :label "From" :type "time" :value "10:00"})
                     (c/text-field {:id "end" :name "end" :label "Until" :type "time" :value "10:30"
                                    :hint "A visit lasts from 5 minutes to 4 hours."})]
                    [:p {:class "mt-3 text-muted"} (str (first (:given patient)) " sees the new time in the portal, and is reminded of it.")]))))

;; ---------------------------------------------------------------------------
;; The jobs of the scheduler

(defn- outcome-chip [outcome]
  (case outcome
    "ok" (c/tone-badge {:tone :normal :size :sm} "OK")
    "failed" (c/tone-badge {:tone :critical :size :sm} "Failed")
    "interrupted" (c/tone-badge {:tone :high :size :sm} "Interrupted")))

(defn- result-text [result]
  (str/join ", " (for [[k v] result] (str v " " (fmt/words k)))))

(defn- run-line
  "How the last run of a job went: its outcome, when it began, how long it took, and what it reported."
  [{:keys [startedAt durationMs outcome result error]}]
  [:span {:class "flex flex-wrap items-center gap-x-2 gap-y-1"}
   (outcome-chip outcome)
   [:span (str (fmt/clock-seconds startedAt) " · " (fmt/duration-text durationMs))]
   (cond result [:span {:class "text-muted"} (result-text result)]
         error [:span {:class "text-muted"} (str "Error: " error)])])

(defn- state-chip [{:keys [state runningSince]}]
  (if (= "running" state)
    (c/tone-badge {:tone :info :icon :loader-circle} (str "Running since " (fmt/clock-seconds runningSince)))
    (c/badge {:variant :outline} "Idle")))

(defn- job-row [{:keys [id description schedule nextRun lastRun counts] :as job}]
  (c/list-row
   {:trailing-wraps? true :trailing (state-chip job)}
   [:p {:class "font-semibold"} id]
   [:p {:class "mb-3 text-muted"} description]
   (c/facts [["Schedule" (fmt/schedule-text schedule)]
             ["Last run" (if lastRun (run-line lastRun) "Has not run yet")]
             ["Next run" (fmt/clock-seconds nextRun)]
             ["Runs" (str (:ok counts) " ok, " (:failed counts) " failed, " (:interrupted counts) " interrupted, "
                          (:skipped counts) " skipped")]])))

(defn jobs
  "The status of the scheduler's jobs, for the administrator: what each one does, when it runs, how the last run went."
  [_context]
  (let [{:keys [items total]} data/jobs
        running (count (filter #(= "running" (:state %)) items))
        failed (filter #(= "failed" (get-in % [:lastRun :outcome])) items)]
    (page {:title "Jobs" :current :jobs :admin? true}
          [:div {:class "space-y-6"}
           (c/page-header {:title "Jobs" :description "What the scheduler does on its own, and how the last run of each went."})
           [:div {:class "grid gap-4 sm:grid-cols-3"}
            (c/stat {:label "Jobs" :value (str total) :icon :timer})
            (c/stat {:label "Running now" :value (str running) :icon :loader-circle})
            (c/stat {:label "Last run failed" :value (str (count failed)) :icon :circle-alert :tone (when (seq failed) :high)})]
           (for [{:keys [id lastRun nextRun]} failed]
             (c/alert {:tone :high :title (str "The last run of " id " failed")}
                      (str (:error lastRun) " after " (fmt/duration-text (:durationMs lastRun)) ". The next run is at "
                           (fmt/clock-seconds nextRun) ".")))
           (c/card {:flush? true}
                   (c/list-group {:aria-label "Jobs of the scheduler"} (map job-row items)))])))

;; ---------------------------------------------------------------------------
;; What goes wrong

(defn states
  "What the workspace says when there is nothing to show or something is wrong: an empty panel and inbox, a page that is
  loading, a chart that could not be opened, and a section of a chart that is not available."
  [_context]
  (page {:title "Empty states and errors" :current :panel}
        [:div {:class "space-y-6"}
         (c/page-header {:title "Empty states and errors" :description "What the workspace says when there is nothing to show, or when something is wrong."})
         [:div {:class "grid gap-4 xl:grid-cols-2"}
          (c/card {:title "A panel with no patients"}
                  (c/empty-state {:icon :users :title "No patients in your panel yet"}
                                 "Patients appear here when you are their general practitioner."))
          (c/card {:title "An inbox with no alerts"}
                  (c/empty-state {:icon :bell-off :title "No alerts"
                                  :actions (c/button {:variant :outline :size :sm :href "#"} "See acknowledged alerts")}
                                 "Nothing needs a decision right now. New alerts appear here as the rules raise them."))
          (c/card {:title "While the panel loads"}
                  [:div {:class "flex items-center gap-3"} (c/spinner {:label "Loading the panel"}) [:span {:class "text-muted"} "Loading the panel"]]
                  [:div {:class "mt-5 space-y-3"}
                   (c/skeleton {:class "h-10 w-full"}) (c/skeleton {:class "h-10 w-full"}) (c/skeleton {:class "h-10 w-3/4"})])
          (c/card {:title "A chart that cannot be opened"}
                  (c/empty-state {:icon :lock :title "You cannot open this chart"
                                  :actions (c/button {:variant :outline :href (link "workspace-panel.html")} "Back to the panel")}
                                 "This patient is not in your panel."))
          (c/card {:title "A patient that does not exist"}
                  (c/empty-state {:icon :search :title "There is no patient with that number"
                                  :actions (c/button {:variant :outline :href (link "workspace-panel.html")} "Back to the panel")}
                                 "Check the number and try again."))
          (c/card {:title "A list that could not be loaded"}
                  (c/alert {:tone :high :title "The alerts could not be loaded"
                            :actions (c/button {:variant :outline :size :sm :icon :refresh-cw :href "#"} "Try again")}
                           "Everything else is up to date. The problem is on our side."))]
         [:section {:aria-labelledby "partial"}
          [:div {:class "mb-3"} (section-heading "partial" "A chart with a section that is not available")]
          [:div {:class "space-y-4"}
           (c/alert {:tone :high :title "Medication is not available right now"
                     :actions (c/button {:variant :outline :size :sm :icon :refresh-cw :href "#"} "Try again")}
                    "The other sections are up to date. Nothing is hidden: an empty section would say it is empty.")
           (vitals-grid "pt-ana")]]]))
