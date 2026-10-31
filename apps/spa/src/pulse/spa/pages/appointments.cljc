(ns pulse.spa.pages.appointments
  "The appointments of the clinical workspace (ADR-0029): the requests that wait for an answer, the appointments that are booked and the
  ones that were cancelled, each with the buttons that the lifecycle gives a clinician, and the dialog over the list that carries one of
  them out. Pure hiccup of the components of `libs/ui`, with no Reagent, so that the workspace and the mock-up of the review make the same
  page: `content` is what both show, and `page` is the workspace around it.

  What the page is a function of is the state of the list as `pulse.spa.appointments` keeps it, in the shapes of the api, with each part
  `{:state :loading|:loaded|:failed ...}`. **What could not be loaded is said, in its place, and is never shown as empty**: a list the api
  could not give is a message with a way to try again, and not \"Nothing is booked\".

  A control that has nothing to lead to is not there: a tab is there only when `:filter` is among the `links` (a function from `:all`,
  `:requests`, `:booked` or `:cancelled` to an address), the name of a patient is a link only when `:patient` is (a function from the id),
  the buttons of a row only when `:dialog` is (a function from the id of the appointment and the command; see
  `pulse.spa.pages.parts/appointment-buttons`), the way out of the dialog only when `:close` is (the address of the list), and the button
  that tries again only when `:retry` is among the `handlers`. The form of the dialog takes `:edit` (a function from the field and the
  event), `:submit` and `:key-down` from the `handlers`: the mock-up, which has none, shows the form as it is."
  (:require [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.pages.parts :as parts]
            [pulse.spa.pages.shell :as shell]
            [pulse.spa.person :as person]
            [pulse.ui.component :as c]
            [pulse.view.views :as views]))

;; ---------------------------------------------------------------------------
;; What the person is told besides the list

(defn- list-notice [{:keys [id]} handlers]
  (case id
    :check-failed (parts/not-loaded "We could not update the list" "What you see may be out of date." handlers)
    :names-failed (parts/not-loaded "The names of the patients could not be loaded" "The appointments are up to date." handlers)))

;; ---------------------------------------------------------------------------
;; A row

(defn- subline
  "The patient, as a link to their chart when the page can lead there, and when the appointment is or was asked to be."
  [{:keys [name patient-id asked] when-text :when} links]
  (let [patient (when name
                  (if-let [link (and patient-id (:patient links))]
                    ;; a link is a target of 24 px at least, though a line of this type is 20
                    [:a {:href (link patient-id) :class "inline-flex min-h-6 items-center text-base-content underline"} (parts/wrapped name)]
                    (parts/wrapped name)))
        time-text (or when-text asked)]
    (when (or patient time-text)
      [:p {:class "text-muted wrap-anywhere"}
       patient
       (when (and patient time-text) " · ")
       time-text])))

(defn appointment-row
  "A row of a list of appointments: the day, or a clock for a request, what it is for, whose it is (unless the list is a patient's own,
  `:name` being nil) and when, why it was cancelled if it was, its status and the buttons for what a clinician may do with it. `row` is
  as `pulse.spa.appointments` makes it; `options` have the `:links`."
  [{:keys [id status description month day reason] :as row} {:keys [links] :as options}]
  (c/list-row
   {:id (command/dom-id "appointment" id)
    :leading (if day (c/date-block {:month month :day day}) (c/date-block {:icon :clock}))
    :trailing-wraps? true
    :trailing (parts/actions (views/appointment-chip status) (parts/appointment-buttons row options))}
   [:p {:class "font-semibold wrap-anywhere"} (or (views/text-of description) "Appointment")]
   (subline row links)
   (when-let [r (views/text-of reason)] [:p {:class "text-muted wrap-anywhere"} (str "Reason: " r)])))

;; ---------------------------------------------------------------------------
;; The lists

(defn- section-heading [id text]
  [:h2 {:id id :class "text-base font-semibold"} text])

(defn- cut-note
  "The sentence that says a list is the first or the latest of more, or nil when it is all of them."
  [{:keys [items total]} which noun]
  (when (and (integer? total) (< (count items) total))
    [:p {:class "mt-3 text-sm text-muted"} (str "Showing the " which " " (count items) " of " total " " noun ".")]))

(defn- list-section
  "One list: a heading, and the rows, or what is said instead when they are on their way, could not be loaded or there are none."
  [{:keys [id title lost empty-text which noun]} {:keys [state items] :as section} options]
  [:section {:aria-labelledby id}
   [:div {:class "mb-3"} (section-heading id title)]
   (case state
     :loading (c/card {:flush? true}
                      (c/list-group {:aria-label title} (repeat 2 [:li {:class "px-5 py-4"} (c/skeleton {:class "h-10 w-full"})])))
     :lost (parts/not-loaded lost "Everything else is up to date." (:handlers options))
     (c/card {:flush? true}
             (if (seq items)
               ;; each row is keyed by its appointment: when the list changes, React must not hand the button that has the focus to another
               (c/list-group {:aria-label title} (map #(with-meta (appointment-row % options) {:key (:id %)}) items))
               [:p {:class "px-5 py-4 text-muted"} empty-text])))
   (cut-note section which noun)])

(def ^:private lists
  [[:requests {:id "requests" :title "Requests to answer" :lost "The requests could not be loaded"
               :empty-text "No request is waiting for an answer." :which "first" :noun "requests"}]
   [:booked {:id "booked" :title "Booked" :lost "The booked appointments could not be loaded"
             :empty-text "Nothing is booked." :which "first" :noun "booked appointments"}]
   [:cancelled {:id "cancelled" :title "Cancelled" :lost "The cancelled appointments could not be loaded"
                :empty-text "Nothing has been cancelled." :which "latest" :noun "cancelled appointments"}]])

;; ---------------------------------------------------------------------------
;; The dialog

(defn- close-button
  "The way out of the dialog, a link to the list, or nil when the page has none."
  [text {:keys [links]}]
  (when-let [href (:close links)]
    (c/button {:variant :ghost :href href :id "appointment-close"} text)))

(defn- summary-lines [{:keys [description name asked reason] when-text :when}]
  (list [:p {:class "text-base font-medium wrap-anywhere"} (or (views/text-of description) "Appointment")]
        (when name [:p {:class "text-base text-muted wrap-anywhere"} name])
        (when-let [t (or when-text asked)] [:p {:class "text-base text-muted"} t])
        (when-let [r (views/text-of reason)] [:p {:class "text-base text-muted wrap-anywhere"} (str "Reason: " r)])))

(defn- notice-alert
  "What went wrong that is not about one field, in the dialog: it is inside because the dialog is over the page, and a message at the top of
  the page would be behind it."
  [{:keys [title text]}]
  (c/alert {:tone :high :title title :role "alert" :class "mb-4"} (parts/wrapped text)))

(defn- field-attributes
  "The attributes of a field of the form: what it holds, the words beside it and what to do when the person types in it."
  [field {:keys [fields errors]} {:keys [handlers]}]
  (let [edit (:edit handlers)]
    (cond-> {:id (name field) :name (name field) :value (get fields field)}
      (contains? errors field) (assoc :error (get errors field))
      edit (assoc :on-change (fn [event] (edit field event))))))

(defn- form-fields [action view options]
  (case action
    (:confirm :reschedule)
    [:div {:class "mt-4 grid gap-4 sm:grid-cols-3"}
     (c/text-field (assoc (field-attributes :day view options) :type "date" :label "Day"))
     (c/text-field (assoc (field-attributes :from view options) :type "time" :label "From"))
     (c/text-field (assoc (field-attributes :until view options) :type "time" :label "Until" :hint (:slot-hint view)))]

    :cancel
    [:div {:class "mt-4"}
     (c/text-field (assoc (field-attributes :why view options)
                          :type "text" :label "Why?" :optional? true :autocomplete "off"
                          :hint (str "Up to " (:reason-length view) " characters.")))]))

(defn- who-sees-it [action given]
  (let [who (or given "The patient")]
    (case action
      (:confirm :reschedule) (str who " sees the new time in the portal.")
      :cancel (str who " sees in the portal that it was cancelled, and why if you say."))))

(defn- open-dialog
  "The dialog that carries out a command on an appointment: what it is about, what went wrong the last time, the fields, and the two
  ways out."
  [{:keys [action title icon submit-label busy? notice given] summary-row :summary :as view} {:keys [handlers] :as options}]
  (let [cancelling? (= :cancel action)]
    (c/dialog (cond-> {:id "appointment-dialog"
                       :title title
                       :open? true
                       :actions (list (close-button (if cancelling? "Keep it" "Not now") options)
                                      (c/button (cond-> {:variant (if cancelling? :danger :primary)
                                                         :icon icon
                                                         :id "appointment-submit"
                                                         :type "submit"
                                                         :form "appointment-form"}
                                                  busy? (assoc :loading? true))
                                                submit-label))}
                (:key-down handlers) (assoc :on-key-down (:key-down handlers)))
              (when notice (notice-alert notice))
              (summary-lines summary-row)
              [:form (cond-> {:id "appointment-form" :novalidate true :class "mt-4"}
                       (:submit handlers) (assoc :on-submit (:submit handlers)))
               (form-fields action view options)
               [:p {:class "mt-3 text-muted"} (who-sees-it action given)]])))

(defn- message-dialog
  "The dialog when there is nothing to fill in: the appointment is on its way, could not be loaded, is not there, or does not allow the
  command. It has a title and a way out."
  [{:keys [title message summary] view-state :state} {:keys [handlers] :as options}]
  (c/dialog (cond-> {:id "appointment-dialog"
                     :title title
                     :open? true
                     :actions (list (close-button "Back to the list" options))}
              (:key-down handlers) (assoc :on-key-down (:key-down handlers)))
            (case view-state
              :loading (c/skeleton {:class "h-16 w-full"})
              :lost (list [:p "The problem is on our side, not yours."]
                          (when-let [retry (:retry handlers)]
                            [:div {:class "mt-3"} (c/button {:variant :outline :icon :refresh-cw :on-click retry} "Try again")]))
              :blocked (list (when summary (summary-lines summary))
                             (c/alert {:tone :info :title "That cannot be done now" :class "mt-3"} (parts/wrapped message)))
              :missing [:p message])))

(defn- dialog [{view-state :state :as view} options]
  (when view
    (if (= :open view-state)
      (open-dialog view options)
      (message-dialog view options))))

;; ---------------------------------------------------------------------------
;; The page

(defn content
  "The appointments as a page: `state` is the state of the list (see `pulse.spa.appointments`; nil before it begins to load) and `options`
  are `:show` (`:all`, `:requests`, `:booked` or `:cancelled`: what the address narrows it to), `:admin?` (the administrator sees every
  patient's appointments), `:links` and `:handlers`, which are those of the namespace."
  [state {:keys [show admin? links handlers] :or {show :all}}]
  (let [model (appointments/model (or state appointments/not-begun) {:show show} admin?)
        options {:links links :handlers handlers}
        filter-link (:filter links)]
    (list
     [:div {:class "space-y-6"}
      (c/page-header {:title (:title model) :description (:description model)})
      [:p {:class "sr-only" :role "status"} (:live model)]
      (for [n (:notices model)] (list-notice n handlers))
      (when filter-link
        (c/tab-nav {:label "Appointments by status"
                    :items (for [{:keys [id label count current?]} (:filters model)]
                             {:href (filter-link id)
                              :label (if count (str label " (" count ")") label)
                              :current? current?})}))
      (for [[kind spec] lists
            :when (or (= :all show) (= kind show))]
        (list-section spec (get model kind) options))]
     (dialog (:dialog model) options))))

(defn page
  "The workspace around the list of appointments. `options` are those of `content`, and `:person` (who is signed in), `:me` (what the api
  said of them: see `pulse.spa.person/display`), `:alert-count` (how many alerts need a decision: see `pulse.spa.pages.shell/nav`),
  `:links` (`:panel`, `:alerts`, `:appointments` and `:jobs` for the sidebar) and `:handlers` (`:sign-out`, which puts \"Sign out\" in the
  menu of the person). There is no search box: it would have nothing to search here."
  [state {:keys [person me links handlers alert-count] :as options}]
  (shell/frame {:current :appointments :person person :me me :links links :handlers handlers :alert-count alert-count}
               (content state (assoc options :admin? (person/admin? person)))))
