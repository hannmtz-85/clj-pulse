(ns pulse.platform.http.health-test
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.platform.http.health :as health]
            [taoensso.telemere :as t]))

(deftest checks-are-up-or-down
  (is (= {:a :up :b :down :c :down}
         (:value (t/with-signals true
                   (health/run-checks {:a (constantly true)
                                       :b (constantly false)
                                       :c #(throw (ex-info "database unreachable" {}))}
                                      {}))))
      "truthy is up; falsey and exceptions are down"))

(deftest a-failing-check-is-logged-with-its-cause
  (let [signals (:signals (t/with-signals true
                            (health/run-checks {:db #(throw (ex-info "connection refused" {}))} {})))
        signal (first (filter #(= :pulse.health/check-failed (:id %)) signals))]
    (is (= :warn (:level signal)))
    (is (= "connection refused" (ex-message (:error signal))))))

(deftest a-check-that-hangs-is-down-and-does-not-hold-up-the-others
  (let [started (System/nanoTime)
        results (health/run-checks {:slow #(do (Thread/sleep 5000) true)
                                    :also-slow #(do (Thread/sleep 5000) true)
                                    :fast (constantly true)}
                                   {:timeout-ms 300})
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
    (is (= {:slow :down :also-slow :down :fast :up} results))
    (testing "one deadline for all of them, not one each"
      (is (< elapsed-ms 1500) (str "took " (long elapsed-ms) " ms")))))

(deftest the-response-says-ready-only-when-everything-is-up
  (is (= {:status 200 :body {:status "ready" :checks {"keycloak" "up" "fhir" "up"}}}
         (health/readiness-response {:keycloak :up :fhir :up})))
  (is (= {:status 503 :body {:status "unready" :checks {"keycloak" "up" "fhir" "down"}}}
         (health/readiness-response {:keycloak :up :fhir :down})))
  (is (= 200 (:status (health/readiness-response {}))) "nothing to check is nothing wrong"))
