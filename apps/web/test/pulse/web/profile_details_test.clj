(ns pulse.web.profile-details-test
  "What a mutation analysis found that the tests of the profile did not notice: what a card says when the clinic has a detail and not
  another, and what the handler passes to the frame of the page (the person, the token of the menu, the way to try again)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.design.data :as data]
            [pulse.design.portal :as portal]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages.profile :as profile]
            [pulse.web.test.cohort :refer [csrf-of main-of page-of signed-in with-cohort]]
            [pulse.web.test.harness :as h]))

(def ^:private ana (portal/profile-model "pt-ana"))

(defn- read-back [model]
  (parsed/document (html/render [:main (profile/content model {:retry "/profile"})])))

(defn- card [tree title]
  (first (filter #(= title (hx/words (first (hx/by-tag % :h2)))) (hx/select tree #(= :section (hx/tag %))))))

(deftest a-person-with-an-email-and-no-name-has-the-email-and-no-avatar
  (let [who (card (read-back (update-in ana [:me :resource] #(-> % (dissoc :name) (dissoc :identifier :address :birthDate))))
                  "Who you are")]
    (is (= ["ana.garcia@pulse.test"] (map hx/words (hx/by-tag who :p))))
    (is (empty? (hx/select who #(hx/has-class? % "rounded-full"))) "no initials of nobody")
    (is (empty? (hx/by-tag who :dl)))))

(deftest a-person-with-a-name-and-nothing-else-is-not-told-the-clinic-has-nothing
  (let [who (card (read-back (update-in ana [:me :resource] #(-> % (dissoc :telecom :identifier :address :birthDate)))) "Who you are")]
    (is (= ["Ana García"] (map hx/words (hx/by-tag who :p))))
    (is (not (str/includes? (hx/words who) "no details")))
    (is (= 1 (count (hx/select who #(hx/has-class? % "rounded-full")))) "the initials beside the name")))

(deftest a-person-with-an-email-and-facts-has-both
  (let [who (card (read-back (update-in ana [:me :resource] dissoc :name)) "Who you are")]
    (is (= ["ana.garcia@pulse.test"] (map hx/words (hx/by-tag who :p))))
    (is (= 3 (count (hx/by-tag who :dt))))))

(deftest what-is-not-a-specialty-is-no-line-under-the-doctor
  (doseq [specialty [nil "" "  " 5 {:a 1} ["x"]]]
    (let [team (card (read-back (assoc-in ana [:me :generalPractitioner :specialty] specialty)) "Your care team")]
      (is (= ["Dr. Elena Rivera" "Requests for a visit go to your doctor."] (map hx/words (hx/by-tag team :p))) (pr-str specialty))))
  (testing "and one that is text is the line under the name"
    (let [team (card (read-back ana) "Your care team")]
      (is (= ["Internal medicine"] (map hx/words (filter #(hx/has-class? % "text-xs") (hx/by-tag team :p))))))))

;; ---------------------------------------------------------------------------
;; What the handler gives the frame of the page

(defn- menu [page]
  (first (hx/by-tag page :details)))

(deftest the-menu-of-the-profile-is-the-persons-with-the-token-of-their-session
  (with-cohort "pt-ana" {} {}
    (fn [web]
      (let [b (signed-in web)
            page (page-of (h/visit! b :get "/profile"))
            token (csrf-of web b)]
        (is (= "Account menu of Ana García" (:aria-label (hx/attributes (first (hx/by-tag (menu page) :summary))))))
        (is (= [{:type "hidden" :name "__anti-forgery-token" :value token}]
               (map hx/attributes (hx/by-tag (first (filter #(= "account-sign-out" (:id (hx/attributes %))) (hx/by-tag page :form))) :input))))
        (is (= token (:value (hx/attributes (first (hx/by-tag (main-of page) :input))))) "and so is the form of the page")
        (is (= {:X-CSRF-Token token}
               (json/read-value (:hx-headers (hx/attributes (first (hx/by-tag page :body)))) json/keyword-keys-object-mapper))
            "and htmx has it")))))

(deftest the-profile-that-could-not-be-loaded-offers-to-try-again-at-the-profile
  (with-cohort "pt-ana" {:me (h/json-answer (assoc (data/me "pt-ana") :resource nil))} {}
    (fn [web]
      (let [page (page-of (h/visit! (signed-in web) :get "/profile"))]
        (is (= ["/profile"] (map (comp :href hx/attributes) (filter #(= "Try again" (hx/words %)) (hx/by-tag page :a)))))))))

(deftest a-long-name-and-a-long-email-wrap-inside-the-card
  (let [who (card (read-back ana) "Who you are")
        [name mail] (hx/by-tag who :p)]
    (is (hx/has-class? name "wrap-anywhere"))
    (is (hx/has-class? mail "wrap-anywhere"))
    (is (hx/has-class? (first (filter #(some #{name} (hx/child-nodes %)) (hx/by-tag who :div))) "min-w-0")
        "and the block that holds them may be narrower than its text")))
