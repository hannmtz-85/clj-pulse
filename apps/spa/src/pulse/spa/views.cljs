(ns pulse.spa.views
  "The views of the workspace: each asks the state for what it shows and gives it to a page, which is pure hiccup of `libs/ui`
  (ADR-0029, point 2); `->reagent` makes that hiccup what Reagent takes. A view never dispatches while it renders: what has to be
  loaded is asked for by the events of the route, not by a view."
  (:require [pulse.spa.events :as events]
            [pulse.spa.hiccup :as hiccup]
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

(defn- search
  "The text of the box is the search of the panel, which is in the address too: it is dispatched at once, and not in the turn that
  follows, because a field whose text comes back a moment later loses the place of the cursor."
  [^js event]
  (rf/dispatch-sync [::panel/search (.. event -target -value)]))

(defn- panel-view [person me {:keys [params]}]
  (let [state @(rf/subscribe [::subs/panel])]
    (panel-page/page state params
                     {:person person
                      :me me
                      :links {:panel (route/panel-path)
                              :filter (fn [show] (route/panel-path {:show show :q (:q params)}))
                              :patient route/patient-path}
                      :handlers {:sign-out sign-out :retry retry :search search}})))

(defn- patient-view
  "The chart of the patient the address names. The state of the chart that is open may be that of another patient for the moment
  between the change of the address and the events that leave one chart and begin the other, and it is not shown for this one."
  [person me {:keys [params]}]
  (let [open @(rf/subscribe [::subs/chart])
        state (when (= (:id params) (:id open)) open)]
    (patient-page/page state
                       {:person person
                        :me me
                        :links {:panel (route/panel-path)
                                :chart (fn [{:keys [measurement days]}] (route/patient-path (:id params) {:measurement measurement :days days}))}
                        :handlers {:sign-out sign-out :retry retry-chart}})))

(defn- workspace
  "The page of the workspace the person is on: the wait while the history of the browser has not said where, the page for a page the
  person's role does not open, the page for an address that is none, or the page the route names."
  [person me here]
  (let [options {:person person :me me :links {:panel (route/panel-path)} :handlers {:sign-out sign-out}}]
    (cond
      (nil? here) (start/starting)
      (not (route/allowed? (:name here) person)) (start/not-allowed options)
      :else (case (:name here)
              :panel (panel-view person me here)
              :patient (patient-view person me here)
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
