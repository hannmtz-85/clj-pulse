(ns pulse.web.visits-test
  "Asking for a visit and cancelling an appointment from the forms of the portal (ADR-0028), without a server: what a patient types,
  turned into what the api takes and held to what the api itself accepts; what the api refuses, turned into the words beside the
  fields; and the days of a window, which are days of the clinic's clock, through the days the clocks change."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [pulse.api.appointments :as api-appointments]
            [pulse.fhir.appointment :as lifecycle]
            [pulse.view.format :as fmt]
            [pulse.web.visits :as visits])
  (:import (java.time Duration Instant LocalDate ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def ^:private now "2026-10-13T12:00:00Z")

(defn- form [& {:as fields}]
  (merge {"reason" "Medication review" "from" "2026-10-20" "to" "2026-10-27"} (update-keys fields name)))

(defn- date ^LocalDate [text] (LocalDate/parse text))

(defn- at-the-clinic
  "An instant as it is on the clinic's clock."
  ^ZonedDateTime [^Instant instant]
  (.atZone instant fmt/zone))

(defn- label
  "A day as the page writes it: `Oct 20`."
  [^LocalDate day]
  (.format day (.withLocale (DateTimeFormatter/ofPattern "MMM d") Locale/ENGLISH)))

;; ---------------------------------------------------------------------------
;; The limits are the api's

(deftest the-form-says-what-the-api-says-it-allows
  (is (= (:text-length lifecycle/limits) visits/reason-length))
  (is (= (:window-days lifecycle/limits) visits/window-days))
  (is (= (:window-ahead-days lifecycle/limits) visits/ahead-days))
  (testing "which are, as the form says them"
    (is (= [200 30 180] [visits/reason-length visits/window-days visits/ahead-days]))))

;; ---------------------------------------------------------------------------
;; What was typed

(deftest what-the-form-holds-is-what-was-typed-as-text
  (is (= {:reason "Medication review" :from "2026-10-20" :to "2026-10-27"} (visits/typed (form))))
  (testing "what is not there, or is not text, is nil: a parameter that comes twice is a vector"
    (is (= {:reason nil :from nil :to nil} (visits/typed {})))
    (is (= {:reason nil :from nil :to nil} (visits/typed nil)))
    (is (= {:reason nil :from "2026-10-20" :to nil} (visits/typed {"reason" ["a" "b"] "from" "2026-10-20" "to" 5})))
    (is (= {:reason "x" :from nil :to nil} (visits/typed {"reason" "x" "from" {:a 1}})))))

(deftest a-day-is-read-as-a-browser-writes-it
  (doseq [[text expected] {"2026-10-20" "2026-10-20" " 2026-10-20 " "2026-10-20" "2024-02-29" "2024-02-29" "0001-01-01" "0001-01-01"
                           "9999-12-31" "9999-12-31"}]
    (is (= (date expected) (visits/parse-date text)) (pr-str text)))
  (testing "and what is not a day is nothing"
    (doseq [text [nil "" " " "yesterday" "2026-10-2" "26-10-20" "2026/10/20" "20/10/2026" "2026-10-20T10:00" "2026-10-20 10:00" "2026-02-30"
                  "2025-02-29" "2026-13-01" "2026-00-10" "2026-10-00" "2026-10-32" "12345-10-20" "+2026-10-20" "-2026-10-20" "2026-10-20x" "x2026-10-20"
                  "٢٠٢٦-١٠-٢٠" 20261020 ["2026-10-20"] :2026-10-20 {:day "2026-10-20"}]]
      (is (nil? (visits/parse-date text)) (pr-str text)))))

(defspec any-text-is-a-day-or-nothing-and-never-an-error 300
  (prop/for-all [text (gen/one-of [gen/string gen/string-ascii
                                   (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "0123456789-/ T:")) 0 14))])]
    (let [day (visits/parse-date text)]
      (or (nil? day) (instance? LocalDate day)))))

(defspec a-day-that-is-written-is-the-day-that-is-read 300
  (prop/for-all [days (gen/choose 0 (* 366 300))]
    (let [day (.plusDays (date "1900-01-01") days)]
      (= day (visits/parse-date (str day))))))

(deftest the-reason-is-the-text-without-the-blanks-around-it-and-with-its-lines-as-a-text-keeps-them
  (is (= "Medication review" (visits/reason-of "Medication review")))
  (is (= "Medication review" (visits/reason-of "  Medication review \n ")))
  (is (= "Two\nlines" (visits/reason-of "Two\r\nlines")) "a browser sends a new line as two characters")
  (is (= "Two\nlines" (visits/reason-of "Two\rlines")))
  (is (= "Two\n\nlines" (visits/reason-of "Two\r\n\r\nlines")))
  (is (= "a  b" (visits/reason-of "a  b")) "what is inside is not touched")
  (testing "and no reason is none"
    (doseq [text [nil "" " " "\n" "\r\n \t" 5 ["a"] :a]]
      (is (nil? (visits/reason-of text)) (pr-str text)))))

;; ---------------------------------------------------------------------------
;; A window of days, on the clinic's clock

(deftest a-window-begins-when-the-first-day-does-and-ends-when-the-last-day-does
  (testing "in winter time, an hour ahead of UTC"
    (is (= {:requestedStart "2026-01-14T23:00:00Z" :requestedEnd "2026-01-20T23:00:00Z"}
           (visits/window (date "2026-01-15") (date "2026-01-20")))))
  (testing "in summer time, two hours ahead"
    (is (= {:requestedStart "2026-07-14T22:00:00Z" :requestedEnd "2026-07-20T22:00:00Z"}
           (visits/window (date "2026-07-15") (date "2026-07-20")))))
  (testing "the same day is the whole of that day: asking from the 20th until the 20th is asking for the 20th"
    (is (= {:requestedStart "2026-10-19T22:00:00Z" :requestedEnd "2026-10-20T22:00:00Z"}
           (visits/window (date "2026-10-20") (date "2026-10-20"))))))

(deftest the-days-the-clocks-change-are-the-length-they-are
  (testing "the night they go back, in autumn, the day is 25 hours long"
    (let [{:keys [requestedStart requestedEnd]} (visits/window (date "2026-10-25") (date "2026-10-25"))]
      (is (= "2026-10-24T22:00:00Z" requestedStart))
      (is (= "2026-10-25T23:00:00Z" requestedEnd))
      (is (= (Duration/ofHours 25) (Duration/between (Instant/parse requestedStart) (Instant/parse requestedEnd))))))
  (testing "the night they go forward, in spring, it is 23"
    (let [{:keys [requestedStart requestedEnd]} (visits/window (date "2026-03-29") (date "2026-03-29"))]
      (is (= "2026-03-28T23:00:00Z" requestedStart))
      (is (= "2026-03-29T22:00:00Z" requestedEnd))
      (is (= (Duration/ofHours 23) (Duration/between (Instant/parse requestedStart) (Instant/parse requestedEnd))))))
  (testing "so a window of 30 days across the autumn night is an hour longer than 30 days, which the api refuses, with its words"
    (let [{:keys [requestedStart requestedEnd]} (visits/window (date "2026-10-10") (date "2026-11-08"))]
      (is (= (Duration/ofHours 721) (Duration/between (Instant/parse requestedStart) (Instant/parse requestedEnd))))
      (is (= {:requestedEnd ["must be at most 30 days after the start"]}
             (lifecycle/request-errors {:description "x" :requestedStart requestedStart :requestedEnd requestedEnd} now))))))

(defspec a-window-is-the-days-it-was-asked-for-and-reads-back-as-them 300
  (prop/for-all [first-offset (gen/choose 0 1500)
                 length (gen/choose 0 40)]
    (let [first-day (.plusDays (date "2025-01-01") first-offset)
          last-day (.plusDays first-day length)
          {:keys [requestedStart requestedEnd]} (visits/window first-day last-day)
          start (Instant/parse requestedStart)
          end (Instant/parse requestedEnd)]
      (and
       ;; it begins when the first day does, and ends when the day after the last one does
       (= first-day (.toLocalDate (at-the-clinic start)))
       (= (.atStartOfDay first-day fmt/zone) (at-the-clinic start))
       (= (.atStartOfDay (.plusDays last-day 1) fmt/zone) (at-the-clinic end))
       ;; it covers exactly the days that were asked for, whatever the clocks did
       (= (inc length) (.between java.time.temporal.ChronoUnit/DAYS (.toLocalDate (at-the-clinic start)) (.toLocalDate (at-the-clinic end))))
       ;; and the page says them back: the day it begins and the last day it covers
       (= (label first-day) (fmt/short-date requestedStart))
       (= (label last-day) (fmt/last-date requestedEnd))))))

;; ---------------------------------------------------------------------------
;; The request, held to what the api takes

(deftest a-request-is-the-reason-and-the-window
  (is (= {:body {:description "Medication review" :requestedStart "2026-10-19T22:00:00Z" :requestedEnd "2026-10-27T23:00:00Z"}}
         (visits/submission (form)))))

(deftest what-the-form-sends-is-what-the-api-takes
  (doseq [fields [{} {"reason" "A very long one"} {"from" "2026-10-13" "to" "2026-10-13"} {"from" "2026-11-02" "to" "2026-12-01"}
                  {"from" "2027-04-11" "to" "2027-04-11"} {"reason" "  padded  \r\n"} {"reason" (apply str (repeat 200 "x"))}]
          :let [{:keys [body errors]} (visits/submission (merge (form) fields))]]
    (testing (pr-str fields)
      (is (nil? errors))
      (is (m/validate api-appointments/request-body body) "the shape the api takes")
      (is (nil? (lifecycle/request-errors body now)) "and nothing it would refuse for what it says")))
  (testing "the reason is the text, with no blanks around it"
    (is (= "padded" (get-in (visits/submission (form "reason" "  padded  \r\n")) [:body :description])))))

(deftest what-cannot-be-read-is-said-beside-its-field-and-nothing-is-asked-of-the-api
  (is (= {:errors {:reason visits/blank-reason}} (visits/submission (form "reason" ""))))
  (is (= {:errors {:from "Enter a date."}} (visits/submission (form "from" ""))))
  (is (= {:errors {:to "Enter a date."}} (visits/submission (form "to" "soon"))))
  (is (= {:errors {:reason visits/blank-reason :from "Enter a date." :to "Enter a date."}} (visits/submission {})) "a form that is empty")
  (testing "a reason that is blank is none, whatever blank is"
    (doseq [reason [nil "" " " "\r\n" "\t \n"]]
      (is (= {:errors {:reason "Write a few words about it."}} (visits/submission (form "reason" reason))) (pr-str reason))))
  (testing "a day that is not one"
    (doseq [text [nil "" "2026-02-30" "20/10/2026" "2026-10-20T10:00" ["2026-10-20" "2026-10-21"] 5]]
      (is (= {:errors {:from "Enter a date."}} (visits/submission (form "from" text))) (pr-str text))
      (is (= {:errors {:to "Enter a date."}} (visits/submission (form "to" text))) (pr-str text))))
  (testing "a parameter that comes twice is not text"
    (is (= {:errors {:reason visits/blank-reason}} (visits/submission (form "reason" ["a" "b"]))))))

(deftest what-the-api-would-refuse-is-for-the-api-to-say
  (testing "a last day before the first: the api says so, and its words are put beside the field"
    (let [{:keys [body errors]} (visits/submission (form "from" "2026-10-20" "to" "2026-10-19"))
          refused (lifecycle/request-errors body now)]
      (is (nil? errors) "the portal reads what was typed, and does not judge it")
      (is (= {:requestedEnd ["must be after the start"]} refused))
      (is (= {:to "Must be after the start."} (visits/refusal refused)))))
  (testing "a window that is too long"
    (let [refused (lifecycle/request-errors (:body (visits/submission (form "from" "2026-10-14" "to" "2026-11-30"))) now)]
      (is (= {:to "Must be at most 30 days after the start."} (visits/refusal refused)))))
  (testing "a first day too far ahead"
    (let [refused (lifecycle/request-errors (:body (visits/submission (form "from" "2027-06-01" "to" "2027-06-01"))) now)]
      (is (= {:from "Must be within 180 days from now."} (visits/refusal refused)))))
  (testing "a window that is over"
    (let [refused (lifecycle/request-errors (:body (visits/submission (form "from" "2026-09-01" "to" "2026-09-10"))) now)]
      (is (= {:to "Must be in the future."} (visits/refusal refused)))))
  (testing "a reason that is too long"
    (let [refused (lifecycle/request-errors (:body (visits/submission (form "reason" (apply str (repeat 201 "x"))))) now)]
      (is (= {:reason "Must be 1 to 200 characters."} (visits/refusal refused))))))

(deftest what-the-api-says-of-the-fields-of-the-request-is-placed-beside-the-fields-of-the-form
  (is (= {:reason "A." :from "B." :to "C."}
         (visits/refusal {:description ["a"] :requestedStart ["b"] :requestedEnd ["c"]})))
  (testing "and what it says of anything else is left out"
    (is (= {} (visits/refusal {:patient ["x"] :status ["y"]})))
    (is (= {} (visits/refusal nil)))
    (is (= {} (visits/refusal "must be")))))

;; ---------------------------------------------------------------------------
;; The days of the form

(deftest the-form-is-first-shown-with-today-and-a-week-after-it
  (is (= {:reason "" :from "2026-10-13" :to "2026-10-20"} (visits/fresh now)))
  (testing "today is the day on the clinic's clock: at night in UTC it is already tomorrow in Madrid"
    (is (= {:reason "" :from "2026-10-14" :to "2026-10-21"} (visits/fresh "2026-10-13T22:30:00Z")))
    (is (= {:reason "" :from "2026-10-13" :to "2026-10-20"} (visits/fresh "2026-10-13T21:59:59Z")) "the clinic is two hours ahead in October"))
  (testing "a week is the days there are, whatever month"
    (is (= "2026-11-03" (:to (visits/fresh "2026-10-27T12:00:00Z"))))
    (is (= "2027-01-03" (:to (visits/fresh "2026-12-27T12:00:00Z"))))))

(deftest the-fields-offer-the-days-the-api-allows
  (is (= {:from {:min "2026-10-13" :max "2027-04-11"} :to {:min "2026-10-13" :max "2027-05-10"}} (visits/bounds now))
      "from today to 180 days ahead, and a window of 30 days from the last of them")
  (is (= "2026-10-14" (get-in (visits/bounds "2026-10-13T22:30:00Z") [:from :min])) "today on the clinic's clock")
  (testing "what is offered is what the api takes"
    (let [{:keys [from to]} (visits/bounds now)
          widest (visits/submission (form "from" (:max from) "to" (:max to)))]
      (is (nil? (lifecycle/request-errors (:body widest) now)))
      (is (some? (lifecycle/request-errors (:body (visits/submission (form "from" (str (.plusDays (date (:max from)) 1)) "to" (:max to)))) now))
          "and the next day is not")
      (is (some? (lifecycle/request-errors (:body (visits/submission (form "from" (:max from) "to" (str (.plusDays (date (:max to)) 1))))) now))
          "neither"))))

;; ---------------------------------------------------------------------------
;; Cancelling

(deftest a-cancellation-has-a-reason-when-the-patient-gave-one
  (is (= {:action "cancel"} (visits/cancellation {})))
  (is (= {:action "cancel" :reason "I had to travel"} (visits/cancellation {"why" "I had to travel"})))
  (is (= {:action "cancel" :reason "I had\nto travel"} (visits/cancellation {"why" "  I had\r\nto travel \n"})))
  (testing "a reason that is blank is none: the api would refuse it"
    (doseq [why [nil "" " " "\r\n" 5 ["a" "b"] :a]]
      (is (= {:action "cancel"} (visits/cancellation {"why" why})) (pr-str why))))
  (testing "what the form holds in the field for why"
    (is (= "I had to travel" (visits/why-typed {"why" "I had to travel"})))
    (is (= "  " (visits/why-typed {"why" "  "})) "as it was typed")
    (is (nil? (visits/why-typed {})))
    (is (nil? (visits/why-typed {"why" ["a" "b"]})))))

(deftest what-the-form-sends-to-cancel-is-what-the-api-takes
  (doseq [fields [{} {"why" "I had to travel"} {"why" (apply str (repeat 200 "x"))}]
          :let [body (visits/cancellation fields)]]
    (is (m/validate api-appointments/change-body body) (pr-str fields))
    (is (contains? (lifecycle/change {:status "booked" :start "2026-10-14T08:00:00Z"} (update body :action keyword) now :patient) :ok)
        "and the lifecycle lets a patient do it")))

(deftest what-the-api-refuses-of-a-cancellation-is-said-beside-the-field-for-why
  (let [body (visits/cancellation {"why" (apply str (repeat 201 "x"))})
        {:keys [errors]} (lifecycle/change {:status "booked" :start "2026-10-14T08:00:00Z"} (update body :action keyword) now :patient)]
    (is (= {:reason ["must be 1 to 200 characters"]} errors))
    (is (= {:why "Must be 1 to 200 characters."} (visits/cancellation-refusal errors))))
  (is (= {} (visits/cancellation-refusal {:action ["x"] :start ["y"]})) "the rest is not for this field")
  (is (= {} (visits/cancellation-refusal nil)))
  (is (str/starts-with? (:why (visits/cancellation-refusal {:reason ["no way"]})) "No way")))
