(ns pulse.web.pages.profile
  "The profile of the portal (ADR-0028): who the patient is to the clinic, their doctor and the way out. It is a function of what
  `GET /api/me` answers, which holds the patient's own resource and their general practitioner, and the mock-ups of the review give it the
  synthetic cohort, so that what is reviewed is what is served. Pure hiccup, free of Ring.

  The `model` is

    `:me`          what `GET /api/me` answered: `:resource`, the Patient as the clinic has it, and `:generalPractitioner`
    `:csrf-token`  the CSRF token of the person's session, which the form that signs them out carries

  and `links` says where the buttons lead (see `pulse.web.pages.parts`): `:retry`, and `:sign-out`, where the form that signs the person out
  posts (`/sign-out` unless it says another: the review of the mock-ups leads it to the page of signing in).

  There is nothing to edit here and the page says so: the api has no way for a patient to change what the clinic holds (ADR-0026), so
  there is no form that looks as if it had. The page says only what the clinic holds: a detail that is not there, or cannot be read, is
  left out, since other systems write to the same FHIR server."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.view.format :as fmt]
            [pulse.web.pages :as pages]
            [pulse.web.pages.parts :as parts]
            [pulse.view.views :as views])
  (:import (java.time.format DateTimeParseException)))

;; ---------------------------------------------------------------------------
;; What the clinic holds

(defn full-name
  "The name of the patient as the clinic writes it, such as `Ana García`: the given names and the family name of the first name of the
  `resource`, or nil when it has none."
  [resource]
  (let [{:keys [given family]} (get-in resource [:name 0])
        parts (concat (when (sequential? given) (keep views/text-of given)) (keep views/text-of [family]))]
    (when (seq parts) (str/join " " parts))))

(defn email
  "The email address of the patient: the first contact point of the `resource` that is an email, or nil."
  [resource]
  (let [points (:telecom resource)]
    (when (sequential? points)
      (some #(when (= "email" (:system %)) (views/text-of (:value %))) points))))

(defn birth-date
  "The date of birth as the page writes it, such as `14 March 1968`, or nil when the `resource` has none or it cannot be read."
  [resource]
  (when-let [text (views/text-of (:birthDate resource))]
    (try (fmt/birth-date text)
         (catch DateTimeParseException _ nil))))

(defn facts
  "The details of the patient that the `resource` has, as `[term detail]` pairs in the order of the page: the date of birth, the patient
  number (the first identifier) and the city (of the first address)."
  [resource]
  (let [number (views/text-of (get-in resource [:identifier 0 :value]))
        city (views/text-of (get-in resource [:address 0 :city]))
        born (birth-date resource)]
    (cond-> []
      born (conj ["Date of birth" born])
      number (conj ["Patient number" number])
      city (conj ["City" city]))))

;; ---------------------------------------------------------------------------
;; The cards

(defn- who-card
  "Who the patient is: their name and email, and what else the clinic holds. A resource that is not there is a message in its place."
  [resource links]
  (if-not (map? resource)
    (parts/not-loaded "Your details could not be loaded" links)
    (let [name (full-name resource)
          mail (email resource)
          pairs (facts resource)]
      (c/card {:title "Who you are"}
              (when (or name mail)
                [:div {:class "mb-5 flex items-center gap-4"}
                 (when name (c/avatar {:name name :size :lg}))
                 [:div {:class "min-w-0"}
                  (when name [:p {:class "text-lg font-semibold wrap-anywhere"} name])
                  (when mail [:p {:class "text-muted wrap-anywhere"} mail])]])
              (if (seq pairs)
                (c/facts pairs)
                (when-not (or name mail)
                  [:p {:class "text-sm text-muted"} "Your clinic has no details of you on file. Tell them."]))))))

(defn- care-team-card
  "The doctor of the patient, whom every request for a visit goes to; or that the portal does not know who it is."
  [me]
  (let [{:keys [name specialty]} (:generalPractitioner me)]
    (c/card {:title "Your care team"}
            (if-let [doctor (views/text-of name)]
              (list (c/person {:name doctor :detail (views/text-of specialty) :size :lg})
                    [:p {:class "mt-4 text-sm text-muted"} "Requests for a visit go to your doctor."])
              [:p {:class "text-sm text-muted"} "We do not know who your doctor is. Tell your clinic."]))))

;; ---------------------------------------------------------------------------
;; The page

(defn content
  "The profile: who the patient is, their doctor and the button that signs them out. `model` and `links` are described at the top of this
  namespace."
  [{:keys [me csrf-token]} links]
  [:div {:class "space-y-6"}
   (c/page-header {:title "Profile" :description "What the clinic knows about you. To change it, tell them."})
   (who-card (:resource me) links)
   (care-team-card me)
   [:div (pages/sign-out-form csrf-token (:sign-out links "/sign-out"))]])
