(ns pulse.scheduler.job-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.scheduler.job :as job]))

(defn- a-job
  "A sound job, with whatever `overrides` say."
  [& {:as overrides}]
  (merge {:id :evaluate-rules
          :description "Evaluate the clinical rules"
          :schedule {:type :interval :every-ms 60000}
          :handler (fn [_] :done)}
         overrides))

(defn- problems
  "What `check!` says is wrong with `jobs`, or nil when it accepts them."
  [jobs]
  (try
    (job/check! jobs)
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (= ::job/invalid-jobs (:type (ex-data e))))
      (is (= (str "Invalid jobs:\n  " (str/join "\n  " (:problems (ex-data e))))
             (ex-message e)) "the message lists what the data holds")
      (:problems (ex-data e)))))

(deftest a-sound-catalogue-is-returned-as-a-vector-in-the-order-given
  (let [jobs (list (a-job :id :b)
                   (a-job :id :a :schedule {:type :daily :at "08:00" :zone "Europe/Madrid"}))]
    (is (= jobs (job/check! jobs)))
    (is (vector? (job/check! jobs)))
    (is (= [:b :a] (map :id (job/check! jobs)))))
  (is (= [] (job/check! [])) "no jobs is a catalogue too")
  (testing "a handler may be a var, as it is when it is defined with defn"
    (is (nil? (problems [(a-job :handler #'problems)])))))

(deftest a-job-needs-all-of-its-parts-and-nothing-else
  (is (= ["job :evaluate-rules: {:description [\"missing required key\"]}"]
         (problems [(dissoc (a-job) :description)])))
  (is (= ["job :evaluate-rules: {:description [\"should be at least 1 character\"]}"]
         (problems [(a-job :description "")])))
  (is (= ["job :evaluate-rules: {:handler [\"missing required key\"]}"]
         (problems [(dissoc (a-job) :handler)])))
  (is (= ["job :evaluate-rules: {:handler [\"should be a function\"]}"]
         (problems [(a-job :handler "evaluate")])))
  (is (= ["job :evaluate-rules: {:schedule [\"missing required key\"]}"]
         (problems [(dissoc (a-job) :schedule)])))
  (is (= ["job :evaluate-rules: {:run-on-start [\"disallowed key\"]}"]
         (problems [(a-job :run-on-start true)]))
      "a misspelt key is an error, not a setting that does nothing"))

(deftest the-schedule-is-judged-by-its-own-rules
  (is (= ["job :evaluate-rules: {:schedule {:every-ms [\"should be at least 1\"]}}"]
         (problems [(a-job :schedule {:type :interval :every-ms 0})])))
  (is (= ["job :evaluate-rules: {:schedule {:zone [\"should be the name of a time zone such as \\\"Europe/Madrid\\\" or \\\"UTC\\\"\"]}}"]
         (problems [(a-job :schedule {:type :daily :at "08:00" :zone "Madrid"})]))))

(deftest an-id-can-sit-in-a-url-and-a-log-line
  (doseq [id [:evaluate-rules :r1 :a]]
    (is (nil? (problems [(a-job :id id)])) (pr-str id)))
  (doseq [id [:Evaluate :evaluate_rules :1st :-a :pulse/evaluate :a.b "evaluate" (keyword "")]]
    (is (= [(str "job " (pr-str id) ": {:id [\"should be a keyword such as :evaluate-rules"
                 " (lower case, digits and hyphens, no namespace)\"]}")]
           (problems [(a-job :id id)]))
        (pr-str id)))
  (testing "a job with no id at all is named by its place in the catalogue"
    (is (= ["job #0: {:id [\"should be a keyword such as :evaluate-rules (lower case, digits and hyphens, no namespace)\"]}"]
           (problems [(a-job :id nil)])))))

(deftest two-jobs-cannot-share-an-id
  (is (= ["job :evaluate-rules is defined 2 times"]
         (problems [(a-job) (a-job :description "The same, again")])))
  (is (= ["job :a is defined 3 times"]
         (problems [(a-job :id :a) (a-job :id :a) (a-job :id :b) (a-job :id :a)]))))

(deftest every-problem-is-reported-at-once
  (is (= ["job :a: {:description [\"missing required key\"]}"
          "job :b: {:handler [\"should be a function\"]}"
          "job :a is defined 2 times"]
         (problems [(dissoc (a-job :id :a) :description)
                    (a-job :id :b :handler nil)
                    (a-job :id :a)]))))

(deftest something-that-is-not-a-map-is-named-by-its-position
  (let [lines (problems [(a-job) nil "evaluate"])]
    (is (= 2 (count lines)))
    (is (re-find #"^job #1: " (first lines)))
    (is (re-find #"^job #2: " (second lines)))))
