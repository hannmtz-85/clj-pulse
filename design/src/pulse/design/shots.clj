(ns pulse.design.shots
  "Screenshots of the mock-ups (ADR-0026), taken by `design/shots.mjs` with headless Chrome or Edge, so that the pages
  can be looked at without opening each. They go to `design/dist/shots`, which git ignores. What is taken is
  `pulse.design.shotplan`'s to say."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [pulse.design.shotplan :as plan]))

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

(defn take!
  "Takes the pictures. `args` are the file name prefixes of the pages (`components`) and the options of
  `plan/parse-args`; none means every page."
  [args]
  (let [spec-file (str (fs/create-temp-file {:prefix "pulse-shots-" :suffix ".json"}))
        shots (vec (plan/default-shots (plan/parse-args args)))]
    (spit spec-file (json/generate-string {:browser (browser) :dist (str (fs/absolutize dist)) :out (str (fs/absolutize out))
                                           :shots shots}))
    (try
      (process/shell "node" "design/shots.mjs" spec-file)
      (finally (fs/delete-if-exists spec-file)))))
