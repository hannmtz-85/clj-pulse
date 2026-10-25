(ns pulse.web.pages-test
  "The pages of the portal (ADR-0028) as hiccup and as the HTML that is served: a document that needs nothing from another
  server and carries no script or style of its own, the pages for when something goes wrong in the words the owner
  approved, the classes of every page found in the stylesheet, and the mock-up of the review made of the same cards."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [pulse.design.build :as build]
            [pulse.design.portal :as portal]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]
            [pulse.ui.test.html :as parsed]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.pages.home :as home-page]))

(def ^:private stylesheet (io/file "apps/web/resources/public/pulse.css"))

(defn- page-html [kind & [options]]
  (html/render (pages/error-page kind options)))

(defn- tree [text] (parsed/document text))

;; ---------------------------------------------------------------------------
;; The document

(deftest a-page-is-a-whole-document-that-needs-nothing-from-another-server
  (let [text (html/render (pages/document {:title "Home"} [:main {:id "main"} [:h1 "Hello"]]))
        page (tree text)]
    (is (str/starts-with? text "<!DOCTYPE html>"))
    (is (= "en" (:lang (hx/attributes page))))
    (is (= "Home · Pulse" (hx/words (first (hx/by-tag page :title)))) "the title ends with the name of the product")
    (testing "the stylesheet, the icon and htmx are the portal's own files"
      (is (= ["/assets/pulse.css"] (map (comp :href hx/attributes) (filter #(= "stylesheet" (:rel (hx/attributes %))) (hx/by-tag page :link)))))
      (is (= [["/assets/favicon.svg" "image/svg+xml"]]
             (map (juxt :href :type) (map hx/attributes (filter #(= "icon" (:rel (hx/attributes %))) (hx/by-tag page :link)))))
          "the icon is an SVG, and says so")
      (let [[script :as scripts] (hx/by-tag page :script)]
        (is (= 1 (count scripts)))
        (is (= "/assets/htmx.min.js" (:src (hx/attributes script))))
        (is (true? (:defer (hx/attributes script))) "it runs when the page has been read, after its configuration")
        (is (= "" (hx/text script)) "a script that is a file and not a text in the page")))
    (testing "nothing is asked of another server"
      (is (empty? (re-seq #"(?:src|href|action)=\"(?:https?:)?//" text))))
    (testing "and the page has no code of its own, so a policy that forbids all inline code accepts it"
      (is (empty? (re-seq #"<style" text)))
      (is (empty? (re-seq #"\sstyle=" text)))
      (is (empty? (re-seq #"\son[a-z]+=" text)) "no handler written in an attribute")
      (is (empty? (re-seq #"javascript:" text))))))

(deftest htmx-is-told-to-keep-to-itself
  (let [page (tree (html/render (pages/document {:title "x"} [:main])))
        meta-tag (first (filter #(= "htmx-config" (:name (hx/attributes %))) (hx/by-tag page :meta)))
        config (json/read-value (:content (hx/attributes meta-tag)) json/keyword-keys-object-mapper)]
    (is (some? meta-tag))
    (is (= {:allowEval false :allowScriptTags false :historyCacheSize 0 :includeIndicatorStyles false :selfRequestsOnly true}
           config)
        "no eval and no script in an answer; no <style> of its own, which the policy forbids; no copy of a page of a patient's record in the storage of the browser; only this server")
    (testing "and the meta tag is before the script that reads it"
      (let [head-children (vec (filter hx/element? (hx/child-nodes (first (hx/by-tag page :head)))))
            position (fn [pred] (first (keep-indexed (fn [i el] (when (pred el) i)) head-children)))
            config-at (position #(= "htmx-config" (:name (hx/attributes %))))
            script-at (position #(= :script (hx/tag %)))]
        (is (some? config-at))
        (is (some? script-at))
        (is (< config-at script-at))))))

(deftest every-page-for-a-failure-has-its-own-title-and-the-name-of-the-product-once
  (is (= {:not-found "Page not found · Pulse"
          :forbidden "Not allowed · Pulse"
          :server-error "Something went wrong · Pulse"
          :session-ended "Your session has ended · Pulse"
          :sign-in-expired "Sign-in expired · Pulse"
          :sign-in-failed "Sign-in failed · Pulse"
          :sign-in-unavailable "Sign-in is not available · Pulse"
          :not-for-patients "For patients only · Pulse"
          :request-refused "Request refused · Pulse"}
         (into {} (for [kind (keys pages/error-kinds)]
                    [kind (hx/words (first (hx/by-tag (pages/error-page kind) :title)))])))))

;; ---------------------------------------------------------------------------
;; When something goes wrong

(def ^:private approved
  "The words of the mock-up the owner approved on 2026-10-17, for each failure: the title and the text of its card, and
  the label of its button."
  {:not-found ["We cannot find that page" "The address may be old, or mistyped." "Go to the home page"]
   :forbidden ["You cannot see this" "This page is about someone else. You can only see your own record." "Go to the home page"]
   :server-error ["Something went wrong on our side"
                  "Your information is safe and nothing was lost. If it keeps happening, tell your clinic." "Try again"]
   :session-ended ["Your session has ended" "For your safety, you are signed out after a while without activity." "Sign in again"]})

(def ^:private added
  "The words of the failures that the sign-in added (step 4 of Phase 7). No mock-up had them: they are the portal's own, in the
  order of the cards, and the review page of the errors shows them for the owner to say whether they are right. Two of them,
  \"This portal is for patients\" and \"Sign-in is not available right now\", have the words the owner accepted when the step
  was planned."
  {:sign-in-expired ["That sign-in has expired" "A sign-in has to be finished within a few minutes. Start again to sign in." "Start again"]
   :sign-in-failed ["We could not sign you in"
                    "The sign-in was cancelled or was not accepted. You can try again. If it keeps happening, tell your clinic." "Try again"]
   :sign-in-unavailable ["Sign-in is not available right now"
                         "We could not reach the service that checks who you are. Nothing was lost. Try again in a moment." "Try again"]
   :not-for-patients ["This portal is for patients"
                      "You are signed in as someone from the clinic. The portal is for patients: sign out and use the workspace instead." "Sign out"]
   :request-refused ["We could not do that"
                     "The page may be out of date, or the request did not come from this site. Go to the home page and try again."
                     "Go to the home page"]})

(def ^:private all-words (merge approved added))

(deftest the-failures-are-those-of-the-approved-mock-up-in-its-words-and-then-those-the-sign-in-added
  (is (= (concat (keys approved) (keys added)) (keys pages/error-kinds)) "the four of the mock-up first, in its order")
  (doseq [[kind [title text action]] all-words
          :let [card (pages/error-card kind)
                words (hx/words card)]]
    (testing kind
      (is (str/includes? words title))
      (is (str/includes? words text))
      (is (str/includes? words action))
      (is (= (str title text action) words) "the title, the text and the button, and nothing more"))))

(deftest each-failure-has-the-status-that-says-what-happened
  (is (= {:not-found 404 :forbidden 403 :server-error 500 :session-ended 401
          :sign-in-expired 400 :sign-in-failed 400 :sign-in-unavailable 503 :not-for-patients 403 :request-refused 403}
         (into {} (for [kind (keys pages/error-kinds)] [kind (pages/status-of kind)])))))

(deftest a-page-for-a-failure-is-a-page-a-person-can-use
  (doseq [kind (keys pages/error-kinds)
          :let [[title _ action] (all-words kind)
                text (page-html kind)
                page (tree text)]]
    (testing kind
      (is (= [] (a11y/problems page {:page? true})) "what a program can see of accessibility: a language, a title, one h1, a main, names")
      (is (= [title] (map hx/words (hx/by-tag page :h1))) "the card's title is the heading of the page")
      (is (= 1 (count (hx/by-tag page :main))))
      (is (some #(= action (hx/words %)) (hx/by-tag page :a)) "the way out is a link")
      (is (not (re-find #"(?i)exception|stack|trace|sql|password" text)) "nothing of the inside"))))

(deftest the-way-out-leads-where-it-is-told-to
  (let [hrefs (fn [kind options] (set (map (comp :href hx/attributes) (hx/by-tag (pages/error-card kind options) :a))))]
    (testing "by default, home, the same page again, the sign-in and the page that says that the person signed out"
      (is (= #{"/"} (hrefs :not-found nil)))
      (is (= #{"/"} (hrefs :forbidden nil)))
      (is (= #{"/"} (hrefs :server-error nil)))
      (is (= #{"/sign-in"} (hrefs :session-ended nil)))
      (is (= #{"/sign-in"} (hrefs :sign-in-expired nil)))
      (is (= #{"/sign-in"} (hrefs :sign-in-failed nil)))
      (is (= #{"/"} (hrefs :sign-in-unavailable nil)))
      (is (= #{"/signed-out"} (hrefs :not-for-patients nil)))
      (is (= #{"/"} (hrefs :request-refused nil))))
    (testing "and where the page says"
      (is (= #{"/readings?period=30"} (hrefs :server-error {:retry-href "/readings?period=30"})))
      (is (= #{"/readings?period=30"} (hrefs :sign-in-unavailable {:retry-href "/readings?period=30"})))
      (is (= #{"/home"} (hrefs :not-found {:home-href "/home"})))
      (is (= #{"/home"} (hrefs :forbidden {:home-href "/home"})))
      (is (= #{"/home"} (hrefs :request-refused {:home-href "/home"})))
      (is (= #{"/sign-in?return-to=%2Freadings"} (hrefs :session-ended {:sign-in-href "/sign-in?return-to=%2Freadings"})))
      (is (= #{"/sign-in?return-to=%2Freadings"} (hrefs :sign-in-expired {:sign-in-href "/sign-in?return-to=%2Freadings"})))
      (is (= #{"/sign-in?return-to=%2Freadings"} (hrefs :sign-in-failed {:sign-in-href "/sign-in?return-to=%2Freadings"})))
      (is (= #{"http://localhost:8080/logout"} (hrefs :not-for-patients {:sign-out-href "http://localhost:8080/logout"}))))
    (testing "an option of another failure is none of this one's"
      (is (= #{"/"} (hrefs :not-found {:retry-href "/x" :sign-in-href "/y" :sign-out-href "/z"})))
      (is (= #{"/sign-in"} (hrefs :sign-in-failed {:retry-href "/x" :home-href "/y" :sign-out-href "/z"}))))))

(deftest the-title-of-a-card-is-a-heading-only-when-it-is-told-to-be
  (is (= [:p] (map hx/tag (hx/select (pages/error-card :not-found) #(hx/has-class? % "font-semibold"))))
      "in a page that has a heading of its own, as the mock-up's does")
  (is (= [:h1] (map hx/tag (hx/select (pages/error-card :not-found {:heading :h1}) #(hx/has-class? % "font-semibold"))))))

(deftest a-failure-nobody-has-heard-of-is-an-error-that-says-which
  (let [e (try (pages/error-card :teapot) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :teapot (:kind (ex-data e))))
    (is (= (keys pages/error-kinds) (:kinds (ex-data e))))
    (is (= "No such kind of error: :teapot" (ex-message e))))
  (is (thrown? clojure.lang.ExceptionInfo (pages/error-page :teapot)))
  (is (thrown? clojure.lang.ExceptionInfo (pages/status-of :teapot))))

;; ---------------------------------------------------------------------------
;; The CSRF token in a page

(deftest a-page-for-a-person-gives-htmx-the-csrf-token-of-their-session
  (let [page (tree (html/render (pages/document {:title "Home" :csrf-token "tok-en_1"} [:main {:id "main"} [:h1 "Hi"]])))
        body (first (hx/by-tag page :body))]
    (is (= {:X-CSRF-Token "tok-en_1"}
           (json/read-value (:hx-headers (hx/attributes body)) json/keyword-keys-object-mapper))
        "htmx sends it as a header with every request it makes, so no fragment has to carry it")
    (is (hx/has-class? body "bg-base-200") "and the body is the body of every page"))
  (testing "a page that is for nobody in particular carries none"
    (let [body (first (hx/by-tag (tree (html/render (pages/document {:title "x"} [:main]))) :body))]
      (is (not (contains? (hx/attributes body) :hx-headers)))))
  (testing "a token that would break the attribute is escaped, and cannot add one"
    (let [text (html/render (pages/document {:title "x" :csrf-token "\" onmouseover=\"alert(1)"} [:main]))
          body (first (hx/by-tag (tree text) :body))]
      (is (= #{:class :hx-headers} (set (keys (hx/attributes body)))) "the page read back has no attribute that the token added")
      (is (= {:X-CSRF-Token "\" onmouseover=\"alert(1)"}
             (json/read-value (:hx-headers (hx/attributes body)) json/keyword-keys-object-mapper))
          "and the token is what it was"))))

(deftest a-form-carries-the-token-in-the-field-the-check-reads
  (let [field (pages/csrf-field "tok-en_1")]
    (is (= :input (hx/tag field)))
    (is (= {:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"} (hx/attributes field))))
  (testing "written as HTML, the value is an attribute whatever it holds"
    (is (str/includes? (html/render [:form (pages/csrf-field "a\"b<c")]) "value=\"a&quot;b&lt;c\""))))

;; ---------------------------------------------------------------------------
;; Around signing in and out

(deftest the-page-after-signing-out-says-so-and-offers-to-sign-in
  (let [text (html/render (pages/signed-out-page))
        page (tree text)]
    (is (= [] (a11y/problems page {:page? true})))
    (is (= ["You have signed out"] (map hx/words (hx/by-tag page :h1))))
    (is (str/includes? (hx/words page) "To see your record again, sign in."))
    (is (= [["Sign in" "/sign-in"]] (for [a (hx/by-tag page :a) :when (= "Sign in" (hx/words a))] [(hx/words a) (:href (hx/attributes a))])))
    (is (= "Signed out · Pulse" (hx/words (first (hx/by-tag page :title)))))
    (is (empty? (hx/by-tag page :form)) "nothing to post: there is no session to end")))

(deftest the-sign-out-form-is-a-post-with-the-token
  (let [form (pages/sign-out-form "tok-en_1")]
    (is (= :form (hx/tag form)))
    (is (= {:method "post" :action "/sign-out"} (hx/attributes form)) "a POST, since a link would let any page sign a person out")
    (is (= [{:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"}]
           (map hx/attributes (hx/by-tag form :input))))
    (let [[button :as buttons] (hx/by-tag form :button)]
      (is (= 1 (count buttons)))
      (is (= "submit" (:type (hx/attributes button))))
      (is (= "Sign out" (hx/words button))))))

;; ---------------------------------------------------------------------------
;; The frame of a page of a patient

(defn- frame-page
  "A page of a patient in the frame of the portal, read back from the HTML that is served."
  [& [options]]
  (tree (html/render (pages/page (merge {:title "Home" :current :home :person {:name "Ana García"} :csrf-token "tok-en_1"} options)
                                 [:h1 "Hello"]))))

(defn- nav-named [page label]
  (first (hx/select page #(and (= :nav (hx/tag %)) (= label (:aria-label (hx/attributes %)))))))

(deftest the-frame-of-a-page-of-a-patient-is-a-page-a-person-can-use
  (let [page (frame-page)]
    (is (= [] (a11y/problems page {:page? true})))
    (is (= "Home · Pulse" (hx/words (first (hx/by-tag page :title)))))
    (is (= ["Hello"] (map hx/words (hx/by-tag (first (hx/by-tag page :main)) :h1))) "what the page says is in main")))

(deftest the-navigation-is-the-pages-that-exist
  (is (= [{:id :home :href "/" :label "Home" :icon :house}
          {:id :readings :href "/readings" :label "Readings" :icon :activity}
          {:id :appointments :href "/appointments" :label "Appointments" :icon :calendar}
          {:id :record :href "/record" :label "My record" :icon :clipboard-list}
          {:id :profile :href "/profile" :label "Profile" :icon :user}]
         pages/nav)
      "the five pages of the mock-up, each added by its step of Phase 7: nothing leads to a page that is not there")
  (let [page (frame-page)
        bar (nav-named page "Main")
        dock (nav-named page "Main, on a phone")]
    (is (= [["Home" "/"] ["Readings" "/readings"] ["Appointments" "/appointments"] ["My record" "/record"] ["Profile" "/profile"]]
           (map (juxt hx/words (comp :href hx/attributes)) (hx/by-tag bar :a))))
    (is (= (map (comp :href hx/attributes) (hx/by-tag bar :a)) (map (comp :href hx/attributes) (hx/by-tag dock :a)))
        "the dock is the same navigation")
    (testing "the page it is on is marked, for a screen reader too"
      (is (= ["page" nil nil nil nil] (map (comp :aria-current hx/attributes) (hx/by-tag bar :a))))
      (is (= ["page" nil nil nil nil] (map (comp :aria-current hx/attributes) (hx/by-tag dock :a))))
      (doseq [[current index] {:readings 1 :appointments 2 :record 3 :profile 4}]
        (is (= (assoc [nil nil nil nil nil] index "page")
               (map (comp :aria-current hx/attributes) (hx/by-tag (nav-named (frame-page {:current current}) "Main") :a)))
            (name current))))
    (testing "and one that is not in it marks none"
      (is (empty? (filter (comp :aria-current hx/attributes) (hx/by-tag (nav-named (frame-page {:current :elsewhere}) "Main") :a)))))
    (is (= "/" (:href (hx/attributes (first (hx/by-tag (first (hx/by-tag page :header)) :a))))) "the mark leads home")))

(deftest the-addresses-of-the-appointments-are-where-the-routes-are
  (is (= "/appointments" pages/appointments-path))
  (is (= "/appointments#ask" pages/request-visit-path) "the form is in the page of the appointments")
  (testing "the question before an appointment is cancelled is at the address of the appointment, which is written as a segment"
    (is (= "/appointments/appt-ana-1/cancel" (pages/cancel-path "appt-ana-1")))
    (is (= "/appointments/a%20b/cancel" (pages/cancel-path "a b")) "a space is not a plus")
    (is (= "/appointments/a%2Fb%3Fc%23d/cancel" (pages/cancel-path "a/b?c#d")) "what could end a segment does not")
    (is (= "/appointments/%E2%82%AC/cancel" (pages/cancel-path "€")))
    (testing "an appointment with no id has no address of its own, so there is no button that leads to it"
      (doseq [id [nil "" "  " "
"]]
        (is (nil? (pages/cancel-path id)) (pr-str id))))))

(deftest a-person-signs-out-from-the-menu-with-a-form-that-carries-the-token
  (let [text (html/render (pages/page {:title "Home" :current :home :person {:name "Ana García"} :csrf-token "tok-en_1"} [:h1 "Hello"]))
        page (tree text)
        menu (first (hx/by-tag page :details))
        form (first (hx/by-tag page :form))
        button (first (hx/by-tag menu :button))]
    (is (= "Account menu of Ana García" (:aria-label (hx/attributes (first (hx/by-tag menu :summary))))))
    (is (= ["Profile" "Sign out"] (map hx/words (hx/by-tag menu :li))) "the menu has the profile and the way out, and nothing that leads nowhere")
    (is (= ["/profile"] (map (comp :href hx/attributes) (hx/by-tag menu :a))) "the profile is a link and signing out is not")
    (is (= "Sign out" (hx/words button)))
    (testing "a button of a POST, since a link would let any page sign a person out"
      (is (= {:type "submit" :form "account-sign-out"} (hx/attributes button)))
      (is (= 1 (count (hx/by-tag page :form))))
      (is (= {:id "account-sign-out" :method "post" :action "/sign-out"} (hx/attributes form)))
      (is (= [{:type "hidden" :name "__anti-forgery-token" :value "tok-en_1"}] (map hx/attributes (hx/by-tag form :input)))
          "with the token of the session, in the field that the check reads"))
    (testing "and htmx has the token too"
      (is (str/includes? text "X-CSRF-Token"))
      (is (= {:X-CSRF-Token "tok-en_1"}
             (json/read-value (:hx-headers (hx/attributes (first (hx/by-tag page :body)))) json/keyword-keys-object-mapper))))))

(deftest the-bar-has-no-button-for-a-theme-that-it-cannot-change
  (let [page (frame-page)]
    (is (empty? (filter #(= "Dark theme" (:aria-label (hx/attributes %))) (hx/by-tag page :button)))
        "the pages follow the preference of the browser until there is a script to change it")
    (is (= 1 (count (hx/by-tag page :button))) "the only button is the way out")))

(deftest the-name-of-a-person-is-text-whatever-it-holds
  (let [hostile (html/render (pages/page {:title "Home" :current :home :person {:name "<script>alert(1)</script>"} :csrf-token "t"} [:h1 "x"]))]
    (is (not (str/includes? hostile "<script>alert")))
    (is (str/includes? hostile "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (= 1 (count (re-seq #"<script" hostile))) "the only script is htmx, a file of the portal")))

;; ---------------------------------------------------------------------------
;; The same cards in the review

(deftest the-mock-up-of-the-review-shows-the-cards-of-the-portal
  (let [mock-up (portal/errors {})
        words (hx/words mock-up)]
    (doseq [[kind [title text action]] all-words]
      (testing kind
        (is (str/includes? words title))
        (is (str/includes? words text))
        (is (str/includes? words action))))
    (testing "each card of the portal is in the page, as it is served but for where its buttons lead"
      (let [cards (hx/select mock-up #(and (= :section (hx/tag %)) (hx/has-class? % "rounded-box")
                                           (some (fn [kind] (str/includes? (hx/words %) (first (all-words kind)))) (keys all-words))))]
        (is (= (count all-words) (count cards)))
        (is (= (map #(hx/words (pages/error-card %)) (keys pages/error-kinds))
               (map hx/words cards)))))))

;; ---------------------------------------------------------------------------
;; The stylesheet

(deftest every-class-the-pages-use-has-a-rule-in-the-stylesheet
  (let [css (slurp stylesheet :encoding "UTF-8")
        person {:name "Ana García"}
        home (fn [model] (html/render (pages/page {:title "Home" :current :home :person person :csrf-token "token-1"}
                                                  (home-page/content model portal/links))))
        ana (portal/home-model "pt-ana")
        lost (assoc ana :summary (assoc (:summary ana) :unavailable ["appointments" "vitals.glucose"]))
        markup (apply str (concat (for [kind (keys pages/error-kinds)] (page-html kind))
                                  [(html/render (pages/signed-out-page))
                                   (home ana)
                                   (home (portal/home-model "pt-noah"))
                                   (home lost)
                                   (home (assoc ana :summary nil))
                                   (home (assoc ana :recent nil))]))]
    (is (> (count (build/class-tokens markup)) 30) "the check has classes to check")
    (is (empty? (build/unknown-classes markup css))
        "run `bb design:web` when a page uses a class that the stylesheet was compiled without")
    (testing "and the check can fail"
      (is (= #{"no-such-class"} (build/unknown-classes "<p class=\"no-such-class btn\">" css))))))
