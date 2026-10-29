(ns pulse.spa.router
  "The history of the browser, as effects of re-frame (ADR-0029, point 5): the address is where the workspace is, a click on a link
  that stays in the workspace changes it without loading the page again, and the back button of the browser goes where the person
  was. What is decided without a browser is `pulse.spa.route`; this is the part only a browser can run, and it holds no decision.

  `::start` begins to listen (reitit's history, with the address as a path and not a fragment: the server of the release answers the
  page for any path) and tells `:pulse.spa.events/navigated` where the workspace is, now and every time it changes. `::replace`
  changes the address of the page without adding to the history, for the text of a search typed letter by letter: it is the browser's
  `replaceState` and nothing else, so that it tells nobody (the state already has what was typed, and an echo of every letter that came
  back a moment later could put an older text over a newer one). `::arrived` sets
  the title of the tab and, when the person came to another page and did not just open the workspace, moves the focus to the heading
  of the page: a single-page application loses what loading a page gives a screen reader."
  (:require [pulse.spa.route :as route]
            [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]))

(rf/reg-fx
 ::start
 (fn [_]
   (rfe/start! route/router
               (fn [match _history] (rf/dispatch [:pulse.spa.events/navigated (route/from-match match)]))
               {:use-fragment false})))

(rf/reg-fx
 ::replace
 (fn [path]
   (.replaceState js/history nil "" path)))

(defn- focus-heading!
  "Puts the focus on the heading of the page, which is not a control and so needs a `tabindex` of its own to take it."
  []
  (when-let [^js heading (.querySelector js/document "main h1")]
    (.setAttribute heading "tabindex" "-1")
    (.focus heading)))

(rf/reg-fx
 ::arrived
 (fn [{:keys [title focus?]}]
   (set! (.-title js/document) title)
   (when focus?
     ;; the page is drawn on the next frame, and the heading of the new one is there on the frame after it
     (js/requestAnimationFrame #(js/requestAnimationFrame focus-heading!)))))
