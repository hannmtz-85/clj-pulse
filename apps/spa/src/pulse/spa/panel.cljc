(ns pulse.spa.panel
  "The panel of the clinical workspace, as far as it can be decided without a browser (ADR-0029, point 6): the fan-out that fills it,
  and what it shows once it is filled. Pure and `.cljc`, so that the JVM and Node run the same tests over it and the mock-up of the
  review is made from the same functions as the page.

  **The fan-out.** `GET /api/patients` says whom the panel is of; for each patient the summary says their latest readings, alerts
  and appointments, and the series of their *headline* reading (the one furthest from normal) says what the sparkline draws; and the
  inbox of alerts and the list of requests for a visit are asked once. That is a lot of calls from a browser, so `pump` starts them at
  most `max-in-flight` at a time (the limit a browser puts on one server anyway), and one deadline, `deadline-ms`, ends what is not
  back: a part that did not arrive is a failed part, `:timeout`, and **never an empty list**. Each part has its own state,
  `{:state :loading|:loaded|:failed ...}`, so that one patient can fail alone.

  **The order.** Rows do not jump while a person reads: until the panel *settles* (every part is back or has failed) they stay in the
  order of the list, and then, once, they are put in the order of urgency (`urgency-order`): the worst latest reading, then the most
  active alerts, then the family name, and a row that did not arrive last. A retry settles again and puts the order right again.

  The state is a map: `:now`, `:list`, `:rows` (by id of the patient: `:summary` and `:series`), `:inbox` (`:active`, `:acknowledged`
  and `:requests`), `:queue` (the tasks not started), `:inflight` (a set of tasks) and `:order` (the ids, once it has settled).
  A task is `:list`, `:active`, `:acknowledged`, `:requests`, `[:summary id]` or `[:series id kind]`."
  (:require [clojure.string :as str]
            [pulse.ui.tone :as tone]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views]))

(def list-limit
  "How many patients the panel asks for: the most the api gives."
  100)

(def series-days
  "How many days the sparkline of a row draws."
  14)

(def series-limit
  "How many readings of the series the panel asks for: the most the api gives, so that a patient who is measured often still has
  their fourteen days."
  500)

(def max-in-flight
  "How many calls are on their way at once."
  6)

(def deadline-ms
  "How long the panel has, from the moment it begins, for all of its parts: what is not back by then has failed."
  10000)

;; ---------------------------------------------------------------------------
;; The calls

(defn- task-type [task]
  (if (vector? task) (first task) task))

(defn- part-path
  "Where in the panel the state of the part that `task` fills is."
  [task]
  (case (task-type task)
    :list [:list]
    (:active :acknowledged :requests) [:inbox task]
    :summary [:rows (second task) :summary]
    :series [:rows (second task) :series]))

(defn request
  "The call for `task` of a panel loading since `generation`, as the `:http` effect takes it. The answer is told to
  `:pulse.spa.panel/succeeded` or `:pulse.spa.panel/failed` with the generation and the task, so that an answer to a panel that has been
  left is not taken for the answer of the one that is open."
  [task panel generation]
  (let [base {:method :get
              :id [:panel task]
              :on-success [:pulse.spa.panel/succeeded generation task]
              :on-failure [:pulse.spa.panel/failed generation task]}]
    (case (task-type task)
      :list (assoc base :path ["api" "patients"] :query {:limit list-limit :sort "family"})
      :summary (assoc base :path ["api" "patients" (second task) "summary"])
      :series (assoc base
                     :path ["api" "patients" (second task) "observations"]
                     :query {:measurement (name (nth task 2))
                             :from (fmt/instant-text (- (fmt/millis (:now panel)) (* series-days 86400000)))
                             :limit series-limit})
      :active (assoc base :path ["api" "alerts"] :query {:status "active" :limit 1})
      :acknowledged (assoc base :path ["api" "alerts"] :query {:status "inactive" :limit 100})
      :requests (assoc base :path ["api" "appointments"] :query {:status "proposed" :limit 1}))))

(defn start
  "A panel that begins to load at `now` (an instant, as text): the list of patients, the alerts and the requests are asked for first,
  and what depends on the list when it is back."
  [now]
  {:now now
   :list {:state :loading}
   :rows {}
   :inbox {:active {:state :loading} :acknowledged {:state :loading} :requests {:state :loading}}
   :queue [:list :active :acknowledged :requests]
   :inflight #{}})

(defn pump
  "Starts the tasks that are waiting while fewer than `max-in-flight` are on their way: `[panel calls]`, the panel with them in
  flight and the calls to make, in order."
  [panel generation]
  (loop [panel panel
         calls []]
    (let [{:keys [queue inflight]} panel]
      (if (and (seq queue) (< (count inflight) max-in-flight))
        (let [task (first queue)]
          (recur (-> panel (assoc :queue (vec (rest queue))) (update :inflight conj task))
                 (conj calls (request task panel generation))))
        [panel calls]))))

;; ---------------------------------------------------------------------------
;; What the api says of a patient

(defn- band-severity
  "How far from normal a band is, as the api names it: from 0 (normal) to 3 (critical); -1 for no band."
  [band]
  (case (some-> band tone/band :tone)
    :critical 3
    :high 2
    :elevated 1
    :normal 0
    -1))

(defn- usable-vitals
  "The latest readings of a summary that say something, by measurement."
  [summary]
  (into {}
        (for [kind views/kinds
              :let [reading (get-in summary [:vitals kind])]
              :when (and (map? reading) (views/usable? kind reading))]
          [kind reading])))

(defn headline
  "The reading that says most about a patient, `{:kind :reading}`, from their `summary` (the api's): the furthest from normal among
  their latest readings, and of those equally far the first measurement of `views/kinds`. Nil for a patient with no reading that
  says anything."
  [summary]
  (let [order (zipmap views/kinds (range))]
    (when-let [[kind reading] (first (sort-by (fn [[kind reading]] [(- (band-severity (:band reading))) (order kind)])
                                              (usable-vitals summary)))]
      {:kind kind :reading reading})))

;; ---------------------------------------------------------------------------
;; The answers

(defn- well-formed?
  "Whether `body`, the answer to `task`, is what the api answers: another service could be on that address, or the api could have
  changed, and a part is not shown as empty for it."
  [task body]
  (case (task-type task)
    :list (and (map? body) (sequential? (:items body)) (integer? (:total body)))
    :summary (map? body)
    :series (and (map? body) (sequential? (:items body)))
    (:active :requests) (and (map? body) (integer? (:total body)))
    :acknowledged (and (map? body) (integer? (:total body)) (sequential? (:items body)))))

(defn patients
  "The patients of the list the api answered (`{:total :items}`), the ones that have an id, once each."
  [body]
  (->> (:items body)
       (filter #(and (map? %) (views/text-of (:id %))))
       (reduce (fn [[seen found] patient]
                 (if (contains? seen (:id patient))
                   [seen found]
                   [(conj seen (:id patient)) (conj found patient)]))
               [#{} []])
       second))

(defn- listed
  "The panel when the list is back: a row for each patient, whose summary is the next to be asked for."
  [panel body]
  (let [ids (map :id (patients body))]
    (-> panel
        (assoc :rows (into {} (map (fn [id] [id {:summary {:state :loading}}])) ids))
        (update :queue into (map (fn [id] [:summary id])) ids))))

(defn- summarized
  "The panel when the summary of `id` is back: the series of the reading that says most about the patient is next, before the
  summaries that are still waiting, so that a row is finished sooner."
  [panel id body]
  (if-let [{:keys [kind]} (headline body)]
    (-> panel
        (assoc-in [:rows id :series] {:state :loading})
        (update :queue #(into [[:series id kind]] %)))
    panel))

(defn answer
  "The panel when the call for `task` is answered with `outcome`: `{:result :ok :body ...}` or `{:result :failed :kind ...}`, as
  `pulse.spa.http/outcome` gives (a refusal counts as a failure here). An answer that is not what the api answers is a failure of
  kind `:unexpected`."
  [panel task outcome]
  (let [ok? (and (= :ok (:result outcome)) (well-formed? task (:body outcome)))
        part (cond ok? {:state :loaded :data (:body outcome)}
                   (= :ok (:result outcome)) {:state :failed :kind :unexpected}
                   :else {:state :failed :kind (or (:kind outcome) :unexpected)})
        panel (-> panel (update :inflight disj task) (assoc-in (part-path task) part))]
    (cond-> panel
      (and ok? (= :list task)) (listed (:body outcome))
      (and ok? (= :summary (task-type task))) (summarized (second task) (:body outcome)))))

(defn settled?
  "Whether every part of the panel is back or has failed: nothing is loading."
  [panel]
  (and (not-any? #(= :loading (:state %)) (cons (:list panel) (vals (:inbox panel))))
       (not-any? (fn [{:keys [summary series]}] (or (= :loading (:state summary)) (= :loading (:state series))))
                 (vals (:rows panel)))))

(defn expire
  "The panel when the deadline has come: every part that is still loading has failed, of kind `:timeout`, and nothing waits or is
  on its way any more. The tasks that were on their way are in `:cancelled` (in the order of their names), for the calls to be stopped."
  [panel]
  (let [timed-out (fn [part] (if (= :loading (:state part)) {:state :failed :kind :timeout} part))]
    (-> panel
        (update :list timed-out)
        (update :inbox update-vals timed-out)
        (update :rows update-vals (fn [row] (cond-> (update row :summary timed-out)
                                              (:series row) (update :series timed-out))))
        (assoc :cancelled (vec (sort-by str (:inflight panel))) :queue [] :inflight #{}))))

(defn failed-tasks
  "The tasks whose part has failed, which a retry asks for again: the list when it failed, or else the alerts and requests, the
  summaries and the series."
  [panel]
  (if (= :failed (get-in panel [:list :state]))
    [:list]
    (vec (concat
          (for [k [:active :acknowledged :requests]
                :when (= :failed (get-in panel [:inbox k :state]))]
            k)
          (for [[id {:keys [summary series]}] (:rows panel)
                task (cond-> []
                       (= :failed (:state summary)) (conj [:summary id])
                       ;; the series of a patient is asked for with the measurement their headline reading is of
                       (= :failed (:state series)) (conj (when-let [{:keys [kind]} (headline (:data summary))] [:series id kind])))
                :when task]
            task)))))

(defn retry
  "The panel with what failed asked for again: the failed parts loading and their tasks waiting, the order kept as it was until the
  panel settles again. When the list itself failed it is a fresh start, as `start` makes it, but for the alerts and requests that
  came back."
  [panel]
  (let [tasks (failed-tasks panel)]
    (if (= [:list] tasks)
      (-> (start (:now panel))
          (update :inbox merge (into {} (filter (fn [[_ part]] (= :loaded (:state part)))) (:inbox panel)))
          (update :queue #(vec (remove (fn [task] (= :loaded (get-in panel [:inbox task :state]))) %))))
      (reduce (fn [panel task] (-> panel (assoc-in (part-path task) {:state :loading}) (update :queue conj task)))
              panel
              tasks))))

;; ---------------------------------------------------------------------------
;; Text, to compare

(def ^:private combining-marks
  (re-pattern (str "[" (char 0x300) "-" (char 0x36f) "]")))

(defn fold
  "`text` as it is compared: in lower case and without accents, so that a search for \"garcia\" finds García, as the api's search of
  a name does."
  [text]
  (let [decomposed #?(:clj (java.text.Normalizer/normalize (str text) java.text.Normalizer$Form/NFD)
                      :cljs (.normalize (str text) "NFD"))]
    (str/lower-case (str/replace decomposed combining-marks ""))))

;; ---------------------------------------------------------------------------
;; A row

(defn- next-booked
  "The soonest of `appointments` (the summary's) that is booked and has not begun at `now`, or nil."
  [appointments now]
  (let [now-ms (fmt/millis now)]
    (->> appointments
         (filter #(and (map? %) (= "booked" (:status %)) (fmt/instant? (:start %)) (< now-ms (fmt/millis (:start %)))))
         (sort-by #(fmt/millis (:start %)))
         first)))

(defn- sparkline-tone
  "The colour of the line of a sparkline: the tone of the band of the latest reading."
  [band]
  (case (some-> band tone/band :tone)
    :critical :critical
    :high :high
    :elevated :elevated
    :neutral))

(defn- trend
  "The sparkline of a row: nothing for a patient with no headline reading, else its state and, once it is back, its points
  (`[millis value]`, oldest first, of the systolic pressure for a blood pressure), its tone and the words a screen reader says."
  [head series]
  (when head
    (case (:state series)
      :failed {:state :failed}
      :loaded (let [kind (:kind head)
                    body (:data series)
                    channel (if (= :blood-pressure kind) :systolic :value)
                    label (get-in views/kind-info [kind :label])]
                {:state :loaded
                 :points (mapv (fn [reading] [(fmt/millis (:at reading)) (get reading channel)])
                               (views/usable-series kind (:items body)))
                 :tone (sparkline-tone (get-in head [:reading :band]))
                 :label (if (true? (:truncated body))
                          (str label ", the latest " series-limit " readings")
                          (str label " over the last " series-days " days"))})
      {:state :loading})))

(defn row
  "The row of the panel for `patient` (an item of the list), whose parts are `{:summary :series}`, at `now`:
    `:id :name :identifier :family :given` and `:age` (nil when the date of birth cannot be read),
    `:state` (`:loading`, `:loaded` or `:failed`, of the summary) and `:failure` (of what kind),
    `:headline` (`{:kind :reading}` or nil), `:band` and `:severity` (from -1, no reading, to 3, critical) of it,
    `:vitals-lost?` (some reading could not be loaded, so the headline may not be the worst),
    `:alerts` (how many are active, nil when the summary could not say), `:next-visit` (the words of the soonest booked
    appointment, nil when there is none) and `:visits-known?`, and `:trend` (see `trend`)."
  [patient {:keys [summary series]} now]
  (let [state (or (:state summary) :loading)
        body (when (= :loaded state) (:data summary))
        unavailable (set (filter string? (:unavailable body)))
        head (when body (headline body))
        band (get-in head [:reading :band])
        appointments (when (and body (sequential? (:appointments body))) (:appointments body))]
    {:id (:id patient)
     :name (or (views/text-of (:name patient)) "Not named")
     :identifier (views/text-of (:identifier patient))
     :family (views/text-of (:family patient))
     :given (views/text-of (first (when (sequential? (:given patient)) (:given patient))))
     :age (when (fmt/date? (:birthDate patient)) (fmt/age (:birthDate patient) now))
     :state state
     :failure (when (= :failed state) (:kind summary))
     :headline head
     :band band
     :severity (if head (band-severity band) -1)
     :vitals-lost? (boolean (some #(str/starts-with? % "vitals.") unavailable))
     :alerts (when (and body (sequential? (:alerts body))) (count (filter map? (:alerts body))))
     :visits-known? (some? appointments)
     :next-visit (some-> (next-booked appointments now) (views/appointment-row {}) :when)
     :trend (trend head series)}))

(defn- sort-key [row]
  [(if (= :loaded (:state row)) 0 1)
   (- (:severity row))
   (- (or (:alerts row) 0))
   (fold (:family row))
   (fold (:given row))
   (:id row)])

(defn urgency-order
  "The ids of the patients of `panel` in the order of urgency: the worst latest reading first, then the most active alerts, then
  the family name; a row that is not loaded comes after the ones that are."
  [panel]
  (->> (patients (get-in panel [:list :data]))
       (map #(row % (get-in panel [:rows (:id %)]) (:now panel)))
       (sort-by sort-key)
       (mapv :id)))

(defn with-order
  "`panel`, with its order of urgency put right if it has settled: until then the rows stay where the list had them."
  [panel]
  (if (settled? panel)
    (assoc panel :order (urgency-order panel))
    panel))

;; ---------------------------------------------------------------------------
;; What the panel shows

(defn- rows-in-order
  "Every row of the panel, in the order of urgency when it has settled and in the order of the list until then."
  [panel]
  (let [found (patients (get-in panel [:list :data]))
        built (into {} (map (fn [patient] [(:id patient) (row patient (get-in panel [:rows (:id patient)]) (:now panel))])) found)
        in-list (map :id found)
        order (:order panel)]
    (keep built (if order (concat order (remove (set order) in-list)) in-list))))

(defn- shown?
  "Whether `row` is among those the panel is narrowed to by `show`."
  [show row]
  (case show
    :alerts (pos? (or (:alerts row) 0))
    :away (<= 1 (:severity row))
    :normal (zero? (:severity row))
    true))

(defn matches?
  "Whether `row` is what the search `q` looks for: every word of it is in the name or the number of the patient, whatever the case and
  the accents."
  [row q]
  (let [terms (remove str/blank? (str/split (fold q) #"\s+"))
        haystack (fold (str (:name row) " " (:identifier row)))]
    (every? #(str/includes? haystack %) terms)))

(defn- patients-phrase [n]
  (if (= 1 n) "1 patient" (str n " patients")))

(defn- figure
  "The value and the hint of a figure whose part is `part`: while it loads, a dash and \"Loading\"; if it failed, a dash and \"Not
  loaded\"; and once it is back, what `f` makes of its data."
  [part f]
  (case (:state part)
    :loaded (f (:data part))
    :failed {:value "–" :hint "Not loaded"}
    {:value "–" :hint "Loading"}))

(defn- stats
  "The four figures of the head of the panel."
  [panel rows settled? failed-rows]
  (let [away (count (filter #(<= 1 (:severity %)) rows))
        acknowledged (:acknowledged (:inbox panel))
        today (when (= :loaded (:state acknowledged))
                (count (filter #(and (fmt/instant? (:until %)) (= "Today" (fmt/day-label (:until %) (:now panel))))
                               (get-in acknowledged [:data :items]))))
        active (figure (:active (:inbox panel)) (fn [body] {:value (str (:total body))}))]
    [(merge {:id :patients :label "Patients" :icon :users}
            (figure (:list panel) (fn [body] {:value (str (:total body))})))
     (merge {:id :alerts :label "Active alerts" :icon :bell-ring}
            active
            (when (and (= :loaded (get-in panel [:inbox :active :state])) (pos? (get-in panel [:inbox :active :data :total])))
              {:tone :critical})
            (when (= :loaded (get-in panel [:inbox :active :state]))
              (case (:state acknowledged)
                :loaded {:hint (str today " acknowledged today")}
                :failed {:hint "Acknowledged today: not loaded"}
                {})))
     (merge {:id :requests :label "To confirm" :icon :calendar-clock}
            (figure (:requests (:inbox panel)) (fn [body] {:value (str (:total body)) :hint "requests for a visit"})))
     (merge {:id :away :label "Away from normal" :icon :triangle-alert}
            (cond
              (not settled?) {:value "–" :hint "Loading"}
              (not= :loaded (get-in panel [:list :state])) {:value "–" :hint "Not loaded"}
              (pos? failed-rows) {:value (str away) :hint (str (patients-phrase failed-rows) " not loaded")}
              :else {:value (str away) :hint "outside the normal range"}))]))

(defn model
  "What the panel shows, from its state `panel` (nil before it begins), what it is narrowed to (`{:show :q}`, see
  `pulse.spa.route/panel-params`) and whether the person is the administrator, who sees every patient:
    `:title` and `:description`, `:stats` (`{:id :label :value :hint :icon :tone}`), `:filters` (`{:id :label :count :current?}`, the
    counts only once every patient is loaded, since before that they would be wrong), `:rows` (as `row` makes them, narrowed),
    `:notices` (what could not be loaded: `{:id :list-failed}`, `{:id :rows-failed :count n}` or `{:id :figures-failed}`, only
    once it has settled, so that a retry is never offered while a call is on its way), `:empty` (`:no-patients` or `:no-match`),
    `:footer` (when the list is the first of more), `:settled?` and `:live`, the words a screen reader is told."
  [panel {:keys [show q] :or {show :all q ""}} admin?]
  (let [list-state (get-in panel [:list :state] :loading)
        all-rows (if (= :loaded list-state) (vec (rows-in-order panel)) [])
        settled? (boolean (and panel (settled? panel)))
        failed-rows (count (filter #(= :failed (:state %)) all-rows))
        everything-loaded? (and settled? (= :loaded list-state) (zero? failed-rows))
        count-of (fn [show] (when everything-loaded? (count (filter #(shown? show %) all-rows))))
        visible (vec (filter #(and (shown? show %) (matches? % q)) all-rows))
        total (get-in panel [:list :data :total])
        n (count all-rows)]
    {:title (if admin? "All patients" "Your panel")
     :description (when (and (= :loaded list-state) (pos? n))
                    (str "The " (if (= 1 n) "patient" (str n " patients")) (if admin? " of the clinic" " you look after")
                         (when (< 1 n) ", the most urgent first") "."))
     :stats (stats panel all-rows settled? failed-rows)
     :filters [{:id :all :label "All" :count (when (= :loaded list-state) n) :current? (= :all show)}
               {:id :alerts :label "With active alerts" :count (count-of :alerts) :current? (= :alerts show)}
               {:id :away :label "Away from normal" :count (count-of :away) :current? (= :away show)}
               {:id :normal :label "Normal" :count (count-of :normal) :current? (= :normal show)}]
     :rows visible
     :notices (cond-> []
                (= :failed list-state) (conj {:id :list-failed})
                (and settled? (pos? failed-rows)) (conj {:id :rows-failed :count failed-rows})
                (and settled? (some #(= :failed (:state %)) (vals (:inbox panel)))) (conj {:id :figures-failed}))
     :empty (cond
              (not= :loaded list-state) nil
              (zero? n) :no-patients
              (and (pos? n) (empty? visible)) :no-match)
     :footer (when (and (= :loaded list-state) (integer? total) (< n total))
               (str "Showing the first " n " of " total " patients"))
     :settled? settled?
     :live (cond
             (= :failed list-state) (if admin? "The patients could not be loaded" "Your panel could not be loaded")
             (not settled?) (if admin? "Loading the patients" "Loading your panel")
             :else (str (if admin? "The patients are loaded: " "Your panel is loaded: ") (patients-phrase n)
                        (when (< 1 n) ", the most urgent first")
                        (when (pos? failed-rows) (str ". " (patients-phrase failed-rows) " could not be loaded"))))}))
