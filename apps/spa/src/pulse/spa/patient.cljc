(ns pulse.spa.patient
  "The chart of a patient in the clinical workspace, as far as it can be decided without a browser (ADR-0029, point 6): the calls that
  fill it and what it shows once it is filled. Pure and `.cljc`, so that the JVM and Node run the same tests over it and the mock-up
  of the review is made from the same functions as the page.

  **Whose chart it is, the api says.** Every call goes to a path of the patient, and the api decides whether the caller may see them
  (a practitioner sees the patients whose doctor they are, the administrator all of them: ADR-0012). A patient the api refuses, or
  does not know, is a chart that says so (`:gone?`), and nothing else that arrives is shown; the workspace filters nothing itself and
  asks for nothing that a list of the whole clinic would hold.

  **The calls.** `GET /api/patients/{id}` is who the patient is (the header), the summary holds what the clinic has on file (the latest
  readings, the conditions, the medication, the allergies, the appointments and the active alerts), the series is the chart, and, for
  each measurement that an active alert's rule watches, the latest readings behind the alert (`pulse.view.alerts`). The series
  begins at once if the address names a measurement, and otherwise when the summary has said which of the patient's readings is the
  furthest from normal (their *headline*, the panel's), which is the chart's default. At most `max-in-flight` calls are on their way and one
  deadline, `deadline-ms`, ends what is not back: a part that did not arrive is a failed part, `:timeout`, and **never an empty one**.
  Each part has its own state, `{:state :loading|:loaded|:failed ...}`, so that one can fail alone and the rest is shown.

  The state is a map: `:now`, `:id` (of the patient), `:asked` (the measurement the address asks for, or nil), `:days`, the parts
  `:patient`, `:summary`, `:series` (with its `:measurement` and `:days`, nil until there is a measurement to draw) and `:evidence` (by
  measurement), `:queue` (the tasks not started) and `:inflight` (a set of tasks). A task is `:patient`, `:summary`,
  `[:series kind days]` or `[:evidence kind]`."
  (:require [clojure.string :as str]
            [pulse.spa.panel :as panel]
            [pulse.view.alerts :as alerts]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views]))

(def periods
  "The periods, in days, that the chart can look at."
  [7 14 30])

(def default-days
  "The period the chart looks at when the address says none."
  14)

(def series-limit
  "How many readings of the series are asked for: the most the api gives, so that a patient who is measured often still has their
  thirty days."
  panel/series-limit)

(def max-in-flight
  "How many calls are on their way at once."
  panel/max-in-flight)

(def deadline-ms
  "How long the chart has, from the moment it begins (or is tried again), for all of its parts: what is not back by then has failed."
  panel/deadline-ms)

;; ---------------------------------------------------------------------------
;; The calls

(defn- task-type [task]
  (if (vector? task) (first task) task))

(defn call-id
  "The id of the call for `task`, with which the effect stops it: one call of a kind is on its way at a time, and the chart that is
  left stops the ones that are not back."
  [task]
  [:chart (case (task-type task)
            :series :series
            task)])

(defn- part-path
  "Where in the chart the state of the part that `task` fills is."
  [task]
  (case (task-type task)
    :patient [:patient]
    :summary [:summary]
    :series [:series]
    :evidence [:evidence (second task)]))

(defn request
  "The call for `task` of a chart loading since `generation`, as the `:http` effect takes it. The answer is told to
  `:pulse.spa.patient/succeeded` or `:pulse.spa.patient/failed` with the generation and the task, so that an answer to a chart that
  has been left is not taken for the answer of the one that is open."
  [task chart generation]
  (let [id (:id chart)
        base {:method :get
              :id (call-id task)
              :on-success [:pulse.spa.patient/succeeded generation task]
              :on-failure [:pulse.spa.patient/failed generation task]}]
    (case (task-type task)
      :patient (assoc base :path ["api" "patients" id])
      :summary (assoc base :path ["api" "patients" id "summary"])
      :series (let [[_ kind days] task]
                (assoc base
                       :path ["api" "patients" id "observations"]
                       :query {:measurement (name kind)
                               :from (fmt/instant-text (- (fmt/millis (:now chart)) (* days 86400000)))
                               :limit series-limit}))
      :evidence (assoc base
                       :path ["api" "patients" id "observations"]
                       :query {:measurement (name (second task)) :limit alerts/evidence-count}))))

(defn pump
  "Starts the tasks that are waiting while fewer than `max-in-flight` are on their way: `[chart calls]`, the chart with them in flight
  and the calls to make, in order."
  [chart generation]
  (loop [chart chart
         calls []]
    (let [{:keys [queue inflight]} chart]
      (if (and (seq queue) (< (count inflight) max-in-flight))
        (let [task (first queue)]
          (recur (-> chart (assoc :queue (vec (rest queue))) (update :inflight conj task))
                 (conj calls (request task chart generation))))
        [chart calls]))))

;; ---------------------------------------------------------------------------
;; Which measurement is drawn

(defn default-kind
  "The measurement the chart is of when the address asks for none, from the `summary` of the api: the patient's headline reading (the
  furthest from normal, see `pulse.spa.panel/headline`) or, for a patient whose readings say nothing, the first they can be offered,
  or nil for one with no reading at all."
  [summary]
  (or (:kind (panel/headline summary))
      (first (views/offered-kinds summary nil))))

(defn- gone-part?
  "Whether `part`, the state of the Patient, is the api's refusal: it does not let the person see this patient (403) or does not know
  them (404). The page says so and shows nothing else, and nothing else is asked for."
  [part]
  (and (= :failed (:state part)) (contains? #{:forbidden :not-found} (:kind part))))

(defn- wanted-kind
  "The measurement the chart should be of now: the one asked for, else the default of the summary once it is back. When the summary
  could not be had there is nothing to choose by, and the chart is of the first measurement, so that it is a chart and not a blank, once
  the Patient has answered (the summary can fail before it does); of a patient the api refuses there is no chart at all."
  [chart]
  (when-not (gone-part? (:patient chart))
    (or (:asked chart)
        (case (get-in chart [:summary :state])
          :loaded (default-kind (get-in chart [:summary :data]))
          :failed (when (not= :loading (get-in chart [:patient :state])) (first views/kinds))
          nil))))

(defn- drop-series
  "The chart without a series: nothing of it is waiting or on its way."
  [chart]
  (let [series? #(= :series (task-type %))]
    (-> chart
        (assoc :series nil)
        (update :queue (fn [queue] (vec (remove series? queue))))
        (update :inflight (fn [inflight] (into #{} (remove series?) inflight))))))

(defn- with-series
  "The chart with the series of the measurement it should be of, for its period, asked for first unless it is that already."
  [chart]
  (let [kind (wanted-kind chart)
        days (:days chart)
        current (:series chart)]
    (cond
      (nil? kind) (drop-series chart)
      (and (= kind (:measurement current)) (= days (:days current))) chart
      :else (-> (drop-series chart)
                (assoc :series {:state :loading :measurement kind :days days})
                (update :queue #(into [[:series kind days]] %))))))

(def loading
  "The chart before it has begun to load, which is what is shown for the moment between coming to the page and the first of its calls:
  every part is on its way, and there is no moment yet."
  {:now nil :id nil :asked nil :days default-days
   :patient {:state :loading} :summary {:state :loading} :series nil :evidence {} :queue [] :inflight #{}})

(defn start
  "A chart that begins to load at `now` (an instant, as text), for `params`, `{:id :measurement :days}` as `pulse.spa.route` reads
  an address: who the patient is and their summary are asked for first, and the series at once when the address names a measurement."
  [now {:keys [id measurement days] :or {days default-days}}]
  (with-series {:now now
                :id id
                :asked measurement
                :days days
                :patient {:state :loading}
                :summary {:state :loading}
                :series nil
                :evidence {}
                :queue [:patient :summary]
                :inflight #{}}))

(defn choose
  "The chart when the address asks for another measurement or period of the same patient (`{:measurement :days}`): the series is
  asked for again, and the call of the one before it, if it is still on its way, is not heard of again. What is not about the series
  is left as it was."
  [chart {:keys [measurement days]}]
  (if (and (= measurement (:asked chart)) (= days (:days chart)))
    chart
    (with-series (assoc chart :asked measurement :days days))))

;; ---------------------------------------------------------------------------
;; The answers

(defn- well-formed?
  "Whether `body`, the answer to `task`, is what the api answers: another service could be on that address, or the api could have
  changed, and a part is not shown as empty for it."
  [task body]
  (case (task-type task)
    :patient (and (map? body) (= "Patient" (:resourceType body)))
    :summary (map? body)
    (:series :evidence) (and (map? body) (sequential? (:items body)))))

(defn- stale?
  "Whether the answer to `task` is to a call that the chart has left behind: the series of another measurement or period than the one
  it is of now, or the readings behind an alert that nothing asks for."
  [chart task]
  (case (task-type task)
    :series (let [series (:series chart)]
              (not (and series (= (second task) (:measurement series)) (= (nth task 2) (:days series)))))
    :evidence (not (contains? (:evidence chart) (second task)))
    false))

(defn- with-evidence
  "The chart when the summary is back: the readings behind each active alert are the next to be asked for, one call for each
  measurement that the rules of the alerts watch."
  [chart summary]
  (let [kinds (alerts/kinds-to-read (when (sequential? (:alerts summary)) (:alerts summary)))]
    (-> chart
        (assoc :evidence (into {} (map (fn [kind] [kind {:state :loading}])) kinds))
        (update :queue into (map (fn [kind] [:evidence kind])) kinds))))

(defn answer
  "The chart when the call for `task` is answered with `outcome`: `{:result :ok :body ...}` or `{:result :failed :kind ...}`, as
  `pulse.spa.http/outcome` gives (a refusal counts as a failure here). An answer that is not what the api answers is a failure of kind
  `:unexpected`. The answer to a call that the chart has left behind (see `choose`) is dropped."
  [chart task outcome]
  (let [chart (update chart :inflight disj task)]
    (if (stale? chart task)
      chart
      (let [ok? (and (= :ok (:result outcome)) (well-formed? task (:body outcome)))
            part (cond ok? {:state :loaded :data (:body outcome)}
                       (= :ok (:result outcome)) {:state :failed :kind :unexpected}
                       :else {:state :failed :kind (or (:kind outcome) :unexpected)})
            part (cond-> part
                   (= :series (task-type task)) (assoc :measurement (second task) :days (nth task 2)))
            chart (cond-> (assoc-in chart (part-path task) part)
                    ;; the api does not let the person see this patient: what was begun for them is not wanted
                    (and (= :patient task) (gone-part? part)) drop-series)]
        (case task
          ;; a summary that did not come asks for nothing more; the series waits for it only when nothing was asked for, and if one is
          ;; there already, it stays
          :summary (cond-> (with-evidence chart (:body outcome))
                     (nil? (:series chart)) with-series)
          ;; the chart to fall back on, when the summary failed first, waits for the Patient to say that there is one
          :patient (cond-> chart
                     (nil? (:series chart)) with-series)
          chart)))))

(defn settled?
  "Whether every part of the chart is back or has failed: nothing is loading."
  [chart]
  (not-any? #(= :loading (:state %))
            (concat [(:patient chart) (:summary chart)] (when-let [series (:series chart)] [series]) (vals (:evidence chart)))))

(defn expire
  "The chart when the deadline has come: every part that is still loading has failed, of kind `:timeout`, and nothing waits or is on
  its way any more. The tasks that were on their way are in `:cancelled` (in the order of their names), for the calls to be stopped."
  [chart]
  (let [timed-out (fn [part] (if (= :loading (:state part)) (assoc part :state :failed :kind :timeout) part))]
    (-> chart
        (update :patient timed-out)
        (update :summary timed-out)
        (update :series #(some-> % timed-out))
        (update :evidence update-vals timed-out)
        (assoc :cancelled (vec (sort-by str (:inflight chart))) :queue [] :inflight #{}))))

(defn failed-tasks
  "The tasks whose part has failed, which a retry asks for again."
  [chart]
  (let [failed? #(= :failed (:state %))
        series (:series chart)]
    (vec (concat (when (failed? (:patient chart)) [:patient])
                 (when (failed? (:summary chart)) [:summary])
                 (when (and series (failed? series)) [[:series (:measurement series) (:days series)]])
                 (for [[kind part] (sort-by (comp str key) (:evidence chart))
                       :when (failed? part)]
                   [:evidence kind])))))

(defn retry
  "The chart with what failed asked for again: the failed parts loading and their tasks waiting."
  [chart]
  (reduce (fn [chart task]
            (-> chart
                (assoc-in (part-path task) (cond-> {:state :loading}
                                             (= :series (task-type task)) (assoc :measurement (second task) :days (nth task 2))))
                (update :queue conj task)))
          chart
          (failed-tasks chart)))

;; ---------------------------------------------------------------------------
;; What the chart shows

(defn- text-of [x] (views/text-of x))

(defn- full-name
  "The name of a Patient resource as the clinic writes it, or nil."
  [resource]
  (let [written (first (when (sequential? (:name resource)) (:name resource)))]
    (when (map? written)
      (when-let [words (seq (keep text-of (concat (when (sequential? (:given written)) (:given written)) [(:family written)])))]
        (str/join " " words)))))

(defn- first-of
  "The first of `x` when it is a sequence, else nil."
  [x]
  (first (when (sequential? x) x)))

(defn- years [age]
  (if (= 1 age) "1 year" (str age " years")))

(defn- description
  "The line under the name of the patient: `58 years · Female · PULSE-0001 · Madrid`, each only when the resource says it and it can be
  read."
  [resource now]
  (let [age (when (fmt/date? (:birthDate resource)) (fmt/age (:birthDate resource) now))
        parts (cond-> []
                (some? age) (conj (years age))
                (text-of (:gender resource)) (conj (str/capitalize (text-of (:gender resource))))
                (text-of (:value (first-of (:identifier resource)))) (conj (text-of (:value (first-of (:identifier resource)))))
                (text-of (:city (first-of (:address resource)))) (conj (text-of (:city (first-of (:address resource))))))]
    (when (seq parts) (str/join " · " parts))))

(defn- section
  "A list of the summary, `key`, as the chart shows it: `{:state :loading}` while the summary is on its way, `{:state :lost}` when it
  could not be had, or the list is not among what it could load, and `{:state :loaded :items [...]}` otherwise, the items that are
  maps."
  [summary key]
  (case (:state summary)
    :loading {:state :loading}
    :failed {:state :lost}
    (let [body (:data summary)
          unavailable (when (sequential? (:unavailable body)) (:unavailable body))]
      (if (or (some #{(name key)} unavailable) (not (sequential? (get body key))))
        {:state :lost}
        {:state :loaded :items (vec (filter map? (get body key)))}))))

(defn- vitals
  "The latest readings of the summary: `{:state :loading}`, `{:state :lost}` for a summary that could not be had or has none of the
  section, or `{:state :loaded :items [{:kind :reading}...] :lost? bool}`, the readings that say something in the order the
  measurements are shown, and whether some could not be loaded (the summary names them `vitals.glucose`)."
  [summary]
  (case (:state summary)
    :loading {:state :loading}
    :failed {:state :lost}
    (let [body (:data summary)
          unavailable (when (sequential? (:unavailable body)) (:unavailable body))]
      (if-not (map? (:vitals body))
        {:state :lost}
        {:state :loaded
         :items (vec (for [kind views/kinds
                           :let [reading (get-in body [:vitals kind])]
                           :when (and (map? reading) (views/usable? kind reading))]
                       {:kind kind :reading reading}))
         :lost? (boolean (some #(and (string? %) (str/starts-with? % "vitals.")) unavailable))}))))

(defn- evidence-of
  "The readings behind an alert with this `text`: nil for an alert whose rule is not known (nothing says what it watches),
  `{:state :loading}`, `{:state :failed}` or `{:state :loaded :lines [...]}`."
  [chart text]
  (when-let [kind (alerts/measurement text)]
    (let [part (get-in chart [:evidence kind])]
      (case (:state part)
        :loaded {:state :loaded :lines (alerts/evidence kind (get-in part [:data :items]) (:now chart))}
        :failed {:state :failed}
        {:state :loading}))))

(defn- alert-cards
  "The active alerts of the summary: `{:state :loading}`, `{:state :lost}` or `{:state :loaded :items [...]}`, each item
  `{:id :text :tone :since-phrase :description :evidence}`, the newest first as the api gives them."
  [chart]
  (let [{:keys [state items]} (section (:summary chart) :alerts)]
    (if (= :loaded state)
      {:state :loaded
       :items (vec (for [alert items
                         :let [text (or (text-of (:text alert)) "Alert")]]
                     {:id (:id alert)
                      :text text
                      :tone (alerts/tone text)
                      :since-phrase (when (fmt/instant? (:since alert)) (fmt/when-phrase (:since alert) (:now chart)))
                      :description (alerts/description text)
                      :evidence (evidence-of chart text)}))}
      {:state state})))

(defn- appointment-rows
  "The appointments of the summary as rows, in the order a clinician reads them: what is coming, soonest first, what has begun, and
  what was cancelled last (`pulse.view.views/appointment-sections`); none is lost."
  [chart]
  (let [{:keys [state items]} (section (:summary chart) :appointments)]
    (if (= :loaded state)
      (let [{:keys [coming earlier cancelled]} (views/appointment-sections items {} (:now chart))]
        {:state :loaded :rows (vec (concat coming earlier cancelled))})
      {:state state})))

(defn- live-text [{:keys [gone? settled? name failed-parts]}]
  (cond
    gone? "That patient could not be found"
    (not settled?) (str "Loading the chart of " (or name "the patient"))
    (pos? failed-parts) (str "The chart of " (or name "the patient") " is loaded. " (if (= 1 failed-parts) "1 part" (str failed-parts " parts")) " could not be loaded")
    ;; the chart has settled and nothing failed, so the Patient is there and has a name
    :else (str "The chart of " name " is loaded")))

(defn model
  "What the chart shows, from its state `chart`:
    `:gone?` (the api refused the patient or does not know them: nothing else is shown),
    `:title` (the name of the patient as the clinic writes it, `Patient` while it is not known) and `:description`,
    `:patient` (`:loading`, `:loaded` or `:failed`, the state of the part),
    `:kind` and `:days` (what the chart is of), `:periods`, `:offered` (the measurements to choose between; nil while the summary
    is on its way), `:series` (the part: `{:state :loading|:failed|:loaded :measurement :days :data}`, nil when there is nothing to draw),
    `:conditions`, `:medications`, `:allergies` (each `{:state :loading|:lost|:loaded :items}`), `:vitals` (see `vitals`), `:alerts`
    (see `alert-cards`), `:appointments` (`{:state ... :rows}`), `:no-readings?` (the summary is back and says the patient has none,
    nor any that could not be loaded), `:settled?` and `:live`, the words a screen reader is told."
  [chart]
  (let [{patient-part :patient summary-part :summary series :series} chart
        resource (when (= :loaded (:state patient-part)) (:data patient-part))
        gone? (gone-part? patient-part)
        name (when resource (or (full-name resource) "Not named"))
        settled? (settled? chart)
        parts (concat [patient-part summary-part] (when series [series]) (vals (:evidence chart)))
        failed-parts (count (filter #(= :failed (:state %)) parts))
        kind (:measurement series)
        vitals* (vitals summary-part)
        offered (case (:state summary-part)
                  :loading nil
                  :failed (views/offered-kinds nil kind)
                  (views/offered-kinds (:data summary-part) kind))]
    {:gone? gone?
     :title (or name "Patient")
     :description (description resource (:now chart))
     :patient (:state patient-part)
     :kind kind
     :days (:days chart)
     :periods periods
     :offered offered
     :series series
     :conditions (section summary-part :conditions)
     :medications (section summary-part :medications)
     :allergies (section summary-part :allergies)
     :vitals vitals*
     :alerts (alert-cards chart)
     :appointments (appointment-rows chart)
     :no-readings? (and (= :loaded (:state vitals*)) (empty? offered))
     :settled? settled?
     :live (live-text {:gone? gone? :settled? settled? :name name :failed-parts failed-parts})}))
