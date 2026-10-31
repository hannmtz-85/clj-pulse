(ns pulse.spa.appointments-events-details-test
  "What a mutation analysis of `pulse.spa.appointments-events` found that the first tests did not look at: what a page that was left is still
  waiting for, what an answer that frees a place starts, the guards of a retry, the deadline (its time, and what it does to a dialog that
  the person is filling in and to one that cannot be filled in), where the focus goes when a dialog is closed, the moment a press is
  judged at, the answers and failures about a dialog that is not the one that is open, a stopped call, where the words of a refusal are
  put, the list that a route with no narrowing means, and a reading again that did not come back and is tried again. As the tests of
  `appointments-events-test`, they run in `day8.re-frame/test`'s own re-frame state, with the effects that leave Node written down."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [day8.re-frame.test :as rf-test]
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

(def ^:private asked (atom []))

(def ^:private last-command (atom nil))

(defn- clock-at!
  "Makes the moment of the events `now`."
  [now]
  (quietly (fn [] (rf/reg-cofx ::appointments-events/now (fn [cofx _] (assoc cofx :now now))))))

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
                             (reset! last-command value)))))))
  (clock-at! fx/now))

(defn- state [] @db/app-db)

(defn- calls [] (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- list-calls [] (filterv #(= :appointments (first (:id %))) (calls)))

(defn- tasks-asked [] (mapv #(nth (:on-success %) 2) (list-calls)))

(defn- command-calls [] (filterv #(= [:appointment-command] (:id %)) (calls)))

(defn- command-call [] @last-command)

(defn- deadlines [] (vec (for [[effect value] @asked :when (and (= :dispatch-later effect) (= :pulse.spa.appointments/deadline (first (:dispatch value))))] value)))

(defn- focused [] (vec (for [[effect value] @asked :when (= ::router/focus effect)] value)))

(defn- went [] (vec (for [[effect value] @asked :when (= ::router/go effect)] value)))

(def ^:private rivera {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera" "realm_access" {"roles" ["practitioner"]}})

(def ^:private list-route {:name :appointments :params {:show :all}})

(defn- dialog-route [id action]
  {:name (keyword (str "appointment-" (name action))) :params {:show :all :id id :action action}})

(defn- on-the-page!
  ([] (on-the-page! list-route))
  ([here]
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/signed-in rivera])
   (reset! asked [])
   (rf/dispatch [::events/navigated here])))

(defn- answer!
  ([spec] (answer! spec nil))
  ([spec body]
   (let [task (nth (:on-success spec) 2)]
     (rf/dispatch (conj (:on-success spec) {:status 200 :body (or body (fx/body-of task)) :request-id "r"})))))

(defn- answer-everything!
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

(defn- confirm-sofia!
  "Opens the dialog of Sofía's request and presses the button; gives the call of the command."
  []
  (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
  (rf/dispatch [:pulse.spa.appointments/submit])
  (command-call))

(def ^:private after-confirming
  {:requests fx/requests-after-confirming-sofia :booked fx/booked-after-confirming-sofia :cancelled fx/cancelled})

;; ---------------------------------------------------------------------------
;; The page, its calls and its deadline

(deftest what-a-page-that-was-left-was-waiting-for-is-not-heard-when-nothing-has-taken-its-place
  (rf-test/run-test-sync
   (on-the-page!)
   (let [call (first (list-calls))]
     (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
     (is (not (contains? (state) :appointments)))
     (answer! call)
     (fail! call {:kind :server})
     (rf/dispatch [:pulse.spa.appointments/deadline 1 1])
     (is (not (contains? (state) :appointments)) "no answer and no deadline makes a page where there is none")
     (is (= 2 (:appointments-generation (state))) "leaving moves the generation on"))))

(deftest an-answer-that-frees-a-place-starts-what-waits
  (rf-test/run-test-sync
   (on-the-page!)
   (let [requests (first (list-calls))]
     (swap! db/app-db update :appointments #(-> % (update :inflight into [[:check :x] [:check :y]]) (assoc :queue [[:check :requests]])))
     (reset! asked [])
     (answer! requests)
     (is (= [[:check :requests]] (tasks-asked)) "five are on their way now: the one that waited begins"))))

(deftest a-retry-does-nothing-while-something-is-loading-even-if-something-failed
  (rf-test/run-test-sync
   (on-the-page!)
   (fail! (first (list-calls)) {:kind :server})
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/retry])
   (is (= [] (calls)))
   (is (= [] (deadlines)))
   (is (= 1 (:appointments-generation (state))) "and the answers of the calls on their way are still heard")))

(deftest a-retry-when-nothing-failed-begins-nothing
  (rf-test/run-test-sync
   (the-page-has-loaded!)
   (rf/dispatch [:pulse.spa.appointments/retry])
   (is (= [] (deadlines)))
   (is (= 1 (:appointments-generation (state))))
   (is (= 1 (:appointments-epoch (state))))))

(deftest the-deadline-is-the-time-the-page-has
  (rf-test/run-test-sync
   (on-the-page!)
   (is (= appointments/deadline-ms (:ms (first (deadlines)))))))

(deftest the-deadline-of-a-reading-again-leaves-the-dialog-the-person-is-filling-in-alone
  (rf-test/run-test-sync
   (succeed! (confirm-sofia!) fx/sofia-confirmed)
   (rf/dispatch [::events/navigated (dialog-route "appt-ana-1" :cancel)])
   (rf/dispatch [:pulse.spa.appointments/edit :why "I am away"])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/deadline 1 2])
   (is (= "appt-ana-1" (:id (dialog))))
   (is (= :editing (:state (dialog))))
   (is (= {:why "I am away"} (:fields (dialog))) "what they typed is still there")
   (is (= [] (went)) "and the address is not changed under them")))

(deftest at-the-deadline-a-dialog-that-could-not-be-filled-in-takes-the-focus-to-its-way-out
  (rf-test/run-test-sync
   (on-the-page! (dialog-route "appt-ana-1" :cancel))
   (is (= :loading (appointments/dialog-state (page))))
   (is (= [] (focused)))
   (rf/dispatch [:pulse.spa.appointments/deadline 1 1])
   (is (= :lost (appointments/dialog-state (page))) "the lists did not come")
   (is (= ["#appointment-close"] (focused)))))

;; ---------------------------------------------------------------------------
;; Closing a dialog, and the moment of a press

(deftest closing-a-dialog-whose-command-is-on-its-way-gives-the-focus-to-nobody
  (rf-test/run-test-sync
   (confirm-sofia!)
   (is (= :sending (:state (dialog))))
   (reset! asked [])
   (rf/dispatch [::events/navigated list-route])
   (is (nil? (dialog)))
   (is (= [] (focused)) "the row may move when the command is done: the focus is not sent to its button")))

(deftest closing-a-dialog-gives-the-focus-to-the-button-of-its-own-command
  (doseq [[id action selector] [["appt-ana-1" :reschedule "#reschedule-appt-ana-1"]
                                ["appt-ana-1" :cancel "#cancel-appt-ana-1"]
                                ["appt-sofia-1" :cancel "#cancel-appt-sofia-1"]
                                ["appt-sofia-1" :confirm "#confirm-appt-sofia-1"]]]
    (rf-test/run-test-sync
     (the-page-has-loaded!)
     (rf/dispatch [::events/navigated (dialog-route id action)])
     (reset! asked [])
     (rf/dispatch [::events/navigated list-route])
     (is (= [selector] (focused)) (str id " " action)))))

(deftest a-press-is-judged-at-the-moment-of-the-press-and-not-of-the-page
  (rf-test/run-test-sync
   (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
   (clock-at! "2026-10-16T08:30:00Z")
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= [] (command-calls)) "10:00 of the 16th is past at half past ten of the 16th")
   (is (= {:from "Must not be in the past."} (:errors (dialog))))))

;; ---------------------------------------------------------------------------
;; What the api answered about a dialog that is not the one that is open

(deftest an-answer-about-another-dialog-than-the-open-one-leaves-it-editing
  (doseq [[id action] [["appt-sofia-1" :cancel] ["appt-ana-2" :confirm] ["appt-liam-1" :reschedule]]]
    (rf-test/run-test-sync
     (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
     (rf/dispatch [:pulse.spa.appointments/answered id action {:status 200 :body fx/sofia-confirmed :request-id "r"}])
     (is (= :editing (:state (dialog))) (str id " " action))
     (is (= "appt-sofia-1" (:id (dialog))))
     (is (= [[:check :requests] [:check :booked] [:check :cancelled]] (tasks-asked)) "the lists are read again all the same"))))

(deftest a-failure-about-another-dialog-than-the-open-one-leaves-it-alone-and-asks-for-no-focus
  (doseq [[id action] [["appt-sofia-1" :cancel] ["appt-ana-2" :confirm]]]
    (rf-test/run-test-sync
     (the-page-has-loaded! (dialog-route "appt-sofia-1" :confirm))
     (rf/dispatch [:pulse.spa.appointments/command-failed id action {:kind :forbidden :request-id "r"}])
     (is (nil? (:notice (dialog))) (str id " " action))
     (is (= :editing (:state (dialog))))
     (is (= [] (focused)) "the person is not taken to a field of the dialog that was not the one that failed"))))

(deftest a-stopped-call-frees-the-dialog-it-was-sent-from-and-no-other
  (rf-test/run-test-sync
   (confirm-sofia!)
   (is (= :sending (:state (dialog))))
   (rf/dispatch [:pulse.spa.appointments/command-failed "appt-ana-1" :cancel {:kind :unauthorized :request-id "r"}])
   (is (= :sending (:state (dialog))) "the command of another appointment was stopped: this one is still on its way")
   (rf/dispatch [:pulse.spa.appointments/command-failed "appt-sofia-1" :confirm {:kind :unauthorized :request-id "r"}])
   (is (= :editing (:state (dialog))) "its own is free again")))

(deftest the-words-of-a-422-are-put-where-the-form-has-its-day-by-the-clock-that-answers
  (rf-test/run-test-sync
   (on-the-page! (dialog-route "appt-sofia-1" :confirm))
   (answer-everything! {:requests (fx/page [(assoc fx/sofia-request :requestedStart "2026-10-10T00:00:00Z") fx/ana-request])})
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/submit])
   (is (= 1 (count (command-calls))) "the form starts with tomorrow, the 14th, from ten, since the window it asked for is past")
   (clock-at! "2026-10-14T05:00:00Z")
   (refuse! (command-call) {:status 422 :problem {:errors {:start ["must not be in the past"]}}})
   (is (= {:from "Must not be in the past."} (:errors (dialog))) "the day of the form is the 14th, which is today on the clock that answers")))

(deftest a-route-that-does-not-say-what-the-list-is-narrowed-to-says-all-of-them
  (rf-test/run-test-sync
   (the-page-has-loaded! {:name :appointment-confirm :params {:id "appt-sofia-1" :action :confirm}})
   (rf/dispatch [:pulse.spa.appointments/submit])
   (succeed! (command-call) fx/sofia-confirmed)
   (answer-everything! after-confirming)
   (is (= ["#appointment-appt-sofia-1"] (focused)) "the row is in the list, which is all of them")
   (is (= [{:name :appointments :query {} :replace? true}] (went)))))

(deftest typing-with-no-page-leaves-the-state-as-it-was
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (let [before (state)]
     (rf/dispatch [:pulse.spa.appointments/edit :why "x"])
     (is (= before (state))))))

;; ---------------------------------------------------------------------------
;; A reading again that did not come back, tried again

(deftest a-reading-again-that-did-not-come-back-and-is-tried-again-is-shown-when-it-does
  (rf-test/run-test-sync
   (succeed! (confirm-sofia!) fx/sofia-confirmed)
   (let [[requests booked cancelled] (list-calls)]
     (answer! requests fx/requests-after-confirming-sofia)
     (fail! booked {:kind :server})
     (answer! cancelled fx/cancelled))
   (is (appointments/check-failed? (page)) "the person is told that the list could not be updated")
   (is (= {:state :loaded :data fx/booked} (:booked (page))) "and what is shown is what it was")
   (reset! asked [])
   (rf/dispatch [:pulse.spa.appointments/retry])
   (is (= [[:check :booked]] (tasks-asked)))
   (answer! (first (list-calls)) fx/booked-after-confirming-sofia)
   (is (= {:state :loaded :data fx/booked-after-confirming-sofia} (:booked (page))) "it is shown as soon as it is back")
   (is (not (appointments/check-failed? (page))) "and the notice goes because the list is up to date")
   (is (= {} (:latest (page))))))
