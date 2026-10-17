(ns pulse.fhir.appointment
  "The lifecycle of an appointment as Pulse has it (ADR-0015, ADR-0027). A patient asks for one
  and it is `proposed`; a clinician `confirm`s it with a time and it is `booked`, may `reschedule`
  it, and may `cancel` it, after which it is `cancelled` for good. A patient may cancel their own
  until it begins. The rules are data, `transitions`, and say who may take each action, a
  `:patient` or a `:clinician`; `change` applies one of them as a pure function of the
  appointment, what is asked, the time it is asked at and who asks.

  Times are instant strings as FHIR has them (\"2026-10-20T09:00:00Z\"), compared as the epoch
  milliseconds `pulse.fhir.time` gives. They are stored as they are given, so a caller that wants
  UTC normalizes first."
  (:require [clojure.string :as str]
            [pulse.fhir.reference :as ref]
            [pulse.fhir.resource :as resource]
            [pulse.fhir.time :as time]))

(def limits
  "What can be asked, as data so that a form can show it. A booked time (a slot) lasts
  `:slot-minutes`, `[min max]`, and starts at most `:slot-ahead-days` from now. The window of a
  request is at most `:window-days` long and starts at most `:window-ahead-days` from now. A
  description or a reason has at most `:text-length` characters."
  {:slot-minutes [5 240]
   :slot-ahead-days 365
   :window-days 30
   :window-ahead-days 180
   :text-length 200})

(def actors
  "The kinds of person who act on an appointment: a `:patient`, on their own, and a `:clinician`,
  a practitioner or the administrator."
  #{:patient :clinician})

(def transitions
  "What can be done to an appointment: the statuses it may be in (`:from`), the status it is in
  afterwards (`:to`), how the answer to the caller words it (`:done`) and who may do it (`:by`),
  a map from the kind of actor to what restricts them. A clinician is not restricted. A patient
  can only cancel, and only until the appointment begins (`:until-start?`). A kind of actor that
  is not in `:by` may not take the action. A `cancelled` appointment is in no `:from`: it is
  over."
  {:confirm {:from #{"proposed"} :to "booked" :done "confirmed" :by {:clinician {}}}
   :reschedule {:from #{"booked"} :to "booked" :done "rescheduled" :by {:clinician {}}}
   :cancel {:from #{"proposed" "booked"} :to "cancelled" :done "cancelled"
            :by {:clinician {} :patient {:until-start? true}}}})

;; ---------------------------------------------------------------------------
;; Reading times and checking text

(def ^:private minute-ms 60000)
(def ^:private day-ms 86400000)

(defn- millis
  "The epoch milliseconds of `s`, a date and time with its offset, or nil when it is not one. A
  date alone is not a time here."
  [s]
  (when (and (string? s) (re-find #"T" s))
    (let [ms (try (time/->millis s)
                  (catch #?(:clj Exception :cljs :default) _ nil))]
      (when (and (number? ms) #?(:clj true :cljs (not (js/isNaN ms))))
        ms))))

(defn- now-millis [now]
  (or (millis now)
      (throw (ex-info "`now` must be an instant" {:now now}))))

(defn- instant-error
  "The message for `x` when it is not a date and time with its offset, or nil."
  [x]
  (cond
    (nil? x) "is required"
    (nil? (millis x)) "must be a date and time with its offset, such as 2026-10-13T08:00:00Z"))

(defn- text-error
  "The message for `s` when it is not a text of 1 to `:text-length` characters, or nil."
  [s]
  (let [limit (:text-length limits)]
    (cond
      (nil? s) "is required"
      (not (string? s)) "must be text"
      (or (str/blank? s) (> (count s) limit)) (str "must be 1 to " limit " characters"))))

(defn- duration-text [minutes]
  (if (and (pos? minutes) (zero? (mod minutes 60)))
    (let [hours (quot minutes 60)]
      (str hours (if (= 1 hours) " hour" " hours")))
    (str minutes " minutes")))

(defn- errors-of
  "A map from field to `[message]` for the fields of `messages` that have one, or nil for none."
  [& field-messages]
  (not-empty (into {} (keep (fn [[field message]] (when message [field [message]])))
                   (partition 2 field-messages))))

(defn- ahead-error [start-ms now-ms days]
  (when (> start-ms (+ now-ms (* day-ms days)))
    (str "must be within " days " days from now")))

(defn- slot-errors
  "The errors of the time from `start` to `end` as a time to book, seen at `now-ms`: a map from
  field to messages, or nil when it can be booked."
  [start end now-ms]
  (let [start-error (instant-error start)
        end-error (instant-error end)]
    (if (or start-error end-error)
      (errors-of :start start-error :end end-error)
      (let [s (millis start)
            e (millis end)
            [min-minutes max-minutes] (:slot-minutes limits)
            length (- e s)]
        (errors-of
         :start (cond (< s now-ms) "must not be in the past"
                      :else (ahead-error s now-ms (:slot-ahead-days limits)))
         :end (cond (<= e s) "must be after the start"
                    (< length (* min-minutes minute-ms)) (str "must be at least " (duration-text min-minutes) " after the start")
                    (> length (* max-minutes minute-ms)) (str "must be at most " (duration-text max-minutes) " after the start")))))))

(defn- window-errors
  "The errors of the window from `start` to `end` that a patient asks to be given a time in,
  seen at `now-ms`: a map from field to messages, or nil when it can be asked for. A window that
  began in the past and is still open is fine."
  [start end now-ms field-start field-end]
  (let [start-error (instant-error start)
        end-error (instant-error end)]
    (if (or start-error end-error)
      (errors-of field-start start-error field-end end-error)
      (let [s (millis start)
            e (millis end)]
        (errors-of
         field-start (ahead-error s now-ms (:window-ahead-days limits))
         field-end (cond (<= e s) "must be after the start"
                         (<= e now-ms) "must be in the future"
                         (> (- e s) (* day-ms (:window-days limits)))
                         (str "must be at most " (:window-days limits) " days after the start")))))))

;; ---------------------------------------------------------------------------
;; What an appointment allows

(defn- begun?
  "Whether `appointment` has begun as of `now-ms`: it has a time, and that time is not ahead. A
  request, which has none yet, has not."
  [appointment now-ms]
  (let [start (millis (:start appointment))]
    (boolean (and start (<= start now-ms)))))

(defn- open-to?
  "Whether what `restrictions` says of a kind of actor (an entry of `:by`) leaves `appointment`
  open to them as of `now-ms`."
  [restrictions appointment now-ms]
  (not (and (:until-start? restrictions) (begun? appointment now-ms))))

(defn available-actions
  "The actions that `actor`, a `:patient` or a `:clinician`, may take on `appointment` at `now`,
  as a set of keywords: its status allows them, `transitions` lets that kind of actor take them,
  and nothing that restricts them has happened (a patient may no longer cancel an appointment
  that has begun). Whether this caller may touch this appointment at all is for authorization to
  say. Any other kind of actor may take none. Pure."
  [appointment actor now]
  (let [now-ms (now-millis now)]
    (into #{}
          (for [[action {:keys [from by]}] transitions
                :let [restrictions (get by actor)]
                :when (and (some? restrictions)
                           (contains? from (:status appointment))
                           (open-to? restrictions appointment now-ms))]
            action))))

;; ---------------------------------------------------------------------------
;; Asking for an appointment

(defn request-errors
  "The errors of a request for an appointment, `{:description :requestedStart :requestedEnd}`,
  made at `now`: a map from field to messages, or nil when it can be made. It does not throw,
  whatever it is given, except for a `now` that is not an instant. Pure."
  [{:keys [description requestedStart requestedEnd]} now]
  (let [now-ms (now-millis now)]
    (merge (errors-of :description (text-error description))
           (window-errors requestedStart requestedEnd now-ms :requestedStart :requestedEnd))))

(defn proposal
  "The Appointment that a request becomes: `proposed`, the patient accepted and the practitioner
  yet to answer. `:id` is the new resource's, `:patient` and `:practitioner` are ids, and the rest
  is the request as `request-errors` checks it."
  [{:keys [id patient practitioner description requestedStart requestedEnd]}]
  (resource/appointment {:id id
                         :patient patient
                         :practitioner practitioner
                         :description description
                         :requested-start requestedStart
                         :requested-end requestedEnd}))

;; ---------------------------------------------------------------------------
;; Changing an appointment

(defn- minutes-between [start end]
  (quot (- (millis end) (millis start)) minute-ms))

(defn- practitioners-accept [participants]
  (mapv (fn [participant]
          (if (= "Practitioner" (:type (ref/target (:actor participant))))
            (assoc participant :status "accepted")
            participant))
        participants))

(defn- apply-action [appointment action {:keys [start end reason]}]
  (let [to (get-in transitions [action :to])]
    (case action
      (:confirm :reschedule)
      (-> appointment
          (assoc :status to :start start :end end :minutesDuration (minutes-between start end))
          (update :participant practitioners-accept))

      :cancel
      (cond-> (assoc appointment :status to)
        (some? reason) (assoc :cancelationReason {:text reason})))))

(defn- conflict-message [{:keys [status]} action]
  (let [{:keys [from done]} (get transitions action)]
    (str "Only a " (str/join " or " (sort from)) " appointment can be " done "; this one is " (or status "of no known status") ".")))

(defn- forbidden-message [action actor]
  (str "A " (if (keyword? actor) (name actor) "caller of that kind") " may not " (name action) " an appointment."))

(defn- begun-message [action]
  (str "This appointment has already begun, so only the clinic can " (name action) " it."))

(defn change
  "Applies `command`, `{:action :confirm :start s :end e}`, `{:action :reschedule :start s :end e}`
  or `{:action :cancel :reason r}` (the reason is optional), to `appointment` as of `now`, for
  `actor`, the kind of person who asks: `:patient` or `:clinician`. Returns

    {:ok appointment'}            the appointment, changed
    {:forbidden message}          that kind of actor may not take the action
    {:conflict message}           its status, or the time, does not allow the action
    {:errors {field [message]}}   the values cannot be right, named as in the command

  What the kind of actor may do is checked first, then the status and the time, then the values.
  A kind of actor that `transitions` does not name may do nothing. It does not throw, whatever
  the command holds, except for a `now` that is not an instant. Pure."
  [appointment {:keys [action reason] :as command} now actor]
  (let [now-ms (now-millis now)]
    (if-let [{:keys [from by]} (get transitions action)]
      (if-let [restrictions (get by actor)]
        (cond
          (not (contains? from (:status appointment)))
          {:conflict (conflict-message appointment action)}

          (not (open-to? restrictions appointment now-ms))
          {:conflict (begun-message action)}

          :else
          (if-let [errors (case action
                            (:confirm :reschedule) (slot-errors (:start command) (:end command) now-ms)
                            :cancel (when (some? reason)
                                      (errors-of :reason (text-error reason))))]
            {:errors errors}
            {:ok (apply-action appointment action command)}))
        {:forbidden (forbidden-message action actor)})
      {:errors {:action [(str "must be one of " (str/join ", " (map name (sort (keys transitions)))))]}})))
