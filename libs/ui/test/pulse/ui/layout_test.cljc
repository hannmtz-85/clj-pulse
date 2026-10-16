(ns pulse.ui.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private page
  (layout/document {:title "Alerts" :theme "pulse-dark" :stylesheets ["pulse.css"] :description "The alert inbox."}
                   (layout/skip-link "main")
                   [:main {:id "main"} [:h1 "Alerts"]]))

(deftest a-document-has-a-language-a-title-a-viewport-and-the-stylesheets
  (let [html (first (hx/by-tag page :html))
        head (first (hx/by-tag page :head))]
    (is (= "en" (:lang (hx/attributes html))))
    (is (= "pulse-dark" (:data-theme (hx/attributes html))))
    (is (= "Alerts" (hx/words (first (hx/by-tag head :title)))))
    (is (some #(= "viewport" (:name (hx/attributes %))) (hx/by-tag head :meta)))
    (is (some #(= "utf-8" (:charset (hx/attributes %))) (hx/by-tag head :meta)))
    (is (some #(= "The alert inbox." (:content (hx/attributes %))) (hx/by-tag head :meta)))
    (is (= ["pulse.css"] (map #(:href (hx/attributes %)) (hx/by-tag head :link))))
    (is (= "stylesheet" (:rel (hx/attributes (first (hx/by-tag head :link))))))))

(deftest a-document-follows-the-browser-when-it-has-no-theme-and-says-so
  (let [doc (layout/document {:title "T"} [:main [:h1 "T"]])]
    (is (not (contains? (hx/attributes (first (hx/by-tag doc :html))) :data-theme)))
    (is (some #(and (= "color-scheme" (:name (hx/attributes %))) (= "light dark" (:content (hx/attributes %))))
              (hx/by-tag doc :meta)))
    (is (= "es" (:lang (hx/attributes (first (hx/by-tag (layout/document {:title "T" :lang "es"}) :html))))))))

(deftest a-document-has-no-inline-script-or-style
  (is (empty? (hx/by-tag page :script)))
  (is (empty? (hx/by-tag page :style)))
  (is (not-any? #(contains? (hx/attributes %) :style) (hx/elements page))
      "so a strict Content-Security-Policy accepts it")
  (is (not-any? (fn [el] (some #(str/starts-with? (name %) "on") (keys (hx/attributes el)))) (hx/elements page))))

(deftest the-body-has-the-colours-of-the-theme
  (let [body (first (hx/by-tag page :body))]
    (is (every? (hx/classes body) ["bg-base-200" "text-base-content" "min-h-screen"]))
    (is (hx/has-class? (first (hx/by-tag (layout/document {:title "T" :body-class "grid"}) :body)) "grid"))))

(deftest the-skip-link-comes-first-and-points-at-the-main-content
  (let [link (first (hx/by-tag page :a))]
    (is (= "#main" (:href (hx/attributes link))))
    (is (= "Skip to the content" (hx/words link)))
    (is (hx/has-class? link "sr-only") "hidden until it has focus")
    (is (hx/has-class? link "focus:not-sr-only"))
    (is (= link (first (hx/by-tag (first (hx/by-tag page :body)) :a))))))

(deftest a-whole-page-passes-the-checks-of-a-page
  (is (= [] (a11y/problems page {:page? true}))))
