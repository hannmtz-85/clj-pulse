(ns pulse.spa.hiccup
  "The one place that knows that the components of `libs/ui` were written for a server (ADR-0029, point 2).

  They return hiccup for HTML: a list of children has no keys and an attribute is spelt as HTML spells it. Reagent hands the
  hiccup to React, which wants a `:key` on every child of a list, and spells `tabindex` as `tabIndex`; left alone, every list of
  every page is a warning in the console, and `tabindex` is refused (spike of 2026-10-25: from 18 to 172 warnings on a page).
  `->reagent` walks the hiccup once and makes the two changes, and nothing else: no element is added, taken away or moved.

  A list in the components is static: `for` over what the page was given, in an order that does not change while it is on the
  screen, so the position of a child is a good key. A child that already has a key (`^{:key id} [...]`) keeps it.")

(def ^:private renamed
  "An attribute as `libs/ui` writes it for a server, to the name React wants. Reagent turns `:kebab-case` into `camelCase`.
  `:class`, `:for`, `:viewBox` and the `aria-` and `data-` attributes need nothing, and `:style` is a map in a page of ours
  (the components write no style at all: a policy that forbids inline style would refuse it)."
  {:tabindex :tab-index
   :colspan :col-span
   :rowspan :row-span
   :maxlength :max-length
   :minlength :min-length
   :autocomplete :auto-complete
   :autofocus :auto-focus
   :novalidate :no-validate
   :readonly :read-only
   :enctype :enc-type
   :datetime :date-time
   :accesskey :access-key
   :contenteditable :content-editable
   :crossorigin :cross-origin
   :srcset :src-set
   :usemap :use-map})

(declare ->reagent)

(defn- attributes
  "The attributes `m` of an element, with the names React wants."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (get renamed k k) v)) {} m))

(defn- keyed
  "The children of a list, each element in it with its position as `:key` unless it has one."
  [xs]
  (map-indexed (fn [i x]
                 (let [y (->reagent x)]
                   (if (and (vector? y) (nil? (:key (meta y))))
                     (vary-meta y assoc :key i)
                     y)))
               xs))

(defn ->reagent
  "`x`, hiccup made by the components, ready for Reagent: a list of children has keys and the attributes of every element have the
  names of React. An element is a vector that begins with a keyword; a vector that begins with anything else (a component of
  Reagent and its arguments) is left as it is, and so is whatever is not hiccup (text, a number, nil). A sequence is a list of
  children, which gets the keys; a *vector* of vectors is not a list in hiccup, it is an element named by a vector, and it is not
  turned into one."
  [x]
  (cond
    (and (vector? x) (keyword? (first x)))
    (let [[tag & more] x
          [attrs children] (if (map? (first more)) [(attributes (first more)) (rest more)] [nil more])]
      (with-meta (into (if attrs [tag attrs] [tag]) (map ->reagent) children) (meta x)))

    (seq? x) (keyed x)

    :else x))
