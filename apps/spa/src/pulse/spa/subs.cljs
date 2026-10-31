(ns pulse.spa.subs
  "What the views read of the state, by name (ADR-0029, point 3)."
  (:require [pulse.spa.watch :as watch]
            [re-frame.core :as rf]))

(defn status
  "What the workspace is doing: `:starting`, `:signed-in`, `:refused`, `:unauthorized`, `:signed-out` or `:failed`, or nil before
  anything has been asked."
  [db _]
  (:status db))

(defn person
  "Who is signed in, or nil."
  [db _]
  (:person db))

(defn me
  "What the api says of the person, a part: `{:state :loading|:loaded|:failed ...}`, or nil while nothing has been asked."
  [db _]
  (:me db))

(defn route
  "Where the workspace is, `{:name :params}` (see `pulse.spa.route`), or nil before the history of the browser has said."
  [db _]
  (:route db))

(defn panel
  "The state of the panel (see `pulse.spa.panel`), or nil when the person is not on it."
  [db _]
  (:panel db))

(defn chart
  "The state of the chart of a patient (see `pulse.spa.patient`), or nil when the person is not on one."
  [db _]
  (:chart db))

(defn inbox
  "The state of the inbox of alerts (see `pulse.spa.inbox`), or nil when the person is not on it."
  [db _]
  (:inbox db))

(defn acks
  "The state of the acknowledgements of alerts (see `pulse.spa.ack`): `{:alerts {id state} :said text}`, or nil when none was begun."
  [db _]
  (:acks db))

(defn alert-count
  "How many alerts need a decision, for the navigation: a number, `:unknown` when it could not be read, nil while it is not known."
  [db _]
  (watch/nav-count (:watch db)))

(rf/reg-sub ::status status)

(rf/reg-sub ::person person)

(rf/reg-sub ::me me)

(rf/reg-sub ::route route)

(rf/reg-sub ::panel panel)

(rf/reg-sub ::chart chart)

(rf/reg-sub ::inbox inbox)

(rf/reg-sub ::acks acks)

(rf/reg-sub ::alert-count alert-count)
