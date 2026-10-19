(ns pulse.design.web-test
  "The static files of the patient portal that the design system makes (ADR-0028): that what is committed is what the
  generator makes, that htmx is the version `design/package.json` pins and comes with its license, that the stylesheet has
  the colours of the tokens, the rules htmx would have written itself and the font of the design, and nothing from
  another server. The classes the pages use are checked against it in the tests of the portal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [design]
            [pulse.design.keycloak :as kc]
            [pulse.design.web :as web])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- committed ^java.io.File [path] (io/file web/public-dir path))

(def ^:private ^java.io.File stylesheet (committed "pulse.css"))

(defn- temporary-directory []
  (str (Files/createTempDirectory "pulse-web" (into-array FileAttribute []))))

(defn- fake-htmx-package
  "A directory with the shape of the npm package of htmx, whose file says it is `version`."
  [version]
  (let [dir (temporary-directory)
        file (io/file dir "dist" "htmx.min.js")]
    (io/make-parents file)
    (spit file (str "var htmx=function(){return{version:\"" version "\"}}()"))
    dir))

;; ---------------------------------------------------------------------------
;; What is committed is what the generator makes

(deftest the-committed-files-are-what-the-generator-makes
  (is (= [] (web/check))))

(deftest a-file-that-differs-from-what-the-generator-makes-is-found
  (let [made web/files]
    (testing "a text file that has changed, or is missing"
      (with-redefs [web/files (fn [] (assoc (made) "favicon.svg" "changed by hand"))]
        (is (= ["favicon.svg"] (web/check))))
      (with-redefs [web/files (fn [] (assoc (made) "forgotten.svg" "x"))]
        (is (= ["forgotten.svg"] (web/check))))))
  (testing "a font, or its license, that is not the one of the design"
    (let [elsewhere (temporary-directory)
          names (concat (kc/font-files) [kc/font-license])]
      (doseq [file names]
        (spit (io/file elsewhere file) "not a font"))
      (with-redefs [web/fonts-dir elsewhere]
        (is (= (sort (map #(str "fonts/" %) names)) (web/check))))))
  (testing "a font that is not there"
    (with-redefs [kc/font-files (fn [] ["inter-latin-wght-normal.woff2" "inter-none.woff2"])]
      (is (= ["fonts/inter-none.woff2"] (web/check)))))
  (testing "htmx of another version than the one that is pinned, or none"
    (with-redefs [web/htmx-version (fn [] "9.9.9")]
      (is (= ["htmx.min.js"] (web/check))))
    (with-redefs [web/htmx-version (fn [] nil)]
      (is (= ["htmx.min.js"] (web/check))))
    (with-redefs [web/htmx-file "htmx-none.min.js"]
      (is (= ["htmx-none.min.js"] (web/check)))))
  (testing "a stylesheet that was never compiled"
    (with-redefs [web/stylesheet "none.css"]
      (is (= ["none.css"] (web/check)))))
  (testing "a stylesheet that is empty: a compiler that failed after it had opened the file"
    (let [directory (temporary-directory)]
      (with-redefs [web/public-dir directory
                    web/htmx-package (fake-htmx-package (web/htmx-version))]
        (binding [*out* (java.io.StringWriter.)] (web/write!))
        (spit (io/file directory web/stylesheet) "")
        (is (= [web/stylesheet] (web/check)))))))

(deftest writing-makes-what-checking-wants
  (let [directory (temporary-directory)
        out (java.io.StringWriter.)
        version (web/htmx-version)]
    (with-redefs [web/public-dir directory
                  web/htmx-package (fake-htmx-package version)]
      (is (= (sort (concat (keys (web/files))
                           (keys (web/copied-files))
                           [web/htmx-file web/stylesheet]))
             (web/check))
          "an empty directory lacks all of it")
      (binding [*out* out] (web/write!))
      (testing "it says what it wrote and what it copied"
        (doseq [path (keys (web/files))]
          (is (str/includes? (str out) (str "Wrote " (io/file directory path))) path))
        (doseq [path (conj (keys (web/copied-files)) web/htmx-file)]
          (is (str/includes? (str out) (str "Copied " (io/file directory path))) path)))
      (testing "what is left is the stylesheet, which Tailwind compiles"
        (is (= [web/stylesheet] (web/check)))
        (spit (io/file directory web/stylesheet) "a{color:red}")
        (is (= [] (web/check))))
      (spit (io/file directory "favicon.svg") "edited")
      (is (= ["favicon.svg"] (web/check)))
      (binding [*out* out] (web/write!))
      (is (= [] (web/check)) "and writing again puts it back"))))

(deftest htmx-is-copied-only-from-the-version-that-is-pinned
  (let [version (web/htmx-version)]
    (testing "a package of another version"
      (with-redefs [web/public-dir (temporary-directory)
                    web/htmx-package (fake-htmx-package "1.0.0")]
        (let [e (try (binding [*out* (java.io.StringWriter.)] (web/write!)) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) (str "is not htmx " version)))
          (is (str/includes? (ex-message e) "npm ci")))))
    (testing "no package at all"
      (with-redefs [web/public-dir (temporary-directory)
                    web/htmx-package (temporary-directory)]
        (let [e (try (binding [*out* (java.io.StringWriter.)] (web/write!)) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) "No such file in the package"))
          (is (str/includes? (ex-message e) "npm ci") "and what to do about it"))))
    (testing "no version pinned"
      (with-redefs [web/public-dir (temporary-directory)
                    web/htmx-version (fn [] nil)]
        (let [e (try (binding [*out* (java.io.StringWriter.)] (web/write!)) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (str/includes? (ex-message e) "exact version")))))))

(deftest the-public-directory-holds-only-what-the-generator-and-the-compiler-make
  (let [root (io/file web/public-dir)
        files (->> (file-seq root)
                   (filter #(.isFile ^java.io.File %))
                   (map #(str/replace (str (.relativize (.toPath root) (.toPath ^java.io.File %))) "\\" "/"))
                   set)
        made (into #{web/htmx-file web/stylesheet} (concat (keys (web/files)) (keys (web/copied-files))))]
    (is (= made files) "no file that nothing makes, such as a stylesheet of an earlier design")))

(deftest the-text-the-generator-makes-has-the-line-endings-and-the-encoding-the-repository-wants
  (doseq [[path text] (web/files)]
    (testing path
      (is (not (str/includes? text "\r")))
      (is (str/ends-with? text "\n") "ends its last line"))))

;; ---------------------------------------------------------------------------
;; htmx

(deftest htmx-is-pinned-to-an-exact-version
  (let [package (slurp web/package-json :encoding "UTF-8")]
    (is (re-matches #"\d+\.\d+\.\d+" (web/htmx-version)))
    (is (re-find #"\"htmx\.org\"\s*:\s*\"\d+\.\d+\.\d+\"" package) "not a range: ^ and ~ would copy a file that was never reviewed")
    (is (= "2" (first (str/split (web/htmx-version) #"\."))) "the line the owner approved: htmx 2")))

(deftest the-committed-htmx-is-that-version-and-comes-with-its-license
  (let [js (slurp (committed web/htmx-file) :encoding "UTF-8")
        notice (slurp "NOTICE" :encoding "UTF-8")]
    (is (str/includes? js (str "version:\"" (web/htmx-version) "\"")))
    (is (> (count js) 40000) "the library, minified, and not a stub")
    (testing "the notice of third parties names it, with the license it is under"
      (is (str/includes? notice "htmx"))
      (is (str/includes? notice "Zero-Clause BSD")))))

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
        "every colour of the theme of the design, as the compiler writes it: run `bb design:web` when a token changes")))

(deftest the-stylesheet-brings-the-font-of-the-design-and-nothing-from-outside
  (let [css (slurp stylesheet :encoding "UTF-8")
        fonts (set (re-seq #"(?<=url\(fonts/)[^)]+(?=\))" css))]
    (testing "the files it asks for are the ones the tokens name, and they are beside it"
      (is (= (set (kc/font-files)) fonts))
      (is (every? #(.exists (committed (str "fonts/" %))) fonts)))
    (testing "the font loads from the portal and swaps in when it has loaded"
      (is (str/includes? css "font-family:Inter Variable"))
      (is (str/includes? css "font-display:swap")))
    (testing "nothing is asked of another server"
      (is (empty? (re-seq #"url\(\s*[\"']?https?:" css)))
      (is (empty? (re-seq #"@import" css))))))

(deftest the-stylesheet-has-the-rules-htmx-would-have-injected-as-a-style-element
  ;; the policy of the pages allows no inline style, and htmx is configured not to write one (ADR-0028)
  (let [css (slurp stylesheet :encoding "UTF-8")]
    (is (str/includes? css ".htmx-indicator{opacity:0}"))
    (is (re-find #"\.htmx-request \.htmx-indicator,\.htmx-request\.htmx-indicator\{[^}]*opacity:1" css))))

(deftest the-stylesheet-is-the-one-of-the-interfaces
  (let [css (slurp stylesheet :encoding "UTF-8")]
    (testing "the themes of the design are in it"
      (is (str/includes? css "pulse-dark")))
    (testing "and the components of the portal"
      (doseq [class [".btn" ".card" ".dock" ".input" ".badge" ".menu"]]
        (is (str/includes? css class) class)))))

;; ---------------------------------------------------------------------------
;; The icon and the font

(deftest the-icon-is-the-mark-of-the-login-theme
  (is (= (kc/favicon-svg) (slurp (committed "favicon.svg") :encoding "UTF-8"))))

(deftest the-font-goes-with-its-license
  (is (.exists (committed "fonts/OFL.txt")))
  (is (str/includes? (slurp (committed "fonts/OFL.txt") :encoding "UTF-8") "SIL OPEN FONT LICENSE Version 1.1")
      "the license that the font was given under"))
