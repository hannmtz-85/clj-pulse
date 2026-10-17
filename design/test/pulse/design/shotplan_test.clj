(ns pulse.design.shotplan-test
  "What the screenshots of the mock-ups are: which pages, in which theme and at which width, and what each file is
  called."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.pages :as pages]
            [pulse.design.shotplan :as plan]))

(deftest a-picture-is-named-after-its-page-its-theme-and-its-width
  (let [page {:file "components.html"}]
    (is (= "components-light-1280.png" (plan/file-name page "pulse" 1280 nil)))
    (is (= "components-dark-390.png" (plan/file-name page "pulse-dark" 390 nil)))
    (is (= "components-teal-820.png" (plan/file-name page "pulse-teal" 820 nil)) "any other theme, without its prefix")
    (is (= "components-light-1280-y600.png" (plan/file-name page "pulse" 1280 {:y 600 :height 400})) "a slice has where it starts")))

(deftest the-arguments-are-the-prefixes-of-pages-and-the-options
  (is (= {:only []} (plan/parse-args [])))
  (is (= {:only ["portal" "login"]} (plan/parse-args ["portal" "login"])))
  (is (= {:only ["portal"] :theme "pulse-dark" :width 390 :clip {:y 100 :height 600}}
         (plan/parse-args ["portal" "--theme" "pulse-dark" "--width" "390" "--clip" "100:600"])))
  (is (= {:only ["a" "b"] :width 1280} (plan/parse-args ["a" "--width" "1280" "b"])) "in any order"))

(deftest a-page-of-the-style-tile-is-taken-at-1280-pixels-in-both-themes
  (is (= [["components.html" "pulse" 1280 false] ["components.html" "pulse-dark" 1280 false]]
         (map (juxt :page :theme :width :mobile) (plan/default-shots {:only ["components"]})))))

(deftest a-mockup-is-taken-as-it-is-in-its-frame-at-each-of-its-widths
  (is (= [["frames/portal-home.html" "pulse" 390 true "portal-home-light-390.png"]
          ["frames/portal-home.html" "pulse" 1280 false "portal-home-light-1280.png"]
          ["frames/portal-home.html" "pulse-dark" 390 true "portal-home-dark-390.png"]
          ["frames/portal-home.html" "pulse-dark" 1280 false "portal-home-dark-1280.png"]]
         (map (juxt :page :theme :width :mobile :name) (plan/default-shots {:only ["portal-home"]}))))
  (is (= [820 1280] (map :width (take 2 (plan/default-shots {:only ["workspace-panel"]}))))
      "a workspace is for a tablet and a desk")
  (is (= [true false] (map :mobile (take 2 (plan/default-shots {:only ["portal-home"]}))))
      "a phone is taken as a phone"))

(deftest what-is-asked-for-narrows-the-pictures
  (testing "a theme, a width and a slice"
    (let [shots (plan/default-shots {:only ["portal-home"] :theme "pulse-dark" :width 390 :clip {:y 100 :height 600}})]
      (is (= [{:page "frames/portal-home.html" :theme "pulse-dark" :width 390 :mobile true
               :name "portal-home-dark-390-y100.png" :clip {:y 100 :height 600}}]
             shots))))
  (testing "a width asked for is taken whatever the page is for"
    (is (= [1000 1000] (map :width (plan/default-shots {:only ["workspace-panel"] :width 1000})))))
  (testing "a prefix takes every page that starts with it, and one that matches nothing takes none"
    (is (= 4 (count (set (map :page (plan/default-shots {:only ["workspace-patient"]}))))))
    (is (empty? (plan/default-shots {:only ["nothing"]}))))
  (testing "no prefix takes every page"
    (is (= (set (map :file pages/pages))
           (set (map #(str/replace (:page %) #"^frames/" "") (plan/default-shots {}))))))
  (testing "a width under 600 pixels is a phone"
    (is (= [[599 true]] (map (juxt :width :mobile) (plan/default-shots {:only ["components"] :theme "pulse" :width 599}))))
    (is (= [[600 false]] (map (juxt :width :mobile) (plan/default-shots {:only ["components"] :theme "pulse" :width 600}))))))

(deftest every-picture-is-of-a-page-the-build-writes
  (let [written (set (map :file (build/documents (build/context))))]
    (doseq [{:keys [page]} (plan/default-shots {})]
      (is (contains? written page) page))))
