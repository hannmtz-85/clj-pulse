(ns pulse.fhir.appointment-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.appointment :as appointment :refer [available-actions change proposal request-errors]]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.time :as time]))

(def ^:private now "2026-10-14T12:00:00Z")
(def ^:private now-ms (time/->millis now))
(def ^:private minute 60000)
(def ^:private hour (* 60 minute))
(def ^:private day (* 24 hour))

(defn- iso [ms]
  #?(:clj (str (java.time.Instant/ofEpochMilli ms))
     :cljs (.toISOString (js/Date. ms))))

(defn- from-now [ms] (iso (+ now-ms ms)))

(def ^:private request
  {:description "Hypertension follow-up"
   :requestedStart (from-now (* 2 day))
   :requestedEnd (from-now (* 5 day))})

(def ^:private proposed
  (proposal (assoc request :id "appt-1" :patient "pt-ana" :practitioner "pr-rivera")))

(defn- slot
  "A time to book: from `from` to `to` milliseconds after now, a day ahead for half an hour by default."
  ([] (slot day (+ day (* 30 minute))))
  ([from to] {:start (from-now from) :end (from-now to)}))

(def ^:private booked (:ok (change proposed (assoc (slot) :action :confirm) now :clinician)))
(def ^:private cancelled (:ok (change booked {:action :cancel} now :clinician)))

;; ---------------------------------------------------------------------------
;; The lifecycle

(defn- begins-in
  "The booked appointment, but beginning `ms` milliseconds after now (before it, if negative) and
  lasting an hour."
  [ms]
  (assoc booked :start (from-now ms) :end (from-now (+ ms hour))))

(deftest what-an-appointment-allows-follows-its-status-and-who-asks
  (testing "a clinician: what the status allows"
    (is (= #{:confirm :cancel} (available-actions proposed :clinician now)))
    (is (= #{:reschedule :cancel} (available-actions booked :clinician now)))
    (is (= #{} (available-actions cancelled :clinician now))))
  (testing "a patient: only to cancel"
    (is (= #{:cancel} (available-actions proposed :patient now)))
    (is (= #{:cancel} (available-actions booked :patient now)))
    (is (= #{} (available-actions cancelled :patient now))))
  (testing "a status Pulse does not use allows nothing"
    (doseq [actor [:patient :clinician]]
      (is (= #{} (available-actions {:status "fulfilled"} actor now)))
      (is (= #{} (available-actions {} actor now)))))
  (testing "a kind of actor that is not listed may do nothing, whatever the status"
    (doseq [actor [nil :rules-engine :administrator "clinician" 5]
            appointment [proposed booked cancelled]]
      (is (= #{} (available-actions appointment actor now)) (pr-str actor)))))

(deftest who-may-take-which-action-is-data
  (is (= #{:patient :clinician} appointment/actors))
  (testing "every action names who may take it, and they are kinds that exist"
    (doseq [[action {:keys [by]}] appointment/transitions]
      (is (seq by) (name action))
      (is (every? appointment/actors (keys by)) (name action))))
  (testing "a clinician may take every action, with no restriction"
    (is (= #{:confirm :reschedule :cancel}
           (set (for [[action {:keys [by]}] appointment/transitions :when (contains? by :clinician)] action))))
    (is (every? #(= {} (get-in % [1 :by :clinician])) appointment/transitions)))
  (testing "a patient may only cancel, and only until it begins"
    (is (= {:cancel {:until-start? true}}
           (into {} (for [[action {:keys [by]}] appointment/transitions :when (contains? by :patient)] [action (:patient by)]))))))

(deftest a-request-becomes-a-proposed-appointment
  (is (= "proposed" (:status proposed)))
  (is (= [{:actor {:reference "Patient/pt-ana"} :status "accepted"}
          {:actor {:reference "Practitioner/pr-rivera"} :status "needs-action"}]
         (:participant proposed)))
  (is (= [{:start (:requestedStart request) :end (:requestedEnd request)}] (:requestedPeriod proposed)))
  (is (= "Hypertension follow-up" (:description proposed)))
  (is (not-any? #(contains? proposed %) [:start :end :minutesDuration]) "it has no time yet")
  (is (schema/valid? proposed)))

(deftest confirming-gives-a-proposal-its-time
  (let [result (change proposed (assoc (slot) :action :confirm) now :clinician)
        ok (:ok result)]
    (is (= #{:ok} (set (keys result))))
    (is (= "booked" (:status ok)))
    (is (= [(:start (slot)) (:end (slot))] [(:start ok) (:end ok)]))
    (is (= 30 (:minutesDuration ok)))
    (testing "the doctor has accepted, and the patient is as they were"
      (is (= ["accepted" "accepted"] (map :status (:participant ok)))))
    (testing "what was asked for stays on record"
      (is (= (:requestedPeriod proposed) (:requestedPeriod ok)))
      (is (= (:description proposed) (:description ok))))
    (is (schema/valid? ok))
    (is (= "proposed" (:status proposed)) "the appointment given is a value, and is unchanged")))

(deftest rescheduling-moves-a-booked-appointment
  (let [moved (:ok (change booked (assoc (slot (* 3 day) (+ (* 3 day) hour)) :action :reschedule) now :clinician))]
    (is (= "booked" (:status moved)))
    (is (= (from-now (* 3 day)) (:start moved)))
    (is (= 60 (:minutesDuration moved)))
    (is (= (:participant booked) (:participant moved)))
    (is (= (:requestedPeriod booked) (:requestedPeriod moved)))
    (is (schema/valid? moved)))
  (is (= {:ok booked} (change booked (assoc (slot) :action :reschedule) now :clinician))
      "to the same time changes nothing"))

(deftest cancelling-ends-an-appointment-for-good
  (doseq [[label appointment] {"a proposal" proposed "a booked one" booked}]
    (testing label
      (let [ok (:ok (change appointment {:action :cancel :reason "The doctor is away"} now :clinician))]
        (is (= "cancelled" (:status ok)))
        (is (= {:text "The doctor is away"} (:cancelationReason ok)))
        (is (= (:start appointment) (:start ok)) "its time stays on record")
        (is (schema/valid? ok)))
      (is (not (contains? (:ok (change appointment {:action :cancel} now :clinician)) :cancelationReason))
          "a reason is optional")
      (is (contains? (change appointment {:action :cancel :reason nil} now :clinician) :ok))))
  (testing "and nothing can be done to it afterwards"
    (doseq [action [:confirm :reschedule :cancel]]
      (is (contains? (change cancelled (assoc (slot) :action action) now :clinician) :conflict) (str action)))))

(deftest the-status-decides-first-and-the-message-names-it
  (testing "each action from each status: allowed exactly when the table says so"
    (doseq [[label appointment] {"proposed" proposed "booked" booked "cancelled" cancelled}
            action [:confirm :reschedule :cancel]
            :let [result (change appointment (assoc (slot) :action action) now :clinician)
                  allowed? (contains? (available-actions appointment :clinician now) action)]]
      (testing (str label " " (name action))
        (is (= allowed? (contains? result :ok)))
        (is (= (not allowed?) (contains? result :conflict))))))
  (is (= "Only a proposed appointment can be confirmed; this one is booked."
         (:conflict (change booked (assoc (slot) :action :confirm) now :clinician))))
  (is (= "Only a booked appointment can be rescheduled; this one is proposed."
         (:conflict (change proposed (assoc (slot) :action :reschedule) now :clinician))))
  (is (= "Only a booked or proposed appointment can be cancelled; this one is cancelled."
         (:conflict (change cancelled {:action :cancel} now :clinician))))
  (is (= "Only a booked or proposed appointment can be cancelled; this one is of no known status."
         (:conflict (change {} {:action :cancel} now :clinician))))
  (testing "before the values: a wrong status with wrong times is a conflict, not a list of errors"
    (is (contains? (change booked {:action :confirm :start "garbage" :end nil} now :clinician) :conflict))))

(deftest only-the-three-actions-exist
  (doseq [action [:delete :fulfil nil "confirm" :Confirm]
          actor [:clinician :patient]]
    (is (= {:errors {:action ["must be one of cancel, confirm, reschedule"]}}
           (change proposed {:action action} now actor))
        (pr-str action actor))))

;; ---------------------------------------------------------------------------
;; A patient

(deftest a-patient-may-cancel-an-appointment-until-it-begins
  (testing "a request has no time, so it can be withdrawn at any moment"
    (is (= "cancelled" (get-in (change proposed {:action :cancel} now :patient) [:ok :status])))
    (is (= "cancelled" (get-in (change proposed {:action :cancel} (from-now (* 400 day)) :patient) [:ok :status]))))
  (testing "a booked appointment can be cancelled until the moment it begins, with a reason if they like"
    (doseq [ms [(* 30 day) day minute 1]]
      (let [result (change (begins-in ms) {:action :cancel :reason "I cannot come"} now :patient)]
        (is (= "cancelled" (get-in result [:ok :status])) (str ms " ms ahead"))
        (is (= {:text "I cannot come"} (get-in result [:ok :cancelationReason])))
        (is (schema/valid? (:ok result)))))
    (is (= #{:cancel} (available-actions (begins-in 1) :patient now))))
  (testing "and not from that moment on, whatever the reason"
    (doseq [ms [0 -1 (- minute) (- day)]
            command [{:action :cancel} {:action :cancel :reason "I cannot come"} {:action :cancel :reason "  "}]]
      (is (= {:conflict "This appointment has already begun, so only the clinic can cancel it."}
             (change (begins-in ms) command now :patient))
          (str ms " ms, " (pr-str command)))
      (is (= #{} (available-actions (begins-in ms) :patient now)))))
  (testing "a clinician can, at any time"
    (doseq [ms [(- day) 0 day]]
      (is (contains? (change (begins-in ms) {:action :cancel} now :clinician) :ok))
      (is (= #{:reschedule :cancel} (available-actions (begins-in ms) :clinician now)))))
  (testing "the moment that counts is the one it is asked at"
    (is (contains? (change booked {:action :cancel} now :patient) :ok))
    (is (contains? (change booked {:action :cancel} (from-now (+ day minute)) :patient) :conflict))))

(deftest what-a-patient-cancels-is-still-judged-like-any-other-cancellation
  (testing "the status first: one that is over is a conflict that names it"
    (is (= {:conflict "Only a booked or proposed appointment can be cancelled; this one is cancelled."}
           (change cancelled {:action :cancel} now :patient))))
  (testing "then the time, then the values: a reason that cannot be right"
    (is (= #{:reason} (set (keys (:errors (change booked {:action :cancel :reason ""} now :patient))))))
    (is (contains? (change (begins-in (- hour)) {:action :cancel :reason ""} now :patient) :conflict)
        "the appointment has begun, so the reason is not looked at"))
  (testing "the appointment given is a value, and is unchanged"
    (change proposed {:action :cancel} now :patient)
    (is (= "proposed" (:status proposed)))))

(deftest a-patient-may-not-confirm-or-reschedule-and-is-told-so-before-anything-else
  (doseq [[label appointment] {"proposed" proposed "booked" booked "cancelled" cancelled "begun" (begins-in (- day))}
          action [:confirm :reschedule]]
    (testing (str label " " (name action))
      (is (= {:forbidden (str "A patient may not " (name action) " an appointment.")}
             (change appointment (assoc (slot) :action action) now :patient)))))
  (testing "before the status and the values: neither changes the answer"
    (is (contains? (change cancelled {:action :confirm} now :patient) :forbidden))
    (is (contains? (change booked {:action :confirm :start "garbage" :end nil} now :patient) :forbidden))
    (is (contains? (change proposed {:action :reschedule :start nil :end nil} now :patient) :forbidden))))

(deftest a-kind-of-actor-that-is-not-listed-is-refused-everything
  (doseq [actor [nil :rules-engine :administrator "clinician" 5]
          action [:confirm :reschedule :cancel]
          [label appointment] {"proposed" proposed "booked" booked "cancelled" cancelled}]
    (testing (str (pr-str actor) " " (name action) " a " label " one")
      (is (= {:forbidden (str "A " (if (keyword? actor) (name actor) "caller of that kind")
                              " may not " (name action) " an appointment.")}
             (change appointment (assoc (slot) :action action) now actor))))))

;; ---------------------------------------------------------------------------
;; The time of a booking

(def ^:private slot-problems
  "Times that cannot be booked: what is wrong with them, the start, the end, and the fields named."
  [["a start in the past" (from-now (- minute)) (from-now (* 30 minute)) #{:start}]
   ["a start a year and a day ahead" (from-now (* 366 day)) (from-now (+ (* 366 day) hour)) #{:start}]
   ["an end that is the start" (from-now day) (from-now day) #{:end}]
   ["an end before the start" (from-now day) (from-now (- day hour)) #{:end}]
   ["a slot of 4 minutes" (from-now day) (from-now (+ day (* 4 minute))) #{:end}]
   ["a slot of 4 hours and a second" (from-now day) (from-now (+ day (* 4 hour) 1000)) #{:end}]
   ["no start" nil (from-now day) #{:start}]
   ["no end" (from-now day) nil #{:end}]
   ["a start that is not a time" "tomorrow" (from-now day) #{:start}]
   ["a date, which is not a time" "2026-10-20" (from-now day) #{:start}]
   ["a time with no offset" "2026-10-20T09:00:00" (from-now day) #{:start}]
   ["both wrong" "x" "y" #{:start :end}]])

(def ^:private slot-boundaries
  "Times that can just be booked."
  [["a start that is now" (from-now 0) (from-now (* 5 minute))]
   ["a start a year ahead" (from-now (* 365 day)) (from-now (+ (* 365 day) hour))]
   ["a slot of 5 minutes" (from-now day) (from-now (+ day (* 5 minute)))]
   ["a slot of 4 hours" (from-now day) (from-now (+ day (* 4 hour)))]
   ["times with an offset" "2026-10-20T11:00:00+02:00" "2026-10-20T09:30:00Z"]])

(deftest a-time-that-cannot-be-booked-is-refused-and-the-fields-are-named
  (doseq [action [:confirm :reschedule]
          [label start end fields] slot-problems
          :let [appointment (if (= :confirm action) proposed booked)
                result (change appointment {:action action :start start :end end} now :clinician)]]
    (testing (str (name action) ": " label)
      (is (= fields (set (keys (:errors result)))))
      (is (every? (every-pred vector? seq #(every? string? %)) (vals (:errors result)))))))

(deftest a-time-that-can-just-be-booked-is-accepted
  (doseq [action [:confirm :reschedule]
          [label start end] slot-boundaries
          :let [appointment (if (= :confirm action) proposed booked)
                result (change appointment {:action action :start start :end end} now :clinician)]]
    (testing (str (name action) ": " label)
      (is (contains? result :ok) (pr-str (:errors result)))
      (is (schema/valid? (:ok result))))))

(deftest the-messages-say-what-is-allowed
  (let [errors (fn [start end] (:errors (change proposed {:action :confirm :start start :end end} now :clinician)))]
    (is (= {:end ["must be at least 5 minutes after the start"]}
           (errors (from-now day) (from-now (+ day minute)))))
    (is (= {:end ["must be at most 4 hours after the start"]}
           (errors (from-now day) (from-now (+ day (* 5 hour))))))
    (is (= {:end ["must be after the start"]} (errors (from-now day) (from-now day))))
    (is (= {:start ["must not be in the past"]} (errors (from-now (- hour)) (from-now (* 30 minute)))))
    (is (= {:start ["must be within 365 days from now"]}
           (errors (from-now (* 400 day)) (from-now (+ (* 400 day) hour)))))
    (is (= {:start ["is required"]} (errors nil (from-now day))))))

(deftest the-other-messages-say-what-is-wrong
  (testing "a description that is not text, and a time that is not a time"
    (is (= {:description ["must be text"]} (request-errors (assoc request :description 5) now)))
    (is (= {:requestedStart ["must be a date and time with its offset, such as 2026-10-13T08:00:00Z"]}
           (request-errors (assoc request :requestedStart "soon") now))))
  (testing "a window that ends before it starts"
    (is (= {:requestedEnd ["must be after the start"]}
           (request-errors (assoc request :requestedStart (from-now (* 2 day)) :requestedEnd (from-now day)) now))))
  (testing "a length is said in hours when it is whole hours, and in the singular for one"
    (with-redefs [appointment/limits (assoc appointment/limits :slot-minutes [60 120])]
      (is (= {:end ["must be at least 1 hour after the start"]}
             (:errors (change proposed {:action :confirm :start (from-now day) :end (from-now (+ day (* 30 minute)))}
                              now :clinician))))
      (is (= {:end ["must be at most 2 hours after the start"]}
             (:errors (change proposed {:action :confirm :start (from-now day) :end (from-now (+ day (* 3 hour)))}
                              now :clinician))))))
  (testing "a now that is not a time says so"
    (is (= "`now` must be an instant"
           (try (request-errors request nil)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e (ex-message e)))))))

(deftest a-reason-for-cancelling-is-text-of-a-reasonable-length
  (doseq [reason ["" "   " (apply str (repeat 201 "x")) 5 [] {}]]
    (is (= #{:reason} (set (keys (:errors (change booked {:action :cancel :reason reason} now :clinician)))))
        (pr-str reason)))
  (is (contains? (change booked {:action :cancel :reason (apply str (repeat 200 "x"))} now :clinician) :ok)))

;; ---------------------------------------------------------------------------
;; A request

(def ^:private request-problems
  "Requests that cannot be made: what is wrong, the request, and the fields named."
  [["no description" (dissoc request :description) #{:description}]
   ["a blank description" (assoc request :description "  ") #{:description}]
   ["a description of 201 characters" (assoc request :description (apply str (repeat 201 "x"))) #{:description}]
   ["a description that is not text" (assoc request :description 5) #{:description}]
   ["no window" (dissoc request :requestedStart :requestedEnd) #{:requestedStart :requestedEnd}]
   ["a window that ends before it starts" (assoc request :requestedEnd (from-now day)) #{:requestedEnd}]
   ["a window that ends where it starts"
    (assoc request :requestedStart (from-now day) :requestedEnd (from-now day)) #{:requestedEnd}]
   ["a window that is over"
    (assoc request :requestedStart (from-now (- (* 5 day))) :requestedEnd (from-now (- day))) #{:requestedEnd}]
   ["a window that ends right now" (assoc request :requestedStart (from-now (- day)) :requestedEnd (from-now 0)) #{:requestedEnd}]
   ["a window of 30 days and a second"
    (assoc request :requestedStart (from-now day) :requestedEnd (from-now (+ day (* 30 day) 1000))) #{:requestedEnd}]
   ["a window that starts more than 180 days ahead"
    (assoc request :requestedStart (from-now (* 181 day)) :requestedEnd (from-now (* 182 day))) #{:requestedStart}]
   ["times that are not times" (assoc request :requestedStart "soon" :requestedEnd "later") #{:requestedStart :requestedEnd}]
   ["a date, which is not a time" (assoc request :requestedStart "2026-10-20") #{:requestedStart}]
   ["everything wrong" {:description "" :requestedStart "x" :requestedEnd nil} #{:description :requestedStart :requestedEnd}]])

(def ^:private acceptable-requests
  [["the one in the examples" request]
   ["a window that began and is still open"
    (assoc request :requestedStart (from-now (- (* 3 day))) :requestedEnd (from-now day))]
   ["a window of exactly 30 days" (assoc request :requestedStart (from-now day) :requestedEnd (from-now (* 31 day)))]
   ["a window that starts in exactly 180 days"
    (assoc request :requestedStart (from-now (* 180 day)) :requestedEnd (from-now (* 181 day)))]
   ["a description of 200 characters" (assoc request :description (apply str (repeat 200 "x")))]
   ["a window that ends a second from now"
    (assoc request :requestedStart (from-now (- day)) :requestedEnd (from-now 1000))]])

(deftest a-request-that-cannot-be-made-is-refused-and-the-fields-are-named
  (doseq [[label r fields] request-problems]
    (testing label
      (let [errors (request-errors r now)]
        (is (= fields (set (keys errors))))
        (is (every? (every-pred vector? seq #(every? string? %)) (vals errors)))))))

(deftest a-request-inside-the-limits-is-accepted
  (doseq [[label r] acceptable-requests]
    (testing label
      (is (nil? (request-errors r now)))
      (is (schema/valid? (proposal (assoc r :id "appt-2" :patient "pt-ana" :practitioner "pr-rivera")))))))

(deftest the-limits-are-the-ones-the-messages-quote
  (is (= {:requestedEnd ["must be at most 30 days after the start"]}
         (request-errors (assoc request :requestedStart (from-now day) :requestedEnd (from-now (* 40 day))) now)))
  (is (= {:requestedStart ["must be within 180 days from now"]}
         (request-errors (assoc request :requestedStart (from-now (* 200 day)) :requestedEnd (from-now (* 201 day))) now)))
  (is (= {:requestedEnd ["must be in the future"]}
         (request-errors (assoc request :requestedStart (from-now (- (* 5 day))) :requestedEnd (from-now (- day))) now)))
  (is (= {:description ["must be 1 to 200 characters"]} (request-errors (assoc request :description "") now)))
  (is (= {:description ["is required"]} (request-errors (dissoc request :description) now))))

(deftest a-now-that-is-not-a-time-is-a-mistake-of-the-caller
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (request-errors request nil)))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (change proposed {:action :cancel} "yesterday" :clinician)))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (change proposed {:action :cancel} "yesterday" :patient)))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (available-actions proposed :patient "yesterday"))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private gen-junk
  (gen/one-of [(gen/return nil)
               gen/small-integer
               gen/string-alphanumeric
               gen/boolean
               (gen/elements ["2026-10-20T09:00:00Z" "2026-10-20" "2026-10-20T09:00:00" "T" ""
                              "2026-10-20T23:59:60Z" "+999999999-01-01T00:00:00Z" "2026-10-20T09:00:00+99:00"])
               (gen/vector gen/small-integer 0 2)
               (gen/map gen/keyword gen/small-integer {:max-elements 2})]))

(defspec changing-and-requesting-answer-whatever-they-are-given-and-never-throw 500
  (prop/for-all [status (gen/elements ["proposed" "booked" "cancelled" "fulfilled" nil 5])
                 action (gen/one-of [(gen/elements [:confirm :reschedule :cancel :nope nil "x"]) gen-junk])
                 actor (gen/one-of [(gen/elements [:patient :clinician :rules-engine nil "patient"]) gen-junk])
                 start gen-junk
                 end gen-junk
                 reason gen-junk
                 description gen-junk]
    (let [changed (change {:status status :start start :participant []}
                          {:action action :start start :end end :reason reason} now actor)
          asked (request-errors {:description description :requestedStart start :requestedEnd end} now)]
      (and (= 1 (count changed))
           (contains? #{:ok :forbidden :conflict :errors} (key (first changed)))
           (set? (available-actions {:status status :start start} actor now))
           (or (nil? asked)
               (and (map? asked) (every? (every-pred vector? seq) (vals asked))))))))

(def ^:private gen-command
  "A command that is valid whatever the appointment: times within the limits."
  (gen/let [action (gen/elements [:confirm :reschedule :cancel])
            start-minutes (gen/choose 0 (* 300 24 60))
            length (gen/choose 5 240)
            reason (gen/elements [nil "away" "not needed"])]
    (cond-> {:action action}
      (not= :cancel action) (assoc :start (from-now (* start-minutes minute))
                                   :end (from-now (* (+ start-minutes length) minute)))
      (and (= :cancel action) reason) (assoc :reason reason))))

(defn- consistent?
  "Whether `a` is an appointment a clinic could have: a known status, and a booked one has a time
  that starts before it ends and a duration that is the length of it."
  [a]
  (and (contains? #{"proposed" "booked" "cancelled"} (:status a))
       (or (not= "booked" (:status a))
           (let [start (time/->millis (:start a))
                 end (time/->millis (:end a))]
             (and (< start end)
                  (= (:minutesDuration a) (quot (- end start) minute)))))))

(defspec any-sequence-of-valid-commands-leaves-a-valid-appointment-and-cancelled-is-final 300
  (prop/for-all [commands (gen/vector gen-command 0 8)]
    (loop [appointment proposed, commands commands]
      (if-let [command (first commands)]
        (let [result (change appointment command now :clinician)
              allowed? (contains? (available-actions appointment :clinician now) (:action command))]
          (and (= allowed? (contains? result :ok))
               (or (not allowed?) (and (schema/valid? (:ok result)) (consistent? (:ok result))))
               (or (not= "cancelled" (:status appointment)) (contains? result :conflict))
               (recur (or (:ok result) appointment) (rest commands))))
        true))))

(defspec a-patient-may-cancel-what-has-not-begun-and-nothing-else-whatever-the-time 500
  ;; an oracle that does not go through `transitions`: what ADR-0027 says
  (prop/for-all [status (gen/elements ["proposed" "booked" "cancelled"])
                 minutes-ahead (gen/choose -3000 3000)
                 asked-at (gen/choose -3000 3000)
                 action (gen/elements [:confirm :reschedule :cancel])]
    (let [appointment (case status
                        "proposed" proposed
                        "booked" (begins-in (* minutes-ahead minute))
                        "cancelled" cancelled)
          at (from-now (* asked-at minute))
          result (change appointment (assoc (slot) :action action) at :patient)
          begun? (and (= "booked" status) (<= (* minutes-ahead minute) (* asked-at minute)))
          expected (cond (not= :cancel action) :forbidden
                         (= "cancelled" status) :conflict
                         begun? :conflict
                         :else :ok)]
      (and (= expected (key (first result)))
           (= (= :ok expected) (contains? (available-actions appointment :patient at) action))
           (or (not= :ok expected) (= "cancelled" (:status (:ok result))))))))

(defspec a-request-inside-the-limits-is-always-accepted 300
  (prop/for-all [start-minutes (gen/choose (- (* 10 24 60)) (* 179 24 60))
                 length (gen/choose 60 (* 29 24 60))
                 description (gen/fmap #(apply str (repeat (inc %) "x")) (gen/choose 0 199))]
    (let [end-minutes (+ start-minutes length)]
      (or (<= end-minutes 0)
          (nil? (request-errors {:description description
                                 :requestedStart (from-now (* start-minutes minute))
                                 :requestedEnd (from-now (* end-minutes minute))}
                                now))))))

(deftest the-limits-are-data-a-form-can-show
  (is (= [5 240] (:slot-minutes appointment/limits)))
  (is (= 365 (:slot-ahead-days appointment/limits)))
  (is (= 30 (:window-days appointment/limits)))
  (is (= 180 (:window-ahead-days appointment/limits)))
  (is (= 200 (:text-length appointment/limits))))
