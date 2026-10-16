(ns pulse.scheduler.reminders
  "The appointment reminders of rule R5 (ADR-0024): a confirmed appointment that begins within the
  window, 24 hours by default, gets one `Communication` for its patient, and one only, however often
  this runs. It reads and writes FHIR and not the api: one search finds the appointments, and a
  reminder is created with `pulse.fhir.client/create-once!`, a conditional create that goes by an
  identifier made of the appointment and the instant it starts. So two runs at once, a run that is
  repeated and a restart send nothing twice, and an appointment that is moved to another time is
  reminded again.

  A run goes appointment by appointment. A failure of the infrastructure ends it, since the next
  appointment would fail the same way; any other failure is counted and the run goes on."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [pulse.fhir.client :as c]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.resource :as resource]
            [pulse.fhir.time :as time]
            [pulse.scheduler.failure :as failure]
            [taoensso.telemere :as t])
  (:import (java.time Instant ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)
           (java.util Locale)))

(def identifier-system
  "The system of the identifier of a reminder."
  "https://pulse.example/identifier/reminder")

(def rule-id
  "The rule of Annex C that a reminder carries out, as the audit trail names it."
  "R5")

;; ---------------------------------------------------------------------------
;; What to ask for

(defn query
  "The search for the appointments that are to be reminded at `now`: those that are booked and
  begin after `now` and no later than `window-hours` after it, soonest first. It is in whole
  seconds, which is how a time is booked, so that the server and the fake agree to the second."
  [^Instant now window-hours]
  (let [from (.truncatedTo now ChronoUnit/SECONDS)
        to (.plus from (long window-hours) ChronoUnit/HOURS)]
    {:status "booked"
     :date [(str "gt" from) (str "le" to)]
     :_sort "date"
     :_count 100}))

;; ---------------------------------------------------------------------------
;; What a reminder is

(defn- instant-of
  "The instant of `start`, a FHIR date and time with its offset."
  ^Instant [start]
  (Instant/ofEpochMilli (time/->millis start)))

(defn reminder-key
  "The identifier value of the reminder of `appointment`: its id and the instant it starts, in UTC
  whatever offset its time is written with, as in `appt-ana-1/2026-10-14T04:00:00Z`. An appointment
  that is moved has a new one."
  [appointment]
  (str (:id appointment) "/" (instant-of (:start appointment))))

(defn practitioner-name
  "The name of `practitioner`, a Practitioner, as it is said aloud: its given names and its family
  name, with no title (a title is not for the code to assume). Nil when it has no name, or none
  with a word in it."
  [practitioner]
  (some (fn [{:keys [given family]}]
          (let [words (remove str/blank? (concat given [family]))]
            (when (seq words)
              (str/join " " words))))
        (:name practitioner)))

(defn message
  "The text of the reminder of `appointment`: when it is, in `zone` (the name of a time zone), with
  `practitioner-name` if there is one and the description of the appointment if it has one. In
  English, whatever the language of the machine:

    Reminder: your appointment with Elena Rivera is on Wednesday 14 October at 06:00 (Hypertension follow-up)."
  [{:keys [start description]} practitioner-name zone]
  (let [at (ZonedDateTime/ofInstant (instant-of start) (ZoneId/of zone))
        ;; the English locale is part of the format: the text must not depend on the machine
        day-and-time (DateTimeFormatter/ofPattern "EEEE d MMMM 'at' HH:mm" Locale/ENGLISH)]
    (str "Reminder: your appointment"
         (when-not (str/blank? practitioner-name) (str " with " practitioner-name))
         " is on " (.format day-and-time at)
         (when-not (str/blank? description) (str " (" description ")"))
         ".")))

(defn communication
  "The Communication that reminds the patient with id `patient-id` of `appointment`, saying `text`,
  sent at `sent`, an Instant. It has the identifier of the reminder (see `reminder-key`) and no id:
  the server gives it one."
  [appointment patient-id text sent]
  (-> (resource/communication {:id "unused"
                               :patient patient-id
                               :appointment (:id appointment)
                               :sent (str sent)
                               :text text
                               :identifier {:system identifier-system :value (reminder-key appointment)}})
      (dissoc :id)))

;; ---------------------------------------------------------------------------
;; A run

(defn- participant-id
  "The id of the first participant of `appointment` whose resource is a `type`, or nil."
  [appointment type]
  (some (fn [{:keys [actor]}]
          (let [{target-type :type id :id} (ref/target actor)]
            (when (= type target-type)
              id)))
        (:participant appointment)))

(defn- read-practitioner-name
  "The name of the practitioner with id `practitioner-id`, or nil when there is none to be had: a
  reminder is worth sending without the name of the doctor. A failure that ends the run does."
  [fhir practitioner-id]
  (try
    (some-> (c/read-resource fhir "Practitioner" practitioner-id) practitioner-name)
    (catch Exception e
      (when (failure/fatal? e)
        (throw e))
      (t/event! :pulse.reminders/practitioner-unreadable
                {:level :warn :error e :data {:practitioner practitioner-id}})
      nil)))

(defn- audit-sent!
  "The record that a reminder was sent: who, what, for whom and which resource, and the rule, never
  the text."
  [actor patient-id communication-id]
  (t/event! :pulse.audit/write
            {:level :info
             :data {:actor actor
                    :action :reminder/write
                    :outcome :created
                    :patient (str "Patient/" patient-id)
                    :resource (str "Communication/" communication-id)
                    :rule rule-id}}))

(defn- remind!
  "Reminds the patient of `appointment` at `now`: `:created` if the reminder was sent now,
  `:existing` if it had been sent. Throws when it cannot."
  [{:keys [fhir actor zone name-of-practitioner]} now appointment]
  (let [patient-id (or (participant-id appointment "Patient")
                       (throw (ex-info "The appointment has no patient to remind" {:appointment (:id appointment)})))
        practitioner-id (participant-id appointment "Practitioner")
        text (message appointment (some-> practitioner-id name-of-practitioner) zone)
        {:keys [outcome id]} (c/create-once! fhir (communication appointment patient-id text now))]
    (when (= :created outcome)
      (audit-sent! actor patient-id id)
      (t/event! :pulse.reminders/sent
                {:level :info
                 :data {:appointment (:id appointment) :patient patient-id :communication id
                        :starts-at (str (instant-of (:start appointment)))}}))
    outcome))

(defn run!
  "Sends the reminders that are due at `now`, an Instant. `deps` is `:fhir` (a FHIR client),
  `:window-hours` (how long before an appointment its patient is reminded), `:zone` (the name of
  the time zone the text says the time in) and `:actor` (who is named in the audit event of a
  reminder, such as `{:client \"pulse-scheduler\" :roles [\"rules-engine\"]}`).

  Returns a summary of numbers: `:appointments` (those found to be about to begin), `:sent`
  (reminders created), `:already-sent` and `:failed` (appointments that could not be reminded,
  each logged as `:pulse.reminders/failed`). Throws when the infrastructure fails."
  [{:keys [fhir window-hours] :as deps} ^Instant now]
  (let [now (.truncatedTo now ChronoUnit/SECONDS)
        ;; the same doctor has many appointments: read each once for the run
        deps (assoc deps :name-of-practitioner (memoize #(read-practitioner-name fhir %)))
        outcome->key {:created :sent :existing :already-sent}]
    (reduce (fn [summary appointment]
              (let [summary (update summary :appointments inc)]
                (try
                  (update summary (outcome->key (remind! deps now appointment)) inc)
                  (catch Exception e
                    (when (failure/fatal? e)
                      (throw e))
                    (t/event! :pulse.reminders/failed
                              {:level :warn :error e :data {:appointment (:id appointment)}})
                    (update summary :failed inc)))))
            {:appointments 0 :sent 0 :already-sent 0 :failed 0}
            (c/search fhir "Appointment" (query now window-hours)))))
