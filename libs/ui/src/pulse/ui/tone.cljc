(ns pulse.ui.tone
  "The tones of a reading (ADR-0026): normal, three degrees away from it, and information. A tone is never
  shown by its colour alone: it comes with an icon of its own shape and a word. The colours are the tokens
  `tone-<name>-soft` (the background), `-ink` (the text on it) and `-edge` (the border or the icon), whose
  contrast `bb design:check` measures for every palette.

  The classes are written whole, in a table, because Tailwind finds them by reading this file.

  The bands of `pulse.fhir.ranges` map to a tone by their severity: 0 normal, 1 elevated (`low` and
  `elevated`), 2 high (`very-low`, `high` and `fever`), 3 critical (`very-high` and `high-fever`). The library
  does not depend on `libs/fhir`; a test in the design suite checks that the two agree.")

(def tones
  "For each tone: its default `:label`, its `:icon`, and the classes of its parts: `:surface` (the soft
  background, the text on it and the border), `:icon-colour`, `:ink` (the text of the tone on the page, as a figure
  or a word that is not in a chip) and `:dot` (a filled mark: the bar on the edge of an alert or a tile)."
  {:normal {:label "Normal" :icon :circle-check
            :surface "border-tone-normal-edge bg-tone-normal-soft text-tone-normal-ink"
            :icon-colour "text-tone-normal-edge"
            :ink "text-tone-normal-ink"
            :dot "bg-tone-normal-edge"}
   :elevated {:label "Elevated" :icon :trending-up
              :surface "border-tone-elevated-edge bg-tone-elevated-soft text-tone-elevated-ink"
              :icon-colour "text-tone-elevated-edge"
              :ink "text-tone-elevated-ink"
              :dot "bg-tone-elevated-edge"}
   :high {:label "High" :icon :triangle-alert
          :surface "border-tone-high-edge bg-tone-high-soft text-tone-high-ink"
          :icon-colour "text-tone-high-edge"
          :ink "text-tone-high-ink"
          :dot "bg-tone-high-edge"}
   :critical {:label "Critical" :icon :octagon-alert
              :surface "border-tone-critical-edge bg-tone-critical-soft text-tone-critical-ink"
              :icon-colour "text-tone-critical-edge"
              :ink "text-tone-critical-ink"
              :dot "bg-tone-critical-edge"}
   :info {:label "Information" :icon :info
          :surface "border-tone-info-edge bg-tone-info-soft text-tone-info-ink"
          :icon-colour "text-tone-info-edge"
          :ink "text-tone-info-ink"
          :dot "bg-tone-info-edge"}})

(def bands
  "The bands of a reading, as the api names them (`very-high`, ...): the tone each has, the word to show and the
  icon. A low reading and a high one of the same severity have the same tone and tell them apart by the word
  and, for the mildest, by the direction of the arrow."
  {:normal {:tone :normal :label "Normal" :icon :circle-check}
   :low {:tone :elevated :label "Low" :icon :trending-down}
   :elevated {:tone :elevated :label "Elevated" :icon :trending-up}
   :very-low {:tone :high :label "Very low" :icon :triangle-alert}
   :high {:tone :high :label "High" :icon :triangle-alert}
   :fever {:tone :high :label "Fever" :icon :triangle-alert}
   :very-high {:tone :critical :label "Very high" :icon :octagon-alert}
   :high-fever {:tone :critical :label "High fever" :icon :octagon-alert}})

(defn tone
  "The data of the tone `k`; throws for one that does not exist."
  [k]
  (or (get tones k)
      (throw (ex-info (str "There is no tone called " (pr-str k)) {:tone k :known (keys tones)}))))

(defn band
  "The data of a band, given as a keyword or as the string the api sends, or nil for a reading that has no band."
  [k]
  (when k
    (get bands (keyword k))))
