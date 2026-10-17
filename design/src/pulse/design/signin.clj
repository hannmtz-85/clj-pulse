(ns pulse.design.signin
  "The pages of Keycloak that the realm uses (ADR-0026), as mock-ups of the login theme: the sign-in, with and without an
  error, the message that one has signed out, the question before signing out, and the page that has expired. Each is
  a function from the context of the build to the document of its frame."
  (:require [pulse.design.mockup :as mockup]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]))

(defn- sign-in-form
  "The form of the sign-in. `:error?` shows what Keycloak says when the name or the password are wrong."
  [{:keys [error? username]}]
  [:form {:class "flex flex-col gap-5" :action "#" :method "post"}
   (when error?
     (c/alert {:tone :critical :title "Invalid email or password" :role "alert"}
              "Check them and try again."))
   (c/text-field (cond-> {:id "username" :name "username" :label "Email" :type "email" :autocomplete "username"
                          :value username}
                   (not error?) (assoc :autofocus true)
                   error? (assoc :aria-invalid "true" :class "input-error")))
   (c/text-field (cond-> {:id "password" :name "password" :label "Password" :type "password" :autocomplete "current-password"}
                   error? (assoc :aria-invalid "true" :class "input-error" :autofocus true)))
   [:div {:class "flex flex-wrap items-center justify-between gap-x-4 gap-y-2"}
    (c/checkbox-field {:id "remember" :name "rememberMe" :label "Keep me signed in"})
    [:a {:href "#" :class "link link-primary text-sm font-medium"} "Forgot your password?"]]
   (c/button {:type "submit" :size :lg :block? true} "Sign in")])

(defn sign-in
  "The sign-in page, as a patient meets it when the portal sends them here."
  [_context]
  (mockup/frame-document
   {:title "Sign in"}
   (layout/login-shell {:title "Sign in" :subtitle "to continue to the Pulse patient portal"}
                       (sign-in-form {})
                       [:p {:class "mt-6 text-center text-sm text-muted"}
                        "No account yet? Your clinic gives you one."])))

(defn- other-pages
  "The four pages of Keycloak that are not the sign-in, as cards."
  []
  [(layout/login-card {:title "Sign in" :subtitle "to continue to the Pulse patient portal" :heading :h2}
                      (sign-in-form {:error? true :username "ana.garcia@pulse.test"}))
   (layout/login-card {:title "Sign out?" :subtitle "Do you want to sign out of Pulse?" :heading :h2}
                      [:div {:class "flex flex-col gap-3"}
                       (c/button {:size :lg :block? true} "Yes, sign me out")
                       (c/button {:variant :ghost :size :lg :block? true :href "#"} "No, stay signed in")])
   (layout/login-card {:title "You have been signed out" :subtitle "Thank you for using Pulse." :heading :h2}
                      (c/alert {:tone :info :title "Your session has ended"} "You can close this window, or sign in again.")
                      [:div {:class "mt-5"} (c/button {:variant :outline :size :lg :block? true :href "#"} "Sign in again")])
   (layout/login-card {:title "Your login page has expired" :subtitle "It stayed open for too long." :heading :h2}
                      [:p "To continue, go back to the sign-in."]
                      [:div {:class "mt-5"} (c/button {:size :lg :block? true :href "#"} "Back to sign in")])])

(defn other-states
  "The error, the message after signing out, the question before it, and the expired page, side by side."
  [_context]
  (mockup/frame-document
   {:title "The other pages of Keycloak"}
   (layout/skip-link "main")
   [:div {:class "min-h-screen bg-base-200 px-4 py-10"}
    [:main {:id "main" :class "mx-auto max-w-5xl"}
     [:div {:class "mb-8 flex flex-wrap items-center justify-between gap-4"}
      (c/logo)
      [:h1 {:class "text-xl font-semibold"} "The other pages of Keycloak"]]
     (into [:div {:class "grid gap-6 md:grid-cols-2"}] (other-pages))]]))
