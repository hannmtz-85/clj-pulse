(ns pulse.fhir.coding
  "Helpers for Coding and CodeableConcept maps."
  (:require [pulse.fhir.terminology :as t]))

(defn has-code?
  "True when CodeableConcept `cc` carries `code` in `system` (a short name such as :loinc
  or a URI)."
  [cc system code]
  (let [uri (t/system-uri system)]
    (boolean
     (some (fn [c] (and (= uri (:system c)) (= code (:code c))))
           (:coding cc)))))

(defn code-in
  "The first code of CodeableConcept `cc` in `system`, or nil."
  [cc system]
  (let [uri (t/system-uri system)]
    (some (fn [c] (when (= uri (:system c)) (:code c)))
          (:coding cc))))

(defn display
  "A human-readable text for a CodeableConcept: its `:text`, else the first coding display."
  [cc]
  (or (:text cc) (some :display (:coding cc))))
