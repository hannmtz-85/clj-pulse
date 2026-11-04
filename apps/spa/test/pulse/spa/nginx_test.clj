(ns pulse.spa.nginx-test
  "The configuration of nginx for the workspace's release (ADR-0030, point 4): the committed file is what the generator makes, the
  generator is made of the data of the Ring server (its headers, its kinds of file, its names, its way of keeping a file), and the
  rules that decide what a path is give the answer that the Ring server gives. There is no nginx here to ask: the container is held to
  the same table of requests by `bb smoke`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.platform.config :as platform]
            [pulse.platform.http.problem :as problem]
            [pulse.spa.files :as files]
            [pulse.spa.handler :as handler]
            [pulse.spa.nginx :as nginx]
            [pulse.spa.security :as security]
            [spa-requests :as requests])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private script "main.77BB12C92D2AE8EA.js")

(def ^:private settings (nginx/default-settings))

(def ^:dynamic *root* nil)

(defn- write! [^java.io.File root path content]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file content)))

(defn- with-release
  "A release as `bb spa:build` makes it, beside a file that is not part of it."
  [f]
  (let [parent (.toFile (Files/createTempDirectory "spa-nginx-" (make-array FileAttribute 0)))
        root (io/file parent "release")]
    (write! root "index.html" "<!DOCTYPE html><title>Pulse</title>")
    (write! root (str "js/" script) "console.log(1)")
    (write! root "js/manifest.edn" "[]")
    (write! root "pulse.css" "body{}")
    (write! root "favicon.svg" "<svg/>")
    (write! root "fonts/inter-latin-wght-normal.woff2" "font")
    (write! root "fonts/OFL.txt" "license")
    (write! root ".hidden.html" "hidden")
    (binding [*root* (str root)]
      (f))))

(use-fixtures :once with-release)

(def ^:private text (delay (nginx/generate settings)))

(defn- lines [] (str/split-lines @text))

(defn- body [response]
  (let [b (:body response)] (cond (string? b) b (instance? java.io.File b) (slurp b) :else b)))

;; ---------------------------------------------------------------------------
;; The file

(deftest the-committed-file-is-what-the-generator-makes
  (is (nil? (nginx/check)) "run `bb spa:nginx` and commit what it writes")
  (is (= @text (slurp "infra/spa/nginx.conf" :encoding "UTF-8"))))

(deftest the-file-is-made-for-the-addresses-of-the-local-stack-whatever-the-environment-says
  (is (= {:api-base-url "http://localhost:4000"
          :issuer "http://localhost:8080/realms/pulse"
          :base-url "http://localhost:3000"}
         settings))
  (binding [platform/*environment* {"API_BASE_URL" "https://api.example" "KEYCLOAK_ISSUER" "https://sso.example/realms/x"
                                    "SPA_BASE_URL" "https://app.example"}]
    (is (= settings (nginx/default-settings)) "so the committed file is the same on every machine")))

(deftest the-file-is-text-that-git-keeps-as-it-is
  (is (str/ends-with? @text "\n"))
  (is (not (str/ends-with? @text "\n\n")))
  (is (not (str/includes? @text "\r")) "LF, whatever the machine that made it")
  (is (not (str/includes? @text "\t")))
  (is (not-any? #(re-find #"\s$" %) (lines)) "no space at the end of a line")
  (is (every? #(or (<= (count %) 140) (re-find #"^    add_header (Content-Security|Permissions)-Policy " %)) (lines))
      "a line is short, unless it is a header whose value is a list")
  (testing "and braces that close what they open, leaving out the comments and the JSON of the bodies that are returned"
    (let [code (->> (lines)
                    (remove #(str/starts-with? (str/trim %) "#"))
                    (map #(str/replace % #"'\{.*\}'" "")))]
      (is (seq (filter #(str/includes? % "{") code)))
      (is (= (count (re-seq #"\{" (str/join "\n" code)))
             (count (re-seq #"\}" (str/join "\n" code))))))))

(deftest it-listens-where-the-image-takes-it-to
  (is (some #{(str "    listen " nginx/port ";")} (lines)))
  (is (= 8080 nginx/port) "the image is nginx-unprivileged, which cannot take a port below 1024")
  (is (some #{(str "    root " nginx/root ";")} (lines)))
  (is (some #{"    server_tokens off;"} (lines)) "the version of nginx is nobody's business"))

;; ---------------------------------------------------------------------------
;; The headers

(defn- header-value
  "The value of the header `header-name` that the text adds, or nil."
  [text header-name]
  (second (re-find (re-pattern (str "(?m)^    add_header " header-name " \"(.*)\" always;$")) text)))

(deftest every-header-of-the-ring-server-is-added-to-every-response-with-its-value
  (let [headers (dissoc (:headers (security/header-options settings)) "Cache-Control")]
    (is (seq headers))
    (doseq [[header-name value] headers]
      (is (= value (header-value @text header-name)) header-name))
    (testing "and the policy is the policy"
      (is (= (security/policy settings) (header-value @text "Content-Security-Policy"))))
    (testing "and no others are written by hand"
      (is (= (set (conj (keys headers) "Cache-Control" "Allow" "X-Request-Id"))
             (set (map second (re-seq #"(?m)^    add_header (\S+) " @text))))))))

(deftest the-headers-are-added-once-at-the-top-so-that-no-location-drops-them
  (testing "add_header in a location replaces all those of the server that contains it"
    (let [add-headers (filter #(str/includes? % "add_header ") (lines))]
      (is (seq add-headers))
      (is (every? #(re-find #"^    add_header " %) add-headers) "all at the level of the server")
      (is (every? #(str/ends-with? % " always;") add-headers) "an error carries them as well"))))

(deftest strict-transport-security-is-for-an-https-workspace-only
  (is (nil? (header-value @text "Strict-Transport-Security")))
  (let [secure (nginx/generate (assoc settings :base-url "https://app.example"))]
    (is (= security/hsts (header-value secure "Strict-Transport-Security")))
    (is (= (header-value @text "Content-Security-Policy") (header-value secure "Content-Security-Policy")) "the policy names the api and the identity provider, not the workspace")))

(deftest the-policy-names-the-api-and-the-identity-provider-of-the-settings
  (let [other (nginx/generate (assoc settings :api-base-url "https://api.example:8443" :issuer "https://sso.example/realms/x"))]
    (is (str/includes? (header-value other "Content-Security-Policy") "connect-src 'self' https://api.example:8443 https://sso.example;"))
    (is (str/includes? other "# The policy lets the page call https://api.example:8443 and https://sso.example/realms/x and nothing else;"))))

(deftest a-dollar-sign-in-a-value-is-refused-since-nginx-would-read-a-variable
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dollar sign"
                        (nginx/generate (assoc settings :api-base-url "http://api$host:4000")))))

;; ---------------------------------------------------------------------------
;; The kinds of file, and how long they are kept

(defn- block-lines
  "The lines between the line `start` and the `}` that closes it, trimmed."
  [text start]
  (->> (str/split-lines text)
       (drop-while #(not= start (str/trim %)))
       rest
       (take-while #(not= "}" (str/trim %)))
       (map str/trim)))

(deftest the-kinds-of-file-are-those-of-the-ring-server
  (let [declared (set (for [line (block-lines @text "types {")
                            :let [[type extension] (str/split (str/replace line ";" "") #"\s+")]]
                        [extension type]))
        expected (set (for [[extension content-type] files/types]
                        [extension (first (str/split content-type #";"))]))]
    (is (= expected declared)))
  (testing "and the ones that are text have the charset the Ring server gives them"
    (let [with-charset (set (for [[_ content-type] files/types
                                  :when (str/includes? content-type "charset=utf-8")
                                  :let [type (first (str/split content-type #";"))]
                                  ;; nginx always sets the charset of text/html, and warns of a duplicate when it is listed
                                  :when (not= "text/html" type)]
                              type))]
      (is (some #{"    charset utf-8;"} (lines)))
      (is (= with-charset (set (str/split (second (re-find #"(?m)^    charset_types (.*);$" @text)) #" ")))))))

(defn- cache-entries
  "The map `$spa_cache_control` as `[[regex value] ...]` in order and its default."
  [text]
  (let [entries (block-lines text "map \"$status:$uri\" $spa_cache_control {")]
    {:default (second (re-find #"^default \"(.*)\";$" (first (filter #(str/starts-with? % "default") entries))))
     :regexes (vec (for [entry entries
                         :let [[_ regex value] (re-find #"^\"~(.*)\" \"(.*)\";$" entry)]
                         :when regex]
                     [(re-pattern regex) value]))}))

(defn- kept
  "What the map says of a response with `status` for `uri`, as nginx reads a map: the first regular expression that matches, else the
  default."
  [text status uri]
  (let [{:keys [default regexes]} (cache-entries text)
        key (str status ":" uri)]
    (or (some (fn [[regex value]] (when (re-find regex key) value)) regexes) default)))

(deftest what-a-browser-keeps-is-what-the-ring-server-says-it-may
  (doseq [name ["pulse.css" "favicon.svg" "index.html" "fonts/OFL.txt" "fonts/inter-latin-wght-normal.woff2" "js/main.js"
                (str "js/" script) "js/main.0123abcd.js" "js/main.0123abc.js" "js/main.xyz12345.js" "main.77BB12C92D2AE8EA86AC9E48FF8D222A.js"
                "js/pulse.0123abcd.css" "a/b/main.0123ABCD.js"]]
    (testing name
      (is (= (files/cache-control name) (kept @text 200 (str "/" name))))
      (is (= (files/cache-control name) (kept @text 304 (str "/" name))) "and a 304 says the same")))
  (testing "the page is kept as the file it is, however the path asked for it"
    (is (= "no-cache" (kept @text 200 "/index.html"))))
  (testing "nothing else is kept: an error, whatever the file, and the health check"
    (doseq [status [400 403 404 405 500 502]]
      (is (= "no-store" (kept @text status (str "/js/" script))) (str status)))
    (is (= "no-store" (kept @text 200 "/healthz")))
    (is (= (get security/defaults "Cache-Control") (:default (cache-entries @text))))))

(deftest a-name-that-is-kept-is-the-one-the-pattern-of-the-ring-server-says
  (let [hashed (str/join "|" (map (comp str first) (:regexes (cache-entries @text))))]
    (is (str/includes? hashed (subs (.pattern ^java.util.regex.Pattern files/hashed) 1)) "the regular expression is written from the pattern, not beside it")))

(defn- allowed-for
  "What `$spa_allow` is for a response with `status`."
  [text status]
  (let [entries (block-lines text "map $status $spa_allow {")]
    (or (some (fn [entry] (let [[_ k v] (re-find #"^(\d+) \"(.*)\";$" entry)] (when (= (str status) k) v))) entries)
        (second (re-find #"^default \"(.*)\";$" (first (filter #(str/starts-with? % "default") entries)))))))

(deftest a-405-says-what-is-allowed-and-nothing-else-does
  (is (= "GET, HEAD" (allowed-for @text 405)))
  (doseq [status [200 304 404 500]]
    (is (= "" (allowed-for @text status)) "an empty value is no header")))

;; ---------------------------------------------------------------------------
;; What a path is

(defn- ring [root]
  (handler/ring-handler {:root root
                         :api-base-url (:api-base-url settings)
                         :issuer (:issuer settings)
                         :base-url (:base-url settings)
                         :quiet-paths #{"/healthz"}}))

(defn- ring-status [uri]
  (:status ((ring *root*) {:request-method :get :uri uri :headers {}})))

(defn- expected-status
  "The status that the rules give a path: nothing for a file that is not in the release."
  [uri]
  (case (nginx/classify uri)
    :not-found 404
    :page 200
    :healthz 200
    :file (if (.isFile (io/file *root* (subs uri 1))) 200 404)))

(deftest the-rules-are-in-the-order-that-they-are-written
  (is (= [:not-found :file :not-found] (map :action (nginx/rules))))
  (testing "and the text has them in that order, with the page after them: nginx tries a regular expression in the order it finds them"
    (let [locations (map second (re-seq #"(?m)^    location (.*) \{$" @text))]
      (is (= (concat ["= /healthz"]
                     (map #(str "~ \"" (:regex %) "\"") (nginx/rules))
                     ["/" "@spa_not_found" "@spa_method_not_allowed"])
             locations)))))

(deftest a-path-is-what-the-ring-server-says-it-is
  (doseq [uri ["/" "/patients/pt-ana" "/patients/pt-ana/" "/alerts" "/appointments/ap-1/confirm" "/js" "/fonts" "/a/b/c/d" "/signed-out"
               "/index.html" "/pulse.css" "/favicon.svg" "/fonts/inter-latin-wght-normal.woff2" "/fonts/OFL.txt" (str "/js/" script)
               "/missing.js" "/js/missing.js" "/patients/pt-ana.html" "/favicon.ico" "/pulse.css/" "/patients//" "//" "/js/manifest.edn"
               "/.hidden.html" "/js/.hidden.html" "/-x" "/a b" "/café" "/a:b" "/X.JS" "/a." "/a./" "/." "/.." "/a/./b" "/_x" "/a-" "/v1.2/x"
               "/healthz"]]
    (is (= (ring-status uri) (expected-status uri)) uri)))

(def ^:private segment-gen
  (gen/one-of [(gen/elements ["" "." ".." ".hidden" "-x" "js" "fonts" "patients" "pt-ana" "alerts" "pulse.css" "index.html"
                              "favicon.svg" "inter-latin-wght-normal.woff2" "OFL.txt" "manifest.edn" "missing.js" script "a b" "café"
                              "X.JS" "a." "_x" "v1.2" "healthz" "a:b" "a+b" "a~b"])
               (gen/fmap #(apply str %) (gen/vector (gen/elements (seq "abcXYZ019_-. :+~@é")) 0 8))]))

(defspec whatever-the-path-is-the-rules-say-what-the-ring-server-says 600
  (prop/for-all [segments (gen/vector segment-gen 0 4)
                 slash? gen/boolean]
    (let [uri (str "/" (str/join "/" segments) (when slash? "/"))]
      (= (ring-status uri) (expected-status uri)))))

(deftest the-page-and-the-files-are-looked-for-where-the-release-is
  (is (= 1 (count (filter #{"        try_files /index.html =404;"} (lines)))) "the page, by name, for every path that is not a file")
  (is (= 1 (count (filter #{"        try_files $uri =404;"} (lines)))) "the file a path names, for the kinds of file that are served")
  (is (not-any? #(str/includes? % "autoindex on") (lines)))
  (is (some #{"    merge_slashes off;"} (lines)) "an empty segment is a name that is not there"))

;; ---------------------------------------------------------------------------
;; The answers that nginx writes itself

(defn- return-body
  "The body that the location `name` returns, with the variable replaced by `request-id`."
  [text name request-id]
  (let [block (block-lines text (str "location " name " {"))
        line (first (filter #(str/starts-with? % "return ") block))]
    (some-> (re-find #"^return \d+ '(.*)';$" line) second (str/replace "$request_id" request-id) json/read-str)))

(deftest a-404-and-a-405-are-the-problems-the-ring-server-writes
  (let [ring-handler (ring *root*)
        not-found (ring-handler {:request-method :get :uri "/missing.js" :headers {}})
        refused (ring-handler {:request-method :post :uri "/" :headers {}})
        ;; the path of the request is the one thing in a problem that the sender chose, and the id is the server's own
        from-ring #(-> % :body json/read-str (dissoc "instance" "requestId"))
        from-nginx #(dissoc (return-body @text % "abc") "requestId")]
    (is (= (from-ring not-found) (from-nginx "@spa_not_found")))
    (is (= (from-ring refused) (from-nginx "@spa_method_not_allowed")))
    (is (= "abc" (get (return-body @text "@spa_not_found" "abc") "requestId")) "with the id of the request that nginx makes")
    (is (= "abc" (get (return-body @text "@spa_method_not_allowed" "abc") "requestId")))
    (is (not-any? #(contains? (return-body @text % "abc") "instance") ["@spa_not_found" "@spa_method_not_allowed"]))
    (testing "in the type of a problem, whatever the extension of the path that was asked for"
      (doseq [name ["@spa_not_found" "@spa_method_not_allowed"]
              :let [block (block-lines @text (str "location " name " {"))]]
        (is (some #{"types {}"} block) "or a 404 for a .js would be a script")
        (is (some #{"default_type application/problem+json;"} block))))))

(deftest the-health-check-says-what-the-ring-server-says
  (let [response ((ring *root*) {:request-method :get :uri "/healthz" :headers {}})
        block (block-lines @text "location = /healthz {")]
    (is (= (body response) (second (re-find #"^return 200 '(.*)';$" (first (filter #(str/starts-with? % "return ") block))))))
    (is (some #{"default_type application/json;"} block))
    (is (some #{"types {}"} block))))

(deftest only-reading-is-allowed-before-any-path-is-looked-at
  (is (some #{"    if ($request_method !~ \"^(?:GET|HEAD)$\") {"} (lines)))
  (is (some #{"    error_page 404 @spa_not_found;"} (lines)))
  (is (some #{"    error_page 405 @spa_method_not_allowed;"} (lines))))

;; ---------------------------------------------------------------------------
;; The table of requests

(deftest the-ring-server-answers-the-table
  (let [context {:headers (dissoc (:headers (security/header-options settings)) "Cache-Control")
                 :page (slurp (io/file *root* "index.html"))}
        server (ring *root*)]
    (doseq [row (requests/requests script)]
      (let [response (update (server {:request-method (:method row) :uri (:path row) :headers {}}) :body #(when % (body {:body %})))]
        (is (= [] (requests/problems row response context)) (str (name (:method row)) " " (:path row)))))))

;; ---------------------------------------------------------------------------
;; Making it

(deftest check-says-when-the-committed-file-is-not-what-the-generator-makes
  (is (nil? (nginx/check settings)))
  (is (re-find #"is not what pulse.spa.nginx makes" (nginx/check (assoc settings :issuer "http://localhost:9999/realms/pulse"))))
  (let [file (.toFile (Files/createTempFile "nginx-" ".conf" (make-array FileAttribute 0)))]
    (with-redefs [nginx/path (str file)]
      (is (nil? (do (spit file (nginx/generate settings)) (nginx/check settings))))
      (is (re-find #"is not what" (do (spit file "server {}") (nginx/check settings))))
      (io/delete-file file)
      (is (re-find #"is not there" (nginx/check settings))))))

(deftest the-arguments-of-the-task
  (is (= {:mode :write :settings {}} (nginx/parse-args [])))
  (is (= {:mode :check :settings {}} (nginx/parse-args ["check"])))
  (is (= {:mode :write :settings {:api-base-url "https://a" :issuer "https://i/realms/x" :base-url "https://w"}}
         (nginx/parse-args ["--api-base-url" "https://a" "--issuer" "https://i/realms/x" "--base-url" "https://w"])))
  (is (= {:mode :check :settings {:issuer "https://i"}} (nginx/parse-args ["--issuer" "https://i" "check"])))
  (doseq [bad [["nope"] ["--issuer"] ["--issuer" "https://i" "--also"] ["--api-base-url"]]]
    (is (thrown? clojure.lang.ExceptionInfo (nginx/parse-args bad)) (pr-str bad))))

;; ---------------------------------------------------------------------------
;; What a mutation analysis of the generator asked for

(defn- message-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest what-a-path-is-says-which-of-the-four-it-is
  (is (= :healthz (nginx/classify "/healthz")))
  (is (= :page (nginx/classify "/")))
  (is (= :page (nginx/classify "/patients/pt-ana")))
  (is (= :file (nginx/classify "/pulse.css")))
  (is (= :file (nginx/classify (str "/js/" script))))
  (is (= :not-found (nginx/classify "/missing.php")))
  (is (= :not-found (nginx/classify "//")))
  (testing "only /healthz itself is the health check"
    (is (= :page (nginx/classify "/healthz/")))
    (is (= :page (nginx/classify "/healthzx")))))

(deftest each-rule-does-what-its-action-says
  (let [directive {:not-found "return 404;" :file "try_files $uri =404;"}]
    (doseq [{:keys [regex action]} (nginx/rules)]
      (is (= [(directive action)] (block-lines @text (str "location ~ \"" regex "\" {"))) regex))))

(deftest the-server-has-a-name-and-refuses-a-method-that-is-not-reading
  (is (some #{"    server_name _;"} (lines)))
  (is (= ["return 405;"] (block-lines @text "if ($request_method !~ \"^(?:GET|HEAD)$\") {"))))

(deftest how-long-a-file-is-kept-is-said-once-and-by-the-map
  (is (= ["    add_header Cache-Control $spa_cache_control always;"]
         (filter #(str/includes? % "add_header Cache-Control") (lines)))
      "a second Cache-Control, written by hand beside the map, would be two headers"))

(deftest a-value-with-a-quote-or-a-backslash-is-escaped
  (let [text (nginx/generate (assoc settings :api-base-url "http://api\"x\\y:4000"))]
    (is (str/includes? text "http://api\\\"x\\\\y:4000") "or nginx would read the end of the string where the value goes on")))

(deftest what-nginx-cannot-be-told-is-refused-in-words
  (testing "a regular expression with a double quote"
    (with-redefs [nginx/bad-name "a\"b"]
      (is (= "A regular expression for nginx cannot hold a double quote: \"a\\\"b\"" (message-of #(nginx/generate settings))))))
  (testing "a body with a single quote"
    (with-redefs [problem/response (fn [& _] {:headers {"Content-Type" "application/problem+json"} :body "it's"})]
      (is (= "A body for nginx cannot hold a single quote: \"it's\"" (message-of #(nginx/generate settings))))))
  (testing "a charset that is not the one nginx is told to write"
    (with-redefs [files/types {"js" "text/javascript; charset=latin1"}]
      (is (= "nginx writes the charset of a type for all of them alike, utf-8: text/javascript; charset=latin1"
             (message-of #(nginx/generate settings))))))
  (testing "a pattern of a name with a hash that is not anchored at both ends"
    (doseq [pattern [#"main\.js$" #"^main\.js" #"main\.js"]]
      (with-redefs [files/hashed pattern]
        (is (= "The pattern of a name with a hash must be anchored" (message-of #(nginx/generate settings))) (str pattern))))
    (with-redefs [files/hashed #"^main\.js$"]
      (is (string? (nginx/generate settings)) "and one that is anchored is fine"))))

(deftest a-list-of-types-for-the-charset-is-written-only-when-it-has-something-in-it
  (with-redefs [files/types {"js" "text/javascript" "html" "text/html; charset=utf-8"}]
    (let [text (nginx/generate settings)]
      (is (not (str/includes? text "charset_types")) "html always has it: a list with nothing in it is not valid")
      (is (str/includes? text "    charset utf-8;\n")))))

(deftest the-differences-are-in-the-file-word-for-word-in-lines-of-a-width
  (let [header (take-while #(str/starts-with? % "#") (lines))
        bullets (filter #(re-find #"^# (- |  )" %) header)
        words #(str/replace (str/trim %) #"\s+" " ")
        said (words (str/join " " (map #(str/replace % #"^# (- |  )" "") bullets)))]
    (is (seq bullets))
    (is (every? #(<= (count %) 122) bullets) "so that the file reads in an editor")
    (is (= (count nginx/differences) (count (filter #(str/starts-with? % "# - ") bullets))) "each begins with a dash")
    (doseq [difference nginx/differences]
      (is (str/includes? said (words difference)) difference))))

(deftest the-variables-of-the-addresses-are-ignored-whatever-the-environment-says-even-with-others-set
  (binding [platform/*environment* {"API_BASE_URL" "https://api.example" "KEYCLOAK_ISSUER" "https://sso.example/realms/x"
                                    "SPA_BASE_URL" "https://app.example" "LOG_LEVEL" "debug"}]
    (is (= settings (nginx/default-settings)))))

(deftest write-puts-the-text-where-the-image-takes-it-from
  (let [dir (.toFile (Files/createTempDirectory "nginx-write-" (make-array FileAttribute 0)))
        target (io/file dir "infra" "spa" "nginx.conf")]
    (with-redefs [nginx/path (str target)]
      (let [out (with-out-str (nginx/write! settings))]
        (is (= (str "Wrote " target) (str/trim out)))
        (is (= (nginx/generate settings) (slurp target :encoding "UTF-8")))))))

(deftest an-argument-that-is-not-one-says-what-the-arguments-are
  (is (= (str "I do not know what to do with \"nope\". The arguments are `check`, `--api-base-url address`, "
              "`--issuer address` and `--base-url address`.")
         (message-of #(nginx/parse-args ["nope"]))))
  (is (= (str "I do not know what to do with \"--issuer\". The arguments are `check`, `--api-base-url address`, "
              "`--issuer address` and `--base-url address`.")
         (message-of #(nginx/parse-args ["--issuer"])))
      "an option with no value"))
