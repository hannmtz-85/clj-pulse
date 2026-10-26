(ns pulse.spa.pages-details-test
  "What the mutation analysis of the pages found that `pages-test` did not pin (Phase 8, step 3): the icons of the sidebar, the words
  a screen reader is given while the person waits, what the card says on its own, and the one role the sidebar names."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.shell :as shell]
            [pulse.spa.pages.start :as start]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private links {:panel "/" :alerts "/alerts" :appointments "/appointments" :jobs "/jobs"})

(deftest the-items-of-the-sidebar-are-what-the-mock-up-has
  (is (= [{:items [{:id :panel :href "/" :label "Panel" :icon :layout-dashboard}
                   {:id :alerts :href "/alerts" :label "Alerts" :icon :bell}
                   {:id :appointments :href "/appointments" :label "Appointments" :icon :calendar}]}
          {:heading "Administration" :items [{:id :jobs :href "/jobs" :label "Jobs" :icon :server}]}]
         (shell/nav {:admin? true} links))))

(deftest the-search-box-is-there-only-when-it-is-asked-for
  (let [with (shell/shell {:person {:name "E"} :search {:id "search" :name "q" :label "Search patients"}} [:h1 "T"])
        without (shell/shell {:person {:name "E"}} [:h1 "T"])
        nil-search (shell/shell {:person {:name "E"} :search nil} [:h1 "T"])]
    (is (= 1 (count (hx/by-tag with :input))))
    (is (empty? (hx/by-tag without :input)))
    (is (empty? (hx/by-tag nil-search :input)))))

(deftest a-screen-reader-is-told-that-the-person-is-being-signed-in
  (let [waiting (start/starting)
        status (first (hx/select waiting #(= "status" (:role (hx/attributes %)))))]
    (is (some? status))
    (is (= "Signing in" (hx/words status)) "the words of the spinner itself, not the title of the card around it")))

(deftest the-card-says-who-the-person-is-on-its-own
  (let [card (start/content {:id "x" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
        words (hx/words card)]
    (is (str/includes? words "Name"))
    (is (str/includes? words "Elena Rivera"))
    (is (str/includes? words "Username"))
    (is (str/includes? words "dr.rivera@pulse.test"))
    (is (str/includes? words "Role"))
    (is (str/includes? words "Practitioner"))))

(deftest the-sidebar-names-the-first-role-and-the-card-names-them-all
  (let [person {:id "x" :username "u" :name "Someone" :roles #{:patient :practitioner :clinic-admin}}
        page (start/signed-in person {:links links})
        sidebar (hx/words (first (hx/by-tag page :aside)))]
    (is (str/includes? sidebar "Someone"))
    (is (str/includes? sidebar "Clinic administrator") "the first of the roles, in the order of the card")
    (is (not (str/includes? sidebar "Patient")))
    (is (not (str/includes? sidebar "Practitioner")))
    (testing "and the card names every one, in that order"
      (is (str/includes? (hx/words (start/content person)) "Clinic administrator, Practitioner, Patient")))))

(deftest the-failure-page-says-what-happened-and-the-button-what-to-do
  (let [page (start/failed {:retry (fn [])})]
    (is (= "Try again" (hx/words (first (hx/by-tag page :button)))) "the words of the button, not those of the message above it")
    (is (str/includes? (hx/words page) "The sign-in service did not answer."))
    (is (str/includes? (hx/words (first (hx/by-tag page :h1))) "We could not sign you in"))))

(deftest the-wait-has-a-heading-that-says-what-is-going-on
  (is (= "Signing in" (hx/words (first (hx/by-tag (start/starting) :h1))))))
