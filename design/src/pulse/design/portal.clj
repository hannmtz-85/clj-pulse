(ns pulse.design.portal
  "The mock-ups of the patient portal (ADR-0026), the interface of `web`: what a patient sees of their own record, on
  a phone and at a desk. The patient is Ana García, who has hypertension and whose last readings are very high; Noah
  Kim, who has no readings yet, is the one of the empty state. Each page is a function from the context of the build
  to the document of its frame."
  (:require [pulse.design.data :as data]
            [pulse.design.mockup :as mockup]
            [pulse.fhir.measurement :as measurement]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]
            [pulse.view.format :as fmt]
            [pulse.web.pages :as pages]
            [pulse.web.pages.appointments :as appointments-page]
            [pulse.web.pages.home :as home-page]
            [pulse.web.pages.parts :as parts]
            [pulse.web.pages.profile :as profile-page]
            [pulse.web.pages.reading-form :as reading-form]
            [pulse.web.pages.readings :as readings-page]
            [pulse.web.pages.record :as record-page]
            [pulse.web.recording :as recording]
            [pulse.view.views :as web-views])
  (:import (java.time Duration)))

(defn- link [file] (mockup/href file))

(def ^:private nav
  [{:id :home :href (link "portal-home.html") :label "Home" :icon :house}
   {:id :readings :href (link "portal-readings.html") :label "Readings" :icon :activity}
   {:id :appointments :href (link "portal-appointments.html") :label "Appointments" :icon :calendar}
   {:id :record :href (link "portal-record.html") :label "My record" :icon :clipboard-list}
   {:id :profile :href (link "portal-profile.html") :label "Profile" :icon :user}])

(defn- menu-of [patient]
  {:name (:name patient)
   :items [{:href (link "portal-profile.html") :label "Profile" :icon :user}
           {:href (link "login.html") :label "Sign out" :icon :log-out}]})

(defn- page
  "The frame of a page of the portal: the shell with the navigation, and the content in it. `:patient` is the person
  who is signed in (Ana by default)."
  [{:keys [title current patient]} & content]
  (mockup/frame-document
   {:title title}
   (apply layout/portal-shell {:current current :nav nav :user (menu-of (or patient (data/patient "pt-ana")))} content)))

(def links
  "Where the buttons of the pages lead, for the pages of the portal that have them (see `pulse.web.pages.parts`): the mock-ups
  have every page, so every button is there. The portal leaves out the ones whose pages it does not have yet."
  {:new-reading (link "portal-new-reading.html")
   :readings (link "portal-readings.html")
   :record (link "portal-record.html")
   :appointments (link "portal-appointments.html")
   :request-visit (link "portal-appointments.html#ask")
   :retry (link "portal-home.html")
   :cancel-appointment (constantly (link "portal-cancel.html"))
   :cancel-request (constantly (link "portal-cancel-request.html"))})

;; ---------------------------------------------------------------------------
;; Home

(defn home-model
  "What the home is a function of for `patient-id`: what the api answers, from the cohort, at the moment of the mock-ups."
  [patient-id]
  (let [summary (data/summary patient-id)]
    {:now data/now-text
     :me (data/me patient-id)
     :summary summary
     :recent (into [] (mapcat #(:items (data/series patient-id % home-page/recent-count))) (web-views/measured-kinds summary))}))

(defn home
  "The home of a patient with readings: a greeting, what to know now, the next appointment and the latest readings. The page
  is the one the portal serves (`pulse.web.pages.home`), made from the cohort."
  [_context]
  (page {:title "Home" :current :home}
        (home-page/content (home-model "pt-ana") links)))

;; ---------------------------------------------------------------------------
;; Readings

(defn readings-model
  "What the page of the readings is a function of for `patient-id`, the measurement `kind` and a period of `days`: what the api answers,
  from the cohort, at the moment of the mock-ups."
  [patient-id kind days]
  {:now data/now-text
   :kind kind
   :days days
   :summary (data/summary patient-id)
   :series (data/series patient-id kind {:from (str (.minus data/now (Duration/ofDays days))) :limit readings-page/series-limit})})

(defn readings
  "The trend of one measurement of a patient: the chart, drawn for the width of the screen, the figures and the table. The page is
  the one the portal serves (`pulse.web.pages.readings`), made from the cohort."
  [_context]
  (page {:title "Readings" :current :readings}
        (readings-page/content (readings-model "pt-ana" :blood-pressure 30) links)))

(defn new-reading-model
  "What the form to record a reading is a function of when the api has refused one: a diastolic that cannot be a pressure, with the
  words the portal writes beside the field for what the api says of it."
  []
  (let [refused (:errors (measurement/normalize {:measurement :blood-pressure :systolic 132 :diastolic 310}))]
    {:kind :blood-pressure
     :values {:systolic "132" :diastolic "310" :taken (fmt/local-input data/now-text)}
     :errors (recording/refusal :blood-pressure refused)
     :failure :refused
     :now data/now-text
     :csrf-token "the-token-of-the-session"}))

(defn new-reading
  "The form to record a reading, as it looks when the api has refused one. The page is the one the portal serves
  (`pulse.web.pages.reading-form`)."
  [_context]
  (page {:title "Record a reading" :current :readings}
        (reading-form/content (new-reading-model) links)))

;; ---------------------------------------------------------------------------
;; Appointments

(defn appointments-model
  "What the page of the appointments is a function of for `patient-id`: what the api answers, from the cohort, at the moment of the
  mock-ups (the newest first, as the portal asks for them)."
  [patient-id]
  {:now data/now-text
   :me (data/me patient-id)
   :appointments (data/appointment-list patient-id {:sort "-start" :limit appointments-page/list-limit})
   :csrf-token "the-token-of-the-session"})

(defn appointments
  "The appointments of a patient: the one that is confirmed, the request that has not been answered, the one that was cancelled, and the
  form to ask for another. The page is the one the portal serves (`pulse.web.pages.appointments`), made from the cohort."
  [_context]
  (page {:title "Appointments" :current :appointments}
        (appointments-page/content (appointments-model "pt-ana") links)))

(defn cancel-model
  "What the question before an appointment is cancelled is a function of: the page of the appointments of `patient-id` and the first
  of theirs in `status`, `booked` or `proposed`."
  [patient-id status]
  (let [model (appointments-model patient-id)]
    (assoc model :target (first (filter #(= status (:status %)) (sort-by :id (get-in model [:appointments :items])))))))

(defn cancel
  "The appointments with the question before one is cancelled: what is cancelled, who is told, and a way back. The page is the one the
  portal serves (`pulse.web.pages.appointments`), made from the cohort."
  [_context]
  (page {:title "Cancel appointment" :current :appointments}
        (appointments-page/cancel-content (cancel-model "pt-ana" "booked") links)))

(defn cancel-request
  "The same question for a request that the clinic has not answered."
  [_context]
  (page {:title "Cancel request" :current :appointments}
        (appointments-page/cancel-content (cancel-model "pt-ana" "proposed") links)))

;; ---------------------------------------------------------------------------
;; The record and the profile

(defn record-model
  "What the page of the record is a function of for `patient-id`: what the api answers, from the cohort."
  [patient-id]
  {:summary (data/summary patient-id)})

(defn record
  "What the clinic has on file about a patient: their medication, their conditions and their allergies. The page is the one the
  portal serves (`pulse.web.pages.record`), made from the cohort."
  [_context]
  (page {:title "My record" :current :record}
        (record-page/content (record-model "pt-ana") links)))

(defn profile-model
  "What the page of the profile is a function of for `patient-id`: what the api answers, from the cohort."
  [patient-id]
  {:me (data/me patient-id)
   :csrf-token "the-token-of-the-session"})

(defn profile
  "The profile of a patient: who they are to the clinic, their doctor, and the way out. The page is the one the portal serves
  (`pulse.web.pages.profile`), made from the cohort; in the review, the way out leads to the page of signing in."
  [_context]
  (page {:title "Profile" :current :profile}
        (profile-page/content (profile-model "pt-ana") (assoc links :sign-out (link "login.html")))))

;; ---------------------------------------------------------------------------
;; A new patient, and what goes wrong

(defn new-patient
  "The home of a patient who has no readings yet: what to do first, and the request that is waiting. It is the home that the
  portal serves (`pulse.web.pages.home`), made from the cohort: it is the same function, with a patient with nothing yet."
  [_context]
  (page {:title "Home, new patient" :current :home :patient (data/patient "pt-noah")}
        (home-page/content (home-model "pt-noah") links)))

(defn errors
  "What the portal says when something is wrong: part of a page that could not be loaded, a page that is not there,
  one the patient may not see, a failure on our side and a session that ended."
  [_context]
  (page {:title "When something goes wrong" :current :home}
        [:div {:class "space-y-8"}
         (c/page-header {:title "When something goes wrong"
                         :description "The messages of the portal: what happened and what to do."})
         [:section {:aria-labelledby "partial"}
          [:div {:class "mb-3"} (parts/section-heading "partial" "Part of a page could not be loaded")]
          [:div {:class "space-y-4"}
           (parts/not-loaded "Your appointments could not be loaded" {:retry "#"})
           (parts/readings-grid (data/vitals "pt-ana") data/now-text)]]
         [:section {:aria-labelledby "stopped"}
          [:div {:class "mb-3"} (parts/section-heading "stopped" "A whole page")]
          ;; the cards are those of the portal itself: what is reviewed here is what is served (ADR-0028)
          (let [links {:home-href (link "portal-home.html") :retry-href "#" :sign-in-href (link "login.html")
                       :sign-out-href (link "login.html")}]
            [:div {:class "grid gap-4 md:grid-cols-2"}
             (for [kind (keys pages/error-kinds)]
               (pages/error-card kind links))])]]))
