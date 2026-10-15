(ns pulse.scheduler.pipeline-test
  "The pipeline of the device simulator without FHIR (ADR-0023): the items go through in order and in
  batches, a batch or an item that fails is counted and the rest go on, a slow writer slows the source
  down, and an interrupted or late run stops reading and says what it did."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.scheduler.pipeline :as pipeline]
            [taoensso.telemere :as t])
  (:import (java.util.concurrent TimeUnit)))

(defn- eventually
  "True once `pred` is, within five seconds."
  [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 2)
            (recur))))))

(defn- settled
  "The value of `f` once it has stayed the same for a quarter of a second."
  [f]
  (loop [before (f)]
    (Thread/sleep 250)
    (let [now (f)]
      (if (= before now) now (recur now)))))

(defn- collecting
  "A writer that keeps what it is given: `[write! batches]`, the atom holding the batches."
  []
  (let [batches (atom [])]
    [(fn [batch] (swap! batches conj batch)) batches]))

;; ---------------------------------------------------------------------------
;; What goes through

(deftest everything-is-written-in-order-and-in-batches
  (let [[write! batches] (collecting)
        stats (pipeline/run! (range 100) (map inc) write! {:batch-size 7 :parallelism 4})]
    (is (= {:read 100 :written 100 :batches 15 :failed 0 :failed-batches 0 :errors 0} stats))
    (is (= (concat (repeat 14 7) [2]) (map count @batches)) "the last batch is what is left")
    (is (= (map inc (range 100)) (apply concat @batches)) "in the order they came, however many threads")))

(deftest the-transducers-change-and-leave-out
  (let [[write! batches] (collecting)
        stats (pipeline/run! (range 20) (comp (filter even?) (map #(* % 10))) write! {:batch-size 4})]
    (is (= (map #(* % 10) (range 0 20 2)) (apply concat @batches)))
    (is (= {:read 20 :written 10 :batches 3 :failed 0 :failed-batches 0 :errors 0} stats)
        "what a transducer leaves out was read and is neither written nor failed")))

(deftest nothing-to-send-is-no-batches
  (let [[write! batches] (collecting)]
    (is (= {:read 0 :written 0 :batches 0 :failed 0 :failed-batches 0 :errors 0}
           (pipeline/run! [] (map identity) write!)))
    (is (empty? @batches))))

(deftest a-batch-is-not-lost-for-being-the-last-and-small
  (let [[write! batches] (collecting)]
    (pipeline/run! (range 3) (map identity) write! {:batch-size 20})
    (is (= [[0 1 2]] @batches))))

;; ---------------------------------------------------------------------------
;; What fails

(deftest a-batch-that-cannot-be-written-is-counted-and-the-rest-go-on
  (let [written (atom [])
        write! (fn [batch]
                 (when (some #{5} batch) (throw (ex-info "FHIR refused" {})))
                 (swap! written conj batch))
        {:keys [value signals]} (t/with-signals true (pipeline/run! (range 12) (map identity) write! {:batch-size 4}))
        failures (filter #(= :pulse.pipeline/batch-failed (:id %)) signals)]
    (is (= {:read 12 :written 8 :batches 2 :failed 4 :failed-batches 1 :errors 0} value))
    (is (= [[0 1 2 3] [8 9 10 11]] @written))
    (testing "logged as a warning, with the exception and how many items"
      (is (= 1 (count failures)))
      (is (= :warn (:level (first failures))))
      (is (= "FHIR refused" (ex-message (:error (first failures)))))
      (is (= 4 (get-in (first failures) [:data :items]))))))

(deftest an-item-that-a-transducer-throws-on-is-counted-and-left-out
  (let [[write! batches] (collecting)
        {:keys [value signals]} (t/with-signals true
                                  (pipeline/run! (range 6)
                                                 (map #(if (= 3 %) (throw (ex-info "bad item" {})) %))
                                                 write! {:batch-size 10}))]
    (is (= {:read 6 :written 5 :batches 1 :failed 0 :failed-batches 0 :errors 1} value))
    (is (= [[0 1 2 4 5]] @batches))
    (is (= ["bad item"] (map (comp ex-message :error) (filter #(= :pulse.pipeline/item-failed (:id %)) signals))))))

(deftest a-source-that-fails-fails-the-run-after-what-it-gave-is-written
  (let [[write! batches] (collecting)
        items (concat (range 5) (lazy-seq (throw (ex-info "the plan broke" {}))))
        e (try (pipeline/run! items (map identity) write! {:batch-size 2})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::pipeline/source-failed (:type (ex-data e))))
    (is (= "the plan broke" (ex-message (ex-cause e))))
    (is (= {:read 5 :written 5 :batches 3 :failed 0 :failed-batches 0 :errors 0} (:stats (ex-data e))))
    (is (= (range 5) (apply concat @batches)))))

;; ---------------------------------------------------------------------------
;; Backpressure

(deftest a-slow-writer-slows-the-source-down
  (let [realized (atom 0)
        stop? (atom false)
        gate (promise)
        written (atom [])
        items (->> (range)
                   (map (fn [i] (swap! realized inc) i))
                   (take-while (fn [_] (not @stop?))))
        run (future (pipeline/run! items (map identity)
                                   (fn [batch] @gate (swap! written into batch))
                                   {:buffer 4 :parallelism 2 :batch-size 3 :batches 1}))]
    (is (eventually #(pos? @realized)))
    (let [ahead (settled #(deref realized))]
      (is (< ahead 40) (str ahead " items were taken from a source that could give for ever, with the writer stuck"))
      (is (> ahead 3) "and the pipeline did fill up")
      (reset! stop? true)
      (deliver gate :go)
      (let [{n-read :read n-written :written n-failed :failed} @run]
        (is (= n-read n-written (count @written)) "the writer got everything that was taken")
        (is (zero? n-failed))
        (is (= (range n-read) @written) "in order")))))

;; ---------------------------------------------------------------------------
;; Orderly shutdown

(defn- on-a-thread
  "Runs `f` on a new thread; returns `[thread result]`, the promise of `{:value v}` or `{:error e}`."
  [f]
  (let [result (promise)
        thread (Thread. ^Runnable (fn []
                                    (deliver result (try {:value (f)}
                                                         (catch Throwable e {:error e})))))]
    (.start thread)
    [thread result]))

(deftest an-interrupted-run-stops-reading-writes-what-is-in-the-pipeline-and-says-so
  (let [realized (atom 0)
        gate (promise)
        started (promise)
        written (atom [])
        items (map (fn [i] (swap! realized inc) i) (range 100000))
        [^Thread thread result] (on-a-thread
                                 (fn [] (pipeline/run! items (map identity)
                                                       (fn [batch] (deliver started true) @gate (swap! written into batch))
                                                       {:buffer 4 :parallelism 2 :batch-size 3 :batches 1 :drain-ms 10000})))]
    (is (deref started 5000 false) "the writer has a batch in its hands")
    (settled #(deref realized))
    (.interrupt thread)
    (testing "it waits for what is in the pipeline, which cannot be written yet"
      (is (not= :done (deref result 400 :waiting))))
    (deliver gate :go)
    (let [{:keys [error]} (deref result 5000 nil)
          stats (:stats (ex-data error))]
      (is (= ::pipeline/interrupted (:type (ex-data error))))
      (is (instance? InterruptedException (ex-cause error)) "so the executor knows what it was")
      (is (< (:read stats) 100000) "it stopped reading")
      (is (= (:read stats) (:written stats) (count @written)) "and everything it had read was written")
      (is (= (range (:read stats)) @written) "in order"))))

(deftest a-run-that-takes-too-long-gives-up-and-says-what-it-did
  (let [gate (promise)
        started (System/nanoTime)
        e (try (pipeline/run! (range 100) (map identity) (fn [_] @gate) {:batch-size 5 :timeout-ms 200 :drain-ms 100})
               (catch clojure.lang.ExceptionInfo e e))
        took-ms (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) started))]
    (try
      (is (= ::pipeline/timeout (:type (ex-data e))))
      (is (= 200 (:timeout-ms (ex-data e))))
      (is (pos? (get-in (ex-data e) [:stats :read])))
      (is (< 250 took-ms 2000) "after its time and a moment to drain, and no more")
      (finally (deliver gate :go)))))
