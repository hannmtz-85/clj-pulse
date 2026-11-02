(ns pulse.spa.views-test
  "Which page the workspace shows for what the state says (ADR-0029): the wait, the pages of a person who is not let in or whose sign-in was
  not accepted, the page of someone who has signed out, and, for a person who is in, the page that the route names or the one that says
  there is none or that it is not for them. The state is put in place and the root view is rendered to a string on Node, where the
  subscriptions answer as they do in the browser."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.test.inbox-fixtures :as ifx]
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

(deftest the-inbox-is-the-page-of-its-route
  (rf-test/run-test-sync
   (let [here {:name :alerts :params {:show :all}}]
     (testing "the inbox that is open"
       (let [text (shown {:status :signed-in :person rivera :route here :inbox (ifx/settled)})]
         (is (says? text "Alerts"))
         (is (says? text "Needs a decision"))
         (is (says? text "BP stage 2 · Ana García"))
         (is (says? text "Acknowledge"))
         (is (says? text "Glucose out of range · Liam Chen"))
         (is (says? text "Elena Rivera"))))
     (testing "before it has begun it is the wait inside the workspace, with nobody named"
       (let [text (shown {:status :signed-in :person rivera :route here})]
         (is (says? text "Loading the alerts"))
         (is (not (says? text "Ana García")))))
     (testing "the administrator's"
       (is (says? (shown {:status :signed-in :person sam :route here :inbox (ifx/settled)}) "readings of the clinic")))
     (testing "a person whose role does not open it"
       (is (says? (shown {:status :signed-in :person ana :route here}) "You cannot open that page"))))))

(deftest what-the-address-narrows-the-inbox-to-is-in-the-page
  (rf-test/run-test-sync
   (let [html (fn [show]
                (reset! db/app-db {:status :signed-in :person rivera :route {:name :alerts :params {:show show}} :inbox (ifx/settled)})
                (server/render-to-string [views/root]))]
     (is (str/includes? (html :active) "Needs a decision"))
     (is (not (str/includes? (html :active) "Glucose out of range")))
     (is (str/includes? (html :acknowledged) "Glucose out of range"))
     (is (not (str/includes? (html :acknowledged) "Needs a decision")))
     (is (str/includes? (html :all) "href=\"/alerts?show=acknowledged\"") "the pills are links that keep the address of the inbox"))))

(deftest the-count-of-the-alerts-is-in-the-sidebar-of-every-page
  (rf-test/run-test-sync
   (doseq [[label state] {"the panel" {:route {:name :panel :params {:show :all :q ""}} :panel (fx/settled)}
                          "the chart" {:route {:name :patient :params {:id "pt-ana" :measurement nil :days 14}} :chart (pfx/settled-ana)}
                          "the inbox" {:route {:name :alerts :params {:show :all}} :inbox (ifx/settled)}
                          "a page that is none" {:route {:name :not-found :params {}}}}
           :let [base (merge {:status :signed-in :person rivera} state)
                 html (fn [watch] (reset! db/app-db (assoc base :watch watch)) (server/render-to-string [views/root]))]]
     (testing label
       (is (str/includes? (html {:count {:state :loaded :total 3}}) "aria-label=\"Alerts 3 active\""))
       (is (str/includes? (html {:count {:state :failed :kind :timeout}}) "aria-label=\"Alerts ? could not be read\"") "never a badge that went away")
       (is (str/includes? (html {:count {:state :loaded :total 0}}) "aria-label=\"Alerts\"") "nothing for zero")
       (is (str/includes? (html nil) "aria-label=\"Alerts\"") "nor before it is known")
       (is (str/includes? (html nil) "href=\"/alerts\"") "and the way to the inbox")))))

(deftest the-chart-of-a-patient-can-acknowledge-and-leads-to-the-inbox
  (rf-test/run-test-sync
   (reset! db/app-db {:status :signed-in :person rivera :route {:name :patient :params {:id "pt-ana" :measurement nil :days 14}} :chart (pfx/settled-ana)})
   (let [html (server/render-to-string [views/root])]
     (is (str/includes? html "Acknowledge"))
     (is (str/includes? html "id=\"ack-flag-pt-ana-R1\""))
     (is (str/includes? html "All alerts")))
   (testing "and the button is busy while the command is on its way"
     (swap! db/app-db assoc :acks {:alerts {"flag-pt-ana-R1" {:state :sending :label "BP stage 2 · Ana García"}}})
     (is (re-find #"<button[^>]*disabled[^>]*id=\"ack-flag-pt-ana-R1\"|<button[^>]*id=\"ack-flag-pt-ana-R1\"[^>]*disabled" (server/render-to-string [views/root]))))))

(deftest a-sign-in-that-takes-too-long-says-so-and-offers-to-try-again
  (rf-test/run-test-sync
   (testing "when the patience has run out and the identity provider has not answered"
     (let [text (shown {:status :starting :slow-start? true})]
       (is (says? text "This is taking longer than usual"))
       (is (says? text "Try again"))))
   (testing "not before"
     (let [text (shown {:status :starting})]
       (is (says? text "Signing in"))
       (is (not (says? text "taking longer")))
       (is (not (says? text "Try again")))))
   (testing "and not for a person who is in, whose wait for the history is a moment"
     (let [text (shown {:status :signed-in :person rivera :slow-start? true})]
       (is (says? text "Signing in"))
       (is (not (says? text "taking longer")))))))

(deftest what-react-keeps-for-a-boundary-says-whether-what-is-under-it-threw
  (is (true? (views/crashed? (views/crash-state (js/Error. "boom")))))
  (is (false? (views/crashed? nil)) "before React has any state for it")
  (is (false? (views/crashed? #js {})))
  (is (false? (views/crashed? #js {"crashed" false})))
  (testing "the state is read by a name that the compiler does not rename"
    (is (= ["crashed"] (vec (js/Object.keys (views/crash-state nil)))))
    (is (true? (aget (views/crash-state nil) "crashed")))))

(deftest a-page-that-cannot-be-drawn-is-a-page-that-says-so-with-the-ways-out
  (let [html (server/render-to-string [views/crashed])
        text (str/replace (str/replace html #"<[^>]*>" " ") #"\s+" " ")]
    (is (says? text "Something went wrong"))
    (is (says? text "The workspace could not show this page."))
    (is (says? text "Reload the page"))
    (is (says? text "Go to the panel"))
    (is (says? text "Sign out"))
    (is (= 1 (count (re-seq #"<h1" html))) "one heading")))

(deftest the-workspace-with-its-boundary-is-the-workspace-while-nothing-threw
  (rf-test/run-test-sync
   (reset! db/app-db {:status :signed-in :person rivera :route {:name :panel :params {:show :all :q ""}} :panel (fx/settled)})
   (let [guarded (server/render-to-string [views/app])
         bare (server/render-to-string [views/root])]
     (is (str/includes? guarded "Ana García"))
     (is (not (str/includes? guarded "Something went wrong")))
     (is (= bare guarded) "the boundary adds nothing to the page"))))