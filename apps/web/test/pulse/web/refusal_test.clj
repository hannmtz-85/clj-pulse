(ns pulse.web.refusal-test
  "What the api refuses, as words beside the fields of a form (ADR-0028), whatever the form: the api's own messages, each as a sentence of
  its own, and only for the fields the form has."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.web.refusal :as refusal]))

(def ^:private known {:description :reason :requestedStart :from})

(deftest the-names-of-the-fields-that-are-wrong-are-a-list-in-a-sentence
  (is (= "the reason" (refusal/join-and ["the reason"])))
  (is (= "the reason and the first day" (refusal/join-and ["the reason" "the first day"])))
  (is (= "the reason, the first day and the last day" (refusal/join-and ["the reason" "the first day" "the last day"])))
  (is (= "a, b, c and d" (refusal/join-and ["a" "b" "c" "d"]))))

(deftest a-message-is-written-beside-the-field-of-the-form-that-it-is-about
  (is (= {:reason "Must be 1 to 200 characters."}
         (refusal/place known {:description ["must be 1 to 200 characters"]})))
  (is (= {:reason "Is required." :from "Must be within 180 days from now."}
         (refusal/place known {:description ["is required"] :requestedStart ["must be within 180 days from now"]}))
      "each field has its own"))

(deftest what-the-api-says-of-a-field-the-form-has-not-got-is-left-out
  (is (= {} (refusal/place known {:requestedEnd ["must be after the start"]})))
  (is (= {:reason "Is required."} (refusal/place known {:description ["is required"] :requestedEnd ["must be after the start"]})))
  (testing "a key that is not a keyword is nothing the api said"
    (is (= {} (refusal/place known {"description" ["is required"]})))))

(deftest the-first-message-that-says-something-is-the-one-that-is-written
  (is (= {:reason "First."} (refusal/place known {:description ["first" "second"]})))
  (is (= {:reason "Second."} (refusal/place known {:description [nil "  " "second"]})))
  (is (= {:reason "Alone."} (refusal/place known {:description "alone"})) "a message that is not in a list")
  (testing "and a field with no message says nothing"
    (doseq [messages [[] [nil] [""] ["  "] [1 :a] nil {} 5]]
      (is (= {} (refusal/place known {:description messages})) (pr-str messages)))))

(deftest what-is-not-a-map-of-errors-says-nothing
  (doseq [errors [nil "must be" ["x"] 5 :x]]
    (is (= {} (refusal/place known errors)) (pr-str errors))))

(deftest a-message-is-a-sentence-of-its-own
  (is (= {:reason "Already a sentence."} (refusal/place known {:description ["already a sentence."]})))
  (is (= {:reason "Asked?"} (refusal/place known {:description ["asked?"]})))
  (is (= {:reason "Shouted!"} (refusal/place known {:description ["shouted!"]})))
  (is (= {:reason "Spaces around."} (refusal/place known {:description ["   spaces around  "]})))
  (testing "not a word more than 200 characters long"
    (is (= {:reason (str "X" (apply str (repeat 199 "y")) ".")}
           (refusal/place known {:description [(str "x" (apply str (repeat 500 "y")))]})))
    (is (= {:reason (str "X" (apply str (repeat 198 "y")) ".")}
           (refusal/place known {:description [(str "x" (apply str (repeat 198 "y")))]}))
        "199 characters are kept, and a stop is added")
    (is (= {:reason "Y."} (refusal/place known {:description [(str (apply str (repeat 250 " ")) "y")]}))
        "the blanks are not counted")))

(defspec any-errors-are-words-for-the-fields-of-the-form-or-nothing 300
  (prop/for-all [errors (gen/map (gen/elements [:description :requestedStart :requestedEnd :other])
                                 (gen/one-of [(gen/vector gen/string-alphanumeric 0 3) gen/string-alphanumeric (gen/return nil)]))]
    (let [words (refusal/place known errors)]
      (and (map? words)
           (every? #{:reason :from} (keys words))
           (every? #(and (string? %) (re-find #"[.!?]$" %) (<= (count %) 201)) (vals words))))))
