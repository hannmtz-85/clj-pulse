(ns pulse.scheduler.pipeline
  "A pipeline from a sequence of items to batches that a function writes (ADR-0023), on core.async.

    producer -> source -> [transducers] -> transformed -> [batches] -> batched -> writer

  A producer thread puts the items, a lazy sequence that it realizes one by one, on a channel.
  `pipeline` runs the transducers on them in parallel and keeps their order. A `partition-all`
  transducer on the next channel makes the batches, and the last, incomplete one is flushed when the
  items end. One writer thread, not a `go` block since it does blocking I/O, hands each batch to the
  function that writes it.

  Every channel is bounded, so a slow writer slows the producer down and nothing grows: the number of
  items taken from the sequence is never far ahead of the number written.

  A batch that cannot be written is logged and counted and the next ones go on. An item that a
  transducer throws on is logged, counted and left out. What a run did is data (see `run!`).

  An orderly shutdown: an interruption of the thread that called `run!`, or a run that takes longer
  than its timeout, closes the source so that nothing more is read, gives what is in the pipeline a
  moment (`:drain-ms`) to be written, and throws an ex-info that says what was done."
  (:refer-clojure :exclude [run!])
  (:require [clojure.core.async :as a]
            [taoensso.telemere :as t]))

(def defaults
  "The settings of a run, each of which `run!` takes in its options."
  {:buffer 16        ; room in the channels between the stages
   :parallelism 2    ; threads of the stage of the transducers
   :batch-size 20    ; items to a batch
   :batches 1        ; batches that wait for the writer
   :timeout-ms 30000 ; for the whole run
   :drain-ms 2000})  ; what an interrupted or late run waits for what is in the pipeline

(defn run!
  "Sends `items` through the transducer `xf` (applied to each item alone, so a stateless one such as
  `map`, `filter` or `keep`) and the result in batches of `:batch-size` to `write!`, a function of a
  batch (a vector) that throws when it cannot write it. Options: those of `defaults`.

  Returns the numbers `:read` (items taken from `items`), `:written` and `:batches` (those that were
  written), `:failed` and `:failed-batches` (those that could not be) and `:errors` (items that a
  transducer threw on). An item that `xf` leaves out is read and neither written nor failed.

  Throws an ex-info of `:type` `::timeout`, `::interrupted` (with the InterruptedException as its
  cause) or `::source-failed` (when realizing `items` threw, once what was read has been written),
  each with the `:stats` so far."
  ([items xf write!] (run! items xf write! {}))
  ([items xf write! options]
   (let [{:keys [buffer parallelism batch-size batches timeout-ms drain-ms]} (merge defaults options)
         source (a/chan buffer)
         transformed (a/chan buffer)
         batched (a/chan batches (partition-all batch-size))
         taken (atom 0)
         errors (atom 0)
         stats (atom {:written 0 :batches 0 :failed 0 :failed-batches 0})
         stats-so-far #(assoc @stats :read @taken :errors @errors)
         producer (a/thread
                    (try
                      (doseq [item items
                              :while (a/>!! source item)]
                        (swap! taken inc))
                      nil
                      (catch Throwable e e)
                      (finally (a/close! source))))
         _ (a/pipeline parallelism transformed xf source true
                       (fn [e]
                         (swap! errors inc)
                         (t/event! :pulse.pipeline/item-failed {:level :warn :error e})
                         nil))
         _ (a/pipe transformed batched true)
         writer (a/thread
                  (loop []
                    (when-some [batch (a/<!! batched)]
                      (try
                        (write! batch)
                        (swap! stats #(-> % (update :written + (count batch)) (update :batches inc)))
                        (catch Exception e
                          (t/event! :pulse.pipeline/batch-failed
                                    {:level :warn :error e :data {:items (count batch)}})
                          (swap! stats #(-> % (update :failed + (count batch)) (update :failed-batches inc)))))
                      (recur)))
                  :done)
         stop! (fn []
                 (a/close! source)
                 (a/alts!! [writer (a/timeout drain-ms)]))]
     (try
       (let [[_ port] (a/alts!! [writer (a/timeout timeout-ms)])]
         (if (identical? port writer)
           (if-some [failure (a/<!! producer)]
             (throw (ex-info "The source of the pipeline failed"
                             {:type ::source-failed :stats (stats-so-far)} failure))
             (stats-so-far))
           (do (stop!)
               (throw (ex-info "The pipeline did not finish in time"
                               {:type ::timeout :timeout-ms timeout-ms :stats (stats-so-far)})))))
       (catch InterruptedException e
         (stop!)
         (throw (ex-info "The pipeline was interrupted" {:type ::interrupted :stats (stats-so-far)} e)))))))
