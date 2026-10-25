(ns pulse.design.spa
  "The static files of the clinical workspace that the design system makes (ADR-0026, ADR-0029), beside the stylesheet that
  Tailwind compiles from the tokens and the components (`bb design:spa`): the page that loads the script, the font of the
  interfaces with its license and the mark of Pulse for the tab of the browser. They are committed in
  `apps/spa/public`, so that serving the workspace needs no Node: `write!` makes them and
  `check` finds what a committed file lacks or has wrong. The script is not committed; it is what the build of ClojureScript
  makes, and `release!` puts the release together from the build and these files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hiccup2.core :as h]
            [pulse.design.keycloak :as kc]
            [pulse.ui.layout :as layout]))

(def public-dir
  "Where the static files of the workspace are committed. Not under `resources`: that is on the classpath of every JVM that has the workspace, and
  the portal serves its own `public/pulse.css` from the classpath too, so two files of one name would be one for both."
  "apps/spa/public")

(def release-dir "apps/spa/target/release")

(def fonts-dir "design/fonts")

(def stylesheet "pulse.css")

(def dev-script
  "The script as the development server serves it: shadow-cljs writes it with no hash in its name."
  "/js/main.js")

(defn index-html
  "The text of the page that loads the workspace, whose script is at the address `script`: the document of `libs/ui` with the
  stylesheet, the icon and a place for the application to be drawn in. It has no inline script or style, so the policy of the
  server (`pulse.spa.security`) accepts it. `noscript` tells a browser that cannot run the script why there is nothing to see."
  [script]
  (str "<!DOCTYPE html>\n"
       (h/html {:mode :html}
               (layout/document {:title "Pulse"
                                 :stylesheets [(str "/" stylesheet)]
                                 :head [[:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
                                        [:script {:src script :defer true}]]}
                                [:div {:id "app"}]
                                [:noscript
                                 [:p {:class "p-6"} "The clinical workspace needs JavaScript. Turn it on in your browser and reload the page."]]))
       "\n"))

(defn files
  "What this namespace makes as text, as `{path text}` with each path relative to the public directory of the workspace."
  []
  {"favicon.svg" (kc/favicon-svg)
   "index.html" (index-html dev-script)})

(defn copied-files
  "What is copied byte for byte, as `{path source-file}`: the font and its license, from the design."
  []
  (into {} (for [font (concat (kc/font-files) [kc/font-license])]
             [(str "fonts/" font) (io/file fonts-dir font)])))

(defn- public-file ^java.io.File [path] (io/file public-dir path))

(defn- same-bytes? [^java.io.File a ^java.io.File b]
  (and (.exists a) (.exists b)
       (java.util.Arrays/equals (java.nio.file.Files/readAllBytes (.toPath a))
                                (java.nio.file.Files/readAllBytes (.toPath b)))))

(defn check
  "What a committed file lacks: a sorted vector of the paths, relative to the public directory, that are not what this namespace
  makes or are missing: the text files, the font and its license, and the stylesheet (it must be there; `bb design:spa` compiles
  it, and the tests look into it)."
  []
  (vec (sort (concat
              (for [[path text] (files)
                    :let [file (public-file path)]
                    :when (or (not (.exists file)) (not= text (slurp file :encoding "UTF-8")))]
                path)
              (for [[path from] (copied-files)
                    :when (not (same-bytes? (public-file path) from))]
                path)
              (let [file (public-file stylesheet)]
                (when-not (and (.exists file) (pos? (.length file)))
                  [stylesheet]))))))

(defn- copy! [^java.io.File from ^java.io.File to]
  (io/make-parents to)
  (io/copy from to)
  (println "Copied" (str to)))

(defn write!
  "Writes the files this namespace makes into the public directory of the workspace, and copies the font and its license there.
  The stylesheet is compiled by Tailwind, which `bb design:spa` runs after this."
  []
  (doseq [[path text] (files)
          :let [file (public-file path)]]
    (io/make-parents file)
    (spit file text :encoding "UTF-8")
    (println "Wrote" (str file)))
  (doseq [[path from] (copied-files)]
    (copy! from (public-file path))))

;; ---------------------------------------------------------------------------
;; The release

(defn script-name
  "The name of the script of the module `:main` in `manifest`, what shadow-cljs writes with the release: a vector of the modules
  with their `:output-name`, which has the hash of the content in it (`main.7A1F2C09....js`). Nil when there is no such module."
  [manifest]
  (some #(when (= :main (:module-id %)) (:output-name %)) manifest))

(defn- delete-tree! [^java.io.File file]
  (when (.isDirectory file)
    (run! delete-tree! (.listFiles file)))
  (io/delete-file file true))

(defn release!
  "Puts the release together in `apps/spa/target/release` from the build of ClojureScript (`js/` there, with its manifest) and the
  files of the workspace: the stylesheet, the icon and the font are copied, and the page is written to load the script by the
  name that has its hash in it. Throws when the build left no script."
  []
  (let [js-manifest (io/file release-dir "js" "manifest.edn")]
    (when-not (.exists js-manifest)
      (throw (ex-info (str "No build to put together: " js-manifest " is not there. Run the release build of shadow-cljs first.")
                      {:file (str js-manifest)})))
    (let [script (script-name (edn/read-string (slurp js-manifest :encoding "UTF-8")))]
      (when-not script
        (throw (ex-info "The manifest of the build has no module :main" {:file (str js-manifest)})))
      (doseq [path (concat [stylesheet "favicon.svg"] (keys (copied-files)))]
        (copy! (public-file path) (io/file release-dir path)))
      (let [index (io/file release-dir "index.html")]
        (spit index (index-html (str "/js/" script)) :encoding "UTF-8")
        (println "Wrote" (str index)))
      script)))

(defn clean-release!
  "Deletes the release directory, so that a script from an earlier build, with another hash in its name, is not left in it."
  []
  (delete-tree! (io/file release-dir))
  nil)
