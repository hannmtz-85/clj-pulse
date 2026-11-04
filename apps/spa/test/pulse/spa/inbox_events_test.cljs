(ns pulse.spa.inbox-events-test
  "The events of the inbox of alerts and of the route that leads to it (ADR-0029, points 3 and 6), as the state they leave and the
  effects they ask for. The effects that reach out of the page (the api, the history, the clock, the focus) are replaced by ones that
  write down what they were asked, so nothing leaves Node; and some tests answer the calls the inbox makes with the answers of the
  fixtures, as the api would, to see the inbox whole. The point of the inbox is that what is shown does not move on its own, and the
  tests of looking again are about that. Each test runs in `day8.re-frame/test`'s own re-frame state, which is thrown away after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.ack :as ack]
            [pulse.spa.ack-events]
            [pulse.spa.auth :as auth]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.inbox :as inbox]
            [pulse.spa.inbox-events :as inbox-events]
            [pulse.spa.panel-events]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.inbox-fixtures :as fx]
            [pulse.spa.watch-events :as watch-events]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [re-frame.loggers :as loggers]
            [re-frame.registrar :as registrar]))

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
     (rf/reg-cofx ::inbox-events/now (fn [cofx _] (assoc cofx :now fx/now))))))

(defn- state [] @db/app-db)

(defn- calls
  "The calls made so far, as their specs."
  []
  (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- tasks-asked
  "The tasks the calls of the inbox made so far are for, in order."
  []
  (vec (for [spec (calls) :when (= :inbox (first (:id spec)))] (nth (:on-success spec) 2))))

(defn- cancelled
  "The ids of the calls that were stopped."
  []
  (set (for [[effect value] @asked :when (= ::fetch/cancel effect)] (:id value))))

(defn- deadlines
  "The deadlines of the inbox that were set."
  []
  (vec (for [[effect value] @asked :when (and (= :dispatch-later effect) (= :pulse.spa.inbox/deadline (first (:dispatch value))))] value)))

(defn- focused [] (vec (for [[effect value] @asked :when (= ::router/focus effect)] value)))

(defn- hushes
  "What was set to be taken away from a screen reader, and when."
  []
  (vec (for [[effect value] @asked :when (and (= :dispatch-later effect) (= :pulse.spa.ack/hush (first (:dispatch value))))] value)))

(def ^:private rivera {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera" "realm_access" {"roles" ["practitioner"]}})

(def ^:private inbox-route {:name :alerts :params {:show :all}})

(defn- on-the-inbox!
  "Signs in and comes to the inbox, which begins to load (the load of the generation 1); what was asked since is in `@asked`."
  ([] (on-the-inbox! inbox-route))
  ([here]
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/signed-in rivera])
   (reset! asked [])
   (rf/dispatch [::events/navigated here])))

(defn- answer!
  "The api answers the call `spec` of the inbox as the fixtures say, as the effect would tell the page."
  ([spec] (answer! spec nil))
  ([spec body]
   (let [task (nth (:on-success spec) 2)]
     (rf/dispatch (conj (:on-success spec) {:status 200 :body (or body (fx/body-of task)) :request-id "r"})))))

(defn- answer-everything!
  "Answers every call the inbox makes, and the calls that answering makes, until none is left. It gives the number of calls answered."
  []
  (let [answered (atom #{})]
    (loop [guard 0]
      (let [pending (remove #(contains? @answered (:id %)) (filter #(= :inbox (first (:id %))) (calls)))]
        (when (and (seq pending) (< guard 40))
          (doseq [spec pending]
            (swap! answered conj (:id spec))
            (answer! spec))
          (recur (inc guard)))))
    (count @answered)))

(defn- the-inbox-has-loaded! []
  (on-the-inbox!)
  (answer-everything!)
  (reset! asked []))

;; ---------------------------------------------------------------------------
;; The route

(deftest coming-to-the-inbox-starts-it-and-sets-the-title-without-moving-the-focus
  (rf-test/run-test-sync
   (on-the-inbox!)
   (is (= inbox-route (subs/route (state) nil)))
   (is (= [:active :acknowledged :patients] (tasks-asked)))
   (is (= [[::router/arrived {:title "Alerts · Pulse" :focus? false}]] (filter #(= ::router/arrived (first %)) @asked)))))

(deftest coming-from-the-panel-moves-the-focus-to-the-heading-and-leaves-the-panel
  (rf-test/run-test-sync
   (on-the-inbox! {:name :panel :params {:show :all :q ""}})
   (reset! asked [])
   (rf/dispatch [::events/navigated inbox-route])
   (is (= [[::router/arrived {:title "Alerts · Pulse" :focus? true}]] (filter #(= ::router/arrived (first %)) @asked)))
   (is (nil? (:panel (state))) "the panel is left")
   (is (some? (:inbox (state))) "and the inbox begins")))

(deftest narrowing-the-inbox-is-the-same-page-and-asks-for-nothing
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [::events/navigated {:name :alerts :params {:show :active}}])
   (rf/dispatch [::events/navigated {:name :alerts :params {:show :acknowledged}}])
   (is (= {:name :alerts :params {:show :acknowledged}} (subs/route (state) nil)))
   (is (= [] (calls)))
   (is (= (fx/settled) (subs/inbox (state) nil)) "and changes nothing of what it holds")))

(deftest a-person-who-may-not-open-it-is-asked-nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (swap! db/app-db assoc :status :signed-in :person {:id "a1" :name "Ana" :roles #{:patient}})
   (rf/dispatch [::events/navigated inbox-route])
   (is (= [] (calls)))
   (is (nil? (:inbox (state))))))

;; ---------------------------------------------------------------------------
;; Loading

(deftest loading-the-inbox-asks-for-the-lists-and-the-names-and-sets-the-deadline
  (rf-test/run-test-sync
   (on-the-inbox!)
   (is (= [{:ms 10000 :dispatch [:pulse.spa.inbox/deadline 1 1]}] (deadlines)) "one deadline, for this load")
   (is (= fx/now (get-in (state) [:inbox :now])) "the moment it began")
   (is (= {:state :loading} (get-in (state) [:inbox :active])))
   (testing "the calls are the ones the machine made, with the generation in the events that hear them"
     (let [spec (first (calls))]
       (is (= [:inbox :active] (:id spec)))
       (is (= ["api" "alerts"] (:path spec)))
       (is (= {:status "active" :sort "-since" :limit 100} (:query spec)))
       (is (= [:pulse.spa.inbox/succeeded 1 :active] (:on-success spec)))))
   (testing "no token, nothing of the person, in what was asked"
     (is (not (re-find #"eyJ" (pr-str @asked)))))))

(deftest the-real-clock-gives-an-instant-of-the-api
  (rf-test/run-test-sync
   (reset! asked [])
   (quietly (fn []
              (rf/reg-fx ::fetch/request (fn [spec] (swap! asked conj [::fetch/request spec])))
              (rf/reg-fx :dispatch-later (fn [_] nil))))
   (rf/dispatch [:pulse.spa.inbox/load])
   (let [now (get-in (state) [:inbox :now])]
     (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" now) "to the second, in UTC, as the api reads it")
     (is (< (js/Math.abs (- (js/Date.parse now) (js/Date.now))) 2000)))))

(deftest the-active-alerts-make-the-inbox-ask-for-the-readings-behind-them
  (rf-test/run-test-sync
   (on-the-inbox!)
   (let [active-call (first (filter #(= :active (nth (:on-success %) 2)) (calls)))]
     (reset! asked [])
     (answer! active-call))
   (is (= [[:evidence "pt-ana" :blood-pressure] [:evidence "pt-sofia" :spo2]] (tasks-asked)))
   (is (= [["api" "patients" "pt-ana" "observations"] ["api" "patients" "pt-sofia" "observations"]] (map :path (calls))))))

(deftest the-whole-inbox-answered-is-the-inbox-of-the-fixtures
  (rf-test/run-test-sync
   (on-the-inbox!)
   (is (= 5 (answer-everything!)) "the two lists, the names and the readings behind two alerts: five calls, and none asked twice")
   (is (= (fx/settled) (subs/inbox (state) nil)))
   (is (true? (inbox/settled? (subs/inbox (state) nil))))
   (is (= [] (focused)) "and the focus is not taken: nobody asked for anything")))

(deftest a-call-that-fails-is-a-part-that-failed
  (rf-test/run-test-sync
   (on-the-inbox!)
   (let [patients-call (first (filter #(= :patients (nth (:on-success %) 2)) (calls)))]
     (rf/dispatch (conj (:on-failure patients-call) {:kind :server :status 500 :request-id "r"})))
   (is (= {:state :failed :kind :server} (get-in (state) [:inbox :patients])))
   (testing "and the rest goes on"
     (doseq [spec (remove #(= :patients (nth (:on-success %) 2)) (calls))]
       (answer! spec))
     (doseq [spec (filter #(vector? (nth (:on-success %) 2)) (calls))]
       (answer! spec))
     (is (= :loaded (get-in (state) [:inbox :active :state])))
     (is (= [:patients] (inbox/failed-tasks (subs/inbox (state) nil)))))))

(deftest an-answer-of-an-inbox-that-was-left-is-not-heard
  (rf-test/run-test-sync
   (on-the-inbox!)
   (let [first-call (first (calls))]
     (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
     (is (nil? (:inbox (state))))
     (rf/dispatch [::events/navigated inbox-route])
     (is (= 3 (:inbox-generation (state))) "leaving one is a generation, and beginning the other is another")
     (reset! asked [])
     (answer! first-call)
     (is (= [] (calls)) "an answer to the first load makes no call")
     (is (= {:state :loading} (get-in (state) [:inbox :active])) "and does not fill the second"))))

(deftest an-answer-when-the-inbox-is-not-open-is-not-heard
  (rf-test/run-test-sync
   (on-the-inbox!)
   (let [first-call (first (calls))]
     (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
     (answer! first-call)
     (is (nil? (:inbox (state)))))))

;; ---------------------------------------------------------------------------
;; The deadline, the retry and leaving

(deftest at-the-deadline-what-is-not-back-has-failed-and-its-calls-are-stopped
  (rf-test/run-test-sync
   (on-the-inbox!)
   (answer! (first (calls)))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/deadline 1 1])
   (is (= {:state :failed :kind :timeout} (get-in (state) [:inbox :acknowledged])))
   (is (= :loaded (get-in (state) [:inbox :active :state])) "what had come is kept")
   (is (= #{[:inbox :acknowledged] [:inbox :patients] [:inbox [:evidence "pt-ana" :blood-pressure]] [:inbox [:evidence "pt-sofia" :spo2]]}
          (cancelled)))))

(deftest the-deadline-of-another-load-or-of-an-epoch-that-is-over-does-nothing
  (rf-test/run-test-sync
   (on-the-inbox!)
   (let [before (state)]
     (rf/dispatch [:pulse.spa.inbox/deadline 7 1])
     (rf/dispatch [:pulse.spa.inbox/deadline 1 7])
     (is (= before (state)))
     (is (= [] (filter #(= ::fetch/cancel (first %)) @asked))))))

(deftest the-deadline-of-an-inbox-that-is-closed-does-nothing
  (rf-test/run-test-sync
   (on-the-inbox!)
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/deadline 1 1])
   (is (= [] @asked))
   (is (nil? (:inbox (state))))))

(deftest a-retry-asks-again-for-what-failed-once-the-inbox-has-settled
  (rf-test/run-test-sync
   (on-the-inbox!)
   (answer-everything!)
   (swap! db/app-db assoc-in [:inbox :active] fx/failed)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/retry])
   (is (= [:active] (tasks-asked)) "only what failed")
   (is (= 2 (:inbox-generation (state))) "in a generation of its own, so that nothing of the first is heard")
   (is (= [{:ms 10000 :dispatch [:pulse.spa.inbox/deadline 2 2]}] (deadlines)))
   (is (= [:pulse.spa.inbox/succeeded 2 :active] (:on-success (first (calls)))))))

(deftest a-retry-of-nothing-or-of-an-inbox-that-has-not-settled-does-nothing
  (rf-test/run-test-sync
   (on-the-inbox!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/retry])
   (is (= [] @asked) "calls are on their way and a new generation would make them deaf"))
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/retry])
   (is (= [] @asked) "nothing failed"))
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [:pulse.spa.inbox/retry])
   (is (= [] @asked) "no inbox")))

(deftest leaving-the-inbox-stops-its-calls-and-forgets-it
  (rf-test/run-test-sync
   (on-the-inbox!)
   (reset! asked [])
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (= #{[:inbox :active] [:inbox :acknowledged] [:inbox :patients]} (cancelled)) "its calls are stopped")
   (is (nil? (:inbox (state))))))

(deftest leaving-the-inbox-ends-what-the-acknowledgements-were-waiting-for
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (swap! db/app-db assoc :acks {:alerts {"a" {:state :settling :label "A"} "b" {:state :sending :label "B"}} :said "Said."})
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (is (= {:alerts {"b" {:state :sending :label "B"}}} (subs/acks (state) nil)) "a command on its way is not over, and what was said is")))

(deftest coming-to-the-inbox-takes-away-what-was-said-before
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [::events/start])
   (rf/dispatch [::events/signed-in rivera])
   (swap! db/app-db assoc :acks {:alerts {} :said "Said before."})
   (rf/dispatch [::events/navigated inbox-route])
   (is (not (contains? (subs/acks (state) nil) :said)))))

;; ---------------------------------------------------------------------------
;; Looking again: what is shown does not move

(deftest the-beat-makes-the-inbox-look-again-at-both-lists
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (is (= [[:check :active] [:check :acknowledged]] (tasks-asked)))
   (is (= [[:inbox [:check :active]] [:inbox [:check :acknowledged]]] (map :id (calls))))
   (is (= [{:ms 10000 :dispatch [:pulse.spa.inbox/deadline 1 2]}] (deadlines)) "with a deadline of its own, in the same load")
   (is (= (select-keys (fx/settled) [:active :acknowledged :patients :evidence :latest])
          (select-keys (subs/inbox (state) nil) [:active :acknowledged :patients :evidence :latest]))
       "and nothing that is shown changes")
   (is (= [] (focused)))))

(deftest a-look-is-not-begun-while-the-inbox-is-loading-or-looking-or-is-not-open
  (rf-test/run-test-sync
   (on-the-inbox!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (is (= [] @asked) "it is still loading"))
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (is (= [] @asked) "a look is on its way"))
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (is (= [] @asked) "no inbox")))

(deftest what-a-look-finds-is-kept-apart-and-the-page-says-the-alerts-have-changed
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (let [[active-call acknowledged-call] (calls)]
     (answer! active-call fx/active-with-liam)
     (answer! acknowledged-call))
   (let [shown (subs/inbox (state) nil)]
     (is (= (fx/loaded fx/active) (:active shown)) "what is shown is what was shown")
     (is (= (fx/loaded fx/active-with-liam) (get-in shown [:latest :active])))
     (is (true? (inbox/changed? shown)))
     (is (= [{:id :changed}] (:notices (inbox/model shown {} false)))))
   (is (= [] (focused)) "nothing is moved or taken")
   (is (= [:check :check] (map first (tasks-asked))) "and no readings are asked for yet")))

(deftest a-look-that-finds-the-same-alerts-says-nothing
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (doseq [spec (calls)] (answer! spec))
   (is (false? (inbox/changed? (subs/inbox (state) nil))))
   (is (= [] (:notices (inbox/model (subs/inbox (state) nil) {} false))))))

(deftest a-look-that-failed-is-said-and-leaves-what-is-shown-as-it-is
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (rf/dispatch (conj (:on-failure (first (calls))) {:kind :timeout :request-id "r"}))
   (answer! (second (calls)))
   (let [shown (subs/inbox (state) nil)]
     (is (= {:state :failed :kind :timeout} (get-in shown [:latest :active])))
     (is (true? (inbox/check-failed? shown)))
     (is (= (:active (fx/settled)) (:active shown)))
     (is (= [{:id :check-failed}] (:notices (inbox/model shown {} false)))))))

(deftest a-look-that-is-not-back-by-its-deadline-has-failed
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/deadline 1 2])
   (is (= {:state :failed :kind :timeout} (get-in (state) [:inbox :latest :active])))
   (is (= #{[:inbox [:check :active]] [:inbox [:check :acknowledged]]} (cancelled)))
   (is (= (:active (fx/settled)) (get-in (state) [:inbox :active])))))

(deftest a-retry-of-a-look-that-failed-looks-again
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (swap! db/app-db assoc-in [:inbox :latest :active] fx/failed)
   (rf/dispatch [:pulse.spa.inbox/retry])
   (is (= [[:check :active]] (tasks-asked)))))

(deftest showing-the-changes-shows-the-alerts-asks-for-the-readings-of-the-new-ones-and-takes-the-focus-to-the-heading
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (let [[active-call acknowledged-call] (calls)]
     (answer! active-call fx/active-with-liam)
     (answer! acknowledged-call))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/show-changes])
   (let [shown (subs/inbox (state) nil)]
     (is (= (fx/loaded fx/active-with-liam) (:active shown)))
     (is (= {} (:latest shown)))
     (is (false? (inbox/changed? shown))))
   (is (= [[:evidence "pt-liam" :glucose]] (tasks-asked)) "only the readings behind the new alert")
   (is (= [{:ms 10000 :dispatch [:pulse.spa.inbox/deadline 1 3]}] (deadlines)))
   (is (= ["#needs"] (focused)) "the button that was pressed is gone, and the focus goes to the heading of the alerts")
   (answer-everything!)
   (is (= :loaded (get-in (state) [:inbox :evidence ["pt-liam" :glucose] :state])))))

(deftest showing-changes-when-there-are-none-does-nothing
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/show-changes])
   (is (= [] @asked))
   (is (= (fx/settled) (subs/inbox (state) nil))))
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [:pulse.spa.inbox/show-changes])
   (is (= [] @asked))))

;; ---------------------------------------------------------------------------
;; After the person acknowledged an alert

(deftest after-acknowledging-the-inbox-looks-again-and-shows-what-it-finds-when-both-are-back
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (swap! db/app-db assoc :acks {:alerts {"flag-pt-ana-R1" {:state :settling :label "BP stage 2 · Ana García"}} :said "BP stage 2 · Ana García: Alert acknowledged."})
   (rf/dispatch [:pulse.spa.inbox/after-ack])
   (is (= [[:check :active] [:check :acknowledged]] (tasks-asked)))
   (is (true? (get-in (state) [:inbox :adopt?])))
   (is (= [{:ms 10000 :dispatch [:pulse.spa.inbox/deadline 1 2]}] (deadlines)))
   (let [[active-call acknowledged-call] (calls)]
     (answer! active-call fx/active-without-ana)
     (testing "one of the two is back, and nothing is shown yet"
       (is (= (fx/loaded fx/active) (get-in (state) [:inbox :active])))
       (is (= [] (focused)))
       (is (= {:state :settling :label "BP stage 2 · Ana García"} (get-in (state) [:acks :alerts "flag-pt-ana-R1"]))))
     (answer! acknowledged-call fx/acknowledged-with-ana))
   (testing "both are, and the alert is where the api says it is"
     (let [shown (subs/inbox (state) nil)]
       (is (= (fx/loaded fx/active-without-ana) (:active shown)))
       (is (= (fx/loaded fx/acknowledged-with-ana) (:acknowledged shown)))
       (is (false? (:adopt? shown)))
       (is (= "1 alert needs a decision" (:live (inbox/model shown {} false)))))
     (is (= ["#needs"] (focused)) "and the focus is on the heading, since the button that had it is gone")
     (is (= {:alerts {} :said "BP stage 2 · Ana García: Alert acknowledged."} (subs/acks (state) nil))
         "what the acknowledgement waited for is over, and what was said stays a moment, so that it is drawn before the page says its own words")
     (is (= [{:ms ack/said-for-ms :dispatch [:pulse.spa.ack/hush "BP stage 2 · Ana García: Alert acknowledged."]}] (hushes)))
     (rf/dispatch [:pulse.spa.ack/hush "BP stage 2 · Ana García: Alert acknowledged."])
     (is (= {:alerts {}} (subs/acks (state) nil)) "and then it gives way to the page"))))

(deftest a-look-that-was-begun-before-the-command-is-stopped-by-the-one-after-it
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (let [old-calls (calls)]
     (reset! asked [])
     (rf/dispatch [:pulse.spa.inbox/after-ack])
     (is (= (map :id old-calls) (map :id (calls))) "the new calls have the ids of the old, and the effect stops those")
     (let [[active-call acknowledged-call] (calls)]
       (answer! active-call fx/active-without-ana)
       (answer! acknowledged-call fx/acknowledged-with-ana))
     (is (= (fx/loaded fx/active-without-ana) (get-in (state) [:inbox :active]))))))

(deftest after-acknowledging-with-nothing-open-nothing-is-asked
  (rf-test/run-test-sync
   (note-the-effects!)
   (rf/dispatch [:pulse.spa.inbox/after-ack])
   (is (= [] @asked))))

(deftest if-the-looks-do-not-all-come-by-the-deadline-what-came-is-shown
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (swap! db/app-db assoc :acks {:alerts {"flag-pt-ana-R1" {:state :settling :label "A"}} :said "x"})
   (rf/dispatch [:pulse.spa.inbox/after-ack])
   (answer! (first (calls)) fx/active-without-ana)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/deadline 1 2])
   (let [shown (subs/inbox (state) nil)]
     (is (= (fx/loaded fx/active-without-ana) (:active shown)) "the person acted, and sees what came")
     (is (= (fx/loaded fx/acknowledged) (:acknowledged shown)) "the list that did not come is the one that was shown")
     (is (= {:acknowledged {:state :failed :kind :timeout}} (:latest shown)) "and the page says that it could not look")
     (is (false? (:adopt? shown))))
   (is (= #{[:inbox [:check :acknowledged]]} (cancelled)))
   (is (= ["#needs"] (focused)))
   (is (= {:alerts {} :said "x"} (subs/acks (state) nil)) "the wait is over; what was said stays a moment")
   (is (= [{:ms ack/said-for-ms :dispatch [:pulse.spa.ack/hush "x"]}] (hushes)))
   (rf/dispatch [:pulse.spa.ack/hush "x"])
   (is (= {:alerts {}} (subs/acks (state) nil)))))

(deftest if-no-look-comes-by-the-deadline-the-person-is-told-so-and-the-button-is-free
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (swap! db/app-db assoc :acks {:alerts {"flag-pt-ana-R1" {:state :settling :label "A"}} :said "x"})
   (rf/dispatch [:pulse.spa.inbox/after-ack])
   (rf/dispatch [:pulse.spa.inbox/deadline 1 2])
   (let [shown (subs/inbox (state) nil)]
     (is (= (:active (fx/settled)) (:active shown)))
     (is (true? (inbox/check-failed? shown))))
   (is (= {:alerts {} :said "x"} (subs/acks (state) nil)))
   (is (= [{:ms ack/said-for-ms :dispatch [:pulse.spa.ack/hush "x"]}] (hushes)))))

(deftest the-state-the-views-read
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (is (= (fx/settled) (subs/inbox (state) nil)))
   (is (nil? (subs/acks (state) nil)))
   (is (some? (:inbox (state))))
   (let [acks {:alerts {"a" {:state :sending}}}]
     (swap! db/app-db assoc :acks acks)
     (is (= acks (subs/acks (state) nil))))))

(deftest the-events-and-the-effects-are-registered
  (doseq [event [:pulse.spa.inbox/load :pulse.spa.inbox/succeeded :pulse.spa.inbox/failed :pulse.spa.inbox/deadline :pulse.spa.inbox/retry
                 :pulse.spa.inbox/check :pulse.spa.inbox/after-ack :pulse.spa.inbox/show-changes :pulse.spa.inbox/leave]]
    (is (some? (registrar/get-handler :event event)) (str event)))
  (is (some? (registrar/get-handler :cofx ::inbox-events/now)))
  (is (some? (registrar/get-handler :fx ::router/focus))))

(deftest nothing-of-the-person-is-in-what-was-asked
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (swap! db/app-db assoc :acks (ack/begin nil "a" "A"))
   (rf/dispatch [:pulse.spa.inbox/after-ack])
   (is (not (re-find #"eyJ" (pr-str @asked))))))

;; ---------------------------------------------------------------------------
;; What a mutation analysis found that the tests above did not hold

(deftest an-answer-of-the-generation-in-the-state-is-not-heard-when-there-is-no-inbox
  ;; not a state the workspace reaches (leaving an inbox is another generation), but the guard is what keeps an answer from making an inbox
  ;; out of nothing
  (rf-test/run-test-sync
   (note-the-effects!)
   (swap! db/app-db assoc :inbox-generation 1)
   (rf/dispatch [:pulse.spa.inbox/succeeded 1 :active {:status 200 :body fx/active :request-id "r"}])
   (rf/dispatch [:pulse.spa.inbox/failed 1 :patients {:kind :server :request-id "r"}])
   (is (nil? (:inbox (state))))
   (is (= [] @asked))))

(deftest the-deadline-of-a-look-the-beat-asked-for-shows-nothing-that-the-person-did-not-ask-for
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (answer! (first (calls)) fx/active-with-liam)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/deadline 1 2])
   (let [shown (subs/inbox (state) nil)]
     (is (= (fx/loaded fx/active) (:active shown)) "what is shown is what was shown")
     (is (= (fx/loaded fx/active-with-liam) (get-in shown [:latest :active])) "and what the look found is kept apart")
     (is (= {:state :failed :kind :timeout} (get-in shown [:latest :acknowledged])) "with the list that did not come as one that failed")
     (is (true? (inbox/changed? shown)) "for the page to say so"))
   (is (= [] (focused)) "and the focus is where the person put it")))

(deftest the-state-keeps-no-trace-of-the-calls-the-deadline-stopped
  (rf-test/run-test-sync
   (on-the-inbox!)
   (rf/dispatch [:pulse.spa.inbox/deadline 1 1])
   (is (not (contains? (:inbox (state)) :cancelled)) "they are in the effects that stop them, and not in what the views read")
   (is (seq (cancelled)) "and they were stopped")))

(deftest a-retry-while-something-is-still-loading-does-nothing-even-if-something-has-failed
  (rf-test/run-test-sync
   (on-the-inbox!)
   (rf/dispatch (conj (:on-failure (first (calls))) {:kind :server :request-id "r"}))
   (is (= [:active] (inbox/failed-tasks (subs/inbox (state) nil))) "one list failed, and the others are on their way")
   (reset! asked [])
   (let [before (state)]
     (rf/dispatch [:pulse.spa.inbox/retry])
     (is (= before (state)) "a new generation would make the calls on their way deaf")
     (is (= [] @asked)))))

(deftest showing-changes-that-need-no-new-call-sets-no-deadline
  (rf-test/run-test-sync
   (the-inbox-has-loaded!)
   (rf/dispatch [:pulse.spa.inbox/check {:show :all}])
   (let [[active-call acknowledged-call] (calls)]
     (answer! active-call fx/active-without-ana)
     (answer! acknowledged-call fx/acknowledged-with-ana))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.inbox/show-changes])
   (is (= (fx/loaded fx/active-without-ana) (:active (subs/inbox (state) nil))) "the changes are shown")
   (is (= [] (tasks-asked)) "the readings of the alert that is left are the ones it has")
   (is (= [] (deadlines)) "so nothing is on its way that a deadline would have to end")
   (is (= ["#needs"] (focused)))))
