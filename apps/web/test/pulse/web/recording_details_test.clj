(ns pulse.web.recording-details-test
  "What a mutation analysis of `pulse.web.recording` found that the first tests did not hold: a year that Java takes and the api does not, and
  the end of a sentence."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.web.recording :as recording]))

(deftest a-year-that-java-takes-and-the-api-does-not-is-no-time
  ;; the api's schema of an instant has years of four digits, which is what FHIR has; ISO takes a signed year of any length
  (doseq [text ["+12345-01-01T10:00" "-0001-01-01T10:00" "+10000-01-01T00:00:00" "-2026-10-13T14:00"]]
    (is (= ::recording/invalid (recording/taken-at text)) text)))

(deftest a-message-is-ended-by-a-stop-a-question-mark-or-an-exclamation-mark-and-by-nothing-else
  (testing "the marks that end a sentence are left as they are"
    (doseq [mark ["." "!" "?"]]
      (is (= {:value (str "Ends in a mark" mark)} (recording/refusal :heart-rate {:value [(str "ends in a mark" mark)]})) mark)))
  (testing "any other gets a stop after it"
    (doseq [mark ["," ";" ":" ")" "-"]]
      (is (= {:value (str "Ends in a mark" mark ".")} (recording/refusal :heart-rate {:value [(str "ends in a mark" mark)]})) mark))))
