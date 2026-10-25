(ns pulse.spa.views
  "The views of the workspace: each asks the state for what it shows and gives it to a page, which is pure hiccup of
  `libs/ui` (ADR-0029, point 2); `->reagent` makes that hiccup what Reagent takes. A view never dispatches while it renders: what
  has to be loaded is asked for by the events of the route, not by a view."
  (:require [pulse.spa.hiccup :as hiccup]
            [pulse.spa.pages.start :as start]
            [pulse.spa.subs :as subs]
            [re-frame.core :as rf]))

(defn- reload [] (.reload js/location))

(defn root
  "The workspace: the wait for the sign-in, the page when it failed, or the page of the person who is signed in."
  []
  (let [status @(rf/subscribe [::subs/status])
        person @(rf/subscribe [::subs/person])]
    (hiccup/->reagent
     (case status
       :signed-in (start/signed-in person {})
       :failed (start/failed {:retry reload})
       (start/starting)))))
