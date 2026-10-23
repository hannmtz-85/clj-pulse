(ns pulse.scheduler.executor
  "Runs a catalogue of jobs (see `pulse.scheduler.job`) on a clock (ADR-0020). JVM only.

  There are two pools. A thread of a `ScheduledExecutorService` only fires ticks, one pending tick
  for each job; the handlers run on virtual threads. A tick does two things, in this order. It
  arms the next tick for the job, at `next-run` asked from now (or from the moment the tick was
  due, if the clock says that is later), so the chain of ticks goes on whatever else happens.
  Then it starts a run, unless the job is still running from before: that tick is skipped,
  counted and logged. So a job is never run twice at once, a pause or a long run is not made up
  for, and a handler that hangs shows as skipped ticks and does not stop its schedule.

  The clock, the scheduler and the workers can be given, which is how the tests drive time by
  hand; the executor owns them, and `stop!` shuts them down.

  A handler is a function of one argument, `{:job-id :trigger :due :started-at}` (`:trigger` is
  `:schedule` or `:manual`, `:due` the tick's moment or nil), that returns nil or a map of simple
  values to show in the status. It must be safe to repeat and must not swallow interruption."
  (:require [pulse.scheduler.job :as job]
            [pulse.scheduler.schedule :as schedule]
            [taoensso.telemere :as t])
  (:import (java.time Clock Duration Instant)
           (java.util.concurrent ExecutorService Executors RejectedExecutionException
                                 ScheduledExecutorService ScheduledThreadPoolExecutor
                                 ThreadFactory TimeUnit)))

;; ---------------------------------------------------------------------------
;; The state of a job: {:running-since instant-or-nil, :next-run instant-or-nil,
;;   :last-run map-or-nil, :counts {:ok n :failed n :interrupted n :skipped n}}, in one atom
;; for the whole catalogue, changed only by `swap!` so that claiming a run is atomic.

(def ^:private initial-state
  {:running-since nil :next-run nil :last-run nil
   :counts {:ok 0 :failed 0 :interrupted 0 :skipped 0}})

(defn- claim!
  "Marks the job `id` as running since `now` unless it already is. True when this call did it."
  [state id now]
  (let [[before _] (swap-vals! state update id
                               (fn [job-state] (if (:running-since job-state)
                                                 job-state
                                                 (assoc job-state :running-since now))))]
    (nil? (get-in before [id :running-since]))))

(defn- summary
  "What a handler returned, as the status shows it: a map of its numbers, strings, booleans and
  keywords (as strings), or nil for anything else."
  [result]
  (when (map? result)
    (into {}
          (keep (fn [[k v]]
                  (cond (keyword? v) [k (name v)]
                        (or (number? v) (string? v) (boolean? v)) [k v])))
          result)))

(defn- later [^Instant a ^Instant b]
  (if (.isAfter a b) a b))

(defn- interruption?
  "Whether `e`, or anything it was caused by, is an interruption: a handler that catches one and
  throws another thing in its place has still been interrupted."
  [e]
  (boolean (some #(instance? InterruptedException %) (take-while some? (iterate ex-cause e)))))

;; ---------------------------------------------------------------------------
;; Runs

(defn- perform!
  "Runs the handler of `job` for `run` and records what became of it. It does not throw, except
  for an error the JVM cannot recover from."
  [{:keys [^Clock clock state]} {:keys [id handler]} {:keys [trigger due started-at]}]
  (t/with-ctx+ {:job id :trigger trigger}
    (let [outcome (try
                    {:outcome :ok
                     :result (summary (handler {:job-id id :trigger trigger :due due :started-at started-at}))}
                    (catch Throwable e
                      {:outcome (if (interruption? e) :interrupted :failed) :error e}))
          finished (.instant clock)
          duration-ms (.toMillis (Duration/between ^Instant started-at finished))
          error (:error outcome)
          last-run (cond-> {:trigger trigger
                            :started-at started-at
                            :finished-at finished
                            :duration-ms duration-ms
                            :outcome (:outcome outcome)}
                     (:result outcome) (assoc :result (:result outcome))
                     error (assoc :error (.getName (class error))))]
      (swap! state update id
             (fn [job-state]
               (-> job-state
                   (assoc :running-since nil :last-run last-run)
                   (update-in [:counts (:outcome outcome)] inc))))
      (t/event! :pulse.scheduler/job-finished
                {:level (case (:outcome outcome) :ok :info :interrupted :warn :failed :error)
                 :error (when (= :failed (:outcome outcome)) error)
                 :data (cond-> {:job id :trigger trigger :outcome (:outcome outcome) :duration-ms duration-ms}
                         (:result outcome) (assoc :result (:result outcome)))})
      (when (instance? VirtualMachineError error)
        (throw error)))))

(defn- dispatch!
  "Hands a run of `job`, already claimed, to the workers. False, with the claim given back, when
  they no longer take work."
  [{:keys [^ExecutorService workers state] :as executor} job run]
  (try
    (.execute workers (bound-fn* (fn [] (perform! executor job run))))
    true
    (catch RejectedExecutionException _
      (swap! state assoc-in [(:id job) :running-since] nil)
      false)))

;; ---------------------------------------------------------------------------
;; Ticks

(declare tick!)

(defn- arm!
  "Schedules the tick of `job` for `due` and records it as the job's next run."
  [{:keys [^ScheduledExecutorService scheduler ^Clock clock state] :as executor} job ^Instant due]
  (swap! state assoc-in [(:id job) :next-run] due)
  (let [^Runnable task (fn [] (tick! executor job due))
        wait-ms (max 0 (.toMillis (Duration/between (.instant clock) due)))]
    (try
      (.schedule scheduler task wait-ms TimeUnit/MILLISECONDS)
      (catch RejectedExecutionException _
        nil))))

(defn- skip!
  [{:keys [state]} job ^Instant due]
  (swap! state update-in [(:id job) :counts :skipped] inc)
  (t/event! :pulse.scheduler/job-skipped
            {:level :warn
             :data {:job (:id job)
                    :due (str due)
                    :running-since (str (get-in @state [(:id job) :running-since]))}}))

(defn- tick!
  "The tick of `job` that fell due at `due`: arm the next one, then run the job or skip it."
  [{:keys [^Clock clock state stopped?] :as executor} job ^Instant due]
  (when-not @stopped?
    (try
      (let [now (.instant clock)]
        (arm! executor job (schedule/next-run (:schedule job) (later now due)))
        (if (claim! state (:id job) now)
          (dispatch! executor job {:trigger :schedule :due due :started-at now})
          (skip! executor job due)))
      (catch Throwable e
        (t/event! :pulse.scheduler/tick-failed
                  {:level :error :error e :data {:job (:id job)}})))))

;; ---------------------------------------------------------------------------
;; The executor

(defn- tick-scheduler []
  (doto (ScheduledThreadPoolExecutor. 1 (reify ThreadFactory
                                          (newThread [_ task]
                                            (doto (Thread. ^Runnable task "scheduler-ticks")
                                              (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn- job-workers []
  (Executors/newThreadPerTaskExecutor (.factory (.name (Thread/ofVirtual) "job-" 0))))

(defn start
  "Starts running `jobs`, a collection of jobs as `pulse.scheduler.job/check!` accepts them, and
  returns the executor. Each job gets its first tick at the next time its schedule says: nothing
  runs at start-up. Options:

    `:grace-ms`   how long `stop!` lets running handlers finish before interrupting them (5000)
    `:clock`      a `java.time.Clock` (the system clock, UTC)
    `:scheduler`  a `ScheduledExecutorService` for the ticks (one daemon thread)
    `:workers`    an `ExecutorService` for the handlers (a virtual thread for each run)"
  ([jobs] (start jobs {}))
  ([jobs {:keys [grace-ms clock scheduler workers] :or {grace-ms 5000}}]
   (let [jobs (job/check! jobs)
         clock (or clock (Clock/systemUTC))
         executor {:jobs jobs
                   :by-id (into {} (map (juxt :id identity)) jobs)
                   :clock clock
                   :scheduler (or scheduler (tick-scheduler))
                   :workers (or workers (job-workers))
                   :grace-ms grace-ms
                   :state (atom (zipmap (map :id jobs) (repeat initial-state)))
                   :stopped? (atom false)}
         now (.instant ^Clock clock)]
     (doseq [job jobs]
       (arm! executor job (schedule/next-run (:schedule job) now)))
     (t/event! :pulse.scheduler/started
               {:level :info
                :data {:jobs (mapv (fn [{:keys [id]}] {:id id :next-run (str (get-in @(:state executor) [id :next-run]))})
                                   jobs)}})
     executor)))

(defn running?
  "True until `stop!` has been called."
  [{:keys [stopped?]}]
  (not @stopped?))

(defn status
  "What each job is doing, in the order of the catalogue: a map of `:id`, `:description`,
  `:schedule`, `:state` (`:idle` or `:running`), `:running-since`, `:next-run` (when its next tick
  falls due), `:last-run` (`:trigger`, `:started-at`, `:finished-at`, `:duration-ms`, `:outcome`
  and the handler's `:result` or the class of its `:error`) and `:counts` of the runs by outcome
  and of the ticks skipped. All in memory: it starts from nothing when the process does."
  [{:keys [jobs state]}]
  (let [snapshot @state]
    (mapv (fn [{:keys [id description schedule]}]
            (let [job-state (get snapshot id)]
              (assoc job-state
                     :id id
                     :description description
                     :schedule schedule
                     :state (if (:running-since job-state) :running :idle))))
          jobs)))

(defn job-status
  "The entry of `status` for job `id`, or nil if there is no such job."
  [executor id]
  (first (filter #(= id (:id %)) (status executor))))

(defn run-now!
  "Runs job `id` now, in the background, as a run apart from its schedule, which it does not
  change. Returns `:started`, `:already-running` (one run at a time, whoever asks), `:unknown-job`
  or `:stopped`. The events of the run carry the log context of the caller."
  [{:keys [by-id state stopped? ^Clock clock] :as executor} id]
  (cond
    @stopped? :stopped
    (not (contains? by-id id)) :unknown-job
    :else (let [now (.instant clock)
                result (if (claim! state id now)
                         (if (dispatch! executor (get by-id id) {:trigger :manual :started-at now})
                           :started
                           :stopped)
                         :already-running)]
            (t/event! :pulse.scheduler/manual-run
                      {:level :info :data {:job id :result result}})
            result)))

(defn- running-ids [state]
  (into [] (keep (fn [[id job-state]] (when (:running-since job-state) id))) state))

(defn stop!
  "Stops the executor in order: no more ticks, then the handlers in flight get the grace period
  to finish and are interrupted if they have not, and what was interrupted is logged. Calling it
  again does nothing."
  [{:keys [^ScheduledExecutorService scheduler ^ExecutorService workers grace-ms state stopped?]}]
  (when (compare-and-set! stopped? false true)
    (t/event! :pulse.scheduler/stopping {:level :info :data {:running (running-ids @state)}})
    (.shutdownNow scheduler)
    (try
      ;; a tick that was running when the scheduler was told to stop is interrupted, and is over in a moment: when this returns, no
      ;; tick is running or will, and none can hand a job to the workers once they are shut down
      (.awaitTermination scheduler 2000 TimeUnit/MILLISECONDS)
      (.shutdown workers)
      (when-not (.awaitTermination workers grace-ms TimeUnit/MILLISECONDS)
        (t/event! :pulse.scheduler/interrupting {:level :warn :data {:jobs (running-ids @state)}})
        (.shutdownNow workers)
        (.awaitTermination workers 2000 TimeUnit/MILLISECONDS))
      (catch InterruptedException _
        (.shutdownNow workers)
        (.interrupt (Thread/currentThread))))
    (t/event! :pulse.scheduler/stopped {:level :info :data {}})))
