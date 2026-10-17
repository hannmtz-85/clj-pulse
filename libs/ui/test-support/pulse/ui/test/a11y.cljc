(ns pulse.ui.test.a11y
  "Accessibility rules that can be checked on hiccup (ADR-0026): the part of WCAG 2.2 AA that a program can see.
  `problems` returns a list of sentences, empty when the tree has none; a test asserts it is empty for a
  component and for a whole page. Colour contrast is not here (`bb design:check` measures it), nor is
  anything that needs a browser (focus order, reflow, target size).

  `problems` takes `:page? true` for a whole document, which also has a language, a title, a viewport, one `h1` and
  a `main`."
  (:require [clojure.string :as str]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private input-types-without-label
  #{"hidden" "submit" "button" "reset" "image"})

(defn- label-targets
  "The ids that a `<label for>` in the tree points at."
  [tree]
  (set (keep #(:for (hx/attributes %)) (hx/by-tag tree :label))))

(defn- accessible-name?
  "Whether an element has a name a screen reader can say: text inside, or `aria-label`, `aria-labelledby` or a
  `<title>` for an SVG, or an `alt` on an image inside."
  [el]
  (let [a (hx/attributes el)]
    (or (not (str/blank? (:aria-label a)))
        (not (str/blank? (:aria-labelledby a)))
        (not (str/blank? (hx/words el)))
        (some #(not (str/blank? (:alt (hx/attributes %)))) (hx/by-tag el :img))
        (some #(and (= :svg (hx/tag %)) (not (str/blank? (:aria-label (hx/attributes %))))) (rest (hx/elements el))))))

(defn- control-problems [tree]
  (let [targets (label-targets tree)]
    (for [el (hx/elements tree)
          :let [t (hx/tag el) a (hx/attributes el)]
          :when (and (#{:input :select :textarea} t)
                     (not (input-types-without-label (:type a))))
          :when (not (or (contains? targets (:id a))
                         (not (str/blank? (:aria-label a)))
                         (not (str/blank? (:aria-labelledby a)))))]
      (str (name t) (when (:type a) (str " (" (:type a) ")")) " with id " (pr-str (:id a)) " has no label"))))

(defn- name-problems [tree]
  (concat
   (for [el (hx/by-tag tree :button)
         :when (not (accessible-name? el))]
     "a button has no name: give it text or an aria-label")
   (for [el (hx/by-tag tree :a)
         :when (and (:href (hx/attributes el)) (not (accessible-name? el)))]
     (str "a link to " (:href (hx/attributes el)) " has no name"))
   (for [el (hx/by-tag tree :img)
         :when (nil? (:alt (hx/attributes el)))]
     "an image has no alt")))

(defn- svg-problems [tree]
  (for [el (hx/by-tag tree :svg)
        :let [a (hx/attributes el)
              decorative? (= "true" (str (:aria-hidden a)))
              named? (and (= "img" (:role a))
                          (or (not (str/blank? (:aria-label a)))
                              (not (str/blank? (:aria-labelledby a)))))]
        :when (not (or decorative? named?))]
    "an svg is neither hidden (aria-hidden) nor an image with a name (role=img and aria-label or aria-labelledby)"))

(defn- id-problems [tree]
  (let [ids (keep #(:id (hx/attributes %)) (hx/elements tree))
        duplicated (for [[id n] (frequencies ids) :when (> n 1)] id)
        known (set ids)
        references (concat
                    (for [el (hx/elements tree)
                          attribute [:aria-describedby :aria-labelledby :aria-controls]
                          id (str/split (str (get (hx/attributes el) attribute)) #"\s+")
                          :when (not (str/blank? id))]
                      [attribute id])
                    (for [el (hx/by-tag tree :label)
                          :let [f (:for (hx/attributes el))]
                          :when f]
                      [:for f]))]
    (concat
     (for [id duplicated] (str "the id " (pr-str id) " is used more than once"))
     (for [[attribute id] references
           :when (not (contains? known id))]
       (str attribute " points at " (pr-str id) ", which is not in the page")))))

(defn- table-problems [tree]
  (for [table (hx/by-tag tree :table)
        :let [captioned? (or (seq (hx/by-tag table :caption)) (not (str/blank? (:aria-label (hx/attributes table)))))]
        problem (concat
                 (when-not captioned? ["a table has no caption"])
                 (for [th (hx/by-tag table :th)
                       :when (nil? (:scope (hx/attributes th)))]
                   "a th has no scope"))]
    problem))

(defn- heading-level [el]
  (get {:h1 1 :h2 2 :h3 3 :h4 4 :h5 5 :h6 6} (hx/tag el)))

(defn- heading-problems
  "Headings must not skip a level going down (an h1 followed by an h3)."
  [tree]
  (let [levels (keep heading-level (hx/elements tree))]
    (for [[a b] (partition 2 1 levels)
          :when (> b (inc a))]
      (str "a heading of level " b " follows one of level " a))))

(defn- misc-problems [tree]
  (concat
   (for [el (hx/elements tree)
         :let [ti (:tabindex (hx/attributes el))]
         :when (and ti (pos? (parse-long (str ti))))]
     "a positive tabindex changes the natural order of focus")
   (for [el (hx/by-tag tree :details)
         :when (empty? (hx/by-tag el :summary))]
     "a details has no summary")
   (for [el (hx/by-tag tree :nav)
         :when (str/blank? (:aria-label (hx/attributes el)))
         :when (> (count (hx/by-tag tree :nav)) 1)]
     "there are several navs and one has no aria-label")))

(defn- page-problems [tree]
  (let [html-el (first (hx/by-tag tree :html))
        head (first (hx/by-tag tree :head))
        ids (set (keep #(:id (hx/attributes %)) (hx/elements tree)))]
    (concat
     (when-not html-el ["the page has no html element"])
     ;; a link inside the page (the skip link above all) that leads nowhere is a way past the navigation that does not work
     (for [el (hx/by-tag tree :a)
           :let [target (str (:href (hx/attributes el)))]
           :when (and (> (count target) 1) (str/starts-with? target "#"))
           :when (not (contains? ids (subs target 1)))]
       (str "a link to " target " points at an id that is not in the page"))
     (when (and html-el (str/blank? (:lang (hx/attributes html-el)))) ["the html element has no lang"])
     (when (and head (str/blank? (hx/words (first (hx/by-tag head :title))))) ["the page has no title"])
     (when (and head (not-any? #(= "viewport" (:name (hx/attributes %))) (hx/by-tag head :meta)))
       ["the page has no viewport"])
     (let [n (count (hx/by-tag tree :h1))]
       (when-not (= 1 n) [(str "the page has " n " h1 elements, not one")]))
     (when (empty? (hx/by-tag tree :main)) ["the page has no main"]))))

(defn problems
  "What is wrong with `tree` for accessibility, as a vector of sentences. Options: `:page?` for a whole document."
  ([tree] (problems tree nil))
  ([tree {:keys [page?]}]
   (vec (concat (control-problems tree)
                (name-problems tree)
                (svg-problems tree)
                (id-problems tree)
                (table-problems tree)
                (heading-problems tree)
                (misc-problems tree)
                (when page? (page-problems tree))))))
