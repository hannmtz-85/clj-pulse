(ns pulse.spa.panel-events-test
  "The events of the panel and of the route (ADR-0029, points 3, 5 and 6), as the state they leave and the effects they ask for. The effects
  that reach out of the page (the api, the history, the clock) are replaced by ones that write down what they were asked, so nothing leaves
  Node; and some tests answer the calls the panel makes with the answers of the fixtures, as the api would, to see the panel whole. Each test
  runs in `day8.re-frame/test`'s own re-frame state, which is thrown away after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.panel :as panel]
            [pulse.spa.panel-events :as panel-events]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.panel-fixtures :as fx]
            [pulse.view.format :as fmt]
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
     (doseq [effect [::auth/init ::auth/logout ::fetch/request ::fetch/cancel ::router/start ::router/replace ::router/arrived :dispatch-later]]
       (rf/reg-fx effect (fn [value] (swap! asked conj [effect value]))))
     (rf/reg-cofx ::panel-events/now (fn [cofx _] (assoc cofx :now fx/now))))))

(defn- state [] @db/app-db)

(defn- requests
  "The calls the panel made, as the tasks they are for, in order."
  []
  (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] (second (:id spec)))))

(defn- cancelled
  "The tasks whose calls were stopped."
  []
  (set (for [[effect value] @asked :when (= ::fetch/cancel effect)] (second (:id value)))))

(defn- deadlines [] (vec (for [[effect value] @asked :when (= :dispatch-later effect)] value)))

(defn- signed-in! []
  (rf/dispatch [::events/start])
  (rf/dispatch [::events/signed-in {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
                                    "realm_access" {"roles" ["practitioner"]}}]))

(def ^:private here {:name :panel :params {:show :all :q ""}})

(defn- signed-in-with-quiet-effects!
  "Signs in, with the effects noted and what the sign-in asked forgotten."
  []
  (note-the-effects!)
  (signed-in!)
  (reset! asked []))

(defn- on-the-panel!
  "Signs in and comes to the panel, which begins to load (the load of the generation 1); what was asked since is in `@asked`."
  []
  (signed-in-with-quiet-effects!)
  (rf/dispatch [::events/navigated here]))

(defn- body-of [task]
  (cond
    (= :list task) fx/patients
    (= :active task) fx/active-alerts
    (= :acknowledged task) fx/acknowledged-alerts
    (= :requests task) fx/requests
    (= :summary (first task)) (get fx/summaries (second task))
    (= :series (first task)) (get fx/series-by-patient (second task))))

(defn- answer!
  "The api answers the call `spec`, as the effect would tell the page: the event it named, with the answer added."
  [spec]
  (rf/dispatch (conj (:on-success spec) {:status 200 :body (body-of (second (:id spec))) :request-id "r"})))

(defn- calls
  "The calls made so far, as their specs."
  []
  (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- answer-everything!
  "Answers every call the panel makes, and the calls that answering makes, until none is left; `answer` is a function of a call that
  does it (the default is the answer of the api). It gives the number of calls answered."
  ([] (answer-everything! answer!))
  ([answer]
   (let [answered (atom #{})]
     (loop [guard 0]
       (let [pending (remove #(contains? @answered (:id %)) (calls))]
         (when (and (seq pending) (< guard 40))
           (doseq [spec pending]
             (swap! answered conj (:id spec))
             (answer spec))
           (recur (inc guard)))))
     (count @answered))))

;; ---------------------------------------------------------------------------
;; The route

(deftest the-first-page-the-person-comes-to-starts-it-and-sets-the-title-without-moving-the-focus
  (rf-test/run-test-sync
   (signed-in-with-quiet-effects!)
   (rf/dispatch [::events/navigated here])
   (is (= here (subs/route (state) nil)))
   (is (= [:list :active :acknowledged :requests] (requests)) "the panel begins to load")
   (is (= [[::router/arrived {:title "Panel · Pulse" :focus? false}]] (filter #(= ::router/arrived (first %)) @asked))
       "the title is set, and the focus is not taken: the person has not come from another page")))

(deftest a-change-of-the-filter-or-the-search-is-the-same-page-and-asks-for-nothing
  (rf-test/run-test-sync
   (on-the-panel!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :alerts :q ""}}])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :alerts :q "ana"}}])
   (is (= {:name :panel :params {:show :alerts :q "ana"}} (subs/route (state) nil)))
   (is (= [] @asked) "no call, no title, no focus: the page is the same")
   (is (some? (:panel (state))) "and the panel is not touched")))

(deftest coming-to-another-page-stops-the-panel-moves-the-focus-and-sets-the-title
  (rf-test/run-test-sync
   (on-the-panel!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :not-found :params {}}])
   (is (nil? (:panel (state))) "the panel is left")
   (is (= #{:list :active :acknowledged :requests} (cancelled)) "and its calls are stopped")
   (is (= [[::router/arrived {:title "Page not found · Pulse" :focus? true}]] (filter #(= ::router/arrived (first %)) @asked)))))

(deftest a-page-the-person-may-not-open-starts-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (swap! db/app-db assoc :status :signed-in :person {:id "a1" :name "Ana" :roles #{:patient}})
   (rf/dispatch [::events/navigated here])
   (is (= here (subs/route (state) nil)))
   (is (= [] (requests)) "nothing is asked of the api for them")
   (is (nil? (:panel (state))))))

;; ---------------------------------------------------------------------------
;; Loading

(deftest loading-the-panel-asks-for-the-list-the-alerts-and-the-requests-and-sets-the-deadline
  (rf-test/run-test-sync
   (on-the-panel!)
   (is (= [:list :active :acknowledged :requests] (requests)))
   (is (= [{:ms 10000 :dispatch [:pulse.spa.panel/deadline 1]}] (deadlines)) "one deadline, for this load")
   (is (= fx/now (get-in (state) [:panel :now])) "the moment it began")
   (is (= {:state :loading} (get-in (state) [:panel :list])))
   (is (= 1 (:panel-generation (state))))
   (testing "the calls are the ones the model made, with the generation in the events that hear them"
     (let [spec (first (calls))]
       (is (= [:panel :list] (:id spec)))
       (is (= [:pulse.spa.panel/succeeded 1 :list] (:on-success spec)))))
   (testing "no token, nothing of the person, in what was asked"
     (is (not (re-find #"eyJ" (pr-str @asked)))))))

(deftest the-real-clock-gives-an-instant-of-the-api
  (rf-test/run-test-sync
   (reset! asked [])
   (quietly (fn []
              (rf/reg-fx ::fetch/request (fn [spec] (swap! asked conj [::fetch/request spec])))
              (rf/reg-fx :dispatch-later (fn [_] nil))))
   (rf/dispatch [:pulse.spa.panel/load here])
   (let [now (get-in (state) [:panel :now])]
     (is (fmt/instant? now) now)
     (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" now) "to the second, in UTC, as the api reads it")
     (is (< (js/Math.abs (- (fmt/millis now) (js/Date.now))) 2000)))))

(deftest the-answers-make-the-panel-a-few-calls-at-a-time
  (rf-test/run-test-sync
   (on-the-panel!)
   (let [list-call (first (calls))]
     (reset! asked [])
     (answer! list-call))
   (is (= [[:summary "pt-liam"] [:summary "pt-ana"] [:summary "pt-omar"]] (requests)) "three places were free: the alerts and the requests are on their way")
   (is (nil? (get-in (state) [:panel :order])) "and the rows are in the order of the list while it loads")
   (is (= 6 (count (get-in (state) [:panel :inflight]))))))

(deftest a-whole-panel-is-answered-and-is-the-panel-of-the-fixtures
  (rf-test/run-test-sync
   (on-the-panel!)
   (is (= 13 (answer-everything!)) "the list, the alerts and the requests, five summaries and four series")
   (is (= (fx/settled) (:panel (state))))
   (is (= ["pt-ana" "pt-sofia" "pt-liam" "pt-omar" "pt-noah"] (get-in (state) [:panel :order])))))

(deftest an-answer-of-a-load-that-was-left-is-not-heard
  (rf-test/run-test-sync
   (on-the-panel!)
   (let [old (first (calls))]
     (rf/dispatch [:pulse.spa.panel/leave])
     (rf/dispatch [:pulse.spa.panel/load here])
     (let [before (:panel (state))]
       (answer! old)
       (is (= before (:panel (state))) "the answer of the first load is not the answer of the second")
       (is (= 3 (:panel-generation (state))) "the first load was 1, leaving it made it 2, and the one that is open is 3")
       (rf/dispatch [:pulse.spa.panel/failed 1 :list {:kind :server}])
       (is (= before (:panel (state))))))))

(deftest an-answer-when-there-is-no-panel-is-not-heard
  (rf-test/run-test-sync
   (signed-in-with-quiet-effects!)
   (rf/dispatch [:pulse.spa.panel/succeeded 0 :list {:status 200 :body fx/patients}])
   (is (nil? (:panel (state))))
   (is (= [] @asked))))

(deftest a-failure-is-a-part-that-failed-and-the-others-go-on
  (rf-test/run-test-sync
   (on-the-panel!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/failed 1 :active {:kind :timeout :status nil :request-id "r"}])
   (is (= {:state :failed :kind :timeout} (get-in (state) [:panel :inbox :active])))
   (is (= {:state :loading} (get-in (state) [:panel :list])))
   (is (= [] (requests)) "nothing waits: the list is not back")
   (rf/dispatch [:pulse.spa.panel/succeeded 1 :list {:status 200 :body fx/patients}])
   (is (= [[:summary "pt-liam"] [:summary "pt-ana"] [:summary "pt-omar"] [:summary "pt-noah"]] (requests))
       "and the place of the one that failed is used for the next")))

(deftest a-list-that-could-not-be-loaded-leaves-the-panel-settled-and-with-nothing
  (rf-test/run-test-sync
   (on-the-panel!)
   (rf/dispatch [:pulse.spa.panel/failed 1 :list {:kind :unreachable}])
   (is (= {:state :failed :kind :unreachable} (get-in (state) [:panel :list])))
   (is (= {} (get-in (state) [:panel :rows])))
   (testing "the alerts and requests, which do not need the list, come all the same, and then it settles"
     (doseq [task [:active :acknowledged :requests]]
       (rf/dispatch [:pulse.spa.panel/succeeded 1 task {:status 200 :body (body-of task)}]))
     (is (panel/settled? (:panel (state))))
     (is (= [] (get-in (state) [:panel :order]))))))

;; ---------------------------------------------------------------------------
;; The deadline

(deftest at-the-deadline-what-is-not-back-has-failed-and-its-calls-are-stopped
  (rf-test/run-test-sync
   (on-the-panel!)
   (answer! (first (calls)))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/deadline 1])
   (is (panel/settled? (:panel (state))))
   (is (= {:state :failed :kind :timeout} (get-in (state) [:panel :inbox :active])))
   (is (every? #(= {:state :failed :kind :timeout} (:summary %)) (vals (get-in (state) [:panel :rows]))))
   (is (= #{:active :acknowledged :requests [:summary "pt-liam"] [:summary "pt-ana"] [:summary "pt-omar"]} (cancelled))
       "the six that were on their way")
   (is (nil? (:cancelled (:panel (state)))) "and the list of them is not kept")
   (is (= [] (requests)) "nothing more is asked")
   (is (some? (get-in (state) [:panel :order])) "the rows are put in order: it has settled")))

(deftest a-deadline-of-another-load-or-of-a-panel-that-has-settled-does-nothing
  (rf-test/run-test-sync
   (on-the-panel!)
   (let [before (:panel (state))]
     (reset! asked [])
     (rf/dispatch [:pulse.spa.panel/deadline 0])
     (rf/dispatch [:pulse.spa.panel/deadline 2])
     (is (= before (:panel (state))) "the load it is for is not the one open")
     (is (= #{} (cancelled)))))
  (rf-test/run-test-sync
   (on-the-panel!)
   (answer-everything!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/deadline 1])
   (is (= (fx/settled) (:panel (state))) "a panel that is whole is left as it is")
   (is (= #{} (cancelled)))))

;; ---------------------------------------------------------------------------
;; Trying again

(deftest trying-again-asks-for-what-failed-in-a-new-load-with-a-new-deadline
  (rf-test/run-test-sync
   (on-the-panel!)
   (answer-everything! (fn [spec]
                         (if (= [:panel [:summary "pt-ana"]] (:id spec))
                           (rf/dispatch (conj (:on-failure spec) {:kind :server :status 500 :request-id "r"}))
                           (answer! spec))))
   (is (= :failed (get-in (state) [:panel :rows "pt-ana" :summary :state])))
   (is (panel/settled? (:panel (state))))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/retry])
   (is (= [[:summary "pt-ana"]] (requests)) "only what failed")
   (is (= [{:ms 10000 :dispatch [:pulse.spa.panel/deadline 2]}] (deadlines)) "the deadline is for the new load")
   (is (= 2 (:panel-generation (state))))
   (is (= ["pt-sofia" "pt-liam" "pt-omar" "pt-noah" "pt-ana"] (get-in (state) [:panel :order])) "the rows stay where they were while it loads")
   (testing "and when it is answered the panel is whole"
     (answer-everything!)
     (is (= (fx/settled) (:panel (state)))))))

(deftest trying-again-while-a-call-is-on-its-way-does-nothing-even-if-another-has-failed
  (rf-test/run-test-sync
   (on-the-panel!)
   (rf/dispatch [:pulse.spa.panel/failed 1 :active {:kind :server :status 500 :request-id "r"}])
   (reset! asked [])
   (is (seq (panel/failed-tasks (:panel (state)))) "something failed")
   (is (not (panel/settled? (:panel (state)))) "and something is still on its way")
   (rf/dispatch [:pulse.spa.panel/retry])
   (is (= [] @asked) "a new load would make the calls on their way deaf")
   (is (= 1 (:panel-generation (state))))))

(deftest trying-again-when-nothing-failed-or-while-it-loads-does-nothing
  (rf-test/run-test-sync
   (on-the-panel!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/retry])
   (is (= [] @asked) "it is on its way: a new load would make the calls on their way deaf")
   (is (= 1 (:panel-generation (state)))))
  (rf-test/run-test-sync
   (on-the-panel!)
   (answer-everything!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/retry])
   (is (= [] @asked) "nothing failed"))
  (rf-test/run-test-sync
   (signed-in-with-quiet-effects!)
   (rf/dispatch [:pulse.spa.panel/retry])
   (is (= [] @asked) "there is no panel")))

;; ---------------------------------------------------------------------------
;; Leaving

(deftest leaving-the-panel-stops-what-is-on-its-way-and-forgets-it
  (rf-test/run-test-sync
   (on-the-panel!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/leave])
   (is (nil? (:panel (state))))
   (is (= #{:list :active :acknowledged :requests} (cancelled)))
   (is (= 2 (:panel-generation (state))))))

(deftest leaving-when-there-is-no-panel-stops-nothing
  (rf-test/run-test-sync
   (signed-in-with-quiet-effects!)
   (rf/dispatch [:pulse.spa.panel/leave])
   (is (= #{} (cancelled)))
   (is (nil? (:panel (state))))))

;; ---------------------------------------------------------------------------
;; The search

(deftest typing-in-the-search-box-changes-the-address-without-adding-to-the-history
  (rf-test/run-test-sync
   (on-the-panel!)
   (rf/dispatch [::events/navigated {:name :panel :params {:show :alerts :q ""}}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/search "ana"])
   (is (= {:show :alerts :q "ana"} (get-in (state) [:route :params])) "at once: the box has what was typed")
   (is (= [[::router/replace "/?q=ana&show=alerts"]] @asked) "the filter is kept")
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/search ""])
   (is (= [[::router/replace "/?show=alerts"]] @asked) "an empty search is no search in the address")))

(deftest a-search-that-is-too-long-is-cut-and-one-that-is-not-text-is-none
  (rf-test/run-test-sync
   (on-the-panel!)
   (rf/dispatch [:pulse.spa.panel/search (apply str (repeat 150 "a"))])
   (is (= 100 (count (get-in (state) [:route :params :q]))))
   (rf/dispatch [:pulse.spa.panel/search nil])
   (is (= "" (get-in (state) [:route :params :q])))))

(deftest a-search-typed-anywhere-but-on-the-panel-is-nothing
  (rf-test/run-test-sync
   (signed-in-with-quiet-effects!)
   (rf/dispatch [:pulse.spa.panel/search "ana"])
   (is (nil? (:route (state))))
   (is (= [] @asked))
   (rf/dispatch [::events/navigated {:name :not-found :params {}}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.panel/search "ana"])
   (is (= {:name :not-found :params {}} (:route (state))))
   (is (= [] @asked))))
