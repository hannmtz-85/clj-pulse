(ns pulse.spa.appointments-details-test
  "What a mutation analysis of `pulse.spa.appointments` found that the first tests did not look at: that a page does not begin ready to show
  what a reading again finds, the queue when a reading again is asked for twice, what the deadline does to a call that had not begun and to
  the wish to show what was read, the dialog of another appointment under the same command, the rows of a list that holds an appointment
  with no id or one twice, the hint for how long a visit may be, and the words a screen reader is told when several things are true at
  once. Pure, so it runs on the JVM and on Node over the same data."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.appointments :as a]
            [pulse.spa.test.appointments-fixtures :as fx]))

(defn- ok [body] {:result :ok :body body})

(def ^:private checks [[:check :requests] [:check :booked] [:check :cancelled]])

(defn- six-on-their-way
  "The page at its start with six calls on their way: the four it asks for and two readings again of lists nobody has."
  []
  (-> (first (a/pump (a/start fx/now) 1))
      (update :inflight into [[:check :x] [:check :y]])))

(deftest a-page-does-not-begin-ready-to-show-what-a-reading-again-finds
  (let [state (a/start fx/now)]
    (is (false? (:adopt? state)))
    (is (false? (a/adopt-now? state)))
    (is (false? (a/adopt-now? (a/answer state :requests (ok fx/requests)))) "an answer is not a reading again")))

(deftest a-reading-again-asked-for-twice-leaves-one-of-each-in-the-queue
  (let [busy (six-on-their-way)
        once (a/reread busy fx/now)
        twice (a/reread once "2026-10-13T12:00:20Z")]
    (is (= 6 (count (:inflight busy))))
    (is (= checks (:queue once)) "the six calls on their way are why they wait")
    (is (= checks (:queue twice)) "and asked again they are still three, and not six")
    (is (= "2026-10-13T12:00:20Z" (:now twice)))
    (testing "what waited for another reason keeps its place before them"
      (is (= (into [:patients] checks) (:queue (a/reread (assoc busy :queue [:patients [:check :booked]]) fx/now)))))))

(deftest at-the-deadline-what-had-not-begun-is-not-asked-for-and-what-was-to-be-shown-is-not-waited-for
  (let [state (a/reread (six-on-their-way) fx/now)
        expired (a/expire state)]
    (is (= checks (:queue state)) "(the three readings wait for a place)")
    (is (= [] (:queue expired)) "nothing waits any more")
    (is (= #{} (:inflight expired)))
    (is (false? (:adopt? expired)) "and nothing is waited for to be shown")
    (is (every? #(= {:state :failed :kind :timeout} (get-in expired [:latest %])) [:requests :booked :cancelled :x :y])
        "a reading again that had not begun has failed too, like the ones on their way")
    (is (every? #(= {:state :failed :kind :timeout} (get expired %)) [:requests :booked :cancelled :patients]))
    (is (= (sort-by str (:inflight state)) (:cancelled-calls expired)) "only the calls on their way are to be stopped")))

(deftest the-dialog-of-another-appointment-under-the-same-command-is-another-dialog
  (let [state (-> (fx/settled)
                  (a/sync-dialog {:id "appt-ana-1" :action :reschedule})
                  (a/edit :from "11:15"))
        other (a/sync-dialog state {:id "appt-liam-1" :action :reschedule})]
    (is (= "appt-liam-1" (get-in other [:dialog :id])))
    (is (nil? (get-in other [:dialog :fields])) "what was typed for the first is not carried to the second")
    (is (= "11:15" (get-in (a/sync-dialog state {:id "appt-ana-1" :action :reschedule}) [:dialog :fields :from])) "while the same one keeps it")
    (is (nil? (get-in (a/sync-dialog state {:id "appt-ana-1" :action :cancel}) [:dialog :fields])) "and another command on it is another dialog")))

(deftest a-list-holds-the-appointments-with-an-id-once-each-in-its-rows-and-in-the-search-for-one
  (let [body (fx/page [fx/ana-booked (dissoc fx/liam-booked :id) fx/ana-booked (assoc fx/liam-booked :id "appt-liam-2")] 4)
        state (assoc (fx/settled) :booked (fx/loaded body))
        model (a/model state {:show :all} false)]
    (is (= ["appt-ana-1" "appt-liam-2"] (map :id (get-in model [:booked :items]))) "the one without an id and the repeated one are not rows")
    (is (= 4 (get-in model [:booked :total])) "the total is the api's")
    (is (a/row-shown? state :all "appt-liam-2"))
    (is (not (a/row-shown? state :all "appt-liam-1")) "it has no id to be found by")))

(deftest a-row-is-not-on-the-page-when-its-list-did-not-load
  (let [state (assoc (fx/settled) :booked fx/failed)]
    (is (not (a/row-shown? state :all "appt-ana-1")))
    (is (not (a/row-shown? (assoc state :booked fx/loading) :all "appt-ana-1")))
    (is (a/row-shown? (fx/settled) :all "appt-ana-1"))))

(deftest the-hint-for-how-long-a-visit-may-be-says-whole-hours-in-hours
  (is (= "A visit lasts from 5 minutes to 4 hours." (a/slot-hint {:slot-minutes [5 240]})))
  (is (= "A visit lasts from 15 minutes to 1 hour." (a/slot-hint {:slot-minutes [15 60]})) "one hour is not hours")
  (is (= "A visit lasts from 1 hour to 2 hours." (a/slot-hint {:slot-minutes [60 120]})))
  (is (= "A visit lasts from 30 minutes to 90 minutes." (a/slot-hint {:slot-minutes [30 90]})) "ninety minutes is not a whole number of hours")
  (let [open (a/sync-dialog (fx/settled) {:id "appt-sofia-1" :action :confirm})]
    (is (= "A visit lasts from 5 minutes to 4 hours." (get-in (a/model open {} false) [:dialog :slot-hint])) "the lifecycle's own limits")))

(deftest what-a-screen-reader-is-told-is-what-was-said-first-and-then-the-state-of-the-page
  (let [settled (fx/settled)
        said (assoc settled :said "Appointment confirmed.")
        loading (assoc settled :requests fx/loading :inflight #{:requests})]
    (is (= "Appointment confirmed." (:live (a/model said {} false))))
    (is (= "Appointment confirmed." (:live (a/model (assoc loading :said "Appointment confirmed.") {} false)))
        "even while something loads: what the person did is what they are told")
    (is (= "Loading the appointments" (:live (a/model loading {} false))))))

(deftest when-every-list-failed-the-page-says-so-and-not-that-it-could-not-update
  (let [all-failed (assoc (fx/settled) :requests fx/failed :booked fx/failed :cancelled fx/failed :latest {:requests fx/failed})]
    (is (= "The appointments could not be loaded" (:live (a/model all-failed {} false))))
    (is (= "We could not update the list of appointments" (:live (a/model (assoc (fx/settled) :latest {:requests fx/failed}) {} false))))))

(deftest the-dialog-of-a-cancelled-appointment-says-why-it-was-cancelled
  (let [state (a/sync-dialog (fx/settled) {:id "appt-ana-0" :action :confirm})
        dialog (:dialog (a/model state {} false))]
    (is (= :blocked (:state dialog)))
    (is (= "I had to travel that week." (get-in dialog [:summary :reason])))
    (is (= "Blood pressure check" (get-in dialog [:summary :description])))))

(deftest a-reading-again-that-failed-and-is-tried-again-is-shown-when-it-is-back
  (let [state (-> (first (a/pump (a/start fx/now) 1))
                  (a/answer :requests (ok fx/requests)) (a/answer :booked (ok fx/booked)) (a/answer :cancelled (ok fx/cancelled)) (a/answer :patients (ok fx/patients))
                  (a/reread fx/now)
                  (as-> s (first (a/pump s 2)))
                  (a/answer [:check :requests] (ok fx/requests-after-confirming-sofia))
                  (a/answer [:check :booked] {:result :failed :kind :timeout})
                  (a/answer [:check :cancelled] (ok fx/cancelled))
                  a/adopt)
        retried (a/retry state)]
    (is (a/check-failed? state) "the person is told that the list could not be updated")
    (is (= [[:check :booked]] (:queue retried)))
    (is (true? (:adopt? retried)) "and asking again is asking to see what the lists say now")
    (let [back (-> (first (a/pump retried 3)) (a/answer [:check :booked] (ok fx/booked-after-confirming-sofia)))]
      (is (a/adopt-now? back))
      (let [shown (a/adopt back)]
        (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked shown)) "it is what the list shows")
        (is (not (a/check-failed? shown)) "and the notice is gone because the list is up to date")
        (is (= {} (:latest shown)))))
    (testing "a list that failed to load is asked for again as before, and nothing is waited for"
      (let [lost (a/answer (a/start fx/now) :requests {:result :failed :kind :server})]
        (is (false? (:adopt? (a/retry lost))))))))
