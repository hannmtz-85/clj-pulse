(ns pulse.design.chrome
  "The frame around the pages of the mock-ups (ADR-0026): a bar with the links to the other pages and a choice of
  theme that is remembered between them. It is for the review and is not part of any interface."
  (:require [design]
            [pulse.ui.icon :as i]
            [pulse.ui.layout :as layout]))

(defn theme-options
  "The themes of the tokens as `[name label]`, such as `[\"pulse-teal-dark\" \"Teal, dark\"]`."
  [tokens]
  (for [{:keys [name palette scheme]} (design/themes tokens)]
    [name (str (get-in tokens [:palettes palette :label]) ", " (clojure.core/name scheme))]))

(defn- nav-link [{:keys [file title]} current?]
  [:a (cond-> {:href file
               :class (str "rounded-field px-3 py-1.5 text-sm font-medium "
                           (if current? "bg-accent text-accent-content" "text-base-content hover:bg-base-200"))}
        current? (assoc :aria-current "page"))
   title])

(defn page
  "A page of the mock-ups. `:title` of the page, `:pages` (the links: `{:file :title}`), `:file` of this one,
  `:themes` (from `theme-options`) and `:theme` (the one it starts in). The children are its content."
  [{:keys [title pages file themes theme] :or {theme "pulse-teal"}} & content]
  (layout/document
   {:title (str title " · Pulse design")
    :theme theme
    :stylesheets ["pulse.css"]
    :head [[:script {:src "review.js"}]]}
   (layout/skip-link "main")
   [:header {:class "border-b border-base-300 bg-base-100"}
    [:div {:class "mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3"}
     [:a {:href "index.html" :class "flex items-center gap-2 font-semibold"}
      [:span {:class "grid size-8 place-items-center rounded-field bg-primary text-primary-content"}
       (i/icon :heart-pulse {:size :md})]
      "Pulse"
      [:span {:class "font-normal text-muted"} "design"]]
     [:nav {:aria-label "Pages of the mock-ups" :class "flex flex-wrap gap-1"}
      (for [p pages] (nav-link p (= file (:file p))))]
     [:div {:class "ml-auto flex items-center gap-2"}
      [:label {:for "theme-select" :class "text-sm text-muted"} "Theme"]
      (into [:select {:id "theme-select" :class "select select-sm"}]
            (for [[value text] themes]
              [:option (cond-> {:value value} (= value theme) (assoc :selected true)) text]))]]]
   [:main {:id "main" :class "mx-auto max-w-6xl px-4 py-8"} content]
   [:footer {:class "mx-auto max-w-6xl px-4 pb-10 text-sm text-muted"}
    "Mock-ups of Pulse, made from the design tokens and the components of libs/ui with the synthetic cohort. "
    "Nothing here is connected to anything."]))
