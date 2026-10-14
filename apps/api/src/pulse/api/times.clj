(ns pulse.api.times
  "Times as the api's requests carry them: how they are read, and the malli schemas of the fields
  that hold them. FHIR has no use for a year of more than four digits, nor has a clinic for one
  before 1900, so what lies outside is a mistake and is refused here instead of being sent on."
  (:import (java.time DateTimeException Instant LocalDate OffsetDateTime ZoneOffset)))

(def ^:private date-re #"\d{4}-\d{2}-\d{2}")
(def ^:private instant-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})")

(defn parse-instant
  "The Instant of `s`, a date and time with its offset such as 2026-10-13T08:00:00Z, or nil when
  it is not one. Only years of four digits, which is what FHIR has."
  [s]
  (when (re-matches instant-re s)
    (try (.toInstant (OffsetDateTime/parse s))
         (catch DateTimeException _ nil))))

(defn- parse-date
  "The Instant at midnight UTC of the date `s`, such as 2026-10-13, or nil."
  [s]
  (when (re-matches date-re s)
    (try (.toInstant (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC))
         (catch DateTimeException _ nil))))

(def ^:private earliest (Instant/parse "1900-01-01T00:00:00Z"))
(def ^:private latest (Instant/parse "2200-01-01T00:00:00Z"))

(defn parse-bound
  "The Instant of `s`, an end of a period asked for: a date (midnight UTC) or an instant with its
  offset, in the years 1900 to 2199. Nil when it is not one: what lies outside is a mistake, and
  FHIR could not be asked about it."
  [s]
  (when-let [^Instant i (or (parse-instant s) (parse-date s))]
    (when (and (.isAfter i earliest) (.isBefore i latest))
      i)))

(def instant
  "The schema of a field that holds a time: a date and time with its offset."
  [:and
   [:string {:max 40}]
   [:fn {:error/message "must be a date and time with its offset, such as 2026-10-13T08:00:00Z"}
    (fn [s] (some? (parse-instant s)))]])

(def bound
  "The schema of a field that holds an end of a period: a date or an instant."
  [:and
   [:string {:max 40}]
   [:fn {:error/message "must be a date (2026-10-01) or an instant with its offset (2026-10-01T08:00:00Z), from 1900 to 2199"}
    (fn [s] (some? (parse-bound s)))]])
