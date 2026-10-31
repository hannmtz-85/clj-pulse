(ns pulse.spa.jobs-page-details-test
  "What a mutation analysis of `pulse.spa.pages.jobs` found that the first tests did not look at: that the figure of the runs that failed is
  in the colour of attention only when one did, that a run with nothing to tell has no empty line, that an alert for a failure with
  nothing to say has only its title, the words of the pages for a person who may not see the jobs and for jobs that could not be loaded,
  and the layout of the rows. Pure hiccup, so it runs on the JVM and on Node over the same data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pulse.spa.jobs :as jobs]
            [pulse.spa.pages.jobs :as page]
            [pulse.spa.test.jobs-fixtures :as fx]
            [pulse.ui.test.hiccup :as hx]))

(def ^:private links {:panel "/" :alerts "/alerts" :appointments "/appointments" :jobs "/jobs"})

(defn- content
  ([state] (content state {:links links :handlers {:retry (fn [])}}))
  ([state options] (page/content state options)))

(defn- words [tree] (hx/words tree))

(defn- state-of [& items] {:now fx/now :list (fx/loaded {:total (count items) :items (vec items)})})

(defn- figure
  "The figure of the page named `label`: the box with its label and its number."
  [tree label]
  (first (filter #(str/starts-with? (words %) label) (hx/select tree #(hx/has-class? % "shadow-card")))))

(defn- number-of [figure] (second (hx/by-tag figure :p)))

(deftest the-figure-of-the-runs-that-failed-is-in-the-colour-of-attention-only-when-one-did
  (let [with-failure (content (fx/settled))
        without (content (state-of fx/evaluate-rules fx/simulate-devices))]
    (is (not= (hx/classes (number-of (figure with-failure "Jobs"))) (hx/classes (number-of (figure with-failure "Last run failed"))))
        "a failed run colours the figure")
    (is (= (hx/classes (number-of (figure without "Jobs"))) (hx/classes (number-of (figure without "Last run failed"))))
        "and with none it is like the others")))

(defn- spans-of-last-run
  "How many spans the line of the last run of a job that ran as `last-run` says holds."
  [last-run]
  (count (hx/by-tag (first (hx/by-tag (content (state-of {:id "odd" :lastRun last-run})) :dd)) :span)))

(deftest a-run-with-nothing-to-tell-has-no-empty-line
  (is (= (dec (spans-of-last-run {:outcome "ok" :startedAt "2026-10-13T12:00:00Z"}))
         (spans-of-last-run {:outcome "ok" :startedAt "soon" :durationMs "slow"}))
      "the line of the time is not there when there is no time to tell: a chip, and not a chip and an empty span"))

(deftest an-alert-for-a-failure-with-nothing-to-say-has-only-its-title
  (let [tree (content (state-of {:id "odd" :lastRun {:outcome "failed"}}))
        alert (first (hx/select tree #(hx/has-class? % "border-tone-high-edge")))]
    (is (some? alert))
    (is (= "The last run of odd failed" (words alert)))
    (is (empty? (hx/select alert #(hx/has-class? % "text-sm"))) "no empty text under the title")))

(defn- visible-paragraphs
  "The paragraphs of a page that a person sees: the one for a screen reader says the same words, and a test of the whole text would be
  satisfied by it."
  [tree]
  (map words (remove #(hx/has-class? % "sr-only") (hx/by-tag tree :p))))

(defn- an-element-says?
  "Whether some element of `tree` has exactly the words `text`."
  [tree text]
  (boolean (seq (hx/select tree #(= text (words %))))))

(deftest a-job-that-is-running-with-no-time-says-only-that-it-is
  (is (an-element-says? (content (state-of (assoc fx/simulate-devices :runningSince nil))) "Running") "the chip says that it runs")
  (is (an-element-says? (content (state-of fx/simulate-devices)) "Running since 14:00:15") "and, when it is known, since when"))

(deftest the-page-for-a-person-who-may-not-see-the-jobs-says-so-in-its-own-words
  (let [tree (content {:now fx/now :list {:state :failed :kind :forbidden}})]
    (is (some #{"You cannot see the jobs"} (visible-paragraphs tree)) "the title, and not only the words for a screen reader")
    (is (an-element-says? tree "Only the clinic's administrator can."))
    (is (= ["Back to the panel"] (map words (hx/by-tag tree :a))))
    (is (= "/" (:href (hx/attributes (first (hx/by-tag tree :a))))))))

(deftest the-page-for-jobs-that-could-not-be-loaded-says-so-in-its-own-words
  (let [tree (content {:now fx/now :list fx/failed})]
    (is (some #{"The jobs could not be loaded"} (visible-paragraphs tree)) "the title of the message")
    (is (= ["Try again"] (map words (hx/by-tag tree :button))))
    (is (not (str/includes? (words tree) "No jobs")))))

(deftest the-list-has-a-name-while-it-loads-and-when-it-is-loaded
  (doseq [state [(jobs/start fx/now) (fx/settled)]]
    (is (= 1 (count (hx/select (content state) #(= "Jobs of the scheduler" (:aria-label (hx/attributes %)))))) (pr-str (:list state)))))

(deftest the-text-of-a-row-gives-way-to-its-state-chip-and-the-list-reaches-the-edges-of-its-card
  (let [tree (content (fx/settled))
        loading (content (jobs/start fx/now))]
    (is (seq (hx/select tree #(hx/has-class? % "basis-44"))) "a row says that its chip goes under the text when it is wide")
    (is (= 3 (count (hx/select tree #(hx/has-class? % "basis-44")))))
    (is (seq (hx/select loading #(hx/has-class? % "mt-3"))) "the skeleton of the list is in a card with no padding of its own")
    (is (seq (hx/select tree #(hx/has-class? % "mt-3"))))))

(deftest what-the-scheduler-reports-of-a-failure-breaks-wherever-it-must-in-the-alert
  (let [state (state-of (assoc fx/send-reminders :id (apply str (repeat 12 "sendreminders"))
                               :lastRun {:outcome "failed" :startedAt "2026-10-13T12:00:00Z" :durationMs 10012
                                         :error "java.util.concurrent.CompletionException"}))
        alert (first (hx/select (content state) #(hx/has-class? % "border-tone-high-edge")))
        broken (fn [text] (first (filter #(= text (words %)) (hx/select alert #(hx/has-class? % "wrap-anywhere")))))]
    (is (some? alert))
    (is (some? (broken (str "The last run of " (apply str (repeat 12 "sendreminders")) " failed"))) "its title")
    (is (some? (broken "java.util.concurrent.CompletionException after 10 s. The next run is at 14:01:00.")) "and its text")))
