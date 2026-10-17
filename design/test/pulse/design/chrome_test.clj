(ns pulse.design.chrome-test
  "The frame around the pages of the review: a bar with the links, in a menu for each group that has several pages, the
  choice of theme, and what is listed and what is not."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [design]
            [pulse.design.chrome :as chrome]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private pages
  [{:file "index.html" :title "Overview" :group "Overview"}
   {:file "a.html" :title "A" :group "Group one"}
   {:file "b.html" :title "B" :group "Group one"}
   {:file "c.html" :title "C" :group "Group one" :listed? false}
   {:file "d.html" :title "D" :group "Group two" :listed? true}
   {:file "e.html" :title "E" :group "Hidden" :listed? false}])

(def ^:private themes [["pulse" "Teal, light"] ["pulse-dark" "Teal, dark"]])

(defn- page [file & {:as options}]
  (chrome/page (merge {:title "Page" :pages pages :file file :themes themes} options) [:h1 "Content"]))

(defn- nav [doc]
  (first (hx/select doc #(and (= :nav (hx/tag %)) (= "Pages of the mock-ups" (:aria-label (hx/attributes %)))))))

(deftest a-page-is-listed-unless-it-says-it-is-not
  (is (= ["index.html" "a.html" "b.html" "d.html"] (map :file (chrome/listed pages))))
  (is (= [] (chrome/listed [])))
  (is (= [{:file "x.html"}] (chrome/listed [{:file "x.html"}])) "a page that says nothing is listed")
  (is (= [{:file "x.html" :listed? nil}] (chrome/listed [{:file "x.html" :listed? nil}])) "and so is one that says nil"))

(deftest the-groups-are-in-the-order-they-first-appear-and-have-only-the-listed-pages
  (is (= [["Overview" ["index.html"]] ["Group one" ["a.html" "b.html"]] ["Group two" ["d.html"]]]
         (map (fn [[group group-pages]] [group (map :file group-pages)]) (chrome/groups pages))))
  (is (not-any? #(= "Hidden" (first %)) (chrome/groups pages)) "a group with nothing listed is not there"))

(deftest a-group-is-an-id-in-lower-case-with-hyphens
  (is (= ["patient-portal" "sign-in" "style-tile" "clinical-workspace" "a-b-c"]
         (map chrome/slug ["Patient portal" "Sign-in" "Style tile" "Clinical workspace" "A & B / C"]))))

(deftest the-themes-are-those-of-the-tokens-with-their-labels
  (is (= [["pulse" "Teal, light"] ["pulse-dark" "Teal, dark"]] (chrome/theme-options (design/read-tokens)))))

(deftest a-group-of-one-page-is-a-link-and-a-group-of-several-is-a-menu
  (let [n (nav (page "index.html"))
        menus (hx/by-tag n :details)]
    (is (= ["Overview" "D"] (map hx/words (filter #(= :a (hx/tag %)) (hx/child-nodes n)))) "the groups of one page")
    (is (= ["Group one"] (map #(hx/words (first (hx/by-tag % :summary))) menus)) "and the one of two")
    (is (= ["A" "B"] (map hx/words (hx/by-tag (first menus) :a))) "the one that is not listed is not in it")
    (is (every? #(hx/has-class? % "dropdown") menus))))

(deftest the-page-it-is-on-is-marked-in-the-bar-and-in-the-menu
  (testing "a page in a menu: the summary of its group, and its own link"
    (let [n (nav (page "b.html"))
          menu (first (hx/by-tag n :details))
          [link-a link-b] (hx/by-tag menu :a)]
      (is (hx/has-class? (first (hx/by-tag menu :summary)) "bg-accent"))
      (is (not (hx/has-class? (first (hx/by-tag menu :summary)) "text-base-content")))
      (is (= [nil "page"] (map #(:aria-current (hx/attributes %)) [link-a link-b])))
      (is (= [false true] (map #(hx/has-class? % "menu-active") [link-a link-b])))
      (testing "and no other link is the current one"
        (is (= ["page"] (keep #(:aria-current (hx/attributes %)) (hx/by-tag n :a)))))))
  (testing "a page that is a link of its own"
    (let [n (nav (page "d.html"))
          links (filter #(= :a (hx/tag %)) (hx/child-nodes n))]
      (is (= [nil "page"] (map #(:aria-current (hx/attributes %)) links)))
      (is (hx/has-class? (second links) "bg-accent"))
      (is (not (hx/has-class? (first links) "bg-accent")))
      (is (hx/has-class? (first links) "hover:bg-base-200"))
      (is (not (hx/has-class? (first (hx/by-tag (first (hx/by-tag n :details)) :summary)) "bg-accent")) "the menu is not")))
  (testing "a page that is not listed marks nothing"
    (is (empty? (hx/select (nav (page "c.html")) #(= "page" (:aria-current (hx/attributes %))))))
    (is (empty? (hx/select (nav (page "c.html")) #(hx/has-class? % "bg-accent"))))))

(deftest a-page-is-as-wide-as-the-window-when-it-asks-for-it-and-in-a-column-otherwise
  (let [classes (fn [doc] [(hx/classes (first (hx/by-tag doc :main))) (hx/classes (first (hx/select doc #(= :div (hx/tag %)))))])]
    (is (every? #(and (contains? % "px-6") (not (contains? % "max-w-6xl"))) (classes (page "a.html" :wide? true))))
    (is (every? #(and (contains? % "mx-auto") (contains? % "max-w-6xl") (contains? % "px-4") (not (contains? % "px-6")))
                (classes (page "a.html"))))))

(deftest a-page-has-the-stylesheet-the-script-the-title-and-the-theme
  (let [doc (page "a.html" :title "Alerts")
        head (first (hx/by-tag doc :head))]
    (is (= "Alerts · Pulse design" (hx/words (first (hx/by-tag head :title)))))
    (is (= ["pulse.css"] (map #(:href (hx/attributes %)) (hx/by-tag head :link))))
    (is (= ["review.js"] (map #(:src (hx/attributes %)) (hx/by-tag head :script))))
    (is (= "pulse" (:data-theme (hx/attributes (first (hx/by-tag doc :html))))))
    (is (= "pulse-dark" (:data-theme (hx/attributes (first (hx/by-tag (page "a.html" :theme "pulse-dark") :html))))))
    (is (= [] (a11y/problems doc {:page? true})))))

(deftest the-theme-is-chosen-in-a-select-with-a-label
  (let [doc (page "a.html" :theme "pulse-dark")
        select (hx/by-id doc "theme-select")]
    (is (= ["Theme"] (map hx/words (hx/select doc #(and (= :label (hx/tag %)) (= "theme-select" (:for (hx/attributes %))))))))
    (is (= [["pulse" "Teal, light"] ["pulse-dark" "Teal, dark"]] (map (juxt #(:value (hx/attributes %)) hx/words) (hx/by-tag select :option))))
    (is (= ["pulse-dark"] (keep #(when (:selected (hx/attributes %)) (:value (hx/attributes %))) (hx/by-tag select :option))))))

(deftest a-page-says-that-it-is-for-the-review-and-that-nothing-is-connected
  (let [doc (page "a.html")]
    (is (= "index.html" (:href (hx/attributes (first (hx/by-tag (first (hx/by-tag doc :header)) :a))))) "the mark leads to the overview")
    (is (= "Pulse" (hx/words (first (hx/by-tag (first (hx/by-tag doc :header)) :a)))))
    (is (some #(= "design" (hx/words %)) (hx/by-tag (first (hx/by-tag doc :header)) :span)) "and the word that says what this is")
    (is (str/includes? (hx/words (first (hx/by-tag doc :footer))) "Mock-ups of Pulse, made from the design tokens and the components of libs/ui with the synthetic cohort. Nothing here is connected to anything."))))
