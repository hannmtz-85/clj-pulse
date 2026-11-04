(ns pulse.spa.inbox-events
  "The events of the inbox of alerts (ADR-0029, points 3 and 6): pure functions from the state and an event to the effects that follow,
  with what is decided in `pulse.spa.inbox`. The inbox is loaded when the person comes to it (the `:start` of its route), its calls are
  made a few at a time as the ones before them are answered, and one deadline ends what is not back. Every answer carries the
  *generation* of the load it belongs to: the inbox that was left, or that was loaded again, does not hear the answers of the one
  before it.

  While it is open the beat of the workspace asks it to look again (`::inbox/check`, the `:refresh` of its route). What the look finds is
  kept apart, and the page says when it differs from what it shows; the person shows it (`::inbox/show-changes`). What changes under
  the hand of someone about to press a button is how the wrong alert is acknowledged. The one exception is what the person did
  themselves: after they acknowledge an alert the inbox looks again and shows what it finds (`::inbox/after-ack`), and the focus, which
  was on a button that is gone, goes to the heading of the alerts that need a decision.

  A new *epoch* is begun by each load, retry and look, and the deadline that was set for an epoch that is over does nothing."
  (:require [pulse.spa.ack :as ack]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.router :as router]
            [pulse.view.format :as fmt]
            [re-frame.core :as rf]))

(rf/reg-cofx
 ::now
 (fn [cofx _]
   (assoc cofx :now (fmt/instant-text (js/Date.now)))))

(defn- generation [db]
  (get db :inbox-generation 0))

(defn- epoch [db]
  (get db :inbox-epoch 0))

(defn- current?
  "Whether an answer of the load `gen` is for the inbox that is open."
  [db gen]
  (and (some? (:inbox db)) (= gen (generation db))))

(defn- calls
  "The effects that make the calls of `specs`, in order."
  [specs]
  (mapv (fn [spec] [::fetch/request spec]) specs))

(defn- cancels
  "The effects that stop the calls of the tasks `tasks`."
  [tasks]
  (mapv (fn [task] [::fetch/cancel {:id (inbox/call-id task)}]) tasks))

(defn- deadline [gen ep]
  [:dispatch-later {:ms inbox/deadline-ms :dispatch [::inbox/deadline gen ep]}])

(defn- settled-acks
  "The acknowledgements once what they were waiting for is over: the inbox has been read again, or it is not going to be. Nothing is
  waiting, and what a screen reader was told of them gives way to what the page says itself."
  [acks]
  (-> acks ack/clear-settling ack/hush))

(defn- pumped
  "The state with `inbox` in it, and the effects for the calls that can begin now. When the looks the person asked for after
  acknowledging an alert are all back, what they found is shown, and the focus goes where it belongs (`pulse.spa.inbox/focus-target`):
  the heading of the alerts that need a decision, or the button of an alert whose acknowledgement was not done."
  [db inbox gen]
  (let [adopt? (inbox/adopt-now? inbox)
        [inbox started] (inbox/pump (cond-> inbox adopt? inbox/adopt) gen)
        hush (when adopt? (ack/hush-later (:acks db)))]
    {:db (cond-> (assoc db :inbox inbox)
           adopt? (update :acks ack/clear-settling))
     :fx (cond-> (calls started)
           adopt? (conj [::router/focus (inbox/focus-target inbox (:acks db))])
           hush (conj hush))}))

(defn- looking
  "The state and effects of the inbox begun to look again, `inbox` being the inbox as it must be for that."
  [db inbox]
  (let [gen (generation db)
        ep (inc (epoch db))
        [looked started] (inbox/pump inbox gen)]
    {:db (assoc db :inbox-epoch ep :inbox looked)
     :fx (conj (calls started) (deadline gen ep))}))

(rf/reg-event-fx
 ::inbox/load
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   (let [gen (inc (generation db))
         ep (inc (epoch db))
         [inbox started] (inbox/pump (inbox/start now) gen)]
     {:db (-> db
              (assoc :inbox-generation gen :inbox-epoch ep :inbox inbox)
              (update :acks ack/hush))
      :fx (conj (calls started) (deadline gen ep))})))

(rf/reg-event-fx
 ::inbox/succeeded
 (fn [{:keys [db]} [_ gen task response]]
   (if (current? db gen)
     (pumped db (inbox/answer (:inbox db) task {:result :ok :body (:body response)}) gen)
     {})))

(rf/reg-event-fx
 ::inbox/failed
 (fn [{:keys [db]} [_ gen task failure]]
   (if (current? db gen)
     (pumped db (inbox/answer (:inbox db) task {:result :failed :kind (:kind failure)}) gen)
     {})))

(rf/reg-event-fx
 ::inbox/deadline
 (fn [{:keys [db]} [_ gen ep]]
   ;; an inbox that has settled has nothing that is loading: `expire` leaves it as it is and has no call to stop; and a deadline of an
   ;; epoch that is over (the person asked for a look since) is not the deadline of what is loading now
   (if (and (current? db gen) (= ep (epoch db)))
     (let [adopt? (boolean (get-in db [:inbox :adopt?]))
           expired (inbox/expire (:inbox db))
           ;; the look that the person asked for shows what it found, even if some of it did not come
           inbox (cond-> (dissoc expired :cancelled) adopt? inbox/adopt)
           hush (when adopt? (ack/hush-later (:acks db)))]
       {:db (cond-> (assoc db :inbox inbox)
              adopt? (update :acks ack/clear-settling))
        :fx (cond-> (cancels (:cancelled expired))
              adopt? (conj [::router/focus (inbox/focus-target inbox (:acks db))])
              hush (conj hush))})
     {})))

(rf/reg-event-fx
 ::inbox/retry
 (fn [{:keys [db]} _]
   ;; offered only once the inbox has settled, so that nothing is on its way that a new generation would make deaf
   (let [inbox (:inbox db)]
     (if (and inbox (inbox/settled? inbox) (seq (inbox/failed-tasks inbox)))
       (let [gen (inc (generation db))
             ep (inc (epoch db))
             [retried started] (inbox/pump (inbox/retry inbox) gen)]
         {:db (assoc db :inbox-generation gen :inbox-epoch ep :inbox retried)
          :fx (conj (calls started) (deadline gen ep))})
       {}))))

(rf/reg-event-fx
 ::inbox/check
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   ;; the beat of the workspace asks the page to look again; one that is still loading, or looking, has no use for another look
   (let [inbox (:inbox db)]
     (if (and inbox (inbox/settled? inbox))
       (looking db (inbox/check inbox now))
       {}))))

(rf/reg-event-fx
 ::inbox/after-ack
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   ;; whatever is on its way is older than the command that was just answered: the new look stops the one before it
   (if-let [inbox (:inbox db)]
     (looking db (inbox/after-ack inbox now))
     {})))

(rf/reg-event-fx
 ::inbox/show-changes
 (fn [{:keys [db]} _]
   (let [inbox (:inbox db)]
     (if (and inbox (inbox/changed? inbox))
       (let [gen (generation db)
             ep (inc (epoch db))
             [adopted started] (inbox/pump (inbox/adopt inbox) gen)]
         {:db (assoc db :inbox-epoch ep :inbox adopted)
          :fx (-> (calls started)
                  (cond-> (seq started) (conj (deadline gen ep)))
                  (conj [::router/focus "#needs"]))})
       {}))))

(rf/reg-event-fx
 ::inbox/leave
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :inbox-generation (inc (generation db)))
            (dissoc :inbox)
            (update :acks settled-acks))
    :fx (cancels (sort-by str (get-in db [:inbox :inflight])))}))
