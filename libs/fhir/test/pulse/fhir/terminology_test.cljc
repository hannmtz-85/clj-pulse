(ns pulse.fhir.terminology-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.coding :as coding]
            [pulse.fhir.terminology :as t]))

(def ^:private code-format
  {:loinc #"\d{1,5}-\d"
   :icd-10-cm #"[A-Z]\d{2}(\.[0-9A-Z]{1,4})?"
   :rxnorm #"\d+"})

(deftest catalogue-is-well-formed
  (doseq [[k {:keys [system code display]}] t/concepts]
    (testing (str "concept " k)
      (is (contains? t/systems system) "system is a known short name")
      (is (re-matches (code-format system) code) "code has the format of its system")
      (is (seq display) "has a display text"))))

(deftest coding-and-codeable-concept
  (is (= {:system "http://loinc.org" :code "8480-6" :display "Systolic blood pressure"}
         (t/coding :systolic)))
  (is (= {:coding [(t/coding :heart-rate)] :text "Heart rate"}
         (t/codeable-concept :heart-rate)))
  (is (= "Resting heart rate" (:text (t/codeable-concept :heart-rate "Resting heart rate"))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (t/coding :not-a-concept))
      "a typo in a concept key fails loudly"))

(deftest search-token
  (is (= "http://loinc.org|8867-4" (t/token :heart-rate)))
  (is (= "http://hl7.org/fhir/sid/icd-10-cm|I10" (t/token :hypertension)))
  (doseq [k (keys t/concepts)
          :let [token (t/token k)
                {:keys [system code]} (t/coding k)]]
    (is (= (str system "|" code) token) (str k)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (t/token :not-a-concept))))

(deftest codeable-concept-helpers
  (let [cc (t/codeable-concept :systolic)]
    (is (coding/has-code? cc :loinc "8480-6"))
    (is (coding/has-code? cc "http://loinc.org" "8480-6") "systems can be given as URIs")
    (is (not (coding/has-code? cc :loinc "8462-4")))
    (is (not (coding/has-code? cc :icd-10-cm "8480-6")))
    (is (= "8480-6" (coding/code-in cc :loinc)))
    (is (nil? (coding/code-in cc :rxnorm)))
    (is (= "Systolic blood pressure" (coding/display cc)))
    (is (= "free text" (coding/display {:text "free text" :coding [(t/coding :systolic)]})))))
