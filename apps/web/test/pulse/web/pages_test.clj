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
            [pulse.web.pages :as pages]))

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
          :session-ended "Your session has ended · Pulse"}
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

(deftest the-failures-are-those-of-the-approved-mock-up-in-its-words
  (is (= (keys approved) (keys pages/error-kinds)) "four, in the order of the mock-up")
  (doseq [[kind [title text action]] approved
          :let [card (pages/error-card kind)
                words (hx/words card)]]
    (testing kind
      (is (str/includes? words title))
      (is (str/includes? words text))
      (is (str/includes? words action))
      (is (= (str title text action) words) "the title, the text and the button, and nothing more"))))

(deftest each-failure-has-the-status-that-says-what-happened
  (is (= {:not-found 404 :forbidden 403 :server-error 500 :session-ended 401}
         (into {} (for [kind (keys pages/error-kinds)] [kind (pages/status-of kind)])))))

(deftest a-page-for-a-failure-is-a-page-a-person-can-use
  (doseq [kind (keys pages/error-kinds)
          :let [[title _ action] (approved kind)
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
    (testing "by default, home, the same page again and the sign-in"
      (is (= #{"/"} (hrefs :not-found nil)))
      (is (= #{"/"} (hrefs :forbidden nil)))
      (is (= #{"/"} (hrefs :server-error nil)))
      (is (= #{"/sign-in"} (hrefs :session-ended nil))))
    (testing "and where the page says"
      (is (= #{"/readings?period=30"} (hrefs :server-error {:retry-href "/readings?period=30"})))
      (is (= #{"/home"} (hrefs :not-found {:home-href "/home"})))
      (is (= #{"/home"} (hrefs :forbidden {:home-href "/home"})))
      (is (= #{"/sign-in?return-to=%2Freadings"} (hrefs :session-ended {:sign-in-href "/sign-in?return-to=%2Freadings"}))))
    (testing "an option of another failure is none of this one's"
      (is (= #{"/"} (hrefs :not-found {:retry-href "/x" :sign-in-href "/y"}))))))

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
;; The same cards in the review

(deftest the-mock-up-of-the-review-shows-the-cards-of-the-portal
  (let [mock-up (portal/errors {})
        words (hx/words mock-up)]
    (doseq [[kind [title text action]] approved]
      (testing kind
        (is (str/includes? words title))
        (is (str/includes? words text))
        (is (str/includes? words action))))
    (testing "each card of the portal is in the page, as it is served but for where its buttons lead"
      (let [cards (hx/select mock-up #(and (= :section (hx/tag %)) (hx/has-class? % "rounded-box")
                                           (some (fn [kind] (str/includes? (hx/words %) (first (approved kind)))) (keys approved))))]
        (is (= (count approved) (count cards)))
        (is (= (map #(hx/words (pages/error-card %)) (keys pages/error-kinds))
               (map hx/words cards)))))))

;; ---------------------------------------------------------------------------
;; The stylesheet

(deftest every-class-the-pages-use-has-a-rule-in-the-stylesheet
  (let [css (slurp stylesheet :encoding "UTF-8")
        markup (apply str (for [kind (keys pages/error-kinds)] (page-html kind)))]
    (is (> (count (build/class-tokens markup)) 30) "the check has classes to check")
    (is (empty? (build/unknown-classes markup css))
        "run `bb design:web` when a page uses a class that the stylesheet was compiled without")
    (testing "and the check can fail"
      (is (= #{"no-such-class"} (build/unknown-classes "<p class=\"no-such-class btn\">" css))))))
