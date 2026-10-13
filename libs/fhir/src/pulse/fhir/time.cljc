(ns pulse.fhir.time
  "Parsing FHIR date/dateTime/instant strings to epoch milliseconds, portable between the
  JVM and the browser."
  #?(:clj (:import (java.time LocalDate OffsetDateTime ZoneOffset))))

(defn ->millis
  "Epoch milliseconds of a FHIR date (\"2026-10-13\"), dateTime or instant
  (\"2026-10-13T08:00:00Z\", offsets allowed). A date alone means 00:00 UTC."
  [s]
  #?(:clj (if (re-find #"T" s)
            (.toEpochMilli (.toInstant (OffsetDateTime/parse s)))
            (.toEpochMilli (.toInstant (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC))))
     :cljs (js/Date.parse s)))
