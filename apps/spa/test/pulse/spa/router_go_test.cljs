(ns pulse.spa.router-go-test
  "What the router does when the workspace takes the person to a page by what they did (ADR-0029, point 5): the dialog they closed, or
  finished, leaves them on the list. The history itself (`reitit.frontend.easy`) is held to the browser, where the acceptance walks it; here
  it is replaced by a stand-in that writes down what it was asked, and the title that a route of a page gives the tab is the one of the
  event that tells the workspace where it is."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.router :as router]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]
            [re-frame.registrar :as registrar]
            [reitit.frontend.easy :as rfe]))

(defn- went!
  "Does what the effect `::router/go` does for `value`, with the history of reitit replaced: what it was asked is what is given."
  [value]
  (let [asked (atom [])]
    (with-redefs [rfe/push-state (fn [& args] (swap! asked conj (into [:push] args)))
                  rfe/replace-state (fn [& args] (swap! asked conj (into [:replace] args)))]
      ((registrar/get-handler :fx ::router/go) value))
    @asked))

(deftest a-page-is-reached-by-adding-to-the-history-unless-it-is-said-to-replace
  (is (= [[:push :appointments {} {}]] (went! {:name :appointments})))
  (is (= [[:push :appointments {} {:show "requests"}]] (went! {:name :appointments :query {:show "requests"}})))
  (is (= [[:replace :appointments {} {}]] (went! {:name :appointments :replace? true})))
  (is (= [[:replace :appointments {} {:show "booked"}]] (went! {:name :appointments :query {:show "booked"} :replace? true})))
  (testing "a dialog that was finished leaves no entry in the history: the back button does not take the person to it"
    (is (= :replace (ffirst (went! {:name :appointments :replace? true}))))
    (is (= :push (ffirst (went! {:name :appointments}))))))

;; ---------------------------------------------------------------------------
;; The title of a route of a page

(def ^:private asked (atom []))

(defn- note-the-effects! []
  (reset! asked [])
  (let [before (loggers/get-loggers)]
    (loggers/set-loggers! {:warn (fn [& _] nil)})
    (try
      (doseq [effect [::router/arrived ::router/focus ::router/start ::fetch/request ::fetch/cancel :dispatch-later]]
        (rf/reg-fx effect (fn [value] (swap! asked conj [effect value]))))
      (finally (loggers/set-loggers! before)))))

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})

(defn- titles [] (vec (for [[effect value] @asked :when (= ::router/arrived effect)] value)))

(deftest a-route-of-the-same-page-sets-the-title-and-moves-no-focus
  (rf-test/run-test-sync
   (note-the-effects!)
   (reset! db/app-db {:status :signed-in :person rivera :route {:name :appointments :params {:show :all}}})
   (rf/dispatch [::events/navigated {:name :appointment-confirm :params {:show :all :id "appt-sofia-1" :action :confirm}}])
   (is (= [{:title "Confirm an appointment · Pulse" :focus? false}] (titles)) "the dialog is the title, and the heading is not given the focus")
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :appointments :params {:show :all}}])
   (is (= [{:title "Appointments · Pulse" :focus? false}] (titles)) "and closing it gives the title of the list back")))

(deftest a-change-of-the-narrowing-of-the-same-route-does-not-touch-the-title
  (rf-test/run-test-sync
   (note-the-effects!)
   (reset! db/app-db {:status :signed-in :person rivera :route {:name :appointments :params {:show :all}}})
   (rf/dispatch [::events/navigated {:name :appointments :params {:show :requests}}])
   (is (= [] (titles)))))

(deftest another-page-sets-the-title-once-and-moves-the-focus-to-the-heading
  (rf-test/run-test-sync
   (note-the-effects!)
   (reset! db/app-db {:status :signed-in :person rivera :route {:name :appointments :params {:show :all}}})
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (= [{:title "Panel · Pulse" :focus? true}] (titles)))))
