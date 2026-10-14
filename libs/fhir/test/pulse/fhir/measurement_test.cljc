(ns pulse.fhir.measurement-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.measurement :as measurement :refer [measurements normalize]]
            [pulse.fhir.observation :as obs]
            [pulse.fhir.ranges :as ranges]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.terminology :as terminology]
            [pulse.fhir.units :as units]))

(defn- stored [submission] (:ok (normalize submission)))
(defn- errors [submission] (:errors (normalize submission)))

;; ---------------------------------------------------------------------------
;; The catalogue

(deftest the-catalogue-agrees-with-the-rest-of-the-library
  (doseq [[kind {:keys [concept unit channels]}] measurements]
    (testing (str kind)
      (is (some? (terminology/coding concept)) "a concept of the terminology catalogue")
      (is (units/known? unit))
      (doseq [[channel {[lower upper] :plausible band :band}] channels]
        (testing (str channel)
          (is (< lower upper))
          (when (not= :value channel)
            (is (some? (terminology/coding channel)) "the channel of a panel is a concept of its own"))
          (when band
            (let [{band-unit :unit bands :bands} (get ranges/measurements band)]
              (is (seq bands) "a measurement `ranges` classifies")
              (is (= unit band-unit) "classified in the unit it is stored in")
              (testing "the plausible limits leave every reference band reachable"
                (doseq [[_ lo hi] bands
                        edge (remove nil? [lo hi])]
                  (is (<= lower edge upper) (str "edge " edge)))))))))))

;; ---------------------------------------------------------------------------
;; Normalizing

(deftest a-reading-in-the-stored-unit-is-kept
  (is (= {:measurement :heart-rate :unit "/min" :value 72} (stored {:measurement :heart-rate :value 72})))
  (is (= (stored {:measurement :heart-rate :value 72})
         (stored {:measurement :heart-rate :value 72 :unit "/min"}))
      "the stored unit is the default")
  (is (= {:measurement :blood-pressure :unit "mm[Hg]" :systolic 150 :diastolic 95}
         (stored {:measurement :blood-pressure :systolic 150 :diastolic 95})))
  (is (= {:measurement :spo2 :unit "%" :value 97} (stored {:measurement :spo2 :value 97})))
  (is (= {:measurement :weight :unit "kg" :value 72.5} (stored {:measurement :weight :value 72.5}))))

(deftest a-reading-in-another-unit-is-converted
  (is (= {:measurement :glucose :unit "mg/dL" :value 110}
         (stored {:measurement :glucose :value 6.1 :unit "mmol/L"})))
  (is (= {:measurement :weight :unit "kg" :value 72.6}
         (stored {:measurement :weight :value 160 :unit "[lb_av]"})))
  (is (= {:measurement :weight :unit "kg" :value 72.5}
         (stored {:measurement :weight :value 72500 :unit "g"})))
  (is (= {:measurement :temperature :unit "Cel" :value 37.0}
         (stored {:measurement :temperature :value 98.6 :unit "[degF]"})))
  (is (= {:measurement :temperature :unit "Cel" :value 37.0}
         (stored {:measurement :temperature :value 310.15 :unit "K"})))
  (is (= {:measurement :blood-pressure :unit "mm[Hg]" :systolic 150 :diastolic 90}
         (stored {:measurement :blood-pressure :systolic 20 :diastolic 12 :unit "kPa"}))
      "both numbers are in the unit that is named"))

(deftest values-are-rounded-to-the-precision-of-the-measurement
  (is (= 72 (:value (stored {:measurement :heart-rate :value 72.4}))))
  (is (= 73 (:value (stored {:measurement :heart-rate :value 72.6}))))
  (is (integer? (:value (stored {:measurement :heart-rate :value 72.0}))) "a whole number, not 72.0")
  (is (= 72.5 (:value (stored {:measurement :weight :value 72.54}))))
  (is (= 72.6 (:value (stored {:measurement :weight :value 72.56}))))
  (is (= 36.8 (:value (stored {:measurement :temperature :value 36.78})))))

(deftest what-is-not-a-measurement-is-refused
  (let [message {:measurement ["is not a measurement that can be recorded"]}]
    (is (= message (errors {:measurement :nope :value 1})))
    (is (= message (errors {:value 1})))
    (is (= message (errors {:measurement "heart-rate" :value 72})) "the name is a keyword")
    (is (= message (errors nil)))))

(deftest a-unit-the-measurement-cannot-have-is-refused-with-the-ones-it-can
  (is (= {:unit ["must be one of /min"]} (errors {:measurement :heart-rate :value 72 :unit "kg"})))
  (is (= {:unit ["must be one of mg/dL, mmol/L"]} (errors {:measurement :glucose :value 6 :unit "kg"})))
  (testing "a unit that is not UCUM is as unknown as any other"
    (is (= ["must be one of [lb_av], g, kg"] (:unit (errors {:measurement :weight :value 160 :unit "lb"}))))
    (is (= ["must be one of Cel, K, [degF]"] (:unit (errors {:measurement :temperature :value 98 :unit "F"})))))
  (testing "whatever it is"
    (is (contains? (errors {:measurement :heart-rate :value 72 :unit 5}) :unit))
    (is (contains? (errors {:measurement :heart-rate :value 72 :unit ""}) :unit))
    (is (contains? (errors {:measurement :heart-rate :value 72 :unit ["/min"]}) :unit))))

(deftest a-number-that-is-missing-or-is-not-one-is-refused
  (is (= {:value ["is required"]} (errors {:measurement :heart-rate})))
  (is (= {:value ["is required"]} (errors {:measurement :heart-rate :value nil})))
  (is (= {:value ["must be a number"]} (errors {:measurement :heart-rate :value "72"})))
  (is (= {:value ["must be a number"]} (errors {:measurement :heart-rate :value ##NaN})))
  (is (= {:value ["must be a number"]} (errors {:measurement :heart-rate :value ##Inf})))
  (is (= {:value ["must be a number"]} (errors {:measurement :heart-rate :value ##-Inf}))))

(deftest a-value-that-cannot-be-right-is-refused
  (is (= {:value ["must be between 20 and 300 beats/minute"]} (errors {:measurement :heart-rate :value 400})))
  (is (= {:value ["must be between 20 and 300 beats/minute"]} (errors {:measurement :heart-rate :value 5})))
  (is (= {:value ["must be between 50 and 100 %"]} (errors {:measurement :spo2 :value 101})))
  (is (= {:value ["must be between 1 and 500 kg"]} (errors {:measurement :weight :value 700})))
  (is (= {:value ["must be between 10 and 1000 mg/dL"]} (errors {:measurement :glucose :value 5})))
  (testing "a Fahrenheit reading labelled as Celsius is the typo the limits are there for"
    (is (= {:value ["must be between 25 and 45 °C"]} (errors {:measurement :temperature :value 98.6 :unit "Cel"}))))
  (testing "the limits are given in the stored unit, and the message says it was converted"
    (is (= {:value ["must be between 1 and 500 kg once converted"]}
           (errors {:measurement :weight :value 2000 :unit "[lb_av]"}))))
  (testing "the limits are those of the value that would be stored, after rounding"
    (is (= 100 (:value (stored {:measurement :spo2 :value 100.4}))))
    (is (contains? (errors {:measurement :spo2 :value 100.5}) :value))))

(deftest a-blood-pressure-is-two-numbers-that-go-together
  (is (= {:systolic ["is required"] :diastolic ["is required"]} (errors {:measurement :blood-pressure})))
  (is (= {:systolic ["is required"] :diastolic ["is required"]}
         (errors {:measurement :blood-pressure :value 120}))
      "a single value is not a blood pressure")
  (is (= {:diastolic ["is required"]} (errors {:measurement :blood-pressure :systolic 120})))
  (is (= {:systolic ["must be between 40 and 300 mmHg"] :diastolic ["must be between 20 and 200 mmHg"]}
         (errors {:measurement :blood-pressure :systolic 20 :diastolic 250}))
      "each number is checked, and both are reported")
  (testing "the diastolic is lower than the systolic"
    (is (= {:diastolic ["must be lower than the systolic pressure"]}
           (errors {:measurement :blood-pressure :systolic 80 :diastolic 120})))
    (is (= {:diastolic ["must be lower than the systolic pressure"]}
           (errors {:measurement :blood-pressure :systolic 120 :diastolic 120})))
    (is (some? (stored {:measurement :blood-pressure :systolic 121 :diastolic 120})))))

(deftest keys-it-does-not-use-are-ignored
  (is (= (stored {:measurement :heart-rate :value 72})
         (stored {:measurement :heart-rate :value 72 :systolic 1 :at "2026-10-14T08:00:00Z" :other "x"}))))

;; Random submissions: some are nonsense, some are plausible readings in any unit.

(def ^:private gen-number
  (gen/one-of [(gen/double* {:min -1e6 :max 1e6 :NaN? false :infinite? false})
               gen/small-integer
               (gen/elements [0 1 -1 ##NaN ##Inf ##-Inf 1e300 nil "72" :x])]))

(def ^:private gen-anything
  (gen/let [kind (gen/one-of [(gen/elements (keys measurements)) (gen/elements [nil :nope "heart-rate"])])
            unit (gen/one-of [(gen/return nil)
                              (gen/elements (concat (keys units/definitions) ["nope" "" 5 nil]))])
            value gen-number
            systolic gen-number
            diastolic gen-number]
    {:measurement kind :unit unit :value value :systolic systolic :diastolic diastolic}))

(def ^:private gen-plausible
  "A reading that could be right, in any unit its measurement may be sent in."
  (gen/bind
   (gen/elements (keys measurements))
   (fn [kind]
     (let [{stored-unit :unit :keys [channels]} (get measurements kind)]
       (gen/let [unit (gen/elements (filter #(units/compatible? % stored-unit) (keys units/definitions)))
                 numbers (apply gen/tuple (for [[_ {[lower upper] :plausible}] channels]
                                            (gen/double* {:min lower :max upper :NaN? false :infinite? false})))]
         (into {:measurement kind :unit unit}
               (map (fn [channel n] [channel (units/convert n stored-unit unit)]) (keys channels) numbers)))))))

(defn- decimals-of [x]
  (let [s (str x)
        i (.indexOf s ".")]
    (if (neg? i) 0 (- (count s) i 1))))

(defspec normalizing-answers-and-never-throws 500
  (prop/for-all [submission gen-anything]
    (let [answer (normalize submission)]
      (and (= 1 (count answer))
           (or (map? (:ok answer))
               (and (map? (:errors answer))
                    (seq (:errors answer))
                    (every? (every-pred vector? seq #(every? string? %)) (vals (:errors answer)))))))))

(defspec what-is-accepted-is-in-the-stored-unit-within-limits-and-precision 500
  (prop/for-all [submission (gen/one-of [gen-anything gen-plausible])]
    (if-let [reading (:ok (normalize submission))]
      (let [{stored-unit :unit :keys [decimals channels]} (get measurements (:measurement reading))]
        (and (= stored-unit (:unit reading))
             (every? (fn [[channel {[lower upper] :plausible}]]
                       (let [n (get reading channel)]
                         (and (<= lower n upper)
                              (<= (decimals-of n) decimals)
                              (or (pos? decimals) (integer? n)))))
                     channels)))
      true)))

(defspec most-plausible-readings-are-accepted 300
  ;; Not all: two plausible blood pressures can have the diastolic above the systolic
  (prop/for-all [submission gen-plausible]
    (or (some? (:ok (normalize submission)))
        (= :blood-pressure (:measurement submission)))))

(defspec normalizing-a-normalized-reading-changes-nothing 300
  (prop/for-all [submission gen-plausible]
    (if-let [reading (:ok (normalize submission))]
      (= reading (:ok (normalize reading)))
      true)))

;; ---------------------------------------------------------------------------
;; The Observation

(def ^:private at "2026-10-14T08:00:00Z")

(defn- observation-of [submission & [options]]
  (measurement/observation (:ok (normalize submission))
                           (merge {:id "obs-1" :patient "pt-ana" :at at} options)))

(deftest a-reading-becomes-an-observation
  (testing "a measurement of one number"
    (let [o (observation-of {:measurement :heart-rate :value 72})]
      (is (schema/valid? o))
      (is (obs/concept? o :heart-rate))
      (is (= {:kind :quantity :value 72 :unit "/min"} (obs/value o)))
      (is (= "Patient/pt-ana" (get-in o [:subject :reference])))
      (is (= at (:effectiveDateTime o)))
      (is (= "final" (:status o)))
      (is (= "vital-signs" (get-in o [:category 0 :coding 0 :code])))
      (is (= [{:reference "Patient/pt-ana"}] (:performer o)) "the patient, unless told otherwise")))
  (testing "a blood pressure, with its components"
    (let [o (observation-of {:measurement :blood-pressure :systolic 150 :diastolic 95})]
      (is (schema/valid? o))
      (is (obs/concept? o :bp-panel))
      (is (= {:systolic 150 :diastolic 95} (obs/blood-pressure o)))))
  (testing "a laboratory measurement keeps its category"
    (let [o (observation-of {:measurement :glucose :value 6.1 :unit "mmol/L"})]
      (is (= "laboratory" (get-in o [:category 0 :coding 0 :code])))
      (is (= {:kind :quantity :value 110 :unit "mg/dL"} (obs/value o)) "stored in the stored unit"))))

(deftest who-took-the-reading
  (is (= [{:reference "Practitioner/pr-rivera"}]
         (:performer (observation-of {:measurement :weight :value 80} {:performer "Practitioner/pr-rivera"}))))
  (is (= [{:reference "Practitioner/pr-rivera"}]
         (:performer (observation-of {:measurement :blood-pressure :systolic 120 :diastolic 80}
                                     {:performer "Practitioner/pr-rivera"}))))
  (testing "nobody, when the caller is not a person"
    (is (not (contains? (observation-of {:measurement :weight :value 80} {:performer nil}) :performer)))
    (is (schema/valid? (observation-of {:measurement :weight :value 80} {:performer nil})))))

(deftest only-a-measurement-of-the-catalogue-has-an-observation
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (measurement/observation {:measurement :nope :value 1} {:id "x" :patient "pt-ana" :at at}))))

(defspec an-accepted-reading-becomes-a-valid-observation-that-reads-back-the-same 300
  (prop/for-all [submission gen-plausible
                 performer (gen/elements [nil "Patient/pt-ana" "Practitioner/pr-rivera"])]
    (if-let [reading (:ok (normalize submission))]
      (let [{:keys [concept channels]} (get measurements (:measurement reading))
            o (measurement/observation reading {:id "obs-1" :patient "pt-ana" :at at :performer performer})]
        (and (schema/valid? o)
             (obs/concept? o concept)
             (if (contains? channels :value)
               (= (:value reading) (:value (obs/value o)))
               (= (select-keys reading [:systolic :diastolic]) (obs/blood-pressure o)))
             (= performer (get-in o [:performer 0 :reference]))))
      true)))

(deftest every-name-is-a-plain-word
  (is (every? #(re-matches #"[a-z][a-z0-9]*(-[a-z0-9]+)*" (name %)) (keys measurements))
      "the names are used in URLs and in JSON"))

(deftest the-error-messages-never-repeat-what-was-sent
  (let [secret "SECRET-UNIT"
        message (pr-str (errors {:measurement :heart-rate :value 72 :unit secret}))]
    (is (not (str/includes? message secret)))))
