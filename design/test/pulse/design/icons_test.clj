(ns pulse.design.icons-test
  "Turning Lucide's SVG files into Clojure data (ADR-0026), and the list of icons being the icons there are."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.icons :as icons]
            [pulse.ui.icons :as ui-icons]))

(def ^:private heart-pulse
  (str "<!-- @license lucide-static v1.58.0 - ISC -->\n"
       "<svg\n  class=\"lucide lucide-heart-pulse\"\n  xmlns=\"http://www.w3.org/2000/svg\"\n  width=\"24\"\n  height=\"24\"\n"
       "  viewBox=\"0 0 24 24\"\n  fill=\"none\"\n  stroke=\"currentColor\"\n  stroke-width=\"2\"\n  stroke-linecap=\"round\"\n"
       "  stroke-linejoin=\"round\"\n>\n"
       "  <path d=\"M2 9.5a5.5 5.5 0 0 1 9.591-3.676\" />\n"
       "  <path d=\"M3.22 13H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27\" />\n"
       "</svg>\n"))

(defn- svg [& shapes]
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">\n" (str/join "\n" shapes) "\n</svg>\n"))

(defn- unexpected? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e (= ::icons/unexpected (:type (ex-data e))))))

(deftest the-shapes-of-an-icon-are-read-in-order
  (is (= [[:path {:d "M2 9.5a5.5 5.5 0 0 1 9.591-3.676"}]
          [:path {:d "M3.22 13H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27"}]]
         (icons/parse-svg heart-pulse)))
  (testing "every kind of shape, with its attributes in the order they were written"
    (is (= [[:circle {:cx "12" :cy "12" :r "10"}]
            [:rect {:width "18" :height "11" :x "3" :y "11" :rx "2" :ry "2"}]
            [:line {:x1 "12" :x2 "12" :y1 "2" :y2 "22"}]
            [:polyline {:points "22 12 18 12 15 21 9 3 6 12 2 12"}]
            [:polygon {:points "12 2 2 7 12 12"}]
            [:ellipse {:cx "12" :cy "5" :rx "9" :ry "3"}]]
           (icons/parse-svg (svg "<circle cx=\"12\" cy=\"12\" r=\"10\" />"
                                 "<rect width=\"18\" height=\"11\" x=\"3\" y=\"11\" rx=\"2\" ry=\"2\" />"
                                 "<line x1=\"12\" x2=\"12\" y1=\"2\" y2=\"22\" />"
                                 "<polyline points=\"22 12 18 12 15 21 9 3 6 12 2 12\" />"
                                 "<polygon points=\"12 2 2 7 12 12\" />"
                                 "<ellipse cx=\"12\" cy=\"5\" rx=\"9\" ry=\"3\" />")))))
  (testing "a fill is kept, and a shape may be split over lines"
    (is (= [[:circle {:cx "12" :cy "12" :r "1" :fill "currentColor"}]]
           (icons/parse-svg (svg "<circle\n  cx=\"12\"\n  cy=\"12\"\n  r=\"1\"\n  fill=\"currentColor\"\n/>"))))))

(deftest an-icon-that-is-not-only-shapes-is-refused-and-says-so
  (testing "a group, a definition, a style, a script"
    (is (unexpected? #(icons/parse-svg (svg "<g><path d=\"M0 0\" /></g>"))))
    (is (unexpected? #(icons/parse-svg (svg "<defs><path id=\"a\" d=\"M0 0\" /></defs>"))))
    (is (unexpected? #(icons/parse-svg (svg "<style>path { fill: red }</style><path d=\"M0 0\" />"))))
    (is (unexpected? #(icons/parse-svg (svg "<script>alert(1)</script><path d=\"M0 0\" />")))))
  (testing "a self-closing element that is not a shape"
    (is (unexpected? #(icons/parse-svg (svg "<text x=\"1\" y=\"1\" />"))))
    (is (unexpected? #(icons/parse-svg (svg "<use href=\"#a\" />")))))
  (testing "no shapes at all"
    (is (unexpected? #(icons/parse-svg (svg))))
    (is (unexpected? #(icons/parse-svg "")))))

(deftest the-source-is-a-namespace-with-one-map-and-one-icon-on-each-line
  (let [icons {:b [[:circle {:cx "1" :cy "2" :r "3"}]]
               :a [[:path {:d "M0 0"}] [:path {:d "M1 1"}]]}
        text (icons/icons-source icons "9.9.9")
        forms (read-string (str "[" text "]"))
        lines (str/split-lines text)]
    (is (= 'ns (first (first forms))))
    (is (= 'pulse.ui.icons (second (first forms))))
    (is (= icons (nth (second forms) 2)) "the map that is read is the map that was given")
    (is (str/includes? text "lucide-static 9.9.9"))
    (is (str/includes? text "GENERATED"))
    (is (= ["  {:a [[:path {:d \"M0 0\"}] [:path {:d \"M1 1\"}]]"
            "   :b [[:circle {:cx \"1\" :cy \"2\" :r \"3\"}]]})"]
           (take-last 2 lines))
        "sorted by name, one on a line, with no commas")
    (is (str/ends-with? text "\n"))
    (is (not (str/includes? (subs text (str/index-of text "(def icons")) ","))
        "none of the commas that pr-str writes, which the formatter would remove")))

(deftest the-icons-listed-are-the-icons-there-are
  (let [listed (edn/read-string (slurp icons/names-file))]
    (is (vector? listed))
    (is (= (count listed) (count (set listed))) "no name twice")
    (is (= (set listed) (set (keys ui-icons/icons))) "run `bb design:icons` after changing the list")))
