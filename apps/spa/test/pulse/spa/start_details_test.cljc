(ns pulse.spa.start-details-test
  "What the mutation analysis of Phase 8, step 4 found that `pages-test` did not pin in the pages of those the workspace is not for, of
  those who signed out and of a sign-in the api did not accept: that a control with nothing to lead to is not there as *any* element (a
  button with no address is still a button), and the classes that carry what a control is on a phone (its size, its weight, that two
  large buttons wrap instead of leaving the screen)."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.start :as start]
            [pulse.ui.test.hiccup :as hx]))

(defn- control
  "The link or the button of `page` whose words are `text`, or nil."
  [page text]
  (first (filter #(= text (hx/words %)) (concat (hx/by-tag page :a) (hx/by-tag page :button)))))

(deftest a-control-with-nothing-to-lead-to-is-not-there-as-any-element
  (testing "a link with no address would be a button that does nothing"
    (is (nil? (control (start/refused {}) "Go to the patient portal")))
    (is (nil? (control (start/refused {:handlers {:sign-out (fn [])}}) "Go to the patient portal")))
    (is (nil? (control (start/signed-out {}) "Sign in")))
    (is (nil? (control (start/signed-out {:links {}}) "Sign in")))
    (is (nil? (control (start/unauthorized {}) "Sign in again")))
    (is (nil? (control (start/unauthorized {:links {}}) "Sign in again"))))
  (testing "a button with no function would be a button that does nothing"
    (is (nil? (control (start/refused {}) "Sign out")))
    (is (nil? (control (start/refused {:links {:portal "http://localhost:3300"}}) "Sign out"))))
  (testing "and they are there when they have something to lead to"
    (is (some? (control (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}}) "Go to the patient portal")))
    (is (some? (control (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}}) "Sign out")))
    (is (some? (control (start/signed-out {:links {:sign-in "/"}}) "Sign in")))
    (is (some? (control (start/unauthorized {:links {:sign-in "/"}}) "Sign in again")))))

(deftest what-a-control-is-is-in-its-classes
  (let [page (start/refused {:links {:portal "http://localhost:3300"} :handlers {:sign-out (fn [])}})
        portal (control page "Go to the patient portal")
        sign-out (control page "Sign out")]
    (testing "the way to the portal is the main thing to do, and the way out is the other"
      (is (hx/has-class? portal "btn-primary"))
      (is (not (hx/has-class? portal "btn-outline")))
      (is (hx/has-class? sign-out "btn-outline")))
    (testing "both are large, which is a target of 44 px or more for a finger"
      (is (hx/has-class? portal "btn-lg"))
      (is (hx/has-class? sign-out "btn-lg")))
    (testing "and two large buttons wrap instead of leaving a phone's screen"
      (is (some #(and (hx/has-class? % "flex-wrap") (hx/has-class? % "justify-center") (hx/has-class? % "gap-2"))
                (hx/by-tag page :div))))))

(deftest the-ways-back-in-are-large-and-the-main-thing-to-do
  (doseq [[label button] {"after signing out" (control (start/signed-out {:links {:sign-in "/"}}) "Sign in")
                          "after a sign-in that was not accepted" (control (start/unauthorized {:links {:sign-in "/"}}) "Sign in again")}]
    (testing label
      (is (hx/has-class? button "btn-primary"))
      (is (hx/has-class? button "btn-lg"))
      (is (= "/" (:href (hx/attributes button)))))))

(deftest one-control-is-enough-to-make-the-actions-and-the-other-is-not-needed
  (testing "only the way to the portal"
    (let [page (start/refused {:links {:portal "http://localhost:3300"}})]
      (is (some? (control page "Go to the patient portal")))
      (is (nil? (control page "Sign out")))))
  (testing "only the way out"
    (let [page (start/refused {:handlers {:sign-out (fn [])}})]
      (is (nil? (control page "Go to the patient portal")))
      (is (some? (control page "Sign out"))))))
