(ns pulse.spa.watch-events
  "The events of the beat of the workspace (ADR-0029, point 6): every 30 seconds, while the tab is seen, the count of the alerts that need a
  decision is read again and the page the person is on may look again at what it shows. It is the first thing in the workspace that
  repeats, and it is built so that it cannot pile up:

  - there is one run of the beat at a time, and each run has an *epoch*: a beat that belongs to a run that is over does nothing and does
    not arm another, so that however many times the tab is hidden and seen again there is one timer alive;
  - a beat that finds the tab hidden asks for nothing (a tab nobody looks at has no use for the count) and arms the next one, and the
    moment the tab is seen again a new run begins and asks at once, so that the person is not shown a number from before they left;
  - a beat that finds the workspace no longer signed in (the api did not accept the sign-in) does not arm another: the beat stops.

  The count is a part of its own in the state, `{:state :loaded :total n}` or `{:state :failed :kind k}`, and one that failed is shown as
  a count that could not be read, never as zero (`pulse.spa.watch`)."
  (:require [pulse.spa.fetch :as fetch]
            [pulse.spa.route :as route]
            [pulse.spa.watch :as watch]
            [re-frame.core :as rf]))

(rf/reg-cofx
 ::visible?
 (fn [cofx _]
   (assoc cofx :visible? (not= "hidden" (.-visibilityState js/document)))))

(defonce ^:private listening? (atom false))

(rf/reg-fx
 ::listen
 (fn [_]
   ;; once, whatever the number of times the beat begins: the browser tells the page when the tab is seen again
   (when (compare-and-set! listening? false true)
     (.addEventListener js/document "visibilitychange"
                        (fn []
                          (when (= "visible" (.-visibilityState js/document))
                            (rf/dispatch [::watch/visible])))))))

(defn- epoch [db]
  (get-in db [:watch :epoch] 0))

(defn- beat [ep]
  [:dispatch-later {:ms watch/beat-ms :dispatch [::watch/beat ep]}])

(defn- refresh
  "The effects that make the page the person is on look again: the `:refresh` events of its route."
  [db]
  (mapv (fn [event] [:dispatch event]) (route/refresh-events (:route db) (:person db))))

(defn- begin
  "The state and the effects of a new run of the beat: the count is asked for now, the next beat is armed and, if `again?`, the page is
  asked to look again. The count that is known stays where it is until the new one is back."
  [db again?]
  (let [ep (inc (epoch db))]
    {:db (assoc-in db [:watch :epoch] ep)
     :fx (-> [[::fetch/request (watch/count-request ep)] (beat ep) [::listen nil]]
             (into (when again? (refresh db))))}))

(rf/reg-event-fx
 ::watch/start
 (fn [{:keys [db]} _]
   ;; the page the person is on has just been loaded or is about to be: there is nothing yet to look at again
   (begin db false)))

(rf/reg-event-fx
 ::watch/beat
 [(rf/inject-cofx ::visible?)]
 (fn [{:keys [db visible?]} [_ ep]]
   (if (and (= ep (epoch db)) (= :signed-in (:status db)))
     {:fx (cond-> [(beat ep)]
            visible? (-> (conj [::fetch/request (watch/count-request ep)])
                         (into (refresh db))))}
     {})))

(rf/reg-event-fx
 ::watch/visible
 (fn [{:keys [db]} _]
   ;; the tab is seen again: whatever time it was hidden for, what is shown may be old
   (if (= :signed-in (:status db))
     (begin db true)
     {})))

(rf/reg-event-fx
 ::watch/now
 (fn [{:keys [db]} _]
   ;; the count asked for out of turn, after the person acknowledged an alert: the beat is not moved
   (if (= :signed-in (:status db))
     {:fx [[::fetch/request (watch/count-request (epoch db))]]}
     {})))

(rf/reg-event-db
 ::watch/counted
 (fn [db [_ ep response]]
   (if (= ep (epoch db))
     (assoc-in db [:watch :count] (watch/counted (:body response)))
     db)))

(rf/reg-event-db
 ::watch/count-failed
 (fn [db [_ ep failure]]
   (if (= ep (epoch db))
     (assoc-in db [:watch :count] {:state :failed :kind (:kind failure)})
     db)))
