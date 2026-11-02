(ns pulse.spa.states-test
  "The inventory of the states of the workspace (ADR-0029, Phase 8, step 9): what each page says while it is on its way, when there is
  nothing to show, when some or all of it could not be loaded, when what the address names is not there and when the api refuses the
  person. The routes are held to `pulse.spa.test.states`: a route that loads has its wait and its failure, one that lists things has
  its empty state, one with an id in its path has its `gone`, one that is for the administrator alone has its `forbidden`, and a route
  that has none of that is `static`, so a page added to the table with no thought for what goes wrong fails here, as one with no
  roles fails to start. Every example says what it must and never what belongs to another state (a list that failed is never \"No
  alerts\"), has a way to try again where trying again can help and none where it cannot, differs from the other states of its route
  and passes the checks of accessibility. Pure hiccup, so these run on the JVM and on Node."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [pulse.spa.pages.start :as start]
            [pulse.spa.route :as route]
            [pulse.spa.test.states :as states]
            [pulse.ui.layout :as layout]
            [pulse.ui.test.a11y :as a11y]
            [pulse.ui.test.hiccup :as hx]))

(defn- words [tree] (hx/words tree))

(defn- says? [tree text] (str/includes? (words tree) text))

(defn- visible
  "`tree` without what only a screen reader reads (`sr-only`). The live region of a page repeats the message of its state, so a page that
  lacks the message would look as if it had it: what a state says must be said where a person can see it, and the wait, which is
  announced and shown as grey shapes, is the one state that is checked on everything."
  [tree]
  (walk/prewalk (fn [node] (if (and (vector? node) (keyword? (first node)) (hx/has-class? node "sr-only")) nil node)) tree))

(defn- seen-by
  "The part of `tree` that the examples of `state` are held to."
  [state tree]
  (if (= :loading state) tree (visible tree)))

(defn- the-document [tree]
  (layout/document {:title "Pulse"} [:div {:id "app"} tree]))

(defn- needed
  "The states a route must have an example of: those its data and its path ask for."
  [[path data]]
  (let [dialog? (and (:page data) (not= (:page data) (:name data)))
        loads? (seq (:start data))]
    (cond-> #{}
      (not loads?) (conj :static)
      loads? (into [:loading :failed])
      (and loads? (not dialog?)) (conj :empty)
      (str/includes? path ":id") (conj :gone)
      (= #{:clinic-admin} (:roles data)) (conj :forbidden))))

(def ^:private routes-by-name
  (into {} (map (fn [[_ data :as entry]] [(:name data) entry])) route/routes))

;; ---------------------------------------------------------------------------
;; The routes are held to the inventory

(deftest every-route-of-the-workspace-has-examples-and-no-example-is-of-a-route-that-is-not
  (is (= (set (keys routes-by-name)) (set (keys states/examples)))))

(deftest every-route-has-the-states-its-table-asks-for
  (doseq [[route-name entry] routes-by-name
          :let [have (set (keys (get states/examples route-name)))]]
    (testing (str route-name)
      (is (set/subset? (needed entry) have) (str "lacks " (pr-str (set/difference (needed entry) have))))
      (is (set/subset? have states/vocabulary) (str "has " (pr-str (set/difference have states/vocabulary))))
      (is (= (contains? have :static) (empty? (:start (second entry)))) "a page that loads is not static, and one that does not is"))))

(deftest the-need-for-states-is-what-the-routes-say-and-not-a-guess
  (testing "a page that loads must say it is loading and what it does when it fails"
    (is (= #{:loading :empty :failed} (needed ["/alerts" (second (routes-by-name :alerts))]))))
  (testing "an id in the path means that what it names may not be there"
    (is (contains? (needed (routes-by-name :patient)) :gone))
    (is (contains? (needed (routes-by-name :appointment-cancel)) :gone)))
  (testing "a dialog is over a list that has its own empty state"
    (is (= #{:loading :failed :gone} (needed (routes-by-name :appointment-confirm)))))
  (testing "the jobs are the administrator's"
    (is (contains? (needed (routes-by-name :jobs)) :forbidden)))
  (testing "a page with nothing to load is static"
    (is (= #{:static} (needed (routes-by-name :signed-out))))))

;; ---------------------------------------------------------------------------
;; What each example says

(def ^:private generic
  "What every state says whatever the page: the words that tell what the person can do, and those that would belong to another state."
  {:loading {:never ["could not be loaded" "Try again"]}
   :empty {:never ["could not be loaded" "Try again"]}
   :partial {:says ["could not be loaded" "Try again"]}
   :failed {:says ["could not be loaded" "Try again"]}
   :gone {:never ["Try again" "could not be loaded" "Loading"]}
   :forbidden {:never ["Try again" "could not be loaded" "Loading"]}
   :static {}})

(defn- all-examples
  "`[label state example]` for every example of every route."
  []
  (for [[route-name by-state] states/examples
        [state example] by-state]
    [(str route-name " " (name state)) state example]))

(deftest every-example-says-what-its-state-is-and-never-what-another-state-is
  (doseq [[label state {:keys [page says never]}] (all-examples)
          :let [tree (seen-by state (page))
                {generic-says :says generic-never :never} (get generic state)]]
    (testing label
      (is (seq (words tree)) "it says something")
      (doseq [text (concat generic-says says)]
        (is (says? tree text) (str "it says " (pr-str text))))
      (doseq [text (concat generic-never never)]
        (is (not (says? tree text)) (str "it does not say " (pr-str text)))))))

(deftest every-example-passes-the-checks-of-accessibility
  (doseq [[label _ {:keys [page]}] (all-examples)]
    (is (= [] (a11y/problems (the-document (page)) {:page? true})) label)))

(deftest the-states-of-one-route-do-not-look-alike
  (doseq [[route-name by-state] states/examples
          :let [seen (group-by (fn [[_ example]] (words ((:page example)))) by-state)]]
    (is (every? #(= 1 (count %)) (vals seen))
        (str route-name ": " (pr-str (map #(map first %) (filter #(< 1 (count %)) (vals seen))))))))

(deftest a-page-has-one-heading-whatever-state-it-is-in
  (doseq [[label _ {:keys [page]}] (all-examples)]
    (is (= 1 (count (hx/by-tag (page) :h1))) label)))

;; ---------------------------------------------------------------------------
;; The pages that are not a route

(deftest the-pages-outside-the-routes-say-what-they-must
  (doseq [[kind {:keys [page says never]}] states/outside
          :let [tree (page)]]
    (testing (str kind)
      (is (seq (words tree)))
      (doseq [text says] (is (says? (visible tree) text) (pr-str text)))
      (doseq [text never] (is (not (says? tree text)) (pr-str text)))
      (is (= [] (a11y/problems (the-document tree) {:page? true})))
      (is (= 1 (count (hx/by-tag tree :h1))) "one heading"))))

(deftest a-wait-that-does-not-end-says-so
  (let [slow (start/starting {:slow? true :handlers {:retry (fn [])}})]
    (is (some #(str/includes? (words %) "taking longer than usual") (hx/select slow #(= "status" (:role (hx/attributes %)))))
        "a screen reader is told, in the region that already said it was waiting")
    (is (says? slow "The sign-in service has not answered yet.")))
  (testing "and the person is not told it when it is not so"
    (is (not (says? (start/starting {:slow? false}) "taking longer")))
    (is (not (says? (start/starting) "taking longer"))))
  (testing "a button that calls something is there only when there is something to call"
    (is (not (says? (start/starting {:slow? true}) "Try again")))
    (is (not (says? (start/crashed {}) "Reload the page")))
    (is (not (says? (start/crashed {:handlers {:reload (fn [])}}) "Sign out")))
    (is (says? (start/crashed {:handlers {:reload (fn [])}}) "Reload the page"))))
