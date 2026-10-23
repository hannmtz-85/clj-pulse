(ns pulse.design.data-details-test
  "What a mutation analysis found that the tests of what the api answers a patient did not notice: the doctors by their reference,
  whose appointments a summary has, which alerts come first, and when a series says it was cut."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]))

(deftest the-doctors-are-looked-up-by-the-reference-the-api-gives
  (is (= {"Practitioner/pr-rivera" "Dr. Elena Rivera" "Practitioner/pr-okafor" "Dr. Chidi Okafor"}
         (data/practitioner-names))))

(deftest the-appointments-written-by-hand-are-in-the-summary-of-their-patient-and-in-no-other
  (let [by-the-hand (set (map :id data/other-appointments))
        ids-of (fn [id] (map :id (:appointments (data/summary id))))]
    (is (= ["appt-ana-0" "appt-ana-1" "appt-ana-2"] (ids-of "pt-ana")))
    (doseq [id ["pt-liam" "pt-omar" "pt-noah" "pt-maya" "pt-sofia"]]
      (is (empty? (filter by-the-hand (ids-of id))) id))))

(deftest the-alerts-of-a-summary-are-the-active-ones-of-the-patient-newest-first
  (let [older {:id "flag-pt-ana-R2" :patient "Patient/pt-ana" :text "Low SpO₂" :status "active" :since "2026-10-10T08:00:00Z" :until nil}
        newer {:id "flag-pt-ana-R3" :patient "Patient/pt-ana" :text "Glucose out of range" :status "active" :since "2026-10-13T09:00:00Z" :until nil}
        acknowledged {:id "flag-pt-ana-R4" :patient "Patient/pt-ana" :text "Rapid weight gain" :status "inactive" :since "2026-10-13T10:00:00Z" :until "2026-10-13T11:00:00Z"}]
    (with-redefs [data/alerts (into data/alerts [older newer acknowledged])]
      (is (= ["flag-pt-ana-R3" "flag-pt-ana-R1" "flag-pt-ana-R2"] (map :id (:alerts (data/summary "pt-ana"))))
          "the newest first, and not the one that was acknowledged"))))

(deftest a-series-says-it-was-cut-only-when-there-were-more-readings-than-the-limit
  (let [readings (count (:items (data/series "pt-ana" :weight 500)))]
    (is (= 30 readings))
    (testing "exactly as many as the limit is not cut"
      (is (= {:count 30 :truncated false} (let [{:keys [items truncated]} (data/series "pt-ana" :weight 30)] {:count (count items) :truncated truncated}))))
    (testing "one more than the limit is"
      (is (= {:count 29 :truncated true} (let [{:keys [items truncated]} (data/series "pt-ana" :weight 29)] {:count (count items) :truncated truncated}))))))
