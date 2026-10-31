(ns pulse.spa.watch-events-test
  "The beat of the workspace (ADR-0029, point 6), as the state it leaves and the effects it asks for: the count of the alerts that need a
  decision, read when the person is let in and again every 30 seconds while the tab is seen, and the page the person is on asked to look
  again. It is the first thing that repeats, so the tests are about what keeps it from piling up: one run at a time, a beat of a run
  that is over does nothing, a tab that is hidden asks for nothing, and a workspace that is no longer signed in stops. The effects that
  reach out of the page (the api, the clock, the document) are replaced by ones that write down what they were asked; each test runs in
  `day8.re-frame/test`'s own re-frame state, which is thrown away after it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [pulse.spa.events :as events]
            [pulse.spa.fetch :as fetch]
            [pulse.spa.subs :as subs]
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

(def ^:private looked
  "The events that ask the page the person is on to look again, in the order they came."
  (atom []))

(defn- note-the-effects!
  "Replaces the effects that reach out of the page by ones that write down what they were asked, the tab by one that is seen or hidden as
  `visible?` says, and the event with which the beat asks the inbox to look again by one that writes it down."
  [visible?]
  (reset! asked [])
  (reset! looked [])
  (quietly
   (fn []
     (doseq [effect [::fetch/request ::watch-events/listen :dispatch-later]]
       (rf/reg-fx effect (fn [value] (swap! asked conj [effect value]))))
     (rf/reg-cofx ::watch-events/visible? (fn [cofx _] (assoc cofx :visible? visible?)))
     (rf/reg-event-fx :pulse.spa.inbox/check (fn [_ event] (swap! looked conj event) {})))))

(defn- state [] @db/app-db)

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})

(def ^:private inbox {:name :alerts :params {:show :all}})

(defn- signed-in!
  "The workspace with the person let in, on the page `here`, and nothing asked yet."
  [here]
  (swap! db/app-db assoc :status :signed-in :person rivera :route here))

(defn- asked-for [effect] (vec (for [[e value] @asked :when (= effect e)] value)))

(defn- counts-asked [] (asked-for ::fetch/request))

(defn- beats-armed [] (mapv :dispatch (asked-for :dispatch-later)))

(defn- epoch [] (get-in (state) [:watch :epoch]))

;; ---------------------------------------------------------------------------
;; Beginning

(deftest being-let-in-reads-the-count-at-once-arms-the-first-beat-and-listens-for-the-tab
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (is (= 1 (epoch)) "the first run")
   (is (= [(watch/count-request 1)] (counts-asked)) "the count is asked for now")
   (is (= [[:pulse.spa.watch/beat 1]] (beats-armed)) "and the first beat is armed")
   (is (= [{:ms 30000 :dispatch [:pulse.spa.watch/beat 1]}] (asked-for :dispatch-later)) "thirty seconds from now")
   (is (= [nil] (asked-for ::watch-events/listen)) "the tab is listened to")
   (is (= [] @looked) "the page has just been opened and has nothing to look at again")
   (is (nil? (subs/alert-count (state) nil)) "the navigation shows no number until it is read")))

(defn- quiet-the-history! []
  (quietly (fn []
             (doseq [effect [:pulse.spa.auth/init :pulse.spa.router/start]]
               (rf/reg-fx effect (fn [_] nil))))))

(deftest signing-in-begins-the-beat
  (rf-test/run-test-sync
   (note-the-effects! true)
   (quiet-the-history!)
   (rf/dispatch [::events/signed-in {"sub" "r1" "preferred_username" "dr.rivera@pulse.test" "name" "Elena Rivera"
                                     "realm_access" {"roles" ["practitioner"]}}])
   (is (= 1 (epoch)))
   (is (some #(= (watch/count-request 1) %) (counts-asked)) "the count is asked for as soon as the person is let in")
   (is (= [[:pulse.spa.watch/beat 1]] (beats-armed)))))

(deftest a-person-who-is-not-let-in-has-no-beat
  (rf-test/run-test-sync
   (note-the-effects! true)
   (quiet-the-history!)
   (rf/dispatch [::events/signed-in {"sub" "a1" "preferred_username" "ana" "name" "Ana" "realm_access" {"roles" ["patient"]}}])
   (is (nil? (epoch)))
   (is (= [] @asked) "nothing at all is asked")))

;; ---------------------------------------------------------------------------
;; The beat

(deftest a-beat-reads-the-count-again-asks-the-page-to-look-again-and-arms-the-next
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.watch/beat 1])
   (is (= [(watch/count-request 1)] (counts-asked)))
   (is (= [[:pulse.spa.watch/beat 1]] (beats-armed)) "the next beat belongs to the same run")
   (is (= [[:pulse.spa.inbox/check {:show :all}]] @looked) "the inbox is asked to look again, given the parameters of the page")
   (testing "every beat does the same"
     (rf/dispatch [:pulse.spa.watch/beat 1])
     (is (= 2 (count (counts-asked))))
     (is (= 2 (count (beats-armed))))
     (is (= 2 (count @looked))))))

(deftest a-page-with-nothing-to-look-at-again-is-not-asked-to
  (doseq [here [{:name :panel :params {:show :all :q ""}} {:name :patient :params {:id "pt-ana" :measurement nil :days 14}} {:name :not-found :params {}} nil]]
    (rf-test/run-test-sync
     (note-the-effects! true)
     (signed-in! here)
     (rf/dispatch [:pulse.spa.watch/start])
     (reset! asked [])
     (rf/dispatch [:pulse.spa.watch/beat 1])
     (testing (pr-str here)
       (is (= [] @looked))
       (is (= [(watch/count-request 1)] (counts-asked)) "but the count is read all the same")))))

(deftest a-person-the-page-is-not-for-has-it-not-asked-to-look-again
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (swap! db/app-db assoc :person {:id "a1" :name "Ana" :roles #{:patient}})
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/beat 1])
   (is (= [] @looked))))

(deftest a-tab-that-is-hidden-asks-for-nothing-and-the-beat-goes-on
  (rf-test/run-test-sync
   (note-the-effects! false)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.watch/beat 1])
   (is (= [] (counts-asked)) "no count for a tab nobody looks at")
   (is (= [] @looked))
   (is (= [[:pulse.spa.watch/beat 1]] (beats-armed)) "but the next beat is armed, so that it asks again when the tab is seen")))

(deftest a-beat-of-a-run-that-is-over-does-nothing-and-arms-nothing
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/visible])
   (is (= 2 (epoch)))
   (reset! asked [])
   (reset! looked [])
   (rf/dispatch [:pulse.spa.watch/beat 1])
   (is (= [] @asked) "so that however often the tab is hidden and seen there is one beat alive")
   (is (= [] @looked))
   (testing "a beat of a run that never was"
     (rf/dispatch [:pulse.spa.watch/beat 7])
     (is (= [] @asked)))))

(deftest a-workspace-that-is-no-longer-signed-in-stops-the-beat
  (doseq [status [:unauthorized :refused :signed-out :failed :starting nil]]
    (rf-test/run-test-sync
     (note-the-effects! true)
     (signed-in! inbox)
     (rf/dispatch [:pulse.spa.watch/start])
     (swap! db/app-db assoc :status status)
     (reset! asked [])
     (rf/dispatch [:pulse.spa.watch/beat 1])
     (testing (pr-str status)
       (is (= [] @asked) "it asks nothing and arms nothing: the beat is over")
       (is (= [] @looked))))))

;; ---------------------------------------------------------------------------
;; The tab is seen again

(deftest a-tab-that-is-seen-again-begins-a-new-run-and-reads-at-once
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.watch/visible])
   (is (= 2 (epoch)) "a run of its own")
   (is (= [(watch/count-request 2)] (counts-asked)))
   (is (= [[:pulse.spa.watch/beat 2]] (beats-armed)) "with its own beat")
   (is (= [[:pulse.spa.inbox/check {:show :all}]] @looked) "and the page looks again, since what it shows may be old")))

(deftest the-count-that-is-known-stays-while-the-new-one-is-asked-for
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 3 :items []}}])
   (rf/dispatch [:pulse.spa.watch/visible])
   (is (= 3 (subs/alert-count (state) nil)) "not blanked")))

(deftest a-tab-that-is-seen-when-nobody-is-signed-in-begins-nothing
  (doseq [status [:unauthorized :refused :signed-out :failed :starting nil]]
    (rf-test/run-test-sync
     (note-the-effects! true)
     (swap! db/app-db assoc :status status)
     (rf/dispatch [:pulse.spa.watch/visible])
     (testing (pr-str status)
       (is (= [] @asked))
       (is (nil? (epoch)))))))

;; ---------------------------------------------------------------------------
;; The count, out of turn

(deftest the-count-can-be-read-out-of-turn-without-moving-the-beat
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (reset! asked [])
   (rf/dispatch [:pulse.spa.watch/now])
   (is (= [(watch/count-request 1)] (counts-asked)) "of the run that is going")
   (is (= [] (beats-armed)) "and no beat is armed by it")
   (is (= 1 (epoch)))
   (testing "and not by a workspace that is no longer signed in"
     (swap! db/app-db assoc :status :unauthorized)
     (reset! asked [])
     (rf/dispatch [:pulse.spa.watch/now])
     (is (= [] @asked)))))

;; ---------------------------------------------------------------------------
;; The answers

(deftest the-answer-is-the-count-of-the-navigation
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 4 :offset 0 :limit 1 :items [{:id "a"}]} :request-id "r"}])
   (is (= {:state :loaded :total 4} (get-in (state) [:watch :count])))
   (is (= 4 (subs/alert-count (state) nil)))
   (testing "the next one replaces it"
     (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 0 :items []}}])
     (is (= 0 (subs/alert-count (state) nil))))))

(deftest a-count-that-could-not-be-read-is-a-question-mark-in-the-navigation-and-never-zero
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 4 :items []}}])
   (rf/dispatch [:pulse.spa.watch/count-failed 1 {:kind :timeout}])
   (is (= {:state :failed :kind :timeout} (get-in (state) [:watch :count])))
   (is (= :unknown (subs/alert-count (state) nil)))
   (testing "until one can be read"
     (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 2 :items []}}])
     (is (= 2 (subs/alert-count (state) nil))))))

(deftest an-answer-that-is-not-what-the-api-answers-is-a-count-that-could-not-be-read
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:items []}}])
   (is (= :unknown (subs/alert-count (state) nil)))))

(deftest the-answer-to-a-run-that-is-over-is-not-heard
  (rf-test/run-test-sync
   (note-the-effects! true)
   (signed-in! inbox)
   (rf/dispatch [:pulse.spa.watch/start])
   (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 4 :items []}}])
   (rf/dispatch [:pulse.spa.watch/visible])
   (rf/dispatch [:pulse.spa.watch/counted 1 {:status 200 :body {:total 99 :items []}}])
   (rf/dispatch [:pulse.spa.watch/count-failed 1 {:kind :server}])
   (is (= 4 (subs/alert-count (state) nil)) "it is the answer to a question nobody asks any more")
   (rf/dispatch [:pulse.spa.watch/counted 2 {:status 200 :body {:total 5 :items []}}])
   (is (= 5 (subs/alert-count (state) nil)))))

;; ---------------------------------------------------------------------------
;; The tab and the document

(defn- with-document
  "Calls `(f seen document)` with a stand-in for the document in place, `seen` being an atom of what the page was asked, and takes it away
  after."
  [visibility f]
  (let [seen (atom [])
        document #js {:visibilityState visibility
                      :addEventListener (fn [event listener] (swap! seen conj [:listen event listener]))}
        before (aget js/globalThis "document")]
    (aset js/globalThis "document" document)
    (try
      (f seen document)
      (finally
        (aset js/globalThis "document" before)))))

(deftest the-tab-is-seen-unless-the-document-says-it-is-hidden
  (doseq [[visibility seen?] {"visible" true "hidden" false "prerender" true nil true}]
    (with-document visibility
      (fn [_ _]
        (let [cofx ((registrar/get-handler :cofx ::watch-events/visible?) {} nil)]
          (is (= seen? (:visible? cofx)) (pr-str visibility)))))))

(deftest the-document-is-listened-to-once-and-a-tab-that-is-seen-begins-a-run
  (rf-test/run-test-sync
   (reset! @#'watch-events/listening? false)
   (let [dispatched (atom [])]
     (quietly #(rf/reg-event-fx :pulse.spa.watch/visible (fn [_ event] (swap! dispatched conj event) {})))
     (with-document "hidden"
       (fn [seen document]
         (let [listen! (registrar/get-handler :fx ::watch-events/listen)]
           (listen! nil)
           (listen! nil)
           (is (= 1 (count @seen)) "once, whatever the number of runs")
           (let [[_ event listener] (first @seen)]
             (is (= "visibilitychange" event))
             (testing "a tab that is hidden does nothing"
               (listener)
               (is (= [] @dispatched)))
             (testing "a tab that is seen begins a run"
               (set! (.-visibilityState document) "visible")
               (listener)
               (is (= [[:pulse.spa.watch/visible]] @dispatched))))))))))

(deftest the-effects-and-events-of-the-beat-are-registered
  (doseq [effect [::watch-events/listen]]
    (is (some? (registrar/get-handler :fx effect)) (str effect)))
  (doseq [event [:pulse.spa.watch/start :pulse.spa.watch/beat :pulse.spa.watch/visible :pulse.spa.watch/now
                 :pulse.spa.watch/counted :pulse.spa.watch/count-failed]]
    (is (some? (registrar/get-handler :event event)) (str event))))
