(ns pulse.web.views-details-test
  "What a mutation analysis found that the tests of the words of a reading did not notice: the name and the icon of each
  measurement, as the owner approved them in the mock-ups."
  (:require [clojure.test :refer [deftest is]]
            [pulse.web.views :as views]))

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
