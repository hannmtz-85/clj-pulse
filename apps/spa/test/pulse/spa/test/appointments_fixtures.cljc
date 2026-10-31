(ns pulse.spa.test.appointments-fixtures
  "The appointments of a practitioner as the tests need them, in the shapes the api answers (ADR-0015, ADR-0029): the clinic of
  `pulse.spa.test.panel-fixtures` at the same moment (14:00 on Tuesday 13 October 2026 in Madrid), with two requests that wait for an
  answer, Sofía's and Ana's, two appointments that are booked, Ana's tomorrow morning and Liam's on Friday, and one that Ana cancelled
  in September. It is not a namespace of tests (its name does not end in `-test`) and it is `.cljc`, so that the JVM and Node tests alike
  take their data from here."
  (:require [pulse.spa.test.panel-fixtures :as panel-fx]))

(def now panel-fx/now)

(defn loaded [data]
  {:state :loaded :data data})

(def loading {:state :loading})

(def failed {:state :failed :kind :server})

(defn page
  "What `GET /api/appointments` answers for `items`: the page of them, `total` being how many there are in all (the number of `items` unless
  it is said)."
  ([items] (page items (count items)))
  ([items total] {:total total :offset 0 :limit 100 :items (vec items)}))

(def sofia-request
  {:id "appt-sofia-1" :status "proposed" :description "COPD check-up (requested by the patient)"
   :start nil :end nil :requestedStart "2026-10-16T00:00:00Z" :requestedEnd "2026-10-23T00:00:00Z"
   :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-sofia"})

(def ana-request
  {:id "appt-ana-2" :status "proposed" :description "Medication review"
   :start nil :end nil :requestedStart "2026-10-20T00:00:00Z" :requestedEnd "2026-10-27T00:00:00Z"
   :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"})

(def ana-booked
  {:id "appt-ana-1" :status "booked" :description "Hypertension follow-up"
   :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z" :requestedStart nil :requestedEnd nil
   :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"})

(def liam-booked
  {:id "appt-liam-1" :status "booked" :description "Diabetes review"
   :start "2026-10-16T12:00:00Z" :end "2026-10-16T12:30:00Z" :requestedStart nil :requestedEnd nil
   :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-liam"})

(def ana-cancelled
  {:id "appt-ana-0" :status "cancelled" :description "Blood pressure check"
   :start "2026-09-29T07:00:00Z" :end "2026-09-29T07:30:00Z" :requestedStart nil :requestedEnd nil
   :practitioner "Practitioner/pr-rivera" :reason "I had to travel that week." :patient "Patient/pt-ana"})

(def requests (page [sofia-request ana-request]))

(def booked (page [ana-booked liam-booked]))

(def cancelled (page [ana-cancelled]))

(def none (page []))

(def patients panel-fx/patients)

(def sofia-confirmed
  "Sofía's request once it is confirmed for Friday 16 October from 10:00 to 10:30 (Madrid)."
  (assoc sofia-request :status "booked" :start "2026-10-16T08:00:00Z" :end "2026-10-16T08:30:00Z"))

(def sofia-cancelled
  "Sofía's request once it is cancelled, with a reason."
  (assoc sofia-request :status "cancelled" :reason "We have no hours that week."))

(def ana-rescheduled
  "Ana's booked appointment once it is moved to Thursday 15 October from 11:30 to 12:00 (Madrid)."
  (assoc ana-booked :start "2026-10-15T09:30:00Z" :end "2026-10-15T10:00:00Z"))

(def requests-after-confirming-sofia (page [ana-request]))

(def booked-after-confirming-sofia (page [ana-booked sofia-confirmed liam-booked]))

(def cancelled-after-cancelling-sofia (page [ana-cancelled sofia-cancelled]))

(defn settled
  "The page when every part has loaded: the state it shows when all went well."
  []
  {:now now
   :requests (loaded requests)
   :booked (loaded booked)
   :cancelled (loaded cancelled)
   :patients (loaded patients)
   :latest {}
   :adopt? false
   :queue []
   :inflight #{}
   :dialog nil
   :said nil})

(defn body-of
  "What the api answers for the task `task` of the page, as the fixtures have it, or nil for one they have no answer for."
  [task]
  (let [type (if (vector? task) (first task) task)]
    (case type
      :requests requests
      :booked booked
      :cancelled cancelled
      :patients patients
      :check (case (second task) :requests requests :booked booked :cancelled cancelled)
      nil)))
