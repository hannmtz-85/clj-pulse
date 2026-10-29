(ns pulse.spa.pages-test
  "The pages of the workspace (ADR-0029): the frame, the wait for the sign-in, its failure, the pages for a person the workspace is not
  for and for a page that is not there. Pure hiccup, so these run on the JVM and on Node. The panel has its own tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.panel :as panel-page]
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

(defn- frame
  "The workspace around a title, for `person` with `options` (`:links`, `:handlers`, `:me`)."
  [person options]
  (shell/frame (merge {:current :panel :person person} options) [:h1 "Panel"]))

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

(deftest the-shell-has-the-person-and-no-button-that-does-nothing
  (let [shell-page (shell/shell {:current :panel :person {:name "Elena Rivera" :detail "Practitioner"} :nav (shell/nav {} links)} [:h1 "Panel"])
        labels (set (map (comp :aria-label hx/attributes) (hx/by-tag shell-page :button)))]
    (is (some #(= "Account menu of Elena Rivera" (:aria-label (hx/attributes %))) (hx/select shell-page #(= :summary (hx/tag %)))))
    (is (says? shell-page "Elena Rivera"))
    (is (says? shell-page "Practitioner"))
    (is (not (contains? labels "Dark theme")) "no button for the theme: nothing is behind it")
    (is (not (contains? labels "Open the menu")) "no button for the menu of a narrow screen: nothing is behind it")
    (testing "the menu of the person has what it is given"
      (let [menu (shell/shell {:person {:name "Elena Rivera"} :menu [{:href "/profile" :label "Profile" :icon :user}]} [:h1 "T"])]
        (is (says? menu "Profile"))
        (is (not (says? shell-page "Profile")))))
    (testing "the search box is there when it is asked for"
      (is (empty? (hx/by-tag shell-page :input)))
      (is (seq (hx/by-tag (shell/shell {:person {:name "E"} :search {:id "search" :name "q" :label "Search patients"}} [:h1 "T"]) :input))))))

(deftest the-frame-says-who-is-signed-in-and-how-the-role-is-said
  (doseq [[person role] [[rivera "Practitioner"] [sam "Clinic administrator"] [ana "Patient"]]
          :let [page (frame person {})]]
    (is (says? page (:name person)))
    (is (says? page role) (str role " is said"))))

(deftest the-frame-says-what-the-clinic-writes-of-the-practitioner-once-the-api-has-said
  (let [me {:state :loaded :data {:resource {:name [{:prefix ["Dr."] :given ["Elena"] :family "Rivera"}]
                                             :qualification [{:code {:text "Internal medicine"}}]}}}
        page (frame rivera {:me me})]
    (is (says? page "Dr. Elena Rivera"))
    (is (says? page "Internal medicine"))
    (is (not (says? page "Practitioner")) "what they are qualified in says more than the role")
    (testing "until then, or if it could not, the name the identity provider has and the role"
      (doseq [me [nil {:state :loading} {:state :failed :kind :server}]]
        (is (says? (frame rivera {:me me}) "Elena Rivera"))
        (is (says? (frame rivera {:me me}) "Practitioner"))
        (is (not (says? (frame rivera {:me me}) "Dr. Elena")))))))

(deftest the-search-box-has-a-name-an-id-and-the-text-it-is-given-or-none
  (let [input (fn [options] (first (hx/by-tag (frame rivera (merge {:handlers {:search (fn [_])}} options)) :input)))]
    (is (= {:id "search" :name "q" :value "" :type "search" :autocomplete "off" :placeholder "Search patients" :aria-label "Search patients"}
           (select-keys (hx/attributes (input {})) [:id :name :value :type :autocomplete :placeholder :aria-label]))
        "with no text, the box is empty and not 'nil' or anything else")
    (is (= "ana" (:value (hx/attributes (input {:search-value "ana"})))))
    (is (= "" (:value (hx/attributes (input {:search-value ""})))))))

(deftest the-administrators-sidebar-has-the-jobs-only-when-there-is-a-page-for-them
  (is (says? (frame sam {:links links}) "Jobs"))
  (is (not (says? (frame rivera {:links links}) "Jobs")))
  (is (not (says? (frame sam {}) "Jobs"))))

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

;; ---------------------------------------------------------------------------
;; Signing out, and the people the workspace is not for

(defn- button-named
  "The button of `page` whose words are `text`, or nil."
  [page text]
  (first (filter #(= text (hx/words %)) (hx/by-tag page :button))))

(defn- link-named
  "The link of `page` whose words are `text`, or nil."
  [page text]
  (first (filter #(= text (hx/words %)) (hx/by-tag page :a))))

(defn- links-of
  "The links of `page` that lead somewhere: not the one that skips to the content, which every page has."
  [page]
  (remove #(= "#main" (:href (hx/attributes %))) (hx/by-tag page :a)))

(deftest the-menu-of-the-person-signs-them-out-with-the-function-it-is-given
  (let [sign-out (fn [])
        page (frame rivera {:handlers {:sign-out sign-out}})
        button (button-named page "Sign out")]
    (is (some? button))
    (is (= sign-out (:on-click (hx/attributes button))))
    (is (= "button" (:type (hx/attributes button))) "it submits nothing")
    (is (empty? (hx/by-tag page :form)) "and there is no form, and no address that signs a person out")
    (is (not-any? #(str/includes? (str (:href (hx/attributes %))) "sign") (hx/by-tag page :a))))
  (testing "a menu with nothing to call has no Sign out"
    (is (nil? (button-named (frame rivera {}) "Sign out")))
    (is (not (says? (frame rivera {:handlers {}}) "Sign out")))))

(deftest the-person-the-workspace-is-not-for-is-told-so-and-led-away
  (let [sign-out (fn [])
        page (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out sign-out}})]
    (is (= ["This workspace is for the clinic"] (map hx/words (hx/by-tag page :h1))))
    (is (says? page "The workspace is for the people who work at the clinic. If you are a patient, your readings and appointments are in the patient portal."))
    (testing "to the patient portal, which is a link"
      (is (= "http://localhost:3300" (:href (hx/attributes (link-named page "Go to the patient portal"))))))
    (testing "and out, which is a button that calls the function it is given"
      (is (= sign-out (:on-click (hx/attributes (button-named page "Sign out"))))))
    (testing "it is not the workspace: no sidebar, no menu, nothing of the clinic"
      (is (empty? (hx/by-tag page :aside)))
      (is (empty? (hx/by-tag page :details)))
      (is (empty? (hx/by-tag page :input))))))

(deftest a-control-that-has-nothing-to-lead-to-is-not-there
  (let [bare (start/refused {})]
    (is (empty? (links-of bare)))
    (is (empty? (hx/by-tag bare :button)))
    (is (says? bare "This workspace is for the clinic")))
  (is (nil? (link-named (start/refused {:handlers {:sign-out (fn [])}}) "Go to the patient portal")))
  (is (nil? (button-named (start/refused {:links {:portal "http://localhost:3300"}}) "Sign out")))
  (is (empty? (links-of (start/signed-out {}))))
  (is (empty? (links-of (start/unauthorized {})))))

(deftest the-person-who-signed-out-is-told-so-and-can-sign-in-again
  (let [page (start/signed-out {:links {:sign-in "/"}})]
    (is (= ["You have signed out"] (map hx/words (hx/by-tag page :h1))))
    (is (says? page "To open the workspace again, sign in."))
    (is (= "/" (:href (hx/attributes (link-named page "Sign in")))) "a link, so that opening the page asks the identity provider")
    (is (empty? (hx/by-tag page :button)))
    (is (empty? (hx/by-tag page :aside)) "there is no workspace to show them")))

(deftest a-sign-in-the-api-did-not-accept-is-said-and-left-to-the-person
  (let [page (start/unauthorized {:links {:sign-in "/"}})]
    (is (= ["Your sign-in was not accepted"] (map hx/words (hx/by-tag page :h1))))
    (is (says? page "The service that holds the clinic's data did not accept your sign-in. Sign in again."))
    (is (= "/" (:href (hx/attributes (link-named page "Sign in again")))))
    (is (empty? (hx/by-tag page :aside)))))

;; ---------------------------------------------------------------------------
;; An address that is no page, and a page that is not for the person

(deftest an-address-that-is-no-page-is-said-inside-the-workspace-and-leads-to-the-panel
  (let [page (start/not-found {:person rivera :links {:panel "/"} :handlers {:sign-out (fn [])}})]
    (is (= ["We cannot find that page"] (map hx/words (hx/by-tag page :h1))))
    (is (says? page "Check the address, or go to the panel."))
    (is (= "/" (:href (hx/attributes (link-named page "Go to the panel")))))
    (is (seq (hx/by-tag page :aside)) "it is the workspace: the sidebar is there")
    (is (nil? (link-named (start/not-found {:person rivera}) "Go to the panel")) "no panel to go to, no link")))

(deftest a-page-the-role-does-not-open-is-said-inside-the-workspace-and-leads-to-the-panel
  (let [page (start/not-allowed {:person rivera :links {:panel "/"}})]
    (is (= ["You cannot open that page"] (map hx/words (hx/by-tag page :h1))))
    (is (says? page "That page is for people with another role in the clinic."))
    (is (= "/" (:href (hx/attributes (link-named page "Go to the panel")))))
    (is (nil? (link-named (start/not-allowed {:person rivera}) "Go to the panel")))))

(deftest every-page-passes-the-checks-of-accessibility
  (doseq [[label page] {"starting" (start/starting)
                        "failed" (start/failed {:retry (fn [])})
                        "failed, with no button" (start/failed {})
                        "a practitioner" (frame rivera {:links links})
                        "the administrator" (frame sam {:links links})
                        "a person with no pages to go to" (frame ana {})
                        "a practitioner, with a menu to sign out" (frame rivera {:links links :handlers {:sign-out (fn [])}})
                        "a practitioner, with the search box" (frame rivera {:links links :handlers {:search (fn [_])}})
                        "a practitioner, as the clinic writes them" (frame rivera {:me {:state :loaded :data {:resource {:name [{:prefix ["Dr."] :given ["Elena"] :family "Rivera"}]}}}})
                        "a patient, who is not for the workspace" (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}})
                        "a patient, with nowhere to go" (start/refused {})
                        "signed out" (start/signed-out {:links {:sign-in "/"}})
                        "signed out, with nowhere to go" (start/signed-out {})
                        "a sign-in that was not accepted" (start/unauthorized {:links {:sign-in "/"}})
                        "a sign-in that was not accepted, with nowhere to go" (start/unauthorized {})
                        "no such page" (start/not-found {:person rivera :links {:panel "/"} :handlers {:sign-out (fn [])}})
                        "no such page, with nowhere to go" (start/not-found {:person rivera})
                        "a page that is not for them" (start/not-allowed {:person rivera :links {:panel "/"} :handlers {:sign-out (fn [])}})}]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} page]) {:page? true})) label)))

(deftest the-panel-is-among-the-pages-that-pass
  (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} (panel-page/page nil {:show :all :q ""} {:person rivera :links links})])
                           {:page? true}))
      "the panel before it has begun, which is the wait"))
