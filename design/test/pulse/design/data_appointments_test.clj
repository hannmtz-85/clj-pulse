(ns pulse.design.data-appointments-test
  "Details of the list of appointments of the mock-ups that a mutation analysis found nobody was holding (ADR-0026): the page of
  appointments that `GET /api/appointments` answers, for the appointments it is given and not only for those of the cohort."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.design.data :as data]))

(defn- appointment [id & {:as fields}]
  (merge {:id id :status "booked" :description id :start nil :end nil :requestedStart nil :requestedEnd nil
          :practitioner "Practitioner/pr-rivera" :reason nil :patient "Patient/pt-ana"}
         fields))

(def ^:private items
  [(appointment "late" :start "2026-10-20T08:00:00Z")
   (appointment "nothing")
   (appointment "soon" :start "2026-10-14T08:00:00Z")
   (appointment "ask" :status "proposed" :requestedStart "2026-10-16T00:00:00Z")])

(deftest the-appointments-are-ordered-by-the-time-they-are-or-are-asked-to-be-and-what-has-none-comes-last
  (is (= ["soon" "ask" "late" "nothing"] (map :id (:items (data/appointment-page items {})))))
  (is (= ["nothing" "late" "ask" "soon"] (map :id (:items (data/appointment-page items {:sort "-start"})))) "and the newest first is the other way round")
  (testing "whatever order they come in"
    (is (= (:items (data/appointment-page items {})) (:items (data/appointment-page (reverse items) {}))))))

(deftest two-appointments-of-one-moment-are-ordered-by-their-ids
  (let [same [(appointment "b" :start "2026-10-14T08:00:00Z") (appointment "a" :start "2026-10-14T08:00:00Z")]]
    (is (= ["a" "b"] (map :id (:items (data/appointment-page same {})))))
    (is (= ["b" "a"] (map :id (:items (data/appointment-page same {:sort "-start"})))))))

(deftest a-page-is-the-appointments-it-says-and-how-many-there-are-in-all
  (let [page (data/appointment-page items {:limit 2 :offset 1})]
    (is (= ["ask" "late"] (map :id (:items page))))
    (is (= {:total 4 :offset 1 :limit 2} (select-keys page [:total :offset :limit])))
    (is (= {:total 4 :offset 0 :limit 50} (select-keys (data/appointment-page items nil) [:total :offset :limit])) "by default, the first fifty")))

(deftest a-status-keeps-only-the-appointments-in-it
  (is (= ["ask"] (map :id (:items (data/appointment-page items {:status "proposed"})))))
  (is (= 3 (:total (data/appointment-page items {:status "booked"}))))
  (is (= [] (:items (data/appointment-page items {:status "cancelled"})))))
