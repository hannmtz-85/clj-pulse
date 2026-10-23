(ns pulse.web.pages.reading-form
  "The page where a patient records a reading (ADR-0028): the choice of what was measured, the numbers of that measurement, and when
  it was taken. It is a function of what the form holds, and the mock-ups of the review give it the form as it looks when the api
  has refused a reading, so that what is reviewed is what is served. Pure hiccup, free of Ring.

  The form works with no script: choosing the measurement is a form of its own that asks for the page again with the fields of
  the measurement chosen (a button inside `noscript` sends it), and with htmx the choice swaps the fields without leaving the page.
  The page is never made of what the api says is possible: the api decides what a reading may be, and what it refused is written
  beside the field (see `pulse.web.recording`).

  The `model` is

    `:kind`        the measurement, such as `:heart-rate`
    `:values`      what is in the fields, as text: by the name of the field (`:systolic`, `:diastolic` or `:value`, and `:taken`)
    `:errors`      the words beside the fields that have something wrong, by the name of the field
    `:failure`     why the reading is not saved: `:refused` (the api would not take it), `:unsaved` (the api failed before it was
                   sent) or `:unconfirmed` (it failed with the reading on its way, so that nobody knows if it was saved)
    `:now`         the instant it is, as text
    `:csrf-token`  the token of the person's session, which the form carries

  and `links` says where it leads: `:new-reading` (this page, which the forms are addressed to; required) and `:readings` (the
  button to cancel, which goes back to the readings of the measurement that was being recorded)."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.web.format :as fmt]
            [pulse.web.pages :as pages]
            [pulse.web.recording :as recording]
            [pulse.web.views :as views]))

(defn- join-and
  "`a`, `a and b`, `a, b and c`."
  [items]
  (if (= 1 (count items))
    (first items)
    (str (str/join ", " (butlast items)) " and " (last items))))

(defn- alert-of
  "The message at the top of the form: what to check when the reading was refused, or that nobody knows whether it was saved."
  [fields errors failure]
  (cond
    (= :unconfirmed failure)
    (c/alert {:tone :high :title "We could not confirm that your reading was saved" :role "alert"}
             "The problem is on our side, not yours. Look at your readings before you try again, so that it is not recorded twice.")

    (= :unsaved failure)
    (c/alert {:tone :high :title "The reading was not saved" :role "alert"}
             "The problem is on our side, not yours. Try again in a moment.")

    (or (seq errors) failure)
    (let [named (concat (for [{:keys [channel name]} fields :when (contains? errors channel)] (str "the " name))
                        (when (contains? errors :taken) [(str "the " recording/taken-name)]))]
      (c/alert {:tone :high :title "The reading was not saved" :role "alert"}
               (if (seq named)
                 (str "Check " (join-and named) " and save it again.")
                 "Check what you entered and save it again.")))))

(defn- choose-form
  "What was measured, a form of its own: it asks for this page again with the fields of the measurement chosen."
  [kind links]
  [:form {:method "get" :action (:new-reading links)}
   (c/select-field {:id "measurement" :name "measurement" :label "What did you measure?" :value (name kind)
                    :options (for [k views/kinds] [(name k) (get-in views/kind-info [k :label])])
                    :hx-get (:new-reading links) :hx-target "#reading-fields" :hx-select "#reading-fields > *"
                    :hx-swap "innerHTML" :hx-push-url "true"})
   [:noscript (c/button {:type "submit" :variant :outline :size :lg} "Show these fields")]])

(defn content
  "The form to record a reading. `model` and `links` are described at the top of this namespace."
  [{:keys [kind values errors failure now csrf-token]} links]
  (let [fields (recording/fields kind)
        focus (first (filter #(contains? errors %) (concat (map :channel fields) [:taken])))]
    [:div {:class "space-y-6"}
     (c/page-header {:title "Record a reading" :description "Your care team can see it as soon as you save it."})
     (c/card
      [:div {:class "flex max-w-xl flex-col gap-5"}
       (alert-of fields errors failure)
       (choose-form kind links)
       [:form {:method "post" :action (:new-reading links) :novalidate true :class "flex flex-col gap-5"}
        (pages/csrf-field csrf-token)
        [:div {:id "reading-fields" :class "flex flex-col gap-5" :aria-live "polite"}
         [:input {:type "hidden" :name "measurement" :value (name kind)}]
         (for [{:keys [channel label hint step inputmode]} fields]
           (c/text-field (cond-> {:id (name channel) :name (name channel) :label label :type "number"
                                  :inputmode inputmode :step step :value (get values channel)}
                           hint (assoc :hint hint)
                           (contains? errors channel) (assoc :error (get errors channel))
                           (= focus channel) (assoc :autofocus true))))]
        (c/text-field (cond-> {:id "taken" :name "taken" :label "Taken at" :type "datetime-local" :value (get values :taken)
                               :hint (str "Now, or up to " recording/max-age-days " days ago.")
                               :min (fmt/local-input (recording/earliest now)) :max (fmt/local-input now)}
                        (contains? errors :taken) (assoc :error (get errors :taken))
                        (= focus :taken) (assoc :autofocus true)))
        [:div {:class "flex flex-wrap gap-3"}
         (c/button {:type "submit" :size :lg :icon :send} "Save the reading")
         (when-let [href (:readings links)]
           (c/button {:variant :ghost :size :lg :href (str href "?measurement=" (name kind))} "Cancel"))]]])]))
