(ns pulse.fhir.observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.schema :as schema]))

(def ^:private heart-rate
  (obs/quantity-observation {:id "obs-1" :patient "pt-ana" :concept :heart-rate
                             :value 72 :unit "/min" :at "2026-10-13T08:00:00Z"}))

(def ^:private bp
  (obs/blood-pressure-observation {:id "obs-2" :patient "pt-ana" :systolic 128 :diastolic 82
                                   :at "2026-10-13T08:00:00Z"}))

(deftest builders-produce-valid-resources
  (is (schema/valid? heart-rate))
  (is (schema/valid? bp))
  (is (= "vital-signs" (-> heart-rate :category first :coding first :code)))
  (is (= "Patient/pt-ana" (-> bp :subject :reference)))
  (is (= [{:reference "Patient/pt-ana"}] (:performer bp))
      "home measurements are performed by the patient"))

(deftest who-took-the-reading
  (testing "a practitioner entering a reading is named instead of the patient"
    (let [by-doctor {:id "obs-3" :patient "pt-ana" :at "2026-10-13T08:00:00Z" :performer "Practitioner/pr-rivera"}
          hr (obs/quantity-observation (assoc by-doctor :concept :heart-rate :value 72 :unit "/min"))
          bp (obs/blood-pressure-observation (assoc by-doctor :systolic 128 :diastolic 82))]
      (is (= [{:reference "Practitioner/pr-rivera"}] (:performer hr)))
      (is (= [{:reference "Practitioner/pr-rivera"}] (:performer bp)))
      (is (= "Patient/pt-ana" (-> hr :subject :reference)) "the subject is still the patient")
      (is (schema/valid? hr))
      (is (schema/valid? bp))))
  (testing "nobody, when the performer is explicitly nil"
    (let [anonymous (obs/quantity-observation {:id "obs-4" :patient "pt-ana" :concept :heart-rate :value 72
                                               :unit "/min" :at "2026-10-13T08:00:00Z" :performer nil})]
      (is (not (contains? anonymous :performer)))
      (is (schema/valid? anonymous)))))

(deftest value-x-is-polymorphic
  (testing "quantity"
    (is (= {:kind :quantity :value 72 :unit "/min"} (obs/value heart-rate))))
  (testing "other value[x] types go through the same multimethod"
    (is (= {:kind :string :value "tired"} (obs/value {:valueString "tired"})))
    (is (= {:kind :boolean :value true} (obs/value {:valueBoolean true})))
    (is (= {:kind :integer :value 3} (obs/value {:valueInteger 3})))
    (is (= {:kind :codeable-concept :value "Yes"}
           (obs/value {:valueCodeableConcept {:text "Yes"}})))
    (is (= {:kind :other :value "x"} (obs/value {:valueRatio "x"}))))
  (testing "no value"
    (is (nil? (obs/value {:resourceType "Observation"})))
    (is (nil? (obs/value bp)) "a panel has components, not a value")))

(deftest components-and-blood-pressure
  (is (= {:systolic 128 :diastolic 82} (obs/blood-pressure bp)))
  (is (= {:kind :quantity :value 128 :unit "mm[Hg]"} (obs/component-value bp :systolic)))
  (is (nil? (obs/blood-pressure heart-rate)))
  (is (nil? (obs/component-value heart-rate :systolic))))

(deftest identity-of-an-observation
  (is (obs/concept? heart-rate :heart-rate))
  (is (not (obs/concept? heart-rate :systolic)))
  (is (obs/concept? bp :bp-panel))
  (is (= "pt-ana" (obs/patient-id heart-rate)))
  (is (= 1791878400000 (obs/effective-millis heart-rate))))
