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
  of the page: a single-page application loses what loading a page gives a screen reader. `::focus` moves it to the element a selector
  finds, for the page that changed under the person's hands (an alert they acknowledged is gone from the list they were in)."
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

(rf/reg-fx
 ::go
 (fn [{route-name :name :keys [query replace?]}]
   ;; the history of reitit, which tells `on-navigate` (and so `:pulse.spa.events/navigated`) where the workspace is now: for the
   ;; page that a person is taken to by what they did (the dialog they closed). `replace?` does not add to the history, so that the
   ;; back button does not take them to a dialog that was finished.
   ((if replace? rfe/replace-state rfe/push-state) route-name {} (or query {}))))

(def ^:private controls
  "The elements that take the focus by themselves: any other needs a `tabindex` of its own, and one that is a control must not be given
  one, which would take it out of the order of the tab key."
  #{"A" "BUTTON" "INPUT" "SELECT" "TEXTAREA"})

(defn- focus!
  "Puts the focus on the first element that `selector` finds, if there is one: a heading is not a control and so is made able to take
  it."
  [selector]
  (when-let [^js element (.querySelector js/document selector)]
    (when-not (contains? controls (.-tagName element))
      (.setAttribute element "tabindex" "-1"))
    (.focus element)))

(defn- after-the-page-is-drawn
  "Calls `f` once the page that was just changed is drawn: it is drawn on the next frame, and what is new in it is there on the frame
  after that."
  [f]
  (js/requestAnimationFrame #(js/requestAnimationFrame f)))

(rf/reg-fx
 ::arrived
 (fn [{:keys [title focus?]}]
   (set! (.-title js/document) title)
   (when focus?
     (after-the-page-is-drawn #(focus! "main h1")))))

(rf/reg-fx
 ::focus
 (fn [selector]
   ;; the place of the person on a page that has just changed under them: the button they pressed is gone or free again, and the focus
   ;; that was on it is lost
   (after-the-page-is-drawn #(focus! selector))))
