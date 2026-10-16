(ns pulse.ui.test.hiccup
  "Reading hiccup in tests: the elements of a tree, their attributes, classes and text, and, on the JVM, the
  HTML that Hiccup 2 makes of it. Shared by the suites of `libs/ui` and of the design pages."
  (:require [clojure.string :as str]
            #?(:clj [hiccup2.core :as h])))

(defn element?
  "Whether `x` is a hiccup element: a vector that starts with a keyword."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn tag [el] (first el))

(defn attributes
  "The attribute map of an element (empty when it has none)."
  [el]
  (let [a (second el)]
    (if (map? a) a {})))

(defn- flatten-nodes [xs]
  (mapcat (fn [x]
            (cond (nil? x) []
                  (element? x) [x]
                  (sequential? x) (flatten-nodes x)
                  :else [x]))
          xs))

(defn child-nodes
  "The children of an element: elements, strings and numbers, with the sequences of `for` flattened and the nils
  left out."
  [el]
  (let [r (rest el)]
    (flatten-nodes (if (map? (first r)) (rest r) r))))

(defn elements
  "Every element of the tree, the root first, depth first. `tree` may be an element or a sequence of them."
  [tree]
  (mapcat (fn [root] (tree-seq element? (fn [e] (filter element? (child-nodes e))) root))
          (flatten-nodes [tree])))

(defn select
  "The elements of `tree` for which `pred` is true."
  [tree pred]
  (filter pred (elements tree)))

(defn by-tag
  "The elements of `tree` with the tag `k` (`:label`)."
  [tree k]
  (select tree #(= k (tag %))))

(defn by-id
  "The element with the attribute `:id` equal to `id`, or nil."
  [tree id]
  (first (select tree #(= id (:id (attributes %))))))

(defn classes
  "The set of classes of an element."
  [el]
  (set (remove str/blank? (str/split (str (:class (attributes el))) #"\s+"))))

(defn has-class? [el class-name]
  (contains? (classes el) class-name))

(defn text
  "The text inside a node, with no markup."
  [node]
  (cond (string? node) node
        (number? node) (str node)
        (element? node) (apply str (map text (child-nodes node)))
        (sequential? node) (apply str (map text node))
        :else ""))

(defn words
  "The text inside a node with its white space collapsed to single spaces."
  [node]
  (str/trim (str/replace (text node) #"\s+" " ")))

#?(:clj
   (defn html
     "The HTML of `node`, as Hiccup 2 renders it for a page."
     [node]
     (str (h/html {:mode :html} node))))
