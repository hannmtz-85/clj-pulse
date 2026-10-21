(ns pulse.web.pages
  "The pages of the patient portal as functions that return hiccup (ADR-0028). They are pure, and depend on the
  components of `libs/ui` and on nothing else, not even on Ring, so the mock-ups of the review call the same functions
  and what is reviewed is what is served: a page of the portal is a function of the data in the shapes of the api, and
  the mock-up gives it the synthetic cohort.

  This namespace has the document that every page of the portal is, the pages for when something goes wrong, and the few pages
  around signing in and out."
  (:require [clojure.string :as str]
            [pulse.ui.component :as c]
            [pulse.ui.layout :as layout]))

;; ---------------------------------------------------------------------------
;; The document

(def assets-path
  "Where the portal serves its own files: the stylesheet, the font, the icon and htmx. It serves nothing it did not
  bring, so the Content-Security-Policy of the pages allows nothing else."
  "/assets")

(def htmx-config
  "How htmx behaves in a page of the portal, as the JSON of its configuration meta tag. It never evaluates what a page or
  an answer says (`allowEval`, and no `<script>` in an answer), writes no `<style>` element of its own (the policy of the
  pages allows no inline style; the rules it would write are in the stylesheet), asks only this server, and keeps no copy
  of a page in the storage of the browser: what a patient saw would stay there after they signed out."
  (str "{\"allowEval\":false,\"allowScriptTags\":false,\"historyCacheSize\":0,"
       "\"includeIndicatorStyles\":false,\"selfRequestsOnly\":true}"))

(defn- json-string
  "`s` as a JSON string, quoted, with the quotes, the backslashes and the control characters escaped. A few lines and not a
  library: this namespace is loaded by Babashka for the mock-ups, which has none."
  [s]
  (str "\""
       (str/replace (str s) #"[\x00-\x1f\"\\]"
                    (fn [match]
                      (case match
                        "\"" "\\\""
                        "\\" "\\\\"
                        "\n" "\\n"
                        "\r" "\\r"
                        "\t" "\\t"
                        (format "\\u%04x" (int (first match))))))
       "\""))

(defn document
  "A page of the portal: `layout/document` with the stylesheet, the icon and htmx, which the portal serves itself, and
  the title in the form `<title> · Pulse`. `options` are those of `layout/document` (`:title`, `:body-class`...) and
  `:csrf-token`, the CSRF token of the session of the person the page is for: the body gives it to htmx, which sends it as
  `X-CSRF-Token` with every request that changes something (a form carries its own, see `csrf-field`). The children are the
  body. It has no inline script or style."
  [options & body]
  (apply layout/document
         (-> options
             (assoc :title (str (:title options) " · Pulse")
                    :stylesheets [(str assets-path "/pulse.css")]
                    :head [[:meta {:name "htmx-config" :content htmx-config}]
                           [:link {:rel "icon" :type "image/svg+xml" :href (str assets-path "/favicon.svg")}]
                           [:script {:src (str assets-path "/htmx.min.js") :defer true}]])
             (cond-> (:csrf-token options)
               (assoc :body-attrs {:hx-headers (str "{\"X-CSRF-Token\":" (json-string (:csrf-token options)) "}")})))
         body))

(defn csrf-field
  "The hidden field that gives a form the CSRF token of the person's session, for the check that every request that changes
  something has to pass (`pulse.web.csrf`)."
  [csrf-token]
  [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}])

;; ---------------------------------------------------------------------------
;; When something goes wrong

(def error-kinds
  "What the portal says when something is wrong, in the words of the mock-up the owner approved: the kind of failure, the
  HTTP status it is told with, the title of its page, and what the card says (an icon, a title and a text)."
  (array-map
   :not-found {:status 404
               :page "Page not found"
               :icon :search
               :title "We cannot find that page"
               :text "The address may be old, or mistyped."}
   :forbidden {:status 403
               :page "Not allowed"
               :icon :lock
               :title "You cannot see this"
               :text "This page is about someone else. You can only see your own record."}
   :server-error {:status 500
                  :page "Something went wrong"
                  :icon :triangle-alert
                  :title "Something went wrong on our side"
                  :text "Your information is safe and nothing was lost. If it keeps happening, tell your clinic."}
   :session-ended {:status 401
                   :page "Your session has ended"
                   :icon :clock
                   :title "Your session has ended"
                   :text "For your safety, you are signed out after a while without activity."}
   ;; The words below are the portal's own, from the sign-in (ADR-0028): no mock-up had them when the sign-in was written,
   ;; and the review page of the errors shows them for the owner to say whether they are right
   :sign-in-expired {:status 400
                     :page "Sign-in expired"
                     :icon :timer
                     :title "That sign-in has expired"
                     :text "A sign-in has to be finished within a few minutes. Start again to sign in."}
   :sign-in-failed {:status 400
                    :page "Sign-in failed"
                    :icon :circle-alert
                    :title "We could not sign you in"
                    :text "The sign-in was cancelled or was not accepted. You can try again. If it keeps happening, tell your clinic."}
   :sign-in-unavailable {:status 503
                         :page "Sign-in is not available"
                         :icon :key-round
                         :title "Sign-in is not available right now"
                         :text "We could not reach the service that checks who you are. Nothing was lost. Try again in a moment."}
   :not-for-patients {:status 403
                      :page "For patients only"
                      :icon :stethoscope
                      :title "This portal is for patients"
                      :text "You are signed in as someone from the clinic. The portal is for patients: sign out and use the workspace instead."}
   :request-refused {:status 403
                     :page "Request refused"
                     :icon :octagon-alert
                     :title "We could not do that"
                     :text "The page may be out of date, or the request did not come from this site. Go to the home page and try again."}))

(defn- error-kind [kind]
  (or (get error-kinds kind)
      (throw (ex-info (str "No such kind of error: " (pr-str kind)) {:kind kind :kinds (keys error-kinds)}))))

(defn- error-action
  "What a person can do about a failure: the button of its card."
  [kind {:keys [home-href retry-href sign-in-href sign-out-href]}]
  (case kind
    :not-found (c/button {:size :lg :href home-href} "Go to the home page")
    :forbidden (c/button {:variant :outline :size :lg :href home-href} "Go to the home page")
    :server-error (c/button {:size :lg :icon :refresh-cw :href retry-href} "Try again")
    :session-ended (c/button {:size :lg :icon :log-in :href sign-in-href} "Sign in again")
    :sign-in-expired (c/button {:size :lg :icon :log-in :href sign-in-href} "Start again")
    :sign-in-failed (c/button {:size :lg :icon :refresh-cw :href sign-in-href} "Try again")
    :sign-in-unavailable (c/button {:size :lg :icon :refresh-cw :href retry-href} "Try again")
    :not-for-patients (c/button {:variant :outline :size :lg :icon :log-out :href sign-out-href} "Sign out")
    :request-refused (c/button {:variant :outline :size :lg :href home-href} "Go to the home page")))

(def ^:private error-links
  {:home-href "/" :retry-href "/" :sign-in-href "/sign-in" :sign-out-href "/signed-out"})

(defn error-card
  "The card that says that something went wrong, for a `kind` of `error-kinds`. Options: `:home-href`, `:retry-href`,
  `:sign-in-href` and `:sign-out-href`, the places its button leads to (the home, the same page again, the sign-in and the
  sign-out of the identity provider, which asks first), and `:heading`, the level of its title (`:h1` when the card is the
  whole page; a paragraph in a page that has a heading of its own)."
  ([kind] (error-card kind nil))
  ([kind options]
   (let [{:keys [icon title text]} (error-kind kind)
         links (merge error-links (select-keys options [:home-href :retry-href :sign-in-href :sign-out-href]))]
     (c/card (c/empty-state (cond-> {:icon icon :title title :actions (error-action kind links)}
                              (:heading options) (assoc :heading (:heading options)))
                            text)))))

(defn error-page
  "The whole page for a failure, for a person who is not in the portal's frame: the mark and the card. See `error-card`
  for the options."
  ([kind] (error-page kind nil))
  ([kind options]
   (document {:title (:page (error-kind kind))}
             (layout/login-frame {} (error-card kind (assoc options :heading :h1))))))

(defn status-of
  "The HTTP status that a `kind` of failure is told with."
  [kind]
  (:status (error-kind kind)))

;; ---------------------------------------------------------------------------
;; Around signing in and out

(defn signed-out-page
  "The page that a person lands on after signing out at the identity provider: it says so, and offers to sign in again."
  []
  (document {:title "Signed out"}
            (layout/login-frame {}
                                (c/card (c/empty-state {:icon :log-out
                                                        :heading :h1
                                                        :title "You have signed out"
                                                        :actions (c/button {:size :lg :icon :log-in :href "/sign-in"} "Sign in")}
                                                       "To see your record again, sign in.")))))

(defn sign-out-form
  "The form that signs a person out: a POST with the CSRF token of their session, since a link would let any page sign them out
  (ADR-0028)."
  [csrf-token]
  [:form {:method "post" :action "/sign-out"}
   (csrf-field csrf-token)
   (c/button {:type "submit" :variant :outline :size :lg :icon :log-out} "Sign out")])

(defn home-page
  "The page a person lands on when they are signed in, until the home of the portal is made (step 5 of Phase 7): who they are
  signed in as, and the way out. `options` are the `:name` of the person and their `:csrf-token`."
  [{:keys [name csrf-token]}]
  (document {:title "Home" :csrf-token csrf-token}
            (layout/login-frame {}
                                (c/card (c/empty-state {:icon :circle-check
                                                        :heading :h1
                                                        :title (str "Welcome, " name)
                                                        :actions (sign-out-form csrf-token)}
                                                       "You are signed in. The pages of your record are on their way.")))))
