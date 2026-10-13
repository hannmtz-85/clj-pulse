(ns pulse.fhir.ranges-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.ranges :as ranges]))

(deftest classification-examples
  (testing "band boundaries: lower bound inclusive, upper exclusive"
    (is (= :normal (ranges/classify :systolic 119.9)))
    (is (= :elevated (ranges/classify :systolic 120)))
    (is (= :very-high (ranges/classify :systolic 160)))
    (is (= :very-high (ranges/classify :diastolic 100)))
    (is (= :low (ranges/classify :spo2 91)))
    (is (= :normal (ranges/classify :spo2 92))))
  (testing "values in another unit are converted first"
    (is (= :normal (ranges/classify :glucose 5.0 "mmol/L")))
    (is (= :very-high (ranges/classify :glucose 15.0 "mmol/L")))
    (is (= :fever (ranges/classify :body-temperature 100.4 "[degF]"))))
  (testing "untracked measurements"
    (is (nil? (ranges/classify :body-weight 70)))))

(deftest bands-are-contiguous
  (doseq [[measurement {:keys [bands]}] ranges/measurements]
    (testing (name measurement)
      (is (nil? (second (first bands))) "first band is unbounded below")
      (is (nil? (nth (last bands) 2)) "last band is unbounded above")
      (is (every? (fn [[[_ _ upper] [_ lower _]]] (= upper lower))
                  (partition 2 1 bands))
          "each band starts where the previous one ends"))))

(defspec every-number-falls-in-exactly-one-band 300
  (prop/for-all [[measurement {:keys [bands]}] (gen/elements (vec ranges/measurements))
                 x (gen/double* {:min -500 :max 500 :NaN? false :infinite? false})]
    (let [matching (filter (fn [[_ lower upper]]
                             (and (or (nil? lower) (>= x lower))
                                  (or (nil? upper) (< x upper))))
                           bands)]
      (and (= 1 (count matching))
           (= (ffirst matching) (ranges/classify measurement x))))))
