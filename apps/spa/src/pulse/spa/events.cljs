(ns pulse.spa.events
  "The events of the workspace: pure functions from the state and an event to the effects that follow (ADR-0029, point 3). What
  they ask for is data; `pulse.spa.auth` and the others do it."
  (:require [pulse.spa.auth :as auth]
            [pulse.spa.person :as person]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::start
 (fn [_ _]
   {:db {:status :starting}
    ::auth/init {:on-success [::signed-in] :on-failure [::sign-in-failed]}}))

(rf/reg-event-db
 ::signed-in
 (fn [db [_ claims]]
   (if-let [who (person/from-claims claims)]
     (assoc db :status :signed-in :person who)
     (assoc db :status :failed))))

(rf/reg-event-db
 ::sign-in-failed
 (fn [db _]
   (assoc db :status :failed)))
