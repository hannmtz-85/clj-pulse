(ns pulse.ui.attrs-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.ui.attrs :as attrs]))

(deftest the-attributes-are-the-first-argument-when-it-is-a-map
  (is (= [{:id "a"} ["x" "y"]] (let [[a c] (attrs/args [{:id "a"} "x" "y"])] [a (vec c)])))
  (testing "and there are none otherwise, even if a child is a vector"
    (is (= [{} ["x" "y"]] (let [[a c] (attrs/args ["x" "y"])] [a (vec c)])))
    (is (= [{} [[:b "x"]]] (let [[a c] (attrs/args [[:b "x"]])] [a (vec c)]))))
  (testing "an empty call has no attributes and no children"
    (is (= [{} []] (let [[a c] (attrs/args [])] [a (vec c)])))))

(deftest classes-are-joined-without-blanks-or-nils
  (is (= "a b c" (attrs/classes "a" nil "b" "" "  " "c")))
  (is (= "a b c d" (attrs/classes "a b" ["c" ["d"]])) "a string may hold several, and collections are flattened")
  (is (= "" (attrs/classes nil nil)))
  (is (= "" (attrs/classes))))

(deftest a-component-puts-its-classes-before-the-callers
  (is (= {:class "btn mine" :id "x"} (attrs/with-classes {:class "mine" :id "x"} "btn")))
  (is (= {:class "btn"} (attrs/with-classes {} "btn")))
  (is (= {:class "a b c"} (attrs/with-classes {:class "c"} "a" nil "b"))))

(deftest owned-keys-are-taken-out
  (is (= {:id "x"} (attrs/owned {:id "x" :variant :primary :size :md} :variant :size)))
  (is (= {:id "x"} (attrs/owned {:id "x"}))))
