(ns pulse.design.workspace
  "The mock-ups of the clinical workspace (ADR-0026), the interface of `spa`: what a practitioner sees of their panel,
  and what the administrator sees besides. The practitioner is Dr. Elena Rivera, whose panel has Ana (a very high blood
  pressure), Sofía (a low oxygen saturation), Liam and Omar; the administrator is Sam Admin. Each page is a function
  from the context of the build to the document of its frame."
  (:require [pulse.design.data :as data]
            [pulse.design.mockup :as mockup]
            [pulse.design.specimen :as specimen]
            [pulse.design.views :as views]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.pages.alerts :as alerts-page]
            [pulse.spa.pages.appointments :as appointments-page]
            [pulse.spa.pages.jobs :as jobs-page]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]
            [pulse.view.alerts :as alerts]
            [pulse.view.format :as fmt]))

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

(def ^:private dialog-files
  "The mock-up of the dialog of each command that answers an appointment."
  {:confirm "workspace-confirm.html"
   :reschedule "workspace-reschedule.html"
   :cancel "workspace-cancel-appointment.html"})

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
  "Where the buttons and the pills of the chart lead in the mock-ups: the panel, the inbox of alerts (where the button that acknowledges an
  alert leads too, since the mock-up does not make it work), the dialog of each command on an appointment and, for the pills, nowhere."
  []
  {:panel (link "workspace-panel.html")
   :alerts (link "workspace-alerts.html")
   :acknowledge (constantly (link "workspace-alerts.html"))
   :dialog (fn [_id action] (link (dialog-files action)))
   :chart (constantly "#")})

(defn patient
  "The page function of the chart of `patient-id`. The page is the one the workspace serves (`pulse.spa.pages.patient`), made from
  the cohort, with the buttons to confirm, reschedule or cancel an appointment that the lifecycle gives a clinician; the box to
  search the patients is not on it: it would have nothing to search here."
  [patient-id]
  (fn [_context]
    (page {:title (str "Chart of " (:name (data/patient patient-id))) :current :panel :search? false}
          (patient-page/content (patient-state patient-id) {:admin? false :links (chart-links)}))))

;; ---------------------------------------------------------------------------
;; The inbox of alerts

(defn inbox-state
  "What the inbox of the workspace is a function of when it has loaded: the state `pulse.spa.inbox` keeps, made from what the api answers
  (the alerts that need a decision and those that were acknowledged, the patients, and the latest readings behind each active alert), from
  the cohort at the moment of the mock-ups, for the practitioner `practitioner-id` (or every patient, for nil)."
  [practitioner-id]
  (let [active (data/alert-page practitioner-id {:limit inbox/list-limit})]
    {:now data/now-text
     :active (loaded active)
     :acknowledged (loaded (data/alert-page practitioner-id {:status "inactive" :limit inbox/list-limit}))
     :patients (loaded (data/patient-list practitioner-id {:limit panel/list-limit}))
     :evidence (into {}
                     (for [alert (:items active)
                           :let [kind (alerts/measurement (:text alert))
                                 id (inbox/patient-id (:patient alert))]
                           :when (and kind id)]
                       [[id kind] (loaded (data/series id kind alerts/evidence-count))]))
     :latest {}
     :adopt? false
     :queue []
     :inflight #{}}))

(def inbox-links
  "Where the buttons of the inbox lead in the mock-ups, which have every page: the chart of each patient there is one of, and the pills
  and the button that acknowledges an alert, which the mock-up does not make work."
  {:panel (link "workspace-panel.html")
   :alerts (link "workspace-alerts.html")
   :filter (constantly "#")
   :acknowledge (constantly "#")
   :patient chart-link})

(defn alerts
  "The alert inbox of a practitioner: what needs a decision, with the readings behind it and the way to acknowledge it, and what has been
  acknowledged. The page is the one the workspace serves (`pulse.spa.pages.alerts`), made from the cohort. The box to search the patients
  is not on it: it would have nothing to search here."
  [_context]
  (page {:title "Alerts" :current :alerts :search? false}
        (alerts-page/content (inbox-state practitioner-id) {:show :all :admin? false :links inbox-links})))

;; ---------------------------------------------------------------------------
;; Appointments

(defn appointments-state
  "What the list of appointments of the workspace is a function of when it has loaded: the state `pulse.spa.appointments` keeps, made from
  what the api answers (the requests, the booked and the cancelled appointments, and the patients), from the cohort at the moment of the
  mock-ups, for the practitioner `practitioner-id` (or every patient, for nil)."
  [practitioner-id]
  (let [mine (str "Practitioner/" practitioner-id)
        visible (filter #(or (nil? practitioner-id) (= mine (:practitioner %)))
                        (concat (data/appointments) data/other-appointments))
        listed (fn [status order] (loaded (data/appointment-page visible {:status status :sort order :limit appointments/list-limit})))]
    {:now data/now-text
     :requests (listed "proposed" "start")
     :booked (listed "booked" "start")
     :cancelled (listed "cancelled" "-start")
     :patients (loaded (data/patient-list practitioner-id {:limit panel/list-limit}))
     :latest {}
     :adopt? false
     :queue []
     :inflight #{}
     :dialog nil
     :said nil}))

(def appointment-links
  "Where the buttons of the appointments lead in the mock-ups, which have every page: the dialog of each command there is one of, the
  chart of each patient there is one of, and the tabs, which the mock-up does not make work."
  {:panel (link "workspace-panel.html")
   :alerts (link "workspace-alerts.html")
   :appointments (link "workspace-appointments.html")
   :filter (constantly "#")
   :patient chart-link
   :dialog (fn [_id action] (link (dialog-files action)))
   :close (link "workspace-appointments.html")})

(defn appointments
  "The appointments of a practitioner's panel by status: the requests that wait for an answer, what is booked, and what was cancelled, each
  with what the lifecycle allows. The page is the one the workspace serves (`pulse.spa.pages.appointments`), made from the cohort. The box
  to search the patients is not on it: it would have nothing to search here."
  [_context]
  (page {:title "Appointments" :current :appointments :search? false}
        (appointments-page/content (appointments-state practitioner-id) {:show :all :admin? false :links appointment-links})))

(defn- appointment-dialog
  "The page function of the dialog that carries out `action` on the appointment `id`, over the list of the practitioner."
  [title action id]
  (fn [_context]
    (page {:title title :current :appointments :search? false}
          (appointments-page/content (assoc (appointments-state practitioner-id) :dialog (command/opened action id))
                                     {:show :all :admin? false :links appointment-links}))))

(def confirm
  "The list of appointments with the form to confirm a request: the day and the time that are given to it."
  (appointment-dialog "Confirm an appointment" :confirm "appt-sofia-1"))

(def reschedule
  "The list of appointments with the form to move a booked appointment: the day and the time it has, to change."
  (appointment-dialog "Reschedule an appointment" :reschedule "appt-ana-1"))

(def cancel-appointment
  "The list of appointments with the form to cancel a booked appointment: why, if the clinician wants to say."
  (appointment-dialog "Cancel an appointment" :cancel "appt-liam-1"))

;; ---------------------------------------------------------------------------
;; The jobs of the scheduler

(defn jobs-state
  "What the page of the jobs is a function of when it has loaded: the state `pulse.spa.jobs` keeps, made from what the api answers, from the
  jobs of `data/jobs`, fifteen seconds after the moment of the other mock-ups."
  []
  {:now "2026-10-13T12:00:15Z" :list (loaded data/jobs)})

(defn jobs
  "The status of the scheduler's jobs, for the administrator: what each one does, when it runs, how the last run went. The page is the one
  the workspace serves (`pulse.spa.pages.jobs`), made from the jobs of the demo. There is no button to start a job: the api only shows
  the status. The box to search the patients is not on it."
  [_context]
  (page {:title "Jobs" :current :jobs :admin? true :search? false}
        (jobs-page/content (jobs-state) {:links {:panel (link "workspace-panel.html")}})))

(defn- vitals-grid [patient-id]
  (let [vitals (data/vitals patient-id)]
    (into [:div {:class "grid gap-4 md:grid-cols-3"}]
          (for [kind data/kinds :let [reading (get vitals kind)] :when reading]
            (specimen/vital-tile kind reading)))))

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
