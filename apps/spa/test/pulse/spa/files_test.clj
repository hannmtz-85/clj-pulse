(ns pulse.spa.files-test
  "The files of the workspace (ADR-0029, point 9): the page for every path that is not a file, the files as what they are, and no way
  out of the directory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.spa.files :as files]
            [pulse.spa.route :as route])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *root* nil)

(defn- write! [^java.io.File root path content]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file content)))

(defn- with-release [f]
  (let [parent (.toFile (Files/createTempDirectory "spa-files-" (make-array FileAttribute 0)))
        root (io/file parent "release")]
    (write! root "index.html" "<!DOCTYPE html><title>Pulse</title>")
    (write! root "js/main.77BB12C92D2AE8EA.js" "console.log(1)")
    (write! root "js/manifest.edn" "[]")
    (write! root "pulse.css" "body{}")
    (write! root "favicon.svg" "<svg/>")
    (write! root "fonts/inter-latin-wght-normal.woff2" "font")
    (write! root "fonts/OFL.txt" "license")
    (write! root ".hidden.html" "hidden")
    (write! parent "secret.txt" "outside the directory")
    (write! parent "secret.html" "outside the directory")
    (binding [*root* (str root)]
      (f))))

(use-fixtures :once with-release)

(defn- get! [uri & [method]]
  ((files/handler *root*) {:request-method (or method :get) :uri uri}))

(defn- body [response]
  (let [b (:body response)] (cond (string? b) b b (slurp b))))

(defn- header [response name]
  (get (:headers response) name))

(deftest the-page-is-the-answer-to-the-root-and-to-every-path-that-is-not-a-file
  (doseq [uri ["/" "/patients/pt-ana" "/patients/pt-ana/" "/alerts" "/appointments/ap-1/confirm" "/js" "/fonts" "/a/b/c/d" "/alerts/"]]
    (let [response (get! uri)]
      (is (= 200 (:status response)) uri)
      (is (= "<!DOCTYPE html><title>Pulse</title>" (body response)) uri)
      (is (= "text/html; charset=utf-8" (header response "Content-Type")) uri)
      (is (= "no-cache" (header response "Cache-Control")) uri))))

(deftest the-page-of-someone-who-signed-out-is-the-page-of-the-workspace
  (doseq [uri [route/signed-out-path (str route/signed-out-path "/")]]
    (let [response (get! uri)]
      (is (= 200 (:status response)) uri)
      (is (= "<!DOCTYPE html><title>Pulse</title>" (body response)) "the workspace answers, and the script there decides it is not to sign anyone in")
      (is (= "no-cache" (header response "Cache-Control")) uri))))

(deftest a-file-is-served-as-what-it-is
  (doseq [[uri type content] [["/pulse.css" "text/css; charset=utf-8" "body{}"]
                              ["/favicon.svg" "image/svg+xml" "<svg/>"]
                              ["/fonts/inter-latin-wght-normal.woff2" "font/woff2" "font"]
                              ["/fonts/OFL.txt" "text/plain; charset=utf-8" "license"]
                              ["/js/main.77BB12C92D2AE8EA.js" "text/javascript; charset=utf-8" "console.log(1)"]
                              ["/index.html" "text/html; charset=utf-8" "<!DOCTYPE html><title>Pulse</title>"]]]
    (let [response (get! uri)]
      (is (= 200 (:status response)) uri)
      (is (= type (header response "Content-Type")) uri)
      (is (= content (body response)) uri))))

(deftest a-name-with-an-extension-is-a-file-or-it-is-not-there
  (testing "never the page"
    (doseq [uri ["/missing.js" "/js/missing.js" "/missing.css" "/fonts/missing.woff2" "/patients/pt-ana.html" "/favicon.ico"]]
      (let [response (get! uri)]
        (is (= 404 (:status response)) uri)
        (is (= "application/problem+json" (header response "Content-Type")) uri))))
  (testing "a file is not asked for as if it were a route, and a route ends in one slash at most"
    (doseq [uri ["/pulse.css/" "/js/main.77BB12C92D2AE8EA.js/" "/patients//" "/patients///" "//"]]
      (is (= 404 (:status (get! uri))) uri)))
  (testing "a kind of file that the workspace does not have is not served, even when it is there"
    (is (= 404 (:status (get! "/js/manifest.edn"))))))

(deftest what-the-browser-keeps-is-what-cannot-change
  (is (= "public, max-age=31536000, immutable" (header (get! "/js/main.77BB12C92D2AE8EA.js") "Cache-Control"))
      "a name with the hash of its content is kept for ever")
  (doseq [uri ["/pulse.css" "/favicon.svg" "/fonts/inter-latin-wght-normal.woff2" "/index.html" "/"]]
    (is (= "no-cache" (header (get! uri) "Cache-Control")) (str uri " must be asked about")))
  (testing "the rule is on the name"
    (is (= "public, max-age=31536000, immutable" (files/cache-control "main.77BB12C92D2AE8EA86AC9E48FF8D222A.js")))
    (is (= "public, max-age=31536000, immutable" (files/cache-control "js/main.0123abcd.js")))
    (is (= "no-cache" (files/cache-control "main.js")) "a name that is the same for every version")
    (is (= "no-cache" (files/cache-control "main.xyz12345.js")) "a hash is hexadecimal")
    (is (= "no-cache" (files/cache-control "main.0123abc.js")) "and at least eight digits")
    (is (= "no-cache" (files/cache-control "pulse.0123abcd.css")) "only a script is made by a build that hashes")))

(deftest only-a-plain-name-gets-out-of-the-address
  (testing "no way out of the directory, written in any way"
    (doseq [uri ["/../secret.txt" "/../secret.html" "/js/../../secret.html" "/%2e%2e/secret.html" "/%2E%2E/secret.html"
                 "/js/%2e%2e/%2e%2e/secret.html" "/..%2Fsecret.html" "/%2e%2e%2fsecret.html" "/js%2F..%2F..%2Fsecret.html"
                 "/..\\secret.html" "/js\\..\\secret.html" "/%5c..%5csecret.html" "//secret.html" "/js//main.77BB12C92D2AE8EA.js"
                 "/.hidden.html" "/js/.hidden.html" "/./index.html" "/index.html%00.js" "/%00" "/%2e/index.html" "/js/./x.js"]]
      (let [response (get! uri)]
        (is (not= "outside the directory" (body response)) uri)
        (is (contains? #{404} (:status response)) (str uri " is not a name of a file"))))
    (is (not= "hidden" (body (get! "/.hidden.html"))) "a file that begins with a dot is there, and is not served"))
  (testing "a bad percent-encoding is a name that is not there, and not a failure"
    (doseq [uri ["/%" "/%zz.js" "/%e0%a4%a.js" "/js/%.js"]]
      (is (= 404 (:status (get! uri))) uri)))
  (testing "plain-name?"
    (is (true? (files/plain-name? "index.html")))
    (is (true? (files/plain-name? "fonts/inter-latin-wght-normal.woff2")))
    (is (true? (files/plain-name? "a_b-c.d/e")))
    (doseq [name ["" "/" "a//b" "a/" "/a" "." ".." "a/../b" "a/./b" ".a" "a/.b" "a b" "a\\b" "a:b" "é.js" nil 7]]
      (is (not (files/plain-name? name)) (pr-str name)))))

(defspec whatever-the-address-is-it-never-gets-out-of-the-directory 400
  (prop/for-all [uri (gen/fmap #(str "/" (str/join "/" %))
                               (gen/vector (gen/one-of [(gen/elements ["." ".." "%2e%2e" "%2E" "secret.html" "secret.txt" "..%2F" "%5c" "js" ""])
                                                        gen/string-alphanumeric])
                                           0 6))]
    (let [response (get! uri)]
      (and (contains? #{200 404} (:status response))
           (not= "outside the directory" (body response))))))

(deftest only-reading-is-allowed
  (doseq [method [:post :put :patch :delete :options]]
    (let [response (get! "/" method)]
      (is (= 405 (:status response)) (str method))
      (is (= "GET, HEAD" (header response "Allow")) (str method))))
  (testing "a HEAD is what a GET is"
    (is (= 200 (:status (get! "/" :head))))
    (is (= 200 (:status (get! "/pulse.css" :head))))
    (is (= 404 (:status (get! "/missing.js" :head))))))

(deftest there-is-a-page-only-when-the-build-made-one
  (let [empty-root (.toFile (Files/createTempDirectory "spa-empty-" (make-array FileAttribute 0)))
        handler (files/handler (str empty-root))]
    (is (= 404 (:status (handler {:request-method :get :uri "/"}))) "no index.html in the directory: the page cannot be found")
    (is (= 404 (:status (handler {:request-method :get :uri "/patients/pt-ana"}))))))
