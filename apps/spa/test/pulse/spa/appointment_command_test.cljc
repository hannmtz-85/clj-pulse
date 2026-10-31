(ns pulse.spa.appointment-command-test
  "Answering an appointment from the workspace as far as it can be decided without a browser (ADR-0029, points 3 and 8): what the form
  starts with, what a person typed turned into what the api takes through the nights the clocks change, what the lifecycle says before
  anything is sent and where its words are written, the call, what each answer of the api means, and the words. Pure, so it runs on the JVM
  and on Node over the same data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.test.appointments-fixtures :as fx]))

(def ^:private now fx/now) ; Tuesday 13 October 2026, 14:00 in Madrid

(def ^:private sofia fx/sofia-request)
(def ^:private ana fx/ana-booked)

;; ---------------------------------------------------------------------------
;; What the lifecycle allows, and which appointments have a dialog

(deftest what-a-clinician-may-do-is-what-the-lifecycle-says
  (is (= #{:confirm :cancel} (command/available sofia now)) "a request can be confirmed or cancelled")
  (is (= #{:reschedule :cancel} (command/available ana now)) "a booked appointment can be rescheduled or cancelled")
  (is (= #{} (command/available fx/ana-cancelled now)) "a cancelled one has nothing")
  (testing "a booked appointment that has begun is still the clinic's to move or cancel"
    (is (= #{:reschedule :cancel} (command/available (assoc ana :start "2026-10-13T08:00:00Z") now)))))

(deftest an-appointment-whose-id-no-address-can-hold-has-nothing-to-do
  (doseq [id [nil "" "." ".." "a b" "a/b" "a?b" (apply str (repeat 65 "a")) 5 :x]]
    (is (not (command/id? id)) (pr-str id))
    (is (= #{} (command/available (assoc sofia :id id) now)) (pr-str id)))
  (doseq [id ["appt-1" "a.b" "A-b" "x" (apply str (repeat 64 "a"))]]
    (is (command/id? id) id)))

(deftest a-command-that-is-not-possible-says-why-in-the-words-of-the-lifecycle
  (is (nil? (command/blocker sofia :confirm now)))
  (is (nil? (command/blocker ana :reschedule now)))
  (is (= "Only a proposed appointment can be confirmed; this one is booked." (command/blocker ana :confirm now)))
  (is (= "Only a booked appointment can be rescheduled; this one is proposed." (command/blocker sofia :reschedule now)))
  (is (= "Only a booked or proposed appointment can be cancelled; this one is cancelled." (command/blocker fx/ana-cancelled :cancel now)))
  (is (= "That cannot be done to this appointment." (command/blocker sofia :dance now)) "an action nobody knows"))

(deftest the-ids-of-the-page-are-safe-in-a-selector
  (is (= "confirm-appt-1" (command/dom-id "confirm" "appt-1")))
  (is (= "appointment-a_b" (command/dom-id "appointment" "a.b")) "a dot would be a class")
  (is (= "#confirm-appt-1" (command/button-selector :confirm "appt-1")))
  (is (= "#reschedule-appt_1" (command/button-selector :reschedule "appt.1")))
  (is (= "#cancel-appt-1" (command/button-selector :cancel "appt-1")))
  (is (= "#appointment-appt-1" (command/row-selector "appt-1"))))

;; ---------------------------------------------------------------------------
;; The words

(deftest each-command-has-its-title-and-the-word-on-its-button
  (is (= ["Confirm the appointment" "Reschedule the appointment" "Cancel the appointment"]
         (map #(command/title % ana) [:confirm :reschedule :cancel])))
  (is (= "Cancel the request" (command/title :cancel sofia)) "a request is not an appointment yet")
  (is (= ["Confirm" "Reschedule" "Cancel the appointment"] (map #(command/submit-label % ana) [:confirm :reschedule :cancel])))
  (is (= "Cancel the request" (command/submit-label :cancel sofia)))
  (is (= [:calendar-check :calendar-clock :calendar-x] (map #(get-in command/commands [% :icon]) command/order)))
  (is (= ["Confirm" "Reschedule" "Cancel"] (map #(get-in command/commands [% :button]) command/order))))

(deftest what-went-wrong-is-told-in-words-of-its-own
  (is (= {:title "We could not tell whether it was confirmed"
          :text "The problem is on our side, not yours. Look at the list, or try again: doing it twice does no harm."}
         (command/notice-words {:kind :unconfirmed} :confirm)))
  (is (= "We could not tell whether it was rescheduled" (:title (command/notice-words {:kind :unconfirmed} :reschedule))))
  (is (= "We could not tell whether it was cancelled" (:title (command/notice-words {:kind :unconfirmed} :cancel))))
  (is (= {:title "It was not confirmed" :text "Only a proposed appointment can be confirmed; this one is booked."}
         (command/notice-words {:kind :conflict :text "Only a proposed appointment can be confirmed; this one is booked."} :confirm))
      "a conflict is the api's own sentence")
  (is (= "Someone else changed it first." (:text (command/notice-words {:kind :conflict} :confirm))) "and, when the api gave none, ours")
  (is (= {:title "It was not rescheduled" :text "Must be at least 5 minutes after the start."}
         (command/notice-words {:kind :invalid :text "Must be at least 5 minutes after the start."} :reschedule)))
  (is (= "Check what you wrote and try again." (:text (command/notice-words {:kind :invalid} :confirm))))
  (is (= {:title "It was not cancelled" :text "You are not allowed to do this."} (command/notice-words {:kind :forbidden} :cancel)))
  (is (= {:title "That appointment could not be found" :text "It may have been removed. The list has been read again."}
         (command/notice-words {:kind :gone} :confirm))))

(deftest a-screen-reader-is-told-what-happened-with-the-name-of-the-appointment
  (is (= "COPD check-up · Sofía Rossi: Appointment confirmed for Fri 16 Oct, 10:00–10:30."
         (command/announcement :done :confirm "COPD check-up · Sofía Rossi" "Fri 16 Oct, 10:00–10:30")))
  (is (= "Appointment confirmed." (command/announcement :done :confirm nil nil)) "with no name and no time, as much as is known")
  (is (= "Appointment confirmed." (command/announcement :done :confirm "  " nil)) "a name that says nothing is no name")
  (is (= "Diabetes review · Liam Chen: Appointment rescheduled to Thu 15 Oct, 11:30–12:00."
         (command/announcement :done :reschedule "Diabetes review · Liam Chen" "Thu 15 Oct, 11:30–12:00")))
  (is (= "Appointment rescheduled." (command/announcement :done :reschedule nil nil)))
  (is (= "Diabetes review · Liam Chen: Appointment cancelled." (command/announcement :done :cancel "Diabetes review · Liam Chen" "ignored")))
  (is (= "Diabetes review: It was not cancelled." (command/announcement :conflict :cancel "Diabetes review" nil)))
  (is (= "We could not tell whether it was confirmed." (command/announcement :unconfirmed :confirm nil nil))))

;; ---------------------------------------------------------------------------
;; What the form starts with

(deftest tomorrow-is-the-next-midnight-of-the-clinic-and-not-a-day-of-milliseconds
  (is (= "2026-10-14" (command/tomorrow now)))
  (is (= "2026-10-14" (command/tomorrow "2026-10-13T21:59:59Z")) "23:59:59 in Madrid")
  (is (= "2026-10-15" (command/tomorrow "2026-10-13T22:00:00Z")) "midnight in Madrid is already the 14th")
  (is (= "2026-10-15" (command/tomorrow "2026-10-13T22:30:00Z")))
  (testing "the day the clocks go back has 25 hours: half past midnight of it is not tomorrow's date 24 hours later"
    (is (= "2026-10-26" (command/tomorrow "2026-10-24T22:30:00Z")))
    (is (= "2026-10-26" (command/tomorrow "2026-10-25T10:00:00Z")))
    (is (= "2026-10-25" (command/tomorrow "2026-10-24T10:00:00Z"))))
  (testing "and the day they go forward has 23"
    (is (= "2026-03-30" (command/tomorrow "2026-03-28T23:30:00Z")))
    (is (= "2026-03-29" (command/tomorrow "2026-03-28T10:00:00Z")))
    (is (= "2026-03-30" (command/tomorrow "2026-03-29T10:00:00Z")))))

(deftest a-request-is-proposed-the-first-day-of-the-window-it-asked-for
  (is (= {:day "2026-10-16" :from "10:00" :until "10:30"} (command/defaults sofia :confirm now)) "she asked from the 16th")
  (is (= {:day "2026-10-20" :from "10:00" :until "10:30"} (command/defaults fx/ana-request :confirm now)))
  (is (= {:day "2026-10-14" :from "10:00" :until "10:30"} (command/defaults (assoc sofia :requestedStart "2026-10-13T22:00:00Z") :confirm now))
      "a window that begins tomorrow in Madrid")
  (is (= "2026-10-16" (:day (command/defaults (assoc sofia :requestedStart "2026-10-15T22:30:00Z") :confirm now)))
      "the day of the window is the day in Madrid, and not in UTC: 22:30 UTC is 00:30 of the 16th"))

(deftest a-window-that-has-begun-is-not-a-day-in-the-past-and-one-that-is-not-read-is-tomorrow
  (is (= "2026-10-14" (:day (command/defaults (assoc sofia :requestedStart "2026-10-10T00:00:00Z") :confirm now))) "the window is open: tomorrow")
  (is (= "2026-10-14" (:day (command/defaults (assoc sofia :requestedStart "2026-10-13T00:00:00Z") :confirm now))) "it began today")
  (doseq [window [nil "" "soon" 5 "2026-10-16"]]
    (is (= "2026-10-14" (:day (command/defaults (assoc sofia :requestedStart window) :confirm now))) (pr-str window))))

(deftest a-booked-appointment-starts-from-the-time-it-has
  (is (= {:day "2026-10-14" :from "10:00" :until "10:30"} (command/defaults ana :reschedule now)))
  (is (= {:day "2026-10-16" :from "14:00" :until "14:30"} (command/defaults fx/liam-booked :reschedule now)) "in Madrid, and not in UTC")
  (is (= {:day "2026-10-15" :from "00:15" :until "00:45"}
         (command/defaults (assoc ana :start "2026-10-14T22:15:00Z" :end "2026-10-14T22:45:00Z") :reschedule now))
      "the day is the day in Madrid")
  (testing "one whose time cannot be read is proposed a time as a request is"
    (is (= {:day "2026-10-14" :from "10:00" :until "10:30"} (command/defaults (assoc ana :start "soon") :reschedule now)))
    (is (= {:day "2026-10-14" :from "10:00" :until "10:30"} (command/defaults (assoc ana :end nil) :reschedule now)))))

(deftest a-cancellation-starts-with-no-reason
  (is (= {:why ""} (command/defaults ana :cancel now))))

(deftest what-the-person-typed-is-over-what-the-form-starts-with
  (is (= {:day "2026-10-16" :from "10:00" :until "10:30"} (command/values sofia :confirm now nil)))
  (is (= {:day "2026-10-16" :from "11:15" :until "10:30"} (command/values sofia :confirm now {:from "11:15"})))
  (is (= {:why "I am away"} (command/values ana :cancel now {:why "I am away"}))))

;; ---------------------------------------------------------------------------
;; What the person typed, as what the api takes

(defn- submission
  ([appointment action typed] (submission appointment action typed now))
  ([appointment action typed at] (command/submission appointment action at (command/values appointment action now typed))))

(deftest a-confirmation-is-the-day-and-the-times-on-the-clock-of-the-clinic-as-instants
  (is (= {:body {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"}} (submission sofia :confirm nil)))
  (is (= {:body {:action "confirm" :start "2026-12-01T09:15:00Z" :end "2026-12-01T10:00:00Z"}}
         (submission sofia :confirm {:day "2026-12-01" :from "10:15" :until "11:00"}))
      "in winter time Madrid is one hour on")
  (is (= {:body {:action "reschedule" :start "2026-10-15T09:30:00Z" :end "2026-10-15T10:00:00Z"}}
         (submission ana :reschedule {:day "2026-10-15" :from "11:30" :until "12:00"})))
  (is (= {:body {:action "confirm" :start "2026-10-16T08:00:30Z" :end "2026-10-16T08:30:00Z"}}
         (submission sofia :confirm {:from "10:00:30"}))
      "a field of time that has seconds is read as it is"))

(deftest the-night-the-clocks-go-back-is-read-as-the-clinic-reads-it
  (is (= {:body {:action "confirm" :start "2026-10-25T00:30:00Z" :end "2026-10-25T02:30:00Z"}}
         (submission sofia :confirm {:day "2026-10-25" :from "02:30" :until "03:30"}))
      "02:30 happens twice and it is the first, in summer time")
  (is (= {:body {:action "confirm" :start "2026-10-25T02:30:00Z" :end "2026-10-25T02:55:00Z"}}
         (submission sofia :confirm {:day "2026-10-25" :from "03:30" :until "03:55"}))
      "03:30 happens once, in winter time"))

(deftest the-night-the-clocks-go-forward-is-read-as-the-clinic-reads-it
  (is (= {:errors {:until "Must be after the start."}}
         (submission sofia :confirm {:day "2027-03-28" :from "02:30" :until "03:30"}))
      "02:30 does not exist: it is read as 03:30, the same moment as the end")
  (is (= {:body {:action "confirm" :start "2027-03-28T00:30:00Z" :end "2027-03-28T01:30:00Z"}}
         (submission sofia :confirm {:day "2027-03-28" :from "01:30" :until "03:30"}))
      "from half past one to half past three is one hour that day")
  (is (= {:body {:action "confirm" :start "2027-03-28T02:00:00Z" :end "2027-03-28T02:30:00Z"}}
         (submission sofia :confirm {:day "2027-03-28" :from "04:00" :until "04:30"}))))

(deftest what-is-not-a-day-or-a-time-is-said-beside-the-field-and-nothing-is-sent
  (is (= {:errors {:day "Enter a date."}} (submission sofia :confirm {:day ""})))
  (is (= {:errors {:day "Enter a date."}} (submission sofia :confirm {:day "soon"})))
  (is (= {:errors {:day "Enter a date."}} (submission sofia :confirm {:day "2026-10-1"})))
  (is (= {:errors {:day "Enter a date."}} (submission sofia :confirm {:day "2026-02-30"})) "a day that does not exist")
  (is (= {:errors {:day "Enter a date."}} (submission sofia :confirm {:day "  "})))
  (is (= {:errors {:from "Enter a time."}} (submission sofia :confirm {:from ""})))
  (is (= {:errors {:from "Enter a time."}} (submission sofia :confirm {:from "late"})))
  (is (= {:errors {:from "Enter a time."}} (submission sofia :confirm {:from "25:00"})) "an hour that does not exist")
  (is (= {:errors {:from "Enter a time."}} (submission sofia :confirm {:from "10:60"})))
  (is (= {:errors {:until "Enter a time."}} (submission sofia :confirm {:until ""})))
  (is (= {:errors {:until "Enter a time."}} (submission sofia :confirm {:until "10:30:60"})))
  (is (= {:errors {:day "Enter a date." :from "Enter a time." :until "Enter a time."}} (submission sofia :confirm {:day "" :from "" :until ""}))
      "all of them at once")
  (testing "what is not text is nothing"
    (is (= {:errors {:day "Enter a date." :from "Enter a time."}} (submission sofia :confirm {:day nil :from 5})))))

(deftest spaces-around-what-was-typed-are-not-part-of-it
  (is (= {:body {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"}}
         (submission sofia :confirm {:day " 2026-10-16 " :from " 10:00 " :until " 10:30 "}))))

(deftest the-lifecycle-refuses-before-the-api-is-asked-and-its-words-are-beside-the-field
  (testing "a visit that ends when it begins, or before"
    (is (= {:errors {:until "Must be after the start."}} (submission sofia :confirm {:until "10:00"})))
    (is (= {:errors {:until "Must be after the start."}} (submission sofia :confirm {:until "09:00"}))))
  (testing "a visit of less than five minutes and one of more than four hours"
    (is (= {:errors {:until "Must be at least 5 minutes after the start."}} (submission sofia :confirm {:until "10:03"})))
    (is (= {:body {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:05:00Z"}} (submission sofia :confirm {:until "10:05"})) "five is fine")
    (is (= {:errors {:until "Must be at most 4 hours after the start."}} (submission sofia :confirm {:until "14:01"})))
    (is (= {:body {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T12:00:00Z"}} (submission sofia :confirm {:until "14:00"})) "four is fine"))
  (testing "the same for a rescheduling"
    (is (= {:errors {:until "Must be after the start."}} (submission ana :reschedule {:until "09:00"})))))

(deftest a-start-in-the-past-is-said-beside-the-day-and-beside-the-time-when-the-day-is-today
  (is (= {:errors {:day "Must not be in the past."}} (submission sofia :confirm {:day "2026-10-12"})) "yesterday: the day is wrong")
  (is (= {:errors {:from "Must not be in the past."}} (submission sofia :confirm {:day "2026-10-13" :from "09:00" :until "09:30"}))
      "today at nine, when it is two in the afternoon: the time is wrong")
  (is (= {:body {:action "confirm" :start "2026-10-13T13:00:00Z" :end "2026-10-13T13:30:00Z"}}
         (submission sofia :confirm {:day "2026-10-13" :from "15:00" :until "15:30"}))
      "today in an hour is fine")
  (testing "the day is today on the clock of the clinic, and not in UTC"
    (is (= {:errors {:from "Must not be in the past."}}
           (submission sofia :confirm {:day "2026-10-14" :from "00:10" :until "00:40"} "2026-10-13T22:30:00Z"))
        "22:30 UTC is 00:30 of the 14th: the 14th is today, and 00:10 is gone")))

(deftest a-start-too-far-ahead-is-said-beside-the-day
  (is (= {:errors {:day "Must be within 365 days from now."}} (submission sofia :confirm {:day "2028-01-01"})))
  (is (= {:body {:action "confirm" :start "2027-10-13T08:00:00Z" :end "2027-10-13T08:30:00Z"}} (submission sofia :confirm {:day "2027-10-13"}))
      "a year ahead is fine"))

(deftest the-lifecycle-is-asked-at-the-moment-of-the-press-and-not-of-the-page
  (is (= {:errors {:from "Must not be in the past."}}
         (command/submission sofia :confirm "2026-10-16T08:30:00Z" {:day "2026-10-16" :from "10:00" :until "10:30"}))
      "the page was loaded on the 13th: the person pressed on the 16th at half past ten")
  (is (map? (:body (command/submission sofia :confirm now {:day "2026-10-16" :from "10:00" :until "10:30"})))))

(deftest a-command-the-status-does-not-allow-is-a-conflict-in-the-lifecycle-s-words
  (is (= {:conflict "Only a proposed appointment can be confirmed; this one is booked."} (submission ana :confirm nil)))
  (is (= {:conflict "Only a booked appointment can be rescheduled; this one is proposed."} (submission sofia :reschedule nil)))
  (is (= {:conflict "Only a booked or proposed appointment can be cancelled; this one is cancelled."} (submission fx/ana-cancelled :cancel nil)))
  (testing "the status is judged before the values, as the api does"
    (is (= {:conflict "Only a proposed appointment can be confirmed; this one is booked."} (submission ana :confirm {:day "" :from ""})))))

(deftest a-cancellation-has-an-optional-reason
  (is (= {:body {:action "cancel"}} (submission ana :cancel nil)))
  (is (= {:body {:action "cancel"}} (submission ana :cancel {:why ""})) "no reason is none")
  (is (= {:body {:action "cancel"}} (submission ana :cancel {:why "   "})) "and blanks are none")
  (is (= {:body {:action "cancel" :reason "I am away"}} (submission ana :cancel {:why "I am away"})))
  (is (= {:body {:action "cancel" :reason "I am away"}} (submission ana :cancel {:why "  I am away  "})) "without the blanks around it")
  (is (= {:body {:action "cancel"}} (submission sofia :cancel nil)) "a request too")
  (testing "a reason of 200 characters is the longest"
    (is (= {:body {:action "cancel" :reason (apply str (repeat 200 "x"))}} (submission ana :cancel {:why (apply str (repeat 200 "x"))})))
    (is (= {:errors {:why "Must be 1 to 200 characters."}} (submission ana :cancel {:why (apply str (repeat 201 "x"))})))))

(deftest the-reason-of-a-cancellation-is-its-text-without-blanks-or-nothing
  (is (= "x" (command/reason-of " x ")))
  (is (= "a  b" (command/reason-of "a  b")) "what is inside is not touched")
  (doseq [text [nil "" "  " 5 :x [] {}]]
    (is (nil? (command/reason-of text)) (pr-str text))))

;; ---------------------------------------------------------------------------
;; Where the words of the lifecycle and of the api are written

(deftest the-words-for-the-fields-of-the-command-go-beside-the-fields-of-the-form
  (is (= {:until "Must be after the start."} (command/place {:end ["must be after the start"]} "2026-10-16" now)))
  (is (= {:why "Must be 1 to 200 characters."} (command/place {:reason ["must be 1 to 200 characters"]} nil now)))
  (is (= {:day "Must be within 365 days from now." :until "Must be after the start."}
         (command/place {:start ["must be within 365 days from now"] :end ["must be after the start"]} "2028-01-01" now)))
  (is (= {:from "Must not be in the past."} (command/place {:start ["must not be in the past"]} "2026-10-13" now)) "the start of today is the time")
  (is (= {:day "Must not be in the past."} (command/place {:start ["must not be in the past"]} "2026-10-12" now)))
  (is (= {:day "Is required."} (command/place {:start ["is required"]} nil now)) "no day was typed")
  (testing "what the api says of a field the form has not got is left out"
    (is (= {} (command/place {:action ["must be one of cancel, confirm, reschedule"]} "2026-10-16" now)))
    (is (= {} (command/place nil "2026-10-16" now)))
    (is (= {} (command/place "must be" "2026-10-16" now)))))

;; ---------------------------------------------------------------------------
;; The call, and what each answer of the api means

(deftest the-call-patches-the-appointment-with-the-command
  (is (= {:method :patch
          :path ["api" "appointments" "appt-sofia-1"]
          :body {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"}
          :id [:appointment-command]
          :on-success [:pulse.spa.appointments/answered "appt-sofia-1" :confirm]
          :on-refusal [:pulse.spa.appointments/refused "appt-sofia-1" :confirm]
          :on-failure [:pulse.spa.appointments/command-failed "appt-sofia-1" :confirm]}
         (command/request "appt-sofia-1" {:action "confirm" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"})))
  (is (= [:pulse.spa.appointments/answered "appt-ana-1" :cancel] (:on-success (command/request "appt-ana-1" {:action "cancel"})))
      "the answer says which appointment and which command it is of"))

(deftest each-answer-of-the-api-means-something
  (testing "a 409 is someone else's change, in the api's sentence"
    (is (= {:result :conflict :text "Only a proposed appointment can be confirmed; this one is booked."}
           (command/verdict {:kind :refused :status 409
                             :problem {:detail "Only a proposed appointment can be confirmed; this one is booked."}}))))
  (testing "a 409 with no sentence has none"
    (is (= {:result :conflict :text nil} (command/verdict {:kind :refused :status 409 :problem nil})))
    (is (= {:result :conflict :text nil} (command/verdict {:kind :refused :status 409 :problem {:detail "  "}}))))
  (testing "a 400 or a 422 has the errors of the api by field"
    (let [problem {:detail "The appointment cannot be changed as asked." :errors {:end ["must be after the start"]}}]
      (doseq [status [400 422]]
        (is (= {:result :invalid :errors {:end ["must be after the start"]} :text "The appointment cannot be changed as asked."}
               (command/verdict {:kind :refused :status status :problem problem}))))))
  (is (= {:result :gone} (command/verdict {:kind :not-found})))
  (is (= {:result :forbidden} (command/verdict {:kind :forbidden})))
  (is (= {:result :ignore} (command/verdict {:kind :aborted})))
  (is (= {:result :ignore} (command/verdict {:kind :unauthorized})) "the page for it answers")
  (testing "a call that did not come back may or may not have been done"
    (doseq [kind [:timeout :unreachable :server :unexpected nil]]
      (is (= {:result :unconfirmed} (command/verdict {:kind kind})) (pr-str kind)))))

;; ---------------------------------------------------------------------------
;; The state of the dialog

(deftest a-dialog-begins-with-nothing-typed-and-nothing-wrong
  (is (= {:action :confirm :id "appt-sofia-1" :fields nil :errors {} :notice nil :state :editing} (command/opened :confirm "appt-sofia-1"))))

(deftest the-button-is-busy-from-the-press-until-the-lists-have-been-read-again
  (let [dialog (command/opened :confirm "x")]
    (is (not (command/busy? dialog)))
    (is (command/busy? (command/sending dialog)))
    (is (command/busy? (command/settling (command/sending dialog))))
    (is (not (command/busy? (command/refused (command/sending dialog) {} nil))))
    (is (= #{:sending :settling} command/busy-states))))

(deftest typing-keeps-what-was-typed-and-stops-saying-what-was-wrong-with-that-field
  (let [dialog (-> (command/opened :confirm "x") (command/refused {:day "Must not be in the past." :until "Must be after the start."} nil))
        typed (-> dialog (command/typed :day "2026-10-20"))]
    (is (= {:day "2026-10-20"} (:fields typed)))
    (is (= {:until "Must be after the start."} (:errors typed)) "the other field is still wrong")
    (is (= {:day "2026-10-20" :until "09:00"} (:fields (command/typed typed :until "09:00"))))
    (is (= {} (:errors (command/typed typed :until "09:00"))))))

(deftest sending-forgets-the-last-try
  (let [dialog (-> (command/opened :confirm "x") (command/refused {:day "bad"} {:kind :unconfirmed}) command/sending)]
    (is (= :sending (:state dialog)))
    (is (= {} (:errors dialog)))
    (is (nil? (:notice dialog)))))

(deftest a-refused-command-frees-the-button-with-the-words
  (let [dialog (command/refused (command/sending (command/opened :confirm "x")) {:from "bad"} {:kind :conflict :text "No."})]
    (is (= :editing (:state dialog)))
    (is (= {:from "bad"} (:errors dialog)))
    (is (= {:kind :conflict :text "No."} (:notice dialog))))
  (is (= {} (:errors (command/refused (command/opened :confirm "x") nil nil))) "no errors is none, not nil"))

(deftest settling-keeps-what-was-typed
  (let [dialog (-> (command/opened :cancel "x") (command/typed :why "away") command/sending command/settling)]
    (is (= :settling (:state dialog)))
    (is (= {:why "away"} (:fields dialog)))))

(deftest nothing-in-the-text-of-a-notice-is-markup
  (is (str/includes? (:text (command/notice-words {:kind :conflict :text "<b>x</b>"} :confirm)) "<b>x</b>") "the text is the text"))
