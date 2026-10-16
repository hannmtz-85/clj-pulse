(ns pulse.scheduler.stack-test
  "Integration test: the scheduler's FHIR work against the real HAPI of the local stack, checked
  against the same work over a server in memory. HAPI and the fake must agree on which appointments
  are about to begin, to the second, and on a reminder being sent once; and HAPI must find the
  reminders valid.

  Needs the stack (`bb up`) and runs with `bb test:it`. It writes only resources whose ids start with
  `it-rem-` and deletes them afterwards, whatever happens, so the demo data is untouched."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.http :as http]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.outcome :as outcome]
            [pulse.fhir.resource :as r]
            [pulse.scheduler.reminders :as reminders]
            [taoensso.telemere :as t])
  (:import (java.time Instant)))

(def ^:private base-url (or (System/getenv "FHIR_BASE_URL") "http://localhost:8090/fhir"))

(def ^:private actor {:client "pulse-scheduler" :roles ["rules-engine"]})

(defn- booked
  "A booked appointment of the integration patient with the integration doctor, from `start` to `end`."
  [id start end & {:keys [status] :or {status "booked"}}]
  (assoc (r/appointment {:id id :patient "it-rem-pt" :practitioner "it-rem-pr" :description "Integration reminder"
                         :start start :end end :minutes 30})
         :status status))

(def ^:private resources
  "In dependency order. The moment of the runs is 2026-01-10T10:00:00Z: two of these begin within a
  day after it, and the others are at the edges or are not confirmed."
  [(r/practitioner {:id "it-rem-pr" :given "Integration" :family "Doctor" :gender "other"})
   (r/patient {:id "it-rem-pt" :given "Integration" :family "Reminded" :gender "other" :birth-date "1990-01-01"})
   (booked "it-rem-before" "2026-01-10T09:59:59Z" "2026-01-10T10:29:59Z")
   (booked "it-rem-now" "2026-01-10T10:00:00Z" "2026-01-10T10:30:00Z")
   (booked "it-rem-after" "2026-01-10T10:00:01Z" "2026-01-10T10:30:01Z")
   (booked "it-rem-cancelled" "2026-01-10T12:00:00Z" "2026-01-10T12:30:00Z" :status "cancelled")
   (booked "it-rem-24h" "2026-01-11T10:00:00Z" "2026-01-11T10:30:00Z")
   (booked "it-rem-late" "2026-01-11T10:00:01Z" "2026-01-11T10:30:01Z")])

(def ^:private moment (Instant/parse "2026-01-10T10:00:00Z"))

(defn- run-over
  "A run of the reminders at `moment` over `client`: the summary."
  [client]
  (:value (t/with-signals true
            (reminders/run! {:fhir client :actor actor :window-hours 24 :zone "Europe/Madrid"} moment))))

(defn- sent-by
  "The reminders in `client` for the integration patient, by the appointment they are about."
  [client]
  (into (sorted-map)
        (map (juxt #(get-in % [:about 0 :reference]) identity))
        (c/search client "Communication" {:patient "Patient/it-rem-pt"})))

(defn- clean! [hapi]
  (doseq [{:keys [id]} (doall (c/search hapi "Communication" {:patient "Patient/it-rem-pt"}))]
    (c/delete! hapi "Communication" id))
  (doseq [{:keys [resourceType id]} (reverse resources)]
    (c/delete! hapi resourceType id)))

(deftest ^:integration hapi-and-the-fake-remind-the-same-patients-and-hapi-finds-the-reminders-valid
  (let [hapi (http/client {:base-url base-url})
        memory (mem/memory-client resources)]
    (clean! hapi)
    (try
      (c/transact! hapi (bundle/transaction resources))
      (let [expected {:appointments 2 :sent 2 :already-sent 0 :failed 0}]
        (testing "the same run over both: after the moment and up to a day after it, to the second"
          (is (= expected (run-over hapi)))
          (is (= expected (run-over memory)))
          (is (= ["Appointment/it-rem-24h" "Appointment/it-rem-after"] (sort (keys (sent-by hapi)))))
          (is (= (keys (sent-by memory)) (keys (sent-by hapi)))))
        (testing "the same reminders, but for the ids the servers gave them and what HAPI adds of its own"
          (let [what-they-say (juxt :status :sent :subject :recipient :about :category :identifier :payload)]
            (is (= (map what-they-say (vals (sent-by memory)))
                   (map what-they-say (vals (sent-by hapi)))))))
        (testing "and the same again: the identifier finds the reminder that was sent"
          (let [again {:appointments 2 :sent 0 :already-sent 2 :failed 0}]
            (is (= again (run-over hapi)))
            (is (= again (run-over memory))))
          (is (= 2 (count (sent-by hapi)))))
        (testing "HAPI finds the reminders valid, one at a time"
          (doseq [[about reminder] (sent-by hapi)
                  :let [result (c/validate hapi reminder)]]
            (is (not (outcome/error? result)) (str about ": " (outcome/summary result))))))
      (finally
        (clean! hapi)))))
