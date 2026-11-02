(ns pulse.spa.views
  "The views of the workspace: each asks the state for what it shows and gives it to a page, which is pure hiccup of `libs/ui`
  (ADR-0029, point 2); `->reagent` makes that hiccup what Reagent takes. A view never dispatches while it renders: what has to be
  loaded is asked for by the events of the route, not by a view."
  (:require [pulse.spa.events :as events]
            [pulse.spa.hiccup :as hiccup]
            [pulse.spa.pages.alerts :as alerts-page]
            [pulse.spa.pages.appointments :as appointments-page]
            [pulse.spa.pages.jobs :as jobs-page]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.pages.start :as start]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.spa.route :as route]
            [pulse.spa.settings :as settings]
            [pulse.spa.subs :as subs]
            [re-frame.core :as rf]
            [reagent.core :as reagent]))

(defn- reload [] (.reload js/location))

(defn- sign-out [] (rf/dispatch [::events/sign-out]))

(defn- retry [] (rf/dispatch [::panel/retry]))

(defn- retry-chart [] (rf/dispatch [::patient/retry]))

(defn- retry-inbox [] (rf/dispatch [:pulse.spa.inbox/retry]))

(defn- show-changes [] (rf/dispatch [:pulse.spa.inbox/show-changes]))

(defn- retry-jobs [] (rf/dispatch [:pulse.spa.jobs/retry]))

(defn- retry-appointments [] (rf/dispatch [:pulse.spa.appointments/retry]))

(defn- edit-appointment
  "The person typed in a field of the dialog. The text of the field is the state, and it is dispatched at once, and not in the turn that
  follows, because a field whose text comes back a moment later loses the place of the cursor."
  [field ^js event]
  (rf/dispatch-sync [:pulse.spa.appointments/edit field (.. event -target -value)]))

(defn- submit-appointment
  "The person pressed the button of the dialog, or Enter in a field of it: the browser would send the form to an address, and the
  command is sent by the workspace."
  [^js event]
  (.preventDefault event)
  (rf/dispatch [:pulse.spa.appointments/submit]))

(defn- appointment-key-down
  "Escape closes the dialog, as the link that is its way out does."
  [^js event]
  (when (= "Escape" (.-key event))
    (rf/dispatch [:pulse.spa.appointments/close])))

(defn- acknowledge
  "The person pressed the button that acknowledges the alert `id`, which the page names `label`."
  [id label]
  (rf/dispatch [:pulse.spa.ack/acknowledge id label]))

(defn- search
  "The text of the box is the search of the panel, which is in the address too: it is dispatched at once, and not in the turn that
  follows, because a field whose text comes back a moment later loses the place of the cursor."
  [^js event]
  (rf/dispatch-sync [::panel/search (.. event -target -value)]))

(defn- with
  "`options`, those every page of the workspace takes, with the links and the handlers of a page added to its own."
  [options {:keys [links handlers] :as more}]
  (-> options
      (merge (dissoc more :links :handlers))
      (update :links merge links)
      (update :handlers merge handlers)))

(defn- panel-view [options {:keys [params]}]
  (let [state @(rf/subscribe [::subs/panel])]
    (panel-page/page state params
                     (with options
                           {:links {:filter (fn [show] (route/panel-path {:show show :q (:q params)}))
                                    :patient route/patient-path}
                            :handlers {:retry retry :search search}}))))

(defn- patient-view
  "The chart of the patient the address names. The state of the chart that is open may be that of another patient for the moment
  between the change of the address and the events that leave one chart and begin the other, and it is not shown for this one."
  [options {:keys [params]}]
  (let [open @(rf/subscribe [::subs/chart])
        state (when (= (:id params) (:id open)) open)
        acks @(rf/subscribe [::subs/acks])]
    (patient-page/page state
                       (with options
                             {:acks (:alerts acks)
                              :said (:said acks)
                              :links {:chart (fn [{:keys [measurement days]}] (route/patient-path (:id params) {:measurement measurement :days days}))
                                      :dialog (fn [id action] (route/appointment-path id action))}
                              :handlers {:retry retry-chart :acknowledge acknowledge}}))))

(defn- alerts-view [options {:keys [params]}]
  (let [state @(rf/subscribe [::subs/inbox])
        acks @(rf/subscribe [::subs/acks])]
    (alerts-page/page state
                      (with options
                            {:show (:show params)
                             :acks (:alerts acks)
                             :said (:said acks)
                             :links {:filter (fn [show] (route/alerts-path {:show show}))
                                     :patient route/patient-path}
                             :handlers {:retry retry-inbox :show-changes show-changes :acknowledge acknowledge}}))))

(defn- jobs-view [options _here]
  (let [state @(rf/subscribe [::subs/jobs])]
    (jobs-page/page state (with options {:handlers {:retry retry-jobs}}))))

(defn- appointments-view
  "The list of appointments, with the dialog over it that the address names, if it names one."
  [options {:keys [params]}]
  (let [state @(rf/subscribe [::subs/appointments])
        show (:show params)]
    (appointments-page/page state
                            (with options
                                  {:show show
                                   :links {:filter (fn [filter-id] (route/appointments-path {:show filter-id}))
                                           :patient route/patient-path
                                           :dialog (fn [id action] (route/appointment-path id action {:show show}))
                                           :close (route/appointments-path {:show show})}
                                   :handlers {:retry retry-appointments
                                              :edit edit-appointment
                                              :submit submit-appointment
                                              :key-down appointment-key-down}}))))

(defn- workspace
  "The page of the workspace the person is on: the wait while the history of the browser has not said where, the page for a page the
  person's role does not open, the page for an address that is none, or the page the route names."
  [person me here]
  (let [options {:person person
                 :me me
                 :alert-count @(rf/subscribe [::subs/alert-count])
                 :links {:panel (route/panel-path) :alerts (route/alerts-path) :appointments (route/appointments-path) :jobs route/jobs-path}
                 :handlers {:sign-out sign-out}}]
    (cond
      (nil? here) (start/starting)
      (not (route/allowed? (:name here) person)) (start/not-allowed options)
      :else (case (:name here)
              :panel (panel-view options here)
              :patient (patient-view options here)
              :alerts (alerts-view options here)
              (:appointments :appointment-confirm :appointment-reschedule :appointment-cancel) (appointments-view options here)
              :jobs (jobs-view options here)
              (start/not-found options)))))

(defn root
  "The workspace: the wait for the sign-in, the page when it failed, the page for a person it is not for, the one for a sign-in the
  api did not accept, the one of someone who has signed out, or the page of the workspace the person is on. A link to `/` is a link to
  signing in: the page opens, and the identity provider is asked."
  []
  (let [status @(rf/subscribe [::subs/status])
        person @(rf/subscribe [::subs/person])
        me @(rf/subscribe [::subs/me])
        here @(rf/subscribe [::subs/route])
        slow? @(rf/subscribe [::subs/slow-start?])]
    (hiccup/->reagent
     (case status
       :signed-in (workspace person me here)
       :refused (start/refused {:links {:portal settings/portal-url} :handlers {:sign-out sign-out}})
       :unauthorized (start/unauthorized {:links {:sign-in "/"}})
       :signed-out (start/signed-out {:links {:sign-in "/"}})
       :failed (start/failed {:retry reload})
       (start/starting {:slow? slow? :handlers {:retry reload}})))))

;; ---------------------------------------------------------------------------
;; When a page cannot be drawn

(defn- go-to-the-panel
  "Loads the panel afresh. It is not a link of the router, which would keep the workspace that failed."
  []
  (set! (.-href js/location) "/"))

(defn crashed
  "What the person is shown when drawing a page threw: they are told so, with the ways out (see `pulse.spa.pages.start/crashed`)."
  []
  (hiccup/->reagent (start/crashed {:handlers {:reload reload :panel go-to-the-panel :sign-out sign-out}})))

(defn crash-state
  "The state React keeps for a boundary after `error` was thrown while drawing what is under it. The key is a string, which the
  compiler does not rename."
  [_error]
  (js-obj "crashed" true))

(defn crashed?
  "Whether the state React keeps for a boundary (nil before it has any) says that what is under it threw."
  [^js state]
  (boolean (and state (aget state "crashed"))))

(defn- log-crash [_this error ^js info]
  (js/console.error "The workspace could not draw a page:" error (when info (.-componentStack info))))

(def boundary
  "A component that draws what is under it, and, if drawing it throws, the page `crashed` instead: React unmounts everything under an
  error that nothing catches, which leaves a white page. Only an error in drawing is caught here (those of events and of the effects are
  the machines'), and the cause goes to the console."
  (reagent/create-class
   {:display-name "Boundary"
    :get-derived-state-from-error crash-state
    :component-did-catch log-crash
    :reagent-render (fn [child]
                      (if (crashed? (.-state ^js (reagent/current-component)))
                        [crashed]
                        child))}))

(defn app
  "The workspace, with a boundary around it."
  []
  [boundary [root]])
