(ns pulse.fhir.alert-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pulse.fhir.alert :as alert]
            [pulse.fhir.resource :as r]
            [pulse.fhir.schema :as schema]
            [pulse.fhir.time :as time]))

(def ^:private now "2026-10-14T12:00:00Z")

(def ^:private raised
  (r/flag {:id "flag-1" :patient "pt-ana" :text "BP stage 2" :start "2026-10-13T20:00:00Z"
           :identifier {:system "https://pulse.example/identifier/alert" :value "R1-pt-ana"}}))

(deftest only-an-active-alert-is-to-be-acknowledged
  (is (alert/active? raised))
  (doseq [status ["inactive" "entered-in-error" "unknown" nil 5]]
    (is (not (alert/active? (assoc raised :status status))) (pr-str status))))

(deftest acknowledging-makes-an-alert-inactive-and-ends-its-period
  (let [result (alert/acknowledge raised now)
        ok (:ok result)]
    (is (= #{:ok} (set (keys result))))
    (is (= "inactive" (:status ok)))
    (is (= {:start "2026-10-13T20:00:00Z" :end now} (:period ok)))
    (testing "nothing else changes"
      (is (= (dissoc raised :status :period) (dissoc ok :status :period))))
    (is (schema/valid? ok))
    (is (= "active" (:status raised)) "the alert given is a value, and is unchanged")))

(deftest an-alert-without-a-period-gets-one-that-ends-when-it-was-acknowledged
  (let [ok (:ok (alert/acknowledge (dissoc raised :period) now))]
    (is (= {:end now} (:period ok)))
    (is (schema/valid? ok))))

(deftest a-period-does-not-end-before-it-began
  (testing "an alert that claims to start after the clock says it is: it ends where it starts"
    (let [ok (:ok (alert/acknowledge (assoc-in raised [:period :start] "2026-10-15T08:00:00Z") now))]
      (is (= {:start "2026-10-15T08:00:00Z" :end "2026-10-15T08:00:00Z"} (:period ok)))))
  (testing "a start that is a date, as FHIR allows"
    (is (= now (get-in (alert/acknowledge (assoc-in raised [:period :start] "2026-10-13") now) [:ok :period :end])))))

(deftest what-is-not-active-cannot-be-acknowledged-and-the-message-says-what-it-is
  (is (= {:conflict "Only an active alert can be acknowledged; this one is inactive."}
         (alert/acknowledge (assoc raised :status "inactive") now)))
  (is (= {:conflict "Only an active alert can be acknowledged; this one is entered-in-error."}
         (alert/acknowledge (assoc raised :status "entered-in-error") now)))
  (is (= {:conflict "Only an active alert can be acknowledged; this one is of no known status."}
         (alert/acknowledge (dissoc raised :status) now)))
  (testing "so an alert is acknowledged once"
    (is (contains? (alert/acknowledge (:ok (alert/acknowledge raised now)) now) :conflict))))

(def ^:private gen-junk
  (gen/one-of [(gen/return nil) gen/small-integer gen/string-alphanumeric gen/boolean
               (gen/elements ["2026-10-13T20:00:00Z" "2026-10-13" "T" "" "2026-10-13T23:59:60Z" "+999999999-01-01T00:00:00Z"])]))

(defspec acknowledging-answers-whatever-it-is-given-and-never-throws 400
  (prop/for-all [status (gen/elements ["active" "inactive" "entered-in-error" nil "x" 5])
                 start gen-junk
                 end gen-junk
                 now gen-junk]
    (let [result (alert/acknowledge {:status status :period {:start start :end end}} now)]
      (and (= 1 (count result))
           (contains? #{:ok :conflict} (key (first result)))))))

(defspec an-acknowledged-alert-is-valid-never-ends-before-it-began-and-cannot-be-acknowledged-again 300
  (prop/for-all [start-minutes (gen/choose (- (* 60 24 30)) (* 60 24 3))
                 acknowledged-minutes (gen/choose 0 (* 60 24 5))]
    (let [iso (fn [minutes] #?(:clj (str (java.time.Instant/ofEpochMilli (+ (time/->millis now) (* minutes 60000))))
                               :cljs (.toISOString (js/Date. (+ (time/->millis now) (* minutes 60000))))))
          flag (r/flag {:id "flag-x" :patient "pt-ana" :text "BP" :start (iso start-minutes)})
          result (alert/acknowledge flag (iso acknowledged-minutes))
          ok (:ok result)]
      (and (some? ok)
           (schema/valid? ok)
           (<= (time/->millis (get-in ok [:period :start])) (time/->millis (get-in ok [:period :end])))
           (contains? (alert/acknowledge ok (iso acknowledged-minutes)) :conflict)))))
