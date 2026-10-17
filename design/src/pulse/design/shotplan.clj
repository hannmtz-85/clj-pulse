(ns pulse.design.shotplan
  "What the screenshots of the mock-ups are (ADR-0026): which page, in which theme and at which width, and what the
  file is called. It is a function of the registry of pages and of what was asked, with no browser in it, so that it can
  be tested; `pulse.design.shots` takes the pictures."
  (:require [clojure.string :as str]
            [pulse.design.mockup :as mockup]
            [pulse.design.pages :as pages]))

(defn file-name
  "The file of a picture: the page without its extension, the theme (`light` or `dark`), the width, and the top of the
  slice when there is one."
  [page theme width clip]
  (str (str/replace (:file page) #"\.html$" "") "-" (if (= "pulse" theme) "light" (str/replace theme #"^pulse-" "")) "-" width
       (when clip (str "-y" (:y clip)))
       ".png"))

(defn parse-args
  "`[prefix ... --theme name --clip y:height --width pixels]` as a map: `:only` has the prefixes."
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
  "The pictures of every page in the light theme and in the dark one. `only` limits them to pages whose file starts
  with one of the prefixes; `:theme`, `:width` and `:clip` ask for one theme, one width and a slice of the page.
  A page of the style tile is taken at 1280 pixels; a mock-up of an interface is taken as it is in its frame, at each
  of the widths it is for."
  [{:keys [only theme width clip]}]
  (for [page pages/pages
        :when (or (empty? only) (some #(str/starts-with? (:file page) %) only))
        theme (if theme [theme] ["pulse" "pulse-dark"])
        width (cond width [width] (:frame page) (:devices page) :else [1280])]
    (cond-> {:page (if (:frame page) (mockup/frame-path (:file page)) (:file page))
             :theme theme :width width :mobile (< width 600) :name (file-name page theme width clip)}
      clip (assoc :clip clip))))
