(ns pulse.web.pages.home
  "The home of the portal (ADR-0028): what a patient sees first. It is a function of the data in the shapes of the api, and
  the mock-ups of the review give it the synthetic cohort, so that what is reviewed is what is served. Pure hiccup, free of
  Ring.

  A page of the portal is `content` here, the part that goes in `main`, and the frame around it (the bar, the dock and the
  menu of the person) is made by the page that serves it. The `model` is

    `:now`      the instant it is, as text
    `:me`       what `GET /api/me` answered: the patient's own resource, for their name, and their general practitioner, for
                the name of the doctor beside an appointment
    `:summary`  what `GET /api/patients/{id}/summary` answered, or nil when it could not be had at all
    `:recent`   the readings of the series of the api (`GET /api/patients/{id}/observations`) that the list of recent readings
                is made of, one series for each measurement the patient has, or nil when they could not be had

  and `links` says where the buttons lead (see `pulse.web.pages.parts`): `:new-reading`, `:readings`, `:record`,
  `:request-visit`, `:retry`, and `:cancel-appointment` and `:cancel-request`, which are functions of the row of an
  appointment. What is not there is a page the portal does not have yet, and its button is left out.

  Whatever the api could not say is said so: a part that could not be loaded is a message in its place, with a way to try again,
  and never an empty list that looks as if the patient had nothing (the summary names what it could not load)."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.ui.icon :as i]
            [pulse.web.format :as fmt]
            [pulse.web.pages.parts :as parts]
            [pulse.web.views :as views]))

(def recent-count
  "How many readings the list of recent ones shows."
  5)

;; ---------------------------------------------------------------------------
;; What the model says

(defn- given-name
  "The first given name of the patient, or nil."
  [me]
  (let [name (get-in me [:resource :name 0 :given 0])]
    (when-not (str/blank? name) name)))

(defn- doctors
  "The practitioners that the patient's appointments can be with, as the page looks a doctor up: a map from their reference to
  their name. The api names the general practitioner, whom every appointment of a patient is with."
  [me]
  (let [{:keys [reference name]} (:generalPractitioner me)]
    (if reference {reference name} {})))

(defn- vitals-lost
  "Which of the latest readings of the summary could not be loaded: nil for none, `:some`, or `:all` (also when there is no
  summary). The summary names what it could not load, such as `vitals.glucose`."
  [summary]
  (if (nil? summary)
    :all
    (let [lost (filter #(str/starts-with? % "vitals.") (:unavailable summary))]
      (cond (empty? lost) nil
            (< (count lost) (count (:vitals summary))) :some
            :else :all))))

(defn- has-readings?
  "Whether the patient has a latest reading that says something."
  [summary]
  (boolean (some (fn [[kind reading]] (and reading (views/usable? kind reading))) (:vitals summary))))

(defn new-patient?
  "Whether the summary says that the patient has no readings at all: none that says anything, and nothing that could not be
  loaded that might have been one. No summary at all is not that: nothing could be loaded."
  [summary]
  (and (nil? (vitals-lost summary)) (not (has-readings? summary))))

;; ---------------------------------------------------------------------------
;; The parts of the home

(defn- pressure-notice
  "What a patient is told when their latest blood pressure is very high. The portal knows the band of the reading and nothing
  more, so it does not say that anyone has been told."
  [reading now links]
  (c/alert {:tone :critical :title "Your last blood pressure reading is very high"
            :actions (when-let [href (:readings links)]
                       (c/button {:variant :outline :size :lg :href href} "See my readings"))}
           (str (views/reading-text :blood-pressure reading) " " (or (fmt/unit (:unit reading)) "mmHg")
                (when (fmt/instant? (:at reading)) (str ", " (fmt/when-phrase (:at reading) now)))
                ". If you feel unwell, with a headache, chest pain or trouble breathing, call 112. "
                "Otherwise, rest and measure again, and tell your care team.")))

(defn- next-appointment-section [{:keys [now me summary]} links]
  [:section {:aria-labelledby "next"}
   [:div {:class "mb-3"} (parts/section-heading "next" "Your next appointment")]
   (if (some #{"appointments"} (:unavailable summary))
     (parts/not-loaded "Your appointments could not be loaded" links)
     (if-let [appointment (views/next-appointment (:appointments summary) now)]
       (c/card {:flush? true}
               (c/list-group {:aria-label "Your next appointment"}
                             (parts/appointment-item (views/appointment-row appointment (doctors me)) links now)))
       (c/card (c/empty-state {:icon :calendar
                               :title "No appointment coming up"
                               :actions (when-let [href (:request-visit links)]
                                          (c/button {:size :lg :icon :calendar-plus :href href} "Ask for a visit"))}
                              "When your clinic confirms one, you will see it here."))))])

(defn- latest-readings-section [{:keys [now summary]} links]
  (let [lost (vitals-lost summary)]
    [:section {:aria-labelledby "latest"}
     [:div {:class "mb-3 flex items-center justify-between gap-3"}
      (parts/section-heading "latest" "Your latest readings")
      (when-let [href (:readings links)]
        [:a {:href href :class "link link-primary inline-flex min-h-11 items-center gap-1 font-medium"}
         "See all" (i/icon :arrow-right {:size :sm})])]
     (case lost
       :all (parts/not-loaded "Your latest readings could not be loaded" links)
       :some [:div {:class "space-y-4"}
              (parts/not-loaded "Some of your readings could not be loaded" links)
              (parts/readings-grid (:vitals summary) now)]
       (parts/readings-grid (:vitals summary) now))]))

(defn- recent-readings-section [{:keys [now recent]} links]
  (if (nil? recent)
    [:section {:aria-labelledby "recent"}
     [:div {:class "mb-3"} (parts/section-heading "recent" "Recent readings")]
     (parts/not-loaded "Your recent readings could not be loaded" links)]
    (when (seq (views/recent-readings recent recent-count))
      [:section {:aria-labelledby "recent"}
       [:div {:class "mb-3"} (parts/section-heading "recent" "Recent readings")]
       (c/card {:flush? true} (parts/recent-readings recent recent-count now))
       [:p {:class "mt-3 text-sm text-muted"}
        "The words beside a reading, such as Very high, compare it with a reference range. They are not a diagnosis."]])))

;; ---------------------------------------------------------------------------
;; A patient with no readings yet

(defn- first-step [icon title text]
  (c/list-row {:leading (c/icon-disc {:icon icon})}
              [:p {:class "font-medium"} title]
              [:p {:class "text-sm text-muted"} text]))

(defn- first-steps-section
  "What to do first. A step is there only when the page that it is about is: with none of them, there is no section."
  [links]
  (let [steps (cond-> []
                (:new-reading links) (conj (first-step :activity "Record your first reading"
                                                       "Blood pressure, heart rate, oxygen, glucose, weight or temperature."))
                (:record links) (conj (first-step :clipboard-list "Look at your record"
                                                  "Your doctor sees what you see there.")))]
    (when (seq steps)
      [:section {:aria-labelledby "first"}
       [:div {:class "mb-3"} (parts/section-heading "first" "Your first steps")]
       (c/card {:flush? true} (c/list-group {:aria-label "First steps"} (seq steps)))])))

(defn- no-readings-section [links]
  [:section {:aria-labelledby "latest"}
   [:div {:class "mb-3"} (parts/section-heading "latest" "Your latest readings")]
   (parts/no-readings-card links)])

;; ---------------------------------------------------------------------------
;; The page

(defn- greeting [{:keys [now me]}]
  (str "Good " (fmt/part-of-day now) (when-let [name (given-name me)] (str ", " name))))

(defn content
  "The home: a greeting, what to know now, the next appointment, the latest readings and the recent ones. For a patient who
  has no readings yet, a welcome, what to do first, and the same appointment. `model` and `links` are described at the top
  of this namespace."
  [{:keys [now me summary] :as model} links]
  (cond
    (nil? summary)
    [:div {:class "space-y-8"}
     (c/page-header {:title (greeting model) :description (fmt/long-date now)})
     (parts/not-loaded "Your appointments and readings could not be loaded" links {:rest-of-page? false})]

    (new-patient? summary)
    [:div {:class "space-y-8"}
     (c/page-header {:title (str "Welcome" (when-let [name (given-name me)] (str ", " name)))
                     :description "Your clinic has set up your account."})
     (first-steps-section links)
     (no-readings-section links)
     (next-appointment-section model links)]

    :else
    (let [pressure (get-in summary [:vitals :blood-pressure])]
      [:div {:class "space-y-8"}
       (c/page-header {:title (greeting model)
                       :description (fmt/long-date now)
                       :actions (when-let [href (:new-reading links)]
                                  (c/button {:size :lg :icon :plus :href href} "Record a reading"))})
       (when (and (= "very-high" (:band pressure)) (views/usable? :blood-pressure pressure))
         (pressure-notice pressure now links))
       (next-appointment-section model links)
       (latest-readings-section model links)
       (when-not (= :all (vitals-lost summary))
         (recent-readings-section model links))])))
