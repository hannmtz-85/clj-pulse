(ns pulse.ui.test.html
  "Reading the HTML of a page back into hiccup, so that the checks that run on the components (`pulse.ui.test.a11y`,
  `pulse.ui.test.hiccup`) can run on a page as a server made it: the login pages of Keycloak after FreeMarker has filled
  them in, or as Keycloak itself serves them (ADR-0026). It reads what the pages of Pulse are made of and not all of
  HTML: elements with quoted attributes, void elements, the raw text of `script` and `style`, comments, the doctype and
  character references. An element that is opened and never closed, or closed out of order, is an error, which is how
  a template that breaks the structure of a page in one of its branches shows itself."
  (:require [clojure.string :as str]))

(def ^:private void-elements
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(def ^:private raw-text-elements #{"script" "style"})

(def ^:private named-references
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'"
   "nbsp" (str (char 0xA0)) "laquo" (str (char 0xAB)) "raquo" (str (char 0xBB))})

(defn- code-point [n]
  (str (.appendCodePoint (StringBuilder.) (int n))))

(defn decode
  "The text with its character references put back: the named ones a page of Pulse uses and the numeric ones, decimal
  and hexadecimal. Any other reference is left as it is."
  [s]
  (str/replace s #"&(?:#[xX]([0-9a-fA-F]+)|#([0-9]+)|([a-zA-Z]+));"
               (fn [[whole hex decimal named]]
                 (cond hex (code-point (Integer/parseInt hex 16))
                       decimal (code-point (Integer/parseInt decimal))
                       :else (get named-references named whole)))))

(def ^:private attribute-pattern
  #"([^\s\"'<>/=]+)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'))?")

(defn- attributes [text]
  (into {}
        (for [[_ attribute-name double-quoted single-quoted] (re-seq attribute-pattern text)]
          [(keyword (str/lower-case attribute-name))
           (if-let [value (or double-quoted single-quoted)] (decode value) true)])))

(def ^:private token-pattern
  ;; a comment, the doctype, an end tag, a start tag (its attributes may hold a `>` between quotes), text, or a `<`
  ;; that starts none of them
  #"(?s)<!--.*?-->|<![dD][oO][cC][tT][yY][pP][eE][^>]*>|</([a-zA-Z][a-zA-Z0-9-]*)\s*>|<([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s\"'<>/=]+(?:\s*=\s*(?:\"[^\"]*\"|'[^']*'))?)*)\s*(/?)>|[^<]+|<")

(defn- element [tag-name attrs children]
  (into (if (seq attrs) [(keyword tag-name) attrs] [(keyword tag-name)]) children))

(defn- failure [message position html]
  (ex-info (str message " (at character " position ": "
                (pr-str (subs html (max 0 (- position 20)) (min (count html) (+ position 40)))) ")")
           {:type ::unreadable :position position}))

(defn- add-child
  "The stack of open elements with `child` added to the one on top."
  [stack child]
  (conj (pop stack) (update (peek stack) :children conj child)))

(defn parse
  "The nodes of `html` as hiccup: a vector of elements, `[:p {:class \"x\"} \"text\"]`, and strings. Attributes are
  keywords with their values decoded, or `true` for one with no value; the text between elements that is only white space
  is left out. Throws an ex-info of type `::unreadable` when an element is closed out of order, or never."
  [html]
  (let [html (str html)
        matcher (re-matcher token-pattern html)]
    (loop [stack [{:tag nil :children []}]]
      (if-not (.find matcher)
        (if (= 1 (count stack))
          (:children (peek stack))
          (throw (failure (str "<" (:tag (peek stack)) "> is never closed") (count html) html)))
        (let [token (.group matcher)
              end-tag (.group matcher 1)
              start-tag (.group matcher 2)
              position (.start matcher)]
          (cond
            (str/starts-with? token "<!") (recur stack)

            end-tag
            (let [end-tag (str/lower-case end-tag)
                  {open :tag :keys [attrs children]} (peek stack)]
              (when-not (= end-tag open)
                (throw (failure (if open
                                  (str "</" end-tag "> closes <" open ">")
                                  (str "</" end-tag "> closes nothing"))
                                position html)))
              (recur (add-child (pop stack) (element open attrs children))))

            start-tag
            (let [start-tag (str/lower-case start-tag)
                  attrs (attributes (.group matcher 3))]
              (cond
                (or (void-elements start-tag) (= "/" (.group matcher 4)))
                (recur (add-child stack (element start-tag attrs [])))

                (raw-text-elements start-tag)
                ;; everything up to the closing tag is text, whatever it looks like
                (let [content-start (.end matcher)
                      closing (re-matcher (re-pattern (str "(?is)</" start-tag "\\s*>")) html)]
                  (when-not (.find closing content-start)
                    (throw (failure (str "<" start-tag "> is never closed") position html)))
                  (.region matcher (.end closing) (count html))
                  (let [content (subs html content-start (.start closing))]
                    (recur (add-child stack (element start-tag attrs (if (str/blank? content) [] [content]))))))

                :else (recur (conj stack {:tag start-tag :attrs attrs :children []}))))

            (= "<" token) (throw (failure "A < that starts nothing" position html))

            (str/blank? token) (recur stack)

            :else (recur (add-child stack (decode token)))))))))

(defn document
  "The single element that is the whole of `html` (`[:html ...]` for a page), with the doctype and the comments around it
  left out. Throws when there is not exactly one."
  [html]
  (let [nodes (parse html)]
    (when-not (and (= 1 (count nodes)) (vector? (first nodes)))
      (throw (ex-info (str "Expected one element and found " (count nodes) " nodes") {:type ::unreadable :nodes (count nodes)})))
    (first nodes)))
