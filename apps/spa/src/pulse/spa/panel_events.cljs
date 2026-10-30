(ns pulse.spa.panel-events
  "The events of the panel (ADR-0029, points 3 and 6): pure functions from the state and an event to the effects that follow, with
  what is decided in `pulse.spa.panel`. The panel is loaded when the person comes to it (the `:start` of its route), its calls are
  made a few at a time as the ones before them are answered, and one deadline ends what is not back. Every answer carries the
  *generation* of the load it belongs to: the panel that was left, or that was loaded again, does not hear the answers of the one
  before it.

  Where the workspace is, is the address: the filter and the search of the panel are in the `:route` of the state, and typing in the
  search box changes the address without adding to the history."
  (:require [pulse.spa.fetch :as fetch]
            [pulse.spa.panel :as panel]
            [pulse.spa.route :as route]
            [pulse.spa.router :as router]
            [pulse.view.format :as fmt]
            [re-frame.core :as rf]))

(rf/reg-cofx
 ::now
 (fn [cofx _]
   (assoc cofx :now (fmt/instant-text (js/Date.now)))))

(defn- generation [db]
  (get db :panel-generation 0))

(defn- current?
  "Whether an answer of the load `gen` is for the panel that is open."
  [db gen]
  (and (some? (:panel db)) (= gen (generation db))))

(defn- calls
  "The effects that make the calls of `specs`, in order."
  [specs]
  (mapv (fn [spec] [::fetch/request spec]) specs))

(defn- cancels
  "The effects that stop the calls of the tasks `tasks`."
  [tasks]
  (mapv (fn [task] [::fetch/cancel {:id [:panel task]}]) tasks))

(defn- deadline [gen]
  [:dispatch-later {:ms panel/deadline-ms :dispatch [::panel/deadline gen]}])

(defn- pumped
  "The state with `panel` in it, put in order if it has settled, and the effects for the calls that can begin now."
  [db panel gen]
  (let [[panel started] (panel/pump panel gen)]
    {:db (assoc db :panel (panel/with-order panel))
     :fx (calls started)}))

(rf/reg-event-fx
 ::panel/load
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   (let [gen (inc (generation db))
         [panel started] (panel/pump (panel/start now) gen)]
     {:db (assoc db :panel-generation gen :panel panel)
      :fx (conj (calls started) (deadline gen))})))

(rf/reg-event-fx
 ::panel/succeeded
 (fn [{:keys [db]} [_ gen task response]]
   (if (current? db gen)
     (pumped db (panel/answer (:panel db) task {:result :ok :body (:body response)}) gen)
     {})))

(rf/reg-event-fx
 ::panel/failed
 (fn [{:keys [db]} [_ gen task failure]]
   (if (current? db gen)
     (pumped db (panel/answer (:panel db) task {:result :failed :kind (:kind failure)}) gen)
     {})))

(rf/reg-event-fx
 ::panel/deadline
 (fn [{:keys [db]} [_ gen]]
   ;; a panel that has settled has nothing that is loading: `expire` leaves it as it is and has no call to stop
   (if (current? db gen)
     (let [expired (panel/expire (:panel db))]
       {:db (assoc db :panel (panel/with-order (dissoc expired :cancelled)))
        :fx (cancels (:cancelled expired))})
     {})))

(rf/reg-event-fx
 ::panel/retry
 (fn [{:keys [db]} _]
   ;; offered only once the panel has settled, so that nothing is on its way that a new generation would make deaf
   (if (and (:panel db) (panel/settled? (:panel db)) (seq (panel/failed-tasks (:panel db))))
     (let [gen (inc (generation db))
           [retried started] (panel/pump (panel/retry (:panel db)) gen)]
       {:db (assoc db :panel-generation gen :panel retried)
        :fx (conj (calls started) (deadline gen))})
     {})))

(rf/reg-event-fx
 ::panel/leave
 (fn [{:keys [db]} _]
   {:db (-> db (assoc :panel-generation (inc (generation db))) (dissoc :panel))
    :fx (cancels (sort-by str (get-in db [:panel :inflight])))}))

(rf/reg-event-fx
 ::panel/search
 (fn [{:keys [db]} [_ text]]
   ;; the box is on the panel; typed anywhere else it is nothing
   (if (= :panel (get-in db [:route :name]))
     (let [params (route/panel-params {:show (some-> (get-in db [:route :params :show]) name) :q text})]
       {:db (assoc-in db [:route :params] params)
        :fx [[::router/replace (route/panel-path params)]]})
     {})))
