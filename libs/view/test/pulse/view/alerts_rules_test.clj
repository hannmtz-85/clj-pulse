(ns pulse.view.alerts-rules-test
  "The table of the rules that the interfaces tell an alert by is the rules of the scheduler (ADR-0029): their titles, what they
  watch and their words. The scheduler is a JVM-only service, so this is the one test of the alerts that Node cannot run; it is why
  the table can be a copy in `.cljc` and still not drift."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.scheduler.rules :as rules]
            [pulse.view.alerts :as alerts]))

(deftest the-titles-are-those-of-the-rules-of-the-scheduler
  (is (= (set (map :title rules/catalogue)) (set (keys alerts/rules)))))

(deftest what-a-rule-watches-is-said-in-the-words-of-the-rule
  (doseq [{:keys [title doc]} rules/catalogue]
    (is (= (str/replace doc #"\s+" " ") (alerts/description title)) title)))

(deftest the-measurement-of-an-alert-is-that-of-its-rule
  (doseq [{:keys [title measurement]} rules/catalogue]
    (is (= measurement (alerts/measurement title)) title)))
