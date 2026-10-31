(ns pulse.spa.appointments-test
  "The list of appointments of the workspace as far as it can be decided without a browser (ADR-0029, points 3, 6 and 8): the calls that fill
  it, a few at a time and under one deadline, what each answer makes of it, how it is read again after the person answered an appointment,
  and the dialog that answers one. Pure, so it runs on the JVM and on Node over the same data as the tests of the page."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as a]
            [pulse.spa.test.appointments-fixtures :as fx]))

(defn- ok [body] {:result :ok :body body})

(defn- failure [kind] {:result :failed :kind kind})

(defn- answer-all
  "The page once every task of `state` has been answered from the fixtures."
  [state]
  (reduce (fn [state task] (a/answer state task (ok (fx/body-of task)))) state [:requests :booked :cancelled :patients]))

;; ---------------------------------------------------------------------------
;; The calls

(deftest every-call-is-for-the-api-with-its-id-and-the-events-that-hear-the-answer
  (is (= {:method :get
          :id [:appointments :requests]
          :path ["api" "appointments"]
          :query {:status "proposed" :sort "start" :limit 100}
          :on-success [:pulse.spa.appointments/succeeded 3 :requests]
          :on-failure [:pulse.spa.appointments/failed 3 :requests]}
         (a/request :requests 3)))
  (is (= {:status "booked" :sort "start" :limit 100} (:query (a/request :booked 3))) "the soonest first")
  (is (= {:status "cancelled" :sort "-start" :limit 100} (:query (a/request :cancelled 3))) "the newest first")
  (is (= {:path ["api" "patients"] :query {:limit 100 :sort "family"} :id [:appointments :patients]}
         (select-keys (a/request :patients 3) [:path :query :id])))
  (testing "a reading again is the same list under a name of its own"
    (let [call (a/request [:check :booked] 4)]
      (is (= ["api" "appointments"] (:path call)))
      (is (= {:status "booked" :sort "start" :limit 100} (:query call)))
      (is (= [:appointments [:check :booked]] (:id call)))
      (is (= [:pulse.spa.appointments/succeeded 4 [:check :booked]] (:on-success call))))))

(deftest a-call-of-a-kind-has-an-id-of-its-own
  (is (= [:appointments :requests] (a/call-id :requests)))
  (is (= [:appointments [:check :requests]] (a/call-id [:check :requests])))
  (is (not= (a/call-id :requests) (a/call-id [:check :requests])) "a reading again does not stop the first reading"))

(deftest the-page-begins-by-asking-for-the-three-lists-and-the-patients
  (let [state (a/start fx/now)]
    (is (= fx/now (:now state)))
    (is (every? #(= {:state :loading} (get state %)) [:requests :booked :cancelled :patients]))
    (is (= [:requests :booked :cancelled :patients] (:queue state)))
    (is (= #{} (:inflight state)))
    (is (nil? (:dialog state)))
    (is (not (a/settled? state)))
    (let [[started calls] (a/pump state 7)]
      (is (= [] (:queue started)) "four calls are fewer than six")
      (is (= #{:requests :booked :cancelled :patients} (:inflight started)))
      (is (= [[:appointments :requests] [:appointments :booked] [:appointments :cancelled] [:appointments :patients]] (map :id calls)) "in order"))))

(deftest no-more-than-six-calls-are-on-their-way
  (let [state (assoc (a/start fx/now) :queue (vec (repeat 2 [:check :requests])) :inflight (set (map #(vector :check %) (range 5))))
        [pumped calls] (a/pump (assoc state :queue [:requests :booked :cancelled]) 1)]
    (is (= 1 (count calls)) "five are on their way: one more may begin")
    (is (= [:booked :cancelled] (:queue pumped)))
    (is (= 6 (count (:inflight pumped))))))

;; ---------------------------------------------------------------------------
;; The answers

(deftest an-answer-fills-its-part-and-the-call-is-no-longer-on-its-way
  (let [started (first (a/pump (a/start fx/now) 1))
        state (a/answer started :requests (ok fx/requests))]
    (is (= {:state :loaded :data fx/requests} (:requests state)))
    (is (= #{:booked :cancelled :patients} (:inflight state)))
    (is (= {:state :loading} (:booked state)) "the others wait")))

(deftest when-every-part-is-in-the-page-is-settled
  (let [started (first (a/pump (a/start fx/now) 1))
        state (answer-all started)]
    (is (a/settled? state))
    (is (not (a/settled? (assoc state :queue [:requests]))) "something waits")
    (is (not (a/settled? (assoc state :inflight #{:requests}))) "something is on its way")
    (is (not (a/settled? (assoc state :booked fx/loading))) "a part is loading")
    (is (not (a/settled? (assoc state :patients fx/loading))) "the names too")))

(deftest an-answer-that-is-not-what-the-api-answers-is-a-failure-and-never-an-empty-list
  (doseq [body [nil {} {:items []} {:total 1} {:total "1" :items []} {:total 1 :items {:a 1}} [] "x" 5]
          task [:requests :booked :cancelled :patients]]
    (is (= {:state :failed :kind :unexpected} (get (a/answer (a/start fx/now) task (ok body)) task)) (pr-str task body))))

(deftest a-failure-keeps-its-kind-and-never-says-nothing-of-it
  (doseq [kind [:server :timeout :unreachable :forbidden :not-found :unexpected]]
    (is (= {:state :failed :kind kind} (:requests (a/answer (a/start fx/now) :requests (failure kind)))) (name kind)))
  (is (= {:state :failed :kind :unexpected} (:requests (a/answer (a/start fx/now) :requests {:result :failed})))))

(deftest a-part-that-failed-does-not-stop-the-others
  (let [state (-> (a/start fx/now)
                  (a/answer :requests (failure :server))
                  (a/answer :booked (ok fx/booked)))]
    (is (= :failed (get-in state [:requests :state])))
    (is (= :loaded (get-in state [:booked :state])))))

(deftest what-a-reading-again-finds-is-kept-apart
  (let [state (a/answer (a/start fx/now) [:check :booked] (ok fx/booked-after-confirming-sofia))]
    (is (= {:state :loading} (:booked state)) "what is shown is not changed")
    (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (get-in state [:latest :booked])))))

(deftest a-list-of-appointments-is-the-ones-with-an-id-once-each-in-the-order-given
  (let [items [fx/ana-booked {:description "no id"} (assoc fx/liam-booked :id "") (assoc fx/liam-booked :id nil) 5 "x" nil
               fx/ana-booked (assoc fx/liam-booked :id "appt-ana-1") fx/liam-booked]]
    (is (= ["appt-ana-1" "appt-liam-1"] (map :id (a/appointment-items {:items items}))))
    (is (= "Hypertension follow-up" (:description (first (a/appointment-items {:items items})))) "the first of an id wins"))
  (is (= [] (a/appointment-items nil)))
  (is (= [] (a/appointment-items {:items nil}))))

;; ---------------------------------------------------------------------------
;; The deadline

(deftest at-the-deadline-what-is-loading-has-failed-of-a-timeout-and-nothing-more-is-asked
  (let [started (first (a/pump (a/start fx/now) 1))
        state (-> started (a/answer :requests (ok fx/requests)) a/expire)]
    (is (= {:state :loaded :data fx/requests} (:requests state)) "what was in stays")
    (is (every? #(= {:state :failed :kind :timeout} (get state %)) [:booked :cancelled :patients]))
    (is (= [] (:queue state)))
    (is (= #{} (:inflight state)))
    (is (= [[:appointments :booked] [:appointments :cancelled] [:appointments :patients]] (map a/call-id (:cancelled-calls state)))
        "the calls to stop, in the order of their names")
    (is (a/settled? state))
    (is (not (:adopt? state)))))

(deftest at-the-deadline-a-reading-again-that-is-not-back-has-failed-and-the-lists-shown-are-left-alone
  (let [state (-> (answer-all (first (a/pump (a/start fx/now) 1)))
                  (a/reread fx/now)
                  (as-> s (first (a/pump s 2)))
                  (a/answer [:check :requests] (ok fx/requests-after-confirming-sofia))
                  a/expire)]
    (is (= :loaded (get-in state [:latest :requests :state])))
    (is (= {:state :failed :kind :timeout} (get-in state [:latest :booked])))
    (is (= {:state :failed :kind :timeout} (get-in state [:latest :cancelled])))
    (is (= {:state :loaded :data fx/booked} (:booked state)) "what is shown is as it was")))

(deftest an-expired-page-that-had-nothing-loading-is-as-it-was
  (let [state (answer-all (first (a/pump (a/start fx/now) 1)))
        expired (a/expire state)]
    (is (= (dissoc state :queue :inflight :adopt?) (dissoc expired :queue :inflight :adopt? :cancelled-calls)))
    (is (= [] (:cancelled-calls expired)))))

;; ---------------------------------------------------------------------------
;; Trying again

(deftest what-failed-is-asked-for-again-in-an-order
  (let [state (-> (a/start fx/now)
                  (a/answer :patients (failure :server))
                  (a/answer :requests (ok fx/requests))
                  (a/answer :booked (failure :timeout))
                  (a/answer :cancelled (ok fx/cancelled))
                  (a/answer [:check :cancelled] (failure :server)))]
    (is (= [:patients :booked [:check :cancelled]] (a/failed-tasks state)))
    (is (= [] (a/failed-tasks (answer-all (a/start fx/now)))))))

(deftest trying-again-loads-what-failed-and-nothing-else
  (let [state (-> (a/start fx/now)
                  (assoc :queue [] :inflight #{})
                  (a/answer :patients (failure :server))
                  (a/answer :requests (ok fx/requests))
                  (a/answer :booked (failure :timeout))
                  (a/answer :cancelled (ok fx/cancelled)))
        retried (a/retry state)]
    (is (= {:state :loading} (:patients retried)))
    (is (= {:state :loading} (:booked retried)))
    (is (= {:state :loaded :data fx/requests} (:requests retried)) "what is in is kept")
    (is (= [:patients :booked] (:queue retried)))))

(deftest a-reading-again-that-failed-is-asked-for-again-and-keeps-what-it-had-meanwhile
  (let [state (-> (answer-all (first (a/pump (a/start fx/now) 1)))
                  (a/answer [:check :booked] (failure :server)))
        retried (a/retry state)]
    (is (= {:state :failed :kind :server} (get-in retried [:latest :booked])) "it stays as it was until the new one is back")
    (is (= [[:check :booked]] (:queue retried)))))

;; ---------------------------------------------------------------------------
;; Reading the lists again after an answer

(deftest the-three-lists-are-read-again-and-shown-when-they-are-all-back
  (let [state (answer-all (first (a/pump (a/start fx/now) 1)))
        again (a/reread state "2026-10-13T12:05:00Z")]
    (is (= "2026-10-13T12:05:00Z" (:now again)))
    (is (true? (:adopt? again)))
    (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (:queue again)))
    (is (not (a/adopt-now? again)) "they are not back")
    (let [[started _] (a/pump again 2)
          back (-> started
                   (a/answer [:check :requests] (ok fx/requests-after-confirming-sofia))
                   (a/answer [:check :booked] (ok fx/booked-after-confirming-sofia)))]
      (is (not (a/adopt-now? back)) "two of the three")
      (let [back (a/answer back [:check :cancelled] (ok fx/cancelled))]
        (is (a/adopt-now? back))
        (let [shown (a/adopt back)]
          (is (= {:state :loaded :data fx/requests-after-confirming-sofia} (:requests shown)))
          (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked shown)))
          (is (= {} (:latest shown)) "what was kept apart is shown")
          (is (false? (:adopt? shown)))
          (is (not (a/adopt-now? shown))))))))

(deftest a-reading-again-on-its-way-is-stopped-by-the-new-one
  (let [state (-> (answer-all (first (a/pump (a/start fx/now) 1)))
                  (a/reread fx/now)
                  (as-> s (first (a/pump s 2)))
                  (a/reread fx/now))]
    (is (= #{} (:inflight state)) "the ones that were on their way are stopped by the effect, which shares their ids")
    (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (:queue state)) "and asked once")))

(deftest a-list-that-did-not-come-back-is-left-as-it-was-and-the-page-says-so
  (let [state (-> (answer-all (first (a/pump (a/start fx/now) 1)))
                  (a/reread fx/now)
                  (as-> s (first (a/pump s 2)))
                  (a/answer [:check :requests] (ok fx/requests-after-confirming-sofia))
                  (a/answer [:check :booked] (failure :server))
                  (a/answer [:check :cancelled] (ok fx/cancelled))
                  a/adopt)]
    (is (= {:state :loaded :data fx/requests-after-confirming-sofia} (:requests state)) "what came is shown")
    (is (= {:state :loaded :data fx/booked} (:booked state)) "what did not is as it was")
    (is (a/check-failed? state))
    (is (= {:state :failed :kind :server} (get-in state [:latest :booked])))))

(deftest only-a-reading-that-failed-is-a-failed-check
  (is (not (a/check-failed? (answer-all (first (a/pump (a/start fx/now) 1))))))
  (is (a/check-failed? (a/answer (a/start fx/now) [:check :cancelled] (failure :timeout)))))

;; ---------------------------------------------------------------------------
;; Finding an appointment

(deftest an-appointment-is-found-in-whichever-list-it-is
  (let [state (fx/settled)]
    (is (= {:status :found :appointment fx/sofia-request} (a/find-appointment state "appt-sofia-1")))
    (is (= {:status :found :appointment fx/liam-booked} (a/find-appointment state "appt-liam-1")))
    (is (= {:status :found :appointment fx/ana-cancelled} (a/find-appointment state "appt-ana-0")))))

(deftest an-appointment-that-is-in-no-list-is-missing-and-says-whether-it-may-be-beyond-them
  (is (= {:status :missing :cut? false} (a/find-appointment (fx/settled) "appt-nobody")))
  (is (= {:status :missing :cut? true}
         (a/find-appointment (assoc (fx/settled) :cancelled (fx/loaded (fx/page [fx/ana-cancelled] 130))) "appt-nobody"))
      "a list of the first of more"))

(deftest an-appointment-is-not-missing-while-it-may-yet-come
  (is (= {:status :loading} (a/find-appointment (assoc (fx/settled) :booked fx/loading) "appt-nobody")))
  (is (= {:status :lost} (a/find-appointment (assoc (fx/settled) :booked fx/failed) "appt-nobody")) "it may be in the list that could not be had")
  (is (= {:status :loading} (a/find-appointment (assoc (fx/settled) :booked fx/loading :cancelled fx/failed) "appt-nobody")) "on its way comes first")
  (testing "but one that was found is found whatever the others say"
    (is (= :found (:status (a/find-appointment (assoc (fx/settled) :booked fx/failed :cancelled fx/loading) "appt-sofia-1"))))))

(deftest the-words-that-name-an-appointment-and-whether-its-row-is-on-the-page
  (let [state (fx/settled)]
    (is (= "COPD check-up (requested by the patient) · Sofía Rossi" (a/label-for state "appt-sofia-1")))
    (is (= "Hypertension follow-up · Ana García" (a/label-for state "appt-ana-1")))
    (is (nil? (a/label-for state "appt-nobody")))
    (is (= "Hypertension follow-up · Patient pt-ana" (a/label-for (assoc state :patients fx/failed) "appt-ana-1")) "with no names, the number"))
  (let [state (fx/settled)]
    (is (every? #(a/row-shown? state :all %) ["appt-sofia-1" "appt-ana-1" "appt-ana-0"]))
    (is (a/row-shown? state :requests "appt-sofia-1"))
    (is (not (a/row-shown? state :requests "appt-ana-1")) "a booked one is not among the requests")
    (is (a/row-shown? state :booked "appt-ana-1"))
    (is (a/row-shown? state :cancelled "appt-ana-0"))
    (is (not (a/row-shown? state :cancelled "appt-sofia-1")))
    (is (not (a/row-shown? state :all "appt-nobody")))
    (is (not (a/row-shown? (assoc state :booked fx/failed) :all "appt-ana-1")) "a list that is not loaded shows no row")))

;; ---------------------------------------------------------------------------
;; The dialog

(deftest the-address-says-which-dialog-is-open
  (let [state (fx/settled)
        open (a/sync-dialog state {:id "appt-sofia-1" :action :confirm})]
    (is (= (command/opened :confirm "appt-sofia-1") (:dialog open)))
    (is (nil? (:dialog (a/sync-dialog open {:show :requests}))) "an address with no appointment has no dialog")
    (is (nil? (:dialog (a/sync-dialog open {:id "appt-sofia-1"}))) "nor one with no command")
    (is (nil? (:dialog (a/sync-dialog open {:action :confirm}))) "nor one with no appointment")
    (is (= (command/opened :cancel "appt-ana-1") (:dialog (a/sync-dialog open {:id "appt-ana-1" :action :cancel}))) "another dialog replaces it")
    (is (= (command/opened :reschedule "appt-sofia-1") (:dialog (a/sync-dialog open {:id "appt-sofia-1" :action :reschedule}))) "the same appointment, another command")))

(deftest an-address-that-says-the-same-dialog-leaves-what-was-typed-in-it
  (let [typed (-> (fx/settled) (a/sync-dialog {:id "appt-sofia-1" :action :confirm}) (a/edit :from "11:00"))]
    (is (= {:from "11:00"} (get-in (a/sync-dialog typed {:id "appt-sofia-1" :action :confirm :show :requests}) [:dialog :fields])))))

(deftest what-is-typed-goes-to-the-dialog-and-nowhere-without-one
  (let [open (a/sync-dialog (fx/settled) {:id "appt-sofia-1" :action :confirm})]
    (is (= {:day "2026-10-17"} (get-in (a/edit open :day "2026-10-17") [:dialog :fields])))
    (is (= (fx/settled) (a/edit (fx/settled) :day "2026-10-17")) "there is no dialog to type into")))

(deftest the-fields-of-a-dialog-are-in-the-order-of-the-screen
  (is (= [:day :from :until] (a/field-order :confirm)))
  (is (= [:day :from :until] (a/field-order :reschedule)))
  (is (= [:why] (a/field-order :cancel)))
  (is (= "#day" (a/first-field :confirm)))
  (is (= "#day" (a/first-field :reschedule)))
  (is (= "#why" (a/first-field :cancel)))
  (is (= :from (a/first-error {:action :confirm :errors {:until "x" :from "y"}})) "the first on the screen, not the first said")
  (is (= :day (a/first-error {:action :reschedule :errors {:until "x" :day "y"}})))
  (is (= :why (a/first-error {:action :cancel :errors {:why "z"}})))
  (is (nil? (a/first-error {:action :confirm :errors {}})))
  (is (nil? (a/first-error {:action :confirm :errors {:why "not a field of this form"}}))))

(deftest the-dialog-is-ready-to-fill-in-only-when-its-appointment-is-there-and-the-command-is-possible
  (let [state (fx/settled)]
    (is (nil? (a/dialog-state state)) "no dialog")
    (is (= :open (a/dialog-state (a/sync-dialog state {:id "appt-sofia-1" :action :confirm}))))
    (is (a/dialog-open? (a/sync-dialog state {:id "appt-ana-1" :action :reschedule})))
    (is (= :blocked (a/dialog-state (a/sync-dialog state {:id "appt-ana-1" :action :confirm}))) "a booked appointment is not confirmed")
    (is (= :blocked (a/dialog-state (a/sync-dialog state {:id "appt-ana-0" :action :cancel}))) "nor is a cancelled one cancelled")
    (is (= :missing (a/dialog-state (a/sync-dialog state {:id "appt-nobody" :action :confirm}))))
    (is (= :loading (a/dialog-state (a/sync-dialog (assoc state :requests fx/loading) {:id "appt-sofia-1" :action :confirm}))))
    (is (= :lost (a/dialog-state (a/sync-dialog (assoc state :requests fx/failed) {:id "appt-nobody" :action :confirm}))))
    (is (not (a/dialog-open? (a/sync-dialog state {:id "appt-ana-1" :action :confirm}))))))

;; ---------------------------------------------------------------------------
;; The press of the button

(defn- open-dialog [state id action & typed]
  (reduce (fn [state [field value]] (a/edit state field value))
          (a/sync-dialog state {:id id :action action})
          (partition 2 typed)))

(deftest a-press-sends-the-command-and-the-button-is-busy
  (let [state (open-dialog (fx/settled) "appt-sofia-1" :confirm)
        [pressed call] (a/submit state "2026-10-13T12:01:00Z")]
    (is (= :sending (get-in pressed [:dialog :state])))
    (is (= (command/request "appt-sofia-1" {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"}) call))
    (is (= {} (get-in pressed [:dialog :errors])))))

(deftest what-was-typed-is-what-is-sent
  (let [[_ call] (a/submit (open-dialog (fx/settled) "appt-sofia-1" :confirm :day "2026-10-21" :from "09:15" :until "10:00") fx/now)]
    (is (= {:action "confirm" :start "2026-10-21T07:15:00Z" :end "2026-10-21T08:00:00Z"} (:body call))))
  (let [[_ call] (a/submit (open-dialog (fx/settled) "appt-ana-1" :reschedule :from "11:30" :until "12:00") fx/now)]
    (is (= {:action "reschedule" :start "2026-10-14T09:30:00Z" :end "2026-10-14T10:00:00Z"} (:body call)) "the day is the one it has"))
  (let [[_ call] (a/submit (open-dialog (fx/settled) "appt-liam-1" :cancel :why " I am away ") fx/now)]
    (is (= {:action "cancel" :reason "I am away"} (:body call)))))

(deftest a-second-press-is-not-a-second-command
  (let [state (open-dialog (fx/settled) "appt-sofia-1" :confirm)
        [sending call] (a/submit state fx/now)]
    (is (some? call))
    (is (= [sending nil] (a/submit sending fx/now)) "while it is on its way")
    (is (= [(update sending :dialog command/settling) nil] (a/submit (update sending :dialog command/settling) fx/now)) "and while the lists are read again")))

(deftest nothing-is-sent-for-what-cannot-be-read-and-the-words-are-beside-the-fields
  (let [state (open-dialog (fx/settled) "appt-sofia-1" :confirm :day "" :until "09:00")
        [pressed call] (a/submit state fx/now)]
    (is (nil? call))
    (is (= :editing (get-in pressed [:dialog :state])) "the button is free")
    (is (= {:day "Enter a date."} (get-in pressed [:dialog :errors])))
    (is (= :day (a/first-error (:dialog pressed))))))

(deftest nothing-is-sent-for-what-the-lifecycle-refuses
  (let [state (open-dialog (fx/settled) "appt-sofia-1" :confirm :until "10:02")
        [pressed call] (a/submit state fx/now)]
    (is (nil? call))
    (is (= {:until "Must be at least 5 minutes after the start."} (get-in pressed [:dialog :errors])))))

(deftest nothing-is-sent-for-a-reason-that-is-too-long
  (let [[pressed call] (a/submit (open-dialog (fx/settled) "appt-ana-1" :cancel :why (apply str (repeat 201 "x"))) fx/now)]
    (is (nil? call))
    (is (= {:why "Must be 1 to 200 characters."} (get-in pressed [:dialog :errors])))))

(deftest nothing-is-sent-when-there-is-no-dialog-or-the-appointment-is-not-there-or-does-not-allow-it
  (let [state (fx/settled)]
    (is (= [state nil] (a/submit state fx/now)) "no dialog")
    (let [missing (open-dialog state "appt-nobody" :confirm)]
      (is (= [missing nil] (a/submit missing fx/now))))
    (let [blocked (open-dialog state "appt-ana-1" :confirm)]
      (is (= [blocked nil] (a/submit blocked fx/now))))
    (let [loading (open-dialog (assoc state :requests fx/loading) "appt-sofia-1" :confirm)]
      (is (= [loading nil] (a/submit loading fx/now))))))

(deftest the-time-of-the-lifecycle-is-the-time-of-the-press
  (let [state (open-dialog (fx/settled) "appt-sofia-1" :confirm)]
    (is (nil? (second (a/submit state "2026-10-16T08:30:00Z"))) "on the 16th at half past ten the default is in the past")
    (is (some? (second (a/submit state "2026-10-13T12:01:00Z"))))))

;; ---------------------------------------------------------------------------
;; What the page shows

(deftest the-lists-are-sections-of-rows-with-what-the-lifecycle-allows
  (let [model (a/model (fx/settled) {:show :all} false)]
    (is (= "Appointments" (:title model)))
    (is (= "Requests to answer and visits to come." (:description model)))
    (is (= [:loaded :loaded :loaded] (map #(get-in model [% :state]) [:requests :booked :cancelled])))
    (is (= ["appt-sofia-1" "appt-ana-2"] (map :id (get-in model [:requests :items]))) "as the api gave them")
    (is (= ["appt-ana-1" "appt-liam-1"] (map :id (get-in model [:booked :items]))))
    (is (= ["appt-ana-0"] (map :id (get-in model [:cancelled :items]))))
    (is (= [2 2 1] (map #(get-in model [% :total]) [:requests :booked :cancelled])))
    (is (= [#{:confirm :cancel} #{:confirm :cancel}] (map :actions (get-in model [:requests :items]))))
    (is (= [#{:reschedule :cancel} #{:reschedule :cancel}] (map :actions (get-in model [:booked :items]))))
    (is (= [#{}] (map :actions (get-in model [:cancelled :items]))))))

(deftest a-row-says-whose-it-is-what-it-is-for-and-when
  (let [model (a/model (fx/settled) {:show :all} false)
        sofia (first (get-in model [:requests :items]))
        ana (first (get-in model [:booked :items]))
        cancelled (first (get-in model [:cancelled :items]))]
    (is (= {:id "appt-sofia-1" :status "proposed" :description "COPD check-up (requested by the patient)"
            :asked "Asked for a visit between Oct 16 and Oct 23" :requested-start "2026-10-16T00:00:00Z" :doctor nil
            :patient-id "pt-sofia" :name "Sofía Rossi" :label "COPD check-up (requested by the patient) · Sofía Rossi"
            :actions #{:confirm :cancel}}
           sofia))
    (is (= {:month "Oct" :day "14" :when "Wed 14 Oct, 10:00–10:30" :start "2026-10-14T08:00:00Z" :name "Ana García" :patient-id "pt-ana"}
           (select-keys ana [:month :day :when :start :name :patient-id])))
    (is (= "I had to travel that week." (:reason cancelled)))))

(deftest a-patient-the-list-does-not-hold-is-named-by-their-number
  (let [state (assoc (fx/settled) :patients (fx/loaded (fx/page [])))
        model (a/model state {:show :all} false)]
    (is (= ["Patient pt-sofia" "Patient pt-ana"] (map :name (get-in model [:requests :items]))))
    (is (= "COPD check-up (requested by the patient) · Patient pt-sofia" (:label (first (get-in model [:requests :items])))))
    (testing "and an appointment that names no patient has no name"
      (let [state (assoc-in (fx/settled) [:requests :data :items 0 :patient] nil)]
        (is (nil? (:name (first (get-in (a/model state {:show :all} false) [:requests :items])))))
        (is (nil? (:patient-id (first (get-in (a/model state {:show :all} false) [:requests :items])))))
        (is (= "COPD check-up (requested by the patient)" (:label (first (get-in (a/model state {:show :all} false) [:requests :items])))))))))

(deftest a-row-with-an-id-no-address-can-hold-has-no-actions
  (let [state (assoc-in (fx/settled) [:booked :data :items 0 :id] "..")
        row (first (get-in (a/model state {:show :all} false) [:booked :items]))]
    (is (= ".." (:id row)))
    (is (= #{} (:actions row)))))

(deftest the-tabs-count-each-list-and-all-of-them-once-they-are-in
  (let [model (a/model (fx/settled) {:show :all} false)]
    (is (= [{:id :all :label "All" :count 5 :current? true}
            {:id :requests :label "Requests" :count 2 :current? false}
            {:id :booked :label "Booked" :count 2 :current? false}
            {:id :cancelled :label "Cancelled" :count 1 :current? false}]
           (:filters model)))
    (is (= [:requests] (map :id (filter :current? (:filters (a/model (fx/settled) {:show :requests} false))))))
    (is (= [:cancelled] (map :id (filter :current? (:filters (a/model (fx/settled) {:show :cancelled} false))))))
    (testing "a count is not made up for a list that is not in"
      (let [filters (:filters (a/model (assoc (fx/settled) :booked fx/loading) {:show :all} false))]
        (is (= [nil 2 nil 1] (map :count filters)) "and so is not the total")))
    (testing "the total is the api's, not the number of rows shown"
      (is (= [nil 2 130 1] (map #(when (not= :all (:id %)) (:count %))
                                (:filters (a/model (assoc (fx/settled) :booked (fx/loaded (fx/page [fx/ana-booked] 130))) {:show :all} false)))))
      (is (= 133 (:count (first (:filters (a/model (assoc (fx/settled) :booked (fx/loaded (fx/page [fx/ana-booked] 130))) {:show :all} false)))))))))

(deftest the-page-is-for-the-clinic-s-patients-when-the-person-is-the-administrator
  (is (= "Requests to answer and visits to come, for the clinic's patients." (:description (a/model (fx/settled) {:show :all} true)))))

(deftest what-is-on-its-way-is-loading-and-what-could-not-be-had-is-lost-and-never-empty
  (let [loading (a/model (a/start fx/now) {:show :all} false)]
    (is (= [:loading :loading :loading] (map #(get-in loading [% :state]) [:requests :booked :cancelled])))
    (is (= "Loading the appointments" (:live loading)))
    (is (not (:settled? loading))))
  (let [model (a/model (assoc (fx/settled) :booked fx/failed) {:show :all} false)]
    (is (= :lost (get-in model [:booked :state])))
    (is (= :loaded (get-in model [:requests :state])))
    (is (nil? (get-in model [:booked :items])) "not an empty list"))
  (testing "the names are needed for the rows"
    (let [model (a/model (assoc (fx/settled) :patients fx/loading) {:show :all} false)]
      (is (= [:loading :loading :loading] (map #(get-in model [% :state]) [:requests :booked :cancelled]))))))

(deftest the-page-says-what-it-could-not-do
  (testing "names that could not be loaded, once it has settled"
    (let [model (a/model (assoc (fx/settled) :patients fx/failed) {:show :all} false)]
      (is (= [{:id :names-failed}] (:notices model)))
      (is (= ["Patient pt-sofia" "Patient pt-ana"] (map :name (get-in model [:requests :items]))))))
  (testing "a reading again that failed"
    (let [model (a/model (assoc-in (fx/settled) [:latest :booked] fx/failed) {:show :all} false)]
      (is (= [{:id :check-failed}] (:notices model)))
      (is (= "We could not update the list of appointments" (:live model)))))
  (testing "both"
    (is (= [{:id :check-failed} {:id :names-failed}]
           (:notices (a/model (-> (fx/settled) (assoc :patients fx/failed) (assoc-in [:latest :booked] fx/failed)) {:show :all} false)))))
  (testing "no notice while it is still loading"
    (is (= [] (:notices (a/model (assoc (a/start fx/now) :patients fx/failed) {:show :all} false)))))
  (is (= [] (:notices (a/model (fx/settled) {:show :all} false)))))

(deftest a-screen-reader-is-told-how-many-requests-wait
  (is (= "2 requests wait for an answer" (:live (a/model (fx/settled) {:show :all} false))))
  (is (= "1 request waits for an answer" (:live (a/model (assoc (fx/settled) :requests (fx/loaded (fx/page [fx/sofia-request]))) {:show :all} false))))
  (is (= "No request waits for an answer" (:live (a/model (assoc (fx/settled) :requests (fx/loaded fx/none)) {:show :all} false))))
  (is (= "The appointments could not be loaded" (:live (a/model (assoc (fx/settled) :requests fx/failed :booked fx/failed :cancelled fx/failed) {:show :all} false))))
  (is (= "Some appointments could not be loaded" (:live (a/model (assoc (fx/settled) :requests fx/failed) {:show :all} false))))
  (testing "and what was said of what the person did comes first"
    (is (= "Appointment confirmed." (:live (a/model (assoc (fx/settled) :said "Appointment confirmed.") {:show :all} false))))))

(deftest the-model-has-no-dialog-until-one-is-asked-for
  (is (nil? (:dialog (a/model (fx/settled) {:show :all} false)))))

;; ---------------------------------------------------------------------------
;; The dialog as the page shows it

(defn- dialog-of [state id action & typed]
  (:dialog (a/model (apply open-dialog state id action typed) {:show :all} false)))

(deftest the-dialog-of-a-confirmation-has-the-appointment-the-values-and-the-words
  (let [dialog (dialog-of (fx/settled) "appt-sofia-1" :confirm)]
    (is (= :open (:state dialog)))
    (is (= :confirm (:action dialog)))
    (is (= "appt-sofia-1" (:id dialog)))
    (is (= "Confirm the appointment" (:title dialog)))
    (is (= "Confirm" (:submit-label dialog)))
    (is (= :calendar-check (:icon dialog)))
    (is (= {:description "COPD check-up (requested by the patient)" :name "Sofía Rossi" :asked "Asked for a visit between Oct 16 and Oct 23"}
           (:summary dialog)))
    (is (= {:day "2026-10-16" :from "10:00" :until "10:30"} (:fields dialog)))
    (is (= "Sofía" (:given dialog)))
    (is (= "COPD check-up (requested by the patient) · Sofía Rossi" (:label dialog)))
    (is (= "A visit lasts from 5 minutes to 4 hours." (:slot-hint dialog)))
    (is (= 200 (:reason-length dialog)))
    (is (= {} (:errors dialog)))
    (is (nil? (:notice dialog)))
    (is (false? (:busy? dialog)))))

(deftest the-dialog-of-a-rescheduling-and-of-a-cancellation
  (let [dialog (dialog-of (fx/settled) "appt-ana-1" :reschedule)]
    (is (= "Reschedule the appointment" (:title dialog)))
    (is (= "Reschedule" (:submit-label dialog)))
    (is (= :calendar-clock (:icon dialog)))
    (is (= {:day "2026-10-14" :from "10:00" :until "10:30"} (:fields dialog)) "the time it has")
    (is (= "Wed 14 Oct, 10:00–10:30" (get-in dialog [:summary :when]))))
  (let [dialog (dialog-of (fx/settled) "appt-liam-1" :cancel)]
    (is (= "Cancel the appointment" (:title dialog)))
    (is (= "Cancel the appointment" (:submit-label dialog)))
    (is (= :calendar-x (:icon dialog)))
    (is (= {:why ""} (:fields dialog))))
  (is (= "Cancel the request" (:title (dialog-of (fx/settled) "appt-sofia-1" :cancel)))))

(deftest what-is-typed-is-what-the-dialog-shows
  (let [dialog (dialog-of (fx/settled) "appt-sofia-1" :confirm :from "11:15")]
    (is (= {:day "2026-10-16" :from "11:15" :until "10:30"} (:fields dialog)))))

(deftest the-words-beside-the-fields-and-the-notice-are-the-dialog-s
  (let [state (-> (fx/settled)
                  (open-dialog "appt-sofia-1" :confirm)
                  (update :dialog command/refused {:until "Must be after the start."} {:kind :conflict :text "Someone got there first."}))
        dialog (:dialog (a/model state {:show :all} false))]
    (is (= {:until "Must be after the start."} (:errors dialog)))
    (is (= {:title "It was not confirmed" :text "Someone got there first."} (:notice dialog)))))

(deftest the-button-of-the-dialog-is-busy-while-the-command-is-on-its-way-and-until-the-lists-are-read-again
  (let [state (open-dialog (fx/settled) "appt-sofia-1" :confirm)
        [sending _] (a/submit state fx/now)]
    (is (true? (:busy? (:dialog (a/model sending {:show :all} false)))))
    (is (true? (:busy? (:dialog (a/model (update sending :dialog command/settling) {:show :all} false)))))))

(deftest a-dialog-whose-appointment-is-not-there-yet-says-so
  (let [state (assoc (fx/settled) :requests fx/loading)
        dialog (:dialog (a/model (open-dialog state "appt-sofia-1" :confirm) {:show :all} false))]
    (is (= {:action :confirm :id "appt-sofia-1" :busy? false :state :loading :title "Loading the appointment"} dialog))))

(deftest a-dialog-whose-appointment-could-not-be-loaded-says-so
  (let [dialog (:dialog (a/model (open-dialog (assoc (fx/settled) :booked fx/failed) "appt-nobody" :confirm) {:show :all} false))]
    (is (= :lost (:state dialog)))
    (is (= "The appointment could not be loaded" (:title dialog)))))

(deftest a-dialog-for-an-appointment-that-is-nowhere-says-so
  (let [dialog (:dialog (a/model (open-dialog (fx/settled) "appt-nobody" :confirm) {:show :all} false))]
    (is (= :missing (:state dialog)))
    (is (= "We cannot find that appointment" (:title dialog)))
    (is (= "It may have been removed." (:message dialog))))
  (is (= "It may be beyond the first 100 of its kind, which are the ones shown."
         (:message (:dialog (a/model (open-dialog (assoc (fx/settled) :cancelled (fx/loaded (fx/page [fx/ana-cancelled] 130))) "appt-nobody" :confirm)
                                     {:show :all} false))))))

(deftest a-dialog-for-a-command-the-status-does-not-allow-says-so-in-the-lifecycle-s-words
  (let [dialog (:dialog (a/model (open-dialog (fx/settled) "appt-ana-1" :confirm) {:show :all} false))]
    (is (= :blocked (:state dialog)))
    (is (= "Confirm the appointment" (:title dialog)))
    (is (= "Only a proposed appointment can be confirmed; this one is booked." (:message dialog)))
    (is (= "Hypertension follow-up" (get-in dialog [:summary :description])))))

(deftest a-notice-of-the-dialog-is-in-words-and-not-a-keyword
  (let [state (-> (open-dialog (fx/settled) "appt-sofia-1" :confirm)
                  (update :dialog command/refused {} {:kind :unconfirmed}))
        dialog (:dialog (a/model state {:show :all} false))]
    (is (= "We could not tell whether it was confirmed" (get-in dialog [:notice :title])))))
