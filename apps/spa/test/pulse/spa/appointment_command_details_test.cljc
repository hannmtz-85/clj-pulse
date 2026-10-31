(ns pulse.spa.appointment-command-details-test
  "What a mutation analysis of `pulse.spa.appointment-command` found that the first tests did not look at: the characters an id keeps in a
  selector, that a forbidden answer is told in our words whatever the api added, a visit that crosses midnight, a time with a fraction of a
  second, and a reason too long for a begun appointment. Pure, so it runs on the JVM and on Node over the same data."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.appointment-command :as command]
            [pulse.spa.test.appointments-fixtures :as fx]))

(def ^:private now fx/now) ; Tuesday 13 October 2026, 14:00 in Madrid

(deftest the-id-of-an-element-keeps-its-capitals-digits-and-hyphens-and-turns-the-rest-into-underscores
  (is (= "confirm-Appt-1" (command/dom-id "confirm" "Appt-1")) "a capital stays")
  (is (= "confirm-A_b_c" (command/dom-id "confirm" "A.b c")) "a dot and a space do not")
  (is (= "x-09_z" (command/dom-id "x" "09_z")) "digits and an underscore stay")
  (is (= "#cancel-Appt-1" (command/button-selector :cancel "Appt-1"))))

(deftest a-forbidden-answer-is-told-in-our-words-whatever-the-api-added
  (is (= {:title "It was not cancelled" :text "You are not allowed to do this."}
         (command/notice-words {:kind :forbidden :text "a sentence of the api's"} :cancel))))

(deftest a-visit-that-crosses-midnight-is-rescheduled-from-the-day-it-begins
  (is (= {:day "2026-10-14" :from "23:30" :until "00:30"}
         (command/defaults (assoc fx/ana-booked :start "2026-10-14T21:30:00Z" :end "2026-10-14T22:30:00Z") :reschedule now))
      "23:30 to 00:30 in Madrid: the form's day is the day it begins"))

(deftest a-time-with-a-fraction-of-a-second-is-not-a-time-of-the-form
  (doseq [typed [{:from "10:00:30.5"} {:from "10:00:30.500"} {:until "10:30:00.1"}]
          :let [field (key (first typed))]]
    (is (= {:errors {field "Enter a time."}}
           (command/submission fx/sofia-request :confirm now (command/values fx/sofia-request :confirm now typed)))
        (pr-str typed))))

(deftest a-reason-too-long-is-refused-for-an-appointment-that-has-begun-too
  (let [begun (assoc fx/ana-booked :start "2026-10-13T08:00:00Z" :end "2026-10-13T08:30:00Z")
        long-reason (apply str (repeat 201 "x"))]
    (testing "the clinic may cancel what has begun, and the lifecycle still judges the reason"
      (is (= {:errors {:why "Must be 1 to 200 characters."}}
             (command/submission begun :cancel now {:why long-reason})))
      (is (= {:body {:action "cancel" :reason "I am away"}} (command/submission begun :cancel now {:why "I am away"}))))))
