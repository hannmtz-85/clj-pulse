(ns pulse.spa.pages-test
  "The pages of the workspace (ADR-0029): the frame, the wait for the sign-in, its failure and the page of the person who is
  signed in. Pure hiccup, so these run on the JVM and on Node."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.shell :as shell]
            [pulse.spa.pages.start :as start]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private links {:panel "/" :alerts "/alerts" :appointments "/appointments" :jobs "/jobs"})

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})
(def ^:private ana {:id "a1" :username "ana.garcia@pulse.test" :name "Ana García" :roles #{:patient}})

(defn- items [groups] (mapcat :items groups))

(defn- says?
  "Whether the text that `tree` shows has `text` in it."
  [tree text]
  (str/includes? (hx/words tree) text))

(deftest a-page-that-is-not-there-has-no-item
  (testing "every page that exists is an item of the sidebar"
    (is (= [:panel :alerts :appointments] (map :id (items (shell/nav {} links)))))
    (is (= ["/" "/alerts" "/appointments"] (map :href (items (shell/nav {} links))))))
  (testing "and one that does not exist is not"
    (is (= [] (shell/nav {} {})))
    (is (= [:panel] (map :id (items (shell/nav {} {:panel "/"})))))
    (is (= [:alerts] (map :id (items (shell/nav {} {:alerts "/alerts"})))))
    (is (= [:appointments] (map :id (items (shell/nav {} {:appointments "/a"})))))
    (is (= [] (shell/nav {:admin? true} {})) "the administrator has no jobs page to go to either")))

(deftest the-jobs-are-the-administrators
  (is (= [:panel :alerts :appointments :jobs] (map :id (items (shell/nav {:admin? true} links)))))
  (is (= ["Administration"] (keep :heading (shell/nav {:admin? true} links))))
  (testing "a practitioner does not see them, and neither does anyone when there is no page"
    (is (= [:panel :alerts :appointments] (map :id (items (shell/nav {:admin? false} links)))))
    (is (= [:panel] (map :id (items (shell/nav {:admin? true} {:panel "/"})))))))

(deftest the-count-of-alerts-is-shown-when-there-are-some
  (let [alerts (fn [options] (first (filter #(= :alerts (:id %)) (items (shell/nav options links)))))]
    (is (= {:id :alerts :href "/alerts" :label "Alerts" :icon :bell :count 3 :count-label "3 active"} (alerts {:active-alerts 3})))
    (is (= {:id :alerts :href "/alerts" :label "Alerts" :icon :bell} (alerts {:active-alerts 0})))
    (is (= {:id :alerts :href "/alerts" :label "Alerts" :icon :bell} (alerts {})))
    (is (= {:id :alerts :href "/alerts" :label "Alerts" :icon :bell} (alerts {:active-alerts nil})))))

(deftest the-frame-has-the-person-and-no-button-that-does-nothing
  (let [frame (shell/shell {:current :panel :person {:name "Elena Rivera" :detail "Practitioner"} :nav (shell/nav {} links)} [:h1 "Panel"])
        labels (set (map (comp :aria-label hx/attributes) (hx/by-tag frame :button)))]
    (is (some #(= "Account menu of Elena Rivera" (:aria-label (hx/attributes %))) (hx/select frame #(= :summary (hx/tag %)))))
    (is (says? frame "Elena Rivera"))
    (is (says? frame "Practitioner"))
    (is (not (contains? labels "Dark theme")) "no button for the theme: nothing is behind it")
    (is (not (contains? labels "Open the menu")) "no button for the menu of a narrow screen: nothing is behind it")
    (testing "the menu of the person has what it is given"
      (let [menu (shell/shell {:person {:name "Elena Rivera"} :menu [{:href "/profile" :label "Profile" :icon :user}]} [:h1 "T"])]
        (is (says? menu "Profile"))
        (is (not (says? frame "Profile")))))
    (testing "the search box is there when it is asked for"
      (is (empty? (hx/by-tag frame :input)))
      (is (seq (hx/by-tag (shell/shell {:person {:name "E"} :search {:id "search" :name "q" :label "Search patients"}} [:h1 "T"]) :input))))))

(deftest the-wait-and-the-failure-are-pages
  (let [waiting (start/starting)
        failed (start/failed {:retry (fn [])})]
    (is (says? waiting "Signing in"))
    (is (seq (hx/select waiting #(= "status" (:role (hx/attributes %))))) "a screen reader is told that something is loading")
    (is (says? failed "We could not sign you in"))
    (is (says? failed "Try again"))
    (testing "a button whose function is not given is not there"
      (is (empty? (hx/by-tag (start/failed {}) :button))))
    (testing "the button is the function it is given"
      (let [retry (fn [])]
        (is (= retry (:on-click (hx/attributes (first (hx/by-tag (start/failed {:retry retry}) :button))))))))))

(deftest the-page-says-who-is-signed-in
  (doseq [[person role] [[rivera "Practitioner"] [sam "Clinic administrator"] [ana "Patient"]]
          :let [page (start/signed-in person {})
                words (hx/words page)]]
    (is (str/includes? words (:name person)))
    (is (str/includes? words (:username person)))
    (is (str/includes? words role) (str role " is said"))
    (is (str/includes? words "You are signed in"))))

(deftest the-person-with-no-username-or-role-is-told-so
  (let [words (hx/words (start/content {:id "x" :name "Someone" :roles #{}}))]
    (is (str/includes? words "Not given"))
    (is (str/includes? words "None")))
  (is (says? (start/content {:id "x" :name "Someone" :roles #{:patient :practitioner}}) "Practitioner, Patient")
      "roles are said in one order, whatever the order of the set"))

(deftest the-administrators-sidebar-has-the-jobs-only-when-there-is-a-page-for-them
  (is (says? (start/signed-in sam links) "Jobs"))
  (is (not (says? (start/signed-in rivera links) "Jobs")))
  (is (not (says? (start/signed-in sam {}) "Jobs"))))

(deftest every-page-passes-the-checks-of-accessibility
  (doseq [[label page] {"starting" (start/starting)
                        "failed" (start/failed {:retry (fn [])})
                        "failed, with no button" (start/failed {})
                        "a practitioner" (start/signed-in rivera links)
                        "the administrator" (start/signed-in sam links)
                        "a person with no pages to go to" (start/signed-in ana {})}]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} page]) {:page? true})) label)))
