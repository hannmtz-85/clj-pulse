(ns pulse.spa.jobs-test
  "The page of the scheduler's jobs as far as it can be decided without a browser (ADR-0029, points 3 and 6): the call, what each answer
  of the api makes of the page, and what the page shows of the jobs. Pure, so it runs on the JVM and on Node over the same data."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.test.jobs-fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; The call and what becomes of its answer

(deftest the-call-reads-the-jobs-through-the-api
  (is (= {:method :get
          :path ["api" "admin" "jobs"]
          :id [:jobs]
          :on-success [:pulse.spa.jobs/succeeded]
          :on-failure [:pulse.spa.jobs/failed]}
         jobs/request))
  (is (= [:jobs] jobs/call-id) "the page that is left stops it by this id"))

(deftest a-page-begins-by-loading
  (is (= {:now fx/now :list {:state :loading}} (jobs/start fx/now)))
  (is (= {:now nil :list {:state :loading}} jobs/not-begun)))

(deftest an-answer-that-is-what-the-api-answers-is-the-list
  (is (= {:now fx/now :list (fx/loaded fx/body)}
         (jobs/answer (jobs/start fx/now) {:result :ok :body fx/body}))))

(deftest an-answer-that-is-not-what-the-api-answers-is-a-failure-and-never-an-empty-list
  (doseq [body [nil {} {:items []} {:total 1} {:total "1" :items []} {:total 1 :items {:a 1}} [] "jobs" 5]]
    (is (= {:state :failed :kind :unexpected} (:list (jobs/answer (jobs/start fx/now) {:result :ok :body body}))) (pr-str body))))

(deftest a-failure-keeps-its-kind
  (doseq [kind [:server :unreachable :timeout :forbidden :unauthorized :unexpected]]
    (is (= {:state :failed :kind kind} (:list (jobs/answer (jobs/start fx/now) {:result :failed :kind kind}))) (name kind)))
  (is (= {:state :failed :kind :unexpected} (:list (jobs/answer (jobs/start fx/now) {:result :failed})))
      "a failure that says nothing of its kind is not shown as no failure"))

(deftest only-a-list-that-failed-can-be-tried-again
  (is (not (jobs/failed? (jobs/start fx/now))))
  (is (not (jobs/failed? (fx/settled))))
  (is (jobs/failed? (jobs/answer (jobs/start fx/now) {:result :failed :kind :server})))
  (testing "trying again loads at the new moment"
    (is (= {:now "2026-10-13T12:05:00Z" :list {:state :loading}}
           (jobs/retry (jobs/answer (jobs/start fx/now) {:result :failed :kind :server}) "2026-10-13T12:05:00Z")))))

;; ---------------------------------------------------------------------------
;; What the page shows of the jobs

(defn- row-of [model id]
  (first (filter #(= id (:id %)) (:rows model))))

(deftest the-jobs-are-counted-and-each-is-a-row
  (let [model (jobs/model (fx/settled))]
    (is (= :loaded (:state model)))
    (is (= "Jobs" (:title model)))
    (is (= "What the scheduler does on its own, and how the last run of each went." (:description model)))
    (is (= {:total 3 :running 1 :failed 1} (:stats model)))
    (is (= ["evaluate-rules" "simulate-devices" "send-reminders"] (map :id (:rows model))) "in the order the api gave them")
    (is (= "3 jobs, 1 running, 1 failed its last run" (:live model)) "told to a screen reader")))

(deftest a-job-says-what-it-does-when-it-is-due-and-how-often-it-ran
  (let [row (row-of (jobs/model (fx/settled)) "evaluate-rules")]
    (is (= "Evaluates the clinical rules over the latest readings and raises alerts" (:description row)))
    (is (= "Every 30 seconds" (:schedule row)))
    (is (= "14:00:30" (:next-run row)) "on the clock of the clinic, to the second")
    (is (= "214 ok, 0 failed, 0 interrupted, 0 skipped" (:runs row)))))

(deftest a-job-that-is-idle-says-so-and-one-that-is-running-says-since-when
  (let [model (jobs/model (fx/settled))]
    (is (false? (:running? (row-of model "evaluate-rules"))))
    (is (nil? (:since (row-of model "evaluate-rules"))))
    (is (true? (:running? (row-of model "simulate-devices"))))
    (is (= "14:00:15" (:since (row-of model "simulate-devices"))))))

(deftest the-last-run-says-how-it-ended-when-it-began-how-long-it-took-and-what-it-reported
  (let [model (jobs/model (fx/settled))]
    (is (= {:outcome :ok :started "14:00:00" :duration "1.8 s"
            :result "6 patients, 0 raised, 4 already raised, 3 still active, 5 not applicable, 0 failed" :error nil}
           (:last-run (row-of model "evaluate-rules"))))
    (is (= {:outcome :ok :started "14:00:00" :duration "620 ms" :result "7 readings, 7 written, 0 failed" :error nil}
           (:last-run (row-of model "simulate-devices"))))
    (is (= {:outcome :failed :started "14:00:00" :duration "10 s" :result nil :error "HttpTimeoutException"}
           (:last-run (row-of model "send-reminders"))))))

(deftest what-a-run-reported-is-said-in-a-set-order-whatever-order-the-api-keeps
  (testing "the keys the jobs report, in the order they are said"
    (is (= "6 patients, 0 raised, 4 already raised, 3 still active, 5 not applicable, 0 failed"
           (-> (assoc-in (fx/settled) [:list :data :items 0 :lastRun :result]
                         (into (sorted-map-by (comp - compare)) {:patients 6 :raised 0 :alreadyRaised 4 :stillActive 3 :notApplicable 5 :failed 0}))
               jobs/model (row-of "evaluate-rules") :last-run :result))))
  (testing "a key that is not known comes after the known ones, alphabetically"
    (is (= "2 patients, 1 failed, 9 apples, 4 zebras"
           (-> (assoc-in (fx/settled) [:list :data :items 0 :lastRun :result] {:zebras 4 :failed 1 :patients 2 :apples 9})
               jobs/model (row-of "evaluate-rules") :last-run :result))))
  (testing "a value that is not a number is not a count"
    (is (= "2 patients"
           (-> (assoc-in (fx/settled) [:list :data :items 0 :lastRun :result] {:patients 2 :note "x" :list [1] :flag true})
               jobs/model (row-of "evaluate-rules") :last-run :result))))
  (testing "a result with nothing to count says nothing"
    (doseq [result [nil {} {:note "x"} "6 patients" [1 2]]]
      (is (nil? (-> (assoc-in (fx/settled) [:list :data :items 0 :lastRun :result] result)
                    jobs/model (row-of "evaluate-rules") :last-run :result))
          (pr-str result)))))

(deftest a-job-that-has-not-run-says-nothing-of-a-last-run-and-a-daily-one-is-due-another-day
  (let [model (jobs/model {:now fx/now :list (fx/loaded {:total 1 :items [fx/never-run]})})
        row (first (:rows model))]
    (is (nil? (:last-run row)))
    (is (= "Every day at 08:00 (Europe/Madrid)" (:schedule row)))
    (is (= "Tomorrow, 08:00:00" (:next-run row)) "a time that is not today says its day")
    (is (= "0 ok, 0 failed, 0 interrupted, 0 skipped" (:runs row)))
    (is (= {:total 1 :running 0 :failed 0} (:stats model)))
    (is (= [] (:failures model)))
    (is (= "1 job" (:live model)))))

(deftest a-time-of-another-day-says-the-day-and-the-clock
  (letfn [(row-with [next-run since]
            (first (:rows (jobs/model {:now fx/now
                                       :list (fx/loaded {:total 1 :items [(assoc fx/evaluate-rules
                                                                                 :nextRun next-run
                                                                                 :runningSince since
                                                                                 :state "running")]})}))))]
    (let [row (row-with "2026-10-12T10:00:00Z" "2026-10-11T08:00:00Z")]
      (is (= "Yesterday, 12:00:00" (:next-run row)))
      (is (= "Oct 11, 10:00:00" (:since row)) "two days before: its date"))
    (let [row (row-with "2026-10-13T22:30:00Z" "2026-10-12T22:30:00Z")]
      (is (= "Tomorrow, 00:30:00" (:next-run row)) "22:30 UTC is 00:30 of the next day in Madrid")
      (is (= "00:30:00" (:since row)) "and 00:30 of today, for the one before"))))

(deftest a-job-whose-last-run-failed-is-told-by-its-error-how-long-it-took-and-when-it-is-due-again
  (let [model (jobs/model (fx/settled))]
    (is (= [{:id "send-reminders" :text "HttpTimeoutException after 10 s. The next run is at 14:01:00."}] (:failures model)))))

(deftest the-text-of-a-failure-says-only-what-is-known
  (letfn [(text-of [run next-run]
            (-> (jobs/model {:now fx/now
                             :list (fx/loaded {:total 1 :items [(assoc fx/send-reminders :lastRun run :nextRun next-run)]})})
                :failures first :text))]
    (let [failed-run {:outcome "failed" :startedAt "2026-10-13T12:00:00Z" :durationMs 1500}]
      (is (= "Boom after 1.5 s. The next run is at 14:01:00." (text-of (assoc failed-run :error "Boom") "2026-10-13T12:01:00Z")))
      (is (= "Boom. The next run is at 14:01:00." (text-of (-> failed-run (assoc :error "Boom") (dissoc :durationMs)) "2026-10-13T12:01:00Z")))
      (is (= "It ran for 1.5 s. The next run is at 14:01:00." (text-of failed-run "2026-10-13T12:01:00Z")))
      (is (= "Boom after 1.5 s." (text-of (assoc failed-run :error "Boom") nil)) "a job with no next run says none")
      (is (= "The next run is at 14:01:00." (text-of (dissoc failed-run :durationMs) "2026-10-13T12:01:00Z")))
      (is (= "" (text-of (dissoc failed-run :durationMs) nil)) "and a failure that says nothing says nothing"))))

(deftest only-a-failed-last-run-is-a-failure
  (doseq [outcome ["ok" "interrupted" "boom" nil]]
    (let [model (jobs/model {:now fx/now :list (fx/loaded {:total 1 :items [(assoc-in fx/evaluate-rules [:lastRun :outcome] outcome)]})})]
      (is (= [] (:failures model)) (pr-str outcome))
      (is (= 0 (get-in model [:stats :failed])) (pr-str outcome)))))

(deftest a-run-that-ended-in-a-way-the-workspace-does-not-know-is-unknown-and-not-a-failure
  (let [model (jobs/model {:now fx/now :list (fx/loaded {:total 1 :items [(assoc-in fx/evaluate-rules [:lastRun :outcome] "boom")]})})]
    (is (= :unknown (get-in model [:rows 0 :last-run :outcome])))))

;; ---------------------------------------------------------------------------
;; What the scheduler says that the page cannot say

(deftest what-the-page-cannot-say-it-leaves-out-and-does-not-fail
  (let [odd {:id "odd"
             :description 5
             :schedule {:type "weekly"}
             :state "paused"
             :runningSince "soon"
             :nextRun "tomorrow"
             :lastRun {:outcome "ok" :startedAt "yesterday" :durationMs "long" :result nil :error ""}
             :counts {:ok 1 :failed nil}}
        row (first (:rows (jobs/model {:now fx/now :list (fx/loaded {:total 1 :items [odd]})})))]
    (is (= {:id "odd" :description nil :running? false :since nil :schedule nil :next-run nil :runs nil
            :last-run {:outcome :ok :started nil :duration nil :result nil :error nil}}
           row))))

(deftest a-last-run-that-is-not-a-map-is-no-last-run
  (doseq [run ["ok" 5 [] true]]
    (is (nil? (:last-run (first (:rows (jobs/model {:now fx/now :list (fx/loaded {:total 1 :items [(assoc fx/evaluate-rules :lastRun run)]})})))))
        (pr-str run))))

(deftest a-job-with-no-id-is-not-a-row-and-an-id-is-a-row-once
  (let [items [fx/evaluate-rules {:description "no id"} (assoc fx/send-reminders :id "") (assoc fx/send-reminders :id nil)
               5 "job" nil fx/evaluate-rules (assoc fx/simulate-devices :id "evaluate-rules") fx/simulate-devices]
        model (jobs/model {:now fx/now :list (fx/loaded {:total 9 :items items})})]
    (is (= ["evaluate-rules" "simulate-devices"] (map :id (:rows model))) "the first of an id wins, in the order the api gave them")
    (is (= (:description fx/evaluate-rules) (:description (first (:rows model)))))
    (is (= {:total 2 :running 1 :failed 0} (:stats model)) "the count is of the rows shown, not of what the api claims")))

(deftest the-list-of-jobs-that-the-scheduler-does-not-have-is-an-empty-list-and-not-a-failure
  (let [model (jobs/model {:now fx/now :list (fx/loaded {:total 0 :items []})})]
    (is (= :loaded (:state model)))
    (is (= [] (:rows model)))
    (is (= {:total 0 :running 0 :failed 0} (:stats model)))
    (is (= "0 jobs" (:live model)))))

;; ---------------------------------------------------------------------------
;; What the page shows while the jobs are on their way and when they could not be had

(deftest while-the-jobs-are-on-their-way-the-page-says-so-and-shows-no-figures
  (doseq [state [(jobs/start fx/now) jobs/not-begun]]
    (let [model (jobs/model state)]
      (is (= :loading (:state model)))
      (is (= "Loading the jobs" (:live model)))
      (is (= [] (:rows model)))
      (is (not (contains? model :stats)) "a figure that is not known yet is not zero"))))

(deftest jobs-that-could-not-be-loaded-are-lost-and-never-empty
  (doseq [kind [:server :unreachable :timeout :unauthorized :unexpected :not-found]]
    (let [model (jobs/model {:now fx/now :list {:state :failed :kind kind}})]
      (is (= :lost (:state model)) (name kind))
      (is (= kind (:kind model)))
      (is (= "The jobs could not be loaded" (:live model)))
      (is (= [] (:rows model)))
      (is (not (contains? model :stats))))))

(deftest the-api-saying-the-person-may-not-is-a-page-for-that
  (let [model (jobs/model {:now fx/now :list {:state :failed :kind :forbidden}})]
    (is (= :forbidden (:state model)))
    (is (= "You cannot see the jobs" (:live model)))
    (is (not (contains? model :stats)))))
