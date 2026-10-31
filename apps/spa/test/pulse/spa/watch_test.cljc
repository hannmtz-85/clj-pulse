(ns pulse.spa.watch-test
  "The beat of the workspace (ADR-0029, point 6), as far as it can be decided without a browser: the question it asks the api, how the
  answer is taken, and what the navigation shows of it. Pure, so these run on the JVM and on Node."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.spa.watch :as watch]))

(deftest the-beat-is-every-thirty-seconds
  (is (= 30000 watch/beat-ms) "the pace at which the rules run"))

(deftest the-question-is-how-many-alerts-need-a-decision
  (is (= {:method :get
          :path ["api" "alerts"]
          :query {:status "active" :limit 1}
          :id [:watch :count]
          :on-success [:pulse.spa.watch/counted 3]
          :on-failure [:pulse.spa.watch/count-failed 3]}
         (watch/count-request 3)))
  (testing "of one page of one alert, for the total is all that is read"
    (is (= 1 (get-in (watch/count-request 1) [:query :limit])))))

(deftest the-run-of-the-beat-is-in-the-events-that-hear-the-answer
  (is (= [:pulse.spa.watch/counted 7] (:on-success (watch/count-request 7))))
  (is (= [:pulse.spa.watch/count-failed 7] (:on-failure (watch/count-request 7)))))

(deftest the-total-of-the-answer-is-the-count
  (is (= {:state :loaded :total 3} (watch/counted {:total 3 :offset 0 :limit 1 :items [{:id "a"}]})))
  (is (= {:state :loaded :total 0} (watch/counted {:total 0 :items []})) "none is a count too")
  (is (= {:state :loaded :total 1200} (watch/counted {:total 1200}))))

(deftest an-answer-that-is-not-what-the-api-answers-is-not-a-count
  (doseq [body [nil "text" [] {} {:total nil} {:total "3"} {:total 2.5} {:total -1} {:items []} 5 true]]
    (is (= {:state :failed :kind :unexpected} (watch/counted body)) (pr-str body))))

(deftest the-navigation-shows-the-count-a-question-mark-or-nothing
  (is (= 3 (watch/nav-count {:count {:state :loaded :total 3}})))
  (is (= 0 (watch/nav-count {:count {:state :loaded :total 0}})) "zero is a number, and the navigation shows no badge for it")
  (is (= :unknown (watch/nav-count {:count {:state :failed :kind :timeout}})) "a count that could not be read is not zero")
  (testing "and nothing before the first answer"
    (is (nil? (watch/nav-count nil)))
    (is (nil? (watch/nav-count {})))
    (is (nil? (watch/nav-count {:epoch 1})))
    (is (nil? (watch/nav-count {:count {:state :loading}})))))
