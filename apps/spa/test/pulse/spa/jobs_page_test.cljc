(ns pulse.spa.jobs-page-test
  "The page of the scheduler's jobs (ADR-0029): what it says in each of its states, that what could not be loaded is said in its place and
  is never shown as empty, that a control with nothing to lead to is not there, that what the scheduler wrote cannot add markup to the page
  and that every state passes the checks of accessibility. Pure hiccup, so these run on the JVM and on Node, over the same data as the tests
  of the machine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.pages.jobs :as page]
            [pulse.spa.test.jobs-fixtures :as fx]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private sam {:id "s1" :username "admin@pulse.test" :name "Sam Admin" :roles #{:clinic-admin}})

(def ^:private links {:panel "/" :alerts "/alerts" :appointments "/appointments" :jobs "/jobs"})

(def ^:private handlers {:retry (fn [])})

(defn- content
  ([state] (content state {}))
  ([state options] (page/content state (merge {:links links :handlers handlers} options))))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- button-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :button))))

(defn- link-named [tree text]
  (first (filter #(= text (words %)) (hx/by-tag tree :a))))

(defn- rows
  "The rows of the list of jobs."
  [tree]
  (hx/by-tag (first (hx/select tree #(= "Jobs of the scheduler" (:aria-label (hx/attributes %))))) :li))

(defn- row-of [tree id]
  (first (filter #(= id (words (first (hx/select % (fn [node] (hx/has-class? node "font-semibold")))))) (rows tree))))

(defn- fact [row term]
  (let [terms (map words (hx/by-tag row :dt))
        details (hx/by-tag row :dd)]
    (some (fn [[t d]] (when (= term t) (words d))) (map vector terms details))))

(defn- status [tree]
  (words (first (hx/select tree #(= "status" (:role (hx/attributes %)))))))

;; ---------------------------------------------------------------------------
;; The jobs that have loaded

(deftest the-page-says-what-it-is-and-tells-a-screen-reader-what-is-on-it
  (let [tree (content (fx/settled))]
    (is (= ["Jobs"] (map words (hx/by-tag tree :h1))) "the one heading of the page")
    (is (says? tree "What the scheduler does on its own, and how the last run of each went."))
    (is (= "3 jobs, 1 running, 1 failed its last run" (status tree)))))

(deftest three-figures-count-the-jobs-the-running-ones-and-the-failed-ones
  (let [tree (content (fx/settled))
        figures (hx/select tree #(hx/has-class? % "shadow-card"))
        stats (filter #(and (hx/by-tag % :p) (= 2 (count (hx/by-tag % :p)))) figures)]
    (is (= ["Jobs3" "Running now1" "Last run failed1"] (map words (take 3 stats))) "the label and the figure")))

(deftest a-job-that-failed-its-last-run-has-an-alert-and-the-figure-is-in-the-colour-of-attention
  (let [tree (content (fx/settled))
        alert (first (hx/select tree #(hx/has-class? % "border-tone-high-edge")))]
    (is (some? alert))
    (is (says? alert "The last run of send-reminders failed"))
    (is (says? alert "HttpTimeoutException after 10 s. The next run is at 14:01:00."))))

(deftest with-no-failed-run-there-is-no-alert-and-the-figure-has-no-colour
  (let [state (assoc-in (fx/settled) [:list :data :items 2 :lastRun :outcome] "ok")
        tree (content state)]
    (is (empty? (hx/select tree #(hx/has-class? % "border-tone-high-edge"))))
    (is (says? tree "Last run failed0"))
    (is (= "3 jobs, 1 running" (status tree)))))

(deftest each-job-is-a-row-with-its-id-what-it-does-and-its-state
  (let [tree (content (fx/settled))]
    (is (= 3 (count (rows tree))))
    (let [row (row-of tree "evaluate-rules")]
      (is (says? row "Evaluates the clinical rules over the latest readings and raises alerts"))
      (is (says? row "Idle")))
    (is (says? (row-of tree "simulate-devices") "Running since 14:00:15") "a job that is running says since when")
    (is (not (says? (row-of tree "simulate-devices") "Idle")))))

(deftest each-job-says-when-it-is-due-when-it-last-ran-and-how-it-went
  (let [tree (content (fx/settled))
        rules (row-of tree "evaluate-rules")
        simulator (row-of tree "simulate-devices")
        reminders (row-of tree "send-reminders")]
    (is (= "Every 30 seconds" (fact rules "Schedule")))
    (is (= "14:00:30" (fact rules "Next run")))
    (is (= "OK14:00:00 · 1.8 s6 patients, 0 raised, 4 already raised, 3 still active, 5 not applicable, 0 failed" (fact rules "Last run")))
    (is (= "214 ok, 0 failed, 0 interrupted, 0 skipped" (fact rules "Runs")))
    (is (= "OK14:00:00 · 620 ms7 readings, 7 written, 0 failed" (fact simulator "Last run")))
    (is (= "Every 15 seconds" (fact simulator "Schedule")))
    (is (= "Failed14:00:00 · 10 sError: HttpTimeoutException" (fact reminders "Last run")) "the class of the error, and no more")
    (is (= "Every minute" (fact reminders "Schedule")))))

(deftest the-outcome-of-a-run-is-a-word-and-not-a-colour-alone
  (let [run-with (fn [outcome]
                   (fact (row-of (content {:now fx/now :list (fx/loaded {:total 1 :items [(assoc-in fx/evaluate-rules [:lastRun :outcome] outcome)]})})
                                 "evaluate-rules")
                         "Last run"))]
    (is (str/starts-with? (run-with "ok") "OK"))
    (is (str/starts-with? (run-with "failed") "Failed"))
    (is (str/starts-with? (run-with "interrupted") "Interrupted"))
    (is (str/starts-with? (run-with "boom") "Unknown") "a run that ended in a way nobody knows is not said to have gone well")))

(deftest a-job-that-has-not-run-and-is-not-due-says-so
  (let [tree (content {:now fx/now
                       :list (fx/loaded {:total 1 :items [(assoc fx/never-run :nextRun nil)]})})
        row (row-of tree "tidy-up")]
    (is (= "Has not run yet" (fact row "Last run")))
    (is (= "Not scheduled" (fact row "Next run")))
    (is (= "Every day at 08:00 (Europe/Madrid)" (fact row "Schedule")))))

(deftest what-the-scheduler-did-not-say-is-left-out
  (let [tree (content {:now fx/now
                       :list (fx/loaded {:total 1 :items [{:id "odd" :lastRun {:outcome "ok"} :counts {} :schedule {:type "weekly"}}]})})
        row (row-of tree "odd")]
    (is (= ["Last run" "Next run"] (map words (hx/by-tag row :dt))) "no schedule and no runs to tell of")
    (is (= "OK" (fact row "Last run")))
    (is (= 1 (count (hx/select row #(hx/has-class? % "badge")))) "the chip of the job, which is idle")))

(deftest the-list-of-jobs-that-has-none-says-so
  (let [tree (content {:now fx/now :list (fx/loaded {:total 0 :items []})})]
    (is (says? tree "No jobs"))
    (is (says? tree "The scheduler has no jobs."))
    (is (says? tree "Jobs0"))
    (is (empty? (rows tree)))))

;; ---------------------------------------------------------------------------
;; The jobs that are on their way, and the ones that could not be had

(deftest before-the-jobs-are-in-the-page-is-a-title-and-grey-shapes
  (doseq [state [nil (jobs/start fx/now)]]
    (let [tree (content state)]
      (is (= ["Jobs"] (map words (hx/by-tag tree :h1))))
      (is (= "Loading the jobs" (status tree)))
      (is (seq (hx/select tree #(hx/has-class? % "skeleton"))))
      (is (not (says? tree "Running now")) "a figure that is not known is not zero")
      (is (not (says? tree "No jobs")) "and the jobs that are on their way are not none"))))

(deftest jobs-that-could-not-be-loaded-are-a-message-in-their-place-and-not-an-empty-list
  (doseq [kind [:server :timeout :unreachable :unexpected]]
    (let [tree (content {:now fx/now :list {:state :failed :kind kind}})]
      (is (says? tree "The jobs could not be loaded") (name kind))
      (is (says? tree "The problem is on our side, not yours."))
      (is (some? (button-named tree "Try again")))
      (is (not (says? tree "No jobs")))
      (is (not (says? tree "Running now")))
      (is (= "The jobs could not be loaded" (status tree))))))

(deftest there-is-no-button-to-try-again-when-there-is-nothing-to-call
  (let [tree (content {:now fx/now :list fx/failed} {:handlers {}})]
    (is (says? tree "The jobs could not be loaded"))
    (is (nil? (button-named tree "Try again")) "a control with nothing to do is not there")))

(deftest pressing-try-again-asks-the-handler
  (let [pressed (atom 0)
        tree (content {:now fx/now :list fx/failed} {:handlers {:retry #(swap! pressed inc)}})]
    ((:on-click (hx/attributes (button-named tree "Try again"))))
    (is (= 1 @pressed))))

(deftest a-person-the-api-refuses-is-told-the-jobs-are-not-theirs
  (let [tree (content {:now fx/now :list {:state :failed :kind :forbidden}})]
    (is (says? tree "You cannot see the jobs"))
    (is (says? tree "Only the clinic's administrator can."))
    (is (= "/" (:href (hx/attributes (link-named tree "Back to the panel")))))
    (is (not (says? tree "The jobs could not be loaded")) "it is not a failure of the system")
    (is (nil? (button-named tree "Try again")) "and trying again would be refused again")
    (is (nil? (link-named (content {:now fx/now :list {:state :failed :kind :forbidden}} {:links {}}) "Back to the panel"))
        "with no panel to lead to there is no button")))

;; ---------------------------------------------------------------------------
;; What the scheduler wrote

(deftest what-the-scheduler-wrote-cannot-add-markup-to-the-page
  (let [hostile "<script>alert(1)</script>"
        state {:now fx/now
               :list (fx/loaded {:total 1
                                 :items [(-> fx/send-reminders
                                             (assoc :id hostile :description hostile)
                                             (assoc-in [:lastRun :error] hostile))]})}
        tree (content state)]
    (is (empty? (hx/by-tag tree :script)))
    (is (says? tree hostile) "the text is the text")))

(deftest a-description-or-an-error-that-is-very-long-wraps-in-the-places-it-can
  (let [long-text (apply str (repeat 120 "Ñandú"))
        state {:now fx/now
               :list (fx/loaded {:total 1
                                 :items [(-> fx/send-reminders
                                             (assoc :id long-text :description long-text)
                                             (assoc-in [:lastRun :error] long-text))]})}
        tree (content state)
        row (first (rows tree))]
    (is (says? row long-text))
    (is (seq (hx/select row #(and (hx/has-class? % "wrap-anywhere") (= long-text (words %))))) "the id and the description can break anywhere")
    (is (seq (hx/select row #(and (hx/has-class? % "wrap-anywhere") (str/includes? (words %) "Error: ")))) "and so can the error")))

;; ---------------------------------------------------------------------------
;; The workspace around it

(def ^:private states
  {"before it begins" nil
   "on its way" (jobs/start fx/now)
   "loaded" (fx/settled)
   "no job" {:now fx/now :list (fx/loaded {:total 0 :items []})}
   "a job that has not run" {:now fx/now :list (fx/loaded {:total 1 :items [fx/never-run]})}
   "nothing failed" (assoc-in (fx/settled) [:list :data :items 2 :lastRun :outcome] "ok")
   "the jobs could not be loaded" {:now fx/now :list fx/failed}
   "the person is refused" {:now fx/now :list {:state :failed :kind :forbidden}}})

(deftest every-state-of-the-jobs-passes-the-checks-of-accessibility
  (doseq [[label state] states
          :let [tree (page/page state {:person sam :links links :handlers (assoc handlers :sign-out (fn []))})]]
    (is (= [] (a11y/problems (layout/document {:title "Pulse"} [:div {:id "app"} tree]) {:page? true})) label)))

(deftest the-workspace-around-the-jobs-has-them-as-the-current-page-in-the-group-of-the-administrator
  (let [tree (page/page (fx/settled) {:person sam :alert-count 2 :links links :handlers {:sign-out (fn [])}})
        nav (first (hx/select tree #(= "Workspace" (:aria-label (hx/attributes %)))))
        current (first (hx/select nav #(= "page" (:aria-current (hx/attributes %)))))]
    (is (empty? (hx/by-tag tree :input)) "a box that searched nothing here would be a control with nothing to do")
    (is (= "Jobs" (words current)))
    (is (= ["Panel" "Alerts 2" "Appointments" "Jobs"] (map words (hx/by-tag nav :a))))
    (is (says? nav "Administration") "the group that is the administrator's")))
