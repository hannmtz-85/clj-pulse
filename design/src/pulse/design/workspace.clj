(ns pulse.design.workspace
  "The mock-ups of the clinical workspace (ADR-0026), the interface of `spa`: what a practitioner sees of their panel,
  and what the administrator sees besides. The practitioner is Dr. Elena Rivera, whose panel has Ana (a very high blood
  pressure), Sofía (a low oxygen saturation), Liam and Omar; the administrator is Sam Admin. Each page is a function
  from the context of the build to the document of its frame."
  (:require [clojure.string :as str]
            [pulse.design.data :as data]
            [pulse.design.format :as jobs-fmt]
            [pulse.design.mockup :as mockup]
            [pulse.design.specimen :as specimen]
            [pulse.design.views :as views]
            [pulse.fhir.appointment :as appointment]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]
            [pulse.view.alerts :as alerts]
            [pulse.view.format :as fmt]
            [pulse.view.views :as web-views])
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
  signs in as the administrator, who sees every patient; `:search? false` leaves out the box to search the patients, for a page it
  has nothing to search on."
  [{:keys [title current admin? search?] :or {search? true}} & content]
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
             :search (when search? {:id "search" :name "q" :label "Search patients" :placeholder "Search patients"})
             :user {:name (:name person) :items [{:href (link "login.html") :label "Sign out" :icon :log-out}]}
             :user-detail (:detail person)}
            content))))

(defn- section-heading [id text]
  [:h2 {:id id :class "text-base font-semibold"} text])

;; ---------------------------------------------------------------------------
;; The panel

(defn- requests-page
  "What `GET /api/appointments?status=proposed` answers the practitioner (or the administrator, for nil): the requests for a visit that
  they take part in."
  [practitioner-id]
  (let [mine (str "Practitioner/" practitioner-id)]
    (data/appointment-page (filter #(or (nil? practitioner-id) (= mine (:practitioner %)))
                                   (concat (data/appointments) data/other-appointments))
                           {:status "proposed" :limit 1})))

(defn- loaded [body]
  {:state :loaded :data body})

(defn panel-state
  "What the panel of the workspace is a function of when it has loaded: the state `pulse.spa.panel` keeps, made from what the api
  answers, from the cohort at the moment of the mock-ups, for the practitioner `practitioner-id` (or every patient, for nil)."
  [practitioner-id]
  (let [patients (data/patient-list practitioner-id {:limit panel/list-limit})
        from (fmt/instant-text (- (fmt/millis data/now-text) (* panel/series-days 86400000)))]
    (panel/with-order
      {:now data/now-text
       :list (loaded patients)
       :rows (into {}
                   (for [{:keys [id]} (:items patients)
                         :let [summary (data/summary id)
                               head (panel/headline summary)]]
                     [id (cond-> {:summary (loaded summary)}
                           head (assoc :series (loaded (data/series id (:kind head) {:from from :limit panel/series-limit}))))]))
       :inbox {:active (loaded (data/alert-page practitioner-id {:limit 1}))
               :acknowledged (loaded (data/alert-page practitioner-id {:status "inactive" :limit 100}))
               :requests (loaded (requests-page practitioner-id))}
       :queue []
       :inflight #{}})))

(def panel-links
  "Where the buttons and names of the panel lead in the mock-ups, which have every page: the chart of each patient there is one of, and
  the pills, which the mock-up does not make work."
  {:panel (link "workspace-panel.html")
   :filter (constantly "#")
   :patient chart-link})

(defn panel
  "The panel of a practitioner: the figures that need a look, a filter, and the patients with the most urgent first. The page is the
  one the workspace serves (`pulse.spa.pages.panel`), made from the cohort."
  [_context]
  (page {:title "Panel" :current :panel}
        (panel-page/content (panel-state practitioner-id) {:show :all :q ""} {:admin? false :links panel-links})))

;; ---------------------------------------------------------------------------
;; Pieces that other pages of the mock-ups still use

(defn- vitals-grid [patient-id]
  (let [vitals (data/vitals patient-id)]
    (into [:div {:class "grid gap-4 md:grid-cols-3"}]
          (for [kind data/kinds :let [reading (get vitals kind)] :when reading]
            (specimen/vital-tile kind reading)))))

(defn- appointment-actions
  "The buttons an appointment gets from its status: what a clinician may do with it is `pulse.fhir.appointment`'s to say."
  [status]
  (let [actions (appointment/available-actions {:status status} :clinician data/now-text)]
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
     :trailing (list (web-views/appointment-chip status) (appointment-actions status))}
    [:p {:class "font-semibold"} description]
    [:p {:class "text-muted"}
     (when show-patient?
       (list [:a {:href (chart-link (:id patient)) :class "text-base-content underline"} (:name patient)] " · "))
     (or when-text asked)]
    (when reason [:p {:class "text-muted"} (str "Reason: " reason)]))))

;; ---------------------------------------------------------------------------
;; The chart of a patient

(defn patient-state
  "What the chart of a patient of the workspace is a function of when it has loaded: the state `pulse.spa.patient` keeps, made from what
  the api answers (the Patient, the summary, the series of the headline reading and the readings behind each alert), from the cohort at
  the moment of the mock-ups."
  [patient-id]
  (let [summary (data/summary patient-id)
        kind (patient/default-kind summary)
        days patient/default-days
        from (fmt/instant-text (- (fmt/millis data/now-text) (* days 86400000)))]
    {:now data/now-text
     :id patient-id
     :asked nil
     :days days
     :patient (loaded (data/patient-resource patient-id))
     :summary (loaded summary)
     :series (when kind (assoc (loaded (data/series patient-id kind {:from from :limit patient/series-limit})) :measurement kind :days days))
     :evidence (into {} (for [k (alerts/kinds-to-read (:alerts summary))]
                          [k (loaded (data/series patient-id k alerts/evidence-count))]))
     :queue []
     :inflight #{}}))

(defn- chart-links
  "Where the buttons and the pills of the chart lead in the mock-ups: the panel, and, for the pills, nowhere (the mock-up does not make
  them work)."
  []
  {:panel (link "workspace-panel.html")
   :chart (constantly "#")})

(defn patient
  "The page function of the chart of `patient-id`. The page is the one the workspace serves (`pulse.spa.pages.patient`), made from
  the cohort; the search box and the buttons of the next steps (to acknowledge an alert, to confirm, move or cancel an appointment)
  are not on it until the workspace has them."
  [patient-id]
  (fn [_context]
    (page {:title (str "Chart of " (:name (data/patient patient-id))) :current :panel :search? false}
          (patient-page/content (patient-state patient-id) {:admin? false :links (chart-links)}))))

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
  (str/join ", " (for [[k v] result] (str v " " (jobs-fmt/words k)))))

(defn- run-line
  "How the last run of a job went: its outcome, when it began, how long it took, and what it reported."
  [{:keys [startedAt durationMs outcome result error]}]
  [:span {:class "flex flex-wrap items-center gap-x-2 gap-y-1"}
   (outcome-chip outcome)
   [:span (str (fmt/clock-seconds startedAt) " · " (jobs-fmt/duration-text durationMs))]
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
   (c/facts [["Schedule" (jobs-fmt/schedule-text schedule)]
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
                      (str (:error lastRun) " after " (jobs-fmt/duration-text (:durationMs lastRun)) ". The next run is at "
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
