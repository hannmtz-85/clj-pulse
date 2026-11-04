(ns pulse.spa.ack-events-test
  "Acknowledging an alert, as the state the events leave and the effects they ask for (ADR-0029, points 3 and 8): the click sends the
  command and the button is busy; the api answers; and then the page that holds the alert is read again and the alert is where the api's
  list puts it. There is no optimistic update, so the tests follow the whole way on the inbox and on the chart of a patient: the click, the
  call, each answer the api can give, the page read again, and the focus and what a screen reader is told. The effects that reach out
  of the page are replaced by ones that write down what they were asked; each test runs in `day8.re-frame/test`'s own re-frame state,
  which is thrown away after it."
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
            [pulse.spa.patient :as patient]
            [pulse.spa.patient-events :as patient-events]
            [pulse.spa.router :as router]
            [pulse.spa.subs :as subs]
            [pulse.spa.test.inbox-fixtures :as fx]
            [pulse.spa.test.patient-fixtures :as pfx]
            [pulse.spa.watch :as watch]
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

(defn- note-the-effects! []
  (reset! asked [])
  (quietly
   (fn []
     (doseq [effect [::auth/init ::auth/logout ::fetch/request ::fetch/cancel ::router/start ::router/replace ::router/arrived ::router/focus
                     ::watch-events/listen :dispatch-later]]
       (rf/reg-fx effect (fn [value] (swap! asked conj [effect value]))))
     (rf/reg-cofx ::inbox-events/now (fn [cofx _] (assoc cofx :now fx/now)))
     (rf/reg-cofx ::patient-events/now (fn [cofx _] (assoc cofx :now fx/now))))))

(defn- state [] @db/app-db)

(defn- calls [] (vec (for [[effect spec] @asked :when (= ::fetch/request effect)] spec)))

(defn- ids-asked [] (mapv :id (calls)))

(defn- focused [] (vec (for [[effect value] @asked :when (= ::router/focus effect)] value)))

(defn- hushes
  "What was set to be taken away from a screen reader, and when: the `:dispatch-later` that hush the words of a command."
  []
  (vec (for [[effect value] @asked :when (and (= :dispatch-later effect) (= :pulse.spa.ack/hush (first (:dispatch value))))] value)))

(def ^:private rivera {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera" "realm_access" {"roles" ["practitioner"]}})

(def ^:private label "BP stage 2 · Ana García")

(def ^:private ana-id "flag-pt-ana-R1")

(def ^:private pressed-ack-id [:ack ana-id])

(defn- signed-in! []
  (note-the-effects!)
  (rf/dispatch [::events/start])
  (rf/dispatch [::events/signed-in rivera])
  (reset! asked []))

(defn- answer-inbox! [spec body]
  (rf/dispatch (conj (:on-success spec) {:status 200 :body body :request-id "r"})))

(defn- answer-all!
  "Answers the calls of the page whose ids begin with `page-id` (`:inbox` or `:chart`), and the calls that answering makes, until none is
  left; `body-of` gives what the api answers for a task."
  [page-id body-of]
  (let [answered (atom #{})]
    (loop [guard 0]
      (let [pending (remove #(contains? @answered (:id %)) (filter #(= page-id (first (:id %))) (calls)))]
        (when (and (seq pending) (< guard 20))
          (doseq [spec pending]
            (swap! answered conj (:id spec))
            (rf/dispatch (conj (:on-success spec) {:status 200 :body (body-of (nth (:on-success spec) 2)) :request-id "r"})))
          (recur (inc guard)))))))

(defn- on-the-loaded-inbox! []
  (signed-in!)
  (rf/dispatch [::events/navigated {:name :alerts :params {:show :all}}])
  (answer-all! :inbox fx/body-of)
  (reset! asked []))

(defn- on-the-loaded-chart! []
  (signed-in!)
  (rf/dispatch [::events/navigated {:name :patient :params {:id "pt-ana" :measurement nil :days 14}}])
  (answer-all! :chart #(pfx/body-of :ana %))
  (reset! asked []))

(defn- acknowledge! [] (rf/dispatch [:pulse.spa.ack/acknowledge ana-id label]))

(defn- the-post
  "The call that acknowledges Ana's alert, as it was asked for, or nil."
  []
  (first (filter #(= pressed-ack-id (:id %)) (calls))))

;; ---------------------------------------------------------------------------
;; The click

(deftest pressing-the-button-sends-the-command-and-the-button-is-busy
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (is (= (ack/request ana-id) (the-post)) "the command, for that alert, with no body")
   (is (= [pressed-ack-id] (ids-asked)) "and nothing else")
   (is (= {:alerts {ana-id {:state :sending :label label}}} (subs/acks (state) nil)))
   (is (true? (ack/busy? (subs/acks (state) nil) ana-id)))
   (is (= fx/ana-evidence (get-in (state) [:inbox :evidence ["pt-ana" :blood-pressure] :data])) "and the inbox is not touched: the card stays where it is")
   (is (= (fx/loaded fx/active) (get-in (state) [:inbox :active])))))

(deftest a-second-click-on-a-button-that-is-busy-is-not-a-second-command
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (acknowledge!)
   (is (= 1 (count (calls))))
   (testing "nor while the page is being read again"
     (swap! db/app-db assoc-in [:acks :alerts ana-id :state] :settling)
     (acknowledge!)
     (is (= 1 (count (calls)))))
   (testing "but another alert is another command"
     (rf/dispatch [:pulse.spa.ack/acknowledge "flag-pt-sofia-R2" "Low SpO₂ · Sofía Rossi"])
     (is (= 2 (count (calls)))))))

(deftest after-a-failure-the-button-is-free-and-another-click-is-another-command
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :forbidden :status 403}])
   (reset! asked [])
   (acknowledge!)
   (is (= (ack/request ana-id) (the-post)))
   (is (= :sending (get-in (state) [:acks :alerts ana-id :state])))))

;; ---------------------------------------------------------------------------
;; On the inbox: the api says yes

(deftest the-api-saying-yes-reads-the-inbox-again-and-the-count-and-the-button-stays-busy-until-the-inbox-has-it
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {:id ana-id :status "inactive"} :request-id "r"}])
   (is (= [[:inbox [:check :active]] [:inbox [:check :acknowledged]] [:watch :count]] (ids-asked))
       "the inbox looks again at both lists, and the count of the navigation is read")
   (is (= {:state :settling :label label} (get-in (state) [:acks :alerts ana-id])) "still busy: the page does not have it yet")
   (is (= "BP stage 2 · Ana García: Alert acknowledged." (get-in (state) [:acks :said])) "a screen reader is told")
   (is (= (fx/loaded fx/active) (get-in (state) [:inbox :active])) "and the card is where it was until the api's list says otherwise")
   (is (true? (get-in (state) [:inbox :adopt?])))))

(deftest the-whole-way-the-click-the-answer-the-lists-and-the-card-moves
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {:id ana-id} :request-id "r"}])
   (let [[active-call acknowledged-call] (filter #(= :inbox (first (:id %))) (calls))]
     (reset! asked [])
     (answer-inbox! active-call fx/active-without-ana)
     (answer-inbox! acknowledged-call fx/acknowledged-with-ana))
   (let [shown (subs/inbox (state) nil)
         model-now (fn [] (inbox/model shown {:acks (:alerts (subs/acks (state) nil)) :said (:said (subs/acks (state) nil))} false))
         m (model-now)]
     (is (= ["flag-pt-sofia-R2"] (map :id (get-in m [:active :items]))) "Ana's alert is not among those that need a decision")
     (is (= ["flag-pt-ana-R1" "flag-pt-liam-R3"] (map :id (get-in m [:acknowledged :items]))) "it is among those that were acknowledged")
     (is (= "BP stage 2 · Ana García: Alert acknowledged." (:live m)) "the words of the command are still the words of the page: the lists can be back before they were drawn")
     (is (= [{:ms ack/said-for-ms :dispatch [:pulse.spa.ack/hush "BP stage 2 · Ana García: Alert acknowledged."]}] (hushes)) "and they are taken away after a while")
     (rf/dispatch [:pulse.spa.ack/hush "BP stage 2 · Ana García: Alert acknowledged."])
     (is (= "1 alert needs a decision" (:live (model-now))) "and then the page says its own words again"))
   (is (= ["#needs"] (focused)) "the focus is on the heading, since the button that had it is gone")
   (is (nil? (get-in (state) [:acks :alerts ana-id])) "nothing waits")
   (is (nil? (get-in (state) [:acks :said])))
   (is (not (ack/busy? (subs/acks (state) nil) ana-id)))))

(deftest the-count-of-the-navigation-follows-what-the-command-did
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (rf/dispatch [:pulse.spa.watch/counted 0 {:status 200 :body {:total 2 :items []}}])
   (acknowledge!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (let [count-call (first (filter #(= [:watch :count] (:id %)) (calls)))]
     (is (some? count-call))
     (is (= (get-in (watch/count-request 0) [:query]) (:query count-call)))
     (rf/dispatch (conj (:on-success count-call) {:status 200 :body {:total 1 :items [{:id "x"}]} :request-id "r"}))
     (is (= 1 (subs/alert-count (state) nil))))))

;; ---------------------------------------------------------------------------
;; On the inbox: every other answer

(deftest the-api-saying-someone-else-got-there-first-is-the-same-as-yes-with-other-words
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/refused ana-id {:kind :refused :status 409 :problem {:title "Conflict" :detail "x"} :request-id "r"}])
   (is (= [[:inbox [:check :active]] [:inbox [:check :acknowledged]] [:watch :count]] (ids-asked)))
   (is (= :settling (get-in (state) [:acks :alerts ana-id :state])))
   (is (= "BP stage 2 · Ana García: That alert was already acknowledged." (get-in (state) [:acks :said])))))

(deftest the-api-not-knowing-the-alert-reads-the-page-again
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :not-found :status 404 :request-id "r"}])
   (is (= [[:inbox [:check :active]] [:inbox [:check :acknowledged]] [:watch :count]] (ids-asked)))
   (is (= "BP stage 2 · Ana García: That alert could not be found." (get-in (state) [:acks :said])))))

(deftest the-api-not-letting-the-person-is-a-message-with-the-alert-and-the-focus-goes-back-to-the-button
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :forbidden :status 403 :request-id "r"}])
   (is (= {:state :failed :label label :message "You are not allowed to acknowledge this alert."} (get-in (state) [:acks :alerts ana-id])))
   (is (= "BP stage 2 · Ana García: You are not allowed to acknowledge this alert." (get-in (state) [:acks :said])))
   (is (= [] (calls)) "nothing is read again: nothing changed")
   (is (= ["#ack-flag-pt-ana-R1"] (focused)) "the button was disabled and lost the focus")))

(deftest a-command-the-api-found-wrong-is-a-message-and-nothing-is-read-again
  (doseq [status [400 422]]
    (rf-test/run-test-sync
     (on-the-loaded-inbox!)
     (acknowledge!)
     (reset! asked [])
     (rf/dispatch [:pulse.spa.ack/refused ana-id {:kind :refused :status status :problem {:title "x"}}])
     (testing (str status)
       (is (= "The alert was not acknowledged." (get-in (state) [:acks :alerts ana-id :message])))
       (is (= [] (calls)))
       (is (= ["#ack-flag-pt-ana-R1"] (focused)))))))

(deftest a-call-that-did-not-come-back-is-not-known-and-the-page-is-read-again-to-find-out
  (doseq [kind [:timeout :unreachable :server :unexpected]]
    (rf-test/run-test-sync
     (on-the-loaded-inbox!)
     (acknowledge!)
     (reset! asked [])
     (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind kind :request-id "r"}])
     (testing (name kind)
       (is (= {:state :failed :label label
               :message "We could not confirm that the alert was acknowledged. Check the list before you try again."}
              (get-in (state) [:acks :alerts ana-id])) "the alert says so, and its button is free")
       (is (= [[:inbox [:check :active]] [:inbox [:check :acknowledged]] [:watch :count]] (ids-asked)) "and the truth is asked for")
       (is (= ["#ack-flag-pt-ana-R1"] (focused)))))))

(deftest if-the-alert-did-go-through-the-message-goes-with-the-card
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :timeout}])
   (let [[active-call acknowledged-call] (filter #(= :inbox (first (:id %))) (calls))]
     (reset! asked [])
     (answer-inbox! active-call fx/active-without-ana)
     (answer-inbox! acknowledged-call fx/acknowledged-with-ana))
   (let [m (inbox/model (subs/inbox (state) nil) {:acks (:alerts (subs/acks (state) nil))} false)]
     (is (= ["flag-pt-sofia-R2"] (map :id (get-in m [:active :items]))) "the alert is among the acknowledged ones, and its card is gone")
     (is (= ["#needs"] (focused)) "and the focus is on the heading")
     (is (str/includes? (get-in (state) [:acks :said]) "We could not confirm") "what was said stays a moment")
     (let [told (get-in (state) [:acks :said])]
       (is (= [{:ms ack/said-for-ms :dispatch [:pulse.spa.ack/hush told]}] (hushes)))
       (rf/dispatch [:pulse.spa.ack/hush told])
       (is (nil? (get-in (state) [:acks :said])))))))

(deftest if-it-did-not-the-alert-stays-with-its-message-and-a-free-button
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :timeout}])
   (doseq [spec (filter #(= :inbox (first (:id %))) (calls))]
     (answer-inbox! spec (fx/body-of (nth (:on-success spec) 2))))
   (let [m (inbox/model (subs/inbox (state) nil) {:acks (:alerts (subs/acks (state) nil))} false)
         card (first (filter #(= ana-id (:id %)) (get-in m [:active :items])))]
     (is (some? card))
     (is (= :failed (get-in card [:ack :state])))
     (is (str/includes? (get-in card [:ack :message]) "We could not confirm"))
     (is (= "#ack-flag-pt-ana-R1" (last (focused))) "and the focus stays on its button, which is free, so that the person can try again"))))

(deftest a-call-that-was-stopped-or-a-sign-in-the-api-refused-leaves-no-trace
  (doseq [kind [:aborted :unauthorized]]
    (rf-test/run-test-sync
     (on-the-loaded-inbox!)
     (acknowledge!)
     (reset! asked [])
     (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind kind :request-id "r"}])
     (testing (name kind)
       (is (nil? (get-in (state) [:acks :alerts ana-id])))
       (is (= [] @asked) "nothing is asked and the focus is not moved")))))

;; ---------------------------------------------------------------------------
;; On the chart of a patient

(deftest the-api-saying-yes-on-the-chart-reads-its-summary-again
  (rf-test/run-test-sync
   (on-the-loaded-chart!)
   (acknowledge!)
   (is (= [pressed-ack-id] (ids-asked)))
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (is (= [[:chart :refresh] [:watch :count]] (ids-asked)) "the summary, which holds the alerts, and the count of the navigation")
   (is (= ["api" "patients" "pt-ana" "summary"] (:path (first (calls)))))
   (is (= {:state :settling :label label} (get-in (state) [:acks :alerts ana-id])))
   (is (= (fx/loaded pfx/ana-summary) (get-in (state) [:chart :summary])) "the chart is not blanked to read it again")))

(deftest the-whole-way-on-the-chart-the-alert-goes-and-the-focus-goes-to-the-heading-of-the-page
  (rf-test/run-test-sync
   (on-the-loaded-chart!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (let [refresh-call (first (filter #(= [:chart :refresh] (:id %)) (calls)))]
     (reset! asked [])
     (rf/dispatch (conj (:on-success refresh-call) {:status 200 :body (assoc pfx/ana-summary :alerts []) :request-id "r"})))
   (let [m (patient/model (subs/chart (state) nil))]
     (is (= [] (get-in m [:alerts :items])) "no alert is active any more")
     (is (not (contains? (:alerts m) :stale))))
   (is (= ["main h1"] (focused)))
   (is (nil? (get-in (state) [:acks :alerts ana-id])))
   (is (= "BP stage 2 · Ana García: Alert acknowledged." (get-in (state) [:acks :said])) "what a screen reader was told stays: the summary can be back before it was drawn")
   (is (= [{:ms ack/said-for-ms :dispatch [:pulse.spa.ack/hush "BP stage 2 · Ana García: Alert acknowledged."]}] (hushes)))
   (rf/dispatch [:pulse.spa.ack/hush "BP stage 2 · Ana García: Alert acknowledged."])
   (is (nil? (get-in (state) [:acks :said])))))

(deftest words-that-were-hushed-late-do-not-take-away-what-was-said-since
  (rf-test/run-test-sync
   (on-the-loaded-chart!)
   (swap! db/app-db assoc :acks {:alerts {} :said "Said later."})
   (rf/dispatch [:pulse.spa.ack/hush "Said before."])
   (is (= "Said later." (get-in (state) [:acks :said])) "the timer of the first words does not silence the second")
   (rf/dispatch [:pulse.spa.ack/hush "Said later."])
   (is (nil? (get-in (state) [:acks :said])))
   (testing "and with nothing in the state at all it does nothing"
     (swap! db/app-db dissoc :acks)
     (rf/dispatch [:pulse.spa.ack/hush "Anything."])
     (is (not (contains? (state) :acks))))))

(deftest a-summary-that-could-not-be-read-again-ends-the-wait-and-says-so
  (rf-test/run-test-sync
   (on-the-loaded-chart!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (let [refresh-call (first (filter #(= [:chart :refresh] (:id %)) (calls)))]
     (reset! asked [])
     (rf/dispatch (conj (:on-failure refresh-call) {:kind :server :status 500 :request-id "r"})))
   (let [m (patient/model (subs/chart (state) nil))]
     (is (= :server (get-in m [:alerts :stale])))
     (is (= 1 (count (get-in m [:alerts :items]))) "the alerts that were read before are still shown"))
   (is (nil? (get-in (state) [:acks :alerts ana-id])) "the button is free")
   (is (= ["main h1"] (focused)))))

(deftest on-a-page-with-no-alerts-the-answer-is-forgotten-and-only-the-count-is-read
  (rf-test/run-test-sync
   (signed-in!)
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (reset! asked [])
   (swap! db/app-db assoc :acks (ack/begin nil ana-id label))
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (is (nil? (get-in (state) [:acks :alerts ana-id])) "there is no page to read again, so it does not wait for one")
   (is (= [[:watch :count]] (ids-asked)))
   (is (= "BP stage 2 · Ana García: Alert acknowledged." (get-in (state) [:acks :said])))))

(deftest the-person-who-left-the-inbox-before-the-answer-is-not-made-to-wait
  (rf-test/run-test-sync
   (on-the-loaded-inbox!)
   (acknowledge!)
   (rf/dispatch [::events/navigated {:name :panel :params {:show :all :q ""}}])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (is (nil? (get-in (state) [:acks :alerts ana-id])))
   (is (= [[:watch :count]] (ids-asked)))))

(deftest on-the-chart-an-alert-that-is-still-there-after-the-summary-is-read-again-keeps-the-focus-on-its-button
  (rf-test/run-test-sync
   (on-the-loaded-chart!)
   (acknowledge!)
   (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :timeout}])
   (let [call (first (filter #(= [:chart :refresh] (:id %)) (calls)))]
     (reset! asked [])
     (rf/dispatch (conj (:on-success call) {:status 200 :body pfx/ana-summary :request-id "r"})))
   (is (= ["#ack-flag-pt-ana-R1"] (focused)) "not the heading of the page")
   (is (= :failed (get-in (state) [:acks :alerts ana-id :state])) "and its message stays with it")))

(deftest the-events-are-registered
  (doseq [event [:pulse.spa.ack/acknowledge :pulse.spa.ack/answered :pulse.spa.ack/refused :pulse.spa.ack/failed]]
    (is (some? (registrar/get-handler :event event)) (str event))))

;; ---------------------------------------------------------------------------
;; What a mutation analysis found that the tests above did not hold

(deftest a-page-that-is-the-inbox-but-holds-none-has-nothing-to-read-again-and-the-button-does-not-wait-for-it
  ;; not a state the workspace reaches (coming to the inbox is the load of one), but if it did, an acknowledgement that waited for a page
  ;; that nothing is reading again would be busy for ever
  (rf-test/run-test-sync
   (signed-in!)
   (swap! db/app-db assoc :route {:name :alerts :params {:show :all}} :acks (ack/begin nil ana-id label))
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (is (nil? (get-in (state) [:acks :alerts ana-id])) "it does not wait")
   (is (= [[:watch :count]] (ids-asked)) "and the inbox is not asked to look again")
   (testing "nor when it is not known whether the command went through"
     (swap! db/app-db assoc :acks (ack/begin nil ana-id label))
     (reset! asked [])
     (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :timeout}])
     (is (= :failed (get-in (state) [:acks :alerts ana-id :state])) "the button is free, with its message")
     (is (= [[:watch :count]] (ids-asked))))))

(deftest a-page-that-is-the-chart-but-holds-none-has-nothing-to-read-again-and-the-button-does-not-wait-for-it
  (rf-test/run-test-sync
   (signed-in!)
   (swap! db/app-db assoc :route {:name :patient :params {:id "pt-ana" :measurement nil :days 14}} :acks (ack/begin nil ana-id label))
   (rf/dispatch [:pulse.spa.ack/answered ana-id {:status 200 :body {} :request-id "r"}])
   (is (nil? (get-in (state) [:acks :alerts ana-id])) "it does not wait")
   (is (= [[:watch :count]] (ids-asked)) "and the summary is not asked for again")
   (testing "nor when it is not known whether the command went through"
     (swap! db/app-db assoc :acks (ack/begin nil ana-id label))
     (reset! asked [])
     (rf/dispatch [:pulse.spa.ack/failed ana-id {:kind :timeout}])
     (is (= :failed (get-in (state) [:acks :alerts ana-id :state])))
     (is (= [[:watch :count]] (ids-asked))))))
