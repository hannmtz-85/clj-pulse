(ns pulse.scheduler.test.time
  "Time under the control of a test (ADR-0020): a clock that moves only when told to, a tick
  scheduler that holds what is scheduled and runs it as the clock is moved past it, and workers
  that run a job on the spot. With them the executor's behaviour at a given moment can be
  reached without waiting for it.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:import (java.time Clock Instant ZoneOffset)
           (java.util.concurrent ExecutorService RejectedExecutionException ScheduledExecutorService
                                 ScheduledFuture TimeUnit)))

(defn world
  "A world of time that begins at `start`, an ISO instant, with a `:clock`, a `:scheduler` (a
  `ScheduledExecutorService`, of which only what the executor uses is implemented) and the
  state they share. Move it with `advance!`."
  [start]
  (let [now (atom (Instant/parse start))
        pending (atom [])
        counter (atom 0)
        shut-down? (atom false)]
    {:now now
     :pending pending
     :clock (proxy [Clock] []
              (instant [] @now)
              (getZone [] ZoneOffset/UTC)
              (withZone [_] this))
     :scheduler (reify ScheduledExecutorService
                  ;; `schedule` is overloaded (Runnable and Callable), so the return type is named
                  (^ScheduledFuture schedule [_ ^Runnable task ^long delay ^TimeUnit unit]
                    (when @shut-down? (throw (RejectedExecutionException. "shut down")))
                    (swap! pending conj {:due (.plusMillis ^Instant @now (.toMillis unit delay))
                                         :order (swap! counter inc)
                                         :task task})
                    nil)
                  (shutdown [_] (reset! shut-down? true))
                  (shutdownNow [_] (reset! shut-down? true) (reset! pending []) [])
                  (isShutdown [_] @shut-down?)
                  (isTerminated [_] @shut-down?)
                  (awaitTermination [_ _ _] true))}))

(defn pending
  "When each tick that is waiting falls due, in order, as ISO strings."
  [{:keys [pending]}]
  (mapv (comp str :due) (sort-by (juxt :due :order) @pending)))

(defn- take-next!
  "Removes and returns the pending tick due first (the first scheduled, among equals) if it is due
  by `limit`, an instant, or at all when `limit` is nil."
  [{:keys [pending]} limit]
  (let [next-tick (first (sort-by (juxt :due :order) @pending))]
    (when (and next-tick (or (nil? limit) (not (.isAfter ^Instant (:due next-tick) ^Instant limit))))
      (swap! pending (fn [ticks] (vec (remove #{next-tick} ticks))))
      next-tick)))

(defn advance!
  "Moves time forward by `ms` milliseconds, running each tick that falls due on the way, at its
  own moment and in order, also those that running one schedules. Returns the world."
  [{:keys [now] :as world} ms]
  (let [target (.plusMillis ^Instant @now ms)]
    (loop []
      (when-let [{:keys [due task]} (take-next! world target)]
        (when (.isAfter ^Instant due ^Instant @now)
          (reset! now due))
        (.run ^Runnable task)
        (recur)))
    (reset! now target)
    world))

(defn fire-next!
  "Runs the pending tick that is due first, without moving the clock: a tick that fires early,
  or late, by the clock. Returns true, or false if none is waiting."
  [world]
  (if-let [{:keys [task]} (take-next! world nil)]
    (do (.run ^Runnable task) true)
    false))

(defn set-time!
  "Moves the clock to `instant`, an ISO string, running nothing."
  [{:keys [now]} instant]
  (reset! now (Instant/parse instant)))

(defn direct-workers
  "An executor that runs each task at once, on the thread that submits it."
  []
  (let [shut-down? (atom false)]
    (reify ExecutorService
      (execute [_ task]
        (when @shut-down? (throw (RejectedExecutionException. "shut down")))
        (.run ^Runnable task))
      (shutdown [_] (reset! shut-down? true))
      (shutdownNow [_] (reset! shut-down? true) [])
      (isShutdown [_] @shut-down?)
      (isTerminated [_] @shut-down?)
      (awaitTermination [_ _ _] true))))
