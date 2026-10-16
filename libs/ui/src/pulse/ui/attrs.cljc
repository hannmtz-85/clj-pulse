(ns pulse.ui.attrs
  "What every component does with the attributes it is given (ADR-0026): the first argument is a map of
  attributes, as in hiccup, the components take the keys they own out of it, and the rest goes through to the
  element, so that the `hx-*` attributes of htmx and the `:on-click` of Reagent work on any of them.
  A component adds its classes to the ones the caller wrote.

  A class is always a whole string literal, in the component or in the table it looks it up in: Tailwind finds
  the classes by reading the sources, and cannot see one that is put together from parts."
  (:require [clojure.string :as str]))

(defn args
  "Splits the arguments of a component into `[attributes children]`: the attributes are the first argument
  when it is a map, and there are none otherwise."
  [xs]
  (if (map? (first xs))
    [(first xs) (rest xs)]
    [{} xs]))

(defn classes
  "One value for a `:class` attribute from names that may be strings with several classes, collections of
  those, blank or nil."
  [& names]
  (->> names
       flatten
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join " ")))

(defn with-classes
  "`attributes` with the classes of the component first and the ones the caller wrote after them."
  [attributes & component-classes]
  (assoc attributes :class (apply classes (concat component-classes [(:class attributes)]))))

(defn owned
  "`attributes` without the keys the component owns, which are not attributes of an element."
  [attributes & ks]
  (apply dissoc attributes ks))
