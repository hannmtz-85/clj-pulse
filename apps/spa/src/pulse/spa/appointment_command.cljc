(ns pulse.spa.appointment-command
  "Answering an appointment from the workspace, as far as it can be decided without a browser (ADR-0029, points 3 and 8): a clinician
  confirms a request with a time, reschedules a booked appointment, or cancels one. The form is a dialog over the list of appointments; this
  namespace is what is in it: the values it starts with, what the person typed turned into what the api takes, what the lifecycle says
  before anything is sent, the call, what each answer of the api means, and the words. Pure and `.cljc`, so that the JVM and Node run the
  same tests over it.

  **The workspace uses the lifecycle before the api, and the api's words win.** The form checks the times with
  `pulse.fhir.appointment/change` (the same function the api judges with) before it sends them, and writes what it says beside the
  fields; and what the api refuses (a 400 or a 422, with its `errors` by field) is written beside the fields in the api's own sentences
  (`pulse.view.refusal`). A rule that the workspace wrote would be one more place to change. **It never decides that an appointment has
  changed**: it sends the command, the api answers, and the lists are read again.

  The days and the times are those of the clinic's clock (`pulse.view.format`, X-97): a person who types `10:00` means 10:00 in Madrid,
  and `fmt/local->instant` turns it into the instant the api reads, through the nights the clocks change.

  The state of the dialog is a map: `:action` (`:confirm`, `:reschedule` or `:cancel`), `:id` (of the appointment), `:fields` (what the
  person typed, nil until they type: the rest is `defaults`), `:errors` (words beside the fields, by field), `:notice` (what went wrong
  that is not about one field: `{:kind :text}`) and `:state`: `:editing`, `:sending` (from the press until the api has answered) or
  `:settling` (from the answer until the lists have been read again; in both the button is busy, so that a second press is not a second
  command)."
  (:require [clojure.string :as str]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.fhir.reference :as reference]
            [pulse.view.format :as fmt]
            [pulse.view.refusal :as refusal]
            [pulse.view.views :as views]))

(def commands
  "What each command is called and drawn: the icon of its button, the word on the button of the row (and the dialog), and what the
  appointment is once it is done."
  {:confirm {:icon :calendar-check :button "Confirm" :done "confirmed"}
   :reschedule {:icon :calendar-clock :button "Reschedule" :done "rescheduled"}
   :cancel {:icon :calendar-x :button "Cancel" :done "cancelled"}})

(def order
  "The commands in the order the buttons of a row are."
  [:confirm :reschedule :cancel])

(def default-slot
  "The time a visit is given when the form is first shown: half an hour from ten in the morning."
  {:from "10:00" :until "10:30"})

(def busy-states
  "The states in which the button of the dialog is busy."
  #{:sending :settling})

;; ---------------------------------------------------------------------------
;; What the appointment allows

(defn id?
  "Whether `id` can be the id of an appointment in an address: the id of a FHIR resource, and not `.` or `..`, which no address holds as
  a segment (a call to the api is never made with one: see `pulse.spa.http/path`). The api's list can hold an appointment with another
  (by the letter of FHIR), and nothing leads to a dialog for it."
  [id]
  (boolean (and (string? id)
                (not (contains? #{"." ".."} id))
                (reference/parse (str "Appointment/" id)))))

(defn available
  "The commands that the lifecycle lets a clinician take on `appointment` (an item of the api: `:id`, `:status` and `:start`) at `now`, as
  a set: a request can be confirmed or cancelled and a booked appointment rescheduled or cancelled, and nothing can be done to one that
  was cancelled. None for an appointment whose id no address can hold."
  [{:keys [id status start]} now]
  (if (id? id)
    (lifecycle/available-actions {:status status :start start} :clinician now)
    #{}))

(defn dom-id
  "The id of an element of the page about the appointment `id`, `prefix-id`: a dot, which a selector would take for a class, is an
  underscore."
  [prefix id]
  (str prefix "-" (str/replace (str id) #"[^A-Za-z0-9_-]" "_")))

(defn button-selector
  "The selector of the button of the row of the appointment `id` that opens the dialog of `action`."
  [action id]
  (str "#" (dom-id (name action) id)))

(defn row-selector
  "The selector of the row of the appointment `id`."
  [id]
  (str "#" (dom-id "appointment" id)))

(defn blocker
  "Why `action` cannot be taken on `appointment` at `now`, in the lifecycle's own words (the api answers a 409 with the same ones), or
  nil when it can."
  [{:keys [status start]} action now]
  (when-not (contains? (lifecycle/available-actions {:status status :start start} :clinician now) action)
    (or (:conflict (lifecycle/change {:status status :start start} {:action action} now :clinician))
        "That cannot be done to this appointment.")))

;; ---------------------------------------------------------------------------
;; The words

(defn title
  "The title of the dialog that carries out `action` on `appointment`: the question the person is answering."
  [action {:keys [status]}]
  (case action
    :confirm "Confirm the appointment"
    :reschedule "Reschedule the appointment"
    :cancel (if (= "proposed" status) "Cancel the request" "Cancel the appointment")))

(defn submit-label
  "The words on the button that sends the command."
  [action {:keys [status]}]
  (case action
    :confirm "Confirm"
    :reschedule "Reschedule"
    :cancel (if (= "proposed" status) "Cancel the request" "Cancel the appointment")))

(defn- done-word [action]
  (get-in commands [action :done]))

(defn notice-words
  "What the person is told of `notice` (`{:kind :text}`), when the command did not go through: `{:title :text}`.
  `:unconfirmed` is a call that did not come back; `:conflict` a 409, whose sentence is the api's own; `:invalid` a 400 or a 422 whose
  `errors` no field of the form has, with the api's sentence if it gave one; `:forbidden` and `:gone` are a 403 and a 404."
  [{:keys [kind text]} action]
  (case kind
    :unconfirmed {:title (str "We could not tell whether it was " (done-word action))
                  :text "The problem is on our side, not yours. Look at the list, or try again: doing it twice does no harm."}
    :conflict {:title (str "It was not " (done-word action))
               :text (or text "Someone else changed it first.")}
    :invalid {:title (str "It was not " (done-word action))
              :text (or text "Check what you wrote and try again.")}
    :forbidden {:title (str "It was not " (done-word action))
                :text "You are not allowed to do this."}
    :gone {:title "That appointment could not be found"
           :text "It may have been removed. The list has been read again."}))

(defn announcement
  "What a screen reader is told when the command `action` on the appointment named `label` ends: `result` is `:done` (the api said 200;
  `when-text` is when the appointment is now, such as `Wed 14 Oct, 10:00–10:30`, for the commands that give it a time) or the kind of a
  notice."
  [result action label when-text]
  (let [said (case result
               :done (case action
                       :confirm (str "Appointment confirmed" (when when-text (str " for " when-text)) ".")
                       :reschedule (str "Appointment rescheduled" (when when-text (str " to " when-text)) ".")
                       :cancel "Appointment cancelled.")
               (let [{:keys [title]} (notice-words {:kind result} action)] (str title ".")))]
    (if (str/blank? label) said (str label ": " said))))

;; ---------------------------------------------------------------------------
;; What the form starts with

(defn- day-of
  "The day of the instant `iso` on the clinic's clock, such as `2026-10-14`."
  [iso]
  (subs (fmt/local-input iso) 0 10))

(defn- time-of
  "The time of the instant `iso` on the clinic's clock, to the minute, such as `10:30`."
  [iso]
  (subs (fmt/local-input iso) 11 16))

(defn tomorrow
  "The day after the day it is at `now` (an instant, as text) on the clinic's clock, such as `2026-10-14`. It is the next midnight of the
  clinic and not a day's worth of milliseconds later: the night the clocks go back has 25 hours."
  [now]
  (let [at (fmt/millis now)
        midnight (first (fmt/day-ticks (inc at) (+ at (* 3 86400000)) 1))]
    (day-of (fmt/instant-text (:at midnight)))))

(defn defaults
  "The values the form of `action` has before the person types anything, for `appointment` (an item of the api) at `now`:
  `:confirm` proposes the first day of the window the patient asked for that is not past, and at the earliest tomorrow, from
  `default-slot`; `:reschedule` starts from the time the appointment has now; `:cancel` has no reason."
  [{:keys [start end requestedStart]} action now]
  (let [soonest (tomorrow now)
        proposed (fn []
                   (let [window (when (fmt/instant? requestedStart) (day-of requestedStart))]
                     (assoc default-slot :day (if (and window (pos? (compare window soonest))) window soonest))))]
    (case action
      :cancel {:why ""}
      :reschedule (if (and (fmt/instant? start) (fmt/instant? end))
                    {:day (day-of start) :from (time-of start) :until (time-of end)}
                    (proposed))
      :confirm (proposed))))

(defn values
  "What the form shows: `defaults`, with what the person typed (`fields`, a map from `:day`, `:from`, `:until` or `:why` to text, or
  nil) over them."
  [appointment action now fields]
  (merge (defaults appointment action now) fields))

;; ---------------------------------------------------------------------------
;; What the person typed

(def ^:private time-text
  "What a field of time writes: hours and minutes, and seconds if it has them. `fmt/local->instant` also takes a fraction of a second,
  which no such field writes, so this is what keeps it out."
  #"\d{2}:\d{2}(?::\d{2})?")

(defn reason-of
  "The reason that `text` writes, without the blanks around it; nil when there is none (it is optional)."
  [text]
  (when (string? text)
    (let [reason (str/trim text)]
      (when-not (str/blank? reason) reason))))

(defn- start-field
  "Where the words about the start of a visit are written: beside the day when the day is not today (it is the day that is past, or too
  far ahead), and beside the time when it is (the time is past)."
  [typed-day now]
  (if (= typed-day (day-of now)) :from :day))

(defn place
  "The words beside the fields of the form for what the lifecycle or the api said of the command: `errors` is a map from a field of the
  command (`:start`, `:end`, `:reason`) to its messages, `typed-day` the day that was typed (or nil) and `now` the moment. A map from a
  field of the form to a sentence, in the api's own words; what is said of a field the form has not got is left out."
  [errors typed-day now]
  (refusal/place {:start (start-field typed-day now) :end :until :reason :why} errors))

(defn submission
  "What the form of `action` says for `appointment` at `now`, when it holds `values` (see `values`):
    `{:body {...}}`, the command as the api takes it (`{:action \"confirm\" :start :end}` or `{:action \"cancel\" :reason}`), when
      nothing is wrong that the workspace can see;
    `{:errors {field words}}`, the words beside the fields that could not be read (no day, no time) or that the lifecycle refuses;
    `{:conflict text}`, when the lifecycle says that the status does not allow the action.
  Nothing is sent for the last two."
  [appointment action now values]
  ;; the status is judged before the values, as the api does: what cannot be done to this appointment is not made better by a time
  (if-let [conflict (blocker appointment action now)]
    {:conflict conflict}
    (let [known (select-keys appointment [:status :start])]
      (if (= :cancel action)
        (let [reason (reason-of (:why values))
              checked (lifecycle/change known (cond-> {:action :cancel} reason (assoc :reason reason)) now :clinician)]
          (if-let [errors (:errors checked)]
            {:errors (place errors nil now)}
            {:body (cond-> {:action "cancel"} reason (assoc :reason reason))}))
        (let [day (str/trim (str (:day values)))
              from (str/trim (str (:from values)))
              until (str/trim (str (:until values)))
              ;; whether a day or a time exists (the 30th of February, 25:00) is for `fmt/local->instant` to say
              time? (fn [text] (and (re-matches time-text text) (some? (fmt/local->instant (str "2000-01-03T" text)))))
              problems (cond-> {}
                         (nil? (fmt/local->instant (str day "T00:00"))) (assoc :day "Enter a date.")
                         (not (time? from)) (assoc :from "Enter a time.")
                         (not (time? until)) (assoc :until "Enter a time."))]
          (if (seq problems)
            {:errors problems}
            (let [start (fmt/local->instant (str day "T" from))
                  end (fmt/local->instant (str day "T" until))
                  checked (lifecycle/change known {:action action :start start :end end} now :clinician)]
              (if-let [errors (:errors checked)]
                {:errors (place errors day now)}
                {:body {:action (name action) :start start :end end}}))))))))

;; ---------------------------------------------------------------------------
;; The call and what its answer means

(defn request
  "The call that carries out `body` (see `submission`) on the appointment `id`, as the `:http` effect takes it. There is one on its way
  at a time, and the answer is told to the events of `pulse.spa.appointments` with the appointment and the command."
  [id body]
  (let [action (keyword (:action body))]
    {:method :patch
     :path ["api" "appointments" id]
     :body body
     :id [:appointment-command]
     :on-success [:pulse.spa.appointments/answered id action]
     :on-refusal [:pulse.spa.appointments/refused id action]
     :on-failure [:pulse.spa.appointments/command-failed id action]}))

(defn verdict
  "What the answer of the api to the command means, from the failure the effect tells (`{:kind :status :problem}`; a refusal has the kind
  `:refused`): `{:result r ...}` where `r` is
    `:conflict` (409: the status does not allow it now, someone got there first; `:text` is the api's sentence),
    `:invalid` (400 or 422: `:errors` are the api's, by field of the command, and `:text` its sentence),
    `:gone` (404), `:forbidden` (403: the api does not let this person),
    `:unconfirmed` (the call did not come back: the command may or may not have been done, and the lists are read again to find out), and
    `:ignore` for a call that was stopped or a sign-in the api did not accept, which the page for it answers."
  [{:keys [kind status problem]}]
  (case kind
    :refused (let [text (views/text-of (:detail problem))]
               (if (= 409 status)
                 {:result :conflict :text text}
                 {:result :invalid :errors (:errors problem) :text text}))
    :not-found {:result :gone}
    :forbidden {:result :forbidden}
    (:aborted :unauthorized) {:result :ignore}
    {:result :unconfirmed}))

;; ---------------------------------------------------------------------------
;; The state of the dialog

(defn opened
  "The dialog that carries out `action` on the appointment `id`, just opened: nothing typed, nothing wrong."
  [action id]
  {:action action :id id :fields nil :errors {} :notice nil :state :editing})

(defn busy?
  "Whether the button of the dialog is busy: the api has not answered, or the lists have not been read again."
  [dialog]
  (contains? busy-states (:state dialog)))

(defn typed
  "`dialog` once the person typed `value` into `field`: what is wrong with that field is no longer said, since they are changing it."
  [dialog field value]
  (-> dialog
      (update :fields #(assoc (or % {}) field value))
      (update :errors dissoc field)))

(defn sending
  "`dialog` with the command on its way: nothing is said of the last try."
  [dialog]
  (assoc dialog :state :sending :errors {} :notice nil))

(defn settling
  "`dialog` once the api has answered: the button stays busy until the lists have been read again."
  [dialog]
  (assoc dialog :state :settling))

(defn refused
  "`dialog` once the command did not go through: the button is free again, with the words beside the fields (`errors`) and `notice` (a
  map `{:kind :text}`, or nil)."
  [dialog errors notice]
  (assoc dialog :state :editing :errors (or errors {}) :notice notice))
