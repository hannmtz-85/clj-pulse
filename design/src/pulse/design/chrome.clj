(ns pulse.design.chrome
  "The frame around the pages of the mock-ups (ADR-0026): a bar with the links to the other pages, in menus by group,
  and a choice of theme that is remembered between them. It is for the review and is not part of any interface."
  (:require [clojure.string :as str]
            [design]
            [pulse.ui.component :as c]
            [pulse.ui.icon :as i]
            [pulse.ui.layout :as layout]))

(defn theme-options
  "The themes of the tokens as `[name label]`, such as `[\"pulse-dark\" \"Teal, dark\"]`."
  [tokens]
  (for [{:keys [name palette scheme]} (design/themes tokens)]
    [name (str (get-in tokens [:palettes palette :label]) ", " (clojure.core/name scheme))]))

(defn listed
  "The pages that the navigation and the overview list: all but those that say `:listed? false`, which other pages
  link to (the chart of each patient of the panel, for example)."
  [pages]
  (remove #(false? (:listed? %)) pages))

(defn groups
  "The listed pages by group, in the order the groups first appear: `[[group [page ...]] ...]`."
  [pages]
  (let [shown (listed pages)]
    (for [g (distinct (map :group shown))] [g (filter #(= g (:group %)) shown)])))

(defn- link-classes [current?]
  (str "rounded-field px-3 py-1.5 text-sm font-medium "
       (if current? "bg-accent text-accent-content" "text-base-content hover:bg-base-200")))

(defn- nav-item
  "One group of the navigation: a link when it has a single page, and a menu otherwise."
  [file [group group-pages]]
  (if (= 1 (count group-pages))
    (let [{page-file :file :keys [title]} (first group-pages)
          current? (= file page-file)]
      [:a (cond-> {:href page-file :class (link-classes current?)} current? (assoc :aria-current "page")) title])
    (let [current? (some #(= file (:file %)) group-pages)]
      [:details {:class "dropdown"}
       [:summary {:class (str "flex cursor-pointer list-none items-center gap-1 " (link-classes current?))}
        group (i/icon :chevron-down {:size :sm})]
       (into [:ul {:class "menu dropdown-content z-30 mt-1 w-60 rounded-box border border-base-300 bg-base-100 p-2 shadow-overlay"}]
             (for [{page-file :file :keys [title]} group-pages
                   :let [current-page? (= file page-file)]]
               [:li [:a (cond-> {:href page-file :class (when current-page? "menu-active")}
                          current-page? (assoc :aria-current "page"))
                     title]]))])))

(defn page
  "A page of the mock-ups. `:title` of the page, `:pages` (the links: `{:file :title :group}`), `:file` of this one,
  `:themes` (from `theme-options`) and `:theme` (the one it starts in); `:wide?` gives the content the whole
  width. The children are its content."
  [{:keys [title pages file themes theme wide?] :or {theme "pulse"}} & content]
  (let [container (if wide? "px-6" "mx-auto max-w-6xl px-4")]
    (layout/document
     {:title (str title " · Pulse design")
      :theme theme
      :stylesheets ["pulse.css"]
      :head [[:script {:src "review.js"}]]}
     (layout/skip-link "main")
     [:header {:class "relative z-20 border-b border-base-300 bg-base-100"}
      [:div {:class (str container " flex flex-wrap items-center gap-x-5 gap-y-2 py-3")}
       (c/logo {:href "index.html"})
       [:span {:class "-ml-3 text-muted"} "design"]
       [:nav {:aria-label "Pages of the mock-ups" :class "flex flex-wrap items-center gap-1"}
        (for [group (groups pages)] (nav-item file group))]
       [:div {:class "ml-auto flex items-center gap-2"}
        [:label {:for "theme-select" :class "text-sm text-muted"} "Theme"]
        (into [:select {:id "theme-select" :class "select select-sm"}]
              (for [[value text] themes]
                [:option (cond-> {:value value} (= value theme) (assoc :selected true)) text]))]]]
     [:main {:id "main" :class (str container " py-8")} content]
     [:footer {:class (str container " pb-10 text-sm text-muted")}
      "Mock-ups of Pulse, made from the design tokens and the components of libs/ui with the synthetic cohort. "
      "Nothing here is connected to anything."])))

(defn slug
  "A group name as an id: `Patient portal` is `patient-portal`."
  [group]
  (str/lower-case (str/replace group #"\W+" "-")))
