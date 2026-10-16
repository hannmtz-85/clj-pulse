(ns pulse.ui.layout
  "The frames of a page (ADR-0026): the document, with the stylesheet and the theme, and the skip link. The
  frames of an application (the sidebar and the header of the clinical workspace, the bar of the patient portal)
  are added with the pages that use them."
  (:require [pulse.ui.attrs :as attrs]))

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
