(ns pulse.web.recording
  "Recording a reading from the form of the portal (ADR-0028): what the patient typed, turned into what the api takes, and what the
  api refuses, turned into words beside the fields. Pure functions of text and of data in the shapes of the api, free of Ring and
  of the network, so that every way a form can be filled in is tried without a server. The page is
  `pulse.web.pages.reading-form` and the handlers are `pulse.web.readings`.

  The api decides what a reading may be (`pulse.fhir.measurement` for the numbers, `pulse.api.observations` for the time): the
  portal only turns text into numbers and into an instant, and says so beside the field when it cannot. A number out of its
  limits, a diastolic that is not below the systolic and a time that is in the future are for the api to refuse, and its words
  are what the person reads."
  (:require [clojure.string :as str]
            [pulse.fhir.measurement :as measurement]
            [pulse.view.format :as fmt]
            [pulse.web.refusal :as refusal]
            [pulse.view.views :as views])
  (:import (java.time Duration Instant LocalDateTime ZonedDateTime)
           (java.time.format DateTimeParseException)
           (java.time.temporal ChronoUnit)))

(def max-age-days
  "How long ago a reading may have been taken, as the form says it: the api refuses an older one (a mistyped year should not
  rewrite a chart)."
  30)

(def taken-name
  "What the field for the time is called in a sentence."
  "time it was taken")

;; ---------------------------------------------------------------------------
;; The fields of the form

(def ^:private channel-words
  "What the form says of the numbers of a blood pressure, which is two."
  {:systolic {:label "Systolic" :hint "The higher number." :name "systolic pressure"}
   :diastolic {:label "Diastolic" :hint "The lower number." :name "diastolic pressure"}})

(defn kind-of
  "The measurement that the field `text` of a form names (`\"heart-rate\"`), as a keyword, or nil when it is not one that can be
  recorded."
  [text]
  (first (filter #(= text (name %)) views/kinds)))

(defn fields
  "The numbers that a reading of `kind` is made of, in the order of the form. For each: `:channel` (`:systolic` and `:diastolic`,
  or `:value`; also the name of its field), `:label` with the unit, `:name` (what it is called in a sentence), `:step` and
  `:inputmode` (a whole number or a decimal one, as the api keeps the measurement) and `:hint` when it has one."
  [kind]
  (let [{:keys [channels decimals] stored :unit} (get measurement/measurements kind)
        unit (fmt/unit stored)
        label (get-in views/kind-info [kind :label])]
    (for [channel (keys channels)
          :let [words (get channel-words channel)]]
      (cond-> {:channel channel
               :label (str (or (:label words) label) " (" unit ")")
               :name (or (:name words) (str/lower-case label))
               :step (if (zero? decimals) "1" (str "0." (apply str (repeat (dec decimals) "0")) "1"))
               :inputmode (if (zero? decimals) "numeric" "decimal")}
        (:hint words) (assoc :hint (:hint words))))))

;; ---------------------------------------------------------------------------
;; What was typed

(def ^:private number-text
  "A number as a browser writes the value of a field for numbers: digits with a point, or a point and digits, and perhaps an
  exponent. No comma, no thousands, nothing else."
  #"-?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?")

(def ^:private max-number-length 32)

(defn parse-number
  "The number that `text` writes, or nil when it is not one (empty, not a number, or too large to be one). A whole number is a
  `long`, so that it is sent as `72` and not as `72.0`."
  [text]
  (when (string? text)
    (let [text (str/trim text)]
      (when (and (<= (count text) max-number-length) (re-matches number-text text))
        (let [x (Double/parseDouble text)]
          (when (Double/isFinite x)
            (if (and (== x (Math/rint x)) (< (Math/abs x) 1e15))
              (long x)
              x)))))))

(def ^:private local-date-time
  "A date and a time as a browser writes the value of a field for them: with no zone, to the minute or to the second."
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?")

(defn taken-at
  "The moment that the field `text` writes, a date and a time on the clinic's clock, as an instant in UTC written to the second
  (`2026-10-13T12:00:00Z`). Nil when the field is empty or is not there, which means now; `::invalid` when it is not a date and a
  time (or one that does not exist, such as the 30th of February), and when it is not text. A time that the clocks repeat when
  they go back is read as the first of the two, and one that they skip when they go forward as the same time after the gap (the
  rule of the JDK for a time with no zone)."
  [text]
  (cond
    (nil? text) nil
    (not (string? text)) ::invalid
    (str/blank? text) nil
    :else (let [text (str/trim text)]
            (if (re-matches local-date-time text)
              (try
                (let [^ZonedDateTime zoned (ZonedDateTime/ofLocal (LocalDateTime/parse text) fmt/zone nil)]
                  (str (.truncatedTo (.toInstant zoned) ChronoUnit/SECONDS)))
                (catch DateTimeParseException _ ::invalid))
              ::invalid))))

(defn earliest
  "The moment a reading may have been taken at the soonest, as seen from `now`, an instant written as text."
  [now]
  (str (.minus (Instant/parse now) (Duration/ofDays max-age-days))))

(defn typed
  "What `form`, the parameters of the form for `kind` (a map from the name of a field to what is in it), holds in the fields of the
  form, as text: a map from each field (the channels of `fields`, and `:taken`) to its text, nil for one that is not there or that is
  not text, as a parameter that comes twice is not. It is what the form is shown with again when the reading is not saved."
  [kind form]
  (into {}
        (for [field (concat (map :channel (fields kind)) [:taken])
              :let [text (get form (name field))]]
          [field (when (string? text) text)])))

(defn submission
  "What `form`, the parameters of the form for `kind`, says: `{:body ...}`, the reading as the api takes it, or
  `{:errors {field message}}` with the words beside each field that could not be read, which are the numbers that are not numbers
  and a time that is not a time."
  [kind form]
  (let [texts (typed kind form)
        numbers (for [{:keys [channel]} (fields kind)] [channel (parse-number (get texts channel))])
        at (taken-at (get form "taken"))
        errors (cond-> (into {} (keep (fn [[channel x]] (when (nil? x) [channel "Enter a number."]))) numbers)
                 (= ::invalid at) (assoc :taken "Enter a date and time."))]
    (if (seq errors)
      {:errors errors}
      {:body (cond-> (into {:measurement (name kind)} numbers)
               at (assoc :at at))})))

;; ---------------------------------------------------------------------------
;; What the api refused

(defn refusal
  "The words beside the fields for a reading of `kind` that the api refused (a 400 or a 422): `errors` is the `errors` of its problem,
  a map from a field of the api to its messages. A map from the fields of the form to a sentence, for those the form has (the `at`
  of the api is `:taken` here); what the api says of anything else, such as the unit, which the form never sends, is left out.
  Empty when there is nothing to place (see `pulse.web.refusal`)."
  [kind errors]
  (refusal/place (into {:at :taken} (map (fn [{:keys [channel]}] [channel channel])) (fields kind))
                 errors))
