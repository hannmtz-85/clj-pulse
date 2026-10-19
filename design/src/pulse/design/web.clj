(ns pulse.design.web
  "The static files of the patient portal that the design system makes (ADR-0026, ADR-0028), beside the stylesheet
  that Tailwind compiles from the tokens and the components (`bb design:web`): the font of the interfaces with its
  license, the mark of Pulse for the tab of the browser, and htmx, copied from its npm package at the version that
  `design/package.json` pins. The portal serves them from `apps/web/resources/public` as files of its own, and never
  from another server, since its Content-Security-Policy allows nothing else. They are committed, so that running the
  portal needs no Node. `write!` writes what this namespace makes, and `check` finds what a committed file lacks or has
  wrong."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pulse.design.keycloak :as kc]))

(def public-dir "apps/web/resources/public")

(def fonts-dir "design/fonts")

(def package-json "design/package.json")

(def htmx-package "design/node_modules/htmx.org")

(def htmx-file "htmx.min.js")

(def stylesheet "pulse.css")

(defn htmx-version
  "The version of htmx that `design/package.json` pins, such as \"2.0.11\". It is an exact version: a range would make
  the file that is copied depend on the day it was installed. Nil when there is none."
  []
  (second (re-find #"\"htmx\.org\"\s*:\s*\"([0-9]+\.[0-9]+\.[0-9]+)\"" (slurp package-json :encoding "UTF-8"))))

(defn- has-version?
  "Whether the text of htmx.min.js is the file of htmx `version`, which the library writes in the object it defines."
  [text version]
  (boolean (and version (str/includes? text (str "version:\"" version "\"")))))

(defn files
  "What this namespace makes as text, as `{path text}` with each path relative to the public directory of the portal."
  []
  {"favicon.svg" (kc/favicon-svg)})

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
  "What a committed file lacks: a sorted vector of the paths, relative to the public directory, that are not what this
  namespace makes or are missing: the text files, the font and its license, htmx (it must be the version that is
  pinned) and the stylesheet (it must be there; `bb design:web` compiles it, and the tests look into it)."
  []
  (vec (sort (concat
              (for [[path text] (files)
                    :let [file (public-file path)]
                    :when (or (not (.exists file)) (not= text (slurp file :encoding "UTF-8")))]
                path)
              (for [[path from] (copied-files)
                    :when (not (same-bytes? (public-file path) from))]
                path)
              (let [file (public-file htmx-file)]
                (when-not (and (.exists file) (has-version? (slurp file :encoding "UTF-8") (htmx-version)))
                  [htmx-file]))
              (let [file (public-file stylesheet)]
                (when-not (and (.exists file) (pos? (.length file)))
                  [stylesheet]))))))

(defn- copy! [^java.io.File from ^java.io.File to]
  (io/make-parents to)
  (io/copy from to)
  (println "Copied" (str to)))

(defn write!
  "Writes the files this namespace makes into the public directory of the portal, and copies the font, its license and
  htmx there. htmx is copied from `design/node_modules`, so this needs `npm ci` in `design/`; it fails if the package
  is not the version that `design/package.json` pins."
  []
  (doseq [[path text] (files)
          :let [file (public-file path)]]
    (io/make-parents file)
    (spit file text :encoding "UTF-8")
    (println "Wrote" (str file)))
  (doseq [[path from] (copied-files)]
    (copy! from (public-file path)))
  (let [from (io/file htmx-package "dist" htmx-file)
        version (htmx-version)]
    (when-not version
      (throw (ex-info "design/package.json does not pin htmx.org to an exact version" {:file package-json})))
    (when-not (.exists from)
      (throw (ex-info (str "No such file in the package: " from ". Run `npm ci` in design/.") {:file (str from)})))
    (when-not (has-version? (slurp from :encoding "UTF-8") version)
      (throw (ex-info (str from " is not htmx " version ". Run `npm ci` in design/.") {:file (str from) :version version})))
    (copy! from (public-file htmx-file))))
