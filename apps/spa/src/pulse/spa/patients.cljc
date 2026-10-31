(ns pulse.spa.patients
  "How a page of the workspace names a patient from what the api says of them (ADR-0029): the alerts and the appointments name their patient
  by a reference (`Patient/pt-ana`), and the names come from the list of patients that the page asks for as well. The inbox of alerts and the
  list of appointments say the same thing in the same words, so it is said here once. Pure and `.cljc`.

  **A patient the list does not hold is named by their number** (`Patient pt-ana`): the list is the first hundred of the caller's scope, and
  an alert or an appointment must not lose its patient for being the hundred and first. Their chart is still one click away."
  (:require [pulse.spa.panel :as panel]
            [pulse.view.views :as views]))

(defn id-of
  "The id in a reference to a patient, such as `Patient/pt-ana`, or nil for anything else. An id of dots only (`.` and `..` are ids by the
  letter of FHIR) is nil too: no path can carry it, and the route of the chart does not open it."
  [reference]
  (when (string? reference)
    (let [id (second (re-matches #"Patient/([A-Za-z0-9\-.]{1,64})" reference))]
      (when-not (contains? #{"." ".."} id) id))))

(defn names
  "The names of the patients of a part that holds the list the api answered (`{:state :loaded :data {:items ...}}`), by id: the name the
  clinic has, or `Not named`. Empty while the list is not loaded."
  [part]
  (if (= :loaded (:state part))
    (into {} (map (fn [patient] [(:id patient) (or (views/text-of (:name patient)) "Not named")]))
          (panel/patients (:data part)))
    {}))

(defn given-names
  "The given name of each patient of the same part, by id, for the patients that have one: `Ana`. Empty while the list is not loaded."
  [part]
  (if (= :loaded (:state part))
    (into {} (keep (fn [patient]
                     (when-let [given (views/text-of (first (when (sequential? (:given patient)) (:given patient))))]
                       [(:id patient) given])))
          (panel/patients (:data part)))
    {}))

(defn name-of
  "The name a page gives the patient `id`, given the `names` of the list: the one the clinic has, or, for one it was not told, their
  number. Nil for no patient."
  [names id]
  (cond
    (nil? id) nil
    (contains? names id) (get names id)
    :else (str "Patient " id)))
