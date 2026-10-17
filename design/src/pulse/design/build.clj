(ns pulse.design.build
  "Builds the mock-ups into `design/dist` (ADR-0026): the pages as static HTML, rendered from the components of
  `libs/ui` with Hiccup 2, beside the stylesheet that Tailwind compiles (`bb design:css`), the font and the script
  of the review. `dist` is ignored by git: the mock-ups are made again when they are needed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [design]
            [hiccup2.core :as h]
            [pulse.design.chrome :as chrome]
            [pulse.design.mockup :as mockup]
            [pulse.design.pages :as pages]))

(def dist "design/dist")
(def fonts-dir "design/fonts")
(def assets-dir "design/assets")
(def font-package "design/node_modules/@fontsource-variable/inter")

(defn render
  "The text of a page: the doctype and the HTML of `hiccup`, as Hiccup 2 writes it for a page."
  [hiccup]
  (str "<!DOCTYPE html>\n" (h/html {:mode :html} hiccup) "\n"))

(defn context
  "What every page function is given: the tokens, the pages in order and the themes."
  ([] (context (design/read-tokens)))
  ([tokens]
   {:tokens tokens
    :pages pages/pages
    :themes (chrome/theme-options tokens)}))

(defn documents
  "Every document the build writes, as `{:file :hiccup}` with the file relative to `design/dist`: the pages of the
  review, and for each mock-up its page of the review and its frame, the interface itself, under `frames/`."
  [{:keys [pages] :as ctx}]
  (mapcat (fn [{:keys [file frame] render-page :render :as page}]
            (if frame
              [{:file file :hiccup (mockup/wrapper ctx page)}
               {:file (mockup/frame-path file) :hiccup (frame ctx)}]
              [{:file file :hiccup (render-page ctx)}]))
          pages))

(defn- copy! [from to]
  (io/make-parents to)
  (io/copy (io/file from) (io/file to)))

(defn font-files
  "The names of the font files the tokens use."
  [tokens]
  (map :file (get-in tokens [:font :faces])))

(defn install-fonts!
  "Copies the files of Inter that the tokens name, and its license, from the npm package to `design/fonts`, where they
  are committed, so that nothing needs Node to show a page."
  []
  (let [tokens (design/read-tokens)]
    (doseq [file (font-files tokens)
            :let [from (io/file font-package "files" file)]]
      (when-not (.exists from)
        (throw (ex-info (str "No such font file in the package: " from ". Run `npm ci` in design/.") {:file file})))
      (copy! from (io/file fonts-dir file)))
    (copy! (io/file font-package "LICENSE") (io/file fonts-dir "OFL.txt"))
    (println "Copied" (count (font-files tokens)) "font files to" fonts-dir)))

(defn class-tokens
  "Every class that the `class` attributes of `html` use, as a sorted set of strings."
  [html]
  (into (sorted-set)
        (comp (map second) (mapcat #(str/split % #"\s+")) (remove str/blank?))
        (re-seq #"class=\"([^\"]*)\"" html)))

(defn- css-escape
  "A class as it is written in a selector: every character that is not a letter, a digit, an underscore or a hyphen
  has a backslash before it, so that `hover:bg-base-200` is `hover\\:bg-base-200`."
  [token]
  (apply str (map (fn [c] (if (re-matches #"[A-Za-z0-9_-]" (str c)) c (str "\\" c))) token)))

(defn- defined?
  "Whether the stylesheet `css` has a rule for the class `token`."
  [css token]
  ;; the character after the class must not continue the selector: a letter, a digit, an underscore, a hyphen or an
  ;; escape (`.w-2\/3` is the class `w-2/3`, not `w-2`)
  (boolean (re-find (re-pattern (str (java.util.regex.Pattern/quote (str "." (css-escape token))) "(?![A-Za-z0-9_\\\\-])"))
                    css)))

(defn unknown-classes
  "The classes of `html` that `css` has no rule for: a misspelt one, or one Tailwind could not see in the sources."
  [html css]
  (into (sorted-set) (remove #(defined? css %)) (class-tokens html)))

(defn- check-classes!
  "Fails when a page uses a class that the compiled stylesheet has no rule for."
  [files]
  (let [css-file (io/file dist "pulse.css")]
    (when (.exists css-file)
      (let [css (slurp css-file :encoding "UTF-8")
            unknown (for [file files
                          :let [missing (unknown-classes (slurp (io/file dist file) :encoding "UTF-8") css)]
                          :when (seq missing)]
                      [file missing])]
        (when (seq unknown)
          (throw (ex-info (str "Classes with no rule in " css-file ": a misspelling, or a class that is not written whole in the sources\n"
                               (str/join "\n" (for [[file missing] unknown] (str "  " file ": " (str/join ", " missing)))))
                          {:type ::unknown-classes :unknown (into {} unknown)})))))))

(defn build!
  "Writes every page, the font files and the script of the review into `design/dist`. The stylesheet is
  `dist/pulse.css`, which `bb design:css` writes. Fails when a page uses a class the stylesheet lacks."
  []
  (let [tokens (design/read-tokens)
        docs (documents (context tokens))]
    (doseq [{:keys [file hiccup]} docs]
      (let [target (io/file dist file)]
        (io/make-parents target)
        (spit target (render hiccup) :encoding "UTF-8")
        (println "Wrote" (str dist "/" file))))
    (check-classes! (map :file docs))
    (doseq [file (font-files tokens)
            :let [from (io/file fonts-dir file)]]
      (when-not (.exists from)
        (throw (ex-info (str "No font file " from ". Run `bb design:fonts`.") {:file file})))
      (copy! from (io/file dist "fonts" file)))
    (copy! (io/file assets-dir "review.js") (io/file dist "review.js"))
    (when-not (.exists (io/file dist "pulse.css"))
      (println "There is no" (str dist "/pulse.css") "yet: run `bb design:css`."))))
