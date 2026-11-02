(ns pulse.spa.pages.start
  "What the workspace shows from the moment it is opened until it has a page of its own to show (ADR-0029): the wait for the
  identity provider, the failure of it, the page for a person the workspace is not for, the page of someone who has signed out, the
  page for a sign-in that the api did not accept, the page when the workspace itself failed, and, inside the workspace, the pages for
  an address that is no page and for a page that the person's role does not open. Pure hiccup of the components of `libs/ui`, like
  every page. **A control that has nothing to lead to is not there**: a button that calls a function is there only when the function
  is in `handlers`, and one that is a link only when the address is in `links`.

  Each page has a `...-card` that is its card without the frame around it, so that the mock-up of the states shows the very card the
  workspace serves."
  (:require [pulse.spa.pages.shell :as shell]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Before the workspace is open

(defn starting-card
  "The card of the wait: a spinner and the words a screen reader says.

  **A wait that does not end says so.** When `slow?` is true (the identity provider has not answered in a while) the card says that it
  is taking longer than usual, in the words a screen reader is told too, and has a button to try again when `:retry` is among the
  `handlers`: a spinner that turns for ever would leave the person with nothing to do."
  [{:keys [slow? handlers]}]
  (c/card {:title "Signing in" :heading :h1}
          (c/spinner {:label (if slow? "Signing in. This is taking longer than usual." "Signing in")})
          (when slow?
            [:div {:class "mt-3 text-sm text-muted"}
             [:p "This is taking longer than usual. The sign-in service has not answered yet."]
             (when-let [retry (:retry handlers)]
               [:div {:class "mt-3"} (c/button {:variant :outline :icon :refresh-cw :on-click retry} "Try again")])])))

(defn starting
  "The page while the identity provider is asked who the person is, or sends them to its sign-in: the mark and a card with a
  spinner, with the words a screen reader says. It is the frame of the pages of Keycloak, so that the person sees one look from
  the sign-in to the workspace. `options` are those of `starting-card`."
  ([] (starting nil))
  ([options] (layout/login-frame {} (starting-card options))))

(defn failed-card
  "The card when the sign-in could not be made: the identity provider did not answer, or does not know this application. It says
  what to do, and has a button to try again when `handlers` has `:retry`."
  [{:keys [retry]}]
  (c/card (c/empty-state {:icon :triangle-alert
                          :title "We could not sign you in"
                          :heading :h1
                          :actions (when retry (c/button {:variant :primary :on-click retry} "Try again"))}
                         "The sign-in service did not answer. Try again in a moment.")))

(defn failed
  "The page when the sign-in could not be made (see `failed-card`)."
  [options]
  (layout/login-frame {} (failed-card options)))

(defn refused-card
  "The card for a person who has signed in and whom the workspace is not for: a patient, or anyone who has no role of the clinic.
  It says so, with a way to the patient portal (`:portal` in `links`) and a way out (`:sign-out` in `handlers`). Nothing is asked
  of the api for them: it would refuse everything, and they would be shown a workspace of refusals."
  [{:keys [links handlers]}]
  (let [portal (:portal links)
        sign-out (:sign-out handlers)]
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
                           "The workspace is for the people who work at the clinic. If you are a patient, your readings and appointments are in the patient portal."))))

(defn refused
  "The page for a person the workspace is not for (see `refused-card`)."
  [options]
  (layout/login-frame {} (refused-card options)))

(defn signed-out-card
  "The card of a person who has signed out, which is where the identity provider sends the browser back to. It says so and offers
  to sign in again, a link to the workspace (`:sign-in` in `links`): opening it asks the identity provider, which signs the person
  in, or asks them to."
  [{:keys [links]}]
  (c/card (c/empty-state {:icon :log-out
                          :title "You have signed out"
                          :heading :h1
                          :actions (when-let [sign-in (:sign-in links)]
                                     (c/button {:variant :primary :size :lg :icon :log-in :href sign-in} "Sign in"))}
                         "To open the workspace again, sign in.")))

(defn signed-out
  "The page of a person who has signed out (see `signed-out-card`)."
  [options]
  (layout/login-frame {} (signed-out-card options)))

(defn unauthorized-card
  "The card for a sign-in that the api did not accept, even after the token was renewed and the call repeated. The person decides
  whether to sign in again (`:sign-in` in `links`): the workspace does not send them, since an api that refuses every token of this
  client would then send them round and round."
  [{:keys [links]}]
  (c/card (c/empty-state {:icon :key-round
                          :title "Your sign-in was not accepted"
                          :heading :h1
                          :actions (when-let [sign-in (:sign-in links)]
                                     (c/button {:variant :primary :size :lg :icon :log-in :href sign-in} "Sign in again"))}
                         "The service that holds the clinic's data did not accept your sign-in. Sign in again.")))

(defn unauthorized
  "The page for a sign-in that the api did not accept (see `unauthorized-card`)."
  [options]
  (layout/login-frame {} (unauthorized-card options)))

(defn crashed-card
  "The card when the workspace itself failed: a page threw while it was being drawn, and the person is told so instead of being left
  with a white page (React unmounts what it was drawing when nothing catches the error). It says what to do, with a button for each
  way out the page has: to load the page again (`:reload` in `handlers`), to go to the panel by loading it afresh (`:panel`) and to
  sign out (`:sign-out`). It does not say what failed: that is in the console, for whoever looks."
  [{:keys [handlers]}]
  (let [{:keys [reload panel sign-out]} handlers]
    (c/card (c/empty-state {:icon :triangle-alert
                            :title "Something went wrong"
                            :heading :h1
                            :actions (when (or reload panel sign-out)
                                       [:div {:class "flex flex-wrap items-center justify-center gap-2"}
                                        (when reload
                                          (c/button {:variant :primary :size :lg :icon :refresh-cw :on-click reload} "Reload the page"))
                                        (when panel
                                          (c/button {:variant :outline :size :lg :icon-end :arrow-right :on-click panel} "Go to the panel"))
                                        (when sign-out
                                          (c/button {:variant :outline :size :lg :icon :log-out :on-click sign-out} "Sign out"))])}
                           "The workspace could not show this page. The problem is on our side, not yours. Reload it to try again."))))

(defn crashed
  "The page when the workspace itself failed (see `crashed-card`)."
  [options]
  (layout/login-frame {} (crashed-card options)))

;; ---------------------------------------------------------------------------
;; Inside the workspace

(defn not-found-card
  "The card for an address that is no page of the workspace: it says so and leads to the panel (`:panel` in `links`)."
  [{:keys [links]}]
  (c/card (c/empty-state {:icon :search
                          :title "We cannot find that page"
                          :heading :h1
                          :actions (when-let [panel (:panel links)]
                                     (c/button {:variant :primary :size :lg :icon-end :arrow-right :href panel} "Go to the panel"))}
                         "Check the address, or go to the panel.")))

(defn not-found
  "The page for an address that is no page of the workspace, inside the workspace (see `not-found-card`). `options` are those of
  `pulse.spa.pages.shell/frame`."
  [options]
  (shell/frame (assoc options :current nil) (not-found-card options)))

(defn not-allowed-card
  "The card for a page of the workspace that the person's role does not open: it says so and leads to the panel. The api would refuse
  them anyway; they are told so and not shown a page of refusals."
  [{:keys [links]}]
  (c/card (c/empty-state {:icon :lock
                          :title "You cannot open that page"
                          :heading :h1
                          :actions (when-let [panel (:panel links)]
                                     (c/button {:variant :primary :size :lg :icon-end :arrow-right :href panel} "Go to the panel"))}
                         "That page is for people with another role in the clinic.")))

(defn not-allowed
  "The page for a page of the workspace that the person's role does not open, inside the workspace (see `not-allowed-card`)."
  [options]
  (shell/frame (assoc options :current nil) (not-allowed-card options)))
