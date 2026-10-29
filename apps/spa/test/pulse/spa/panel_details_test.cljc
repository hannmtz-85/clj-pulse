(ns pulse.spa.panel-details-test
  "What the mutation analysis of the panel found that `panel-test` and `panel-model-test` did not pin (Phase 8, step 5): how two patients
  who are equally urgent and have the same family name are told apart, what a panel is waiting for before it has settled, and that a list
  that has no patients says so without waiting for the rest. These run on the JVM and on Node."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.pages.panel :as page]
            [pulse.spa.panel :as panel]
            [pulse.spa.test.panel-fixtures :as fx]
            [pulse.ui.test.hiccup :as hx]))

(defn- patient [id family given]
  {:id id :name (str given " " family) :family family :given [given]})

(defn- panel-of
  "A panel of patients who all have the same normal readings, so that nothing but their names tells them apart."
  [& patients]
  {:now fx/now
   :list (fx/loaded {:total (count patients) :offset 0 :limit 100 :items (vec patients)})
   :rows (into {} (map (fn [p] [(:id p) {:summary (fx/loaded (get fx/summaries "pt-omar"))}])) patients)
   :inbox {}
   :queue []
   :inflight #{}})

(deftest two-patients-equally-urgent-with-one-family-name-are-told-apart-by-the-given-name-and-then-by-the-id
  (testing "by the given name, whatever the case and the accents (the ids go the other way, so that it is not they that say)"
    (is (= ["z" "y"] (panel/urgency-order (panel-of (patient "y" "Lopez" "María") (patient "z" "Lopez" "ana")))))
    (is (= ["z" "y"] (panel/urgency-order (panel-of (patient "z" "Lopez" "ana") (patient "y" "Lopez" "María"))))))
  (testing "and when the given names are the same too, by the id"
    (is (= ["a" "b"] (panel/urgency-order (panel-of (patient "b" "Lopez" "Ana") (patient "a" "Lopez" "Ana")))))
    (is (= ["a" "b"] (panel/urgency-order (panel-of (patient "a" "Lopez" "Ana") (patient "b" "Lopez" "Ana")))))))

(deftest a-panel-is-not-settled-while-any-part-is-on-its-way
  (let [done (fx/settled)]
    (is (panel/settled? done))
    (is (not (panel/settled? (assoc done :list fx/loading))) "the list is not back, though every other part is")
    (is (not (panel/settled? (assoc-in done [:rows "pt-ana" :summary] fx/loading))) "a summary is on its way")
    (is (not (panel/settled? (assoc-in done [:rows "pt-ana" :series] fx/loading))) "a series is on its way, though its summary is back")
    (is (not (panel/settled? (assoc-in done [:inbox :requests] fx/loading))) "the requests are on their way")
    (is (not (panel/settled? (assoc-in done [:inbox :acknowledged] fx/loading))))
    (testing "a part that failed is not on its way"
      (is (panel/settled? (assoc done :list fx/failed)))
      (is (panel/settled? (assoc-in done [:rows "pt-ana" :series] fx/failed))))))

(deftest a-list-with-no-patients-says-so-before-the-alerts-and-the-requests-are-back
  (let [no-patients (assoc (panel/start fx/now) :list (fx/loaded {:total 0 :offset 0 :limit 100 :items []}))
        m (panel/model no-patients {} false)]
    (is (not (panel/settled? no-patients)) "the alerts and the requests are still on their way")
    (is (= :no-patients (:empty m)) "and what there is to say about the patients is known")
    (is (false? (:settled? m)))))

(deftest a-patient-with-no-reading-is-in-no-filter-but-all
  (let [m (fn [show] (mapv :id (:rows (panel/model (fx/settled) {:show show} false))))]
    (is (not-any? #{"pt-noah"} (concat (m :alerts) (m :away) (m :normal))))
    (is (some #{"pt-noah"} (m :all)))))

(defn- notice-titles
  "The titles of the messages of the page: what stands where something could not be loaded. The live region says the same words, so the
  messages are looked at by themselves."
  [tree]
  (for [message (hx/select tree #(and (hx/has-class? % "rounded-box") (hx/has-class? % "grid-cols-[auto_1fr]")))]
    (hx/words (first (hx/select message #(and (= :p (hx/tag %)) (hx/has-class? % "font-semibold")))))))

(defn- the-page [state & [options]]
  (page/content state {:show :all :q ""} (merge {:links {:panel "/"} :handlers {:retry (fn [])}} options)))

(deftest the-title-of-a-message-is-in-the-message-and-not-only-in-what-the-screen-reader-is-told
  (let [lost-list (assoc (fx/settled) :list fx/failed :rows {} :order [])
        lost-rows (fn [& ids] (reduce #(assoc-in %1 [:rows %2 :summary] fx/failed) (fx/settled) ids))]
    (is (= ["Your panel could not be loaded"] (notice-titles (the-page lost-list))))
    (is (= ["The patients could not be loaded"] (notice-titles (the-page lost-list {:admin? true}))) "the administrator's is not a panel")
    (is (= ["1 patient could not be loaded"] (notice-titles (the-page (lost-rows "pt-liam")))))
    (is (= ["2 patients could not be loaded"] (notice-titles (the-page (lost-rows "pt-liam" "pt-noah")))))
    (is (= ["Some figures could not be loaded"] (notice-titles (the-page (assoc-in (fx/settled) [:inbox :active] fx/failed)))))
    (is (= [] (notice-titles (the-page (fx/settled)))) "and nothing is lost when nothing is")
    (testing "each says what is up to date, except the list, which is all there was"
      (is (not-any? #(re-find #"The rest of the panel is up to date" %) (map hx/words (hx/select (the-page lost-list) #(hx/has-class? % "rounded-box")))))
      (is (some #(re-find #"The rest of the panel is up to date" %) (map hx/words (hx/select (the-page (lost-rows "pt-liam")) #(hx/has-class? % "rounded-box"))))))))

(deftest a-patient-with-no-number-and-no-age-has-no-line-under-the-name
  (let [state (-> (fx/settled)
                  (update-in [:list :data :items 1] dissoc :identifier :birthDate))
        tree (the-page state)
        cell (fn [n] (first (hx/select (nth (hx/by-tag (first (hx/by-tag (first (hx/by-tag tree :table)) :tbody)) :tr) n) #(= :th (hx/tag %)))))
        lines (fn [n] (hx/select (cell n) #(and (= :span (hx/tag %)) (hx/has-class? % "text-muted"))))]
    (is (= ["Ana García"] [(hx/words (first (hx/select (cell 0) #(and (= :span (hx/tag %)) (hx/has-class? % "block") (hx/has-class? % "font-medium")))))]))
    (is (= [] (lines 0)) "nothing under the name to say: no empty line")
    (is (= 1 (count (lines 1))) "the others have theirs")))
