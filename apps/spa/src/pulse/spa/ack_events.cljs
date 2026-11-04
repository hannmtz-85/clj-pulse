(ns pulse.spa.ack-events
  "The events of acknowledging an alert (ADR-0029, points 3 and 8): pure functions from the state and an event to the effects that
  follow, with what is decided in `pulse.spa.ack`. The same events serve the inbox and the chart of a patient, which both show alerts.

  **The workspace never decides that an alert is acknowledged.** The click sends the command and the button is busy; the api answers;
  and then the page that holds the alert is read again, and the alert is where the api's list puts it. There is no optimistic update
  (point 3): a card that went away before the api said so would be a lie if the command failed. What the person is told is said to a
  screen reader as well, and when a command did not go through the focus goes back to the button, which was disabled while it was sent
  and lost it."
  (:require [pulse.spa.ack :as ack]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.router :as router]
            [re-frame.core :as rf]))

(defn- reread
  "The events that read again the page the person is on, which holds the alert: the inbox looks again and shows what it finds, and the
  chart reads its summary again. A page that holds no alert has nothing to read, and nil is what it gives."
  [db]
  (case (get-in db [:route :name])
    :alerts (when (:inbox db) [[:pulse.spa.inbox/after-ack]])
    :patient (when (:chart db) [[:pulse.spa.patient/after-ack]])
    nil))

(defn- dispatches [events]
  (mapv (fn [event] [:dispatch event]) events))

(defn- ended
  "The state and effects when the api's answer means that the alert `id` is not active any more, whoever acknowledged it (or that it is
  gone): the page is read again, and the count of the navigation. The button stays busy until the page has it."
  [db id result]
  (let [said (ack/announcement result (ack/label-of (:acks db) id))
        again (reread db)]
    {:db (update db :acks (if (seq again) #(ack/settling % id said) #(ack/forget % id said)))
     :fx (conj (dispatches again) [:dispatch [:pulse.spa.watch/now]])}))

(defn- not-done
  "The state and effects when the alert `id` has not been acknowledged, or it is not known: the button is free again with `message` beside
  it, and the focus goes back to it. If it is not known, the page is read again to find out."
  [db id message find-out?]
  (let [again (when find-out? (reread db))]
    {:db (update db :acks ack/failed id message)
     :fx (-> [[::router/focus (str "#ack-" id)]]
             (into (dispatches again))
             (cond-> find-out? (conj [:dispatch [:pulse.spa.watch/now]])))}))

(defn- by-verdict
  "What an answer that was not a success does, from what `pulse.spa.ack/verdict` says it means."
  [db id failure]
  (let [{:keys [result message]} (ack/verdict failure)]
    (case result
      (:already :gone) (ended db id result)
      :unconfirmed (not-done db id message true)
      (:forbidden :refused) (not-done db id message false)
      :ignore {:db (update db :acks ack/forget id nil)})))

(rf/reg-event-fx
 ::ack/acknowledge
 (fn [{:keys [db]} [_ id label]]
   ;; a click on a button that is busy is not a second command
   (if (ack/busy? (:acks db) id)
     {}
     {:db (update db :acks ack/begin id label)
      :fx [[::fetch/request (ack/request id)]]})))

(rf/reg-event-fx
 ::ack/answered
 (fn [{:keys [db]} [_ id _response]]
   (ended db id :done)))

(rf/reg-event-db
 ::ack/hush
 (fn [db [_ said]]
   ;; what a screen reader was told stays a moment after the page has been read again (`ack/said-for-ms`), so that it is drawn before the
   ;; page says its own words; then it gives way, unless something else has been said since
   (cond-> db (:acks db) (update :acks ack/hush-said said))))

(rf/reg-event-fx
 ::ack/refused
 (fn [{:keys [db]} [_ id failure]]
   (by-verdict db id failure)))

(rf/reg-event-fx
 ::ack/failed
 (fn [{:keys [db]} [_ id failure]]
   (by-verdict db id failure)))
