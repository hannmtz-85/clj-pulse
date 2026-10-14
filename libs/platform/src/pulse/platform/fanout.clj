(ns pulse.platform.fanout
  "Running several independent calls at once, under one deadline (ADR-0013). JVM only.

  A request that needs a dozen queries should take as long as the slowest one, not as long as
  all of them, and a slow or failing query should cost its own section, not the whole answer."
  (:import (java.util.concurrent Callable ExecutionException Executors Future TimeUnit TimeoutException)))

(defn- outcome
  "What happened to `fut`, waiting for it no longer than until `deadline` (a `System/nanoTime`).
  A task that is already done is collected even when the deadline has passed."
  [^Future fut deadline]
  (try
    {:ok (.get fut (max 0 (- deadline (System/nanoTime))) TimeUnit/NANOSECONDS)}
    (catch TimeoutException _
      (.cancel fut true)
      {:timeout true})
    (catch ExecutionException e
      {:error (or (ex-cause e) e)})))

(defn run
  "Runs `tasks`, a map of name to a function of no arguments, all at once, each on its own
  virtual thread, and waits for them until `timeout-ms` after the call, in total. Returns a map
  of the same names to what became of each:

    {:ok value}         the function returned `value`
    {:error exception}  it threw
    {:timeout true}     it was not done at the deadline: it has been cancelled, which
                        interrupts it, and it is not waited for

  The thread bindings of the caller, a log context among them, are carried over to the tasks.
  A task that ignores interruption is abandoned, not waited for."
  [tasks {:keys [timeout-ms]}]
  (let [executor (Executors/newVirtualThreadPerTaskExecutor)]
    (try
      (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))
            futures (update-vals tasks (fn [task] (.submit executor ^Callable (bound-fn* task))))]
        (update-vals futures (fn [fut] (outcome fut deadline))))
      (finally
        (.shutdownNow executor)))))
