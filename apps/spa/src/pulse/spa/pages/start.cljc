(ns pulse.spa.pages.start
  "What the workspace shows from the moment it is opened until it has a page of its own to show (ADR-0029): the wait for the
  identity provider, the failure of it, and, while there is no panel yet (step 5 of the phase), who is signed in. Pure hiccup
  of the components of `libs/ui`, like every page. A control whose function is not in `handlers` is not there."
  (:require [pulse.spa.pages.shell :as shell]
            [pulse.spa.person :as person]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]))

(defn starting
  "The page while the identity provider is asked who the person is, or sends them to its sign-in: the mark and a card with a
  spinner, with the words a screen reader says. It is the frame of the pages of Keycloak, so that the person sees one look from
  the sign-in to the workspace."
  []
  (layout/login-frame {}
                      (c/card {:title "Signing in" :heading :h1}
                              (c/spinner {:label "Signing in"}))))

(defn failed
  "The page when the sign-in could not be made: the identity provider did not answer, or does not know this application. It says
  what to do, and has a button to try again when `handlers` has `:retry`."
  [{:keys [retry]}]
  (layout/login-frame {}
                      (c/card (c/empty-state {:icon :triangle-alert
                                              :title "We could not sign you in"
                                              :heading :h1
                                              :actions (when retry (c/button {:variant :primary :on-click retry} "Try again"))}
                                             "The sign-in service did not answer. Try again in a moment."))))

(defn- role-words [{:keys [roles]}]
  (->> [[:clinic-admin "Clinic administrator"] [:practitioner "Practitioner"] [:patient "Patient"]]
       (keep (fn [[role words]] (when (contains? roles role) words)))))

(defn content
  "The card that says who is signed in: the name the identity provider has for them, their username and their roles."
  [person]
  (c/card {:title "You are signed in" :heading :h1}
          (c/facts [["Name" (:name person)]
                    ["Username" (or (:username person) "Not given")]
                    ["Role" (if-let [words (seq (role-words person))] (apply str (interpose ", " words)) "None")]])))

(defn signed-in
  "The workspace, with who is signed in as its only page. The administrator has the group of the sidebar that a practitioner
  does not, when the jobs have a page."
  [person links]
  (shell/shell {:person {:name (:name person) :detail (first (role-words person))}
                :nav (shell/nav {:admin? (person/admin? person)} links)}
               (content person)))
