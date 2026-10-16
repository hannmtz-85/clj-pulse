(ns pulse.ui.tone-details-test
  "What a mutation analysis found the tests of the tones did not notice: the exact words."
  (:require [clojure.test :refer [deftest is]]
            [pulse.ui.tone :as tone]))

(deftest the-words-of-the-tones-and-of-the-bands-are-these
  (is (= {:normal "Normal" :elevated "Elevated" :high "High" :critical "Critical" :info "Information"}
         (update-vals tone/tones :label)))
  (is (= {:normal "Normal" :low "Low" :elevated "Elevated" :very-low "Very low" :high "High" :fever "Fever"
          :very-high "Very high" :high-fever "High fever"}
         (update-vals tone/bands :label))))

(deftest the-tone-and-the-icon-of-each-band
  (is (= {:normal [:normal :circle-check] :low [:elevated :trending-down] :elevated [:elevated :trending-up]
          :very-low [:high :triangle-alert] :high [:high :triangle-alert] :fever [:high :triangle-alert]
          :very-high [:critical :octagon-alert] :high-fever [:critical :octagon-alert]}
         (update-vals tone/bands (juxt :tone :icon)))))
