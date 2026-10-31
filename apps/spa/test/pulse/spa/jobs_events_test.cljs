(ns pulse.spa.jobs-events-test
  "The events of the page of the jobs and of the route that leads to it (ADR-0029, points 3 and 6), as the state they leave and the effects
  they ask for. The effects that reach out of the page (the api, the history, the clock, the focus) are replaced by ones that write down
  what they were asked, so nothing leaves Node. The jobs are read when the person comes to the page, once, and nothing repeats it; a
  practitioner who writes the address is asked nothing. Each test runs in `day8.re-frame/test`'s own re-frame state, which is thrown away
  after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.jobs-events :as jobs-events]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.jobs-fixtures :as fx]
            [pulse.spa.watch-events :as watch-events]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]))

(defn- quietly
  "Runs `f` with re-frame's warning about a handler registered twice put aside: here it is meant."
  [f]
  (let [before (loggers/get-loggers)]
    (loggers/set-loggers! {:warn (fn [& args] (when-not (str/starts-with? (str (first args)) "re-frame: overwriting") (apply (:warn before) args)))})
    (try (f) (finally (loggers/set-loggers! before)))))

(def ^:private asked
  "What the effects were asked, in order: `[effect value]`."
  (atom []))

(defn- note-the-effects!
  "Replaces the effects that reach out of the page by ones that write down what they were asked, and the clock by the moment of the
  fixtures."
  []
  (reset! asked [])
  (quietly
   (fn []
     (doseq [effect [::auth/init ::auth/logout ::fetch/request ::fetch/cancel ::router/start ::router/replace ::router/arrived ::router/focus
                     ::watch-events/listen :dispatch-later]]
       (rf/reg-fx effect (fn [value] (swap! asked conj [effect value]))))
     (rf/reg-cofx ::jobs-events/now (fn [cofx _] (assoc cofx :now fx/now))))))

(defn- state [] @db/app-db)

(defn- calls [] (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- cancelled [] (vec (for [[effect value] @asked :when (= ::fetch/cancel effect)] value)))

(def ^:private rivera {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera" "realm_access" {"roles" ["practitioner"]}})
(def ^:private sam {"sub" "s1" "preferred_username" "admin@pulse.test" "name" "Sam Admin" "realm_access" {"roles" ["clinic-admin"]}})

(def ^:private jobs-route {:name :jobs :params {}})

(defn- on-the-page!
  "Signs in as the administrator and comes to the jobs, which begin to load; what was asked since is in `@asked`."
  ([] (on-the-page! sam))
  ([claims]
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/signed-in claims])
   (reset! asked [])
   (rf/dispatch [::events/navigated jobs-route])))

(defn- page [] (subs/jobs (state) nil))

;; ---------------------------------------------------------------------------

(deftest coming-to-the-jobs-reads-them-once-and-sets-the-title
  (rf-test/run-test-sync
   (on-the-page!)
   (is (= [jobs/request] (calls)))
   (is (= {:now fx/now :list {:state :loading}} (page)))
   (is (= [[::router/arrived {:title "Jobs · Pulse" :focus? false}]] (filter #(= ::router/arrived (first %)) @asked)))))

(deftest the-answer-is-the-list
  (rf-test/run-test-sync
   (on-the-page!)
   (rf/dispatch (conj (:on-success jobs/request) {:status 200 :body fx/body :request-id "r"}))
   (is (= (fx/settled) (page)))))

(deftest an-answer-that-is-not-what-the-api-answers-is-a-failure-and-not-an-empty-list
  (rf-test/run-test-sync
   (on-the-page!)
   (rf/dispatch (conj (:on-success jobs/request) {:status 200 :body {:oops true} :request-id "r"}))
   (is (= {:state :failed :kind :unexpected} (:list (page))))))

(deftest a-failure-keeps-its-kind-and-can-be-tried-again
  (doseq [kind [:server :timeout :unreachable :forbidden]]
    (rf-test/run-test-sync
     (on-the-page!)
     (rf/dispatch (conj (:on-failure jobs/request) {:kind kind :request-id "r"}))
     (is (= {:state :failed :kind kind} (:list (page))) (name kind))
     (reset! asked [])
     (rf/dispatch [:pulse.spa.jobs/retry])
     (is (= [jobs/request] (calls)) "asked again")
     (is (= {:state :loading} (:list (page)))))))

(deftest there-is-nothing-to-try-again-while-it-is-loading-or-when-it-loaded
  (rf-test/run-test-sync
   (on-the-page!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.jobs/retry])
   (is (= [] (calls)) "while it is loading")
   (rf/dispatch (conj (:on-success jobs/request) {:status 200 :body fx/body :request-id "r"}))
   (rf/dispatch [:pulse.spa.jobs/retry])
   (is (= [] (calls)) "once it has loaded")
   (is (= (fx/settled) (page)))))

(deftest leaving-stops-the-call-and-forgets-the-page
  (rf-test/run-test-sync
   (on-the-page!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (nil? (page)))
   (is (= [{:id [:jobs]}] (cancelled)))))

(deftest an-answer-after-the-page-was-left-is-nothing
  (rf-test/run-test-sync
   (on-the-page!)
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (rf/dispatch (conj (:on-success jobs/request) {:status 200 :body fx/body :request-id "r"}))
   (rf/dispatch (conj (:on-failure jobs/request) {:kind :server :request-id "r"}))
   (is (nil? (page)) "the page is not made again by an answer that comes late")))

(deftest a-practitioner-who-writes-the-address-asks-the-api-nothing
  (rf-test/run-test-sync
   (on-the-page! rivera)
   (is (= [] (calls)))
   (is (nil? (page)))))

(deftest coming-back-reads-them-again
  (rf-test/run-test-sync
   (on-the-page!)
   (rf/dispatch (conj (:on-success jobs/request) {:status 200 :body fx/body :request-id "r"}))
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (reset! asked [])
   (rf/dispatch [::events/navigated jobs-route])
   (is (= [jobs/request] (calls)))
   (is (= {:now fx/now :list {:state :loading}} (page)) "and shows what is being read, not what was read before")))
