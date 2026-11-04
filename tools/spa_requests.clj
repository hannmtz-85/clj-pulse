(ns spa-requests
  "The requests that the two servers of the workspace's release must answer alike (ADR-0030, point 4): the Ring server that
  `bb spa:serve` runs, and the nginx of the image. A row is a request and what is expected of the answer; `problems` says what
  an answer lacks. The unit tests hold the Ring server to the table, and `bb smoke` holds the container to it, so what the two
  servers agree on is written once.

  Nothing here uses what only the JVM or only Babashka has, so both load it. The paths are the request targets as a browser writes
  them, with the percent-encoding in them (an address with a space in it is not an address). `{script}` is the name of the script of
  the release, which has the hash of its content in it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def page-type "text/html; charset=utf-8")
(def problem-type "application/problem+json")
(def immutable "public, max-age=31536000, immutable")

(def titles
  "The title of the problem for each status the table expects."
  {404 "Not Found" 405 "Method Not Allowed"})

(defn- page [path] {:method :get :path path :status 200 :type page-type :cache "no-cache" :body :page})
(defn- file [path type] {:method :get :path path :status 200 :type type :cache "no-cache"})
(defn- missing [path] {:method :get :path path :status 404 :type problem-type :cache "no-store" :body :problem})
(defn- refused [method] {:method method :path "/" :status 405 :type problem-type :cache "no-store" :allow "GET, HEAD" :body :problem})

(def table
  "The rows, in the order of the cases of `pulse.spa.files-test`."
  (vec
   (concat
    ;; every path that is not a file is the page, because the routes of the workspace are paths
    (map page ["/" "/patients/pt-ana" "/patients/pt-ana/" "/alerts" "/alerts/" "/appointments/ap-1/confirm"
               "/js" "/fonts" "/a/b/c/d" "/signed-out" "/signed-out/" "/index.html"])
    ;; and a file is served as what it is
    [(file "/pulse.css" "text/css; charset=utf-8")
     (file "/favicon.svg" "image/svg+xml")
     (file "/fonts/inter-latin-wght-normal.woff2" "font/woff2")
     (file "/fonts/OFL.txt" "text/plain; charset=utf-8")
     (assoc (file "/js/{script}" "text/javascript; charset=utf-8") :cache immutable)]
    ;; a name with an extension is a file or it is not there: never the page
    (map missing ["/missing.js" "/js/missing.js" "/missing.css" "/fonts/missing.woff2" "/patients/pt-ana.html"
                  "/patients/pt-ana.json" "/favicon.ico"])
    ;; a file is not a route, and a route ends in one slash at most
    (map missing ["/pulse.css/" "/js/{script}/" "/patients//" "/patients///" "//"])
    ;; a kind of file the workspace does not have is not served, even when it is in the release
    (map missing ["/js/manifest.edn"])
    ;; only a plain name is a name
    (map missing ["/.hidden.html" "/js/.hidden.html" "/-x" "/a%20b" "/caf%C3%A9" "/a%3Ab"])
    ;; only reading is allowed
    (map refused [:post :put :patch :delete :options])
    [{:method :head :path "/" :status 200 :type page-type :cache "no-cache"}
     {:method :head :path "/pulse.css" :status 200 :type "text/css; charset=utf-8" :cache "no-cache"}
     {:method :head :path "/missing.js" :status 404 :type problem-type :cache "no-store"}]
    ;; and the process says it is serving
    [{:method :get :path "/healthz" :status 200 :type "application/json" :cache "no-store" :body :healthz}])))

(defn requests
  "The rows with the name of the script of the release (`script`, such as `main.FC753F2B0EC2BAC3C2E49D6A5404976A.js`) in place of
  `{script}`."
  [script]
  (mapv #(update % :path str/replace "{script}" script) table))

(defn- lower-case-headers
  "The headers with their names in lower case and one string for each value: a client of HTTP may give them in any case, and a
  header sent twice as a list."
  [headers]
  (into {} (map (fn [[k v]] [(str/lower-case (name k)) (if (sequential? v) (str/join ", " v) (str v))])) headers))

(defn problems
  "What the `response` (`{:status :headers :body}`) lacks of what the `row` expects of it, as a vector of sentences; empty when
  it is what was expected. `context` has `:headers`, the headers that every answer must carry (a map from name to value: the
  security headers of the workspace) and `:page`, the text of the page (`index.html`), for the rows whose body is the page."
  [row response context]
  (let [{:keys [status type cache allow body method]} row
        headers (lower-case-headers (:headers response))
        text (when (string? (:body response)) (:body response))]
    (cond-> []
      (not= status (:status response))
      (conj (str "the status is " (:status response) " and should be " status))

      (not= type (get headers "content-type"))
      (conj (str "the Content-Type is " (pr-str (get headers "content-type")) " and should be " (pr-str type)))

      (not= cache (get headers "cache-control"))
      (conj (str "the Cache-Control is " (pr-str (get headers "cache-control")) " and should be " (pr-str cache)))

      (not= allow (get headers "allow"))
      (conj (str "the Allow is " (pr-str (get headers "allow")) " and should be " (pr-str allow)))

      (str/blank? (get headers "x-request-id"))
      (conj "there is no X-Request-Id")

      true
      (into (for [[header-name value] (sort-by key (:headers context))
                  :when (not= value (get headers (str/lower-case header-name)))]
              (str "the " header-name " is " (pr-str (get headers (str/lower-case header-name))) " and should be " (pr-str value))))

      (and (= :page body) (not= :head method) (not= (:page context) text))
      (conj "the body is not the page")

      (and (= :healthz body) (not= :head method) (not= {"status" "up"} (try (json/read-str (str text)) (catch Exception _ nil))))
      (conj (str "the body is " (pr-str text) " and should be {\"status\":\"up\"}"))

      (and (= :problem body) (not= :head method))
      (into (let [parsed (try (json/read-str (str text)) (catch Exception _ nil))]
              (cond-> []
                (not (map? parsed)) (conj (str "the body is not a problem: " (pr-str text)))
                (and (map? parsed) (not= status (get parsed "status"))) (conj (str "the status of the problem is " (pr-str (get parsed "status"))))
                (and (map? parsed) (not= "about:blank" (get parsed "type"))) (conj (str "the type of the problem is " (pr-str (get parsed "type"))))
                (and (map? parsed) (not= (get titles status) (get parsed "title"))) (conj (str "the title of the problem is " (pr-str (get parsed "title"))))))))))
