(ns pulse.design.pages
  "The pages of the mock-ups (ADR-0026): which there are, in the order they are linked, and the function that
  makes each one. A page function takes the context `{:tokens :pages :themes}` and returns hiccup."
  (:require [pulse.design.catalogue :as catalogue]
            [pulse.design.tile :as tile]))

(def pages
  "Every page: its `:file`, `:title`, the `:group` it is listed under, a `:summary` for the index and the `:render`
  function."
  [{:file "index.html" :title "Overview" :group "Overview" :summary "" :render tile/index-page}
   {:file "palette.html" :title "Palette" :group "Style tile"
    :summary "Teal in light and dark, with the same components."
    :render tile/palette-page}
   {:file "type.html" :title "Type" :group "Style tile"
    :summary "Inter, the scale, tabular figures and a reading."
    :render tile/type-page}
   {:file "components.html" :title "Components" :group "Style tile"
    :summary "Buttons, tones, messages, forms, tables, tabs and states."
    :render catalogue/components-page}
   {:file "charts.html" :title "Charts" :group "Style tile"
    :summary "A reading over time: bands, thresholds, two series, and the small version."
    :render catalogue/charts-page}
   {:file "contrast.html" :title "Contrast" :group "Style tile"
    :summary "Every pair of colours, measured, for the palette in both schemes."
    :render tile/contrast-page}])
