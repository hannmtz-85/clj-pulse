(ns pulse.view.views-appointments-test
  "Details of the list of appointments that a mutation analysis found nobody was holding (ADR-0028): which time an appointment is ordered by
  when it has two, and where what has no time that can be read comes in each list."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.view.views :as views]))

(def ^:private now "2026-10-13T12:00:00Z")

(def ^:private doctors {"Practitioner/pr-rivera" "Dr. Elena Rivera"})

(defn- appointment [& {:as fields}]
  (merge {:id "a1" :status "booked" :description "Hypertension follow-up" :start nil :end nil
          :requestedStart nil :requestedEnd nil :practitioner "Practitioner/pr-rivera" :reason nil}
         fields))

(defn- ids [rows] (mapv :id rows))

(deftest an-appointment-that-has-a-time-is-ordered-by-it-and-not-by-the-days-that-were-asked-for
  (let [booked (appointment :id "booked" :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z"
                            :requestedStart "2026-10-14T00:00:00Z" :requestedEnd "2026-10-20T00:00:00Z")
        request (appointment :id "request" :status "proposed" :requestedStart "2026-10-16T00:00:00Z" :requestedEnd "2026-10-23T00:00:00Z")]
    (testing "by its time, which is the 20th, so after the request that asks for the 16th"
      (is (= ["request" "booked"] (ids (:coming (views/appointment-sections [booked request] doctors now)))))
      (is (= ["request" "booked"] (ids (:coming (views/appointment-sections [request booked] doctors now))))))
    (testing "and the same for what began, the newest first"
      (let [late (appointment :id "late" :start "2026-10-09T08:00:00Z" :end "2026-10-09T08:30:00Z" :requestedStart "2026-10-01T00:00:00Z")
            early (appointment :id "early" :start "2026-10-02T08:00:00Z" :end "2026-10-02T08:30:00Z" :requestedStart "2026-10-08T00:00:00Z")]
        (is (= ["late" "early"] (ids (:earlier (views/appointment-sections [early late] doctors now)))))))))

(deftest what-has-no-time-that-can-be-read-comes-last-whichever-way-the-list-is-ordered
  (let [timed (fn [id start] (appointment :id id :status "cancelled" :start start :end start))
        none (appointment :id "none" :status "cancelled")
        odd (appointment :id "odd" :status "cancelled" :start "soon" :requestedStart "next week")]
    (testing "of what was cancelled, which is the newest first"
      (is (= ["new" "old" "odd" "none"]
             (ids (:cancelled (views/appointment-sections [none (timed "old" "2026-09-01T08:00:00Z") odd (timed "new" "2026-10-01T08:00:00Z")] doctors now))))
          "the ones with no time that can be read come after the oldest, and between themselves the other way round of their ids, as the rest of the newest first"))
    (testing "of what is coming, which is the soonest first"
      (let [soon (appointment :id "soon" :start "2026-10-14T08:00:00Z" :end "2026-10-14T08:30:00Z")
            late (appointment :id "late" :start "2026-10-20T08:00:00Z" :end "2026-10-20T08:30:00Z")
            nothing (appointment :id "nothing" :status "pending")]
        (is (= ["soon" "late" "nothing"] (ids (:coming (views/appointment-sections [nothing late soon] doctors now)))))))))
