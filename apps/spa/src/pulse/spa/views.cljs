(ns pulse.spa.views
  "The views of the workspace: each asks the state for what it shows and gives it to a page, which is pure hiccup of `libs/ui`
  (ADR-0029, point 2); `->reagent` makes that hiccup what Reagent takes. A view never dispatches while it renders: what has to be
  loaded is asked for by the events of the route, not by a view."
  (:require [pulse.spa.events :as events]
            [pulse.spa.hiccup :as hiccup]
            [pulse.spa.pages.alerts :as alerts-page]
            [pulse.spa.pages.panel :as panel-page]
            [pulse.spa.pages.patient :as patient-page]
            [pulse.spa.pages.start :as start]
            [pulse.spa.panel :as panel]
            [pulse.spa.patient :as patient]
            [pulse.spa.route :as route]
            [pulse.spa.settings :as settings]
            [pulse.spa.subs :as subs]
            [re-frame.core :as rf]))

(defn- reload [] (.reload js/location))

(defn- sign-out [] (rf/dispatch [::events/sign-out]))

(defn- retry [] (rf/dispatch [::panel/retry]))

(defn- retry-chart [] (rf/dispatch [::patient/retry]))

(defn- retry-inbox [] (rf/dispatch [:pulse.spa.inbox/retry]))

(defn- show-changes [] (rf/dispatch [:pulse.spa.inbox/show-changes]))

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
                              :links {:chart (fn [{:keys [measurement days]}] (route/patient-path (:id params) {:measurement measurement :days days}))}
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

(defn- workspace
  "The page of the workspace the person is on: the wait while the history of the browser has not said where, the page for a page the
  person's role does not open, the page for an address that is none, or the page the route names."
  [person me here]
  (let [options {:person person
                 :me me
                 :alert-count @(rf/subscribe [::subs/alert-count])
                 :links {:panel (route/panel-path) :alerts (route/alerts-path)}
                 :handlers {:sign-out sign-out}}]
    (cond
      (nil? here) (start/starting)
      (not (route/allowed? (:name here) person)) (start/not-allowed options)
      :else (case (:name here)
              :panel (panel-view options here)
              :patient (patient-view options here)
              :alerts (alerts-view options here)
              (start/not-found options)))))

(defn root
  "The workspace: the wait for the sign-in, the page when it failed, the page for a person it is not for, the one for a sign-in the
  api did not accept, the one of someone who has signed out, or the page of the workspace the person is on. A link to `/` is a link to
  signing in: the page opens, and the identity provider is asked."
  []
  (let [status @(rf/subscribe [::subs/status])
        person @(rf/subscribe [::subs/person])
        me @(rf/subscribe [::subs/me])
        here @(rf/subscribe [::subs/route])]
    (hiccup/->reagent
     (case status
       :signed-in (workspace person me here)
       :refused (start/refused {:links {:portal settings/portal-url} :handlers {:sign-out sign-out}})
       :unauthorized (start/unauthorized {:links {:sign-in "/"}})
       :signed-out (start/signed-out {:links {:sign-in "/"}})
       :failed (start/failed {:retry reload})
       (start/starting)))))
