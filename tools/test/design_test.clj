(ns design-test
  "The design tokens and what is made from them (ADR-0026): the contrast formula against known values,
  the pairs of colours of every palette in both schemes, what the schema refuses, and the generated
  theme. The functions are pure; only the last test reads the committed file.

  The palette of the repository is teal, which the owner chose on 2026-10-16 from three candidates. The
  generator can still write a theme for each of several palettes, as the style tile did, and `candidates`
  (the tokens of that time, made in the test) is what the tests of that use."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [design]))

(def ^:private tokens (design/read-tokens))

(def ^:private palette (first (keys (:palettes tokens))))

(def ^:private candidates
  "The tokens as they were while the owner chose: three palettes and none selected."
  (-> tokens
      (assoc-in [:palette :selected] nil)
      (assoc-in [:palettes :blue]
                {:label "Blue"
                 :light {:primary "#1d4ed8" :primary-content "#ffffff" :accent "#dbeafe" :accent-content "#1e3a8a"}
                 :dark {:primary "#60a5fa" :primary-content "#172554" :accent "#1e3a8a" :accent-content "#bfdbfe"}})
      (assoc-in [:palettes :indigo]
                {:label "Indigo"
                 :light {:primary "#4338ca" :primary-content "#ffffff" :accent "#e0e7ff" :accent-content "#312e81"}
                 :dark {:primary "#818cf8" :primary-content "#1e1b4b" :accent "#312e81" :accent-content "#c7d2fe"}})))

(defn- approx
  "True when `x` is within `epsilon` of `expected`."
  [expected x epsilon]
  (< (Math/abs (- (double expected) (double x))) epsilon))

(defn- tone-colour [tone part]
  (keyword (str "tone-" (name tone) "-" (name part))))

;; ---------------------------------------------------------------------------
;; The formula

(deftest the-formula-gives-the-values-wcag-publishes
  (testing "black on white is 21, and a colour on itself is 1"
    (is (approx 21.0 (design/contrast-ratio "#000000" "#ffffff") 1e-9))
    (is (approx 1.0 (design/contrast-ratio "#336699" "#336699") 1e-9)))
  (testing "#767676 is the lightest grey that has 4.5 on white (4.54), and #777777 does not (4.48)"
    (is (approx 4.54 (design/contrast-ratio "#767676" "#ffffff") 0.005))
    (is (approx 4.48 (design/contrast-ratio "#777777" "#ffffff") 0.005))
    (is (>= (design/contrast-ratio "#767676" "#ffffff") 4.5))
    (is (< (design/contrast-ratio "#777777" "#ffffff") 4.5)))
  (testing "which is the foreground does not matter"
    (is (= (design/contrast-ratio "#0f766e" "#ffffff") (design/contrast-ratio "#ffffff" "#0f766e")))))

(deftest luminance-is-linear-light-with-the-weights-of-the-eye
  (is (approx 1.0 (design/luminance "#ffffff") 1e-12))
  (is (approx 0.0 (design/luminance "#000000") 1e-12))
  (testing "the weights of red, green and blue"
    (is (approx 0.2126 (design/luminance "#ff0000") 1e-12))
    (is (approx 0.7152 (design/luminance "#00ff00") 1e-12))
    (is (approx 0.0722 (design/luminance "#0000ff") 1e-12)))
  (testing "a dark channel is divided by 12.92 and a light one follows the curve"
    (is (approx (* 0.2126 (/ (/ 10 255.0) 12.92)) (design/luminance "#0a0000") 1e-12)
        "10/255 is 0.0392, below the 0.04045 where the two meet")
    (is (approx (* 0.2126 (Math/pow (/ (+ (/ 11 255.0) 0.055) 1.055) 2.4)) (design/luminance "#0b0000") 1e-12)
        "11/255 is 0.0431, above it")))

(deftest a-ratio-is-not-rounded-before-it-is-compared
  ;; #478080 on white is 4.49940: it would read 4.50 if rounded, and it is not enough for 4.5
  (let [ratio (design/contrast-ratio "#478080" "#ffffff")
        almost (-> tokens
                   (assoc-in [:neutrals :light :base-100] "#ffffff")
                   (assoc-in [:neutrals :light :muted] "#478080"))
        found (->> (design/failures almost)
                   (filter #(and (= palette (:palette %)) (= :light (:scheme %)) (= :muted (:fg %)) (= :base-100 (:bg %)))))]
    (is (< 4.499 ratio 4.5))
    (is (= 1 (count found)) "the pair is below its minimum")
    (testing "and the text shows it below, never as the minimum"
      (is (= "4.49:1" (design/ratio-text ratio)))
      (is (= "4.50:1" (design/ratio-text 4.5)))
      (is (= "4.54:1" (design/ratio-text 4.5442)))
      (is (= "21.00:1" (design/ratio-text 21.0)))
      (is (= "1.00:1" (design/ratio-text 1))))))

(defspec the-ratio-is-symmetric-and-between-1-and-21 500
  (let [channel (gen/choose 0 255)
        colour (gen/fmap (fn [[r g b]] (format "#%02x%02x%02x" r g b)) (gen/tuple channel channel channel))]
    (prop/for-all [a colour b colour]
      (let [ratio (design/contrast-ratio a b)]
        (and (= ratio (design/contrast-ratio b a))
             (<= 1.0 ratio (+ 21.0 1e-9)))))))

(deftest a-colour-must-be-six-hex-digits
  (is (= [255 0 128] (design/hex->rgb "#ff0080")))
  (is (= [255 0 128] (design/hex->rgb "#FF0080")) "the formula takes either case; the schema asks for lower case")
  (doseq [bad ["ff0080" "#f08" "#ff00800" "#gg0080" "" "red"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a colour" (design/hex->rgb bad)) (pr-str bad))))

;; ---------------------------------------------------------------------------
;; The tokens of the repository

(deftest the-tokens-file-is-sound
  (is (nil? (design/problems tokens))))

(deftest every-pair-of-every-palette-in-both-schemes-has-its-contrast
  (let [report (design/contrast-report tokens)
        palettes (count (:palettes tokens))
        pairs (count (get-in tokens [:contrast :pairs]))]
    (testing "the report is complete, so that an empty one cannot pass"
      (is (pos? palettes))
      (is (<= 30 pairs))
      (is (= (* palettes 2 pairs) (count report))))
    (testing "no pair is below its minimum"
      (is (empty? (map (juxt :palette :scheme :fg :bg :ratio :min) (design/failures tokens)))))
    (testing "the minimum of each kind is the one of WCAG 2.2 AA"
      (is (= 4.5 (get-in tokens [:contrast :text])))
      (is (= 3.0 (get-in tokens [:contrast :ui])))
      (is (= #{4.5 3.0} (set (map :min report)))))
    (testing "and so do the other two candidates, whichever the palette that is chosen"
      (is (= (* 3 2 pairs) (count (design/contrast-report candidates))))
      (is (empty? (design/failures candidates))))))

(deftest the-palette-is-teal-chosen-by-the-owner
  (is (= :teal (get-in tokens [:palette :selected])))
  (is (= [:teal] (keys (:palettes tokens))) "the other candidates were deleted")
  (is (= "Teal" (get-in tokens [:palettes :teal :label]))))

(deftest the-pairs-cover-what-a-person-has-to-read-or-see
  (let [pairs (get-in tokens [:contrast :pairs])
        has? (fn [fg bg kind] (some #(= [fg bg kind] %) pairs))]
    (is (has? :base-content :base-100 :text) "the text of the page")
    (is (has? :muted :base-100 :text) "the secondary text")
    (is (has? :primary :base-100 :text) "a link")
    (is (has? :primary-content :primary :text) "a primary button")
    (is (has? :control-border :base-100 :ui) "the edge of a field")
    (doseq [tone design/tone-names]
      (is (has? (tone-colour tone :ink) (tone-colour tone :soft) :text) (str "the word of the tone " tone))
      (is (has? (tone-colour tone :edge) :base-100 :ui) (str "the icon of the tone " tone)))
    (doseq [n (range 1 5)]
      (is (has? (keyword (str "series-" n)) :base-100 :ui) (str "the series " n " of a chart")))))

(deftest a-pair-below-its-minimum-is-found-and-named
  (let [weaker (assoc-in tokens [:palettes palette :light :primary] "#5eead4")
        failed (design/failures weaker)
        pairs (set (map (juxt :fg :bg) failed))]
    (is (seq failed))
    (is (every? #(and (= palette (:palette %)) (= :light (:scheme %))) failed)
        "only the palette and the scheme that changed")
    (is (contains? pairs [:primary-content :primary]))
    (is (contains? pairs [:primary :base-100]) "the brand as text")
    (testing "and the first series of a chart, which is the primary"
      (is (contains? pairs [:series-1 :base-100])))))

(deftest the-first-series-of-a-chart-is-the-primary-of-the-palette
  (doseq [p (keys (:palettes tokens))
          scheme [:light :dark]]
    (is (= (get-in tokens [:palettes p scheme :primary])
           (:series-1 (design/scheme-colors tokens p scheme)))
        (str p " " scheme))))

(deftest scheme-colors-gives-every-colour-the-pairs-and-the-theme-use
  (let [colours (design/scheme-colors tokens palette :light)]
    (is (every? #(contains? colours %) design/neutral-keys))
    (is (every? #(contains? colours %) design/brand-keys))
    (is (every? #(contains? colours %)
                (for [tone design/tone-names part design/tone-parts] (tone-colour tone part))))
    (is (every? #(re-matches #"#[0-9a-f]{6}" %) (vals colours)))
    (is (= (+ (count design/neutral-keys) (count design/brand-keys)
              (* (count design/tone-names) (count design/tone-parts))
              4 1)
           (count colours))
        "the neutrals, the brand, the tones, four series and the grid, and nothing else")))

;; ---------------------------------------------------------------------------
;; What the schema refuses

(deftest the-schema-refuses-what-would-break-the-theme
  (testing "a colour that is not lower-case hex"
    (is (seq (design/problems (assoc-in tokens [:neutrals :light :base-100] "#FFFFFF"))))
    (is (seq (design/problems (assoc-in tokens [:neutrals :light :base-100] "white"))))
    (is (seq (design/problems (assoc-in tokens [:neutrals :light :base-100] "#fff")))))
  (testing "a misspelt key, because the maps are closed"
    (is (seq (design/problems (assoc-in tokens [:palettes palette :light :primry] "#0f766e"))))
    (is (seq (design/problems (assoc-in tokens [:shape :radius-fields] "0.5rem"))))
    (is (seq (design/problems (assoc tokens :extra 1)))))
  (testing "a missing tone"
    (is (seq (design/problems (update-in tokens [:tones :light] dissoc :critical)))))
  (testing "a chart with other than four series"
    (is (seq (design/problems (update-in tokens [:chart :light :series] pop)))))
  (testing "a pair of kind other than text or ui"
    (is (seq (design/problems (update-in tokens [:contrast :pairs] conj [:primary :base-100 :decoration])))))
  (testing "a pair that names a colour that does not exist"
    (let [found (design/problems (update-in tokens [:contrast :pairs] conj [:primary :base-400 :text]))]
      (is (some #(str/includes? % ":base-400") found))))
  (testing "a chosen palette that is not one of the palettes"
    (let [found (design/problems (assoc-in tokens [:palette :selected] :magenta))]
      (is (some #(str/includes? % ":magenta") found))))
  (testing "a palette that is chosen and exists is sound"
    (is (nil? (design/problems (assoc-in tokens [:palette :selected] palette)))))
  (testing "there must be a palette"
    (is (seq (design/problems (assoc tokens :palettes {})))))
  (testing "reading throws with the lines that are wrong"
    (let [file (java.io.File/createTempFile "tokens" ".edn")]
      (try
        (spit file (pr-str (assoc-in tokens [:neutrals :light :base-100] "white")))
        (let [e (try (design/read-tokens (str file)) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= :design/invalid-tokens (:type (ex-data e))))
          (is (str/includes? (ex-message e) "6-digit lower-case hex")))
        (finally (.delete file))))))

;; ---------------------------------------------------------------------------
;; The themes

(deftest the-theme-of-the-repository-is-pulse-in-light-and-in-dark
  (let [themes (design/themes tokens)]
    (is (= ["pulse" "pulse-dark"] (map :name themes)))
    (is (= [:teal :teal] (map :palette themes)))
    (is (= [:light :dark] (map :scheme themes)))
    (is (= [[true false] [false true]] (map (juxt :default? :prefersdark?) themes))
        "light is the default, and dark is what a browser that asks for it gets")
    (is (= 2 (count (re-seq #"@plugin \"daisyui/theme\"" (design/theme-css tokens)))))
    (testing "and they carry the colours of the palette"
      (let [css (design/theme-css tokens)]
        (is (str/includes? css (str "--color-primary: " (get-in tokens [:palettes :teal :light :primary]) ";")))
        (is (str/includes? css (str "--color-primary: " (get-in tokens [:palettes :teal :dark :primary]) ";")))))))

(deftest while-no-palette-is-chosen-every-candidate-has-two-themes
  (let [themes (design/themes candidates)]
    (is (= ["pulse-teal" "pulse-teal-dark" "pulse-blue" "pulse-blue-dark" "pulse-indigo" "pulse-indigo-dark"]
           (map :name themes)))
    (is (= ["pulse-teal"] (map :name (filter :default? themes))) "the first candidate in light is the default")
    (is (= ["pulse-teal-dark"] (map :name (filter :prefersdark? themes))) "and in dark, what a dark browser gets")
    (is (= 6 (count (re-seq #"@plugin \"daisyui/theme\"" (design/theme-css candidates)))))))

(deftest once-a-palette-is-chosen-among-several-there-are-two-themes-called-pulse
  (let [chosen (assoc-in candidates [:palette :selected] :blue)
        themes (design/themes chosen)
        css (design/theme-css chosen)]
    (is (= ["pulse" "pulse-dark"] (map :name themes)))
    (is (= [:blue :blue] (map :palette themes)))
    (is (= 2 (count (re-seq #"@plugin \"daisyui/theme\"" css))))
    (is (str/includes? css "--color-primary: #1d4ed8;"))
    (is (str/includes? css "--color-primary: #60a5fa;"))
    (is (not (str/includes? css "#0f766e")) "and nothing of the others")))

(deftest daisyui-is-loaded-once-with-none-of-its-own-themes
  (let [css (design/theme-css tokens)]
    (is (= 1 (count (re-seq #"@plugin \"daisyui\" \{" css))))
    (is (str/includes? css "@plugin \"daisyui\" {\n  logs: false;\n  themes: false;\n}\n"))
    (is (< (str/index-of css "@plugin \"daisyui\" {") (str/index-of css "@plugin \"daisyui/theme\" {"))
        "the plugin comes before its themes")))

(deftest the-css-has-a-block-for-each-theme-with-every-colour-of-daisyui
  (let [css (design/theme-css tokens)
        blocks (rest (str/split css #"@plugin \"daisyui/theme\" \{\n"))]
    (is (= (count (design/themes tokens)) (count blocks)))
    (doseq [block blocks
            k [:base-100 :base-200 :base-300 :base-content :primary :primary-content :secondary :secondary-content
               :accent :accent-content :neutral :neutral-content :info :info-content :success :success-content
               :warning :warning-content :error :error-content]]
      (is (re-find (re-pattern (str "(?m)^  --color-" (name k) ": #[0-9a-f]{6};$")) block) (name k)))
    (testing "the shape of daisyUI"
      (doseq [block blocks
              k design/shape-keys]
        (is (str/includes? block (str "  --" (name k) ": ")) (name k))))
    (testing "the scheme and the preference of each theme"
      (is (= (count blocks) (count (re-seq #"(?m)^  color-scheme: (light|dark);$" css))))
      (is (= (count blocks) (count (re-seq #"(?m)^  name: \"pulse[a-z-]*\";$" css)))))))

(deftest what-daisyui-has-no-variable-for-is-a-custom-property-that-switches-with-the-theme
  (let [css (design/theme-css tokens)
        blocks (rest (str/split css #"@plugin \"daisyui/theme\" \{\n"))]
    (is (= "--pulse-tone-normal-soft" (design/pulse-property :tone-normal-soft)))
    (doseq [block blocks
            property ["--pulse-muted" "--pulse-control-border" "--pulse-tone-critical-edge" "--pulse-tone-info-ink"
                      "--pulse-series-1" "--pulse-series-4" "--pulse-chart-grid"]]
      (is (str/includes? block (str "  " property ": #")) property))
    (testing "and Tailwind has them as colours, so that `bg-tone-normal-soft` and `text-muted` exist"
      (is (str/includes? css "  --color-tone-normal-soft: var(--pulse-tone-normal-soft);"))
      (is (str/includes? css "  --color-muted: var(--pulse-muted);"))
      (is (str/includes? css "  --color-series-2: var(--pulse-series-2);"))
      (is (str/includes? css "@theme inline {")))))

(deftest the-font-is-self-hosted-and-the-stack-falls-back-to-the-system
  (let [css (design/theme-css tokens)]
    (is (= 2 (count (re-seq #"@font-face \{" css))))
    (is (str/includes? css "src: url(\"fonts/inter-latin-wght-normal.woff2\") format(\"woff2-variations\");"))
    (is (str/includes? css "font-weight: 100 900;"))
    (is (str/includes? css "font-display: swap;"))
    (is (str/includes? css "--font-sans: \"Inter Variable\", ui-sans-serif, system-ui,"))
    (is (str/includes? css "sans-serif;") "a generic family is a keyword, never quoted")
    (is (not (str/includes? css "http")) "nothing is fetched from the network")))

(deftest the-generated-text-is-stable
  (is (= (design/theme-css tokens) (design/theme-css tokens)))
  (is (str/ends-with? (design/theme-css tokens) "}\n"))
  (is (not (str/includes? (design/theme-css tokens) "\r")) "lines end with a line feed whatever the machine"))

(deftest the-committed-theme-is-what-the-tokens-say
  (is (= (design/theme-css tokens) (slurp design/theme-file :encoding "UTF-8"))
      "run `bb design:theme` and commit the file"))
