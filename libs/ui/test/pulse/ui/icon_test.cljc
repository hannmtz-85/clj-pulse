(ns pulse.ui.icon-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.ui.icon :as i]
            [pulse.ui.icons :as icons]
            [pulse.ui.test.hiccup :as hx]))

(deftest an-icon-without-a-label-is-hidden-from-a-screen-reader
  (let [svg (i/icon :heart-pulse)
        a (hx/attributes svg)]
    (is (= :svg (hx/tag svg)))
    (is (= "true" (:aria-hidden a)))
    (is (nil? (:role a)))
    (is (= "0 0 24 24" (:viewBox a)))
    (is (= "currentColor" (:stroke a)) "it takes the colour of the text around it")
    (is (= "none" (:fill a)))
    (is (seq (hx/child-nodes svg)) "and it has shapes")))

(deftest an-icon-with-a-label-is-an-image-with-a-name
  (let [a (hx/attributes (i/icon :bell {:label "Alerts"}))]
    (is (= "img" (:role a)))
    (is (= "Alerts" (:aria-label a)))
    (is (nil? (:aria-hidden a)))))

(deftest sizes-are-names-or-pixels
  (is (= [14 16 20 24 32] (map #(:width (hx/attributes (i/icon :bell {:size %}))) [:xs :sm :md :lg :xl])))
  (is (= 20 (:width (hx/attributes (i/icon :bell)))) "medium by default")
  (is (= 18 (:width (hx/attributes (i/icon :bell {:size 18})))))
  (is (= (:width (hx/attributes (i/icon :bell {:size 18}))) (:height (hx/attributes (i/icon :bell {:size 18}))))))

(deftest the-class-of-the-caller-follows-the-one-that-keeps-it-from-shrinking
  (is (= "shrink-0 text-primary" (:class (hx/attributes (i/icon :bell {:class "text-primary"})))))
  (is (= "shrink-0" (:class (hx/attributes (i/icon :bell))))))

(deftest an-unknown-icon-is-an-error
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no icon called :nope" (i/icon :nope)))
  (is (true? (i/known? :bell)))
  (is (false? (i/known? :nope))))

(deftest every-icon-is-only-plain-shapes
  (let [allowed-attributes #{:d :cx :cy :r :rx :ry :x :y :x1 :x2 :y1 :y2 :width :height :points :fill}]
    (is (<= 100 (count icons/icons)))
    (doseq [[k shapes] icons/icons]
      (is (seq shapes) (str k " has shapes"))
      (doseq [[tag attributes :as shape] shapes]
        (is (contains? #{:path :circle :rect :line :polyline :polygon :ellipse} tag) (str k " has a " tag))
        (is (map? attributes) (str k " has attributes"))
        (is (every? allowed-attributes (keys attributes)) (str k ": " (pr-str (remove allowed-attributes (keys attributes)))))
        (is (every? string? (vals attributes)) (str k ": values are text"))
        (is (contains? #{nil "currentColor"} (:fill attributes)) (str k ": a fill is the colour of the text"))
        (is (not-any? #(re-find #"(?i)script|javascript:|on\w+=" %) (vals attributes)) (str k ": nothing that runs"))
        (is (= 2 (count shape)))))))

(deftest the-names-are-lower-case-with-hyphens
  (is (every? #(re-matches #"[a-z0-9]+(-[a-z0-9]+)*" (name %)) (keys icons/icons)))
  (is (every? keyword? (keys icons/icons)))
  (is (not-any? #(str/blank? (name %)) (keys icons/icons))))
