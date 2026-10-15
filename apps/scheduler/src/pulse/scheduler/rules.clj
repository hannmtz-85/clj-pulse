(ns pulse.scheduler.rules
  "The rules that turn readings into alerts: Annex C of the plan as data, with a condition each
  (ADR-0022). Nothing here touches a server or a clock: a rule is evaluated over the readings it is
  given and the time it is given, and the answer is nil or the evidence.

  A rule is made by `defrule`, which takes its data and its condition, written with the names of
  the numbers of a reading (`value`, `systolic`, `diastolic`; for a rise, `value` and `baseline`),
  and defines a map holding both the function that runs and the quoted form of the same condition,
  so that the condition shown to a clinician is the one that ran. `evaluate` is a multimethod on the
  `:type` of a rule.

  A reading is `{:at instant :value n}`, or `{:at instant :systolic n :diastolic n}`, in the unit
  the measurement is stored in."
  (:require [malli.core :as m]
            [malli.error :as me]
            [pulse.fhir.measurement :as measurement])
  (:import (java.time Duration Instant)
           (java.time.temporal ChronoUnit)))

;; ---------------------------------------------------------------------------
;; What a rule is

(def rule-types
  "The types of rule, with the names that a condition may use."
  {:recent-count '[value systolic diastolic]
   :window-count '[value systolic diastolic]
   :rise '[value baseline]})

(def ^:private common
  [[:id [:re {:error/message "should be R and a number, as in Annex C"} #"^R\d+$"]]
   [:title [:string {:min 1}]]
   [:doc [:string {:min 1}]]
   [:measurement (into [:enum] (keys measurement/measurements))]
   [:look-back-hours pos-int?]
   [:requires-condition {:optional true} [:map {:closed true} [:system :keyword] [:prefix [:string {:min 1}]]]]
   [:test fn?]
   [:condition any?]])

(defn- typed [extra]
  (into [:map {:closed true}] (concat common extra)))

(def schema
  "What a rule must satisfy, by type."
  [:multi {:dispatch :type}
   [:recent-count
    [:and
     (typed [[:type [:= :recent-count]] [:of-last pos-int?] [:at-least pos-int?]])
     [:fn {:error/message "should ask for no more readings than it looks at"}
      (fn [{:keys [at-least of-last]}] (<= at-least of-last))]]]
   [:window-count
    (typed [[:type [:= :window-count]] [:at-least pos-int?]])]
   [:rise
    [:and
     (typed [[:type [:= :rise]] [:fresh-hours pos-int?] [:window-hours pos-int?]])
     [:fn {:error/message "should look back far enough to hold the latest reading and the window before it"}
      (fn [{:keys [look-back-hours fresh-hours window-hours]}] (>= look-back-hours (+ fresh-hours window-hours)))]]]])

(defn rule*
  "The rule made of `spec` (its data), `test` (the function of its condition) and `condition` (the
  form of the same condition). What `defrule` expands to; throws an ex-info of type
  `::invalid-rule` that says what is wrong when the result is not a rule."
  [spec test condition]
  (let [rule (assoc spec :test test :condition condition)]
    (if-let [explanation (m/explain schema rule)]
      (throw (ex-info (str "Invalid rule " (pr-str (:id spec)) ": " (pr-str (me/humanize explanation)))
                      {:type ::invalid-rule :id (:id spec) :problems (me/humanize explanation)}))
      rule)))

(defmacro defrule
  "Defines `rule-name` as a rule. `spec` is the data of the rule, as a map, with its condition under
  `:when`: a form over the numbers of a reading, of which the names depend on the `:type` (see
  `rule-types`). The var holds the rule, and is marked with `:rule` in its metadata.

    (defrule bp-stage-2
      \"Blood pressure at stage 2 in at least 2 of the last 3 readings.\"
      {:id \"R1\" :title \"BP stage 2\" :type :recent-count :measurement :blood-pressure
       :look-back-hours 72 :of-last 3 :at-least 2
       :when (or (>= systolic 160) (>= diastolic 100))})

  The rule is checked when the namespace loads, so a mistake in one stops the start-up and not the
  first evaluation."
  [rule-name doc spec]
  (let [{condition :when :as data} spec
        names (get rule-types (:type spec))]
    (when-not names
      (throw (ex-info (str "defrule " rule-name ": not a type of rule: " (pr-str (:type spec)))
                      {:type ::invalid-rule :known (keys rule-types)})))
    (when-not (contains? spec :when)
      (throw (ex-info (str "defrule " rule-name ": a rule needs its condition under :when")
                      {:type ::invalid-rule})))
    `(def ~(vary-meta rule-name assoc :rule true)
       ~doc
       (rule* ~(-> data (dissoc :when) (assoc :doc doc))
              (fn [{:keys ~names}] ~condition)
              '~condition))))

;; ---------------------------------------------------------------------------
;; Evaluating a rule

(defn- readings-within
  "The readings in the `hours` up to `now`: after the start of the period, and not after `now`."
  [readings ^Instant now hours]
  (let [start (.minus now (Duration/ofHours hours))]
    (filter (fn [{:keys [^Instant at]}]
              (and (.isAfter at start) (not (.isAfter at now))))
            readings)))

(defn- chronological
  "`readings` from the oldest to the newest. Readings of the same instant, which devices that send in
  batches produce, go by their numbers: the order they were given in, which a server does not
  promise for them, makes no difference to what a rule finds."
  [readings]
  (sort-by (juxt :at :value :systolic :diastolic) readings))

(defn- newest [readings]
  (reduce (fn [^Instant latest ^Instant at] (if (.isAfter at latest) at latest))
          (map :at readings)))

(defn- evidence
  "What a rule found: the `readings` that count, and the time of the newest."
  [readings]
  {:readings (vec (chronological readings))
   :newest-at (newest readings)})

(defmulti evaluate
  "Whether the readings make `rule` fire at `now`: nil if not, otherwise
  `{:readings [...] :newest-at instant}`, the readings that support it and the time of the newest.
  Pure, and the same for the readings in any order. `readings` are those of the rule's measurement;
  any outside the look-back, or after `now`, are not counted."
  (fn [rule _readings _now] (:type rule)))

(defmethod evaluate :recent-count
  [{:keys [test of-last at-least look-back-hours]} readings now]
  (let [recent (->> (readings-within readings now look-back-hours)
                    chronological
                    (take-last of-last))
        hits (filter test recent)]
    (when (>= (count hits) at-least)
      (evidence hits))))

(defmethod evaluate :window-count
  [{:keys [test at-least look-back-hours]} readings now]
  (let [hits (filter test (readings-within readings now look-back-hours))]
    (when (>= (count hits) at-least)
      (evidence hits))))

(defmethod evaluate :rise
  [{:keys [test look-back-hours fresh-hours window-hours]} readings now]
  (let [sorted (chronological (readings-within readings now look-back-hours))
        latest (last sorted)]
    (when (and latest (.isAfter ^Instant (:at latest) (.minus ^Instant now (Duration/ofHours fresh-hours))))
      (let [from (.minus ^Instant (:at latest) (Duration/ofHours window-hours))
            before (filter (fn [{:keys [^Instant at]}]
                             (and (not (.isBefore at from)) (.isBefore at ^Instant (:at latest))))
                           sorted)]
        (when (seq before)
          (let [lowest (apply min-key :value before)]
            ;; Rationals, so that a rise of 2.0 kg is exactly 2: with doubles, 64.1 - 62.1 is
            ;; 1.999999999999993, and the rule would miss a gain of exactly 2 kg on its threshold
            (when (test {:value (rationalize (:value latest)) :baseline (rationalize (:value lowest))})
              (evidence [lowest latest]))))))))

(defn query
  "What to ask for to evaluate `rule` at `now`: the measurement and the instant from which its
  readings count, in whole seconds (a server compares dates to a precision it chooses), and so up to
  a second earlier than the look-back: `evaluate` is exact about the window."
  [{:keys [measurement look-back-hours]} ^Instant now]
  {:measurement measurement
   :from (.minus (.truncatedTo now ChronoUnit/SECONDS) (Duration/ofHours look-back-hours))})

;; ---------------------------------------------------------------------------
;; Annex C

(defrule bp-stage-2
  "Blood pressure at stage 2 (a systolic of 160 or more, or a diastolic of 100 or more) in at least
  2 of the last 3 readings of the last 3 days."
  {:id "R1" :title "BP stage 2" :type :recent-count :measurement :blood-pressure
   :look-back-hours 72 :of-last 3 :at-least 2
   :when (or (>= systolic 160) (>= diastolic 100))})

(defrule low-spo2
  "Oxygen saturation below 92 % in at least 2 readings of the last 24 hours."
  {:id "R2" :title "Low SpO₂" :type :window-count :measurement :spo2
   :look-back-hours 24 :at-least 2
   :when (< value 92)})

(defrule glucose-out-of-range
  "A glucose above 250 or below 70 mg/dL in the last 24 hours."
  {:id "R3" :title "Glucose out of range" :type :window-count :measurement :glucose
   :look-back-hours 24 :at-least 1
   :when (or (> value 250) (< value 70))})

(defrule rapid-weight-gain
  "A latest weight, from the last 24 hours, 2 kg or more above the lowest of the 72 hours before it,
  for a patient with an active heart failure (ICD-10-CM I50)."
  {:id "R4" :title "Rapid weight gain" :type :rise :measurement :weight
   :look-back-hours 96 :fresh-hours 24 :window-hours 72
   :requires-condition {:system :icd-10-cm :prefix "I50"}
   :when (>= (- value baseline) 2.0)})

(def catalogue
  "Every rule, in the order of Annex C."
  [bp-stage-2 low-spo2 glucose-out-of-range rapid-weight-gain])
