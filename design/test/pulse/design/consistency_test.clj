(ns pulse.design.consistency-test
  "What `libs/ui` knows about readings, which does not depend on `libs/fhir`, agrees with `libs/fhir`."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.ranges :as ranges]
            [pulse.ui.tone :as tone]))

(deftest the-bands-of-the-interface-are-the-bands-of-the-ranges
  (is (= (set (keys ranges/severity)) (set (keys tone/bands))))
  (is (= (set (for [[_ {:keys [bands]}] ranges/measurements
                    [label] bands]
                label))
         (set (keys tone/bands)))
      "every band a measurement can have, and no other"))

(deftest the-tone-of-a-band-follows-its-severity
  (doseq [[band severity] ranges/severity]
    (is (= ({0 :normal 1 :elevated 2 :high 3 :critical} severity) (:tone (tone/band band)))
        (str band " has severity " severity))))

(deftest a-worse-band-never-has-a-milder-tone
  (let [order {:normal 0 :elevated 1 :high 2 :critical 3}]
    (is (every? (fn [[a b]] (<= (order (:tone (tone/band a))) (order (:tone (tone/band b)))))
                (for [a (keys ranges/severity)
                      b (keys ranges/severity)
                      :when (<= (ranges/severity a) (ranges/severity b))]
                  [a b])))))

(deftest the-three-degrees-away-from-normal-have-three-different-icons
  (let [icon-of (fn [severity] (set (for [[band s] ranges/severity :when (= s severity)] (:icon (tone/band band)))))]
    (testing "so a tone is told by shape and not only by colour"
      (is (= #{:circle-check} (icon-of 0)))
      (is (= #{:triangle-alert} (icon-of 2)))
      (is (= #{:octagon-alert} (icon-of 3)))
      (is (= #{:trending-up :trending-down} (icon-of 1)) "the direction, for the mildest"))))
