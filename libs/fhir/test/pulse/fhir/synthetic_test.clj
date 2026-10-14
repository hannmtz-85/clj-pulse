(ns pulse.fhir.synthetic-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.measurement :as measurement]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.ranges :as ranges]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.synthetic :as synthetic])
  (:import (java.time Instant)))

(def ^:private cohort (synthetic/cohort))

(defn- generate [end] (synthetic/generate cohort {:end-instant (Instant/parse end)}))

(def ^:private end "2026-10-13T20:00:00Z")
(def ^:private resources (generate end))

(defn- of-type [type] (filter #(= type (:resourceType %)) resources))

(deftest cohort-shape
  (is (= {"Organization" 1 "Practitioner" 2 "Patient" 6 "Condition" 4 "MedicationRequest" 8
          "AllergyIntolerance" 1 "Appointment" 5
          ;; Ana 5 readings/day, Liam 4, Sofía 4, Omar 5, Maya 5, Noah 0; 30 days each
          "Observation" (* 30 (+ 5 4 4 5 5))}
         (synthetic/counts resources))))

(deftest the-cohort-and-the-catalogue-of-measurements-agree
  (testing "every signal of the cohort is a measurement that can be recorded"
    (let [signals (set (for [patient (:patients cohort) signal (:signals patient)] (:signal signal)))]
      (is (seq signals))
      (is (every? #(contains? measurement/measurements %) signals))))
  (testing "what was generated is in the unit the catalogue stores that measurement in, so a
            series of seeded readings and recorded ones is in one unit"
    (let [by-concept (into {} (map (fn [[_ m]] [(:concept m) m])) measurement/measurements)]
      (doseq [o (of-type "Observation")
              :let [m (some (fn [[concept m]] (when (obs/concept? o concept) m)) by-concept)
                    quantities (if (contains? (:channels m) :value)
                                 [(obs/value o)]
                                 [(obs/component-value o :systolic) (obs/component-value o :diastolic)])]]
        (is (some? m) (str (:id o) " is a measurement of the catalogue"))
        (is (every? #(= (:unit m) (:unit %)) quantities) (:id o))))))

(deftest generation-is-deterministic
  (is (= resources (generate end)))
  (is (= resources (synthetic/generate cohort {:end-instant (Instant/parse end) :days 30}))))

(deftest every-resource-is-valid
  (doseq [resource resources]
    (is (nil? (schema/explain resource)) (pr-str (:resourceType resource) (:id resource)))))

(deftest ids-are-unique-per-type
  (let [ids (map (juxt :resourceType :id) resources)]
    (is (= (count ids) (count (set ids))))))

(defn- references
  "Every relative reference string found anywhere in a resource."
  [x]
  (cond
    (map? x) (concat (when (string? (:reference x)) [(:reference x)]) (mapcat references (vals x)))
    (sequential? x) (mapcat references x)
    :else []))

(deftest references-resolve-within-the-cohort
  (let [existing (set (map (fn [r] (str (:resourceType r) "/" (:id r))) resources))]
    (doseq [resource resources
            reference (references resource)]
      (is (contains? existing reference)
          (str (:resourceType resource) "/" (:id resource) " points at missing " reference)))))

(defn- observations-by-id [resources]
  (into {} (comp (filter #(= "Observation" (:resourceType %)))
                 (map (juxt :id identity)))
        resources))

(defspec values-of-a-day-do-not-depend-on-the-end-instant 25
  ;; Moving the end instant within a day only adds or removes the latest readings; the
  ;; readings that both runs contain must be identical.
  (prop/for-all [hour (gen/choose 8 23)]
    (let [a (observations-by-id (generate (format "2026-10-13T%02d:00:00Z" hour)))
          b (observations-by-id resources)]
      (every? (fn [[id observation]] (= observation (get b id)))
              (filter (fn [[id _]] (contains? b id)) a)))))

;; ---------------------------------------------------------------------------
;; The scenarios must trigger the rules whatever hour of the day the cohort is seeded

(def ^:private seeding-hours (range 0 24))

(defn- at-hour [h] (format "2026-10-13T%02d:00:00Z" h))

(defn- observations-of [patient-id resources concept]
  (->> resources
       (filter #(and (= "Observation" (:resourceType %))
                     (= patient-id (obs/patient-id %))
                     (obs/concept? % concept)))
       (sort-by obs/effective-millis)))

(defn- within-last-24h [end-str observations]
  (let [end-ms (.toEpochMilli (Instant/parse end-str))]
    (filter #(< (- end-ms (* 24 3600 1000)) (obs/effective-millis %) (inc end-ms)) observations)))

(deftest ana-blood-pressure-keeps-rising-R1
  (doseq [h seeding-hours
          :let [res (generate (at-hour h))
                last-3 (take-last 3 (observations-of "pt-ana" res :bp-panel))
                high (filter (fn [o] (let [{:keys [systolic diastolic]} (obs/blood-pressure o)]
                                       (or (>= systolic 160) (>= diastolic 100))))
                             last-3)]]
    (is (>= (count high) 2) (str "R1 needs 2 of the last 3 readings high when seeding at " h ":00"))))

(deftest liam-glucose-out-of-range-R3
  (doseq [h seeding-hours
          :let [res (generate (at-hour h))
                recent (within-last-24h (at-hour h) (observations-of "pt-liam" res :glucose))]]
    (is (some (fn [o] (let [v (:value (obs/value o))] (or (> v 250) (< v 70)))) recent)
        (str "R3 needs a glucose out of range in the last 24 h when seeding at " h ":00"))))

(deftest sofia-low-saturation-R2
  (doseq [h seeding-hours
          :let [res (generate (at-hour h))
                recent (within-last-24h (at-hour h) (observations-of "pt-sofia" res :spo2))
                low (filter #(< (:value (obs/value %)) 92) recent)]]
    (is (>= (count low) 2) (str "R2 needs 2 low SpO2 readings in 24 h when seeding at " h ":00"))))

(deftest maya-rapid-weight-gain-R4
  (doseq [h seeding-hours
          :let [res (generate (at-hour h))
                weights (observations-of "pt-maya" res :body-weight)
                latest (last weights)
                three-days-before (- (obs/effective-millis latest) (* 72 3600 1000))
                earlier (first (filter #(= three-days-before (obs/effective-millis %)) weights))]]
    (is (some? earlier))
    (is (>= (- (:value (obs/value latest)) (:value (obs/value earlier))) 2.0)
        (str "R4 needs +2 kg in 3 days when seeding at " h ":00"))))

(deftest omar-never-alerts
  (doseq [h [0 8 14 23]
          o (filter #(= "pt-omar" (obs/patient-id %))
                    (filter #(= "Observation" (:resourceType %)) (generate (at-hour h))))
          :let [classes (if-let [{:keys [systolic diastolic]} (obs/blood-pressure o)]
                          [(ranges/classify :systolic systolic) (ranges/classify :diastolic diastolic)]
                          (when (obs/concept? o :heart-rate)
                            [(ranges/classify :heart-rate (:value (obs/value o)))]))]]
    (is (not-any? #{:low :very-low :high :very-high} classes) (pr-str (:id o) classes))))

(deftest noah-has-no-measurements
  (is (empty? (filter #(= "pt-noah" (obs/patient-id %))
                      (filter #(= "Observation" (:resourceType %)) resources)))))

(deftest appointments-are-relative-to-the-seeding-moment
  (let [appts (into {} (map (juxt :id identity)) (of-type "Appointment"))]
    (is (= "2026-10-14T16:00:00Z" (:start (appts "appt-ana-1"))) "20 h after 2026-10-13T20:00 truncated to the hour")
    (is (= "booked" (:status (appts "appt-ana-1"))))
    (is (= "proposed" (:status (appts "appt-sofia-1"))))
    (is (contains? (appts "appt-sofia-1") :requestedPeriod))
    (is (not (contains? (appts "appt-sofia-1") :start)))))

(deftest the-last-reading-is-never-after-the-end-instant
  (doseq [h [0 7 8 9 19 20 21]
          o (filter #(= "Observation" (:resourceType %)) (generate (at-hour h)))]
    (is (<= (obs/effective-millis o) (.toEpochMilli (Instant/parse (at-hour h)))))))
