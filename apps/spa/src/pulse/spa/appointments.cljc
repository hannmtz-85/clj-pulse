(ns pulse.spa.appointments
  "The list of appointments of the clinical workspace, as far as it can be decided without a browser (ADR-0029, points 3, 6 and 8): the
  calls that fill it, how it is read again when the person has answered an appointment, the dialog that answers one, and what the page
  shows once it is filled. Pure and `.cljc`, so that the JVM and Node run the same tests over it and the mock-up of the review is made
  from the same functions as the page.

  **Whose appointments they are, the api says.** `GET /api/appointments` answers the appointments of the caller's scope: a practitioner's
  own, the administrator's all of them (ADR-0015). The workspace filters nothing itself. The appointments name their patient by a
  reference, so the list of patients is asked for as well, for the names.

  **The calls.** `start` queues the three lists, the requests to answer (`proposed`), the appointments that are booked and the ones that
  were cancelled, up to a hundred each, and the patients. At most `max-in-flight` calls are on their way and one deadline,
  `deadline-ms`, ends what is not back: a part that did not arrive is a failed part, `:timeout`, and **never an empty list**. Each part
  has its own state, `{:state :loading|:loaded|:failed ...}`, so that one can fail alone and the rest is shown. **The lists are read when
  the page is opened and nothing repeats it** (point 6: nothing else polls).

  **What is shown does not move until the person has answered an appointment.** The workspace never decides that an appointment has
  changed: the command is sent, the api answers, and then the three lists are read again (`reread`) and what they say is shown all at
  once (`adopt`), when they are all back or at the deadline: the appointment moves from the requests to the booked ones because the
  api's lists say so. Until then the button of the dialog is busy.

  The dialog that answers an appointment is part of the state (`:dialog`, see `pulse.spa.appointment-command`): the address says which
  appointment and which command (`sync-dialog`), and what the person types stays here.

  The state is a map: `:now`, `:requests`, `:booked`, `:cancelled` and `:patients` (parts), `:latest` (the parts of the last reading again,
  by `:requests`, `:booked` and `:cancelled`), `:adopt?` (whether what that finds is to be shown as soon as it is back), `:queue` (the
  tasks not started), `:inflight` (a set of tasks), `:dialog` and `:said` (what a screen reader was last told). A task is `:requests`,
  `:booked`, `:cancelled`, `:patients` or `[:check kind]`."
  (:require [clojure.string :as str]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.panel :as panel]
            [pulse.spa.patients :as patients]
            [pulse.view.views :as views]))

(def list-limit
  "How many appointments each list asks for: the most the api gives."
  100)

(def max-in-flight
  "How many calls are on their way at once."
  panel/max-in-flight)

(def deadline-ms
  "How long the page has, from the moment it begins (or reads again, or is tried again), for all of its calls: what is not back by then
  has failed."
  panel/deadline-ms)

(def loading {:state :loading})

(def kinds
  "The lists, in the order the page has them: the requests to answer, the appointments that are booked, the ones that were cancelled."
  [:requests :booked :cancelled])

(def ^:private statuses
  {:requests "proposed" :booked "booked" :cancelled "cancelled"})

;; ---------------------------------------------------------------------------
;; The calls

(defn- task-type [task]
  (if (vector? task) (first task) task))

(defn call-id
  "The id of the call for `task`, with which the effect stops it: a call of a kind is on its way at a time, a reading again that is begun
  stops the one before it, and the page that is left stops the ones that are not back."
  [task]
  [:appointments task])

(defn- part-path
  "Where in the state the part that `task` fills is."
  [task]
  (case (task-type task)
    (:requests :booked :cancelled :patients) [task]
    :check [:latest (second task)]))

(defn- list-query
  "The query of the list of `kind`: the soonest first, and the cancelled ones the newest first."
  [kind]
  {:status (statuses kind)
   :sort (if (= :cancelled kind) "-start" "start")
   :limit list-limit})

(defn request
  "The call for `task` of a page loading since `generation`, as the `:http` effect takes it. The answer is told to
  `:pulse.spa.appointments/succeeded` or `:pulse.spa.appointments/failed` with the generation and the task, so that an answer to a page
  that has been left is not taken for the answer of the one that is open."
  [task generation]
  (let [base {:method :get
              :id (call-id task)
              :on-success [:pulse.spa.appointments/succeeded generation task]
              :on-failure [:pulse.spa.appointments/failed generation task]}]
    (case (task-type task)
      (:requests :booked :cancelled) (assoc base :path ["api" "appointments"] :query (list-query task))
      :check (assoc base :path ["api" "appointments"] :query (list-query (second task)))
      :patients (assoc base :path ["api" "patients"] :query {:limit panel/list-limit :sort "family"}))))

(defn start
  "A page that begins to load at `now` (an instant, as text): the lists and the patients are asked for."
  [now]
  {:now now
   :requests loading
   :booked loading
   :cancelled loading
   :patients loading
   :latest {}
   :adopt? false
   :queue [:requests :booked :cancelled :patients]
   :inflight #{}
   :dialog nil
   :said nil})

(defn pump
  "Starts the tasks that are waiting while fewer than `max-in-flight` are on their way: `[state calls]`, the state with them in flight and
  the calls to make, in order."
  [state generation]
  (loop [state state
         calls []]
    (let [{:keys [queue inflight]} state]
      (if (and (seq queue) (< (count inflight) max-in-flight))
        (let [task (first queue)]
          (recur (-> state (assoc :queue (vec (rest queue))) (update :inflight conj task))
                 (conj calls (request task generation))))
        [state calls]))))

;; ---------------------------------------------------------------------------
;; The answers

(defn- well-formed?
  "Whether `body`, the answer to a call, is what the api answers: another service could be on that address, or the api could have
  changed, and a part is not shown as empty for it."
  [body]
  (and (sequential? (:items body)) (integer? (:total body))))

(defn answer
  "The page when the call for `task` is answered with `outcome`: `{:result :ok :body ...}` or `{:result :failed :kind ...}`, as
  `pulse.spa.http/outcome` gives (a refusal counts as a failure here). An answer that is not what the api answers is a failure of kind
  `:unexpected`."
  [state task outcome]
  (let [ok? (and (= :ok (:result outcome)) (well-formed? (:body outcome)))
        part (cond ok? {:state :loaded :data (:body outcome)}
                   (= :ok (:result outcome)) {:state :failed :kind :unexpected}
                   :else {:state :failed :kind (or (:kind outcome) :unexpected)})]
    (-> state
        (update :inflight disj task)
        (assoc-in (part-path task) part))))

(defn settled?
  "Whether nothing is waiting or on its way and no part is loading."
  [state]
  (and (empty? (:queue state))
       (empty? (:inflight state))
       (not-any? #(= :loading (:state (get state %))) (conj kinds :patients))))

(defn expire
  "The page when the deadline has come: every part that is still loading has failed, of kind `:timeout`, a reading again that is not back
  has failed the same way, and nothing waits or is on its way any more. The tasks that were on their way are in `:cancelled` (in the order
  of their names), for the calls to be stopped."
  [state]
  (let [timed-out (fn [part] (if (= :loading (:state part)) {:state :failed :kind :timeout} part))
        waiting (concat (:queue state) (:inflight state))]
    (-> (reduce (fn [state task] (assoc-in state (part-path task) {:state :failed :kind :timeout})) state waiting)
        (update :requests timed-out)
        (update :booked timed-out)
        (update :cancelled timed-out)
        (update :patients timed-out)
        (assoc :cancelled-calls (vec (sort-by str (:inflight state))) :queue [] :inflight #{} :adopt? false))))

(defn failed-tasks
  "The tasks whose part has failed, which a retry asks for again: the patients, the lists, and the readings again."
  [state]
  (let [failed? #(= :failed (:state %))]
    (vec (concat
          (for [kind (cons :patients kinds)
                :when (failed? (get state kind))]
            kind)
          (for [kind kinds
                :when (failed? (get-in state [:latest kind]))]
            [:check kind])))))

(defn retry
  "The page with what failed asked for again: the failed parts loading and their tasks waiting. A reading again that failed stays as it
  was until the new one is back, and **is shown as soon as it is**: the person who tries again asks to see what the lists say now, as
  the one who answered an appointment did (without that, what came back would be kept apart for ever and the notice that said the list
  could not be updated would go, leaving a list that is out of date and says nothing)."
  [state]
  (let [tasks (failed-tasks state)]
    (cond-> (reduce (fn [state task]
                      (-> state
                          (cond-> (not= :check (task-type task)) (assoc-in (part-path task) loading))
                          (update :queue conj task)))
                    state
                    tasks)
      (some #(= :check (task-type %)) tasks) (assoc :adopt? true))))

;; ---------------------------------------------------------------------------
;; Reading the lists again

(defn reread
  "The page reading the three lists again at `now`, once the person has answered an appointment, and showing what it finds as soon as it
  is back. A reading that is on its way is stopped by the new one (they share an id)."
  [state now]
  (let [tasks (mapv (fn [kind] [:check kind]) kinds)]
    (-> state
        (assoc :now now :adopt? true)
        (update :inflight #(apply disj % tasks))
        (update :queue (fn [queue] (into (vec (remove (set tasks) queue)) tasks))))))

(defn checks-done?
  "Whether no reading again is waiting or on its way."
  [state]
  (not-any? #(= :check (task-type %)) (concat (:queue state) (:inflight state))))

(defn adopt-now?
  "Whether what the readings again found is to be shown now: the person asked for it (`:adopt?`) and every reading is back."
  [state]
  (boolean (and (:adopt? state) (checks-done? state))))

(defn adopt
  "The page showing what the last reading found: each list that came back replaces the one shown. A reading that failed is left where it
  is, for the page to say so."
  [state]
  (let [latest (:latest state)
        coming (filter #(= :loaded (get-in latest [% :state])) kinds)]
    (-> (reduce (fn [state kind] (-> state (assoc kind (get latest kind)) (update :latest dissoc kind))) state coming)
        (assoc :adopt? false))))

(defn check-failed?
  "Whether the last reading again failed in some list."
  [state]
  (boolean (some #(= :failed (get-in state [:latest % :state])) kinds)))

;; ---------------------------------------------------------------------------
;; The appointments

(defn appointment-items
  "The appointments of a list the api answered (`{:total :items}`) that have an id, once each, in the order the api gave them."
  [body]
  (->> (:items body)
       (filter #(views/text-of (:id %)))
       (reduce (fn [[seen found] appointment]
                 (if (contains? seen (:id appointment))
                   [seen found]
                   [(conj seen (:id appointment)) (conj found appointment)]))
               [#{} []])
       second))

(defn find-appointment
  "The appointment `id` among the lists that are loaded: `{:status :found :appointment item}`, or why it is not to be had:
  `{:status :loading}` while a list is on its way, `{:status :lost}` when a list could not be loaded (it may be in that one), and
  `{:status :missing}` when every list is in and it is in none (`:cut?` says that a list holds only the first of more, so that it may be
  beyond them)."
  [state id]
  (let [parts (map #(get state %) kinds)
        found (some (fn [part] (first (filter #(= id (:id %)) (appointment-items (:data part))))) parts)]
    (cond
      found {:status :found :appointment found}
      (some #(= :loading (:state %)) parts) {:status :loading}
      (some #(= :failed (:state %)) parts) {:status :lost}
      :else {:status :missing
             :cut? (boolean (some (fn [part] (< (count (appointment-items (:data part))) (:total (:data part)))) parts))})))

;; ---------------------------------------------------------------------------
;; The dialog

(defn sync-dialog
  "The page with the dialog the address says: `params` is `{:id :action}` of the route (the list has neither: no dialog). A dialog that is
  already the one the address says is left as it is, with what was typed in it."
  [state {:keys [id action]}]
  (cond
    (not (and id action)) (assoc state :dialog nil)
    (and (= id (get-in state [:dialog :id])) (= action (get-in state [:dialog :action]))) state
    :else (assoc state :dialog (command/opened action id))))

(defn edit
  "The page once the person typed `value` into `field` of the dialog (a keyword: `:day`, `:from`, `:until` or `:why`)."
  [state field value]
  (if (:dialog state)
    (update state :dialog command/typed field value)
    state))

(declare dialog-view)

(defn dialog-state
  "What the dialog is, as the page shows it: `:loading`, `:lost`, `:missing`, `:blocked` or `:open`, or nil when there is none."
  [state]
  (:state (dialog-view state)))

(defn dialog-open?
  "Whether the dialog is there for the person to fill in: the appointment it is about is found and the command is possible."
  [state]
  (= :open (dialog-state state)))

(defn submit
  "The press of the button of the dialog at `now` (the moment of the press, an instant as text): `[state call]`. Nothing is sent when
  there is no dialog, when it is busy, or when the appointment is not there or does not allow the command (the dialog is then a message
  with no button to press, and the state is left as it is): then `call` is nil. Nothing is sent either when what was typed cannot be read
  or the lifecycle refuses it: then `call` is nil and the dialog says why beside the fields. Otherwise the dialog is `:sending` and
  `call` is the command to send (see `pulse.spa.appointment-command/request`)."
  [state now]
  (let [{:keys [action id] :as dialog} (:dialog state)
        {:keys [status appointment]} (when dialog (find-appointment state id))]
    (if (or (nil? dialog) (command/busy? dialog) (not= :found status) (command/blocker appointment action now))
      [state nil]
      (let [typed (command/values appointment action (:now state) (:fields dialog))
            result (command/submission appointment action now typed)]
        ;; the status was judged above, so `submission` has a body or the words beside the fields and never a conflict
        (if (:body result)
          [(update state :dialog command/sending) (command/request id (:body result))]
          [(update state :dialog command/refused (:errors result) nil) nil])))))

(defn field-order
  "The fields of the dialog in the order they are on the screen; none for what is not a command."
  [action]
  (case action
    (:confirm :reschedule) [:day :from :until]
    :cancel [:why]
    []))

(defn first-error
  "The field of the dialog that has words beside it and comes first on the screen, or nil."
  [dialog]
  (first (filter #(contains? (:errors dialog) %) (field-order (:action dialog)))))

(defn first-field
  "The selector of the field of the dialog that takes the focus when it opens: the first one."
  [action]
  (str "#" (name (first (field-order action)))))

;; ---------------------------------------------------------------------------
;; What the page shows

(def not-begun
  "The page as it is shown before it has begun to load, for the moment between coming to it and the first of its calls: every part is on
  its way, and there is no moment yet."
  {:now nil :requests loading :booked loading :cancelled loading :patients loading :latest {} :queue [] :inflight #{} :dialog nil :said nil})

(defn- label-of
  "The words that name an appointment in what is said of it: what it is for and whose it is."
  [description name]
  (str/join " · " (keep identity [description name])))

(defn- row
  "An appointment as the page shows it: the row of `pulse.view.views/appointment-row` and `:patient-id`, `:name` (as the clinic has it, or
  the number of a patient the list does not hold), `:label` and `:actions`, the commands the lifecycle lets a clinician take on it."
  [names now appointment]
  (let [row (views/appointment-row appointment {})
        patient-id (patients/id-of (:patient appointment))
        name (patients/name-of names patient-id)]
    (assoc row
           :patient-id patient-id
           :name name
           :label (label-of (views/text-of (:description appointment)) name)
           :actions (command/available appointment now))))

(defn label-for
  "The words that name the appointment `id` in what is said of it, as the page names it (`Medication review · Ana García`), or nil when it
  is not among the lists."
  [state id]
  (let [{:keys [status appointment]} (find-appointment state id)]
    (when (= :found status)
      (:label (row (patients/names (:patients state)) (:now state) appointment)))))

(defn row-shown?
  "Whether the row of the appointment `id` is on the page when it is narrowed to `show` (`:all`, `:requests`, `:booked` or `:cancelled`):
  the list it is in is one of those the page shows."
  [state show id]
  (boolean (some (fn [kind]
                   (and (or (= :all show) (= kind show))
                        (some #(= id (:id %)) (appointment-items (get-in state [kind :data])))))
                 kinds)))

(defn- section
  "A list as it is shown: `{:state :loading}` while it or the names are on their way, `{:state :lost}` when it could not be had, and
  `{:state :loaded :items [rows] :total n}` otherwise."
  [state kind names]
  (let [part (get state kind)]
    (cond
      (or (= :loading (:state part)) (= :loading (get-in state [:patients :state]))) {:state :loading}
      (not= :loaded (:state part)) {:state :lost}
      :else {:state :loaded
             :items (mapv #(row names (:now state) %) (appointment-items (:data part)))
             :total (:total (:data part))})))

(defn- requests-phrase [n]
  (cond
    (zero? n) "No request waits for an answer"
    (= 1 n) "1 request waits for an answer"
    :else (str n " requests wait for an answer")))

(defn slot-hint
  "What the field for the end of a visit says about how long it may be, from the `limits` of the lifecycle (`:slot-minutes`, the least and
  the most): `A visit lasts from 5 minutes to 4 hours.` A whole number of hours is said in hours."
  [limits]
  (let [[least most] (:slot-minutes limits)
        text (fn [minutes]
               (if (and (pos? minutes) (zero? (mod minutes 60)))
                 (let [hours (quot minutes 60)] (str hours (if (= 1 hours) " hour" " hours")))
                 (str minutes " minutes")))]
    (str "A visit lasts from " (text least) " to " (text most) ".")))

(defn- dialog-view
  "The dialog as the page shows it: why it cannot be filled in yet or at all (`:loading`, `:lost`, `:missing` and `:blocked`, the last
  with the lifecycle's own words), or `:open` with the appointment it is about, the values of its fields, the words beside them and what
  went wrong that is not about one."
  [state]
  (when-let [{:keys [action id errors notice] :as dialog} (:dialog state)]
    (let [{:keys [status appointment cut?]} (find-appointment state id)
          base {:action action :id id :busy? (command/busy? dialog)}]
      (case status
        :loading (assoc base :state :loading :title "Loading the appointment")
        :lost (assoc base :state :lost :title "The appointment could not be loaded")
        :missing (assoc base :state :missing :title "We cannot find that appointment"
                        :message (if cut?
                                   "It may be beyond the first 100 of its kind, which are the ones shown."
                                   "It may have been removed."))
        :found (let [names (patients/names (:patients state))
                     row (row names (:now state) appointment)
                     blocked (command/blocker appointment action (:now state))
                     summary (select-keys row [:description :name :when :asked :reason])]
                 (if blocked
                   (assoc base :state :blocked :title (command/title action appointment) :message blocked :summary summary)
                   (assoc base
                          :state :open
                          :title (command/title action appointment)
                          :submit-label (command/submit-label action appointment)
                          :icon (get-in command/commands [action :icon])
                          :summary summary
                          :given (get (patients/given-names (:patients state)) (:patient-id row))
                          :label (:label row)
                          :fields (command/values appointment action (:now state) (:fields dialog))
                          :errors errors
                          :notice (some-> notice (command/notice-words action))
                          :slot-hint (slot-hint lifecycle/limits)
                          :reason-length (:text-length lifecycle/limits))))))))

(defn model
  "What the page shows, from its state `state` (`not-begun` before it begins), what it is narrowed to (`show`: `:all`, `:requests`,
  `:booked` or `:cancelled`) and whether the person is the administrator, who sees every patient's appointments:
    `:title` and `:description`, `:show`, `:filters` (`{:id :label :count :current?}`, the counts only when the lists are in),
    `:requests`, `:booked` and `:cancelled` (`{:state ... :items [rows] :total}`, a row being as `row` makes it),
    `:notices` (`{:id :check-failed}` and `{:id :names-failed}`), `:settled?`, `:dialog` (see `dialog-view`; nil when none is open)
    and `:live`, the words a screen reader is told."
  [state {:keys [show] :or {show :all}} admin?]
  (let [names (patients/names (:patients state))
        sections (into {} (map (fn [kind] [kind (section state kind names)])) kinds)
        totals (into {} (map (fn [kind] [kind (when (= :loaded (:state (get sections kind))) (:total (get sections kind)))])) kinds)
        settled? (settled? state)
        every-total? (every? some? (vals totals))
        all-failed? (every? #(= :failed (:state (get state %))) kinds)
        requests-total (:requests totals)]
    {:title "Appointments"
     :description (if admin?
                    "Requests to answer and visits to come, for the clinic's patients."
                    "Requests to answer and visits to come.")
     :show show
     :filters (into [{:id :all :label "All" :count (when every-total? (reduce + (vals totals))) :current? (= :all show)}]
                    (map (fn [[kind label]] {:id kind :label label :count (get totals kind) :current? (= kind show)}))
                    [[:requests "Requests"] [:booked "Booked"] [:cancelled "Cancelled"]])
     :requests (:requests sections)
     :booked (:booked sections)
     :cancelled (:cancelled sections)
     :notices (cond-> []
                (check-failed? state) (conj {:id :check-failed})
                (and settled? (= :failed (get-in state [:patients :state]))) (conj {:id :names-failed}))
     :settled? settled?
     :dialog (dialog-view state)
     :live (cond
             (some? (:said state)) (:said state)
             (not settled?) "Loading the appointments"
             all-failed? "The appointments could not be loaded"
             (check-failed? state) "We could not update the list of appointments"
             (some? requests-total) (requests-phrase requests-total)
             :else "Some appointments could not be loaded")}))
