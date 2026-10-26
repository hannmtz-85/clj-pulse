(ns pulse.spa.pages.start
  "What the workspace shows from the moment it is opened until it has a page of its own to show (ADR-0029): the wait for the
  identity provider, the failure of it, the page for a person the workspace is not for, the page of someone who has signed out, the
  page for a sign-in that the api did not accept and, while there is no panel yet (step 5 of the phase), who is signed in. Pure
  hiccup of the components of `libs/ui`, like every page. **A control that has nothing to lead to is not there**: a button that
  calls a function is there only when the function is in `handlers`, and one that is a link only when the address is in `links`."
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

(defn refused
  "The page for a person who has signed in and whom the workspace is not for: a patient, or anyone who has no role of the clinic.
  It says so, with a way to the patient portal (`:portal` in `links`) and a way out (`:sign-out` in `handlers`). Nothing is asked
  of the api for them: it would refuse everything, and they would be shown a workspace of refusals."
  [{:keys [links handlers]}]
  (let [portal (:portal links)
        sign-out (:sign-out handlers)]
    (layout/login-frame {}
                        (c/card (c/empty-state {:icon :lock
                                                :title "This workspace is for the clinic"
                                                :heading :h1
                                                ;; two large buttons do not fit side by side on a phone, which is where a patient is
                                                :actions (when (or portal sign-out)
                                                           [:div {:class "flex flex-wrap items-center justify-center gap-2"}
                                                            (when portal
                                                              (c/button {:variant :primary :size :lg :icon-end :arrow-right :href portal}
                                                                        "Go to the patient portal"))
                                                            (when sign-out
                                                              (c/button {:variant :outline :size :lg :icon :log-out :on-click sign-out}
                                                                        "Sign out"))])}
                                               "The workspace is for the people who work at the clinic. If you are a patient, your readings and appointments are in the patient portal.")))))

(defn signed-out
  "The page of a person who has signed out, which is where the identity provider sends the browser back to. It says so and offers
  to sign in again, a link to the workspace (`:sign-in` in `links`): opening it asks the identity provider, which signs the person
  in, or asks them to."
  [{:keys [links]}]
  (layout/login-frame {}
                      (c/card (c/empty-state {:icon :log-out
                                              :title "You have signed out"
                                              :heading :h1
                                              :actions (when-let [sign-in (:sign-in links)]
                                                         (c/button {:variant :primary :size :lg :icon :log-in :href sign-in} "Sign in"))}
                                             "To open the workspace again, sign in."))))

(defn unauthorized
  "The page for a sign-in that the api did not accept, even after the token was renewed and the call repeated. The person decides
  whether to sign in again (`:sign-in` in `links`): the workspace does not send them, since an api that refuses every token of this
  client would then send them round and round."
  [{:keys [links]}]
  (layout/login-frame {}
                      (c/card (c/empty-state {:icon :key-round
                                              :title "Your sign-in was not accepted"
                                              :heading :h1
                                              :actions (when-let [sign-in (:sign-in links)]
                                                         (c/button {:variant :primary :size :lg :icon :log-in :href sign-in} "Sign in again"))}
                                             "The service that holds the clinic's data did not accept your sign-in. Sign in again."))))

(defn- role-words [{:keys [roles]}]
  (->> [[:clinic-admin "Clinic administrator"] [:practitioner "Practitioner"] [:patient "Patient"]]
       (keep (fn [[role words]] (when (contains? roles role) words)))))

(defn- record-words
  "What the card says of the person's record, which only the api knows: the FHIR resource that is them. `me` is the state of the
  call that asked: `{:state :loading|:loaded|:failed :data ...}`, or nil while it has not been made."
  [me]
  (case (:state me)
    :loaded (or (person/text-of (get-in me [:data :fhirUser])) "None")
    :failed "Not available"
    "Loading"))

(defn content
  "The card that says who is signed in: the name the identity provider has for them, their username and their roles and, from the
  api, the record that is them (`me`: see `record-words`)."
  ([person] (content person nil))
  ([person me]
   (c/card {:title "You are signed in" :heading :h1}
           (c/facts [["Name" (:name person)]
                     ["Username" (or (:username person) "Not given")]
                     ["Role" (if-let [words (seq (role-words person))] (apply str (interpose ", " words)) "None")]
                     ["Your record" (record-words me)]]))))

(defn signed-in
  "The workspace, with who is signed in as its only page. `options` are `:links` (where the pages are: the administrator has the
  group of the sidebar that a practitioner does not, when the jobs have a page), `:handlers` (`:sign-out`, which puts \"Sign out\"
  in the menu of the person) and `:me` (see `content`)."
  [person {:keys [links handlers me]}]
  (shell/shell {:person {:name (:name person) :detail (first (role-words person))}
                :nav (shell/nav {:admin? (person/admin? person)} links)
                :menu (when-let [sign-out (:sign-out handlers)]
                        [{:label "Sign out" :icon :log-out :on-click sign-out}])}
               (content person me)))
