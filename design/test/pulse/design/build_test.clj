(ns pulse.design.build-test
  "What the build needs that is committed: the font files and their license, and the way a page is written."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [design]
            [pulse.design.build :as build]
            [pulse.design.pages :as pages]))

(defn- magic
  "The first four bytes of a file, as text."
  [file]
  (with-open [in (io/input-stream file)]
    (let [bytes (byte-array 4)]
      (.read in bytes)
      (String. bytes "ISO-8859-1"))))

(deftest the-font-files-the-tokens-name-are-in-the-repository-and-are-woff2
  (let [tokens (design/read-tokens)
        files (build/font-files tokens)]
    (is (= 2 (count files)))
    (doseq [file files
            :let [f (io/file build/fonts-dir file)]]
      (is (.exists f) file)
      (is (< 10000 (.length f)) (str file " is not empty"))
      (is (= "wOF2" (magic f)) (str file " is a WOFF2 file")))))

(deftest the-license-of-the-font-comes-with-it
  (let [text (slurp (io/file build/fonts-dir "OFL.txt"))]
    (is (str/includes? text "SIL OPEN FONT LICENSE Version 1.1"))
    (is (str/includes? text "The Inter Project Authors"))))

(deftest the-css-of-the-theme-asks-for-the-files-that-are-there
  (let [css (slurp design/theme-file)
        urls (map second (re-seq #"url\(\"fonts/([^\"]+)\"\)" css))]
    (is (= (set (build/font-files (design/read-tokens))) (set urls)))
    (is (every? #(.exists (io/file build/fonts-dir %)) urls))))

(deftest a-page-is-written-after-the-doctype-in-html-and-ends-with-a-line
  (let [text (build/render [:html {:lang "en"} [:head [:title "T"]] [:body [:input {:disabled true :type "text"}] [:br] [:p "a & b < c"]]])]
    (is (= "<!DOCTYPE html>\n<html lang=\"en\"><head><title>T</title></head><body><input disabled type=\"text\"><br><p>a &amp; b &lt; c</p></body></html>\n"
           text))))

(deftest the-classes-of-a-page-are-the-words-of-its-class-attributes
  (is (= ["a" "b" "c" "d"]
         (seq (build/class-tokens "<div class=\"b  a\"><p class=\"c\"></p><span class=\"\"></span><i class=\"  d a \"></i></div>"))))
  (is (empty? (build/class-tokens "<p>no classes here, class is not an attribute</p>"))))

(deftest a-class-the-stylesheet-has-no-rule-for-is-found
  (let [css (str ".btn{} .btn-primary{} .hover\\:bg-base-200:hover{} .w-2\\/3{} .py-0\\.5{} "
                 ".grid-cols-\\[max-content_1fr\\]{} .md\\:grid-cols-2{}")
        html (str "<div class=\"btn btn-primary hover:bg-base-200 w-2/3 py-0.5 grid-cols-[max-content_1fr] md:grid-cols-2\">"
                  "<p class=\"btn-secondary typo\"></p></div>")]
    (is (= ["btn-secondary" "typo"] (seq (build/unknown-classes html css))))
    (testing "a class that is the start of another is not found by it"
      (is (= ["btn"] (seq (build/unknown-classes "<b class=\"btn\"></b>" ".btn-primary{}"))))
      (is (= ["w-2"] (seq (build/unknown-classes "<b class=\"w-2\"></b>" ".w-2\\/3{}")))))
    (testing "nor by a class that ends the same way"
      (is (= ["primary"] (seq (build/unknown-classes "<b class=\"primary\"></b>" ".btn-primary{}")))))
    (is (empty? (build/unknown-classes "<b></b>" "")))))

(deftest the-context-has-what-every-page-is-given
  (let [context (build/context)]
    (is (= #{:tokens :pages :themes} (set (keys context))))
    (is (= [["pulse" "Teal, light"] ["pulse-dark" "Teal, dark"]] (:themes context)))
    (is (= pages/pages (:pages context)))
    (is (= 28 (count (:pages context))))))

(deftest a-page-is-one-document-and-a-mockup-is-two
  (let [documents (build/documents (build/context))
        files (map :file documents)]
    (is (= (+ 28 22) (count documents)))
    (is (= ["palette.html" "type.html"] (take 2 (rest files))) "in the order of the registry")
    (testing "the page of the review comes first and then its frame, in a folder of its own"
      (is (= ["login.html" "frames/login.html" "login-states.html" "frames/login-states.html"]
             (take 4 (drop 6 files)))))
    (is (every? vector? (map :hiccup documents)))
    (is (= [:html] (distinct (map (comp first :hiccup) documents))))))
