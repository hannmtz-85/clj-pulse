(ns pulse.spa.files
  "The files of the workspace, from a directory (ADR-0029, point 9): the page, the script that the build made, the stylesheet, the
  font and the icon. Every path that is not a file is the page, because the routes of the workspace are paths (history mode) and a
  person may open or reload any of them.

  Only a name made of plain segments is looked up, whatever the browser sent: no empty segment, no `.` or `..`, no segment that
  begins with a dot, and nothing but letters, digits, dots, hyphens and underscores, after the percent-encoding has been undone
  (so `%2e%2e` and `%2F` are no way out of the directory). A name with an extension is a file or it is not there; it is never the
  page. What is not a kind of file that the workspace has is not served."
  (:require [clojure.string :as str]
            [pulse.platform.http.problem :as problem]
            [ring.util.codec :as codec]
            [ring.util.response :as response])
  (:import (java.io File)))

(def ^:private segment #"[A-Za-z0-9_][A-Za-z0-9._-]*")

(def types
  "The kinds of file that are served, by extension. Anything else is not served."
  {"html" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "svg" "image/svg+xml"
   "woff2" "font/woff2"
   "txt" "text/plain; charset=utf-8"})

(def ^:private hashed
  "A name that the build made from the content of the file (`main.7A1F2C09.js`): it never changes what it holds, so it may be kept."
  #"^[A-Za-z0-9_-]+\.[0-9A-Fa-f]{8,}\.js$")

(defn plain-name?
  "Whether `name` is a path made of plain segments, such as `fonts/inter-latin-wght-normal.woff2`."
  [name]
  (and (string? name)
       (every? #(re-matches segment %) (str/split name #"/" -1))))

(defn- extension [name]
  (second (re-find #"\.([A-Za-z0-9]+)$" name)))

(defn- last-segment [name]
  (peek (str/split name #"/")))

(defn cache-control
  "How long the browser may keep the file called `name`: for ever when its name says what it holds, and otherwise only after it has
  asked whether it has changed (the page, the stylesheet, the font and the icon have the same name for every version)."
  [name]
  (if (re-matches hashed (last-segment name))
    "public, max-age=31536000, immutable"
    "no-cache"))

(defn- without-final-slash
  "`name` without the one slash that a route may end in (`patients/pt-ana/`), which is no different from the route without it.
  A file does not end in one, and two slashes are an empty segment, which no name has."
  [name]
  (if (re-find #"[^/]/$" name)
    (let [shorter (subs name 0 (dec (count name)))]
      (if (extension shorter) name shorter))
    name))

(defn- path-name
  "The name that the path of a request asks for, with the percent-encoding undone, or nil when it is not a plain name. `/` is the
  page."
  [uri]
  (let [decoded (try (codec/url-decode (str uri)) (catch Exception _ nil))
        name (some-> decoded (str/replace-first #"^/" "") without-final-slash)]
    (cond
      (nil? name) nil
      (= "" name) name
      (plain-name? name) name)))

(defn- file-response
  "The file called `name` under `root` as a response, or nil when it is not a file there: a directory is not one, whatever is in
  it, and a link is not followed."
  [^File root name]
  (some-> (response/file-response name {:root (str root) :index-files? false :allow-symlinks? false})
          (response/content-type (get types (extension name)))
          (response/header "Cache-Control" (cache-control name))))

(defn- not-found [request]
  (problem/response 404 {:detail "There is no such file." :request request}))

(defn handler
  "The Ring handler of the files under the directory `root`, for GET and HEAD. A path that names a kind of file that is served is
  that file, or a 404; any other path is the page, `index.html`, which a request for `/` also is."
  [root]
  (let [root (File. ^String root)]
    (fn [request]
      (if-not (contains? #{:get :head} (:request-method request))
        (problem/response 405 {:headers {"Allow" "GET, HEAD"} :request request})
        (let [name (path-name (:uri request))
              extension (some-> name extension)]
          (cond
            (nil? name) (not-found request)
            (nil? extension) (or (file-response root "index.html") (not-found request))
            (contains? types extension) (or (file-response root name) (not-found request))
            :else (not-found request)))))))
