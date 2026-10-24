(ns pulse.web.visits
  "Asking for a visit and cancelling an appointment from the forms of the portal (ADR-0028): what the patient typed, turned into what the
  api takes, and what the api refuses, turned into words beside the fields. Pure functions of text and of data in the shapes of the
  api, free of Ring and of the network, so that every way a form can be filled in is tried without a server. The page is
  `pulse.web.pages.appointments` and the handlers are `pulse.web.appointments`.

  The api decides what a request or a cancellation may be (`pulse.fhir.appointment` for the text and the window, the lifecycle for
  who may do what): the portal only turns text into the days of a window and into a reason, and says so beside the field when it
  cannot. A window that is too long, a first day too far ahead and a reason that is too long are for the api to refuse, and its words
  are what the person reads.

  The days of a window are days of the clinic's clock (`pulse.web.format/zone`): the window begins when the first day does and ends when
  the last day does, so that asking from the 20th until the 20th is asking for that whole day."
  (:require [clojure.string :as str]
            [pulse.fhir.appointment :as appointment]
            [pulse.web.format :as fmt]
            [pulse.web.refusal :as refusal])
  (:import (java.time Instant LocalDate)
           (java.time.format DateTimeParseException)
           (java.time.temporal ChronoUnit)))

(def reason-length
  "How long the reason of a request, or of a cancellation, may be, as the form says it: the api refuses a longer one."
  (:text-length appointment/limits))

(def window-days
  "How many days a window may cover, counting the first and the last, as the form says it: the api refuses a longer one."
  (:window-days appointment/limits))

(def ahead-days
  "How many days from today the window may begin, as the form says it: the api refuses a later first day."
  (:window-ahead-days appointment/limits))

(def default-window-days
  "How many days after the first day the last day is when the form is first shown: a week."
  7)

(def blank-reason
  "What is said beside the reason of a request when there is none: the api would say that it must be 1 to 200 characters long."
  "Write a few words about it.")

;; ---------------------------------------------------------------------------
;; What was typed

(defn- text-or-nil [x] (when (string? x) x))

(defn typed
  "What the form, the parameters of the form to ask for a visit, holds in its fields, as text: a map from `:reason`, `:from` and `:to`
  to what is in each, nil for one that is not there or that is not text, as a parameter that comes twice is not. It is what the form
  is shown with again when the request is not sent."
  [form]
  {:reason (text-or-nil (get form "reason"))
   :from (text-or-nil (get form "from"))
   :to (text-or-nil (get form "to"))})

(def ^:private date-text
  "A date as a browser writes the value of a field for one: four digits of the year, the month and the day."
  #"\d{4}-\d{2}-\d{2}")

(defn parse-date
  "The day that `text` writes, a `LocalDate`, or nil when it is not one: empty, not text, not written as a field for dates writes it
  (a year of four digits, which is what FHIR has), or a day that does not exist, such as the 30th of February."
  [text]
  (when (string? text)
    (let [text (str/trim text)]
      (when (re-matches date-text text)
        (try
          (LocalDate/parse text)
          (catch DateTimeParseException _ nil))))))

(defn- start-of-day
  "The moment `day`, a `LocalDate`, begins on the clinic's clock, as an instant in UTC written to the second."
  [^LocalDate day]
  (str (.truncatedTo (.toInstant (.atStartOfDay day fmt/zone)) ChronoUnit/SECONDS)))

(defn window
  "The window that the days from `first-day` to `last-day`, `LocalDate`s, are, as the api takes it: `{:requestedStart :requestedEnd}`, the
  moment the first day begins and the moment the last day ends, which is when the next one begins, on the clinic's clock."
  [^LocalDate first-day ^LocalDate last-day]
  {:requestedStart (start-of-day first-day)
   :requestedEnd (start-of-day (.plusDays last-day 1))})

(defn reason-of
  "The reason that `text` writes, without the blanks around it and with the lines the way a text is kept (a browser sends a new line
  as two characters); nil when there is none."
  [text]
  (when (string? text)
    (let [text (str/trim (str/replace text #"\r\n?" "\n"))]
      (when-not (str/blank? text) text))))

(defn submission
  "What `form`, the parameters of the form to ask for a visit, says: `{:body ...}`, the request as the api takes it
  (`{:description :requestedStart :requestedEnd}`), or `{:errors {field message}}` with the words beside each field that could not be
  read: no reason, and a day that is not one. The rest is for the api to judge."
  [form]
  (let [{:keys [reason from to]} (typed form)
        description (reason-of reason)
        first-day (parse-date from)
        last-day (parse-date to)
        errors (cond-> {}
                 (nil? description) (assoc :reason blank-reason)
                 (nil? first-day) (assoc :from "Enter a date.")
                 (nil? last-day) (assoc :to "Enter a date."))]
    (if (seq errors)
      {:errors errors}
      {:body (assoc (window first-day last-day) :description description)})))

(defn refusal
  "The words beside the fields for a request that the api refused (a 400 or a 422): `errors` is the `errors` of its problem, a map from
  a field of the api to its messages. A map from the fields of the form to a sentence, for those the form has (`description` is the
  reason, `requestedStart` the first day and `requestedEnd` the last); what the api says of anything else is left out."
  [errors]
  (refusal/place {:description :reason :requestedStart :from :requestedEnd :to} errors))

;; ---------------------------------------------------------------------------
;; The days of the form

(defn- today
  "The day it is on the clinic's clock at `now`, an instant written as text."
  ^LocalDate [now]
  (.toLocalDate (.atZone (Instant/parse now) fmt/zone)))

(defn fresh
  "What the form to ask for a visit holds when it is first shown at `now`: no reason, today as the first day and a week after it as the
  last, as the text of the fields."
  [now]
  (let [day (today now)]
    {:reason ""
     :from (str day)
     :to (str (.plusDays day default-window-days))}))

(defn bounds
  "The earliest and the latest day that the fields for the days offer, as the text of `min` and `max` at `now`: a first day is today or
  later and at most `ahead-days` from now, and the last day is not before today and at most `window-days` after the latest first day. A
  browser only offers them; the api judges."
  [now]
  (let [day (today now)
        latest-first (.plusDays day ahead-days)]
    {:from {:min (str day) :max (str latest-first)}
     :to {:min (str day) :max (str (.plusDays latest-first (dec window-days)))}}))

;; ---------------------------------------------------------------------------
;; Cancelling

(defn why-typed
  "What the form to cancel holds in the field for why, as text, or nil when it is not text (a parameter that comes twice is not)."
  [form]
  (text-or-nil (get form "why")))

(defn cancellation
  "The cancellation that `form`, the parameters of the form to cancel, says, as the api takes it: `{:action \"cancel\"}` with the reason
  when the patient wrote one. A reason is optional; the api judges how long it may be."
  [form]
  (let [reason (reason-of (why-typed form))]
    (cond-> {:action "cancel"}
      reason (assoc :reason reason))))

(defn cancellation-refusal
  "The words beside the field for why, for a cancellation that the api refused (a 400 or a 422), from the `errors` of its problem: the
  `reason` of the api is the `why` of the form. Empty when there is nothing to place."
  [errors]
  (refusal/place {:reason :why} errors))
