(ns pulse.spa.subs
  "What the views read of the state, by name (ADR-0029, point 3)."
  (:require [re-frame.core :as rf]))

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

(rf/reg-sub ::status status)

(rf/reg-sub ::person person)

(rf/reg-sub ::me me)
