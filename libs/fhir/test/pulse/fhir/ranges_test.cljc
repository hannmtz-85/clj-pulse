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
    (is (nil? (ranges/classify :body-weight 70))))
  (testing "a unit it cannot convert from is not classified, and is not an error"
    (is (nil? (ranges/classify :glucose 5.0 "kg")))
    (is (nil? (ranges/classify :glucose 5.0 "no-such-unit")))
    (is (nil? (ranges/classify :systolic 120 "")))))

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

(deftest every-band-has-a-severity
  (is (every? ranges/severity (for [[_ {:keys [bands]}] ranges/measurements
                                    [label] bands]
                                label))
      "a new band label without a severity would sort as nothing"))

(deftest the-worse-band-wins
  (is (= :very-high (ranges/worst [:normal :very-high :elevated])))
  (is (= :normal (ranges/worst [:normal])))
  (is (= :high (ranges/worst [:elevated :high])))
  (is (= :low (ranges/worst [:low :elevated])) "of equal severity, the first")
  (is (= :elevated (ranges/worst [:elevated :low])))
  (is (nil? (ranges/worst [])))
  (is (nil? (ranges/worst nil))))

(defspec the-worst-band-is-one-of-them-and-nothing-is-worse 200
  (prop/for-all [bands (gen/not-empty (gen/vector (gen/elements (vec (keys ranges/severity)))))]
    (let [w (ranges/worst bands)]
      (and (some #{w} bands)
           (every? #(<= (ranges/severity %) (ranges/severity w)) bands)))))
