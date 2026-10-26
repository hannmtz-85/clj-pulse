(ns pulse.spa.views
  "The views of the workspace: each asks the state for what it shows and gives it to a page, which is pure hiccup of
  `libs/ui` (ADR-0029, point 2); `->reagent` makes that hiccup what Reagent takes. A view never dispatches while it renders: what
  has to be loaded is asked for by the events of the route, not by a view."
  (:require [pulse.spa.events :as events]
            [pulse.spa.hiccup :as hiccup]
            [pulse.spa.pages.start :as start]
            [pulse.spa.settings :as settings]
            [pulse.spa.subs :as subs]
            [re-frame.core :as rf]))

(defn- reload [] (.reload js/location))

(defn- sign-out [] (rf/dispatch [::events/sign-out]))

(defn root
  "The workspace: the wait for the sign-in, the page when it failed, the page for a person it is not for, the one for a sign-in the
  api did not accept, the one of someone who has signed out, or the page of the person who is signed in. A link to `/` is a link to
  signing in: the page opens, and the identity provider is asked."
  []
  (let [status @(rf/subscribe [::subs/status])
        person @(rf/subscribe [::subs/person])
        me @(rf/subscribe [::subs/me])]
    (hiccup/->reagent
     (case status
       :signed-in (start/signed-in person {:links {} :handlers {:sign-out sign-out} :me me})
       :refused (start/refused {:links {:portal settings/portal-url} :handlers {:sign-out sign-out}})
       :unauthorized (start/unauthorized {:links {:sign-in "/"}})
       :signed-out (start/signed-out {:links {:sign-in "/"}})
       :failed (start/failed {:retry reload})
       (start/starting)))))
