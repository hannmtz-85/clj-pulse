(ns pulse.spa.appointments-events
  "The events of the list of appointments and of the dialog that answers one (ADR-0029, points 3, 5 and 8): pure functions from the state
  and an event to the effects that follow, with what is decided in `pulse.spa.appointments` and `pulse.spa.appointment-command`. The list
  is loaded when the person comes to it (the `:start` of its route), its calls are made a few at a time as the ones before them are
  answered, and one deadline ends what is not back. Every answer carries the *generation* of the load it belongs to: the page that was
  left, or that was loaded again, does not hear the answers of the one before it. A new *epoch* is begun by each load, retry and reading
  again, and the deadline that was set for an epoch that is over does nothing.

  The dialog is a page of the address (`/appointments/:id/confirm`, `/reschedule` and `/cancel` are one page with the list, so that
  opening one loads nothing again): `::appointments/choose`, the `:update` of the route, makes the state say what the address says.

  **The workspace never decides that an appointment has changed.** The press of the button sends the command (`PATCH`) and the button is
  busy; the api answers; and then the three lists are read again and the dialog is closed when they are all back, so the appointment is
  where the api's lists put it. There is no optimistic update (point 3): a row that moved before the api said so would be a lie if the
  command failed. What the person is told is said to a screen reader as well, and the focus, which the button lost when it went busy and
  then went away with the dialog, goes to the row of the appointment, or back to the field or the button that needs the person.

  What each answer of the api means is `pulse.spa.appointment-command/verdict`: a 200 is done; a 409 (someone got there first) and a 400
  or 422 are the api's own words, in the dialog (beside a field when it is about one); a call that did not come back is *unconfirmed*, and
  the lists are read again to find out."
  (:require [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.route :as route]
            [pulse.spa.router :as router]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views]
            [re-frame.core :as rf]))

(rf/reg-cofx
 ::now
 (fn [cofx _]
   (assoc cofx :now (fmt/instant-text (js/Date.now)))))

(defn- generation [db]
  (get db :appointments-generation 0))

(defn- epoch [db]
  (get db :appointments-epoch 0))

(defn- current?
  "Whether an answer of the load `gen` is for the page that is open. Leaving the page begins a new generation, so what a page that was
  left is still waiting for is of a generation that is over."
  [db gen]
  (= gen (generation db)))

(defn- calls
  "The effects that make the calls of `specs`, in order."
  [specs]
  (mapv (fn [spec] [::fetch/request spec]) specs))

(defn- cancels
  "The effects that stop the calls of the tasks `tasks`."
  [tasks]
  (mapv (fn [task] [::fetch/cancel {:id (appointments/call-id task)}]) tasks))

(defn- deadline [gen ep]
  [:dispatch-later {:ms appointments/deadline-ms :dispatch [::appointments/deadline gen ep]}])

(defn- show-of
  "What the address narrows the list to."
  [db]
  (get-in db [:route :params :show] :all))

(defn- on-a-dialog?
  "Whether the address is the one of a dialog and not of the list."
  [db]
  (contains? route/appointment-dialogs (get-in db [:route :name])))

(defn- to-the-list
  "The effect that takes the person to the list, narrowed as it was, without adding to the history."
  [db]
  [::router/go {:name :appointments :query (route/appointments-query (show-of db)) :replace? true}])

(def ^:private nothing-to-fill-in
  "What the dialog is when it holds a message and no form: the appointment could not be loaded, is not there, or does not allow the command."
  #{:lost :missing :blocked})

(defn- opened
  "The effects when the dialog of `before` and `after` has just become something to fill in, or a message in place of it: the focus goes to
  the first field of the form, or to the way out of a message."
  [before after]
  (let [was (appointments/dialog-state before)
        is (appointments/dialog-state after)]
    (cond
      (and (not= :open was) (= :open is)) [[::router/focus (appointments/first-field (get-in after [:dialog :action]))]]
      (and (contains? nothing-to-fill-in is) (not (contains? nothing-to-fill-in was))) [[::router/focus "#appointment-close"]]
      :else [])))

(defn- finished
  "The state and the effects when the lists that were read again after the person answered an appointment have been adopted and the dialog
  was waiting for them: the dialog is gone, the address is the list, and the focus goes to the row of the appointment (or to the heading
  when the narrowing of the list does not show it)."
  [db after id]
  (let [shown? (appointments/row-shown? after (show-of db) id)]
    {:state (assoc after :dialog nil)
     :fx [(to-the-list db)
          [::router/focus (if shown? (command/row-selector id) "main h1")]]}))

(defn- pumped
  "The state with `after` in it (what `before` became with an answer), and the effects for the calls that can begin now. When the readings
  the person asked for after answering an appointment are all back, what they found is shown, and the dialog that waited for them is
  closed (see `finished`)."
  [db before after gen]
  (let [adopt? (appointments/adopt-now? after)
        adopted (cond-> after adopt? appointments/adopt)
        settling-id (when (and adopt? (= :settling (get-in adopted [:dialog :state]))) (get-in adopted [:dialog :id]))
        {:keys [state fx]} (if settling-id
                             (finished db adopted settling-id)
                             {:state adopted :fx (opened before adopted)})
        [state started] (appointments/pump state gen)]
    {:db (assoc db :appointments state)
     :fx (into (calls started) fx)}))

(defn- reading-again
  "The state and effects of the page reading the lists again, `state` being the page as it must be for that. `extra` are the effects to
  add."
  [db state now extra]
  (let [gen (generation db)
        ep (inc (epoch db))
        [state started] (appointments/pump (appointments/reread state now) gen)]
    {:db (assoc db :appointments-epoch ep :appointments state)
     :fx (-> (calls started) (conj (deadline gen ep)) (into extra))}))

;; ---------------------------------------------------------------------------
;; The page

(rf/reg-event-fx
 ::appointments/load
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} [_ params]]
   (let [gen (inc (generation db))
         ep (inc (epoch db))
         state (appointments/sync-dialog (appointments/start now) params)
         [state started] (appointments/pump state gen)]
     {:db (assoc db :appointments-generation gen :appointments-epoch ep :appointments state)
      :fx (conj (calls started) (deadline gen ep))})))

(rf/reg-event-fx
 ::appointments/succeeded
 (fn [{:keys [db]} [_ gen task response]]
   (if (current? db gen)
     (let [before (:appointments db)]
       (pumped db before (appointments/answer before task {:result :ok :body (:body response)}) gen))
     {})))

(rf/reg-event-fx
 ::appointments/failed
 (fn [{:keys [db]} [_ gen task failure]]
   (if (current? db gen)
     (let [before (:appointments db)]
       (pumped db before (appointments/answer before task {:result :failed :kind (:kind failure)}) gen))
     {})))

(rf/reg-event-fx
 ::appointments/deadline
 (fn [{:keys [db]} [_ gen ep]]
   ;; a page that has settled has nothing that is loading: `expire` leaves it as it is and has no call to stop; and a deadline of an
   ;; epoch that is over (a reading again was begun since) is not the deadline of what is loading now
   (if (and (current? db gen) (= ep (epoch db)))
     (let [before (:appointments db)
           expired (appointments/expire before)
           state (dissoc expired :cancelled-calls)
           ;; the readings that the person asked for show what they found, even if some of it did not come (when there were none, there
           ;; is nothing to show)
           after (appointments/adopt state)
           ;; a dialog that is settling is waiting for the lists that were read again, which are as back as they will be
           settling-id (when (= :settling (get-in after [:dialog :state])) (get-in after [:dialog :id]))
           {:keys [state fx]} (if settling-id
                                (finished db after settling-id)
                                {:state after :fx (opened before after)})]
       {:db (assoc db :appointments state)
        :fx (into (cancels (:cancelled-calls expired)) fx)})
     {})))

(rf/reg-event-fx
 ::appointments/retry
 (fn [{:keys [db]} _]
   ;; offered only once the page has settled, so that nothing is on its way that a new generation would make deaf
   (let [state (:appointments db)]
     (if (and state (appointments/settled? state) (seq (appointments/failed-tasks state)))
       (let [gen (inc (generation db))
             ep (inc (epoch db))
             [retried started] (appointments/pump (appointments/retry state) gen)]
         {:db (assoc db :appointments-generation gen :appointments-epoch ep :appointments retried)
          :fx (conj (calls started) (deadline gen ep))})
       {}))))

(rf/reg-event-fx
 ::appointments/leave
 (fn [{:keys [db]} _]
   ;; the command that may be on its way is not stopped: the api has it, and the page is read again the next time
   {:db (-> db
            (assoc :appointments-generation (inc (generation db)))
            (dissoc :appointments))
    :fx (cancels (sort-by str (get-in db [:appointments :inflight])))}))

;; ---------------------------------------------------------------------------
;; The dialog

(rf/reg-event-fx
 ::appointments/choose
 (fn [{:keys [db]} [_ params]]
   ;; the address changed within the page: the dialog is the one it says, and no more
   (if-let [before (:appointments db)]
     (let [after (appointments/sync-dialog before params)
           closed (when (and (:dialog before) (nil? (:dialog after))) (:dialog before))]
       {:db (assoc db :appointments after)
        :fx (cond-> (opened before after)
              ;; the person closed the dialog: the focus goes back to the button that opened it
              (and closed (not (command/busy? closed)))
              (conj [::router/focus (command/button-selector (:action closed) (:id closed))]))})
     {})))

(rf/reg-event-db
 ::appointments/edit
 (fn [db [_ field value]]
   (if (:appointments db)
     (update db :appointments appointments/edit field value)
     db)))

(rf/reg-event-fx
 ::appointments/submit
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} _]
   (if-let [before (:appointments db)]
     (let [[state call] (appointments/submit before now)
           field (when-not call (appointments/first-error (:dialog state)))]
       {:db (assoc db :appointments state)
        :fx (cond-> []
              call (conj [::fetch/request call])
              ;; what the person must put right is where the focus goes
              field (conj [::router/focus (str "#" (name field))]))})
     {})))

(rf/reg-event-fx
 ::appointments/close
 (fn [{:keys [db]} _]
   ;; the way out of the dialog by the keyboard: the same as the link "Not now", which is an address
   (if (on-a-dialog? db)
     {:fx [[::router/go {:name :appointments :query (route/appointments-query (show-of db))}]]}
     {})))

;; ---------------------------------------------------------------------------
;; What the api answered to the command

(defn- label-and-when
  "How the page names the appointment `id`, and when it is now, as the api answered (`body`, the appointment), for what a screen reader is
  told."
  [state id body]
  [(appointments/label-for state id)
   (:when (views/appointment-row (if (map? body) body {}) {}))])

(rf/reg-event-fx
 ::appointments/answered
 [(rf/inject-cofx ::now)]
 (fn [{:keys [db now]} [_ id action response]]
   (if-let [state (:appointments db)]
     (let [[label when-text] (label-and-when state id (:body response))
           ours? (and (= id (get-in state [:dialog :id])) (= action (get-in state [:dialog :action])))
           said (command/announcement :done action label when-text)
           state (cond-> (assoc state :said said)
                   ours? (update :dialog command/settling))]
       (reading-again db state now []))
     {})))

(defn- not-done
  "The state and effects when the command did not go through: the button is free again, with the words beside the fields (`errors`) and
  the `notice`, and the focus goes to what needs the person. `find-out?` reads the lists again, for what may have been done."
  [db now id action errors notice find-out?]
  (let [state (:appointments db)
        ours? (and state (= id (get-in state [:dialog :id])) (= action (get-in state [:dialog :action])))
        state (cond-> state ours? (update :dialog command/refused errors notice))
        field (when ours? (appointments/first-error (:dialog state)))
        focus [::router/focus (if field (str "#" (name field)) "#appointment-submit")]]
    (cond
      (nil? state) {}
      find-out? (reading-again db state now (if ours? [focus] []))
      :else {:db (assoc db :appointments state)
             :fx (if ours? [focus] [])})))

(defn- placed-beside-the-fields
  "The words of the api's `errors` for the fields of the form, as `pulse.spa.appointment-command/place` puts them: the start beside the
  day, unless the day the person has in the form (what they typed, or what it started with) is today at `now`."
  [state id action errors now]
  (let [dialog (:dialog state)
        values (when (and dialog (not= :cancel action))
                 (command/values (:appointment (appointments/find-appointment state id)) action (:now state) (:fields dialog)))]
    (command/place errors (:day values) now)))

(defn- by-verdict
  "What an answer that was not a success does, from what `pulse.spa.appointment-command/verdict` says it means."
  [{:keys [db now]} id action failure]
  (let [{:keys [result text errors]} (command/verdict failure)
        state (:appointments db)
        dialog (:dialog state)]
    (case result
      :ignore (if state
                {:db (assoc db :appointments (cond-> state (= id (:id dialog)) (update :dialog command/refused {} nil)))}
                {})
      :conflict (not-done db now id action {} {:kind :conflict :text text} true)
      :invalid (let [placed (placed-beside-the-fields state id action errors now)]
                 (if (seq placed)
                   (not-done db now id action placed nil false)
                   (not-done db now id action {} {:kind :invalid :text text} false)))
      :gone (not-done db now id action {} {:kind :gone} true)
      :forbidden (not-done db now id action {} {:kind :forbidden} false)
      :unconfirmed (not-done db now id action {} {:kind :unconfirmed} true))))

(rf/reg-event-fx
 ::appointments/refused
 [(rf/inject-cofx ::now)]
 (fn [cofx [_ id action failure]]
   (by-verdict cofx id action failure)))

(rf/reg-event-fx
 ::appointments/command-failed
 [(rf/inject-cofx ::now)]
 (fn [cofx [_ id action failure]]
   (by-verdict cofx id action failure)))
