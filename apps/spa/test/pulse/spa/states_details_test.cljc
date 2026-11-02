(ns pulse.spa.states-details-test
  "What the mutation analysis of Phase 8, step 9 found that the inventory of the states (`states-test`) did not pin in the pages of the start: the
  words of the card of a sign-in that failed, of a wait that does not end and of a page that cannot be drawn, each in the element that
  carries them (an assertion on the text of the whole page is satisfied by the other words that contain it: the message of the card says
  \"Try again in a moment\", which made the button's words look as if they were there), and what a button is by its classes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.start :as start]
            [pulse.ui.test.hiccup :as hx]))

(defn- control
  "The link or the button of `page` whose words are exactly `text`, or nil."
  [page text]
  (first (filter #(= text (hx/words %)) (concat (hx/by-tag page :a) (hx/by-tag page :button)))))

(defn- paragraph
  "The element of `page` that has exactly the words `text`, other than a control, or nil."
  [page text]
  (first (filter #(= text (hx/words %)) (concat (hx/by-tag page :p) (hx/select page #(and (= :div (hx/tag %)) (hx/has-class? % "text-muted")))))))

(defn- status-words
  "What the regions that a screen reader hears when they change say."
  [page]
  (map hx/words (hx/select page #(= "status" (:role (hx/attributes %))))))

;; ---------------------------------------------------------------------------
;; A sign-in that could not be made

(deftest the-card-of-a-sign-in-that-failed-says-what-happened-and-has-a-button-that-tries-again
  (let [card (start/failed-card {:retry (fn [])})]
    (is (some? (paragraph card "The sign-in service did not answer. Try again in a moment.")) "the words are in an element of their own")
    (is (= ["We could not sign you in"] (map hx/words (hx/by-tag card :h1))))
    (let [button (control card "Try again")]
      (is (= :button (hx/tag button)))
      (is (hx/has-class? button "btn-primary") "it is the one thing to do")
      (is (fn? (:on-click (hx/attributes button)))))))

(deftest with-nothing-to-call-the-card-of-a-sign-in-that-failed-has-no-button-and-the-same-words
  (let [card (start/failed-card {})]
    (is (nil? (control card "Try again")) "the words of the message are not a button")
    (is (some? (paragraph card "The sign-in service did not answer. Try again in a moment.")))
    (is (empty? (hx/by-tag card :button)))))

;; ---------------------------------------------------------------------------
;; A wait that does not end

(deftest a-wait-that-does-not-end-says-so-to-the-eye-and-to-a-screen-reader-in-the-region-that-said-it-was-waiting
  (let [slow (start/starting-card {:slow? true :handlers {:retry (fn [])}})
        waiting (start/starting-card {})]
    (testing "the screen reader is told in the region of the spinner, which changes its words"
      (is (= ["Signing in. This is taking longer than usual."] (status-words slow)))
      (is (= ["Signing in"] (status-words waiting))))
    (testing "the eye is told in a paragraph of its own"
      (is (some? (paragraph slow "This is taking longer than usual. The sign-in service has not answered yet.")))
      (is (nil? (paragraph waiting "This is taking longer than usual. The sign-in service has not answered yet."))))
    (testing "and the way to try again is a button that is not the main thing to do, and only when there is something to call"
      (let [button (control slow "Try again")]
        (is (= :button (hx/tag button)))
        (is (hx/has-class? button "btn-outline"))
        (is (fn? (:on-click (hx/attributes button)))))
      (is (nil? (control waiting "Try again")))
      (is (nil? (control (start/starting-card {:slow? true}) "Try again"))))))

;; ---------------------------------------------------------------------------
;; A page that cannot be drawn

(deftest the-card-of-a-page-that-cannot-be-drawn-says-what-to-do-and-blames-nobody
  (let [card (start/crashed-card {:handlers {:reload (fn []) :panel (fn []) :sign-out (fn [])}})]
    (is (some? (paragraph card "The workspace could not show this page. The problem is on our side, not yours. Reload it to try again.")))
    (is (= ["Something went wrong"] (map hx/words (hx/by-tag card :h1))))
    (testing "the ways out, as buttons: reloading is the main thing to do, the others are not, and all are large"
      (let [reload (control card "Reload the page")
            panel (control card "Go to the panel")
            sign-out (control card "Sign out")]
        (is (every? #(= :button (hx/tag %)) [reload panel sign-out]))
        (is (hx/has-class? reload "btn-primary"))
        (is (hx/has-class? panel "btn-outline"))
        (is (hx/has-class? sign-out "btn-outline"))
        (is (every? #(hx/has-class? % "btn-lg") [reload panel sign-out]))
        (is (every? #(fn? (:on-click (hx/attributes %))) [reload panel sign-out]))))
    (testing "two or three large buttons wrap instead of leaving a phone's screen"
      (is (some #(and (hx/has-class? % "flex-wrap") (hx/has-class? % "justify-center") (hx/has-class? % "gap-2"))
                (hx/by-tag card :div))))))

(deftest a-control-of-a-page-that-cannot-be-drawn-is-there-only-when-there-is-something-to-call
  (doseq [[handler text] {:reload "Reload the page" :panel "Go to the panel" :sign-out "Sign out"}]
    (testing text
      (is (some? (control (start/crashed-card {:handlers {handler (fn [])}}) text)))
      (is (every? #(nil? (control (start/crashed-card {:handlers {handler (fn [])}}) %))
                  (remove #{text} ["Reload the page" "Go to the panel" "Sign out"]))
          "and no other is there for it")))
  (testing "with nothing to call there is no row of buttons at all, and the words are still there"
    (let [card (start/crashed-card {})]
      (is (empty? (hx/by-tag card :button)))
      (is (str/includes? (hx/words card) "The workspace could not show this page.")))))
