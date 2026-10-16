(ns pulse.design.icons-details-test
  "What a mutation analysis found the tests of the icon generator did not notice."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.design.icons :as icons]))

(defn- svg [& shapes]
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">\n" (apply str shapes) "\n</svg>\n"))

(deftest the-attributes-of-a-shape-keep-the-order-they-were-written-in
  (let [[[_ attributes]] (icons/parse-svg (svg "<rect width=\"18\" height=\"11\" x=\"3\" y=\"11\" rx=\"2\" ry=\"2\" />"))]
    (is (= [:width :height :x :y :rx :ry] (keys attributes)))))

(deftest the-order-is-the-order-of-the-text-whatever-the-names
  (let [text (svg "<path zz=\"1\" aa=\"2\" mm=\"3\" bb=\"4\" yy=\"5\" cc=\"6\" xx=\"7\" dd=\"8\" />")
        [[_ attributes]] (icons/parse-svg text)]
    (is (= [:zz :aa :mm :bb :yy :cc :xx :dd] (keys attributes)))
    (is (str/includes? (icons/icons-source {:a (icons/parse-svg text)} "1")
                       "{:a [[:path {:zz \"1\" :aa \"2\" :mm \"3\" :bb \"4\" :yy \"5\" :cc \"6\" :xx \"7\" :dd \"8\"}]]})")
        "and so is the text that is written")))

(deftest an-attribute-has-a-name-that-starts-with-a-letter-and-may-have-hyphens
  (is (= [[:path {:d "M0 0"}]] (icons/parse-svg (svg "<path d=\"M0 0\" 9x=\"1\" />"))))
  (is (= [[:path {:d "M0 0" :stroke-linecap "round"}]] (icons/parse-svg (svg "<path d=\"M0 0\" stroke-linecap=\"round\" />")))))

(deftest each-comment-ends-at-its-own-end
  (is (= [[:path {:d "M0 0"}]]
         (icons/parse-svg "<!-- a --><svg viewBox=\"0 0 24 24\"><!-- b --><path d=\"M0 0\" /><!-- c --></svg>"))
      "what is between two comments is not a comment"))

(deftest the-version-is-the-one-of-the-package
  (is (= "1.58.0" (icons/version-of "{\n  \"name\": \"lucide-static\",\n  \"version\": \"1.58.0\",\n  \"versions\": \"9\"\n}")))
  (is (= "1.58.0" (icons/version-of "{\"version\":\"1.58.0\"}")))
  (is (nil? (icons/version-of "{\"versions\": \"9\"}")))
  (is (nil? (icons/version-of "{}"))))
