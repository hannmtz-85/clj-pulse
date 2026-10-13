(ns pulse.fhir.synthetic
  "Deterministic synthetic clinical data: the Pulse demo cohort as FHIR resources.

  `generate` turns the cohort description (resources/pulse/seed/cohort.edn) into resources.
  It is a pure function of the cohort, the end instant and the number of days: the same
  inputs always give the same resources, so seeding is idempotent and tests can assert on
  exact scenarios. Randomness comes from a seeded PRNG per patient; measurements are
  generated day by day, signal by signal, drawing noise whether or not the reading falls
  before the end instant, so the values of a given day do not change when the end instant
  moves.

  Used by `bb seed` and, later, by the scheduler's device simulator."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.resource :as r])
  (:import (java.time Instant LocalDate ZoneOffset)
           (java.time.temporal ChronoUnit)
           (java.util Random)))

(defn cohort
  "The default demo cohort, read from the classpath (never from a working-directory path)."
  []
  (with-open [rdr (java.io.PushbackReader. (io/reader (io/resource "pulse/seed/cohort.edn")))]
    (edn/read rdr)))

;; ---------------------------------------------------------------------------
;; Reproducible randomness

(defn- seed-of
  "A stable 64-bit seed from a string (explicit, so it never depends on `hash`)."
  [^String s]
  (reduce (fn [h c] (unchecked-add (unchecked-multiply 31 h) (long (int c)))) 7 s))

(defn- make-rng ^Random [s] (Random. (seed-of s)))

(defn- gaussian
  "An approximately standard normal sample: the sum of 12 uniform samples minus 6
  (Irwin-Hall). Mean 0, standard deviation 1, and never beyond +-6. It only adds, and
  IEEE 754 specifies addition exactly, so the result is bit-for-bit identical on every JVM
  and in Babashka (which has neither StrictMath nor guaranteed-identical Math.log/cos)."
  [^Random rng]
  (- (reduce + (repeatedly 12 #(.nextDouble rng))) 6.0))

(defn- round-to [x decimals]
  (if (zero? decimals)
    (long (Math/round (double x)))
    (let [f (Math/pow 10 decimals)]
      (/ (Math/round (* x f)) f))))

;; ---------------------------------------------------------------------------
;; One numeric channel of a signal

(defn- channel-value
  "The value of `channel` on `day` (0 = last day, negative = earlier) at hour `slot`, for
  the noise sample `z`. An event for that day and slot overrides the computed value."
  [{:keys [baseline sd by-slot ramp events decimals] :or {sd 0 decimals 0}} day slot z]
  (let [t (if ramp
            (-> (/ (+ day (:days ramp)) (double (:days ramp))) (max 0.0) (min 1.0))
            0.0)
        drift (if ramp (* t (- (:to ramp) baseline)) 0.0)
        event (some (fn [e] (when (and (= day (:day e)) (= slot (:slot e))) (:value e)))
                    events)]
    (round-to (or event (+ baseline (get by-slot slot 0) drift (* sd z))) decimals)))

;; ---------------------------------------------------------------------------
;; Signals: one multimethod, one method per kind of measurement

(defmulti reading
  "One reading of a signal as an Observation. `spec` is the signal's entry in the cohort,
  `ctx` is {:rng :patient (the id without \"pt-\") :patient-id :day :slot :at :stamp}.
  Dispatches on the signal kind."
  (fn [spec _ctx] (:signal spec)))

(defn- observation-id [prefix {:keys [patient stamp]}]
  (str "obs-" patient "-" prefix "-" stamp))

(defn- quantity-reading [spec {:keys [rng day slot at patient-id] :as ctx} id-prefix concept unit category]
  (obs/quantity-observation
   {:id (observation-id id-prefix ctx)
    :patient patient-id
    :concept concept
    :value (channel-value (:channel spec) day slot (gaussian rng))
    :unit unit
    :at at
    :category category}))

(defmethod reading :blood-pressure [spec {:keys [rng day slot at patient-id] :as ctx}]
  ;; systolic first, then diastolic: the order of the draws is part of the data's identity
  (let [sys (channel-value (:systolic spec) day slot (gaussian rng))
        dia (channel-value (:diastolic spec) day slot (gaussian rng))]
    (obs/blood-pressure-observation {:id (observation-id "bp" ctx)
                                     :patient patient-id
                                     :systolic sys
                                     :diastolic dia
                                     :at at})))

(defmethod reading :heart-rate [spec ctx]
  (quantity-reading spec ctx "hr" :heart-rate "/min" "vital-signs"))

(defmethod reading :spo2 [spec ctx]
  (quantity-reading spec ctx "spo2" :spo2 "%" "vital-signs"))

(defmethod reading :weight [spec ctx]
  (quantity-reading spec ctx "wt" :body-weight "kg" "vital-signs"))

(defmethod reading :glucose [spec ctx]
  (quantity-reading spec ctx "glu" :glucose "mg/dL" "laboratory"))

(defmethod reading :temperature [spec ctx]
  (quantity-reading spec ctx "temp" :body-temperature "Cel" "vital-signs"))

;; ---------------------------------------------------------------------------
;; Time

(defn- slot-instant ^Instant [^LocalDate date slot]
  (.toInstant (.plusHours (.atStartOfDay date ZoneOffset/UTC) (long slot))))

(defn- stamp
  "yyyyMMddHH of an instant string: \"2026-10-13T08:00:00Z\" -> \"2026101308\"."
  [at]
  (subs (apply str (filter #(Character/isDigit ^char %) at)) 0 10))

(defn- hours-from [^Instant end hours]
  (.truncatedTo (.plus end (long hours) ChronoUnit/HOURS) ChronoUnit/HOURS))

(defn- days-from [^Instant end days]
  (.truncatedTo (.plus end (long days) ChronoUnit/DAYS) ChronoUnit/DAYS))

;; ---------------------------------------------------------------------------
;; A patient

(defn- short-id [patient-id] (subs patient-id 3))

(defn- observations
  "Every reading of `patient` up to `end`, oldest first."
  [{:keys [id signals]} ^Instant end days]
  (let [rng (make-rng id)
        end-date (LocalDate/ofInstant end ZoneOffset/UTC)]
    (vec
     (for [day (range (- (dec days)) 1)
           spec signals
           slot (:slots spec)
           :let [at-instant (slot-instant (.plusDays end-date (long day)) slot)
                 at (str at-instant)
                 ;; always draw, even for readings that fall after `end`
                 observation (reading spec {:rng rng :patient (short-id id) :patient-id id
                                            :day day :slot slot :at at :stamp (stamp at)})]
           :when (not (.isAfter at-instant end))]
       observation))))

(defn- appointment [^Instant end patient-id {:keys [requested minutes in-hours] :as appt}]
  (let [base {:id (:id appt) :patient patient-id :practitioner (:practitioner appt)
              :description (:description appt)}]
    (r/appointment
     (if requested
       (assoc base
              :requested-start (str (days-from end (:from-days requested)))
              :requested-end (str (days-from end (:to-days requested))))
       (let [start (hours-from end in-hours)]
         (assoc base
                :start (str start)
                :end (str (.plus start (long minutes) ChronoUnit/MINUTES))
                :minutes minutes))))))

(defn- patient-resources [end days patient]
  (let [pid (:id patient)]
    (concat
     (for [c (:conditions patient)]
       (r/condition (assoc c :patient pid)))
     (for [m (:medications patient)]
       (r/medication-request (assoc m :patient pid :practitioner (:gp patient))))
     (for [a (:allergies patient)]
       (r/allergy-intolerance (assoc a :patient pid)))
     (for [a (:appointments patient)]
       (appointment end pid a))
     (observations patient end days))))

;; ---------------------------------------------------------------------------
;; The cohort

(defn generate
  "All the resources of `cohort` as a vector in dependency order (organization,
  practitioners, patients, then everything about each patient).

  Options: :end-instant (an Instant, default now) is when the last reading may be taken;
  :days (default 30) is how many days of readings to produce."
  ([cohort] (generate cohort {}))
  ([cohort {:keys [end-instant days] :or {days 30}}]
   (let [end (or end-instant (Instant/now))
         org (:id (:organization cohort))]
     (vec
      (concat
       [(r/organization (:organization cohort))]
       (map r/practitioner (:practitioners cohort))
       (map #(r/patient (assoc % :organization org)) (:patients cohort))
       (mapcat #(patient-resources end days %) (:patients cohort)))))))

(defn counts
  "{resourceType count} of a seq of resources."
  [resources]
  (frequencies (map :resourceType resources)))
