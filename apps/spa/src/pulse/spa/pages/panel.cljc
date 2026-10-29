(ns pulse.spa.pages.panel
  "The panel of the clinical workspace (ADR-0029): the patients of a practitioner, or all of them for the administrator, the most
  urgent first, with the figures that need a look and the filters. Pure hiccup of the components of `libs/ui`, with no Reagent, so
  that the workspace and the mock-up of the review make the same page: `content` is what both show, and `page` is the workspace
  around it.

  What the page is a function of is the state of the panel as `pulse.spa.panel` keeps it, in the shapes of the api, with each part
  `{:state :loading|:loaded|:failed ...}`, and what it is narrowed to (`{:show :q}`). **What could not be loaded is said, in its place,
  and is never shown as empty.** A control that has nothing to lead to is not there: a pill is there only when `:filter` is among the
  `links` (a function from the filter to the address), a button to try again only when `:retry` is among the `handlers`, and the name
  of a patient is a link only when `:patient` is among the `links` (a function from the id: the chart of a patient is the next step of
  the phase, so there is none yet)."
  (:require [pulse.spa.pages.shell :as shell]
            [pulse.spa.panel :as panel]
            [pulse.spa.person :as person]
            [pulse.ui.chart :as chart]
            [pulse.ui.component :as c]
            [pulse.view.format :as fmt]
            [pulse.view.views :as views]))

(defn- not-loaded-text
  "What stands where a number or a name should be when it could not be loaded."
  []
  [:span {:class "text-muted"} "Not loaded"])

(defn- skeleton
  "A grey shape where a cell is on its way."
  [width]
  (c/skeleton {:class (str "h-5 " width)}))

(defn- sub-line
  "The line under a name: the number of the patient and their age, such as `MRN-0012 · 58 years`."
  [{:keys [identifier age]}]
  (let [parts (cond-> [] identifier (conj identifier) age (conj (str age " years")))]
    (when (seq parts) (apply str (interpose " · " parts)))))

(defn- patient-cell [{:keys [id name] :as row} links]
  (if (nil? name)
    (skeleton "w-40")
    (let [href (when-let [link (:patient links)] (link id))
          ;; the column does not break a name in the middle of it, but one that is longer than 20 rem gives way, so that it does not push
          ;; the other columns out of the screen
          inside (list (c/avatar {:name name})
                       [:span {:class "max-w-80 whitespace-normal wrap-anywhere"}
                        [:span {:class "block font-medium"} name]
                        (when-let [line (sub-line row)] [:span {:class "block text-xs font-normal text-muted"} line])])]
      (if href
        [:a {:href href :class "flex items-center gap-3 hover:underline"} inside]
        [:div {:class "flex items-center gap-3"} inside]))))

(defn- status-cell [{:keys [state band vitals-lost?]}]
  (case state
    :loading (skeleton "w-24")
    :failed (c/tone-badge {:tone :info :icon :triangle-alert :size :sm} "Not loaded")
    (or (when band (c/band-badge band {:size :sm}))
        (if vitals-lost?
          (c/tone-badge {:tone :info :icon :triangle-alert :size :sm} "Not loaded")
          (c/tone-badge {:tone :info :icon :clock :size :sm} "No readings yet")))))

(defn- latest-cell [{:keys [state headline vitals-lost?]} now]
  (case state
    :loading (skeleton "w-32")
    :failed (not-loaded-text)
    (if-let [{:keys [kind reading]} headline]
      [:div
       [:p {:class "font-medium whitespace-nowrap"} (str (views/reading-text kind reading) " " (fmt/unit (:unit reading)))]
       [:p {:class "text-xs text-muted"}
        (str (get-in views/kind-info [kind :label])
             (when (fmt/instant? (:at reading)) (str ", " (fmt/when-phrase (:at reading) now))))]
       (when vitals-lost? [:p {:class "text-xs text-muted"} "Some readings not loaded"])]
      (if vitals-lost? (not-loaded-text) "–"))))

(defn- trend-cell [{:keys [state trend headline]}]
  (case state
    :loading (skeleton "w-24")
    :failed (not-loaded-text)
    (cond
      (nil? headline) "–"
      (= :loading (:state trend)) (skeleton "w-24")
      (= :failed (:state trend)) (not-loaded-text)
      (empty? (:points trend)) [:span {:class "text-muted"} (str "None in " panel/series-days " days")]
      :else (chart/sparkline (select-keys trend [:points :tone :label])))))

(defn- alerts-cell [{:keys [state alerts]}]
  (case state
    :loading (skeleton "w-16")
    :failed (not-loaded-text)
    (cond
      (nil? alerts) (not-loaded-text)
      (pos? alerts) (c/tone-badge {:tone :critical :icon :bell-ring :size :sm} (str alerts " active"))
      :else [:span {:class "text-muted"} "None"])))

(defn- visit-cell [{:keys [state visits-known? next-visit]}]
  (case state
    :loading (skeleton "w-32")
    :failed (not-loaded-text)
    (cond
      (not visits-known?) (not-loaded-text)
      next-visit next-visit
      :else "–")))

(defn- table-row [row now links]
  {:patient (patient-cell row links)
   :status (status-cell row)
   :latest (latest-cell row now)
   :trend (trend-cell row)
   :alerts (alerts-cell row)
   :next (visit-cell row)})

(defn- table
  "The table of patients: a row for each one."
  [caption rows now links]
  (c/data-table
   {:caption caption
    :columns [{:key :patient :label "Patient" :header? true :class "whitespace-nowrap"}
              {:key :status :label "Status"}
              {:key :latest :label "Latest reading"}
              {:key :trend :label (str "Last " panel/series-days " days")}
              {:key :alerts :label "Alerts"}
              {:key :next :label "Next visit" :class "hidden xl:table-cell"}]
    :rows (map #(table-row % now links) rows)}))

(defn- waiting-table
  "The table while the list of patients is on its way: three rows of grey shapes, since how many there are is not known yet."
  []
  (table "Patients, loading" (repeat 3 {:state :loading}) nil nil))

(defn- retry-button [handlers]
  (when-let [retry (:retry handlers)]
    (c/button {:variant :outline :size :lg :icon :refresh-cw :on-click retry} "Try again")))

(defn- not-loaded
  "The message that stands where a part of the page should be when it could not be loaded, as the portal says it."
  [title handlers {:keys [rest-of-page?] :or {rest-of-page? true}}]
  (c/alert {:tone :high :title title :actions (retry-button handlers)}
           (str (when rest-of-page? "The rest of the panel is up to date. ") "The problem is on our side, not yours.")))

(defn- notice [{:keys [id count]} admin? handlers]
  (case id
    :list-failed (not-loaded (if admin? "The patients could not be loaded" "Your panel could not be loaded") handlers {:rest-of-page? false})
    :rows-failed (not-loaded (str (if (= 1 count) "1 patient" (str count " patients")) " could not be loaded") handlers nil)
    :figures-failed (not-loaded "Some figures could not be loaded" handlers nil)))

(defn- caption [admin? settled?]
  (str (if admin? "Patients of the clinic" "Patients of your panel") (when settled? ", the most urgent first")))

(defn content
  "The panel as a page: `state` is the state of the panel (nil before it begins), `params` what it is narrowed to (`{:show :q}`), and
  `options` are `:admin?` (the administrator sees every patient), `:links` (`:filter`, a function from `:all`, `:alerts`, `:away` or
  `:normal` to an address; `:panel`, the address of the panel with nothing narrowed; `:patient`, a function from the id of a patient to
  the address of their chart) and `:handlers` (`:retry`)."
  [state params {:keys [admin? links handlers]}]
  (let [{:keys [title description stats filters rows notices footer settled? live] empty-kind :empty} (panel/model state params admin?)
        now (:now state)
        filter-link (:filter links)]
    [:div {:class "space-y-6"}
     (c/page-header {:title title :description description})
     [:p {:class "sr-only" :role "status"} live]
     (for [n notices] (notice n admin? handlers))
     [:div {:class "grid gap-4 sm:grid-cols-2 xl:grid-cols-4"}
      (for [stat stats] (c/stat (dissoc stat :id)))]
     (when (and filter-link (= :loaded (get-in state [:list :state])))
       (c/pill-nav {:label "Filter the panel"
                    :items (for [{:keys [id label count current?]} filters]
                             {:href (filter-link id) :label label :count count :current? current?})}))
     (cond
       (not= :loaded (get-in state [:list :state] :loading))
       (when-not (= :failed (get-in state [:list :state])) (waiting-table))

       (= :no-patients empty-kind)
       (c/card (c/empty-state {:icon :users :title (if admin? "There are no patients yet" "You have no patients yet")}
                              (if admin?
                                "Patients appear here when they are added to the clinic's records."
                                "Patients appear here when you are their doctor.")))

       (= :no-match empty-kind)
       (c/card (c/empty-state {:icon :search
                               :title "No patients match"
                               :actions (when-let [href (:panel links)] (c/button {:variant :outline :href href} "Show all patients"))}
                              "Try another word, or another filter."))

       :else (table (caption admin? settled?) rows now links))
     (when footer [:p {:class "text-sm text-muted"} footer])]))

(defn page
  "The workspace around the panel. `options` are those of `content`, and `:person` (who is signed in), `:me` (what the api said of
  them: see `pulse.spa.person/display`), `:links` (`:panel`, where the page is, for the sidebar) and `:handlers` (`:sign-out`, which
  puts \"Sign out\" in the menu of the person, and `:search`, a function of the text typed in the box, which puts the box in the
  header)."
  [state params {:keys [person me links handlers] :as options}]
  (shell/frame {:current :panel :person person :me me :links links :handlers handlers :search-value (:q params)}
               (content state params (assoc options :admin? (person/admin? person)))))
