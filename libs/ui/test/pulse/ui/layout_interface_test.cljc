(ns pulse.ui.layout-interface-test
  "The frames of the interfaces (ADR-0026): the bar and the dock of the patient portal, the sidebar and the header of
  the clinical workspace, and the card of the pages of Keycloak."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private portal-nav
  [{:id :home :href "/home" :label "Home" :icon :house}
   {:id :readings :href "/readings" :label "Readings" :icon :activity}
   {:id :profile :href "/profile" :label "Profile" :icon :user}])

(def ^:private user {:name "Ana García" :items [{:href "/logout" :label "Sign out" :icon :log-out}]})

(defn- nav-named [tree label]
  (first (hx/select tree #(and (= :nav (hx/tag %)) (= label (:aria-label (hx/attributes %)))))))

;; ---------------------------------------------------------------------------
;; The patient portal

(def ^:private portal
  (layout/portal-shell {:current :readings :nav portal-nav :user user} [:h1 "Readings"] [:p "The page."]))

(deftest the-portal-has-a-skip-link-a-bar-the-page-and-a-dock
  (is (= [:div] [(hx/tag portal)]))
  (let [[skip header main dock] (hx/child-nodes portal)]
    (is (= "#main" (:href (hx/attributes skip))))
    (is (= :header (hx/tag header)))
    (is (= :main (hx/tag main)))
    (is (= "main" (:id (hx/attributes main))))
    (is (= "Readings" (hx/words (first (hx/by-tag main :h1)))))
    (is (= "The page." (hx/words (first (hx/by-tag main :p)))))
    (is (= :nav (hx/tag dock))))
  (testing "room for the dock at the bottom of a phone, and none from a medium screen on"
    (is (every? (hx/classes portal) ["pb-24" "md:pb-0" "min-h-screen" "bg-base-200"]))))

(deftest the-bar-of-the-portal-has-the-mark-the-links-and-the-person
  (let [header (first (hx/by-tag portal :header))
        bar (nav-named portal "Main")
        links (hx/by-tag bar :a)]
    (is (= "/home" (:href (hx/attributes (first (hx/by-tag header :a)))))
        "the mark leads to the first page")
    (is (= ["Home" "Readings" "Profile"] (map hx/words links)))
    (is (= ["/home" "/readings" "/profile"] (map #(:href (hx/attributes %)) links)))
    (is (= [nil "page" nil] (map #(:aria-current (hx/attributes %)) links)) "the page it is on")
    (is (= [false true false] (map #(hx/has-class? % "bg-accent") links)))
    (is (every? #(hx/has-class? % "min-h-11") links) "44 px high")
    (is (every? (hx/classes bar) ["hidden" "md:flex"]) "from a medium screen on")
    (is (= "Dark theme" (:aria-label (hx/attributes (first (hx/by-tag header :button))))))
    (is (hx/has-class? (first (hx/by-tag header :button)) "btn-lg") "48 px, for a finger")
    (is (= "Account menu of Ana García" (:aria-label (hx/attributes (first (hx/by-tag header :summary))))))))

(deftest the-dock-of-the-portal-is-the-same-navigation-for-a-phone
  (let [dock (nav-named portal "Main, on a phone")
        links (hx/by-tag dock :a)]
    (is (hx/has-class? dock "dock"))
    (is (hx/has-class? dock "md:hidden"))
    (is (= ["Home" "Readings" "Profile"] (map hx/words links)))
    (is (= ["/home" "/readings" "/profile"] (map #(:href (hx/attributes %)) links)))
    (is (= [nil "page" nil] (map #(:aria-current (hx/attributes %)) links)))
    (is (= [false true false] (map #(hx/has-class? % "dock-active") links)))
    (is (= [1 1 1] (map #(count (hx/by-tag % :svg)) links)) "an icon over each label")
    (is (= ["Home" "Readings" "Profile"] (map #(hx/words (first (hx/select % (fn [e] (hx/has-class? e "dock-label"))))) links)))))

(deftest the-portal-passes-the-checks-of-accessibility
  (is (= [] (a11y/problems (layout/document {:title "Readings"} portal) {:page? true})))
  (testing "with a current page that is not in the navigation, nothing is current"
    (let [shell (layout/portal-shell {:current :elsewhere :nav portal-nav :user user} [:h1 "T"])]
      (is (empty? (hx/select shell #(= "page" (:aria-current (hx/attributes %)))))))))

;; ---------------------------------------------------------------------------
;; The clinical workspace

(def ^:private workspace-nav
  [{:items [{:id :panel :href "/panel" :label "Panel" :icon :layout-dashboard}
            {:id :alerts :href "/alerts" :label "Alerts" :icon :bell :count 2 :count-label "2 active"}]}
   {:heading "Administration" :items [{:id :jobs :href "/jobs" :label "Jobs" :icon :server}]}])

(def ^:private workspace
  (layout/workspace-shell {:current :alerts :nav workspace-nav
                           :search {:id "search" :label "Search patients" :placeholder "Search patients" :name "q"}
                           :user user :user-detail "Internal medicine"}
                          [:h1 "Alerts"] [:p "The page."]))

(deftest the-workspace-has-a-sidebar-and-beside-it-a-header-and-the-page
  (let [[skip aside body] (hx/child-nodes workspace)]
    (is (= "#main" (:href (hx/attributes skip))))
    (is (= :aside (hx/tag aside)))
    (is (= :div (hx/tag body)))
    (is (= [:header :main] (map hx/tag (hx/child-nodes body))))
    (is (= "Alerts" (hx/words (first (hx/by-tag (second (hx/child-nodes body)) :h1)))))
    (is (= "main" (:id (hx/attributes (second (hx/child-nodes body)))))))
  (testing "its text is the size of a desk"
    (is (hx/has-class? workspace "text-sm")))
  (testing "the sidebar is a rail of icons from a medium screen and full from a large one"
    (let [aside (first (hx/by-tag workspace :aside))]
      (is (every? (hx/classes aside) ["hidden" "md:flex" "w-16" "lg:w-60" "sticky" "h-screen"])))))

(deftest the-sidebar-has-its-links-in-groups-and-says-where-it-is
  (let [nav (nav-named workspace "Workspace")
        links (hx/by-tag nav :a)]
    (is (= ["Panel" "Alerts2" "Jobs"] (map hx/words (hx/by-tag nav :li))) "the count is in the item")
    (is (= ["/panel" "/alerts" "/jobs"] (map #(:href (hx/attributes %)) links)))
    (is (= [nil "page" nil] (map #(:aria-current (hx/attributes %)) links)))
    (is (= [false true false] (map #(hx/has-class? % "bg-accent") links)))
    (is (= ["Panel" "Alerts" "Jobs"] (map #(:title (hx/attributes %)) links)) "on a rail the label is a tooltip")
    (is (= ["Panel" "Alerts, 2 active" "Jobs"] (map #(:aria-label (hx/attributes %)) links))
        "and what a screen reader says, with the count")
    (is (= ["Administration"] (map hx/words (hx/select nav #(= :p (hx/tag %)))))
        "a heading only for the group that has one")
    (is (hx/has-class? (first (hx/select nav #(= :p (hx/tag %)))) "lg:block"))
    (is (hx/has-class? (first (hx/select nav #(= :p (hx/tag %)))) "hidden") "and not on a rail")))

(deftest the-mark-of-the-sidebar-leads-to-the-first-page-and-loses-its-name-on-a-rail
  (let [logo (first (hx/by-tag (first (hx/by-tag workspace :aside)) :a))]
    (is (= "/panel" (:href (hx/attributes logo))))
    (is (= "Pulse" (hx/words logo)))
    (is (every? (hx/classes (last (hx/child-nodes logo))) ["hidden" "lg:inline"]) "only the heart is left on a rail")))

(deftest a-count-of-the-sidebar-is-a-number-in-full-and-a-dot-on-a-rail
  (let [nav (nav-named workspace "Workspace")
        alerts (second (hx/by-tag nav :a))
        number (first (hx/select alerts #(hx/has-class? % "tabular-nums")))
        dot (first (hx/select alerts #(hx/has-class? % "size-2")))]
    (is (= "2" (hx/words number)))
    (is (every? (hx/classes number) ["hidden" "lg:inline" "bg-tone-critical-soft" "text-tone-critical-ink"]))
    (is (every? (hx/classes dot) ["lg:hidden" "absolute" "rounded-full" "bg-tone-critical-edge"]))
    (is (= "true" (:aria-hidden (hx/attributes dot))) "the label already says it")
    (testing "an item with no count has neither"
      (let [panel (first (hx/by-tag nav :a))]
        (is (empty? (hx/select panel #(hx/has-class? % "tabular-nums"))))
        (is (empty? (hx/select panel #(hx/has-class? % "size-2"))))))))

(deftest the-person-is-at-the-foot-of-the-sidebar-and-the-header-has-the-search-and-the-menu
  (let [aside (first (hx/by-tag workspace :aside))
        header (first (hx/by-tag workspace :header))]
    (is (str/includes? (hx/words aside) "Ana García"))
    (is (str/includes? (hx/words aside) "Internal medicine"))
    (is (= "Search patients" (:aria-label (hx/attributes (first (hx/by-tag header :input))))))
    (is (= "Account menu of Ana García" (:aria-label (hx/attributes (first (hx/by-tag header :summary))))))
    (is (hx/has-class? (first (hx/select header #(hx/has-class? % "sm:inline"))) "lg:hidden")
        "the name is in the sidebar from a large screen on, so the menu does not repeat it")
    (is (= "Open the menu" (:aria-label (hx/attributes (first (hx/select header #(and (= :button (hx/tag %)) (= "Open the menu" (:aria-label (hx/attributes %))))))))))
    (is (= "Dark theme" (:aria-label (hx/attributes (first (hx/select header #(and (= :button (hx/tag %)) (= "Dark theme" (:aria-label (hx/attributes %))))))))))))

(deftest a-workspace-with-no-search-has-no-box
  (let [shell (layout/workspace-shell {:current :panel :nav workspace-nav :user user} [:h1 "T"])]
    (is (empty? (hx/by-tag shell :input)))))

(deftest the-workspace-passes-the-checks-of-accessibility
  (is (= [] (a11y/problems (layout/document {:title "Alerts"} workspace) {:page? true})))
  (testing "a nav with no groups and a first item with no icon count"
    (is (= [] (a11y/problems (layout/document {:title "T"}
                                              (layout/workspace-shell {:current :panel :nav [{:items [{:id :panel :href "/p" :label "Panel" :icon :house}]}]
                                                                       :user user}
                                                                      [:h1 "T"]))
                             {:page? true})))))

;; ---------------------------------------------------------------------------
;; The pages of Keycloak

(deftest a-login-card-has-a-title-an-optional-subtitle-and-its-content
  (let [card (layout/login-card {:title "Sign in" :subtitle "to continue"} [:form "The form"])]
    (is (= :h1 (hx/tag (first (hx/by-tag card :h1)))))
    (is (= "Sign in" (hx/words (first (hx/by-tag card :h1)))))
    (is (= ["to continue"] (map hx/words (hx/by-tag card :p))))
    (is (= "The form" (hx/words (first (hx/by-tag card :form)))))
    (is (every? (hx/classes card) ["rounded-box" "border" "bg-base-100" "shadow-raised"])))
  (testing "no subtitle, no line for it"
    (is (empty? (hx/by-tag (layout/login-card {:title "T"} "x") :p))))
  (testing "the heading can be of another level, for a page that has several cards"
    (let [card (layout/login-card {:title "Sign out?" :heading :h2} "x")]
      (is (empty? (hx/by-tag card :h1)))
      (is (= "Sign out?" (hx/words (first (hx/by-tag card :h2))))))))

(deftest the-pieces-of-a-login-card-are-the-ones-a-template-of-keycloak-is-made-of
  (testing "the card is its class, and the title and the subtitle are what the card puts in it"
    (is (= layout/login-card-class (:class (hx/attributes (layout/login-card {:title "T"} "x")))))
    (is (every? (set (str/split layout/login-card-class #" "))
                ["rounded-box" "border" "border-base-300" "bg-base-100" "p-6" "shadow-raised" "sm:p-8"])))
  (testing "a title is an h1 unless it is told otherwise, and keeps the attributes it is given"
    (let [title (layout/login-title "Sign in")]
      (is (= :h1 (hx/tag title)))
      (is (= "Sign in" (hx/words title)))
      (is (every? (hx/classes title) ["text-2xl" "font-semibold" "tracking-tight"])))
    (let [title (layout/login-title :h2 {:id "kc-page-title" :class "extra"} "Sign out?")]
      (is (= :h2 (hx/tag title)))
      (is (= "kc-page-title" (:id (hx/attributes title))))
      (is (every? (hx/classes title) ["extra" "text-2xl" "font-semibold" "tracking-tight"]))))
  (testing "a subtitle is a line of muted text"
    (let [subtitle (layout/login-subtitle "to continue")]
      (is (= :p (hx/tag subtitle)))
      (is (= "to continue" (hx/words subtitle)))
      (is (every? (hx/classes subtitle) ["mt-1" "text-base" "text-muted"]))))
  (testing "what is under the title has room above it, unless there is nothing in it"
    (is (= "mt-6 empty:mt-0" layout/login-content-class))
    (is (= layout/login-content-class (:class (hx/attributes (last (hx/child-nodes (layout/login-card {:title "T"} "x"))))))))
  (testing "the line under a form is small, muted and in the middle, and keeps the attributes it is given"
    (let [info (layout/login-info [:span "No account yet?"])]
      (is (= :div (hx/tag info)))
      (is (= "No account yet?" (hx/words info)))
      (is (every? (hx/classes info) ["mt-6" "text-center" "text-sm" "text-muted"])))
    (let [info (layout/login-info {:id "kc-info" :class "extra"} "x")]
      (is (= "kc-info" (:id (hx/attributes info))))
      (is (every? (hx/classes info) ["extra" "mt-6" "text-center" "text-sm" "text-muted"])))))

(deftest a-login-frame-has-the-mark-the-content-and-the-note-if-there-is-one
  (let [frame (layout/login-frame {:notice "A demonstration."} [:section "The card"])
        main (first (hx/by-tag frame :main))]
    (is (= "main" (:id (hx/attributes main))))
    (is (= "#main" (:href (hx/attributes (first (hx/by-tag frame :a))))))
    (is (= "Pulse" (hx/words (first (hx/select main #(hx/has-class? % "inline-flex"))))))
    (is (= "The card" (hx/words (first (hx/by-tag main :section)))))
    (is (every? (hx/classes frame) ["grid" "min-h-screen" "place-items-center" "bg-base-200"]))
    (is (hx/has-class? main "max-w-md"))
    (testing "the note is the last thing in the frame, small and muted"
      (let [note (last (hx/child-nodes main))]
        (is (= :p (hx/tag note)))
        (is (= "A demonstration." (hx/words note)))
        (is (every? (hx/classes note) ["mt-6" "text-center" "text-xs" "text-muted"])))))
  (testing "with no note there is no line for it, and the content may be several things"
    (let [frame (layout/login-frame {} [:section "One"] [:section "Two"])
          main (first (hx/by-tag frame :main))]
      (is (empty? (hx/by-tag frame :p)))
      (is (= ["One" "Two"] (map hx/words (hx/by-tag main :section)))))))

(deftest a-login-page-has-the-mark-the-card-and-the-note-it-is-given
  (let [shell (layout/login-shell {:title "Sign in" :subtitle "to continue" :notice "All the data is synthetic."}
                                  [:form "The form"])
        main (first (hx/by-tag shell :main))]
    (is (= "Sign in" (hx/words (first (hx/by-tag main :h1)))))
    (is (= "The form" (hx/words (first (hx/by-tag main :form)))))
    (is (str/includes? (hx/words main) "All the data is synthetic."))
    (is (= (layout/login-frame {:notice "All the data is synthetic."}
                               (layout/login-card {:title "Sign in" :subtitle "to continue" :notice "All the data is synthetic."}
                                                  [:form "The form"]))
           shell)
        "a frame around a card, and the note is the frame's and not the card's"))
  (testing "it says nothing about a demonstration of its own: the note is the page's to give"
    (is (not (str/includes? (hx/words (layout/login-shell {:title "T"} "x")) "synthetic"))))
  (is (= [] (a11y/problems (layout/document {:title "Sign in"} (layout/login-shell {:title "Sign in"} [:p "x"])) {:page? true}))))
