(ns pulse.web.assets
  "The files the portal serves itself (ADR-0028): the stylesheet, the font, the icon and htmx, from `resources/public` on
  the classpath. The Content-Security-Policy of its pages allows nothing from another server, so there is nothing to
  fetch from one. `bb design:web` makes them.

  Only a name made of plain segments is looked up, whatever the router decoded: no empty segment, no `.` or `..`, no
  segment that begins with a dot, and nothing but letters, digits, dots, hyphens and underscores. The names carry no
  version, so the browser may keep a file but must ask whether it has changed (`no-cache`), and the answer is a 304 with
  no body when it has not (`wrap-not-modified`, on the route)."
  (:require [clojure.string :as str]
            [pulse.web.errors :as errors]
            [ring.util.response :as response]))

(def ^:private root "public")

(def ^:private segment #"[A-Za-z0-9_][A-Za-z0-9._-]*")

(def types
  "The kinds of file the portal serves, by extension. Anything else is not served."
  {"css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "svg" "image/svg+xml"
   "woff2" "font/woff2"
   "txt" "text/plain; charset=utf-8"})

(defn plain-name?
  "Whether `name` is a path made of plain segments, such as `fonts/inter-latin-wght-normal.woff2`."
  [name]
  ;; no empty name or segment gets through the pattern, which wants a character at the start of each
  (and (string? name)
       (every? #(re-matches segment %) (str/split name #"/" -1))))

(defn- extension [name]
  (second (re-find #"\.([A-Za-z0-9]+)$" name)))

(defn serve
  "The file called `name` under `resources/public`, as a Ring response, or nil when there is no such file or it is not a
  kind that is served. The browser may keep it, but must ask whether it has changed."
  [name]
  (when-let [type (and (plain-name? name) (types (extension name)))]
    (when-let [file (response/resource-response name {:root root})]
      (-> file
          (response/content-type type)
          (response/header "Cache-Control" "no-cache")))))

(defn handler
  "The Ring handler of the files: it answers a request whose route has the name of the file in its path parameter `:path`,
  and the page that says it cannot be found for a file that is not there."
  [request]
  (or (serve (get-in request [:path-params :path]))
      (errors/failure :not-found request)))
