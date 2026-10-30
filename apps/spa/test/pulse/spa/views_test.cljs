(ns pulse.spa.views-test
  "Which page the workspace shows for what the state says (ADR-0029): the wait, the pages of a person who is not let in or whose sign-in was
  not accepted, the page of someone who has signed out, and, for a person who is in, the page that the route names or the one that says
  there is none or that it is not for them. The state is put in place and the root view is rendered to a string on Node, where the
  subscriptions answer as they do in the browser."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.test.panel-fixtures :as fx]
            [pulse.spa.test.patient-fixtures :as pfx]
            [pulse.spa.views :as views]
            [re-frame.db :as db]
            [reagent.dom.server :as server]))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})
(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})
(def ^:private ana {:id "a1" :username "ana.garcia@pulse.test" :name "Ana García" :roles #{:patient}})

(defn- shown
  "The text of the root view when the state is `state`."
  [state]
  (reset! db/app-db state)
  (let [html (server/render-to-string [views/root])]
    ;; what the text of the page is, with no tags
    (str/replace html #"<[^>]*>" " ")))

(defn- says? [text words] (str/includes? (str/replace text #"\s+" " ") words))

(deftest what-the-workspace-shows-follows-the-status
  (rf-test/run-test-sync
   (testing "before anything has been asked, and while the identity provider is asked"
     (is (says? (shown {}) "Signing in"))
     (is (says? (shown {:status :starting}) "Signing in")))
   (testing "a sign-in that could not be made"
     (is (says? (shown {:status :failed}) "We could not sign you in")))
   (testing "a person the workspace is not for"
     (let [text (shown {:status :refused :person ana})]
       (is (says? text "This workspace is for the clinic"))
       (is (says? text "Go to the patient portal"))))
   (testing "a sign-in the api did not accept, and someone who has signed out"
     (is (says? (shown {:status :unauthorized :person rivera}) "Your sign-in was not accepted"))
     (is (says? (shown {:status :signed-out}) "You have signed out")))))

(deftest a-person-who-is-in-sees-the-page-the-route-names
  (rf-test/run-test-sync
   (testing "the wait, while the history has not said where they are"
     (is (says? (shown {:status :signed-in :person rivera}) "Signing in")))
   (testing "the panel, with the person in the sidebar and the box to search"
     (let [text (shown {:status :signed-in :person rivera :route {:name :panel :params {:show :all :q ""}} :panel (fx/settled)})]
       (is (says? text "Your panel"))
       (is (says? text "Ana García"))
       (is (says? text "Elena Rivera"))
       (is (says? text "Practitioner"))))
   (testing "the panel before it has begun is the wait inside the workspace"
     (let [text (shown {:status :signed-in :person rivera :route {:name :panel :params {:show :all :q ""}}})]
       (is (says? text "Your panel"))
       (is (says? text "Loading your panel"))))
   (testing "the administrator's"
     (is (says? (shown {:status :signed-in :person sam :route {:name :panel :params {:show :all :q ""}} :panel (fx/settled)}) "All patients")))
   (testing "an address that is no page"
     (let [text (shown {:status :signed-in :person rivera :route {:name :not-found :params {}}})]
       (is (says? text "We cannot find that page"))
       (is (says? text "Go to the panel"))))
   (testing "a page that the person's role does not open"
     (is (says? (shown {:status :signed-in :person ana :route {:name :panel :params {}}}) "You cannot open that page")))
   (testing "a route the workspace has no view for is the page that says there is none"
     (is (says? (shown {:status :signed-in :person rivera :route {:name :signed-out :params {}}}) "We cannot find that page")))))

(deftest the-filter-and-the-search-of-the-address-are-in-the-page
  (rf-test/run-test-sync
   (let [html (do (reset! db/app-db {:status :signed-in :person rivera :route {:name :panel :params {:show :alerts :q "ana"}} :panel (fx/settled)})
                  (server/render-to-string [views/root]))]
     (is (str/includes? html "value=\"ana\"") "the box has the search")
     (is (re-find #"href=\"/\?q=ana&amp;show=alerts\"[^>]*aria-current=\"page\"|aria-current=\"page\"[^>]*href=\"/\?q=ana&amp;show=alerts\"" html)
         "the filter that is current is a link that keeps the search")
     (is (str/includes? html "href=\"/?q=ana&amp;show=away\"") "and the others keep it too"))))

(deftest the-chart-of-a-patient-is-the-page-of-its-route
  (rf-test/run-test-sync
   (let [here {:name :patient :params {:id "pt-ana" :measurement nil :days 14}}]
     (testing "the chart that is open"
       (let [text (shown {:status :signed-in :person rivera :route here :chart (pfx/settled-ana)})]
         (is (says? text "Ana García"))
         (is (says? text "58 years · Female · PULSE-0001 · Madrid"))
         (is (says? text "BP stage 2"))
         (is (says? text "Elena Rivera"))))
     (testing "before it has begun it is the wait inside the workspace, with no patient to name"
       (let [text (shown {:status :signed-in :person rivera :route here})]
         (is (says? text "Loading the chart of the patient"))
         (is (not (says? text "Ana García")))))
     (testing "the chart that is open may be another patient's for the moment between the change of the address and the events that follow it"
       (let [text (shown {:status :signed-in :person rivera :route {:name :patient :params {:id "pt-sofia" :measurement nil :days 14}} :chart (pfx/settled-ana)})]
         (is (says? text "Loading the chart of the patient"))
         (is (not (says? text "Ana García")))
         (is (not (says? text "BP stage 2")))))
     (testing "a patient the api refuses is a page that says so"
       (is (says? (shown {:status :signed-in :person rivera :route here :chart (assoc (pfx/settled-ana) :patient pfx/forbidden)})
                  "We cannot find that patient")))
     (testing "a person whose role does not open it"
       (is (says? (shown {:status :signed-in :person ana :route here}) "You cannot open that page"))))))

(deftest the-name-of-a-patient-in-the-panel-leads-to-their-chart
  (rf-test/run-test-sync
   (reset! db/app-db {:status :signed-in :person rivera :route {:name :panel :params {:show :all :q ""}} :panel (fx/settled)})
   (let [html (server/render-to-string [views/root])]
     (is (str/includes? html "href=\"/patients/pt-ana\""))
     (is (str/includes? html "href=\"/patients/pt-noah\"")))))
