(ns pulse.design.mockup-test
  "A mock-up of an interface is two documents: the page itself, in a frame of its own, and the page of the review around
  it, with the choice of width."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.mockup :as mockup]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(def ^:private page
  {:file "demo.html" :title "Demo" :group "Patient portal" :summary "What it shows." :notes ["One thing." "Another."]
   :devices [390 1280]})

(deftest the-widths-a-mockup-is-shown-at-are-a-phone-a-tablet-and-a-desk
  (is (= {390 "Phone" 820 "Tablet" 1280 "Desktop"} mockup/devices)))

(deftest the-frame-of-a-page-is-in-the-folder-of-the-frames-and-links-go-one-level-up
  (is (= "frames/demo.html" (mockup/frame-path "demo.html")))
  (is (= "../demo.html" (mockup/href "demo.html"))))

(deftest a-frame-document-is-a-whole-page-with-the-stylesheet-and-the-script-one-level-up
  (let [doc (mockup/frame-document {:title "Home"} [:main [:h1 "Home"]])
        head (first (hx/by-tag doc :head))]
    (is (= "Home · Pulse" (hx/words (first (hx/by-tag head :title)))))
    (is (= ["../pulse.css"] (map #(:href (hx/attributes %)) (hx/by-tag head :link))))
    (is (= ["../review.js"] (map #(:src (hx/attributes %)) (hx/by-tag head :script))))
    (is (= "pulse" (:data-theme (hx/attributes (first (hx/by-tag doc :html))))))
    (is (= [] (a11y/problems doc {:page? true}))))
  (testing "the theme and the class of the body can be chosen"
    (let [doc (mockup/frame-document {:title "Home" :theme "pulse-dark" :body-class "grid"} [:main [:h1 "Home"]])]
      (is (= "pulse-dark" (:data-theme (hx/attributes (first (hx/by-tag doc :html))))))
      (is (hx/has-class? (first (hx/by-tag doc :body)) "grid")))))

(deftest the-page-of-the-review-of-a-mockup-has-the-title-the-summary-and-the-notes
  (let [doc (mockup/wrapper context page)]
    (is (= ["Demo"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words doc) "What it shows."))
    (is (= ["One thing." "Another."] (map hx/words (hx/by-tag (first (hx/select doc #(hx/has-class? % "list-disc"))) :li))))
    (is (= [] (a11y/problems doc {:page? true})))
    (testing "it is a page of the review, as wide as the window, with the choice of theme"
      (is (some? (hx/by-id doc "theme-select")))
      (is (hx/has-class? (first (hx/by-tag doc :main)) "px-6"))
      (is (not (hx/has-class? (first (hx/by-tag doc :main)) "max-w-6xl"))))))

(deftest the-mockup-is-in-a-frame-as-wide-as-the-first-device-and-as-high-as-the-page-says
  (let [doc (mockup/wrapper context page)
        iframe (hx/by-id doc "device-frame")
        a (hx/attributes iframe)]
    (is (= :iframe (hx/tag iframe)))
    (is (= "frames/demo.html" (:src a)))
    (is (= "Demo, the mock-up" (:title a)))
    (is (= 390 (:width a)))
    (is (= 900 (:height a)) "until the page inside tells how high it is")
    (is (every? (hx/classes iframe) ["mx-auto" "max-w-full" "rounded-box" "border"]))))

(deftest a-mockup-for-several-widths-has-a-button-for-each
  (let [doc (mockup/wrapper context page)
        group (first (hx/select doc #(= "Width of the page" (:aria-label (hx/attributes %)))))
        buttons (hx/by-tag group :button)]
    (is (= "group" (:role (hx/attributes group))))
    (is (= ["Phone, 390 px" "Desktop, 1280 px"] (map hx/words buttons)))
    (is (= [390 1280] (map #(:data-width (hx/attributes %)) buttons)))
    (is (= ["true" "false"] (map #(:aria-pressed (hx/attributes %)) buttons)) "the first is the one in use")
    (is (every? #(= "button" (:type (hx/attributes %))) buttons)))
  (testing "a width that has no name is only a width"
    (is (= ["700 px" "Desktop, 1280 px"]
           (map hx/words (hx/by-tag (mockup/wrapper context (assoc page :devices [700 1280])) :button))))))

(deftest a-mockup-for-one-width-has-no-choice
  (let [doc (mockup/wrapper context (assoc page :devices [1280]))]
    (is (empty? (hx/select doc #(contains? (hx/attributes %) :data-width))))
    (is (empty? (hx/select doc #(= "Width of the page" (:aria-label (hx/attributes %))))))
    (is (= 1280 (:width (hx/attributes (hx/by-id doc "device-frame")))))))

(deftest a-mockup-without-notes-has-no-list
  (doseq [notes [nil []]
          :let [doc (mockup/wrapper context (assoc page :notes notes))]]
    (is (empty? (hx/select doc #(hx/has-class? % "list-disc"))) (str (pr-str notes) ": no empty list"))
    (is (= [] (a11y/problems doc {:page? true})))))
