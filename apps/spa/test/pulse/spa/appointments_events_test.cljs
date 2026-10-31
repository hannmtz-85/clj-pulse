(ns pulse.spa.appointments-events-test
  "The events of the list of appointments, of the dialog that answers one and of the routes that lead to them (ADR-0029, points 3, 5 and 8),
  as the state they leave and the effects they ask for. The effects that reach out of the page (the api, the history, the clock, the focus)
  are replaced by ones that write down what they were asked, so nothing leaves Node; and some tests answer the calls the page makes with
  the answers of the fixtures, as the api would, to see the page whole. The point of the commands is that **the workspace never decides
  that an appointment has changed**: the press sends the command, the api answers, the lists are read again, and the appointment is where
  the api's lists put it. Each test runs in `day8.re-frame/test`'s own re-frame state, which is thrown away after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.appointments :as appointments]
            [pulse.spa.appointments-events :as appointments-events]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.panel-events]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.appointments-fixtures :as fx]
            [pulse.spa.watch-events :as watch-events]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]))

(defn- quietly
  "Runs `f` with re-frame's warning about a handler registered twice put aside: here it is meant."
  [f]
  (let [before (loggers/get-loggers)]
    (loggers/set-loggers! {:warn (fn [& args] (when-not (str/starts-with? (str (first args)) "re-frame: overwriting") (apply (:warn before) args)))})
    (try (f) (finally (loggers/set-loggers! before)))))

(def ^:private asked
  "What the effects were asked, in order: `[effect value]`."
  (atom []))

(def ^:private last-command
  "The last call of a command, which a test may need after it has forgotten what the effects were asked."
  (atom nil))

(defn- note-the-effects!
  "Replaces the effects that reach out of the page by ones that write down what they were asked, and the clock by the moment of the
  fixtures."
  []
  (reset! asked [])
  (reset! last-command nil)
  (quietly
   (fn []
     (doseq [effect [::auth/init ::auth/logout ::fetch/request ::fetch/cancel ::router/start ::router/replace ::router/arrived ::router/focus
                     ::router/go ::watch-events/listen :dispatch-later]]
       (rf/reg-fx effect (fn [value]
                           (swap! asked conj [effect value])
                           (when (and (= ::fetch/request effect) (= [:appointment-command] (:id value)))
                             (reset! last-command value)))))
     (rf/reg-cofx ::appointments-events/now (fn [cofx _] (assoc cofx :now fx/now))))))

(defn- state [] @db/app-db)

(defn- calls
  "The calls made so far, as their specs."
  []
  (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- list-calls
  "The calls of the page that read, as their specs."
  []
  (filterv #(= :appointments (first (:id %))) (calls)))

(defn- tasks-asked
  "The tasks the calls that read are for, in order."
  []
  (mapv #(nth (:on-success %) 2) (list-calls)))

(defn- command-calls [] (filterv #(= [:appointment-command] (:id %)) (calls)))

(defn- command-call [] @last-command)

(defn- cancelled
  "The ids of the calls that were stopped."
  []
  (set (for [[effect value] @asked :when (= ::fetch/cancel effect)] (:id value))))

(defn- deadlines
  "The deadlines of the page that were set."
  []
  (vec (for [[effect value] @asked :when (and (= :dispatch-later effect) (= :pulse.spa.appointments/deadline (first (:dispatch value))))] value)))

(defn- focused [] (vec (for [[effect value] @asked :when (= ::router/focus effect)] value)))

(defn- went [] (vec (for [[effect value] @asked :when (= ::router/go effect)] value)))

(defn- titles [] (vec (for [[effect value] @asked :when (= ::router/arrived effect)] value)))

(def ^:private rivera {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera" "realm_access" {"roles" ["practitioner"]}})

(def ^:private list-route {:name :appointments :params {:show :all}})

(defn- dialog-route
  ([id action] (dialog-route id action :all))
  ([id action show] {:name (keyword (str "appointment-" (name action))) :params {:show show :id id :action action}}))

(defn- on-the-page!
  "Signs in and comes to `here`, which begins to load (the load of the generation 1); what was asked since is in `@asked`."
  ([] (on-the-page! list-route))
  ([here]
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/signed-in rivera])
   (reset! asked [])
   (rf/dispatch [::events/navigated here])))

(defn- answer!
  "The api answers the call `spec` as the fixtures say (or `body`), as the effect would tell the page."
  ([spec] (answer! spec nil))
  ([spec body]
   (let [task (nth (:on-success spec) 2)]
     (rf/dispatch (conj (:on-success spec) {:status 200 :body (or body (fx/body-of task)) :request-id "r"})))))

(defn- answer-everything!
  "Answers every call that reads the page makes, and those that answering makes, until none is left, with the fixtures or with `bodies`
  (a map from the kind of list, `:requests`, `:booked` or `:cancelled`, to what the api answers now)."
  ([] (answer-everything! {}))
  ([bodies]
   (let [answered (atom #{})]
     (loop [guard 0]
       (let [pending (remove #(contains? @answered (:on-success %)) (list-calls))]
         (when (and (seq pending) (< guard 40))
           (doseq [spec pending]
             (swap! answered conj (:on-success spec))
             (let [task (nth (:on-success spec) 2)
                   kind (if (vector? task) (second task) task)]
               (answer! spec (get bodies kind))))
           (recur (inc guard)))))
     (count @answered))))

(defn- the-page-has-loaded!
  ([] (the-page-has-loaded! list-route))
  ([here]
   (on-the-page! here)
   (answer-everything!)
   (reset! asked [])))

(defn- page [] (subs/appointments (state) nil))

(defn- dialog [] (:dialog (page)))

(defn- refuse! [spec failure]
  (rf/dispatch (conj (:on-refusal spec) (merge {:kind :refused :request-id "r"} failure))))

(defn- fail! [spec failure]
  (rf/dispatch (conj (:on-failure spec) (merge {:request-id "r"} failure))))

(defn- succeed! [spec body]
  (rf/dispatch (conj (:on-success spec) {:status 200 :body body :request-id "r"})))

;; ---------------------------------------------------------------------------
;; The route

(deftest coming-to-the-list-starts-it-and-sets-the-title-without-moving-the-focus
  (rf-test/run-test-sync
   (on-the-page!)
   (is (= list-route (subs/route (state) nil)))
   (is (= [:requests :booked :cancelled :patients] (tasks-asked)))
   (is (= [[:pulse.spa.appointments/deadline 1 1]] (map :dispatch (deadlines))))
   (is (= [{:title "Appointments · Pulse" :focus? false}] (titles)))
   (is (nil? (dialog)))))

(deftest coming-from-the-panel-moves-the-focus-to-the-heading-and-leaves-the-panel
  (rf-test/run-test-sync
   (on-the-page! {:name :panel :params {:show :all :q ""}})
   (reset! asked [])
   (rf/dispatch [::events/navigated list-route])
   (is (= [{:title "Appointments · Pulse" :focus? true}] (titles)))
   (is (nil? (:panel (state))) "the panel is left")
   (is (some? (page)) "and the list begins")))

(deftest the-list-asks-for-the-three-kinds-and-the-patients-with-the-limits-of-the-api
  (rf-test/run-test-sync
   (on-the-page!)
   (is (= [{:status "proposed" :sort "start" :limit 100} {:status "booked" :sort "start" :limit 100} {:status "cancelled" :sort "-start" :limit 100}
           {:limit 100 :sort "family"}]
          (map :query (list-calls))))))

(deftest narrowing-the-list-is-the-same-page-and-asks-for-nothing
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (rf/dispatch [::events/navigated {:name :appointments :params {:show :requests}}])
   (rf/dispatch [::events/navigated {:name :appointments :params {:show :cancelled}}])
   (is (= {:name :appointments :params {:show :cancelled}} (subs/route (state) nil)))
   (is (= [] (calls)))
   (is (= (fx/settled) (page)) "and changes nothing of what it holds")))

(deftest a-person-who-may-not-open-it-is-asked-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (swap! db/app-db assoc :status :signed-in :person {:id "a1" :name "Ana" :roles #{:patient}})
   (rf/dispatch [::events/navigated list-route])
   (is (= [] (calls)))
   (is (nil? (page)))))

(deftest opening-a-dialog-from-the-list-loads-nothing-and-names-it-in-the-title-without-moving-the-focus-to-the-heading
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (rf/dispatch [::events/navigated (dialog-route "appt-sofia-1" :confirm)])
   (is (= [] (calls)))
   (is (= [{:title "Confirm an appointment · Pulse" :focus? false}] (titles)))
   (is (= :confirm (:action (dialog))))
   (is (= "appt-sofia-1" (:id (dialog))))
   (is (= [["#day"]] (map vector (focused))) "the focus goes to the first field")))

(deftest closing-a-dialog-returns-to-the-list-and-the-title-and-the-focus-to-the-button-that-opened-it
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (rf/dispatch [::events/navigated (dialog-route "appt-sofia-1" :confirm)])
   (reset! asked [])
   (rf/dispatch [::events/navigated list-route])
   (is (= [{:title "Appointments · Pulse" :focus? false}] (titles)))
   (is (nil? (dialog)))
   (is (= ["#confirm-appt-sofia-1"] (focused)))
   (is (= [] (calls)))))

(deftest a-dialog-reached-by-an-address-waits-for-the-lists-and-takes-the-focus-when-it-can-be-filled-in
  (rf-test/run-test-sync
   (on-the-page! (dialog-route "appt-sofia-1" :confirm))
   (is (= "appt-sofia-1" (:id (dialog))) "the address says which dialog")
   (is (= [] (focused)) "it is not there to fill in yet")
   (is (= [{:title "Confirm an appointment · Pulse" :focus? false}] (titles)))
   (answer-everything!)
   (is (= ["#day"] (focused)) "once its appointment is there")
   (is (= :open (appointments/dialog-state (page))))))

(deftest a-dialog-for-a-command-the-status-does-not-allow-takes-the-focus-to-its-way-out
  (rf-test/run-test-sync
   (on-the-page! (dialog-route "appt-ana-1" :confirm))
   (answer-everything!)
   (is (= ["#appointment-close"] (focused)))
   (is (= :blocked (appointments/dialog-state (page))))))

(deftest a-dialog-for-an-appointment-that-is-nowhere-takes-the-focus-to-its-way-out
  (rf-test/run-test-sync
   (on-the-page! (dialog-route "appt-nobody" :confirm))
   (answer-everything!)
   (is (= ["#appointment-close"] (focused)))
   (is (= :missing (appointments/dialog-state (page))))))

(deftest the-chart-of-a-patient-leads-to-the-dialog-over-the-list
  (rf-test/run-test-sync
   (on-the-page! {:name :patient :params {:id "pt-ana" :measurement nil :days 14}})
   (reset! asked [])
   (rf/dispatch [::events/navigated (dialog-route "appt-ana-1" :reschedule)])
   (is (= [:requests :booked :cancelled :patients] (tasks-asked)) "the list is loaded for the dialog")
   (is (= [{:title "Reschedule an appointment · Pulse" :focus? true}] (titles)) "another page: the focus goes to the heading")
   (answer-everything!)
   (is (= ["#day"] (focused)))))

;; ---------------------------------------------------------------------------
;; The page loading, failing and being left

(deftest the-answers-fill-the-parts-and-the-page-settles
  (rf-test/run-test-sync
   (on-the-page!)
   (is (= 4 (answer-everything!)))
   (is (= (fx/settled) (page)))
   (is (appointments/settled? (page)))))

(deftest a-call-that-failed-is-a-part-that-failed-and-the-others-are-shown
  (rf-test/run-test-sync
   (on-the-page!)
   (let [[requests booked cancelled patients] (list-calls)]
     (fail! booked {:kind :server})
     (answer! requests)
     (answer! cancelled)
     (answer! patients)
     (is (= {:state :failed :kind :server} (:booked (page))))
     (is (= :loaded (get-in (page) [:requests :state]))))))

(deftest at-the-deadline-what-is-not-back-has-failed-and-its-calls-are-stopped
  (rf-test/run-test-sync
   (on-the-page!)
   (answer! (first (list-calls)))
   (rf/dispatch [:pulse.spa.appointments/deadline 1 1])
   (is (= {:state :failed :kind :timeout} (:booked (page))))
   (is (= :loaded (get-in (page) [:requests :state])))
   (is (= #{[:appointments :booked] [:appointments :cancelled] [:appointments :patients]} (cancelled)))
   (is (appointments/settled? (page)))
   (is (not (contains? (page) :cancelled-calls)) "nothing of that is kept in the state")))

(deftest the-deadline-of-a-page-that-was-left-or-of-an-epoch-that-is-over-does-nothing
  (rf-test/run-test-sync
   (on-the-page!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/deadline 7 1])
   (rf/dispatch [:pulse.spa.appointments/deadline 1 7])
   (is (= {:state :loading} (:booked (page))) "neither is the deadline of what is loading now")
   (is (= [] (calls)))
   (is (= #{} (cancelled)))))

(deftest an-answer-of-a-page-that-was-left-is-not-heard
  (rf-test/run-test-sync
   (on-the-page!)
   (let [first-calls (list-calls)]
     (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
     (is (nil? (page)) "the page is forgotten")
     (rf/dispatch [::events/navigated list-route])
     (is (= {:state :loading} (:requests (page))) "a new page")
     (answer! (first first-calls))
     (is (= {:state :loading} (:requests (page))) "the answer of the one before is not for this one"))))

(deftest leaving-stops-the-calls-that-read-and-forgets-the-page
  (rf-test/run-test-sync
   (on-the-page!)
   (answer! (first (list-calls)))
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (nil? (page)))
   (is (= #{[:appointments :booked] [:appointments :cancelled] [:appointments :patients]} (cancelled)))))

(deftest retrying-asks-again-for-what-failed-only-and-once-the-page-has-settled
  (rf-test/run-test-sync
   (on-the-page!)
   (let [[requests booked cancelled patients] (list-calls)]
     (answer! requests)
     (fail! booked {:kind :server})
     (answer! cancelled)
     (answer! patients))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/retry])
   (is (= [:booked] (tasks-asked)))
   (is (= {:state :loading} (:booked (page))))
   (is (= [[:pulse.spa.appointments/deadline 2 2]] (map :dispatch (deadlines))))
   (testing "and not while something is loading"
     (reset! asked [])
     (rf/dispatch [:pulse.spa.appointments/retry])
     (is (= [] (calls))))))

(deftest there-is-nothing-to-retry-when-nothing-failed
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (rf/dispatch [:pulse.spa.appointments/retry])
   (is (= [] (calls)))
   (is (= (fx/settled) (page)))))

;; ---------------------------------------------------------------------------
;; The dialog: what is typed

(deftest typing-goes-to-the-dialog
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (rf/dispatch [:pulse.spa.appointments/edit :from "11:15"])
   (rf/dispatch [:pulse.spa.appointments/edit :day "2026-10-21"])
   (is (= {:from "11:15" :day "2026-10-21"} (:fields (dialog))))))

(deftest typing-with-no-page-does-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [:pulse.spa.appointments/edit :from "11:15"])
   (is (nil? (page)))))

;; ---------------------------------------------------------------------------
;; The press of the button

(deftest a-press-sends-the-command-and-the-button-is-busy
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= [(command/request "appt-sofia-1" {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"})] (calls)))
   (is (= :sending (:state (dialog))))
   (is (= [] (focused)) "the button that is busy has the focus")))

(deftest a-second-press-is-not-a-second-command
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= 1 (count (command-calls))))))

(deftest what-cannot-be-read-sends-nothing-and-the-focus-goes-to-the-first-field-that-is-wrong
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/edit :from ""])
   (rf/dispatch [:pulse.spa.appointments/edit :until ""])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= [] (calls)))
   (is (= {:from "Enter a time." :until "Enter a time."} (:errors (dialog))))
   (is (= :editing (:state (dialog))))
   (is (= ["#from"] (focused)))))

(deftest what-the-lifecycle-refuses-sends-nothing
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/edit :until "10:01"])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= [] (calls)))
   (is (= {:until "Must be at least 5 minutes after the start."} (:errors (dialog))))
   (is (= ["#until"] (focused)))))

(deftest typing-in-a-field-clears-what-was-said-of-it
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (rf/dispatch [:pulse.spa.appointments/edit :until "10:01"])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (rf/dispatch [:pulse.spa.appointments/edit :until "10:30"])
   (is (= {} (:errors (dialog))))))

(deftest a-press-of-a-dialog-that-is-not-there-sends-nothing
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= [] (calls)))
   (rf/dispatch [:pulse.spa.appointments/leave])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= [] (calls)))))

;; ---------------------------------------------------------------------------
;; What the api answered: done

(defn- confirm-sofia!
  "Opens the dialog of Sofía's request and presses the button. It gives the call of the command, which is also in `(command-calls)` until the
  effects are forgotten."
  []
  (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
  (rf/dispatch [:pulse.spa.appointments/submit])
  (command-call))

(def ^:private after-confirming
  {:requests fx/requests-after-confirming-sofia :booked fx/booked-after-confirming-sofia :cancelled fx/cancelled})

(deftest the-api-saying-yes-reads-the-lists-again-and-keeps-the-button-busy-until-they-are-back
  (rf-test/run-test-sync
   (let [call (confirm-sofia!)]
     (reset! asked [])
     (succeed! call fx/sofia-confirmed))
   (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)) "the three lists, again")
   (is (= :settling (:state (dialog))) "and the button is still busy")
   (is (= (:requests (fx/settled)) (:requests (page))) "what is shown has not moved: the workspace does not decide that it changed")
   (is (= "COPD check-up (requested by the patient) · Sofía Rossi: Appointment confirmed for Fri 16 Oct, 10:00–10:30." (:said (page))))
   (is (= [[:pulse.spa.appointments/deadline 1 2]] (map :dispatch (deadlines))) "with a deadline of its own")))

(deftest when-the-lists-are-back-the-appointment-is-where-the-api-puts-it-and-the-dialog-is-gone
  (rf-test/run-test-sync
   (succeed! (confirm-sofia!) fx/sofia-confirmed)
   (answer-everything! after-confirming)
   (is (= {:state :loaded :data fx/requests-after-confirming-sofia} (:requests (page))))
   (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked (page))))
   (is (nil? (dialog)) "the dialog is closed")
   (is (= [{:name :appointments :query {} :replace? true}] (went)) "the address is the list, with no entry in the history for the dialog that was done")
   (is (= ["#appointment-appt-sofia-1"] (focused)) "the focus is on the row")
   (is (= {} (:latest (page))) "what was kept apart is shown")))

(deftest the-list-keeps-its-narrowing-when-the-dialog-goes
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm :requests))
   (rf/dispatch [:pulse.spa.appointments/submit])
   (succeed! (command-call) fx/sofia-confirmed)
   (answer-everything! after-confirming)
   (is (= [{:name :appointments :query {:show "requests"} :replace? true}] (went)))
   (is (= ["main h1"] (focused)) "the row is no longer in the list that the page shows: the heading is")))

(deftest a-row-that-is-in-the-list-the-page-shows-takes-the-focus
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm :booked))
   (rf/dispatch [:pulse.spa.appointments/submit])
   (succeed! (command-call) fx/sofia-confirmed)
   (answer-everything! after-confirming)
   (is (= ["#appointment-appt-sofia-1"] (focused)) "it moved to the booked ones, which are the ones the page shows")))

(deftest a-rescheduling-and-a-cancellation-are-done-the-same-way
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-ana-1" :reschedule))
   (rf/dispatch [:pulse.spa.appointments/edit :from "11:30"])
   (rf/dispatch [:pulse.spa.appointments/edit :until "12:00"])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= {:action "reschedule" :start "2026-10-14T09:30:00Z" :end "2026-10-14T10:00:00Z"} (:body (command-call))))
   (succeed! (command-call) (assoc fx/ana-booked :start "2026-10-14T09:30:00Z" :end "2026-10-14T10:00:00Z"))
   (is (= "Hypertension follow-up · Ana García: Appointment rescheduled to Wed 14 Oct, 11:30–12:00." (:said (page)))))
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :cancel))
   (rf/dispatch [:pulse.spa.appointments/edit :why "We have no hours that week."])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= {:action "cancel" :reason "We have no hours that week."} (:body (command-call))))
   (succeed! (command-call) fx/sofia-cancelled)
   (is (= "COPD check-up (requested by the patient) · Sofía Rossi: Appointment cancelled." (:said (page))))
   (answer-everything! {:requests fx/requests-after-confirming-sofia :booked fx/booked :cancelled fx/cancelled-after-cancelling-sofia})
   (is (nil? (dialog)))
   (is (= ["#appointment-appt-sofia-1"] (focused)))))

(deftest the-lists-that-are-not-all-back-by-the-deadline-are-shown-with-what-came
  (rf-test/run-test-sync
   (succeed! (confirm-sofia!) fx/sofia-confirmed)
   (let [[requests booked _cancelled] (list-calls)]
     (answer! requests fx/requests-after-confirming-sofia)
     (answer! booked fx/booked-after-confirming-sofia))
   (is (= :settling (:state (dialog))) "one is not back")
   (is (= (:requests (fx/settled)) (:requests (page))) "so nothing is shown yet")
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/deadline 1 2])
   (is (= {:state :loaded :data fx/requests-after-confirming-sofia} (:requests (page))))
   (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked (page))))
   (is (nil? (dialog)))
   (is (= ["#appointment-appt-sofia-1"] (focused)))
   (is (appointments/check-failed? (page)) "and the page says that the list could not be updated")
   (is (= #{[:appointments [:check :cancelled]]} (cancelled)))))

(deftest a-person-who-went-back-to-the-list-while-it-was-on-its-way-finds-it-up-to-date
  (rf-test/run-test-sync
   (let [call (confirm-sofia!)]
     (rf/dispatch [::events/navigated list-route])
     (is (nil? (dialog)) "the address says there is no dialog")
     (reset! asked [])
     (succeed! call fx/sofia-confirmed))
   (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)) "the lists are read again all the same")
   (answer-everything! after-confirming)
   (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked (page))))
   (is (= [] (went)) "and the address is not touched: it is the list")
   (is (= [] (focused)))))

(deftest an-answer-of-a-page-that-was-left-is-nothing
  (rf-test/run-test-sync
   (let [call (confirm-sofia!)]
     (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
     (is (nil? (page)))
     (reset! asked [])
     (succeed! call fx/sofia-confirmed))
   (is (= [] (calls)))
   (is (nil? (page)))))

(deftest leaving-does-not-stop-the-command-that-is-on-its-way
  (rf-test/run-test-sync
   (confirm-sofia!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (not (contains? (cancelled) [:appointment-command])) "the api has it: the next time the page is read, it says")))

;; ---------------------------------------------------------------------------
;; What the api answered: no

(deftest a-409-is-the-apis-own-sentence-and-the-lists-are-read-again
  (rf-test/run-test-sync
   (confirm-sofia!)
   (reset! asked [])
   (refuse! (command-call) {:status 409 :problem {:detail "Only a proposed appointment can be confirmed; this one is booked."}})
   (is (= :editing (:state (dialog))) "the button is free")
   (is (= {:kind :conflict :text "Only a proposed appointment can be confirmed; this one is booked."} (:notice (dialog))))
   (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)) "to know what happened")
   (is (= ["#appointment-submit"] (focused)))))

(deftest once-the-lists-say-it-was-confirmed-by-someone-else-the-dialog-says-it-cannot-be-done
  (rf-test/run-test-sync
   (confirm-sofia!)
   (refuse! (command-call) {:status 409 :problem {:detail "Only a proposed appointment can be confirmed; this one is booked."}})
   (answer-everything! after-confirming)
   (is (= :blocked (appointments/dialog-state (page))))
   (is (= "Only a proposed appointment can be confirmed; this one is booked." (:message (:dialog (appointments/model (page) {:show :all} false)))))
   (is (= ["#appointment-submit" "#appointment-close"] (focused)) "the focus went to the button, and to the way out when the button was gone")
   (is (= [] (went)) "nothing finished: the dialog is still the address")))

(deftest a-422-puts-the-apis-words-beside-the-fields-and-asks-nothing-more
  (rf-test/run-test-sync
   (confirm-sofia!)
   (reset! asked [])
   (refuse! (command-call) {:status 422
                            :problem {:detail "The appointment cannot be changed as asked."
                                      :errors {:start ["must not be in the past"] :end ["must be after the start"]}}})
   (is (= :editing (:state (dialog))))
   (is (= {:day "Must not be in the past." :until "Must be after the start."} (:errors (dialog))) "the day is not today: the start is the day's")
   (is (nil? (:notice (dialog))))
   (is (= [] (calls)) "the lists do not change for what the person types wrong")
   (is (= ["#day"] (focused)))))

(deftest a-start-of-today-is-beside-the-time-and-not-the-day
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (rf/dispatch [:pulse.spa.appointments/edit :day "2026-10-13"])
   (rf/dispatch [:pulse.spa.appointments/edit :from "15:00"])
   (rf/dispatch [:pulse.spa.appointments/edit :until "15:30"])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (reset! asked [])
   (refuse! (command-call) {:status 422 :problem {:errors {:start ["must not be in the past"]}}})
   (is (= {:from "Must not be in the past."} (:errors (dialog))))
   (is (= ["#from"] (focused)))))

(deftest a-400-that-has-no-field-of-the-form-is-a-notice-in-the-apis-words
  (rf-test/run-test-sync
   (confirm-sofia!)
   (reset! asked [])
   (refuse! (command-call) {:status 400 :problem {:detail "Something is wrong." :errors {:action ["must be one of confirm"]}}})
   (is (= {} (:errors (dialog))))
   (is (= {:kind :invalid :text "Something is wrong."} (:notice (dialog))))
   (is (= ["#appointment-submit"] (focused)))
   (is (= [] (calls)))))

(deftest a-refusal-that-says-nothing-is-still-a-notice
  (rf-test/run-test-sync
   (confirm-sofia!)
   (refuse! (command-call) {:status 422 :problem nil})
   (is (= {:kind :invalid :text nil} (:notice (dialog))))))

(deftest a-404-says-the-appointment-is-gone-and-reads-the-lists-again
  (rf-test/run-test-sync
   (confirm-sofia!)
   (reset! asked [])
   (fail! (command-call) {:kind :not-found :status 404})
   (is (= {:kind :gone} (:notice (dialog))))
   (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)))
   (answer-everything! {:requests (fx/page [fx/ana-request]) :booked fx/booked :cancelled fx/cancelled})
   (is (= :missing (appointments/dialog-state (page))) "now it is not in any list")))

(deftest a-403-says-the-person-may-not-and-reads-nothing-again
  (rf-test/run-test-sync
   (confirm-sofia!)
   (reset! asked [])
   (fail! (command-call) {:kind :forbidden :status 403})
   (is (= {:kind :forbidden} (:notice (dialog))))
   (is (= :editing (:state (dialog))))
   (is (= [] (calls)))
   (is (= ["#appointment-submit"] (focused)))))

(deftest a-call-that-did-not-come-back-is-unconfirmed-and-the-lists-are-read-to-find-out
  (doseq [kind [:timeout :unreachable :server :unexpected]]
    (rf-test/run-test-sync
     (confirm-sofia!)
     (reset! asked [])
     (fail! (command-call) {:kind kind})
     (is (= {:kind :unconfirmed} (:notice (dialog))) (name kind))
     (is (= :editing (:state (dialog))) "the button is free: doing it twice does no harm")
     (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)))
     (is (= ["#appointment-submit"] (focused)))
     (is (nil? (:said (page))) "the notice is announced where it is"))))

(deftest what-the-lists-say-after-an-unconfirmed-command-is-shown-and-the-dialog-stays-for-the-person-to-decide
  (rf-test/run-test-sync
   (confirm-sofia!)
   (fail! (command-call) {:kind :timeout})
   (answer-everything! after-confirming)
   (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked (page))) "it was done after all")
   (is (= :blocked (appointments/dialog-state (page))) "and the dialog says it cannot be done again")
   (is (= [] (went)))))

(deftest a-call-that-was-stopped-or-a-sign-in-the-api-did-not-accept-frees-the-button-and-says-nothing
  (doseq [kind [:aborted :unauthorized]]
    (rf-test/run-test-sync
     (confirm-sofia!)
     (reset! asked [])
     (fail! (command-call) {:kind kind})
     (is (= :editing (:state (dialog))) (name kind))
     (is (nil? (:notice (dialog))))
     (is (= [] (calls))))))

(deftest an-answer-for-another-dialog-than-the-open-one-leaves-it-alone
  (rf-test/run-test-sync
   (confirm-sofia!)
   (let [call (command-call)]
     (rf/dispatch [::events/navigated (dialog-route "appt-ana-1" :reschedule)])
     (is (= :editing (:state (dialog))))
     (reset! asked [])
     (fail! call {:kind :timeout})
     (is (nil? (:notice (dialog))) "the dialog that is open is not the one that was answered")
     (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)) "but the lists are read again")
     (is (= [] (focused)) "and the focus is not taken from where it is"))))

;; ---------------------------------------------------------------------------
;; Escape

(deftest escape-leads-to-the-list-as-the-link-does
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm :booked))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/close])
   (is (= [{:name :appointments :query {:show "booked"}}] (went)) "narrowed as the list was, and a step in the history, as the link is")))

(deftest escape-with-no-dialog-does-nothing
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/close])
   (is (= [] (went)))))
