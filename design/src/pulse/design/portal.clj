(ns pulse.design.portal
  "The mock-ups of the patient portal (ADR-0026), the interface of `web`: what a patient sees of their own record, on
  a phone and at a desk. The patient is Ana García, who has hypertension and whose last readings are very high; Noah
  Kim, who has no readings yet, is the one of the empty state. Each page is a function from the context of the build
  to the document of its frame."
  (:require [clojure.string :as str]
            [pulse.design.data :as data]
            [pulse.design.mockup :as mockup]
            [pulse.design.specimen :as specimen]
            [pulse.design.views :as views]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]
            [pulse.web.format :as fmt]
            [pulse.web.pages :as pages]
            [pulse.web.pages.home :as home-page]
            [pulse.web.pages.parts :as parts]
            [pulse.web.views :as web-views]))

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

(defn- phone-only [content] [:div {:class "md:hidden"} content])

(defn- from-tablet [content] [:div {:class "hidden md:block"} content])

(def links
  "Where the buttons of the pages lead, for the pages of the portal that have them (see `pulse.web.pages.parts`): the mock-ups
  have every page, so every button is there. The portal leaves out the ones whose pages it does not have yet."
  {:new-reading (link "portal-new-reading.html")
   :readings (link "portal-readings.html")
   :record (link "portal-record.html")
   :request-visit (link "portal-appointments.html#ask")
   :retry (link "portal-home.html")
   :cancel-appointment (constantly (link "portal-cancel.html"))
   :cancel-request (constantly (link "portal-appointments.html"))})

(defn- appointment-item [row] (parts/appointment-item row links data/now-text))

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

(defn- stats-row [patient-id kind days]
  (let [readings (specimen/within (data/readings patient-id kind) days)
        key-of #(or (:systolic %) (:value %))
        latest (last readings)
        highest (apply max-key key-of readings)
        lowest (apply min-key key-of readings)
        text (fn [reading] (str (web-views/reading-text kind reading) " " (fmt/unit (:unit reading))))]
    [:div {:class "grid gap-4 sm:grid-cols-3"}
     (c/stat {:label "Latest" :value (text latest) :hint (fmt/when-text (:at latest) data/now-text)})
     (c/stat {:label (str "Highest, " days " days") :value (text highest) :hint (fmt/when-text (:at highest) data/now-text)})
     (c/stat {:label (str "Lowest, " days " days") :value (text lowest) :hint (fmt/when-text (:at lowest) data/now-text)})]))

(defn- readings-table [patient-id kind n]
  (let [label (get-in web-views/kind-info [kind :label])]
    (c/data-table
     {:caption (str label ", the last " n " readings")
      :dense? true
      :columns [{:key :at :label "When" :header? true :class "whitespace-nowrap"}
                {:key :value :label "Reading" :align :end :class "whitespace-nowrap"}
                {:key :band :label (list [:span {:class "hidden sm:inline"} "Compared with the range"] [:span {:class "sm:hidden"} "Range"])}]
      :rows (for [reading (reverse (take-last n (data/readings patient-id kind)))]
              {:at (fmt/when-text (:at reading) data/now-text)
               :value (str (web-views/reading-text kind reading) " " (fmt/unit (:unit reading)))
               :band (or (c/band-badge (:band reading) {:size :sm}) "–")})})))

(defn readings
  "The trend of one measurement of a patient: the chart, drawn for the width of the screen, the figures and the table."
  [_context]
  (page {:title "Readings" :current :readings}
        [:div {:class "space-y-6"}
         (c/page-header {:title "My readings"
                         :description "What you and your devices have recorded."
                         :actions (c/button {:size :lg :icon :plus :href (link "portal-new-reading.html")} "Record a reading")})
         [:div {:class "flex flex-wrap items-center justify-between gap-x-6 gap-y-3"}
          (c/pill-nav {:label "Measurement" :size :lg
                       :items [{:href "#" :label "Blood pressure" :current? true}
                               {:href "#" :label "Heart rate"}
                               {:href "#" :label "Weight"}]})
          (c/pill-nav {:label "Period" :size :lg
                       :items [{:href "#" :label "7 days"} {:href "#" :label "14 days"} {:href "#" :label "30 days" :current? true}]})]
         (c/card {:title "Blood pressure" :subtitle "Last 30 days"}
                 (from-tablet (specimen/line-chart "pt-ana" :blood-pressure {:days 30 :width 950 :height 320 :ticks-every 5 :id "bp-wide"}))
                 (phone-only (specimen/line-chart "pt-ana" :blood-pressure {:days 30 :width 318 :height 260 :ticks-every 10 :id "bp-narrow"
                                                                            :thresholds? false :bands? false})))
         (stats-row "pt-ana" :blood-pressure 30)
         [:section {:aria-labelledby "last"}
          [:div {:class "mb-3"} (parts/section-heading "last" "The last readings")]
          (readings-table "pt-ana" :blood-pressure 8)]]))

(defn new-reading
  "The form to record a reading, as it looks when the api has refused one: a diastolic that cannot be a pressure."
  [_context]
  (page {:title "Record a reading" :current :readings}
        [:div {:class "space-y-6"}
         (c/page-header {:title "Record a reading" :description "Your care team can see it as soon as you save it."})
         (c/card
          [:form {:class "flex max-w-xl flex-col gap-5" :action "#" :method "post"}
           (c/alert {:tone :high :title "The reading was not saved" :role "alert"}
                    "Check the diastolic pressure and save it again.")
           (c/select-field {:id "measurement" :name "measurement" :label "What did you measure?" :value "blood-pressure"
                            :options [["blood-pressure" "Blood pressure"] ["heart-rate" "Heart rate"] ["spo2" "Oxygen saturation"]
                                      ["glucose" "Glucose"] ["weight" "Weight"] ["temperature" "Temperature"]]})
           [:div {:class "grid gap-4 sm:grid-cols-2"}
            (c/text-field {:id "systolic" :name "systolic" :label "Systolic (mmHg)" :type "number" :inputmode "numeric" :value "132"
                           :hint "The higher number."})
            (c/text-field {:id "diastolic" :name "diastolic" :label "Diastolic (mmHg)" :type "number" :inputmode "numeric" :value "310"
                           :hint "The lower number." :error "Enter a number between 20 and 200 mmHg."})]
           (c/text-field {:id "taken" :name "taken" :label "Taken at" :type "datetime-local" :value "2026-10-13T14:00"
                          :hint "Now, or up to 30 days ago."})
           [:div {:class "flex flex-wrap gap-3"}
            (c/button {:type "submit" :size :lg :icon :send} "Save the reading")
            (c/button {:variant :ghost :size :lg :href (link "portal-readings.html")} "Cancel")]])]))

;; ---------------------------------------------------------------------------
;; Appointments

(defn- request-form
  "The patient's request: what it is about and the days that would suit, which go to their general practitioner."
  []
  (let [doctor (:name (data/practitioner (:practitioner (data/patient "pt-ana"))))]
    (c/card {:title "Ask for a visit" :subtitle (str "Your request goes to " doctor ", who will confirm a time.")}
            [:form {:class "flex max-w-xl flex-col gap-5" :action "#" :method "post"}
             (c/textarea-field {:id "reason" :name "reason" :label "What is it about?" :rows 3
                                :hint "A few words are enough, up to 200 characters."})
             [:fieldset {:class "grid gap-4 sm:grid-cols-2"}
              [:legend {:class "mb-1 text-sm font-medium sm:col-span-2"} "When would suit you?"]
              (c/text-field {:id "from" :name "from" :label "From" :type "date" :value "2026-10-20"})
              (c/text-field {:id "to" :name "to" :label "Until" :type "date" :value "2026-10-27"
                             :hint "At most 30 days after the first day."})]
             [:div (c/button {:type "submit" :size :lg :icon :send} "Send the request")]])))

(defn- appointment-list [title rows]
  (c/card {:flush? true} (c/list-group {:aria-label title} (map appointment-item rows))))

(defn- appointments-content
  "The page of appointments. `:form?` and `:cancelled?` leave out the request form and the cancelled ones."
  [{:keys [form? cancelled?]}]
  (let [rows (views/appointments-of "pt-ana")
        coming (filter #(#{"booked" "proposed"} (:status %)) rows)
        cancelled (filter #(= "cancelled" (:status %)) rows)]
    [:div {:class "space-y-8"}
     (c/page-header {:title "Appointments" :description "Visits with your care team."
                     :actions (when form? (c/button {:size :lg :icon :calendar-plus :href "#ask"} "Ask for a visit"))})
     [:section {:aria-labelledby "coming"}
      [:div {:class "mb-3"} (parts/section-heading "coming" "Coming up")]
      (appointment-list "Appointments that are coming" coming)]
     (when cancelled?
       [:section {:aria-labelledby "cancelled"}
        [:div {:class "mb-3"} (parts/section-heading "cancelled" "Cancelled")]
        (appointment-list "Cancelled appointments" cancelled)])
     (when form?
       [:section {:id "ask" :aria-label "Ask for a visit"} (request-form)])]))

(defn appointments
  "The appointments of a patient: the one that is confirmed, the request that has not been answered, the one that was
  cancelled, and the form to ask for another."
  [_context]
  (page {:title "Appointments" :current :appointments}
        (appointments-content {:form? true :cancelled? true})))

(defn cancel
  "The appointments with the question before one is cancelled: what is cancelled, who is told, and a way back."
  [_context]
  (let [booked (first (filter #(= "booked" (:status %)) (views/appointments-of "pt-ana")))]
    (page {:title "Cancel appointment" :current :appointments}
          (appointments-content {})
          (c/dialog {:id "cancel-appointment" :title "Cancel this appointment?" :open? true
                     :actions (list (c/button {:variant :ghost :size :lg :href (link "portal-appointments.html")} "Keep it")
                                    (c/button {:variant :danger :size :lg :icon :calendar-x :href (link "portal-appointments.html")} "Cancel the appointment"))}
                    [:p {:class "text-base font-medium"} (:description booked)]
                    [:p {:class "text-base text-muted"} (str (:when booked) " · " (:doctor booked))]
                    [:p {:class "mt-3 text-base"} "Your care team will see it. You can ask for another visit at any time."]
                    [:div {:class "mt-4"}
                     (c/textarea-field {:id "why" :name "why" :label "Why? It helps your care team" :optional? true :rows 2
                                        :hint "Up to 200 characters."})]))))

;; ---------------------------------------------------------------------------
;; The record and the profile

(defn- record-card [title rows empty-text]
  (c/card {:title title :flush? true}
          (if (seq rows)
            (c/list-group {:aria-label title} rows)
            [:p {:class "px-5 pb-5 text-base text-muted"} empty-text])))

(defn record
  "What the clinic has on file about a patient: their medication, their conditions and their allergies."
  [_context]
  (page {:title "My record" :current :record}
        [:div {:class "space-y-6"}
         (c/page-header {:title "My record" :description "What your clinic has on file. Tell them if something is wrong."})
         (record-card "Medication"
                      (for [{:keys [display dosage authoredOn]} (data/medications "pt-ana")]
                        (c/list-row {:leading (c/icon-disc {:icon :pill})
                                     :trailing (c/badge {:variant :outline} (str "Since " (fmt/month-year authoredOn)))}
                                    [:p {:class "font-medium"} (str/capitalize display)]
                                    [:p {:class "text-sm text-muted"} dosage]))
                      "No medication is prescribed.")
         (record-card "Conditions"
                      (for [{:keys [display onset]} (data/conditions "pt-ana")]
                        (c/list-row {:leading (c/icon-disc {:icon :stethoscope})
                                     :trailing (c/badge {:variant :outline} (str "Since " (fmt/month-year onset)))}
                                    [:p {:class "font-medium"} display]
                                    [:p {:class "text-sm text-muted"} "Active"]))
                      "No conditions are recorded.")
         (record-card "Allergies"
                      (for [{:keys [display reaction]} (data/allergies "pt-ana")]
                        (c/list-row {:leading (c/icon-disc {:icon :triangle-alert})}
                                    [:p {:class "font-medium"} display]
                                    [:p {:class "text-sm text-muted"} reaction]))
                      "No allergies are recorded. If you have one, tell your clinic.")]))

(defn profile
  "The profile of a patient: who they are to the clinic, their doctor, and the way out."
  [_context]
  (let [ana (data/patient "pt-ana")
        doctor (data/practitioner (:practitioner ana))]
    (page {:title "Profile" :current :profile}
          [:div {:class "space-y-6"}
           (c/page-header {:title "Profile" :description "What the clinic knows about you. To change it, tell them."})
           (c/card {:title "Who you are"}
                   [:div {:class "mb-5 flex items-center gap-4"}
                    (c/avatar {:name (:name ana) :size :lg})
                    [:div [:p {:class "text-lg font-semibold"} (:name ana)] [:p {:class "text-muted"} (:email ana)]]]
                   (c/facts [["Date of birth" (fmt/birth-date (:birthDate ana))]
                             ["Patient number" (:identifier ana)]
                             ["City" (:city ana)]]))
           (c/card {:title "Your care team"}
                   (c/person {:name (:name doctor) :detail (:specialty doctor) :size :lg})
                   [:p {:class "mt-4 text-sm text-muted"} "Pulse Demo Clinic. Requests for a visit go to your doctor."])
           [:div (c/button {:variant :outline :size :lg :icon :log-out :href (link "login.html")} "Sign out")]])))

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
