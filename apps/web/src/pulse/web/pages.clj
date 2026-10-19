(ns pulse.web.pages
  "The pages of the patient portal as functions that return hiccup (ADR-0028). They are pure, and depend on the
  components of `libs/ui` and on nothing else, not even on Ring, so the mock-ups of the review call the same functions
  and what is reviewed is what is served: a page of the portal is a function of the data in the shapes of the api, and
  the mock-up gives it the synthetic cohort.

  This namespace has the document that every page of the portal is, and the pages for when something goes wrong."
  (:require [pulse.ui.component :as c]
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

(defn document
  "A page of the portal: `layout/document` with the stylesheet, the icon and htmx, which the portal serves itself, and
  the title in the form `<title> · Pulse`. `options` are those of `layout/document` (`:title`, `:body-class`...); the
  children are the body. It has no inline script or style."
  [options & body]
  (apply layout/document
         (assoc options
                :title (str (:title options) " · Pulse")
                :stylesheets [(str assets-path "/pulse.css")]
                :head [[:meta {:name "htmx-config" :content htmx-config}]
                       [:link {:rel "icon" :type "image/svg+xml" :href (str assets-path "/favicon.svg")}]
                       [:script {:src (str assets-path "/htmx.min.js") :defer true}]])
         body))

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
                   :text "For your safety, you are signed out after a while without activity."}))

(defn- error-kind [kind]
  (or (get error-kinds kind)
      (throw (ex-info (str "No such kind of error: " (pr-str kind)) {:kind kind :kinds (keys error-kinds)}))))

(defn- error-action
  "What a person can do about a failure: the button of its card."
  [kind {:keys [home-href retry-href sign-in-href]}]
  (case kind
    :not-found (c/button {:size :lg :href home-href} "Go to the home page")
    :forbidden (c/button {:variant :outline :size :lg :href home-href} "Go to the home page")
    :server-error (c/button {:size :lg :icon :refresh-cw :href retry-href} "Try again")
    :session-ended (c/button {:size :lg :icon :log-in :href sign-in-href} "Sign in again")))

(def ^:private error-links
  {:home-href "/" :retry-href "/" :sign-in-href "/sign-in"})

(defn error-card
  "The card that says that something went wrong, for a `kind` of `error-kinds`. Options: `:home-href`, `:retry-href` and
  `:sign-in-href`, the places its button leads to (the home, the same page again, and the sign-in), and `:heading`, the
  level of its title (`:h1` when the card is the whole page; a paragraph in a page that has a heading of its own)."
  ([kind] (error-card kind nil))
  ([kind options]
   (let [{:keys [icon title text]} (error-kind kind)
         links (merge error-links (select-keys options [:home-href :retry-href :sign-in-href]))]
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
