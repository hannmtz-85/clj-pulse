(ns pulse.ui.icon
  "An icon as inline SVG (ADR-0026): the shapes come from `pulse.ui.icons`, which is generated from Lucide, and
  it is drawn in the colour of the text around it. It is decorative unless it has a `:label`: an icon next to a
  word hides itself from a screen reader, and one that stands alone has to say what it is."
  (:require [pulse.ui.attrs :as attrs]
            [pulse.ui.icons :as icons]))

(def sizes
  "The sizes by name, in pixels."
  {:xs 14 :sm 16 :md 20 :lg 24 :xl 32})

(defn known?
  "Whether there is an icon called `k`."
  [k]
  (contains? icons/icons k))

(defn icon
  "The icon called `k`. Options: `:size` (a key of `sizes`, or a number of pixels; `:md` by default), `:label`
  (what it means, when nothing next to it says so) and `:class`."
  ([k] (icon k nil))
  ([k {:keys [size label class] :or {size :md}}]
   (let [shapes (or (get icons/icons k)
                    (throw (ex-info (str "There is no icon called " k) {:icon k})))
         px (get sizes size size)]
     (into [:svg (cond-> {:viewBox "0 0 24 24" :width px :height px :fill "none" :stroke "currentColor"
                          :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                          :class (attrs/classes "shrink-0" class)}
                   label (assoc :role "img" :aria-label label)
                   (not label) (assoc :aria-hidden "true"))]
           shapes))))
