(ns pulse.view.alerts-test
  "What the interfaces say of an alert (ADR-0029): the words, the measurement and the tone of the rule that raised it, which an alert
  is told by from its text; the readings behind it; and what is said of an alert whose text is none of the rules'. These run on the JVM
  and on Node alike. That the table is the rules of the scheduler is held by `alerts-rules-test`, which only the JVM can run."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.alerts :as alerts]))

(def ^:private now "2026-10-13T12:00:00Z")

(defn- bp [id at systolic diastolic]
  {:id id :at at :systolic systolic :diastolic diastolic :unit "mm[Hg]" :band "very-high"})

;; ---------------------------------------------------------------------------
;; The rules

(deftest an-alert-is-told-by-the-title-of-its-rule
  (is (= :blood-pressure (alerts/measurement "BP stage 2")))
  (is (= :spo2 (alerts/measurement "Low SpO₂")))
  (is (= :glucose (alerts/measurement "Glucose out of range")))
  (is (= :weight (alerts/measurement "Rapid weight gain")))
  (is (= "Oxygen saturation below 92 % in at least 2 readings of the last 24 hours." (alerts/description "Low SpO₂")))
  (is (= {"BP stage 2" :critical "Low SpO₂" :high "Glucose out of range" :high "Rapid weight gain" :elevated}
         (into {} (map (fn [title] [title (alerts/tone title)])) (keys alerts/rules)))
      "the more urgent the rule, the louder the alert"))

(deftest an-alert-of-no-known-rule-needs-a-look-and-says-no-more
  (doseq [text ["Something else" "" "bp stage 2" " BP stage 2" nil 7 :x ["BP stage 2"] {:text "BP stage 2"}]]
    (testing (pr-str text)
      (is (= :high (alerts/tone text)))
      (is (nil? (alerts/measurement text)))
      (is (nil? (alerts/description text)))
      (is (nil? (alerts/rule-of text))))))

(deftest every-rule-has-what-a-page-needs-of-it
  (doseq [[title {:keys [measurement tone description]}] alerts/rules]
    (testing title
      (is (contains? #{:blood-pressure :heart-rate :spo2 :glucose :weight :temperature} measurement))
      (is (contains? #{:critical :high :elevated} tone))
      (is (and (string? description) (re-find #"\.$" description)) "a sentence"))))

;; ---------------------------------------------------------------------------
;; The readings behind an alert

(deftest the-readings-behind-an-alert-are-the-latest-three-with-the-newest-first
  (let [items [(bp "a" "2026-10-11T10:00:00Z" 150 90)
               (bp "b" "2026-10-12T08:00:00Z" 165 106)
               (bp "c" "2026-10-12T20:00:00Z" 172 102)
               (bp "d" "2026-10-13T08:00:00Z" 177 100)]]
    (is (= ["177/100 mmHg, today, 10:00" "172/102 mmHg, yesterday, 22:00" "165/106 mmHg, yesterday, 10:00"]
           (alerts/evidence :blood-pressure items now)))
    (is (= (alerts/evidence :blood-pressure items now) (alerts/evidence :blood-pressure (reverse items) now))
        "whichever order the api gave them in")))

(deftest fewer-readings-than-that-are-all-there-is
  (is (= ["177/100 mmHg, today, 10:00"] (alerts/evidence :blood-pressure [(bp "d" "2026-10-13T08:00:00Z" 177 100)] now)))
  (is (= [] (alerts/evidence :blood-pressure [] now)))
  (is (= [] (alerts/evidence :blood-pressure nil now))))

(deftest a-reading-that-says-nothing-is-not-a-reading-behind-an-alert
  (let [items [(bp "ok" "2026-10-13T08:00:00Z" 177 100)
               {:id "no-numbers" :at "2026-10-13T09:00:00Z" :unit "mm[Hg]"}
               {:id "no-diastolic" :at "2026-10-13T09:30:00Z" :systolic 170}
               {:id "no-time" :systolic 170 :diastolic 100}
               {:id "bad-time" :at "later" :systolic 170 :diastolic 100}]]
    (is (= ["177/100 mmHg, today, 10:00"] (alerts/evidence :blood-pressure items now)))))

(deftest a-reading-is-told-in-its-own-unit-and-number
  (is (= ["90 %, today, 10:00"] (alerts/evidence :spo2 [{:id "s" :at "2026-10-13T08:00:00Z" :value 90 :unit "%"}] now)))
  (is (= ["75.8 kg, today, 10:00" "74.6 kg, yesterday, 10:00" "73.7 kg, Oct 11, 10:00"]
         (alerts/evidence :weight [{:id "w1" :at "2026-10-11T08:00:00Z" :value 73.7 :unit "kg"}
                                   {:id "w2" :at "2026-10-12T08:00:00Z" :value 74.6 :unit "kg"}
                                   {:id "w3" :at "2026-10-13T08:00:00Z" :value 75.8 :unit "kg"}]
                          now))))

;; ---------------------------------------------------------------------------
;; What to read for the alerts of a patient

(deftest the-measurements-to-read-are-those-of-the-rules-of-the-alerts-once-each-in-the-usual-order
  (let [alert (fn [text] {:id text :text text :status "active" :since "2026-10-12T20:30:00Z"})]
    (is (= [] (alerts/kinds-to-read [])))
    (is (= [:blood-pressure] (alerts/kinds-to-read [(alert "BP stage 2") (alert "BP stage 2")])) "once each")
    (is (= [:blood-pressure :spo2 :glucose :weight]
           (alerts/kinds-to-read (map alert ["Rapid weight gain" "Glucose out of range" "Low SpO₂" "BP stage 2"])))
        "in the order the measurements are shown")
    (is (= [:spo2] (alerts/kinds-to-read [(alert "Something else") (alert "Low SpO₂")])) "an alert of no known rule asks for nothing")
    (is (= [:spo2] (alerts/kinds-to-read [nil "text" 7 (alert "Low SpO₂") {:text nil}])) "what is not an alert is not one")
    (is (= [] (alerts/kinds-to-read nil)))))
