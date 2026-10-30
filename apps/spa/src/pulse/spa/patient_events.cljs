(ns pulse.spa.patient-events
  "The events of the chart of a patient (ADR-0029, points 3 and 6): pure functions from the state and an event to the effects that
  follow, with what is decided in `pulse.spa.patient`. The chart is loaded when the person comes to it (the `:start` of its route), its
  calls are made as the ones before them are answered, and one deadline ends what is not back. Every answer carries the *generation* of
  the load it belongs to: the chart that was left, or that was loaded again, does not hear the answers of the one before it.

  Where the chart is, is the address: the measurement and the period are in the `:route` of the state and in the path, and choosing
  another is a change of the address that this namespace hears as `::patient/choose` (the `:update` of its route), which asks for the
  series again and for nothing else. A new *epoch* is begun by each load, retry and choice, and the deadline that was set for an
  epoch that is over does nothing: a person who chooses a period nine seconds after the chart settled must not find the deadline of
  the first load failing the chart they asked for."
  (:require [pulse.spa.fetch :as fetch]
            [pulse.spa.patient :as patient]
            [pulse.view.format :as fmt]
            [re-frame.core :as rf]))

(rf/reg-cofx
 ::now
 (fn [cofx _]
   (assoc cofx :now (fmt/instant-text (js/Date.now)))))

(defn- generation [db]
  (get db :chart-generation 0))

(defn- epoch [db]
  (get db :chart-epoch 0))

(defn- current?
  "Whether an answer of the load `gen` is for the chart that is open."
  [db gen]
  (and (some? (:chart db)) (= gen (generation db))))

(defn- calls
  "The effects that make the calls of `specs`, in order."
  [specs]
  (mapv (fn [spec] [::fetch/request spec]) specs))

(defn- cancels
  "The effects that stop the calls of the tasks `tasks`."
  [tasks]
  (mapv (fn [task] [::fetch/cancel {:id (patient/call-id task)}]) tasks))

(defn- deadline [gen epoch]
  [:dispatch-later {:ms patient/deadline-ms :dispatch [::patient/deadline gen epoch]}])

(defn- pumped
  "The state with `chart` in it, and the effects for the calls that can begin now."
  [db chart gen]
  (let [[chart started] (patient/pump chart gen)]
    {:db (assoc db :chart chart)
     :fx (calls started)}))

(rf/reg-event-fx
 ::patient/load
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} [_ params]]
   (let [gen (inc (generation db))
         ep (inc (epoch db))
         [chart started] (patient/pump (patient/start now params) gen)]
     {:db (assoc db :chart-generation gen :chart-epoch ep :chart chart)
      :fx (conj (calls started) (deadline gen ep))})))

(rf/reg-event-fx
 ::patient/succeeded
 (fn [{:keys [db]} [_ gen task response]]
   (if (current? db gen)
     (pumped db (patient/answer (:chart db) task {:result :ok :body (:body response)}) gen)
     {})))

(rf/reg-event-fx
 ::patient/failed
 (fn [{:keys [db]} [_ gen task failure]]
   (if (current? db gen)
     (pumped db (patient/answer (:chart db) task {:result :failed :kind (:kind failure)}) gen)
     {})))

(rf/reg-event-fx
 ::patient/deadline
 (fn [{:keys [db]} [_ gen ep]]
   ;; a chart that has settled has nothing that is loading: `expire` leaves it as it is and has no call to stop; and a deadline of an
   ;; epoch that is over (the person chose another period since) is not the deadline of what is loading now
   (if (and (current? db gen) (= ep (epoch db)))
     (let [expired (patient/expire (:chart db))]
       {:db (assoc db :chart (dissoc expired :cancelled))
        :fx (cancels (:cancelled expired))})
     {})))

(rf/reg-event-fx
 ::patient/retry
 (fn [{:keys [db]} _]
   ;; offered only once the chart has settled, so that nothing is on its way that a new generation would make deaf
   (if (and (:chart db) (patient/settled? (:chart db)) (seq (patient/failed-tasks (:chart db))))
     (let [gen (inc (generation db))
           ep (inc (epoch db))
           [retried started] (patient/pump (patient/retry (:chart db)) gen)]
       {:db (assoc db :chart-generation gen :chart-epoch ep :chart retried)
        :fx (conj (calls started) (deadline gen ep))})
     {})))

(rf/reg-event-fx
 ::patient/choose
 (fn [{:keys [db]} [_ params]]
   ;; the series of the measurement and the period in the address; the rest of the chart is as it was, and its answers still count
   (let [chart (:chart db)]
     (if (and chart (= (:id params) (:id chart)))
       (let [[chosen started] (patient/pump (patient/choose chart params) (generation db))]
         (if (empty? started)
           ;; a choice that asks for nothing (the same as before, or the measurement that was the default asked for by name) has nothing to
           ;; set a deadline for
           {:db (assoc db :chart chosen)}
           (let [ep (inc (epoch db))]
             {:db (assoc db :chart chosen :chart-epoch ep)
              :fx (conj (calls started) (deadline (generation db) ep))})))
       {}))))

(rf/reg-event-fx
 ::patient/leave
 (fn [{:keys [db]} _]
   {:db (-> db (assoc :chart-generation (inc (generation db))) (dissoc :chart))
    :fx (cancels (sort-by str (get-in db [:chart :inflight])))}))
