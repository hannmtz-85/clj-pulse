(ns pulse.spa.subs
  "What the views read of the state, by name (ADR-0029, point 3)."
  (:require [re-frame.core :as rf]))

(defn status
  "How the sign-in went: `:starting`, `:signed-in` or `:failed`, or nil before anything has been asked."
  [db _]
  (:status db))

(defn person
  "Who is signed in, or nil."
  [db _]
  (:person db))

(rf/reg-sub ::status status)

(rf/reg-sub ::person person)
