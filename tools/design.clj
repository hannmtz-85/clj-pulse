(ns design
  "The design tokens as data, and what is made from them (ADR-0026): `design/theme.css`, the themes of
  daisyUI with the custom properties and the @theme of Tailwind, and the contrast of the pairs of colours
  that carry text or controls. The functions are pure, and the same under Babashka (`bb design:theme`,
  `bb design:check`) and on the JVM, where `tools/test/design_test.clj` runs them; only `write-theme!`
  and `check-theme!` touch a file."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def tokens-file "design/tokens.edn")
(def theme-file "design/theme.css")

;; ---------------------------------------------------------------------------
;; What the tokens file must say

(def ^:private hex
  [:re {:error/message "should be a colour in 6-digit lower-case hex, such as #0f766e"} #"^#[0-9a-f]{6}$"])

(defn- closed-map
  "A closed map schema whose keys are `ks`, each with the schema `value`."
  [ks value]
  (into [:map {:closed true}] (map (fn [k] [k value])) ks))

(def neutral-keys
  [:base-100 :base-200 :base-300 :base-content :secondary :secondary-content :neutral :neutral-content
   :info :info-content :success :success-content :warning :warning-content :error :error-content
   :muted :control-border])

(def brand-keys [:primary :primary-content :accent :accent-content])

(def tone-names [:normal :elevated :high :critical :info])

(def tone-parts [:soft :ink :edge])

(def shape-keys [:radius-selector :radius-field :radius-box :size-selector :size-field :border :depth :noise])

(defn- by-scheme [schema]
  [:map {:closed true} [:light schema] [:dark schema]])

(def schema
  "What `design/tokens.edn` must satisfy. Maps are closed, so that a misspelt key is an error."
  [:map {:closed true}
   [:palette [:map {:closed true} [:selected [:maybe :keyword]]]]
   [:font [:map {:closed true}
           [:sans [:vector {:min 1} :string]]
           [:mono [:vector {:min 1} :string]]
           [:url :string]
           [:faces [:vector {:min 1} [:map {:closed true} [:file :string] [:range :string]]]]]]
   [:shape (closed-map shape-keys :string)]
   [:shadow (closed-map [:card :raised :overlay] :string)]
   [:neutrals (by-scheme (closed-map neutral-keys hex))]
   [:tones (by-scheme (closed-map tone-names (closed-map tone-parts hex)))]
   [:chart (by-scheme [:map {:closed true}
                       [:series [:vector {:min 4 :max 4} [:or hex [:= :primary]]]]
                       [:grid hex]])]
   [:palettes [:map-of :keyword
               [:map {:closed true}
                [:label :string]
                [:light (closed-map brand-keys hex)]
                [:dark (closed-map brand-keys hex)]]]]
   [:contrast [:map {:closed true}
               [:text number?]
               [:ui number?]
               [:pairs [:vector [:tuple :keyword :keyword [:enum :text :ui]]]]]]])

;; ---------------------------------------------------------------------------
;; The colours of a palette in a scheme

(defn- tone-key [tone part]
  (keyword (str "tone-" (name tone) "-" (name part))))

(defn scheme-colors
  "Every colour of `palette` in `scheme` (:light or :dark) as one flat map from a keyword to a hex
  string: the neutrals and the states of daisyUI, muted text and the border of a control; the brand of
  the palette; the tones of the bands as `:tone-normal-soft`, `:tone-normal-ink`, ...; and the chart's,
  `:series-1` to `:series-4` and `:chart-grid`. These are the names the pairs of :contrast use."
  [tokens palette scheme]
  (let [brand (get-in tokens [:palettes palette scheme])
        series (get-in tokens [:chart scheme :series])]
    (merge (get-in tokens [:neutrals scheme])
           brand
           (into {} (for [[tone parts] (get-in tokens [:tones scheme])
                          [part colour] parts]
                      [(tone-key tone part) colour]))
           (into {} (map-indexed (fn [i colour]
                                   [(keyword (str "series-" (inc i))) (if (= :primary colour) (:primary brand) colour)]))
                 series)
           {:chart-grid (get-in tokens [:chart scheme :grid])})))

(defn problems
  "What is wrong with `tokens`, as a vector of lines, or nil when nothing is: the schema, then what the
  schema cannot say. A chosen palette must exist, and a pair of :contrast must name colours that exist."
  [tokens]
  (if-let [explanation (m/explain schema tokens)]
    (->> (me/humanize explanation) pr-str vector)
    (let [selected (get-in tokens [:palette :selected])
          palettes (set (keys (:palettes tokens)))
          known (set (keys (scheme-colors tokens (first (keys (:palettes tokens))) :light)))
          unknown (for [[fg bg _] (get-in tokens [:contrast :pairs])
                        colour [fg bg]
                        :when (not (contains? known colour))]
                    colour)]
      (not-empty
       (cond-> []
         (and selected (not (contains? palettes selected)))
         (conj (str "the chosen palette " selected " is not one of " (sort palettes)))
         (empty? palettes) (conj "there is no palette")
         (seq unknown) (conj (str "a pair of :contrast names colours that do not exist: " (vec (distinct unknown)))))))))

(defn read-tokens
  "The tokens in `design/tokens.edn`, checked. Throws an ex-info that lists what is wrong."
  ([] (read-tokens tokens-file))
  ([file]
   (let [tokens (edn/read-string (slurp file :encoding "UTF-8"))]
     (when-let [found (problems tokens)]
       (throw (ex-info (str "The design tokens in " file " are not sound:\n  " (str/join "\n  " found))
                       {:type ::invalid-tokens :problems found})))
     tokens)))

;; ---------------------------------------------------------------------------
;; Contrast (WCAG 2.2, success criteria 1.4.3 and 1.4.11)

(defn hex->rgb
  "The red, green and blue of a 6-digit hex colour as numbers from 0 to 255."
  [colour]
  (let [[_ r g b] (re-matches #"#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})" colour)]
    (when-not r
      (throw (ex-info (str "Not a colour in 6-digit hex: " (pr-str colour)) {:colour colour})))
    (mapv #(Integer/parseInt ^String % 16) [r g b])))

(defn- linear
  "A channel of sRGB (0 to 255) as linear light (0 to 1)."
  [channel]
  (let [c (/ channel 255.0)]
    (if (<= c 0.04045)
      (/ c 12.92)
      (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn luminance
  "The relative luminance of a hex colour: 0 for black and 1 for white."
  [colour]
  (let [[r g b] (map linear (hex->rgb colour))]
    (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))))

(defn contrast-ratio
  "The contrast ratio of two hex colours, from 1 (the same) to 21 (black on white), whichever is the
  foreground. It is not rounded: 4.499 does not pass 4.5."
  [a b]
  (let [la (luminance a)
        lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn contrast-report
  "One entry for every pair of :contrast in every palette and scheme: `{:palette :scheme :fg :bg :kind
  :ratio :min :ok?}`."
  [tokens]
  (let [{:keys [pairs] :as contrast} (:contrast tokens)]
    (for [palette (keys (:palettes tokens))
          scheme [:light :dark]
          :let [colours (scheme-colors tokens palette scheme)]
          [fg bg kind] pairs
          :let [ratio (contrast-ratio (get colours fg) (get colours bg))
                minimum (get contrast kind)]]
      {:palette palette :scheme scheme :fg fg :bg bg :kind kind
       :ratio ratio :min minimum :ok? (>= ratio minimum)})))

(defn failures
  "The entries of `contrast-report` that are below their minimum."
  [tokens]
  (remove :ok? (contrast-report tokens)))

(defn ratio-text
  "A ratio as shown to a person, as in `7.58:1`: cut at two decimals and never rounded, so that a ratio
  below its minimum does not read as the minimum (4.4994 is `4.49:1`, not `4.50:1`)."
  [ratio]
  (format "%.2f:1" (/ (Math/floor (* 100.0 (double ratio))) 100.0)))

;; ---------------------------------------------------------------------------
;; The themes

(def ^:private daisy-colour-order
  [:base-100 :base-200 :base-300 :base-content
   :primary :primary-content :secondary :secondary-content :accent :accent-content
   :neutral :neutral-content :info :info-content :success :success-content
   :warning :warning-content :error :error-content])

(defn- property [k v]
  (str "  " k ": " v ";\n"))

(defn pulse-property
  "The name of the custom property for a colour that daisyUI has no variable for, such as
  `--pulse-tone-normal-soft` for `:tone-normal-soft`."
  [k]
  (str "--pulse-" (name k)))

(defn- pulse-colour-order
  "The colours that are custom properties, in the order they are written."
  [colours]
  (concat [:muted :control-border]
          (sort (filter #(str/starts-with? (name %) "tone-") (keys colours)))
          (sort (filter #(str/starts-with? (name %) "series-") (keys colours)))
          [:chart-grid]))

(defn- theme-block
  "The `@plugin \"daisyui/theme\"` block of one palette in one scheme. daisyUI writes every property of
  the block under the selector of the theme, so the custom properties switch with it."
  [tokens {theme-name :name :keys [scheme default? prefersdark?]} colours]
  (str "@plugin \"daisyui/theme\" {\n"
       (property "name" (pr-str theme-name))
       (property "default" default?)
       (property "prefersdark" prefersdark?)
       (property "color-scheme" (name scheme))
       (apply str (for [k daisy-colour-order] (property (str "--color-" (name k)) (get colours k))))
       (apply str (for [k shape-keys] (property (str "--" (name k)) (get-in tokens [:shape k]))))
       (apply str (for [k (pulse-colour-order colours)] (property (pulse-property k) (get colours k))))
       "}\n"))

(defn themes
  "The themes the file has: a vector of `{:name :palette :scheme :default? :prefersdark?}`. While no
  palette is chosen there are two for each candidate, `pulse-teal` and `pulse-teal-dark`, the first
  candidate being the default; once one is chosen there are two, `pulse` and `pulse-dark`."
  [tokens]
  (let [selected (get-in tokens [:palette :selected])
        palettes (if selected [selected] (keys (:palettes tokens)))
        first-palette (first palettes)
        base-name (fn [palette] (if selected "pulse" (str "pulse-" (name palette))))]
    (vec (for [palette palettes
               scheme [:light :dark]]
           {:name (cond-> (base-name palette) (= :dark scheme) (str "-dark"))
            :palette palette
            :scheme scheme
            :default? (and (= palette first-palette) (= :light scheme))
            :prefersdark? (and (= palette first-palette) (= :dark scheme))}))))

(defn- font-stack [names]
  (str/join ", " (map (fn [n] (if (re-matches #"[a-z-]+" n) n (pr-str n))) names)))

(defn theme-css
  "The text of `design/theme.css` for `tokens`."
  [tokens]
  (let [{:keys [font shadow]} tokens
        themes (themes tokens)
        sample (scheme-colors tokens (:palette (first themes)) :light)
        mapped (pulse-colour-order sample)]
    (str "/* Generated by `bb design:theme` from design/tokens.edn. Do not edit it: change the tokens and run\n"
         "   the task again (ADR-0026). `bb design:check` fails when this file is not what the tokens say. */\n"
         "\n"
         ;; none of daisyUI's own themes: the ones below are the only ones, and it says nothing in the log
         "@plugin \"daisyui\" {\n"
         (property "logs" "false")
         (property "themes" "false")
         "}\n"
         "\n"
         "@theme {\n"
         (property "--font-sans" (font-stack (:sans font)))
         (property "--font-mono" (font-stack (:mono font)))
         (property "--shadow-card" (:card shadow))
         (property "--shadow-raised" (:raised shadow))
         (property "--shadow-overlay" (:overlay shadow))
         "}\n"
         "\n"
         ;; the custom properties as Tailwind colours, so that bg-tone-normal-soft and text-muted exist
         "@theme inline {\n"
         (apply str (for [k mapped]
                      (property (str "--color-" (name k)) (str "var(" (pulse-property k) ")"))))
         "}\n"
         "\n"
         (apply str (for [{:keys [file range]} (:faces font)]
                      (str "@font-face {\n"
                           (property "font-family" "\"Inter Variable\"")
                           (property "font-style" "normal")
                           (property "font-display" "swap")
                           (property "font-weight" "100 900")
                           (property "src" (str "url(\"" (:url font) file "\") format(\"woff2-variations\")"))
                           (property "unicode-range" range)
                           "}\n\n")))
         (str/join "\n" (for [{:keys [palette scheme] :as theme} themes]
                          (theme-block tokens theme (scheme-colors tokens palette scheme)))))))

;; ---------------------------------------------------------------------------
;; The tasks

(defn write-theme!
  "Writes `design/theme.css` from `design/tokens.edn`."
  []
  (let [tokens (read-tokens)]
    (spit theme-file (theme-css tokens) :encoding "UTF-8")
    (println "Wrote" theme-file)))

(defn check!
  "Exits 1 when a pair of colours is below its contrast in any palette, or `design/theme.css` is not what
  the tokens say."
  []
  (let [tokens (read-tokens)
        failed (failures tokens)
        stale? (not (and (.exists (io/file theme-file))
                         (= (slurp theme-file :encoding "UTF-8") (theme-css tokens))))]
    (doseq [{:keys [palette scheme fg bg ratio min]} failed]
      (println (format "LOW CONTRAST %s %s: %s on %s is %s, and %s is needed"
                       (name palette) (name scheme) (name fg) (name bg) (ratio-text ratio) min)))
    (when stale?
      (println theme-file "is stale: run `bb design:theme`"))
    (if (or (seq failed) stale?)
      (System/exit 1)
      (println theme-file "is up to date and every pair of colours has its contrast"))))

(defn print-contrast
  "Prints the ratio of every pair, palette by palette, for a person to read."
  []
  (let [tokens (read-tokens)]
    (doseq [palette (keys (:palettes tokens))
            scheme [:light :dark]]
      (println (str "\n" (name palette) " / " (name scheme)))
      (doseq [{:keys [fg bg kind ratio min ok?]} (filter #(and (= palette (:palette %)) (= scheme (:scheme %)))
                                                         (contrast-report tokens))]
        (println (format "  %-4s %-26s on %-26s %9s  (needs %s)  %s"
                         (name kind) (name fg) (name bg) (ratio-text ratio) min (if ok? "ok" "FAILS")))))))
