(ns pulse.design.shots
  "Screenshots of the mock-ups (ADR-0026), taken by `design/shots.mjs` with headless Chrome or Edge, so that the pages
  can be looked at without opening each. They go to `design/dist/shots`, which git ignores."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [pulse.design.pages :as pages]))

(def dist "design/dist")
(def out "design/dist/shots")

(def ^:private browser-candidates
  ["C:/Program Files/Google/Chrome/Application/chrome.exe"
   "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"
   "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
   "C:/Program Files/Microsoft/Edge/Application/msedge.exe"
   "/usr/bin/google-chrome" "/usr/bin/chromium" "/usr/bin/chromium-browser"
   "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"])

(defn browser
  "The path of a Chrome or an Edge: the variable CHROME, or the first that exists."
  []
  (or (System/getenv "CHROME")
      (first (filter fs/exists? browser-candidates))
      (throw (ex-info "No Chrome or Edge found: set CHROME to its path" {}))))

(defn- file-name [page theme width clip]
  (str (str/replace (:file page) #".html$" "") "-" (str/replace theme #"^pulse-" "") "-" width
       (when clip (str "-y" (:y clip)))
       ".png"))

(defn- parse-args
  "`[prefix ... --theme name --clip y:height --width pixels]` as a map."
  [args]
  (loop [args args
         acc {:only []}]
    (if-let [[a & more] (seq args)]
      (case a
        "--theme" (recur (rest more) (assoc acc :theme (first more)))
        "--width" (recur (rest more) (assoc acc :width (parse-long (first more))))
        "--clip" (recur (rest more) (assoc acc :clip (let [[y h] (map parse-long (str/split (first more) #":"))]
                                                       {:y y :height h})))
        (recur more (update acc :only conj a)))
      acc)))

(defn default-shots
  "The pictures of every page in the teal theme, light and dark, and of the components and the palettes in the other
  two light themes as well. `only` limits them to pages whose file starts with one of the prefixes; `:theme`,
  `:width` and `:clip` ask for one theme, one width and a slice of the page."
  [{:keys [only theme width clip]}]
  (for [page pages/pages
        :when (or (empty? only) (some #(str/starts-with? (:file page) %) only))
        theme (cond theme [theme]
                    :else (cond-> ["pulse-teal" "pulse-teal-dark"]
                            (#{"components.html" "palettes.html"} (:file page)) (into ["pulse-blue" "pulse-indigo"])))
        :let [width (or width 1280)]]
    (cond-> {:page (:file page) :theme theme :width width :mobile (< width 600) :name (file-name page theme width clip)}
      clip (assoc :clip clip))))

(defn take!
  "Takes the pictures. `args` are the file name prefixes of the pages (`components`) and the options of
  `parse-args`; none means every page."
  [args]
  (let [spec-file (str (fs/create-temp-file {:prefix "pulse-shots-" :suffix ".json"}))
        shots (vec (default-shots (parse-args args)))]
    (spit spec-file (json/generate-string {:browser (browser) :dist (str (fs/absolutize dist)) :out (str (fs/absolutize out))
                                           :shots shots}))
    (try
      (process/shell "node" "design/shots.mjs" spec-file)
      (finally (fs/delete-if-exists spec-file)))))
