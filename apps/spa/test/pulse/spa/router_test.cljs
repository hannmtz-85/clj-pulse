(ns pulse.spa.router-test
  "What the router does to the page when the person comes to another one (ADR-0029, point 5): the title of the tab is set and, when the
  person did not just open the workspace, the focus goes to the heading of the page, since a single-page application loses what loading a
  page gives a screen reader. Node has no page, so a stand-in for the document is put where the effect looks for it, and taken away after;
  the history itself (`reitit.frontend.easy`) is held to the browser, where the acceptance walks it."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.router :as router]
            [re-frame.registrar :as registrar]))

(defn- arrived!
  "Does what the effect `::router/arrived` does for `value`."
  [value]
  ((registrar/get-handler :fx ::router/arrived) value))

(defn- on-a-page
  "Calls `(f seen document)` with a stand-in for the document and for the frames of the browser in place, `seen` being an atom of
  what the page was asked: the frames, the selectors looked for, the attributes set on the heading and the focus; `heading-of` makes
  what `main h1` finds from `seen`. The frames are called at once, so that the order is the order they were asked in."
  [heading-of f]
  (let [seen (atom [])
        heading (heading-of seen)
        document #js {:title "before"
                      :querySelector (fn [selector]
                                       (swap! seen conj [:looked-for selector])
                                       heading)}
        before-document (aget js/globalThis "document")
        before-frame (aget js/globalThis "requestAnimationFrame")]
    (aset js/globalThis "document" document)
    (aset js/globalThis "requestAnimationFrame" (fn [callback] (swap! seen conj [:frame]) (callback)))
    (try
      (f seen document)
      (finally
        (aset js/globalThis "document" before-document)
        (aset js/globalThis "requestAnimationFrame" before-frame)))))

(defn- a-heading [seen]
  #js {:setAttribute (fn [attribute value] (swap! seen conj [:attribute attribute value]))
       :focus (fn [] (swap! seen conj [:focus]))})

(deftest the-title-of-the-tab-is-set-on-every-arrival
  (on-a-page (constantly nil)
             (fn [seen document]
               (arrived! {:title "Panel · Pulse" :focus? false})
               (is (= "Panel · Pulse" (.-title document)))
               (is (= [] @seen) "and the page is not asked for anything else: nothing is moved when the workspace has just been opened"))))

(deftest coming-to-another-page-moves-the-focus-to-its-heading-once-the-page-is-drawn
  (on-a-page a-heading
             (fn [seen document]
               (arrived! {:title "Page not found · Pulse" :focus? true})
               (is (= "Page not found · Pulse" (.-title document)))
               (is (= [[:frame] [:frame] [:looked-for "main h1"] [:attribute "tabindex" "-1"] [:focus]] @seen)
                   "after two frames, the heading is found, made able to take the focus, and given it"))))

(deftest a-page-with-no-heading-takes-no-focus-and-breaks-nothing
  (on-a-page (constantly nil)
             (fn [seen document]
               (arrived! {:title "Panel · Pulse" :focus? true})
               (is (= "Panel · Pulse" (.-title document)))
               (is (= [[:frame] [:frame] [:looked-for "main h1"]] @seen)))))

(deftest the-effects-of-the-history-are-registered-for-the-events-that-ask-for-them
  (testing "the effects the events name exist"
    (doseq [effect [::router/start ::router/replace ::router/arrived]]
      (is (some? (registrar/get-handler :fx effect)) (str effect)))))
