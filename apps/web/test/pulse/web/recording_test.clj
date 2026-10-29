(ns pulse.web.recording-test
  "Recording a reading from the form of the portal (ADR-0028), without a server: what a patient types, turned into what the api takes
  and held to what the api itself accepts; what the api refuses, turned into the words beside the fields; and the moment a
  `datetime-local` field means, which has no zone, through the days the clocks change."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.api.observations :as api-observations]
            [pulse.fhir.measurement :as measurement]
            [pulse.view.format :as fmt]
            [pulse.web.recording :as recording]
            [pulse.view.views :as views])
  (:import (java.time Duration Instant)))

;; ---------------------------------------------------------------------------
;; The measurements and the fields

(deftest the-form-can-record-every-measurement-the-api-can-record-and-no-other
  (is (= (set (keys measurement/measurements)) (set views/kinds)))
  (doseq [kind views/kinds]
    (is (= kind (recording/kind-of (name kind)))))
  (testing "anything else is no measurement"
    (doseq [text [nil "" " " "heart" "Heart-Rate" " heart-rate" "heart-rate " "heart-rate\n" ":heart-rate" "blood_pressure" 1 :heart-rate ["heart-rate"]]]
      (is (nil? (recording/kind-of text)) (pr-str text)))))

(deftest the-fields-of-a-form-are-the-numbers-of-the-measurement
  (doseq [kind views/kinds
          :let [fields (recording/fields kind)
                {:keys [channels decimals]} (get measurement/measurements kind)]]
    (testing (name kind)
      (is (= (keys channels) (map :channel fields)) "the numbers, in the order of the measurement")
      (is (every? #(and (string? (:label %)) (string? (:name %)) (string? (:step %)) (string? (:inputmode %))) fields))
      (is (every? #(str/includes? (:label %) (str "(" (fmt/unit (:unit (get measurement/measurements kind))) ")")) fields)
          "each says the unit it is in")
      (is (every? #(= (if (zero? decimals) "numeric" "decimal") (:inputmode %)) fields) "a keypad for what the api keeps")
      (is (every? #(= (if (zero? decimals) "1" "0.1") (:step %)) fields) "as fine as the api keeps it")))
  (testing "a blood pressure is two numbers, with their words"
    (is (= [{:channel :systolic :label "Systolic (mmHg)" :name "systolic pressure" :step "1" :inputmode "numeric" :hint "The higher number."}
            {:channel :diastolic :label "Diastolic (mmHg)" :name "diastolic pressure" :step "1" :inputmode "numeric" :hint "The lower number."}]
           (recording/fields :blood-pressure))))
  (testing "the others are one, named for what it is"
    (is (= [{:channel :value :label "Heart rate (bpm)" :name "heart rate" :step "1" :inputmode "numeric"}] (recording/fields :heart-rate)))
    (is (= [{:channel :value :label "Oxygen saturation (%)" :name "oxygen saturation" :step "1" :inputmode "numeric"}] (recording/fields :spo2)))
    (is (= [{:channel :value :label "Glucose (mg/dL)" :name "glucose" :step "1" :inputmode "numeric"}] (recording/fields :glucose)))
    (is (= [{:channel :value :label "Weight (kg)" :name "weight" :step "0.1" :inputmode "decimal"}] (recording/fields :weight)))
    (is (= [{:channel :value :label "Temperature (°C)" :name "temperature" :step "0.1" :inputmode "decimal"}] (recording/fields :temperature))))
  (testing "a measurement that does not exist has no fields"
    (is (empty? (recording/fields :nothing)))))

(deftest the-form-says-what-the-api-says-about-how-long-ago
  (is (= api-observations/max-age-days recording/max-age-days)))

;; ---------------------------------------------------------------------------
;; A number

(deftest a-number-is-read-as-a-browser-writes-it
  (doseq [[text expected] {"72" 72 " 72 " 72 "0072" 72 "-5" -5 "0" 0 "-0" 0 "36.6" 36.6 "36.60" 36.6 ".5" 0.5 "-.5" -0.5 "1e2" 100 "1E2" 100
                           "1.5e1" 15 "1e-2" 0.01 "1e+2" 100}]
    (is (= expected (recording/parse-number text)) (pr-str text)))
  (testing "as long as it is no more than 32 characters, which is more than any reading needs"
    (is (some? (recording/parse-number (apply str (repeat 32 "1")))))
    (is (some? (recording/parse-number (str "0." (apply str (repeat 30 "1")))))))
  (testing "a number too large to be told from a whole one is not made into one"
    (is (instance? Long (recording/parse-number "999999999999999")))
    (is (instance? Double (recording/parse-number "1e15")))
    (is (= 1.0E15 (recording/parse-number "1e15"))))
  (testing "a whole number is a whole number, so that it is sent as 72 and not as 72.0"
    (is (instance? Long (recording/parse-number "72")))
    (is (instance? Long (recording/parse-number "72.0")))
    (is (instance? Double (recording/parse-number "72.5"))))
  (testing "and what is not a number is nothing"
    (doseq [text [nil "" " " "abc" "5." "." "-" "+5" "1,5" "1 000" "٣" "1e" "e5" "0x10" "NaN" "Infinity" "-Infinity" "1e400" "--5" "5-" "1.2.3" "٫5"
                  72 :72 ["72"] ["1" "2"] {:value "1"} "72abc" "7 2" (apply str (repeat 33 "1"))]]
      (is (nil? (recording/parse-number text)) (pr-str text)))))

(defspec any-text-is-a-number-or-nothing-and-never-an-error 300
  (prop/for-all [text (gen/one-of [gen/string gen/string-ascii (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "0123456789.-+eE, ")) 0 12))])]
    (let [x (recording/parse-number text)]
      (or (nil? x) (and (number? x) (Double/isFinite (double x)))))))

(defspec a-number-that-is-written-is-the-number-that-is-read 300
  (prop/for-all [x (gen/one-of [(gen/choose -100000 100000)
                                (gen/fmap #(/ % 10.0) (gen/choose -100000 100000))
                                (gen/fmap #(/ % 100.0) (gen/choose -100000 100000))])]
    (= (double x) (double (recording/parse-number (str x))))))

;; ---------------------------------------------------------------------------
;; A moment, which the field writes without a zone

(deftest a-time-is-the-time-on-the-clinics-clock
  (doseq [[text expected] {"2026-10-13T14:00" "2026-10-13T12:00:00Z"
                           "2026-10-13T14:00:30" "2026-10-13T12:00:30Z"
                           "2026-10-13T14:00:30.5" "2026-10-13T12:00:30Z"
                           "2026-01-15T09:00" "2026-01-15T08:00:00Z"
                           " 2026-10-13T14:00 " "2026-10-13T12:00:00Z"}]
    (is (= expected (recording/taken-at text)) (pr-str text))))

(deftest an-empty-field-means-now
  (doseq [text [nil "" " " "\n"]]
    (is (nil? (recording/taken-at text)) (pr-str text))))

(deftest what-is-not-a-time-is-said-so
  (doseq [text ["yesterday" "2026-10-13" "14:00" "2026-10-13 14:00" "2026-10-13T14" "2026-10-13T14:00Z" "2026-10-13T14:00+02:00" "2026-02-30T10:00"
                "2026-13-01T10:00" "2026-10-32T10:00" "2026-10-13T25:00" "2026-10-13T14:61" "2026-10-13T14:00:61" "12345-01-01T10:00" "+2026-10-13T14:00"
                "2026-10-13T14:00:00.1234567891" "2026-1-13T14:00" 1 ["2026-10-13T14:00"] :now]]
    (is (= ::recording/invalid (recording/taken-at text)) (pr-str text))))

(deftest the-days-the-clocks-change-have-a-moment-for-every-time-that-can-be-typed
  (testing "in autumn the clocks go back at 03:00 to 02:00, so the times from 02:00 to 02:59 happen twice: the first is the one meant"
    (is (= "2026-10-24T23:59:00Z" (recording/taken-at "2026-10-25T01:59")) "before it, summer time")
    (is (= "2026-10-25T00:00:00Z" (recording/taken-at "2026-10-25T02:00")) "the first 02:00, in summer time")
    (is (= "2026-10-25T00:30:00Z" (recording/taken-at "2026-10-25T02:30")))
    (is (= "2026-10-25T00:59:00Z" (recording/taken-at "2026-10-25T02:59")) "the last 02:xx of the first hour, in summer time")
    (is (= "2026-10-25T02:00:00Z" (recording/taken-at "2026-10-25T03:00")) "winter time again, an hour after the second 02:00"))
  (testing "in spring they go forward at 02:00 to 03:00, so the times from 02:00 to 02:59 never happen: they are read an hour later"
    (is (= "2026-03-29T00:59:00Z" (recording/taken-at "2026-03-29T01:59")))
    (is (= "2026-03-29T01:00:00Z" (recording/taken-at "2026-03-29T02:00")) "03:00 in summer time")
    (is (= "2026-03-29T01:30:00Z" (recording/taken-at "2026-03-29T02:30")))
    (is (= "2026-03-29T01:00:00Z" (recording/taken-at "2026-03-29T03:00")) "the same moment as the one that does not exist, an hour before")))

(defspec a-time-that-was-on-the-clock-is-read-back-as-the-same-time-and-never-an-hour-away 300
  (prop/for-all [seconds (gen/choose (.getEpochSecond (Instant/parse "2025-01-01T00:00:00Z")) (.getEpochSecond (Instant/parse "2028-12-31T23:59:59Z")))]
    (let [instant (Instant/ofEpochSecond seconds)
          typed (fmt/local-input (str instant))
          read (Instant/parse (recording/taken-at typed))
          minute (.minus instant (Duration/ofSeconds (mod seconds 60)))]
      (and
       ;; what is read back is the time that was typed
       (= typed (fmt/local-input (str read)))
       ;; the minute that was typed, or, in the hour that happens twice, the first time it happened
       (contains? #{minute (.minus minute (Duration/ofHours 1))} read)))))

(deftest a-reading-may-be-taken-at-the-soonest-30-days-before-now
  (is (= "2026-09-13T12:00:00Z" (recording/earliest "2026-10-13T12:00:00Z")))
  (is (= "2026-09-25T00:30:00Z" (recording/earliest "2026-10-25T00:30:00Z"))))

;; ---------------------------------------------------------------------------
;; The form as the api takes it

(deftest what-the-form-holds-is-text-or-nothing
  (is (= {:systolic "132" :diastolic nil :taken ""}
         (recording/typed :blood-pressure {"systolic" "132" "taken" "" "measurement" "blood-pressure" "__anti-forgery-token" "x"}))
      "only the fields of the measurement, and none that is not there")
  (is (= {:value nil :taken nil} (recording/typed :heart-rate {"value" ["1" "2"] "taken" 5})) "a parameter that comes twice is not text")
  (is (= {:value "72" :taken nil} (recording/typed :heart-rate {"value" "72" "systolic" "1"})) "another measurement's field is not read"))

(deftest a-blood-pressure-is-sent-as-two-numbers
  (is (= {:body {:measurement "blood-pressure" :systolic 132 :diastolic 88 :at "2026-10-13T12:00:00Z"}}
         (recording/submission :blood-pressure {"systolic" "132" "diastolic" "88" "taken" "2026-10-13T14:00"}))))

(deftest every-other-measurement-is-sent-as-one
  (doseq [[kind text number] [[:heart-rate "72" 72] [:spo2 "97" 97] [:glucose "110" 110] [:weight "82.3" 82.3] [:temperature "36.6" 36.6]]]
    (is (= {:body {:measurement (name kind) :value number :at "2026-10-13T12:00:00Z"}}
           (recording/submission kind {"value" text "taken" "2026-10-13T14:00"}))
        (name kind))))

(deftest an-empty-time-is-no-time-and-the-api-takes-now
  (is (= {:body {:measurement "heart-rate" :value 72}} (recording/submission :heart-rate {"value" "72" "taken" ""})))
  (is (= {:body {:measurement "heart-rate" :value 72}} (recording/submission :heart-rate {"value" "72"}))))

(deftest what-cannot-be-read-is-said-beside-its-field
  (is (= {:errors {:value "Enter a number."}} (recording/submission :heart-rate {"value" ""})))
  (is (= {:errors {:value "Enter a number."}} (recording/submission :heart-rate {"value" "seventy"})))
  (is (= {:errors {:value "Enter a number."}} (recording/submission :heart-rate {"taken" "2026-10-13T14:00"})) "a field that is not there")
  (is (= {:errors {:value "Enter a number."}} (recording/submission :heart-rate {"value" ["72" "73"]})) "a field that comes twice")
  (is (= {:errors {:systolic "Enter a number." :diastolic "Enter a number."}} (recording/submission :blood-pressure {})))
  (is (= {:errors {:diastolic "Enter a number."}} (recording/submission :blood-pressure {"systolic" "132" "diastolic" "88x"})))
  (is (= {:errors {:taken "Enter a date and time."}} (recording/submission :heart-rate {"value" "72" "taken" "yesterday"})))
  (is (= {:errors {:taken "Enter a date and time."}} (recording/submission :heart-rate {"value" "72" "taken" ["2026-10-13T14:00" "2026-10-13T15:00"]}))
      "a time that comes twice is no time")
  (is (= {:errors {:value "Enter a number." :taken "Enter a date and time."}}
         (recording/submission :heart-rate {"value" "" "taken" "x"}))
      "everything that is wrong at once, so that it is corrected at once"))

(deftest the-form-leaves-the-limits-and-the-rest-to-the-api
  (testing "a number out of its limits, or a diastolic above the systolic, is a reading to send: the api says what is wrong"
    (is (contains? (recording/submission :heart-rate {"value" "400"}) :body))
    (is (contains? (recording/submission :blood-pressure {"systolic" "80" "diastolic" "120"}) :body))
    (is (contains? (recording/submission :heart-rate {"value" "-5"}) :body))
    (is (contains? (recording/submission :heart-rate {"value" "72" "taken" "2000-01-01T00:00"}) :body) "and a time too far back")))

(defspec a-form-with-plausible-numbers-gives-a-reading-the-api-accepts 300
  (prop/for-all [kind (gen/elements (remove #{:blood-pressure} views/kinds))
                 fraction (gen/choose 0 1000)]
    (let [[lower upper] (get-in measurement/measurements [kind :channels :value :plausible])
          x (+ lower (* fraction (/ (- upper lower) 1000.0)))
          {:keys [body errors]} (recording/submission kind {"value" (str x)})
          normalized (measurement/normalize (update (select-keys body [:measurement :value]) :measurement keyword))]
      (and (nil? errors) (contains? normalized :ok)))))

(defspec a-blood-pressure-with-plausible-numbers-gives-a-reading-the-api-accepts 300
  (prop/for-all [[systolic diastolic] (gen/bind (gen/choose 40 300)
                                                (fn [systolic] (gen/fmap (fn [diastolic] [systolic diastolic])
                                                                         (gen/choose 20 (min 200 (dec systolic))))))]
    (let [{:keys [body errors]} (recording/submission :blood-pressure {"systolic" (str systolic) "diastolic" (str diastolic)})]
      (and (nil? errors)
           (contains? (measurement/normalize (update (select-keys body [:measurement :systolic :diastolic]) :measurement keyword)) :ok)))))

;; ---------------------------------------------------------------------------
;; What the api refused

(defn- problem-of
  "The `errors` of what the api answers when it refuses `submission`, from the catalogue and the time it also judges."
  [submission]
  (:errors (measurement/normalize (update submission :measurement keyword))))

(deftest what-the-api-refuses-is-written-beside-the-field-as-a-sentence
  (testing "a number out of its limits"
    (is (= {:diastolic "Must be between 20 and 200 mmHg."}
           (recording/refusal :blood-pressure (problem-of {:measurement "blood-pressure" :systolic 132 :diastolic 310}))))
    (is (str/starts-with? (:value (recording/refusal :heart-rate (problem-of {:measurement "heart-rate" :value 400})))
                          "Must be between 20 and 300 ")
        "in the unit the api says it in"))
  (testing "numbers that cannot go together"
    (is (= {:diastolic "Must be lower than the systolic pressure."}
           (recording/refusal :blood-pressure (problem-of {:measurement "blood-pressure" :systolic 80 :diastolic 120})))))
  (testing "a time the api does not take is beside the field for the time"
    (is (= {:taken "Must not be in the future."} (recording/refusal :heart-rate {:at ["must not be in the future"]})))
    (is (= {:taken "Must not be more than 30 days ago."} (recording/refusal :heart-rate {:at ["must not be more than 30 days ago"]}))))
  (testing "several at once"
    (is (= {:systolic "Must be between 40 and 300 mmHg." :diastolic "Must be between 20 and 200 mmHg." :taken "Must not be in the future."}
           (recording/refusal :blood-pressure (merge (problem-of {:measurement "blood-pressure" :systolic 20 :diastolic 250})
                                                     {:at ["must not be in the future"]}))))))

(deftest what-the-form-has-no-field-for-is-left-out
  (is (= {} (recording/refusal :heart-rate {:unit ["must be one of /min"]})) "the form never sends a unit")
  (is (= {} (recording/refusal :heart-rate {:measurement ["is not a measurement that can be recorded"]})))
  (is (= {:value "Must be a number."} (recording/refusal :heart-rate {:value ["must be a number"] :unit ["x"]})))
  (is (= {} (recording/refusal :heart-rate {:systolic ["x"]})) "a field of another measurement")
  (is (= {} (recording/refusal :heart-rate {"value" ["x"]})) "a key that is not a keyword is nothing the api said"))

(deftest whatever-the-api-says-the-words-are-words
  (testing "the first message of a field, a message that is not one is passed over"
    (is (= {:value "First."} (recording/refusal :heart-rate {:value ["first" "second"]})))
    (is (= {:value "Second."} (recording/refusal :heart-rate {:value [nil "  " "second"]})))
    (is (= {:value "Alone."} (recording/refusal :heart-rate {:value "alone"})) "a message that is not in a list"))
  (testing "nothing to place"
    (doseq [errors [nil {} [] "nonsense" 5 {:value []} {:value [nil]} {:value ["  "]} {:value 5} {:value {:nested "x"}} {:value [{:a 1}]}]]
      (is (= {} (recording/refusal :heart-rate errors)) (pr-str errors))))
  (testing "a sentence of its own: a capital, one stop, and not a word more than 200 characters long"
    (is (= {:value "Already a sentence."} (recording/refusal :heart-rate {:value ["already a sentence."]})))
    (is (= {:value "Asked?"} (recording/refusal :heart-rate {:value ["asked?"]})))
    (is (= {:value "Shouted!"} (recording/refusal :heart-rate {:value ["shouted!"]})))
    (is (= {:value "Spaces around."} (recording/refusal :heart-rate {:value ["   spaces around  "]})))
    (is (= {:value (str "X" (apply str (repeat 199 "y")) ".")}
           (recording/refusal :heart-rate {:value [(str "x" (apply str (repeat 500 "y")))]})))
    (is (= {:value "Y."} (recording/refusal :heart-rate {:value [(str (apply str (repeat 250 " ")) "y")]}))
        "white space that fills the first 200 characters is nothing")))
