(ns pulse.spa.core
  "The entry of the browser build: mounts the workspace and asks for the sign-in (ADR-0029), unless the page is the one of a person
  who has just signed out, which must not send them straight back to sign in."
  (:require [pulse.spa.ack-events]
            [pulse.spa.appointments-events]
            [pulse.spa.events :as events]
            ;; the events of the pages, of the acknowledgement of an alert and of the beat of the workspace are registered when these are
            ;; loaded
            [pulse.spa.inbox-events]
            [pulse.spa.jobs-events]
            [pulse.spa.panel-events]
            [pulse.spa.patient-events]
            [pulse.spa.route :as route]
            [pulse.spa.views :as views]
            [pulse.spa.watch-events]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]))

(defonce ^:private root-ref (atom nil))

(defn- mount []
  (when-not @root-ref
    (reset! root-ref (rdc/create-root (js/document.getElementById "app"))))
  (rdc/render @root-ref [views/app]))

(defn ^:dev/after-load reload
  "Shows the code that was just compiled, with the state it had."
  []
  (rf/clear-subscription-cache!)
  (mount))

(defn init
  "Where the page begins."
  []
  (rf/dispatch-sync (if (route/signed-out? (.-pathname js/location))
                      [::events/signed-out]
                      [::events/start]))
  (mount))
