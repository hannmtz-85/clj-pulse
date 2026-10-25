(ns pulse.design.spa-test
  "The static files of the clinical workspace that the design system makes (ADR-0029): that what is committed is what the generator
  makes, that the page loads its script and nothing else, that the stylesheet has the colours of the tokens and nothing from another
  server, and that the release is put together from the build and these files. The classes the pages use are checked against the
  stylesheet in the tests of the workspace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.keycloak :as kc]
            [pulse.design.spa :as spa]
            [pulse.ui.test.html :as html]
            [pulse.ui.test.hiccup :as hx])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- committed ^java.io.File [path] (io/file spa/public-dir path))

(def ^:private ^java.io.File stylesheet (committed "pulse.css"))

(defn- temporary-directory []
  (str (Files/createTempDirectory "pulse-spa" (into-array FileAttribute []))))

(defn- quietly [f] (binding [*out* (java.io.StringWriter.)] (f)))

;; ---------------------------------------------------------------------------
;; What is committed is what the generator makes

(deftest the-committed-files-are-what-the-generator-makes
  (is (= [] (spa/check))))

(deftest a-file-that-differs-from-what-the-generator-makes-is-found
  (let [made spa/files]
    (testing "a text file that has changed, or is missing"
      (with-redefs [spa/files (fn [] (assoc (made) "index.html" "changed by hand"))]
        (is (= ["index.html"] (spa/check))))
      (with-redefs [spa/files (fn [] (assoc (made) "forgotten.svg" "x"))]
        (is (= ["forgotten.svg"] (spa/check))))))
  (testing "a font, or its license, that is not the one of the design"
    (let [elsewhere (temporary-directory)
          names (concat (kc/font-files) [kc/font-license])]
      (doseq [file names]
        (spit (io/file elsewhere file) "not a font"))
      (with-redefs [spa/fonts-dir elsewhere]
        (is (= (sort (map #(str "fonts/" %) names)) (spa/check))))))
  (testing "a stylesheet that was never compiled"
    (with-redefs [spa/stylesheet "none.css"]
      (is (= ["index.html" "none.css"] (spa/check)) "and the page, which asks for it by that name")))
  (testing "a stylesheet that is empty: a compiler that failed after it had opened the file"
    (let [directory (temporary-directory)]
      (with-redefs [spa/public-dir directory]
        (quietly spa/write!)
        (spit (io/file directory spa/stylesheet) "")
        (is (= [spa/stylesheet] (spa/check)))))))

(deftest writing-makes-what-checking-wants
  (let [directory (temporary-directory)
        out (java.io.StringWriter.)]
    (with-redefs [spa/public-dir directory]
      (is (= (sort (concat (keys (spa/files)) (keys (spa/copied-files)) [spa/stylesheet])) (spa/check))
          "an empty directory lacks all of it")
      (binding [*out* out] (spa/write!))
      (testing "it says what it wrote and what it copied"
        (doseq [path (keys (spa/files))]
          (is (str/includes? (str out) (str "Wrote " (io/file directory path))) path))
        (doseq [path (keys (spa/copied-files))]
          (is (str/includes? (str out) (str "Copied " (io/file directory path))) path)))
      (testing "what is left is the stylesheet, which Tailwind compiles"
        (is (= [spa/stylesheet] (spa/check)))
        (spit (io/file directory spa/stylesheet) "a{color:red}")
        (is (= [] (spa/check))))
      (spit (io/file directory "favicon.svg") "edited")
      (is (= ["favicon.svg"] (spa/check)))
      (quietly spa/write!)
      (is (= [] (spa/check)) "and writing again puts it back"))))

(deftest the-public-directory-holds-only-what-the-generator-and-the-compiler-make
  (let [root (io/file spa/public-dir)
        files (->> (file-seq root)
                   (filter #(.isFile ^java.io.File %))
                   (map #(str/replace (str (.relativize (.toPath root) (.toPath ^java.io.File %))) "\\" "/"))
                   set)
        made (into #{spa/stylesheet} (concat (keys (spa/files)) (keys (spa/copied-files))))]
    (is (= made files) "no file that nothing makes: the script of the build is not here, it goes to target/")))

(deftest the-text-the-generator-makes-has-the-line-endings-and-the-encoding-the-repository-wants
  (doseq [[path text] (spa/files)]
    (testing path
      (is (not (str/includes? text "\r")))
      (is (str/ends-with? text "\n") "ends its last line"))))

;; ---------------------------------------------------------------------------
;; The page

(deftest the-page-loads-its-script-the-stylesheet-and-the-icon-and-draws-in-one-place
  (let [page (html/document (spa/index-html "/js/main.0123ABCD.js"))]
    (is (str/starts-with? (spa/index-html "/x.js") "<!DOCTYPE html>\n"))
    (testing "the script, deferred, and no other"
      (is (= [{:src "/js/main.0123ABCD.js" :defer true}] (map hx/attributes (hx/by-tag page :script)))))
    (testing "the stylesheet and the icon are the files of the workspace"
      (is (= [{:rel "stylesheet" :href "/pulse.css"} {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
             (map hx/attributes (hx/by-tag page :link)))))
    (testing "a place for the application, and an explanation for a browser that cannot run it"
      (is (= 1 (count (hx/select page #(= "app" (:id (hx/attributes %)))))))
      (is (str/includes? (hx/words (first (hx/by-tag page :noscript))) "needs JavaScript")))
    (testing "a language, a title and a viewport"
      (is (= "en" (:lang (hx/attributes (first (hx/by-tag page :html))))))
      (is (= ["Pulse"] (map hx/words (hx/by-tag page :title))))
      (is (some #(= "width=device-width, initial-scale=1" (:content (hx/attributes %))) (hx/by-tag page :meta))))))

(deftest the-page-has-no-inline-script-or-style-so-that-the-policy-can-forbid-them
  (let [text (spa/index-html spa/dev-script)]
    (is (empty? (filter #(nil? (:src (hx/attributes %))) (hx/by-tag (html/document text) :script))) "every script has a source")
    (is (empty? (hx/by-tag (html/document text) :style)))
    (is (not (re-find #"\sstyle=" text)))
    (is (not (re-find #"\son[a-z]+=" text)) "no handler written in an attribute")))

(deftest the-development-page-loads-the-script-as-shadow-cljs-writes-it
  (is (= "/js/main.js" spa/dev-script))
  (is (str/includes? (slurp (committed "index.html") :encoding "UTF-8") "src=\"/js/main.js\"")))

;; ---------------------------------------------------------------------------
;; The stylesheet

(deftest the-stylesheet-has-the-colours-of-the-tokens
  (let [css (slurp stylesheet :encoding "UTF-8")
        short-hex (fn [hex] (let [hex (str/lower-case hex)]
                              (if-let [[_ a b c] (re-matches #"#(.)\1(.)\2(.)\3" hex)] (str "#" a b c) hex)))
        declarations (re-seq #"(--(?:pulse|color)-[a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\s*;"
                             (slurp "design/theme.css" :encoding "UTF-8"))]
    (is (> (count declarations) 60) "there are colours to look for")
    (is (= [] (for [[_ property hex] declarations
                    :when (not (str/includes? css (str property ":" (short-hex hex))))]
                [property hex]))
        "every colour of the theme of the design, as the compiler writes it: run `bb design:spa` when a token changes")))

(deftest the-stylesheet-brings-the-font-of-the-design-and-nothing-from-outside
  (let [css (slurp stylesheet :encoding "UTF-8")
        fonts (set (re-seq #"(?<=url\(fonts/)[^)]+(?=\))" css))]
    (is (= (set (kc/font-files)) fonts) "the files it asks for are the ones the tokens name")
    (is (every? #(.exists (committed (str "fonts/" %))) fonts) "and they are beside it")
    (is (str/includes? css "font-display:swap"))
    (is (empty? (re-seq #"url\(\s*[\"']?https?:" css)) "nothing is asked of another server")
    (is (empty? (re-seq #"@import" css)))))

(deftest the-stylesheet-is-the-one-of-the-interfaces
  (let [css (slurp stylesheet :encoding "UTF-8")]
    (is (str/includes? css "pulse-dark"))
    (doseq [class [".btn" ".card" ".input" ".badge" ".menu" ".loading"]]
      (is (str/includes? css class) class))))

(deftest the-icon-is-the-mark-of-the-login-theme-and-the-font-goes-with-its-license
  (is (= (kc/favicon-svg) (slurp (committed "favicon.svg") :encoding "UTF-8")))
  (is (str/includes? (slurp (committed "fonts/OFL.txt") :encoding "UTF-8") "SIL OPEN FONT LICENSE Version 1.1")))

;; ---------------------------------------------------------------------------
;; The release

(def ^:private manifest "[{:module-id :main :name :main :output-name \"main.77BB12C92D2AE8EA.js\" :entries [pulse.spa.core]}]")

(defn- fake-build
  "A directory that has what shadow-cljs leaves in it: `js/` with the script and its manifest."
  [manifest-text]
  (let [release (temporary-directory)]
    (spit (doto (io/file release "js" "manifest.edn") io/make-parents) manifest-text)
    (spit (io/file release "js" "main.77BB12C92D2AE8EA.js") "console.log(1)")
    release))

(deftest the-script-is-found-by-the-name-the-build-gave-it
  (is (= "main.77BB12C92D2AE8EA.js" (spa/script-name [{:module-id :main :output-name "main.77BB12C92D2AE8EA.js"}])))
  (is (= "main.AAAA.js" (spa/script-name [{:module-id :other :output-name "other.js"} {:module-id :main :output-name "main.AAAA.js"}])))
  (is (nil? (spa/script-name [{:module-id :other :output-name "other.js"}])))
  (is (nil? (spa/script-name [])))
  (is (nil? (spa/script-name nil))))

(deftest the-release-has-the-page-that-loads-the-script-with-the-hash-in-its-name
  (let [release (fake-build manifest)]
    (with-redefs [spa/release-dir release]
      (let [out (java.io.StringWriter.)
            script (binding [*out* out] (spa/release!))
            page (slurp (io/file release "index.html") :encoding "UTF-8")]
        (is (= "main.77BB12C92D2AE8EA.js" script))
        (testing "it says what it wrote and what it copied"
          (is (str/includes? (str out) (str "Wrote " (io/file release "index.html"))))
          (doseq [path (concat [spa/stylesheet "favicon.svg"] (keys (spa/copied-files)))]
            (is (str/includes? (str out) (str "Copied " (io/file release path))) path)))
        (is (= (spa/index-html "/js/main.77BB12C92D2AE8EA.js") page))
        (testing "and the files of the workspace beside it"
          (doseq [path (concat [spa/stylesheet "favicon.svg"] (keys (spa/copied-files)))]
            (is (.exists (io/file release path)) path)
            (is (= (.length (committed path)) (.length (io/file release path))) path))
          (is (.exists (io/file release "js" "main.77BB12C92D2AE8EA.js"))))))))

(deftest there-is-no-release-from-a-build-that-is-not-there
  (testing "no build at all"
    (with-redefs [spa/release-dir (temporary-directory)]
      (let [e (try (quietly spa/release!) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "No build to put together"))
        (is (str/includes? (ex-message e) "manifest.edn") "which file is not there")
        (is (str/includes? (ex-message e) "Run the release build of shadow-cljs first") "and what to do about it"))))
  (testing "a build with no module that is the application"
    (let [release (fake-build "[{:module-id :other :output-name \"other.js\"}]")]
      (with-redefs [spa/release-dir release]
        (let [e (try (quietly spa/release!) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) "no module :main"))
          (is (not (.exists (io/file release "index.html"))) "and no page that loads nothing"))))))

(deftest cleaning-takes-the-old-release-away-whole
  (let [release (temporary-directory)]
    (spit (doto (io/file release "js" "main.OLDHASH00.js") io/make-parents) "old")
    (spit (io/file release "index.html") "old")
    (with-redefs [spa/release-dir release]
      (spa/clean-release!)
      (is (not (.exists (io/file release)))))
    (testing "and cleaning what is not there is not a failure"
      (with-redefs [spa/release-dir release]
        (is (nil? (spa/clean-release!)))))))
