(ns pulse.ui.html-test
  "Reading the HTML of a page back into hiccup (`pulse.ui.test.html`), which is how the checks of accessibility run on
  a page as a server made it. The reader is a tool of the tests, so it is tested as one: against the HTML that Hiccup
  makes of the components and against the cases a page of Pulse is made of."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as html]))

(defn- unreadable
  "The message of the error that reading `text` gives, or nil when it reads."
  [text]
  (try (html/parse text)
       nil
       (catch clojure.lang.ExceptionInfo e
         (is (= ::html/unreadable (:type (ex-data e))))
         (ex-message e))))

(deftest elements-and-text-become-hiccup
  (is (= [[:p {:class "a b" :id "x"} "Hello " [:b "world"] "!"]]
         (html/parse "<p class=\"a b\" id=\"x\">Hello <b>world</b>!</p>")))
  (testing "an element with no attributes has no map, and one with no children is just its tag"
    (is (= [[:ul [:li "One"] [:li "Two"]] [:hr]] (html/parse "<ul><li>One</li><li>Two</li></ul><hr>"))))
  (testing "tags and names of attributes are read in lower case, and the quotes may be single"
    (is (= [[:a {:href "/x" :aria-label "Back"} "Home"]] (html/parse "<A HREF='/x' Aria-Label=\"Back\">Home</A>")))))

(deftest an-attribute-with-no-value-is-true
  (is (= [[:input {:autofocus true :type "text" :disabled true}]]
         (html/parse "<input autofocus type=\"text\" disabled>")))
  (testing "and one with an empty value is an empty string"
    (is (= [[:input {:value ""}]] (html/parse "<input value=\"\">")))))

(deftest void-elements-and-self-closing-tags-have-no-children
  (is (= [[:p "a" [:br] "b" [:img {:alt "x" :src "/i.png"}] "c"]]
         (html/parse "<p>a<br>b<img src=\"/i.png\" alt=\"x\">c</p>")))
  (is (= [[:div [:path {:d "M0 0"}] [:circle]]] (html/parse "<div><path d=\"M0 0\"/><circle /></div>"))
      "an svg shape that closes itself")
  (is (= [[:head [:meta {:charset "utf-8"}] [:link {:href "/a.css" :rel "stylesheet"}] [:title "T"]]]
         (html/parse "<head><meta charset=\"utf-8\"><link rel=\"stylesheet\" href=\"/a.css\"><title>T</title></head>"))))

(deftest every-void-element-has-no-children-and-no-end-tag
  (doseq [tag ["area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"]]
    (is (= [[:p "before" [(keyword tag)] "after"]] (html/parse (str "<p>before<" tag ">after</p>"))) tag)))

(deftest a-quoted-value-may-hold-what-would-end-a-tag
  (is (= [[:a {:onclick "if (a > b) { go(); }" :href "#"} "x"]]
         (html/parse "<a href=\"#\" onclick=\"if (a > b) { go(); }\">x</a>")))
  (is (= [[:form {:onsubmit "login.disabled = true; return true;"}]]
         (html/parse "<form onsubmit=\"login.disabled = true; return true;\"></form>"))))

(deftest the-text-of-a-script-or-a-style-is-text-whatever-it-looks-like
  (is (= [[:script {:type "module"} "if (a < b && c > d) { document.write('<p>x</p>'); }"]]
         (html/parse "<script type=\"module\">if (a < b && c > d) { document.write('<p>x</p>'); }</script>")))
  (is (= [[:style "p::after { content: \"<b>\" }"]] (html/parse "<style>p::after { content: \"<b>\" }</style>")))
  (is (= [[:script {:src "/a.js"}]] (html/parse "<script src=\"/a.js\"></script>")) "an empty one has no child")
  (testing "the closing tag is found in any case, and what follows is read as usual"
    (is (= [[:script "x"] [:p "after"]] (html/parse "<SCRIPT>x</SCRIPT><p>after</p>")))))

(def ^:private nbsp (str (char 0xA0)))

(def ^:private laquo (str (char 0xAB)))

(def ^:private raquo (str (char 0xBB)))

(def ^:private grinning-face
  "A character outside the basic plane, which Java writes as two chars."
  (String. (Character/toChars 0x1F600)))

(deftest character-references-are-put-back-in-text-and-in-values
  (is (= [[:p {:title "a&b \"c\" 'd'"} (str "1 < 2 > 0 & " laquo " x " raquo " = " nbsp)]]
         (html/parse "<p title=\"a&amp;b &quot;c&quot; &#39;d&#39;\">1 &lt; 2 &gt; 0 &amp; &laquo; x &raquo; &#x3D; &nbsp;</p>")))
  (testing "each of the names there are, one by one"
    (doseq [[reference text] [["&amp;" "&"] ["&lt;" "<"] ["&gt;" ">"] ["&quot;" "\""] ["&apos;" "'"]
                              ["&nbsp;" nbsp] ["&laquo;" laquo] ["&raquo;" raquo]]]
      (is (= [[:p text]] (html/parse (str "<p>" reference "</p>"))) reference)
      (is (= [[:p {:title text}]] (html/parse (str "<p title=\"" reference "\"></p>"))) reference)))
  (testing "a reference of a character outside the basic plane"
    (is (= 2 (count grinning-face)))
    (is (= [[:p grinning-face]] (html/parse "<p>&#128512;</p>")))
    (is (= [[:p grinning-face]] (html/parse "<p>&#x1F600;</p>"))))
  (testing "one that is not known is left as it is, and so is an ampersand that starts none"
    (is (= [[:p "&unknown; and a & b"]] (html/parse "<p>&unknown; and a & b</p>")))))

(deftest comments-and-the-doctype-are-left-out
  (is (= [[:p "a" "b"]] (html/parse "<!DOCTYPE html><!-- a <b>comment</b> --><p>a<!-- another -->b</p>")))
  (is (= [[:p "x"]] (html/parse "<!doctype html>\n<p>x</p>")) "the doctype in lower case"))

(deftest white-space-between-elements-is-left-out-and-the-rest-is-kept
  (is (= [[:ul [:li "One"] [:li "Two"]]] (html/parse "<ul>\n  <li>One</li>\n  <li>Two</li>\n</ul>")))
  (is (= [[:p "  spaced  " [:b "bold"] " text "]] (html/parse "<p>  spaced  <b>bold</b> text </p>"))))

(deftest document-is-the-one-element-of-a-page
  (is (= [:html {:lang "en"} [:head [:title "T"]] [:body [:p "x"]]]
         (html/document "<!DOCTYPE html>\n<html lang=\"en\"><head><title>T</title></head><body><p>x</p></body></html>\n")))
  (testing "not two elements, not text next to it, not nothing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected one element and found 2 nodes" (html/document "<p>a</p><p>b</p>")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected one element and found 2 nodes" (html/document "<p>a</p> text")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected one element and found 1 nodes" (html/document "just text")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected one element and found 0 nodes" (html/document "<!-- nothing -->")))))

(deftest an-element-closed-out-of-order-or-never-is-an-error
  (testing "the message says where, and which element is open"
    (is (re-find #"</div> closes <p>.*at character 8" (unreadable "<div><p></div>")))
    (is (re-find #"</p> closes nothing.*at character 0" (unreadable "</p>")))
    (is (re-find #"<div> is never closed" (unreadable "<div><p>text</p>")))
    (is (re-find #"</main> closes <section>" (unreadable "<main><section></main>")) "the one that is open when another closes")
    (is (re-find #"<script> is never closed" (unreadable "<script>var a = 1;"))))
  (testing "balanced input is not an error"
    (is (nil? (unreadable "<div><p>text</p></div>")))
    (is (nil? (unreadable "")))))

(deftest what-is-not-a-tag-is-an-error-and-not-silently-text
  (is (re-find #"A < that starts nothing" (unreadable "<p>a <3 b</p>")))
  (is (re-find #"A < that starts nothing" (unreadable "<p class=unquoted>x</p>")) "a value with no quotes is not read"))

(deftest the-html-of-the-components-reads-back-as-they-were-made
  (let [form [:form {:class "flex flex-col gap-5" :action "/login?a=1&b=2" :method "post"}
              (c/alert {:tone :critical :role "alert" :title "Invalid email or password"} "Check them and try again.")
              (c/text-field {:id "username" :name "username" :label "Email" :type "text" :value "ana@pulse.test" :autofocus true})
              (c/text-field {:id "password" :name "password" :label "Password" :type "password"})
              (c/button {:type "submit" :size :lg :block? true} "Sign in")]
        read-back (first (html/parse (hx/html form)))]
    (is (= (hx/words form) (hx/words read-back)) "the same words")
    (is (= (map hx/tag (hx/elements form)) (map hx/tag (hx/elements read-back))) "the same elements in the same order")
    (is (= (map hx/classes (hx/elements form)) (map hx/classes (hx/elements read-back))) "with the same classes")
    (is (= "/login?a=1&b=2" (:action (hx/attributes read-back))) "an address comes back as it was written")
    (is (true? (:autofocus (hx/attributes (hx/by-id read-back "username")))))
    (is (= "ana@pulse.test" (:value (hx/attributes (hx/by-id read-back "username")))))))

(deftest a-whole-page-reads-back-and-passes-the-same-checks-as-the-hiccup-it-came-from
  (let [page (layout/document {:title "Sign in" :stylesheets ["/pulse.css"]}
                              (layout/login-shell {:title "Sign in" :subtitle "to continue" :notice "A demonstration."}
                                                  [:form {:action "#" :method "post"}
                                                   (c/text-field {:id "username" :name "username" :label "Email"})
                                                   (c/button {:type "submit"} "Sign in")]))
        text (str "<!DOCTYPE html>\n" (hx/html page))
        read-back (html/document text)]
    (is (= :html (hx/tag read-back)))
    (is (= "en" (:lang (hx/attributes read-back))))
    (is (= [] (a11y/problems page {:page? true})))
    (is (= [] (a11y/problems read-back {:page? true})) "it reads the way the checker needs it")
    (is (= (hx/words page) (hx/words read-back)))
    (testing "and a page that breaks a rule still breaks it once it is read back"
      (let [broken (str/replace text " for=\"username\"" "")]
        (is (not= text broken) "the label was tied to its field")
        (is (seq (a11y/problems (html/document broken) {:page? true})))))))
