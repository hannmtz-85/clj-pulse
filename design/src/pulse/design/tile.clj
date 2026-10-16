(ns pulse.design.tile
  "The style tile, first point of review (ADR-0026): the candidate palettes in light and dark, the type, and the
  measured contrast. The components and the charts are in `pulse.design.catalogue`. Each page is a function from
  a context `{:tokens :pages :themes}` to hiccup."
  (:require [clojure.string :as str]
            [design]
            [pulse.design.chrome :as chrome]
            [pulse.design.data :as data]
            [pulse.design.specimen :as specimen]
            [pulse.ui.component :as c]
            [pulse.ui.icon :as i]))

(defn- frame
  "The page `file` with its title and content."
  [{:keys [pages themes]} file title & content]
  (apply chrome/page {:title title :pages pages :file file :themes themes} content))

(defn section
  "A titled part of a page; `description` may be nil."
  [id title description & content]
  [:section {:class "mt-10" :aria-labelledby id}
   [:h2 {:id id :class "text-xl font-semibold tracking-tight"} title]
   (when description [:p {:class "mt-1 max-w-3xl text-muted"} description])
   (into [:div {:class "mt-5 space-y-4"}] content)])

(defn intro
  "The title and the lead of a page."
  [title lead]
  [:div
   [:h1 {:class "text-3xl font-semibold tracking-tight"} title]
   [:p {:class "mt-2 max-w-3xl text-lg text-muted"} lead]])

;; ---------------------------------------------------------------------------
;; The index

(defn index-page [{:keys [pages] :as context}]
  (frame context "index.html" "Style tile"
         (intro "Style tile"
                "The first point of review: palette, type and components, before any page is drawn. Each page has a theme choice at the top right, remembered between pages.")
         (section "decide" "What I need from you"
                  nil
                  [:ol {:class "list-decimal space-y-2 pl-6"}
                   [:li [:strong "A palette."] " Teal, blue or indigo, on the page \"Palettes\". The others are deleted once you choose."]
                   [:li [:strong "Whether the look is right"] ": roundness, density, the weight of the type, the way a reading away from normal is shown."]
                   [:li [:strong "Anything missing"] " from the components, which the pages will be made of."]])
         (section "pages" "The pages" nil
                  (into [:ul {:class "grid gap-3 md:grid-cols-2"}]
                        (for [{:keys [file title summary]} pages
                              :when (not= file "index.html")]
                          [:li (c/card {:class "h-full"}
                                       [:a {:href file :class "text-base font-semibold text-primary hover:underline"} title]
                                       [:p {:class "mt-1 text-sm text-muted"} summary])])))
         (section "rules" "What holds on every page" nil
                  (c/facts [["Contrast" "WCAG 2.2 AA, measured: 4.5 for text and 3 for controls and graphics"]
                            ["Meaning" "Never by colour alone: a reading away from normal has an icon and a word"]
                            ["Type" "Inter, self-hosted; tabular figures wherever numbers are compared"]
                            ["Data" "The synthetic cohort, at 14:00 on Tuesday 13 October 2026"]
                            ["Motion" "Reduced when the browser asks for it"]]))))

;; ---------------------------------------------------------------------------
;; Palettes

(def ^:private swatch-classes
  {:primary "bg-primary text-primary-content"
   :accent "bg-accent text-accent-content"
   :neutral "bg-neutral text-neutral-content"
   :base-100 "border border-base-300 bg-base-100 text-base-content"
   :base-200 "border border-base-300 bg-base-200 text-base-content"})

(defn- swatch [role label hex ratio]
  [:div {:class (str "rounded-field px-3 py-2.5 " (swatch-classes role))}
   [:p {:class "text-sm font-semibold"} label]
   [:p {:class "text-xs tabular-nums opacity-90"} hex]
   [:p {:class "text-xs tabular-nums opacity-90"} (str "text " ratio)]])

(defn- theme-of [tokens palette scheme]
  (:name (first (filter #(and (= palette (:palette %)) (= scheme (:scheme %))) (design/themes tokens)))))

(defn specimen
  "A fragment of an interface in the theme of one palette and scheme, on a page that may be in another."
  [tokens palette scheme]
  (let [colours (design/scheme-colors tokens palette scheme)
        ratio (fn [fg bg] (design/ratio-text (design/contrast-ratio (colours fg) (colours bg))))
        id (str (name palette) "-" (name scheme))
        ana (data/vitals "pt-ana")]
    [:div {:data-theme (theme-of tokens palette scheme)
           :class "rounded-box border border-base-300 bg-base-200 p-5 text-base-content"}
     [:div {:class "flex items-baseline justify-between gap-3"}
      [:h3 {:class "text-base font-semibold"} (str (get-in tokens [:palettes palette :label]) ", " (name scheme))]
      [:span {:class "text-xs text-muted"} (theme-of tokens palette scheme)]]
     [:div {:class "mt-4 grid grid-cols-3 gap-2"}
      (swatch :primary "Primary" (:primary colours) (ratio :primary-content :primary))
      (swatch :accent "Accent" (:accent colours) (ratio :accent-content :accent))
      (swatch :neutral "Neutral" (:neutral colours) (ratio :neutral-content :neutral))]
     [:div {:class "mt-5 flex flex-wrap gap-2"}
      (c/button {:variant :primary :icon :calendar-plus} "Book")
      (c/button {:variant :outline} "Reschedule")
      (c/button {:variant :subtle} "Details")
      (c/button {:variant :ghost} "Cancel")]
     [:p {:class "mt-4 text-sm"}
      "Next visit with " [:a {:href "#" :class "link link-primary font-medium"} "Dr. Rivera"] " on Friday. The link is "
      (ratio :primary :base-100) " on the page."]
     [:div {:class "mt-4 flex flex-wrap gap-2"}
      (for [tone [:normal :elevated :high :critical :info]] (c/tone-badge {:tone tone :size :sm}))]
     [:div {:class "mt-5 grid gap-3 sm:grid-cols-2"}
      (specimen/vital-tile :heart-rate (:heart-rate ana))
      (specimen/vital-tile :blood-pressure (:blood-pressure ana))]
     [:div {:class "mt-5"}
      (c/text-field {:id (str id "-note") :label "Note for the clinic" :hint "Only your care team can read it."
                     :placeholder "Felt dizzy this morning"})]]))

(def ^:private palette-notes
  {:teal "Calm and clinical. Reads as health without borrowing the red of urgency, so the tones of a reading stand out against it."
   :blue "The familiar colour of software for care. Safe, and the most likely to look like every other health application."
   :indigo "Warmer and more distinctive. Closer to the violet of the series 3 line in charts, so it needs more care there."})

(def ^:private recommended #{:teal})

(defn palettes-page [{:keys [tokens] :as context}]
  (frame context "palettes.html" "Palettes"
         (intro "Palettes"
                "Three candidates in the light and the dark scheme, with the same components. The numbers are measured contrast ratios; WCAG 2.2 AA asks for 4.5 for text and 3 for controls, and every one of them passes.")
         (for [palette (keys (:palettes tokens))]
           (section (str "palette-" (name palette))
                    [:span (get-in tokens [:palettes palette :label])
                     (when (recommended palette) [:span {:class "ml-3 align-middle"} (c/badge {:variant :accent} "Recommended")])]
                    (palette-notes palette)
                    [:div {:class "grid gap-5 lg:grid-cols-2"}
                     (specimen tokens palette :light)
                     (specimen tokens palette :dark)]))))

;; ---------------------------------------------------------------------------
;; Type

(def ^:private scale
  "The type scale: a name, the classes that make it, the size, and where it is used."
  [{:name "Display" :class "text-4xl font-semibold tracking-tight" :size "36 / 40, semibold" :use "A greeting on the patient's home"}
   {:name "Page title" :class "text-2xl font-semibold tracking-tight" :size "24 / 32, semibold" :use "The one h1 of a page"}
   {:name "Section" :class "text-xl font-semibold tracking-tight" :size "20 / 28, semibold" :use "A part of a page"}
   {:name "Card title" :class "text-base font-semibold" :size "16 / 24, semibold" :use "The title of a card"}
   {:name "Body" :class "text-base" :size "16 / 24, regular" :use "Text in the portal"}
   {:name "Body, dense" :class "text-sm" :size "14 / 20, regular" :use "Text and tables in the clinical workspace"}
   {:name "Label" :class "text-sm font-medium" :size "14 / 20, medium" :use "The label of a field"}
   {:name "Caption" :class "text-xs text-muted" :size "12 / 16, regular" :use "A time, a unit, a hint"}])

(defn type-page [context]
  (frame context "type.html" "Type"
         (intro "Type"
                "Inter, an open variable font (SIL Open Font License) drawn for screens, served from this repository: no request to anyone else. It has figures that line up.")
         (section "inter" "Inter" nil
                  [:div {:class "rounded-box border border-base-300 bg-base-100 p-6 shadow-card"}
                   [:p {:class "text-5xl font-semibold tracking-tight"} "Pulse looks after what matters"]
                   [:p {:class "mt-4 text-lg"} "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz"]
                   [:p {:class "mt-1 text-lg"} "ÁÉÍÓÚ ÑÜ àèìòù ç  0123456789  (€ % °C ≥ ≤ → ·)"]
                   [:div {:class "mt-5 flex flex-wrap items-baseline gap-x-8 gap-y-2 text-2xl"}
                    [:span {:class "font-light"} "Light 300"]
                    [:span {:class "font-normal"} "Regular 400"]
                    [:span {:class "font-medium"} "Medium 500"]
                    [:span {:class "font-semibold"} "Semibold 600"]
                    [:span {:class "font-bold"} "Bold 700"]]])
         (section "scale" "The scale" "Eight styles, all from Tailwind's own sizes. The portal reads at 16 px and the clinical workspace at 14."
                  [:div {:class "overflow-hidden rounded-box border border-base-300 bg-base-100"}
                   (into [:dl {:class "divide-y divide-base-300"}]
                         (for [{:keys [name class size use]} scale]
                           [:div {:class "grid gap-1 px-5 py-4 md:grid-cols-[14rem_1fr] md:gap-6"}
                            [:dt {:class "text-sm"}
                             [:span {:class "font-medium"} name]
                             [:span {:class "block text-xs text-muted"} size]]
                            [:dd
                             [:p {:class class} "Blood pressure, 172 over 104"]
                             [:p {:class "mt-1 text-xs text-muted"} use]]]))])
         (section "figures" "Figures"
                  "Readings are compared by eye, so digits are the same width (tabular figures) wherever they are in a column. Left: proportional, right: tabular."
                  [:div {:class "grid gap-5 md:grid-cols-2"}
                   (c/card {:title "Proportional"}
                           [:p {:class "space-y-1 text-2xl font-semibold proportional-nums"}
                            (for [v ["1,111.11" "8,888.88" "172/104" "98 %" "37.8 °C"]] [:span {:class "block"} v])])
                   (c/card {:title "Tabular (what the interfaces use)"}
                           [:p {:class "space-y-1 text-2xl font-semibold tabular-nums"}
                            (for [v ["1,111.11" "8,888.88" "172/104" "98 %" "37.8 °C"]] [:span {:class "block"} v])])])
         (section "readings" "A reading" "A number, a unit smaller and in the muted colour, and a time."
                  [:div {:class "grid gap-5 md:grid-cols-3"}
                   (specimen/vital-tile :blood-pressure (:blood-pressure (data/vitals "pt-ana")))
                   (specimen/vital-tile :glucose (:glucose (data/vitals "pt-liam")))
                   (specimen/vital-tile :weight (:weight (data/vitals "pt-maya")))])
         (section "prose" "Running text"
                  "At most 65 characters a line, and a line height of 1.5."
                  [:div {:class "max-w-prose space-y-3 rounded-box border border-base-300 bg-base-100 p-6 shadow-card"}
                   [:h3 {:class "text-xl font-semibold tracking-tight"} "How your readings are used"]
                   [:p "Your care team sees the readings you record, and a rule raises an alert when a pattern needs a person to look. "
                    "The bands you see beside a reading, such as " [:strong "Elevated"] ", are a didactic reference and not medical advice. "
                    [:a {:href "#" :class "link link-primary"} "Read how the bands are set"] "."]
                   [:p {:class "text-sm text-muted"} "You can ask for your data or delete your account at any time. "
                    "Press " [:kbd {:class "kbd kbd-sm"} "Tab"] " to move between the controls."]])))

;; ---------------------------------------------------------------------------
;; Contrast

(defn- kind-text [kind] (if (= :text kind) "text" "control"))

(defn- contrast-table [title entries]
  (c/data-table
   {:caption (str "Contrast of the pairs of colours, " title)
    :dense? true
    :columns [{:key :fg :label "Foreground" :header? true}
              {:key :bg :label "Background"}
              {:key :kind :label "For"}
              {:key :ratio :label "Ratio" :align :end}
              {:key :min :label "Needs" :align :end}
              {:key :ok :label "Result" :align :center}]
    :rows (for [{:keys [fg bg kind ratio min ok?]} entries]
            {:fg (name fg) :bg (name bg) :kind (kind-text kind)
             :ratio (design/ratio-text ratio) :min (str min ":1")
             :ok (if ok?
                   (i/icon :circle-check {:label "Passes" :class "mx-auto text-tone-normal-edge"})
                   (i/icon :circle-x {:label "Fails" :class "mx-auto text-tone-critical-edge"}))})}))

(defn contrast-page [{:keys [tokens] :as context}]
  (let [report (design/contrast-report tokens)
        by-theme (group-by (juxt :palette :scheme) report)
        first-palette (first (keys (:palettes tokens)))]
    (frame context "contrast.html" "Contrast"
           (intro "Contrast"
                  [:span "Every pair of colours that carries text or a control, measured with the WCAG formula, for every palette in both schemes: "
                   (count report) " measurements, " (count (design/failures tokens)) " below their minimum. "
                   [:code "bb design:check"] " fails when one is, and so does the test suite."])
           (for [palette (keys (:palettes tokens))]
             (section (str "contrast-" (name palette)) (get-in tokens [:palettes palette :label]) nil
                      (for [scheme [:light :dark]
                            :let [entries (get by-theme [palette scheme])
                                  tightest (apply min-key #(/ (:ratio %) (:min %)) entries)
                                  title (str (get-in tokens [:palettes palette :label]) ", " (name scheme))]]
                        [:details {:class "rounded-box border border-base-300 bg-base-100 shadow-card"
                                   :open (and (= palette first-palette) (= scheme :light))}
                         [:summary {:class "cursor-pointer px-5 py-4"}
                          [:span {:class "font-semibold"} (str/capitalize (name scheme))]
                          [:span {:class "ml-3 text-sm text-muted"}
                           (str (count entries) " pairs. The tightest: " (name (:fg tightest)) " on " (name (:bg tightest))
                                ", " (design/ratio-text (:ratio tightest)) " where " (:min tightest) " is needed.")]]
                         [:div {:class "px-3 pb-4"} (contrast-table title entries)]]))))))
