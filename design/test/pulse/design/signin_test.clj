(ns pulse.design.signin-test
  "The pages of Keycloak that the realm uses, as mock-ups: the sign-in, and the error of a wrong password, the question
  before signing out, the message after it, an error of the request and the expired page. They say what the theme says,
  since they read its messages: that is what is tested, besides what each page has to offer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.design.build :as build]
            [pulse.design.keycloak :as kc]
            [pulse.design.signin :as signin]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private context (build/context))

(defn- field [doc id] (hx/by-id doc id))

(defn- cards
  "The cards of a page, in the order they are in."
  [doc]
  (hx/select doc #(and (= :div (hx/tag %)) (hx/has-class? % "shadow-raised"))))

(defn- buttons [node] (hx/select node #(hx/has-class? % "btn")))

(defn- texts
  "Every piece of text in the body of a page, as it is written, once."
  [doc]
  (->> (hx/elements (first (hx/by-tag doc :body)))
       (mapcat hx/child-nodes)
       (filter string?)
       (map str/trim)
       (remove str/blank?)
       set))

(def ^:private chrome
  "What a page says that is not the theme's: the name of the mark and the skip link."
  #{"Pulse" "Skip to the content"})

;; ---------------------------------------------------------------------------
;; The sign-in

(deftest the-sign-in-asks-for-an-email-and-a-password
  (let [doc (signin/sign-in context)
        form (first (hx/by-tag doc :form))]
    (is (= ["Sign in"] (map hx/words (hx/by-tag doc :h1))))
    (is (= "Sign in · Pulse" (hx/words (first (hx/by-tag doc :title)))) "the title of the tab")
    (is (str/includes? (hx/words (first (hx/by-tag doc :main))) "to continue to Pulse web portal"))
    (is (= "post" (:method (hx/attributes form))))
    (is (= [["text" "username" "username"] ["password" "password" "current-password"]]
           (map #((juxt :type :name :autocomplete) (hx/attributes (field form %))) ["username" "password"]))
        "the name is typed as text, since Keycloak takes an email or a username, and the browser keeps it as one")
    (is (= "ltr" (:dir (hx/attributes (field form "username")))) "an email is read from left to right whatever the language")
    (is (= ["Email" "Password"] (map hx/words (take 2 (hx/by-tag form :label)))))
    (let [submit (last (hx/by-tag form :button))]
      (is (= "Sign in" (hx/words submit)))
      (is (= "submit" (:type (hx/attributes submit))))
      (is (= ["kc-login" "login"] ((juxt :id :name) (hx/attributes submit))) "the ones the template has")
      (is (hx/has-class? submit "btn-block")))))

(deftest the-sign-in-has-neither-the-choice-to-be-remembered-nor-the-way-to-a-new-password
  (testing "the realm allows neither, and the page does not offer what it cannot give"
    (let [doc (signin/sign-in context)
          form (first (hx/by-tag doc :form))]
      (is (empty? (hx/by-tag form :a)) "no link in the form")
      (is (not-any? #(= "checkbox" (:type (hx/attributes %))) (hx/by-tag doc :input)))
      (is (not (str/includes? (hx/words doc) "Forgot")))
      (is (not (str/includes? (hx/words doc) "Keep me signed in"))))))

(deftest the-sign-in-puts-the-cursor-in-the-first-field-and-shows-no-error
  (let [doc (signin/sign-in context)]
    (is (true? (:autofocus (hx/attributes (field doc "username")))))
    (is (not (contains? (hx/attributes (field doc "password")) :autofocus)))
    (is (empty? (hx/select doc #(= "alert" (:role (hx/attributes %))))))
    (is (not-any? #(contains? (hx/attributes %) :aria-invalid) (hx/by-tag doc :input)))))

(deftest the-sign-in-says-that-the-data-is-a-demonstration
  (let [words (hx/words (signin/sign-in context))]
    (is (str/includes? words (kc/message :demoNotice)))
    (is (str/includes? words "All the data is synthetic"))
    (is (str/includes? words "it is not a medical device"))))

(deftest the-sign-in-says-nothing-but-what-the-theme-says
  (is (= (into chrome [(kc/message :loginAccountTitle)
                       (kc/message :loginSubtitle "Pulse web portal")
                       (kc/message :usernameOrEmail)
                       (kc/message :password)
                       (kc/message :doLogIn)
                       (kc/message :noAccountHint)
                       (kc/message :demoNotice)])
         (texts (signin/sign-in context)))))

(deftest the-sign-in-is-a-whole-page-that-passes-the-checks-of-accessibility
  (is (= [] (a11y/problems (signin/sign-in context) {:page? true}))))

;; ---------------------------------------------------------------------------
;; The other pages

(deftest the-other-pages-are-five-cards-each-with-its-own-heading
  (let [doc (signin/other-states context)]
    (is (= ["The other pages of Keycloak"] (map hx/words (hx/by-tag doc :h1))))
    (is (= "The other pages of Keycloak · Pulse" (hx/words (first (hx/by-tag doc :title)))) "the title of the tab")
    (is (= [(kc/message :loginAccountTitle) (kc/message :logoutConfirmTitle) (kc/message :successLogout)
            (kc/message :errorTitle) (kc/message :pageExpiredTitle)]
           (map #(hx/words (first (hx/by-tag % :h2))) (cards doc))))
    (is (= 5 (count (hx/by-tag doc :h2))))))

(deftest a-wrong-password-is-said-in-words-with-an-icon-and-is-announced
  (let [doc (signin/other-states context)
        card (first (cards doc))
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
        card (second (cards doc))]
    (is (str/includes? (hx/words card) (kc/message :logoutConfirmHeader)))
    (is (= ["Yes, sign me out" "No, stay signed in"] (map hx/words (buttons card))))
    (is (every? #(and (hx/has-class? % "btn-block") (hx/has-class? % "btn-lg")) (buttons card)) "as wide as the card")))

(deftest after-signing-out-the-page-says-so-and-leads-back-to-the-application
  (let [doc (signin/other-states context)
        card (nth (cards doc) 2)]
    (is (= ["You have been signed out"] (map hx/words (hx/by-tag card :h2))))
    (is (empty? (hx/by-tag card :p)) "it says so and has nothing else to say")
    (is (= ["Back to the application"] (map hx/words (buttons card))) "and one way on")
    (is (every? #(hx/has-class? % "btn-block") (buttons card)))
    (is (empty? (hx/select card #(= "alert" (:role (hx/attributes %))))) "nothing has gone wrong, so nothing is announced")))

(deftest an-error-of-the-request-is-in-words-with-an-icon-and-has-a-way-out
  (let [doc (signin/other-states context)
        card (nth (cards doc) 3)
        alert (first (hx/select card #(= "alert" (:role (hx/attributes %)))))]
    (is (str/includes? (hx/words alert) "Invalid parameter: redirect_uri"))
    (is (= 1 (count (hx/by-tag alert :svg))))
    (is (hx/has-class? alert "bg-tone-critical-soft"))
    (is (= ["Back to the application"] (map hx/words (buttons card))))
    (is (every? #(hx/has-class? % "btn-block") (buttons card)))))

(deftest an-expired-page-says-what-to-do
  (let [doc (signin/other-states context)
        card (nth (cards doc) 4)]
    (is (str/includes? (hx/words card) "It stayed open for too long."))
    (is (= ["Back to sign in" "Continue where I was"] (map hx/words (buttons card))))
    (is (every? #(hx/has-class? % "btn-block") (buttons card)))
    (testing "the one that starts again is the main one, and the other is quieter"
      (is (not= (hx/classes (first (buttons card))) (hx/classes (second (buttons card))))))))

(deftest the-other-pages-say-nothing-but-what-the-theme-says
  (is (= (into chrome ["The other pages of Keycloak"
                       "Invalid parameter: redirect_uri"
                       (kc/message :loginAccountTitle)
                       (kc/message :loginSubtitle "Pulse web portal")
                       (kc/message :usernameOrEmail)
                       (kc/message :password)
                       (kc/message :doLogIn)
                       (kc/message :invalidUserMessage)
                       (kc/message :loginErrorHint)
                       (kc/message :noAccountHint)
                       (kc/message :logoutConfirmTitle)
                       (kc/message :logoutConfirmHeader)
                       (kc/message :doLogout)
                       (kc/message :doStaySignedIn)
                       (kc/message :successLogout)
                       (kc/message :backToApplication)
                       (kc/message :errorTitle)
                       (kc/message :pageExpiredTitle)
                       (kc/message :pageExpiredText)
                       (kc/message :pageExpiredRestart)
                       (kc/message :pageExpiredContinue)])
         (texts (signin/other-states context)))))

(deftest the-other-pages-pass-the-checks-of-accessibility
  (is (= [] (a11y/problems (signin/other-states context) {:page? true}))))
