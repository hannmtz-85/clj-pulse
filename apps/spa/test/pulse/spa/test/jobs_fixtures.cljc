(ns pulse.spa.test.jobs-fixtures
  "The jobs of the scheduler as the tests need them, in the shapes the api answers (ADR-0025, ADR-0029): the three jobs of the demo
  fifteen seconds after 14:00 on Tuesday 13 October 2026 in Madrid, the rules and the reminders idle and the device simulator running, and
  the last run of the reminders failed. It is not a namespace of tests (its name does not end in `-test`) and it is `.cljc`, so that the
  JVM and Node tests alike take their data from here.")

(def now "2026-10-13T12:00:15Z")

(defn loaded [data]
  {:state :loaded :data data})

(def loading {:state :loading})

(def failed {:state :failed :kind :server})

(def evaluate-rules
  {:id "evaluate-rules"
   :description "Evaluates the clinical rules over the latest readings and raises alerts"
   :schedule {:type "interval" :everyMs 30000}
   :state "idle"
   :runningSince nil
   :nextRun "2026-10-13T12:00:30Z"
   :lastRun {:trigger "schedule" :startedAt "2026-10-13T12:00:00Z" :finishedAt "2026-10-13T12:00:02Z"
             :durationMs 1840 :outcome "ok"
             :result {:patients 6 :raised 0 :alreadyRaised 4 :stillActive 3 :notApplicable 5 :failed 0}}
   :counts {:ok 214 :failed 0 :interrupted 0 :skipped 0}})

(def simulate-devices
  {:id "simulate-devices"
   :description "Simulates the readings of the devices of the demo cohort and writes them to FHIR"
   :schedule {:type "interval" :everyMs 15000}
   :state "running"
   :runningSince "2026-10-13T12:00:15Z"
   :nextRun "2026-10-13T12:00:30Z"
   :lastRun {:trigger "schedule" :startedAt "2026-10-13T12:00:00Z" :finishedAt "2026-10-13T12:00:01Z"
             :durationMs 620 :outcome "ok" :result {:readings 7 :written 7 :failed 0}}
   :counts {:ok 428 :failed 1 :interrupted 0 :skipped 2}})

(def send-reminders
  {:id "send-reminders"
   :description "Sends a reminder to the patients whose confirmed appointment is about to begin"
   :schedule {:type "interval" :everyMs 60000}
   :state "idle"
   :runningSince nil
   :nextRun "2026-10-13T12:01:00Z"
   :lastRun {:trigger "schedule" :startedAt "2026-10-13T12:00:00Z" :finishedAt "2026-10-13T12:00:10Z"
             :durationMs 10012 :outcome "failed" :error "HttpTimeoutException"}
   :counts {:ok 105 :failed 1 :interrupted 0 :skipped 0}})

(def body
  "What `GET /api/admin/jobs` answers."
  {:total 3 :items [evaluate-rules simulate-devices send-reminders]})

(defn settled
  "The page of the jobs when the call has been answered: the state it shows when all went well."
  []
  {:now now :list (loaded body)})

(def never-run
  "A job that has not run yet: a daily one, due tomorrow morning."
  {:id "tidy-up"
   :description "Tidies up"
   :schedule {:type "daily" :at "08:00" :zone "Europe/Madrid"}
   :state "idle"
   :runningSince nil
   :nextRun "2026-10-14T06:00:00Z"
   :lastRun nil
   :counts {:ok 0 :failed 0 :interrupted 0 :skipped 0}})
