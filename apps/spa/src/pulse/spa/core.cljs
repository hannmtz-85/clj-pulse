(ns pulse.spa.core
  "The entry of the browser build: mounts the workspace and asks for the sign-in (ADR-0029)."
  (:require [pulse.spa.events :as events]
            [pulse.spa.views :as views]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]))

(defonce ^:private root-ref (atom nil))

(defn- mount []
  (when-not @root-ref
    (reset! root-ref (rdc/create-root (js/document.getElementById "app"))))
  (rdc/render @root-ref [views/root]))

(defn ^:dev/after-load reload
  "Shows the code that was just compiled, with the state it had."
  []
  (rf/clear-subscription-cache!)
  (mount))

(defn init
  "Where the page begins."
  []
  (rf/dispatch-sync [::events/start])
  (mount))
