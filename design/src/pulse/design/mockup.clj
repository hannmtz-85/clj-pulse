(ns pulse.design.mockup
  "A mock-up of an interface (ADR-0026) is two documents. The page itself, in `frames/`, is the interface as it will
  be, with no trace of the review, and the page of the review has a frame around it that can be as wide as a phone,
  a tablet or a desk, so that the page answers to the width it is given and not to the width of the window it is
  looked at in."
  (:require [pulse.design.chrome :as chrome]
            [pulse.ui.layout :as layout]))

(def devices
  "The widths a mock-up is shown at, with the name of each."
  {390 "Phone" 820 "Tablet" 1280 "Desktop"})

(defn frame-path
  "Where the page of the mock-up `file` is, relative to the pages of the review."
  [file]
  (str "frames/" file))

(defn href
  "The address of the page of the review `file` from inside a frame."
  [file]
  (str "../" file))

(defn frame-document
  "The page of a mock-up as a document of its own: `:title`, `:theme` (\"pulse\" by default), `:body-class`. The
  stylesheet and the script of the review are one level up; the script lets the review change its theme and tells it how
  high the page is. The children are the body."
  [{:keys [title theme body-class]} & body]
  (apply layout/document
         {:title (str title " · Pulse")
          :theme (or theme "pulse")
          :body-class body-class
          :stylesheets ["../pulse.css"]
          :head [[:script {:src "../review.js"}]]}
         body))

(defn- device-button [index width]
  [:button {:type "button"
            :data-width width
            :aria-pressed (str (zero? index))
            :class "rounded-field border border-base-300 bg-base-100 px-3 py-1.5 text-sm font-medium text-base-content hover:bg-base-200 aria-pressed:border-primary aria-pressed:bg-accent aria-pressed:text-accent-content"}
   (if-let [device (devices width)] (str device ", " width " px") (str width " px"))])

(defn wrapper
  "The page of the review of a mock-up: its title, what to look at, a choice of width and the frame with the mock-up
  in it. `page` is its entry of the registry: `:file`, `:title`, `:summary`, `:notes` and `:devices`, the widths it is for."
  [{:keys [pages themes]} {:keys [file title summary notes] page-devices :devices}]
  (chrome/page
   {:title title :pages pages :file file :themes themes :wide? true}
   [:div {:class "flex flex-wrap items-end justify-between gap-x-6 gap-y-3"}
    [:div
     [:h1 {:class "text-2xl font-semibold tracking-tight"} title]
     [:p {:class "mt-1 max-w-3xl text-muted"} summary]]
    (when (> (count page-devices) 1)
      (into [:div {:role "group" :aria-label "Width of the page" :class "flex flex-wrap gap-2"}]
            (map-indexed device-button page-devices)))]
   (when (seq notes)
     (into [:ul {:class "mt-3 max-w-3xl list-disc space-y-1 pl-5 text-sm text-muted"}]
           (for [note notes] [:li note])))
   [:div {:class "mt-6 overflow-x-auto pb-4"}
    [:iframe {:id "device-frame"
              :src (frame-path file)
              :title (str title ", the mock-up")
              :width (first page-devices)
              :height 900
              :class "mx-auto block max-w-full rounded-box border border-base-300 bg-base-100 shadow-raised"}]]))