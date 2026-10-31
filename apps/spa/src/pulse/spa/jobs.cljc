(ns pulse.spa.jobs
  "The status of the scheduler's jobs in the clinical workspace, as far as it can be decided without a browser (ADR-0029, points 3 and 6):
  the call, the part that holds the answer, and what the page shows once it is back. Pure and `.cljc`, so that the JVM and Node run the
  same tests over it and the mock-up of the review is made from the same functions as the page.

  **Whether the person may see the jobs, the api says.** `GET /api/admin/jobs` is for the clinic's administrator (Annex A, ADR-0025): the
  route of the workspace keeps everyone else from being sent there, for their sake, and a 403 from the api is a page that says so all the
  same. The api asks the scheduler, which is another service: when it cannot, the api answers 502 or 504, and the page says that the jobs
  could not be loaded, with a button to try again, and **never shows them as empty**.

  There is one call and nothing else, and `pulse.spa.fetch` gives a call six seconds, so there is no deadline of the page, no queue and no
  generation: a call that is stopped tells nobody anything. **The jobs are read when the page is opened and nothing repeats it** (ADR-0029,
  point 6: nothing else polls); to see a change the person comes to the page again.

  The state is a map: `:now` (an instant, as text, for \"today\") and `:list`, a part, `{:state :loading|:loaded|:failed ...}`."
  (:require [clojure.string :as str]
            [pulse.view.format :as fmt]
            [pulse.view.jobs :as words]
            [pulse.view.views :as views]))

(def call-id
  "The id of the call: there is one on its way at a time, and the page that is left stops it."
  [:jobs])

(def loading {:state :loading})

;; ---------------------------------------------------------------------------
;; The call

(def request
  "The call that reads the jobs, as the `:http` effect takes it."
  {:method :get
   :path ["api" "admin" "jobs"]
   :id call-id
   :on-success [:pulse.spa.jobs/succeeded]
   :on-failure [:pulse.spa.jobs/failed]})

(defn start
  "The jobs page that begins to load at `now` (an instant, as text)."
  [now]
  {:now now :list loading})

(defn- well-formed?
  "Whether `body` is what the api answers: another service could be on that address, or the api could have changed, and a part is not
  shown as empty for it."
  [body]
  (and (sequential? (:items body)) (integer? (:total body))))

(defn answer
  "The jobs page when the call is answered with `outcome`: `{:result :ok :body ...}` or `{:result :failed :kind ...}`, as
  `pulse.spa.http/outcome` gives (a refusal counts as a failure here). An answer that is not what the api answers is a failure of
  kind `:unexpected`."
  [state outcome]
  (assoc state :list (cond
                       (and (= :ok (:result outcome)) (well-formed? (:body outcome))) {:state :loaded :data (:body outcome)}
                       (= :ok (:result outcome)) {:state :failed :kind :unexpected}
                       :else {:state :failed :kind (or (:kind outcome) :unexpected)})))

(defn failed?
  "Whether the jobs could not be loaded, which a retry asks for again."
  [state]
  (= :failed (get-in state [:list :state])))

(defn retry
  "The jobs page asking again at `now`, for the jobs that could not be loaded."
  [state now]
  (assoc state :now now :list loading))

(def not-begun
  "The page as it is shown before it has begun to load, for the moment between coming to it and the first of its calls."
  {:now nil :list loading})

;; ---------------------------------------------------------------------------
;; What the page shows

(def ^:private result-order
  "The keys that a result is known to hold, in the order they are said (the api does not keep the order of the keys of a result); any
  other key follows, alphabetically."
  [:patients :appointments :planned :read :readings :raised :written :sent :alreadyRaised :alreadySent :stillActive :notApplicable
   :abnormal :batches :failed :failedBatches :errors])

(def ^:private result-index
  (zipmap result-order (range)))

(defn- result-text
  "What a run reported, numbers by name, as `6 patients, 0 raised, 4 already raised`, or nil when it reported nothing (or what is not
  numbers: only a number is a count)."
  [result]
  (when (map? result)
    (let [counts (filter (fn [[k v]] (and (keyword? k) (number? v))) result)]
      (when (seq counts)
        (str/join ", " (for [[k v] (sort-by (fn [[k _]] [(get result-index k (count result-order)) (name k)]) counts)]
                         (words/counted k v)))))))

(defn- moment
  "When `iso` is, as the page says it: the clock to the second for a time today, and the day first for another. Nil when it cannot be
  read."
  [iso now]
  (when (and (fmt/instant? iso) (fmt/instant? now))
    (let [day (fmt/day-label iso now)]
      (if (= "Today" day)
        (fmt/clock-seconds iso)
        (str day ", " (fmt/clock-seconds iso))))))

(def ^:private outcomes
  {"ok" :ok "failed" :failed "interrupted" :interrupted})

(defn- last-run
  "How the last run went: `{:outcome :started :duration :result :error}`, each only when it can be said, or nil for a job that has not
  run (or a run that is not a map)."
  [run now]
  (when (map? run)
    {:outcome (get outcomes (:outcome run) :unknown)
     :started (moment (:startedAt run) now)
     :duration (words/duration-text (:durationMs run))
     :result (result-text (:result run))
     :error (views/text-of (:error run))}))

(defn- counts-text
  "How many runs there have been of each outcome, or nil when the api did not say."
  [{:keys [ok failed interrupted skipped]}]
  (when (every? integer? [ok failed interrupted skipped])
    (str ok " ok, " failed " failed, " interrupted " interrupted, " skipped " skipped")))

(defn- job-row
  "A job as the page shows it: `{:id :description :running? :since :schedule :last-run :next-run :runs}`."
  [{:keys [id description schedule state runningSince nextRun lastRun counts]} now]
  {:id id
   :description (views/text-of description)
   :running? (= "running" state)
   :since (moment runningSince now)
   :schedule (when (map? schedule) (words/schedule-text schedule))
   :last-run (last-run lastRun now)
   :next-run (moment nextRun now)
   :runs (when (map? counts) (counts-text counts))})

(defn job-items
  "The jobs of the answer of the api (`{:total :items}`) that have an id, once each, in the order the api gave them."
  [body]
  (->> (:items body)
       (filter #(views/text-of (:id %)))
       (reduce (fn [[seen found] job]
                 (if (contains? seen (:id job))
                   [seen found]
                   [(conj seen (:id job)) (conj found job)]))
               [#{} []])
       second))

(defn- failure-text
  "What the alert for a job whose last run failed says: the class of the error and how long the run took, and when the next run is,
  each only when it is known."
  [{:keys [last-run next-run]}]
  (let [{:keys [error duration]} last-run
        what (cond (and error duration) (str error " after " duration ".")
                   error (str error ".")
                   duration (str "It ran for " duration ".")
                   :else nil)]
    (str/join " " (keep identity [what (when next-run (str "The next run is at " next-run "."))]))))

(defn- jobs-phrase [n]
  (if (= 1 n) "1 job" (str n " jobs")))

(defn model
  "What the page shows, from its state (`not-begun` before it begins):
    `:title` and `:description`,
    `:state` (`:loading`, `:lost` when the jobs could not be loaded, `:forbidden` when the api said the person may not, or `:loaded`),
    `:kind` (the kind of failure, for `:lost` and `:forbidden`),
    `:stats` (`{:total :running :failed}`, once they are loaded),
    `:failures` (`{:id :text}` for each job whose last run failed),
    `:rows` (see `job-row`) and `:live`, the words a screen reader is told."
  [{:keys [now] part :list}]
  (let [part-state (:state part)
        kind (:kind part)
        rows (when (= :loaded part-state) (mapv #(job-row % now) (job-items (:data part))))
        failed (filterv #(= :failed (get-in % [:last-run :outcome])) rows)
        running (count (filter :running? rows))]
    (cond-> {:title "Jobs"
             :description "What the scheduler does on its own, and how the last run of each went."
             :state (case part-state
                      :loading :loading
                      :loaded :loaded
                      (if (= :forbidden kind) :forbidden :lost))
             :kind kind
             :failures (mapv (fn [row] {:id (:id row) :text (failure-text row)}) failed)
             :rows (or rows [])
             :live (case part-state
                     :loading "Loading the jobs"
                     :loaded (str (jobs-phrase (count rows))
                                  (when (pos? running) (str ", " running " running"))
                                  (when (seq failed) (str ", " (count failed) " failed its last run")))
                     (if (= :forbidden kind) "You cannot see the jobs" "The jobs could not be loaded"))}
      (= :loaded part-state) (assoc :stats {:total (count rows) :running running :failed (count failed)}))))
