(ns pulse.web.pages.record
  "The record of the portal (ADR-0028): what the clinic has on file about a patient, their medication, their conditions and their
  allergies, as the summary of the api gives them. It is a function of the data in the shapes of the api, and the mock-ups of the review
  give it the synthetic cohort, so that what is reviewed is what is served. Pure hiccup, free of Ring.

  The `model` is

    `:summary`  what `GET /api/patients/{id}/summary` answered, or nil when it could not be had at all

  and `links` says where the buttons lead (see `pulse.web.pages.parts`): `:retry`.

  No code is shown: a code is for the clinic, and a patient reads the name. A list that the api could not load is a message in its
  place with a way to try again, and never an empty list that looks as if the patient had nothing (the summary names what it could
  not load, and a list it does not have is lost too). What the clinic wrote is shown as it wrote it, with a capital letter to begin;
  a date that cannot be read is no date, and the row leaves it out, since other systems write to the same FHIR server."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.view.format :as fmt]
            [pulse.web.pages.parts :as parts]
            [pulse.view.views :as views])
  (:import (java.time.format DateTimeParseException)))

;; ---------------------------------------------------------------------------
;; What a row says

(defn name-of
  "What a patient reads as the name of a medication, a condition or an allergy: the name the clinic wrote, with a capital letter to
  begin (`penicillin G` is `Penicillin G`), or `Not named` when the record does not name it."
  [display]
  (if-let [text (views/text-of display)]
    (str (str/upper-case (subs text 0 1)) (subs text 1))
    "Not named"))

(defn since
  "`Since Nov 2025`, from an instant or a date, or nil when `text` is neither."
  [text]
  (when (string? text)
    (try (str "Since " (fmt/month-year text))
         (catch DateTimeParseException _ nil))))

(defn- detail [text]
  (when-let [t (views/text-of text)] [:p {:class "text-sm text-muted"} t]))

(defn- since-badge [text]
  (when-let [s (since text)] (c/badge {:variant :outline} s)))

(defn- medication-row [{:keys [display dosage authoredOn]}]
  (c/list-row {:leading (c/icon-disc {:icon :pill})
               :trailing (since-badge authoredOn)}
              [:p {:class "font-medium"} (name-of display)]
              (detail dosage)))

(defn- condition-row [{:keys [display onset status]}]
  (c/list-row {:leading (c/icon-disc {:icon :stethoscope})
               :trailing (since-badge onset)}
              [:p {:class "font-medium"} (name-of display)]
              (detail (some-> (views/text-of status) str/capitalize))))

(defn- allergy-row [{:keys [display reaction]}]
  (c/list-row {:leading (c/icon-disc {:icon :triangle-alert})}
              [:p {:class "font-medium"} (name-of display)]
              (detail reaction)))

;; ---------------------------------------------------------------------------
;; The sections

(def ^:private sections
  "The lists of the record, in the order of the page: the key of the summary, the id of the section (and the name the summary gives it in
  `unavailable`), its title, what is said of a list that is lost, what it is made of and what is said of one with nothing in it."
  [{:key :medications :id "medications" :title "Medication" :lost "Your medication could not be loaded"
    :row medication-row :empty "No medication is prescribed."}
   {:key :conditions :id "conditions" :title "Conditions" :lost "Your conditions could not be loaded"
    :row condition-row :empty "No conditions are recorded."}
   {:key :allergies :id "allergies" :title "Allergies" :lost "Your allergies could not be loaded"
    :row allergy-row :empty "No allergies are recorded. If you have one, tell your clinic."}])

(defn- lost?
  "Whether the list of `section` could not be had: the summary says so, or it does not have it."
  [summary {:keys [key id]}]
  (or (boolean (some #{id} (:unavailable summary)))
      (not (sequential? (get summary key)))))

(defn- section-of [summary {:keys [key id title lost row empty]} rest-of-page? links]
  (cond
    (lost? summary {:key key :id id})
    [:section {:aria-labelledby id}
     [:div {:class "mb-3"} (parts/section-heading id title)]
     (parts/not-loaded lost links {:rest-of-page? rest-of-page?})]

    (seq (get summary key))
    (c/card {:title title :flush? true}
            (c/list-group {:aria-label title} (map row (get summary key))))

    :else
    (c/card {:title title :flush? true}
            [:p {:class "px-5 pb-5 text-base text-muted"} empty])))

;; ---------------------------------------------------------------------------
;; The page

(defn content
  "The record: the medication, the conditions and the allergies of the patient. `model` and `links` are described at the top of this
  namespace."
  [{:keys [summary]} links]
  (into [:div {:class "space-y-6"}
         (c/page-header {:title "My record" :description "What your clinic has on file. Tell them if something is wrong."})]
        (if (nil? summary)
          [(parts/not-loaded "Your record could not be loaded" links {:rest-of-page? false})]
          (let [rest-of-page? (not-every? #(lost? summary %) sections)]
            (map #(section-of summary % rest-of-page? links) sections)))))
