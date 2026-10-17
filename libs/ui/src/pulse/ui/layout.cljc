(ns pulse.ui.layout
  "The frames of a page (ADR-0026): the document, with the stylesheet and the theme, and the skip link; and the frames
  of the interfaces: the bar and the dock of the patient portal, the sidebar and the header of the clinical
  workspace, and the card of the pages of Keycloak."
  (:require [pulse.ui.attrs :as attrs]
            [pulse.ui.component :as c]
            [pulse.ui.icon :as i]))

(defn skip-link
  "The link that takes a keyboard to the main content, hidden until it has focus. `target` is the id of the
  element that holds it."
  [target]
  [:a {:href (str "#" target)
       :class "sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 focus:rounded-field focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-content"}
   "Skip to the content"])

(defn document
  "A whole HTML document: `:title`, `:lang` (\"en\"), `:theme` (the name of a daisyUI theme; the page follows the
  browser's preference when it has none), `:stylesheets` (hrefs), `:description`, `:body-class` and `:head`
  (more elements for the head). The children are the body. It is hiccup, to be rendered after
  `<!DOCTYPE html>`; it has no inline script or style, so a strict Content-Security-Policy accepts it."
  [{:keys [title lang theme stylesheets description body-class head] :or {lang "en"}} & body]
  [:html (cond-> {:lang lang} theme (assoc :data-theme theme))
   (into [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:meta {:name "color-scheme" :content "light dark"}]
          (when description [:meta {:name "description" :content description}])
          [:title title]]
         (concat (for [href stylesheets] [:link {:rel "stylesheet" :href href}])
                 head))
   (into [:body {:class (attrs/classes "min-h-screen bg-base-200 text-base-content antialiased" body-class)}]
         body)])

;; ---------------------------------------------------------------------------
;; The frames of the interfaces

(defn- nav-count-label
  "What a screen reader says for a navigation item that has a count: `Alerts, 3 active`."
  [label count-label]
  (if count-label (str label ", " count-label) label))

(defn portal-shell
  "The frame of the patient portal: a bar at the top with the navigation, from a medium screen on, and the same
  navigation as a dock at the bottom of a phone. `:current` is the `:id` of the item of the page, `:nav` is
  `[{:id :href :label :icon} ...]` and `:user` is the arguments of `user-menu`. Everything is as large as a finger
  needs: the links of the bar are 44 px high. The children are the page, in `main`."
  [{:keys [current nav user]} & content]
  [:div {:class "min-h-screen bg-base-200 pb-24 md:pb-0"}
   (skip-link "main")
   [:header {:class "border-b border-base-300 bg-base-100"}
    [:div {:class "mx-auto flex max-w-5xl items-center gap-3 px-4 py-2 md:gap-6"}
     (c/logo {:href (:href (first nav))})
     (into [:nav {:aria-label "Main" :class "hidden items-center gap-1 md:flex"}]
           (for [{:keys [id href] text :label} nav]
             [:a (cond-> {:href href
                          :class (attrs/classes "inline-flex min-h-11 items-center rounded-field px-4 text-base font-medium"
                                                (if (= id current)
                                                  "bg-accent text-accent-content"
                                                  "text-base-content hover:bg-base-200"))}
                   (= id current) (assoc :aria-current "page"))
              text]))
     [:div {:class "ml-auto flex items-center gap-1"}
      (c/icon-button {:icon :moon :label "Dark theme" :variant :ghost :size :lg})
      (c/user-menu user)]]]
   (into [:main {:id "main" :class "mx-auto max-w-5xl px-4 py-6 md:py-10"}] content)
   (into [:nav {:aria-label "Main, on a phone" :class "dock md:hidden"}]
         (for [{:keys [id href icon] text :label} nav]
           [:a (cond-> {:href href :class (when (= id current) "dock-active")}
                 (= id current) (assoc :aria-current "page"))
            (i/icon icon {:size :md})
            [:span {:class "dock-label text-xs"} text]]))])

(defn workspace-shell
  "The frame of the clinical workspace: a sidebar, full from a large screen and a rail of icons from a medium one, and
  a header with a search box and the menu of the person. `:current` is the `:id` of the item of the page, `:nav` is
  groups of items, `[{:heading :items [{:id :href :label :icon :count :count-label} ...]} ...]`, `:search` the
  arguments of `search-field`, `:user` those of `user-menu`, and `:user-detail` the line under the name in the
  sidebar. The children are the page, in `main`. Its text is of 14 px: it is for a desk."
  [{:keys [current nav search user user-detail]} & content]
  [:div {:class "flex min-h-screen bg-base-200 text-sm"}
   (skip-link "main")
   [:aside {:class "sticky top-0 hidden h-screen w-16 shrink-0 flex-col border-r border-base-300 bg-base-100 md:flex lg:w-60"}
    [:div {:class "flex h-14 shrink-0 items-center justify-center border-b border-base-300 px-3 lg:justify-start"}
     (c/logo {:href (:href (first (:items (first nav)))) :name-class "hidden lg:inline"})]
    (into [:nav {:aria-label "Workspace" :class "flex-1 space-y-5 overflow-y-auto p-2"}]
          (for [{:keys [heading items]} nav]
            [:div
             (when heading
               [:p {:class "mb-1 hidden px-3 text-xs font-semibold tracking-wide text-muted uppercase lg:block"} heading])
             (into [:ul {:class "space-y-1"}]
                   (for [{:keys [id href icon count count-label] text :label} items]
                     [:li
                      [:a (cond-> {:href href
                                   :title text
                                   :aria-label (nav-count-label text count-label)
                                   :class (attrs/classes "relative flex min-h-10 items-center justify-center gap-3 rounded-field px-2 font-medium lg:justify-start lg:px-3"
                                                         (if (= id current)
                                                           "bg-accent text-accent-content"
                                                           "text-base-content hover:bg-base-200"))}
                            (= id current) (assoc :aria-current "page"))
                       (i/icon icon {:size :md})
                       [:span {:class "hidden lg:inline"} text]
                       (when count
                         [:span {:class "ml-auto hidden rounded-full bg-tone-critical-soft px-2 text-xs font-semibold text-tone-critical-ink tabular-nums lg:inline"}
                          (str count)])
                       (when count
                         [:span {:class "absolute top-1.5 right-1.5 size-2 rounded-full bg-tone-critical-edge lg:hidden" :aria-hidden "true"}])]]))]))
    [:div {:class "shrink-0 border-t border-base-300 p-3"}
     [:div {:class "hidden lg:block"} (c/person {:name (:name user) :detail user-detail})]
     [:div {:class "flex justify-center lg:hidden"} (c/avatar {:name (:name user)})]]]
   [:div {:class "flex min-w-0 flex-1 flex-col"}
    [:header {:class "sticky top-0 z-10 flex h-14 shrink-0 items-center gap-3 border-b border-base-300 bg-base-100 px-4 lg:px-6"}
     [:div {:class "md:hidden"} (c/icon-button {:icon :menu :label "Open the menu" :variant :ghost})]
     (when search [:div {:class "hidden w-full max-w-md sm:block"} (c/search-field search)])
     [:div {:class "ml-auto flex items-center gap-1"}
      (c/icon-button {:icon :moon :label "Dark theme" :variant :ghost})
      (c/user-menu (assoc user :name-class "lg:hidden"))]]
    (into [:main {:id "main" :class "min-w-0 flex-1 p-4 lg:p-6"}] content)]])

(defn login-card
  "The card of a page of Keycloak: the `:title`, an `h1` unless `:heading` says otherwise, an optional `:subtitle`, and
  the children."
  [{:keys [title subtitle heading] :or {heading :h1}} & content]
  [:div {:class "rounded-box border border-base-300 bg-base-100 p-6 shadow-raised sm:p-8"}
   [heading {:class "text-2xl font-semibold tracking-tight"} title]
   (when subtitle [:p {:class "mt-1 text-base text-muted"} subtitle])
   (into [:div {:class "mt-6"}] content)])

(defn login-shell
  "The frame of the pages of Keycloak (sign-in, an error, a message): the mark, a `login-card` with the `:title` and an
  optional `:subtitle`, and a line that says that the data is a demo. The children are in the card."
  [options & content]
  [:div {:class "grid min-h-screen place-items-center bg-base-200 px-4 py-10"}
   (skip-link "main")
   [:main {:id "main" :class "w-full max-w-md"}
    [:div {:class "mb-6 flex justify-center"} (c/logo)]
    (apply login-card options content)
    [:p {:class "mt-6 text-center text-xs text-muted"} "Pulse is a demonstration. All the data is synthetic, and it is not a medical device."]]])
