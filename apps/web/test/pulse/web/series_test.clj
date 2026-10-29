(ns pulse.web.series-test
  "A series of readings as the pages of the portal take it (ADR-0028): the ones that say something, oldest first, and always in the
  same order, whichever order the api gave them in."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.view.views :as views]))

(defn- r [id at value] {:id id :at at :value value})

(deftest a-series-is-the-readings-that-say-something-oldest-first
  (let [items [(r "c" "2026-10-13T10:00:00Z" 3)
               (r "a" "2026-10-13T08:00:00Z" 1)
               (r "b" "2026-10-13T09:00:00Z" 2)]]
    (is (= ["a" "b" "c"] (map :id (views/usable-series :heart-rate items))))
    (is (= ["a" "b" "c"] (map :id (views/usable-series :heart-rate (reverse items)))) "whatever order they came in"))
  (is (= [] (views/usable-series :heart-rate [])))
  (is (= [] (views/usable-series :heart-rate nil))))

(deftest a-series-leaves-out-a-reading-that-says-nothing
  (let [items [(r "good" "2026-10-13T08:00:00Z" 1)
               (r "no-number" "2026-10-13T09:00:00Z" nil)
               (r "text" "2026-10-13T09:30:00Z" "seventy")
               (r "no-time" nil 3)
               (r "bad-time" "tomorrow" 3)
               (r "date" "2026-10-13" 3)
               {:id "nothing"}]]
    (is (= ["good"] (map :id (views/usable-series :heart-rate items)))))
  (testing "a blood pressure needs both of its numbers"
    (let [bp (fn [id systolic diastolic] {:id id :at "2026-10-13T08:00:00Z" :systolic systolic :diastolic diastolic})]
      (is (= ["both"] (map :id (views/usable-series :blood-pressure [(bp "both" 120 80) (bp "one" 120 nil) (bp "other" nil 80) (bp "none" nil nil)]))))
      (is (= [] (views/usable-series :blood-pressure [(r "value" "2026-10-13T08:00:00Z" 120)]))))))

(deftest readings-of-one-moment-are-in-the-order-of-their-ids
  (let [same "2026-10-13T08:00:00Z"
        items [(r "b" same 2) (r "c" same 3) (r "a" same 1)]]
    (is (= ["a" "b" "c"] (map :id (views/usable-series :heart-rate items))))
    (is (= ["a" "b" "c"] (map :id (views/usable-series :heart-rate (reverse items)))))
    (testing "the time first, and the id only for what the time does not tell"
      (is (= ["z" "a"] (map :id (views/usable-series :heart-rate [(r "a" "2026-10-13T09:00:00Z" 2) (r "z" "2026-10-13T08:00:00Z" 1)])))))
    (testing "a reading with no id is still in its place"
      (is (= [nil "a"] (map :id (views/usable-series :heart-rate [(r "a" same 1) {:at same :value 2}])))))))

(defspec a-series-is-in-the-same-order-whichever-order-it-comes-in 200
  (prop/for-all [items (gen/bind (gen/vector (gen/tuple (gen/choose 0 5) (gen/elements ["a" "b" "c" "d"]) (gen/choose 1 200)) 1 12)
                                 (fn [triples]
                                   (gen/shuffle (map-indexed (fn [i [hour id value]]
                                                               (r (str id i) (str "2026-10-13T0" hour ":00:00Z") value))
                                                             triples))))]
    (let [sorted (views/usable-series :heart-rate items)]
      (and (= (map :id sorted) (map :id (views/usable-series :heart-rate (reverse items))))
           (= (count items) (count sorted))
           (= (map :at sorted) (sort (map :at sorted)))))))
