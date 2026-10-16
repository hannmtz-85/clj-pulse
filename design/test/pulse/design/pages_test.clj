(ns pulse.design.pages-test
  "The pages of the mock-ups, rendered: each one is a whole document that passes the checks of accessibility a program
  can make, its links go somewhere, and it is the same every time it is made."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.design.build :as build]
            [pulse.design.pages :as pages]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(def ^:private rendered
  "Every page as `{:file :hiccup :html}`, made once."
  (delay (for [{:keys [file] render-page :render} pages/pages
               :let [hiccup (render-page context)]]
           {:file file :hiccup hiccup :html (build/render hiccup)})))

(deftest every-page-passes-the-checks-of-accessibility
  (doseq [{:keys [file hiccup]} @rendered]
    (is (= [] (a11y/problems hiccup {:page? true})) file)))

(deftest every-page-is-a-document-that-starts-with-the-doctype-and-is-written-in-html
  (doseq [{:keys [file html]} @rendered]
    (is (str/starts-with? html "<!DOCTYPE html>\n<html ") file)
    (is (str/ends-with? html "</html>\n") file)
    (is (not (str/includes? html "/>")) (str file ": void elements as HTML writes them, not as XML does"))))

(deftest the-pages-are-the-ones-registered-and-have-their-own-names
  (is (= ["index.html" "palette.html" "type.html" "components.html" "charts.html" "contrast.html"]
         (map :file pages/pages)))
  (is (= (count pages/pages) (count (set (map :file pages/pages)))))
  (is (= (count pages/pages) (count (set (map :title pages/pages)))))
  (is (every? #(str/ends-with? (:file %) ".html") pages/pages))
  (is (every? #(not (str/blank? (:group %))) pages/pages) "each is listed under a group"))

(deftest the-overview-says-what-was-decided-and-lists-the-other-pages-under-their-groups
  (let [page (:hiccup (first (filter #(= "index.html" (:file %)) @rendered)))
        text (hx/words page)
        links (map #(:href (hx/attributes %)) (hx/select page #(and (= :a (hx/tag %)) (str/ends-with? (str (:href (hx/attributes %))) ".html"))))
        ;; the link of the logo and the ones of the navigation come first
        listed (drop (inc (count pages/pages)) links)]
    (is (str/includes? text "Teal, chosen among teal, blue and indigo"))
    (is (str/includes? text "A patient can cancel their own appointment"))
    (is (= (map :file (rest pages/pages)) listed) "every page but the overview, once, in the order of the registry")
    (is (= ["Style tile"] (map hx/words (hx/select page #(and (= :h2 (hx/tag %)) (str/starts-with? (str (:id (hx/attributes %))) "pages-"))))))))

(deftest every-page-has-the-title-in-its-head-and-as-its-one-h1
  (doseq [{:keys [file hiccup]} @rendered
          :let [title (:title (first (filter #(= file (:file %)) pages/pages)))]]
    (is (= 1 (count (hx/by-tag hiccup :h1))) file)
    (is (str/includes? (hx/words (first (hx/by-tag hiccup :title))) title) file)))

(deftest every-page-links-to-every-page-and-says-which-is-the-current-one
  (doseq [{:keys [file hiccup]} @rendered
          :let [nav (first (hx/select hiccup #(and (= :nav (hx/tag %)) (= "Pages of the mock-ups" (:aria-label (hx/attributes %))))))
                links (hx/by-tag nav :a)]]
    (is (= (map :file pages/pages) (map #(:href (hx/attributes %)) links)) file)
    (is (= [file] (keep #(when (= "page" (:aria-current (hx/attributes %))) (:href (hx/attributes %))) links))
        "exactly one, the page itself")))

(deftest the-links-of-a-page-go-somewhere
  (let [files (set (map :file pages/pages))]
    (doseq [{:keys [file hiccup]} @rendered
            link (hx/by-tag hiccup :a)
            :let [href (:href (hx/attributes link))]
            :when href]
      (cond
        (= "#" href) nil
        (str/starts-with? href "#") (is (some? (hx/by-id hiccup (subs href 1))) (str file ": " href))
        :else (is (contains? files href) (str file ": " href))))))

(deftest a-page-has-no-script-of-its-own-and-asks-nobody-for-anything
  (doseq [{:keys [file hiccup html]} @rendered]
    (is (= ["review.js"] (map #(:src (hx/attributes %)) (hx/by-tag hiccup :script))) file)
    (is (every? #(empty? (hx/child-nodes %)) (hx/by-tag hiccup :script)) (str file ": no inline script"))
    (is (empty? (hx/by-tag hiccup :style)) file)
    (is (not-any? #(contains? (hx/attributes %) :style) (hx/elements hiccup)) (str file ": no style attributes"))
    (is (not (re-find #"(?i)(href|src)=\"https?:" html)) (str file ": nothing from the network"))))

(deftest every-page-offers-the-themes-of-the-tokens
  (doseq [{:keys [file hiccup]} @rendered
          :let [select (hx/by-id hiccup "theme-select")
                options (hx/by-tag select :option)]]
    (is (= ["pulse" "pulse-dark"] (map #(:value (hx/attributes %)) options)) file)
    (is (= ["Teal, light" "Teal, dark"] (map hx/words options)) file)
    (is (= ["pulse"] (keep #(when (:selected (hx/attributes %)) (:value (hx/attributes %))) options)) file)
    (is (= "pulse" (:data-theme (hx/attributes (first (hx/by-tag hiccup :html))))) file)))

(deftest the-palette-page-shows-the-palette-in-both-schemes
  (let [page (:hiccup (first (filter #(= "palette.html" (:file %)) @rendered)))
        themes (keep #(:data-theme (hx/attributes %)) (rest (hx/by-tag page :div)))]
    (is (= ["pulse" "pulse-dark"] themes))
    (is (str/includes? (hx/words page) "Teal, in the light and the dark scheme"))))

(deftest the-contrast-page-says-that-nothing-is-below-its-minimum
  (let [page (:hiccup (first (filter #(= "contrast.html" (:file %)) @rendered)))
        text (hx/words page)]
    (is (re-find #"\d+ measurements, 0 below their minimum" text))
    (is (= 2 (count (hx/by-tag page :details))) "a table for the palette in each scheme")
    (is (= 1 (count (filter #(:open (hx/attributes %)) (hx/by-tag page :details)))) "the first open")
    (is (empty? (hx/select page #(= "Fails" (:aria-label (hx/attributes %))))))))

(deftest a-page-is-the-same-every-time-it-is-made
  (is (= (map :html @rendered)
         (for [{render-page :render} pages/pages] (build/render (render-page (build/context)))))))
