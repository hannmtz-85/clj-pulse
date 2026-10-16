(ns pulse.design.pages
  "The pages of the mock-ups (ADR-0026): which there are, in the order they are linked, and the function that
  makes each one. A page function takes the context `{:tokens :pages :themes}` and returns hiccup."
  (:require [pulse.design.catalogue :as catalogue]
            [pulse.design.tile :as tile]))

(def pages
  "Every page: its `:file`, `:title`, a `:summary` for the index and the `:render` function."
  [{:file "index.html" :title "Style tile" :summary "" :render tile/index-page}
   {:file "palettes.html" :title "Palettes"
    :summary "Teal, blue and indigo in light and dark, with the same components."
    :render tile/palettes-page}
   {:file "type.html" :title "Type"
    :summary "Inter, the scale, tabular figures and a reading."
    :render tile/type-page}
   {:file "components.html" :title "Components"
    :summary "Buttons, tones, messages, forms, tables, tabs and states."
    :render catalogue/components-page}
   {:file "charts.html" :title "Charts"
    :summary "A reading over time: bands, thresholds, two series, and the small version."
    :render catalogue/charts-page}
   {:file "contrast.html" :title "Contrast"
    :summary "Every pair of colours, measured, for every palette."
    :render tile/contrast-page}])
