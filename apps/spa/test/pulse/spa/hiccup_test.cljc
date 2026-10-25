(ns pulse.spa.hiccup-test
  "The adapter between the components of `libs/ui` and Reagent (ADR-0029, point 2): a list of children gets keys, an attribute gets the
  name that React wants, and nothing else changes. These run on the JVM and on Node; what React says of the result is in
  `render-test`, on Node."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.hiccup :as hiccup]
            [pulse.ui.chart :as chart]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]))

(def ^:private misspelt
  "The attributes that HTML writes in one way and React in another, which the components may write for a server."
  #{:tabindex :colspan :rowspan :maxlength :minlength :autocomplete :autofocus :novalidate :readonly :enctype :datetime
    :accesskey :contenteditable :crossorigin :srcset :usemap})

(defn- element? [x] (and (vector? x) (keyword? (first x))))

(defn- unkeyed
  "The elements that are children in a list, which is a sequence, and have no `:key`."
  [x]
  (cond
    (element? x) (mapcat unkeyed (rest x))
    (seq? x) (mapcat (fn [y] (concat (when (and (vector? y) (nil? (:key (meta y)))) [y]) (unkeyed y))) x)
    :else []))

(defn- attribute-names
  "Every attribute name that an element anywhere in `x` has."
  [x]
  (cond
    (element? x) (concat (when (map? (second x)) (keys (second x))) (mapcat attribute-names (rest x)))
    (sequential? x) (mapcat attribute-names x)
    :else []))

(defn- skeleton
  "What `x` is made of, apart from attributes and keys: the tag of each element and what it holds, with lists spliced."
  [x]
  (cond
    (element? x) (into [(first x)] (mapcat (fn [y] (cond (map? y) [] (seq? y) (skeleton y) :else [(skeleton y)]))) (rest x))
    (seq? x) (vec (mapcat (fn [y] (let [s (skeleton y)] (if (seq? y) s [s]))) x))
    :else x))

(def ^:private samples
  "Hiccup as the components make it, with lists in it (`for`), `:tabindex`, a chart, a form, a menu and a whole frame."
  [(c/button {:variant :primary} "Save")
   (c/alert {:tone :critical :title "Very high" :actions [(c/button {} "Acknowledge")]} "Blood pressure")
   (c/card {:title "Medication"} (c/facts [["Name" "Lisinopril"] ["Dose" "10 mg"]]))
   (c/text-field {:id "dose" :label "Dose" :value "10" :error "Enter a number" :hint "In milligrams"})
   (c/select-field {:id "unit" :label "Unit" :options [["mg" "mg"] ["ml" "ml"]] :value "mg"})
   (c/user-menu {:name "Elena Rivera" :items [{:href "/profile" :label "Profile" :icon :user}]})
   (c/data-table {:caption "Patients" :columns [{:key :name :label "Name"} {:key :age :label "Age"}]
                  :rows [{:name "Ana" :age 58} {:name "Liam" :age 46}]})
   (chart/line-chart {:id "bp" :title "Systolic" :description "A month of blood pressure" :x-label str
                      :bands [{:from 90 :to 130 :tone :normal :label "Normal"}]
                      :thresholds [{:at 150 :tone :high :label "R1"}]
                      :series [{:label "Systolic" :unit "mmHg" :points [[1 120] [2 126] [3 131] [4 160]]}]})
   (layout/workspace-shell {:current :panel
                            :nav [{:items [{:id :panel :href "/" :label "Panel" :icon :layout-dashboard}
                                           {:id :alerts :href "/alerts" :label "Alerts" :icon :bell :count 2 :count-label "2 active"}]}]
                            :search {:id "search" :name "q" :label "Search patients"}
                            :user {:name "Elena Rivera" :items []}}
                           [:h1 "Panel"]
                           (for [n (range 3)] [:p {:tabindex "0"} (str "Row " n)]))])

(deftest the-check-can-fail
  (testing "the samples have lists without keys and attributes that React does not take, so that the checks below have something to find"
    (is (> (count (mapcat unkeyed samples)) 20))
    (is (contains? (set (mapcat attribute-names samples)) :tabindex))))

(deftest every-list-gets-keys-by-position
  (is (= [[:li {} "a"] [:li {} "b"] [:li {} "c"]]
         (vec (second (hiccup/->reagent [:ul (for [x ["a" "b" "c"]] [:li {} x])])))))
  (is (= [0 1 2]
         (map (comp :key meta) (second (hiccup/->reagent [:ul (for [x ["a" "b" "c"]] [:li x])])))))
  (testing "a child that has a key keeps it"
    (is (= [:first 1]
           (map (comp :key meta) (second (hiccup/->reagent [:ul (list ^{:key :first} [:li "a"] [:li "b"])]))))))
  (testing "a list in a list has its own positions"
    (is (= [[0 1] [0 1]]
           (map #(map (comp :key meta) %) (second (hiccup/->reagent [:div (list (list [:p "a"] [:p "b"]) (list [:p "c"] [:p "d"]))]))))))
  (testing "text and numbers in a list are not given a key, which they cannot have"
    (is (= ["a" 1 nil] (vec (second (hiccup/->reagent [:p (list "a" 1 nil)])))))))

(deftest every-attribute-gets-the-name-react-wants
  (is (= [:td {:col-span 2 :row-span 3 :class "x"} "cell"] (hiccup/->reagent [:td {:colspan 2 :rowspan 3 :class "x"} "cell"])))
  (is (= [:div {:tab-index "0" :aria-label "Numbers"}] (hiccup/->reagent [:div {:tabindex "0" :aria-label "Numbers"}])))
  (is (= {:auto-complete "off" :max-length 5 :read-only true :no-validate true :auto-focus true}
         (second (hiccup/->reagent [:input {:autocomplete "off" :maxlength 5 :readonly true :novalidate true :autofocus true}]))))
  (testing "an attribute that needs nothing is left as it is, and so is its value"
    (let [attributes {:class "a b" :for "x" :viewBox "0 0 1 1" :stroke-width 2 :data-x "1" :aria-hidden "true" :on-click inc :value "7" :id "i"}]
      (is (= attributes (second (hiccup/->reagent [:svg attributes]))))))
  (testing "every name that React spells in another way is renamed"
    (is (empty? (filter misspelt (attribute-names (hiccup/->reagent [:p (into {} (map (fn [k] [k "1"])) misspelt)])))))))

(deftest what-is-not-an-element-is-left-alone
  (is (= "text" (hiccup/->reagent "text")))
  (is (= 7 (hiccup/->reagent 7)))
  (is (nil? (hiccup/->reagent nil)))
  (testing "a component of Reagent and its arguments are not walked"
    (let [f (fn [& _])
          form [f {:tabindex "0"} [:li "a"] (list [:li "b"])]]
      (is (= form (hiccup/->reagent form)))))
  (testing "a vector of vectors is not hiccup that is turned into an element: it is what it was"
    (let [v [[:li "a"] [:li "b"]]]
      (is (= v (hiccup/->reagent v))))))

(deftest the-metadata-of-an-element-stays
  (is (= {:key "k"} (meta (hiccup/->reagent ^{:key "k"} [:li "a"]))))
  (is (= {:key "k"} (meta (first (second (hiccup/->reagent [:ul (list ^{:key "k"} [:li "a"])])))))
      "a key that an element had is the key of an element in a list"))

(deftest what-the-components-make-is-made-ready-and-no-more
  (doseq [sample samples
          :let [ready (hiccup/->reagent sample)]]
    (is (empty? (unkeyed ready)) "every child of a list has a key")
    (is (empty? (filter misspelt (attribute-names ready))) "no attribute is spelt as HTML does and React does not take")
    (is (= (skeleton sample) (skeleton ready)) "no element is added, taken away or moved")))

(deftest it-is-done-once-and-the-same-when-it-is-done-again
  (doseq [sample samples
          :let [once (hiccup/->reagent sample)]]
    (is (= (skeleton once) (skeleton (hiccup/->reagent once))))
    (is (empty? (unkeyed (hiccup/->reagent once))))))
