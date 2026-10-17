(ns pulse.design.keycloak-test
  "The login theme of Keycloak as files (ADR-0026): that what is committed is what the generator makes, what its
  properties and its messages hold, that its stylesheet has a rule for every class the templates use and the colours of
  the tokens, that its fonts and its icon are the design's, and that the realm and the stack use it. The pages it makes
  when Keycloak fills them in are in `keycloak-render-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.xml :as xml]
            [design]
            [jsonista.core :as json]
            [pulse.design.build :as build]
            [pulse.design.keycloak :as kc])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Properties)))

(defn- committed ^java.io.File [path] (io/file kc/theme-dir path))

(defn- properties-of
  "The pairs of a `.properties` file, read as Java reads them (and as Keycloak does: in UTF-8)."
  [file]
  (with-open [reader (io/reader file :encoding "UTF-8")]
    (let [properties (Properties.)]
      (.load properties ^java.io.Reader reader)
      (into {} (map (fn [[k v]] [(str k) (str v)])) properties))))

(def ^:private ^java.io.File stylesheet (committed "resources/pulse.css"))

(defn- templates
  "Every template the generator makes, as `{path text}`."
  []
  (into {} (filter (fn [[path _]] (str/ends-with? path ".ftl"))) (kc/files)))

(defn- temporary-directory []
  (str (Files/createTempDirectory "pulse-theme" (into-array FileAttribute []))))

;; ---------------------------------------------------------------------------
;; What is committed is what the generator makes

(deftest the-committed-theme-is-what-the-generator-makes
  (is (= [] (kc/check))))

(deftest a-file-that-differs-from-what-the-generator-makes-is-found
  (let [made kc/files]
    (testing "one that has changed"
      (with-redefs [kc/files (fn [] (assoc (made) "login.ftl" "changed by hand"))]
        (is (= ["login.ftl"] (kc/check)))))
    (testing "one that is missing"
      (with-redefs [kc/files (fn [] (assoc (made) "forgotten.ftl" "x"))]
        (is (= ["forgotten.ftl"] (kc/check))))))
  (testing "a font, or its license, that is not the one of the design"
    (let [elsewhere (temporary-directory)
          names (concat (kc/font-files) [kc/font-license])]
      (doseq [file names]
        (spit (io/file elsewhere file) "not a font"))
      (with-redefs [kc/fonts-dir elsewhere]
        (is (= (sort (map #(str "resources/fonts/" %) names)) (kc/check))))))
  (testing "a font that is not there"
    (with-redefs [kc/font-files (fn [] ["inter-latin-wght-normal.woff2" "inter-none.woff2"])]
      (is (= ["resources/fonts/inter-none.woff2"] (kc/check)))))
  (testing "a license that is not there"
    (with-redefs [kc/font-license "NO-SUCH-LICENSE.txt"]
      (is (= ["resources/fonts/NO-SUCH-LICENSE.txt"] (kc/check))))))

(deftest the-font-goes-with-its-license
  (is (= "OFL.txt" kc/font-license))
  (is (.exists (committed "resources/fonts/OFL.txt")))
  (is (str/includes? (slurp (committed "resources/fonts/OFL.txt") :encoding "UTF-8") "SIL OPEN FONT LICENSE Version 1.1")
      "the license that the font was given under"))

(deftest writing-the-theme-makes-what-checking-it-wants
  (let [directory (temporary-directory)
        out (java.io.StringWriter.)
        copied (concat (kc/font-files) [kc/font-license])]
    (with-redefs [kc/theme-dir directory]
      (is (= (sort (concat (keys (kc/files)) (map #(str "resources/fonts/" %) copied))) (kc/check))
          "an empty directory lacks all of it")
      (binding [*out* out] (kc/write!))
      (is (= [] (kc/check)))
      (testing "it says what it wrote, and what it copied"
        (doseq [path (keys (kc/files))]
          (is (str/includes? (str out) (str "Wrote " (io/file directory path))) path))
        (doseq [file copied]
          (is (str/includes? (str out) (str "Copied " (io/file directory "resources/fonts" file))) file)))
      (spit (io/file directory "error.ftl") "edited")
      (is (= ["error.ftl"] (kc/check)))
      (binding [*out* out] (kc/write!))
      (is (= [] (kc/check)) "and writing again puts it back"))))

(deftest the-theme-holds-only-what-the-generator-and-the-compiler-make
  (let [root (io/file kc/theme-dir)
        files (->> (file-seq root)
                   (filter #(.isFile ^java.io.File %))
                   (map #(str/replace (str (.relativize (.toPath root) (.toPath ^java.io.File %))) "\\" "/"))
                   set)
        made (into #{"resources/pulse.css"}
                   (concat (keys (kc/files)) (map #(str "resources/fonts/" %) (concat (kc/font-files) [kc/font-license]))))]
    (is (= made files) "no file that nothing makes, such as a stylesheet of an earlier design")))

(deftest the-text-of-the-theme-has-the-line-endings-and-the-encoding-the-repository-wants
  (doseq [[path text] (kc/files)]
    (testing path
      (is (not (str/includes? text "\r")))
      (is (str/ends-with? text "\n") "ends its last line")
      (is (= text (String. (.getBytes ^String text "UTF-8") "UTF-8"))))))

;; ---------------------------------------------------------------------------
;; The properties and the messages

(deftest the-theme-extends-base-and-asks-for-its-stylesheet
  (let [properties (properties-of (committed "theme.properties"))]
    (is (= "base" (properties "parent")) "no stylesheet of Keycloak's: the theme brings its own")
    (is (= "pulse.css" (properties "styles")))
    (is (.exists stylesheet) "and it is there, where the theme looks: resources/")
    (testing "the rest are the classes that the pages of base ask for, which are the classes of the design"
      (is (= kc/class-properties (dissoc properties "parent" "styles"))))
    (testing "nothing else of base's look comes with it"
      (is (not (contains? properties "import")))
      (is (not (contains? properties "scripts"))))))

(deftest the-messages-are-the-ones-the-generator-says
  (let [file (properties-of (committed "messages/messages_en.properties"))]
    (is (= kc/messages file))
    (is (= (count kc/messages) (count file)) "no key twice")))

(deftest a-message-is-text-that-a-message-format-reads-as-written
  (doseq [[k text] kc/messages]
    (testing k
      (is (not (str/includes? text "'")) "an apostrophe is a quotation mark to a message format")
      (is (= text (str/trim text)))
      (is (seq text))
      (is (every? #{"0"} (map second (re-seq #"\{([^}]*)\}" text))) "the only argument there is is the first")
      (is (= text (.format (java.text.MessageFormat. ^String text java.util.Locale/ENGLISH) (object-array ["{0}"])))
          "it comes out as written, with the argument put where {0} is"))))

(deftest what-keycloak-words-is-not-written-again-unless-the-theme-words-it-differently
  (is (empty? (filter (set (keys kc/messages)) (keys kc/base-messages)))
      "a message of Keycloak's that the theme words is in `messages`, and not also in `base-messages`")
  (testing "the messages the theme rewords are Keycloak's own keys: a misspelt one would be a message nobody asks for"
    (is (every? #(re-matches #"[a-zA-Z][a-zA-Z0-9.-]*" %) (concat (keys kc/messages) (keys kc/base-messages))))))

(deftest the-review-reads-the-messages-of-the-theme
  (is (= "Sign in" (kc/message :loginAccountTitle)))
  (is (= "to continue to Pulse web portal" (kc/message :loginSubtitle "Pulse web portal")))
  (is (= "Password" (kc/message "password")) "a key can be a keyword or a string, and Keycloak's own words are there too")
  (testing "an argument is put where its number is"
    (with-redefs [kc/messages (array-map "two" "{1} before {0}, and {0} again")]
      (is (= "b before a, and a again" (kc/message :two "a" "b")))))
  (testing "a message that nobody has written is an error, and says which"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has a message called :noSuchMessage" (kc/message :noSuchMessage)))))

;; ---------------------------------------------------------------------------
;; The stylesheet

(deftest every-class-the-templates-and-the-properties-use-has-a-rule-in-the-stylesheet
  (let [css (slurp stylesheet :encoding "UTF-8")
        markup (apply str (vals (templates)))]
    (is (> (count (build/class-tokens markup)) 40) "the check has classes to check")
    (is (empty? (build/unknown-classes markup css)))
    (is (empty? (build/unknown-classes (str "class=\"" (str/join " " (vals kc/class-properties)) "\"") css)))
    (testing "and the check can fail"
      (is (= #{"no-such-class" "md:no-such-either"}
             (build/unknown-classes "<p class=\"no-such-class btn md:no-such-either\">" css))))))

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
        "every colour of the theme of the design, as the compiler writes it: run `bb design:keycloak` when a token changes")))

(deftest the-stylesheet-brings-the-font-of-the-theme-and-nothing-from-outside
  (let [css (slurp stylesheet :encoding "UTF-8")
        fonts (set (re-seq #"(?<=url\(fonts/)[^)]+(?=\))" css))]
    (testing "the files it asks for are the ones the tokens name, and they are in the theme"
      (is (= (set (kc/font-files)) fonts))
      (is (every? #(.exists (committed (str "resources/fonts/" %))) fonts)))
    (testing "the font loads from the theme and swaps in when it has loaded"
      (is (str/includes? css "font-family:Inter Variable"))
      (is (str/includes? css "font-display:swap")))
    (testing "nothing is asked of another server"
      (is (empty? (re-seq #"url\(\s*[\"']?https?:" css)))
      (is (empty? (re-seq #"@import" css))))))

(deftest the-templates-ask-for-nothing-from-another-server-and-carry-no-style-of-their-own
  (doseq [[path text] (templates)]
    (testing path
      (is (empty? (re-seq #"(?:src|href)=\"https?:" text)))
      (is (empty? (re-seq #"\sstyle=\"" text)) "the look is in the stylesheet, so a policy that forbids inline style loads it all"))))

;; ---------------------------------------------------------------------------
;; The icon

(deftest the-icon-is-the-mark-in-the-colours-of-the-palette
  (let [svg (xml/parse (io/input-stream (committed "resources/img/favicon.svg")))
        tokens (design/read-tokens)
        palette (get-in tokens [:palettes (get-in tokens [:palette :selected]) :light])
        parts (:content svg)
        [background group] parts]
    (is (= :svg (:tag svg)))
    (is (= {:viewBox "0 0 32 32" :width "32" :height "32" :xmlns "http://www.w3.org/2000/svg"} (:attrs svg)))
    (is (= [:rect :g] (map :tag parts)))
    (is (= (:primary palette) (:fill (:attrs background))) "the colour of the brand")
    (is (= (:primary-content palette) (:stroke (:attrs group))) "and the colour that is read on it")
    (is (= [:path :path] (map :tag (:content group))) "the two strokes of the mark")))

;; ---------------------------------------------------------------------------
;; Where it is used

(deftest the-realm-names-the-theme-and-the-stack-brings-it
  (let [realm (json/read-value (io/file "infra/keycloak/realm/pulse-realm.json"))
        theme-name (.getName (.getParentFile (io/file kc/theme-dir)))]
    (is (= "pulse" theme-name))
    (is (= theme-name (get realm "loginTheme")) "the realm shows its sign-in in this theme")
    (is (str/includes? (slurp "compose.yaml" :encoding "UTF-8")
                       (str "./infra/keycloak/themes/" theme-name ":/opt/keycloak/themes/" theme-name ":ro"))
        "and Keycloak has the directory, read-only")))
