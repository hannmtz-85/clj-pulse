(ns design-details-test
  "What a mutation analysis found the tests of the design tokens did not notice."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [design]))

(def ^:private tokens (design/read-tokens))

(defn- teal-light [entries]
  (filter #(and (= :teal (:palette %)) (= :light (:scheme %))) entries))

(deftest a-ratio-equal-to-the-minimum-passes-and-one-a-hair-below-it-does-not
  (let [colours (design/scheme-colors tokens :teal :light)
        exact (design/contrast-ratio (:base-content colours) (:base-100 colours))
        pairs [[:base-content :base-100 :text]]
        at-the-minimum (-> tokens (assoc-in [:contrast :text] exact) (assoc-in [:contrast :pairs] pairs))
        above (assoc-in at-the-minimum [:contrast :text] (+ exact 1e-9))]
    (is (= (* 2 (count (:palettes tokens))) (count (design/contrast-report at-the-minimum))) "the pair in each palette and scheme")
    (is (empty? (teal-light (design/failures at-the-minimum))) "exactly the ratio is enough")
    (is (= 1 (count (teal-light (design/failures above)))) "and a hair more is not")))

(deftest the-neutrals-and-the-brand-never-share-a-name
  (is (empty? (set/intersection (set design/neutral-keys) (set design/brand-keys)))
      "so that which of them wins in `scheme-colors` does not matter"))

(deftest a-file-with-no-palette-is-refused-and-says-so
  (let [found (design/problems (assoc tokens :palettes {}))]
    (is (some #(str/includes? % "there is no palette") found))
    (testing "and a chosen palette is not one of none"
      (is (some #(str/includes? % ":teal") (design/problems (-> tokens (assoc :palettes {}) (assoc-in [:palette :selected] :teal))))))))
