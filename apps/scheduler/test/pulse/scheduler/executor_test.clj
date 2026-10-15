(ns pulse.scheduler.executor-test
  "The executor with time in the hands of the test (`pulse.scheduler.test.time`): a fake clock and
  a fake tick scheduler, and workers that run a job on the spot, or real virtual threads where the
  point is that two things happen at once. A few tests at the end use the real pools."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.scheduler.executor :as executor]
            [pulse.scheduler.job :as job]
            [pulse.scheduler.test.time :as time]
            [taoensso.telemere :as t])
  (:import (java.time Instant)
           (java.util.concurrent Executors)))

(def ^:private every-minute {:type :interval :every-ms 60000})

(defn- a-job [id schedule handler]
  {:id id :description (str "The job " (name id)) :schedule schedule :handler handler})

(defn- instant [text] (Instant/parse text))

(defn- with-executor
  "Starts an executor for `jobs` in a world of fake time that begins at `:start` and calls
  `(f executor world)`; stops it afterwards. `:workers` are direct by default."
  [{:keys [jobs start workers grace-ms] :or {start "2026-10-15T10:02:13Z"}} f]
  (let [world (time/world start)
        ex (executor/start jobs (cond-> {:clock (:clock world)
                                         :scheduler (:scheduler world)
                                         :workers (or workers (time/direct-workers))}
                                  grace-ms (assoc :grace-ms grace-ms)))]
    (try
      (f ex world)
      (finally
        (executor/stop! ex)))))

(defn- eventually
  "Calls `pred` until it is truthy, for up to 5 seconds, and returns what it said (nil if it never
  did). For waiting on a thread, not for waiting on time."
  [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (or (pred)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep 1)
            (recur))))))

(defn- idle? [ex id]
  (= :idle (:state (executor/job-status ex id))))

(defn- events
  "The events with the given `id` among the trapped `signals`."
  [signals id]
  (filter #(= id (:id %)) signals))

;; ---------------------------------------------------------------------------
;; Start

(deftest starting-arms-the-first-tick-of-each-job-and-runs-nothing
  (let [ran (atom 0)
        handler (fn [_] (swap! ran inc) nil)
        quarter-past {:type :interval :every-ms 3600000 :offset-ms 900000}
        morning {:type :daily :at "08:30" :zone "Europe/Madrid"}]
    (with-executor {:jobs [(a-job :every-minute every-minute handler)
                           (a-job :quarter-past quarter-past handler)
                           (a-job :morning morning handler)]}
      (fn [ex world]
        (is (= ["2026-10-15T10:03:00Z" "2026-10-15T10:15:00Z" "2026-10-16T06:30:00Z"]
               (time/pending world)))
        (is (zero? @ran) "nothing runs at start-up")
        (testing "the status, in the order of the catalogue"
          (is (= [:every-minute :quarter-past :morning] (map :id (executor/status ex))))
          (is (= {:id :quarter-past :description "The job quarter-past" :schedule quarter-past
                  :state :idle :running-since nil :last-run nil
                  :next-run (instant "2026-10-15T10:15:00Z")
                  :counts {:ok 0 :failed 0 :interrupted 0 :skipped 0}}
                 (executor/job-status ex :quarter-past)))
          (is (nil? (executor/job-status ex :nope))))
        (is (executor/running? ex))))))

(deftest a-catalogue-that-is-not-sound-does-not-start
  (let [e (try (executor/start [{:id :a}])
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::job/invalid-jobs (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Ticks

(deftest a-tick-runs-the-job-and-arms-the-next
  (let [calls (atom [])]
    (with-executor {:jobs [(a-job :every-minute every-minute (fn [context] (swap! calls conj context) {:evaluated 6}))]}
      (fn [ex world]
        (time/advance! world 46999)
        (is (empty? @calls) "a moment before it is due")
        (time/advance! world 1)
        (is (= [{:job-id :every-minute :trigger :schedule
                 :due (instant "2026-10-15T10:03:00Z") :started-at (instant "2026-10-15T10:03:00Z")}]
               @calls))
        (is (= {:trigger :schedule :started-at (instant "2026-10-15T10:03:00Z")
                :finished-at (instant "2026-10-15T10:03:00Z") :duration-ms 0
                :outcome :ok :result {:evaluated 6}}
               (:last-run (executor/job-status ex :every-minute))))
        (is (= {:ok 1 :failed 0 :interrupted 0 :skipped 0} (:counts (executor/job-status ex :every-minute))))
        (is (= ["2026-10-15T10:04:00Z"] (time/pending world)))
        (time/advance! world (* 3 60000))
        (is (= 4 (count @calls)) "one a minute, on the minute")))))

(deftest a-run-records-when-it-began-and-how-long-it-took
  (let [the-world (atom nil)]
    (with-executor {:jobs [(a-job :j every-minute (fn [_]
                                                    (time/set-time! @the-world "2026-10-15T10:03:01.500Z")
                                                    nil))]}
      (fn [ex world]
        (reset! the-world world)
        (time/set-time! world "2026-10-15T10:03:00Z")
        (time/fire-next! world)
        (is (= {:started-at (instant "2026-10-15T10:03:00Z")
                :finished-at (instant "2026-10-15T10:03:01.500Z")
                :duration-ms 1500}
               (select-keys (:last-run (executor/job-status ex :j)) [:started-at :finished-at :duration-ms])))))))

(deftest what-a-handler-returns-is-shown-as-simple-values
  (doseq [[returned shown] [[{:evaluated 6 :raised 2.5 :note "ok" :flag true :kind :rule :nested {:a 1} :nothing nil :list [1 2]}
                             {:evaluated 6 :raised 2.5 :note "ok" :flag true :kind "rule"}]
                            [nil nil]
                            [42 nil]
                            ["done" nil]
                            [[1 2] nil]]]
    (with-executor {:jobs [(a-job :j every-minute (fn [_] returned))]}
      (fn [ex world]
        (time/advance! world 47000)
        (is (= shown (:result (:last-run (executor/job-status ex :j)))) (pr-str returned))
        (is (= (contains? (:last-run (executor/job-status ex :j)) :result) (some? shown)))))))

(deftest a-failing-handler-is-recorded-and-the-schedule-goes-on
  (let [calls (atom 0)
        {:keys [signals]} (t/with-signals true
                            (with-executor {:jobs [(a-job :j every-minute (fn [_]
                                                                            (swap! calls inc)
                                                                            (throw (ex-info "the database password is hunter2" {}))))]}
                              (fn [ex world]
                                (time/advance! world 47000)
                                (let [shown (executor/job-status ex :j)]
                                  (is (= :failed (get-in shown [:last-run :outcome])))
                                  (is (= "clojure.lang.ExceptionInfo" (get-in shown [:last-run :error])))
                                  (is (not (str/includes? (pr-str shown) "hunter2")) "the status says the class and no more")
                                  (is (= :idle (:state shown))))
                                (time/advance! world 60000)
                                (is (= 2 @calls) "the next minute it runs again")
                                (is (= {:ok 0 :failed 2 :interrupted 0 :skipped 0} (:counts (executor/job-status ex :j)))))))
        finished (events signals :pulse.scheduler/job-finished)]
    (testing "the log has the exception, at error level"
      (is (= 2 (count finished)))
      (is (every? #(= :error (:level %)) finished))
      (is (every? #(= "the database password is hunter2" (ex-message (:error %))) finished))
      (is (= {:job :j :trigger :schedule :outcome :failed} (select-keys (:data (first finished)) [:job :trigger :outcome]))))))

(deftest a-handler-that-throws-an-error-fails-like-any-other-and-leaves-the-job-free
  (with-executor {:jobs [(a-job :j every-minute (fn [_] (throw (AssertionError. "an invariant"))))]}
    (fn [ex world]
      (time/advance! world 47000)
      (let [shown (executor/job-status ex :j)]
        (is (= :failed (get-in shown [:last-run :outcome])))
        (is (= "java.lang.AssertionError" (get-in shown [:last-run :error])))
        (is (= :idle (:state shown)))))))

(deftest an-interruption-wrapped-in-another-exception-is-still-an-interruption
  (let [wrapped (fn [_] (try (throw (InterruptedException. "stop"))
                             (catch InterruptedException e (throw (ex-info "the call was cut short" {} e)))))]
    (with-executor {:jobs [(a-job :j every-minute wrapped)
                           (a-job :k every-minute (fn [_] (throw (ex-info "no" {} (ex-info "cause" {})))))]}
      (fn [ex world]
        (time/advance! world 47000)
        (is (= :interrupted (get-in (executor/job-status ex :j) [:last-run :outcome])))
        (is (= "clojure.lang.ExceptionInfo" (get-in (executor/job-status ex :j) [:last-run :error])))
        (is (= :failed (get-in (executor/job-status ex :k) [:last-run :outcome]))
            "while a cause that is not an interruption is a failure")))))

(deftest workers-that-no-longer-take-work-do-not-leave-a-job-claimed
  (let [calls (atom 0)
        workers (time/direct-workers)]
    (with-executor {:workers workers
                    :jobs [(a-job :j every-minute (fn [_] (swap! calls inc)))]}
      (fn [ex world]
        (.shutdown ^java.util.concurrent.ExecutorService workers)
        (is (= :stopped (executor/run-now! ex :j)))
        (is (= :idle (:state (executor/job-status ex :j))) "the claim was given back")
        (time/advance! world 47000)
        (is (= :idle (:state (executor/job-status ex :j))))
        (is (zero? @calls))
        (is (= {:ok 0 :failed 0 :interrupted 0 :skipped 0} (:counts (executor/job-status ex :j))))))))

(deftest an-exception-in-a-tick-does-not-end-the-chain-of-ticks
  (let [calls (atom 0)
        claim! @#'executor/claim!]
    (with-executor {:jobs [(a-job :j every-minute (fn [_] (swap! calls inc)))]}
      (fn [_ world]
        (let [{:keys [signals]} (t/with-signals true
                                  (with-redefs [executor/claim! (fn [& _] (throw (ex-info "broken" {})))]
                                    (time/advance! world 47000)))]
          (is (zero? @calls))
          (is (= 1 (count (events signals :pulse.scheduler/tick-failed)))))
        (is (= ["2026-10-15T10:04:00Z"] (time/pending world)) "the next tick was armed first")
        (with-redefs [executor/claim! claim!]
          (time/advance! world 60000))
        (is (= 1 @calls) "and it runs when the trouble is over")))))

(deftest a-tick-that-fires-early-does-not-run-its-slot-twice
  (let [calls (atom [])]
    (with-executor {:jobs [(a-job :j every-minute (fn [context] (swap! calls conj context) nil))]}
      (fn [_ world]
        ;; by the clock, five milliseconds before the minute
        (time/set-time! world "2026-10-15T10:02:59.995Z")
        (is (true? (time/fire-next! world)))
        (is (= [{:job-id :j :trigger :schedule
                 :due (instant "2026-10-15T10:03:00Z") :started-at (instant "2026-10-15T10:02:59.995Z")}]
               @calls))
        (is (= ["2026-10-15T10:04:00Z"] (time/pending world)) "the next slot, not this one again")))))

(deftest a-tick-that-fires-late-runs-once-and-the-missed-slots-are-skipped
  (let [calls (atom [])]
    (with-executor {:start "2026-10-15T10:00:30Z"
                    :jobs [(a-job :j every-minute (fn [context] (swap! calls conj context) nil))]}
      (fn [_ world]
        (is (= ["2026-10-15T10:01:00Z"] (time/pending world)))
        ;; the machine was asleep for four minutes
        (time/set-time! world "2026-10-15T10:05:20Z")
        (is (true? (time/fire-next! world)))
        (is (= 1 (count @calls)))
        (is (= {:due (instant "2026-10-15T10:01:00Z") :started-at (instant "2026-10-15T10:05:20Z")}
               (select-keys (first @calls) [:due :started-at])))
        (is (= ["2026-10-15T10:06:00Z"] (time/pending world)) "from now, not from the slot that was missed")))))

(deftest a-daily-job-runs-at-its-time-of-day-through-a-change-of-clocks
  ;; Madrid goes back an hour in the night to 2026-10-25, at 01:00 UTC: 08:30 on the 24th is 06:30
  ;; UTC, and on the 25th and after it is 07:30
  (let [calls (atom [])]
    (with-executor {:start "2026-10-23T12:00:00Z"
                    :jobs [(a-job :morning {:type :daily :at "08:30" :zone "Europe/Madrid"}
                                  (fn [context] (swap! calls conj (str (:due context))) nil))]}
      (fn [_ world]
        (time/advance! world (* 3 24 60 60 1000))
        (is (= ["2026-10-24T06:30:00Z" "2026-10-25T07:30:00Z" "2026-10-26T07:30:00Z"] @calls)
            "every day at 08:30 on the clock of Madrid, which is not every 24 hours")))))

;; ---------------------------------------------------------------------------
;; One run at a time

(deftest a-job-is-never-run-twice-at-once
  (let [gate (promise)
        entered (atom 0)
        workers (Executors/newVirtualThreadPerTaskExecutor)
        {:keys [signals]} (t/with-signals true
                            (with-executor {:workers workers
                                            :jobs [(a-job :slow every-minute (fn [_] (swap! entered inc) @gate {:done true}))]}
                              (fn [ex world]
                                (time/advance! world 47000)
                                (is (eventually #(= 1 @entered)))
                                (is (= :running (:state (executor/job-status ex :slow))))
                                (is (= (instant "2026-10-15T10:03:00Z") (:running-since (executor/job-status ex :slow))))
                                (testing "the ticks that fall due meanwhile are skipped, and the schedule goes on"
                                  (time/advance! world (* 3 60000))
                                  (is (= 1 @entered))
                                  (is (= 3 (get-in (executor/job-status ex :slow) [:counts :skipped])))
                                  (is (= ["2026-10-15T10:07:00Z"] (time/pending world))))
                                (deliver gate :go)
                                (is (eventually #(idle? ex :slow)))
                                (is (= {:ok 1 :failed 0 :interrupted 0 :skipped 3} (:counts (executor/job-status ex :slow))))
                                (testing "and when it is done the next tick runs it again"
                                  (time/advance! world 60000)
                                  (is (eventually #(= 2 @entered)))
                                  (is (eventually #(idle? ex :slow)))))))]
    (is (= 3 (count (events signals :pulse.scheduler/job-skipped))))
    (is (every? #(= :warn (:level %)) (events signals :pulse.scheduler/job-skipped)))))

(deftest a-run-longer-than-its-period-does-not-pile-up-a-queue
  (let [gate (promise)
        entered (atom 0)]
    (with-executor {:workers (Executors/newVirtualThreadPerTaskExecutor)
                    :jobs [(a-job :slow every-minute (fn [_] (swap! entered inc) @gate nil))]}
      (fn [ex world]
        (time/advance! world 47000)
        (is (eventually #(= 1 @entered)))
        (time/advance! world (* 60 60000))
        (deliver gate :go)
        (is (eventually #(idle? ex :slow)))
        (time/advance! world 60000)
        (is (eventually #(= 2 @entered)))
        (is (eventually #(idle? ex :slow)))
        (is (= 2 @entered) "an hour of ticks went by while it ran, and it ran once more, not sixty times")))))

;; ---------------------------------------------------------------------------
;; Manual runs

(deftest a-manual-run-is-a-run-apart-from-the-schedule
  (let [calls (atom [])]
    (with-executor {:jobs [(a-job :j every-minute (fn [context] (swap! calls conj context) {:evaluated 1}))]}
      (fn [ex world]
        (let [before (time/pending world)]
          (is (= :started (executor/run-now! ex :j)))
          (is (= [{:job-id :j :trigger :manual :due nil :started-at (instant "2026-10-15T10:02:13Z")}] @calls))
          (is (= {:trigger :manual :started-at (instant "2026-10-15T10:02:13Z")
                  :finished-at (instant "2026-10-15T10:02:13Z") :duration-ms 0
                  :outcome :ok :result {:evaluated 1}}
                 (:last-run (executor/job-status ex :j))))
          (is (= before (time/pending world)) "the schedule is where it was"))
        (time/advance! world 47000)
        (is (= [:manual :schedule] (map :trigger @calls)) "and the scheduled run still comes")))))

(deftest a-manual-run-answers-for-what-it-cannot-do
  (let [gate (promise)
        entered (atom 0)]
    (with-executor {:workers (Executors/newVirtualThreadPerTaskExecutor)
                    :jobs [(a-job :slow every-minute (fn [_] (swap! entered inc) @gate nil))]}
      (fn [ex world]
        (is (= :unknown-job (executor/run-now! ex :nope)))
        (is (= :started (executor/run-now! ex :slow)))
        (is (eventually #(= 1 @entered)))
        (is (= :already-running (executor/run-now! ex :slow)) "one run at a time, whoever asks")
        (time/advance! world 47000)
        (is (= 1 (get-in (executor/job-status ex :slow) [:counts :skipped])) "the tick finds the manual run going")
        (is (= 1 @entered))
        (deliver gate :go)
        (is (eventually #(idle? ex :slow)))
        (is (= :started (executor/run-now! ex :slow)) "and when it is over, it can be asked again")
        (is (eventually #(idle? ex :slow)))
        (executor/stop! ex)
        (is (= :stopped (executor/run-now! ex :slow)))))))

(deftest a-manual-run-carries-the-log-context-of-whoever-asked
  ;; What the handler sees in `t/*ctx*` is what every event of the run is logged with. The run is
  ;; on another thread, so this is the thread's binding being carried over, not a coincidence.
  (let [seen (promise)]
    (with-executor {:workers (Executors/newVirtualThreadPerTaskExecutor)
                    :jobs [(a-job :j every-minute (fn [_] (deliver seen t/*ctx*) nil))]}
      (fn [ex _]
        (t/with-ctx+ {:request-id "req-7"}
          (executor/run-now! ex :j))
        (let [ctx (deref seen 5000 nil)]
          (is (= "req-7" (:request-id ctx)))
          (is (= {:job :j :trigger :manual} (select-keys ctx [:job :trigger]))
              "and the job and its trigger are in the context of everything a run logs"))
        (is (eventually #(idle? ex :j)))))))

(deftest a-scheduled-run-has-the-job-and-its-trigger-in-its-log-context
  (let [seen (promise)]
    (with-executor {:jobs [(a-job :j every-minute (fn [_] (deliver seen t/*ctx*) nil))]}
      (fn [_ world]
        (time/advance! world 47000)
        (is (= {:job :j :trigger :schedule} (deref seen 5000 nil)))))))

;; ---------------------------------------------------------------------------
;; Stop

(deftest stopping-ends-the-ticks
  (let [calls (atom 0)
        {:keys [signals]} (t/with-signals true
                            (with-executor {:jobs [(a-job :j every-minute (fn [_] (swap! calls inc) nil))]}
                              (fn [ex world]
                                (executor/stop! ex)
                                (is (not (executor/running? ex)))
                                (is (empty? (time/pending world)) "the pending ticks are cancelled")
                                (time/advance! world (* 10 60000))
                                (is (zero? @calls))
                                (executor/stop! ex))))]
    (is (= 1 (count (events signals :pulse.scheduler/stopping))) "stopping twice stops once")
    (is (= 1 (count (events signals :pulse.scheduler/stopped))))))

(deftest a-tick-that-was-already-on-its-way-when-the-scheduler-stopped-does-nothing
  (let [calls (atom 0)]
    (with-executor {:jobs [(a-job :j every-minute (fn [_] (swap! calls inc) nil))]}
      (fn [ex world]
        (let [on-their-way @(:pending world)]
          (executor/stop! ex)
          (doseq [{:keys [task]} on-their-way]
            (.run ^Runnable task))
          (is (seq on-their-way))
          (is (zero? @calls))
          (is (empty? (time/pending world)) "and it armed nothing")
          (is (= (instant "2026-10-15T10:03:00Z") (:next-run (executor/job-status ex :j)))
              "nor did it change what the status says is next")
          (is (= {:ok 0 :failed 0 :interrupted 0 :skipped 0} (:counts (executor/job-status ex :j)))))))))

(deftest stopping-lets-a-run-in-flight-finish-within-the-grace-period
  (let [started (promise)
        finish (promise)]
    (with-executor {:workers (Executors/newVirtualThreadPerTaskExecutor)
                    :grace-ms 5000
                    :jobs [(a-job :j every-minute (fn [_] (deliver started true) @finish {:done true}))]}
      (fn [ex _]
        (executor/run-now! ex :j)
        (is (deref started 5000 nil))
        (future (Thread/sleep 50) (deliver finish :go))
        (executor/stop! ex)
        (is (= :ok (get-in (executor/job-status ex :j) [:last-run :outcome])) "it was not interrupted")
        (is (= {:ok 1 :failed 0 :interrupted 0 :skipped 0} (:counts (executor/job-status ex :j))))))))

(deftest stopping-interrupts-a-run-that-does-not-finish-in-time
  (let [started (promise)
        {:keys [signals]} (t/with-signals true
                            (with-executor {:workers (Executors/newVirtualThreadPerTaskExecutor)
                                            :grace-ms 50
                                            :jobs [(a-job :stuck every-minute (fn [_] (deliver started true) @(promise)))]}
                              (fn [ex _]
                                (executor/run-now! ex :stuck)
                                (is (deref started 5000 nil))
                                (executor/stop! ex)
                                (is (= :interrupted (get-in (executor/job-status ex :stuck) [:last-run :outcome])))
                                (is (= {:ok 0 :failed 0 :interrupted 1 :skipped 0} (:counts (executor/job-status ex :stuck))))
                                (is (= :idle (:state (executor/job-status ex :stuck)))))))]
    (is (= [[:stuck]] (map #(get-in % [:data :jobs]) (events signals :pulse.scheduler/interrupting))))
    (is (= :warn (:level (first (events signals :pulse.scheduler/job-finished)))))))

;; ---------------------------------------------------------------------------
;; The real pools

(deftest the-real-pools-run-a-job-and-stop-in-order
  (let [runs (atom 0)
        ex (executor/start [(a-job :quick {:type :interval :every-ms 20}
                                   (fn [_] (swap! runs inc) {:n 1}))])]
    (try
      (is (eventually #(<= 3 (get-in (executor/job-status ex :quick) [:counts :ok]))) "three runs of an interval of 20 ms")
      (is (every? #(= :schedule (:trigger %)) [(:last-run (executor/job-status ex :quick))]))
      (finally
        (executor/stop! ex)))
    (is (.isTerminated ^java.util.concurrent.ExecutorService (:workers ex)))
    (is (.isTerminated ^java.util.concurrent.ExecutorService (:scheduler ex)))
    (let [after-stop @runs]
      (is (= after-stop @runs) "nothing runs once it is stopped"))))

(deftest the-handlers-run-on-named-virtual-threads
  (let [thread (promise)
        ex (executor/start [(a-job :j every-minute (fn [_] (deliver thread (Thread/currentThread)) nil))])]
    (try
      (is (= :started (executor/run-now! ex :j)))
      (let [^Thread t (deref thread 5000 nil)]
        (is (some? t))
        (is (.isVirtual t))
        (is (str/starts-with? (.getName t) "job-")))
      (finally
        (executor/stop! ex)))))
