(ns pulse.web.record-profile-details-test
  "What a mutation analysis found that the tests of the record and of the profile did not notice: how a row is drawn (the icon of each kind,
  the chip), a name that is not a list, and the words that nothing else pinned."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.design.portal :as portal]
            [pulse.ui.component :as c]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.profile :as profile]
            [pulse.web.pages.record :as record]))

(defn- read-back [hiccup] (parsed/document (html/render [:main hiccup])))

(defn- paths
  "The drawing of the icons inside `tree`, in order: what tells one icon from another."
  [tree]
  (map (comp :d hx/attributes) (hx/by-tag tree :path)))

(defn- rows [tree label]
  (hx/by-tag (first (hx/select tree #(and (= :ul (hx/tag %)) (= label (:aria-label (hx/attributes %)))))) :li))

(defn- icon-of [icon] (paths (read-back (c/icon-disc {:icon icon}))))

(deftest each-kind-of-row-has-its-own-icon
  (let [tree (read-back (record/content (portal/record-model "pt-sofia") {}))]
    (is (seq (icon-of :pill)))
    (is (not= (icon-of :pill) (icon-of :stethoscope)))
    (is (not= (icon-of :pill) (icon-of :triangle-alert)))
    (testing "a medication is a pill, a condition a stethoscope and an allergy a warning"
      (is (= (icon-of :pill) (paths (first (rows tree "Medication")))))
      (is (= (icon-of :stethoscope) (paths (first (rows tree "Conditions")))))
      (is (= (icon-of :triangle-alert) (paths (first (rows tree "Allergies"))))))))

(deftest since-is-an-outline-chip
  (let [tree (read-back (record/content (portal/record-model "pt-ana") {}))
        chips (hx/select tree #(hx/has-class? % "badge"))]
    (is (= 3 (count chips)) "two medications and a condition")
    (is (every? #(hx/has-class? % "badge-outline") chips))
    (is (= ["Since Nov 2025" "Since Nov 2025" "Since May 2019"] (map hx/words chips)))))

(deftest a-given-name-that-is-not-a-list-is-no-name
  (is (= "García" (profile/full-name {:name [{:given 5 :family "García"}]})))
  (is (= "García" (profile/full-name {:name [{:given {:a "Ana"} :family "García"}]})))
  (is (= "García" (profile/full-name {:name [{:given true :family "García"}]})))
  (is (nil? (profile/full-name {:name [{:given 5}]}))))
