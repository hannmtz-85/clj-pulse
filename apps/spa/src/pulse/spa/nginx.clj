(ns pulse.spa.nginx
  "The configuration of nginx for the workspace's release (ADR-0030, point 4), made from the same data as the Ring server that
  `bb spa:serve` runs: the headers and the policy of `pulse.spa.security`, and the kinds of file, the names that are served, the
  names that are kept and the page for every other path of `pulse.spa.files`. `bb spa:nginx` writes it to `infra/spa/nginx.conf`,
  which the image of the workspace copies, and a test fails when the committed file is not what this makes.

  nginx chooses a location for a request by its regular expressions in the order they are written: the first that matches the
  path wins, and the prefix `/` is what is left. So `rules`, in order, are the whole of what decides what a path is, and `classify`
  applies them to a path, which is how the tests hold them to the Ring server without an nginx. What the rules cannot say is
  written under `differences`."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [pulse.platform.config :as platform-config]
            [pulse.platform.http.problem :as problem]
            [pulse.spa.config :as config]
            [pulse.spa.files :as files]
            [pulse.spa.security :as security]))

(def path
  "Where the configuration is committed, and where the image of the workspace takes it from."
  "infra/spa/nginx.conf")

(def port
  "The port nginx listens on inside the container: the image is `nginx-unprivileged`, which cannot take a port below 1024."
  8080)

(def root
  "Where the image keeps the release."
  "/usr/share/nginx/html")

(def differences
  "Where nginx and the Ring server do not answer alike, and why it does not matter. Neither can be made to, and neither lets a
  request out of the directory of the release."
  ["nginx resolves the segments '.' and '..' of a path itself, before any rule, so a path that goes up and comes down again is the
   file it names; the Ring server says it is not a name. Neither serves anything outside the directory."
   "A path that is not valid percent-encoding is a 400 of nginx and a 404 of the Ring server (Jetty answers 400 to some of them
   before the handler)."
   "The Ring server answers /healthz to any method; nginx says 405 to a method that is not GET or HEAD before it looks at the path."
   "The body of a problem has no `instance` in nginx: the path of the request is the one thing in it that the sender chose."])

;; ---------------------------------------------------------------------------
;; What decides what a path is

(def bad-name
  "A path with a character that a name may not have, or a segment that does not begin with a letter, a digit or an underscore (an
  empty one, as in '//', or one that begins with a dot or a hyphen): what `pulse.spa.files/plain-name?` says no to."
  "[^A-Za-z0-9._/-]|/[^A-Za-z0-9_]")

(defn rules
  "The locations that nginx chooses by a regular expression, in the order it tries them, as `{:regex :action :why}`. The action
  is `:not-found` or `:file` (the file the path names, or a 404 when it is not there). A path that none of them matches is the
  page."
  []
  [{:regex bad-name
    :action :not-found
    :why "Not a name the workspace has a file or a route called: a name is made of plain segments (pulse.spa.files)."}
   {:regex (str "\\.(" (str/join "|" (sort (keys files/types))) ")$")
    :action :file
    :why "A name with the extension of a kind of file that the workspace has is that file, or it is not there."}
   {:regex "\\.[A-Za-z0-9]+/?$"
    :action :not-found
    :why "Any other extension, and any extension followed by a slash, is a file that is not there; it is never the page."}])

(defn classify
  "What `path` (decoded, as nginx has it in `$uri`) is for the configuration: `:healthz`, `:not-found`, `:file` or `:page`."
  [path]
  (if (= "/healthz" path)
    :healthz
    (or (some #(when (re-find (re-pattern (:regex %)) path) (:action %)) (rules))
        :page)))

;; ---------------------------------------------------------------------------
;; Text

(defn- quoted
  "`text` as a string of the configuration of nginx. A `$` would start a variable, so one is refused."
  [text]
  (when (str/includes? text "$")
    (throw (ex-info (str "A value for nginx cannot hold a dollar sign: " (pr-str text)) {:value text})))
  (str "\"" (-> text (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- regex-quoted
  "`regex` as a string of the configuration, with its backslashes as they are: nginx leaves `\\.` alone inside quotes."
  [regex]
  (when (str/includes? regex "\"")
    (throw (ex-info (str "A regular expression for nginx cannot hold a double quote: " (pr-str regex)) {:regex regex})))
  (str "\"" regex "\""))

(defn- single-quoted
  "`text` as the body of a `return`, in single quotes, where variables (`$request_id`) are still read."
  [text]
  (when (str/includes? text "'")
    (throw (ex-info (str "A body for nginx cannot hold a single quote: " (pr-str text)) {:body text})))
  (str "'" text "'"))

(defn- indent [n lines]
  (map #(if (str/blank? %) "" (str (apply str (repeat n " ")) %)) lines))

(defn- comment-lines [text]
  (map #(if (str/blank? %) "#" (str "# " %)) (str/split-lines text)))

(defn- bullet
  "`text` as the lines of a bullet no wider than `width`: the first begins with a dash and the rest are under its words."
  [text width]
  (let [lines (reduce (fn [lines word]
                        (let [current (peek lines)]
                          (if (and current (<= (+ (count current) 1 (count word)) width))
                            (conj (pop lines) (str current " " word))
                            (conj lines word))))
                      []
                      (str/split (str/trim text) #"\s+"))]
    (cons (str "- " (first lines)) (map #(str "  " %) (rest lines)))))

(defn- parts-of-type
  "`{:base :charset}` of a content type such as \"text/html; charset=utf-8\"."
  [content-type]
  (let [[base & parameters] (map str/trim (str/split content-type #";"))]
    {:base base
     :charset (some #(second (re-find #"^charset=(.+)$" %)) parameters)}))

(defn- kinds
  "The kinds of file of the workspace as `[extension {:base :charset}]`, in the order of the extensions."
  []
  (for [[extension content-type] (sort-by key files/types)
        :let [parts (parts-of-type content-type)]]
    (do (when-not (contains? #{nil "utf-8"} (:charset parts))
          (throw (ex-info (str "nginx writes the charset of a type for all of them alike, utf-8: " content-type)
                          {:extension extension :content-type content-type})))
        [extension parts])))

(defn- cache-map
  "The map from the status and the path of a response to what the browser may keep: for ever for a name with the hash of its
  content, only after asking for the rest of the files and the page, and nothing for anything else (an error, the health check)."
  []
  (let [pattern (.pattern ^java.util.regex.Pattern files/hashed)
        _ (when-not (and (str/starts-with? pattern "^") (str/ends-with? pattern "$"))
            (throw (ex-info "The pattern of a name with a hash must be anchored" {:pattern pattern})))
        hashed (subs pattern 1)
        immutable (files/cache-control "main.77BB12C92D2AE8EA.js")
        revalidate (files/cache-control "pulse.css")
        nothing (get security/defaults "Cache-Control")]
    (concat
     (comment-lines (str "What a browser may keep of a response: " immutable " for a script whose name has the hash of its content,\n"
                         revalidate " (it asks whether the file has changed) for the page, the stylesheet, the font and the icon, and\n"
                         nothing " for anything else, an error and the health check among it."))
     ["map \"$status:$uri\" $spa_cache_control {"
      (str "    default " (quoted nothing) ";")
      (str "    " (regex-quoted (str "~^(?:200|304):(?:.*/)?" hashed)) " " (quoted immutable) ";")
      (str "    " (regex-quoted "~^(?:200|304):/healthz$") " " (quoted nothing) ";")
      (str "    " (regex-quoted "~^(?:200|304):") " " (quoted revalidate) ";")
      "}"])))

(defn- allow-map
  "The methods that a 405 says are allowed. An empty value is no header at all."
  []
  (concat
   (comment-lines "The methods that a 405 says are allowed (an empty value is no header).")
   ["map $status $spa_allow {"
    "    default \"\";"
    "    405 \"GET, HEAD\";"
    "}"]))

(defn- problem-location
  "A named location that answers `status` with a problem, as the Ring server does (`pulse.platform.http.problem`)."
  [name status options]
  (let [response (problem/response status (assoc-in options [:request :request-id] "$request_id"))]
    [(str "location " name " {")
     "    types {}"
     (str "    default_type " (get-in response [:headers "Content-Type"]) ";")
     (str "    return " status " " (single-quoted (:body response)) ";")
     "}"]))

(defn- location [head directives]
  (concat [(str head " {")] (indent 4 directives) ["}"]))

(defn- rule-location [{:keys [regex action why]}]
  (concat (comment-lines why)
          (location (str "location ~ " (regex-quoted regex))
                    [(case action
                       :not-found "return 404;"
                       :file "try_files $uri =404;")])))

(defn- header-lines
  "The `add_header` of every header the Ring server adds, but the one that says how long a response may be kept, which the map
  decides, and the ones that vary with the response."
  [settings secure?]
  (let [headers (dissoc (:headers (security/header-options settings {:secure? secure?})) "Cache-Control")]
    (concat (for [[header-name value] (sort-by key headers)]
              (str "add_header " header-name " " (quoted value) " always;"))
            ["add_header Cache-Control $spa_cache_control always;"
             "add_header Allow $spa_allow always;"
             "add_header X-Request-Id $request_id always;"])))

(defn generate
  "The text of the configuration for `settings`: `:api-base-url` and `:issuer`, which the policy names, and `:base-url`, whose
  scheme says whether the headers are those of https."
  [{:keys [base-url] :as settings}]
  (let [secure? (str/starts-with? (str base-url) "https://")
        kinds (kinds)
        ;; nginx always sets the charset of text/html, and warns of a duplicate when the list says so too
        charset-types (->> kinds (filter (comp :charset second)) (map (comp :base second)) (remove #{"text/html"}))
        liveness (json/write-value-as-string {:status "up"})]
    (str
     (str/join
      "\n"
      (concat
       (comment-lines
        (str "The nginx of the clinical workspace's release (ADR-0030, point 4). Made by `bb spa:nginx` from pulse.spa.security (the\n"
             "headers and the policy) and pulse.spa.files (which path is which): do not edit it, change those and make it again. A\n"
             "test fails when this file is not what they make.\n"
             "\n"
             "The policy lets the page call " (:api-base-url settings) " and " (:issuer settings) " and nothing else; the workspace is\n"
             "served at " base-url ". Other addresses are another file: `bb spa:nginx --api-base-url ... --issuer ... --base-url ...`.\n"
             "\n"
             "Where this does not answer as the Ring server of `bb spa:serve` does:\n"
             (str/join "\n" (mapcat #(bullet % 118) differences))))
       [""]
       (cache-map)
       [""]
       (allow-map)
       [""
        "server {"]
       (indent 4
               (concat
                [(str "listen " port ";")
                 "server_name _;"
                 "server_tokens off;"
                 (str "root " root ";")
                 ""]
                (comment-lines "An empty segment ('//') is a name that is not there, as it is for the Ring server: keep the slashes.")
                ["merge_slashes off;"
                 ""]
                (comment-lines "The kinds of file that are served, and the charset of the ones that are text (text/html always has it).")
                ["types {"]
                (indent 4 (for [[extension {:keys [base]}] kinds] (str base " " extension ";")))
                ["}"
                 "charset utf-8;"]
                (when (seq charset-types) [(str "charset_types " (str/join " " charset-types) ";")])
                [""]
                (comment-lines "The same headers on every response, an error among them.")
                (header-lines settings secure?)
                [""]
                (comment-lines "Only reading is allowed.")
                ["if ($request_method !~ \"^(?:GET|HEAD)$\") {"
                 "    return 405;"
                 "}"
                 ""
                 "error_page 404 @spa_not_found;"
                 "error_page 405 @spa_method_not_allowed;"
                 ""]
                (comment-lines "The Ring server says that the process is serving at /healthz.")
                (location "location = /healthz"
                          ["types {}"
                           "default_type application/json;"
                           (str "return 200 " (single-quoted liveness) ";")])
                [""]
                (mapcat #(concat (rule-location %) [""]) (rules))
                (comment-lines "Every other path is a route of the workspace, and the page is the answer to it (history mode).")
                (location "location /" ["try_files /index.html =404;"])
                [""]
                (problem-location "@spa_not_found" 404 {:detail "There is no such file."})
                [""]
                (problem-location "@spa_method_not_allowed" 405 {})))
       ["}"]))
     "\n")))

;; ---------------------------------------------------------------------------
;; The committed file

(defn default-settings
  "The addresses the workspace is built for: those of `config.edn`, whatever the environment of the process says, so that the
  committed file is the same on every machine."
  []
  (binding [platform-config/*environment* (merge platform-config/*environment* {"API_BASE_URL" nil "KEYCLOAK_ISSUER" nil "SPA_BASE_URL" nil})]
    (select-keys (:pulse.spa/handler (config/load-config {:profile :dev})) [:api-base-url :issuer :base-url])))

(defn check
  "Nil when the committed file is what `generate` makes for `settings` (the defaults when there are none), and otherwise a sentence
  that says what is wrong with it."
  ([] (check (default-settings)))
  ([settings]
   (let [file (java.io.File. ^String path)]
     (cond
       (not (.exists file)) (str path " is not there. Run `bb spa:nginx`.")
       (not= (generate settings) (slurp file :encoding "UTF-8")) (str path " is not what pulse.spa.nginx makes. Run `bb spa:nginx`.")))))

(defn write!
  "Writes the configuration for `settings` (the defaults when there are none) to `path`, with line ends that are LF."
  ([] (write! (default-settings)))
  ([settings]
   (let [file (java.io.File. ^String path)]
     (some-> (.getParentFile file) .mkdirs)
     (spit file (generate settings) :encoding "UTF-8")
     (println "Wrote" path))))

(defn parse-args
  "The settings and the mode that the arguments of `bb spa:nginx` ask for: `check` only compares, and `--api-base-url`, `--issuer`
  and `--base-url` change the addresses the file is made for. An argument that is not one of them is an exception."
  [args]
  (loop [args (seq args)
         result {:mode :write :settings {}}]
    (if-not args
      result
      (let [[argument & more] args
            options {"--api-base-url" :api-base-url "--issuer" :issuer "--base-url" :base-url}]
        (cond
          (= "check" argument) (recur more (assoc result :mode :check))
          (and (contains? options argument) more) (recur (next more) (assoc-in result [:settings (options argument)] (first more)))
          :else (throw (ex-info (str "I do not know what to do with " (pr-str argument) ". The arguments are `check`, `--api-base-url address`, "
                                     "`--issuer address` and `--base-url address`.")
                                {:argument argument})))))))

(defn -main
  "`clojure -M:dev -m pulse.spa.nginx [check] [--api-base-url address] [--issuer address] [--base-url address]`, or `bb spa:nginx`."
  [& args]
  (let [{:keys [mode settings]} (try (parse-args args)
                                     (catch clojure.lang.ExceptionInfo e
                                       (binding [*out* *err*] (println (ex-message e)))
                                       (System/exit 2)))
        settings (merge (default-settings) settings)]
    (case mode
      :write (write! settings)
      :check (if-let [problem (check settings)]
               (do (println problem) (System/exit 1))
               (println path "is what pulse.spa.nginx makes.")))))
