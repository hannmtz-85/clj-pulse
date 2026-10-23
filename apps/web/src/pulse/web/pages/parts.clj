(ns pulse.web.pages.parts
  "The pieces that more than one page of the portal is made of (ADR-0028): a heading, a row of appointments, the tiles of the
  latest readings, the list of the recent ones, and the message that stands where a part of a page could not be loaded. Pure
  hiccup, free of Ring, like the pages: the mock-ups of the review use them too.

  A page says where its buttons lead with `links`, a map. A link that is not in it is a page the portal does not have yet, and
  the button or the link that would lead there is left out, so that nothing leads nowhere: each step of the portal adds its
  own. The links of an appointment are functions of its row, since each has its own address."
  (:require [clojure.string :as str]
            [pulse.fhir.appointment :as appointment]
            [pulse.ui.component :as c]
            [pulse.web.format :as fmt]
            [pulse.web.views :as views]))

(defn section-heading
  "The heading of a section of a page, with the `id` that the section names itself by (`aria-labelledby`)."
  ([text] (section-heading nil text))
  ([id text] [:h2 (cond-> {:class "text-xl font-semibold tracking-tight"} id (assoc :id id)) text]))

(defn- cancel-button
  "The button that cancels what the row is, or nil: the lifecycle lets a patient cancel an appointment that is booked until it
  begins and a request at any time, and the page has to have somewhere to send them (`:cancel-appointment` or
  `:cancel-request` of `links`)."
  [{:keys [status start] :as row} links now]
  (when (contains? (appointment/available-actions {:status status :start start} :patient now) :cancel)
    (let [address (fn [link] (when link (link row)))]
      (case status
        "booked" (when-let [href (address (:cancel-appointment links))]
                   (c/button {:variant :outline :size :lg :icon :calendar-x :href href} "Cancel appointment"))
        "proposed" (when-let [href (address (:cancel-request links))]
                     (c/button {:variant :outline :size :lg :icon :x :href href} "Cancel request"))
        nil))))

(defn appointment-item
  "A row of a list of appointments of a patient: the day, or a clock for a request, what it is, with whom and when (or the
  days that were asked for), its status, and what the patient may do, which is the lifecycle's to say (ADR-0027): cancel a
  confirmed appointment, until it begins, or withdraw a request. `row` is `pulse.web.views/appointment-row`, `now` the
  instant it is."
  [{:keys [status description doctor month day reason asked] when-text :when :as row} links now]
  (c/list-row
   {:leading (if day (c/date-block {:month month :day day}) (c/date-block {:icon :clock}))
    :trailing-wraps? true
    :trailing (list (views/appointment-chip status)
                    (cancel-button row links now))}
   [:p {:class "text-base font-semibold"} description]
   [:p {:class "text-sm text-muted"} (str/join " · " (remove nil? [(or when-text asked) doctor]))]
   (when reason [:p {:class "text-sm text-muted"} (str "Reason: " reason)])))

(defn readings-grid
  "The tiles of the latest readings: one for each measurement that has a usable one in `vitals`, a map from the measurement to
  its latest reading (nil for none), in the order of `pulse.web.views/kinds`."
  [vitals now]
  (into [:div {:class "grid gap-4 sm:grid-cols-2 lg:grid-cols-3"}]
        (for [kind views/kinds
              :let [reading (get vitals kind)]
              :when (and reading (views/usable? kind reading))]
          (views/vital-tile kind reading now))))

(defn recent-readings
  "The `n` newest readings among `items`, whatever they measure, newest first, as the rows of a list. `items` are those of the
  series of the api, each with its `:measurement`."
  [items n now]
  (c/list-group
   {:aria-label "Recent readings"}
   (for [{:keys [kind reading]} (views/recent-readings items n)
         :let [{:keys [label icon]} (views/kind-info kind)]]
     (c/list-row
      {:leading (c/icon-disc {:icon icon :variant :muted})
       :trailing (c/band-badge (:band reading))}
      [:p {:class "font-medium"} label]
      [:p {:class "text-sm text-muted"}
       (str (views/reading-text kind reading) " " (fmt/unit (:unit reading)) " · " (fmt/when-text (:at reading) now))]))))

(defn no-readings-card
  "The card that says that the patient has no readings yet, with the button to record one when the portal has the page for it
  (`:new-reading` of `links`)."
  [links]
  (c/card (c/empty-state {:icon :activity
                          :title "No readings yet"
                          :actions (when-let [href (:new-reading links)]
                                     (c/button {:size :lg :icon :plus :href href} "Record a reading"))}
                         "When you record one, or a device sends one, you will see it here and how it compares with the usual range.")))

(defn not-loaded
  "The message that stands where a part of a page should be when it could not be loaded: `title` says which part, and it says
  that the rest of the page is fine and that the problem is not the patient's. Its button, to try again, leads to `:retry` of
  `links` (the page itself) when there is one. When what could not be loaded is all there was, `:rest-of-page?` is false, and
  it does not speak of a rest."
  ([title links] (not-loaded title links nil))
  ([title links {:keys [rest-of-page?] :or {rest-of-page? true}}]
   (c/alert {:tone :high :title title
             :actions (when-let [href (:retry links)]
                        (c/button {:variant :outline :size :lg :icon :refresh-cw :href href} "Try again"))}
            (str (when rest-of-page? "The rest of the page is up to date. ")
                 "The problem is on our side, not yours."))))
