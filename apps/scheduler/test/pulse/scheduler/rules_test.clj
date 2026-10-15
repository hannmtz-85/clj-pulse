(ns pulse.scheduler.rules-test
  "The rules of Annex C: the macro that defines them, the examples that the plan gives, and the
  properties that must hold whatever the readings."
  (:require [clojure.test :refer [are deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.scheduler.rules :as rules :refer [defrule evaluate]])
  (:import (java.time Duration Instant)
           (java.time.temporal ChronoUnit)))

(def ^:private ^Instant now (Instant/parse "2026-10-13T08:00:00Z"))

(defn- hours-ago [h] (.minus now (Duration/ofMinutes (long (* 60 h)))))

(defn- bp [hours-before systolic diastolic]
  {:at (hours-ago hours-before) :systolic systolic :diastolic diastolic})

(defn- reading [hours-before value]
  {:at (hours-ago hours-before) :value value})

(defn- fires? [rule readings]
  (some? (evaluate rule readings now)))

;; ---------------------------------------------------------------------------
;; The macro

(defn- expand
  "The expansion of `form`, a use of `defrule`, as it is read in this namespace."
  [form]
  (binding [*ns* (the-ns 'pulse.scheduler.rules-test)]
    (macroexpand-1 form)))

(def ^:private expansion
  (expand '(defrule a-rule "Documented."
                    {:id "R9" :title "A rule" :type :window-count :measurement :spo2
                     :look-back-hours 24 :at-least 2
                     :when (< value 92)})))

(deftest defrule-defines-a-var-marked-as-a-rule
  (is (= 'def (first expansion)))
  (is (= 'a-rule (second expansion)))
  (is (true? (:rule (meta (second expansion)))))
  (is (= "Documented." (nth expansion 2)) "the documentation is the var's")
  (is (= 'pulse.scheduler.rules/rule* (first (nth expansion 3)))))

(deftest defrule-keeps-the-condition-as-code-and-as-data
  (let [[_ spec test-fn condition] (nth expansion 3)]
    (testing "the data of the rule, without the condition and with the documentation"
      (is (= {:id "R9" :title "A rule" :type :window-count :measurement :spo2
              :look-back-hours 24 :at-least 2 :doc "Documented."}
             spec)))
    (testing "a function of the numbers of a reading, over the condition as written"
      (is (= '(clojure.core/fn [{:keys [value systolic diastolic]}] (< value 92)) test-fn)))
    (testing "and the same condition, quoted, to show"
      (is (= '(quote (< value 92)) condition)))))

(deftest a-rise-is-written-with-its-own-names
  (let [[_ _ test-fn _] (nth (expand '(defrule gain "Gain."
                                               {:id "R9" :title "Gain" :type :rise :measurement :weight
                                                :look-back-hours 96 :fresh-hours 24 :window-hours 72
                                                :when (>= (- value baseline) 2.0)}))
                             3)]
    (is (= '(clojure.core/fn [{:keys [value baseline]}] (>= (- value baseline) 2.0)) test-fn))))

(defn- refusal
  "What expanding `form` throws, or nil if it expands."
  [form]
  (try (expand form)
       nil
       (catch Exception e (or (ex-cause e) e))))

(deftest defrule-refuses-what-is-not-a-rule-when-it-is-expanded
  (testing "an unknown type"
    (let [e (refusal '(defrule a-rule "d" {:id "R9" :type :nope :when true}))]
      (is (= ::rules/invalid-rule (:type (ex-data e))))
      (is (re-find #"not a type of rule" (ex-message e)))))
  (testing "no condition"
    (let [e (refusal '(defrule a-rule "d" {:id "R9" :type :window-count}))]
      (is (= ::rules/invalid-rule (:type (ex-data e))))
      (is (re-find #"needs its condition under :when" (ex-message e))))))

(defn- loads
  "What evaluating a `defrule` form does: the rule, or the ex-info it throws."
  [form]
  (try (let [v (binding [*ns* (the-ns 'pulse.scheduler.rules-test)] (eval form))]
         (deref v))
       (catch Exception e (or (ex-cause e) e))))

(deftest a-rule-that-is-wrong-stops-the-load-and-says-what-is-wrong
  (let [base {:id "R9" :title "A rule" :type :window-count :measurement :spo2 :look-back-hours 24 :at-least 2
              :when '(< value 92)}
        problem (fn [changes] (let [e (loads `(defrule ~'a-bad-rule "d" ~(merge base changes)))]
                                (when (instance? Exception e)
                                  (is (= ::rules/invalid-rule (:type (ex-data e))))
                                  (pr-str (:problems (ex-data e))))))]
    (testing "a good one loads"
      (is (map? (loads `(defrule ~'a-good-rule "d" ~base)))))
    (testing "an id that is not R and a number"
      (is (re-find #"R and a number" (problem {:id "rule-9"}))))
    (testing "a measurement that does not exist"
      (is (re-find #":measurement" (problem {:measurement :lung-capacity}))))
    (testing "a look-back of nothing"
      (is (re-find #":look-back-hours" (problem {:look-back-hours 0}))))
    (testing "a misspelt key"
      (is (re-find #":at-leas" (problem {:at-leas 2}))))
    (testing "no title"
      (is (re-find #":title" (problem {:title ""}))))
    (testing "more readings asked for than looked at"
      (is (re-find #"no more readings"
                   (problem {:type :recent-count :of-last 3 :at-least 4 :look-back-hours 72}))))
    (testing "as many as are looked at is all of them, which is fine"
      (is (nil? (problem {:type :recent-count :of-last 3 :at-least 3 :look-back-hours 72}))))
    (testing "a rise that looks back less than it needs"
      (is (re-find #"look back far enough"
                   (problem {:type :rise :measurement :weight :fresh-hours 24 :window-hours 72 :look-back-hours 90
                             :when '(>= (- value baseline) 2.0)}))))))

;; ---------------------------------------------------------------------------
;; The catalogue

(deftest the-catalogue-is-annex-c
  (is (= ["R1" "R2" "R3" "R4"] (map :id rules/catalogue)))
  (is (= ["BP stage 2" "Low SpO₂" "Glucose out of range" "Rapid weight gain"] (map :title rules/catalogue))
      "the titles of the alerts, as the plan names them")
  (is (= #{:blood-pressure :spo2 :glucose :weight} (set (map :measurement rules/catalogue)))))

(deftest every-rule-is-in-the-catalogue
  (let [defined (->> (ns-publics 'pulse.scheduler.rules)
                     vals
                     (filter (comp :rule meta))
                     (map deref))]
    (is (= 4 (count defined)))
    (is (= (set defined) (set rules/catalogue)) "a rule that is defined and not listed would never run")))

(deftest the-conditions-are-those-of-annex-c-as-data
  (is (= '(or (>= systolic 160) (>= diastolic 100)) (:condition rules/bp-stage-2)))
  (is (= '(< value 92) (:condition rules/low-spo2)))
  (is (= '(or (> value 250) (< value 70)) (:condition rules/glucose-out-of-range)))
  (is (= '(>= (- value baseline) 2.0) (:condition rules/rapid-weight-gain))))

(deftest a-rule-has-the-documentation-of-its-var
  (doseq [[_ v] (ns-publics 'pulse.scheduler.rules)
          :when (:rule (meta v))]
    (is (string? (:doc @v)) (str v))
    (is (= (:doc @v) (:doc (meta v))) (str v))))

(deftest only-the-rise-needs-a-condition-of-the-patient
  (is (= {:system :icd-10-cm :prefix "I50"} (:requires-condition rules/rapid-weight-gain)))
  (is (every? nil? (map :requires-condition [rules/bp-stage-2 rules/low-spo2 rules/glucose-out-of-range]))))

(deftest the-rules-have-the-numbers-annex-c-gives
  ;; written out again here, so that a change to a number of a rule is a change to this test too
  (are [rule numbers] (= numbers (select-keys rule (keys numbers)))
    rules/bp-stage-2 {:look-back-hours 72 :of-last 3 :at-least 2}
    rules/low-spo2 {:look-back-hours 24 :at-least 2}
    rules/glucose-out-of-range {:look-back-hours 24 :at-least 1}
    rules/rapid-weight-gain {:look-back-hours 96 :fresh-hours 24 :window-hours 72}))

(deftest a-query-asks-for-the-measurement-from-the-start-of-the-look-back
  (let [at (Instant/parse "2026-10-13T08:00:00.600Z")]
    (are [rule measurement from] (= {:measurement measurement :from (Instant/parse from)} (rules/query rule at))
      rules/bp-stage-2 :blood-pressure "2026-10-10T08:00:00Z"
      rules/low-spo2 :spo2 "2026-10-12T08:00:00Z"
      rules/glucose-out-of-range :glucose "2026-10-12T08:00:00Z"
      rules/rapid-weight-gain :weight "2026-10-09T08:00:00Z")))

(deftest a-query-starts-in-whole-seconds-and-never-after-the-window
  ;; a server compares dates to a precision it chooses, so the query may reach a second before the
  ;; window, and `evaluate` is the one that is exact about it
  (let [at (Instant/parse "2026-10-13T08:00:00.999Z")
        from (:from (rules/query rules/low-spo2 at))]
    (is (= (Instant/parse "2026-10-12T08:00:00Z") from))
    (is (not (.isAfter ^Instant from (.minus at (Duration/ofHours 24)))))))

;; ---------------------------------------------------------------------------
;; R1: at least 2 of the last 3 readings at stage 2

(deftest r1-fires-when-two-of-the-last-three-readings-are-high
  (testing "the systolic or the diastolic"
    (is (fires? rules/bp-stage-2 [(bp 30 150 90) (bp 20 165 90) (bp 10 150 102)]) "2 of 3: a systolic and a diastolic")
    (is (fires? rules/bp-stage-2 [(bp 30 160 90) (bp 20 160 90) (bp 10 120 80)]) "exactly 160 counts")
    (is (fires? rules/bp-stage-2 [(bp 30 130 100) (bp 20 130 100) (bp 10 120 80)]) "exactly 100 counts"))
  (testing "one is not enough"
    (is (not (fires? rules/bp-stage-2 [(bp 30 150 90) (bp 20 165 90) (bp 10 120 80)])))
    (is (not (fires? rules/bp-stage-2 [(bp 10 180 110)]))))
  (testing "just below the thresholds"
    (is (not (fires? rules/bp-stage-2 [(bp 30 159 99) (bp 20 159 99) (bp 10 159 99)]))))
  (testing "two readings, both high, are two of the last three"
    (is (fires? rules/bp-stage-2 [(bp 20 170 90) (bp 10 170 90)]))))

(deftest r1-looks-at-the-last-three-only
  (is (not (fires? rules/bp-stage-2 [(bp 40 180 110) (bp 30 180 110) (bp 20 120 80) (bp 10 120 80) (bp 5 120 80)]))
      "two high readings that are not among the last three")
  (is (not (fires? rules/bp-stage-2 [(bp 60 170 100) (bp 40 120 80) (bp 20 120 80) (bp 5 170 100)]))
      "a high one, two normal ones and a high one: the first is the fourth from the last")
  (is (fires? rules/bp-stage-2 [(bp 60 170 100) (bp 20 120 80) (bp 5 170 100)])
      "and with only one normal one between them they are two of the last three")
  (is (fires? rules/bp-stage-2 [(bp 40 120 80) (bp 30 120 80) (bp 20 170 100) (bp 10 170 100) (bp 5 120 80)])
      "two of the last three, with others before"))

(deftest r1-looks-back-three-days
  (is (not (fires? rules/bp-stage-2 [(bp 73 180 110) (bp 72.5 180 110) (bp 100 180 110)])) "all older than 72 hours")
  (is (not (fires? rules/bp-stage-2 [(bp 72.5 180 110) (bp 72.2 180 110)])) "a little older than 72 hours")
  (is (not (fires? rules/bp-stage-2 [(bp 72 180 110) (bp 71 180 110)])) "72 hours ago exactly is out, and one is not enough")
  (is (fires? rules/bp-stage-2 [(bp 71 180 110) (bp 70 180 110)])))

(def ^:private count-rules
  "The rules that count readings in a window, as Annex C describes them: the hours of the window,
  the readings it takes, and a reading that satisfies the condition of each, as a function of how
  many hours ago it was taken."
  [{:rule rules/bp-stage-2 :hours 72 :needed 2 :hit (fn [h] (bp h 170 105))}
   {:rule rules/low-spo2 :hours 24 :needed 2 :hit (fn [h] (reading h 85))}
   {:rule rules/glucose-out-of-range :hours 24 :needed 1 :hit (fn [h] (reading h 300))}])

(deftest a-count-rule-takes-the-readings-of-its-window-and-no-others
  (doseq [{:keys [rule hours needed hit]} count-rules
          :let [inside (mapv #(hit (+ 1 %)) (range (dec needed)))]]
    (testing (:title rule)
      (is (not (fires? rule inside)) "one reading short")
      (is (fires? rule (conj inside (hit (- hours 0.1)))) "the last one just inside the window")
      (is (not (fires? rule (conj inside (hit hours)))) "the last one at the very edge of it: out")
      (is (not (fires? rule (conj inside (hit (+ hours 0.1))))) "the last one just outside"))))

(deftest r1-reports-the-readings-that-count
  (let [result (evaluate rules/bp-stage-2 [(bp 30 150 90) (bp 20 165 90) (bp 10 150 102)] now)]
    (is (= [(bp 20 165 90) (bp 10 150 102)] (:readings result)) "the high ones, oldest first")
    (is (= (hours-ago 10) (:newest-at result)))))

;; ---------------------------------------------------------------------------
;; R2 and R3: readings in a window of 24 hours

(deftest r2-fires-for-two-low-saturations-in-a-day
  (is (fires? rules/low-spo2 [(reading 20 91) (reading 4 88)]))
  (is (not (fires? rules/low-spo2 [(reading 20 91) (reading 4 95)])) "one low reading")
  (is (not (fires? rules/low-spo2 [(reading 20 92) (reading 4 92)])) "92 is not below 92")
  (is (not (fires? rules/low-spo2 [(reading 26 85) (reading 4 88)])) "one of them is older than 24 hours")
  (is (fires? rules/low-spo2 [(reading 23.9 85) (reading 0 88)]) "a reading at this very moment counts")
  (is (not (fires? rules/low-spo2 [(reading 24 85) (reading 4 88)])) "24 hours ago exactly is out: the window is open at that end")
  (is (fires? rules/low-spo2 [(reading 20 91) (reading 12 90) (reading 4 89)]) "more than two")
  (is (= [(reading 12 90) (reading 4 89)]
         (:readings (evaluate rules/low-spo2 [(reading 20 95) (reading 12 90) (reading 4 89)] now)))))

(deftest r3-fires-for-one-reading-out-of-range-in-a-day
  (is (fires? rules/glucose-out-of-range [(reading 5 251)]))
  (is (fires? rules/glucose-out-of-range [(reading 5 69)]))
  (is (not (fires? rules/glucose-out-of-range [(reading 5 250)])) "250 is the limit, not over it")
  (is (not (fires? rules/glucose-out-of-range [(reading 5 70)])) "and so is 70")
  (is (not (fires? rules/glucose-out-of-range [(reading 5 100) (reading 4 180)])))
  (is (not (fires? rules/glucose-out-of-range [(reading 25 400)])) "yesterday's")
  (is (= (hours-ago 2) (:newest-at (evaluate rules/glucose-out-of-range [(reading 20 300) (reading 2 40)] now)))))

;; ---------------------------------------------------------------------------
;; R4: a rise in the weight

(defn- weight-series
  "Weights as `[hours-before value]`, as readings."
  [& pairs]
  (mapv (fn [[h v]] (reading h v)) pairs))

(deftest r4-fires-for-two-kilos-over-the-lowest-of-three-days
  (is (fires? rules/rapid-weight-gain (weight-series [72 80.0] [48 80.8] [24 81.5] [2 82.0])) "a rise of exactly 2.0")
  (is (not (fires? rules/rapid-weight-gain (weight-series [72 80.0] [48 80.8] [24 81.5] [2 81.9]))) "1.9")
  (testing "from the lowest, not from the oldest"
    (is (fires? rules/rapid-weight-gain (weight-series [70 83.0] [48 80.0] [24 81.0] [2 82.1]))))
  (testing "a drop is not a rise"
    (is (not (fires? rules/rapid-weight-gain (weight-series [70 85.0] [2 80.0])))))
  (testing "one reading has nothing to be compared with"
    (is (not (fires? rules/rapid-weight-gain (weight-series [2 90.0]))))))

(deftest r4-measures-three-days-before-the-latest-reading-not-before-now
  ;; the latest is 10 hours old; its three days reach back to 82 hours ago
  (is (fires? rules/rapid-weight-gain (weight-series [82 78.0] [10 80.0])) "the first reading is 72 hours before the latest")
  (is (not (fires? rules/rapid-weight-gain (weight-series [82.1 78.0] [10 80.0]))) "and a little earlier it is outside")
  (is (not (fires? rules/rapid-weight-gain (weight-series [95 70.0] [30 78.0] [10 79.0]))) "the lowest of the three days is 78.0, not 70.0"))

(deftest r4-wants-a-latest-reading-from-the-last-day
  (is (fires? rules/rapid-weight-gain (weight-series [90 80.0] [23.9 82.0])))
  (is (not (fires? rules/rapid-weight-gain (weight-series [90 80.0] [24 82.0]))) "24 hours ago: not fresh")
  (is (not (fires? rules/rapid-weight-gain (weight-series [99 80.0] [97 82.0]))) "and nothing older than the look-back counts"))

(deftest r4-reports-the-reading-it-rose-from-and-the-latest
  (let [result (evaluate rules/rapid-weight-gain (weight-series [70 83.0] [48 80.0] [24 81.0] [2 82.1]) now)]
    (is (= [(reading 48 80.0) (reading 2 82.1)] (:readings result)))
    (is (= (hours-ago 2) (:newest-at result)))))

(deftest r4-is-exact-on-the-threshold-with-decimals
  ;; with doubles, 64.1 - 62.1 is 1.999999999999993, and a gain of exactly 2 kg was missed on some
  ;; weights (62.1 is one of the ones this range tries)
  (doseq [low (range 600 1200 7)
          :let [high (+ low 20)
                series (weight-series [48 (/ low 10.0)] [2 (/ high 10.0)])]]
    (is (fires? rules/rapid-weight-gain series) (pr-str series))
    (is (not (fires? rules/rapid-weight-gain (weight-series [48 (/ low 10.0)] [2 (/ (dec high) 10.0)])))
        (pr-str series))))

;; ---------------------------------------------------------------------------
;; Readings of the same instant

(defn- permutations [xs]
  (if (<= (count xs) 1)
    [(vec xs)]
    (for [i (range (count xs))
          p (permutations (concat (take i xs) (drop (inc i) xs)))]
      (into [(nth xs i)] p))))

(deftest readings-of-the-same-instant-are-told-apart-by-their-numbers
  (testing "the evidence is in the same order whatever the order they came in"
    (let [a (reading 3 90) b (reading 3 88)]
      (is (= [b a] (:readings (evaluate rules/low-spo2 [a b] now))))
      (is (= [b a] (:readings (evaluate rules/low-spo2 [b a] now))))))
  (testing "and so is which are the last three: four of one instant, two of them high"
    (let [readings [(bp 5 170 90) (bp 5 165 92) (bp 5 120 70) (bp 5 125 72)]
          results (set (map #(evaluate rules/bp-stage-2 % now) (permutations readings)))]
      (is (= 24 (count (permutations readings))))
      (is (= 1 (count results)) "the same answer for each of the 24 orders")
      (is (= [(bp 5 165 92) (bp 5 170 90)] (:readings (first results))) "the two highest are among the last three")))
  (testing "and which is the latest weight: the higher, so that a rise is not missed for the order"
    (let [series [(reading 72 80.0) (reading 2 82.5) (reading 2 81.0)]
          results (set (map #(evaluate rules/rapid-weight-gain % now) (permutations series)))]
      (is (= 1 (count results)))
      (is (= [(reading 72 80.0) (reading 2 82.5)] (:readings (first results))))))
  (testing "a pressure goes by its diastolic when the systolic is the same"
    (let [a (bp 3 160 100) b (bp 3 160 95)]
      (is (= [b a] (:readings (evaluate rules/bp-stage-2 [a b] now))))
      (is (= [b a] (:readings (evaluate rules/bp-stage-2 [b a] now))))))
  (testing "a reading of the same instant as the latest is not one from before it"
    (let [series [(reading 72 81.0) (reading 2 82.5) (reading 2 79.0)]]
      (is (not (fires? rules/rapid-weight-gain series)) "the rise is 1.5 from 81.0; the 79.0 is of the same moment")
      (is (not (fires? rules/rapid-weight-gain (reverse series)))))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private gen-minutes-before (gen/choose -600 (* 6 24 60)))

(def ^:private gen-bp
  (gen/fmap (fn [[m s d]] {:at (.minus now (Duration/ofMinutes m)) :systolic s :diastolic d})
            (gen/tuple gen-minutes-before (gen/choose 90 200) (gen/choose 50 120))))

(defn- gen-quantity [lo hi]
  (gen/fmap (fn [[m v]] {:at (.minus now (Duration/ofMinutes m)) :value v})
            (gen/tuple gen-minutes-before (gen/choose lo hi))))

(def ^:private gen-for
  "A generator of readings for each rule."
  {rules/bp-stage-2 gen-bp
   rules/low-spo2 (gen-quantity 80 100)
   rules/glucose-out-of-range (gen-quantity 40 320)
   rules/rapid-weight-gain (gen/fmap (fn [r] (update r :value #(/ % 10.0))) (gen-quantity 700 900))})

(def ^:private gen-rule-and-readings
  (gen/bind (gen/elements rules/catalogue)
            (fn [rule] (gen/fmap (fn [readings] [rule readings]) (gen/vector (gen-for rule) 0 12)))))

(def ^:private gen-rule-and-readings-at-whole-hours
  "Like `gen-rule-and-readings`, with the times cut to the hour, so that readings of the same instant
  are common: a device that sends in batches stamps its readings alike."
  (gen/fmap (fn [[rule readings]]
              [rule (mapv #(update % :at (fn [^Instant at] (.truncatedTo at ChronoUnit/HOURS))) readings)])
            gen-rule-and-readings))

(defn- with-a-permutation
  "`generator` of `[rule readings]`, each with another order of the same readings: `[rule readings
  permuted]`. The permutation is part of what is generated, so that a failure can be repeated and shrunk."
  [generator]
  (gen/bind generator
            (fn [[rule readings]]
              (gen/fmap (fn [permuted] [rule readings permuted]) (gen/shuffle readings)))))

(defspec the-order-of-the-readings-does-not-matter 400
  (prop/for-all [[rule readings permuted] (with-a-permutation gen-rule-and-readings)]
    (= (evaluate rule readings now)
       (evaluate rule permuted now))))

(defspec the-order-of-readings-of-the-same-instant-does-not-matter 400
  ;; a server does not say in which order it gives readings that have the same time
  (prop/for-all [[rule readings permuted] (with-a-permutation gen-rule-and-readings-at-whole-hours)]
    (= (evaluate rule readings now)
       (evaluate rule permuted now))))

(deftest the-generator-of-readings-at-whole-hours-makes-ties-often
  ;; or the property above would hardly try them. Measured over 40 runs of 500 samples: 63 have two
  ;; readings of the same instant, give or take 8 (the least was 50); 30 is a generator that has
  ;; lost its ties, not one that had a bad run.
  (let [samples (gen/sample (gen/fmap second gen-rule-and-readings-at-whole-hours) 500)
        with-ties (count (filter (fn [readings] (not= (count readings) (count (distinct (map :at readings))))) samples))]
    (is (< 30 with-ties) (str with-ties " of 500 samples have a tie"))))

(defspec what-fires-is-supported-by-readings-that-were-given-and-that-satisfy-the-condition 400
  (prop/for-all [[rule readings] gen-rule-and-readings]
    (let [result (evaluate rule readings now)]
      (or (nil? result)
          (let [evidence (:readings result)]
            (and (every? (set readings) evidence)
                 (seq evidence)
                 (= (:newest-at result) (reduce (fn [a b] (if (.isAfter ^Instant b a) b a)) (map :at evidence)))
                 ;; the readings that count satisfy the condition, but for a rise, whose evidence is
                 ;; the reading it rose from and the latest
                 (or (= :rise (:type rule))
                     (every? (:test rule) evidence))))))))

(defspec readings-after-now-and-before-the-look-back-are-never-counted 400
  (prop/for-all [[rule readings] gen-rule-and-readings]
    (let [too-new (map #(assoc % :at (.plusSeconds now 1)) readings)
          too-old (map #(assoc % :at (.minus now (Duration/ofHours (inc (:look-back-hours rule))))) readings)]
      (and (nil? (evaluate rule too-new now))
           (nil? (evaluate rule too-old now))
           ;; and they do not change the answer when they are added to others
           (= (evaluate rule readings now)
              (evaluate rule (concat readings too-new too-old) now))))))

(defspec more-readings-in-the-window-never-stop-a-count-rule-from-firing 300
  (prop/for-all [rule (gen/elements [rules/low-spo2 rules/glucose-out-of-range])
                 readings (gen/vector (gen-quantity 60 320) 0 10)
                 extra (gen-quantity 60 320)]
    (or (nil? (evaluate rule readings now))
        (some? (evaluate rule (conj readings extra) now)))))

(defspec a-count-rule-fires-exactly-when-enough-readings-in-the-window-satisfy-it 400
  (prop/for-all [rule (gen/elements [rules/low-spo2 rules/glucose-out-of-range])
                 readings (gen/vector (gen-quantity 40 320) 0 12)]
    (let [in-window (filter (fn [{:keys [at]}] (and (.isAfter ^Instant at (.minus now (Duration/ofHours 24)))
                                                    (not (.isAfter ^Instant at now))))
                            readings)
          enough? (>= (count (filter (:test rule) in-window)) (:at-least rule))]
      (= enough? (some? (evaluate rule readings now))))))
