(ns pulse.spa.jobs-events
  "The events of the page of the jobs (ADR-0029, points 3 and 6): pure functions from the state and an event to the effects that follow,
  with what is decided in `pulse.spa.jobs`. The jobs are read when the person comes to the page (the `:start` of its route), once, and
  nothing repeats it. There is one call, and `pulse.spa.fetch` gives it its six seconds, so there is no deadline here; the page that is
  left stops the call, and a call that was stopped tells nobody anything, so an answer is never for a page that is not open."
  (:require [pulse.spa.fetch :as fetch]
            [pulse.spa.jobs :as jobs]
            [pulse.view.format :as fmt]
            [re-frame.core :as rf]))

(rf/reg-cofx
 ::now
 (fn [cofx _]
   (assoc cofx :now (fmt/instant-text (js/Date.now)))))

(rf/reg-event-fx
 ::jobs/load
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   {:db (assoc db :jobs (jobs/start now))
    :fx [[::fetch/request jobs/request]]}))

(rf/reg-event-db
 ::jobs/succeeded
 (fn [db [_ response]]
   ;; the page may have been left between the answer and this event
   (if (:jobs db)
     (update db :jobs jobs/answer {:result :ok :body (:body response)})
     db)))

(rf/reg-event-db
 ::jobs/failed
 (fn [db [_ failure]]
   (if (:jobs db)
     (update db :jobs jobs/answer {:result :failed :kind (:kind failure)})
     db)))

(rf/reg-event-fx
 ::jobs/retry
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   ;; offered only when the jobs could not be loaded, so that nothing is on its way (and when there is no page they did not fail)
   (if (jobs/failed? (:jobs db))
     {:db (update db :jobs jobs/retry now)
      :fx [[::fetch/request jobs/request]]}
     {})))

(rf/reg-event-fx
 ::jobs/leave
 (fn [{:keys [db]} _]
   {:db (dissoc db :jobs)
    :fx [[::fetch/cancel {:id jobs/call-id}]]}))
