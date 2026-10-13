(ns pulse.fhir.units-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.units :as units]))

(defn- approx=
  "Equal within a relative tolerance (with a small absolute floor for values near zero)."
  [a b]
  (<= (#?(:clj Math/abs :cljs js/Math.abs) (- a b))
      (* 1e-9 (max 1.0 (#?(:clj Math/abs :cljs js/Math.abs) a) (#?(:clj Math/abs :cljs js/Math.abs) b)))))

(deftest known-conversions
  (testing "temperature is affine, not just a factor"
    (is (approx= 37.77777777777778 (units/convert 100 "[degF]" "Cel")))
    (is (approx= 212.0 (units/convert 100 "Cel" "[degF]")))
    (is (approx= 273.15 (units/convert 0 "Cel" "K"))))
  (testing "mass"
    (is (approx= 154.3235835294143 (units/convert 70 "kg" "[lb_av]")))
    (is (approx= 1.0 (units/convert 1000 "g" "kg"))))
  (testing "pressure and glucose"
    (is (< (#?(:clj Math/abs :cljs js/Math.abs) (- 15.99868 (units/convert 120 "mm[Hg]" "kPa"))) 1e-5))
    (is (approx= 99.0858 (units/convert 5.5 "mmol/L" "mg/dL")))))

(deftest incompatible-units
  (is (not (units/compatible? "kg" "Cel")))
  (is (not (units/compatible? "kg" "no-such-unit")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (units/convert 1 "kg" "Cel")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (units/convert 1 "nope" "kg"))))

(deftest quantity-shape
  (is (= {:value 120 :unit "mmHg" :system "http://unitsofmeasure.org" :code "mm[Hg]"}
         (units/quantity 120 "mm[Hg]")))
  (is (= "beats/minute" (:unit (units/quantity 70 "/min")))
      "the FHIR unit text is the label, the UCUM code is kept in :code"))

(def ^:private same-dimension-pairs
  (vec (for [[a {da :dim}] units/definitions
             [b {db :dim}] units/definitions
             :when (= da db)]
         [a b])))

(defspec conversion-round-trips 300
  (prop/for-all [[a b] (gen/elements same-dimension-pairs)
                 x (gen/double* {:min -1000 :max 1000 :NaN? false :infinite? false})]
    (approx= x (units/convert (units/convert x a b) b a))))

(defspec converting-to-the-same-unit-is-identity 100
  (prop/for-all [u (gen/elements (keys units/definitions))
                 x (gen/double* {:min -1000 :max 1000 :NaN? false :infinite? false})]
    (approx= x (units/convert x u u))))
