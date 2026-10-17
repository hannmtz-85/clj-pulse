(ns pulse.design.pages-test
  "The pages of the mock-ups, rendered: each document is a whole page that passes the checks of accessibility a program
  can make, its links go somewhere, and it is the same every time it is made. A mock-up of an interface is two
  documents: the page of the review, and the interface itself in a frame."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.chrome :as chrome]
            [pulse.design.mockup :as mockup]
            [pulse.design.pages :as pages]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(def ^:private rendered
  "Every document the build writes, as `{:file :hiccup :html :frame?}`, made once."
  (delay (for [{:keys [file hiccup]} (build/documents context)]
           {:file file :hiccup hiccup :html (build/render hiccup) :frame? (str/starts-with? file "frames/")})))

(def ^:private review-pages (delay (remove :frame? @rendered)))

(def ^:private frames (delay (filter :frame? @rendered)))

(def ^:private mockups (filter :frame pages/pages))

(defn- document-of [file]
  (:hiccup (first (filter #(= file (:file %)) @rendered))))

(defn- frame-of [file]
  (document-of (mockup/frame-path file)))

;; ---------------------------------------------------------------------------
;; What there is

(deftest the-pages-are-the-ones-registered-in-the-order-they-are-linked
  (is (= ["index.html" "palette.html" "type.html" "components.html" "charts.html" "contrast.html"
          "login.html" "login-states.html"
          "portal-home.html" "portal-readings.html" "portal-new-reading.html" "portal-appointments.html" "portal-cancel.html"
          "portal-record.html" "portal-profile.html" "portal-new-patient.html" "portal-errors.html"
          "workspace-panel.html" "workspace-patient.html" "workspace-patient-sofia.html" "workspace-patient-liam.html"
          "workspace-patient-omar.html" "workspace-alerts.html" "workspace-appointments.html" "workspace-confirm.html"
          "workspace-jobs.html" "workspace-states.html"]
         (map :file pages/pages)))
  (is (= ["Overview" "Style tile" "Sign-in" "Patient portal" "Clinical workspace"] (distinct (map :group pages/pages)))))

(deftest every-page-has-its-own-file-and-its-own-name-and-is-listed-under-a-group
  (is (= (count pages/pages) (count (set (map :file pages/pages)))))
  (is (= (count pages/pages) (count (set (map :title pages/pages)))))
  (is (every? #(str/ends-with? (:file %) ".html") pages/pages))
  (is (every? #(not (str/blank? (:group %))) pages/pages)))

(deftest a-page-is-made-by-a-function-or-is-a-mockup-with-a-frame-never-both
  (doseq [{:keys [file render frame]} pages/pages]
    (is (= 1 (count (filter some? [render frame]))) file)))

(deftest a-mockup-says-what-it-shows-and-what-to-look-at-and-which-widths-it-is-for
  (is (= 21 (count mockups)))
  (doseq [{:keys [file summary notes devices listed?]} mockups]
    (is (not (str/blank? summary)) file)
    (is (seq devices) file)
    (is (every? (set (keys mockup/devices)) devices) (str file ": widths the review knows"))
    (is (= (sort devices) devices) (str file ": narrowest first"))
    (when-not (false? listed?)
      (is (and (vector? notes) (seq notes) (every? string? notes)) (str file ": a page that is listed has notes")))))

(deftest the-widths-of-a-mockup-are-those-of-its-interface
  (doseq [{:keys [file group devices]} mockups]
    (is (= (case group "Clinical workspace" [820 1280] [390 1280]) devices) file)))

(deftest the-build-writes-a-page-for-each-page-and-a-frame-for-each-mockup
  (let [files (map :file @rendered)]
    (is (= (+ (count pages/pages) (count mockups)) (count files)))
    (is (= (count files) (count (set files))))
    (is (= (set (map :file pages/pages)) (set (map :file @review-pages))))
    (is (= (set (map (comp mockup/frame-path :file) mockups)) (set (map :file @frames))))))

;; ---------------------------------------------------------------------------
;; Every document

(deftest every-document-passes-the-checks-of-accessibility
  (doseq [{:keys [file hiccup]} @rendered]
    (is (= [] (a11y/problems hiccup {:page? true})) file)))

(deftest every-document-starts-with-the-doctype-and-is-written-in-html
  (doseq [{:keys [file html]} @rendered]
    (is (str/starts-with? html "<!DOCTYPE html>\n<html ") file)
    (is (str/ends-with? html "</html>\n") file)
    (is (not (str/includes? html "/>")) (str file ": void elements as HTML writes them, not as XML does"))))

(deftest a-document-has-no-script-of-its-own-and-asks-nobody-for-anything
  (doseq [{:keys [file hiccup html frame?]} @rendered]
    (is (= [(if frame? "../review.js" "review.js")] (map #(:src (hx/attributes %)) (hx/by-tag hiccup :script))) file)
    (is (every? #(empty? (hx/child-nodes %)) (hx/by-tag hiccup :script)) (str file ": no inline script"))
    (is (empty? (hx/by-tag hiccup :style)) file)
    (is (not-any? #(contains? (hx/attributes %) :style) (hx/elements hiccup)) (str file ": no style attributes"))
    (is (not (re-find #"(?i)(href|src)=\"https?:" html)) (str file ": nothing from the network"))))

(deftest every-document-has-one-h1-and-a-title-in-its-head
  (doseq [{:keys [file hiccup]} @rendered]
    (is (= 1 (count (hx/by-tag hiccup :h1))) file)
    (is (not (str/blank? (hx/words (first (hx/by-tag hiccup :title))))) file)))

(deftest a-document-is-the-same-every-time-it-is-made
  (is (= (map :html @rendered)
         (map #(build/render (:hiccup %)) (build/documents (build/context))))))

;; ---------------------------------------------------------------------------
;; The pages of the review

(deftest the-page-of-the-review-has-the-title-of-the-page-in-its-head
  (doseq [{:keys [file hiccup]} @review-pages
          :let [title (:title (first (filter #(= file (:file %)) pages/pages)))]]
    (is (str/includes? (hx/words (first (hx/by-tag hiccup :title))) title) file)))

(deftest the-page-of-the-review-of-a-mockup-has-its-title-as-its-h1
  (doseq [{:keys [file title]} mockups]
    (is (= [title] (map hx/words (hx/by-tag (document-of file) :h1))) file)))

(deftest every-page-of-the-review-offers-the-themes-of-the-tokens
  (doseq [{:keys [file hiccup]} @review-pages
          :let [select (hx/by-id hiccup "theme-select")
                options (hx/by-tag select :option)]]
    (is (= ["pulse" "pulse-dark"] (map #(:value (hx/attributes %)) options)) file)
    (is (= ["Teal, light" "Teal, dark"] (map hx/words options)) file)
    (is (= ["pulse"] (keep #(when (:selected (hx/attributes %)) (:value (hx/attributes %))) options)) file)
    (is (= "pulse" (:data-theme (hx/attributes (first (hx/by-tag hiccup :html))))) file)))

(deftest every-page-of-the-review-links-to-the-listed-pages-and-says-which-is-the-current-one
  (let [listed (chrome/listed pages/pages)]
    (doseq [{:keys [file hiccup]} @review-pages
            :let [nav (first (hx/select hiccup #(and (= :nav (hx/tag %)) (= "Pages of the mock-ups" (:aria-label (hx/attributes %))))))
                  links (hx/by-tag nav :a)]]
      (is (= (map :file listed) (map #(:href (hx/attributes %)) links)) file)
      (is (= (if (some #(= file (:file %)) listed) [file] [])
             (keep #(when (= "page" (:aria-current (hx/attributes %))) (:href (hx/attributes %))) links))
          (str file ": the page itself, if it is listed")))))

(deftest the-navigation-has-a-menu-for-each-group-with-more-than-one-page
  (let [nav (first (hx/select (document-of "index.html") #(and (= :nav (hx/tag %)) (= "Pages of the mock-ups" (:aria-label (hx/attributes %))))))
        menus (hx/by-tag nav :details)]
    (is (= ["Overview"] (map hx/words (filter #(= :a (hx/tag %)) (hx/child-nodes nav)))) "a group of one page is a link")
    (is (= ["Style tile" "Sign-in" "Patient portal" "Clinical workspace"] (map #(hx/words (first (hx/by-tag % :summary))) menus)))
    (is (= [5 2 9 7] (map #(count (hx/by-tag % :a)) menus)) "the pages that are not listed are not in it")))

(deftest the-overview-says-what-was-decided-and-lists-the-listed-pages-under-their-groups
  (let [page (document-of "index.html")
        text (hx/words page)
        sections (hx/select page #(and (= :section (hx/tag %)) (str/starts-with? (str (:aria-labelledby (hx/attributes %))) "pages-")))
        listed-links (mapcat (fn [section] (map #(:href (hx/attributes %)) (hx/by-tag section :a))) sections)]
    (is (str/includes? text "Teal, chosen among teal, blue and indigo"))
    (is (str/includes? text "A patient can cancel their own appointment"))
    (is (str/includes? text "How to look at the mock-ups"))
    (is (= ["Style tile" "Sign-in" "Patient portal" "Clinical workspace"] (map #(hx/words (first (hx/by-tag % :h2))) sections)))
    (is (= (map :file (remove #(or (= "index.html" (:file %)) (false? (:listed? %))) pages/pages)) listed-links)
        "every page but the overview, once, in the order of the registry, and not the ones that are not listed")))

(deftest the-palette-page-shows-the-palette-in-both-schemes
  (let [page (document-of "palette.html")
        themes (keep #(:data-theme (hx/attributes %)) (rest (hx/by-tag page :div)))]
    (is (= ["pulse" "pulse-dark"] themes))
    (is (str/includes? (hx/words page) "Teal, in the light and the dark scheme"))))

(deftest the-contrast-page-says-that-nothing-is-below-its-minimum
  (let [page (document-of "contrast.html")
        text (hx/words page)]
    (is (re-find #"\d+ measurements, 0 below their minimum" text))
    (let [tables (hx/by-tag (first (hx/by-tag page :main)) :details)]
      (is (= 2 (count tables)) "a table for the palette in each scheme")
      (is (= 1 (count (filter #(:open (hx/attributes %)) tables))) "the first open"))
    (is (empty? (hx/select page #(= "Fails" (:aria-label (hx/attributes %))))))))

;; ---------------------------------------------------------------------------
;; A mockup

(deftest the-page-of-the-review-of-a-mockup-shows-its-frame-and-the-choice-of-width
  (doseq [{:keys [file title devices]} mockups
          :let [doc (document-of file)
                iframe (hx/by-id doc "device-frame")
                buttons (hx/select doc #(contains? (hx/attributes %) :data-width))]]
    (is (= (mockup/frame-path file) (:src (hx/attributes iframe))) file)
    (is (= (first devices) (:width (hx/attributes iframe))) file)
    (is (str/includes? (:title (hx/attributes iframe)) title) file)
    (is (= devices (map #(:data-width (hx/attributes %)) buttons)) file)
    (is (= (cons "true" (repeat (dec (count devices)) "false")) (map #(:aria-pressed (hx/attributes %)) buttons))
        (str file ": the first is the one in use"))))

(deftest the-frame-is-the-interface-with-no-trace-of-the-review
  (doseq [{:keys [file hiccup]} @frames]
    (is (empty? (hx/select hiccup #(= "Pages of the mock-ups" (:aria-label (hx/attributes %))))) file)
    (is (nil? (hx/by-id hiccup "theme-select")) file)
    (is (= ["../pulse.css"] (map #(:href (hx/attributes %)) (hx/by-tag hiccup :link))) file)
    (is (= "pulse" (:data-theme (hx/attributes (first (hx/by-tag hiccup :html))))) file)
    (is (empty? (hx/select hiccup #(= "device-frame" (:id (hx/attributes %))))) file)))

(deftest the-title-of-a-frame-is-its-own-and-ends-with-the-name-of-the-product
  (doseq [{:keys [file hiccup]} @frames]
    (is (str/ends-with? (hx/words (first (hx/by-tag hiccup :title))) " · Pulse") file)))

;; ---------------------------------------------------------------------------
;; The links

(defn- link-target
  "The file a link of a document leads to, or nil for a link to the same page or inside it."
  [file href]
  (cond (str/starts-with? href "#") nil
        (str/starts-with? file "frames/") (str/replace href #"^\.\./" "")
        :else href))

(deftest the-links-of-a-document-go-somewhere
  (let [files (set (map :file pages/pages))]
    (doseq [{:keys [file hiccup]} @rendered
            link (hx/by-tag hiccup :a)
            :let [href (:href (hx/attributes link))]
            :when href]
      (if (str/starts-with? href "#")
        (when (not= "#" href)
          (is (some? (hx/by-id hiccup (subs href 1))) (str file ": " href)))
        (is (contains? files (link-target file href)) (str file ": " href))))))

(deftest the-links-of-a-frame-go-to-the-review-and-not-to-another-frame
  (doseq [{:keys [file hiccup]} @frames
          link (hx/by-tag hiccup :a)
          :let [href (:href (hx/attributes link))]
          :when (and href (not (str/starts-with? href "#")))]
    (is (str/starts-with? href "../") (str file ": " href))
    (is (not (str/includes? href "frames/")) (str file ": " href))))

(deftest a-page-that-is-not-listed-is-linked-from-another-page
  (doseq [{:keys [file]} (remove #(not (false? (:listed? %))) pages/pages)
          :let [linkers (for [{linker :file hiccup :hiccup frame? :frame?} @rendered
                              :when (and frame? (not= linker (mockup/frame-path file)))
                              link (hx/by-tag hiccup :a)
                              :when (= (mockup/href file) (:href (hx/attributes link)))]
                          linker)]]
    (is (seq linkers) (str file " is not linked from any frame"))))

(deftest every-listed-page-is-linked-from-the-overview
  (let [overview-links (set (map #(:href (hx/attributes %)) (hx/by-tag (document-of "index.html") :a)))]
    (doseq [{:keys [file]} (chrome/listed pages/pages)]
      (is (contains? overview-links file) file))))

;; ---------------------------------------------------------------------------
;; The interfaces

(def ^:private current-item
  "The item of the navigation that is current on each page of an interface: what the page is a part of."
  {"portal-home.html" "Home" "portal-readings.html" "Readings" "portal-new-reading.html" "Readings"
   "portal-appointments.html" "Appointments" "portal-cancel.html" "Appointments" "portal-record.html" "My record"
   "portal-profile.html" "Profile" "portal-new-patient.html" "Home" "portal-errors.html" "Home"
   "workspace-panel.html" "Panel" "workspace-patient.html" "Panel" "workspace-patient-sofia.html" "Panel"
   "workspace-patient-liam.html" "Panel" "workspace-patient-omar.html" "Panel" "workspace-alerts.html" "Alerts"
   "workspace-appointments.html" "Appointments" "workspace-confirm.html" "Appointments" "workspace-jobs.html" "Jobs"
   "workspace-states.html" "Panel"})

(defn- navs [doc labels]
  (hx/select doc #(and (= :nav (hx/tag %)) (contains? labels (:aria-label (hx/attributes %))))))

(defn- current-labels [nav]
  (for [link (hx/by-tag nav :a)
        :when (= "page" (:aria-current (hx/attributes link)))]
    (or (:title (hx/attributes link)) (hx/words link))))

(deftest every-page-of-an-interface-says-where-it-is-in-its-navigation
  (is (= (set (map :file (filter #(#{"Patient portal" "Clinical workspace"} (:group %)) pages/pages))) (set (keys current-item))))
  (doseq [[file label] current-item
          :let [doc (frame-of file)
                portal? (str/starts-with? file "portal-")
                found (mapcat current-labels (navs doc (if portal? #{"Main" "Main, on a phone"} #{"Workspace"})))]]
    (is (= (if portal? [label label] [label]) found)
        (str file ": the bar and the dock of a portal, the sidebar of a workspace"))))

(deftest the-portal-has-a-bar-and-a-dock-and-the-workspace-a-sidebar
  (doseq [{:keys [file group]} mockups
          :let [doc (frame-of file)
                names (set (map #(:aria-label (hx/attributes %)) (hx/by-tag doc :nav)))]]
    (case group
      "Patient portal" (do (is (contains? names "Main") file) (is (contains? names "Main, on a phone") file)
                           (is (not (contains? names "Workspace")) file))
      "Clinical workspace" (do (is (contains? names "Workspace") file) (is (not (contains? names "Main")) file))
      "Sign-in" (is (not (contains? names "Main")) file))))

(deftest every-page-of-the-portal-is-for-a-finger
  (doseq [{:keys [file group]} mockups
          :when (= "Patient portal" group)
          :let [doc (frame-of file)
                buttons (hx/select doc #(and (hx/has-class? % "btn") (not (hx/has-class? % "btn-lg"))))]]
    (is (empty? buttons) (str file ": every button is a large one, 48 px high"))))

(deftest every-page-of-the-workspace-has-the-search-and-the-menu-of-the-person
  (doseq [{:keys [file group]} mockups
          :when (= "Clinical workspace" group)
          :let [doc (frame-of file)]]
    (is (some? (hx/by-id doc "search")) file)
    (is (= 1 (count (hx/select doc #(str/starts-with? (str (:aria-label (hx/attributes %))) "Account menu of")))) file)))

(deftest the-one-that-signs-in-is-the-same-person-in-the-menu-and-the-sidebar
  (testing "the practitioner"
    (let [doc (frame-of "workspace-panel.html")]
      (is (str/includes? (hx/words (first (hx/by-tag doc :aside))) "Dr. Elena Rivera"))
      (is (= ["Account menu of Dr. Elena Rivera"]
             (map #(:aria-label (hx/attributes %)) (hx/select doc #(str/starts-with? (str (:aria-label (hx/attributes %))) "Account menu of")))))))
  (testing "the administrator, who has the jobs too"
    (let [doc (frame-of "workspace-jobs.html")]
      (is (str/includes? (hx/words (first (hx/by-tag doc :aside))) "Sam Admin"))
      (is (str/includes? (hx/words (first (hx/by-tag doc :aside))) "Jobs"))))
  (testing "the practitioner has no jobs in the sidebar"
    (is (not (str/includes? (hx/words (first (hx/by-tag (frame-of "workspace-panel.html") :aside))) "Jobs"))))
  (testing "a patient"
    (is (= ["Account menu of Ana García"]
           (map #(:aria-label (hx/attributes %)) (hx/select (frame-of "portal-home.html") #(str/starts-with? (str (:aria-label (hx/attributes %))) "Account menu of")))))
    (is (= ["Account menu of Noah Kim"]
           (map #(:aria-label (hx/attributes %)) (hx/select (frame-of "portal-new-patient.html") #(str/starts-with? (str (:aria-label (hx/attributes %))) "Account menu of")))))))

(deftest the-components-page-shows-the-components-of-the-interfaces-too
  (let [page (document-of "components.html")
        main (first (hx/by-tag page :main))
        sections (map #(hx/words (first (hx/by-tag % :h2))) (hx/select main #(and (= :section (hx/tag %)) (hx/has-class? % "mt-10"))))]
    (is (= ["Buttons" "Tones and bands" "Messages" "Readings" "Forms" "Tables and tabs" "People and states"
            "Figures, lists and rows" "Navigation"]
           sections))
    (testing "a row with a date, a clock for a request, an icon, and the menu of a person that is open"
      (is (= 4 (count (hx/select main #(and (= :li (hx/tag %)) (hx/has-class? % "px-5"))))))
      (is (= 1 (count (hx/select main #(and (= :details (hx/tag %)) (true? (:open (hx/attributes %)))
                                            (hx/has-class? % "dropdown")))))))))
