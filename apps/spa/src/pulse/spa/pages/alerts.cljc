(ns pulse.spa.pages.alerts
  "The inbox of alerts of the clinical workspace (ADR-0029): the alerts that need a decision, each in the colour of its rule with when it
  was raised, the rule in its own words and the readings behind it, and the ones that were acknowledged. Pure hiccup of the components of
  `libs/ui`, with no Reagent, so that the workspace and the mock-up of the review make the same page: `content` is what both show, and
  `page` is the workspace around it.

  What the page is a function of is the state of the inbox as `pulse.spa.inbox` keeps it, in the shapes of the api, with each part
  `{:state :loading|:loaded|:failed ...}`. **What could not be loaded is said, in its place, and is never shown as empty**: a list the api
  could not give is a message with a way to try again, and not \"No alerts\". **What is shown does not move on its own**: when the workspace
  looks again and finds other alerts, the page says so and the person decides when to see them.

  A control that has nothing to lead to is not there: a pill is there only when `:filter` is among the `links` (a function from `:active`,
  `:acknowledged` or `:all` to an address), the button that opens the chart of a patient only when `:patient` is (a function from the id),
  the buttons that acknowledge and try again and show the changes only when `:acknowledge`, `:retry` and `:show-changes` are among the
  `handlers`. The mock-up, which has no handlers, leads `:acknowledge` among the `links` (a function from the id of the alert)."
  (:require [clojure.string :as str]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.pages.parts :as parts]
            [pulse.spa.pages.shell :as shell]
            [pulse.spa.person :as person]
            [pulse.ui.component :as c]))

;; ---------------------------------------------------------------------------
;; What the person is told besides the alerts

(defn notice
  "What the inbox says besides its alerts: that they have changed, that it could not look again, or that the names or the readings could not be loaded (`:id` of the notice, and the `:count` of the alerts)."
  [{:keys [id count]} handlers]
  (case id
    :changed (c/alert {:tone :info
                       :title "The alerts have changed"
                       :actions (when-let [show (:show-changes handlers)]
                                  (c/button {:variant :outline :size :sm :icon :refresh-cw :on-click show} "Show the changes"))}
                      "Alerts were raised or acknowledged since you opened this page.")
    :check-failed (parts/not-loaded "We could not check for new alerts" "What you see may be out of date." handlers)
    :names-failed (parts/not-loaded "The names of the patients could not be loaded" "The alerts are up to date." handlers)
    :evidence-failed (parts/not-loaded (str "The readings behind " (if (= 1 count) "1 alert" (str count " alerts")) " could not be loaded")
                                       "The rest of the page is up to date." handlers)))

;; ---------------------------------------------------------------------------
;; The alerts

(defn- chart-button
  "The button that opens the chart of the patient of an alert, or nil when the alert names no patient or the page has no chart to lead
  to. A screen reader is told whose chart, so that the buttons of a page do not all say the same."
  [{:keys [patient-id name]} variant links]
  (when-let [link (when patient-id (:patient links))]
    (c/button {:variant variant :size :sm :href (link patient-id) :aria-label (str "Open the chart of " name)} "Open the chart")))

(defn- active-card
  "An alert that needs a decision, as a message in the colour of its rule: when it was raised and the rule in its own words, the latest
  readings of what it watches, and the buttons to acknowledge it and to open the chart of the patient."
  [{:keys [id label tone since-phrase description evidence ack] :as card} {:keys [links] :as options}]
  (apply c/alert
         {:id (str "alert-" id)
          :tone tone
          :title (parts/wrapped label)
          :actions (parts/actions (parts/acknowledge-button id label ack options)
                                  (chart-button card :outline links))}
         (remove nil? [(parts/raised-line since-phrase description)
                       (parts/readings-lines evidence)
                       (parts/failure-message ack)])))

(defn- history-line
  "`Raised yesterday, 22:30, acknowledged today, 09:40`, each part only when it can be read, or nil when neither can."
  [since-phrase until-phrase]
  (let [pieces (keep identity [(when since-phrase (str "Raised " since-phrase)) (when until-phrase (str "acknowledged " until-phrase))])]
    (when (seq pieces)
      (str/join ", " pieces))))

(defn- acknowledged-row [{:keys [label since-phrase until-phrase] :as row} {:keys [links]}]
  (c/list-row
   {:leading (c/icon-disc {:icon :check :variant :muted})
    :trailing-wraps? true
    :trailing (parts/actions (c/badge {:variant :outline} "Acknowledged")
                             (chart-button row :ghost links))}
   [:p {:class "font-medium wrap-anywhere"} label]
   (when-let [line (history-line since-phrase until-phrase)]
     [:p {:class "text-muted wrap-anywhere"} line])))

(defn- section-heading [id text]
  [:h2 {:id id :class "text-base font-semibold"} text])

(defn- cut-note
  "The sentence that says a list is the latest of more, or nil when it is all of them."
  [{:keys [items total]} noun]
  (when (and (integer? total) (< (count items) total))
    [:p {:class "mt-3 text-sm text-muted"} (str "Showing the latest " (count items) " of " total " " noun ".")]))

(defn nothing-to-decide
  "What the inbox says when no alert needs a decision, with a button to the acknowledged ones when it has one (`see-acknowledged`)."
  [see-acknowledged]
  (c/card (c/empty-state {:icon :bell-off :title "No alerts" :actions see-acknowledged}
                         "Nothing needs a decision right now. New alerts appear here as the rules raise them.")))

(defn- needs-section
  "The alerts that need a decision: the cards, or what is said instead when they are on their way, could not be loaded or there are none."
  [{:keys [show active acknowledged]} {:keys [links handlers] :as options}]
  (let [{:keys [state items]} active
        see-acknowledged (when (and (= :active show) (:filter links) (pos? (or (:total acknowledged) 0)))
                           (c/button {:variant :outline :size :sm :href ((:filter links) :acknowledged)} "See acknowledged alerts"))]
    [:section {:aria-labelledby "needs"}
     [:div {:class "mb-3"} (section-heading "needs" "Needs a decision")]
     (case state
       :loading (into [:div {:class "space-y-4"}] (repeat 2 (c/skeleton {:class "h-28 w-full"})))
       :lost (parts/not-loaded "The alerts could not be loaded" "Everything else is up to date." handlers)
       (if (seq items)
         ;; each card is keyed by its alert: when the list changes, React must not hand the button that has the focus to another alert
         (into [:div {:class "space-y-4"}] (map #(with-meta (active-card % options) {:key (:id %)})) items)
         (nothing-to-decide see-acknowledged)))
     (cut-note active "alerts")]))

(defn- done-section
  "The alerts that were acknowledged, as a list."
  [{:keys [acknowledged]} {:keys [handlers] :as options}]
  (let [{:keys [state items]} acknowledged]
    [:section {:aria-labelledby "done"}
     [:div {:class "mb-3"} (section-heading "done" "Acknowledged")]
     (case state
       :loading (c/card {:flush? true}
                        (c/list-group {:aria-label "Acknowledged alerts"} (repeat 2 [:li {:class "px-5 py-4"} (c/skeleton {:class "h-10 w-full"})])))
       :lost (parts/not-loaded "The acknowledged alerts could not be loaded" "Everything else is up to date." handlers)
       (c/card {:flush? true}
               (if (seq items)
                 (c/list-group {:aria-label "Acknowledged alerts"} (map #(acknowledged-row % options) items))
                 [:p {:class "px-5 py-4 text-muted"} "Nothing has been acknowledged yet."])))
     (cut-note acknowledged "acknowledged alerts")]))

;; ---------------------------------------------------------------------------
;; The page

(defn content
  "The inbox as a page: `state` is the state of the inbox (see `pulse.spa.inbox`; nil before it begins to load) and `options` are `:show`
  (`:all`, `:active` or `:acknowledged`: what the address narrows it to), `:admin?` (the administrator sees the alerts of every patient),
  `:acks` (the state of the acknowledgements, by the id of the alert) and `:said` (what a screen reader was last told of them),
  `:links` and `:handlers`, which are those of the namespace."
  [state {:keys [show admin? acks said links handlers] :or {show :all}}]
  (let [model (inbox/model (or state inbox/not-begun) {:show show :acks acks :said said} admin?)
        options {:links links :handlers handlers}
        filter-link (:filter links)]
    [:div {:class "space-y-6"}
     (c/page-header {:title (:title model) :description (:description model)})
     [:p {:class "sr-only" :role "status"} (:live model)]
     (for [n (:notices model)] (notice n handlers))
     (when filter-link
       (c/pill-nav {:label "Show"
                    :items (for [{:keys [id label count current?]} (:filters model)]
                             {:href (filter-link id) :label label :count count :current? current?})}))
     (when-not (= :acknowledged show) (needs-section model options))
     (when-not (= :active show) (done-section model options))]))

(defn page
  "The workspace around the inbox. `options` are those of `content`, and `:person` (who is signed in), `:me` (what the api said of them:
  see `pulse.spa.person/display`), `:alert-count` (how many alerts need a decision: see `pulse.spa.pages.shell/nav`), `:links` (`:panel`
  and `:alerts` for the sidebar) and `:handlers` (`:sign-out`, which puts \"Sign out\" in the menu of the person). There is no search box:
  it would have nothing to search here."
  [state {:keys [person me links handlers alert-count] :as options}]
  (shell/frame {:current :alerts :person person :me me :links links :handlers handlers :alert-count alert-count}
               (content state (assoc options :admin? (person/admin? person)))))
