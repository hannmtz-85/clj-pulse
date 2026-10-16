(ns pulse.ui.tone-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.icon :as i]
            [pulse.ui.tone :as tone]))

(deftest there-are-five-tones-each-with-a-word-an-icon-and-the-classes-of-its-parts
  (is (= #{:normal :elevated :high :critical :info} (set (keys tone/tones))))
  (doseq [[k t] tone/tones]
    (is (= #{:label :icon :surface :icon-colour :dot} (set (keys t))) (str k))
    (is (not (str/blank? (:label t))) (str k " has a word"))
    (is (i/known? (:icon t)) (str k " has an icon that exists"))))

(deftest the-classes-of-a-tone-name-its-own-tokens-and-no-other
  (doseq [[k t] tone/tones
          :let [token (str "tone-" (name k) "-")
                others (map #(str "tone-" (name %) "-") (remove #{k} (keys tone/tones)))]]
    (testing (name k)
      (is (str/includes? (:surface t) (str "border-" token "edge")))
      (is (str/includes? (:surface t) (str "bg-" token "soft")))
      (is (str/includes? (:surface t) (str "text-" token "ink")))
      (is (= (str "text-" token "edge") (:icon-colour t)))
      (is (= (str "bg-" token "edge") (:dot t)))
      (is (not-any? #(str/includes? (str/join " " (vals (select-keys t [:surface :icon-colour :dot]))) %) others)))))

(deftest the-tones-have-icons-of-different-shapes
  (is (= 5 (count (set (map :icon (vals tone/tones)))))
      "a tone is never told by colour alone, so no two share an icon"))

(deftest every-band-has-a-tone-a-word-and-an-icon
  (is (= #{:normal :low :elevated :very-low :high :fever :very-high :high-fever} (set (keys tone/bands))))
  (doseq [[k b] tone/bands]
    (is (contains? tone/tones (:tone b)) (str k))
    (is (not (str/blank? (:label b))) (str k))
    (is (i/known? (:icon b)) (str k))))

(deftest bands-are-found-by-keyword-or-by-the-string-the-api-sends
  (is (= :critical (:tone (tone/band :very-high))))
  (is (= :critical (:tone (tone/band "very-high"))))
  (is (= "Very high" (:label (tone/band "very-high"))))
  (is (= :normal (:tone (tone/band "normal"))))
  (testing "a reading with no band, or a band that is not known, has none"
    (is (nil? (tone/band nil)))
    (is (nil? (tone/band "unheard-of")))))

(deftest a-low-and-a-high-reading-of-the-same-severity-differ-in-word-and-the-mildest-in-arrow
  (is (= :elevated (:tone (tone/band :low)) (:tone (tone/band :elevated))))
  (is (not= (:label (tone/band :low)) (:label (tone/band :elevated))))
  (is (not= (:icon (tone/band :low)) (:icon (tone/band :elevated)))))

(deftest an-unknown-tone-is-an-error
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"no tone called :loud" (tone/tone :loud)))
  (is (= "Normal" (:label (tone/tone :normal)))))
