(ns pulse.ui.icon-layout-details-test
  "What a mutation analysis found the tests of the icons and the layout did not notice."
  (:require [clojure.test :refer [deftest is]]
            [pulse.ui.icon :as i]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.hiccup :as hx]))

(deftest an-icon-is-drawn-with-a-line-of-two-units-with-round-ends
  (let [a (hx/attributes (i/icon :bell))]
    (is (= 2 (:stroke-width a)))
    (is (= "round" (:stroke-linecap a)))
    (is (= "round" (:stroke-linejoin a)))
    (is (= "none" (:fill a)))))

(deftest a-document-says-how-wide-it-is-and-has-a-description-only-when-it-has-one
  (let [doc (layout/document {:title "T"} [:main [:h1 "T"]])
        metas (hx/by-tag (first (hx/by-tag doc :head)) :meta)
        by-name (fn [n] (first (filter #(= n (:name (hx/attributes %))) metas)))]
    (is (= "width=device-width, initial-scale=1" (:content (hx/attributes (by-name "viewport")))))
    (is (nil? (by-name "description")) "none was given, so none is written"))
  (is (= "About" (:content (hx/attributes (first (filter #(= "description" (:name (hx/attributes %)))
                                                         (hx/by-tag (layout/document {:title "T" :description "About"}) :meta))))))))

(deftest a-document-has-the-head-the-caller-gives-it-after-the-stylesheets
  (let [doc (layout/document {:title "T" :stylesheets ["a.css"] :head [[:script {:src "x.js"}] [:link {:rel "icon" :href "i.svg"}]]}
                             [:main [:h1 "T"]])
        head (first (hx/by-tag doc :head))
        tags (map hx/tag (hx/child-nodes head))]
    (is (= "x.js" (:src (hx/attributes (first (hx/by-tag head :script))))))
    (is (= ["a.css" "i.svg"] (map #(:href (hx/attributes %)) (hx/by-tag head :link))) "the stylesheets first")
    (is (= [:script] (distinct (filter #{:script} tags))))))
