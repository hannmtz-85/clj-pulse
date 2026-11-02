(ns pulse.spa.patient-refresh-test
  "The chart of a patient when an alert is acknowledged (ADR-0029, points 3 and 8): the summary, which holds the alerts, is asked for
  again, and the chart is not blanked to do it; if that fails the chart keeps what it has and says that the alerts could not be updated.
  And the page: the buttons that acknowledge an alert and lead to all of them, busy while the command is on its way, with what is told
  when it was not done. Pure, so these run on the JVM and on Node."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.patient :as page]
            [pulse.spa.patient :as patient]
            [pulse.spa.test.patient-fixtures :as fx]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.hiccup :as hx]))

(defn- ok [body] {:result :ok :body body})

(defn- ana-after-ack
  "The summary of Ana once the alert is acknowledged: no active alert."
  []
  (assoc fx/ana-summary :alerts []))

(defn- refreshing
  "The chart of Ana with the summary being asked for again, the call on its way."
  []
  (first (patient/pump (patient/refresh (fx/settled-ana)) 1)))

;; ---------------------------------------------------------------------------
;; Asking for the summary again

(deftest the-summary-is-asked-for-again-without-the-chart-being-blanked
  (let [chart (patient/refresh (fx/settled-ana))]
    (is (= [:refresh] (:queue chart)))
    (is (= (:summary (fx/settled-ana)) (:summary chart)) "what the chart holds stays")
    (is (= (:evidence (fx/settled-ana)) (:evidence chart)))
    (is (false? (patient/settled? chart)) "but it is not settled: a call is waiting")
    (let [[started calls] (patient/pump chart 4)]
      (is (= #{:refresh} (:inflight started)))
      (is (= [{:method :get :id [:chart :refresh] :path ["api" "patients" "pt-ana" "summary"]
               :on-success [:pulse.spa.patient/succeeded 4 :refresh] :on-failure [:pulse.spa.patient/failed 4 :refresh]}]
             calls) "the call of the summary, with an id of its own so that it never stops the one that loads it"))))

(deftest a-call-for-the-summary-again-stops-the-one-before-it
  (let [asked (refreshing)
        again (patient/refresh asked)]
    (is (= #{:refresh} (:inflight asked)))
    (is (= #{} (:inflight again)) "the new call has the same id and stops it")
    (is (= [:refresh] (:queue again)) "and it is not queued twice")
    (is (= [:refresh] (:queue (patient/refresh again))))))

(deftest the-summary-that-comes-replaces-the-one-the-chart-has
  (let [answered (patient/answer (refreshing) :refresh (ok (ana-after-ack)))]
    (is (= (fx/loaded (ana-after-ack)) (:summary answered)))
    (is (nil? (:refresh-failed answered)))
    (is (= #{} (:inflight answered)))
    (is (true? (patient/settled? answered)))
    (is (= [] (:alerts (:data (:summary answered)))))
    (is (= :loaded (get-in answered [:series :state])) "and the rest of the chart is as it was")))

(deftest the-readings-behind-the-alerts-that-are-still-there-are-kept-and-not-asked-for-again
  (let [answered (patient/answer (refreshing) :refresh (ok fx/ana-summary))]
    (is (= (:evidence (fx/settled-ana)) (:evidence answered)))
    (is (= [] (:queue answered)))))

(deftest the-readings-behind-a-new-alert-are-asked-for-and-those-no-alert-needs-any-more-are-dropped
  (let [with-glucose (update fx/ana-summary :alerts conj {:id "flag-pt-ana-R3" :text "Glucose out of range" :status "active" :since "2026-10-13T09:00:00Z"})
        answered (patient/answer (refreshing) :refresh (ok with-glucose))]
    (is (= [[:evidence :glucose]] (:queue answered)))
    (is (= {:state :loading} (get-in answered [:evidence :glucose])))
    (is (= (get-in (fx/settled-ana) [:evidence :blood-pressure]) (get-in answered [:evidence :blood-pressure]))))
  (let [answered (patient/answer (refreshing) :refresh (ok (ana-after-ack)))]
    (is (= {} (:evidence answered)) "the alert is gone, and so is what was read for it")))

(deftest a-summary-that-could-not-be-read-again-leaves-the-chart-as-it-is-and-says-so
  (doseq [kind [:server :timeout :unreachable :forbidden :not-found]
          :let [answered (patient/answer (refreshing) :refresh {:result :failed :kind kind})]]
    (testing (name kind)
      (is (= (:summary (fx/settled-ana)) (:summary answered)) "what the chart holds is not taken away")
      (is (= kind (:refresh-failed answered)))
      (is (= #{} (:inflight answered)))
      (is (true? (patient/settled? answered)) "nothing is on its way, and a retry can be offered")))
  (testing "a failure with no kind is an unexpected one"
    (is (= :unexpected (:refresh-failed (patient/answer (refreshing) :refresh {:result :failed})))))
  (testing "and so is an answer that is not what the api answers"
    (doseq [body [nil "text" [] 5]]
      (let [answered (patient/answer (refreshing) :refresh (ok body))]
        (is (= :unexpected (:refresh-failed answered)) (pr-str body))
        (is (= (:summary (fx/settled-ana)) (:summary answered)))))))

(deftest a-summary-that-comes-after-a-failure-takes-the-failure-away
  (let [failed-once (patient/answer (refreshing) :refresh {:result :failed :kind :server})
        again (first (patient/pump (patient/refresh failed-once) 1))]
    (is (nil? (:refresh-failed (patient/refresh failed-once))) "asking again clears it")
    (is (nil? (:refresh-failed (patient/answer again :refresh (ok (ana-after-ack))))))))

(deftest at-the-deadline-a-summary-that-is-not-back-has-failed-to-be-read-again
  (let [expired (patient/expire (refreshing))]
    (is (= :timeout (:refresh-failed expired)))
    (is (= [:refresh] (:cancelled expired)))
    (is (= (:summary (fx/settled-ana)) (:summary expired)) "and the chart is as it was")
    (is (true? (patient/settled? (dissoc expired :cancelled)))))
  (testing "one that was queued and not started has failed as well"
    (let [expired (patient/expire (patient/refresh (fx/settled-ana)))]
      (is (= :timeout (:refresh-failed expired)))
      (is (= [] (:cancelled expired)))))
  (testing "and a chart that was not being read again has not failed to be"
    (is (nil? (:refresh-failed (patient/expire (fx/settled-ana)))))))

(deftest a-summary-that-could-not-be-read-again-is-asked-for-again-by-a-retry
  (let [failed (patient/answer (refreshing) :refresh {:result :failed :kind :server})]
    (is (= [:refresh] (patient/failed-tasks failed)))
    (let [retried (patient/retry failed)]
      (is (= [:refresh] (:queue retried)))
      (is (nil? (:refresh-failed retried)))
      (is (= (:summary failed) (:summary retried)) "the chart is not blanked for it either")))
  (is (= [] (patient/failed-tasks (fx/settled-ana)))))

(deftest a-retry-asks-for-the-summary-again-and-for-what-else-failed
  (let [chart (-> (patient/answer (refreshing) :refresh {:result :failed :kind :server})
                  (assoc-in [:evidence :blood-pressure] {:state :failed :kind :server}))
        tasks (patient/failed-tasks chart)]
    (is (= [[:evidence :blood-pressure] :refresh] tasks))
    (is (= [[:evidence :blood-pressure] :refresh] (:queue (patient/retry chart))))))

(deftest a-chart-is-settled-when-nothing-is-loading-and-the-summary-is-not-being-read-again
  (is (true? (patient/settled? (fx/settled-ana))))
  (is (true? (patient/refreshing? (refreshing))))
  (is (true? (patient/refreshing? (patient/refresh (fx/settled-ana)))))
  (is (false? (patient/refreshing? (fx/settled-ana))))
  (is (false? (patient/settled? (refreshing))))
  (testing "a part loading is as before"
    (is (false? (patient/settled? (assoc-in (fx/settled-ana) [:summary :state] :loading))))))

;; ---------------------------------------------------------------------------
;; What the chart shows of it

(deftest the-model-names-the-alert-it-acts-on-by-its-text-and-the-patient
  (let [m (patient/model (fx/settled-ana))
        alert (first (get-in m [:alerts :items]))]
    (is (= "BP stage 2 · Ana García" (:label alert)))
    (is (= "BP stage 2" (:text alert)))
    (is (= "flag-pt-ana-R1" (:id alert)))
    (is (not (contains? (:alerts m) :stale)) "and says nothing of being stale"))
  (testing "a patient whose name is not known yet is not named"
    (is (= "BP stage 2" (:label (first (get-in (patient/model (assoc (fx/settled-ana) :patient {:state :loading})) [:alerts :items])))))))

(deftest the-model-says-that-the-alerts-could-not-be-updated
  (let [failed (patient/answer (refreshing) :refresh {:result :failed :kind :timeout})
        m (patient/model failed)]
    (is (= :timeout (get-in m [:alerts :stale])))
    (is (= :loaded (get-in m [:alerts :state])) "what it shows is what it had")
    (is (= 1 (count (get-in m [:alerts :items]))))
    (is (str/includes? (:live m) "1 part could not be loaded") "and a screen reader is told so")))

;; ---------------------------------------------------------------------------
;; The page

(def ^:private rivera {:id "r1" :username "dr.rivera@pulse.test" :name "Elena Rivera" :roles #{:practitioner}})

(def ^:private links {:panel "/" :alerts "/alerts" :chart (fn [_] "/patients/pt-ana")})

(def ^:private handlers {:retry (fn []) :acknowledge (fn [_id _label])})

(defn- content
  ([state] (content state {}))
  ([state options] (page/content state (merge {:links links :handlers handlers} options))))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- button-named [tree text] (first (filter #(= text (words %)) (hx/by-tag tree :button))))

(defn- link-named [tree text] (first (filter #(= text (words %)) (hx/by-tag tree :a))))

(defn- alert-named [tree id] (first (hx/select tree #(= (str "alert-" id) (:id (hx/attributes %))))))

(deftest an-active-alert-has-the-buttons-to-acknowledge-it-and-to-see-them-all
  (let [tree (content (fx/settled-ana))
        card (alert-named tree "flag-pt-ana-R1")
        acknowledge (button-named card "Acknowledge")]
    (is (some? card))
    (is (= "ack-flag-pt-ana-R1" (:id (hx/attributes acknowledge))))
    (is (= "Acknowledge BP stage 2 · Ana García" (:aria-label (hx/attributes acknowledge))))
    (is (fn? (:on-click (hx/attributes acknowledge))))
    (is (= "/alerts" (:href (hx/attributes (link-named card "All alerts")))))
    (is (says? card "BP stage 2"))
    (is (says? card "The latest readings: 177/100 mmHg, today, 10:00; 172/100 mmHg, yesterday, 10:00; 165/98 mmHg, Oct 11, 10:00."))))

(deftest pressing-the-button-acknowledges-the-alert-and-tells-how-it-is-named
  (let [pressed (atom [])
        tree (content (fx/settled-ana) {:handlers {:acknowledge (fn [id label] (swap! pressed conj [id label]))}})]
    ((:on-click (hx/attributes (button-named tree "Acknowledge"))) nil)
    (is (= [["flag-pt-ana-R1" "BP stage 2 · Ana García"]] @pressed))))

(deftest the-buttons-are-not-there-without-something-to-lead-to
  (testing "no way to acknowledge"
    (is (nil? (button-named (content (fx/settled-ana) {:handlers {:retry (fn [])}}) "Acknowledge"))))
  (testing "no inbox to see"
    (is (nil? (link-named (content (fx/settled-ana) {:links (dissoc links :alerts)}) "All alerts"))))
  (testing "an alert with neither has no place for buttons"
    (let [card (alert-named (content (fx/settled-ana) {:handlers {} :links {:panel "/"}}) "flag-pt-ana-R1")]
      (is (empty? (hx/by-tag card :button)))
      (is (empty? (hx/by-tag card :a)))
      (is (not (hx/has-class? card "sm:grid-cols-[auto_1fr_auto]"))))))

(deftest the-mock-up-leads-the-button-where-the-handler-would-act
  (let [tree (content (fx/settled-ana) {:handlers {:retry (fn [])} :links (assoc links :acknowledge (fn [_] "/alerts"))})]
    (is (= "/alerts" (:href (hx/attributes (link-named tree "Acknowledge")))))
    (is (nil? (button-named tree "Acknowledge")))))

(deftest the-button-is-busy-from-the-click-until-the-summary-has-been-read-again
  (doseq [state [:sending :settling]
          :let [tree (content (fx/settled-ana) {:acks {"flag-pt-ana-R1" {:state state :label "BP stage 2 · Ana García"}}})
                button (button-named (alert-named tree "flag-pt-ana-R1") "Acknowledge")]]
    (testing (name state)
      (is (true? (:disabled (hx/attributes button))))
      (is (= "true" (:aria-busy (hx/attributes button)))))))

(deftest an-alert-that-was-not-acknowledged-says-why-and-its-button-is-free-again
  (let [message "You are not allowed to acknowledge this alert."
        tree (content (fx/settled-ana) {:acks {"flag-pt-ana-R1" {:state :failed :message message}}})
        card (alert-named tree "flag-pt-ana-R1")]
    (is (says? card message))
    (is (not (:disabled (hx/attributes (button-named card "Acknowledge")))))))

(deftest what-a-screen-reader-was-told-of-the-acknowledgement-is-said-in-place-of-the-state-of-the-chart
  (let [tree (content (fx/settled-ana) {:said "BP stage 2 · Ana García: Alert acknowledged."})]
    (is (= ["BP stage 2 · Ana García: Alert acknowledged."] (map words (hx/select tree #(= "status" (:role (hx/attributes %)))))))
    (is (= ["The chart of Ana García is loaded"] (map words (hx/select (content (fx/settled-ana)) #(= "status" (:role (hx/attributes %)))))))))

(deftest the-alerts-that-could-not-be-updated-are-said-above-the-ones-that-were-read-before
  (let [retry (fn [])
        failed (patient/answer (refreshing) :refresh {:result :failed :kind :server})
        tree (content failed {:handlers {:retry retry :acknowledge (fn [_ _])}})]
    (is (says? tree "The alerts could not be updated"))
    (is (says? tree "What you see may be out of date. The problem is on our side, not yours."))
    (is (says? tree "BP stage 2") "and the alerts that were read before are there")
    (is (some? (button-named (alert-named tree "flag-pt-ana-R1") "Acknowledge")) "and can be acknowledged")
    (is (= retry (:on-click (hx/attributes (first (filter #(= "Try again" (words %)) (hx/by-tag tree :button)))))) "with a way to try again")
    (testing "and there is nothing of it before it happens"
      (is (not (says? (content (fx/settled-ana)) "could not be updated"))))))

(deftest an-alert-of-a-rule-nobody-knows-can-be-acknowledged-all-the-same
  (let [state (assoc-in (fx/settled-ana) [:summary :data :alerts] [{:id "x" :text "Something else" :status "active" :since "2026-10-12T20:30:00Z"}])
        card (alert-named (content state) "x")]
    (is (some? (button-named card "Acknowledge")))
    (is (= "Acknowledge Something else · Ana García" (:aria-label (hx/attributes (button-named card "Acknowledge")))))))

(deftest every-state-with-an-acknowledgement-passes-the-checks-of-accessibility
  (doseq [[label state] {"loaded" (fx/settled-ana)
                         "refreshing" (refreshing)
                         "not updated" (patient/answer (refreshing) :refresh {:result :failed :kind :server})
                         "evidence failed" (assoc-in (fx/settled-ana) [:evidence :blood-pressure] {:state :failed :kind :server})}
          acks [nil {"flag-pt-ana-R1" {:state :sending}} {"flag-pt-ana-R1" {:state :failed :message "The alert was not acknowledged."}}]
          :let [tree (page/page state {:person rivera :links links :acks acks :said (when acks "Said.")
                                       :handlers (assoc handlers :sign-out (fn []))})]]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} tree]) {:page? true})) (str label " " (boolean acks)))))

(deftest the-workspace-around-the-chart-has-the-alerts-in-the-navigation-with-their-count
  (let [tree (page/page (fx/settled-ana) {:person rivera :alert-count 3 :links links :handlers {:sign-out (fn [])}})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))]
    (is (= ["Panel" "Alerts 3"] (map words (hx/by-tag nav :a))))
    (is (= "Panel" (words (first (hx/select nav #(= "page" (:aria-current (hx/attributes %))))))) "the chart is a page of the panel")))
