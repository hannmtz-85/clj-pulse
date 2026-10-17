(ns pulse.design.signin
  "The pages of Keycloak that the realm uses (ADR-0026), as mock-ups of the login theme: the sign-in, with and without an
  error, the question before signing out, the message after it, a page that says something went wrong and the page that
  has expired. They are made of the same components as the templates of the theme and say what they say, since the
  English is the theme's own (`pulse.design.keycloak/messages`): what is reviewed here is what Keycloak shows. Each is a
  function from the context of the build to the document of its frame."
  (:require [pulse.design.keycloak :as kc]
            [pulse.design.mockup :as mockup]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]))

(def ^:private client-name
  "How the realm names the client that sends the person to the sign-in: the portal."
  "Pulse web portal")

(def ^:private message kc/message)

(defn- sign-in-form
  "The form of the sign-in. `:error?` shows what Keycloak says when the name or the password are wrong. The line with
  the choice to be kept signed in and the way to a new password, which the template shows when the realm allows them, is
  not here: this realm allows neither (a new password is sent by email, and there is no mail server)."
  [{:keys [error? username]}]
  [:form {:class "flex flex-col gap-5" :action "#" :method "post"}
   (when error?
     (c/alert {:tone :critical :role "alert" :title (message :invalidUserMessage)} (message :loginErrorHint)))
   (c/text-field (cond-> {:id "username" :name "username" :label (message :usernameOrEmail) :type "text"
                          :dir "ltr" :autocomplete "username" :value username}
                   error? (assoc :aria-invalid "true" :class "input-error")
                   (not error?) (assoc :autofocus true)))
   (c/text-field (cond-> {:id "password" :name "password" :label (message :password) :type "password"
                          :autocomplete "current-password"}
                   error? (assoc :aria-invalid "true" :class "input-error" :autofocus true)))
   (c/button {:type "submit" :size :lg :block? true :id "kc-login" :name "login"} (message :doLogIn))])

(defn- sign-in-card
  "The card of the sign-in, with the hint for a person that has no account."
  [options & {:as card-options}]
  (layout/login-card (merge {:title (message :loginAccountTitle) :subtitle (message :loginSubtitle client-name)} card-options)
                     (sign-in-form options)
                     (layout/login-info [:span (message :noAccountHint)])))

(defn sign-in
  "The sign-in page, as a person meets it when an application sends them here."
  [_context]
  (mockup/frame-document
   {:title (message :loginAccountTitle)}
   (layout/login-frame {:notice (message :demoNotice)} (sign-in-card {}))))

(defn- sign-out-card []
  (layout/login-card {:title (message :logoutConfirmTitle) :subtitle (message :logoutConfirmHeader) :heading :h2}
                     [:div {:class "flex flex-col gap-3"}
                      (c/button {:type "submit" :size :lg :block? true :id "kc-logout" :name "confirmLogout"} (message :doLogout))
                      (c/button {:variant :ghost :size :lg :block? true :href "#"} (message :doStaySignedIn))]))

(defn- signed-out-card []
  (layout/login-card {:title (message :successLogout) :heading :h2}
                     (c/button {:size :lg :block? true :href "#"} (message :backToApplication))))

(defn- error-card
  "What Keycloak says when something is wrong with the request, such as `Invalid parameter: redirect_uri`."
  []
  (layout/login-card {:title (message :errorTitle) :heading :h2}
                     [:div {:class "flex flex-col gap-5"}
                      (c/alert {:tone :critical :role "alert" :title "Invalid parameter: redirect_uri"})
                      (c/button {:variant :outline :size :lg :block? true :href "#"} (message :backToApplication))]))

(defn- expired-card []
  (layout/login-card {:title (message :pageExpiredTitle) :subtitle (message :pageExpiredText) :heading :h2}
                     [:div {:class "flex flex-col gap-3"}
                      (c/button {:size :lg :block? true :href "#"} (message :pageExpiredRestart))
                      (c/button {:variant :ghost :size :lg :block? true :href "#"} (message :pageExpiredContinue))]))

(defn other-states
  "A wrong password, the question before signing out, the message after it, an error and the expired page, side by side."
  [_context]
  (mockup/frame-document
   {:title "The other pages of Keycloak"}
   (layout/skip-link "main")
   [:div {:class "min-h-screen bg-base-200 px-4 py-10"}
    [:main {:id "main" :class "mx-auto max-w-5xl"}
     [:div {:class "mb-8 flex flex-wrap items-center justify-between gap-4"}
      (c/logo)
      [:h1 {:class "text-xl font-semibold"} "The other pages of Keycloak"]]
     (into [:div {:class "grid items-start gap-6 md:grid-cols-2"}]
           [(sign-in-card {:error? true :username "ana.garcia@pulse.test"} :heading :h2)
            (sign-out-card)
            (signed-out-card)
            (error-card)
            (expired-card)])]]))
