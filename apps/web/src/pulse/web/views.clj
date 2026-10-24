(ns pulse.web.views
  "What the pages of the portal show of what the api answers (ADR-0028): the words of a reading and of an appointment, made from
  the data in the shapes of the api. It is the layer between those shapes and the components of `libs/ui`: pure, free of Ring,
  and loaded by Babashka for the mock-ups of the review, which give it the synthetic cohort, so that what is reviewed is what
  is served.

  A reading is `{:id :at :unit :band}` and its numbers, `:value` or `:systolic` and `:diastolic` (`pulse.api.observations`); an
  appointment is `{:id :status :description :start :end :requestedStart :requestedEnd :practitioner :reason}`
  (`pulse.api.appointments`). Other systems write to the same FHIR server, so a reading may lack a number or a time: a page
  never fails for it, it leaves out what it cannot say."
  (:require [clojure.string :as str]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.ui.component :as c]
            [pulse.web.format :as fmt]))

;; ---------------------------------------------------------------------------
;; Readings

(def kinds
  "The measurements the interfaces show, in the order they are shown."
  [:blood-pressure :heart-rate :spo2 :glucose :weight :temperature])

(def kind-info
  "How each measurement is named and drawn."
  {:blood-pressure {:label "Blood pressure" :icon :gauge}
   :heart-rate {:label "Heart rate" :icon :heart-pulse}
   :spo2 {:label "Oxygen saturation" :icon :wind}
   :glucose {:label "Glucose" :icon :droplet}
   :weight {:label "Weight" :icon :scale}
   :temperature {:label "Temperature" :icon :thermometer}})

(defn usable?
  "Whether `reading` of the measurement `kind` has the number or numbers that say what it is: a blood pressure has both. A
  reading without them says nothing, and the api leaves a number out of a reading whose value is not one."
  [kind reading]
  (if (= :blood-pressure kind)
    (and (number? (:systolic reading)) (number? (:diastolic reading)))
    (number? (:value reading))))

(defn reading-text
  "The number or numbers of a usable reading as text: `172/104` for a blood pressure, `72` for the rest."
  [kind reading]
  (if (= :blood-pressure kind)
    (str (fmt/number (:systolic reading)) "/" (fmt/number (:diastolic reading)))
    (fmt/number (:value reading))))

(defn usable-series
  "The readings among `items` of the measurement `kind` that say something, oldest first: each has a time that can be read and its
  number or numbers. Other systems write to the same FHIR server, so a page that draws or counts a series leaves out what it
  cannot say. Readings of one moment go by their ids, since a server does not order them, and the same readings are always in the
  same order, whichever order they came in."
  [kind items]
  (->> items
       (filter #(and (fmt/instant? (:at %)) (usable? kind %)))
       (sort-by (juxt (comp fmt/millis :at) #(str (:id %))))))

(defn when-of
  "When a reading was taken, as `Today, 10:00`, or nil when it has no time that can be read."
  [reading now]
  (when (fmt/instant? (:at reading))
    (fmt/when-text (:at reading) now)))

(defn vital-tile
  "The tile of the latest reading `reading` of `kind`, as it is at `now`. `attributes` go to the tile."
  ([kind reading now] (vital-tile kind reading now nil))
  ([kind reading now attributes]
   (let [{:keys [label icon]} (kind-info kind)]
     (c/vital-tile (merge attributes
                          {:label label :icon icon
                           :value (reading-text kind reading)
                           :unit (fmt/unit (:unit reading))
                           :band (:band reading)
                           :at (when-of reading now)})))))

(defn recent-readings
  "The `n` newest readings among `items`, the items of the series of the api (each with its `:measurement`, such as
  `\"heart-rate\"`), as `{:kind :reading}`, newest first. Of readings taken at the same moment the first measurement of
  `kinds` comes first, so the order is always the same. A reading with no time that can be read, or without its numbers, is
  not among them, and neither is one of a measurement that is not known."
  [items n]
  (let [order (zipmap kinds (range))]
    (->> items
         (keep (fn [item]
                 (let [kind (some-> (:measurement item) keyword)]
                   (when (and (contains? order kind) (fmt/instant? (:at item)) (usable? kind item))
                     {:kind kind :reading item}))))
         (sort-by (fn [{:keys [kind reading]}] [(- (fmt/millis (:at reading))) (order kind)]))
         (take n))))

;; ---------------------------------------------------------------------------
;; Appointments

(defn appointment-chip
  "The chip of the status of an appointment, in words and with an icon: a confirmed one is normal, a request is
  information, and one that is over, or has no name of its own, such as a cancelled one, is a plain label."
  ([status] (appointment-chip status nil))
  ([status attributes]
   (case status
     "booked" (c/tone-badge (assoc attributes :tone :normal :icon :calendar-check) "Confirmed")
     "proposed" (c/tone-badge (assoc attributes :tone :info :icon :clock) "Requested")
     "pending" (c/tone-badge (assoc attributes :tone :info :icon :clock) "Pending")
     "fulfilled" (c/badge (assoc attributes :variant :outline) "Completed")
     (c/badge (assoc attributes :variant :outline) (str/capitalize (str status))))))

(defn appointment-row
  "An appointment as a page shows it: `:id :status :description`, `:doctor` (the name, from `doctors`, a map from the
  reference of a practitioner to their name, nil when it is not there), and the time as the page writes it: `:month :day`
  and `:when` (`Wed 14 Oct, 10:00–10:30`) for one that has a time, or `:asked` (`Asked for a visit between Oct 14 and
  Oct 20`) for a request. A cancelled one has the `:reason` that was given. Other systems write to the same FHIR server, so a time
  that cannot be read is no time: the row leaves out what it cannot say."
  [{:keys [id status description start end requestedStart requestedEnd practitioner reason]} doctors]
  (let [timed? (fmt/instant? start)]
    (cond-> {:id id
             :status status
             :description description
             :doctor (get doctors practitioner)}
      reason (assoc :reason reason)
      timed? (assoc :month (fmt/month-abbreviation start)
                    :day (fmt/day-of-month start)
                    :when (str (fmt/weekday-date start) ", " (if (fmt/instant? end) (fmt/time-range start end) (fmt/clock start)))
                    :start start)
      (and (not timed?) (fmt/instant? requestedStart)) (assoc :asked (str "Asked for a visit between "
                                                                          (if (fmt/instant? requestedEnd)
                                                                            (str/replace (fmt/date-range requestedStart requestedEnd) " to " " and ")
                                                                            (str (fmt/short-date requestedStart) " and later")))
                                                              :requested-start requestedStart))))

(defn doctors-of
  "The practitioners that the appointments of a patient can be with, as a page looks a doctor up: a map from their reference to
  their name, from what `GET /api/me` answered. The api names the general practitioner, whom every appointment of a patient is
  with."
  [me]
  (let [{:keys [reference name]} (:generalPractitioner me)]
    (if reference {reference name} {})))

(defn- moment-ms
  "When an appointment of the api is, or is asked to be, in epoch milliseconds: its start, or else the start of the window that was
  asked for. Nil when it has neither, or neither can be read."
  [{:keys [start requestedStart]}]
  (some #(when (fmt/instant? %) (fmt/millis %)) [start requestedStart]))

(defn- begun?
  "Whether an appointment of the api has begun at `now`: it has a start that can be read and it is not ahead (the moment it begins is
  the moment it has begun, as the lifecycle says)."
  [{:keys [start]} now]
  (boolean (and (fmt/instant? start) (<= (fmt/millis start) (fmt/millis now)))))

(defn appointment-sections
  "The `items` of the list of the api (`GET /api/appointments`, any order) as rows (`appointment-row`) in three sections, for a page that
  shows them all and hides none: `:coming`, the ones that are booked and have not begun at `now` and the requests, soonest first;
  `:earlier`, the ones that have begun, since the system does not know whether the visit took place, newest first; and
  `:cancelled`, newest first. An appointment with no time that can be read comes last of its section. `doctors` is as in
  `appointment-row`."
  [items doctors now]
  (let [soonest (fn [items] (sort-by (juxt #(or (moment-ms %) Long/MAX_VALUE) #(str (:id %))) items))
        newest (fn [items] (reverse (sort-by (juxt #(or (moment-ms %) Long/MIN_VALUE) #(str (:id %))) items)))
        section (fn [appointment] (cond (= "cancelled" (:status appointment)) :cancelled
                                        (begun? appointment now) :earlier
                                        :else :coming))
        by-section (group-by section items)
        rows (fn [items] (mapv #(appointment-row % doctors) items))]
    {:coming (rows (soonest (:coming by-section)))
     :earlier (rows (newest (:earlier by-section)))
     :cancelled (rows (newest (:cancelled by-section)))}))

(defn cancel-blocker
  "Why a patient cannot cancel `appointment`, one of the api's (or a row, which has the same `:status` and `:start`), at `now`: nil when
  they can, and otherwise `:already-cancelled`, `:begun` (a booked appointment that has begun, which only the clinic can cancel) or
  `:not-cancellable` (the status is none a patient can cancel). The lifecycle says (`pulse.fhir.appointment/available-actions`, ADR-0027)."
  [{:keys [status start] :as appointment} now]
  (when-not (contains? (lifecycle/available-actions {:status status :start start} :patient now) :cancel)
    (cond (= "cancelled" status) :already-cancelled
          (and (= "booked" status) (begun? appointment now)) :begun
          :else :not-cancellable)))

(defn next-appointment
  "The appointment a patient looks at first, from the `appointments` the summary gave (any order): the soonest that is booked
  and has not begun at `now` (the moment it begins is the moment it has begun, as the lifecycle says), or else the soonest
  request, or nil. A request has no time yet, so it is told by the day it asks
  for."
  [appointments now]
  (let [now-ms (fmt/millis now)
        soonest (fn [appointments time-of]
                  (first (sort-by time-of appointments)))
        booked (filter #(and (= "booked" (:status %)) (fmt/instant? (:start %)) (< now-ms (fmt/millis (:start %)))) appointments)
        requests (filter #(and (= "proposed" (:status %)) (fmt/instant? (:requestedStart %))) appointments)]
    (or (soonest booked #(fmt/millis (:start %)))
        (soonest requests #(fmt/millis (:requestedStart %))))))

;; ---------------------------------------------------------------------------
;; The summary

(defn measured-kinds
  "The measurements that the `summary` of the api has a latest reading of, in the order of `kinds`: the ones whose series has
  something to show."
  [summary]
  (filterv #(some? (get-in summary [:vitals %])) kinds))
