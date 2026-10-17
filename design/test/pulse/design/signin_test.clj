(ns pulse.design.signin-test
  "The pages of Keycloak that the realm uses, as the theme will have them: the sign-in, and the error, the question
  before signing out, the message after it and the expired page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.signin :as signin]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(defn- field [doc id] (hx/by-id doc id))

(deftest the-sign-in-asks-for-an-email-and-a-password
  (let [doc (signin/sign-in context)
        form (first (hx/by-tag doc :form))]
    (is (= ["Sign in"] (map hx/words (hx/by-tag doc :h1))))
    (is (str/includes? (hx/words (first (hx/by-tag doc :main))) "to continue to the Pulse patient portal"))
    (is (= "post" (:method (hx/attributes form))))
    (is (= [["email" "username" "username"] ["password" "password" "current-password"]]
           (map #((juxt :type :name :autocomplete) (hx/attributes (field form %))) ["username" "password"])))
    (is (= ["Email" "Password"] (map hx/words (take 2 (hx/by-tag form :label)))))
    (is (= "Sign in" (hx/words (last (hx/by-tag form :button)))))
    (is (= "submit" (:type (hx/attributes (last (hx/by-tag form :button))))))
    (is (hx/has-class? (last (hx/by-tag form :button)) "btn-block"))
    (is (= ["Keep me signed in"] (map hx/words (hx/select form #(and (= :label (hx/tag %)) (= "remember" (:for (hx/attributes %))))))))
    (is (= "Forgot your password?" (hx/words (first (hx/by-tag form :a)))))))

(deftest the-sign-in-puts-the-cursor-in-the-first-field-and-shows-no-error
  (let [doc (signin/sign-in context)]
    (is (true? (:autofocus (hx/attributes (field doc "username")))))
    (is (not (contains? (hx/attributes (field doc "password")) :autofocus)))
    (is (empty? (hx/select doc #(= "alert" (:role (hx/attributes %))))))
    (is (not-any? #(contains? (hx/attributes %) :aria-invalid) (hx/by-tag doc :input)))))

(deftest the-sign-in-says-that-the-data-is-a-demonstration
  (is (str/includes? (hx/words (signin/sign-in context)) "All the data is synthetic")))

(deftest the-other-pages-are-four-cards-each-with-its-own-heading
  (let [doc (signin/other-states context)
        cards (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-raised")))]
    (is (= ["The other pages of Keycloak"] (map hx/words (hx/by-tag doc :h1))))
    (is (= ["Sign in" "Sign out?" "You have been signed out" "Your login page has expired"]
           (map #(hx/words (first (hx/by-tag % :h2))) cards)))
    (is (= 4 (count (hx/by-tag doc :h2))))))

(deftest a-wrong-password-is-said-in-words-with-an-icon-and-is-announced
  (let [doc (signin/other-states context)
        card (first (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-raised"))))
        alert (first (hx/select card #(= "alert" (:role (hx/attributes %)))))]
    (is (= "Invalid email or password" (hx/words (first (hx/by-tag alert :p)))))
    (is (str/includes? (hx/words alert) "Check them and try again."))
    (is (= 1 (count (hx/by-tag alert :svg))))
    (is (hx/has-class? alert "bg-tone-critical-soft"))
    (testing "both fields are marked, and the one that has to be typed again has the cursor"
      (is (= ["true" "true"] (map #(:aria-invalid (hx/attributes (field card %))) ["username" "password"])))
      (is (every? #(hx/has-class? (field card %) "input-error") ["username" "password"]))
      (is (= "ana.garcia@pulse.test" (:value (hx/attributes (field card "username")))) "the email is kept")
      (is (not (contains? (hx/attributes (field card "username")) :autofocus)))
      (is (true? (:autofocus (hx/attributes (field card "password"))))))))

(deftest signing-out-is-a-question-with-a-way-back
  (let [doc (signin/other-states context)
        card (second (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-raised"))))]
    (is (= ["Yes, sign me out" "No, stay signed in"] (map hx/words (hx/select card #(hx/has-class? % "btn")))))
    (is (every? #(and (hx/has-class? % "btn-block") (hx/has-class? % "btn-lg")) (hx/select card #(hx/has-class? % "btn"))) "as wide as the card")))

(deftest after-signing-out-the-page-says-so-and-offers-to-sign-in-again
  (let [doc (signin/other-states context)
        card (nth (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-raised"))) 2)]
    (is (str/includes? (hx/words card) "Your session has ended"))
    (is (= ["Sign in again"] (map hx/words (hx/select card #(hx/has-class? % "btn")))))
    (is (every? #(hx/has-class? % "btn-block") (hx/select card #(hx/has-class? % "btn"))))))

(deftest an-expired-page-says-what-to-do
  (let [doc (signin/other-states context)
        card (nth (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-raised"))) 3)]
    (is (str/includes? (hx/words card) "It stayed open for too long."))
    (is (str/includes? (hx/words card) "To continue, go back to the sign-in."))
    (is (= ["Back to sign in"] (map hx/words (hx/select card #(hx/has-class? % "btn")))))
    (is (every? #(hx/has-class? % "btn-block") (hx/select card #(hx/has-class? % "btn"))))))
