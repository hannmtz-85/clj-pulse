(ns pulse.ui.a11y-test
  "The checker of accessibility is itself checked: each rule has a tree it must accept and one it must refuse,
  because a rule that never fires would pass every page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.test.a11y :as a11y]))

(defn- flagged?
  "Whether the problems of `tree` include one that matches `pattern`."
  ([pattern tree] (flagged? pattern tree nil))
  ([pattern tree options]
   (boolean (some #(re-find pattern %) (a11y/problems tree options)))))

(deftest a-control-needs-a-label
  (is (flagged? #"input \(text\) with id \"a\" has no label" [:div [:input {:id "a" :type "text"}]]))
  (is (flagged? #"select with id \"a\" has no label" [:div [:select {:id "a"}]]))
  (is (flagged? #"textarea with id \"a\" has no label" [:div [:textarea {:id "a"}]]))
  (testing "a label for it, an aria-label or an aria-labelledby is enough"
    (is (empty? (a11y/problems [:div [:label {:for "a"} "A"] [:input {:id "a"}]])))
    (is (empty? (a11y/problems [:input {:aria-label "A"}])))
    (is (empty? (a11y/problems [:div [:h2 {:id "t"} "T"] [:input {:aria-labelledby "t"}]]))))
  (testing "hidden inputs and buttons are not controls to label"
    (is (empty? (a11y/problems [:div [:input {:type "hidden" :name "csrf"}] [:input {:type "submit" :value "Go"}]])))))

(deftest a-button-or-a-link-needs-a-name
  (is (flagged? #"button has no name" [:button [:svg {:aria-hidden "true"}]]))
  (is (flagged? #"link to /x has no name" [:a {:href "/x"} [:svg {:aria-hidden "true"}]]))
  (is (empty? (a11y/problems [:button "Save"])))
  (is (empty? (a11y/problems [:button {:aria-label "Close"} [:svg {:aria-hidden "true"}]])))
  (is (empty? (a11y/problems [:button [:svg {:aria-hidden "true"}] [:span {:class "sr-only"} "Close"]])))
  (is (empty? (a11y/problems [:a {:href "/x"} [:svg {:role "img" :aria-label "Home"}]])))
  (testing "an anchor with no href is not a link"
    (is (empty? (a11y/problems [:a {:id "top"}])))))

(deftest an-image-needs-an-alt
  (is (flagged? #"image has no alt" [:img {:src "a.png"}]))
  (is (empty? (a11y/problems [:img {:src "a.png" :alt ""}])) "an empty alt says it is decoration")
  (is (empty? (a11y/problems [:img {:src "a.png" :alt "A chart"}]))))

(deftest an-svg-is-hidden-or-an-image-with-a-name
  (is (flagged? #"svg is neither hidden" [:svg {:viewBox "0 0 1 1"}]))
  (is (flagged? #"svg is neither hidden" [:svg {:role "img"}]) "an image with no name")
  (is (flagged? #"svg is neither hidden" [:svg {:aria-hidden "false"}]))
  (is (empty? (a11y/problems [:svg {:aria-hidden "true"}])))
  (is (empty? (a11y/problems [:svg {:role "img" :aria-label "Heart"}])))
  (is (empty? (a11y/problems [:div [:svg {:role "img" :aria-labelledby "t"} [:title {:id "t"} "Heart"]]]))))

(deftest an-id-is-used-once-and-what-points-at-one-finds-it
  (is (flagged? #"id \"a\" is used more than once" [:div [:p {:id "a"}] [:p {:id "a"}]]))
  (is (flagged? #"aria-describedby points at \"nope\"" [:input {:aria-label "A" :aria-describedby "nope"}]))
  (is (flagged? #"aria-labelledby points at \"nope\"" [:svg {:role "img" :aria-labelledby "nope"}]))
  (is (flagged? #":for points at \"nope\"" [:label {:for "nope"} "A"]))
  (is (empty? (a11y/problems [:div [:input {:aria-label "A" :aria-describedby "h e"}] [:p {:id "h"}] [:p {:id "e"}]]))
      "several ids in one attribute"))

(deftest a-table-has-a-caption-and-its-headings-have-a-scope
  (is (flagged? #"table has no caption" [:table [:tbody [:tr [:td "x"]]]]))
  (is (flagged? #"th has no scope" [:table [:caption "C"] [:tr [:th "x"]]]))
  (is (empty? (a11y/problems [:table [:caption "C"] [:tr [:th {:scope "col"} "x"]]])))
  (is (empty? (a11y/problems [:table {:aria-label "C"} [:tr [:th {:scope "col"} "x"]]]))))

(deftest headings-do-not-skip-a-level
  (is (flagged? #"level 3 follows one of level 1" [:main [:h1 "A"] [:h3 "B"]]))
  (is (empty? (a11y/problems [:main [:h1 "A"] [:h2 "B"] [:h3 "C"] [:h2 "D"]])))
  (testing "going back up by any number of levels is fine"
    (is (empty? (a11y/problems [:main [:h1 "A"] [:h2 "B"] [:h3 "C"] [:h2 "D"] [:h3 "E"]])))
    (is (empty? (a11y/problems [:main [:h1 "A"] [:h2 "B"] [:h3 "C"] [:h1 "D"]])))))

(deftest the-order-of-focus-is-the-natural-one
  (is (flagged? #"positive tabindex" [:div {:tabindex "3"} "x"]))
  (is (empty? (a11y/problems [:div {:tabindex "0"} "x"])))
  (is (empty? (a11y/problems [:div {:tabindex "-1"} "x"]))))

(deftest a-details-has-a-summary-and-several-navs-are-told-apart
  (is (flagged? #"details has no summary" [:details [:p "x"]]))
  (is (empty? (a11y/problems [:details [:summary "More"] [:p "x"]])))
  (is (flagged? #"several navs" [:div [:nav {:aria-label "A"} [:a {:href "/"} "x"]] [:nav [:a {:href "/"} "y"]]]))
  (is (empty? (a11y/problems [:div [:nav [:a {:href "/"} "x"]]])) "one nav needs no label"))

(deftest a-page-has-a-language-a-title-a-viewport-one-h1-and-a-main
  (let [good [:html {:lang "en"} [:head [:title "T"] [:meta {:name "viewport" :content "width=device-width"}]]
              [:body [:main [:h1 "T"]]]]
        page? #(a11y/problems % {:page? true})]
    (is (= [] (page? good)))
    (is (flagged? #"no lang" (assoc-in good [1 :lang] "") {:page? true}))
    (is (flagged? #"no title" (assoc-in good [2 1] [:title " "]) {:page? true}))
    (is (flagged? #"no viewport" (assoc-in good [2 2] [:meta {:name "other"}]) {:page? true}))
    (is (flagged? #"no main" (assoc-in good [3 1] [:div [:h1 "T"]]) {:page? true}))
    (is (flagged? #"2 h1" (assoc-in good [3 1] [:main [:h1 "T"] [:h1 "U"]]) {:page? true}))
    (is (flagged? #"0 h1" (assoc-in good [3 1] [:main [:h2 "T"]]) {:page? true}))
    (is (flagged? #"no html element" [:main [:h1 "T"]] {:page? true})))
  (testing "a fragment is not asked to be a page"
    (is (empty? (a11y/problems [:main [:p "x"]])))))

(deftest a-link-inside-the-page-leads-somewhere
  (let [page (fn [body] [:html {:lang "en"} [:head [:title "T"] [:meta {:name "viewport" :content "width=device-width"}]]
                         [:body body]])
        skip-to (fn [target main-id] (page (list [:a {:href target} "Skip to the content"] [:main {:id main-id} [:h1 "T"]])))]
    (testing "a skip link to an id the page has"
      (is (= [] (a11y/problems (skip-to "#main" "main") {:page? true}))))
    (testing "to one it has not, whichever of the two is wrong"
      (is (flagged? #"link to #content points at an id that is not in the page" (skip-to "#content" "main") {:page? true}))
      (is (flagged? #"link to #main points at an id that is not in the page" (skip-to "#main" "content") {:page? true})))
    (testing "a link to the page itself, to another page or with a fragment of another page is not one of these"
      (is (= [] (a11y/problems (skip-to "#" "main") {:page? true})))
      (is (= [] (a11y/problems (page (list [:a {:href "/x#nowhere"} "Elsewhere"] [:main [:h1 "T"]])) {:page? true})))
      (is (= [] (a11y/problems (page (list [:a {:href "/"} "Home"] [:a [:span "no href"]] [:main [:h1 "T"]])) {:page? true}))))
    (testing "the id may be on any element, and a fragment is not asked"
      (is (= [] (a11y/problems (page (list [:a {:href "#top"} "Top"] [:header {:id "top"} "H"] [:main [:h1 "T"]])) {:page? true})))
      (is (empty? (a11y/problems [:a {:href "#nowhere"} "x"]))))))

(deftest sequences-from-for-are-read-like-any-other-children
  (is (flagged? #"input with id \"i0\" has no label" [:div (for [i (range 2)] [:input {:id (str "i" i)}])]))
  (is (empty? (a11y/problems [:div (for [i (range 2)] [:label {:for (str "i" i)} i]) (for [i (range 2)] [:input {:id (str "i" i)}])]))))

(deftest what-is-fine-is-not-reported
  (is (= [] (a11y/problems [:main [:h1 "Title"] [:p "Text " [:a {:href "/x"} "a link"] "."]])))
  (is (= [] (a11y/problems nil)))
  (is (= [] (a11y/problems "just text")))
  (is (not (str/blank? (str (a11y/problems [:input {:id "a"}]))))))
