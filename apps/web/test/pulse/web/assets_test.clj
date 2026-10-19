(ns pulse.web.assets-test
  "The files the portal serves itself (ADR-0028): the stylesheet, the font, the icon and htmx. Each is served as what it
  is, kept only if it has not changed, and nothing outside the directory of the files is ever served, however the address
  is written."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.web.assets :as assets]
            [pulse.web.test.harness :as h :refer [call header with-web]])
  (:import (java.io File)))

(def ^:private ^File public-dir (io/file "apps/web/resources/public"))

(defn- published
  "The names of the files in the public directory, such as `fonts/OFL.txt`."
  []
  (let [root (.toPath public-dir)]
    (->> (file-seq public-dir)
         (filter #(.isFile ^File %))
         (map #(str/replace (str (.relativize root (.toPath ^File %))) "\\" "/"))
         sort)))

(defn- same-bytes? [^bytes a ^bytes b] (java.util.Arrays/equals a b))

(deftest the-portal-brings-the-files-its-pages-ask-for
  (is (= #{"pulse.css" "htmx.min.js" "favicon.svg" "fonts/inter-latin-wght-normal.woff2"
           "fonts/inter-latin-ext-wght-normal.woff2" "fonts/OFL.txt"}
         (set (published)))
      "the stylesheet, htmx, the icon, the font and the license of the font: nothing from another server"))

(deftest every-file-is-served-as-what-it-is
  (with-web {}
    (fn [web]
      (doseq [[name type] {"pulse.css" "text/css" "htmx.min.js" "text/javascript" "favicon.svg" "image/svg+xml"
                           "fonts/inter-latin-wght-normal.woff2" "font/woff2"
                           "fonts/inter-latin-ext-wght-normal.woff2" "font/woff2" "fonts/OFL.txt" "text/plain"}
              :let [r (call web :get (str "/assets/" name))]]
        (testing name
          (is (= 200 (:status r)))
          (is (= type (h/content-type r)))
          (is (same-bytes? (java.nio.file.Files/readAllBytes (.toPath (io/file public-dir name))) (:bytes r))
              "the bytes of the file")
          (is (= "no-cache" (header r "Cache-Control")) "kept, but asked about each time")
          (is (some? (header r "Last-Modified"))))))))

(deftest the-text-files-say-they-are-utf-8
  (with-web {}
    (fn [web]
      (doseq [name ["pulse.css" "htmx.min.js" "fonts/OFL.txt"]]
        (is (str/includes? (str/lower-case (header (call web :get (str "/assets/" name)) "Content-Type")) "charset=utf-8") name)))))

(deftest a-file-that-has-not-changed-is-not-sent-again
  (with-web {}
    (fn [web]
      (let [first-time (call web :get "/assets/pulse.css")
            modified (header first-time "Last-Modified")
            again (call web :get "/assets/pulse.css" :headers {"if-modified-since" modified})]
        (is (= 200 (:status first-time)))
        (is (= 304 (:status again)))
        (is (= "" (:text again)) "no body")
        (testing "and one that is older than the copy the browser has"
          (is (= 304 (:status (call web :get "/assets/pulse.css" :headers {"if-modified-since" "Wed, 01 Jan 2100 00:00:00 GMT"})))))
        (testing "while one the browser has an old copy of is sent whole"
          (let [r (call web :get "/assets/pulse.css" :headers {"if-modified-since" "Thu, 01 Jan 1970 00:00:00 GMT"})]
            (is (= 200 (:status r)))
            (is (pos? (alength ^bytes (:bytes r))))))))))

(deftest a-file-is-for-reading-only
  (with-web {}
    (fn [web]
      (testing "a HEAD is answered as a GET is"
        (let [r (call web :head "/assets/pulse.css")]
          (is (= 200 (:status r)))
          (is (= "text/css" (h/content-type r)))))
      (testing "anything else is a method that is not allowed"
        (doseq [method [:post :put :delete :patch]]
          (let [r (call web method "/assets/pulse.css")]
            (is (= 405 (:status r)) (str method))
            (is (= "GET, HEAD" (header r "Allow")) (str method))))))))

(deftest there-is-no-file-that-is-not-there
  (with-web {}
    (fn [web]
      (doseq [path ["/assets/" "/assets/nothing.css" "/assets/fonts" "/assets/fonts/" "/assets/fonts/none.woff2"
                    "/assets/pulse.css.map" "/assets/htmx.min.js.gz" "/assets/package.json"]]
        (let [r (call web :get path)]
          (is (= 404 (:status r)) path)
          (is (= "text/html" (h/content-type r)) "the page that says so")
          (is (str/includes? (:text r) "We cannot find that page")))))))

(deftest a-kind-of-file-the-portal-does-not-serve-is-not-served-even-when-it-is-there
  (with-web {}
    (fn [web]
      (is (= 200 (:status (call web :get "/assets/fonts/OFL.txt"))))
      (with-redefs [assets/types (dissoc assets/types "txt")]
        (is (= 404 (:status (call web :get "/assets/fonts/OFL.txt"))))))))

(deftest an-address-that-climbs-out-of-the-directory-finds-nothing
  (with-web {}
    (fn [web]
      (doseq [path ["/assets/../deps.edn" "/assets/../../deps.edn" "/assets/%2e%2e/deps.edn" "/assets/%2e%2e%2fdeps.edn"
                    "/assets/..%2fdeps.edn" "/assets/fonts/../pulse.css" "/assets/fonts/%2e%2e/pulse.css"
                    "/assets/fonts/..%2f..%2fdeps.edn" "/assets/%252e%252e/deps.edn" "/assets/..\\deps.edn"
                    "/assets/fonts\\..\\..\\deps.edn" "/assets/%00" "/assets/pulse.css%00.png" "/assets/./pulse.css"
                    "/assets//pulse.css" "/assets/.git/config" "/assets/.hidden.css" "/assets/pulse.css/"]]
        (let [r (call web :get path)]
          (is (= 404 (:status r)) path)
          (is (not (str/includes? (:text r) ":paths")) (str path " did not give deps.edn"))
          (is (not (str/includes? (:text r) "tailwind")) (str path " did not give the stylesheet")))))))

;; ---------------------------------------------------------------------------
;; The names

(deftest a-plain-name-is-made-of-plain-segments
  (doseq [good ["pulse.css" "htmx.min.js" "fonts/inter-latin-wght-normal.woff2" "a/b/c.txt" "_x" "A-b_c.d"]]
    (is (assets/plain-name? good) good))
  (doseq [bad [nil "" "/" "/pulse.css" "pulse.css/" "a//b" "." ".." "../x" "x/.." "x/../y" "./x" ".hidden" "a/.hidden"
               "a b" "a\\b" "a%2fb" "a%00" "a\u0000b" "a:b" "a?b" "a#b" "a;b" "*" "~x" 42 :css ["pulse.css"]]]
    (is (not (assets/plain-name? bad)) (pr-str bad))))

(defspec nothing-outside-the-public-directory-is-ever-served 1000
  (let [root (.getCanonicalPath public-dir)
        piece (gen/one-of [(gen/elements ["." ".." "..." "fonts" "pulse.css" "htmx.min.js" "OFL.txt" "src" "pulse" "web"
                                          "deps.edn" "config.edn" "%2e%2e" "%2f" "\\" "" "~" "$HOME" "C:" "etc" "passwd"])
                           gen/string-alphanumeric
                           gen/string-ascii])]
    (prop/for-all [pieces (gen/vector piece 1 5)]
      (let [name (str/join "/" pieces)
            response (assets/serve name)
            body (:body response)]
        (or (nil? response)
            (and (instance? File body)
                 (str/starts-with? (.getCanonicalPath ^File body) root)))))))
