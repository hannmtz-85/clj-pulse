(ns pulse.web.pages-details-test
  "What the pages must keep whatever they are given (ADR-0028): the token that htmx sends is the CSRF token of the session, however
  odd its characters, since the page writes it as JSON in an attribute without a library (Babashka loads the pages for the
  mock-ups and has none)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]))

(defn- header-of
  "The `hx-headers` that a page gives htmx for `token`, as the browser reads the attribute."
  [token]
  (let [text (html/render (pages/document {:title "x" :csrf-token token} [:main]))]
    (:hx-headers (hx/attributes (first (hx/by-tag (parsed/document text) :body))))))

(def ^:private odd-characters
  ["\"" "\\" "\n" "\r" "\t" (str (char 1)) (str (char 31)) (str (char 127)) "a" "é" "</script>" "'" "&" "<" ">"])

(defspec the-token-htmx-sends-is-the-token-whatever-it-holds 300
  (prop/for-all [token (gen/one-of [gen/string
                                    gen/string-ascii
                                    (gen/fmap #(apply str %) (gen/vector (gen/elements odd-characters) 0 12))])]
    (= {:X-CSRF-Token token}
       (json/read-value (header-of token) json/keyword-keys-object-mapper))))

(deftest the-header-is-an-object-with-one-header
  (is (= "{\"X-CSRF-Token\":\"abc\"}" (header-of "abc")))
  (is (= "{\"X-CSRF-Token\":\"a\\\"b\\\\c\\nd\\u0001\"}" (header-of (str "a\"b\\c\nd" (char 1)))) "escaped as JSON has it"))
