(ns pulse.view.alerts
  "What the interfaces say of an alert (ADR-0029): which rule raised it, in the words of the rules themselves, and the readings behind
  it. The api tells an alert by its text, the title of the rule that raised it (`BP stage 2`), so the words, the measurement and the
  tone of each rule are kept here by that title, in a table that **a test holds to the rules of the scheduler** (the titles, the
  measurements and the words): a rule that changes is a test that fails, and not a page that says something else than the rule does.

  An alert whose text is none of these (a rule added later, or another system's flag) is still an alert: it has the tone of a thing
  that needs a look, and no words of a rule and no readings, since nothing says what it watches. Pure and `.cljc`, so that the
  workspace in the browser and the mock-ups under Babashka say the same."
  (:require [pulse.view.format :as fmt]
            [pulse.view.views :as views]))

(def rules
  "What each rule watches, by the title of the alert it raises: `:measurement` (what its readings are of), `:tone` (how the alert is
  shown: how far from normal what it found is) and `:description` (the rule, in its own words)."
  {"BP stage 2" {:measurement :blood-pressure
                 :tone :critical
                 :description "Blood pressure at stage 2 (a systolic of 160 or more, or a diastolic of 100 or more) in at least 2 of the last 3 readings of the last 3 days."}
   "Low SpO₂" {:measurement :spo2
               :tone :high
               :description "Oxygen saturation below 92 % in at least 2 readings of the last 24 hours."}
   "Glucose out of range" {:measurement :glucose
                           :tone :high
                           :description "A glucose above 250 or below 70 mg/dL in the last 24 hours."}
   "Rapid weight gain" {:measurement :weight
                        :tone :elevated
                        :description "A latest weight, from the last 24 hours, 2 kg or more above the lowest of the 72 hours before it, for a patient with an active heart failure (ICD-10-CM I50)."}})

(def evidence-count
  "How many of the latest readings of what a rule watches an alert shows as the readings behind it."
  3)

(defn rule-of
  "The entry of `rules` for the `text` of an alert, or nil when the text is none of theirs (or is not text)."
  [text]
  (when (string? text)
    (get rules text)))

(defn measurement
  "What the rule that raised the alert with this `text` watches, such as `:blood-pressure`, or nil when it is none that is known."
  [text]
  (:measurement (rule-of text)))

(defn tone
  "The tone an alert with this `text` is shown in: its rule's, or `:high` for one that is not known, since an alert is a thing that
  needs a look."
  [text]
  (or (:tone (rule-of text)) :high))

(defn description
  "The words of the rule that raised the alert with this `text`, or nil when it is none that is known."
  [text]
  (:description (rule-of text)))

(defn evidence
  "The readings behind an alert, as lines of text with the newest first: the latest `evidence-count` of the `items` (a series of the
  api, any order) of the measurement `kind`, each as `172/104 mmHg, yesterday, 22:30`. A reading that says nothing (no number, no time
  that can be read) is not among them."
  [kind items now]
  (vec (for [reading (take evidence-count (reverse (views/usable-series kind items)))]
         (str (views/reading-text kind reading) " " (fmt/unit (:unit reading)) ", " (fmt/when-phrase (:at reading) now)))))

(defn kinds-to-read
  "The measurements whose readings an alert's page needs, from the `alerts` of a summary: the measurement of each alert that has a
  rule, once each, in the order of `pulse.view.views/kinds`."
  [alerts]
  (let [wanted (set (keep (fn [alert] (when (map? alert) (measurement (:text alert)))) alerts))]
    (filterv wanted views/kinds)))
