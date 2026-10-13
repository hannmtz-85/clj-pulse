(ns pulse.fhir.reference
  "Helpers for FHIR relative references such as \"Patient/pt-ana\".
  Pure and portable: shared by the JVM apps and the ClojureScript SPA.")

(def ^:private reference-re
  ;; FHIR resource types start with an uppercase letter; ids are 1-64 chars of [A-Za-z0-9-.].
  #"([A-Z][A-Za-z]+)/([A-Za-z0-9\-.]{1,64})")

(defn parse
  "Parses a relative reference into {:type \"Patient\" :id \"pt-ana\"}.
  Returns nil when `s` is not a string or is not a relative reference."
  [s]
  (when (string? s)
    (when-let [[_ type id] (re-matches reference-re s)]
      {:type type :id id})))

(defn ->str
  "Inverse of `parse`: {:type \"Patient\" :id \"pt-ana\"} -> \"Patient/pt-ana\"."
  [{:keys [type id]}]
  (str type "/" id))

(defn reference
  "A FHIR Reference map pointing at `type`/`id`, optionally with a display text."
  ([type id] {:reference (str type "/" id)})
  ([type id display] (assoc (reference type id) :display display)))

(defn target
  "Parses the `:reference` of a Reference map: {:reference \"Patient/pt-ana\"} -> {:type .. :id ..}."
  [reference-map]
  (parse (:reference reference-map)))
