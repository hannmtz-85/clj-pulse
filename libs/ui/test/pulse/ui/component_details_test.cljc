(ns pulse.ui.component-details-test
  "What a mutation analysis found the tests of the components did not notice: the size of an icon, what is left out
  when it was not given (a title, a text, a hint), and what a screen reader is told."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.ui.component :as c]
            [pulse.ui.icon :as i]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.tone :as tone]))

(defn- width [svg] (:width (hx/attributes svg)))

(defn- shapes [svg] (hx/child-nodes svg))

(defn- icon-shapes [k] (shapes (i/icon k)))

;; ---------------------------------------------------------------------------
;; Icons

(deftest the-icons-of-a-button-are-small-and-the-one-of-an-icon-button-is-medium
  (let [b (c/button {:icon :plus :icon-end :chevron-right} "x")]
    (is (= [16 16] (map width (hx/by-tag b :svg))))
    (is (= [(icon-shapes :plus) (icon-shapes :chevron-right)] (map shapes (hx/by-tag b :svg)))))
  (is (= [20] (map width (hx/by-tag (c/icon-button {:icon :x :label "Close"}) :svg)))))

(deftest a-chip-is-information-unless-it-says-otherwise
  (is (= "Information" (hx/words (c/tone-badge))))
  (is (= "Information" (hx/words (c/tone-badge {}))))
  (is (hx/has-class? (c/tone-badge {}) "bg-tone-info-soft")))

(deftest a-band-badge-has-the-icon-of-its-band-not-the-one-of-its-tone
  (doseq [[k band] tone/bands]
    (is (= (icon-shapes (:icon band)) (shapes (first (hx/by-tag (c/band-badge k) :svg)))) (name k)))
  (is (not= (icon-shapes :trending-up) (shapes (first (hx/by-tag (c/band-badge "low") :svg))))
      "low has the arrow down, and its tone, elevated, the arrow up"))

(deftest an-empty-state-has-the-inbox-and-a-large-icon-unless-told-otherwise
  (let [e (c/empty-state "Nothing")
        svg (first (hx/by-tag e :svg))]
    (is (= (icon-shapes :inbox) (shapes svg)))
    (is (= 24 (width svg))))
  (is (= (icon-shapes :bell-off) (shapes (first (hx/by-tag (c/empty-state {:icon :bell-off}) :svg))))))

;; ---------------------------------------------------------------------------
;; The bar on the edge

(defn- bars [el] (hx/select el #(hx/has-class? % "inset-y-0")))

(deftest the-bar-of-a-tile-or-an-alert-is-on-the-left-edge-and-a-thin-one
  (doseq [el [(c/vital-tile {:label "L" :value "1" :band "very-high"})
              (c/alert {:tone :critical} "x")]]
    (is (= 1 (count (bars el))))
    (is (every? (hx/classes (first (bars el))) ["absolute" "inset-y-0" "left-0" "w-1" "bg-tone-critical-edge"]))
    (is (not (hx/has-class? (first (bars el)) "right-0")))))

(deftest a-tile-with-no-band-or-a-normal-one-has-no-bar
  (is (empty? (bars (c/vital-tile {:label "L" :value "1"}))))
  (is (empty? (bars (c/vital-tile {:label "L" :value "1" :band "normal"}))))
  (is (empty? (bars (c/vital-tile {:label "L" :value "1" :band "unheard-of"})))))

(deftest the-chip-of-a-tile-is-the-small-one
  (let [tile (c/vital-tile {:label "L" :value "1" :band "high"})
        chip (first (hx/select tile #(hx/has-class? % "rounded-full")))]
    (is (hx/has-class? chip "text-xs"))
    (is (not (hx/has-class? chip "text-sm")))))

;; ---------------------------------------------------------------------------
;; Terms, titles and texts

(deftest a-term-comes-before-its-detail
  (is (= [:dt :dd :dt :dd] (map hx/tag (hx/child-nodes (c/facts [["a" "1"] ["b" "2"]])))))
  (is (= ["a" "1" "b" "2"] (map hx/words (hx/child-nodes (c/facts [["a" "1"] ["b" "2"]]))))))

(deftest the-icon-of-an-alert-has-the-colour-of-its-tone
  (let [svg (first (hx/by-tag (c/alert {:tone :critical :title "T"} "x") :svg))]
    (is (hx/has-class? svg "text-tone-critical-edge"))
    (is (hx/has-class? svg "mt-0.5"))))

(deftest an-alert-has-a-title-and-a-text-only-when-it-has-them
  (let [text-div (fn [a] (hx/select a #(hx/has-class? % "text-sm")))
        title-p (fn [a] (hx/by-tag a :p))]
    (is (empty? (text-div (c/alert {:tone :info :title "T"}))))
    (is (= 1 (count (text-div (c/alert {:tone :info} "x")))))
    (is (empty? (title-p (c/alert {:tone :info} "x"))))
    (is (= 1 (count (title-p (c/alert {:tone :info :title "T"})))))
    (is (empty? (hx/select (c/alert {:tone :info :title "T"} "x") #(hx/has-class? % "mt-1"))) "and no actions")))

(deftest an-empty-state-has-a-title-a-text-and-actions-only-when-it-has-them
  (let [titles (fn [e] (hx/select e #(and (= :p (hx/tag %)) (hx/has-class? % "font-semibold"))))
        texts (fn [e] (hx/select e #(hx/has-class? % "max-w-sm")))
        actions (fn [e] (hx/select e #(hx/has-class? % "mt-1")))]
    (is (empty? (titles (c/empty-state "text"))))
    (is (= 1 (count (titles (c/empty-state {:title "T"})))))
    (is (empty? (texts (c/empty-state {:title "T"}))))
    (is (= 1 (count (texts (c/empty-state "text")))))
    (is (empty? (actions (c/empty-state {:title "T"} "text"))))
    (is (= 1 (count (actions (c/empty-state {:actions [:a "x"]})))))))

(deftest the-wheel-of-a-spinner-is-hidden-and-its-words-are-not
  (let [s (c/spinner)
        wheel (first (hx/select s #(hx/has-class? % "loading-spinner")))
        words (first (hx/select s #(hx/has-class? % "sr-only")))]
    (is (= "true" (:aria-hidden (hx/attributes wheel))))
    (is (nil? (:aria-hidden (hx/attributes words))))))

(deftest a-page-header-has-a-description-and-actions-only-when-it-has-them
  (let [bare (c/page-header {:title "T"})]
    (is (empty? (hx/by-tag bare :p)))
    (is (= 1 (count (hx/child-nodes bare))) "the title and nothing beside it"))
  (is (= 1 (count (hx/by-tag (c/page-header {:title "T" :description "D"}) :p))))
  (is (= 2 (count (hx/child-nodes (c/page-header {:title "T" :actions [:a "x"]}))))))

;; ---------------------------------------------------------------------------
;; Forms and tables

(deftest a-field-with-no-hint-and-no-error-says-nothing-about-either
  (doseq [field [(c/text-field {:id "a" :label "A"})
                 (c/select-field {:id "a" :label "A" :options [["x" "X"]]})
                 (c/textarea-field {:id "a" :label "A"})]
          :let [control (first (hx/select field #(#{:input :select :textarea} (hx/tag %))))
                a (hx/attributes control)]]
    (is (not (contains? a :aria-describedby)) (str (hx/tag control)))
    (is (not (contains? a :aria-invalid)) (str (hx/tag control)))
    (is (not-any? #(re-find #"-error$" %) (hx/classes control)) (str (hx/tag control) " has no error style"))
    (is (empty? (hx/by-tag field :p)))))

(deftest a-text-field-is-text-unless-told-otherwise
  (is (= "text" (:type (hx/attributes (first (hx/by-tag (c/text-field {:id "a" :label "A"}) :input))))))
  (is (= "email" (:type (hx/attributes (first (hx/by-tag (c/text-field {:id "a" :label "A" :type "email"}) :input)))))))

(deftest a-checkbox-has-a-hint-and-a-description-only-when-it-has-a-hint
  (let [bare (c/checkbox-field {:id "a" :label "A"})
        hinted (c/checkbox-field {:id "a" :label "A" :hint "H"})]
    (is (empty? (hx/by-tag bare :p)))
    (is (not (contains? (hx/attributes (first (hx/by-tag bare :input))) :aria-describedby)))
    (is (= "a-hint" (:aria-describedby (hx/attributes (first (hx/by-tag hinted :input)))))))
  (testing "and so does a switch"
    (let [bare (c/toggle-field {:id "t" :label "T"})
          hinted (c/toggle-field {:id "t" :label "T" :hint "H"})]
      (is (empty? (hx/by-tag bare :p)))
      (is (not (contains? (hx/attributes (first (hx/by-tag bare :input))) :aria-describedby)))
      (is (= "t-hint" (:aria-describedby (hx/attributes (first (hx/by-tag hinted :input)))))))))

(deftest a-row-of-a-table-lights-up-under-the-pointer
  (let [t (c/data-table {:caption "C" :columns [{:key :a :label "A"}] :rows [{:a "1"} {:a "2"}]})
        rows (hx/by-tag (first (hx/by-tag t :tbody)) :tr)]
    (is (= 2 (count rows)))
    (is (every? #(hx/has-class? % "hover:bg-base-200") rows))))
