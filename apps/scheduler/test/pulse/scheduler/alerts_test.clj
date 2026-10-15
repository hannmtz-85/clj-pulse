(ns pulse.scheduler.alerts-test
  "The alerts of the rules engine as FHIR Flags: what one looks like, how an active one is told from
  the rest, and raising one once."
  (:require [clojure.test :refer [deftest is testing]]
            [pulse.fhir.alert :as alert]
            [pulse.fhir.bundle :as bundle]
            [pulse.fhir.client :as c]
            [pulse.fhir.client.memory :as mem]
            [pulse.fhir.schema :as schema]
            [pulse.scheduler.alerts :as alerts]
            [pulse.scheduler.rules :as rules])
  (:import (java.time Instant)))

(def ^:private newest (Instant/parse "2026-10-13T08:00:00Z"))
(def ^:private raised-at (Instant/parse "2026-10-13T08:05:00Z"))

(deftest the-key-of-an-alert-is-its-rule-its-patient-and-the-time
  (is (= "R1/pt-ana/2026-10-13T08:00:00Z" (alerts/alert-key rules/bp-stage-2 "pt-ana" newest)))
  (is (= "R4/pt-maya/2026-10-13T08:00:00Z" (alerts/alert-key rules/rapid-weight-gain "pt-maya" newest))))

(deftest a-flag-is-an-active-alert-with-no-id
  (let [flag (alerts/flag rules/bp-stage-2 "pt-ana" newest raised-at)]
    (is (not (contains? flag :id)) "the server names it")
    (is (= "Flag" (:resourceType flag)))
    (is (= "active" (:status flag)))
    (is (= "Patient/pt-ana" (get-in flag [:subject :reference])))
    (is (= {:start "2026-10-13T08:05:00Z"} (:period flag)))
    (is (= {:text "BP stage 2"
            :coding [{:system alerts/rule-system :code "R1" :display "BP stage 2"}]}
           (:code flag)))
    (is (= [{:system alerts/identifier-system :value "R1/pt-ana/2026-10-13T08:00:00Z"}] (:identifier flag)))
    (is (alert/active? flag))
    (testing "valid once the server has given it an id"
      (is (schema/valid? (assoc flag :id "f-1"))))))

(defn- flag-with [id status & {:keys [rule patient newest-at system] :or {rule rules/bp-stage-2 patient "pt-ana" newest-at newest}}]
  (cond-> (assoc (alerts/flag rule patient newest-at raised-at) :id id :status status)
    system (assoc-in [:identifier 0 :system] system)))

(deftest an-active-alert-is-one-of-the-rule-for-the-patient-that-is-active
  (let [active (flag-with "f-1" "active")]
    (is (alerts/active? [active] rules/bp-stage-2 "pt-ana"))
    (testing "not an acknowledged one, nor one that was entered in error"
      (is (not (alerts/active? [(flag-with "f-2" "inactive")] rules/bp-stage-2 "pt-ana")))
      (is (not (alerts/active? [(flag-with "f-3" "entered-in-error")] rules/bp-stage-2 "pt-ana"))))
    (testing "not another rule's"
      (is (not (alerts/active? [active] rules/low-spo2 "pt-ana"))))
    (testing "not another patient's"
      (is (not (alerts/active? [active] rules/bp-stage-2 "pt-liam"))))
    (testing "and not that of a patient whose id starts with this one's"
      (is (not (alerts/active? [(flag-with "f-4" "active" :patient "pt-ana-2")] rules/bp-stage-2 "pt-ana")))
      (is (not (alerts/active? [active] rules/bp-stage-2 "pt-ana-2"))))
    (testing "not an identifier of another system"
      (is (not (alerts/active? [(flag-with "f-5" "active" :system "https://other.example/id")] rules/bp-stage-2 "pt-ana"))))
    (testing "whatever readings it was raised for, and among others"
      (is (alerts/active? [(flag-with "f-6" "inactive")
                           (flag-with "f-7" "active" :newest-at (Instant/parse "2026-10-01T08:00:00Z"))
                           (flag-with "f-8" "inactive" :rule rules/low-spo2)]
                          rules/bp-stage-2 "pt-ana")))
    (testing "a flag with no identifier is not ours"
      (is (not (alerts/active? [(dissoc active :identifier)] rules/bp-stage-2 "pt-ana"))))
    (testing "nor one whose identifier merely holds the key of ours"
      (is (not (alerts/active? [(assoc-in active [:identifier 0 :value] "X-R1/pt-ana/2026-10-13T08:00:00Z")]
                               rules/bp-stage-2 "pt-ana"))))
    (is (false? (alerts/active? [] rules/bp-stage-2 "pt-ana")))))

(deftest alerts-of-a-patient-are-the-newest-first
  (let [client (mem/memory-client
                [(assoc (alerts/flag rules/bp-stage-2 "pt-ana" newest (Instant/parse "2026-10-10T08:00:00Z")) :id "f-old")
                 (assoc (alerts/flag rules/low-spo2 "pt-ana" newest (Instant/parse "2026-10-13T08:00:00Z")) :id "f-new")
                 (assoc (alerts/flag rules/bp-stage-2 "pt-liam" newest (Instant/parse "2026-10-12T08:00:00Z")) :id "f-other")])]
    (is (= ["f-new" "f-old"] (mapv :id (alerts/flags-of client "pt-ana"))))
    (is (= ["f-new"] (mapv :id (alerts/flags-of client "pt-ana" 1))) "at most the limit")
    (is (= [] (alerts/flags-of client "pt-noah")))))

(deftest raising-an-alert-creates-it-once
  (let [client (mem/memory-client)
        flag (alerts/flag rules/bp-stage-2 "pt-ana" newest raised-at)
        first-time (alerts/raise! client flag)
        again (alerts/raise! client flag)]
    (is (= :created (:outcome first-time)))
    (is (= :existing (:outcome again)))
    (is (string? (:id first-time)))
    (is (= (:id first-time) (:id again)) "the second answer is the alert of the first")
    (is (= [(:id first-time)] (mapv :id (c/search client "Flag" {}))))
    (is (= flag (dissoc (c/read-resource client "Flag" (:id first-time)) :id)) "what was stored is what was sent")))

(deftest raising-an-alert-leaves-an-acknowledged-one-alone
  (let [client (mem/memory-client)
        flag (alerts/flag rules/bp-stage-2 "pt-ana" newest raised-at)
        {:keys [id]} (alerts/raise! client flag)
        acknowledged (:ok (alert/acknowledge (c/read-resource client "Flag" id) "2026-10-13T09:00:00Z"))]
    (c/put! client acknowledged)
    (is (= {:outcome :existing :id id} (alerts/raise! client flag)))
    (is (= [acknowledged] (vec (c/search client "Flag" {}))))))

(deftest the-condition-of-a-create-is-the-identifier-with-its-system
  ;; what the server is asked, as a search: the identifier with its system, encoded
  (let [sent (atom nil)
        client (reify c/FhirClient
                 (read-resource [_ _ _] nil)
                 (search [_ _ _] [])
                 (put! [_ r] r)
                 (delete! [_ _ _] nil)
                 (transact! [_ tx] (reset! sent tx) {:entry [{:response {:status "201 Created" :location "Flag/9/_history/1"}}]})
                 (validate [_ _] nil))
        flag (alerts/flag rules/bp-stage-2 "pt-ana" newest raised-at)]
    (is (= {:outcome :created :id "9"} (alerts/raise! client flag)))
    (let [[entry :as entries] (:entry @sent)]
      (is (= 1 (count entries)))
      (is (= "transaction" (:type @sent)))
      (is (= "POST" (get-in entry [:request :method])))
      (is (= "Flag" (get-in entry [:request :url])))
      (is (= (str "identifier=" "https%3A%2F%2Fpulse.example%2Fidentifier%2Falert%7CR1%2Fpt-ana%2F2026-10-13T08%3A00%3A00Z")
             (get-in entry [:request :ifNoneExist])))
      (is (= flag (:resource entry))))
    (is (schema/valid? @sent))
    (is (bundle/all-succeeded? {:entry [{:response {:status "201 Created"}}]}))))
