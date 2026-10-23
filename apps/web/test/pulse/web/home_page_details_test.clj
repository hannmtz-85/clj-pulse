(ns pulse.web.home-page-details-test
  "What a mutation analysis found that the tests of the home did not notice: the words of each recent reading, with its chip and
  its icon, and of the parts that are shared with the other pages."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.ui.test.hiccup :as hx]
            [pulse.web.html :as html]
            [pulse.web.pages.home :as home]
            [pulse.web.pages.parts :as parts]))

(def ^:private ana (portal/home-model "pt-ana"))

(defn- recent-rows [model]
  (hx/by-tag (first (hx/select (home/content model portal/links) #(= "Recent readings" (:aria-label (hx/attributes %))))) :li))

(deftest each-recent-reading-says-what-it-measures-its-numbers-with-the-unit-and-when
  (let [rows (recent-rows ana)]
    (is (= [["Blood pressure" "177/100 mmHg · Today, 10:00"]
            ["Heart rate" "70 bpm · Today, 10:00"]
            ["Weight" "82.3 kg · Today, 10:00"]
            ["Blood pressure" "172/102 mmHg · Yesterday, 22:00"]
            ["Heart rate" "79 bpm · Yesterday, 22:00"]]
           (map #(map hx/words (hx/by-tag % :p)) rows)))
    (testing "each with the chip of its band, and none for what has no band"
      (is (= ["Very high" "Normal" nil "Very high" "Normal"]
             (map (fn [row] (some #{"Very high" "Normal"} (map hx/words (hx/by-tag row :span)))) rows))))
    (testing "and the icon of its measurement, which the words do not give"
      (let [icon-of (fn [row] (hx/by-tag (first (hx/select row #(hx/has-class? % "rounded-full"))) :svg))]
        (is (= (icon-of (first rows)) (icon-of (nth rows 3))) "a blood pressure has the icon of a blood pressure")
        (is (not= (icon-of (first rows)) (icon-of (second rows))))
        (is (not= (icon-of (second rows)) (icon-of (nth rows 2))))))))

(deftest a-recent-reading-has-the-unit-as-people-write-it
  (let [model (assoc ana :recent [{:id "s" :measurement "spo2" :at data/now-text :value 97 :unit "%" :band "normal"}
                                  {:id "t" :measurement "temperature" :at data/now-text :value 36.6 :unit "Cel" :band nil}])
        rows (recent-rows model)]
    (is (= [["Oxygen saturation" "97 % · Today, 14:00"] ["Temperature" "36.6 °C · Today, 14:00"]]
           (map #(map hx/words (hx/by-tag % :p)) rows)))))

(deftest a-reading-grid-has-the-tiles-of-the-measurements-in-columns
  (let [grid (parts/readings-grid (:vitals (:summary ana)) data/now-text)]
    (is (every? #(hx/has-class? grid %) ["grid" "gap-4" "sm:grid-cols-2" "lg:grid-cols-3"]))
    (is (= 3 (count (hx/select grid #(hx/has-class? % "text-4xl")))))
    (is (str/includes? (hx/words (first (hx/by-tag grid :div))) "Blood pressure"))))

(deftest every-state-of-the-home-is-written-as-html
  ;; the components of the page are hiccup, and hiccup reads a vector of vectors as an element named by a vector: only the writer says so
  (doseq [[what model] [["a patient with readings" ana]
                        ["a new patient, with its first steps" (portal/home-model "pt-noah")]
                        ["no summary" (assoc ana :summary nil :recent nil)]]]
    (let [text (html/render (home/content model portal/links))]
      (is (str/starts-with? text "<!DOCTYPE html>") what)
      (is (str/includes? text "<h1") what)))
  (let [text (html/render (home/content (portal/home-model "pt-noah") portal/links))]
    (is (str/includes? text "Record your first reading"))
    (is (str/includes? text "Look at your record"))))

(defn- section-words [tree id]
  (hx/words (first (hx/select tree #(and (= :section (hx/tag %)) (= id (:aria-labelledby (hx/attributes %))))))))

(deftest the-words-of-the-home-are-the-ones-the-owner-approved-in-the-mock-up
  (let [noah (portal/home-model "pt-noah")]
    (testing "the notice of a very high pressure, with no button, since there is no page for it"
      (let [notice (first (hx/select (home/content ana {}) #(and (= :div (hx/tag %)) (hx/has-class? % "bg-tone-critical-soft"))))]
        (is (= (str "Your last blood pressure reading is very high"
                    "177/100 mmHg, today, 10:00. If you feel unwell, with a headache, chest pain or trouble breathing, call 112. "
                    "Otherwise, rest and measure again, and tell your care team.")
               (hx/words notice)))))
    (testing "what to do first, and the card of no readings, with a way to each when there is a page for it"
      (let [tree (home/content noah portal/links)]
        (is (= (str "Your first steps"
                    "Record your first readingBlood pressure, heart rate, oxygen, glucose, weight or temperature."
                    "Look at your recordYour doctor sees what you see there.")
               (section-words tree "first")))
        (is (= (str "Your latest readings"
                    "No readings yet"
                    "When you record one, or a device sends one, you will see it here and how it compares with the usual range."
                    "Record a reading")
               (section-words tree "latest")))))
    (testing "the title of every list is the name a screen reader says it by"
      (let [labels (fn [tree] (sort (keep #(:aria-label (hx/attributes %)) (hx/by-tag tree :ul))))]
        (is (= ["Recent readings" "Your next appointment"] (labels (home/content ana {}))))
        (is (= ["First steps" "Your next appointment"] (labels (home/content noah portal/links))))))
    (testing "the note under the recent readings"
      (is (str/ends-with? (section-words (home/content ana {}) "recent")
                          "The words beside a reading, such as Very high, compare it with a reference range. They are not a diagnosis.")))
    (testing "nothing coming up"
      (is (= (str "Your next appointment"
                  "No appointment coming up"
                  "When your clinic confirms one, you will see it here."
                  "Ask for a visit")
             (section-words (home/content (assoc-in ana [:summary :appointments] []) portal/links) "next"))))))

(deftest a-card-of-a-list-reaches-the-edges-and-the-card-of-a-message-does-not
  (let [body-of (fn [tree id] (last (filter hx/element? (hx/child-nodes (first (hx/select (first (hx/select tree #(and (= :section (hx/tag %)) (= id (:aria-labelledby (hx/attributes %))))))
                                                                                          #(hx/has-class? % "shadow-card")))))))
        noah (portal/home-model "pt-noah")]
    (testing "a list: the appointment, the recent readings and the first steps"
      (is (hx/has-class? (body-of (home/content ana portal/links) "next") "mt-3"))
      (is (hx/has-class? (body-of (home/content ana portal/links) "recent") "mt-3"))
      (is (hx/has-class? (body-of (home/content noah portal/links) "first") "mt-3")))
    (testing "an empty card has room around its words"
      (is (hx/has-class? (body-of (home/content noah portal/links) "latest") "px-5"))
      (is (not (hx/has-class? (body-of (home/content noah portal/links) "latest") "mt-3"))))))
