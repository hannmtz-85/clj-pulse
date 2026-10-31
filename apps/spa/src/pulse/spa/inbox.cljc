(ns pulse.spa.inbox
  "The inbox of alerts of the clinical workspace, as far as it can be decided without a browser (ADR-0029, points 6 and 8): the calls
  that fill it, how it is read again while it is open, and what it shows once it is filled. Pure and `.cljc`, so that the JVM and Node
  run the same tests over it and the mock-up of the review is made from the same functions as the page.

  **Whose alerts they are, the api says.** `GET /api/alerts` answers the alerts of the caller's scope: a practitioner's patients, the
  administrator's all of them (ADR-0016). The workspace filters nothing itself. The alerts name their patient by a reference, so the
  list of patients is asked for as well, for the names, and, for each patient and each measurement that the rule of an active alert
  watches, the latest readings behind the alert (`pulse.view.alerts`).

  **The calls.** `start` queues the active alerts, the acknowledged ones and the patients, and the readings follow when the active
  alerts are back. At most `max-in-flight` calls are on their way and one deadline, `deadline-ms`, ends what is not back: a part that
  did not arrive is a failed part, `:timeout`, and **never an empty list**. Each part has its own state,
  `{:state :loading|:loaded|:failed ...}`, so that one can fail alone and the rest is shown.

  **What is shown does not move on its own.** While the inbox is open the workspace looks again every 30 seconds (`check`), but what it
  finds is kept apart, in `:latest`: if the alerts that need a decision are not the ones on the page, the page says so and the person
  decides when to see them (`adopt`). A list that rearranges itself as a hand is on its way to a button is how the wrong alert is
  acknowledged. What the person does themselves is shown at once: after acknowledging one, the workspace looks again and adopts what it
  finds (`after-ack`).

  The state is a map: `:now`, `:active`, `:acknowledged` and `:patients` (parts), `:evidence` (parts by `[patient-id measurement]`),
  `:latest` (the parts of the last look, by `:active` and `:acknowledged`), `:adopt?` (whether what the look finds is to be shown as
  soon as it is back), `:queue` (the tasks not started) and `:inflight` (a set of tasks). A task is `:active`, `:acknowledged`,
  `:patients`, `[:evidence patient-id measurement]` or `[:check :active]` and `[:check :acknowledged]`."
  (:require [pulse.spa.ack :as ack]
            [pulse.spa.panel :as panel]
            [pulse.spa.patients :as patients]
            [pulse.view.alerts :as alerts]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views]))

(def list-limit
  "How many alerts each list asks for: the most the api gives."
  100)

(def max-in-flight
  "How many calls are on their way at once."
  panel/max-in-flight)

(def deadline-ms
  "How long the inbox has, from the moment it begins (or looks again, or is tried again), for all of its calls: what is not back by then
  has failed."
  panel/deadline-ms)

(def loading {:state :loading})

;; ---------------------------------------------------------------------------
;; The calls

(defn- task-type [task]
  (if (vector? task) (first task) task))

(defn call-id
  "The id of the call for `task`, with which the effect stops it: a call of a kind is on its way at a time, a look again that is begun
  stops the one before it, and the inbox that is left stops the ones that are not back."
  [task]
  [:inbox task])

(defn- part-path
  "Where in the inbox the state of the part that `task` fills is."
  [task]
  (case (task-type task)
    (:active :acknowledged :patients) [task]
    :evidence [:evidence (vec (rest task))]
    :check [:latest (second task)]))

(defn- list-query [kind]
  {:status (case kind :active "active" :acknowledged "inactive")
   :sort "-since"
   :limit list-limit})

(defn request
  "The call for `task` of an inbox loading since `generation`, as the `:http` effect takes it. The answer is told to
  `:pulse.spa.inbox/succeeded` or `:pulse.spa.inbox/failed` with the generation and the task, so that an answer to an inbox that has
  been left is not taken for the answer of the one that is open."
  [task generation]
  (let [base {:method :get
              :id (call-id task)
              :on-success [:pulse.spa.inbox/succeeded generation task]
              :on-failure [:pulse.spa.inbox/failed generation task]}]
    (case (task-type task)
      (:active :acknowledged) (assoc base :path ["api" "alerts"] :query (list-query task))
      :check (assoc base :path ["api" "alerts"] :query (list-query (second task)))
      :patients (assoc base :path ["api" "patients"] :query {:limit panel/list-limit :sort "family"})
      :evidence (let [[_ id kind] task]
                  (assoc base
                         :path ["api" "patients" id "observations"]
                         :query {:measurement (name kind) :limit alerts/evidence-count})))))

(defn start
  "An inbox that begins to load at `now` (an instant, as text): the alerts and the patients are asked for first, and the readings
  behind the active alerts when those are back."
  [now]
  {:now now
   :active loading
   :acknowledged loading
   :patients loading
   :evidence {}
   :latest {}
   :adopt? false
   :queue [:active :acknowledged :patients]
   :inflight #{}})

(defn pump
  "Starts the tasks that are waiting while fewer than `max-in-flight` are on their way: `[inbox calls]`, the inbox with them in flight
  and the calls to make, in order."
  [inbox generation]
  (loop [inbox inbox
         calls []]
    (let [{:keys [queue inflight]} inbox]
      (if (and (seq queue) (< (count inflight) max-in-flight))
        (let [task (first queue)]
          (recur (-> inbox (assoc :queue (vec (rest queue))) (update :inflight conj task))
                 (conj calls (request task generation))))
        [inbox calls]))))

;; ---------------------------------------------------------------------------
;; What the api says of an alert

(defn alert-items
  "The alerts of a list the api answered (`{:total :items}`) that have an id, once each, in the order the api gave them."
  [body]
  (->> (:items body)
       (filter #(and (map? %) (views/text-of (:id %))))
       (reduce (fn [[seen found] alert]
                 (if (contains? seen (:id alert))
                   [seen found]
                   [(conj seen (:id alert)) (conj found alert)]))
               [#{} []])
       second))

(defn patient-id
  "The id in a reference to a patient, such as `Patient/pt-ana`, or nil for anything else. An id of dots only (`.` and `..` are ids by
  the letter of FHIR) is nil too: no path can carry it, and the route of the chart does not open it."
  [reference]
  (patients/id-of reference))

(defn- evidence-pairs
  "The patients and measurements whose latest readings the active alerts of `body` are told by: `[patient-id measurement]` once each,
  only for an alert whose rule is known and whose patient is named."
  [body]
  (->> (alert-items body)
       (keep (fn [alert]
               (when-let [kind (alerts/measurement (:text alert))]
                 (when-let [id (patient-id (:patient alert))]
                   [id kind]))))
       distinct
       vec))

(defn- with-evidence
  "The inbox with the readings behind its active alerts asked for: one call for each patient and measurement that it has not asked
  for yet. Those it has are kept."
  [inbox]
  (let [pairs (when (= :loaded (get-in inbox [:active :state])) (evidence-pairs (get-in inbox [:active :data])))
        missing (remove #(contains? (:evidence inbox) %) pairs)]
    (-> inbox
        (update :evidence into (map (fn [pair] [pair loading])) missing)
        (update :queue into (map (fn [[id kind]] [:evidence id kind])) missing))))

;; ---------------------------------------------------------------------------
;; The answers

(defn- well-formed?
  "Whether `body`, the answer to `task`, is what the api answers: another service could be on that address, or the api could have
  changed, and a part is not shown as empty for it."
  [task body]
  (case (task-type task)
    (:active :acknowledged :check :patients) (and (map? body) (sequential? (:items body)) (integer? (:total body)))
    :evidence (and (map? body) (sequential? (:items body)))))

(defn- stale?
  "Whether the answer to `task` is to a call that the inbox has left behind: the readings behind an alert that nothing asks about."
  [inbox task]
  (and (= :evidence (task-type task))
       (not (contains? (:evidence inbox) (vec (rest task))))))

(defn answer
  "The inbox when the call for `task` is answered with `outcome`: `{:result :ok :body ...}` or `{:result :failed :kind ...}`, as
  `pulse.spa.http/outcome` gives (a refusal counts as a failure here). An answer that is not what the api answers is a failure of kind
  `:unexpected`. When the active alerts are back the readings behind them are queued."
  [inbox task outcome]
  (let [inbox (update inbox :inflight disj task)]
    (if (stale? inbox task)
      inbox
      (let [ok? (and (= :ok (:result outcome)) (well-formed? task (:body outcome)))
            part (cond ok? {:state :loaded :data (:body outcome)}
                       (= :ok (:result outcome)) {:state :failed :kind :unexpected}
                       :else {:state :failed :kind (or (:kind outcome) :unexpected)})]
        (cond-> (assoc-in inbox (part-path task) part)
          (and ok? (= :active task)) with-evidence)))))

(defn settled?
  "Whether nothing is waiting or on its way and no part is loading."
  [inbox]
  (and (empty? (:queue inbox))
       (empty? (:inflight inbox))
       (not-any? #(= :loading (:state %))
                 (concat [(:active inbox) (:acknowledged inbox) (:patients inbox)] (vals (:evidence inbox))))))

(defn expire
  "The inbox when the deadline has come: every part that is still loading has failed, of kind `:timeout`, a look that is not back has
  failed the same way, and nothing waits or is on its way any more. The tasks that were on their way are in `:cancelled` (in the order of
  their names), for the calls to be stopped."
  [inbox]
  (let [timed-out (fn [part] (if (= :loading (:state part)) {:state :failed :kind :timeout} part))
        looking (filter #(= :check (task-type %)) (concat (:queue inbox) (:inflight inbox)))]
    (-> (reduce (fn [inbox task] (assoc-in inbox (part-path task) {:state :failed :kind :timeout})) inbox looking)
        (update :active timed-out)
        (update :acknowledged timed-out)
        (update :patients timed-out)
        (update :evidence update-vals timed-out)
        (assoc :cancelled (vec (sort-by str (:inflight inbox))) :queue [] :inflight #{} :adopt? false))))

(defn failed-tasks
  "The tasks whose part has failed, which a retry asks for again: the lists, the patients, the readings and the looks again."
  [inbox]
  (let [failed? #(= :failed (:state %))]
    (vec (concat
          (for [kind [:patients :active :acknowledged]
                :when (failed? (get inbox kind))]
            kind)
          (for [[[id kind] part] (sort-by (comp str key) (:evidence inbox))
                :when (failed? part)]
            [:evidence id kind])
          (for [kind [:active :acknowledged]
                :when (failed? (get-in inbox [:latest kind]))]
            [:check kind])))))

(defn retry
  "The inbox with what failed asked for again: the failed parts loading and their tasks waiting. A look again that failed stays as it
  was until the new one is back."
  [inbox]
  (reduce (fn [inbox task]
            (-> inbox
                (cond-> (not= :check (task-type task)) (assoc-in (part-path task) loading))
                (update :queue conj task)))
          inbox
          (failed-tasks inbox)))

;; ---------------------------------------------------------------------------
;; Looking again

(defn check
  "The inbox looking at the alerts again, at `now`: both lists are asked for, and what comes back is kept in `:latest` and not shown
  until the person says so (`adopt`). A look that is on its way is stopped by the new one (they share an id)."
  [inbox now]
  (let [tasks [[:check :active] [:check :acknowledged]]]
    (-> inbox
        (assoc :now now)
        (update :inflight #(apply disj % tasks))
        (update :queue (fn [queue] (into (vec (remove (set tasks) queue)) tasks))))))

(defn after-ack
  "The inbox looking again after the person acknowledged an alert, and showing what it finds as soon as it is back: what they did is
  what they expect to see."
  [inbox now]
  (assoc (check inbox now) :adopt? true))

(defn checks-done?
  "Whether no look again is waiting or on its way."
  [inbox]
  (not-any? #(= :check (task-type %)) (concat (:queue inbox) (:inflight inbox))))

(defn adopt-now?
  "Whether what the looks again found is to be shown now: the person asked for it (`:adopt?`) and every look is back."
  [inbox]
  (boolean (and (:adopt? inbox) (checks-done? inbox))))

(defn adopt
  "The inbox showing what the last look found: each list that came back replaces the one shown, and the readings behind the alerts
  that are new are queued. A look that failed is left where it is, for the page to say so."
  [inbox]
  (let [latest (:latest inbox)
        coming (filter #(= :loaded (get-in latest [% :state])) [:active :acknowledged])]
    (-> (reduce (fn [inbox kind] (-> inbox (assoc kind (get latest kind)) (update :latest dissoc kind))) inbox coming)
        (assoc :adopt? false)
        with-evidence)))

(defn focus-target
  "The selector of where the focus goes once the person has acted on an alert and what the inbox found is shown: the button of an alert
  that is still among those that need a decision and whose acknowledgement was not done (`acks`, see `pulse.spa.ack`), so that they can
  try again, or else the heading of the alerts that need a decision, since the card they acted on is gone and so is the button."
  [inbox acks]
  (if-let [id (ack/first-failed acks (map :id (alert-items (get-in inbox [:active :data]))))]
    (str "#ack-" id)
    "#needs"))

(defn- identity-of
  "What tells one list of alerts from another: how many there are in all and which of them are in it."
  [part]
  [(:total (:data part)) (set (map :id (alert-items (:data part))))])

(defn changed?
  "Whether the last look found alerts that need a decision other than the ones the page shows."
  [inbox]
  (let [shown (:active inbox)
        latest (get-in inbox [:latest :active])]
    (and (= :loaded (:state shown))
         (= :loaded (:state latest))
         (not= (identity-of shown) (identity-of latest)))))

(defn check-failed?
  "Whether the last look at the alerts failed in some part."
  [inbox]
  (boolean (some #(= :failed (get-in inbox [:latest % :state])) [:active :acknowledged])))

;; ---------------------------------------------------------------------------
;; What the inbox shows

(def not-begun
  "The inbox as the page shows it before it has begun to load, which is what it shows for the moment between coming to it and the first of
  its calls: every part is on its way, and there is no moment yet."
  {:now nil :active loading :acknowledged loading :patients loading :evidence {} :latest {} :queue [] :inflight #{}})

(defn- label-of [text name]
  (if name (str text " · " name) text))

(defn- evidence-of
  "The readings behind an alert with this `text` for the patient `id`: nil for an alert whose rule is not known (nothing says what it
  watches), `{:state :loading}`, `{:state :failed}` or `{:state :loaded :lines [...]}`."
  [inbox text id]
  (when-let [kind (alerts/measurement text)]
    (when id
      (let [part (get-in inbox [:evidence [id kind]])]
        (case (:state part)
          :loaded {:state :loaded :lines (alerts/evidence kind (get-in part [:data :items]) (:now inbox))}
          :failed {:state :failed}
          {:state :loading})))))

(defn- active-card [inbox names acks alert]
  (let [text (or (views/text-of (:text alert)) "Alert")
        id (patient-id (:patient alert))
        name (patients/name-of names id)]
    {:id (:id alert)
     :patient-id id
     :name name
     :text text
     :label (label-of text name)
     :tone (alerts/tone text)
     :since-phrase (when (fmt/instant? (:since alert)) (fmt/when-phrase (:since alert) (:now inbox)))
     :description (alerts/description text)
     :evidence (evidence-of inbox text id)
     :ack (get acks (:id alert))}))

(defn- acknowledged-row [inbox names alert]
  (let [text (or (views/text-of (:text alert)) "Alert")
        id (patient-id (:patient alert))
        name (patients/name-of names id)]
    {:id (:id alert)
     :patient-id id
     :name name
     :text text
     :label (label-of text name)
     :since-phrase (when (fmt/instant? (:since alert)) (fmt/when-phrase (:since alert) (:now inbox)))
     :until-phrase (when (fmt/instant? (:until alert)) (fmt/when-phrase (:until alert) (:now inbox)))}))

(defn- section
  "A list of the inbox as it is shown: `{:state :loading}` while it or the names are on their way, `{:state :lost}` when it could not
  be had, and `{:state :loaded :items [...] :total n}` otherwise."
  [inbox kind make]
  (let [part (get inbox kind)]
    (cond
      (or (= :loading (:state part)) (= :loading (get-in inbox [:patients :state]))) {:state :loading}
      (not= :loaded (:state part)) {:state :lost}
      :else {:state :loaded
             :items (mapv make (alert-items (:data part)))
             :total (:total (:data part))})))

(defn- alerts-phrase [n]
  (cond
    (zero? n) "No alert needs a decision"
    (= 1 n) "1 alert needs a decision"
    :else (str n " alerts need a decision")))

(defn model
  "What the inbox shows, from its state `inbox` (`not-begun` before it begins), what it is narrowed to (`show`: `:all`, `:active` or
  `:acknowledged`), the state of the acknowledgements (`acks`, a map from the id of an alert to `{:state :sending|:settling|:failed
  :message}`), what a screen reader was last told of them (`said`) and whether the person is the administrator, who sees every
  patient's alerts:
    `:title` and `:description`, `:show`, `:filters` (`{:id :label :count :current?}`, the counts only when the lists are in),
    `:active` (`{:state ... :items [cards] :total}`, a card being `{:id :patient-id :name :text :label :tone :since-phrase :description
    :evidence :ack}`) and `:acknowledged` (the same, with rows `{:id :patient-id :name :text :label :since-phrase :until-phrase}`),
    `:notices` (`{:id :changed}`, `{:id :check-failed}`, `{:id :names-failed}` and `{:id :evidence-failed :count n}`), `:settled?` and
    `:live`, the words a screen reader is told."
  [inbox {:keys [show acks said] :or {show :all}} admin?]
  (let [names (patients/names (:patients inbox))
        active (section inbox :active #(active-card inbox names acks %))
        acknowledged (section inbox :acknowledged #(acknowledged-row inbox names %))
        settled? (settled? inbox)
        total-of (fn [section] (when (= :loaded (:state section)) (:total section)))
        evidence-failed (count (filter #(= :failed (:state %)) (vals (:evidence inbox))))
        moved? (changed? inbox)
        look-failed? (check-failed? inbox)]
    {:title "Alerts"
     :description (if admin?
                    "Raised by the rules from the readings of the clinic's patients."
                    "Raised by the rules from the readings of your patients.")
     :show show
     :filters [{:id :active :label "Active" :count (total-of active) :current? (= :active show)}
               {:id :acknowledged :label "Acknowledged" :count (total-of acknowledged) :current? (= :acknowledged show)}
               {:id :all :label "All"
                :count (when (and (total-of active) (total-of acknowledged)) (+ (total-of active) (total-of acknowledged)))
                :current? (= :all show)}]
     :active active
     :acknowledged acknowledged
     :notices (cond-> []
                moved? (conj {:id :changed})
                look-failed? (conj {:id :check-failed})
                (and settled? (= :failed (get-in inbox [:patients :state]))) (conj {:id :names-failed})
                (and settled? (pos? evidence-failed)) (conj {:id :evidence-failed :count evidence-failed}))
     :settled? settled?
     :live (cond
             (some? said) said
             moved? "The alerts have changed since you opened this page"
             look-failed? "We could not check for new alerts"
             (not settled?) "Loading the alerts"
             (= :failed (get-in inbox [:active :state])) "The alerts could not be loaded"
             :else (alerts-phrase (count (:items active))))}))
