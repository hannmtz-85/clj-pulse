(ns pulse.view.views-details-test
  "What a mutation analysis found that the tests of the words of a reading did not notice: the name and the icon of each
  measurement, as the owner approved them in the mock-ups."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.views :as views]))

(deftest each-measurement-is-named-as-the-mock-ups-name-it
  (is (= {:blood-pressure "Blood pressure"
          :heart-rate "Heart rate"
          :spo2 "Oxygen saturation"
          :glucose "Glucose"
          :weight "Weight"
          :temperature "Temperature"}
         (update-vals views/kind-info :label))))

(deftest each-measurement-is-drawn-with-its-icon
  (is (= {:blood-pressure :gauge
          :heart-rate :heart-pulse
          :spo2 :wind
          :glucose :droplet
          :weight :scale
          :temperature :thermometer}
         (update-vals views/kind-info :icon))))

(deftest text-is-text-with-something-in-it
  (is (= "a" (views/text-of "a")))
  (is (= " a " (views/text-of " a ")) "as it was written: nothing is trimmed")
  (is (= "García" (views/text-of "García")))
  (doseq [odd [nil "" " " "\n\t" 5 :a [] {} ["a"] true]]
    (is (nil? (views/text-of odd)) (pr-str odd))))

(deftest the-name-of-a-medication-begins-with-a-capital-and-is-otherwise-as-the-clinic-wrote-it
  (is (= "Lisinopril" (views/name-of "lisinopril")))
  (is (= "Penicillin G" (views/name-of "penicillin G")))
  (is (= "Essential (primary) hypertension" (views/name-of "Essential (primary) hypertension")))
  (is (= "Éclair" (views/name-of "éclair")))
  (is (= "A" (views/name-of "a")))
  (is (= "Metformin HCl 500 MG" (views/name-of "metformin HCl 500 MG")) "nothing else is changed")
  (doseq [odd [nil "" " " "\n" 5 :kw [] {}]]
    (is (= "Not named" (views/name-of odd)) (pr-str odd))))

(deftest a-page-offers-the-measurements-the-patient-has-readings-of-and-those-it-could-not-load
  (let [vitals {:blood-pressure {:id "bp"} :heart-rate nil :spo2 nil :glucose nil :weight {:id "w"} :temperature nil}
        summary {:vitals vitals :unavailable []}]
    (is (= [:blood-pressure :weight] (views/offered-kinds summary nil)) "those it has a reading of, in the order they are shown")
    (is (= [:blood-pressure :glucose :weight] (views/offered-kinds summary :glucose)) "and the one asked for, whatever the summary says")
    (is (= [:blood-pressure :weight] (views/offered-kinds summary :weight)) "which is there already, once")
    (is (= [] (views/offered-kinds {:vitals (zipmap views/kinds (repeat nil)) :unavailable []} nil)) "a patient with no readings has no choice")
    (is (= [:temperature] (views/offered-kinds {:vitals (zipmap views/kinds (repeat nil)) :unavailable []} :temperature)))
    (testing "a reading that could not be loaded may be there"
      (let [lost (assoc summary :unavailable ["vitals.glucose" "vitals.spo2"])]
        (is (= [:blood-pressure :spo2 :glucose :weight] (views/offered-kinds lost nil)) "in the order they are shown, not the summary's")
        (is (= [:glucose] (views/offered-kinds {:vitals (zipmap views/kinds (repeat nil))
                                                :unavailable ["vitals.glucose" "vitals.nonsense" "alerts"]}
                                               nil))
            "and only what is a measurement")))
    (testing "what the summary says is unavailable is only taken for a name when it is one"
      (let [none (zipmap views/kinds (repeat nil))]
        (is (= [] (views/offered-kinds {:vitals none :unavailable [5 nil :vitals.glucose ["vitals.glucose"]]} nil)))
        (is (= [] (views/offered-kinds {:vitals none :unavailable "vitals.glucose"} nil)) "nor a text that is not a list of them")
        (is (= [:glucose] (views/offered-kinds {:vitals none :unavailable [5 "vitals.glucose" nil]} nil)))))
    (testing "a summary that could not be had offers them all"
      (is (= views/kinds (views/offered-kinds nil nil)))
      (is (= views/kinds (views/offered-kinds nil :weight))))))
