(ns pulse.design.workspace
  "The mock-ups of the clinical workspace (ADR-0026), the interface of `spa`: what a practitioner sees of their panel,
  and what the administrator sees besides. The practitioner is Dr. Elena Rivera, whose panel has Ana (a very high blood
  pressure), Sofía (a low oxygen saturation), Liam and Omar; the administrator is Sam Admin. Each page is a function
  from the context of the build to the document of its frame."
  (:require [clojure.walk :as walk]
            [pulse.design.data :as data]
            [pulse.design.mockup :as mockup]
            [pulse.design.views :as views]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.pages.alerts :as alerts-page]
            [pulse.spa.pages.appointments :as appointments-page]
            [pulse.spa.pages.jobs :as jobs-page]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.pages.start :as start]
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
                     :count (when (pos? active-alerts) active-alerts) :count-label "active"}
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

;; ---------------------------------------------------------------------------
;; What goes wrong

(defn- as-mockup
  "A piece of a page of the workspace, made ready to stand in a page of the mock-ups. The pages put the heading of a state at the top of
  the page (an `h1`, since a state that is the whole page has nothing else to say), and here each piece is one of many on a page that has
  its own `h1`, so it is an `h3`; and what the workspace gives a button to do (a function) is nothing in a file, where every button of a
  mock-up does nothing by itself."
  [piece]
  (walk/postwalk (fn [node]
                   (cond
                     (= :h1 node) :h3
                     (and (map? node) (some fn? (vals node))) (into {} (remove (comp fn? val)) node)
                     :else node))
                 piece))

(defn- specimen
  "A piece of the workspace in a state, with what the state is called above it."
  [caption piece]
  [:div {:class "min-w-0 space-y-2" :data-specimen "true"}
   [:p {:class "text-sm font-medium text-muted"} caption]
   (as-mockup piece)])

(defn- group
  "A group of pieces of the page of the states: a heading and the pieces in a grid."
  [id title description & specimens]
  [:section {:aria-labelledby id}
   [:div {:class "mb-3"}
    (section-heading id title)
    [:p {:class "text-muted"} description]]
   (into [:div {:class "grid items-start gap-4 xl:grid-cols-2"}] specimens)])

(def ^:private states-links
  "Where the buttons of the pieces lead: the panel, the inbox, the patient portal and the sign-in, which have a mock-up each."
  {:panel (link "workspace-panel.html")
   :alerts (link "workspace-alerts.html")
   :portal (link "portal-home.html")
   :sign-in (link "login.html")
   :filter (constantly "#")})

(defn- the-chart-with-a-section-lost
  "The chart of Ana García as the workspace serves it when the api could not give her medication: the section is named, and the others are
  there."
  []
  (let [lost (-> (patient-state "pt-ana")
                 (update-in [:summary :data] (fn [summary] (-> summary (assoc :medications nil) (update :unavailable (fnil conj []) "medications")))))]
    (patient-page/content lost {:admin? false :links (chart-links)})))

(defn states
  "What the workspace says when there is nothing to show or something is wrong, as the workspace says it: before it is open, for a page
  that is not there or not the person's, with nothing in a list, while a page loads, and when some of it could not be loaded. Every piece
  is the one the workspace serves (`pulse.spa.pages.*`), in the state it is named for, and none is written for this page; the buttons do
  nothing here, as in every mock-up. The pages of Keycloak have their own mock-up."
  [_context]
  (let [retry {:retry (fn [])}
        sign-out {:sign-out (fn [])}
        list-notice {:retry (fn [])}]
    (page {:title "Empty states and errors" :current :panel :search? false}
          [:div {:class "space-y-8"}
           (c/page-header {:title "Empty states and errors"
                           :description "What the workspace says when there is nothing to show, or when something is wrong. Each piece is the one the workspace serves."})

           (group "before" "Before the workspace is open"
                  "The person has not reached a page of the workspace yet. These are the cards of the sign-in's frame."
                  (specimen "Waiting for the identity provider" (start/starting-card {}))
                  (specimen "A wait that does not end" (start/starting-card {:slow? true :handlers retry}))
                  (specimen "A sign-in that could not be made" (start/failed-card retry))
                  (specimen "A person the workspace is not for"
                            (start/refused-card {:links {:portal (:portal states-links)} :handlers sign-out}))
                  (specimen "A sign-in the api did not accept" (start/unauthorized-card {:links {:sign-in (:sign-in states-links)}}))
                  (specimen "Someone who has signed out" (start/signed-out-card {:links {:sign-in (:sign-in states-links)}}))
                  (specimen "A page the workspace could not draw"
                            (start/crashed-card {:handlers {:reload (fn []) :panel (fn []) :sign-out (fn [])}})))

           (group "not-there" "A page that is not there, or not the person's"
                  "Inside the workspace: the person is told, with the way back, and is not shown a page of refusals."
                  (specimen "An address that is no page" (start/not-found-card {:links states-links}))
                  (specimen "A page for another role" (start/not-allowed-card {:links states-links}))
                  (specimen "A chart that cannot be opened" (patient-page/gone false states-links))
                  (specimen "A chart that cannot be opened, for the administrator" (patient-page/gone true states-links))
                  (specimen "The jobs, for a person who is not the administrator" (jobs-page/forbidden states-links)))

           (group "nothing" "Nothing to show"
                  "The page loaded, and there is nothing in it. It says so and says what to do."
                  (specimen "A panel with no patients" (panel-page/no-patients false))
                  (specimen "A panel for the administrator, with no patients" (panel-page/no-patients true))
                  (specimen "A filter or a search that finds nobody" (panel-page/no-match states-links))
                  (specimen "An inbox with no alerts"
                            (alerts-page/nothing-to-decide (c/button {:variant :outline :size :sm :href "#"} "See acknowledged alerts")))
                  (specimen "A patient with no readings" (patient-page/no-readings))
                  (specimen "No jobs"
                            (jobs-page/content {:now "2026-10-13T12:00:15Z" :list (loaded {:total 0 :items []})}
                                               {:links states-links})))

           (group "loading" "While a page loads"
                  "Grey shapes where the content will be, and a line for a screen reader. Nothing is shown as empty before it is known."
                  (specimen "The panel" (panel-page/waiting-table))
                  (specimen "The inbox" (alerts-page/content (inbox/start data/now-text) {:show :all :admin? false :links states-links}))
                  (specimen "The jobs" (jobs-page/content (jobs/start data/now-text) {:links states-links})))

           (group "lost" "Some of it could not be loaded"
                  "A message in the place of what is missing, with what else is up to date and a way to try again. A list that failed is never shown as an empty one."
                  (specimen "The panel, when the list of patients failed" (panel-page/notice {:id :list-failed} false list-notice))
                  (specimen "The panel, when some patients failed" (panel-page/notice {:id :rows-failed :count 2} false list-notice))
                  (specimen "The panel, when some figures failed" (panel-page/notice {:id :figures-failed} false list-notice))
                  (specimen "The inbox, when it could not look again" (alerts-page/notice {:id :check-failed} list-notice))
                  (specimen "The inbox, when the names of the patients failed" (alerts-page/notice {:id :names-failed} list-notice))
                  (specimen "The inbox, when the alerts have changed"
                            (alerts-page/notice {:id :changed} {:show-changes (fn [])}))
                  (specimen "The appointments, when the list could not be updated" (appointments-page/list-notice {:id :check-failed} list-notice))
                  (specimen "The jobs, when the scheduler could not be asked"
                            (jobs-page/content {:now "2026-10-13T12:00:15Z" :list {:state :failed :kind :server}}
                                               {:links states-links :handlers list-notice})))

           [:section {:aria-labelledby "partial"}
            [:div {:class "mb-3"}
             (section-heading "partial" "A chart with a section that is not available")
             [:p {:class "text-muted"} "The medication could not be loaded. It is named, the other sections are there, and nothing is shown as empty."]]
            (as-mockup (the-chart-with-a-section-lost))]])))
