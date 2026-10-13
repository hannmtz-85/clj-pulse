(ns pulse.fhir.reference-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.reference :as ref]))

(deftest parse-examples
  (testing "valid relative references"
    (is (= {:type "Patient" :id "pt-ana"} (ref/parse "Patient/pt-ana")))
    (is (= {:type "Observation" :id "1.2-x"} (ref/parse "Observation/1.2-x"))))
  (testing "invalid input yields nil"
    (is (nil? (ref/parse nil)))
    (is (nil? (ref/parse "patient/pt-ana")))
    (is (nil? (ref/parse "Patient/")))
    (is (nil? (ref/parse "Patient/a/b")))
    (is (nil? (ref/parse (str "Patient/" (str/join (repeat 65 "a"))))))))

(def ^:private type-gen
  (gen/fmap (fn [[h t]] (str h t))
            (gen/tuple (gen/fmap (comp str/upper-case str) gen/char-alpha)
                       (gen/fmap str/join (gen/vector gen/char-alpha 1 12)))))

(def ^:private id-gen
  (gen/fmap str/join
            (gen/vector (gen/elements (seq "abcXYZ0189-.")) 1 64)))

(defspec parse-and-->str-round-trip 200
  (prop/for-all [t type-gen
                 id id-gen]
    (let [s (str t "/" id)]
      (and (= {:type t :id id} (ref/parse s))
           (= s (ref/->str (ref/parse s)))))))
